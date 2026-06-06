# PRD: Tesseract V5 — Opus

Status: ready-for-agent

> Plano de execução técnico de referência: [`docs/knowledge/v5/01-opus.md`](../../docs/knowledge/v5/01-opus.md).
> Roadmap de versões: [`docs/knowledge/roadmap.md`](../../docs/knowledge/roadmap.md).
> Benchmark de fechamento da V4: [`docs/knowledge/v4/06-benchmark-veritas.md`](../../docs/knowledge/v4/06-benchmark-veritas.md).

## Declaração do problema

A V4 (Veritas) entregou o teto de detecção: `score_det=3.000`, `final_score ~4.394`,
paridade com o arthurd3 na Onda 5. O `score_det` está no teto — a única folga restante
está no `score_p99`.

O p99 mediano está em ~40ms. A análise de causas aponta três fontes independentes:

1. **GC pressure residual:** `V2IndexSearcher.search()` aloca ~12KB por request em
   `rankClusters` (`long[1024]` + `int[1024]`), mais 88 bytes de `TopKSelector` e
   56+28 bytes em `quantizeQuery`/`toI16Query`. O SerialGC com heap de 80MB dispara
   minor GC com frequência suficiente para aparecer no p99.
2. **Overhead de LB L7:** o nginx parseia cabeçalhos HTTP e reconstrói a requisição
   antes de repassar ao backend, consumindo ~0,3 vCPU do orçamento de 1,5 vCPU da
   stack.
3. **JVM runtime overhead:** ~80MB de heap + metaspace deixam só ~195MB para Page
   Cache; o JIT compila iterativamente nas primeiras requisições; os ~65MB de RAM
   que a JVM ocupa poderiam manter o índice `.v2` de ~56MB inteiramente residente.

A referência de comparação é o arthurd3 na Onda 5 (~32ms, ~4.393 score) → Onda 13
(~14,59ms, ~4.836 score), salto alcançado combinando GraalVM AOT+PGO, HAProxy splice
e cpuset pinning — as mesmas três intervenções mapeadas neste PRD.

## Solução

A V5 (Opus) endereça as três fontes em sequência:

- **V5-0 — ThreadLocal\<SearchState\>:** pré-alocar por thread todos os arrays do hot
  path de busca (`rankClusters`, `quantizeQuery`, `toI16Query`) e inline do estado do
  `TopKSelector`, eliminando os ~12KB de GC pressure por request.
- **V5-1 + V5-2 — HAProxy splice + cpuset:** substituir nginx por HAProxy em modo TCP
  com `splice(2)` e fixar cada container a um core físico, combinando redução de
  overhead de LB com eliminação de cache thrashing inter-core.
- **V5-3 — GraalVM Native Image + PGO:** compilar AOT com perfil de execução real
  (PGO), eliminando JVM do container e liberando ~65MB de Page Cache.

Objetivo: `~40ms → ~15ms` em p99, `~4.394 → ~4.800+` em score.

## Histórias de usuário

### V5-0 — Eliminação de alocações no hot path de busca

1. Como engenheiro de performance, eu quero que `rankClusters` reutilize arrays
   pré-alocados por thread, para eliminar os ~12KB de alocação por request que são
   a maior fonte de GC pressure residual no hot path de busca.
2. Como engenheiro de performance, eu quero que `quantizeQuery` escreva in-place em
   um array pré-alocado por thread, para eliminar o `new int[14]` por request.
3. Como engenheiro de performance, eu quero que `toI16Query` escreva in-place em um
   array pré-alocado por thread, para eliminar o `new short[14]` por request.
4. Como engenheiro de performance, eu quero que o estado do `TopKSelector` (arrays
   `topDist` e `topLabel`) seja reutilizado entre requests da mesma thread, para
   eliminar os 88 bytes de alocação do `TopKSelector` por request.
5. Como mantenedor, eu quero que o `TopKSelector` exponha um método `reset()` que
   restaure completamente o estado inicial, para permitir reutilização sem criar novo
   objeto a cada chamada.
