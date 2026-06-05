package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.prep.V2ArtifactBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste de propriedade da Issue 04 (V4-A, Passo 2): a bounding box persistida por
 * cluster envolve todos os vetores atribuídos àquele cluster.
 *
 * Estratégia — oráculo independente: o teste reparseia o artefato byte a byte (sem usar
 * o {@link V2IndexSearcher}) e afirma, para cada cluster, que todo registro do bloco cai
 * dentro de {@code [bboxMin, bboxMax]} naquela dimensão. Como os registros são gravados
 * fisicamente agrupados por cluster, todo registro do bloco {@code c} é, por construção,
 * um vetor atribuído ao cluster {@code c}.
 *
 * Em seguida confirma que o {@code V2IndexSearcher} carrega exatamente as mesmas bboxes
 * (critério "o searcher lê e carrega as bboxes sem erro").
 *
 * Parametrizado por dtype: cobre i8 e i16 — a parametrização permanece viva mesmo com o
 * i16 escolhido para produção.
 *
 * A fixture varia todas as dimensões (não só a dim0) e injeta a sentinela de ausência
 * (-1.0f → -128 / -32768) em ~1/7 dos registros, exercitando a bbox com a sentinela
 * tratada como coordenada comum.
 */
class V2BboxBuildTest {

    private static final int DIMS = V2ArtifactBuilder.DIMS;

    @ParameterizedTest
    @EnumSource(V2ArtifactBuilder.Dtype.class)
    void bboxEnvolveTodosOsVetoresDoCluster(V2ArtifactBuilder.Dtype dtype, @TempDir Path tmpDir)
            throws Exception {
        Path gz       = tmpDir.resolve("fixture.json.gz");
        Path artifact = tmpDir.resolve("index.v2");
        writeGz(gz, buildFixtureJson());

        // K=8 clusters: a fixture de 96 vetores distribui em vários clusters não-vazios.
        V2ArtifactBuilder.build(gz, artifact, 8, 10, 0L, dtype);

        ParsedArtifact pa = parse(artifact, dtype);

        // ── Critério 2: nenhum vetor atribuído fica fora da bbox do seu cluster ──────
        for (int c = 0; c < pa.numClusters; c++) {
            for (int[] vec : pa.records.get(c)) {
                for (int d = 0; d < DIMS; d++) {
                    assertTrue(vec[d] >= pa.bboxMin[c][d], String.format(
                            "dtype=%s cluster=%d dim=%d: valor %d < bboxMin %d",
                            dtype, c, d, vec[d], pa.bboxMin[c][d]));
                    assertTrue(vec[d] <= pa.bboxMax[c][d], String.format(
                            "dtype=%s cluster=%d dim=%d: valor %d > bboxMax %d",
                            dtype, c, d, vec[d], pa.bboxMax[c][d]));
                }
            }
        }

        // A fixture precisa de fato exercitar múltiplos clusters não-vazios.
        long nonEmpty = Arrays.stream(pa.counts).filter(x -> x > 0).count();
        assertTrue(nonEmpty >= 2, "fixture deveria popular ao menos 2 clusters, teve " + nonEmpty);

        // ── Critério 3: o V2IndexSearcher carrega as mesmas bboxes, sem erro ─────────
        try (V2IndexSearcher searcher =
                     new V2IndexSearcher(artifact, new EuclideanDistanceCalculator(), 1)) {
            int[][] loadedMin = searcher.bboxMin();
            int[][] loadedMax = searcher.bboxMax();
            assertEquals(pa.numClusters, loadedMin.length, "bboxMin: uma entrada por cluster");
            assertEquals(pa.numClusters, loadedMax.length, "bboxMax: uma entrada por cluster");
            for (int c = 0; c < pa.numClusters; c++) {
                assertEquals(DIMS, loadedMin[c].length, "bboxMin[c] deve ter DIMS componentes");
                assertArrayEquals(pa.bboxMin[c], loadedMin[c],
                        "searcher bboxMin diverge do oráculo no cluster " + c);
                assertArrayEquals(pa.bboxMax[c], loadedMax[c],
                        "searcher bboxMax diverge do oráculo no cluster " + c);
            }
        }
    }

