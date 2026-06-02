package br.com.rgbrainlabs.scadufaxthoth;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import br.com.rgbrainlabs.scadufaxthoth.prep.V2ArtifactBuilder;
import br.com.rgbrainlabs.scadufaxthoth.search.EuclideanDistanceCalculator;
import br.com.rgbrainlabs.scadufaxthoth.search.V2IndexSearcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guarda de qualidade da V2: compara IVF contra referência exata e falha
 * quando a divergência de decisão ultrapassa os limites definidos.
 *
 * Três caminhos comparados por query:
 *   1. Float32 brute-force (ground truth)
 *   2. V2 full-scan  nprobe=NUM_CLUSTERS  → isola perda de quantização
 *   3. V2 IVF        nprobe=TEST_NPROBE   → isola perda de particionamento
 *
 * Fixture: 60 vetores em 3 grupos separados na dimensão 0:
 *   Grupo A (20 legítimos) : dim0 em [0.00, 0.19]
 *   Grupo B (10 leg+10 fra): dim0 em [0.40, 0.59]
 *   Grupo C (20 fraudes)   : dim0 em [0.80, 0.99]
 *
 * Com K=6 clusters, nprobe=2 visita 1/3 dos clusters — stress test deliberado.
 * Com nprobe=6 (full scan) o resultado é equivalente ao brute-force int8.
 */
class V2QualityGuardTest {

    private static final int    K_NEIGHBORS         = 5;
    private static final double FRAUD_THRESHOLD     = 0.6;
    private static final int    NUM_CLUSTERS        = 6;
    private static final int    TEST_NPROBE         = 2;

    /** Acordo de decisão mínimo entre full-scan (int8) e float32 BF. */
    private static final double MIN_AGREEMENT_QUANTIZATION = 0.95;

    /** Acordo de decisão mínimo entre IVF (nprobe=TEST_NPROBE) e float32 BF. */
    private static final double MIN_AGREEMENT_IVF          = 0.80;

    @Test
    void guardaQualidade_preservaDecisoesDeFragude(@TempDir Path tmpDir) throws Exception {
        List<FixtureRecord> records = buildFixtureRecords();
        Path gz       = tmpDir.resolve("fixture.json.gz");
        Path artifact = tmpDir.resolve("index.v2");
        writeGz(gz, toJson(records));
        V2ArtifactBuilder.build(gz, artifact, NUM_CLUSTERS, 10, 0L);

        List<float[]> queries = buildQueries();
        int n = queries.size();

        int agreementQuantization = 0;
        int agreementIvf          = 0;
        int divQuantization       = 0;
        int divPartitioning       = 0;

        try (V2IndexSearcher fullScan = new V2IndexSearcher(
                     artifact, new EuclideanDistanceCalculator(), NUM_CLUSTERS);
             V2IndexSearcher ivf      = new V2IndexSearcher(
                     artifact, new EuclideanDistanceCalculator(), TEST_NPROBE)) {

            for (float[] q : queries) {
                boolean truthDecision    = bruteForceDecision(records, q);
                boolean fullScanDecision = decision(fullScan.search(q, K_NEIGHBORS));
                boolean ivfDecision      = decision(ivf.search(q, K_NEIGHBORS));

                if (truthDecision == fullScanDecision) {
                    agreementQuantization++;
                } else {
                    divQuantization++;
                }

                if (truthDecision == ivfDecision) {
                    agreementIvf++;
                } else if (truthDecision == fullScanDecision) {
                    // full-scan concordou com truth mas ivf divergiu → particionamento
                    divPartitioning++;
                }
                // se full-scan também divergiu de truth, o culpado é a quantização,
                // já contado em divQuantization acima
            }
        }

        double rateQ   = (double) agreementQuantization / n;
        double rateIvf = (double) agreementIvf / n;

        System.out.printf("%n=== Guarda de Qualidade V2 ===%n");
        System.out.printf("Queries analisadas   : %d%n", n);
        System.out.printf("Clusters / nprobe    : %d / %d%n", NUM_CLUSTERS, TEST_NPROBE);
        System.out.printf("Acordo quantização   : %.1f%%  (full-scan vs float32, mín %.0f%%)%n",
                rateQ * 100, MIN_AGREEMENT_QUANTIZATION * 100);
        System.out.printf("Acordo IVF           : %.1f%%  (nprobe=%d vs float32, mín %.0f%%)%n",
                rateIvf * 100, TEST_NPROBE, MIN_AGREEMENT_IVF * 100);
        System.out.printf("Divergências         : quantização=%d  particionamento=%d%n",
                divQuantization, divPartitioning);

        assertTrue(rateQ >= MIN_AGREEMENT_QUANTIZATION,
                String.format(
                        "Quantização degradou demais: %.1f%% < %.0f%% mínimo — "
                      + "verificar encodeI8 e domínio do vetor",
                        rateQ * 100, MIN_AGREEMENT_QUANTIZATION * 100));

        assertTrue(rateIvf >= MIN_AGREEMENT_IVF,
                String.format(
                        "IVF degradou demais: %.1f%% < %.0f%% mínimo (nprobe=%d, K=%d) — "
                      + "aumentar nprobe ou revisar K",
                        rateIvf * 100, MIN_AGREEMENT_IVF * 100, TEST_NPROBE, NUM_CLUSTERS));
    }

