package br.com.rgbrainlabs.scadufaxthoth;

import br.com.rgbrainlabs.scadufaxthoth.prep.V2ArtifactBuilder;
import br.com.rgbrainlabs.scadufaxthoth.search.EuclideanDistanceCalculator;
import br.com.rgbrainlabs.scadufaxthoth.search.V2IndexSearcher;
import br.com.rgbrainlabs.scadufaxthoth.web.FraudRequestParser;
import br.com.rgbrainlabs.scadufaxthoth.web.NioHttpServer;
import br.com.rgbrainlabs.scadufaxthoth.web.PreSerializedResponseTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
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
 * Valida o caminho completo:
 *
 *   build → bootstrap (NioHttpServer) → requisição HTTP
 *
 * Cobre:
 *   1. V2ArtifactBuilder gera um artefato válido.
 *   2. V2IndexSearcher + NioHttpServer sobem e o endpoint /fraud-score responde.
 *   3. Um caso com last_transaction ausente (sentinela) é tratado sem erro.
 */
class V2EndToEndTest {

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
        assertEquals(24 + 6 * V2ArtifactBuilder.CLUSTER_ENTRY_SIZE + 6 * 16L, Files.size(artifact),
                "Tamanho do artefato deve ser exato: header + clusters + registros");

        // ── 2. Bootstrap: searcher + NioHttpServer numa porta livre ─────────────
        V2IndexSearcher searcher = new V2IndexSearcher(artifact, new EuclideanDistanceCalculator());
        FraudRequestParser parser = new FraudRequestParser(normMap(), Map.of());
        PreSerializedResponseTable responseTable = new PreSerializedResponseTable(5, 0.6);

        int port = freePort();
        NioHttpServer server = new NioHttpServer(port, searcher, parser, responseTable);
        Thread serverThread = new Thread(() -> {
            try { server.run(); } catch (IOException ignored) { }
        }, "nio-test-server");
        serverThread.setDaemon(true);
        serverThread.start();

        HttpClient http = HttpClient.newHttpClient();
        try {
            waitReady(http, port);

            // ── 3a. Requisição normal ────────────────────────────────────────────
            HttpResponse<String> resp = post(http, port, payloadComLastTransaction());
            assertEquals(200, resp.statusCode(), "Deve retornar HTTP 200");
            assertTrue(resp.body().contains("approved"),    "Resposta deve ter campo 'approved'");
            assertTrue(resp.body().contains("fraud_score"), "Resposta deve ter campo 'fraud_score'");

            // ── 3b. last_transaction ausente ─────────────────────────────────────
            HttpResponse<String> respSentinela = post(http, port, payloadSemLastTransaction());
            assertEquals(200, respSentinela.statusCode(),
                    "Deve retornar HTTP 200 mesmo com last_transaction nulo");
            assertTrue(respSentinela.body().contains("fraud_score"),
                    "Resposta com sentinela deve ter campo 'fraud_score'");
        } finally {
            server.close();
            searcher.close();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void waitReady(HttpClient client, int port) throws Exception {
        for (int i = 0; i < 100; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/ready"))
                        .GET().build();
                HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return;
            } catch (IOException ignored) {
                // servidor ainda subindo
            }
            Thread.sleep(50);
        }
        fail("Servidor não ficou pronto a tempo");
    }

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
