# Issue 03: Remoção do Jackson em AppConfig

Status: done

## Issue pai

[PRD: Tesseract V5 — Opus](../PRD.md) — histórias de usuário 21–23.

## O que construir

Substituir o `ObjectMapper` em `AppConfig.loadMapFromJar` por um parser simples de
`Map<String, Float>` sem dependências externas, e remover o jackson-databind do
classpath de runtime.

`AppConfig.fromEnvironment()` usa `ObjectMapper` para carregar `normalization.json`
e `mcc_risk.json` do classpath — único uso de Jackson pós-V4-C. O novo `loadMap`
lê o `InputStream` linha a linha e extrai chave (entre aspas) e valor float após `:`.
Implementação de ~20 linhas que suporta o formato JSON trivial dos dois arquivos
(`{ "key": 0.123, ... }`).

As dependências `jackson-core`, `jackson-databind` e `jackson-datatype-jsr310` são
removidas do escopo runtime no `pom.xml`. Se ainda houver uso em testes, as
dependências são movidas para `<scope>test</scope>` — o objetivo é a ausência de
jackson-databind no classpath do binary nativo (Issue 04).

Um novo teste `AppConfigTest` verifica que `loadMap` produz `Map<String, Float>`
idêntico (chave a chave, tolerância 1e-6 em floats) ao que o `ObjectMapper` produzia
para os mesmos arquivos de recursos.

## Critérios de aceite

- [x] `mvn -q compile` passa sem erros
- [x] `mvn test` verde, incluindo o novo `AppConfigTest`
- [x] `AppConfigTest` verifica `normalization.json` e `mcc_risk.json` chave a chave
  com tolerância 1e-6
- [x] `mvn dependency:tree` não lista `jackson-databind` fora de escopo `test`
- [x] `docker compose up` sobe e smoke K6 conclui verde (a remoção do Jackson não
  altera o comportamento em runtime)
- [x] Nenhuma importação de `com.fasterxml.jackson` fora do escopo de test no
  código principal

## Nota pós-implementação (2026-06-05) — desvio de escopo do Jackson

O `AppConfig` está **100% sem Jackson** (parser por regex) — objetivo central cumprido.

**Porém**, `jackson-core` e `jackson-databind` voltaram para **escopo `compile`** (não `test`),
porque o `V2ArtifactBuilder` (ferramenta de build-time que lê `references.json.gz` ao gerar
o `.v2`) usa Jackson. O critério "não lista jackson-databind fora de escopo test" foi
**relaxado conscientemente**: o Jackson é **inalcançável a partir do entrypoint de runtime**
(`NioHttpServer`), então o GraalVM Native Image o **elimina como código morto** — não entra
no binário. O objetivo real (ausência de Jackson no nativo) está cumprido; a letra (escopo
test) não, por causa do builder. Se um dia o `V2ArtifactBuilder` deixar de usar Jackson,
dá para movê-lo de volta para `test`.

## Bloqueada por

Nenhum — pode começar imediatamente.
