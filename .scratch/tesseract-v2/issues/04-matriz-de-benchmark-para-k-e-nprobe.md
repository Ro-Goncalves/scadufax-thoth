# Issue 04: Matriz de benchmark para K e nprobe

Status: done
Tipo: AFK

## O que construir

Empacotar uma matriz de benchmark para as combinações previstas de K e nprobe no ambiente realista da Rinha. A fatia deve executar a API V2 de ponta a ponta sob limites de CPU e memória, coletar p99, erros HTTP, taxa de falha e qualidade de detecção e consolidar o resultado para suportar a decisão final.

O foco aqui é transformar os parâmetros experimentais em uma decisão guiada por medição, e não por impressão local isolada.

## Critérios de aceite

- [x] Existe um fluxo reproduzível para executar as combinações planejadas de K e nprobe no ambiente local equivalente ao da competição.
- [x] Cada execução gera um resumo comparável com p99, erros HTTP, taxa de falha e impacto observado na qualidade de detecção.
- [x] O resultado final explicita quais combinações seguem recomendadas e quais combinações foram descartadas com base nos dados coletados.

## Bloqueada por

- [Issue 03: Comparação exata e guarda de qualidade](03-comparacao-exata-e-guarda-de-qualidade.md)

## Comments

- 2026-06-01: issue criada a partir do [PRD](../PRD.md) após aprovação da decomposição em fatias.

## Referências

- [PRD Completo](../PRD.md)
- [Documentação Técnica da V2](../knowledge/v2/01-tesseract.md)