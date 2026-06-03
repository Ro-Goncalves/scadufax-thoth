package br.com.rgbrainlabs.scadufaxthoth.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EuclideanDistanceCalculatorTest {

    private static final ValueLayout.OfFloat  JAVA_FLOAT_BE          = ValueLayout.JAVA_FLOAT.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort JAVA_SHORT_LE_UNALIGNED = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final EuclideanDistanceCalculator calculator = new EuclideanDistanceCalculator();

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("provideTestCases")
    @DisplayName("Deve calcular a distância euclidiana ao quadrado corretamente")
    void testCalculateSquaredDistance(String description, float[] queryVector, float[] storedVector, double expectedSquaredDistance) {
        int dimensions = queryVector.length;

        // 1. Arena.ofConfined() cria um espaço de memória nativa que morre assim que o bloco try/catch termina
        try (Arena arena = Arena.ofConfined()) {
            
            // 2. Alocamos o tamanho exato de bytes para o vetor armazenado (dimensões * 4 bytes)
            MemorySegment simulatedSegment = arena.allocate((long) dimensions * 4);

            // 3. Preenchemos o nosso segmento simulado com os dados do 'storedVector' usando BIG_ENDIAN
            long writeOffset = 0;
            for (float val : storedVector) {
                simulatedSegment.set(JAVA_FLOAT_BE, writeOffset, val);
                writeOffset += 4;
            }

            // 4. Executamos o método da nossa calculadora
            // Passamos offset 0, pois o segmento alocado só contém este vetor
            double actualDistance = calculator.calculate(queryVector, simulatedSegment, 0, dimensions);

            // 5. Validação!
            // Usamos um 'delta' (0.0001) porque operações com ponto flutuante podem ter imprecisões milimétricas
            assertEquals(expectedSquaredDistance, actualDistance, 0.0001, 
                    "Falha no cenário: " + description);
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("provideI8TestCases")
    @DisplayName("Deve calcular distância euclidiana ao quadrado para int8")
    void testCalculateI8(String description, byte[] query, byte[] stored, double expected) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate(stored.length);
            for (int i = 0; i < stored.length; i++) {
                segment.set(ValueLayout.JAVA_BYTE, i, stored[i]);
            }
            double actual = calculator.calculateI8(query, segment, 0, stored.length);
            assertEquals(expected, actual, 0.0, description);
        }
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
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment segment = arena.allocate((long) stored.length * 2);
            for (int i = 0; i < stored.length; i++) {
                segment.set(JAVA_SHORT_LE_UNALIGNED, (long) i * 2, stored[i]);
            }
            double actual = calculator.calculateI16(query, segment, 0, stored.length);
            assertEquals(expected, actual, 0.0, description);
        }
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