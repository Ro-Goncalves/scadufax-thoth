package br.com.rgbrainlabs.scadufaxthoth.web;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Parser cursor-based zero-alocação: lê os bytes do request JSON e escreve
 * o vetor float[14] diretamente no array passado pelo caller, fundindo parse
 * e vetorização em uma única passagem.
 *
 * Após aquecimento JIT, o caminho quente não aloca nenhum objeto Java por
 * requisição. O estado mutável (buffers de scratch, acumuladores de timestamp)
 * vive num ThreadLocal<ParseState> inicializado uma vez por thread do pool.
 *
 * Restrição: IDs de merchant com mais de 8 bytes são comparados apenas pelos
 * primeiros 8 bytes (empacotamento big-endian em long). Para o corpus do
 * projeto (IDs no formato "MERC-NNN" com 8 chars), não há colisões.
 */
public final class FraudRequestParser {

    // ── Constantes de normalização (imutáveis após construção) ──────────────

    private final float maxAmount;
    private final float maxInstallments;
    private final float amountVsAvgRatio;
    private final float maxMinutes;
    private final float maxKm;
    private final float maxTxCount24h;
    private final float maxMerchantAvgAmount;

    // ── Tabela MCC como arrays paralelos (sem Map por requisição) ───────────

    private final int[]   mccKeys;
    private final float[] mccValues;
    private final int     mccCount;

    // ── Nomes de campos como bytes estáticos (sem new String por comparação) ─

    private static final byte[] K_TRANSACTION      = b("transaction");
    private static final byte[] K_CUSTOMER         = b("customer");
    private static final byte[] K_MERCHANT         = b("merchant");
    private static final byte[] K_TERMINAL         = b("terminal");
    private static final byte[] K_LAST_TRANSACTION = b("last_transaction");
    private static final byte[] K_AMOUNT           = b("amount");
    private static final byte[] K_INSTALLMENTS     = b("installments");
    private static final byte[] K_REQUESTED_AT     = b("requested_at");
    private static final byte[] K_AVG_AMOUNT       = b("avg_amount");
    private static final byte[] K_TX_COUNT_24H     = b("tx_count_24h");
    private static final byte[] K_KNOWN_MERCHANTS  = b("known_merchants");
    private static final byte[] K_ID               = b("id");
    private static final byte[] K_MCC              = b("mcc");
    private static final byte[] K_IS_ONLINE        = b("is_online");
    private static final byte[] K_CARD_PRESENT     = b("card_present");
    private static final byte[] K_KM_FROM_HOME     = b("km_from_home");
    private static final byte[] K_TIMESTAMP        = b("timestamp");
    private static final byte[] K_KM_FROM_CURRENT  = b("km_from_current");

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ── Estado thread-local (uma instância por thread do pool) ───────────────

    private static final int MAX_MERCHANTS = 32;

    private static final class ParseState {
        final double[]  dbl   = new double[1];
        final int[]     ints  = new int[1];
        final boolean[] bools = new boolean[1];
        /** ts[0]=epochSecs, ts[1]=isoWeekday (Seg=1..Dom=7), ts[2]=hora */
        final long[]    ts    = new long[3];
        /** hashes de até 32 IDs de known_merchants (primeiros 8 bytes empacotados) */
        final long[]    merch = new long[MAX_MERCHANTS];
        /** buffer scratch para bytes do nome de campo (chave JSON) */
        final byte[]    keyBuf = new byte[32];
        final int[]     keyLen = new int[1];
    }

    private static final ThreadLocal<ParseState> STATE =
            ThreadLocal.withInitial(ParseState::new);

    // ── Construtor ───────────────────────────────────────────────────────────

    public FraudRequestParser(Map<String, Float> normalization, Map<String, Float> mccRiskMap) {
        this.maxAmount            = normalization.getOrDefault("max_amount", 10000f);
        this.maxInstallments      = normalization.getOrDefault("max_installments", 12f);
        this.amountVsAvgRatio     = normalization.getOrDefault("amount_vs_avg_ratio", 10f);
        this.maxMinutes           = normalization.getOrDefault("max_minutes", 1440f);
        this.maxKm                = normalization.getOrDefault("max_km", 1000f);
        this.maxTxCount24h        = normalization.getOrDefault("max_tx_count_24h", 20f);
        this.maxMerchantAvgAmount = normalization.getOrDefault("max_merchant_avg_amount", 10000f);

        this.mccCount  = mccRiskMap.size();
        this.mccKeys   = new int[mccCount];
        this.mccValues = new float[mccCount];
        int i = 0;
        for (Map.Entry<String, Float> e : mccRiskMap.entrySet()) {
            mccKeys[i]   = packMccStr(e.getKey());
            mccValues[i] = e.getValue();
            i++;
        }
    }

