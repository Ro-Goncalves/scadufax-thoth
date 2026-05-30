package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionRequest;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

public class TransactionVectorizer {

    private final float maxAmount;
    private final float maxInstallments;
    private final float amountVsAvgRatio;
    private final float maxMinutes;
    private final float maxKm;
    private final float maxTxCount24h;
    private final float maxMerchantAvgAmount;
    private final Map<String, Float> mccRiskMap;

    public TransactionVectorizer(Map<String, Float> normalization, Map<String, Float> mccRiskMap) {
        this.maxAmount = normalization.getOrDefault("max_amount", 10000f);
        this.maxInstallments = normalization.getOrDefault("max_installments", 12f);
        this.amountVsAvgRatio = normalization.getOrDefault("amount_vs_avg_ratio", 10f);
        this.maxMinutes = normalization.getOrDefault("max_minutes", 1440f);
        this.maxKm = normalization.getOrDefault("max_km", 1000f);
        this.maxTxCount24h = normalization.getOrDefault("max_tx_count_24h", 20f);
        this.maxMerchantAvgAmount = normalization.getOrDefault("max_merchant_avg_amount", 10000f);
        this.mccRiskMap = mccRiskMap;
    }

    public float[] vectorize(TransactionRequest req) {
        float[] vector = new float[14];

        // 0: amount
        vector[0] = clamp((float) (req.transaction().amount() / maxAmount));

        // 1: installments
        vector[1] = clamp((req.transaction().installments() / maxInstallments));

        // 2: amount_vs_avg
        double avgAmount = req.customer().avgAmount();
        double amountVsAvg = avgAmount > 0 ? (req.transaction().amount() / avgAmount) : 0;
        vector[2] = clamp((float) (amountVsAvg / amountVsAvgRatio));

        // Parse do Timestamp (OffsetDateTime lida perfeitamente com o UTC em formato ISO)
        OffsetDateTime requestedAt = OffsetDateTime.parse(req.transaction().requestedAt());

        // 3: hour_of_day (0-23 dividido por 23)
        vector[3] = requestedAt.getHour() / 23.0f;

        // 4: day_of_week (seg=0, dom=6 dividido por 6)
        // O Java usa segunda=1 até domingo=7. Subtraímos 1 para casar com a regra.
        int dayOfWeek = requestedAt.getDayOfWeek().getValue() - 1;
        vector[4] = dayOfWeek / 6.0f;

        // 5 e 6: O caso especial do "last_transaction: null"
        if (req.lastTransaction() == null) {
            vector[5] = -1.0f;
            vector[6] = -1.0f;
        } else {
            OffsetDateTime lastTxTime = OffsetDateTime.parse(req.lastTransaction().timestamp());
            long minutes = Math.max(0, Duration.between(lastTxTime, requestedAt).toMinutes());
            
            vector[5] = clamp(minutes / maxMinutes);
            vector[6] = clamp((float) (req.lastTransaction().kmFromCurrent() / maxKm));
        }

        // 7: km_from_home
        vector[7] = clamp((float) (req.terminal().kmFromHome() / maxKm));

        // 8: tx_count_24h
        vector[8] = clamp((req.customer().txCount24h() / maxTxCount24h));

        // 9: is_online
        vector[9] = req.terminal().isOnline() ? 1.0f : 0.0f;

        // 10: card_present
        vector[10] = req.terminal().cardPresent() ? 1.0f : 0.0f;

        // 11: unknown_merchant (1 se NÃO estiver na lista, 0 se estiver)
        boolean isKnown = req.customer().knownMerchants() != null && 
                          req.customer().knownMerchants().contains(req.merchant().id());
        vector[11] = isKnown ? 0.0f : 1.0f;

        // 12: mcc_risk (puxa do mapa, se não existir assume 0.5)
        vector[12] = mccRiskMap.getOrDefault(req.merchant().mcc(), 0.5f);

        // 13: merchant_avg_amount
        vector[13] = clamp((float) (req.merchant().avgAmount() / maxMerchantAvgAmount));

        return vector;
    }

    /**
     * Aplica o "Clamp": Mantém o valor estritamente no intervalo [0.0, 1.0]
     */
    private float clamp(float value) {
        // Recebe: (valor, mínimo, máximo)
        return Math.clamp(value, 0.0f, 1.0f);
    }
}