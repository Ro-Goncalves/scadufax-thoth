package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Seletor top-k zero-alocação no hot path.
 *
 * Mantém os k menores via insertion sort sobre dois arrays primitivos paralelos
 * (distância e label byte). Nenhum objeto é alocado por candidato varrido; a
 * construção de SearchResult e String de label ocorre uma única vez em materialize().
 *
 * Arrays são locais à chamada — sem estado compartilhado entre requisições.
 */
public final class TopKSelector {

    private final double[] topDist;
    private final byte[] topLabel;
    private final int k;
    private int size;

    public TopKSelector(int k) {
        this.k = k;
        this.topDist = new double[k];
        this.topLabel = new byte[k];
        this.size = 0;
        java.util.Arrays.fill(topDist, Double.MAX_VALUE);
    }

    /**
     * Tenta inserir (dist, label) no conjunto top-k.
     *
     * Se há menos de k elementos, ou dist é menor que o pior elemento atual
     * (topDist[k-1]), insere via insertion sort mantendo ordem crescente.
     */
    public void tryInsert(double dist, byte label) {
        if (size < k) {
            // Ainda há espaço: insertion sort no slot size
            int pos = size;
            while (pos > 0 && dist < topDist[pos - 1]) {
                topDist[pos] = topDist[pos - 1];
                topLabel[pos] = topLabel[pos - 1];
                pos--;
            }
            topDist[pos] = dist;
            topLabel[pos] = label;
            size++;
        } else if (dist < topDist[k - 1]) {
            // Substitui o pior: insertion sort a partir do último slot
            int pos = k - 1;
            while (pos > 0 && dist < topDist[pos - 1]) {
                topDist[pos] = topDist[pos - 1];
                topLabel[pos] = topLabel[pos - 1];
                pos--;
            }
            topDist[pos] = dist;
            topLabel[pos] = label;
        }
    }

    /**
     * Materializa os resultados coletados como List<SearchResult> em ordem
     * crescente de distância. Chamado uma única vez ao final da varredura.
     */
    public List<SearchResult> materialize() {
        List<SearchResult> results = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String labelStr = topLabel[i] == 1 ? "fraud" : "legitimate";
            results.add(new SearchResult(labelStr, topDist[i]));
        }
        return results;
    }
}