6. Como mantenedor, eu quero que o `SearchState` seja inicializado com `numClusters`
   lido do artefato no construtor do searcher, para que não haja risco de mismatch
   caso o artefato seja rebuiltado com `NUM_CLUSTERS` diferente.
7. Como mantenedor, eu quero que a interface `VectorSearcher` não mude, para que
   testes e a guarda de qualidade existentes continuem válidos sem reescrita.
8. Como mantenedor, eu quero que `search()` continue retornando `List<SearchResult>`
   normalmente via `materialize()`, para que o `SearchHandler` não precise mudar.
9. Como mantenedor, eu quero que a pré-condição de virtual threads desligadas esteja
   satisfeita antes desta mudança, para que o `ThreadLocal<SearchState>` não reproduza
   a regressão do `ParseState` da V4-C.
10. Como engenheiro de performance, eu quero que nenhum array de `SearchState` seja
    redimensionado ou recriado em runtime, para garantir que a pré-alocação seja
    permanente durante todo o ciclo de vida da thread.

### V5-1 — HAProxy L4 + splice

11. Como engenheiro de infra, eu quero substituir o nginx pelo HAProxy em modo TCP,
    para liberar ~0,3 vCPU do orçamento que o nginx consumia processando L7.
12. Como engenheiro de infra, eu quero que o HAProxy use as opções `splice-request`
    e `splice-response`, para que o kernel copie bytes entre sockets via `splice(2)`
    sem envolvimento do espaço de usuário do HAProxy.
13. Como mantenedor, eu quero que a substituição nginx→HAProxy não exija nenhuma
    mudança no código Java, para manter o escopo em configuração pura.
14. Como avaliado na Rinha, eu quero que o `score_det` não regrida após a troca de
    LB, para confirmar que a mudança de infra é transparente para a detecção.
15. Como operador de benchmark, eu quero que o haproxy.cfg seja versionado no
    repositório, para que o build seja reproduzível sem configuração manual.

### V5-2 — cpuset pinning + thread pool

16. Como engenheiro de infra, eu quero que cada container da API esteja fixado a um
    core físico via `cpuset`, para eliminar migrações de thread entre cores e
    o thrashing de cache L1/L2 associado.
17. Como engenheiro de infra, eu quero que o HAProxy esteja fixado a um core diferente
    dos APIs, para que o LB não concorra pelo mesmo cache L1 das threads de busca.
18. Como engenheiro de performance, eu quero reduzir o pool de threads do Jetty de 16
    para `maxThreads=8, minThreads=2`, para diminuir context-switch overhead num
    container fixado a 1 core físico com 0,45 vCPU.
19. Como mantenedor, eu quero que cpuset e HAProxy sejam aplicados no mesmo commit e
    benchmark, para medir o efeito combinado conforme a referência do arthurd3 Onda 13.
20. Como operador de benchmark, eu quero validar com K6 que o p99 cai abaixo de 30ms
    após V5-1 e V5-2 (antes do GraalVM), para separar o ganho de infra do ganho de AOT.

### V5-3 — Remoção do Jackson

21. Como engenheiro de build, eu quero substituir o `ObjectMapper` do `AppConfig` por
    um parser simples de `Map<String, Float>` (~20 linhas), para eliminar
    jackson-databind do runtime inteiramente.
22. Como mantenedor, eu quero que o parser simples produza `Map` idêntico ao que o
    Jackson produzia para os arquivos `normalization.json` e `mcc_risk.json`, para que
    nenhum comportamento observable do `AppConfig` mude.
23. Como engenheiro de build, eu quero remover as dependências jackson do escopo runtime
    no pom.xml, para simplificar o Tracing Agent do GraalVM e reduzir o tamanho do
    binary nativo.

### V5-3 — GraalVM Native Image + PGO

24. Como engenheiro de build, eu quero adicionar o `native-maven-plugin 0.10.3` ao
    pom.xml como perfil `native`, para construir um binary nativo sem alterar o build
    JVM padrão (`mvn package` continua gerando o JAR).
