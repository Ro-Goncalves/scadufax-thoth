# Issue 02 — Parametrização de dtype (i8/i16)

## O que foi feito e por que

Antes desta issue, o pipeline de build só sabia produzir artefatos em **int8** (inteiros
de 8 bits, também chamados de `byte` em Java). O tipo de dado era uma constante fixa no
código — se você quisesse experimentar com **int16** (inteiros de 16 bits, `short` em
Java), precisaria reescrever várias classes.

O objetivo desta issue foi simples: tornar o dtype um **parâmetro de build**. Com isso,
dois comandos bastam para comparar as duas precisões:

```bash
docker build --build-arg DTYPE=i8  -t api-i8  .
docker build --build-arg DTYPE=i16 -t api-i16 .
```

Isso é a pré-condição para a Issue 03 (investigação de quantização), que vai medir
quantos dos 207 erros de detecção são culpa da precisão do tipo de dado e quantos são
culpa do particionamento IVF.

---

## Conceitos fundamentais antes do código

### O que é quantização?

Os vetores de entrada chegam como `float[]` (números de ponto flutuante de 32 bits, com
valores entre 0 e 1 para a maioria das dimensões, e −1.0f como sentinela especial para
ausência de `last_transaction`).

Guardar tudo como float32 custa 4 bytes por número. O artefato teria ~400 MB para o
dataset real. Ao converter para int8, cada número cabe em 1 byte — o artefato cai para
~42 MB. Essa conversão se chama **quantização**.

A fórmula é simples:

```
valor_quantizado = round(valor_float × SCALE)
```

Para int8 com `SCALE = 127`, o número `0.5f` vira `round(0.5 × 127) = 64` (cabe em 1
byte). Para int16 com `SCALE = 10.000`, o mesmo `0.5f` vira `round(0.5 × 10.000) =
5.000` (precisa de 2 bytes).

A sentinela `−1.0f` não passa pela fórmula — ela vira diretamente `Byte.MIN_VALUE`
(−128) ou `Short.MIN_VALUE` (−32.768). Isso garante que o valor especial de ausência
nunca se confunda com um valor quantizado normal.

### Por que int16 pode ser melhor que int8?

Com int8, o espaço de representação vai de −127 a 127 (255 valores distintos). Dois
vetores float que diferem em `0.004` na mesma dimensão recebem o mesmo valor int8 depois
do `round()` — a diferença some. Com int16 (escala 10.000), esses mesmos dois vetores
ficam a `40` unidades de distância um do outro. A precisão extra pode mudar o resultado
do vizinho mais próximo para queries na fronteira entre classes.

A questão é: isso muda o suficiente para justificar um artefato 2× maior? A Issue 03 vai
responder isso com dados reais.

### O que é little-endian?

Quando um `short` (2 bytes) precisa ser escrito em disco ou memória, existe uma escolha:
gravar o byte mais significativo primeiro (**big-endian**) ou o menos significativo
primeiro (**little-endian**).

Exemplo: o número `5.000` em hexadecimal é `0x1388`. Em big-endian: `[0x13, 0x88]`. Em
little-endian: `[0x88, 0x13]`.

O `DataOutputStream` do Java usa big-endian por padrão. Mas o `EuclideanDistanceCalculator`
lê os dados via `MemorySegment.get(JAVA_SHORT_LE_UNALIGNED, ...)` — ou seja, **espera
little-endian**. Por isso `encodeI16()` e a escrita dos centróides i16 fazem o byte-swap
manualmente, escrevendo `byte a byte` na ordem correta:

```java
dst[offset]     = (byte)  (s & 0xFF);        // byte baixo (menos significativo)
dst[offset + 1] = (byte) ((s >> 8) & 0xFF);  // byte alto (mais significativo)
```

---

## Layout do artefato binário

O arquivo `.v2` tem três seções encadeadas:

```
┌─────────────────────────────────┐
│  Header — 24 bytes (fixo)       │
├─────────────────────────────────┤
│  Diretório de clusters          │
│  K entradas × (30 ou 44 bytes)  │
├─────────────────────────────────┤
│  Blocos de registros            │
│  N registros × (16 ou 30 bytes) │
└─────────────────────────────────┘
```

