# Issue 02: Busca IVF real no caminho da requisição

Status: done
Tipo: AFK

## O que construir

Substituir o caminho de consulta do Runtime por busca IVF de fato. Com o artefato V2 já sendo produzido e carregado, a requisição deve selecionar os clusters mais promissores, visitar apenas a quantidade configurada por nprobe e ranquear os melhores candidatos para calcular fraud_score.

O contrato HTTP e a decisão final permanecem inalterados; a diferença desta fatia é que o caminho principal deixa de varrer a base inteira e passa a operar sobre o particionamento da V2.

## Critérios de aceite

- [x] Em um artefato com múltiplos clusters, a requisição percorre apenas os agrupamentos selecionados para aquela query e continua produzindo uma resposta válida no mesmo contrato HTTP.
- [x] A fatia suporta rodadas experimentais com diferentes valores de K e nprobe sem exigir reescrita do motor de busca.
- [x] Existem testes automatizados cobrindo a seleção de clusters e a resposta final do endpoint sobre um fixture clusterizado.

## Bloqueada por

- [Issue 01: Artefato V2 mínimo consumido pela API](01-artefato-v2-minimo-consumido-pela-api.md)

## Comments

- 2026-06-01: issue criada a partir do [PRD](../PRD.md) após aprovação da decomposição em fatias.

## Referências

- [PRD Completo](../PRD.md)
- [Documentação Técnica da V2](../knowledge/v2/01-tesseract.md)