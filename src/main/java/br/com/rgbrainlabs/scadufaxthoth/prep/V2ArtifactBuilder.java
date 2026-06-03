package br.com.rgbrainlabs.scadufaxthoth.prep;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Gera o artefato binário V2 — um único arquivo com layout:
 *
 *   [Header 24 bytes] → [Diretório de Clusters K×30 bytes] → [Blocos de Registros]
 *
 * Diferença da Issue 01: agora o builder executa K-means (Lloyd's int8) e escreve
 * um cluster por grupo. O diretório de clusters carrega centróide real, offset e
 * count de cada bloco. O V2IndexSearcher usa esse diretório para IVF real.
 *
 * Uso:
 *   java ...V2ArtifactBuilder <input.json.gz> <output.v2> [numClusters] [iterations] [seed]
 */
public final class V2ArtifactBuilder {

    public enum Dtype { I8, I16 }

    public static final byte VERSION       = 2;
    public static final byte DTYPE_I8      = 1;
    public static final byte DTYPE_I16     = 2;
    public static final int  DIMS          = 14;
    public static final int  SCALE         = 127;
    public static final int  SCALE_I16     = 10_000;

    public static final int HEADER_SIZE             = 24; // 1+2+1+4+8+8
    public static final int CLUSTER_ENTRY_SIZE      = 30; // 14(centróide)+4(radius)+8(offset)+4(count)
    public static final int CLUSTER_ENTRY_SIZE_I16  = 44; // 28(centróide)+4(radius)+8(offset)+4(count)
    public static final int RECORD_SIZE             = 16; // 1(label)+14(vetor i8)+1(padding)
    public static final int RECORD_SIZE_I16         = 30; // 1(label)+28(vetor i16)+1(padding)

    public static final int DEFAULT_NUM_CLUSTERS    = 256;
    public static final int DEFAULT_KMEANS_ITER     = 20;
    public static final long DEFAULT_KMEANS_SEED    = 42L;

    private static final int LOG_EVERY = 500_000;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: V2ArtifactBuilder <input.json.gz> <output.v2> [numClusters] [iterations] [seed]");
            System.exit(1);
        }
        int   numClusters = args.length > 2 ? Integer.parseInt(args[2])            : DEFAULT_NUM_CLUSTERS;
        int   iterations  = args.length > 3 ? Integer.parseInt(args[3])            : DEFAULT_KMEANS_ITER;
        long  seed        = args.length > 4 ? Long.parseLong(args[4])              : DEFAULT_KMEANS_SEED;
        Dtype dtype       = (args.length > 5 && "i16".equals(args[5])) ? Dtype.I16 : Dtype.I8;

        build(Path.of(args[0]), Path.of(args[1]), numClusters, iterations, seed, dtype);
    }

    /** Overload retrocompatível com defaults. */
    public static void build(Path input, Path output) throws Exception {
        build(input, output, DEFAULT_NUM_CLUSTERS, DEFAULT_KMEANS_ITER, DEFAULT_KMEANS_SEED, Dtype.I8);
    }

    /** Overload retrocompatível — dtype padrão I8. */
    public static void build(Path input, Path output,
                              int numClusters, int kmeansIterations, long kmeansSeed)
            throws Exception {
        build(input, output, numClusters, kmeansIterations, kmeansSeed, Dtype.I8);
    }

    /**
     * Constrói o artefato V2 multi-cluster em duas fases:
     *
     *   Fase 1 — streaming: lê o JSON.GZ, quantiza para o dtype escolhido, grava temp e
     *             acumula vetores i8 em memória para K-means (~14 bytes × N).
     *   K-means — agrupa os vetores i8 com Lloyd's int8.
     *   Fase 2 — escrita: header + diretório + blocos de registros por cluster.
     */
    public static void build(Path input, Path output,
                              int numClusters, int kmeansIterations, long kmeansSeed,
                              Dtype dtype)
            throws Exception {
        Files.createDirectories(output.getParent() != null ? output.getParent() : Path.of("."));

        int  recordSize       = (dtype == Dtype.I16) ? RECORD_SIZE_I16        : RECORD_SIZE;
        int  clusterEntrySize = (dtype == Dtype.I16) ? CLUSTER_ENTRY_SIZE_I16 : CLUSTER_ENTRY_SIZE;
        byte dtypeByte        = (dtype == Dtype.I16) ? DTYPE_I16              : DTYPE_I8;

        Path tempRecords = output.resolveSibling(output.getFileName() + ".tmp");
        long t0 = System.currentTimeMillis();
        System.out.println("[v2-builder] lendo " + input.toAbsolutePath() + " dtype=" + dtype);

        // ── Fase 1: streaming JSON → temp + coleta vetores i8 para K-means ──────

        List<byte[]>   allVectors = new ArrayList<>();
        List<Boolean>  allLabels  = new ArrayList<>();
        byte[] row = new byte[recordSize];
        ObjectMapper mapper = new ObjectMapper();

        try (InputStream raw  = Files.newInputStream(input);
             InputStream gz   = new GZIPInputStream(new BufferedInputStream(raw, 1 << 20));
             JsonParser  jp   = mapper.getFactory().createParser(gz);
             OutputStream tmp = new BufferedOutputStream(Files.newOutputStream(tempRecords), 1 << 20)) {

            if (jp.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("JSON deve começar com '['.");
            }

            while (jp.nextToken() == JsonToken.START_OBJECT) {
                boolean isFraud = false;
                float[] floats  = new float[DIMS];
                int dim = 0;

                while (jp.nextToken() != JsonToken.END_OBJECT) {
                    String field = jp.currentName();
                    jp.nextToken();
                    if ("vector".equals(field)) {
                        while (jp.nextToken() != JsonToken.END_ARRAY) {
                            if (dim < DIMS) floats[dim++] = jp.getFloatValue();
                        }
                    } else if ("label".equals(field)) {
                        isFraud = "fraud".equals(jp.getText());
                    } else {
                        jp.skipChildren();
                    }
                }

                int count = allVectors.size();
                if (dim != DIMS) {
                    throw new IllegalStateException(
                            "Registro " + count + " tem " + dim + " dims; esperado " + DIMS + ".");
                }

                // quantiza e grava no temp (no formato do dtype escolhido)
                row[0] = isFraud ? (byte) 1 : (byte) 0;
                if (dtype == Dtype.I16) {
                    encodeI16(floats, row, 1);
                } else {
                    encodeI8(floats, row, 1);
                }
                row[recordSize - 1] = 0;
                tmp.write(row);

                // coleta vetor i8 para K-means (independente do dtype do artefato)
                byte[] vec = new byte[DIMS];
                encodeI8(floats, vec, 0);
                allVectors.add(vec);
                allLabels.add(isFraud);

                if ((count + 1) % LOG_EVERY == 0) {
                    System.out.printf("[v2-builder] %d registros (%.1fs)%n",
                            count + 1, (System.currentTimeMillis() - t0) / 1000.0);
                }
            }
        }

        int n = allVectors.size();
        System.out.printf("[v2-builder] %d registros lidos (%.1fs) — rodando K-means k=%d iter=%d%n",
                n, (System.currentTimeMillis() - t0) / 1000.0, numClusters, kmeansIterations);

        // ── K-means sobre os vetores int8 ────────────────────────────────────────

        byte[][] vectors = allVectors.toArray(new byte[0][]);
        KMeansClusterer clusterer = new KMeansClusterer(numClusters, kmeansIterations, kmeansSeed);
        KMeansClusterer.ClusterResult kmResult = clusterer.cluster(vectors);
        byte[][] centroids  = kmResult.centroids();
        int[]    assignments = kmResult.assignments();
        int actualK = centroids.length;

        System.out.printf("[v2-builder] K-means concluído: %d clusters (%.1fs)%n",
                actualK, (System.currentTimeMillis() - t0) / 1000.0);

        // ── Calcula tamanhos e offsets de cada cluster ────────────────────────────

        int[] clusterCount = new int[actualK];
        for (int a : assignments) clusterCount[a]++;

        long[] clusterOffset = new long[actualK]; // relativo ao dataOffset
        long running = 0;
        for (int c = 0; c < actualK; c++) {
            clusterOffset[c] = running;
            running += (long) clusterCount[c] * recordSize;
        }

        // ── Fase 2: header + diretório + blocos de registros ─────────────────────

        long dataOffset = HEADER_SIZE + (long) actualK * clusterEntrySize;

        // Monta os registros agrupados por cluster em memória (índices de posição)
        // Para não re-ler o temp file, re-lemos os bytes do temp e os distribuímos.
        // Alocamos um buffer por cluster.
        byte[][] clusterBuffers = new byte[actualK][];
        int[]    writePos       = new int[actualK];
        for (int c = 0; c < actualK; c++) {
            clusterBuffers[c] = new byte[clusterCount[c] * recordSize];
        }

        try (InputStream tmpIn = new BufferedInputStream(Files.newInputStream(tempRecords), 1 << 20)) {
            byte[] rec = new byte[recordSize];
            for (int i = 0; i < n; i++) {
                int read = 0;
                while (read < recordSize) read += tmpIn.read(rec, read, recordSize - read);
                int c = assignments[i];
                System.arraycopy(rec, 0, clusterBuffers[c], writePos[c], recordSize);
                writePos[c] += recordSize;
            }
        }

        try (OutputStream    rawOut = new BufferedOutputStream(Files.newOutputStream(output), 1 << 20);
             DataOutputStream dos   = new DataOutputStream(rawOut)) {

            // Header (24 bytes)
            dos.writeByte(VERSION);
            dos.writeShort(DIMS);
            dos.writeByte(dtypeByte);
            dos.writeInt(actualK);
            dos.writeLong(HEADER_SIZE);       // clusterDirOffset = imediatamente após header
            dos.writeLong(dataOffset);

            // Diretório de clusters (actualK × clusterEntrySize bytes)
            for (int c = 0; c < actualK; c++) {
                if (dtype == Dtype.I16) {
                    // Centróide i8 rescalado para i16, escrito como LE shorts (28 bytes)
                    for (int d = 0; d < DIMS; d++) {
                        short s = (short) Math.round(centroids[c][d] / 127.0f * SCALE_I16);
                        dos.writeByte(s & 0xFF);
                        dos.writeByte((s >> 8) & 0xFF);
                    }
                } else {
                    dos.write(centroids[c]);                       // centróide i8 (14 bytes)
                }
                dos.writeFloat(Float.MAX_VALUE);                   // radius (não usado no IVF)
                dos.writeLong(clusterOffset[c]);                   // offset relativo a dataOffset
                dos.writeInt(clusterCount[c]);                     // quantidade de registros
            }

            dos.flush();

            // Blocos de registros por cluster
            for (int c = 0; c < actualK; c++) {
                rawOut.write(clusterBuffers[c]);
            }
        }

        Files.deleteIfExists(tempRecords);

        System.out.printf("[v2-builder] OK: %d registros, %d clusters em %.1fs — %.1f MB%n",
                n, actualK, (System.currentTimeMillis() - t0) / 1000.0,
                Files.size(output) / (1024.0 * 1024.0));
    }

    /**
     * Converte 14 floats para int8 com sentinela −128 e escreve em dst a partir de dstOffset.
     *
     * Regra de quantização:
     *   −1.0f (sentinela de ausência de last_transaction) → −128 (Byte.MIN_VALUE)
     *   demais valores [0, 1]                             → round(v × 127), clamp [−127, 127]
     */
    static void encodeI8(float[] src, byte[] dst, int dstOffset) {
        for (int d = 0; d < DIMS; d++) {
            float v = src[d];
            byte b;
            if (v == -1.0f) {
                b = Byte.MIN_VALUE;
            } else {
                int q = Math.round(v * SCALE);
                if (q < -127) q = -127;
                if (q > 127)  q = 127;
                b = (byte) q;
            }
            dst[dstOffset + d] = b;
        }
    }

    /**
     * Converte 14 floats para int16 (LE) com sentinela Short.MIN_VALUE e escreve em dst.
     *
     * Regra de quantização:
     *   −1.0f (sentinela de ausência de last_transaction) → −32768 (Short.MIN_VALUE)
     *   demais valores [0, 1]                             → round(v × 10000), clamp [−32767, 32767]
     *
     * Cada short ocupa 2 bytes consecutivos em little-endian (consistente com calculateI16).
     */
    static void encodeI16(float[] src, byte[] dst, int dstOffset) {
        for (int d = 0; d < DIMS; d++) {
            float v = src[d];
            short s;
            if (v == -1.0f) {
                s = Short.MIN_VALUE;
            } else {
                int q = Math.round(v * SCALE_I16);
                if (q < -32767) q = -32767;
                if (q >  32767) q =  32767;
                s = (short) q;
            }
            dst[dstOffset + d * 2]     = (byte)  (s & 0xFF);
            dst[dstOffset + d * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
    }
}
