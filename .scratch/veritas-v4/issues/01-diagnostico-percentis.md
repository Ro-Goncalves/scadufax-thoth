# Issue 01 — V4-0: Diagnóstico de percentis (p50/p95)

Status: done

## Issue pai

[PRD: Tesseract V4 — Veritas](../PRD.md)

## O que construir

Adicionar `p(50)` e `p(95)` ao relatório do K6, para que o spread de cauda residual
da V3 (17–52ms em 5 rodadas) seja diagnosticado com precisão antes de qualquer
mudança no código Java.

Hoje o K6 reporta apenas `p(99)`. Com apenas um percentil, é impossível distinguir:

- **Hipótese A (cauda de GC):** p50 e p95 estáveis entre runs; só o p99 salta. O
  Jackson ainda aloca no parse de entrada — pressão suficiente para coletas GC a
  ~900 req/s.
- **Hipótese B (host sem CPU):** p50 e p95 sobem junto com o p99. É ruído de ambiente;
  nenhuma otimização de código resolve.

A mudança é uma linha no script de teste (`summaryTrendStats`) — zero impacto no
código da aplicação. Após a alteração, rodar 3–5 boots frios e registrar os percentis.

## Critérios de aceite

- [x] O relatório do K6 exibe `p(50)`, `p(95)` e `p(99)` por endpoint.
- [x] Foram rodados pelo menos 3 boots frios completos — rodados 5 em 2026-06-03.
- [x] O resultado está registrado no doc `docs/knowledge/v4/01-veritas.md` (seção
      "V4-0 → Resultado").
- [x] A decisão sobre o V4-C está registrada: **entra** — Hipótese A confirmada.

## Bloqueada por

Nenhum — pode começar imediatamente.