# Bounding boxes: o que são, como funcionam e por que importam

> Documento de estudo da V4-A (Veritas), Passo 2 — Issue 04.
> Objetivo: que qualquer pessoa, inclusive quem está chegando agora no projeto,
> entenda o conceito de *bounding box* e consiga reproduzir o que foi feito.

---

## 1. O problema que estamos resolvendo

Nosso buscador é um **IVF** (Inverted File Index): em vez de comparar a query com os
~2,5 milhões de vetores do dataset (força bruta), nós:

1. agrupamos os vetores em `K` clusters (K-means, no build);
2. em runtime, ordenamos os clusters pela distância da query ao **centróide** de cada um;
3. varremos só os `nprobe` clusters mais próximos (hoje `nprobe=4` de `K=1024`).

Isso é rápido — visitamos 0,4% dos clusters — mas é uma busca **aproximada**. O risco
está nas *queries de fronteira*: uma transação cujo vizinho real mora num cluster que não
entrou nos 4 mais próximos do centróide. Para essa query, devolvemos o vizinho errado.

Foi exatamente isso que sobrou depois da migração para i16: os erros de *quantização*
caíram de 208 para 19, e esses **19 erros residuais (6 FP + 13 FN) são de
particionamento** — o `nprobe=4` não visita o cluster certo.

A pergunta da V4-A é: **dá para varrer menos clusters que a força bruta, mas mesmo assim
nunca errar?** A resposta é sim — com *bounding-box pruning*.

---

## 2. O que é uma bounding box

A **bounding box** (caixa delimitadora) de um cluster é a **menor caixa alinhada aos
eixos que contém todos os vetores daquele cluster**. Ela é descrita por dois vetores:

- `bboxMin[d]` = o **menor** valor que qualquer vetor do cluster tem na dimensão `d`;
- `bboxMax[d]` = o **maior** valor que qualquer vetor do cluster tem na dimensão `d`.

Em 2D, é fácil visualizar. Suponha um cluster com 4 pontos:

```
  y
  8 |
  7 |        ┌───────────────┐  ← bboxMax = (5, 7)
  6 |        │      ●        │
  5 |        │            ● │
  4 |        │   ●          │
  3 |        └──●────────────┘  ← bboxMin = (2, 3)
  2 |
  1 |
  0 +---------------------------- x
      0  1  2  3  4  5  6  7  8
```

A caixa vai de `x ∈ [2, 5]` e `y ∈ [3, 7]`. Todo ponto do cluster está dentro dela — é
isso que a caixa garante. Em 14 dimensões o desenho é impossível, mas a ideia é idêntica:
`bboxMin` e `bboxMax` têm 14 componentes cada, um par `[min, max]` por dimensão.

> Note que a caixa é uma **aproximação grosseira** do cluster: ela cobre o cluster, mas
> também cobre "cantos" vazios onde não há nenhum ponto. Isso é intencional e barato — e,
> como veremos, é suficiente para podar com segurança.

---

## 3. Por que a caixa permite busca exata

A mágica está num **limite inferior (lower bound)**. Para uma query `q` e a caixa de um
cluster, conseguimos calcular **a menor distância possível** entre `q` e *qualquer ponto
dentro da caixa* — sem olhar um único vetor do cluster.

A regra é, por dimensão:

- se `q[d]` está **à esquerda** da caixa (`q[d] < bboxMin[d]`), a caixa só "começa" em
  `bboxMin[d]`; a contribuição mínima daquela dimensão é `(bboxMin[d] − q[d])²`;
- se `q[d]` está **à direita** (`q[d] > bboxMax[d]`), a contribuição mínima é
  `(q[d] − bboxMax[d])²`;
- se `q[d]` está **dentro** da faixa (`bboxMin[d] ≤ q[d] ≤ bboxMax[d]`), aquela dimensão
  contribui **0** — a caixa pode chegar exatamente no valor de `q` naquela dimensão.

Somando as 14 dimensões, temos a distância (ao quadrado) de `q` até a caixa. É o nosso
`bboxLowerBound`:

