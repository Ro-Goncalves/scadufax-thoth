package br.com.rgbrainlabs.scadufaxthoth;

import br.com.rgbrainlabs.scadufaxthoth.bootstrap.JavalinBootstrap;
import br.com.rgbrainlabs.scadufaxthoth.config.AppConfig;

public final class ScadufaxThothApplication {

    private ScadufaxThothApplication() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();
        new JavalinBootstrap(config).start();
    }
}