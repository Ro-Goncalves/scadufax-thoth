package br.com.rgbrainlabs.scadufaxthoth;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import br.com.rgbrainlabs.scadufaxthoth.prep.V2ArtifactBuilder;
import br.com.rgbrainlabs.scadufaxthoth.search.EuclideanDistanceCalculator;
import br.com.rgbrainlabs.scadufaxthoth.search.TransactionVectorizer;
import br.com.rgbrainlabs.scadufaxthoth.search.V2IndexSearcher;
import br.com.rgbrainlabs.scadufaxthoth.web.PreSerializedResponseTable;
import br.com.rgbrainlabs.scadufaxthoth.web.ReadyHandler;
import br.com.rgbrainlabs.scadufaxthoth.web.SearchHandler;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Valida a busca IVF real da Issue 02.
 *
 * O fixture usa 30 vetores em 3 grupos bem separados na dimensão 0:
 *   - Grupo A (legítimos): dim0 ≈ 0.0–0.15  → centróide próximo de 0.0
 *   - Grupo B (mistos):    dim0 ≈ 0.45–0.55  → centróide próximo de 0.5
 *   - Grupo C (fraudes):   dim0 ≈ 0.85–1.0   → centróide próximo de 1.0
 *
 * Com K-means k=3, cada grupo deve formar um cluster distinto, permitindo
 * verificar que nprobe=1 visita somente o cluster mais próximo da query.
 */
class V2IvfSearchTest {

    // 30 vetores: 10 por grupo, só dim0 varia, restantes = 0.0
    private static final String FIXTURE_JSON = buildFixtureJson();

    @Test
    void buildArtefatoMultiCluster(@TempDir Path tmpDir) throws Exception {
        Path gz       = tmpDir.resolve("fixture.json.gz");
        Path artifact = tmpDir.resolve("index.v2");
        writeGz(gz, FIXTURE_JSON);

        // K=3 clusters, 10 iterações, semente 0
        V2ArtifactBuilder.build(gz, artifact, 3, 10, 0L);

        assertTrue(Files.exists(artifact), "Artefato deve existir");
        // header(24) + 3 cluster entries×58 + 30 registros×16 (i8, pós-V4-A com bboxes)
        assertEquals(24 + 3 * V2ArtifactBuilder.CLUSTER_ENTRY_SIZE + 30 * 16L, Files.size(artifact),
                "Tamanho do artefato multi-cluster deve ser exato");
    }

    @Test
    void ivfSelecionaClustersCorretamente(@TempDir Path tmpDir) throws Exception {
        Path gz       = tmpDir.resolve("fixture.json.gz");
        Path artifact = tmpDir.resolve("index.v2");
        writeGz(gz, FIXTURE_JSON);
        V2ArtifactBuilder.build(gz, artifact, 3, 10, 0L);

        // Query com dim0 = 0.0 → deve cair no cluster do Grupo A (legítimos)
        float[] queryGrupoA = new float[V2ArtifactBuilder.DIMS]; // dim0 = 0.0, resto = 0.0

        // nprobe=1: busca só no cluster mais próximo
        try (V2IndexSearcher searcher1 = new V2IndexSearcher(artifact, new EuclideanDistanceCalculator(), 1)) {
            List<SearchResult> results1 = searcher1.search(queryGrupoA, 5);
            assertFalse(results1.isEmpty(), "IVF com nprobe=1 deve retornar resultados");
            // Todos os vizinhos devem ser legítimos (Grupo A — dim0 ≈ 0.0)
            assertTrue(results1.stream().allMatch(r -> "legitimate".equals(r.label())),
                    "Com nprobe=1 e query próxima do Grupo A, todos devem ser legítimos");
        }

        // nprobe=3: busca nos 3 clusters — mistura de legítimos e fraudes
        try (V2IndexSearcher searcher3 = new V2IndexSearcher(artifact, new EuclideanDistanceCalculator(), 3)) {
            List<SearchResult> results3 = searcher3.search(queryGrupoA, 5);
            assertFalse(results3.isEmpty(), "IVF com nprobe=3 deve retornar resultados");
        }

        // Query com dim0 = 1.0 → cluster do Grupo C (fraudes)
        float[] queryGrupoC = new float[V2ArtifactBuilder.DIMS];
        queryGrupoC[0] = 1.0f;

        try (V2IndexSearcher searcherC = new V2IndexSearcher(artifact, new EuclideanDistanceCalculator(), 1)) {
            List<SearchResult> resultsC = searcherC.search(queryGrupoC, 5);
            assertFalse(resultsC.isEmpty(), "IVF com query no Grupo C deve retornar resultados");
            assertTrue(resultsC.stream().allMatch(r -> "fraud".equals(r.label())),
                    "Com nprobe=1 e query próxima do Grupo C, todos devem ser fraudes");
        }
    }

