# Decisões técnicas — IVF (Issue 02)

Registro das escolhas de implementação para a busca IVF do Tesseract V2. O objetivo é tornar cada decisão auditável e reversível à medida que os benchmarks da Issue 04 produzem dados reais.

---

## 1. Número de clusters: `NUM_CLUSTERS=256`

### Raciocínio

Para IVF, a regra de ouro da literatura é `K ≈ sqrt(N)`. Para 3 milhões de vetores, isso daria `sqrt(3.000.000) ≈ 1.730` clusters. Na prática, usamos valores menores porque:

- O diretório de clusters ocupa `K × 30 bytes` de memória a mais no processo.
- A etapa de seleção de centróide custa `O(K)` por requisição — mais clusters significa mais comparações antes de sequer começar a varredura.
- Com 14 dimensões (espaço de baixa dimensão), os clusters tendem a ser bem separados, então valores menores de `K` já produzem boa qualidade de clustering.

**256** é um ponto de partida conservador: diretório pequeno (7.680 bytes), seleção rápida de centróide, e cada cluster tem em média ~11.700 vetores para o dataset de 3M. A Issue 04 medirá se vale aumentar.

### Como alterar

```bash
docker build --build-arg NUM_CLUSTERS=128 -t scadufax-thoth .
docker build --build-arg NUM_CLUSTERS=512 -t scadufax-thoth .
```

---

## 2. Algoritmo de K-means: Lloyd's com inicialização aleatória

### Por que Lloyd's e não K-means++

K-means++ melhora a inicialização escolhendo os primeiros centróides com probabilidade proporcional à distância ao centróide mais próximo já escolhido. Isso reduz o número de iterações necessárias para convergir.

A escolha por inicialização aleatória simples se justifica por:

1. **14 dimensões**: em espaços de baixa dimensão, os vetores tendem a se agrupar naturalmente. Qualquer inicialização razoável converge bem em poucas iterações.
2. **Semente fixa**: com `KMEANS_SEED=42`, o build é reproduzível independentemente da ordem dos dados ou da JVM.
3. **Custo**: K-means++ exige uma passagem extra pelos dados para cada centróide inicializado (O(N×K) antes de começar), adicionando tempo de build sem ganho mensurável em 14 dims.

### Número de iterações: `KMEANS_ITERATIONS=20`

20 iterações é suficiente para convergência em datasets com clusters bem separados. O loop tem parada antecipada: se nenhuma atribuição mudar entre iterações, o K-means termina antes de atingir o máximo.

---

## 3. K-means em int8, não float32

### A pergunta

"Usar float32 no K-means não é ruim para a CPU?"

### Resposta

K-means roda apenas no **build** — não afeta a latência de query em nada. Mas a escolha entre float32 e int8 ainda importa por razões de consistência e memória:

| Aspecto | float32 | int8 (escolhido) |
|---------|---------|------------------|
| Memória (3M vetores × 14 dims) | ~168 MB | ~42 MB |
| Espaço de clustering | Original | Quantizado |
| Consistência com a busca | Não — dois espaços diferentes | Sim — mesmo espaço |
| Precisão dos centróides | Alta | Levemente menor (erro ≤ 1/127 ≈ 0.8%) |

**Consistência métrica** é o argumento principal: se os centróides são armazenados como int8 e comparados com a query int8 em tempo de requisição, faz sentido ter construído os clusters nesse mesmo espaço. Clustering em float32 e busca em int8 introduziria uma pequena distorção entre "qual cluster parece mais próximo" no build versus no runtime.

### Implementação do passo de atualização

O centróide não pode ser byte puro no meio do cálculo — a soma de até 3M valores int8 explodiria int8. A implementação acumula em int32 e arredonda ao final:

```java
// Acumula em int32 (sem overflow para até ~16M vetores × 128)
sums[c][d] += vectors[i][d];

// Calcula a média float e quantiza de volta para byte
int q = Math.round((float) sums[c][d] / counts[c]);
centroids[c][d] = (byte) Math.clamp(q, -127, 127);
```

---

## 4. Semente fixa: `KMEANS_SEED=42`

Builds com a mesma semente e o mesmo dataset produzem exatamente o mesmo artefato. Isso é importante para:

- **Comparação de benchmarks**: garantir que mudanças de `NPROBE` ou `K_NEIGHBORS` estão comparando a mesma estrutura de clusters.
- **Debugging**: se um resultado inesperado aparecer num benchmark, recriar o artefato exato é trivial.

Para explorar sensibilidade ao ponto de partida, basta variar a semente:

```bash
docker build --build-arg KMEANS_SEED=123 -t scadufax-thoth .
```

---

## 5. nprobe padrão: `NPROBE=8`

### Raciocínio

Com `K=256` clusters e `nprobe=8`, a busca visita `8/256 = 3,1%` dos dados — uma redução de ~32× em relação ao brute force.

O valor 8 é um ponto de partida sem evidências empíricas deste dataset ainda. A Issue 04 fará a varredura sistemática. O padrão precisa ser:

- Suficientemente alto para não produzir respostas obviamente erradas.
- Baixo o suficiente para demonstrar ganho de velocidade.

8 atende os dois critérios como valor inicial.

### Como experimentar

```yaml
# docker-compose.yml
environment:
  - NPROBE=4   # mais rápido, recall menor
  - NPROBE=16  # mais recall, mais lento
  - NPROBE=64  # próximo do brute force
```

---

## 6. K_NEIGHBORS e FRAUD_THRESHOLD como variáveis de ambiente

### Por que não constantes no código

A Issue 04 vai construir uma matriz de benchmark variando `K` (número de vizinhos) e `nprobe`. Para isso, a API precisa ser reconfigurável sem rebuild do JAR — apenas reiniciando o container com diferentes env vars.

| Parâmetro | Padrão | O que muda |
|-----------|--------|------------|
| `K_NEIGHBORS` | `5` | Tamanho da vizinhança para calcular `fraud_score` |
| `FRAUD_THRESHOLD` | `0.6` | Fração mínima de fraudes para rejeitar a transação |

### Impacto do FRAUD_THRESHOLD na decisão

```
fraud_score = fraudes_no_top_K / K

Com K=5:
  0 fraudes → score=0.0 → approved=true  (threshold 0.6: qualquer score < 0.6 aprova)
  1 fraude  → score=0.2 → approved=true
  2 fraudes → score=0.4 → approved=true
  3 fraudes → score=0.6 → approved=false  ← ponto de corte padrão
  4 fraudes → score=0.8 → approved=false
  5 fraudes → score=1.0 → approved=false
```

Reduzir o threshold aumenta a precisão (menos falsos negativos de fraude) ao custo de mais falsos positivos (mais transações legítimas rejeitadas).

---

## 7. Compatibilidade retroativa

Todos os arquivos que usavam o construtor antigo de `V2IndexSearcher(path, calculator)` continuam compilando sem alteração — o construtor 2-arg usa `nprobe=8` como padrão.

O `SearchHandler` passou a exigir `kNeighbors` e `fraudThreshold` no construtor (não há valor sensato para inferir esses parâmetros). Os locais de instanciação são poucos (`JavalinBootstrap` e os testes) e foram atualizados.

---

## 8. Aumento de memória no Dockerfile de build

O build anterior usava `-Xmx256m`. Com K-means acumulando ~42 MB de vetores int8 em memória, e a JVM precisando de espaço para o K-means em si, elevamos para `-Xmx512m` no estágio de build. O estágio de runtime continua com `-Xmx80m` (inalterado — K-means não roda em runtime).
