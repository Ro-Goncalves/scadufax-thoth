package br.com.rgbrainlabs.scadufaxthoth.bootstrap;

import br.com.rgbrainlabs.scadufaxthoth.config.AppConfig;
import br.com.rgbrainlabs.scadufaxthoth.search.EuclideanDistanceCalculator;
import br.com.rgbrainlabs.scadufaxthoth.search.TransactionVectorizer;
import br.com.rgbrainlabs.scadufaxthoth.search.V2IndexSearcher;
import br.com.rgbrainlabs.scadufaxthoth.web.ReadyHandler;
import br.com.rgbrainlabs.scadufaxthoth.web.SearchHandler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

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

        TransactionVectorizer vectorizer = new TransactionVectorizer(
                config.normalizationMap(), config.mccRiskMap());

        searcher.prewarm();
        WarmupService.warmup(searcher, vectorizer);

        SearchHandler searchHandler = new SearchHandler(
                searcher, vectorizer, config.kNeighbors(), config.fraudThreshold());
        ReadyHandler readyHandler = new ReadyHandler();

        return Javalin.create(javalinConfig -> {
            javalinConfig.concurrency.useVirtualThreads = true;
            javalinConfig.startup.showJavalinBanner = false;
            javalinConfig.events.serverStopped(searcher::close);

            javalinConfig.jsonMapper(new JavalinJackson().updateMapper(mapper -> {
                mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
                mapper.findAndRegisterModules();
                mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
                mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            }));

            javalinConfig.routes.post("/fraud-score", searchHandler);
            javalinConfig.routes.get("/ready", readyHandler);
        });
    }
}