    @Test
    void endToEndComMultiCluster(@TempDir Path tmpDir) throws Exception {
        Path gz       = tmpDir.resolve("fixture.json.gz");
        Path artifact = tmpDir.resolve("index.v2");
        writeGz(gz, FIXTURE_JSON);
        V2ArtifactBuilder.build(gz, artifact, 3, 10, 0L);

        V2IndexSearcher searcher = new V2IndexSearcher(artifact, new EuclideanDistanceCalculator(), 2);
        TransactionVectorizer vectorizer = new TransactionVectorizer(normMap(), Map.of());

        ObjectMapper testMapper = new ObjectMapper();
        testMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        testMapper.findAndRegisterModules();
        testMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        testMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        PreSerializedResponseTable responseTable = new PreSerializedResponseTable(5, 0.6, testMapper);
        SearchHandler searchHandler = new SearchHandler(searcher, vectorizer, responseTable);
        ReadyHandler  readyHandler  = new ReadyHandler();

        Javalin app = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinJackson().updateMapper(m -> {
                m.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
                m.findAndRegisterModules();
                m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            }));
            cfg.routes.post("/fraud-score", searchHandler);
            cfg.routes.get("/ready",        readyHandler);
            cfg.events.serverStopped(searcher::close);
        }).start(0);

        int port = app.port();
        HttpClient http = HttpClient.newHttpClient();

        try {
            HttpResponse<String> resp = post(http, port, payload());
            assertEquals(200, resp.statusCode(), "Deve retornar HTTP 200");
            assertTrue(resp.body().contains("approved"),    "Resposta deve ter 'approved'");
            assertTrue(resp.body().contains("fraud_score"), "Resposta deve ter 'fraud_score'");
        } finally {
            app.stop();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static HttpResponse<String> post(HttpClient client, int port, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/fraud-score"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static void writeGz(Path dest, String json) throws Exception {
        try (OutputStream os = new GZIPOutputStream(
                new BufferedOutputStream(Files.newOutputStream(dest)))) {
            os.write(json.getBytes());
        }
    }

    private static String payload() {
        return """
                {
                  "id": "tx-ivf",
                  "transaction": {"amount": 50.0, "installments": 1, "requested_at": "2024-06-01T10:00:00Z"},
                  "customer":    {"avg_amount": 200.0, "tx_count_24h": 2, "known_merchants": []},
                  "merchant":    {"id": "m1", "mcc": "5411", "avg_amount": 500.0},
                  "terminal":    {"is_online": false, "card_present": true, "km_from_home": 5.0},
                  "last_transaction": {"timestamp": "2024-06-01T09:30:00Z", "km_from_current": 1.0}
                }
                """;
    }

    private static Map<String, Float> normMap() {
        return Map.of(
                "max_amount",              10_000f,
                "max_installments",        12f,
                "amount_vs_avg_ratio",     10f,
                "max_minutes",             1440f,
                "max_km",                  1000f,
                "max_tx_count_24h",        20f,
                "max_merchant_avg_amount", 10_000f
        );
    }

    /**
     * 30 vetores em 3 grupos separados:
     *   Grupo A (10 legítimos): dim0 em {0.00, 0.02, ..., 0.18}
     *   Grupo B (10 mistos):    dim0 em {0.45, 0.47, ..., 0.63}
     *   Grupo C (10 fraudes):   dim0 em {0.82, 0.84, ..., 1.00}
     */
    private static String buildFixtureJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;

        // Grupo A — legítimos, dim0 próximo de 0.0
        for (int i = 0; i < 10; i++) {
            if (!first) sb.append(",");
            first = false;
            float dim0 = i * 0.02f; // 0.00 a 0.18
            appendRecord(sb, dim0, "legitimate");
        }
        // Grupo B — mistos, dim0 próximo de 0.5
        for (int i = 0; i < 5; i++) {
            sb.append(",");
            float dim0 = 0.45f + i * 0.02f; // 0.45 a 0.53
            appendRecord(sb, dim0, "legitimate");
        }
        for (int i = 0; i < 5; i++) {
            sb.append(",");
            float dim0 = 0.55f + i * 0.02f; // 0.55 a 0.63
            appendRecord(sb, dim0, "fraud");
        }
        // Grupo C — fraudes, dim0 próximo de 1.0
        for (int i = 0; i < 10; i++) {
            sb.append(",");
            float dim0 = 0.82f + i * 0.02f; // 0.82 a 1.00
            appendRecord(sb, dim0, "fraud");
        }

        sb.append("]");
        return sb.toString();
    }

    private static void appendRecord(StringBuilder sb, float dim0, String label) {
        sb.append("{\"label\":\"").append(label).append("\",\"vector\":[");
        sb.append(dim0);
        for (int d = 1; d < V2ArtifactBuilder.DIMS; d++) sb.append(",0.0");
        sb.append("]}");
    }
}
