# Veritas: Plano de Execução

## Objetivo

A V3 (Celeritas) eliminou o overhead de latência: page faults frios, alocação de
resposta e garbage no hot path da busca. O resultado foi que o `score_p99` saturou,
com medianas na faixa de 13–30ms dependendo do envelope.

O gargalo que sobrou é **detecção**. Nossos 105 FP + 102 FN comprimem o `score_det`
para ~1.335, enquanto o teto teórico (0 FP/FN) vale ~2.000+. A causa é estrutural: o
IVF com nprobe=4 visita só 0,4% dos clusters; queries na fronteira entre clusters têm
seus vizinhos reais em clusters não visitados.

A V4 (Veritas) tem um único objetivo: **transformar a busca aproximada em busca
provadamente exata**, via bounding-box pruning por cluster. Confirmado por AndDev741
como técnica responsável pelos seus 0 FP / 0 FN e score=4.056 — com p99=87ms, 2× mais
lento que nós, mas 1,5× melhor em score.

**Impacto esperado:** `score_det` ~1.335 → ~2.000+; `final_score` ~2.862 →
**3.600+**.

---

## Escopo acordado

| Item | O que é | Status |
|---|---|---|
| **V4-0** | Diagnóstico de percentis (p50/p95 no K6) | Entra |
| **V4-A** | Bounding-box pruning (busca exata) ⭐ | Entra |
| ↳ Passo 0-A | Parametrização i8/i16 (`ARG DTYPE` no build) | Entra — pré-condição do Passo 1 |
| ↳ Passo 1 | Investigação de quantização: int8-BF vs float32-BF | Entra — decide a escala usada |
| ↳ Passo 2 | Build time: persistir `bboxMin`/`bboxMax` por cluster | Entra |
| ↳ Passo 3 | Query time: pruning iterando todos os clusters | Entra |
| **V4-B** | IVF repair | ❌ Cortado — subsumido pelo V4-A |
| **V4-C** | Parser JSON custom (zero-alocação) | Condicionado ao V4-0 |
| **V4-D** | Insertion sort K=5 | ✅ Entregue na V3-D |
| **V4-E** | Servidor HTTP NIO custom | ➡️ Movido para a V6 |

### Por que o V4-B saiu

O repair (lucasmontano) reescaneia clusters quando o IVF *aproximado* devolve resultado
ambíguo. O V4-A, ao iterar todos os clusters com poda por bbox, já entrega top-5
*exato* — não sobra ambiguidade para reparar. O V4-B resolve um problema que o V4-A
elimina.

---

## Decisões de configuração

### Envelope operacional

Mantemos **K=1024 / nprobe=4** (Dockerfile e docker-compose já corretos desde o
pré-requisito da V3).

A matriz exploratória da V3 mostrou K=2048/nprobe=2 potencialmente mais alto (3.090
vs. 2.862), mas essa decisão fica em aberto até o gate pós-V4-A — quando o score real
com busca exata estiver na mão. Com bbox pruning, `nprobe` deixa de afetar *correctness*
(todos os clusters são visitados; os que o lower-bound não poda são varridos de
qualquer jeito). O `nprobe` vira somente um parâmetro de "quantos clusters varrer antes
de começar o pruning", e reavaliar K×nprobe faz mais sentido com os novos números.

### Formato do artefato

Optamos por **estender o diretório de clusters sem bumpar a versão**:

- `VERSION` permanece `2`; o byte `dtype` no header identifica i8 vs. i16.
- `CLUSTER_ENTRY_SIZE` cresce: i8 → **58 bytes** (+14 `bboxMin` +14 `bboxMax`);
  i16 → **100 bytes** (+28 `bboxMin` +28 `bboxMax`).

Artefatos construídos com a versão anterior (sem bboxes) são incompatíveis, mas são
sempre reconstruídos no `docker build`. Não há necessidade de retrocompatibilidade em
runtime.

---

## V4-0: Diagnóstico de percentis

### Problema

Os benchmarks da V3 mostraram spread residual no p99 (17–52ms em 5 rodadas). A causa
não está identificada — existem duas hipóteses:

- **Hipótese A (cauda de GC):** p50/p95 estáveis; só o p99 salta. O Jackson ainda
  aloca no parse de entrada (~900 req/s → pressão suficiente para coletas da geração
  jovem).
- **Hipótese B (host sem CPU):** p50/p95 sobem junto com o p99. É ruído de ambiente,
  não do código — nenhuma otimização de GC ajuda aqui.

