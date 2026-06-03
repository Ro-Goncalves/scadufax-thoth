package br.com.rgbrainlabs.scadufaxthoth.web;

import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class PreSerializedResponseTable {

    private final byte[][] table;
    private final int k;

    public PreSerializedResponseTable(int k, double threshold, ObjectMapper mapper) {
        try {
            this.k = k;
            this.table = new byte[k + 1][];
            for (int i = 0; i <= k; i++) {
                double score = (double) i / k;
                boolean approved = score < threshold;
                this.table[i] = mapper.writeValueAsBytes(new TransactionResponse(approved, score));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao pré-serializar tabela de respostas", e);
        }
    }

    public byte[] get(int fraudCount) {
        return table[fraudCount];
    }

    public int k() {
        return k;
    }
}
