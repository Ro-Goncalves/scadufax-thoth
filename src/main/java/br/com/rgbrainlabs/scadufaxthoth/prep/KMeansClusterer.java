package br.com.rgbrainlabs.scadufaxthoth.prep;

import java.util.Arrays;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * Lloyd's K-means sobre vetores int8.
 *
 * Todos os cálculos de distância e atribuição rodam em aritmética inteira —
 * o mesmo espaço usado pelo V2IndexSearcher em tempo de consulta. O passo de
 * atualização acumula somas em int32 (sem overflow para até ~16M vetores × 128)
 * e re-quantiza o centróide flutuante de volta para byte[].
 *
 * O número real de clusters é min(k, n) para lidar com fixtures pequenos nos
 * testes sem mudança de comportamento em produção.
 */
public final class KMeansClusterer {

    private static final int DIMS = V2ArtifactBuilder.DIMS;

    private final int k;
    private final int maxIterations;
    private final Random rng;

    public KMeansClusterer(int k, int maxIterations, long seed) {
        this.k             = k;
        this.maxIterations = maxIterations;
        this.rng           = new Random(seed);
    }

    public record ClusterResult(byte[][] centroids, int[] assignments) {}

    /**
     * Agrupa os vetores em até k clusters.
     *
     * @param vectors N vetores de DIMS bytes cada (int8 quantizado)
     * @return centróides e atribuições finais
     */
    public ClusterResult cluster(byte[][] vectors) {
        int n = vectors.length;
        int actualK = Math.min(k, n);

        byte[][] centroids  = initCentroids(vectors, actualK);
        int[]    assignments = new int[n];

        Arrays.fill(assignments, 0);

        for (int iter = 0; iter < maxIterations; iter++) {
            long it0 = System.currentTimeMillis();
            int changed = assignStep(vectors, centroids, assignments);
            if (changed == 0) {
                System.out.printf("[kmeans] iter %d/%d: convergiu (0 reatribuicoes)%n",
                        iter + 1, maxIterations);
                break;
            }
            updateStep(vectors, assignments, centroids, actualK);
            System.out.printf("[kmeans] iter %d/%d: %d reatribuicoes (%.1fs)%n",
                    iter + 1, maxIterations, changed,
                    (System.currentTimeMillis() - it0) / 1000.0);
        }

        return new ClusterResult(centroids, assignments);
    }

    /** Inicializa centróides por amostragem aleatória sem reposição. */
    private byte[][] initCentroids(byte[][] vectors, int actualK) {
        int n = vectors.length;
        int[] chosen = reservoirSample(n, actualK);
        byte[][] centroids = new byte[actualK][DIMS];
        for (int i = 0; i < actualK; i++) {
            System.arraycopy(vectors[chosen[i]], 0, centroids[i], 0, DIMS);
        }
        return centroids;
    }

    /** Sorteia actualK índices distintos de [0, n) sem reposição (Knuth shuffle parcial). */
    private int[] reservoirSample(int n, int actualK) {
        int[] idx = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        for (int i = 0; i < actualK; i++) {
            int j = i + rng.nextInt(n - i);
            int tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp;
        }
        return Arrays.copyOf(idx, actualK);
    }

    /**
     * Passo de atribuição: cada vetor vai para o centróide mais próximo.
     *
     * É O(n·k·DIMS) e domina o custo do K-means — para k grande (ex.: 2048) e
     * n na casa dos milhões, roda em paralelo sobre os cores disponíveis. Cada
     * vetor é atribuído de forma independente (escritas em índices distintos de
     * {@code assignments}), então o resultado é idêntico ao serial e continua
     * determinístico para uma dada seed.
     *
     * @return número de vetores que mudaram de cluster (0 = convergiu)
     */
    private static int assignStep(byte[][] vectors, byte[][] centroids, int[] assignments) {
        int k = centroids.length;

        return IntStream.range(0, vectors.length).parallel().map(i -> {
            byte[] v        = vectors[i];
            int    best     = assignments[i];
            int    bestDist = dist(v, centroids[best]);

            for (int c = 0; c < k; c++) {
                if (c == best) continue;
                int d = dist(v, centroids[c]);
                if (d < bestDist) {
                    bestDist = d;
                    best     = c;
                }
            }

            if (best != assignments[i]) {
                assignments[i] = best;
                return 1;
            }
            return 0;
        }).sum();
    }

    /**
     * Passo de atualização: cada centróide vira a média (float) dos vetores
     * atribuídos, re-quantizada para byte[].
     *
     * Clusters que ficaram vazios mantêm o centróide anterior.
     */
    private static void updateStep(byte[][] vectors, int[] assignments,
                                   byte[][] centroids, int actualK) {
        int n    = vectors.length;
        int[][] sums   = new int[actualK][DIMS];
        int[]   counts = new int[actualK];

        for (int i = 0; i < n; i++) {
            int c = assignments[i];
            counts[c]++;
            for (int d = 0; d < DIMS; d++) {
                sums[c][d] += vectors[i][d]; // int8 cabe em int32 sem overflow
            }
        }

        for (int c = 0; c < actualK; c++) {
            if (counts[c] == 0) continue; // cluster vazio: mantém centróide
            for (int d = 0; d < DIMS; d++) {
                // média float → arredonda → clamp [-127, 127]
                int q = Math.round((float) sums[c][d] / counts[c]);
                if (q < -127) q = -127;
                if (q > 127)  q = 127;
                centroids[c][d] = (byte) q;
            }
        }
    }

    /** Distância euclidiana ao quadrado em int32 (sem raiz — mesma métrica do V2IndexSearcher). */
    static int dist(byte[] a, byte[] b) {
        int sum = 0;
        for (int d = 0; d < DIMS; d++) {
            int diff = a[d] - b[d];
            sum += diff * diff;
        }
        return sum;
    }
}