### Implementação

Uma linha no K6:

```js
// test/test.js
summaryTrendStats: ['p(50)', 'p(95)', 'p(99)'],
```

### Critério de decisão

| Resultado | Diagnóstico | Consequência para o escopo |
|---|---|---|
| Só o p99 balança (p50/p95 estáveis) | Cauda de GC | V4-C entra no escopo |
| p50/p95 balançam junto com o p99 | Host sem CPU / ruído | V4-C fica fora do escopo |

### Resultado (2026-06-03)

5 boots frios completos com K=1024 / nprobe=4:

| Run | p50 | p95 | p99 | HTTP errors |
|---|---|---|---|---|
| 1 | 0.67ms | 2.57ms | 38.51ms | 0 |
| 2 | 0.69ms | 2.93ms | 48.11ms | 0 |
| 3 | 0.68ms | 2.46ms | 30.67ms | 0 |
| 4 | 0.68ms | 2.23ms | 14.09ms | 0 |
| 5 | 0.68ms | 2.25ms | 12.38ms | 0 |

**Diagnóstico: Hipótese A confirmada.**

- **p50:** congelado em 0.67–0.69ms (spread 0.02ms) — zero variância.
- **p95:** estável em 2.23–2.93ms (spread 0.70ms).
- **p99:** oscila entre 12.38ms e 48.11ms (spread 35.73ms) — cauda completamente instável.

O corpo da distribuição é rápido e constante; só a cauda balança. Assinatura
inequívoca de pausa de GC: o Jackson aloca no parse de entrada a ~900 req/s,
pressão suficiente para coletas frequentes da geração jovem. Quando o GC roda,
as poucas requisições em voo durante a pausa caem todas na cauda do p99.

**Decisão: V4-C entra no escopo.**

---

## V4-A: Bounding-box pruning ⭐

### O que é e por que funciona

O IVF atual seleciona `nprobe=4` clusters e varre só eles — busca *aproximada*. Queries
na fronteira entre clusters têm seus vizinhos reais em clusters não visitados.

O bbox pruning não aumenta o nprobe: ele itera **todos** os clusters restantes, mas
poda geometricamente aqueles que não podem conter nenhum vizinho melhor que o pior
candidato já encontrado. A prova é por desigualdade triangular: se o lower-bound
geométrico do cluster for maior que a distância atual do k-ésimo vizinho, nenhum vetor
dentro do cluster pode melhorá-lo.

```java
private int bboxLowerBound(int[] query, int[] bboxMin, int[] bboxMax) {
    int lb = 0;
    for (int d = 0; d < DIMS; d++) {
        int q = query[d];
        int lo = bboxMin[d], hi = bboxMax[d];
        if      (q < lo) { int diff = lo - q; lb += diff * diff; }
        else if (q > hi) { int diff = q - hi; lb += diff * diff; }
    }
    return lb;
}
```

Se `lb > worstDist` (distância do k-ésimo vizinho atual) → cluster podado. Com ~95%
de pods esperados (referência: AndDev741), a busca vira exata sem custo equivalente ao
brute force.

### Passo 0-A: parametrização i8/i16

Antes de codar o pruning, parametrizamos o `dtype` no pipeline de build. O objetivo é
poder rodar a investigação de quantização (Passo 1) e comparar os dois configs com um
único `docker build`.

**O que já existe (não muda):**

- `DistanceCalculator.calculateI16(short[], MemorySegment, ...)` — interface definida.
- `EuclideanDistanceCalculator.calculateI16()` — implementada; opera em `long` para
  não estourar int (máx. 14 × 20.000² = 5,6 × 10⁹).

**O que muda:**

`V2ArtifactBuilder`:

| Constante | i8 (atual) | i16 (novo) |
|---|---|---|
| `DTYPE` | `1` | `2` |
| `SCALE` | `127` | `10.000` |
| `RECORD_SIZE` | `16` (1+14+1) | `30` (1+28+1) |
| `CLUSTER_ENTRY_SIZE` | `58` (pós-V4-A) | `100` (pós-V4-A) |
| Sentinela (`-1.0f`) | `Byte.MIN_VALUE` | `Short.MIN_VALUE` |

Método `encodeI16()`: espelha o `encodeI8()` existente, substituindo `byte` por `short`
e `SCALE=127` por `SCALE=10.000`.

Parâmetro `Dtype dtype` adicionado ao `build()`.

