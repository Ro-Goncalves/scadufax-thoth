# Opus: Plano de Execução

## Contexto

A V4 (Veritas) entregou o teto de detecção: `score_det=3.000` (0 FP / 0 FN),
`final_score ~4.394`. Paridade com o `arthurd3` na Onda 5 — mesmos ~4.393, mesmo
envelope operacional, mesma stack Java com busca exata i16.

A partir daqui o roadmap gira de **detecção** para **latência**. O `score_p99` ainda
tem folga: nossos ~40ms valem ~1.432; p99=5ms valeria ~1.565 extra. A diferença real
está na cauda: GC pauses residuais, overhead L7 do nginx e JIT warmup consomem os
~25ms que nos separam dos ~15ms da Onda 13 do arthurd3.

**Objetivo da V5:** `~4.394 → ~4.800+` em score, `~40ms → ~15ms` em p99 — análogo
ao salto Onda 5→13 do arthurd3 (GraalVM AOT+PGO + cpuset + HAProxy splice).

> A meta de **5ms p99** pertence à **V6** (fd-passing, −80% p99 confirmado pelo arthurd3
> na Onda 30). A V5 chega a ~15ms; a V6 cruza o threshold.

---

## Alocações residuais no hot path

O V4-C (`FraudRequestParser`) e o V3-D (`TopKSelector`) zeraram as alocações de
entrada e de candidatos varridos. O que sobra em `V2IndexSearcher.search()`:

| Alocação | Tamanho | Frequência |
|---|---|---|
| `quantizeQuery()` → `new int[14]` | 56 bytes | por request |
| `toI16Query()` → `new short[14]` | 28 bytes | por request |
| `rankClusters()` → `new long[K]` | **8.192 bytes** | por request |
| `rankClusters()` → `new int[K]` | **4.096 bytes** | por request |
| `new TopKSelector(5)` → `double[5]` + `byte[5]` | 88 bytes | por request |

Custo dominante: **~12KB por request** de `rankClusters`. Com SerialGC e heap de
80MB sob carga, esse padrão acumula pressure suficiente para disparar minor GC a
cada poucas centenas de requisições — e cada pausa infla o p99.

---

## V5-0: ThreadLocal\<SearchState\> no V2IndexSearcher

**Pré-condição:** ✅ virtual threads desligadas (platform threads desde o fix do V4-C).

**Escopo decidido:** eliminar os ~12KB de `rankClusters` + inline do estado do
`TopKSelector` (88 bytes). O `materialize()` ainda cria 6 objetos curtos (ArrayList +
5 SearchResult) — Eden trivial que SerialGC varre sem pause visível.

**Estrutura:**

```java
private static final class SearchState {
    final int[]    qi;
    final short[]  q16;
    final long[]   distAndIdx; // ~12KB com K=1024
    final int[]    ranked;
    // TopKSelector inlined
    final double[] topDist;
    final byte[]   topLabel;

    SearchState(int numClusters) {
        qi         = new int[DIMS];
        q16        = new short[DIMS];
        distAndIdx = new long[numClusters];  // lido do artefato no construtor
        ranked     = new int[numClusters];
        topDist    = new double[K_NEIGHBORS];
        topLabel   = new byte[K_NEIGHBORS];
    }
}
```

**Por que `numClusters` e não constante:** `V2IndexSearcher` lê `numClusters` do
header do `.v2` na construção. Passar esse valor ao `SearchState` elimina o risco de
mismatch caso o artefato seja rebuiltado com `NUM_CLUSTERS` diferente.

```java
// No construtor de V2IndexSearcher, após ler o header:
final int nc = this.numClusters;
this.searchState = ThreadLocal.withInitial(() -> new SearchState(nc));
```

**Mudanças necessárias:**

- `quantizeQuery(float[] v, int[] out)` — escreve in-place em `out`
- `toI16Query(int[] qi, short[] out)` — idem
- `rankClusters(int[] qi, long[] distAndIdx, int[] out)` — idem
- `TopKSelector` ganha `reset()` + campos migram para `SearchState`; o objeto
  `TopKSelector` deixa de ser alocado por request
- `search()` extrai `searchState.get()` no topo e passa os arrays pré-alocados

**Resultado:** zero `new` no hot path de busca. A única alocação restante por request
é `ctx.bodyAsBytes()` (Javalin copia o body para `byte[]`) — desaparece com o servidor
NIO da V6.

---

## V5-1: HAProxy L4 + splice

**Custo:** apenas configuração — `docker-compose.yml` + `haproxy.cfg`. Zero mudança em Java.

