# V5 (Opus) — Status geral

> Índice de issues e estado real (atualizado 2026-06-05 após os benchmarks nativos).
> Lê isto primeiro para saber o que está pronto e o que falta.

## Onde estamos

A V5 virou **GraalVM Native Image + servidor NIO próprio (V6 antecipado)**. O caminho
real divergiu do PRD em dois pontos descobertos na prática (ambos documentados nas issues):

1. **Jetty não funciona no nativo** (busy-spin do selector) → escrevemos um servidor NIO
   próprio (`NioHttpServer`). O Javalin/Jetty saiu do runtime.
2. **`MemorySegment` (FFM) é ~14x mais lento no nativo** → trocamos por `MappedByteBuffer`.
   Foi o lever decisivo: a busca nativa voltou a sub-ms.

Resultado atual (stack constrangida, cpuset, 0.45 CPU/inst, 350MB total): **0% de falha,
0 FP/FN, score ~4200**, p50 2-15ms. **O p99 ~62ms é o teto atual** e é o foco agora.

## Issues

| # | Título | Status |
|---|---|---|
| 01 | ThreadLocal\<SearchState\> | ✅ done |
| 02 | HAProxy splice + cpuset (+ pool de threads) | ✅ done (ver nota: pool obsoleto) |
| 03 | Remoção do Jackson em AppConfig | ✅ done (ver nota: escopo) |
| 04 | GraalVM Native Image sem PGO | ✅ done (ver desvios) |
| 06 | Servidor NIO V6 (substitui Jetty) | ✅ done |
| 07 | Busca via MappedByteBuffer | ✅ done |
| 08 | Cauda de p99: conexões configuráveis + busy-poll/CFS + sysctls | 🔲 aberto — **PRIORIDADE** |
| 05 | PGO loop + benchmark final | 🔲 aberto (depois da 08) |

## Prioridade de implementação (pós-benchmark)

O p99 (~62ms) é **idêntico em todas as configs de K/nprobe** → não é custo de busca, é a
**cauda do busy-poll sob throttle de CFS**. Por isso:

1. **Issue 08 (cauda de p99) — PRIMEIRO.** Maior lever de score hoje. Inclui:
   - Tornar o número de conexões/VUs do `test.js` **configurável** (default 30; nunca passou
     de 50). Menos conexões = menos varredura por iteração = menos cota de CPU queimada.
   - Reavaliar `Selector` no nativo (o teste anterior foi confundido pelo hang do warmup,
     já removido) ou backoff CFS-friendly no busy-poll.
   - sysctls (`somaxconn`, `tcp_fastopen`).
2. **Issue 05 (PGO) — DEPOIS.** Reduz custo por request (p50), mas **não** ataca a cauda
   fixa de p99. Reavaliar os critérios (p99<20ms só vem após a 08).
3. **Tuning de K/nprobe — adiado.** Enquanto o p99 dominar e for config-independente, o
   ajuste de K/nprobe não move o score (todas as 6 configs deram ~4200). Decidir depois
   da 08, com `K4096/np2` ou `K2048/np2` como candidatos (marginalmente melhores hoje).

## Nota de estabilidade

Após a troca para `MappedByteBuffer`, os benchmarks ficaram estáveis: **1 run basta**
(antes exigia 3 runs + média). Variância run-to-run colapsou.