    // ── Hot path ─────────────────────────────────────────────────────────────

    /**
     * Parseia {@code buf[0..len-1]} como JSON e escreve os 14 floats do vetor
     * de fraude em {@code out}. Zero alocações após aquecimento JIT.
     */
    public void parse(byte[] buf, int len, float[] out) {
        ParseState st = STATE.get();

        // acumuladores de campos brutos
        double  txAmount          = 0;
        int     installments      = 0;
        long    reqEpochSecs      = 0;
        int     reqWeekday        = 1;
        int     reqHour           = 0;
        double  customerAvgAmount = 0;
        int     txCount24h        = 0;
        int     knownMerchCount   = 0;
        long    merchantIdPack    = 0;
        int     mccPacked         = 0;
        double  merchantAvgAmount = 0;
        boolean isOnline          = false;
        boolean cardPresent       = false;
        double  kmFromHome        = 0;
        long    lastEpochSecs     = Long.MIN_VALUE; // Long.MIN_VALUE = sem last_transaction
        double  kmFromCurrent     = 0;

        int pos = skipWs(buf, 0, len);
        if (pos >= len || buf[pos] != '{') return;
        pos++; // avança o '{'

        // ── objeto raiz ─────────────────────────────────────────────────────
        while (true) {
            pos = skipWs(buf, pos, len);
            if (pos >= len || buf[pos] == '}') break;
            if (buf[pos] == ',') { pos++; continue; }
            if (buf[pos] != '"') { pos = skipValue(buf, pos, len); continue; }

            pos = readKey(buf, pos + 1, len, st.keyBuf, st.keyLen);
            int kl = st.keyLen[0];
            pos = skipColon(buf, pos, len);

            if (keyIs(st.keyBuf, kl, K_TRANSACTION)) {
                // ── transaction ─────────────────────────────────────────────
                if (pos >= len || buf[pos] != '{') { pos = skipValue(buf, pos, len); continue; }
                pos++;
                while (true) {
                    pos = skipWs(buf, pos, len);
                    if (pos >= len || buf[pos] == '}') { if (pos < len) pos++; break; }
                    if (buf[pos] == ',') { pos++; continue; }
                    if (buf[pos] != '"') { pos = skipValue(buf, pos, len); continue; }
                    pos = readKey(buf, pos + 1, len, st.keyBuf, st.keyLen);
                    kl = st.keyLen[0];
                    pos = skipColon(buf, pos, len);
                    if (keyIs(st.keyBuf, kl, K_AMOUNT)) {
                        pos = readDouble(buf, pos, len, st.dbl);
                        txAmount = st.dbl[0];
                    } else if (keyIs(st.keyBuf, kl, K_INSTALLMENTS)) {
                        pos = readInt(buf, pos, len, st.ints);
                        installments = st.ints[0];
                    } else if (keyIs(st.keyBuf, kl, K_REQUESTED_AT)) {
                        if (pos < len && buf[pos] == '"') {
                            pos = readTimestamp(buf, pos + 1, len, st.ts);
                            reqEpochSecs = st.ts[0];
                            reqWeekday   = (int) st.ts[1];
                            reqHour      = (int) st.ts[2];
                        } else {
                            pos = skipValue(buf, pos, len);
                        }
                    } else {
                        pos = skipValue(buf, pos, len);
                    }
                }

            } else if (keyIs(st.keyBuf, kl, K_CUSTOMER)) {
                // ── customer ────────────────────────────────────────────────
                if (pos >= len || buf[pos] != '{') { pos = skipValue(buf, pos, len); continue; }
                pos++;
                while (true) {
                    pos = skipWs(buf, pos, len);
                    if (pos >= len || buf[pos] == '}') { if (pos < len) pos++; break; }
                    if (buf[pos] == ',') { pos++; continue; }
                    if (buf[pos] != '"') { pos = skipValue(buf, pos, len); continue; }
                    pos = readKey(buf, pos + 1, len, st.keyBuf, st.keyLen);
                    kl = st.keyLen[0];
                    pos = skipColon(buf, pos, len);
                    if (keyIs(st.keyBuf, kl, K_AVG_AMOUNT)) {
                        pos = readDouble(buf, pos, len, st.dbl);
                        customerAvgAmount = st.dbl[0];
                    } else if (keyIs(st.keyBuf, kl, K_TX_COUNT_24H)) {
                        pos = readInt(buf, pos, len, st.ints);
                        txCount24h = st.ints[0];
                    } else if (keyIs(st.keyBuf, kl, K_KNOWN_MERCHANTS)) {
                        if (pos < len && buf[pos] == '[') {
                            pos++;
                            knownMerchCount = 0;
                            while (true) {
                                pos = skipWs(buf, pos, len);
                                if (pos >= len || buf[pos] == ']') { if (pos < len) pos++; break; }
                                if (buf[pos] == ',') { pos++; continue; }
                                if (buf[pos] == '"') {
                                    pos++;
                                    int start = pos;
                                    while (pos < len && buf[pos] != '"') pos++;
                                    int sLen = pos - start;
                                    if (pos < len) pos++; // avança closing '"'
                                    if (knownMerchCount < MAX_MERCHANTS) {
                                        st.merch[knownMerchCount++] = packMerch(buf, start, sLen);
                                    }
                                } else {
                                    pos = skipValue(buf, pos, len);
                                }
                            }
                        } else {
                            pos = skipValue(buf, pos, len);
                        }
                    } else {
                        pos = skipValue(buf, pos, len);
                    }
                }

            } else if (keyIs(st.keyBuf, kl, K_MERCHANT)) {
                // ── merchant ────────────────────────────────────────────────
                if (pos >= len || buf[pos] != '{') { pos = skipValue(buf, pos, len); continue; }
                pos++;
                while (true) {
                    pos = skipWs(buf, pos, len);
                    if (pos >= len || buf[pos] == '}') { if (pos < len) pos++; break; }
                    if (buf[pos] == ',') { pos++; continue; }
                    if (buf[pos] != '"') { pos = skipValue(buf, pos, len); continue; }
                    pos = readKey(buf, pos + 1, len, st.keyBuf, st.keyLen);
                    kl = st.keyLen[0];
                    pos = skipColon(buf, pos, len);
                    if (keyIs(st.keyBuf, kl, K_ID)) {
                        if (pos < len && buf[pos] == '"') {
                            pos++;
                            int start = pos;
                            while (pos < len && buf[pos] != '"') pos++;
                            merchantIdPack = packMerch(buf, start, pos - start);
                            if (pos < len) pos++;
                        } else {
                            pos = skipValue(buf, pos, len);
                        }
                    } else if (keyIs(st.keyBuf, kl, K_MCC)) {
                        if (pos < len && buf[pos] == '"') {
                            pos++;
                            // MCC são sempre 4 dígitos ASCII
                            if (pos + 3 < len) {
                                mccPacked = packMccBytes(buf, pos);
                                pos += 4;
                            }
                            if (pos < len && buf[pos] == '"') pos++;
                        } else {
                            pos = skipValue(buf, pos, len);
                        }
                    } else if (keyIs(st.keyBuf, kl, K_AVG_AMOUNT)) {
                        pos = readDouble(buf, pos, len, st.dbl);
                        merchantAvgAmount = st.dbl[0];
                    } else {
                        pos = skipValue(buf, pos, len);
                    }
                }

            } else if (keyIs(st.keyBuf, kl, K_TERMINAL)) {
                // ── terminal ────────────────────────────────────────────────
                if (pos >= len || buf[pos] != '{') { pos = skipValue(buf, pos, len); continue; }
                pos++;
                while (true) {
                    pos = skipWs(buf, pos, len);
                    if (pos >= len || buf[pos] == '}') { if (pos < len) pos++; break; }
                    if (buf[pos] == ',') { pos++; continue; }
                    if (buf[pos] != '"') { pos = skipValue(buf, pos, len); continue; }
                    pos = readKey(buf, pos + 1, len, st.keyBuf, st.keyLen);
                    kl = st.keyLen[0];
                    pos = skipColon(buf, pos, len);
                    if (keyIs(st.keyBuf, kl, K_IS_ONLINE)) {
                        pos = readBoolean(buf, pos, len, st.bools);
                        isOnline = st.bools[0];
                    } else if (keyIs(st.keyBuf, kl, K_CARD_PRESENT)) {
                        pos = readBoolean(buf, pos, len, st.bools);
                        cardPresent = st.bools[0];
                    } else if (keyIs(st.keyBuf, kl, K_KM_FROM_HOME)) {
                        pos = readDouble(buf, pos, len, st.dbl);
                        kmFromHome = st.dbl[0];
                    } else {
                        pos = skipValue(buf, pos, len);
                    }
                }

            } else if (keyIs(st.keyBuf, kl, K_LAST_TRANSACTION)) {
                // ── last_transaction (null ou objeto) ───────────────────────
                if (pos + 3 < len
                        && buf[pos] == 'n' && buf[pos+1] == 'u'
                        && buf[pos+2] == 'l' && buf[pos+3] == 'l') {
                    pos += 4;
                    // lastEpochSecs permanece Long.MIN_VALUE (sentinel)
                } else if (pos < len && buf[pos] == '{') {
                    pos++;
                    while (true) {
                        pos = skipWs(buf, pos, len);
                        if (pos >= len || buf[pos] == '}') { if (pos < len) pos++; break; }
                        if (buf[pos] == ',') { pos++; continue; }
                        if (buf[pos] != '"') { pos = skipValue(buf, pos, len); continue; }
                        pos = readKey(buf, pos + 1, len, st.keyBuf, st.keyLen);
                        kl = st.keyLen[0];
                        pos = skipColon(buf, pos, len);
                        if (keyIs(st.keyBuf, kl, K_TIMESTAMP)) {
                            if (pos < len && buf[pos] == '"') {
                                pos = readTimestamp(buf, pos + 1, len, st.ts);
                                lastEpochSecs = st.ts[0];
                            } else {
                                pos = skipValue(buf, pos, len);
                            }
                        } else if (keyIs(st.keyBuf, kl, K_KM_FROM_CURRENT)) {
                            pos = readDouble(buf, pos, len, st.dbl);
                            kmFromCurrent = st.dbl[0];
                        } else {
                            pos = skipValue(buf, pos, len);
                        }
                    }
                } else {
                    pos = skipValue(buf, pos, len);
                }

            } else {
                pos = skipValue(buf, pos, len);
            }
        }

        // ── monta vetor (espelha TransactionVectorizer.vectorize) ────────────

        out[0] = clamp((float) (txAmount / maxAmount));
        out[1] = clamp(installments / maxInstallments);

        double amountVsAvg = customerAvgAmount > 0 ? (txAmount / customerAvgAmount) : 0.0;
        out[2] = clamp((float) (amountVsAvg / amountVsAvgRatio));

        out[3] = reqHour / 23.0f;
        out[4] = (reqWeekday - 1) / 6.0f;

        if (lastEpochSecs == Long.MIN_VALUE) {
            out[5] = -1.0f;
            out[6] = -1.0f;
        } else {
            long diffSecs = reqEpochSecs - lastEpochSecs;
            long diffMins = Math.max(0L, diffSecs / 60L); // espelha Duration.between().toMinutes()
            out[5] = clamp((float) diffMins / maxMinutes);
            out[6] = clamp((float) (kmFromCurrent / maxKm));
        }

        out[7]  = clamp((float) (kmFromHome / maxKm));
        out[8]  = clamp(txCount24h / maxTxCount24h);
        out[9]  = isOnline    ? 1.0f : 0.0f;
        out[10] = cardPresent ? 1.0f : 0.0f;

        boolean known = false;
        for (int i = 0; i < knownMerchCount; i++) {
            if (st.merch[i] == merchantIdPack) { known = true; break; }
        }
        out[11] = known ? 0.0f : 1.0f;
        out[12] = lookupMcc(mccPacked);
        out[13] = clamp((float) (merchantAvgAmount / maxMerchantAvgAmount));
    }

