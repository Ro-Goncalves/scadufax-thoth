# Tesseract V2: Jornada, Resultados e Fechamento

Este documento consolida o aprendizado acumulado ao longo da branch `tesseract` —
desde a motivação inicial até a decisão final de envelope operacional. Serve como
registro histórico da V2 e ponto de partida para a V3 (Celeritas).

---

## Ponto de Partida: A Linha de Base V1

A V1 operava com **busca bruta em float32**: para cada requisição, calculava a
distância euclidiana do vetor da transação contra **todos os 3 milhões** de vetores
de referência, sem nenhum índice ou atalho.

| Métrica | Valor |
|---|---|
| Algoritmo | Brute force float32, O(N) |
| avg | 1.11s |
| p99 | ~2.0s |
| Uso de CPU | 100% por requisição |
| Erros HTTP | 0 |

Isso provava estabilidade da infraestrutura (mmap, serialização, stack Javalin) mas
deixava todos os ciclos de CPU na mesa calculando ponto flutuante. O objetivo da
branch `tesseract` era sair do território de segundos e chegar em dezenas de
milissegundos.

---

## Issue 00 — Quantização Escalar (int8/int16)

A primeira aposta foi simples: se o dado já é normalizado em `[0.0, 1.0]`, pode-se
mapeá-lo para bytes inteiros. Isso reduz o payload vetorial de 56 bytes (14 × float32)
para 14 bytes (14 × int8) — **4× menos I/O** — e substitui aritmética de ponto
flutuante por aritmética inteira, muito mais eficiente na JVM.

A decisão crítica foi reservar o valor `-128` (Byte.MIN_VALUE) como **sentinela** para
a dimensão `last_transaction` quando nula, em vez de `-127`. Com `-127`, o domínio de
valores normais colidia com o sentinela (ambos representavam `-1.0f`). Com `-128`,
o sentinela fica fora do intervalo alcançável pela quantização `round(v × 127)`.

### Smoke test — int16

```
http_req_duration avg=512ms min=85ms med=585ms max=813ms p(90)=758ms p(95)=785ms
```

### Smoke test — int8

```
http_req_duration avg=481ms min=32ms med=177ms max=1.09s p(90)=1.08s p(95)=1.08s
```

O int8 mostrou uma queda na média (~6%) mas com mais variância — a JVM ainda aquecia
o JIT nas primeiras requisições. O dado importante foi o **min=32ms**: prova de que
sem o JIT warmup, o int8 bruto já estava numa faixa muito melhor que o float32.

A quantização int8 foi escolhida sobre int16 pelos motivos:
- **4× menos bytes** que float32 (vs. 2× do int16)
- **Consistência métrica** com o espaço de busca — o K-means rodaria no mesmo espaço
- **Erro de quantização aceitável**: ≤ 0.8% (1/127) para vetores em `[0, 1]`

---

## Issue 01 — Artefato V2: Formato Binário Único

A V1 usava três arquivos separados: `vectors-i8.bin`, `labels.bin` e `meta.properties`.
Isso forçava dois mmap distintos e um parser de texto na inicialização. A V2 consolida
tudo em um único `.v2`:

```
[Header 24 bytes] → [Diretório de Clusters K×30 bytes] → [Blocos de Registros N×16 bytes]
```

Cada registro fixo em 16 bytes: `1 byte label + 14 bytes vetor int8 + 1 byte padding`.
O cabeçalho é lido com `DataInputStream` (big-endian nativo do Java); os registros
são acessados via `MemorySegment` (FFM API / mmap), com o `FileChannel` fechado logo
após o mapeamento.

Na Issue 01, o arquivo ainda tinha **um único cluster** cobrindo todos os registros
(busca bruta disfarçada de IVF). O valor de raio era `Float.MAX_VALUE` e o centróide
era zero — placeholders para o IVF real que viria na Issue 02.

### Smoke test — V2IndexSearcher (cluster único)

```
http_req_duration avg=269ms min=31ms med=265ms max=573ms p(90)=522ms p(95)=548ms
```

Ganho de ~47% na média sobre o int8 anterior, só pela eliminação do parser de texto
e da segunda chamada de mmap. O artefato passou de três arquivos para **45-48 MB** únicos.

