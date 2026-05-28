package br.com.rgbrainlabs.scadufaxthoth.config;

public record AppConfig(int port) {

    private static final String DEFAULT_PORT = "9999";

    public static AppConfig fromEnvironment() {
        return new AppConfig(Integer.parseInt(System.getenv().getOrDefault("PORT", DEFAULT_PORT)));
    }
}