    // ── Utilitários de cursor ────────────────────────────────────────────────

    private static int skipWs(byte[] buf, int pos, int len) {
        while (pos < len) {
            byte c = buf[pos];
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            pos++;
        }
        return pos;
    }

    private static int skipColon(byte[] buf, int pos, int len) {
        pos = skipWs(buf, pos, len);
        if (pos < len && buf[pos] == ':') pos++;
        return skipWs(buf, pos, len);
    }

    /**
     * Lê os bytes do nome de campo (após o '"' de abertura já consumido).
     * Avança até o '"' de fechamento (inclusive) e grava comprimento em scrLen[0].
     */
    private static int readKey(byte[] buf, int pos, int len, byte[] scratch, int[] scrLen) {
        int i = 0;
        while (pos < len && buf[pos] != '"') {
            if (i < scratch.length) scratch[i++] = buf[pos];
            pos++;
        }
        scrLen[0] = i;
        if (pos < len) pos++; // avança closing '"'
        return pos;
    }

    private static boolean keyIs(byte[] scratch, int scrLen, byte[] key) {
        if (scrLen != key.length) return false;
        for (int i = 0; i < scrLen; i++) {
            if (scratch[i] != key[i]) return false;
        }
        return true;
    }

    private static int skipString(byte[] buf, int pos, int len) {
        pos++; // avança opening '"'
        while (pos < len && buf[pos] != '"') {
            if (buf[pos] == '\\') pos++; // escape: pula próximo char
            pos++;
        }
        if (pos < len) pos++; // avança closing '"'
        return pos;
    }