### Header (24 bytes, nunca muda)

```
Byte  0    : VERSION (sempre 2)
Bytes 1-2  : DIMS como short (sempre 14)
Byte  3    : DTYPE  (1 = i8,  2 = i16)     ← NOVO
Bytes 4-7  : numClusters como int
Bytes 8-15 : clusterDirOffset como long
Bytes 16-23: dataOffset como long
```

O byte `DTYPE` já existia no formato — o `V2IndexSearcher` o lia mas jogava uma exceção
se não fosse `1`. Agora ele aceita `1` ou `2` e adapta todo o comportamento a seguir.

### Entrada de cluster no diretório

| Campo | i8 | i16 |
|---|---|---|
| Centróide | 14 bytes (1 por dimensão) | 28 bytes (2 por dimensão, LE) |
| Radius (float, não usado) | 4 bytes | 4 bytes |
| Offset (long) | 8 bytes | 8 bytes |
| Count (int) | 4 bytes | 4 bytes |
| **Total** | **30 bytes** | **44 bytes** |

### Registro de vetor

| Campo | i8 | i16 |
|---|---|---|
| Label (byte: 0=legítimo, 1=fraude) | 1 byte | 1 byte |
| Vetor quantizado | 14 bytes | 28 bytes (2 por dimensão, LE) |
| Padding | 1 byte | 1 byte |
| **Total** | **16 bytes** | **30 bytes** |

---

## Mudanças no `V2ArtifactBuilder`

### O enum `Dtype`

```java
public enum Dtype { I8, I16 }
```

Um enum é a forma mais segura de representar uma escolha finita de valores. Usar `"i8"`
e `"i16"` como strings seria arriscado: um erro de digitação passaria em silêncio. Com o
enum, o compilador avisa se você escrever `Dtype.I17`.

### Constantes novas

```java
public static final byte DTYPE_I16          = 2;
public static final int  SCALE_I16          = 10_000;
public static final int  RECORD_SIZE_I16    = 30;   // 1 + 28 + 1
public static final int  CLUSTER_ENTRY_SIZE_I16 = 44; // 28 + 4 + 8 + 4
```

### O método `encodeI16()`

Espelha exatamente o `encodeI8()` existente, com três diferenças:

1. O tipo de destino é `short` (2 bytes) em vez de `byte` (1 byte).
2. A escala é `10.000` em vez de `127`.
3. O clamp é `[−32.767, 32.767]` em vez de `[−127, 127]`. (O −32.768 fica reservado
   para a sentinela `Short.MIN_VALUE`.)

```java
static void encodeI16(float[] src, byte[] dst, int dstOffset) {
    for (int d = 0; d < DIMS; d++) {
        float v = src[d];
        short s;
        if (v == -1.0f) {
            s = Short.MIN_VALUE;           // sentinela de ausência
        } else {
            int q = Math.round(v * SCALE_I16);
            if (q < -32767) q = -32767;
            if (q >  32767) q =  32767;
            s = (short) q;
        }
        dst[dstOffset + d * 2]     = (byte)  (s & 0xFF);        // byte baixo (LE)
        dst[dstOffset + d * 2 + 1] = (byte) ((s >> 8) & 0xFF);  // byte alto  (LE)
    }
}
```

### Variáveis locais em vez de constantes estáticas

No início do método `build()`, as constantes de layout são calculadas localmente:

```java
int  recordSize       = (dtype == Dtype.I16) ? RECORD_SIZE_I16        : RECORD_SIZE;
int  clusterEntrySize = (dtype == Dtype.I16) ? CLUSTER_ENTRY_SIZE_I16 : CLUSTER_ENTRY_SIZE;
byte dtypeByte        = (dtype == Dtype.I16) ? DTYPE_I16              : DTYPE_I8;
```

