# Roadmap: Tesseract V3+

> Pesquisa completa em `.scratch/competitive-analysis/` — 5 repositórios analisados.

## Decisão da V2 (issue 05 — HITL pendente)

A matriz K×nprobe está medida. O vencedor é **K=1024, nprobe=4**:

| Métrica | Valor |
|---|---|
| p99 | 36.95ms |
| final_score | 2.766,95 |
| score_p99 | 1.432,39 |
| score_det | 1.334,56 |
| recall | 99.57% |
| FP / FN | 105 / 102 |
| Erros HTTP | 0 |

---

## O que os competidores ensinaram

Cinco implementações analisadas. A descoberta mais importante não foi sobre
latência.

### A qualidade vale mais que a velocidade no scoring

AndDev741 (Java) tem p99=87ms — **2× mais lento que nós** — mas score=4.056 —
**1.5× melhor que nós**. Motivo: 0 FP e 0 FN. Detecção perfeita via busca exata.

Nosso IVF com nprobe=4 visita 0.4% dos vetores. Queries na fronteira de cluster
têm seus vizinhos reais em clusters não visitados — gerando os 105 FP e 102 FN
que comprimem nosso `score_det` para ~1.334. Com busca exata, `score_det`
potencialmente dobra, levando o `final_score` para 3.400+.

### Comparativo de posições

| Implementação | Lang | Score | p99 | FP/FN | Técnica central |
|---|---|---|---|---|---|
| **Nós (V2)** | Java | 2.767 | 36.95ms | 105/102 | IVF aprox. int8 |
| AndDev741 | Java | 4.057 | 87.73ms | 0/0 | IVF exato + bbox pruning |
| arthurd3 | Java+Rust | 5.731 | 1.86ms | 0/0 | KD-tree + Rust FFI + fd-passing |
| Papagaio | Rust | 6.000 | 0.13ms | 0/0 | MLP router + IVF |
| lucasmontano | Rust+C | 6.000 | 0.252264ms | 0/0 | KD-tree + C LB + busy-poll |

### O que NÃO funciona (confirmado empiricamente)

**Java Panama Vector API / SIMD: NÃO implementar.**
arthurd3 removeu completamente — foi 3.8× mais lento que scalar e quebra o
build do GraalVM Native Image. Nenhum top performer Java usa Vector API.
Removido do roadmap.

---

## Por que a V2 parou em 36ms e FP/FN ≠ 0

**Latência residual:**
1. Page fault penalty — mmap frio; observado empiricamente: 130ms → 36ms entre runs
2. JIT warmup — kernel de distância compila iterativamente; penaliza primeiros requests
3. JVM overhead — ~80MB de RAM deixam só ~195MB para Page Cache do `.v2`

**FP/FN ≠ 0:**
IVF é busca **aproximada**. nprobe=4 de 1024 clusters = 0.4% dos vetores visitados.
Queries de fronteira têm vizinhos reais em clusters não visitados.

---

## V3: Celeritas — Remoção de overhead — rápido de implementar

Todos os itens são independentes e podem ser feitos em qualquer ordem.
Nenhum exige mudança no algoritmo de busca.

### V3-A: Page pre-warming

Leitura sequencial do `.v2` inteiro antes de aceitar conexões.
Confirmado por AndDev741, arthurd3 e lucasmontano como técnica padrão.

```java
// No bootstrap, antes de /ready retornar true
try (FileChannel ch = FileChannel.open(v2Path, StandardOpenOption.READ)) {
    ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20);
    while (ch.read(buf) != -1) buf.clear();
}
```

Elimina variância 130ms → 36ms. O K6 passa a medir steady state desde o request 1.

**Risco:** startup aumenta alguns segundos. `/ready` só retorna `200` após completar.

### V3-B: Respostas pré-serializadas

6 possíveis resultados (`fraud_count` ∈ {0..5}). Pré-alocar como `byte[]` no bootstrap.
Confirmado por todos os top performers (EdnaldoLuiz, AndDev741, arthurd3, lucasmontano).

