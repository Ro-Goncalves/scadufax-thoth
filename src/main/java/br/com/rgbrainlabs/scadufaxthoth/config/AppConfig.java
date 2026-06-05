package br.com.rgbrainlabs.scadufaxthoth.config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record AppConfig(
        int port,
        String datasetPath,
        String dataDir,
        String v2ArtifactPath,
        Map<String, Float> normalizationMap,
        Map<String, Float> mccRiskMap,
        int    nprobe,
        int    kNeighbors,
        double fraudThreshold
) {
    private static final String DEFAULT_PORT             = "9999";
    private static final String DEFAULT_DATASET_PATH     = "dataset.bin";
    private static final String DEFAULT_DATA_DIR         = "./data";
    private static final String DEFAULT_V2_ARTIFACT      = "./data/index.v2";
    private static final String DEFAULT_NPROBE           = "8";
    private static final String DEFAULT_K_NEIGHBORS      = "5";
    private static final String DEFAULT_FRAUD_THRESHOLD  = "0.6";

    // Captura "key": number — suporta inteiros e floats no formato dos dois JSONs de recursos.
    static final Pattern ENTRY_PATTERN =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*([0-9.eE+\\-]+)");

    public static AppConfig fromEnvironment() {
        int    port           = Integer.parseInt(System.getenv().getOrDefault("PORT",             DEFAULT_PORT));
        String datasetPath    = System.getenv().getOrDefault("DATASET_PATH",   DEFAULT_DATASET_PATH);
        String dataDir        = System.getenv().getOrDefault("DATA_DIR",        DEFAULT_DATA_DIR);
        String v2Path         = System.getenv().getOrDefault("V2_ARTIFACT_PATH", DEFAULT_V2_ARTIFACT);
        int    nprobe         = Integer.parseInt(System.getenv().getOrDefault("NPROBE",           DEFAULT_NPROBE));
        int    kNeighbors     = Integer.parseInt(System.getenv().getOrDefault("K_NEIGHBORS",      DEFAULT_K_NEIGHBORS));
        double fraudThreshold = Double.parseDouble(System.getenv().getOrDefault("FRAUD_THRESHOLD", DEFAULT_FRAUD_THRESHOLD));

        Map<String, Float> normMap = loadMapFromJar("/normalization.json");
        Map<String, Float> mccMap  = loadMapFromJar("/mcc_risk.json");

        return new AppConfig(port, datasetPath, dataDir, v2Path, normMap, mccMap,
                             nprobe, kNeighbors, fraudThreshold);
    }

    static Map<String, Float> parseFloatMap(String json) {
        Map<String, Float> map = new LinkedHashMap<>();
        Matcher m = ENTRY_PATTERN.matcher(json);
        while (m.find()) {
            map.put(m.group(1), Float.parseFloat(m.group(2)));
        }
        return map;
    }

    private static Map<String, Float> loadMapFromJar(String resourcePath) {
        try (InputStream is = AppConfig.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Arquivo não encontrado no JAR: " + resourcePath);
            }
            return parseFloatMap(new String(is.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Falha catastrófica ao carregar " + resourcePath, e);
        }
    }
}
