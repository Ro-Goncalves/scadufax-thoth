package br.com.rgbrainlabs.scadufaxthoth.web;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionRequest;
import br.com.rgbrainlabs.scadufaxthoth.search.TransactionVectorizer;
import br.com.rgbrainlabs.scadufaxthoth.search.VectorSearcher;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.List;

public class SearchHandler implements Handler {

    private final VectorSearcher searcher;
    private final TransactionVectorizer vectorizer;
    private final PreSerializedResponseTable responseTable;

    public SearchHandler(VectorSearcher searcher, TransactionVectorizer vectorizer,
                         PreSerializedResponseTable responseTable) {
        this.searcher       = searcher;
        this.vectorizer     = vectorizer;
        this.responseTable  = responseTable;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        TransactionRequest request = ctx.bodyAsClass(TransactionRequest.class);

        float[] queryVector = vectorizer.vectorize(request);

        List<SearchResult> topK = searcher.search(queryVector, responseTable.k());

        int fraudCount = 0;
        for (SearchResult r : topK) {
            if ("fraud".equals(r.label())) fraudCount++;
        }

        ctx.result(responseTable.get(fraudCount)).contentType("application/json");
    }
}