`V2IndexSearcher`:

- `readHeader()` aceita ambos os dtypes (remove a validação rígida atual).
- Centróides internos como `int[][]` (widened de `byte` ou `short` — unifica o código
  de distância).
- Query quantizada como `int[]` no hot path; despacha para `calculateI8` ou
  `calculateI16` com base no `dtype` lido no header.

`Dockerfile`:

```dockerfile
ARG DTYPE=i8
```

Passado como argumento para `V2ArtifactBuilder` na fase de build.

**Fluxo de benchmark com dois dtypes:**

```bash
# Build i8 (padrão atual)
docker build --build-arg DTYPE=i8 -t api-i8 .

# Build i16 (para comparação)
docker build --build-arg DTYPE=i16 -t api-i16 .
```

### Passo 1: investigação de quantização

**Pergunta:** quantos dos 207 erros (105 FP + 102 FN) são de *quantização* (int8 perde
precisão) e quantos são de *aproximação* (IVF não visita o cluster certo)?

Se quase todos os erros somem com int8 full-scan (`nprobe = numClusters`), o problema é
particionamento → fica int8. Se os erros persistem mesmo no full-scan, é quantização →
passa para int16.

**Experimento:** comparar no dataset real (`references.json.gz`):

1. float32 brute-force — ground truth
2. int8 full-scan (`nprobe = K = 1024`) — isola perda de quantização
3. int16 full-scan (`nprobe = K = 1024`) — isola ganho de precisão extra

**Critério de decisão:**

| Resultado | Decisão |
|---|---|
| int8 full-scan: divergências < ~10 | Fica **int8** — erro era só particionamento |
| int8 full-scan: divergências > ~20 | Migra para **int16** |

**Implicação de migrar para int16:** artefato cresce de ~42 MB para ~84 MB; bboxes
crescem de 28 KB para 56 KB adicionais. O budget de ~195 MB de page cache acomoda os
dois, mas deve-se verificar que o artefato i16 ainda cabe confortavelmente dentro do
limite de memória do contêiner (165 MB × 2 instâncias + nginx).

### Passo 2: build time — bboxes por cluster

Para cada cluster, após a fase de atribuição do K-means, calcular e persistir:

- `bboxMin[DIMS]`: mínimo por dimensão sobre todos os vetores do cluster.
- `bboxMax[DIMS]`: máximo por dimensão sobre todos os vetores do cluster.

**Layout do diretório estendido (entrada por cluster):**

```
[centróide int8 (14 bytes) | int16 (28 bytes)]
[radius float (4 bytes)]
[offset long (8 bytes)]
[count int (4 bytes)]
[bboxMin (14 ou 28 bytes)]
[bboxMax (14 ou 28 bytes)]
──────────────────────────────────
Total: 58 bytes (i8)  |  100 bytes (i16)
```

**Mudanças no `V2ArtifactBuilder`:**

- Calcular `bboxMin` e `bboxMax` na fase de distribuição dos vetores por cluster
  (mesmo laço que já conta `clusterCount` e `clusterOffset`).
- Persistir logo após `count` em cada entrada do diretório.

### Passo 3: query time — pruning

No `V2IndexSearcher.search()`, após varrer os `nprobe` clusters iniciais:

```java
for (int ci = nprobe; ci < numClusters; ci++) {
    int cluster = ranked[ci];
    if (bboxLowerBound(q, bboxMin[cluster], bboxMax[cluster]) > selector.worstDist()) {
        continue;
    }
    // varrer cluster normalmente
}
```

`TopKSelector` ganha o método `worstDist()` que retorna `topDist[k-1]` (a pior
distância no top-k atual).

**Nota sobre a ordem de iteração:** a ordem dos clusters restantes não afeta
*correctness*, só a velocidade de convergência do pruning. A primeira implementação
itera na ordem do ranking (mais próximos do centróide primeiro), que tende a popular
o top-k com bons candidatos rapidamente e acelera a poda dos seguintes.

**Mudanças necessárias:**

| Arquivo | O que muda |
|---|---|
| `V2ArtifactBuilder.java` | Calcular e gravar `bboxMin`/`bboxMax`; suporte a i16 |
| `V2IndexSearcher.java` | Ler bboxes no `readHeader()`; loop de pruning; dtype-aware |
| `TopKSelector.java` | Adicionar `worstDist()` |
| `V2QualityGuardTest.java` | Asserção de 0 divergências contra float32-BF |
| `Dockerfile` | `ARG DTYPE=i8` |

