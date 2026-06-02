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
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Buscador força-bruta sobre o artefato binário V2.
 *
 * Lê o cabeçalho e o diretório de clusters com DataInputStream (big-endian),
 * mapeia o arquivo inteiro em memória nativa via Arena e acessa os registros
 * por offset aritmético direto, sem parsing por registro.
 *
 * Nesta fatia (Issue 01) o diretório tem 1 cluster. A busca percorre todos os
 * registros em sequência — idêntico ao brute force, mas sobre o formato V2.
 * A navegação seletiva por clusters (IVF real) entra na Issue 02.
 *
 * Layout do registro (16 bytes fixos):
 *   [0]    label   byte  — 0=legítimo, 1=fraude
 *   [1-14] vetor   bytes — int8, sentinela −128 para ausência de last_transaction
 *   [15]   padding byte  — reservado
 */
public final class V2IndexSearcher implements VectorSearcher, AutoCloseable {

    private static final int DIMS        = V2ArtifactBuilder.DIMS;
    private static final int SCALE       = V2ArtifactBuilder.SCALE;
    private static final int RECORD_SIZE = V2ArtifactBuilder.RECORD_SIZE;

    private final int count;
    private final long dataOffset;
    private final Arena arena;
    private final MemorySegment file;
    private final DistanceCalculator calculator;

    public V2IndexSearcher(Path artifactPath, DistanceCalculator calculator) throws IOException {
        this.calculator = calculator;
        this.arena = Arena.ofShared();

        // Lê cabeçalho e diretório de clusters via DataInputStream (big-endian)
        Header header = readHeader(artifactPath);
        this.dataOffset = header.dataOffset;
        this.count      = header.count;

        // mmap do arquivo inteiro — o FileChannel pode ser fechado após o map
        try (FileChannel ch = FileChannel.open(artifactPath, StandardOpenOption.READ)) {
            this.file = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
        }
    }

    @Override
    public List<SearchResult> search(float[] queryVector, int k) {
        byte[] q = quantizeQuery(queryVector);
        PriorityQueue<SearchResult> pq = new PriorityQueue<>(k);

        for (int i = 0; i < count; i++) {
            long recordBase = dataOffset + (long) i * RECORD_SIZE;

            // Byte 0 do registro: label (0 = legítimo, 1 = fraude)
            byte labelByte = file.get(ValueLayout.JAVA_BYTE, recordBase);
            String label = labelByte == 1 ? "fraud" : "legitimate";

            // Bytes 1–14 do registro: vetor int8
            long vectorBase = recordBase + 1;
            double dist = calculator.calculateI8(q, file, vectorBase, DIMS);

            if (pq.size() < k) {
                pq.offer(new SearchResult(label, dist));
            } else if (dist < pq.peek().distance()) {
                pq.poll();
                pq.offer(new SearchResult(label, dist));
            }
        }

        List<SearchResult> results = new ArrayList<>(pq);
        results.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        return results;
    }

    @Override
    public void close() {
        arena.close();
    }

    private record Header(long dataOffset, int count) {}

    /**
     * Lê e valida o cabeçalho e a primeira entrada do diretório de clusters.
     * DataInputStream usa big-endian — mesmo byte order do DataOutputStream do builder.
     */
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
            dis.readInt();              // numClusters — lido para avançar o cursor
            dis.readLong();             // clusterDirOffset — lido para avançar o cursor
            long dataOffset = dis.readLong();

            // Primeira entrada do diretório: centróide(dims) + radius(4) + offset(8) + count(4)
            dis.readNBytes(dims);       // centróide — ignorado no brute force
            dis.readFloat();            // raio     — ignorado
            dis.readLong();             // offset   — ignorado (único cluster começa em 0)
            int count = dis.readInt();

            return new Header(dataOffset, count);
        }
    }

    /**
     * Quantiza o vetor de query com a mesma regra do V2ArtifactBuilder:
     *   −1.0f → −128 (sentinela), demais → round(v × 127), clamp [−127, 127].
     *
     * Usar a mesma função de encode no builder e no searcher garante que a
     * distância entre dois vetores com sentinela seja zero (ausência == ausência),
     * preservando a semântica do vizinho mais próximo.
     */
    private static byte[] quantizeQuery(float[] v) {
        byte[] q = new byte[DIMS];
        for (int d = 0; d < DIMS; d++) {
            float val = v[d];
            if (val == -1.0f) {
                q[d] = Byte.MIN_VALUE;
            } else {
                int r = Math.round(val * SCALE);
                if (r < -127) r = -127;
                if (r > 127)  r = 127;
                q[d] = (byte) r;
            }
        }
        return q;
    }
}
