# Issue 03 — V3-B: Respostas pré-serializadas + teste de contrato

Status: done

## Issue pai

[PRD: Celeritas](../PRD.md)

## O que construir

Eliminar a serialização e a alocação de resposta no hot path. O endpoint `/fraud-score`
só tem `K_NEIGHBORS + 1` respostas possíveis (uma por `fraudCount ∈ {0 .. K_NEIGHBORS}`).
Pré-serializá-las **uma vez no bootstrap** como uma tabela de `byte[]`, derivada de
`K_NEIGHBORS` e `FRAUD_THRESHOLD`, e devolver os bytes prontos por requisição — sem montar
objeto de resposta nem acionar o Jackson.

A tabela é um módulo puro: dado `(k, threshold)`, produz `byte[][]` indexado por
`fraudCount`, onde cada posição é o corpo JSON já em UTF-8 com `approved = (score <
threshold)` e `score = fraudCount / k`. No handler de busca, a resposta passa a ser a
escrita direta dos bytes da posição `fraudCount`, com content-type JSON.

A saída deve ser **byte-a-byte idêntica** à que o Jackson produzia hoje, para não regredir
o contrato com o avaliador da Rinha. Esta fatia trata **apenas a resposta**; o parse de
entrada (Jackson na desserialização do request) permanece e só muda no V4-C.

## Critérios de aceite

- [ ] A tabela de respostas é construída uma vez no bootstrap a partir de `K_NEIGHBORS` e
      `FRAUD_THRESHOLD` (nada hardcoded a "6 entradas").
- [ ] O `/fraud-score` devolve os bytes pré-serializados correspondentes ao `fraudCount`,
      sem acionar o Jackson e sem alocar `String`/objeto de resposta por requisição.
- [ ] A resposta é byte-a-byte idêntica à serialização anterior para todos os
      `K_NEIGHBORS + 1` casos.
- [ ] Mudar `K_NEIGHBORS`/`FRAUD_THRESHOLD` por env ajusta a tabela sem recompilar.
- [ ] **Teste de contrato:** para vários `K_NEIGHBORS`/`FRAUD_THRESHOLD`, cada `byte[]` da
      tabela é igual à serialização Jackson de referência do par `(approved, fraud_score)`,
      cobrindo os limites (`fraudCount=0` e `fraudCount=k`) e a fronteira do `threshold`.
- [ ] `V2EndToEndTest` (fluxo build → HTTP) permanece verde.

## Bloqueada por

Nenhum - pode começar imediatamente.