```java
private int bboxLowerBound(int[] query, int[] bboxMin, int[] bboxMax) {
    int lb = 0;
    for (int d = 0; d < DIMS; d++) {
        int q = query[d];
        int lo = bboxMin[d], hi = bboxMax[d];
        if      (q < lo) { int diff = lo - q; lb += diff * diff; }
        else if (q > hi) { int diff = q - hi; lb += diff * diff; }
        // caso contrário (dentro da faixa) a dimensão contribui 0
    }
    return lb;
}
```

### Por que isso é um limite inferior de verdade

Todo ponto `p` do cluster está **dentro** da caixa. Em cada dimensão, a distância
`|q[d] − p[d]|` é **no mínimo** a distância de `q[d]` até a faixa `[min, max]` — porque
`p[d]` está nessa faixa. Como isso vale dimensão a dimensão, a distância total de `q` a
`p` é **no mínimo** a distância de `q` à caixa. Ou seja:

```
bboxLowerBound(q, cluster) ≤ distância(q, p)   para TODO p no cluster
```

### O critério de poda

Durante a busca mantemos os `k` melhores vizinhos até agora. Seja `worstDist` a distância
do **k-ésimo** (o pior dos melhores). Então:

> Se `bboxLowerBound(q, cluster) > worstDist`, **nenhum** ponto daquele cluster pode entrar
> no top-k — porque todos estão *mais longe* que o lower bound, que já é pior que o
> `worstDist`. Podemos **pular o cluster inteiro** com segurança.

### Exemplo numérico (2D)

Usando a caixa do desenho (`min=(2,3)`, `max=(5,7)`) e uma query `q=(8,5)`:

- dimensão x: `q.x = 8 > max.x = 5` → `diff = 3` → contribui `9`;
- dimensão y: `min.y = 3 ≤ q.y = 5 ≤ max.y = 7` → dentro → contribui `0`;
- `bboxLowerBound = 9` (distância 3).

Se o 5º vizinho atual está a `worstDist = 4` (distância 2), então `9 > 4` → **podamos** o
cluster. Não há ponto nele capaz de melhorar o top-5, e nem precisamos olhar.

### Por que vira busca exata

O pruning **não aumenta o `nprobe`**: ele itera **todos** os `K` clusters restantes, mas
*pula* com prova matemática os que não podem conter um vizinho melhor. Os que sobram são
varridos de verdade. O resultado é **idêntico ao da força bruta** (top-k exato), só que
visitando uma fração dos vetores — com ~95% de poda esperada (referência: AndDev741, que
faz 0 FP / 0 FN com essa técnica).

O `nprobe` deixa de afetar *correctness* e vira só um parâmetro de aquecimento: "quantos
clusters varrer primeiro para popular o top-k com bons candidatos antes de começar a
podar". Quanto melhor o top-k inicial, menor o `worstDist`, mais clusters são podados.

---

## 4. Em que espaço a caixa vive (e a sentinela)

Detalhe sutil e importante: a bbox é gravada **no mesmo espaço quantizado dos registros
armazenados**, não no espaço dos floats originais nem no espaço i8 do K-means.

- Num artefato **i8**, a caixa é de **bytes** (`[−128, 127]`).
- Num artefato **i16**, a caixa é de **shorts** (`[−32768, 32767]`).

O motivo é correção: em runtime o `bboxLowerBound` compara a **query já quantizada** (i8
ou i16) contra a caixa. Para o lower bound bater com a distância que o buscador realmente
calcula (`calculateI8` / `calculateI16`, que operam em inteiros, distância euclidiana ao
quadrado, sem `sqrt`), a caixa precisa estar no mesmo espaço dos registros.

**A sentinela entra como coordenada comum.** Quando `last_transaction` está ausente, a
dimensão recebe `−1.0f`, que é quantizado para `−128` (i8) ou `−32768` (i16). No cálculo
da bbox, esse valor entra no `min`/`max` como qualquer outro número. Isso é consistente:
a query quantiza a sentinela do mesmo jeito e o cálculo de distância a trata como um
inteiro. Logo o lower bound continua válido — não há tratamento especial, e não pode
haver, sob pena de quebrar a garantia de exatidão.

---

## 5. Layout do artefato: antes e depois

