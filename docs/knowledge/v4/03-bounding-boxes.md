# Bounding boxes: o que são, como funcionam e por que importam

> Documento de estudo da V4-A (Veritas), Passos 2 e 3 — Issues 04 e 05.
> Objetivo: que qualquer pessoa, inclusive quem está chegando agora no projeto,
> entenda o conceito de *bounding box*, como ele transforma a busca aproximada em
> busca **exata**, e consiga reproduzir o que foi feito.

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
private static long bboxLowerBound(int[] query, int[] bboxMin, int[] bboxMax) {
    long lb = 0;
    for (int d = 0; d < DIMS; d++) {
        int q = query[d];
        int lo = bboxMin[d], hi = bboxMax[d];
        if      (q < lo) { long diff = lo - q; lb += diff * diff; }
        else if (q > hi) { long diff = q - hi; lb += diff * diff; }
        // caso contrário (dentro da faixa) a dimensão contribui 0
    }
    return lb;
}
```

> **Por que `long` e não `int`?** Em i16 uma dimensão pode contribuir até
> `(32767 − (−32768))² ≈ 4,3 × 10⁹`, e a soma das 14 dimensões chega a ~6 × 10¹⁰ —
> muito além de `Integer.MAX_VALUE` (~2,1 × 10⁹). Se acumulássemos em `int`, o valor
> *transbordaria* e o lower bound voltaria **menor** do que a distância real. Aí a
> garantia `lb ≤ distância(q, p)` quebraria e poderíamos podar um cluster que tinha um
> vizinho melhor — busca deixaria de ser exata. O `calculateI16` acumula em `long`
> exatamente pela mesma razão; o lower bound precisa acompanhar. (Em i8 a soma máxima é
> ~9 × 10⁵ e caberia em `int`, mas usamos `long` para um único código correto nos dois
> dtypes.)

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

## 7. O algoritmo de busca com poda, passo a passo

Até aqui falamos da *caixa* e do *critério* de poda. Agora o algoritmo completo, como ele
roda no `V2IndexSearcher.search()`.

O IVF antigo fazia só uma coisa: varrer os `nprobe` clusters mais próximos do centróide e
parar. A V4-A acrescenta um segundo laço — *iterar todos os clusters restantes, podando os
que não podem ajudar*. Em pseudocódigo (versão i16; a i8 é simétrica):

```java
int[] ranked = rankClusters(q);          // clusters ordenados por distância ao centróide
TopKSelector selector = new TopKSelector(k);
int probes = Math.min(nprobe, numClusters);

// 1) Aquecimento: varre os nprobe clusters mais próximos (como o IVF antigo).
for (int ci = 0; ci < probes; ci++) {
    scanCluster(ranked[ci], q, selector);
}

