package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import java.util.List;

public interface VectorSearcher {
    List<SearchResult> search(float[] queryVector, int k);
}