```java
static final byte[][] RESPONSES = new byte[6][];
static {
    for (int i = 0; i < 6; i++) {
        boolean approved = (double) i / 5 < FRAUD_THRESHOLD;
        double score = (double) i / 5;
        RESPONSES[i] = ("{\"approved\":" + approved
            + ",\"fraud_score\":" + score + "}").getBytes(UTF_8);
    }
}
```

Zero serialização por request. Elimina alocação de String e GC pressure.

### V3-C: nginx em modo stream + Unix domain sockets

Trocar o bloco `http {}` por `stream {}`. nginx passa a encaminhar TCP puro
sem parsear HTTP. Confirmado por papagaio (-0.01ms) e lucasmontano.

```nginx
stream {
    upstream api_backend {
        server unix:/tmp/api1.sock;
        server unix:/tmp/api2.sock;
    }
    server {
        listen 9999 reuseport backlog=4096;
        proxy_pass api_backend;
    }
}
```

Requer que Javalin (ou o servidor custom de V4-C) escute em Unix domain socket
(`UnixDomainSocketAddress`, disponível desde Java 16).

**Ressalva:** modo stream perde HTTP keepalive gerenciado pelo nginx. Medir
ganho líquido — impacto depende do volume de conexões persistentes.

---

## V4: Veritas — Busca exata e hot path

O conjunto de mudanças com maior impacto potencial de score.

### V4-A: Bounding-box pruning por cluster ⭐ prioridade máxima

Transforma o IVF aproximado em busca exata. Confirmado por AndDev741 como
responsável pelos 0 FP e 0 FN que valem 4.057 de score com p99=87ms.

**Build time:** para cada cluster, calcular e persistir `int8[14] bboxMin` e
`int8[14] bboxMax` (em int8 já que os vetores são int8). São `2 × 14 × 1024`
bytes = 28KB adicionais no artefato `.v2`.

**Query time:** após ranquear centroides e selecionar os `nprobe` clusters para
varredura completa, iterar sobre os clusters restantes:

```java
private int bboxLowerBound(byte[] query, byte[] bboxMin, byte[] bboxMax) {
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

Se `lb > currentWorst` (distância int do 5º vizinho atual) → skip o cluster.
Prova matemática por triângulo: nenhum vetor dentro do cluster pode melhorar
o top-5. Resultado: busca exata com ~95% dos clusters ignorados.

**Impacto estimado:** com 0 FP e 0 FN, `score_det` sobe de ~1.334 para ~2.000+.
`final_score` estimado: 1.432 (p99) + 2.000 (det) = **3.400+**, bem acima do verde.

**Mudanças necessárias:**
- `V2ArtifactBuilder`: calcular min/max por cluster durante build, persistir no diretório
- `V2IndexSearcher`: ler bboxes do header, aplicar pruning no query time
- `V2QualityGuardTest`: validar que busca exata tem 100% de acordo com float32 BF

### V4-B: IVF repair para queries ambíguas

Técnica do lucasmontano: se após a busca o resultado for ambíguo
(`fraud_count` ∈ {1,2,3,4} — próximo da fronteira de decisão a 0.6),
escanear clusters adicionais até acumular mais candidatos.

```java
// Após busca inicial com nprobe clusters
int fraudCount = countFraud(topK);
if (fraudCount >= 1 && fraudCount <= 4) {
    // Resultado incerto — expandir busca para clusters vizinhos
    expandSearch(query, topK, additionalClusters);
}
```

Complementa V4-A: a bbox pruning garante exatidão para queries claras; o repair
garante qualidade extra nas queries de fronteira que mais importam para o score.

### V4-C: Custom JSON parser (zero-alocação)

Jackson aloca por request. Para schema fixo e payload pequeno, cursor-based
é 10–20× mais rápido e zero-alocação. Confirmado por todos os top performers.

Referências de implementação:
- `JsonReader.java` do AndDev741 (~300 linhas) — Java, cursor-based
- `FraudRequestParser.java` do arthurd3 — single-pass com índice de chaves

Técnica chave: ISO-8601 → epoch seconds sem `Instant`/`ZonedDateTime`, usando
algoritmo de Howard Hinnant (daysFromCivil). O caller passa `float[]` pré-alocado;
o parser escreve in-place — zero `new` no hot path.

### V4-D: Insertion sort para K=5

`PriorityQueue` tem overhead de boxing e reorganização de heap. Para K=5 fixo,
insertion sort é O(5) com acesso linear, cache-friendly, zero alocação.

```java
// Array de 5 slots, mantido ordenado por distância crescente
// thread-local — reuso entre requests
private final long[] topDists  = new long[5];  // distância
private final byte[] topLabels = new byte[5];   // label int8