// 2) Poda exata: percorre o RESTANTE, do mais próximo ao mais distante.
for (int ci = probes; ci < numClusters; ci++) {
    int cluster = ranked[ci];
    if (bboxLowerBound(q, bboxMin[cluster], bboxMax[cluster]) > selector.worstDist()) {
        continue;                        // prova matemática: pular o cluster inteiro
    }
    scanCluster(cluster, q, selector);   // não deu pra podar → varre de verdade
}
```

Três peças fazem isso funcionar:

**1. `selector.worstDist()` — a régua da poda.** O `TopKSelector` guarda os `k` melhores
vizinhos até agora; `worstDist()` devolve a distância do k-ésimo (o pior deles). É contra
essa régua que comparamos o lower bound. Detalhe importante: *enquanto o top-k ainda não
tem k elementos*, `worstDist()` devolve `Double.MAX_VALUE`. Assim nenhum lower bound é
maior que ela e **nada é podado até o top-k encher** — exatamente o que queremos (não dá
para podar com base num top-k incompleto).

**2. A ordem `ranked[]` — do mais perto ao mais longe.** Os clusters restantes são
visitados na ordem de distância ao centróide. Isso não muda o resultado, mas muda a
*velocidade*: visitar primeiro os clusters próximos faz o `worstDist` cair rápido (bons
candidatos entram cedo), e um `worstDist` menor poda mais agressivamente os clusters
seguintes. É um efeito bola-de-neve a nosso favor.

**3. `nprobe` deixa de afetar a correção.** Esta é a virada conceitual. No IVF antigo,
`nprobe` controlava *qualidade*: poucos clusters → busca pior. Agora o segundo laço
considera **todos** os clusters (podando a maioria), então o resultado é sempre o exato,
**não importa o `nprobe`**. Ele vira só um parâmetro de **aquecimento**: quantos clusters
varrer "no escuro" antes de começar a podar. Mais aquecimento = top-k inicial melhor =
mais poda; menos aquecimento = o primeiro lower bound já corta cedo. Em ambos os casos o
conjunto final de vizinhos é idêntico ao da força bruta.

### Por que isso é seguro mesmo com a query quantizada

O lower bound compara a **query já quantizada** (int) contra a caixa (mesmo espaço, ver
seção 4), e o resultado é comparado com `worstDist`, que vem do `calculateI8/I16` — também
inteiro. Tudo no mesmo espaço e na mesma aritmética, então a desigualdade
`lb ≤ distância real` vale exatamente. A comparação final `lb > worstDist()` promove o
`long` do lower bound para `double`; como os dois lados são inteiros menores que `2⁵³`, a
comparação é exata — sem surpresa de ponto flutuante.

### Custo: uma chamada por cluster, zero alocação por candidato

A poda adiciona, por cluster, **um** cálculo de lower bound (14 subtrações/multiplicações).
Os clusters que sobrevivem à poda são varridos pelo mesmo código de antes — o
`scanCluster` não aloca nada por vetor (insere direto no `TopKSelector`, herança do V3-D).
Ou seja: o ganho de "varrer menos vetores" não vem com um novo custo de garbage.

### Como sabemos que ficou *idêntico* à força bruta

O `V2QualityGuardTest` prova isso de forma direta: para cada query ele roda a busca podada
(`nprobe=2`) **e** o full-scan (`nprobe=K`, que nunca poda) e exige que as duas listas de
vizinhos sejam **iguais** — mesma distância e mesmo label, na mesma ordem. Se a poda
removesse por engano um vizinho real, essa igualdade quebraria. Roda verde para i8 e i16,
com **0 divergências** (ver seção 10).

---

## 8. O que a V4-A entrega

Somando as Issues 04 e 05, a V4-A entrega a fatia completa do bbox pruning:

- ✅ **calcula** `bboxMin`/`bboxMax` por cluster no build (Issue 04);
- ✅ **persiste** no diretório do artefato (Issue 04);
- ✅ o `V2IndexSearcher` **lê e carrega** as caixas na inicialização (Issue 04);
- ✅ o `search()` **usa** as caixas para podar e devolver o top-k **exato** (Issue 05).

A guarda de qualidade, que na Issue 04 só confirmava que nada havia mudado, agora exige
**busca exata**: 100% de acordo com o full-scan e com o float32 brute-force na fixture, e
0 divergências de particionamento.

**Impacto esperado no score:** zerar os erros de particionamento (na V3, 6 FP + 13 FN no
recorte i16; ~105 FP + 102 FN na rodada completa da Rinha), levando o `score_det` rumo ao
teto e o `final_score` na direção dos 4.000+ do AndDev741, que faz 0/0 com exatamente esta
técnica. A confirmação empírica em score é o **gate pós-V4-A** (5 boots frios), medido
fora desta issue.

---

## 9. Parametrização i8/i16 preservada

Mesmo com o i16 escolhido para produção, **mantivemos a parametrização**: tudo funciona
para os dois dtypes. A caixa é gravada/lida no tamanho certo (14 bytes i8 / 28 i16) com
base no byte `dtype` do header. O `Dockerfile` mantém `ARG DTYPE=i8` e o
`run-benchmark.sh` continua iterando `DTYPE_VALUES="i8 i16"`. Assim seguimos podendo
benchmarkar e comparar os dois.

---

## 10. Como reproduzir

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

A guarda de **exatidão** — agora exige que a busca podada devolva exatamente os mesmos
vizinhos da força bruta (0 divergências, i8 e i16):

```bash
mvn -q test -Dtest=V2QualityGuardTest
```

E o teste do `worstDist()`, a régua que a poda usa para decidir o corte:

```bash
mvn -q test -Dtest=TopKSelectorTest
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

## 11. Glossário rápido

- **IVF** — *Inverted File Index*: particiona o espaço em clusters e varre só alguns.
- **nprobe** — quantos clusters o IVF varre por query.
- **bounding box** — menor caixa alinhada aos eixos que contém todos os pontos do cluster.
- **lower bound** — menor distância possível de uma query a *qualquer* ponto da caixa.
- **pruning (poda)** — pular um cluster inteiro porque seu lower bound já é pior que o
  k-ésimo vizinho atual.
- **worstDist** — distância do pior elemento no top-k corrente (`topDist[k−1]`).
- **sentinela** — valor especial (`−1.0f` → `−128`/`−32768`) para `last_transaction`
  ausente.