O nginx atual opera em L7: parseia cabeçalhos HTTP, reconstrói a requisição e abre
nova conexão para o backend. Isso consome ~0,3 vCPU do orçamento de 1,5 vCPU da
stack. O HAProxy em modo TCP com a opção `splice` vai além de L4 puro: usa o
syscall `splice(2)` do Linux para mover bytes entre sockets **dentro do kernel**,
sem cópia para o espaço do usuário.

```haproxy
# haproxy.cfg
global
    tune.bufsize 4096
    tune.maxaccept 100

frontend rinha
    bind *:9999
    mode tcp
    option splice-request
    option splice-response
    default_backend apis

backend apis
    mode tcp
    balance roundrobin
    option splice-request
    option splice-response
    option tcp-check
    server api1 api1:8080 check inter 1000
    server api2 api2:8080 check inter 1000
```

**Por que splice importa:** arthurd3 combinou cpuset + HAProxy splice na Onda 13 e
caiu de ~32ms para 14,59ms. O `splice(2)` cria um pipe interno no kernel entre o
socket do cliente e o socket do backend — o HAProxy nunca copia os bytes para o seu
próprio espaço de endereço. A latência do LB cai a quase zero.

---

## V5-2: cpuset pinning

**Custo:** três linhas adicionais no `docker-compose.yml`. Zero mudança em código.

Migração de thread entre cores físicos é transparente para o scheduler, mas cara
para o hardware: a linha de cache fica stale no core antigo e o novo core precisa
recarregá-la via L3 ou RAM. Com dois APIs de ~0,45 vCPU, a solução é fixar cada
container a um core físico — o scheduler só vê aquele core, sem migrações.

**Junto com isso:** reduzir o pool de threads do Jetty de 16 para 8. Com `cpuset="0"`
(1 core físico, 0,45 vCPU), 16 threads competem pelo mesmo core. A Lei de Little
ao target de ~15ms com 500 req/s por instância indica ~7-8 threads em voo — 8 é
suficiente e gera menos context-switch.

```yaml
services:
  api1:
    cpuset: "0"
    deploy:
      resources:
        limits:
          cpus: "0.45"
          memory: "165M"
  api2:
    cpuset: "1"
    deploy:
      resources:
        limits:
          cpus: "0.45"
          memory: "165M"
  lb:          # HAProxy
    cpuset: "2"
    deploy:
      resources:
        limits:
          cpus: "0.1"
          memory: "20M"
```

```java
// JavalinBootstrap.java — reduzir junto com o cpuset
javalinConfig.jetty.threadPool = new QueuedThreadPool(8, 2);
```

arthurd3 (Onda 13) e lucasmontano usam esse padrão: lb nos CPUs 2-3, api1 no CPU 0,
api2 no CPU 1.

**Por que aplicar junto com o HAProxy:** arthurd3 não separou os ganhos de cpuset e
HAProxy — foram uma única "Onda 13". Os dois se complementam: menos migração de
contexto ajuda o HAProxy tanto quanto a API, e a mudança no docker-compose é única.

---

## V5-3: GraalVM Native Image + PGO

O item de maior esforço e maior impacto em RAM e latência de inicialização.

| Dimensão | JVM atual | GraalVM Native |
|---|---|---|
| RAM de repouso | ~80MB (JVM + metaspace) | ~15MB (binary) |
| Page Cache disponível | ~195MB | **~260MB** (+65MB) |
| Startup | 2–5s (JIT warmup) | ~50ms (AOT) |
| Variância de p99 no cold start | alta nos primeiros requests | zero — AOT desde o request 1 |
| Tamanho do binário | JAR + JRE ~200MB | **~12MB** |

**O +65MB de page cache é o ganho silencioso:** o `.v2` indexado tem ~56MB (i16).
Com 260MB disponíveis o índice inteiro fica residente em RAM — eliminando soft faults
no `MemorySegment` mmap durante picos de carga.

### java.lang.foreign + GraalVM

`V2IndexSearcher` usa `Arena.ofShared()` e `MemorySegment` para mmap —
APIs de `java.lang.foreign`. arthurd3 documenta que **FFM quebra o GraalVM quando
usado para chamadas de função nativa via `DowncallHandle` / `Linker`**. Nosso caso é
diferente: usamos `MemorySegment` apenas para mapeamento de arquivo, não para invocar
código nativo. Essa variante é suportada pelo GraalVM.

Requer `--enable-native-access=ALL-UNNAMED` nos `buildArgs` do plugin (já presente em
`JAVA_OPTS`; precisa migrar para a diretiva de build).