O artefato `.v2` tem três regiões: `[Header] [Diretório de Clusters] [Blocos de Registros]`.
Esta issue cresce **apenas o diretório** — cada entrada de cluster ganha `bboxMin` e
`bboxMax`.

### Entrada do diretório (por cluster)

| Campo      | i8 (bytes) | i16 (bytes) | Observação |
|------------|-----------:|------------:|------------|
| centróide  | 14 | 28 | bytes signed (i8) / shorts LE (i16) |
| radius     | 4  | 4  | `float`, não usado no IVF por distância |
| offset     | 8  | 8  | `long`, início do bloco relativo ao `dataOffset` |
| count      | 4  | 4  | `int`, nº de registros do cluster |
| **bboxMin**| **14** | **28** | **novo** — mesma codificação do centróide |
| **bboxMax**| **14** | **28** | **novo** — mesma codificação do centróide |
| **Total**  | **58** | **100** | era 30 (i8) / 44 (i16) |

Constantes correspondentes em `V2ArtifactBuilder`:

```java
public static final int CLUSTER_ENTRY_SIZE      = 58;  // i8  (era 30)
public static final int CLUSTER_ENTRY_SIZE_I16  = 100; // i16 (era 44)
```

### Quanto o artefato cresce

Só o diretório cresce, em `K × 2 × centroidBytes`:

- **i8**, K=1024: `1024 × 28 = 28.672 bytes` ≈ **28 KB**;
- **i16**, K=1024: `1024 × 56 = 57.344 bytes` ≈ **56 KB**.

Desprezível perto dos blocos de registros (~42 MB i8 / ~84 MB i16), que **não mudam**. O
`VERSION` permanece `2`: o artefato é sempre reconstruído no `docker build`, sem
retrocompatibilidade com formatos antigos.

### Cuidado com endianness (para quem for parsear o arquivo)

O formato mistura duas convenções, herança do `DataOutputStream`:

- **escalares** do header e do diretório (`version`, `dims`, `numClusters`, `radius`,
  `offset`, `count`) são **big-endian**;
- **vetores i16** (centróide e bbox) são **shorts little-endian**, gravados byte a byte —
  consistente com `calculateI16`.

Em i8, vetores são bytes signed simples (sem questão de ordem).

---

## 6. Como a caixa é calculada no build (sem passe extra)

Cuidado de performance: o builder roda no `docker build`, sob `1 CPU`. A bbox **não pode**
custar uma varredura extra do dataset.

A solução é calcular a caixa na passagem que **já existe**. O builder tem duas fases:

1. **Fase 1 (streaming):** lê o JSON.GZ, quantiza cada vetor, grava num arquivo temporário
   e acumula vetores i8 para o K-means.
2. **K-means:** agrupa e produz, para cada vetor, o seu `cluster` (`assignments[i]`).
3. **Fase 2 (distribuição):** relê o temp e copia cada registro para o bloco do seu
   cluster.

A Fase 2 é o **único** ponto que tem ao mesmo tempo (a) os bytes do registro já no dtype
do artefato e (b) o cluster a que ele pertence. É lá que atualizamos a caixa — no mesmo
laço que já move o registro:

```java
int c = assignments[i];
System.arraycopy(rec, 0, clusterBuffers[c], writePos[c], recordSize);
writePos[c] += recordSize;

// mesma passagem: decodifica o vetor do registro e atualiza a caixa do cluster
int[] lo = bboxMin[c];
int[] hi = bboxMax[c];
for (int d = 0; d < DIMS; d++) {
    int v = (dtype == Dtype.I16) ? decodeI16(rec, 1 + d * 2) : rec[1 + d];
    if (v < lo[d]) lo[d] = v;
    if (v > hi[d]) hi[d] = v;
}
```

O custo adicional é `O(n × DIMS)` de comparações dentro de um laço que já era `O(n)` — nada
perto do K-means, que é `O(n × K × DIMS × iterações)`. O tempo de build não muda de forma
perceptível.

### Cluster vazio

O K-means usa `actualK = min(K, n)` e pode deixar um cluster sem nenhum ponto
(`count == 0`). Nesse caso a caixa fica com os valores de inicialização
(`Integer.MAX_VALUE` / `Integer.MIN_VALUE`); antes de gravar, zeramos a caixa desses
clusters para o cast a `byte`/`short` não estourar. O bloco vazio nunca é varrido nem
podado, então o valor é irrelevante — só não pode quebrar a escrita.

