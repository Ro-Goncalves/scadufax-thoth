package br.com.rgbrainlabs.scadufaxthoth.benchmark;

import br.com.rgbrainlabs.scadufaxthoth.config.AppConfig;
import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionRequest;
import br.com.rgbrainlabs.scadufaxthoth.search.DistanceCalculator;
import br.com.rgbrainlabs.scadufaxthoth.search.EuclideanDistanceCalculator;
import br.com.rgbrainlabs.scadufaxthoth.search.MmapBruteForceSearcher;
import br.com.rgbrainlabs.scadufaxthoth.search.QuantizedBruteForceSearcher;
import br.com.rgbrainlabs.scadufaxthoth.search.TransactionVectorizer;
import br.com.rgbrainlabs.scadufaxthoth.search.VectorSearcher;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Compara a qualidade do ranking entre float32, int8 e int16 sobre o mesmo
 * corpus de queries, reportando recall@5 e divergência de decisão.
 *
 * Critério de aprovação (alinhado ao Issue 00):
 *   recall@5 ≥ 99 % AND divergência ≤ 1 % → representação aprovada
 *
 * Variáveis de ambiente:
 *   DATASET_PATH     — dataset.bin float32 (default: dataset.bin)
 *   DATA_DIR         — diretório com arquivos quantizados (default: ./data)
 *   TEST_DATA_PATH   — corpus de queries      (default: test/test-data.json)
 *   BENCHMARK_SAMPLE — máximo de queries       (default: 500)
 */
public final class QuantizationBenchmark {

    private static final int    K                  = 5;
    private static final double APPROVED_THRESHOLD = 0.6;

    record Entry(
            TransactionRequest request,
            @JsonProperty("expected_approved")   boolean expectedApproved,
            @JsonProperty("expected_fraud_score") int expectedFraudScore) {}

    record TestData(List<Entry> entries) {}

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.fromEnvironment();
        String testDataPath = System.getenv().getOrDefault("TEST_DATA_PATH", "test/test-data.json");
        int sampleSize = Integer.parseInt(System.getenv().getOrDefault("BENCHMARK_SAMPLE", "500"));

        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        System.out.println("[benchmark] carregando test-data.json de " + testDataPath);
        TestData td = mapper.readValue(Path.of(testDataPath).toFile(), TestData.class);
        List<Entry> entries = td.entries().size() > sampleSize
                ? td.entries().subList(0, sampleSize)
                : td.entries();
        System.out.printf("[benchmark] %d queries de amostra%n", entries.size());

        TransactionVectorizer vectorizer = new TransactionVectorizer(
                config.normalizationMap(), config.mccRiskMap());
        Path dataDir = Path.of(config.dataDir());

