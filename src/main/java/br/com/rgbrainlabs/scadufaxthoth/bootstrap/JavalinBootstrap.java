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
import org.eclipse.jetty.util.thread.QueuedThreadPool;

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
            // Pool pequeno de platform threads — NÃO virtual threads. O workload é
            // CPU-bound num container de 0,45 CPU / 165 MB; virtual-thread-por-requisição
            // degradou o benchmark (p99 40ms→1116ms) porque os ThreadLocal de
            // ParseState/queryVector deixam de reaproveitar e a retenção por thread
            // empilha sob carga. Com pool fixo o ThreadLocal volta a ser zero-alocação.
            // maxThreads=16 cobre a infra do Jetty + workers sem estourar os stacks de
            // platform thread no orçamento de 165 MB; minThreads=4. Afinável no benchmark.
            // Ver docs/knowledge/v4/07-postmortem-parser-virtual-threads.md.
            javalinConfig.concurrency.useVirtualThreads = false;
            javalinConfig.jetty.threadPool = new QueuedThreadPool(16, 4);
            javalinConfig.startup.showJavalinBanner = false;
            javalinConfig.events.serverStopped(searcher::close);

            javalinConfig.routes.post("/fraud-score", searchHandler);
            javalinConfig.routes.get("/ready", readyHandler);
        });
    }
}
