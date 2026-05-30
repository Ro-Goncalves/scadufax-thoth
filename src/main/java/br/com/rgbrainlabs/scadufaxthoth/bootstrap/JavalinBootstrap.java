package br.com.rgbrainlabs.scadufaxthoth.bootstrap;

import br.com.rgbrainlabs.scadufaxthoth.config.AppConfig;
import br.com.rgbrainlabs.scadufaxthoth.search.DistanceCalculator;
import br.com.rgbrainlabs.scadufaxthoth.search.EuclideanDistanceCalculator;
import br.com.rgbrainlabs.scadufaxthoth.search.MmapBruteForceSearcher;
import br.com.rgbrainlabs.scadufaxthoth.search.TransactionVectorizer;
import br.com.rgbrainlabs.scadufaxthoth.search.VectorSearcher;
import br.com.rgbrainlabs.scadufaxthoth.web.ReadyHandler;
import br.com.rgbrainlabs.scadufaxthoth.web.SearchHandler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

import java.lang.foreign.Arena;

public final class JavalinBootstrap {

    private final AppConfig config;

    public JavalinBootstrap(AppConfig config) {
        this.config = config;
    }

    public Javalin start() throws Exception {
        return create().start(config.port());
    }

    public Javalin create() throws Exception {

        Arena globalArena = Arena.ofShared();
        DistanceCalculator calculator = new EuclideanDistanceCalculator();
        VectorSearcher searcher = new MmapBruteForceSearcher(config.datasetPath(), globalArena, calculator);
        TransactionVectorizer vectorizer = new TransactionVectorizer(config.normalizationMap(), config.mccRiskMap());
        
        SearchHandler searchHandler = new SearchHandler(searcher, vectorizer);
        ReadyHandler readyHandler = new ReadyHandler();

        return Javalin.create(javalinConfig -> {
            javalinConfig.concurrency.useVirtualThreads = true;
            javalinConfig.startup.showJavalinBanner = false;

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