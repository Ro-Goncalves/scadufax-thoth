# Issue 06 — V4-C: Parser JSON custom (zero-alocação na entrada)

Status: closed

## Issue pai

[PRD: Tesseract V4 — Veritas](../PRD.md)

## ⚠️ Condicional ao resultado da Issue 01

**Esta issue só entra em execução se o V4-0 (Issue 01) confirmar a Hipótese A:**
p50/p95 estáveis entre runs e apenas o p99 saltando. Isso indica cauda de GC causada
pelo Jackson alocando no parse de entrada.

Se o V4-0 confirmar a Hipótese B (p50/p95 também sobem), esta issue não é executada
— o problema é ruído de host, não GC, e o parser custom não ajudaria.

## O que construir

Substituir o Jackson no parse do request de entrada por um parser cursor-based
zero-alocação, para eliminar a última fonte de alocação por requisição no hot path.

Hoje o `SearchHandler` usa `ctx.bodyAsClass(TransactionRequest.class)`, que aciona
o Jackson a cada requisição. Com ~900 req/s, isso gera alocação constante e pressiona
o GC — hipótese para o spread de p99 residual da V3.

Para schema fixo com campos conhecidos (`account_id`, `amount`, `last_transaction`,
`transaction_date`, `vector`), um parser cursor-based é 10–20× mais rápido e opera
sem `new` no hot path: o caller passa o `float[]` de vetor pré-alocado; o parser
escreve in-place.

### Técnica central

- **Timestamp ISO-8601 → epoch seconds** sem `Instant` nem `ZonedDateTime`: usar o
  algoritmo de Howard Hinnant (`daysFromCivil`) para converter data para dias e
  extrair hora e dia da semana diretamente da string, sem criar objetos de data/hora.
- **Vetor:** iterar os valores float do campo `vector` escrevendo diretamente no
  `float[]` pré-alocado, posição a posição.
- **Sentinela `last_transaction`:** se o campo for nulo ou ausente, gravar `-1.0f` no
  `float[]` nas dimensões correspondentes — idêntico ao comportamento atual do
  `TransactionVectorizer`.

**Referências de implementação:**

- `JsonReader.java` do AndDev741 (~300 linhas) — Java, cursor-based, mesmo domínio.
- `FraudRequestParser.java` do arthurd3 — single-pass com índice de chaves.

### Bônus arquitetural

A remoção do Jackson no parse elimina reflexão de desserialização, simplificando
significativamente a configuração de GraalVM Native Image necessária para a V5.

## Critérios de aceite

- [x] O hot path de parse não chama Jackson nem aloca `String` de payload por
      requisição. (`ctx.bodyAsClass` removido; `JavalinJackson` não registrado como mapper HTTP)
- [x] **Teste de contrato:** `FraudRequestParserContractTest` — 50 payloads, incluindo
      10 com `last_transaction: null`; 3/3 testes verdes com delta 1e-4f.
- [ ] Zero erros HTTP no K6 após a substituição. (pendente — Issue 07)
- [x] `V2QualityGuardTest`, `V2IvfSearchTest` e `V2EndToEndTest` permanecem verdes.
      (56/56 testes verdes)
- [ ] O spread de p99 reduz ou o p99 mediano melhora. (pendente — Issue 07)
- [x] Resultado registrado em `docs/knowledge/v4/06-parser-json-custom.md`.

## Bloqueada por

Issue 01 (V4-0) — a decisão de entrar ou não depende do diagnóstico de percentis.
Issue 05 (V4-A Passo 3) — executa após o gate de score do bbox pruning.