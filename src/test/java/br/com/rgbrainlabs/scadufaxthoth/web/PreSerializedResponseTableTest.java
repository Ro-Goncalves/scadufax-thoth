package br.com.rgbrainlabs.scadufaxthoth.web;

import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreSerializedResponseTableTest {

    static Stream<Arguments> provideKThresholdCases() {
        return Stream.of(
                Arguments.of(5,    0.6,  "padrão Rinha"),
                Arguments.of(10,   0.5,  "threshold exato na metade"),
                Arguments.of(1,    0.3,  "k mínimo"),
                Arguments.of(1024, 0.7,  "k grande")
        );
    }

    @ParameterizedTest(name = "[{index}] {2}")
    @MethodSource("provideKThresholdCases")
    void tabelaMatchesJacksonReference(int k, double threshold, String desc) throws Exception {
        ObjectMapper mapper = configuredMapper();
        PreSerializedResponseTable table = new PreSerializedResponseTable(k, threshold);

        for (int i = 0; i <= k; i++) {
            double score = (double) i / k;
            boolean approved = score < threshold;
            TransactionResponse actual = mapper.readValue(table.get(i), TransactionResponse.class);
            assertEquals(approved, actual.approved(),
                    "k=%d threshold=%f fraudCount=%d approved".formatted(k, threshold, i));
            assertEquals(score, actual.fraud_score(), 1e-9,
                    "k=%d threshold=%f fraudCount=%d fraud_score".formatted(k, threshold, i));
        }
    }

    private static ObjectMapper configuredMapper() {
        ObjectMapper m = new ObjectMapper();
        m.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        m.findAndRegisterModules();
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return m;
    }
}