25. Como engenheiro de build, eu quero rodar o Tracing Agent com o K6 smoke real,
    para capturar toda a reflection do Javalin/Jetty de forma abrangente e não só com
    requisições manuais.
26. Como engenheiro de build, eu quero que os configs do Tracing Agent
    (`reflect-config.json`, `resource-config.json`, `jni-config.json`) sejam commitados
    em `META-INF/native-image`, para que o build native seja reproduzível sem
    re-instrumentar.
27. Como engenheiro de build, eu quero construir e validar um binary GraalVM sem PGO
    primeiro, para isolar problemas de compilação AOT de problemas do loop PGO.
28. Como engenheiro de build, eu quero que `Arena.ofShared()` e `MemorySegment` para
    mmap funcionem no binary nativo com `--enable-native-access=ALL-UNNAMED`, para
    manter a estratégia de mmap do `V2IndexSearcher` sem reescrita.
29. Como engenheiro de performance, eu quero gerar o `default.iprof` rodando o binary
    instrumentado com o K6 smoke real, para que o perfil capture os hot paths da carga
    real da Rinha.
30. Como engenheiro de build, eu quero commitar o `default.iprof` em
    `src/main/resources/pgo/`, para que qualquer rebuild produza o mesmo binary
    otimizado sem re-instrumentar localmente.
31. Como engenheiro de build, eu quero que o Dockerfile use build multi-estágio com
    `ghcr.io/graalvm/native-image-community:25` como builder e
    `gcr.io/distroless/base-debian12` como runtime, para entregar um container de ~12MB
    sem JRE.
32. Como mantenedor, eu quero que a imagem `container-registry.oracle.com/graalvm/jdk:25`
    esteja documentada como fallback no Dockerfile (comentário), caso a community image
    não esteja disponível.
33. Como operador de benchmark, eu quero ~65MB adicionais de Page Cache com o GraalVM
    (de ~195MB para ~260MB), para que o índice `.v2` de ~56MB fique inteiramente
    residente em RAM e elimine soft faults durante picos de carga.
34. Como avaliado na Rinha, eu quero zero variância de p99 no cold start após o GraalVM,
    para que o harness meça steady state desde o primeiro request sem warmup JIT.
35. Como avaliado na Rinha, eu quero que o `score_det` continue em 3.000 (0 FP/FN) no
    binary nativo, para confirmar que a compilação AOT não alterou a lógica de busca.
36. Como operador de benchmark, eu quero rodar 5 boots frios após o V5-3 e comparar
    p99 antes e depois, para confirmar que o resultado é análogo à Onda 13 do arthurd3
    (~32ms → ~14,59ms).

## Decisões de implementação

### V5-0 — SearchState (módulo: V2IndexSearcher + TopKSelector)

- `V2IndexSearcher` ganha uma inner class `SearchState` com os arrays pré-alocados:
  `int[] qi` (DIMS), `short[] q16` (DIMS), `long[] distAndIdx` (numClusters),
  `int[] ranked` (numClusters), `double[] topDist` (K\_NEIGHBORS), `byte[] topLabel`
  (K\_NEIGHBORS). O construtor de `SearchState` recebe `numClusters` como parâmetro —
  não existe constante `MAX_CLUSTERS` hardcoded.
- `ThreadLocal<SearchState>` inicializado no construtor de `V2IndexSearcher`, capturando
  `this.numClusters` via variável efetivamente final para a lambda.
- As assinaturas de `quantizeQuery`, `toI16Query` e `rankClusters` mudam para escrever
  in-place nos arrays passados como parâmetro. São métodos privados — a interface
  `VectorSearcher` não muda.
- `TopKSelector` ganha `reset()` que reinicializa `size=0` e `Arrays.fill(topDist,
  Double.MAX_VALUE)`. Os campos `topDist` e `topLabel` migram para `SearchState`; o
  `TopKSelector` passa a operar sobre arrays passados por referência ou, alternativamente,
  o estado inline no `search()` usa os arrays do `SearchState` diretamente sem delegar
  ao `TopKSelector` — a escolha de encapsulamento é do implementador, desde que o
  comportamento externo de `tryInsert` e `materialize` seja preservado.