---

## V4-C: Parser JSON custom (condicionado ao V4-0)

> **Entra no escopo somente se o V4-0 confirmar cauda de GC (Hipótese A).**

O Jackson no parse de **entrada** aloca por request. Para schema fixo (campos
`account_id`, `amount`, `last_transaction`, `transaction_date`, `vector`), um parser
cursor-based é 10–20× mais rápido e zero-alocação.

**Técnica central:** ISO-8601 → epoch seconds sem `Instant`/`ZonedDateTime`,
usando o algoritmo de Howard Hinnant (`daysFromCivil`). O caller passa `float[]`
pré-alocado; o parser escreve in-place — zero `new` no hot path.

**Referências de implementação:**

- `JsonReader.java` do AndDev741 (~300 linhas) — Java, cursor-based
- `FraudRequestParser.java` do arthurd3 — single-pass com índice de chaves

**Bônus:** remove reflexão do Jackson → simplifica o caminho para V5 (GraalVM Native
Image).

---

## Como vamos medir

1. **V4-0:** rodar `run-benchmark.sh` com p50/p95 adicionados; comparar 3–5 runs;
   registrar se é Hipótese A ou B.
2. **Passo 0-A:** builds i8 e i16 separados; rodar `V2QualityGuardTest` nos dois para
   validar que a infra está correta antes de avançar.
3. **Passo 1:** executar comparação int8-full-scan vs. float32-BF no dataset real;
   contar divergências; registrar decisão de dtype; **não avançar para o Passo 2 sem
   este número**.
4. **Passo 2+3:** rebuild com bboxes; rodar benchmark de 5 boots frios; comparar
   `score_det` pré e pós-V4-A; medir se `score_p99` regrediu (bbox acrescenta
   iterações — esperado custo < 5ms).

---

## Critérios de aceite

- **Guarda de qualidade:** `V2QualityGuardTest` verde com asserção de **0 divergências**
  contra float32-BF (não apenas o threshold de acordo mínimo da V2 — exatamente zero).
- **Zero erros HTTP** no K6.
- **`score_det` sobe** de ~1.335 em direção a ~2.000+ (confirmado pelos números da
  rinha, não apenas pelo teste local).
- **`score_p99` não regride** mais que ~5ms em relação à mediana da V3.

---

## Sequência de implementação

```
1. V4-0     — adicionar p50/p95 ao K6 → rodar benchmark → registrar Hipótese A ou B
              ↓
2. Passo 0-A — parametrizar dtype no V2ArtifactBuilder e no V2IndexSearcher
               (ARG DTYPE=i8 no Dockerfile)
              ↓
3. Passo 1  — investigação de quantização (int8-BF vs float32-BF no dataset real)
               → decidir dtype → não avançar sem este número
              ↓
4. Passo 2  — bboxes no build (calcular min/max por cluster e persistir no diretório)
              ↓
5. Passo 3  — pruning no search (ler bboxes, iterar todos os clusters com poda)
              TopKSelector.worstDist()
              ↓
6.          — validar V2QualityGuardTest com asserção 0 divergências
              ↓
7.          — benchmark 5 boots frios → registrar score_det e final_score

[GATE] — medir score; replanejar V5/V6 com o número novo

8. V4-C     — (somente se V4-0 confirmou GC) parser JSON custom
```

---

## Resultados

### V4-0 — Diagnóstico de percentis ✅

Hipótese A confirmada (cauda de GC). Detalhes e tabela de runs na seção V4-0 acima.
**V4-C entra no escopo.**

### V4-A — Bounding-box pruning

> A medir após implementação (Issues 04 e 05).

### V4-C — Parser JSON custom

> A medir após implementação (Issue 06).

---

## Pendências

- **Atualizar `run-benchmark.sh` e `summarize-benchmarks.sh` para p50/p95:** hoje o
  script captura só `p99_ms` dos JSONs de resultado. Com o `test.js` emitindo
  `p50_ms` e `p95_ms`, o pipeline de benchmark deve: (1) extrair e acumular os novos
  campos nos arrays de agregação; (2) incluir p50/p95 no `aggregate.json`;
  (3) revisar se todas as métricas atuais do `summarize` são necessárias (recall%,
  precision%, F1%, FPR% — avaliar o que realmente importa para a rinha vs. ruído
  visual). **Fica para o fim de tudo (pós-V4-C).**