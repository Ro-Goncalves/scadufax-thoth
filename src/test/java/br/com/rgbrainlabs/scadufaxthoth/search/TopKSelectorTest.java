package br.com.rgbrainlabs.scadufaxthoth.search;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TopKSelectorTest {

    // Referência: Max-Heap + sort (comportamento original)
    private List<SearchResult> referenceTopK(double[] dists, byte[] labels, int k) {
        PriorityQueue<double[]> pq = new PriorityQueue<>(k,
                (a, b) -> Double.compare(b[0], a[0])); // max-heap por distância
        for (int i = 0; i < dists.length; i++) {
            if (pq.size() < k) {
                pq.offer(new double[]{dists[i], labels[i]});
            } else if (dists[i] < pq.peek()[0]) {
                pq.poll();
                pq.offer(new double[]{dists[i], labels[i]});
            }
        }
        List<double[]> list = new ArrayList<>(pq);
        list.sort(Comparator.comparingDouble(a -> a[0]));
        List<SearchResult> results = new ArrayList<>(list.size());
        for (double[] e : list) {
            results.add(new SearchResult((byte) e[1] == 1 ? "fraud" : "legitimate", e[0]));
        }
        return results;
    }

    @Test
    void equivalenciaComMaxHeap_distribuicaoAleatoria() {
        int n = 5000, k = 100;
        Random rng = new Random(42);
        double[] dists = new double[n];
        byte[] lbls = new byte[n];
        for (int i = 0; i < n; i++) {
            dists[i] = rng.nextDouble() * 1000.0;
            lbls[i] = (byte) (rng.nextBoolean() ? 1 : 0);
        }

        TopKSelector selector = new TopKSelector(k);
        for (int i = 0; i < n; i++) selector.tryInsert(dists[i], lbls[i]);
        List<SearchResult> got = selector.materialize();

        List<SearchResult> expected = referenceTopK(dists, lbls, k);

        assertEquals(expected.size(), got.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).distance(), got.get(i).distance(), 1e-12,
                    "distância diverge na posição " + i);
            assertEquals(expected.get(i).label(), got.get(i).label(),
                    "label diverge na posição " + i);
        }
    }

    @Test
    void menosCandidatosQueK_retornaTodosInseridos() {
        int k = 10;
        TopKSelector selector = new TopKSelector(k);
        selector.tryInsert(3.0, (byte) 0);
        selector.tryInsert(1.0, (byte) 1);
        selector.tryInsert(2.0, (byte) 0);

        List<SearchResult> results = selector.materialize();

        assertEquals(3, results.size());
        assertEquals(1.0, results.get(0).distance());
        assertEquals(2.0, results.get(1).distance());
        assertEquals(3.0, results.get(2).distance());
        assertEquals("fraud", results.get(0).label());
    }

    @Test
    void exatamenteKCandidatos_todosRetornados() {
        int k = 4;
        double[] dists = {4.0, 2.0, 3.0, 1.0};
        byte[] lbls   = {0,   1,   0,   1};

        TopKSelector selector = new TopKSelector(k);
        for (int i = 0; i < k; i++) selector.tryInsert(dists[i], lbls[i]);
        List<SearchResult> results = selector.materialize();

        assertEquals(k, results.size());
        assertEquals(1.0, results.get(0).distance());
        assertEquals(4.0, results.get(k - 1).distance());
    }

    @Test
    void empatesDeDistancia_naoGeraMaisQueKResultados() {
        int k = 3;
        TopKSelector selector = new TopKSelector(k);
        for (int i = 0; i < 10; i++) selector.tryInsert(5.0, (byte) 0);

        List<SearchResult> results = selector.materialize();

        assertEquals(k, results.size());
        for (SearchResult r : results) assertEquals(5.0, r.distance());
    }

    @Test
    void kIgualA1_retornaApenasOMaisProximo() {
        TopKSelector selector = new TopKSelector(1);
        selector.tryInsert(10.0, (byte) 0);
        selector.tryInsert(2.0, (byte) 1);
        selector.tryInsert(7.0, (byte) 0);

        List<SearchResult> results = selector.materialize();

        assertEquals(1, results.size());
        assertEquals(2.0, results.get(0).distance());
        assertEquals("fraud", results.get(0).label());
    }

    @Test
    void nenhumaCandidatoInserido_retornaListaVazia() {
        TopKSelector selector = new TopKSelector(5);
        assertTrue(selector.materialize().isEmpty());
    }

    @Test
    void labelsCorretamenteMaterializados() {
        TopKSelector selector = new TopKSelector(2);
        selector.tryInsert(1.0, (byte) 1);   // fraud
        selector.tryInsert(2.0, (byte) 0);   // legitimate

        List<SearchResult> results = selector.materialize();

        assertEquals("fraud", results.get(0).label());
        assertEquals("legitimate", results.get(1).label());
    }
}
