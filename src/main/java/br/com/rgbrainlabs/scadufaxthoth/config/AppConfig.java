package br.com.rgbrainlabs.scadufaxthoth.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Map;

public record AppConfig(
        int port,
        String datasetPath,
        String dataDir,
        String v2ArtifactPath,
        Map<String, Float> normalizationMap,
        Map<String, Float> mccRiskMap
) {
    private static final String DEFAULT_PORT            = "9999";
    private static final String DEFAULT_DATASET_PATH    = "dataset.bin";
    private static final String DEFAULT_DATA_DIR        = "./data";
    private static final String DEFAULT_V2_ARTIFACT     = "./data/index.v2";

    public static AppConfig fromEnvironment() {
        int port          = Integer.parseInt(System.getenv().getOrDefault("PORT", DEFAULT_PORT));
        String datasetPath = System.getenv().getOrDefault("DATASET_PATH", DEFAULT_DATASET_PATH);
        String dataDir    = System.getenv().getOrDefault("DATA_DIR", DEFAULT_DATA_DIR);
        String v2Path     = System.getenv().getOrDefault("V2_ARTIFACT_PATH", DEFAULT_V2_ARTIFACT);

        ObjectMapper bootMapper = new ObjectMapper();

        Map<String, Float> normMap = loadMapFromJar(bootMapper, "/normalization.json");
        Map<String, Float> mccMap = loadMapFromJar(bootMapper, "/mcc_risk.json");

        return new AppConfig(port, datasetPath, dataDir, v2Path, normMap, mccMap);
    }

    private static Map<String, Float> loadMapFromJar(ObjectMapper mapper, String resourcePath) {        
        try (InputStream is = AppConfig.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Arquivo não encontrado no JAR: " + resourcePath);
            }
            return mapper.readValue(is, new TypeReference<Map<String, Float>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Falha catastrófica ao carregar " + resourcePath, e);
        }
    }
}