Todas as referências a `RECORD_SIZE` e `CLUSTER_ENTRY_SIZE` dentro do método foram
substituídas por essas variáveis. Isso evita que qualquer caminho use o valor errado.

### K-means sempre em espaço i8

Esta foi a decisão de design mais importante desta issue. Mesmo quando `dtype == I16`,
os vetores coletados para o K-means continuam sendo quantizados em i8:

```java
// coleta vetor i8 para K-means (independente do dtype do artefato)
byte[] vec = new byte[DIMS];
encodeI8(floats, vec, 0);
allVectors.add(vec);
```

**Por que?** O `KMeansClusterer` opera sobre `byte[][]`. Modificá-lo para suportar
`short[][]` adicionaria complexidade sem benefício real: o agrupamento dos dados
(quais vetores ficam juntos) não muda com a escala. O que muda é apenas a representação
dos valores dentro de cada grupo — e essa representação só importa na hora de calcular
distância durante a busca, não na hora de montar os clusters.

### Conversão dos centróides i8 → i16

Após o K-means, os centróides existem como `byte[]` (espaço i8). Para um artefato i16,
precisamos escrevê-los como shorts no diretório de clusters. A conversão faz o
rescaling:

```java
short s = (short) Math.round(centroids[c][d] / 127.0f * SCALE_I16);
```

Exemplo: se o centróide numa dimensão é `64` (em i8), o valor i16 correspondente é
`round(64 / 127 × 10.000) = 5.039`. Isso preserva a posição relativa do centróide no
espaço de dados — ele ainda aponta para o mesmo ponto geométrico, só com outra escala.

### Retrocompatibilidade dos overloads

O método `build()` de 5 parâmetros (sem `dtype`) continua existindo e delega para o de
6 parâmetros com `Dtype.I8`. Todos os testes e código existente que chamavam o `build()`
sem dtype continuam funcionando sem modificação.

---

## Mudanças no `V2IndexSearcher`

### Por que o searcher precisa mudar?

O searcher lia o byte `dtype` do header, mas imediatamente lançava uma exceção se não
fosse `DTYPE_I8`. Toda a lógica de busca — quantização da query, leitura dos centróides,
cálculo de distância — era codificada para i8.

Para suportar artefatos i16, o searcher precisa:
1. Ler os centróides como shorts (28 bytes) em vez de bytes (14 bytes).
2. Quantizar a query na escala correta (×10.000 em vez de ×127).
3. Chamar `calculateI16()` em vez de `calculateI8()` no hot path.
4. Usar `RECORD_SIZE = 30` em vez de `16` ao navegar pelos registros.

### Centróides como `int[][]` (widening)

Os centróides são armazenados internamente como `int[][]` em vez de `byte[][]`:

```java
private final int[][] centroids;
```

O termo **widening** significa "alargar o tipo sem perder informação". Um `byte` com valor
`64` se torna o `int` `64`. Um `short` com valor `5.039` se torna o `int` `5.039`. Os
bits são os mesmos; o container ficou maior.

Por que unificar em `int[][]`? Porque o código de distância entre a query e os centróides
(usado para ranquear quais clusters visitar) pode ser o mesmo para ambos os dtypes — os
valores já estão em `int`, não importa se vieram de bytes ou shorts.

### A leitura dos centróides no `readHeader()`

Para i8, cada dimensão é um `byte` signed — basta ler e alargar:

```java
centroids[c][d] = dis.readByte(); // byte signed widened para int automaticamente
```

Para i16, cada dimensão é um `short` em little-endian, armazenado em 2 bytes. O
`DataInputStream.readShort()` leria big-endian — errado. Então lemos byte a byte e
reconstruímos manualmente:

```java
int lo = dis.readByte() & 0xFF;  // byte baixo; & 0xFF converte para int sem extensão de sinal
int hi = dis.readByte() & 0xFF;  // byte alto
centroids[c][d] = (short) (lo | (hi << 8)); // LE → short → widen para int
```