---

## 7. O que esta issue entrega (e o que fica para a próxima)

Esta issue (04) é **só metade** da V4-A:

- ✅ **calcula** `bboxMin`/`bboxMax` por cluster no build;
- ✅ **persiste** no diretório do artefato;
- ✅ o `V2IndexSearcher` **lê e carrega** as caixas na inicialização.

Ela **não muda o resultado da busca** — as caixas ficam carregadas mas ainda não são
usadas. O `bboxLowerBound` e o laço de poda entram na **Issue 05** (query time). Por isso
o `V2QualityGuardTest` continua idêntico: o comportamento de busca é o mesmo de antes.

**Impacto esperado quando a Issue 05 ligar a poda:** zerar os 19 erros de particionamento
(6 FP + 13 FN), levando o `score_det` do patamar atual (~2501, já alcançado com o i16)
rumo ao teto, e o `final_score` em direção aos 4.000+ do AndDev741 (que faz 0/0 com
exatamente esta técnica).

---

## 8. Parametrização i8/i16 preservada

Mesmo com o i16 escolhido para produção, **mantivemos a parametrização**: tudo funciona
para os dois dtypes. A caixa é gravada/lida no tamanho certo (14 bytes i8 / 28 i16) com
base no byte `dtype` do header. O `Dockerfile` mantém `ARG DTYPE=i8` e o
`run-benchmark.sh` continua iterando `DTYPE_VALUES="i8 i16"`. Assim seguimos podendo
benchmarkar e comparar os dois.

---

## 9. Como reproduzir

### Testes (o caminho mais rápido)

```bash
mvn -q test -Dtest=V2BboxBuildTest
```

`V2BboxBuildTest` é parametrizado por dtype (i8 e i16). Ele:

1. constrói um artefato de uma fixture conhecida (96 vetores, variação em todas as
   dimensões, com sentinela em ~1/7 dos registros);
2. **reparseia o arquivo** de forma independente (sem usar o searcher) e afirma que
   **nenhum registro** fica fora da caixa do seu cluster;
3. confirma que o `V2IndexSearcher` carrega exatamente as mesmas caixas.

A guarda de qualidade continua verde (a busca não mudou):

```bash
mvn -q test -Dtest=V2QualityGuardTest
```

### Construir o artefato real e conferir o layout

```bash
# i8
java -cp target/scadufax-thoth.jar \
  br.com.rgbrainlabs.scadufaxthoth.prep.V2ArtifactBuilder \
  src/main/resources/references.json.gz /tmp/index-i8.v2  1024 20 42 i8

# i16
java -cp target/scadufax-thoth.jar \
  br.com.rgbrainlabs.scadufaxthoth.prep.V2ArtifactBuilder \
  src/main/resources/references.json.gz /tmp/index-i16.v2 1024 20 42 i16
```

O header guarda `numClusters` (bytes 4–7, BE) e `dataOffset` (bytes 16–23, BE). A relação
**`dataOffset == 24 + numClusters × CLUSTER_ENTRY_SIZE`** prova que a caixa está no
diretório: `24 + K × 58` (i8) ou `24 + K × 100` (i16).

### Build da imagem (os dois dtypes)

```bash
docker build --build-arg DTYPE=i8  -t api-i8  .
docker build --build-arg DTYPE=i16 -t api-i16 .
```

---

## 10. Glossário rápido

- **IVF** — *Inverted File Index*: particiona o espaço em clusters e varre só alguns.
- **nprobe** — quantos clusters o IVF varre por query.
- **bounding box** — menor caixa alinhada aos eixos que contém todos os pontos do cluster.
- **lower bound** — menor distância possível de uma query a *qualquer* ponto da caixa.
- **pruning (poda)** — pular um cluster inteiro porque seu lower bound já é pior que o
  k-ésimo vizinho atual.
- **worstDist** — distância do pior elemento no top-k corrente (`topDist[k−1]`).
- **sentinela** — valor especial (`−1.0f` → `−128`/`−32768`) para `last_transaction`
  ausente.