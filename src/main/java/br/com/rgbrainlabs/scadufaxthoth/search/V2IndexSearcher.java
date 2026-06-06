package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;

import java.io.*;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
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

    // Constantes do formato binário V2 — espelham V2ArtifactBuilder que vive em src/test.
    private static final byte VERSION        = 2;
    private static final byte DTYPE_I8       = 1;
    private static final byte DTYPE_I16      = 2;
    private static final int  DIMS           = 14;
    private static final int  SCALE          = 127;
    private static final int  SCALE_I16      = 10_000;
    private static final int  RECORD_SIZE    = 16;  // 1(label)+14(i8)+1(padding)
    private static final int  RECORD_SIZE_I16 = 30; // 1(label)+28(i16)+1(padding)
    private static final int  K_NEIGHBORS    = 5;

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
    private final MappedByteBuffer file; // índice mmap; LITTLE_ENDIAN (encoding V2)
    private final DistanceCalculator calculator;
    private final ThreadLocal<SearchState> searchState;

    /** Retrocompatível — nprobe padrão = 8. */
    public V2IndexSearcher(Path artifactPath, DistanceCalculator calculator) throws IOException {
        this(artifactPath, calculator, 8);
    }

    public V2IndexSearcher(Path artifactPath, DistanceCalculator calculator, int nprobe)
            throws IOException {
        this.calculator = calculator;
        this.nprobe = nprobe;

        Header header = readHeader(artifactPath);
        this.numClusters = header.numClusters;
        this.dataOffset = header.dataOffset;
        this.dtype = header.dtype;
        this.scale = (dtype == DTYPE_I16) ? SCALE_I16 : SCALE;
        this.recordSize = (dtype == DTYPE_I16) ? RECORD_SIZE_I16 : RECORD_SIZE;
        this.centroids = header.centroids;
        this.counts = header.counts;
        this.offsets = header.offsets;
        this.bboxMin = header.bboxMin;
        this.bboxMax = header.bboxMax;

        final int nc = this.numClusters;
        this.searchState = ThreadLocal.withInitial(() -> new SearchState(nc));

        try (FileChannel ch = FileChannel.open(artifactPath, StandardOpenOption.READ)) {
            MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            m.order(ByteOrder.LITTLE_ENDIAN); // shorts i16 do artefato são little-endian
            this.file = m; // o mapeamento sobrevive ao fechamento do channel
        }
    }

    @Override
    public List<SearchResult> search(float[] queryVector, int k) {
        SearchState s = searchState.get();
        s.topK.reset();

        quantizeQuery(queryVector, s.qi);

        // Ordena clusters por distância crescente ao centróide
        rankClusters(s.qi, s.distAndIdx, s.ranked);

        int probes = Math.min(nprobe, numClusters);

        if (dtype == DTYPE_I16) {
            toI16Query(s.qi, s.q16);
            // Aquecimento: varre os nprobe clusters mais próximos do centróide.
            for (int ci = 0; ci < probes; ci++) {
                scanClusterI16(s.ranked[ci], s.q16, s.topK);
            }
            // Poda exata (desigualdade triangular): itera o restante em ordem de
            // distância ao centróide; pula o cluster inteiro quando seu lower-bound
            // geométrico já é pior que o k-ésimo vizinho atual.
            for (int ci = probes; ci < numClusters; ci++) {
                int cluster = s.ranked[ci];
                if (bboxLowerBound(s.qi, bboxMin[cluster], bboxMax[cluster]) > s.topK.worstDist()) {
                    continue;
                }
                scanClusterI16(cluster, s.q16, s.topK);
            }
        } else {
            toI8Query(s.qi, s.q8);
            for (int ci = 0; ci < probes; ci++) {
                scanClusterI8(s.ranked[ci], s.q8, s.topK);
            }
            for (int ci = probes; ci < numClusters; ci++) {
                int cluster = s.ranked[ci];
                if (bboxLowerBound(s.qi, bboxMin[cluster], bboxMax[cluster]) > s.topK.worstDist()) {
                    continue;
                }
                scanClusterI8(cluster, s.q8, s.topK);
            }
        }

        return s.topK.materialize();
    }

    /** Varre os registros do cluster (i16) inserindo cada um no top-k. Zero alocação por candidato. */
    private void scanClusterI16(int cluster, short[] q16, TopKSelector selector) {
        long blockStart = dataOffset + offsets[cluster];
        int blockCount = counts[cluster];
        for (int i = 0; i < blockCount; i++) {
            long recordBase = blockStart + (long) i * recordSize;
            double dist = calculator.calculateI16(q16, file, recordBase + 1, DIMS);
            byte labelByte = file.get((int) recordBase);
            selector.tryInsert(dist, labelByte);
        }
    }

    /** Varre os registros do cluster (i8) inserindo cada um no top-k. Zero alocação por candidato. */
    private void scanClusterI8(int cluster, byte[] q8, TopKSelector selector) {
        long blockStart = dataOffset + offsets[cluster];
        int blockCount = counts[cluster];
        for (int i = 0; i < blockCount; i++) {
            long recordBase = blockStart + (long) i * recordSize;
            double dist = calculator.calculateI8(q8, file, recordBase + 1, DIMS);
            byte labelByte = file.get((int) recordBase);
            selector.tryInsert(dist, labelByte);
        }
    }

    /**
     * Lower-bound geométrico: menor distância euclidiana ao quadrado possível entre a
     * query e qualquer ponto dentro da bounding box do cluster. Por dimensão: 0 se a
     * query cai na faixa [min, max]; caso contrário, o quadrado da folga até o lado
     * mais próximo. Soma sobre as DIMS dimensões.
     *
     * Acumula em long (não int): em i16 a soma pode chegar a ~5,6×10⁹ e ultrapassar
     * Integer.MAX_VALUE — igual ao calculateI16. Truncar para int daria um lower-bound
     * menor que a distância real e quebraria a exatidão da poda. A comparação com
     * worstDist() (double) é exata: ambos são inteiros menores que 2^53.
     */
    private static long bboxLowerBound(int[] query, int[] bboxMin, int[] bboxMax) {
        long lb = 0;
        for (int d = 0; d < DIMS; d++) {
            int q = query[d];
            int lo = bboxMin[d];
            int hi = bboxMax[d];
            if (q < lo) {
                long diff = lo - q;
                lb += diff * diff;
            } else if (q > hi) {
                long diff = q - hi;
                lb += diff * diff;
            }
            // dentro da faixa [lo, hi]: a dimensão contribui 0
        }
        return lb;
    }

    @Override
    public void close() {
        // MappedByteBuffer não tem unmap explícito (liberado pelo Cleaner na coleta).
        // Mantido para satisfazer AutoCloseable e o try-with-resources dos testes.
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
        // MADV_WILLNEED + force-load: lê o mapeamento para a RAM e sinaliza ao page
        // cache para mantê-lo residente, matando page faults frios no primeiro request.
        file.load();
        int size = file.capacity();
        long sink = 0;
        long accesses = 0;
        for (int off = 0; off < size; off += 4096) {
            sink += file.get(off);
            accesses++;
        }
        if (size > 0 && (size % 4096) != 0) {
            sink += file.get(size - 1);
        }
        PREWARM_SINK = sink;
        System.out.printf("[prewarm] %d páginas tocadas em %d ms%n",
                accesses, System.currentTimeMillis() - t0);
        return accesses;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** Ordena os índices de cluster por distância euclidiana ao quadrado até q. Escreve em ranked. */
    private void rankClusters(int[] q, long[] distAndIdx, int[] ranked) {
        for (int c = 0; c < numClusters; c++) {
            int dist = centroidDist(q, centroids[c]);
            // Empacota: Distância nos 32 bits altos | Índice nos 32 bits baixos
            distAndIdx[c] = ((long) dist << 32) | c;
        }

        // Sort 100% primitivo. O Java ordena pelos 32 bits altos (a distância)
        // naturalmente!
        Arrays.sort(distAndIdx);

        for (int i = 0; i < numClusters; i++) {
            // Extrai apenas os 32 bits baixos (o índice do cluster original)
            ranked[i] = (int) distAndIdx[i];
        }
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

    private static void toI8Query(int[] q, byte[] dest) {
        for (int i = 0; i < q.length; i++) {
            dest[i] = (byte) q[i];
        }
    }

    private static void toI16Query(int[] q, short[] dest) {
        for (int i = 0; i < q.length; i++) {
            dest[i] = (short) q[i];
        }
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
            if (version != VERSION) {
                throw new IllegalStateException(
                        "Artefato V2 esperado (versão 2), encontrado: " + version);
            }
            short dims = dis.readShort();
            byte dtype = dis.readByte();
            if (dtype != DTYPE_I8 && dtype != DTYPE_I16) {
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

    // ── Estado thread-local ───────────────────────────────────────────────────────

    /**
     * Arrays pré-alocados por thread para eliminar alocações no hot path de busca.
     * Uma instância é criada por thread na primeira chamada a search() e reutilizada
     * em todas as chamadas subsequentes via ThreadLocal.
     */
    static final class SearchState {
        final int[]         qi;
        final short[]       q16;
        final byte[]        q8;
        final long[]        distAndIdx;
        final int[]         ranked;
        final double[]      topDist;
        final byte[]        topLabel;
        final TopKSelector  topK;

        SearchState(int numClusters) {
            qi         = new int   [DIMS];
            q16        = new short [DIMS];
            q8         = new byte  [DIMS];
            distAndIdx = new long  [numClusters];
            ranked     = new int   [numClusters];
            topDist    = new double[K_NEIGHBORS];
            topLabel   = new byte  [K_NEIGHBORS];
            topK       = new TopKSelector(topDist, topLabel);
        }
    }

    /**
     * Lê um vetor de {@code dims} componentes do diretório: bytes signed (i8) ou
     * shorts little-endian (i16). Usado para o centróide e para os limites da bbox.
     */
    private static int[] readVector(DataInputStream dis, int dims, byte dtype) throws IOException {
        int[] v = new int[dims];
        if (dtype == DTYPE_I16) {
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
    private void quantizeQuery(float[] v, int[] dest) {
        for (int d = 0; d < DIMS; d++) {
            float val = v[d];
            if (val == -1.0f) {
                dest[d] = (dtype == DTYPE_I16) ? Short.MIN_VALUE : Byte.MIN_VALUE;
            } else {
                int r = Math.round(val * scale);
                if (dtype == DTYPE_I16) {
                    if (r < -32767) r = -32767;
                    if (r >  32767) r =  32767;
                } else {
                    if (r < -127) r = -127;
                    if (r >  127) r =  127;
                }
                dest[d] = r;
            }
        }
    }
}