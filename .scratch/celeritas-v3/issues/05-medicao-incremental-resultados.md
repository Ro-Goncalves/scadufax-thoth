# Issue 05 — Medição incremental + registro de resultados

Status: done

## Issue pai

[PRD: Tesseract V3 — Celeritas](../PRD.md)

## O que construir

Medir o impacto da V3 de forma incremental e registrar os números na documentação. A V3
é sobre **variância**, então a medição importa tanto quanto o código: cada técnica deve ter
sua contribuição isolada ao p99 e ao avg.

É uma fatia **HITL**: exige um operador subindo o docker compose e rodando o K6 oficial
(carga pesada, específica do ambiente) e transcrevendo os resultados para a seção
"Resultados" de `docs/knowledge/v3/01-celeritas.md`.

Sequência de medição:

1. **Baseline** — já com o envelope correto (Issue 01), sem V3-A/B/D: capturar p99, avg e a
   curva cold→hot.
2. **Incremental** — medir após V3-A (Issue 02), depois V3-B (Issue 03), depois V3-D
   (Issue 04), isolando o ganho de cada um.
3. **Registro** — preencher a seção "Resultados" do doc da V3 com os números medidos, no
   mesmo padrão do fechamento da V2.

Sinais esperados: V3-A achata a cauda cold (request 1 de ~130ms para a faixa de regime);
V3-B + V3-D reduzem avg e p99 em regime quente (menos GC, menos trabalho por requisição),
sem impacto em FP/FN.

## Critérios de aceite

- [x] Baseline K6 capturado com o envelope correto (K=1024/nprobe=4).
- [x] Medições incrementais capturadas após cada uma das fatias V3-A, V3-B e V3-D.
- [x] Zero erros HTTP sob a carga do K6 em todas as medições.
- [x] A seção "Resultados" de `docs/knowledge/v3/01-celeritas.md` é preenchida com os
      números medidos e a comparação com a linha de base da V2.
- [x] O documento explica o porquê dos ganhos observados (não só os números).

## Bloqueada por

- Issue 01 — Pin do envelope operacional
- Issue 02 — V3-A: Page pre-warming
- Issue 03 — V3-B: Respostas pré-serializadas
- Issue 04 — V3-D: Seletor top-k + busca sem alocação
