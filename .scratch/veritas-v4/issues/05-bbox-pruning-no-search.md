# Issue 05 — V4-A Passo 3: Bounding-box pruning no search

Status: done

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

- [x] **Guarda de qualidade com 0 divergências:** `V2QualityGuardTest` extendido para
      exigir acordo de 100% com float32 brute-force (não apenas o threshold mínimo da
      V2). Toda query deve retornar os mesmos vizinhos da busca exata.
- [x] `TopKSelector.worstDist()` adicionado e coberto por teste: após inserções
      variadas, retorna `topDist[k-1]` e evolui corretamente conforme candidatos
      melhores entram.
- [x] A interface `VectorSearcher` permanece inalterada; testes existentes não mudam
      de assinatura.
- [x] Zero alocação por candidato varrido no novo loop (confirmado por inspeção; não
      regride o trabalho do V3-D).
- [x] `V2QualityGuardTest` e `V2IvfSearchTest` existentes permanecem verdes.

## Bloqueada por

Issue 04 (bboxes no build) — o searcher precisa dos bboxes carregados no header para
executar o pruning.

## Comments

### Entrega (2026-06-04)

Implementado e fechado. Mudanças:

- `search/TopKSelector.java` — `worstDist()` devolve `topDist[k-1]` (e `Double.MAX_VALUE`
  enquanto o top-k não enche, garantindo que nada seja podado prematuramente).
- `search/V2IndexSearcher.java` — `search()` ganha o segundo laço de poda após os
  `nprobe` iniciais; corpo de varredura extraído em `scanClusterI8`/`scanClusterI16`
  (reuso pelos dois laços, sem alocação por candidato); `bboxLowerBound(...)` novo.
- `search/TopKSelectorTest.java`, `V2QualityGuardTest.java` — testes (abaixo).
- `docs/knowledge/v4/03-bounding-boxes.md` — nova seção 7 (algoritmo de busca com poda)
  e correção do lower bound para `long`.

**Desvio técnico do protótipo (intencional):** o `bboxLowerBound` acumula em `long`, não
`int`. Em i16 a soma de quadrados ultrapassa `Integer.MAX_VALUE` (igual ao
`calculateI16`); truncar para `int` daria um lower bound menor que a distância real e
quebraria a exatidão da poda. A comparação `lb > worstDist()` é exata (inteiros < 2⁵³).

### Análise dos critérios de aceite

1. **Guarda de qualidade com 0 divergências** ✅ — `V2QualityGuardTest` agora compara,
   por query, a lista de vizinhos do IVF podado (`nprobe=2`) com a do full-scan
   (`nprobe=K`, oráculo que nunca poda) e exige igualdade (distância + label, em ordem);
   além de `assertEquals(0, divPartitioning)` e `assertEquals(0, divQuantization)`.
   Saída medida (i8 **e** i16): acordo quantização 100%, acordo IVF 100%, divergências
   `quantização=0 particionamento=0`.
2. **`TopKSelector.worstDist()` testado** ✅ — novo teste
   `worstDist_retornaTopDistKMenos1EEvoluiComCandidatosMelhores`: `Double.MAX_VALUE`
   antes de k inserções, `topDist[k-1]` ao encher, decréscimo monotônico quando entra
   candidato melhor, e estabilidade quando entra candidato pior.
3. **`VectorSearcher` inalterada** ✅ — assinatura intacta; tudo novo é privado
   (`scanClusterI8/I16`, `bboxLowerBound`) ou método de instância no `TopKSelector`.
   Nenhum teste mudou de assinatura.
4. **Zero alocação por candidato** ✅ — o laço de poda faz uma chamada por cluster
   sobrevivente; `scanClusterI8/I16` reusam o corpo do V3-D (inserção direta nos arrays
   primitivos do `TopKSelector`, sem `new`); `bboxLowerBound` é estático sobre `int[]`
   já existentes. Sem nova alocação por vetor varrido.
5. **`V2QualityGuardTest` e `V2IvfSearchTest` verdes** ✅ — ambos passam; suíte completa
   `mvn test` → **53 testes, 0 falhas**.

### Fora do escopo (follow-up)

Gate pós-V4-A (5 boots frios medindo `score_det`/`final_score` antes/depois) — PRD
stories 24-25. Não é critério de aceite desta issue.