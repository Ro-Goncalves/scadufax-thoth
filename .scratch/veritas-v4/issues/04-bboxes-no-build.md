# Issue 04 — V4-A Passo 2: Bounding boxes no artefato de build

Status: open

## Issue pai

[PRD: Tesseract V4 — Veritas](../PRD.md)

## O que construir

Persistir `bboxMin[DIMS]` e `bboxMax[DIMS]` por cluster no diretório do artefato `.v2`,
para que o buscador possa calcular o lower-bound geométrico em runtime sem reler o
dataset.

Hoje o diretório armazena, por cluster: centróide + radius + offset + count (30 bytes
para i8, 44 para i16). Após esta issue, cada entrada passa a incluir também os limites
por dimensão (bboxes), calculados durante a fase de atribuição dos vetores ao cluster.

### Layout estendido do diretório (por cluster)

```
[centróide: 14 bytes (i8) | 28 bytes (i16)]
[radius float: 4 bytes]
[offset long: 8 bytes]
[count int: 4 bytes]
[bboxMin: 14 bytes (i8) | 28 bytes (i16)]
[bboxMax: 14 bytes (i8) | 28 bytes (i16)]
Total: 58 bytes (i8) | 100 bytes (i16)
```

`VERSION` permanece `2` — o artefato é sempre reconstruído no build, sem necessidade
de retrocompatibilidade com artefatos antigos.

## Critérios de aceite

- [ ] O artefato gerado inclui `bboxMin` e `bboxMax` para cada cluster.
- [ ] **Teste de propriedade:** para um artefato de fixture conhecido, nenhum vetor
      atribuído a um cluster tem qualquer dimensão menor que `bboxMin[cluster][d]`
      nem maior que `bboxMax[cluster][d]`. Cobre todos os clusters da fixture.
- [ ] O `V2IndexSearcher` lê e carrega os bboxes sem erro.
- [ ] O `CLUSTER_ENTRY_SIZE` atualizado reflete o novo layout (58 i8 / 100 i16).
- [ ] `V2QualityGuardTest` e testes existentes permanecem verdes (os bboxes ainda
      não afetam o resultado da busca; o pruning é da Issue 05).
- [ ] O tempo de build não aumenta de forma significativa (os bboxes são calculados
      na passagem que já ocorre, sem iteração extra).

## Bloqueada por

Issue 03 (investigação de quantização) — a decisão de dtype precisa estar fechada
antes de persistir bboxes, para gravar no tamanho correto (14 vs. 28 bytes).