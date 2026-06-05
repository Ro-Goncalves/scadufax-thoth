# Issue 07 — Gate de score pós-V4-A: benchmark + decisão V5/V6

Status: open
Tipo: HITL

## Issue pai

[PRD: Tesseract V4 — Veritas](../PRD.md)

## O que construir

Medir o score real após a implementação do bbox pruning (Issue 05) e tomar a decisão
de planejamento das próximas versões (V5/V6) com base nos números obtidos, não em
estimativas.

A V3 saturou o `score_p99`; toda a folga restante está no `score_det`. O bbox pruning
deve elevar o `score_det` de ~1.335 para ~2.000+ e o `final_score` de ~2.862 para
~3.600+, cruzando o território de top-10 Java. Mas a decisão de onde investir a seguir
(V5 = GraalVM, V6 = fd-passing) depende do número real, não da estimativa — porque o
balanço entre `score_p99` e `score_det` pós-V4 decide qual alavanca tem mais retorno.

Este gate é **HITL**: requer rodar o benchmark, ler os números e escrever a decisão.

## Critérios de aceite

- [ ] Benchmark de **5 boots frios** completos executado com o artefato pós-Issue 05
      (`down → up → /ready → K6` a cada rodada), seguindo a metodologia da V3.
- [ ] Resultados registrados em `docs/knowledge/v4/01-veritas.md` (seção "Resultados"):
      `p99` mediana + spread, `score_det`, `score_p99`, `final_score`, FP/FN por rodada.
- [ ] Comparação explícita com o baseline pré-V4 (score_det ~1.335, final_score ~2.862).
- [ ] Decisão registrada: qual versão entra a seguir (V5 ou V6) e com qual justificativa
      baseada nos números medidos.

## Bloqueada por

Issue 05 (bbox pruning no search) — o benchmark só é significativo com a busca exata
implementada e os testes de qualidade verdes.