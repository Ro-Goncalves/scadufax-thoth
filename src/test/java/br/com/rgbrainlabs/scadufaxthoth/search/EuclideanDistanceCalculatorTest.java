package br.com.rgbrainlabs.scadufaxthoth.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EuclideanDistanceCalculatorTest {

    private final EuclideanDistanceCalculator calculator = new EuclideanDistanceCalculator();

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("provideTestCases")
    @DisplayName("Deve calcular a distância euclidiana ao quadrado corretamente")
    void testCalculateSquaredDistance(String description, float[] queryVector, float[] storedVector, double expectedSquaredDistance) {
        int dimensions = queryVector.length;

        // Buffer com o vetor armazenado em BIG_ENDIAN (o path float lê BE explicitamente).
        ByteBuffer buffer = ByteBuffer.allocate(dimensions * 4).order(ByteOrder.BIG_ENDIAN);
        for (float val : storedVector) {
            buffer.putFloat(val);
        }
        buffer.flip();

        double actualDistance = calculator.calculate(queryVector, buffer, 0, dimensions);

        assertEquals(expectedSquaredDistance, actualDistance, 0.0001,
                "Falha no cenário: " + description);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("provideI8TestCases")
    @DisplayName("Deve calcular distância euclidiana ao quadrado para int8")
    void testCalculateI8(String description, byte[] query, byte[] stored, double expected) {
        ByteBuffer buffer = ByteBuffer.wrap(stored.clone());
        double actual = calculator.calculateI8(query, buffer, 0, stored.length);
        assertEquals(expected, actual, 0.0, description);
    }

    private static Stream<Arguments> provideI8TestCases() {
        return Stream.of(
            Arguments.of("Distância para si mesmo",
                new byte[]{0, 25, 51}, new byte[]{0, 25, 51}, 0.0),
            Arguments.of("Vetor zero vs máximo positivo",
                new byte[]{0, 0}, new byte[]{127, 0}, 16129.0),        // 127² = 16129
            Arguments.of("Sentinela -127 preservado",
                new byte[]{-127, 0}, new byte[]{-127, 0}, 0.0),
            Arguments.of("Diferença cruzada máxima",
                new byte[]{127, -127}, new byte[]{-127, 127}, 129032.0) // 2 × 254² = 129032
        );
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("provideI16TestCases")
    @DisplayName("Deve calcular distância euclidiana ao quadrado para int16")
    void testCalculateI16(String description, short[] query, short[] stored, double expected) {
        // Buffer LITTLE_ENDIAN — o contrato de calculateI16 (encoding V2).
        ByteBuffer buffer = ByteBuffer.allocate(stored.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : stored) {
            buffer.putShort(s);
        }
        buffer.flip();
        double actual = calculator.calculateI16(query, buffer, 0, stored.length);
        assertEquals(expected, actual, 0.0, description);
    }

    private static Stream<Arguments> provideI16TestCases() {
        return Stream.of(
            Arguments.of("Distância para si mesmo",
                new short[]{0, 10000}, new short[]{0, 10000}, 0.0),
            Arguments.of("Vetor zero vs máximo positivo",
                new short[]{0}, new short[]{10000}, 100_000_000.0),     // 10000² = 100_000_000
            Arguments.of("Sentinela -10000 preservado",
                new short[]{-10000}, new short[]{-10000}, 0.0),
            Arguments.of("Diferença cruzada máxima i16",
                new short[]{10000, -10000}, new short[]{-10000, 10000}, 800_000_000.0) // 2 × 20000²
        );
    }

    /**
     * Fornece os parâmetros para o teste. Cada 'Arguments.of' é uma execução diferente.
     */
    private static Stream<Arguments> provideTestCases() {
        return Stream.of(
            // --- EDGE CASES MATEMÁTICOS ---
            
            Arguments.of("Distância para si mesmo deve ser 0",
                    new float[]{1.0f, 2.5f, 3.1f}, // Query
                    new float[]{1.0f, 2.5f, 3.1f}, // Stored
                    0.0),                          // Expected Squared Distance

            Arguments.of("Distância com números negativos e positivos cruzados",
                    new float[]{-1.0f, -1.0f},     
                    new float[]{1.0f, 1.0f},       
                    8.0), // Cálculo: (-1 - 1)^2 + (-1 - 1)^2 = 4 + 4 = 8

            Arguments.of("Triângulo de Pitágoras (Catetos 3 e 4, Hipotenusa 5)",
                    new float[]{0.0f, 0.0f},
                    new float[]{3.0f, 4.0f},
                    25.0), // Como não tiramos a raiz, a distância esperada é 5^2 = 25

            // --- CASOS DO MUNDO REAL (Baseado nos exemplos fornecidos) ---
            
            Arguments.of("Comparação real: Vetor 1 vs Vetor 2",
                    new float[]{
                        0.01f, 0.0833f, 0.05f, 0.8261f, 0.1667f, -1.0f, -1.0f, 0.0432f, 0.25f, 0.0f, 1.0f, 0.0f, 0.2f, 0.0416f
                    },
                    new float[]{
                        0.0109f, 0.1667f, 0.05f, 0.3913f, 0.6667f, 0.3007f, 0.0139f, 0.0154f, 0.2f, 0.0f, 1.0f, 0.0f, 0.15f, 0.0282f
                    },
                    3.17177) // Distância ao quadrado pré-calculada aproximada das diferenças
        );
    }
}