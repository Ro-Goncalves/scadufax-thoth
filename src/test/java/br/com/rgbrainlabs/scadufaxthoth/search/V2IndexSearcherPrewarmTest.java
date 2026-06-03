package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.prep.V2ArtifactBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste leve do page pre-warmer.
 *
 * Não testa latência — apenas verifica que o aquecimento percorre o mapeamento
 * sem lançar exceção e cobre o número esperado de páginas (cobertura completa).
 */
class V2IndexSearcherPrewarmTest {

    private static final String FIXTURE_JSON = buildFixtureJson();

    @Test
    @DisplayName("prewarm percorre o artefato .v2 sem exceção e cobre todas as páginas")
    void prewarmCoversTodoArquivo(@TempDir Path tmpDir) throws Exception {
        Path gz = tmpDir.resolve("fixture.json.gz");
        Path artifact = tmpDir.resolve("index.v2");
        writeGz(gz, FIXTURE_JSON);

        // K=1: cluster único garante artefato mínimo e determinístico
        V2ArtifactBuilder.build(gz, artifact, 1, 5, 42L);

        assertTrue(Files.exists(artifact), "Artefato deve existir antes do prewarm");

        try (V2IndexSearcher searcher = new V2IndexSearcher(artifact, new EuclideanDistanceCalculator(), 1)) {
            long accesses = searcher.prewarm();

            long fileSize = Files.size(artifact);
            long expectedAccesses = (fileSize + 4095) / 4096;
            assertEquals(expectedAccesses, accesses,
                    "prewarm deve tocar exatamente ceil(tamanho/4096) páginas — cobertura completa");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /**
     * 5 vetores legítimos simples — suficientes para gerar um artefato .v2 válido com K=1.
     * Não precisamos de distribuição especial; o objetivo é ter um arquivo mapeável.
     */
    private static String buildFixtureJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 5; i++) {
            if (i > 0) sb.append(",");
            float dim0 = i * 0.1f;
            sb.append("{\"label\":\"legitimate\",\"vector\":[").append(dim0);
            for (int d = 1; d < V2ArtifactBuilder.DIMS; d++) sb.append(",0.0");
            sb.append("]}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static void writeGz(Path dest, String json) throws Exception {
        try (OutputStream os = new GZIPOutputStream(
                new BufferedOutputStream(Files.newOutputStream(dest)))) {
            os.write(json.getBytes());
        }
    }
}
