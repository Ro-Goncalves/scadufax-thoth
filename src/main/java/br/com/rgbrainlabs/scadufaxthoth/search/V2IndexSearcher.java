package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import br.com.rgbrainlabs.scadufaxthoth.prep.V2ArtifactBuilder;

import java.io.*;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/**
 * Buscador IVF (Inverted File Index) sobre o artefato binário V2.
 *
 * Em tempo de consulta:
 * 1. Quantiza a query para int[] (i8 ou i16, conforme dtype do artefato).
 * 2. Calcula distância euclidiana ao quadrado entre a query e cada centróide (int[][]).
 * 3. Seleciona os nprobe clusters mais próximos.
 * 4. Escaneia apenas os registros desses clusters com max-heap de tamanho k.
 *
 * O artefato pode ter qualquer número de clusters ≥ 1. Se nprobe ≥ numClusters,
 * o comportamento é equivalente ao brute force da Issue 01.
 *
 * Layout i8 (dtype=1):
 *   [Header 24 bytes] [Diretório: K × 58 bytes] [Registros: 16 bytes cada]
 *
 * Layout i16 (dtype=2):
 *   [Header 24 bytes] [Diretório: K × 100 bytes] [Registros: 30 bytes cada]
 */
public final class V2IndexSearcher implements VectorSearcher, AutoCloseable {

    private static final int DIMS = V2ArtifactBuilder.DIMS;

    private static volatile long PREWARM_SINK = 0;

    private final int numClusters;
    private final int nprobe;
    private final long dataOffset;
    private final byte dtype;
    private final int scale;
    private final int recordSize;
    private final int[][] centroids; // widened de byte (i8) ou short (i16) — unifica centroidDist
    private final int[] counts; // registros por cluster
    private final long[] offsets; // offsets relativos ao dataOffset
    private final int[][] bboxMin; // limite inferior por dimensão (espaço do dtype) — poda V4-A
    private final int[][] bboxMax; // limite superior por dimensão (espaço do dtype) — poda V4-A
    private final Arena arena;
    private final MemorySegment file;
    private final DistanceCalculator calculator;

    /** Retrocompatível — nprobe padrão = 8. */
    public V2IndexSearcher(Path artifactPath, DistanceCalculator calculator) throws IOException {
        this(artifactPath, calculator, 8);
    }

    public V2IndexSearcher(Path artifactPath, DistanceCalculator calculator, int nprobe)
            throws IOException {
        this.calculator = calculator;
        this.nprobe = nprobe;
        this.arena = Arena.ofShared();

        Header header = readHeader(artifactPath);
        this.numClusters = header.numClusters;
        this.dataOffset = header.dataOffset;
        this.dtype = header.dtype;
        this.scale = (dtype == V2ArtifactBuilder.DTYPE_I16) ? V2ArtifactBuilder.SCALE_I16 : V2ArtifactBuilder.SCALE;
        this.recordSize = (dtype == V2ArtifactBuilder.DTYPE_I16) ? V2ArtifactBuilder.RECORD_SIZE_I16 : V2ArtifactBuilder.RECORD_SIZE;
        this.centroids = header.centroids;
        this.counts = header.counts;
        this.offsets = header.offsets;
        this.bboxMin = header.bboxMin;
        this.bboxMax = header.bboxMax;

        try (FileChannel ch = FileChannel.open(artifactPath, StandardOpenOption.READ)) {
            this.file = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
        }
    }