void tryInsert(long dist, byte label) {
    if (size < 5 || dist < topDists[size - 1]) {
        int pos = size < 5 ? size++ : 4;
        while (pos > 0 && topDists[pos - 1] > dist) {
            topDists[pos]  = topDists[pos - 1];
            topLabels[pos] = topLabels[pos - 1];
            pos--;
        }
        topDists[pos]  = dist;
        topLabels[pos] = label;
    }
}
```

### V4-E: Custom HTTP server single-threaded (NIO)

Substituir Javalin por NIO reactor (~500 linhas). Modelo single-threaded elimina
context switches com 0.45 vCPU. Confirmado por arthurd3 e AndDev741.

Micro-otimizações do arthurd3 a incorporar:
- `selectNow()` antes de `select()` — evita syscall quando dados já buffered
- Inline response write — escreve resposta no path de leitura (elimina um epoll_wait)

**Pré-requisito:** V4-C (custom JSON parser) — sem Jackson, o servidor pode ser
completamente desacoplado do framework.

**Integração com V3-C:** se nginx está em stream mode, o servidor precisa escutar
em `UnixDomainSocketAddress`.

---

## V5: Opus — GraalVM Native Image + PGO

**Objetivo:** eliminar JVM do contêiner, liberar ~65MB de RAM para Page Cache.

| Dimensão | JVM atual | GraalVM Native |
|---|---|---|
| RAM de repouso | ~80MB | ~15MB |
| Page Cache disponível | ~195MB | ~260MB (+65MB) |
| Startup | segundos | milissegundos |
| JIT warmup | sim | zero (AOT) |

**Nota crítica: usar PGO (Profile-Guided Optimization).**
arthurd3 usa `default.iprof` no build do Native Image. Sem PGO, o ganho é menor.
Processo:
1. Rodar a app com agent de instrumentação: `native-image --pgo-instrument`
2. Executar K6 smoke para gerar o perfil
3. Recompilar com o perfil: `native-image --pgo=default.iprof`

**Caminho de implementação:**
1. Adicionar `native-maven-plugin` ao `pom.xml`
2. Rodar Tracing Agent com K6 smoke (captura reflection/resources de Javalin+Jackson)
3. Gerar `reflect-config.json`, `resource-config.json` em `META-INF/native-image`
4. Novo Dockerfile: build com `ghcr.io/graalvm/native-image-community:25`, runner `distroless`
5. Adicionar loop de PGO (instrumento → smoke → recompila)
6. Validar `V2QualityGuardTest` e smoke K6

**Nota:** V4-C (parser custom) e V4-E (servidor custom) simplificam muito o GraalVM
porque eliminam Jackson e Javalin — principais fontes de reflection.

**Referências:**
- arthurd3: binário final de 12MB, score 5.731 partindo de ~4.393 sem Rust

---

## V6: Pontifex — File descriptor passing (maior salto de infra)

Substituir nginx/HAProxy por um LB minimal que passa client fds via SCM_RIGHTS.

**arthurd3 Onda 30:** HAProxy → Rust LB fd-passing deu **-80% de p99**
(2.65ms → 1.86ms). Maior ganho único de infra em toda a jornada dele.

**Mecanismo:**
1. LB aceita TCP connection na porta 9999
2. Passa o file descriptor do client via `sendmsg()` com `SCM_RIGHTS`
3. API Java recebe o fd via `recvmsg()`, wraps em `SocketChannel`
4. Nenhum byte de payload passa pelo LB — só o fd é transferido

**Implementações de referência:**
- lucasmontano: C estático, 218 linhas, `-O3 -march=haswell -static -flto`
  - `TCP_DEFER_ACCEPT`: kernel aguarda dados antes de wakeup
  - Accept batching: 128 conexões por batch
- arthurd3: Rust ~50 linhas de lógica real, FFI exposta para Java

**Java recebendo fds:**
arthurd3 usa reflection para fazer `FdWrap.wrapFd(fd)` → `SocketChannel`.
Requer `--add-opens` no GraalVM ou acesso via sun.nio interno.

**CPU affinity:** lucasmontano pina lb nos CPUs 2-3 e workers nos CPUs 0 e 1.
Configurável via `cpuset_cpus` no docker-compose.

---

## V7: Sapientia — Knowledge distillation (longo prazo)

Replicar a abordagem do Papagaio em Java: MLP router que elimina IVF para 96%
das queries.

**Conceito:**
MLP 3 camadas (14→64→64→3, ~5K parâmetros, ~20KB) treinado sobre o ground truth
da busca exata. Classifica cada query em A-Legítimo, A-Fraude, ou B (incerto).

- **Fast path (96.5%):** MLP decide em ~1µs sem tocar no índice
- **Slow path (3.5%):** IVF ou KD-tree sobre subconjunto de fronteira (~213k refs)

**Acoplamento numérico crítico** (lição do Papagaio):
O vetor de entrada do MLP deve usar exatamente o mesmo arredondamento `round4`
que o data generator aplica antes de calcular os k-NN de referência. Uma
divergência de arredondamento numa dimensão pode flip um tie-break e introduzir
erros de classificação. Documentar e testar cada constante.

**Implementação Java:**
- Treino: Python/PyTorch, exporta pesos como `float[]` binário
- Inferência: multiplicações de matriz + GELU — ~200 FLOPs, trivial em Java
- Sem dependência de framework ML em runtime

**Pré-requisito:** V4-A (bbox pruning) estabilizado. O MLP é treinado sobre o
resultado da busca exata — sem busca exata como ground truth, o treinamento
é comprometido.

---

## Sequência recomendada

```
V2 finalizada (K=1024, nprobe=4)
    └── Issue 05: congelar envelope operacional
         │
         ├── V3 Celeritas (velocidade)
         │   ├── V3-A  page pre-warming        ← 1-2h, elimina variância de latência
         │   ├── V3-B  respostas pré-serializadas ← 30min, elimina alocação no hot path
         │   └── V3-C  nginx stream + Unix sockets
         │
         └── V4 Veritas (verdade) — bounding-box pruning ⭐ ← PRIORIDADE MÁXIMA
                   (busca exata → 0 FP/FN → score ~3.400+)
                    │
                    ├── V4-A  bounding-box pruning
                    ├── V4-B  IVF repair para fronteira
                    ├── V4-C  custom JSON parser (zero-alocação)
                    ├── V4-D  insertion sort K=5
                    ├── V4-E  custom HTTP server NIO
                    │
                    ├── [medir score — se ≥ 4.000: V5/V6 são incrementais]
                    │
                    ├── V5 Opus (obra-prima)
                    │      GraalVM Native Image + PGO
                    │      (simplificado após V4-C/E eliminarem Jackson+Javalin)
                    │
                    ├── V6 Pontifex (pontes)
                    │      fd-passing LB (C ou Rust) ← maior salto de infra
                    │      (-80% p99 confirmado por arthurd3)
                    │
                    └── V7 Sapientia (sabedoria)
                           Knowledge distillation ← longo prazo
```

**Gate de decisão principal — após V4-A:**
Se bbox pruning levar `score_det` de ~1.334 para ~2.000+, o `final_score` cruza
3.400 e entra em território de top-10 Java. A partir daí, V5 (GraalVM) e V6
(fd-passing) são os alavancadores de latência para perseguir o top-5.