---

## Issue 02 — Busca IVF com K-means int8

A mudança estrutural da V2: parar de ser O(N) e se tornar O(k + nprobe × N/K).

O **K-means de Lloyd** foi implementado inteiramente em int8 — o mesmo espaço
da busca em runtime. Isso garante consistência métrica: a "proximidade" calculada
no build é idêntica à usada na requisição. O passo de atualização acumula em int32
(sem overflow para até ~16M vetores × 128) e re-quantiza o centróide ao final.

A busca IVF em runtime opera em duas etapas:
1. **Seleção de clusters**: calcula a distância da query para todos os K centróides;
   seleciona os `nprobe` mais próximos.
2. **Varredura seletiva**: percorre apenas os registros dos clusters selecionados,
   mantendo um max-heap de tamanho k com os menores distâncias.

### Smoke test — Clusterização (K=256, nprobe=8)

```
http_req_duration avg=257ms min=4.53ms med=93ms max=607ms p(90)=593ms p(95)=600ms
```

O **min=4.53ms** foi o sinal mais importante: pela primeira vez, uma requisição
retornou em tempo de milissegundos baixos. Isso confirmou que o IVF funcionava — ao
visitar apenas 8 dos 256 clusters, a busca examinava ~93.750 vetores em vez de
3.000.000. O potencial estava provado; o ajuste fino de K e nprobe viria na Issue 04.

A média alta (~257ms) e a variância grande eram esperadas no smoke test por causa do
JIT warmup e page cache frio — os primeiros requests penalizados, os últimos voando.

---

## Issue 03 — Guarda de Qualidade e Parametrização

Com o IVF funcionando, o risco passou a ser de regressão silenciosa: uma mudança em
K ou nprobe poderia melhorar latência às custas de recall sem ninguém perceber.

A Issue 03 introduziu o **V2QualityGuardTest**: uma suíte que compara os resultados
do IVF contra a busca exata em float32 (ground truth) para um conjunto de queries
de referência. Se o recall cair abaixo do limiar, o build falha.

Também foram parametrizadas as variáveis de ambiente que a Issue 04 precisaria variar
sem recompilar o JAR:

| Variável | Padrão | Papel |
|---|---|---|
| `K_NEIGHBORS` | 5 | Tamanho da vizinhança para calcular fraud_score |
| `FRAUD_THRESHOLD` | 0.6 | Fração mínima de fraudes para rejeitar |
| `NPROBE` | 8 | Clusters visitados por requisição |
| `NUM_CLUSTERS` | 256 | K do K-means (via ARG no Dockerfile) |

---

## Issue 04 — Matriz de Benchmark K × nprobe

A etapa mais densa de dados: 28 configurações testadas com o K6 oficial (54.100
requisições, carga realista). A cada run, o script de benchmark capturava p99 e o
scoring completo da Rinha (score_p99, score_det, final_score, FP/FN).

### A fórmula de score da Rinha

O score penaliza simultaneamente latência e erros de detecção:

```
final_score = score_p99 + score_det

score_p99   = f(p99_ms)       — decresce com latência (corte em p99 > 3s → -3000)
score_det   = f(FP + FN)      — decresce com erros de detecção (corte em > 15% → -3000)
```

Isso cria uma **tensão estrutural**: aumentar nprobe melhora recall (menos FP/FN)
mas piora latência. Encontrar o ponto de equilíbrio era o objetivo da Issue 04.

### Top 10 configurações (por final_score)