    /** Avança sobre qualquer valor JSON (string, número, booleano, null, objeto, array). */
    private static int skipValue(byte[] buf, int pos, int len) {
        if (pos >= len) return pos;
        byte b = buf[pos];
        if (b == '"') return skipString(buf, pos, len);
        if (b == '{') {
            pos++;
            int depth = 1;
            while (pos < len && depth > 0) {
                byte c = buf[pos++];
                if      (c == '"') pos = skipString(buf, pos - 1, len);
                else if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            return pos;
        }
        if (b == '[') {
            pos++;
            int depth = 1;
            while (pos < len && depth > 0) {
                byte c = buf[pos++];
                if      (c == '"') pos = skipString(buf, pos - 1, len);
                else if (c == '[') depth++;
                else if (c == ']') depth--;
            }
            return pos;
        }
        // número, booleano ou null: avança até delimitador
        while (pos < len) {
            byte c = buf[pos];
            if (c == ',' || c == '}' || c == ']'
                    || c == ' ' || c == '\t' || c == '\n' || c == '\r') break;
            pos++;
        }
        return pos;
    }

    /**
     * Parseia um double, guardando o resultado em out[0].
     * Suporta negativo e parte decimal (ex.: 41.12, -0.5, 9505.97).
     * Acumula dígitos fracionários como inteiro para evitar erros de precisão
     * em cascata de multiplicações por 0.1.
     */
    private static int readDouble(byte[] buf, int pos, int len, double[] out) {
        boolean neg = pos < len && buf[pos] == '-';
        if (neg) pos++;
        long intPart = 0;
        while (pos < len && buf[pos] >= '0' && buf[pos] <= '9') {
            intPart = intPart * 10 + (buf[pos++] - '0');
        }
        double frac = 0;
        if (pos < len && buf[pos] == '.') {
            pos++;
            long fracDigits = 0;
            int  fracCount  = 0;
            while (pos < len && buf[pos] >= '0' && buf[pos] <= '9') {
                fracDigits = fracDigits * 10 + (buf[pos++] - '0');
                fracCount++;
            }
            if (fracCount > 0) {
                frac = fracDigits / Math.pow(10, fracCount);
            }
        }
        out[0] = neg ? -(intPart + frac) : (intPart + frac);
        return pos;
    }

    /** Parseia um inteiro, guardando o resultado em out[0]. */
    private static int readInt(byte[] buf, int pos, int len, int[] out) {
        boolean neg = pos < len && buf[pos] == '-';
        if (neg) pos++;
        int val = 0;
        while (pos < len && buf[pos] >= '0' && buf[pos] <= '9') {
            val = val * 10 + (buf[pos++] - '0');
        }
        out[0] = neg ? -val : val;
        return pos;
    }

    /** Parseia "true" ou "false", guardando em out[0]. */
    private static int readBoolean(byte[] buf, int pos, int len, boolean[] out) {
        if (pos < len && buf[pos] == 't') {
            out[0] = true;
            return pos + 4; // "true"
        }
        out[0] = false;
        return pos + 5; // "false"
    }

    /**
     * Parseia timestamp ISO-8601 "YYYY-MM-DDTHH:MM:SSZ" (20 chars fixos).
     * Posição {@code pos} aponta para o primeiro char após a '"' de abertura.
     *
     * <p>Saída em {@code out}:
     * <ul>
     *   <li>out[0] = epoch segundos UTC (via algoritmo de Howard Hinnant)</li>
     *   <li>out[1] = dia da semana ISO (Seg=1 … Dom=7)</li>
     *   <li>out[2] = hora (0-23)</li>
     * </ul>
     *
     * Retorna posição após a '"' de fechamento.
     */
    private static int readTimestamp(byte[] buf, int pos, int len, long[] out) {
        int y  = d4(buf, pos);
        int mo = d2(buf, pos + 5);
        int d  = d2(buf, pos + 8);
        int h  = d2(buf, pos + 11);
        int mn = d2(buf, pos + 14);
        int s  = d2(buf, pos + 17);
        // pos+19 = 'Z', pos+20 = '"' de fechamento

        int days = daysFromCivil(y, mo, d);
        out[0] = (long) days * 86400L + h * 3600L + mn * 60L + s;
        out[1] = Math.floorMod(days + 3, 7) + 1; // Seg=1..Dom=7
        out[2] = h;

        return pos + 21; // 20 chars do timestamp + closing '"'
    }

    /**
     * Algoritmo de Howard Hinnant: converte data civil (y, m, d) em número de
     * dias desde a época Unix (1970-01-01 = dia 0).
     *
     * Referência: https://howardhinnant.github.io/date_algorithms.html
     */
    private static int daysFromCivil(int y, int m, int d) {
        if (m <= 2) { y -= 1; m += 9; } else { m -= 3; }
        int era = Math.floorDiv(y, 400);
        int yoe = y - era * 400;                          // [0, 399]
        int doy = (153 * m + 2) / 5 + d - 1;             // [0, 365]
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy; // [0, 146096]
        return era * 146097 + doe - 719468;
    }

    private static int d2(byte[] buf, int pos) {
        return (buf[pos] - '0') * 10 + (buf[pos + 1] - '0');
    }

    private static int d4(byte[] buf, int pos) {
        return (buf[pos]     - '0') * 1000
             + (buf[pos + 1] - '0') * 100
             + (buf[pos + 2] - '0') * 10
             + (buf[pos + 3] - '0');
    }

    // ── Helpers de empacotamento ─────────────────────────────────────────────

    /**
     * Empacota os primeiros 8 bytes de um ID de merchant em um long big-endian.
     * IDs do corpus ("MERC-NNN") têm exatamente 8 bytes; o empacotamento é sem perdas.
     */
    private static long packMerch(byte[] buf, int pos, int sLen) {
        long v = 0;
        int  n = Math.min(sLen, 8);
        for (int i = 0; i < n; i++) {
            v = (v << 8) | (buf[pos + i] & 0xFF);
        }
        v <<= (8 - n) * 8; // alinhar à esquerda com zeros
        return v;
    }

    private static int packMccStr(String mcc) {
        byte[] mb = mcc.getBytes(StandardCharsets.US_ASCII);
        return ((mb[0] & 0xFF) << 24) | ((mb[1] & 0xFF) << 16)
             | ((mb[2] & 0xFF) << 8)  |  (mb[3] & 0xFF);
    }

    private static int packMccBytes(byte[] buf, int pos) {
        return ((buf[pos]     & 0xFF) << 24) | ((buf[pos + 1] & 0xFF) << 16)
             | ((buf[pos + 2] & 0xFF) << 8)  |  (buf[pos + 3] & 0xFF);
    }

    private float lookupMcc(int packed) {
        for (int i = 0; i < mccCount; i++) {
            if (mccKeys[i] == packed) return mccValues[i];
        }
        return 0.5f;
    }

    private static float clamp(float v) {
        return Math.clamp(v, 0.0f, 1.0f);
    }
}
