package br.com.rgbrainlabs.scadufaxthoth;

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
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Valida o caminho completo da Issue 01:
 *
 *   build → bootstrap → requisição HTTP
 *
 * Cobre os três critérios de aceite:
 *   1. V2ArtifactBuilder gera um artefato válido.
 *   2. V2IndexSearcher sobe e o endpoint /fraud-score responde.
 *   3. Um caso com last_transaction ausente (sentinela −128) é tratado sem erro.
 */
class V2EndToEndTest {

    // Dataset de fixture: 6 vetores, só a primeira dimensão varia.
    // Mistura de fraud e legítimo para exercitar o cálculo de fraud_score.
    //
    //   idx  | first dim | label
    //    0   |   0.0     | legitimate
    //    1   |   0.1     | legitimate
    //    2   |   0.2     | legitimate
    //    3   |   0.6     | fraud
    //    4   |   0.8     | fraud
    //    5   |   1.0     | fraud
    private static final String FIXTURE_JSON = buildFixtureJson();

    @Test
    void buildBootstrapRequisicaoHTTP(@TempDir Path tmpDir) throws Exception {
        // ── 1. Build: gera o artefato V2 ────────────────────────────────────────
        Path gz       = tmpDir.resolve("fixture.json.gz");
        Path artifact = tmpDir.resolve("index.v2");

        try (OutputStream os = new GZIPOutputStream(
                new BufferedOutputStream(Files.newOutputStream(gz)))) {
            os.write(FIXTURE_JSON.getBytes());
        }
        V2ArtifactBuilder.build(gz, artifact);

        assertTrue(Files.exists(artifact), "Artefato V2 deve existir após o build");
        // header(24) + 6 clusters×30(180) + 6 registros×16(96) = 300 bytes
        // (K-means cap: actualK = min(256, 6) = 6)
        assertEquals(24 + 6 * 30 + 6 * 16L, Files.size(artifact),
                "Tamanho do artefato deve ser exato: header + clusters + registros");

        // ── 2. Bootstrap: cria o searcher e sobe o Javalin ──────────────────────
        V2IndexSearcher searcher = new V2IndexSearcher(artifact, new EuclideanDistanceCalculator());
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
        }).start(0); // porta 0 = SO escolhe uma porta livre

        int port = app.port();
        HttpClient http = HttpClient.newHttpClient();

        try {
            // ── 3a. Requisição normal ────────────────────────────────────────────
            HttpResponse<String> resp = post(http, port, payloadComLastTransaction());
            assertEquals(200, resp.statusCode(), "Deve retornar HTTP 200");
            assertTrue(resp.body().contains("approved"),    "Resposta deve ter campo 'approved'");
            assertTrue(resp.body().contains("fraud_score"), "Resposta deve ter campo 'fraud_score'");

            // ── 3b. Caso com last_transaction ausente (sentinela −128) ───────────
            HttpResponse<String> respSentinela = post(http, port, payloadSemLastTransaction());
            assertEquals(200, respSentinela.statusCode(),
                    "Deve retornar HTTP 200 mesmo com last_transaction nulo");
            assertTrue(respSentinela.body().contains("fraud_score"),
                    "Resposta com sentinela deve ter campo 'fraud_score'");
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

    private static String payloadComLastTransaction() {
        return """
                {
                  "id": "tx-001",
                  "transaction": {"amount": 50.0, "installments": 1, "requested_at": "2024-06-01T10:00:00Z"},
                  "customer":    {"avg_amount": 200.0, "tx_count_24h": 2, "known_merchants": []},
                  "merchant":    {"id": "m1", "mcc": "5411", "avg_amount": 500.0},
                  "terminal":    {"is_online": false, "card_present": true, "km_from_home": 5.0},
                  "last_transaction": {"timestamp": "2024-06-01T09:30:00Z", "km_from_current": 1.0}
                }
                """;
    }

    private static String payloadSemLastTransaction() {
        return """
                {
                  "id": "tx-002",
                  "transaction": {"amount": 50.0, "installments": 1, "requested_at": "2024-06-01T10:00:00Z"},
                  "customer":    {"avg_amount": 200.0, "tx_count_24h": 2, "known_merchants": []},
                  "merchant":    {"id": "m1", "mcc": "5411", "avg_amount": 500.0},
                  "terminal":    {"is_online": false, "card_present": true, "km_from_home": 5.0},
                  "last_transaction": null
                }
                """;
    }

    /** Mapa de normalização com os mesmos defaults do AppConfig. */
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

    /** Monta o JSON do dataset de fixture (6 registros com 14 dimensões cada). */
    private static String buildFixtureJson() {
        float[] firstDims = {0.0f, 0.1f, 0.2f, 0.6f, 0.8f, 1.0f};
        String[]  labels    = {"legitimate", "legitimate", "legitimate", "fraud", "fraud", "fraud"};

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < firstDims.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"label\":\"").append(labels[i]).append("\",\"vector\":[");
            sb.append(firstDims[i]);
            for (int d = 1; d < V2ArtifactBuilder.DIMS; d++) sb.append(",0.0");
            sb.append("]}");
        }
        sb.append("]");
        return sb.toString();
    }
}
