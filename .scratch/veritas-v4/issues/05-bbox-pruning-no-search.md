# Issue 05 — V4-A Passo 3: Bounding-box pruning no search

Status: open

## Issue pai

[PRD: Tesseract V4 — Veritas](../PRD.md)

## O que construir

Transformar a busca IVF aproximada em **busca provadamente exata**, iterando todos os
clusters restantes após os `nprobe` iniciais e podando geometricamente os que não
podem melhorar o top-k atual.

Hoje o `search` visita exatamente `nprobe=4` clusters e retorna. Queries na fronteira
entre clusters têm vizinhos reais em clusters não visitados — gerando os 105 FP e
102 FN da V3. Com bbox pruning, todos os clusters são considerados mas ~95% são
descartados sem varrer nenhum vetor: se o lower-bound geométrico do cluster for maior
que a pior distância atual no top-k, é matematicamente impossível que qualquer vetor
dentro do cluster melhore o resultado.

### Mudanças no `V2IndexSearcher.search()`

Após o loop dos `nprobe` clusters iniciais (que não muda), adicionar:

```java
// Prova por desigualdade triangular: se lb > worstDist, nenhum
// ponto dentro do bbox pode melhorar o top-k atual.
for (int ci = nprobe; ci < numClusters; ci++) {
    int cluster = ranked[ci];
    if (bboxLowerBound(q, bboxMin[cluster], bboxMax[cluster]) > selector.worstDist()) {
        continue;
    }
    // varrer o cluster normalmente (mesmo código do loop inicial)
}
```

O lower-bound opera em aritmética inteira pura (não float), preservando o ganho da
quantização no caminho de pruning. Para cada dimensão: se a query está dentro do
bbox → contribuição 0; se está fora → `(diff ao lado mais próximo)²`.

## Critérios de aceite

- [ ] **Guarda de qualidade com 0 divergências:** `V2QualityGuardTest` extendido para
      exigir acordo de 100% com float32 brute-force (não apenas o threshold mínimo da
      V2). Toda query deve retornar os mesmos vizinhos da busca exata.
- [ ] `TopKSelector.worstDist()` adicionado e coberto por teste: após inserções
      variadas, retorna `topDist[k-1]` e evolui corretamente conforme candidatos
      melhores entram.
- [ ] A interface `VectorSearcher` permanece inalterada; testes existentes não mudam
      de assinatura.
- [ ] Zero alocação por candidato varrido no novo loop (confirmado por inspeção; não
      regride o trabalho do V3-D).
- [ ] `V2QualityGuardTest` e `V2IvfSearchTest` existentes permanecem verdes.

## Bloqueada por

Issue 04 (bboxes no build) — o searcher precisa dos bboxes carregados no header para
executar o pruning.