- `search()` extrai `searchState.get()` no topo, chama `state.reset()` (ou equivalente)
  antes de usar os campos de TopK, e passa os arrays pré-alocados para os helpers.
- O `materialize()` continua alocando 6 objetos curtos (1 ArrayList + 5 SearchResult)
  — esses são Eden triviais e ficam fora do escopo desta mudança.

### V5-1 — HAProxy splice (módulo: configuração de infra)

- `nginx.conf` e o serviço nginx são removidos do `docker-compose.yml`.
- Novo serviço `lb` usa imagem HAProxy. `haproxy.cfg` adicionado ao repositório.
- `haproxy.cfg` configura `mode tcp`, `option splice-request`, `option splice-response`
  no frontend e no backend. `tune.bufsize 4096` (payload de ~300-500 bytes cabe).
- O serviço backend da API continua na porta 8080; o HAProxy ouve na 9999.

### V5-2 — cpuset + thread pool (módulos: docker-compose + JavalinBootstrap)

- `docker-compose.yml` adiciona `cpuset: "0"` para `api1`, `cpuset: "1"` para `api2`,
  `cpuset: "2"` para `lb`.
- `JavalinBootstrap` muda `QueuedThreadPool(16, 4)` para `QueuedThreadPool(8, 2)`.
  Razão: Lei de Little ao target de ~15ms com 500 req/s por instância indica ~7-8
  threads em voo; 8 reduz context-switch num core físico compartilhado com 0,45 vCPU.

### V5-3a — Remoção do Jackson (módulo: AppConfig)

- `AppConfig.loadMapFromJar` é substituído por `loadMap` sem `ObjectMapper`. O parser
  lê `InputStream` linha a linha, extrai chave (entre aspas) e valor float após `:`.
  Implementação de ~20 linhas, sem dependências externas.
- As dependências `jackson-core`, `jackson-databind` e `jackson-datatype-jsr310` são
  removidas do escopo runtime no `pom.xml` (ou movidas para `<scope>test</scope>` se
  ainda houver uso em testes). Objetivo: ausência de jackson-databind no classpath do
  binary nativo.

### V5-3b — GraalVM Native Image + PGO (módulos: pom.xml + Dockerfile + configs)

- `native-maven-plugin 0.10.3` adicionado como perfil `native` no `pom.xml`.
  `buildArgs` inclui `--enable-native-access=ALL-UNNAMED` e
  `-H:+ReportExceptionStackTraces`.
- Configs do Tracing Agent commitados em `src/main/resources/META-INF/native-image/`.
  Gerados com carga real do K6 smoke (não apenas `curl`).
- `default.iprof` commitado em `src/main/resources/pgo/`. Gerado com binary
  `--pgo-instrument` + K6 smoke. Precisa ser regerado apenas quando o hot path de
  busca mudar significativamente.
- Dockerfile reescrito com dois estágios: `native-build` (GraalVM community:25)
  e runner (`distroless/base-debian12`). O V2ArtifactBuilder ainda roda no estágio
  de build (sobre JVM, antes do native compile). O binary nativo é copiado para o
  runner; a JRE não está presente no container final.
- `JAVA_OPTS` da imagem JVM deixa de ser usada; startup passa a ser via `ENTRYPOINT`
  direto no binary.

## Decisões de teste

Um bom teste exercita **comportamento externo observável**: os vizinhos retornados pela
busca, a identidade dos mapas carregados pelo `AppConfig`, e o estado do `TopKSelector`
após reset — nunca a estrutura interna de arrays, a implementação do `ThreadLocal`, ou
a contagem de alocações. O estilo segue `V2QualityGuardTest`, `V2IvfSearchTest` e
`TopKSelectorTest`.

**Módulos com teste dedicado:**

1. **V2IndexSearcher (comportamento pós-SearchState):** os testes existentes
   `V2QualityGuardTest` e `V2IvfSearchTest` devem permanecer verdes sem modificação.
   Eles exercitam a interface pública `search()` e validam recall e acordo com
   float32 brute-force — se o `ThreadLocal<SearchState>` introduzir regressão de
   resultado, esses testes a detectarão. Nenhum teste novo de alocação é adicionado
   (comportamento de memória não é testável via JUnit de forma confiável).