O `& 0xFF` é necessário porque `readByte()` retorna `byte` signed. Se o byte lido for
`0x88` (136 em unsigned), Java o interpreta como `−120` (signed). O `& 0xFF` força a
interpretação como `int` positivo `136`, preservando o valor correto antes de combinar
os dois bytes.

### Distância até centróide com saturação

A função `centroidDist()` recebe `int[]` e precisa retornar um valor que caiba em `int`
(para o truque de bit-packing no `rankClusters()`). O problema: para i16, a diferença
máxima entre dois valores é `65.534`, e `65.534² × 14 ≈ 6 × 10¹⁰` — que não cabe em
`int` (máximo ~2,1 × 10⁹).

A solução é calcular em `long` e saturar:

```java
private static int centroidDist(int[] q, int[] c) {
    long sum = 0;
    for (int d = 0; d < DIMS; d++) {
        int diff = q[d] - c[d];
        sum += (long) diff * diff;   // cast para long ANTES da multiplicação
    }
    return (int) Math.min(sum, Integer.MAX_VALUE);
}
```

A saturação é segura porque só usamos `centroidDist` para **ranquear** clusters por
proximidade. Dois clusters com distâncias `7 × 10¹⁰` e `8 × 10¹⁰` ficam ambos como
`Integer.MAX_VALUE` depois da saturação — empate — e a ordenação os coloca em qualquer
ordem entre si. Mas esses valores absurdamente grandes só ocorrem para clusters muito
distantes da query, que jamais seriam visitados antes dos próximos. O ranking dos
clusters relevantes (os de menor distância) não é afetado.

### O truque do bit-packing no `rankClusters()`

Esta função precisa ordenar `numClusters` clusters por distância. Em vez de criar
objetos `(distância, índice)` — que alocam memória — ela empacota os dois valores num
único `long`:

```
distAndIdx[c] = ((long) dist << 32) | c;
```

Os 32 bits altos guardam a distância; os 32 bits baixos guardam o índice do cluster.
`Arrays.sort()` em `long[]` ordena pelos bits altos naturalmente, e extrair o índice
depois é só um cast para `int`:

```java
result[i] = (int) distAndIdx[i];
```

Isso funciona porque `numClusters` nunca passa de ~16.000 na prática (cabe em 32 bits
com sobra). E `dist` é `int`, então `(long) dist << 32` coloca os bits todos no lugar
certo sem cortar nada.

### A query como `int[]`

`quantizeQuery()` agora retorna `int[]` em vez de `byte[]`. Isso unifica o caminho de
ranking de centróides (que usa `int[]` para ambos os dtypes). Para o hot path de busca,
os helpers `toI8Query()` e `toI16Query()` convertem o `int[]` para `byte[]` ou `short[]`
— que é o que `calculateI8()` e `calculateI16()` esperam:

```java
private static byte[] toI8Query(int[] q) {
    byte[] b = new byte[q.length];
    for (int i = 0; i < q.length; i++) {
        b[i] = (byte) q[i]; // cast direto: int com valor em [-128, 127] → byte
    }
    return b;
}
```

O cast `(byte) q[i]` funciona porque os valores já foram clampados em `quantizeQuery()`
para caber num byte. Não há truncamento inesperado.

### O `recordSize` como campo de instância

Antes, `RECORD_SIZE` era uma constante estática da classe — sempre 16. Agora é um campo
de instância inicializado no construtor a partir do dtype lido no header:

```java
this.recordSize = (dtype == V2ArtifactBuilder.DTYPE_I16)
    ? V2ArtifactBuilder.RECORD_SIZE_I16   // 30
    : V2ArtifactBuilder.RECORD_SIZE;      // 16
```

No hot path de busca, `recordSize` é usado para pular de um registro para o próximo
dentro de um bloco de cluster:

```java
long recordBase = blockStart + (long) i * recordSize;
```

Se o valor estivesse errado (por exemplo, 16 em vez de 30 para um artefato i16), o
searcher leria bytes da posição errada — lixo silencioso, sem exceção.

---

## Mudanças no `Dockerfile`

```dockerfile
ARG DTYPE=i8
```

