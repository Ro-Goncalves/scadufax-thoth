package br.com.rgbrainlabs.scadufaxthoth;

import br.com.rgbrainlabs.scadufaxthoth.bootstrap.HttpServerBootstrap;
import br.com.rgbrainlabs.scadufaxthoth.config.AppConfig;

public final class ScadufaxThothApplication {

    private ScadufaxThothApplication() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.fromEnvironment();
        new HttpServerBootstrap(config).start();
    }
}