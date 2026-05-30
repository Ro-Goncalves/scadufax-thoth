package br.com.rgbrainlabs.scadufaxthoth.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransactionRequest(
        String id,
        TransactionRequest.TransactionData transaction,
        TransactionRequest.CustomerData customer,
        TransactionRequest.MerchantData merchant,
        TransactionRequest.TerminalData terminal,
        TransactionRequest.LastTransactionData lastTransaction // Pode vir nulo no JSON!
) {

    public static record TransactionData(
            double amount,
            int installments,
            String requestedAt
    ) {}

    public static record CustomerData(
            double avgAmount,
            @JsonProperty("tx_count_24h")
            int txCount24h,
            List<String> knownMerchants
    ) {}

    public static record MerchantData(
            String id,
            String mcc,
            double avgAmount
    ) {}

    public static record TerminalData(
            boolean isOnline,
            boolean cardPresent,
            double kmFromHome
    ) {}

    public static record LastTransactionData(
            String timestamp,
            double kmFromCurrent
    ) {}
}