O `ARG` no Dockerfile define uma variável disponível **apenas durante o build da imagem**
(diferente de `ENV`, que persiste no contêiner em execução). O valor padrão `i8` garante
que um `docker build` sem argumentos produz o mesmo artefato de sempre.

A variável é passada para o builder como 6º argumento posicional:

```dockerfile
RUN java ... V2ArtifactBuilder \
    src/main/resources/references.json.gz /build/data/index.v2 \
    ${NUM_CLUSTERS} ${KMEANS_ITERATIONS} ${KMEANS_SEED} ${DTYPE}
```

No `main()` do `V2ArtifactBuilder`, o parsing é simples:

```java
Dtype dtype = (args.length > 5 && "i16".equals(args[5])) ? Dtype.I16 : Dtype.I8;
```

Qualquer valor diferente de `"i16"` — inclusive ausência do argumento — resulta em `I8`.

---

## Mudanças no `V2QualityGuardTest`

O teste foi parametrizado com JUnit 5:

```java
@ParameterizedTest
@EnumSource(V2ArtifactBuilder.Dtype.class)
void guardaQualidade_preservaDecisoesDeFragude(V2ArtifactBuilder.Dtype dtype, @TempDir Path tmpDir)
```

`@EnumSource(V2ArtifactBuilder.Dtype.class)` instrui o JUnit a rodar o teste uma vez
para cada valor do enum — `I8` e `I16`. O `V2IndexSearcher` não precisa de nenhuma
mudança no teste: ele lê o byte `dtype` do header do artefato e se adapta automaticamente.

Os thresholds de acordo (95% para quantização, 80% para IVF) foram mantidos. Com a
fixture de 60 vetores em grupos bem separados, ambos os dtypes atingem 100% de acordo —
a fixture não é desafiadora o suficiente para distinguir i8 de i16 em precisão.

---

## Fluxo completo de uma requisição com artefato i16

Para tornar concreto o que acontece em tempo de execução:

1. O `V2IndexSearcher` é criado na inicialização do servidor.
2. `readHeader()` lê o byte `3` do arquivo e encontra `dtype = 2` (i16).
3. O constructor configura `scale = 10.000` e `recordSize = 30`.
4. Os centróides são lidos como shorts LE e armazenados como `int[][]`.
5. Chega uma requisição com vetor `float[] = [0.5, 0.3, ...]`.
6. `quantizeQuery()` converte: `round(0.5 × 10.000) = 5.000`, `round(0.3 × 10.000) = 3.000`, etc.
7. `rankClusters()` calcula a distância int² entre o `int[]` da query e cada centróide `int[]`.
8. Os `nprobe` clusters mais próximos são selecionados.
9. Para cada registro nesses clusters, `toI16Query(qi)` converte o `int[]` para `short[]`.
10. `calculator.calculateI16(q16, file, recordBase + 1, DIMS)` lê 28 bytes (14 shorts LE)
    do mmap e calcula a distância euclidiana em `long` (para não estourar).
11. O `TopKSelector` mantém os 5 vizinhos mais próximos.
12. A decisão de fraude é calculada a partir dos labels dos 5 vizinhos.

---

## Resumo das decisões de design

| Decisão | Alternativa descartada | Motivo |
|---|---|---|
| K-means sempre em i8 | Adaptar `KMeansClusterer` para i16 | Agrupamento é invariante à escala; complexidade desnecessária |
| Centróides como `int[][]` | Manter como `byte[][]`, converter on-the-fly | Unifica o código de `centroidDist()` para ambos os dtypes |
| `centroidDist()` com saturação | Retornar `long`, mudar o bit-packing | Saturação não afeta o ranking dos clusters próximos; evita refatoração do `rankClusters()` |
| Centróides i16 escritos como LE bytes | Usar `writeShort()` (big-endian) | Consistência com `calculateI16()` que usa `JAVA_SHORT_LE_UNALIGNED` |
| Overload retrocompatível de 5 parâmetros | Atualizar todos os call sites | Zero regressões nos testes existentes sem tocar em código que não é desta issue |