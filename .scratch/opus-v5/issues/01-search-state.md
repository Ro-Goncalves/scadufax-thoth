# Issue 01: ThreadLocal\<SearchState\> no V2IndexSearcher

Status: done

## Issue pai

[PRD: V5 — Opus](../PRD.md) — histórias de usuário 1–10.

## O que construir

Eliminar as ~12KB de alocação por request no hot path de busca introduzindo um
`ThreadLocal<SearchState>` no `V2IndexSearcher`.

**`SearchState`** é uma inner class com todos os arrays que hoje são alocados por
chamada a `search()`:

- `int[] qi` — query quantizada (DIMS elementos)
- `short[] q16` — query em i16 (DIMS elementos)
- `long[] distAndIdx` — pacote distância+índice para o sort de clusters (numClusters elementos)
- `int[] ranked` — índices de clusters ordenados por distância ao centróide (numClusters elementos)
- `double[] topDist` — distâncias do top-k atual (K\_NEIGHBORS elementos)
- `byte[] topLabel` — labels do top-k atual (K\_NEIGHBORS elementos)

O construtor de `SearchState` recebe `numClusters` como parâmetro — lido do artefato
`.v2` no construtor do searcher, sem constante hardcoded.

O `ThreadLocal` é inicializado no construtor do `V2IndexSearcher`, capturando
`this.numClusters` via variável efetivamente final.

Os helpers `quantizeQuery`, `toI16Query` e `rankClusters` mudam a assinatura para
receber os arrays de destino e escrever in-place. São métodos privados — a interface
`VectorSearcher` e o método público `search(float[], int)` não mudam.

O `TopKSelector` ganha `reset()` que restaura `size=0` e preenche `topDist` com
`Double.MAX_VALUE`. Os campos `topDist` e `topLabel` migram para `SearchState`; o
`TopKSelector` passa a operar sobre os arrays passados por referência. O comportamento
externo de `tryInsert`, `worstDist` e `materialize` é preservado.

`search()` extrai `searchState.get()`, chama reset no estado de TopK, e passa os
arrays pré-alocados para os helpers. O `materialize()` continua alocando 6 objetos
curtos (ArrayList + 5 SearchResult) — Eden trivial, fora do escopo desta issue.

## Critérios de aceite

- [x] `mvn -q compile` passa sem erros ou warnings novos
- [x] `V2QualityGuardTest` verde — busca exata mantida (0 FP/FN, score\_det=3.000)
- [x] `V2IvfSearchTest` verde — recall preservado
- [x] `TopKSelectorTest` verde, incluindo novo caso para `reset()`: após inserções +
  reset, `worstDist()` retorna `Double.MAX_VALUE` e inserções posteriores produzem
  resultado idêntico a um `TopKSelector` recém-criado
- [x] `EuclideanDistanceCalculatorTest` verde (regressão)
- [x] `docker compose up` sobe a stack JVM e smoke K6 conclui verde (HTTP 200 em
  `/fraud-score` e `/ready`)
- [x] A interface `VectorSearcher` não foi modificada
- [x] Nenhum `new` dentro do corpo de `quantizeQuery`, `toI16Query`, `rankClusters`
  ou do loop de varredura de clusters em `search()`

## Bloqueada por

Nenhum — pode começar imediatamente.