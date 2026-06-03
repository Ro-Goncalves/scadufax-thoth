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
 * 1. Quantiza a query para int8.
 * 2. Calcula distância euclidiana ao quadrado entre a query e cada centróide.
 * 3. Seleciona os nprobe clusters mais próximos.
 * 4. Escaneia apenas os registros desses clusters com max-heap de tamanho k.
 *
 * O artefato pode ter qualquer número de clusters ≥ 1. Se nprobe ≥ numClusters,
 * o comportamento é equivalente ao brute force da Issue 01.
 *
 * Layout do arquivo:
 * [Header 24 bytes]
 * [Diretório: numClusters × 30 bytes] — centróide(14) + radius(4) + offset(8) +
 * count(4)
 * [Blocos de registros por cluster] — 16 bytes cada: label(1) + vetor(14) +
 * padding(1)
 */
public final class V2IndexSearcher implements VectorSearcher, AutoCloseable {

    private static final int DIMS = V2ArtifactBuilder.DIMS;
    private static final int SCALE = V2ArtifactBuilder.SCALE;
    private static final int RECORD_SIZE = V2ArtifactBuilder.RECORD_SIZE;

    private static volatile long PREWARM_SINK = 0;

    private final int numClusters;
    private final int nprobe;
    private final long dataOffset;
    private final byte[][] centroids; // int8, um por cluster
    private final int[] counts; // registros por cluster
    private final long[] offsets; // offsets relativos ao dataOffset
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
        this.centroids = header.centroids;
        this.counts = header.counts;
        this.offsets = header.offsets;

        try (FileChannel ch = FileChannel.open(artifactPath, StandardOpenOption.READ)) {
            this.file = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
        }
    }

    @Override
    public List<SearchResult> search(float[] queryVector, int k) {
        byte[] q = quantizeQuery(queryVector);

        // Ordena clusters por distância crescente ao centróide
        int[] ranked = rankClusters(q);

        TopKSelector selector = new TopKSelector(k);
        int probes = Math.min(nprobe, numClusters);

        for (int ci = 0; ci < probes; ci++) {
            int cluster = ranked[ci];
            long blockStart = dataOffset + offsets[cluster];
            int blockCount = counts[cluster];

            for (int i = 0; i < blockCount; i++) {
                long recordBase = blockStart + (long) i * RECORD_SIZE;
                double dist = calculator.calculateI8(q, file, recordBase + 1, DIMS);
                byte labelByte = file.get(ValueLayout.JAVA_BYTE, recordBase);
                selector.tryInsert(dist, labelByte);
            }
        }

        return selector.materialize();
    }

    @Override
    public void close() {
        arena.close();
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
    private int[] rankClusters(byte[] q) {
        long[] distAndIdx = new long[numClusters];

        for (int c = 0; c < numClusters; c++) {
            long dist = centroidDist(q, centroids[c]);
            // Empacota: Distância nos 32 bits altos | Índice nos 32 bits baixos
            distAndIdx[c] = (dist << 32) | c;
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

    /** Distância euclidiana ao quadrado entre dois vetores int8. */
    private static int centroidDist(byte[] q, byte[] c) {
        int sum = 0;
        for (int d = 0; d < DIMS; d++) {
            int diff = q[d] - c[d];
            sum += diff * diff;
        }
        return sum;
    }

    // ── Leitura do cabeçalho e diretório ─────────────────────────────────────────

    private record Header(int numClusters, long dataOffset,
            byte[][] centroids, int[] counts, long[] offsets) {
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
            if (dtype != V2ArtifactBuilder.DTYPE_I8) {
                throw new IllegalStateException(
                        "Tipo I8 esperado (dtype=1), encontrado: " + dtype);
            }
            int numClusters = dis.readInt();
            dis.readLong(); // clusterDirOffset — imediatamente após header
            long dataOffset = dis.readLong();

            byte[][] centroids = new byte[numClusters][dims];
            int[] counts = new int[numClusters];
            long[] offsets = new long[numClusters];

            for (int c = 0; c < numClusters; c++) {
                dis.readFully(centroids[c]); // centróide int8 (dims bytes)
                dis.readFloat(); // radius — ignorado no IVF por distância
                offsets[c] = dis.readLong();
                counts[c] = dis.readInt();
            }

            return new Header(numClusters, dataOffset, centroids, counts, offsets);
        }
    }

    /**
     * Quantiza o vetor de query com a mesma regra do V2ArtifactBuilder:
     * −1.0f → −128 (sentinela), demais → round(v × 127), clamp [−127, 127].
     */
    private static byte[] quantizeQuery(float[] v) {
        byte[] q = new byte[DIMS];
        for (int d = 0; d < DIMS; d++) {
            float val = v[d];
            if (val == -1.0f) {
                q[d] = Byte.MIN_VALUE;
            } else {
                int r = Math.round(val * SCALE);
                if (r < -127)
                    r = -127;
                if (r > 127)
                    r = 127;
                q[d] = (byte) r;
            }
        }
        return q;
    }
}
