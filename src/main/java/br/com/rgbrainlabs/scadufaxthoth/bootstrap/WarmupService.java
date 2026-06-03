package br.com.rgbrainlabs.scadufaxthoth.bootstrap;

import br.com.rgbrainlabs.scadufaxthoth.domain.SearchResult;
import br.com.rgbrainlabs.scadufaxthoth.domain.TransactionRequest;
import br.com.rgbrainlabs.scadufaxthoth.search.TransactionVectorizer;
import br.com.rgbrainlabs.scadufaxthoth.search.VectorSearcher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.InputStream;
import java.util.List;

/**
 * Aquece o hot path de busca antes de /ready estar disponível.
 *
 * Usa example-payloads.json (embutido no JAR) como corpus de queries sintéticas
 * para forçar a compilação JIT (C2) do caminho exato — vetorização + busca — que
 * o tráfego real vai exercitar.
 *
 * <h2>Por que detecção de platô em vez de um número fixo de buscas?</h2>
 *
 * O custo do aquecimento depende do host: número de núcleos, fatia de CPU do
 * container, quão rápido o C2 dispara. Um número fixo de iterações ou subaquece
 * (o C2 ainda não compilou o hot path quando /ready libera tráfego, e a compilação
 * cai dentro da janela medida, inflando o p99 e a variância) ou superaquece
 * (gasta tempo de partida à toa).
 *
 * Em vez disso, medimos a latência média por janela de buscas e paramos quando ela
 * <b>para de melhorar</b> — sinal de que o C2 já estabilizou o caminho. Pisos e tetos
 * (mín/máx de buscas e tempo) garantem que sempre alcançamos o limiar de compilação
 * e nunca prendemos a partida num host muito lento.
 *
 * <h2>Por que não usar vetores aleatórios?</h2>
 *
 * Vetores uniformemente aleatórios não reproduzem a distribuição do dataset e podem
 * não acionar as mesmas rotas de código (seleção de clusters no IVF, ramos do
 * insertion sort do top-k) que queries reais acionam. Payloads reais garantem que o
 * C2 compile exatamente o bytecode que o tráfego vai bater.
 */
public final class WarmupService {

    /** Buscas por janela de medição antes de reavaliar a latência média. */
    private static final int WINDOW_SIZE = 2_000;

    /**
     * Piso de buscas. Garante ultrapassar o limiar de compilação C2 (~10k invocações)
     * mesmo que a latência pareça estável cedo por ruído de medição. Sobrescreve com
     * {@code WARMUP_MIN_SEARCHES}.
     */
    private static final int MIN_SEARCHES = envInt("WARMUP_MIN_SEARCHES", 12_000);

    /**
     * Teto de buscas. Evita prender a partida indefinidamente num host onde a latência
     * nunca platô claramente. Sobrescreve com {@code WARMUP_MAX_SEARCHES}.
     */
    private static final int MAX_SEARCHES = envInt("WARMUP_MAX_SEARCHES", 400_000);

    /**
     * Teto de tempo (ms). Limite absoluto da partida no container capado. Sobrescreve
     * com {@code WARMUP_MAX_MS}.
     */
    private static final long MAX_DURATION_MS = envInt("WARMUP_MAX_MS", 25_000);

    /** Melhora relativa entre janelas abaixo disso conta como "não melhorou mais". */
    private static final double PLATEAU_EPSILON = 0.02; // 2%

    /** Janelas estáveis consecutivas necessárias para declarar platô. */
    private static final int REQUIRED_STABLE_WINDOWS = 3;

    /** Impede o JIT de eliminar a busca como código morto (volatile = escrita observável). */
    @SuppressWarnings("unused")
    private static volatile long blackhole;

    private WarmupService() {}

    public static void warmup(VectorSearcher searcher, TransactionVectorizer vectorizer) {
        long t0 = System.currentTimeMillis();

        List<TransactionRequest> payloads = loadPayloads();
        if (payloads == null || payloads.isEmpty()) {
            System.out.println("[warmup] nenhum payload disponível; pulando warmup.");
            return;
        }

        long deadline = t0 + MAX_DURATION_MS;
        int payloadIdx = 0;
        int totalSearches = 0;
        int stableWindows = 0;
        double prevWindowAvgNs = Double.MAX_VALUE;
        double localBlackhole = 0;
        String stopReason;

        while (true) {
            long windowStart = System.nanoTime();
            for (int i = 0; i < WINDOW_SIZE; i++) {
                TransactionRequest req = payloads.get(payloadIdx);
                if (++payloadIdx >= payloads.size()) {
                    payloadIdx = 0;
                }
                float[] vec = vectorizer.vectorize(req);
                List<SearchResult> results = searcher.search(vec, 5);
                // Consumir o resultado para que o caminho não seja eliminado pelo JIT.
                localBlackhole += results.size();
                if (!results.isEmpty()) {
                    localBlackhole += results.get(0).distance();
                }
            }
            long windowNs = System.nanoTime() - windowStart;
            totalSearches += WINDOW_SIZE;

            double windowAvgNs = (double) windowNs / WINDOW_SIZE;
            double improvement = (prevWindowAvgNs - windowAvgNs) / prevWindowAvgNs;
            if (improvement < PLATEAU_EPSILON) {
                stableWindows++;
            } else {
                stableWindows = 0;
            }
            prevWindowAvgNs = windowAvgNs;

            boolean plateaued = stableWindows >= REQUIRED_STABLE_WINDOWS && totalSearches >= MIN_SEARCHES;
            boolean hitSearchCap = totalSearches >= MAX_SEARCHES;
            boolean timedOut = System.currentTimeMillis() >= deadline;

            System.out.printf(
                    "[warmup] janela: %d buscas, %.1f us/busca, estáveis=%d%n",
                    totalSearches, windowAvgNs / 1_000.0, stableWindows);

            if (plateaued) {
                stopReason = "platô atingido";
                break;
            }
            if (hitSearchCap) {
                stopReason = "teto de buscas (" + MAX_SEARCHES + ")";
                break;
            }
            if (timedOut) {
                stopReason = "teto de tempo (" + MAX_DURATION_MS + "ms)";
                break;
            }
        }

        blackhole = (long) localBlackhole;
        System.out.printf(
                "[warmup] completo em %d ms — %s — %d buscas, %.1f us/busca final%n",
                System.currentTimeMillis() - t0, stopReason, totalSearches, prevWindowAvgNs / 1_000.0);
    }

    private static List<TransactionRequest> loadPayloads() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        try (InputStream is = WarmupService.class.getResourceAsStream("/example-payloads.json")) {
            if (is == null) {
                System.out.println("[warmup] example-payloads.json não encontrado.");
                return null;
            }
            return mapper.readValue(is, new TypeReference<>() {});
        } catch (Exception e) {
            System.out.println("[warmup] falha ao carregar payloads: " + e.getMessage());
            return null;
        }
    }

    private static int envInt(String name, int fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.out.printf("[warmup] %s inválido ('%s'); usando %d%n", name, raw, fallback);
            return fallback;
        }
    }
}
