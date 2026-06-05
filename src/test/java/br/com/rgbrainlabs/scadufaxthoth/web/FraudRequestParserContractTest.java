package br.com.rgbrainlabs.scadufaxthoth.web;

import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionRequest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Teste de contrato: verifica que FraudRequestParser produz o mesmo float[14]
 * que a vetorização de referência (implementada inline aqui) para todos os
 * payloads de example-payloads.json.
 *
 * Inclui explicitamente os casos com last_transaction: null (sentinela -1.0f).
 */
class FraudRequestParserContractTest {

    private static final Map<String, Float> NORM = Map.of(
            "max_amount",              10000f,
            "max_installments",           12f,
            "amount_vs_avg_ratio",        10f,
            "max_minutes",              1440f,
            "max_km",                   1000f,
            "max_tx_count_24h",           20f,
            "max_merchant_avg_amount", 10000f
    );

    private static final Map<String, Float> MCC_RISK = Map.of(
            "5411", 0.15f, "5812", 0.30f, "5912", 0.20f, "5944", 0.45f,
            "7801", 0.80f, "7802", 0.75f, "7995", 0.85f, "4511", 0.35f,
            "5311", 0.25f, "5999", 0.50f
    );

    private static final float DELTA = 1e-4f;

    private static ObjectMapper mapper;
    private static FraudRequestParser candidate;
    private static List<TransactionRequest> payloads;

    @BeforeAll
    static void setUp() throws Exception {
        mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.findAndRegisterModules();

        candidate = new FraudRequestParser(NORM, MCC_RISK);

        try (InputStream is = FraudRequestParserContractTest.class
                .getResourceAsStream("/example-payloads.json")) {
            assertNotNull(is, "example-payloads.json não encontrado no classpath");
            payloads = mapper.readValue(is, new TypeReference<>() {});
        }
        assertFalse(payloads.isEmpty(), "corpus de payloads está vazio");
    }

    @Test
    @DisplayName("Parser produz o mesmo vetor que a referência para todos os payloads")
    void parserMatchesVectorizerForAllPayloads() throws Exception {
        float[] dest = new float[14];

        for (int i = 0; i < payloads.size(); i++) {
            TransactionRequest req = payloads.get(i);

            float[] expected = vectorize(req);

            byte[] rawJson = mapper.writeValueAsBytes(req);
            candidate.parse(rawJson, rawJson.length, dest);

            final int   idx = i;
            final float[] exp = expected;
            final float[] got = Arrays.copyOf(dest, dest.length);
            assertArrayEquals(exp, got, DELTA,
                    () -> String.format(
                            "Divergência no payload %d (id=%s)%nEsperado: %s%nObtido:   %s",
                            idx, req.id(),
                            Arrays.toString(exp),
                            Arrays.toString(got)));
        }
    }

    @Test
    @DisplayName("Sentinela -1.0f nos índices 5 e 6 quando last_transaction é null")
    void sentinelForNullLastTransaction() throws Exception {
        float[] dest = new float[14];

        for (int i = 0; i < payloads.size(); i++) {
            TransactionRequest req = payloads.get(i);
            if (req.lastTransaction() != null) continue;

            byte[] rawJson = mapper.writeValueAsBytes(req);
            candidate.parse(rawJson, rawJson.length, dest);

            assertEquals(-1.0f, dest[5], DELTA,
                    () -> "Sentinela ausente em dest[5] para payload id=" + req.id());
            assertEquals(-1.0f, dest[6], DELTA,
                    () -> "Sentinela ausente em dest[6] para payload id=" + req.id());
        }
    }

