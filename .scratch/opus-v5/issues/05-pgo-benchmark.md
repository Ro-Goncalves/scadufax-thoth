# Issue 05: PGO loop + benchmark final

Status: ready-for-agent (fazer **depois** da Issue 08 — ver nota de prioridade)

## Nota de prioridade e realidade (2026-06-05)

Reposicionada para **depois da Issue 08**. Motivo: o gargalo atual é o **p99 ~62ms**, que é
**independente de K/nprobe** (cauda do busy-poll sob CFS), logo **não é custo por request** —
e o PGO ataca custo por request (p50), não a cauda fixa. Fazer PGO antes de domar o p99
(Issue 08) sub-otimizaria a medição.

Ajustes desta issue à realidade já implementada:
- O hot path já está em **MappedByteBuffer** (Issue 07) e sem warmup (Issue 06) — o
  `default.iprof` deve ser gerado sobre esse hot path final.
- O servidor é o **NioHttpServer** (não Jetty); o Tracing Agent/PGO devem rodar contra ele.
- O Dockerfile da Issue 04 já compila via `native-maven-plugin`; o `--pgo` entra nos
  `buildArgs` do perfil `native` (pom), não numa flag solta.
- **Critérios de aceite (p99<20ms, score>4800) só são alcançáveis após a Issue 08.** Se a
  Issue 08 já levar o p99 ao alvo, o PGO vira polimento incremental — reavaliar as metas
  com os números da 08 em mãos.

## Issue pai

[PRD: Tesseract V5 — Opus](../PRD.md) — histórias de usuário 29–36.

## O que construir

Gerar um perfil de execução real (PGO) com o K6 smoke, committá-lo no repositório,
recompilar o binary com o perfil e executar o benchmark de fechamento da V5.

**Por que o PGO deve vir após as Issues 01 e 02:**
O `default.iprof` captura os hot paths em execução. Se for gerado antes do
`ThreadLocal<SearchState>` (Issue 01), o perfil registra o comportamento com
alocações — quando o código mudar, o perfil fica obsoleto. O perfil correto é
gerado sobre a versão final do hot path, com SearchState e pool de threads já
reduzido.

**Loop PGO:**

1. Compilar o binary instrumentado: `mvn -Pnative -DnativeImageArgs="--pgo-instrument" package`.
2. Rodar o binary instrumentado localmente e executar o K6 smoke como carga real.
3. O `default.iprof` gerado no diretório corrente é copiado para
   `src/main/resources/pgo/` e commitado.
4. O Dockerfile (da Issue 04) é atualizado para referenciar o perfil:
   `--pgo=src/main/resources/pgo/default.iprof` nos `buildArgs` do estágio nativo.
5. Rebuild: `docker build` compila o binary com PGO. O container resultante é o
   artefato de produção da V5.

**Benchmark de fechamento:**
Rodar o K6 de carga completa (`--profile test`) 5 vezes do boot frio. Registrar
p50, p95 e p99 de cada run em `docs/knowledge/v5/benchmark-opus.md`.

## Critérios de aceite

- [ ] `default.iprof` presente e commitado em `src/main/resources/pgo/`
- [ ] `docker build` com PGO conclui sem erros
- [ ] Smoke K6 verde contra o container com PGO
- [ ] `V2QualityGuardTest` verde — score\_det=3.000, 0 FP/FN (via mvn nativo ou proxy K6)
- [ ] p99 mediano < 20ms em 5 boots consecutivos
- [ ] `final_score` > 4.800 em pelo menos 3 das 5 rodadas
- [ ] Resultados registrados em `docs/knowledge/v5/benchmark-opus.md` (p50/p95/p99
  por run, score por run, comparativo com V4 baseline ~40ms/~4.394)
- [ ] Gate de progressão para V6 avaliado e documentado:
  se p99 mediano < 20ms e score\_det=3.000 → V6 desbloqueada

## Bloqueada por

- [Issue 01: ThreadLocal\<SearchState\>](01-search-state.md) — perfil PGO deve ser
  gerado sobre o hot path sem alocações; perfil pré-01 capturaria comportamento obsoleto.
- [Issue 02: HAProxy + cpuset](02-haproxy-cpuset.md) — o Tracing Agent e o perfil
  PGO devem ser gerados com a infra final (HAProxy, cpuset, pool=8).
- [Issue 04: GraalVM Native Image](04-graalvm-native.md) — o loop PGO parte do binary
  nativo já funcional e validado.
