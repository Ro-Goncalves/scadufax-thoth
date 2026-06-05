package br.com.rgbrainlabs.scadufaxthoth.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    private static final float TOLERANCE = 1e-6f;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void normalizationMapMatchesJackson() throws Exception {
        assertMapMatchesJackson("/normalization.json");
    }

    @Test
    void mccRiskMapMatchesJackson() throws Exception {
        assertMapMatchesJackson("/mcc_risk.json");
    }

    private void assertMapMatchesJackson(String resourcePath) throws Exception {
        Map<String, Float> expected;
        String json;
        try (InputStream is = AppConfigTest.class.getResourceAsStream(resourcePath)) {
            assertNotNull(is, "Recurso não encontrado: " + resourcePath);
            byte[] bytes = is.readAllBytes();
            json = new String(bytes, StandardCharsets.UTF_8);
            expected = MAPPER.readValue(bytes, new TypeReference<>() {});
        }

        Map<String, Float> actual = AppConfig.parseFloatMap(json);

        assertEquals(expected.keySet(), actual.keySet(),
                "Conjuntos de chaves divergem em " + resourcePath);

        for (Map.Entry<String, Float> entry : expected.entrySet()) {
            String key = entry.getKey();
            float exp = entry.getValue();
            float act = actual.get(key);
            assertEquals(exp, act, TOLERANCE,
                    "Divergência na chave '%s' de %s".formatted(key, resourcePath));
        }
    }
}