    // ── Fixture ──────────────────────────────────────────────────────────────────

    private record FixtureRecord(float[] vector, boolean fraud) {}

    private static List<FixtureRecord> buildFixtureRecords() {
        List<FixtureRecord> list = new ArrayList<>();
        // Grupo A — 20 legítimos, dim0 em [0.00, 0.19]
        for (int i = 0; i < 20; i++) list.add(entry(i * 0.01f, false));
        // Grupo B — 10 legítimos em [0.40, 0.49] + 10 fraudes em [0.50, 0.59]
        for (int i = 0; i < 10; i++) list.add(entry(0.40f + i * 0.01f, false));
        for (int i = 0; i < 10; i++) list.add(entry(0.50f + i * 0.01f, true));
        // Grupo C — 20 fraudes, dim0 em [0.80, 0.99]
        for (int i = 0; i < 20; i++) list.add(entry(0.80f + i * 0.01f, true));
        return list;
    }

    private static FixtureRecord entry(float dim0, boolean fraud) {
        float[] v = new float[V2ArtifactBuilder.DIMS];
        v[0] = dim0;
        return new FixtureRecord(v, fraud);
    }

    /**
     * Queries cobrindo zonas claras, fronteiras e região mista do grupo B.
     * As queries de fronteira (0.30, 0.35) e borda do grupo B (0.42, 0.57)
     * são as mais propensas a expor divergência quando nprobe é baixo.
     */
    private static List<float[]> buildQueries() {
        List<float[]> qs = new ArrayList<>();
        for (float d : new float[]{0.05f, 0.10f, 0.15f}) qs.add(queryAt(d)); // claramente legítimo
        for (float d : new float[]{0.30f, 0.35f})         qs.add(queryAt(d)); // fronteira A/B
        for (float d : new float[]{0.42f, 0.50f, 0.57f})  qs.add(queryAt(d)); // grupo B (misto)
        for (float d : new float[]{0.85f, 0.90f, 0.95f})  qs.add(queryAt(d)); // claramente fraude
        return qs;
    }

    private static float[] queryAt(float dim0) {
        float[] q = new float[V2ArtifactBuilder.DIMS];
        q[0] = dim0;
        return q;
    }

    // ── Float32 brute-force (ground truth) ───────────────────────────────────────

    private boolean bruteForceDecision(List<FixtureRecord> records, float[] query) {
        record Entry(double dist, boolean fraud) {}
        PriorityQueue<Entry> pq = new PriorityQueue<>(K_NEIGHBORS,
                (a, b) -> Double.compare(b.dist(), a.dist())); // max-heap

        for (FixtureRecord r : records) {
            double dist = euclidean(query, r.vector());
            if (pq.size() < K_NEIGHBORS) {
                pq.offer(new Entry(dist, r.fraud()));
            } else if (dist < pq.peek().dist()) {
                pq.poll();
                pq.offer(new Entry(dist, r.fraud()));
            }
        }

        long fraudCount = pq.stream().filter(Entry::fraud).count();
        // Replica exatamente a fórmula do SearchHandler: fraudScore = fraudCount / kNeighbors
        return (double) fraudCount / K_NEIGHBORS < FRAUD_THRESHOLD; // true == approved
    }

    private static double euclidean(float[] a, float[] b) {
        double sum = 0;
        for (int d = 0; d < a.length; d++) {
            double diff = a[d] - b[d];
            sum += diff * diff;
        }
        return sum;
    }

    // ── Decisão a partir de resultados do V2IndexSearcher ────────────────────────

    private boolean decision(List<SearchResult> results) {
        long fraudCount = results.stream().filter(r -> "fraud".equals(r.label())).count();
        return (double) fraudCount / K_NEIGHBORS < FRAUD_THRESHOLD; // true == approved
    }

    // ── Serialização do fixture ───────────────────────────────────────────────────

    private static String toJson(List<FixtureRecord> records) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) sb.append(",");
            FixtureRecord r = records.get(i);
            sb.append("{\"label\":\"").append(r.fraud() ? "fraud" : "legitimate")
              .append("\",\"vector\":[");
            for (int d = 0; d < r.vector().length; d++) {
                if (d > 0) sb.append(",");
                sb.append(r.vector()[d]);
            }
            sb.append("]}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static void writeGz(Path dest, String json) throws Exception {
        try (OutputStream os = new GZIPOutputStream(
                new BufferedOutputStream(Files.newOutputStream(dest)))) {
            os.write(json.getBytes());
        }
    }
}