    @Override
    public List<SearchResult> search(float[] queryVector, int k) {
        int[] qi = quantizeQuery(queryVector);

        // Ordena clusters por distância crescente ao centróide
        int[] ranked = rankClusters(qi);

        TopKSelector selector = new TopKSelector(k);
        int probes = Math.min(nprobe, numClusters);

        if (dtype == V2ArtifactBuilder.DTYPE_I16) {
            short[] q16 = toI16Query(qi);
            for (int ci = 0; ci < probes; ci++) {
                int cluster = ranked[ci];
                long blockStart = dataOffset + offsets[cluster];
                int blockCount = counts[cluster];
                for (int i = 0; i < blockCount; i++) {
                    long recordBase = blockStart + (long) i * recordSize;
                    double dist = calculator.calculateI16(q16, file, recordBase + 1, DIMS);
                    byte labelByte = file.get(ValueLayout.JAVA_BYTE, recordBase);
                    selector.tryInsert(dist, labelByte);
                }
            }
        } else {
            byte[] q8 = toI8Query(qi);
            for (int ci = 0; ci < probes; ci++) {
                int cluster = ranked[ci];
                long blockStart = dataOffset + offsets[cluster];
                int blockCount = counts[cluster];
                for (int i = 0; i < blockCount; i++) {
                    long recordBase = blockStart + (long) i * recordSize;
                    double dist = calculator.calculateI8(q8, file, recordBase + 1, DIMS);
                    byte labelByte = file.get(ValueLayout.JAVA_BYTE, recordBase);
                    selector.tryInsert(dist, labelByte);
                }
            }
        }

        return selector.materialize();
    }

    @Override
    public void close() {
        arena.close();
    }

    /** Bounding box inferior por cluster (espaço do dtype). Pacote-privado para teste/poda V4-A. */
    int[][] bboxMin() {
        return bboxMin;
    }

    /** Bounding box superior por cluster (espaço do dtype). Pacote-privado para teste/poda V4-A. */
    int[][] bboxMax() {
        return bboxMax;
    }

