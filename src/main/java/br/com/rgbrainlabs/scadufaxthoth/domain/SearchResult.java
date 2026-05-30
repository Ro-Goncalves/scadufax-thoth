package br.com.rgbrainlabs.scadufaxthoth.domain;

public record SearchResult(
    String label, 
    double distance
) implements Comparable<SearchResult> {
    @Override
    public int compareTo(SearchResult o) {
        // Invertido propositalmente para PriorityQueue funcionar como Max-Heap
        return Double.compare(o.distance, this.distance);
    }
}