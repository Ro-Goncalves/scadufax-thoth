# Issue 06: Servidor NIO próprio (substitui Javalin/Jetty)

Status: done

## Issue pai

[PRD: V5 — Opus](../PRD.md). Antecipação do servidor NIO da V6, forçada por bloqueador
descoberto na Issue 04.

## Contexto

A imagem nativa da Issue 04 falhava ~92% sob carga. A causa: **o selector NIO do Jetty
entra em busy-spin no GraalVM Native Image** — consome ~100% de CPU numa thread só, o
pool de threads não sobe, e o processamento serializa (3 threads totais, `main` em R a
100% mesmo ocioso). O mesmo Javalin/Jetty roda perfeito no JVM, então é a interação
Jetty-NIO + GraalVM. O roadmap já previa substituir o Jetty por um servidor NIO próprio
na V6 (pré-requisito do fd-passing); foi antecipado.

## O que foi construído

**`web/NioHttpServer`** — reactor HTTP/1.1 single-thread em **busy-poll sobre canais NIO
não-bloqueantes, sem `Selector`** (o `Selector`/epoll também gira sem servir no nativo):

- Loop único: `accept()` não-bloqueante → varre conexões → `read`/parse/`search`/`write`
  inline na própria thread. Padrão dos top performers (lucasmontano busy-poll, arthurd3).
- Single-thread é ótimo num container pinado a 1 core (0,45 vCPU) — sem context-switch.
- Parser HTTP mínimo (request line + Content-Length), keep-alive e pipelining básico.
- Respostas HTTP pré-montadas (zero serialização no hot path); vetor de query e buffer de
  corpo reaproveitados (zero alocação por request).
- Backoff (`parkNanos`) **só quando não há conexões** — evita queimar CPU antes do tráfego.

**Mudanças de suporte:**
- `HttpServerBootstrap` substitui `JavalinBootstrap`. Faz `prewarm` e roda o reactor na
  thread chamadora (que vira a thread do servidor). **Warmup removido** — era para forçar
  JIT (C2), inócuo em AOT, e travava o boot nativo.
- `ScadufaxThothApplication` usa o novo bootstrap.
- `JavalinBootstrap`, `SearchHandler`, `ReadyHandler` (acoplados ao `io.javalin.Context`)
  **removidos**; lógica inline no servidor.
- `pom.xml`: **Javalin → escopo `test`** (sai do fat JAR/nativo; testes ainda compilam).
- `V2EndToEndTest` reescrito para exercitar o `NioHttpServer` por HTTP real; `V2IvfSearchTest`
  desacoplado do Javalin.

## Critérios de aceite

- [x] 37 testes verdes, incluindo `V2EndToEndTest` contra o `NioHttpServer` por HTTP real
- [x] Javalin/Jetty fora do runtime (escopo `test`); ausente do binário nativo
- [x] Container nativo sobe, `/ready` 200, `/fraud-score` responde correto
- [x] CPU ociosa baixa (≠ 100% do busy-spin do Jetty)
- [x] Detecção preservada (0 FP/FN)

## Notas / dívidas conhecidas

- O busy-poll varre **todas** as conexões a cada iteração. Sob muitas conexões keep-alive
  (250 VUs) isso queima cota de CPU e, sob throttle de CFS, infla o p99. **Tratado na
  Issue 08** (conexões configuráveis + reavaliar Selector/backoff CFS-friendly).
- O `Selector` foi descartado com base num teste que **depois descobrimos estar confundido
  pelo hang do warmup** (search a 10ms × 12000 = ~80min, nunca chegava ao loop). Com warmup
  removido e busca rápida (Issue 07), **vale reavaliar o Selector** (Issue 08) — se funcionar
  no nativo, dorme quando ocioso e mata a cauda de p99.

## Bloqueada por

- [Issue 04: GraalVM Native Image](04-graalvm-native.md) — o bloqueador surgiu ali.
