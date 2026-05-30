package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.domain.*;
import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionRequest.TransactionData;
import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionRequest.CustomerData;
import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionRequest.MerchantData;
import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionRequest.TerminalData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TransactionVectorizerTest {

    private TransactionVectorizer vectorizer;

    @BeforeEach
    void setUp() {
        // 1. Simulamos a carga do normalization.json
        Map<String, Float> normalization = Map.of(
                "max_amount", 10000f,
                "max_installments", 12f,
                "amount_vs_avg_ratio", 10f,
                "max_minutes", 1440f,
                "max_km", 1000f,
                "max_tx_count_24h", 20f,
                "max_merchant_avg_amount", 10000f
        );

        // 2. Simulamos a carga do mcc_risk.json (apenas os necessários para o teste)
        Map<String, Float> mccRisk = Map.of(
                "5411", 0.15f,
                "7802", 0.75f
        );

        // 3. Instanciamos a nossa classe a ser testada
        vectorizer = new TransactionVectorizer(normalization, mccRisk);
    }

    @Test
    @DisplayName("Deve vetorizar corretamente o exemplo LEGÍTIMO do gabarito oficial")
    void testVectorizeLegitExample() {
        // DADO (Given): O payload exato do exemplo legítimo do REGRAS_DE_DETECCAO.md
        TransactionRequest request = new TransactionRequest(
                "tx-1329056812",
                new TransactionData(41.12, 2, "2026-03-11T18:45:53Z"),
                new CustomerData(82.24, 3, List.of("MERC-003", "MERC-016")),
                new MerchantData("MERC-016", "5411", 60.25),
                new TerminalData(false, true, 29.23),
                null // last_transaction: null -> Deve acionar a regra do valor sentinela -1
        );

        // QUANDO (When): Nós vetorizamos a requisição
        float[] actualVector = vectorizer.vectorize(request);

        // ENTÃO (Then): Deve bater milimetricamente com o array do gabarito
        float[] expectedVector = {
                0.0041f,  // [0] amount
                0.1667f,  // [1] installments
                0.0500f,  // [2] amount_vs_avg
                0.7826f,  // [3] hour_of_day (18 / 23)
                0.3333f,  // [4] day_of_week (Quarta = 2 / 6)
                -1.0000f, // [5] minutes_since_last_tx (Sentinela)
                -1.0000f, // [6] km_from_last_tx (Sentinela)
                0.0292f,  // [7] km_from_home
                0.1500f,  // [8] tx_count_24h
                0.0000f,  // [9] is_online
                1.0000f,  // [10] card_present
                0.0000f,  // [11] unknown_merchant (Era conhecido, então é 0)
                0.1500f,  // [12] mcc_risk (5411 = 0.15)
                0.0060f   // [13] merchant_avg_amount
        };

        // Usamos um 'delta' de 0.0005 porque os floats do documento estão arredondados em 4 casas
        assertArrayEquals(expectedVector, actualVector, 0.0005f, 
                "O vetor gerado divergiu do gabarito oficial de transação legítima!");
    }

    @Test
    @DisplayName("Deve vetorizar corretamente o exemplo FRAUDULENTO e aplicar o Clamp nos limites")
    void testVectorizeFraudExampleWithClamp() {
        // DADO (Given): O payload exato do exemplo fraudulento do documento
        TransactionRequest request = new TransactionRequest(
                "tx-3330991687",
                new TransactionData(9505.97, 10, "2026-03-14T05:15:12Z"),
                new CustomerData(81.28, 20, List.of("MERC-008", "MERC-007", "MERC-005")),
                new MerchantData("MERC-068", "7802", 54.86),
                new TerminalData(false, true, 952.27),
                null 
        );

        // QUANDO (When)
        float[] actualVector = vectorizer.vectorize(request);

        // ENTÃO (Then)
        float[] expectedVector = {
                0.9506f,  // [0] amount (Quase no limite)
                0.8333f,  // [1] installments
                1.0000f,  // [2] amount_vs_avg (Aqui o Clamp salvou a vida! O valor puro seria 11.6)
                0.2174f,  // [3] hour_of_day (05 / 23)
                0.8333f,  // [4] day_of_week (Sábado = 5 / 6)
                -1.0000f, // [5] sentinela
                -1.0000f, // [6] sentinela
                0.9523f,  // [7] km_from_home
                1.0000f,  // [8] tx_count_24h (Bateu no teto de 20 transações, Clamp aplicou 1.0)
                0.0000f,  // [9] is_online
                1.0000f,  // [10] card_present
                1.0000f,  // [11] unknown_merchant (MERC-068 não estava na lista, então é 1.0)
                0.7500f,  // [12] mcc_risk (7802 = 0.75)
                0.0055f   // [13] merchant_avg_amount
        };

        assertArrayEquals(expectedVector, actualVector, 0.0005f, 
                "O vetor gerado divergiu do gabarito oficial de transação fraudulenta!");
    }

    @Test
    @DisplayName("Deve aplicar valor padrão (0.5) para MCC Risk desconhecido")
    void testUnknownMccRisk() {
        TransactionRequest request = new TransactionRequest(
                "tx-mock",
                new TransactionData(100.0, 1, "2026-03-11T12:00:00Z"),
                new CustomerData(100.0, 1, List.of()),
                new MerchantData("MERC-123", "9999", 100.0), // MCC 9999 NÃO existe no nosso mapa de mock!
                new TerminalData(true, false, 10.0),
                null
        );

        float[] actualVector = vectorizer.vectorize(request);

        // Índice 12 é o mcc_risk. Se não encontrou no mapa, deve injetar 0.5f
        org.junit.jupiter.api.Assertions.assertEquals(0.5f, actualVector[12], 
                "MCC desconhecido não aplicou o valor padrão (fallback) corretamente.");
    }
}