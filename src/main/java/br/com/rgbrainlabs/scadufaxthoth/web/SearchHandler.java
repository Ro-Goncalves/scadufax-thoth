package br.com.rgbrainlabs.scadufaxthoth.web;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionRequest;
import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionResponse;
import br.com.rgbrainlabs.scadufaxthoth.search.TransactionVectorizer;
import br.com.rgbrainlabs.scadufaxthoth.search.VectorSearcher;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.List;

public class SearchHandler implements Handler {

    private final VectorSearcher searcher;
    private final TransactionVectorizer vectorizer;
    private final int    kNeighbors;
    private final double fraudThreshold;

    public SearchHandler(VectorSearcher searcher, TransactionVectorizer vectorizer,
                         int kNeighbors, double fraudThreshold) {
        this.searcher        = searcher;
        this.vectorizer      = vectorizer;
        this.kNeighbors      = kNeighbors;
        this.fraudThreshold  = fraudThreshold;
    }

    @Override
    public void handle(Context ctx) throws Exception {
        TransactionRequest request = ctx.bodyAsClass(TransactionRequest.class);

        float[] queryVector = vectorizer.vectorize(request);

        List<SearchResult> topK = searcher.search(queryVector, kNeighbors);

        int fraudCount = 0;
        for (SearchResult r : topK) {
            if ("fraud".equals(r.label())) fraudCount++;
        }

        double fraudScore = (double) fraudCount / kNeighbors;
        boolean approved  = fraudScore < fraudThreshold;

        ctx.json(new TransactionResponse(approved, fraudScore));
    }
}
