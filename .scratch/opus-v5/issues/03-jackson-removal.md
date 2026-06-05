# Issue 03: Remoção do Jackson em AppConfig

Status: ready-for-agent

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

- [ ] `mvn -q compile` passa sem erros
- [ ] `mvn test` verde, incluindo o novo `AppConfigTest`
- [ ] `AppConfigTest` verifica `normalization.json` e `mcc_risk.json` chave a chave
  com tolerância 1e-6
- [ ] `mvn dependency:tree` não lista `jackson-databind` fora de escopo `test`
- [ ] `docker compose up` sobe e smoke K6 conclui verde (a remoção do Jackson não
  altera o comportamento em runtime)
- [ ] Nenhuma importação de `com.fasterxml.jackson` fora do escopo de test no
  código principal

## Bloqueada por

Nenhum — pode começar imediatamente.
