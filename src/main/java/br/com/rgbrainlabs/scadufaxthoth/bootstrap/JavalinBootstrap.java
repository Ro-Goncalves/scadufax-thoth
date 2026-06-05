package br.com.rgbrainlabs.scadufaxthoth.bootstrap;

import br.com.rgbrainlabs.scadufaxthoth.config.AppConfig;
import br.com.rgbrainlabs.scadufaxthoth.search.EuclideanDistanceCalculator;
import br.com.rgbrainlabs.scadufaxthoth.search.V2IndexSearcher;
import br.com.rgbrainlabs.scadufaxthoth.web.FraudRequestParser;
import br.com.rgbrainlabs.scadufaxthoth.web.PreSerializedResponseTable;
import br.com.rgbrainlabs.scadufaxthoth.web.ReadyHandler;
import br.com.rgbrainlabs.scadufaxthoth.web.SearchHandler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.javalin.Javalin;

import java.io.IOException;
import java.nio.file.Path;

public final class JavalinBootstrap {

    private final AppConfig config;

    public JavalinBootstrap(AppConfig config) {
        this.config = config;
    }

    public Javalin start() throws IOException {
        return create().start(config.port());
    }

    public Javalin create() throws IOException {
        V2IndexSearcher searcher = new V2IndexSearcher(
                Path.of(config.v2ArtifactPath()),
                new EuclideanDistanceCalculator(),
                config.nprobe());

        FraudRequestParser parser = new FraudRequestParser(
                config.normalizationMap(), config.mccRiskMap());

        searcher.prewarm();
        WarmupService.warmup(searcher, parser);

        // sharedMapper: usado apenas pelo PreSerializedResponseTable no boot (cold path)
        ObjectMapper sharedMapper = new ObjectMapper();
        sharedMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        sharedMapper.findAndRegisterModules();
        sharedMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        PreSerializedResponseTable responseTable = new PreSerializedResponseTable(
                config.kNeighbors(), config.fraudThreshold(), sharedMapper);

        SearchHandler searchHandler = new SearchHandler(searcher, parser, responseTable);
        ReadyHandler readyHandler = new ReadyHandler();

        // JavalinJackson não é registrado como mapper HTTP — ctx.bodyAsClass() não é
        // mais usado; FraudRequestParser parseia os bytes diretamente no hot path.
        return Javalin.create(javalinConfig -> {
            javalinConfig.concurrency.useVirtualThreads = true;
            javalinConfig.startup.showJavalinBanner = false;
            javalinConfig.events.serverStopped(searcher::close);

            javalinConfig.routes.post("/fraud-score", searchHandler);
            javalinConfig.routes.get("/ready", readyHandler);
        });
    }
}
