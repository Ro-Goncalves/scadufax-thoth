# Issue 02: HAProxy splice + cpuset + pool de threads

Status: done

## Issue pai

[PRD: V5 — Opus](../PRD.md) — histórias de usuário 11–20.

## O que construir

Substituir o nginx por HAProxy em modo TCP com `splice(2)`, fixar cada container a
um core físico via `cpuset` e reduzir o pool de threads do Jetty. As três mudanças
são feitas em um único commit e medidas juntas (analogia direta à Onda 13 do arthurd3).

**HAProxy:**
- O serviço `nginx` é removido do `docker-compose.yml`; um novo serviço `lb` usa
  a imagem `haproxy:lts-alpine` (ou equivalente mínima).
- Um arquivo `haproxy.cfg` é adicionado ao repositório e montado no container.
- Configuração: `mode tcp`, `option splice-request`, `option splice-response` no
  frontend e no backend. `tune.bufsize 4096`. O HAProxy ouve na porta 9999 e
  distribui para `api1:8080` e `api2:8080`.
- O arquivo `nginx.conf` e qualquer referência a ele no docker-compose são removidos.

**cpuset:**
- `docker-compose.yml` ganha `cpuset: "0"` para `api1`, `cpuset: "1"` para `api2`,
  `cpuset: "2"` para `lb`.
- Os limites de CPU e memória por serviço já presentes são mantidos.

**Pool de threads:**
- Em `JavalinBootstrap`, `QueuedThreadPool(16, 4)` muda para `QueuedThreadPool(8, 2)`.
  Razão: Lei de Little ao target de ~15ms com 500 req/s por instância indica ~7–8
  threads em voo; 8 reduz context-switch num container fixado a 1 core físico (0,45 vCPU).

Nenhuma mudança em código Java além do pool de threads.

## Critérios de aceite

- [x] `mvn -q compile` passa sem erros
- [x] `docker compose up` sobe a stack (HAProxy + api1 + api2) sem erros
- [x] Smoke K6 (`--profile smoke`) conclui verde: HTTP 200 em `/fraud-score` e `/ready`
- [x] Carga K6 (`--profile test`) conclui e gera `test/results.json` com
  `score_det` ≥ 3.000 (sem regressão de detecção)
- [x] `haproxy.cfg` versionado no repositório
- [x] `nginx.conf` removido do repositório
- [x] p99 medido após esta issue documentado em `docs/knowledge/v5/benchmark-opus.md`
  (registro do ganho de infra isolado do GraalVM)

## Bloqueada por

Nenhum — pode começar imediatamente.