### Remoção do Jackson

`AppConfig.fromEnvironment()` usa `ObjectMapper` para carregar `normalization.json` e
`mcc_risk.json` do classpath — **único uso de Jackson pós-V4-C**. **Decisão:**
substituir por parser simples de `Map<String, Float>` (~20 linhas). Elimina
jackson-databind do runtime inteiramente: binary menor, Tracing Agent mais simples,
e V6 herda um AppConfig já limpo.

```java
// AppConfig — substitui loadMapFromJar
private static Map<String, Float> loadMap(String resourcePath) {
    try (InputStream is = AppConfig.class.getResourceAsStream(resourcePath);
         BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
        Map<String, Float> map = new LinkedHashMap<>();
        String line;
        while ((line = br.readLine()) != null) {
            // formato: "  \"key\": 0.123," ou "  \"key\": 0.123"
            line = line.trim();
            if (!line.startsWith("\"")) continue;
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(1, line.indexOf('"', 1));
            String val = line.substring(colon + 1)
                             .replaceAll("[,}]", "").trim();
            map.put(key, Float.parseFloat(val));
        }
        return Collections.unmodifiableMap(map);
    }
}
```

### Processo de build

**Passo 1 — remoção do Jackson e substituição do loadMap**

Antes de tocar em GraalVM, substituir `loadMapFromJar` por `loadMap` (acima) e
validar `mvn -q compile` + smoke.

**Passo 2 — native-maven-plugin no pom.xml**

```xml
<profiles>
  <profile>
    <id>native</id>
    <build>
      <plugins>
        <plugin>
          <groupId>org.graalvm.buildtools</groupId>
          <artifactId>native-maven-plugin</artifactId>
          <version>0.10.3</version>
          <configuration>
            <mainClass>br.com.rgbrainlabs.scadufaxthoth.ScadufaxThothApplication</mainClass>
            <buildArgs>
              <buildArg>--enable-native-access=ALL-UNNAMED</buildArg>
              <buildArg>-H:+ReportExceptionStackTraces</buildArg>
            </buildArgs>
          </configuration>
          <executions>
            <execution>
              <id>build-native</id>
              <goals><goal>compile-no-fork</goal></goals>
              <phase>package</phase>
            </execution>
          </executions>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

**Passo 3 — Tracing Agent (captura reflection de Javalin/Jetty)**

```bash
mvn package

java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image \
     -jar target/scadufax-thoth.jar &

# Popula os hot paths com carga real — não só curl
docker compose -f test/docker-compose.yml --profile smoke up --abort-on-container-exit

kill %1
# Commitar os configs gerados em META-INF/native-image/
git add src/main/resources/META-INF/native-image/
```

Os arquivos gerados (`reflect-config.json`, `resource-config.json`, `jni-config.json`)
capturam o que o Tracing Agent observou. Commitá-los é o padrão — o Javalin/Jetty
em si não muda entre builds.

**Passo 4 — Build sem PGO (validação)**

```bash
mvn -Pnative package
./target/scadufax-thoth &
mvn -Pnative verify        # smoke + V2QualityGuardTest
```

Valida que o binary sobe, serve requisições e mantém accuracy antes de qualquer PGO.

**Passo 5 — Loop PGO**

**Decisão:** `default.iprof` gerado localmente e commitado no repo. Padrão do arthurd3.
Simples, determinístico — o perfil fica versionado e só precisa ser regerado quando
o código do hot path muda significativamente.

```bash
# Build instrumentado
mvn -Pnative -DnativeImageArgs="--pgo-instrument" package
mv target/scadufax-thoth target/scadufax-thoth-instrumented

# Gera perfil com carga real do K6
./target/scadufax-thoth-instrumented &
docker compose -f test/docker-compose.yml --profile smoke up --abort-on-container-exit
kill %1
# default.iprof gerado no diretório corrente

mkdir -p src/main/resources/pgo
cp default.iprof src/main/resources/pgo/
git add src/main/resources/pgo/default.iprof

# Build final com perfil
mvn -Pnative \
    -DnativeImageArgs="--pgo=src/main/resources/pgo/default.iprof" \
    package
```

**Passo 6 — Dockerfile multi-estágio**

```dockerfile
FROM ghcr.io/graalvm/native-image-community:25 AS native-build
# Fallback se a imagem community:25 não existir:
# FROM container-registry.oracle.com/graalvm/jdk:25 AS native-build
WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN mvn -q -B -DskipTests package

ADD https://github.com/zanfranceschi/rinha-de-backend-2026/raw/main/resources/references.json.gz \
    /build/src/main/resources/references.json.gz

