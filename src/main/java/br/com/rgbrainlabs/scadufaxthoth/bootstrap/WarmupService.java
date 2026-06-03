package br.com.rgbrainlabs.scadufaxthoth.bootstrap;

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
 * Usa example-payloads.json (embutido no JAR) como corpus de queries sintéticas.
 * O objetivo é forçar a compilação JIT do caminho exato que o tráfego real
 * vai exercitar, eliminando a latência de "aquecimento frio" nos primeiros
 * requests da rinha.
 *
 * Por que não usar vetores aleatórios?
 *   Vetores uniformemente aleatórios não reproduzem a distribuição do dataset
 *   e podem não acionar as mesmas rotas de código que queries reais acionam
 *   (relevante especialmente quando IVF for adicionado na Issue 02).
 *   Usar payloads reais garante que o JIT C2 compile exatamente o bytecode
 *   que o tráfego vai bater.
 */
public final class WarmupService {

    private static final int TARGET_RUNS = 50;

    private WarmupService() {}

    public static void warmup(VectorSearcher searcher, TransactionVectorizer vectorizer) {
        long t0 = System.currentTimeMillis();

        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

        List<TransactionRequest> payloads;
        try (InputStream is = WarmupService.class.getResourceAsStream("/example-payloads.json")) {
            if (is == null) {
                System.out.println("[warmup] example-payloads.json não encontrado; pulando warmup.");
                return;
            }
            payloads = mapper.readValue(is, new TypeReference<>() {});
        } catch (Exception e) {
            System.out.println("[warmup] falha ao carregar payloads: " + e.getMessage() + "; pulando.");
            return;
        }

        if (payloads.isEmpty()) {
            System.out.println("[warmup] nenhum payload encontrado; pulando warmup.");
            return;
        }

        int runs = 0;
        while (runs < TARGET_RUNS) {
            for (TransactionRequest req : payloads) {
                float[] vec = vectorizer.vectorize(req);
                searcher.search(vec, 5);
                runs++;
                if (runs >= TARGET_RUNS) break;
            }
        }

        System.out.printf("[warmup] completo em %d ms (%d buscas)%n",
                System.currentTimeMillis() - t0, runs);
    }
}