2. **TopKSelector (reset):** adicionar ao `TopKSelectorTest` existente um caso que:
   (a) chama `tryInsert` com N candidatos, (b) chama `reset()`, (c) verifica que
   `worstDist()` retorna `Double.MAX_VALUE`, `size==0`, e que inserções posteriores
   produzem o mesmo resultado que um `TopKSelector` recém-criado. Comportamento externo
   testado: estado visível pós-reset é idêntico ao estado inicial.

3. **AppConfig.loadMap:** novo teste unitário que carrega `normalization.json` e
   `mcc_risk.json` dos recursos de teste e verifica que o `loadMap` simples produz
   o mesmo `Map<String, Float>` que o `ObjectMapper` produziria para os mesmos arquivos.
   Compara chave a chave e valor a valor (com tolerância `1e-6` para floats). Referência:
   não há análogo direto existente — é o primeiro teste de `AppConfig`.

4. **V2QualityGuardTest no binary nativo (smoke de regressão):** após o build GraalVM,
   executar `V2QualityGuardTest` contra o binary nativo para confirmar que a compilação
   AOT preserva a exatidão da busca (`score_det=3.000`, zero divergências contra
   float32 brute-force). Se o plugin surefire não suportar execução nativa, a validação
   é feita via smoke K6 com o score como proxy.

Guarda de regressão (existente, deve permanecer verde em todos os passos):
`V2QualityGuardTest`, `V2IvfSearchTest`, `TopKSelectorTest`,
`EuclideanDistanceCalculatorTest`.

## Fora do escopo

- **V6 (servidor NIO custom + fd-passing):** a substituição do Javalin por um servidor
  NIO single-threaded e do HAProxy por um LB fd-passing (Rust/C) é a V6. A V5 entrega
  o Javalin com GraalVM; o NIO vem depois, quando a latência de infra volta a pagar.
- **Ajuste fino do envelope K×nprobe:** com bbox pruning o nprobe não afeta correctness;
  experimentação de K diferente fica para a V6, onde o binary nativo já está no baseline.
- **Java Vector API / Panama SIMD:** confirmado empiricamente por arthurd3 como 3,8×
  mais lento que scalar e incompatível com GraalVM. Não entra em nenhuma versão.
- **Migração para KD-tree:** mudança de algoritmo de busca (IVF → KD-tree como o
  arthurd3) é planejada para a V7 (Sapientia), não para a V5.
- **`nearestFraudCount()` na interface VectorSearcher:** eliminar os 6 objetos do
  `materialize()` exigiria mudar a interface e tem vida limitada (V7 troca tudo).
  Fora do escopo desta versão.

## Notas adicionais

- **Analogia arthurd3:** a V5 reproduz o salto Onda 5→13 do arthurd3 (mesma paridade
  de score, mesmo caminho técnico). A Onda 30 (fd-passing, −80% p99) é a V6.
  A meta de 5ms p99 é da V6, não da V5.
- **Ordem importa:** V5-0 deve ser validado antes da V5-3. O `ThreadLocal<SearchState>`
  reduz GC antes da compilação AOT, e o perfil PGO captura hot paths já sem alocações —
  um perfil gerado antes do V5-0 pode sub-otimizar o binary nativo.
- **default.iprof deve ser regerado** após V5-0 e antes do build final de V5-3.
  O perfil captura o comportamento de execução; se o hot path muda (remove alocações),
  o perfil antigo pode ter padrões obsoletos.
- **Jetty reflection:** o Tracing Agent deve ser rodado com o mesmo `QueuedThreadPool(8, 2)`
  que entrará na V5-2, para capturar a reflection correta do Jetty com o pool reduzido.
- **Documentação:** ao fechar cada sub-item, atualizar a seção de resultados do
  `docs/knowledge/v5/01-opus.md` conforme o padrão das versões anteriores.
