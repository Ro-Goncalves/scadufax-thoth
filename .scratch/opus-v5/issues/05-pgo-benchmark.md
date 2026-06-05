# Issue 05: PGO loop + benchmark final

Status: ready-for-agent

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