ARG NUM_CLUSTERS=1024
ARG DTYPE=i16

RUN java -Xmx512m -cp target/scadufax-thoth.jar \
    br.com.rgbrainlabs.scadufaxthoth.prep.V2ArtifactBuilder \
    src/main/resources/references.json.gz /build/data/index.v2 \
    ${NUM_CLUSTERS} 20 42 ${DTYPE}

RUN mvn -q -B -Pnative \
    -DnativeImageArgs="--pgo=src/main/resources/pgo/default.iprof" \
    -DskipTests package

FROM gcr.io/distroless/base-debian12
WORKDIR /app

COPY --from=native-build /build/target/scadufax-thoth /app/api
COPY --from=native-build /build/data /data

ENV V2_ARTIFACT_PATH=/data/index.v2
EXPOSE 8080
ENTRYPOINT ["/app/api"]
```

---

## Decisões fechadas

| Decisão | Escolha |
|---|---|
| Jackson em AppConfig | Substituir por parser simples de Map\<String, Float\> (~20 linhas) |
| Profundidade do SearchState | 12KB de rankClusters + TopKSelector inlined; materialize() mantém 6 objetos curtos |
| MAX\_CLUSTERS no SearchState | Lido de `numClusters` do artefato no construtor — sem constante hardcoded |
| Estratégia de PGO | `default.iprof` gerado localmente e commitado (padrão arthurd3) |
| Pool de threads com cpuset | Reduzir para `maxThreads=8, minThreads=2` (1 core físico, Lei de Little) |
| Imagem GraalVM | `ghcr.io/graalvm/native-image-community:25`; fallback Oracle se indisponível |

---

## Sequência de execução

```
V4 ✅  (score_det=3.000, final_score ~4.394)
│
├── V5-0  ThreadLocal<SearchState>
│         1. Criar SearchState(numClusters) com arrays pré-alocados
│         2. quantizeQuery / toI16Query / rankClusters escrevem in-place
│         3. TopKSelector.reset() + campos migram para SearchState
│         4. search() usa searchState.get() — zero new no hot path
│         Valida: mvn compile + smoke + V2QualityGuardTest
│
├── V5-1 + V5-2  HAProxy L4 splice + cpuset + thread pool
│         1. Substituir nginx por HAProxy com splice (docker-compose + haproxy.cfg)
│         2. cpuset: "0"/"1"/"2" para api1/api2/lb
│         3. QueuedThreadPool(8, 2) em JavalinBootstrap
│         Valida: benchmark K6 completo, p99 < 30ms
│
└── V5-3  GraalVM Native Image + PGO
          1. Substituir loadMapFromJar por loadMap (elimina Jackson do runtime)
          2. native-maven-plugin no pom.xml
          3. Tracing Agent com K6 smoke → META-INF/native-image configs
          4. Build sem PGO → smoke + V2QualityGuardTest
          5. Loop PGO: instrumento → K6 smoke → commitar default.iprof → recompilar
          6. Dockerfile multi-estágio (native-build + distroless)
          7. Benchmark K6 completo (5 boots)
```

---

## Projeção de resultados

Baseado na trajetória do arthurd3 (referência mais próxima — mesma busca exata i16
em Java, paridade de score na Onda 5):

| Versão | p99 estimado | Score estimado | Referência |
|---|---|---|---|
| V4 atual | ~40ms | ~4.394 | medido |
| Após V5-0 + V5-1 + V5-2 | ~20–25ms | ~4.6xx | HAProxy splice + cpuset, sem native |
| Após V5-3 (GraalVM + PGO) | **~15ms** | **~4.8xx** | análogo à Onda 13 (32→14,59ms) |
| Após V6 (fd-passing) | ~3ms | ~5.5xx | análogo à Onda 30 (14,59→2,65ms) |

> arthurd3 foi de 14,59ms para 2,65ms com uma única mudança: HAProxy → Rust LB
> fd-passing (Onda 30). Esse é o gate para perseguir os 5ms.

---

## Gate de progressão para a V6

A V5 está completa quando:
1. Binary GraalVM nativo com `score_det=3.000` (nenhuma regressão de detecção)
2. p99 mediano < 20ms em 5 boots consecutivos
3. HAProxy + cpuset estáveis no K6 de carga completa
4. `V2QualityGuardTest` verde no binary nativo

Esses resultados desbloqueiam a V6: servidor NIO custom (ex-V4-E) + LB
fd-passing em Rust ou C (~50/218 linhas respectivamente) — o caminho para os 5ms.
