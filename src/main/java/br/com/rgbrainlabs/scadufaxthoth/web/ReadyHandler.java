package br.com.rgbrainlabs.scadufaxthoth.web;

import io.javalin.http.Context;
import io.javalin.http.Handler;

public class ReadyHandler implements Handler {
    @Override
    public void handle(Context ctx) throws Exception {
        ctx.status(200).result("OK");
    }    
}