    // ── Oráculo: parse independente do artefato ──────────────────────────────────────

    private record ParsedArtifact(int numClusters, int[] counts,
            int[][] bboxMin, int[][] bboxMax, List<List<int[]>> records) {}

    /**
     * Reparseia o artefato sem o searcher. Escalares do header/diretório são big-endian
     * (DataOutputStream); os vetores i16 são shorts little-endian (gravados byte a byte).
     * Após o diretório, os blocos de registros vêm em ordem de cluster (0..K-1).
     */
    private static ParsedArtifact parse(Path artifact, V2ArtifactBuilder.Dtype dtype)
            throws IOException {
        boolean i16 = dtype == V2ArtifactBuilder.Dtype.I16;

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(artifact)))) {
            in.readByte();                 // version
            in.readShort();                // dims
            in.readByte();                 // dtype
            int numClusters = in.readInt();
            in.readLong();                 // clusterDirOffset
            in.readLong();                 // dataOffset

            int[]   counts  = new int[numClusters];
            int[][] bboxMin = new int[numClusters][];
            int[][] bboxMax = new int[numClusters][];

            for (int c = 0; c < numClusters; c++) {
                readVec(in, i16);          // centróide (descartado neste teste)
                in.readFloat();            // radius
                in.readLong();             // offset
                counts[c]  = in.readInt(); // count
                bboxMin[c] = readVec(in, i16);
                bboxMax[c] = readVec(in, i16);
            }

            List<List<int[]>> records = new ArrayList<>(numClusters);
            for (int c = 0; c < numClusters; c++) {
                List<int[]> block = new ArrayList<>(counts[c]);
                for (int r = 0; r < counts[c]; r++) {
                    in.readByte();         // label
                    block.add(readVec(in, i16));
                    in.readByte();         // padding
                }
                records.add(block);
            }
            return new ParsedArtifact(numClusters, counts, bboxMin, bboxMax, records);
        }
    }

    /** Lê DIMS componentes: bytes signed (i8) ou shorts little-endian (i16). */
    private static int[] readVec(DataInputStream in, boolean i16) throws IOException {
        int[] v = new int[DIMS];
        if (i16) {
            for (int d = 0; d < DIMS; d++) {
                int lo = in.readUnsignedByte();
                int hi = in.readUnsignedByte();
                v[d] = (short) (lo | (hi << 8));
            }
        } else {
            for (int d = 0; d < DIMS; d++) {
                v[d] = in.readByte();
            }
        }
        return v;
    }

    // ── Fixture ──────────────────────────────────────────────────────────────────────

    /**
     * 96 registros com 14 dimensões. dim0 em 3 bandas (0.0/0.4/0.8) gera clusters
     * distintos; as demais dimensões variam de forma determinística; dim13 recebe a
     * sentinela -1.0f em ~1/7 dos registros.
     */
    private static String buildFixtureJson() {
        StringBuilder sb = new StringBuilder("[");
        int n = 96;
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"label\":\"").append(i % 2 == 0 ? "legitimate" : "fraud")
              .append("\",\"vector\":[");
            for (int d = 0; d < DIMS; d++) {
                if (d > 0) sb.append(",");
                float val;
                if (d == 13 && i % 7 == 0) {
                    val = -1.0f;                          // sentinela de ausência
                } else if (d == 0) {
                    val = (i % 3) * 0.4f;                 // 0.0, 0.4, 0.8 → bandas
                } else {
                    val = ((i * (d + 1)) % 11) / 10.0f;   // 0.0..1.0 determinístico
                }
                sb.append(val);
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
