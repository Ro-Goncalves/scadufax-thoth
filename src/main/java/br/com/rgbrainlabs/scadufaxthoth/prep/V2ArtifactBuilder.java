package br.com.rgbrainlabs.scadufaxthoth.prep;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Gera o artefato binário V2 — um único arquivo com layout:
 *
 *   [Header 24 bytes] → [Diretório de Clusters] → [Blocos de Registros]
 *
 * Nesta fatia (Issue 01) o diretório tem sempre 1 cluster contendo todos os
 * registros. O IVF real com múltiplos clusters entra na Issue 02.
 *
 * Diferença-chave em relação ao QuantizedDatasetBuilder:
 *   - Arquivo único em vez de múltiplos arquivos separados.
 *   - Label binária (1 byte) embutida em cada registro.
 *   - Sentinela −128 (Byte.MIN_VALUE) para ausência de last_transaction,
 *     em vez de −127 usado no formato anterior.
 *
 * Uso: java ...V2ArtifactBuilder <references.json.gz> <output.v2>
 */
public final class V2ArtifactBuilder {

    public static final byte VERSION       = 2;
    public static final byte DTYPE_I8      = 1;
    public static final int  DIMS          = 14;
    public static final int  SCALE         = 127;

    // Tamanhos fixos do formato V2
    public static final int HEADER_SIZE       = 24; // 1+2+1+4+8+8
    public static final int CLUSTER_ENTRY_SIZE = 30; // 14(centróide)+4(radius)+8(offset)+4(count)
    public static final int RECORD_SIZE       = 16; // 1(label)+14(vetor)+1(padding)

    private static final int LOG_EVERY = 500_000;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: V2ArtifactBuilder <references.json.gz> <output.v2>");
            System.exit(1);
        }
        build(Path.of(args[0]), Path.of(args[1]));
    }

    /**
     * Constrói o artefato V2 em duas passagens:
     *   Pass 1 — lê o JSON e grava só os registros (16 bytes cada) num arquivo temp.
     *   Pass 2 — monta o arquivo final: header + entrada de cluster + copia o temp.
     *
     * A separação em duas passagens é necessária porque o header precisa do count
     * (total de registros), que só é conhecido após varrer todo o JSON.
     */
    public static void build(Path input, Path output) throws Exception {
        Files.createDirectories(output.getParent() != null ? output.getParent() : Path.of("."));

        Path tempRecords = output.resolveSibling(output.getFileName() + ".tmp");
        long t0 = System.currentTimeMillis();
        System.out.println("[v2-builder] lendo " + input.toAbsolutePath());

        // ── Pass 1: streaming do JSON → registros binários em temp ─────────────

        int count = 0;
        byte[] row = new byte[RECORD_SIZE];
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

                if (dim != DIMS) {
                    throw new IllegalStateException(
                            "Registro " + count + " tem " + dim + " dims; esperado " + DIMS + ".");
                }

                row[0] = isFraud ? (byte) 1 : (byte) 0;
                encodeI8(floats, row, 1);
                row[15] = 0; // padding reservado para uso futuro
                tmp.write(row);
                count++;

                if (count % LOG_EVERY == 0) {
                    System.out.printf("[v2-builder] %d registros (%.1fs)%n",
                            count, (System.currentTimeMillis() - t0) / 1000.0);
                }
            }
        }

        // ── Pass 2: header + entrada de cluster + copia os registros ───────────

        // data_offset = onde os registros começam no arquivo final
        long dataOffset = HEADER_SIZE + (long) CLUSTER_ENTRY_SIZE; // 24 + 30 = 54

        try (OutputStream    rawOut = new BufferedOutputStream(Files.newOutputStream(output), 1 << 20);
             DataOutputStream dos   = new DataOutputStream(rawOut)) {

            // Header (24 bytes)
            dos.writeByte(VERSION);          // 1 byte  — versão do formato
            dos.writeShort(DIMS);            // 2 bytes — dimensões do vetor
            dos.writeByte(DTYPE_I8);         // 1 byte  — tipo de quantização
            dos.writeInt(1);                 // 4 bytes — num_clusters (mínimo: 1)
            dos.writeLong(HEADER_SIZE);      // 8 bytes — offset do diretório de clusters
            dos.writeLong(dataOffset);       // 8 bytes — offset da área de dados

            // Entrada do cluster único (30 bytes)
            for (int d = 0; d < DIMS; d++) {
                dos.writeByte(0);            // centróide = vetor zero (placeholder para Issue 01)
            }
            dos.writeFloat(Float.MAX_VALUE); // raio = máximo (contém todos os vetores)
            dos.writeLong(0L);               // offset do bloco (0 = começa no data_offset)
            dos.writeInt(count);             // quantidade de registros neste cluster

            // Flush antes de copiar o temp — DataOutputStream pode ter buffer interno
            dos.flush();

            // Copia os registros do arquivo temporário para o output final
            Files.copy(tempRecords, rawOut);
        }

        Files.deleteIfExists(tempRecords);

        System.out.printf("[v2-builder] OK: %d registros em %.1fs — %.1f MB%n",
                count, (System.currentTimeMillis() - t0) / 1000.0,
                Files.size(output) / (1024.0 * 1024.0));
    }

    /**
     * Converte 14 floats para int8 com sentinela −128 e escreve em dst a partir de dstOffset.
     *
     * Regra de quantização:
     *   −1.0f (sentinela de ausência de last_transaction) → −128 (Byte.MIN_VALUE)
     *   demais valores [0, 1]                             → round(v × 127), clamp [−127, 127]
     *
     * O sentinela −128 fica fora do domínio de valores normais (que vão até −127),
     * tornando a ausência detectável no espaço quantizado sem tabela extra.
     */
    static void encodeI8(float[] src, byte[] dst, int dstOffset) {
        for (int d = 0; d < DIMS; d++) {
            float v = src[d];
            byte b;
            if (v == -1.0f) {
                b = Byte.MIN_VALUE; // sentinela explícito para dimensões sem last_transaction
            } else {
                int q = Math.round(v * SCALE);
                if (q < -127) q = -127;
                if (q > 127)  q = 127;
                b = (byte) q;
            }
            dst[dstOffset + d] = b;
        }
    }
}