| Configuração | p99 | final_score | score_p99 | score_det | FP/FN | Erros HTTP |
|---|---|---|---|---|---|---|
| **K=1024, nprobe=4** | **36.95ms** | **2766.95** | **1432.39** | **1334.56** | **105/102** | **0** |
| K=2048, nprobe=16 | 54.91ms | 2610.22 | 1260.35 | 1349.87 | 106/98 | 0 |
| K=512, nprobe=4 | 54.37ms | 2592.15 | 1264.64 | 1327.51 | 107/103 | 0 |
| K=2048, nprobe=8 | 61.3ms | 2560.86 | 1212.55 | 1348.30 | 110/97 | 0 |
| K=512, nprobe=8 | 89.82ms | 2402.05 | 1046.64 | 1355.42 | 105/97 | 0 |
| K=1024, nprobe=8 | 111.35ms | 2315.91 | 953.31 | 1362.60 | 103/96 | 0 |
| K=4096, nprobe=4 | 103.82ms | 2291.32 | 983.73 | 1307.58 | 131/100 | 0 |
| K=2048, nprobe=4 | 117.41ms | 2260.39 | 930.29 | 1330.11 | 117/99 | 0 |
| K=2048, nprobe=6 | 126.93ms | 2232.09 | 896.44 | 1335.65 | 113/99 | 0 |
| K=4096, nprobe=6 | 158.97ms | 2118.63 | 798.69 | 1319.94 | 121/100 | 0 |

### O que os dados ensinaram

**1. K pequeno com nprobe alto destrói o contêiner.**
K=64 e K=128 com nprobe≥8 geraram centenas a milhares de erros HTTP. Com K=64,
cada cluster tem ~47.000 vetores; nprobe=8 significa varrer ~375.000 vetores por
requisição, em um contêiner com 1 CPU e 350MB de RAM. Caiu na faixa de p99 > 2s
e score negativo.

**2. K grande demais também não escala.**
K=4096 com nprobe=4 visitava só 4 clusters mas o custo de **comparar a query contra
4096 centróides** antes de começar a varredura já era expressivo. O p99 ficou em
103ms — pior que K=1024 com nprobe=4 (36ms), mesmo visitando a mesma proporção
de dados.

**3. O ponto ótimo é onde clusters são enxutos e seleção de centróide é barata.**
K=1024 → ~2930 vetores/cluster. Com nprobe=4, a busca examina ~11.720 vetores
(0.39% dos 3M). A seleção de centróide custa 1024 comparações — barato. O resultado
foi 36.95ms de p99 com zero erros HTTP.

**4. Aumentar nprobe melhora pouco a detecção, mas piora muito a latência.**
De K=1024/nprobe=4 para K=1024/nprobe=8, o score_det sobe de 1334 para 1362
(+28 pontos) mas o p99 vai de 36ms para 111ms. O score_p99 cai 479 pontos. Troca
desfavorável — e isso revela que o teto do IVF aproximado está próximo.

**5. O teto de detecção do IVF é estrutural, não paramétrico.**
Em todas as configurações saudáveis, o score_det ficou na faixa de 1300–1365.
Mesmo com K=1024/nprobe=32 (visitando 3.1% dos dados), o score_det não passa de
~1365. O problema não é quantidade de dados examinados — é que queries na **fronteira
de cluster** têm seus 5 vizinhos reais em clusters não visitados. IVF aproximado
gera FP e FN por design.

### Vencedor: K=1024, nprobe=4

```
p99           36.95ms
final_score   2766.95
score_p99     1432.39
score_det     1334.56
FP            105
FN            102
Erros HTTP    0
Recall        99.57%
```

---

## Issue 05 — PRD e Roadmap (Planejamento)

Com o envelope operacional definido (K=1024, nprobe=4), a Issue 05 produziu o PRD
e roadmap das próximas versões, com base em análise competitiva de 5 implementações
da Rinha (AndDev741, arthurd3, Papagaio, lucasmontano, EdnaldoLuiz).

A descoberta central: **AndDev741 tem p99=87ms (2× mais lento que nós) mas score=4.057
(1.5× melhor)**. Razão: 0 FP e 0 FN via busca exata com bounding-box pruning. Isso
confirmou que o gargalo não é latência — é qualidade de detecção.

---

## Fix — K-means Paralelo (Desbloqueio do Build K=2048)

Durante os experimentos com K=2048, o `docker build` parava na mensagem
`rodando K-means k=2048 iter=20` por vários minutos, sem nenhuma saída, parecendo
travado.

Não havia travamento real. O problema era **lentidão extrema sem feedback**:

| | K=256 | K=2048 |
|---|---|---|
| Ops por iteração | ~10.7 bilhões | ~86 bilhões |
| Tempo por iteração (serial) | ~8s | ~70s |
| 20 iterações | ~3min | ~25min |
| Log entre iterações | nenhum | nenhum |

A combinação "muito lento" + "completamente silencioso" foi lida como "congelado".

**Correção:** o passo de atribuição (`assignStep`) é trivialmente paralelo — cada
vetor é atribuído de forma independente, escrevendo em índices distintos de
`assignments[]`. Com `IntStream.parallel()`, o resultado é **bit-a-bit idêntico**
ao serial (artefato determinístico por seed). Nos 12 cores do estágio de build:

| | Antes (serial) | Depois (paralelo) |
|---|---|---|
| Tempo por iteração | ~70s | ~7s |
| K-means total (20 iter) | ~25min | ~150s |
| Feedback | nenhum | log por iteração |

O K-means roda apenas no **estágio 1 do Docker (build)**, com todos os cores da
máquina disponíveis. A restrição de 1 CPU / 350MB vale somente para o estágio 2
(runtime), que carrega o artefato já pronto e nunca executa K-means.

---

## Resultado Final da V2 (Tesseract)

### Comparação com a V1

| Métrica | V1 (float32 brute force) | V2 (IVF int8 K=1024/nprobe=4) | Ganho |
|---|---|---|---|
| p99 | ~2000ms | 36.95ms | **54× mais rápido** |
| avg | ~1110ms | ~10ms (hot) | **>100×** |
| Erros HTTP | 0 | 0 | mantido |
| Recall | 100% | 99.57% | -0.43% |
| FP + FN | 0 | 207 | custo do IVF aproximado |

### Confronto com as metas da V2

| Meta | Valor-alvo | Alcançado | Status |
|---|---|---|---|
| Meta principal: p99 | ≤ 25ms | 36.95ms | Próximo, não atingido |
| Meta esticada: p99 | ~10ms | 36.95ms | Requer V3/V4 |
| Erros HTTP | 0 | 0 | Atingida |
| Taxa de falhas | < 15% | 0.38% | Atingida |

A meta de 25ms não foi atingida, mas o result de 36.95ms representa uma melhoria
de 54× sobre a V1. O caminho para 25ms (e além) passa pela V3 (Celeritas) —
page pre-warming que elimina a variância de latência cold vs. hot.

### O que limita a V2

Dois gargalos estruturais:

1. **Page fault penalty (cold reads):** Com mmap, o SO carrega páginas do disco sob
   demanda. O primeiro benchmark de uma sessão roda "frio" — page faults elevam o
   p99 observado. Empiricamente: ~130ms no frio vs. ~36ms no quente. O K6 mede um
   mix dos dois. Pre-warming (V3-A) elimina a variância.

2. **Detecção aproximada (FP/FN estruturais):** IVF com nprobe=4 visita 0.39% dos
   dados. Queries cuja vizinhança real está espalhada entre clusters distintos vão
   errar. Não há parametrização que resolva isso sem mudar o algoritmo. A solução
   é bounding-box pruning (V4-A), que transforma o IVF aproximado em busca exata
   sem custo de varredura.

---

## Legado para a V3 e além

A branch `tesseract` entrega:

- **Artefato V2** compacto (45–48 MB), layout binário otimizado, acessado via mmap
- **K-means Lloyd int8** determinístico, paralelo, com 20 iterações e semente fixa
- **V2IndexSearcher** com busca IVF dois passos, max-heap primitivo (`long[]` bit-shift)
- **Guarda de qualidade** automática (V2QualityGuardTest, V2IvfSearchTest)
- **Parametrização completa** de K, nprobe, k_neighbors, fraud_threshold via env vars
- **Roadmap versionado** com nomes (Celeritas → Veritas → Opus → Pontifex → Sapientia)
  e análise competitiva de 5 repositórios

O próximo passo imediato é a **V3 Celeritas** — page pre-warming + respostas pré-serializadas —
para eliminar a variância de latência e medir o p99 real em condições de hot cache.
O salto de score virá com a **V4 Veritas**: bounding-box pruning que entrega 0 FP/FN,
potencialmente levando o `final_score` de 2.767 para ~3.400+.
