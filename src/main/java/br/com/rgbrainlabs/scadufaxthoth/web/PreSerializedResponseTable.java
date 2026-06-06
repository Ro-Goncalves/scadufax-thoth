package br.com.rgbrainlabs.scadufaxthoth.web;

import java.nio.charset.StandardCharsets;

public final class PreSerializedResponseTable {

    private final byte[][] table;
    private final int k;

    public PreSerializedResponseTable(int k, double threshold) {
        this.k = k;
        this.table = new byte[k + 1][];
        for (int i = 0; i <= k; i++) {
            double score = (double) i / k;
            boolean approved = score < threshold;
            String json = "{\"approved\":" + approved + ",\"fraud_score\":" + score + "}";
            this.table[i] = json.getBytes(StandardCharsets.UTF_8);
        }
    }

    public byte[] get(int fraudCount) {
        return table[fraudCount];
    }

    public int k() {
        return k;
    }
}
