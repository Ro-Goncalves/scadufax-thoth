# Issue 04 — V3-D: Seletor top-k extraído + busca sem alocação

Status: done

## Issue pai

[PRD: Tesseract V3 — Celeritas](../PRD.md)

## O que construir

Eliminar o garbage por candidato na varredura da busca. Hoje a busca usa um *Max-Heap* de
objetos e aloca um objeto de resultado + uma `String` de label **por candidato varrido**
(~11.720 por requisição em K=1024/nprobe=4) — a maior fonte de pressão sobre o GC.

Extrair um **seletor top-k** como módulo isolado e testável, que mantém os k menores via
*insertion sort* sobre dois arrays primitivos paralelos (distância e label int8), expondo
`tryInsert(distância, label)` e a materialização final dos k resultados. O searcher deixa
de usar o *Max-Heap* e passa a alimentar o seletor com `(distância, label-byte)` por
candidato; a construção de objetos de resultado e de `String` de label acontece **uma
única vez no final**, ao materializar os k resultados.

A interface pública do searcher (`List<SearchResult> search(float[], int)`) **não muda** —
a refatoração é interna e a semântica (mesmos vizinhos, mesma ordem crescente de
distância) é idêntica. O seletor não pode manter estado mutável de instância no searcher
(compartilhado entre requisições em virtual threads) e não usa `ThreadLocal`; seus arrays
são locais à chamada.

## Critérios de aceite

- [x] Existe um módulo de seletor top-k isolado, com `tryInsert` e materialização dos k
      resultados, testável sem subir um searcher inteiro.
- [x] A busca não aloca objeto de resultado nem `String` de label por candidato varrido;
      a materialização dos k resultados ocorre uma única vez ao final.
- [x] A interface `VectorSearcher` (`List<SearchResult> search`) permanece inalterada.
- [x] O seletor não introduz estado mutável compartilhado nem `ThreadLocal`.
- [x] **Teste de equivalência:** para entradas variadas (distâncias aleatórias, empates,
      mais/menos candidatos que k), o seletor retorna exatamente os mesmos k vizinhos e na
      mesma ordem que uma referência (Max-Heap/ordenação completa).
- [x] `V2QualityGuardTest` e `V2IvfSearchTest` permanecem verdes (recall e resultado da
      busca inalterados).

## Bloqueada por

Nenhum - pode começar imediatamente.
