package br.com.rgbrainlabs.scadufaxthoth.web;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import br.com.rgbrainlabs.scadufaxthoth.search.VectorSearcher;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.List;

public class SearchHandler implements Handler {

    private final VectorSearcher searcher;
    private final FraudRequestParser parser;
    private final PreSerializedResponseTable responseTable;

    /** Vetor de query pré-alocado por thread do pool — zero alocação no hot path. */
    private static final ThreadLocal<float[]> QUERY_VEC =
            ThreadLocal.withInitial(() -> new float[14]);

    public SearchHandler(VectorSearcher searcher, FraudRequestParser parser,
                         PreSerializedResponseTable responseTable) {
        this.searcher      = searcher;
        this.parser        = parser;
        this.responseTable = responseTable;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        byte[] body = ctx.bodyAsBytes();
        float[] queryVector = QUERY_VEC.get();

        parser.parse(body, body.length, queryVector);

        List<SearchResult> topK = searcher.search(queryVector, responseTable.k());

        int fraudCount = 0;
        for (SearchResult r : topK) {
            if ("fraud".equals(r.label())) fraudCount++;
        }

        ctx.result(responseTable.get(fraudCount)).contentType("application/json");
    }
}
