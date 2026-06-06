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

Resultado atual (stack constrangida, cpuset, 0.45 CPU/inst, 350MB total, K2048/np2):
**0% de falha, 0 FP/FN, p50 0.67ms / p95 1.06ms / p99 1.344ms, score 5871.58** (mediana
de 5 boots, spread de p99 de 12µs). Ver `docs/knowledge/v5/benchmark-opus.md` (V5-5).

## Issues

| # | Título | Status |
|---|---|---|
| 01 | ThreadLocal\<SearchState\> | ✅ done |
| 02 | HAProxy splice + cpuset (+ pool de threads) | ✅ done (ver nota: pool obsoleto) |
| 03 | Remoção do Jackson em AppConfig | ✅ done (ver nota: escopo) |
| 04 | GraalVM Native Image sem PGO | ✅ done (ver desvios) |
| 06 | Servidor NIO V6 (substitui Jetty) | ✅ done |
| 07 | Busca via MappedByteBuffer | ✅ done |
| 05 | PGO loop + benchmark final | ✅ done (2026-06-06; V5-4/V5-5) |
| 08 | Cauda de p99: backoff CFS-aware no busy-poll | ✅ done (2026-06-06; fix de 1 linha) |

## Resolução da cauda de p99 (Issue 08) — 2026-06-06

O p99 (~57-62ms) era **idêntico em todas as configs de K/nprobe** → não era custo de
busca. Era um **bug no busy-poll do `NioHttpServer`**: o reactor só cedia CPU
(`parkNanos`) quando `conns.isEmpty()`; sob keep-alive (conexões sempre abertas) ele
fazia **spin puro a 100% de CPU**, queimando a cota de CFS (0.45) e sendo estrangulado
~55ms por período. **Fix de 1 linha:** ceder a CPU sempre que um ciclo não acha trabalho
(`if (!didWork)`), park 20µs→50µs. Resultado: **p99 57ms → 1.344ms, score 4243 → 5872**.

Itens secundários da Issue 08 (conexões/VUs configuráveis, sysctls, `Selector` nativo)
ficaram **opcionais**: o backoff CFS-aware já resolveu a cauda. Trocar `parkNanos` por
`Selector`/epoll é o único caminho para empurrar p99 < 1ms (zero latência de park), mas
só vale se a Rinha exigir — o envelope atual (p50 0.67 / p99 1.34ms) já é dominante.

**Tuning de K/nprobe — reavaliar agora** que o p99 deixou de dominar: com p99 ~1.3ms,
o `score_p99` ainda manda no `final_score`, então configs que minimizam p99 (K2048/np2)
seguem ótimos; reconfirmar K2048/np2 vs np6 com a cauda já domada se quiser fechar o
envelope V6.

## Nota de estabilidade

Após a troca para `MappedByteBuffer`, os benchmarks ficaram estáveis: **1 run basta**
(antes exigia 3 runs + média). Variância run-to-run colapsou.
