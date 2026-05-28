package br.com.rgbrainlabs.scadufaxthoth.bootstrap;

import br.com.rgbrainlabs.scadufaxthoth.config.AppConfig;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

public final class JavalinBootstrap {

    private final AppConfig config;

    public JavalinBootstrap(AppConfig config) {
        this.config = config;
    }

    public Javalin start() {
        return create().start(config.port());
    }

    public Javalin create() {
        return Javalin.create(javalinConfig -> {
            javalinConfig.concurrency.useVirtualThreads = true;
            javalinConfig.startup.showJavalinBanner = false;
            javalinConfig.jsonMapper(new JavalinJackson().updateMapper(mapper -> {
                mapper.findAndRegisterModules();
                mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            }));
        });
    }
}