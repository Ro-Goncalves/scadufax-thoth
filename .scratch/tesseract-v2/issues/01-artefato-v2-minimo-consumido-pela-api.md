# Issue 01: Artefato V2 mínimo consumido pela API

Status: done
Tipo: AFK

## O que construir

Entregar o primeiro caminho completo da V2: o build gera o artefato binário V2 com cabeçalho versionado, diretório de clusters mínimo, registros com label binária e vetor quantizado com sentinela -128, e a aplicação sobe consumindo esse artefato sem quebrar o POST /fraud-score.

Nesta fatia, o foco é provar o novo formato de ponta a ponta. O caminho de busca ainda pode permanecer simples, desde que a API leia exclusivamente o artefato V2 e preserve o comportamento externo esperado.

## Critérios de aceite

- [x] O pipeline de build gera um artefato V2 válido, com versão identificável, dimensões coerentes, label binária e tratamento explícito do sentinela de ausência de last_transaction.
- [x] A aplicação inicializa usando apenas o artefato V2 e o endpoint POST /fraud-score continua aceitando e respondendo com o mesmo contrato externo.
- [x] Existe validação automatizada cobrindo o caminho build -> bootstrap -> requisição HTTP sobre um dataset pequeno, incluindo ao menos um caso com last_transaction ausente.

## Bloqueada por

[Issue 00: Quantização baseline e plano B int16](00-quantizacao-baseline-e-plano-b-int16.md)

## Comments

- 2026-06-01: issue criada a partir do [PRD](../PRD.md) após aprovação da decomposição em fatias.

## Referências

- [PRD Completo](../PRD.md)
- [Documentação Técnica da V2](../knowledge/v2/01-tesseract.md)