    @Test
    @DisplayName("Parser aceita JSON com last_transaction como objeto (não null)")
    void vectorWithLastTransaction() throws Exception {
        float[] dest = new float[14];

        for (int i = 0; i < payloads.size(); i++) {
            TransactionRequest req = payloads.get(i);
            if (req.lastTransaction() == null) continue;

            float[] expected = vectorize(req);
            byte[] rawJson = mapper.writeValueAsBytes(req);
            candidate.parse(rawJson, rawJson.length, dest);

            assertNotEquals(-1.0f, dest[5],
                    () -> "dest[5] inesperadamente -1.0f para payload id=" + req.id());
            assertNotEquals(-1.0f, dest[6],
                    () -> "dest[6] inesperadamente -1.0f para payload id=" + req.id());

            final int   idx = i;
            final float[] exp = expected;
            final float[] got = Arrays.copyOf(dest, dest.length);
            assertArrayEquals(exp, got, DELTA,
                    () -> String.format(
                            "Divergência no payload %d (id=%s)%nEsperado: %s%nObtido:   %s",
                            idx, req.id(),
                            Arrays.toString(exp),
                            Arrays.toString(got)));
        }
    }

    // ── Referência inline (espelha FraudRequestParser.parse) ────────────────────

    private float[] vectorize(TransactionRequest req) {
        float maxAmount            = NORM.get("max_amount");
        float maxInstallments      = NORM.get("max_installments");
        float amountVsAvgRatio     = NORM.get("amount_vs_avg_ratio");
        float maxMinutes           = NORM.get("max_minutes");
        float maxKm                = NORM.get("max_km");
        float maxTxCount24h        = NORM.get("max_tx_count_24h");
        float maxMerchantAvgAmount = NORM.get("max_merchant_avg_amount");

        float[] out = new float[14];

        double txAmount          = req.transaction().amount();
        int    installments      = req.transaction().installments();
        OffsetDateTime reqTs     = OffsetDateTime.parse(req.transaction().requestedAt());
        long   reqEpochSecs      = reqTs.toEpochSecond();
        int    reqHour           = reqTs.getHour();
        int    reqWeekday        = reqTs.getDayOfWeek().getValue(); // Mon=1..Sun=7

        double customerAvgAmount = req.customer().avgAmount();
        int    txCount24h        = req.customer().txCount24h();

        String merchantId        = req.merchant().id();
        String mcc               = req.merchant().mcc();
        double merchantAvgAmount = req.merchant().avgAmount();

        boolean isOnline    = req.terminal().isOnline();
        boolean cardPresent = req.terminal().cardPresent();
        double  kmFromHome  = req.terminal().kmFromHome();

        out[0] = clamp((float)(txAmount / maxAmount));
        out[1] = clamp(installments / maxInstallments);
        double ratio = customerAvgAmount > 0 ? txAmount / customerAvgAmount : 0.0;
        out[2] = clamp((float)(ratio / amountVsAvgRatio));
        out[3] = reqHour / 23.0f;
        out[4] = (reqWeekday - 1) / 6.0f;

        if (req.lastTransaction() == null) {
            out[5] = -1.0f;
            out[6] = -1.0f;
        } else {
            OffsetDateTime lastTs  = OffsetDateTime.parse(req.lastTransaction().timestamp());
            long lastEpochSecs     = lastTs.toEpochSecond();
            long diffSecs          = reqEpochSecs - lastEpochSecs;
            long diffMins          = Math.max(0L, diffSecs / 60L);
            out[5] = clamp((float) diffMins / maxMinutes);
            out[6] = clamp((float)(req.lastTransaction().kmFromCurrent() / maxKm));
        }

        out[7]  = clamp((float)(kmFromHome / maxKm));
        out[8]  = clamp(txCount24h / maxTxCount24h);
        out[9]  = isOnline    ? 1.0f : 0.0f;
        out[10] = cardPresent ? 1.0f : 0.0f;
        out[11] = req.customer().knownMerchants().contains(merchantId) ? 0.0f : 1.0f;
        out[12] = MCC_RISK.getOrDefault(mcc, 0.5f);
        out[13] = clamp((float)(merchantAvgAmount / maxMerchantAvgAmount));

        return out;
    }

    private static float clamp(float v) {
        return Math.clamp(v, 0.0f, 1.0f);
    }
}