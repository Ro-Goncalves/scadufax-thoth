# Como funciona a busca IVF

## O problema: 3 milhões de comparações por requisição

Quando uma transação chega, precisamos encontrar as 5 mais parecidas em um conjunto de referência. A forma mais simples é comparar a transação com **todos** os vetores — isso é chamado de *brute force* ou força bruta.

Para 3 milhões de vetores, são 3 milhões de cálculos de distância por requisição. A cada 100 requisições por segundo, são 300 milhões de operações. Funciona, mas deixa ciclos de CPU na mesa.

A solução é organizar os dados antes de buscar.

---

## A analogia da biblioteca

Imagine uma biblioteca com 3 milhões de livros espalhados aleatoriamente. Para achar os 5 livros mais parecidos com o que você está lendo, você teria que abrir um por um.

Agora imagine que os livros estão organizados em **256 prateleiras por gênero**. Se o seu livro é de suspense, você vai primeiro às prateleiras de suspense e talvez de thriller — e ignora as de romance e culinária.

Esse é exatamente o princípio do IVF (*Inverted File Index*): organizar os vetores em **grupos** durante o build, e na hora da busca verificar apenas os grupos mais relevantes.

---

## O que é K-means

K-means é o algoritmo que cria os grupos (chamados de *clusters*). Ele recebe um conjunto de vetores e um número `K` (quantidade de grupos desejada) e os distribui de forma que vetores parecidos fiquem no mesmo grupo.

Cada grupo tem um **centróide** — o vetor médio de todos os elementos do grupo. O centróide representa o "centro de gravidade" do cluster.

```
Vetores antes do K-means:         Após K-means com K=3:

  ·  · ·                            [ · · · ]  cluster 0
      ·  ·  ·                          [ · · · ]  cluster 1
              · ·                            [ · · ]  cluster 2
    · ·  ·    ·  · ·
```

K-means **não é busca** — é uma etapa de organização que acontece uma única vez no **build**, antes do servidor subir.

---

## O algoritmo de Lloyd

O K-means que implementamos usa o algoritmo de Lloyd, que alterna dois passos simples até convergir:

### Passo 1 — Atribuição (*assign*)

Cada vetor vai para o centróide mais próximo:

```
Para cada vetor v:
    cluster[v] = centróide mais próximo de v
```

### Passo 2 — Atualização (*update*)

Cada centróide vira a média de todos os vetores atribuídos a ele:

```
Para cada cluster c:
    centróide[c] = média de todos os vetores com cluster[v] == c
```

### Iteração

Os dois passos se repetem até que nenhum vetor mude de cluster (convergência) ou até atingir o número máximo de iterações configurado (`KMEANS_ITERATIONS`, padrão 20).

**Exemplo visual com 2 iterações:**

```
Iteração 1:                       Iteração 2:
  ★ centróide inicial (aleatório)   ★ centróide recalculado (média)

  · ·★· ·        · ·               · ·  · ·       · ·
       ★   ·  ·        →              ★  ·  ·
                  ★ · ·                     ★ · ·
```

---

## Como a busca IVF funciona em tempo de requisição

Quando uma transação chega ao endpoint `/fraud-score`:

```
1. Vetorização
   transação JSON → float[14] (TransactionVectorizer)

2. Quantização
   float[14] → byte[14] int8 (mesma regra do build)

3. Seleção de clusters
   calcular distância do vetor int8 até cada centróide
   selecionar os nprobe clusters mais próximos

4. Varredura seletiva
   percorrer apenas os registros dos clusters selecionados
   manter max-heap de tamanho k com os menores distâncias

5. Decisão
   fraud_score = fraudes_no_top_k / k
   approved = fraud_score < FRAUD_THRESHOLD
```

### Diagrama do fluxo

```
transação
    │
    ▼
[Vetorizar] → float[14]
    │
    ▼
[Quantizar] → byte[14] (int8)
    │
    ▼
[Comparar com centróides] ──→ distâncias para cada centróide
    │
    ▼
[Selecionar nprobe clusters] ──→ ex.: cluster 47, cluster 12
    │
    ▼
[Varrer registros dos clusters selecionados]
    │
    ▼
[Top-K vizinhos] → calcular fraud_score → approved/rejected
```

---

## O que é nprobe

`nprobe` controla **quantos clusters** a busca visita. É a principal alavanca de qualidade versus velocidade.

| nprobe | Clusters visitados | Vetores avaliados (K=256, N=3M) | Trade-off |
|--------|-------------------|----------------------------------|-----------|
| 1      | 1                 | ~11.700                          | Mais rápido, recall menor |
| 8      | 8                 | ~93.750                          | Bom equilíbrio (padrão) |
| 32     | 32                | ~375.000                         | Alto recall, mais lento |
| 256    | 256               | 3.000.000                        | Equivalente ao brute force |

> Os valores reais de recall para cada configuração serão medidos na Issue 04 com a matriz de benchmark.

---

## Por que usamos int8 e não float32

Os vetores já são quantizados para int8 durante o build (quantização do Tesseract). Rodar o K-means no mesmo espaço int8 traz dois benefícios:

1. **Consistência métrica**: a "proximidade" calculada no K-means é a mesma usada em tempo de busca. Não há distorção entre o espaço de organização e o espaço de busca.

2. **Eficiência de memória**: para 3M vetores de 14 dimensões, int8 ocupa ~42 MB contra ~168 MB de float32 — 4× menos memória durante o build.

---

## Resumo do ciclo de vida

```
BUILD TIME                          RUNTIME
──────────────────────────          ──────────────────────────
references.json.gz                  POST /fraud-score
    │                                   │
    ▼                                   ▼
Quantizar para int8              Vetorizar + quantizar query
    │                                   │
    ▼                                   ▼
K-means (Lloyd's, k=256)         Distância para centróides
    │                                   │
    ▼                                   ▼
Escrever artefato V2             Selecionar nprobe clusters
(header + diretório + dados)           │
                                        ▼
                                   Varrer + top-K → resposta
```