    /**
     * Toca um byte a cada 4KB do MemorySegment mapeado, faltando todas as páginas
     * na tabela de páginas do processo antes do /ready abrir.
     *
     * Por que tocar o próprio MemorySegment (não um FileChannel à parte)?
     * Um FileChannel separado aquece apenas o page cache do SO. O mmap ainda
     * sofreria um soft fault na tabela de páginas do processo na primeira busca.
     * Tocar o mesmo segmento que o hot path usa elimina os dois overheads.
     *
     * Por que PREWARM_SINK volatile? O JIT detectaria que os bytes lidos não
     * produzem efeito observável e eliminaria o laço (dead-code elimination).
     * O volatile força a JVM a de fato ler e gravar, impedindo essa otimização.
     *
     * @return número de acessos realizados — útil para testes de cobertura
     */
    public long prewarm() {
        long t0 = System.currentTimeMillis();
        long size = file.byteSize();
        long sink = 0;
        long accesses = 0;
        for (long off = 0; off < size; off += 4096) {
            sink += file.get(ValueLayout.JAVA_BYTE, off);
            accesses++;
        }
        if (size > 0 && (size % 4096) != 0) {
            sink += file.get(ValueLayout.JAVA_BYTE, size - 1);
        }
        PREWARM_SINK = sink;
        System.out.printf("[prewarm] %d páginas tocadas em %d ms%n",
                accesses, System.currentTimeMillis() - t0);
        return accesses;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Ordena os índices de cluster por distância euclidiana ao quadrado até q. */
    private int[] rankClusters(int[] q) {
        long[] distAndIdx = new long[numClusters];

        for (int c = 0; c < numClusters; c++) {
            int dist = centroidDist(q, centroids[c]);
            // Empacota: Distância nos 32 bits altos | Índice nos 32 bits baixos
            distAndIdx[c] = ((long) dist << 32) | c;
        }

        // Sort 100% primitivo. O Java ordena pelos 32 bits altos (a distância)
        // naturalmente!
        Arrays.sort(distAndIdx);

        int[] result = new int[numClusters];
        for (int i = 0; i < numClusters; i++) {
            // Extrai apenas os 32 bits baixos (o índice do cluster original)
            result[i] = (int) distAndIdx[i];
        }
        return result;
    }

    /**
     * Distância euclidiana ao quadrado entre dois vetores em espaço int.
     * Retorna saturado em Integer.MAX_VALUE para i16 onde a soma pode exceder int.
     */
    private static int centroidDist(int[] q, int[] c) {
        long sum = 0;
        for (int d = 0; d < DIMS; d++) {
            int diff = q[d] - c[d];
            sum += (long) diff * diff;
        }
        return (int) Math.min(sum, Integer.MAX_VALUE);
    }

    private static byte[] toI8Query(int[] q) {
        byte[] b = new byte[q.length];
        for (int i = 0; i < q.length; i++) {
            b[i] = (byte) q[i];
        }
        return b;
    }

    private static short[] toI16Query(int[] q) {
        short[] s = new short[q.length];
        for (int i = 0; i < q.length; i++) {
            s[i] = (short) q[i];
        }
        return s;
    }

    // ── Leitura do cabeçalho e diretório ─────────────────────────────────────────

    private record Header(int numClusters, long dataOffset, byte dtype,
            int[][] centroids, int[] counts, long[] offsets,
            int[][] bboxMin, int[][] bboxMax) {
    }

    private static Header readHeader(Path artifactPath) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(artifactPath));
                DataInputStream dis = new DataInputStream(raw)) {

            byte version = dis.readByte();
            if (version != V2ArtifactBuilder.VERSION) {
                throw new IllegalStateException(
                        "Artefato V2 esperado (versão 2), encontrado: " + version);
            }
            short dims = dis.readShort();
            byte dtype = dis.readByte();
            if (dtype != V2ArtifactBuilder.DTYPE_I8 && dtype != V2ArtifactBuilder.DTYPE_I16) {
                throw new IllegalStateException(
                        "dtype desconhecido (esperado 1=i8 ou 2=i16), encontrado: " + dtype);
            }
            int numClusters = dis.readInt();
            dis.readLong(); // clusterDirOffset — imediatamente após header
            long dataOffset = dis.readLong();

            int[][] centroids = new int[numClusters][];
            int[] counts = new int[numClusters];
            long[] offsets = new long[numClusters];
            int[][] bboxMin = new int[numClusters][];
            int[][] bboxMax = new int[numClusters][];

            for (int c = 0; c < numClusters; c++) {
                centroids[c] = readVector(dis, dims, dtype);
                dis.readFloat(); // radius — ignorado no IVF por distância
                offsets[c] = dis.readLong();
                counts[c] = dis.readInt();
                bboxMin[c] = readVector(dis, dims, dtype); // limites da bbox, mesmo encoding do centróide
                bboxMax[c] = readVector(dis, dims, dtype);
            }

            return new Header(numClusters, dataOffset, dtype, centroids, counts, offsets,
                    bboxMin, bboxMax);
        }
    }

    /**
     * Lê um vetor de {@code dims} componentes do diretório: bytes signed (i8) ou
     * shorts little-endian (i16). Usado para o centróide e para os limites da bbox.
     */
    private static int[] readVector(DataInputStream dis, int dims, byte dtype) throws IOException {
        int[] v = new int[dims];
        if (dtype == V2ArtifactBuilder.DTYPE_I16) {
            for (int d = 0; d < dims; d++) {
                int lo = dis.readByte() & 0xFF;
                int hi = dis.readByte() & 0xFF;
                v[d] = (short) (lo | (hi << 8)); // widen LE short → int
            }
        } else {
            for (int d = 0; d < dims; d++) {
                v[d] = dis.readByte(); // widen signed byte → int
            }
        }
        return v;
    }

    /**
     * Quantiza o vetor de query para int[] com a mesma regra do V2ArtifactBuilder.
     * Para i8:  −1.0f → Byte.MIN_VALUE,  demais → round(v × 127),  clamp [−127, 127].
     * Para i16: −1.0f → Short.MIN_VALUE, demais → round(v × 10000), clamp [−32767, 32767].
     */
    private int[] quantizeQuery(float[] v) {
        int[] q = new int[DIMS];
        for (int d = 0; d < DIMS; d++) {
            float val = v[d];
            if (val == -1.0f) {
                q[d] = (dtype == V2ArtifactBuilder.DTYPE_I16) ? Short.MIN_VALUE : Byte.MIN_VALUE;
            } else {
                int r = Math.round(val * scale);
                if (dtype == V2ArtifactBuilder.DTYPE_I16) {
                    if (r < -32767) r = -32767;
                    if (r >  32767) r =  32767;
                } else {
                    if (r < -127) r = -127;
                    if (r >  127) r =  127;
                }
                q[d] = r;
            }
        }
        return q;
    }
}