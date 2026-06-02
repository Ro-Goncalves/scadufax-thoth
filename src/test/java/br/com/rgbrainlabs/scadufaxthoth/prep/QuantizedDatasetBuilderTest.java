package br.com.rgbrainlabs.scadufaxthoth.prep;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class QuantizedDatasetBuilderTest {

    // ── encodeI8 ─────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("provideEncodeI8Cases")
    void testEncodeI8(String desc, float input, byte expected) {
        float[] src = new float[QuantizedDatasetBuilder.DIMS];
        src[0] = input;
        byte[] dst = new byte[QuantizedDatasetBuilder.DIMS];
        QuantizedDatasetBuilder.encodeI8(src, dst);
        assertEquals(expected, dst[0], desc);
    }

    private static Stream<Arguments> provideEncodeI8Cases() {
        return Stream.of(
            Arguments.of("zero",              0.0f,  (byte)    0),
            Arguments.of("máximo +1.0",       1.0f,  (byte)  127),
            Arguments.of("sentinela -1.0",   -1.0f,  (byte) -127),
            Arguments.of("+0.5 → round 64",   0.5f,  (byte)   64),  // round(63.5) = 64
            Arguments.of("-0.5 → round -63", -0.5f,  (byte)  -63),  // Math.round(-63.5f) = -63 (Java rounds half-up)
            Arguments.of("clamp superior",    2.0f,  (byte)  127),
            Arguments.of("clamp inferior",   -2.0f,  (byte) -127)
        );
    }

    // ── encodeI16LE ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("provideEncodeI16Cases")
    void testEncodeI16LE(String desc, float input, byte lo, byte hi) {
        float[] src = new float[QuantizedDatasetBuilder.DIMS];
        src[0] = input;
        byte[] dst = new byte[QuantizedDatasetBuilder.DIMS * 2];
        QuantizedDatasetBuilder.encodeI16LE(src, dst);
        assertEquals(lo, dst[0], desc + " byte lo");
        assertEquals(hi, dst[1], desc + " byte hi");
    }

    private static Stream<Arguments> provideEncodeI16Cases() {
        return Stream.of(
            // 10000 = 0x2710 LE → [0x10, 0x27]
            Arguments.of("zero",  0.0f,  (byte) 0x00, (byte) 0x00),
            Arguments.of("+1.0",  1.0f,  (byte) 0x10, (byte) 0x27),
            // -10000 = 0xD8F0 LE → [0xF0, 0xD8]
            Arguments.of("-1.0", -1.0f,  (byte) 0xF0, (byte) 0xD8),
            // 5000 = 0x1388 LE → [0x88, 0x13]
            Arguments.of("+0.5",  0.5f,  (byte) 0x88, (byte) 0x13)
        );
    }

    // ── build() integração ───────────────────────────────────────────────────

    @Test
    void testBuildProducesArtefatosCorretos(@TempDir Path outDir) throws Exception {
        // 3 registros: fraud (1.0), legitimate (0.5), fraud (-1.0)
        Path gz = outDir.resolve("test.json.gz");
        try (OutputStream os = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(gz)))) {
            os.write(buildJson(
                new float[]{1.0f},   "fraud",
                new float[]{0.5f},   "legitimate",
                new float[]{-1.0f},  "fraud"
            ).getBytes());
        }

        QuantizedDatasetBuilder.build(gz, outDir, QuantizedDatasetBuilder.Types.ALL);

        // meta.properties
        String meta = Files.readString(outDir.resolve("meta.properties"));
        assertTrue(meta.contains("count=3"));
        assertTrue(meta.contains("dims=14"));
        assertTrue(meta.contains("scale_i8=127"));
        assertTrue(meta.contains("scale_i16=10000"));

        // tamanhos
        assertEquals(3L * 14,       Files.size(outDir.resolve("vectors-i8.bin")));
        assertEquals(3L * 14 * 2,   Files.size(outDir.resolve("vectors-i16.bin")));
        assertEquals(1L,            Files.size(outDir.resolve("labels.bin")));

        // primeiro byte do i8: 1.0 × 127 = 127
        byte[] i8 = Files.readAllBytes(outDir.resolve("vectors-i8.bin"));
        assertEquals((byte) 127, i8[0]);

        // labels: bit0=1(fraud), bit1=0(legit), bit2=1(fraud) → 0b00000101 = 5
        byte[] labels = Files.readAllBytes(outDir.resolve("labels.bin"));
        assertEquals((byte) 0b00000101, labels[0]);
    }

    /**
     * Monta um array JSON com três registros onde apenas a primeira dimensão
     * é especificada; as 13 restantes são zero.
     */
    private static String buildJson(float[] first, String l1, float[] second, String l2, float[] third, String l3) {
        StringBuilder sb = new StringBuilder("[");
        appendRecord(sb, first[0],  l1); sb.append(",");
        appendRecord(sb, second[0], l2); sb.append(",");
        appendRecord(sb, third[0],  l3);
        sb.append("]");
        return sb.toString();
    }

    private static void appendRecord(StringBuilder sb, float firstDim, String label) {
        sb.append("{\"label\":\"").append(label).append("\",\"vector\":[");
        sb.append(firstDim);
        for (int i = 1; i < QuantizedDatasetBuilder.DIMS; i++) sb.append(",0.0");
        sb.append("]}");
    }
}