        System.out.println("[benchmark] carregando searchers...");
        try (Arena arena = Arena.ofShared()) {
            VectorSearcher f32 = new MmapBruteForceSearcher(
                    config.datasetPath(), arena, new EuclideanDistanceCalculator());
            DistanceCalculator distCalc = new EuclideanDistanceCalculator();
            try (QuantizedBruteForceSearcher i8 = new QuantizedBruteForceSearcher(
                    dataDir, QuantizedBruteForceSearcher.Dtype.I8, distCalc, arena);
                 QuantizedBruteForceSearcher i16 = new QuantizedBruteForceSearcher(
                         dataDir, QuantizedBruteForceSearcher.Dtype.I16, distCalc, arena)) {

                // aquecimento mínimo antes de medir
                for (int w = 0; w < 3; w++) {
                    for (Entry e : entries.subList(0, Math.min(10, entries.size()))) {
                        float[] vec = vectorizer.vectorize(e.request());
                        f32.search(vec, K);
                        i8.search(vec, K);
                        i16.search(vec, K);
                    }
                }

                System.out.printf("[benchmark] medindo %d queries...%n", entries.size());
                long[] latF32 = new long[entries.size()];
                long[] latI8  = new long[entries.size()];
                long[] latI16 = new long[entries.size()];
                int divergI8 = 0;
                int divergI16 = 0;
                int mismatchI8 = 0;
                int mismatchI16 = 0;

                for (int qi = 0; qi < entries.size(); qi++) {
                    float[] vec = vectorizer.vectorize(entries.get(qi).request());

                    long t0 = System.nanoTime();
                    List<SearchResult> resF32 = f32.search(vec, K);
                    latF32[qi] = System.nanoTime() - t0;

                    t0 = System.nanoTime();
                    List<SearchResult> resI8 = i8.search(vec, K);
                    latI8[qi] = System.nanoTime() - t0;

                    t0 = System.nanoTime();
                    List<SearchResult> resI16 = i16.search(vec, K);
                    latI16[qi] = System.nanoTime() - t0;

                    int fc32 = fraudCount(resF32);
                    int fc8  = fraudCount(resI8);
                    int fc16 = fraudCount(resI16);

                    if (fc32 != fc8)                        mismatchI8++;
                    if (fc32 != fc16)                       mismatchI16++;
                    if (approved(fc32) != approved(fc8))    divergI8++;
                    if (approved(fc32) != approved(fc16))   divergI16++;
                }

                int n = entries.size();
                double recallI8  = 100.0 * (n - mismatchI8)  / n;
                double recallI16 = 100.0 * (n - mismatchI16) / n;
                double divPctI8  = 100.0 * divergI8  / n;
                double divPctI16 = 100.0 * divergI16 / n;

                System.out.println("\n══════════════════════════════════════════════════");
                System.out.println(" RESULTADO DO BENCHMARK DE QUANTIZAÇÃO");
                System.out.println("══════════════════════════════════════════════════");
                System.out.printf(" Queries avaliadas : %d%n%n", n);
                System.out.printf(" %-8s  recall@5=%6.2f%%  diverg=%5.2f%%  p50=%6.2fms  p99=%6.2fms%n",
                        "float32", 100.0, 0.0, ms(p(latF32, 50)), ms(p(latF32, 99)));
                System.out.printf(" %-8s  recall@5=%6.2f%%  diverg=%5.2f%%  p50=%6.2fms  p99=%6.2fms%n",
                        "int8",    recallI8,  divPctI8,  ms(p(latI8,  50)), ms(p(latI8,  99)));
                System.out.printf(" %-8s  recall@5=%6.2f%%  diverg=%5.2f%%  p50=%6.2fms  p99=%6.2fms%n",
                        "int16",   recallI16, divPctI16, ms(p(latI16, 50)), ms(p(latI16, 99)));
                System.out.println();

                boolean i8ok  = recallI8  >= 99.0 && divPctI8  <= 1.0;
                boolean i16ok = recallI16 >= 99.0 && divPctI16 <= 1.0;

                if (i8ok) {
                    System.out.println(" VEREDITO: INT8 APROVADO — desbloqueia Issue 01 com representação int8");
                } else {
                    System.out.printf(" INT8 REPROVADO  (recall=%.2f%%, diverg=%.2f%%)%n", recallI8, divPctI8);
                    if (i16ok) {
                        System.out.println(" VEREDITO: PLANO B — INT16 APROVADO — desbloqueia Issue 01 com int16");
                    } else {
                        System.out.printf(" INT16 TAMBÉM REPROVADO (recall=%.2f%%, diverg=%.2f%%)%n",
                                recallI16, divPctI16);
                        System.out.println(" VEREDITO: INVESTIGAÇÃO NECESSÁRIA antes de desbloquear Issue 01");
                    }
                }
                System.out.println("══════════════════════════════════════════════════");
            }
        }
    }

    private static int fraudCount(List<SearchResult> results) {
        int c = 0;
        for (SearchResult r : results) {
            if ("fraud".equals(r.label())) c++;
        }
        return c;
    }

    private static boolean approved(int fraudCount) {
        return (double) fraudCount / K < APPROVED_THRESHOLD;
    }

    private static long p(long[] arr, int pct) {
        long[] sorted = arr.clone();
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, idx)];
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
