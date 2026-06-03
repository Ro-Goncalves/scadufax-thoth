# Competitive Analysis — Rinha de Backend 2026

Pesquisa de implementações competitivas para extração de técnicas aproveitáveis.

## Repositórios Analisados

| # | Arquivo | Repositório | Lang | Score | p99 | FP/FN |
|---|---|---|---|---|---|---|
| 1 | [01-ednaldo-luiz.md](01-ednaldo-luiz.md) | EdnaldoLuiz/rinha-de-backend-2026-java | Java 21 | N/A | N/A | N/A |
| 2 | [02-anddev741.md](02-anddev741.md) | AndDev741/rinha-de-backend-2026-java | Java | 4.056 | 87.73ms | 0/0 ✓ |
| 3 | [03-papagaio.md](03-papagaio.md) | VitorNathanG/rinha-de-papagaio | Rust | 6.000 | 0.13ms | 0/0 ✓ |
| 4 | [04-arthurd3.md](04-arthurd3.md) | arthurd3/fraud-detection | Java+Rust | 5.731 | 1.86ms | 0/0 ✓ |
| 5 | [05-lucasmontano.md](05-lucasmontano.md) | lucasmontano/rinha-backend-2026-detecta-fraude | Rust+C | N/A | <1ms | 0/0 ✓ |

**Nossa posição atual:** score=2.766, p99=36.95ms, FP=105, FN=102

## Ranking de técnicas por impacto descoberto

### Mudança de jogo (score_det)
- **Bounding-box pruning** — transforma IVF aproximado em busca exata; 0 FP/FN → score_det dobra
  - Confirmado por: AndDev741 (score 4.056 com p99 87ms!), arthurd3 (KD-tree exact)

### Alto impacto de latência
- **File descriptor passing via SCM_RIGHTS** — elimina LB do data path
  - arthurd3: HAProxy → Rust LB deu -80% p99 (2.65ms → 1.86ms)
  - Confirmado por: papagaio (nginx stream), lucasmontano (C LB)
  - Pode ser feito com C simples (218 linhas) ou Rust

- **GraalVM Native Image + PGO** — binário 12MB, sem JIT warmup
  - arthurd3: ancora no Onda 5 como baseline (~4.393 score)

### Médio impacto (remover overhead)
- **Custom NIO HTTP server** — single-threaded, sem framework
  - arthurd3, AndDev741: ~500 linhas substituem Javalin
- **Custom JSON parser** — zero-alocação, cursor-based
  - Todos os top performers: 10–20× mais rápido que Jackson
- **Pre-computed 6 responses** — zero serialização por request
  - Todos os top performers implementam

### Baixo impacto, alto custo
- **Migrar search para Rust FFI** — arthurd3 Onda 33: -27% p99
  - Só faz sentido após todas as outras otimizações

### Descartado — NÃO implementar
- **Java Panama Vector API / SIMD** — 3.8× mais lento que scalar (arthurd3 confirmou)
  - Também quebra GraalVM Native Image
  - NÃO está em nenhum top performer Java

## Descoberta mais importante desta pesquisa

**A qualidade de detecção (FP/FN) vale mais que latência no scoring.**

AndDev741: p99=87ms (2× pior que nós) mas score 4.056 (1.5× melhor que nós).
Motivo: 0 FP e 0 FN via busca exata. Nosso IVF aproximado gera 105 FP + 102 FN.

Consequência para o roadmap: **V4-A (bounding-box pruning) é o item mais
prioritário**, antes até de GraalVM ou custom HTTP server.

## Técnicas inéditas encontradas nesta rodada de pesquisa

1. **IVF repair mechanism** (lucasmontano) — scan extra quando fraud_count ∈ {1–4}
2. **Early termination por confiança** (lucasmontano) — para se 5º dist ≤ threshold
3. **Accept batching no LB** (lucasmontano) — 128 conexões por batch
4. **TCP_DEFER_ACCEPT** (lucasmontano) — kernel aguarda dados antes de wakeup
5. **Partition key por feature bits** (lucasmontano) — alternativa ao K-means
6. **Busy-poll epoll** (lucasmontano) — latência sub-microsegundo
7. **Beam-of-2 prime phase** (arthurd3) — duas descidas em vez de fan-out no KD-tree
8. **Dimension variance permutation** (arthurd3) — reordena features por variância
9. **selectNow() antes de select()** (arthurd3) — evita syscall quando dados buffered
10. **Inline response write** (arthurd3) — elimina um epoll_wait por request
11. **PGO (Profile-Guided Optimization)** (arthurd3) — compilação guiada por perfil real
