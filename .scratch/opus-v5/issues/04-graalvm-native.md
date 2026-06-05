# Issue 04: GraalVM Native Image sem PGO

Status: ready-for-agent

## Issue pai

[PRD: Tesseract V5 — Opus](../PRD.md) — histórias de usuário 24–32.

## O que construir

Compilar a aplicação como binary nativo com GraalVM, validar que sobe e serve
requisições com accuracy preservada, e preparar o Dockerfile de produção multi-estágio.
O PGO (perfil de execução) fica para a Issue 05.

**native-maven-plugin:**
Adicionar o `native-maven-plugin 0.10.3` ao `pom.xml` como perfil `native`. O build
JVM padrão (`mvn package`) não muda. O perfil `native` (`mvn -Pnative package`) compila
o binary nativo usando `--enable-native-access=ALL-UNNAMED`. O `main class` e os
`buildArgs` ficam no plugin; nenhuma flag vai para a linha de comando.

**Tracing Agent:**
Rodar o Tracing Agent do GraalVM contra o JAR existente com o K6 smoke real como
carga. Os arquivos gerados em `src/main/resources/META-INF/native-image/`
(`reflect-config.json`, `resource-config.json`, `jni-config.json`,
`serialization-config.json`) são commitados. Eles capturam a reflection do
Javalin/Jetty observada em runtime — gerados uma vez e mantidos até que uma
dependência mude.

O Tracing Agent deve ser rodado com `QueuedThreadPool(8, 2)` já em vigor (Issue 02
aplicada), para capturar a reflection correta do Jetty com o pool reduzido.

**Validação do binary sem PGO:**
O binary é testado com `mvn -Pnative verify` (smoke + `V2QualityGuardTest`). Se o
plugin surefire não suportar execução nativa, a validação é via smoke K6 com o score
como proxy.

**Dockerfile multi-estágio:**
O Dockerfile existente é reescrito com dois estágios:

- `native-build`: imagem `ghcr.io/graalvm/native-image-community:25`. Compila o JAR,
  roda o `V2ArtifactBuilder` (JVM ainda disponível neste estágio) e depois compila o
  binary nativo. A imagem `container-registry.oracle.com/graalvm/jdk:25` é documentada
  como comentário de fallback.
- `runner`: imagem `gcr.io/distroless/base-debian12`. Recebe o binary e o artefato
  `.v2`. Sem JRE. `ENTRYPOINT` direto no binary; `JAVA_OPTS` não existe neste estágio.

`docker compose up` passa a usar esta nova imagem; o smoke K6 valida que a stack sobe
e responde. A Issue 05 (PGO) atualiza o Dockerfile para referenciar o `default.iprof`.

## Critérios de aceite

- [ ] `mvn -Pnative package` conclui e gera o binary em `target/`
- [ ] `mvn -Pnative verify` verde, ou smoke K6 verde como fallback
- [ ] `V2QualityGuardTest` verde no binary nativo (score\_det=3.000, 0 FP/FN) — via
  mvn verify nativo ou via carga K6 com score como proxy
- [ ] `docker build` com o novo Dockerfile multi-estágio conclui sem erros
- [ ] Container nativo sobe e `/ready` retorna HTTP 200
- [ ] Smoke K6 (`--profile smoke`) verde contra o container nativo
- [ ] Configs do Tracing Agent commitados em `META-INF/native-image/`
- [ ] Binary nativo < 30MB (expectativa: ~12MB)
- [ ] Container final baseado em `distroless` — sem JRE presente

## Bloqueada por

- [Issue 03: Remoção do Jackson em AppConfig](03-jackson-removal.md) — jackson-databind
  ausente do classpath simplifica o Tracing Agent e o binary nativo.
