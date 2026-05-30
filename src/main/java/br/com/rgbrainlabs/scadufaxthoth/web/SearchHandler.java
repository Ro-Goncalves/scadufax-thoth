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
    private static final int K_NEIGHBORS = 5;
    private static final double FRAUD_THRESHOLD = 0.6;
    
    public SearchHandler(VectorSearcher searcher, TransactionVectorizer vectorizer) {
        this.searcher = searcher;
        this.vectorizer = vectorizer;
    }

    @Override
    public void handle(Context ctx) throws Exception {       
        TransactionRequest request = ctx.bodyAsClass(TransactionRequest.class);
       
        float[] queryVector = vectorizer.vectorize(request);
      
        List<SearchResult> top5 = searcher.search(queryVector, K_NEIGHBORS);
    
        int fraudCount = 0;
        for (int i = 0; i < top5.size(); i++) {
            if ("fraud".equals(top5.get(i).label())) {
                fraudCount++;
            }
        }
       
        double fraudScore = (double) fraudCount / K_NEIGHBORS;
        boolean approved = fraudScore < FRAUD_THRESHOLD;
      
        ctx.json(new TransactionResponse(approved, fraudScore));
    }
}