package br.com.rgbrainlabs.scadufaxthoth.prep;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.zip.GZIPInputStream;

/**
 * Converte references.json.gz em dois artefatos binários quantizados:
 *
 *   vectors-i8.bin   — N × 14 bytes  (round(v × 127); sentinela -1.0 → -127)
 *   vectors-i16.bin  — N × 14 shorts (round(v × 10000), little-endian)
 *   labels.bin       — bitset empacotado (bit i = 1 se fraude)
 *   meta.properties  — count, scale_i8, scale_i16, dims
 *
 * Por que int8 com scale=127 e não int8 com min/max por dimensão?
 *   Os vetores normalizados já vivem em [-1, 1] (com sentinela exato em -1.0).
 *   Multiplicar por 127 mapeia [-1, 1] → [-127, 127], dentro do range de byte.
 *   O sentinela -1.0 vira -127 — distinguível dos valores normais (> -127).
 *   Isso preserva a semântica sem precisar de tabela de parâmetros por dimensão.
 *
 * Por que int16 com scale=10000?
 *   Mapeia [-1, 1] → [-10000, 10000], dentro do range de short (±32767).
 *   Preserva ~4 casas decimais — precisão efetivamente equivalente ao float32
 *   para os valores presentes no dataset.
 *
 * A leitura é completamente em streaming: apenas 14 floats de um registro
 * estão em memória por vez. Pico de heap < 2 MB além do overhead do Jackson.
 *
 * Uso: java ...QuantizedDatasetBuilder <references.json.gz> <outputDir>
 */
public final class QuantizedDatasetBuilder {

    static final int DIMS      = 14;
    static final int SCALE_I8  = 127;
    static final int SCALE_I16 = 10_000;

    private static final int LOG_EVERY = 500_000;

    /** Controla quais representações quantizadas serão gravadas. */
    public enum Types { I8, I16, ALL }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Uso: QuantizedDatasetBuilder <references.json.gz> <outputDir> [--types i8|i16|all]");
            System.exit(1);
        }
        Types types = Types.ALL;
        for (int i = 2; i < args.length - 1; i++) {
            if ("--types".equals(args[i])) {
                types = switch (args[i + 1].toLowerCase()) {
                    case "i8"  -> Types.I8;
                    case "i16" -> Types.I16;
                    default    -> Types.ALL;
                };
            }
        }
        build(Path.of(args[0]), Path.of(args[1]), types);
    }

    /** Atalho que mantém compatibilidade com chamadas internas existentes (gera tudo). */
    public static void build(Path input, Path outDir) throws Exception {
        build(input, outDir, Types.ALL);
    }

    public static void build(Path input, Path outDir, Types types) throws Exception {
        Files.createDirectories(outDir);

        boolean writeI8  = types == Types.I8  || types == Types.ALL;
        boolean writeI16 = types == Types.I16 || types == Types.ALL;

        Path i8Path     = outDir.resolve("vectors-i8.bin");
        Path i16Path    = outDir.resolve("vectors-i16.bin");
        Path labelsPath = outDir.resolve("labels.bin");
        Path metaPath   = outDir.resolve("meta.properties");

        long t0 = System.currentTimeMillis();
        System.out.println("[builder-q] lendo " + input.toAbsolutePath()
                + "  types=" + types);

        ObjectMapper mapper = new ObjectMapper();
        byte[] i8Row  = new byte[DIMS];
        byte[] i16Row = new byte[DIMS * 2];
        BitSet labels = new BitSet();
        int count = 0;

        // NullOutputStream descarta bytes quando o tipo não é solicitado
        OutputStream devNull = OutputStream.nullOutputStream();

        try (InputStream raw     = Files.newInputStream(input);
             InputStream gz      = new GZIPInputStream(new BufferedInputStream(raw, 1 << 20));
             JsonParser jp       = mapper.getFactory().createParser(gz);
             OutputStream i8Out  = writeI8  ? new BufferedOutputStream(Files.newOutputStream(i8Path),  1 << 20) : devNull;
             OutputStream i16Out = writeI16 ? new BufferedOutputStream(Files.newOutputStream(i16Path), 1 << 20) : devNull) {

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

                encodeI8(floats, i8Row);
                i8Out.write(i8Row);
                encodeI16LE(floats, i16Row);
                i16Out.write(i16Row);

                if (isFraud) labels.set(count);
                count++;
                if (count % LOG_EVERY == 0) {
                    System.out.printf("[builder-q] %d registros (%.1fs)%n",
                            count, (System.currentTimeMillis() - t0) / 1000.0);
                }
            }
        }

        // labels.bin — bits empacotados em byte order little-endian
        byte[] labelBytes = new byte[(count + 7) / 8];
        for (int i = 0; i < count; i++) {
            if (labels.get(i)) labelBytes[i / 8] |= (byte) (1 << (i % 8));
        }
        Files.write(labelsPath, labelBytes);

        Files.writeString(metaPath,
                "count=" + count + "\n"
              + "scale_i8=" + SCALE_I8 + "\n"
              + "scale_i16=" + SCALE_I16 + "\n"
              + "dims=" + DIMS + "\n");

        System.out.printf("[builder-q] OK: %d vetores em %.1fs%n",
                count, (System.currentTimeMillis() - t0) / 1000.0);
        if (writeI8)  System.out.printf("[builder-q] i8 : %d MB%n",  Files.size(i8Path)  / (1024 * 1024));
        if (writeI16) System.out.printf("[builder-q] i16: %d MB%n",  Files.size(i16Path) / (1024 * 1024));
        System.out.printf("[builder-q] labels: %d KB%n", Files.size(labelsPath) / 1024);
    }

    /** round(v × 127), clamp em [-127, 127]. Sentinela -1.0 → -127. */
    static void encodeI8(float[] src, byte[] dst) {
        for (int d = 0; d < DIMS; d++) {
            int q = Math.round(src[d] * SCALE_I8);
            if (q < -127) q = -127;
            if (q > 127)  q = 127;
            dst[d] = (byte) q;
        }
    }

    /** round(v × 10000), clamp em [Short.MIN_VALUE, Short.MAX_VALUE], little-endian. */
    static void encodeI16LE(float[] src, byte[] dst) {
        for (int d = 0; d < DIMS; d++) {
            int q = Math.round(src[d] * SCALE_I16);
            if (q < Short.MIN_VALUE) q = Short.MIN_VALUE;
            if (q > Short.MAX_VALUE) q = Short.MAX_VALUE;
            short s = (short) q;
            dst[d * 2]     = (byte)  (s & 0xFF);
            dst[d * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
    }
}
