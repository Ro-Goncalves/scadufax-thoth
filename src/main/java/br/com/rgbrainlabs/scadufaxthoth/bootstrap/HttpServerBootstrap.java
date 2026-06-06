package br.com.rgbrainlabs.scadufaxthoth.bootstrap;

import br.com.rgbrainlabs.scadufaxthoth.config.AppConfig;
import br.com.rgbrainlabs.scadufaxthoth.search.EuclideanDistanceCalculator;
import br.com.rgbrainlabs.scadufaxthoth.search.V2IndexSearcher;
import br.com.rgbrainlabs.scadufaxthoth.web.FraudRequestParser;
import br.com.rgbrainlabs.scadufaxthoth.web.NioHttpServer;
import br.com.rgbrainlabs.scadufaxthoth.web.PreSerializedResponseTable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Sobe o índice, pagina o mmap e roda o {@link NioHttpServer} na thread
 * chamadora. O reactor só passa a aceitar conexões depois do prewarm, de modo
 * que {@code /ready} (e todo o resto) só responde com o índice já residente.
 *
 * <p>Nota: o antigo {@code WarmupService} existia para forçar a compilação JIT
 * (C2) do hot path. Sob GraalVM Native Image (AOT) não há JIT — o warmup é inócuo
 * e foi removido do caminho. O {@code prewarm} (page-in do índice) permanece, pois
 * seu ganho (evitar page faults frios) independe de JIT.
 */
public final class HttpServerBootstrap {

    private final AppConfig config;

    public HttpServerBootstrap(AppConfig config) {
        this.config = config;
    }

    /** Bloqueia: a thread chamadora vira a thread do reactor. */
    public void start() throws IOException {
        V2IndexSearcher searcher = new V2IndexSearcher(
                Path.of(config.v2ArtifactPath()),
                new EuclideanDistanceCalculator(),
                config.nprobe());

        FraudRequestParser parser = new FraudRequestParser(
                config.normalizationMap(), config.mccRiskMap());

        searcher.prewarm();

        PreSerializedResponseTable responses = new PreSerializedResponseTable(
                config.kNeighbors(), config.fraudThreshold());

        NioHttpServer server = new NioHttpServer(config.port(), searcher, parser, responses);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.close(); } catch (IOException ignored) { }
            searcher.close();
        }));
        server.run();
    }
}
