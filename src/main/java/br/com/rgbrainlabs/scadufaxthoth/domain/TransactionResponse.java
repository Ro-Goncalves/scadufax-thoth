package br.com.rgbrainlabs.scadufaxthoth.domain;

public record TransactionResponse(
    boolean approved, 
    double fraud_score
) {
}
