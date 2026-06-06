# Issue 08: Cauda de p99 — conexões configuráveis + busy-poll/CFS + sysctls

Status: ✅ done (2026-06-06) — root cause foi spin puro no busy-poll do `NioHttpServer`
sob cota de CFS (não conexões/sysctls). Fix de 1 linha (backoff CFS-aware: `parkNanos`
sempre que `!didWork`, não só quando `conns.isEmpty()`). p99 57ms → 1.344ms, score
4243 → 5872. Itens secundários (VUs configuráveis, sysctls, Selector) ficaram opcionais.
Ver `docs/knowledge/v5/benchmark-opus.md` (V5-5).

## Issue pai

[PRD: V5 — Opus](../PRD.md). Desbloqueia o score travado pelo p99 após as Issues 06/07.

## Contexto: o p99 é o teto, e é fixo

Pós-MappedByteBuffer (Issue 07): 0% falha, 0 FP/FN, score ~4200. O **p50 é ótimo (2-15ms)**,
mas o **p99 ~62ms** segura o `score_p99` (logarítmico: p99 62ms → ~score 4200; p99 15ms →
~4800; p99 2.5ms → ~5600).

Benchmark mostra o p99 **idêntico em todas as 6 configs de K/nprobe** (todas ~62-65ms,
score ~4185-4207):

| config | p50 | p99 | score |
|---|---|---|---|
| K2048/np2 | 6.55ms | 62.11ms | 4206 |
| K2048/np6 | 2.31ms | 62.68ms | 4202 |
| K4096/np2 | 4.08ms | 62.08ms | 4207 |
| K4096/np6 | 15.6ms | 65.36ms | 4184 |

p99 **independente de K/nprobe** ⇒ **não é custo de busca**. É a **cauda do reactor busy-poll
sob throttle de CFS**: a 0,45 CPU (período CFS 100ms, cota ~45ms), o busy-poll varre TODAS as
conexões a cada iteração e queima a cota girando; quando o kernel estrangula (~55ms), os
requests que chegam nessa janela esperam → cauda de ~62ms.

## Informação do operador (decisiva)

Os testes **nunca passam de ~30-50 VUs** (o `test.js` hoje usa `preAllocatedVUs: 100`,
`maxVUs: 250` — superdimensionado). **Menos conexões = menos varredura por iteração = menos
cota de CPU queimada = p99 menor.** Tornar o número de conexões configurável é, ao mesmo
tempo, mais realista E um lever direto de p99.

## O que construir (em ordem; cada item é testável isolado)

### 8.1 — Conexões/VUs configuráveis no benchmark ⭐ PRIMEIRO (barato)

- `test/test.js`: `preAllocatedVUs`/`maxVUs` e o `target` de rate passam a ler variáveis de
  ambiente (ex.: `VUS`, `MAX_VUS`, `RATE`), com **default 30** (nunca passou disso; 50 =
  margem de segurança).
- `run-benchmark.sh` e/ou `docker-compose` de teste propagam essas variáveis, no mesmo
  padrão de `K_VALUES`/`NPROBE_VALUES`/`DTYPE_VALUES`, para o operador variar como os demais.
- **Medir**: rodar com 30 VUs vs 250 e comparar p99. Hipótese: p99 cai junto com o nº de
  conexões (menos trabalho de varredura por iteração do busy-poll).

**Aceite:** número de conexões controlável por env; benchmark com 30 VUs registrado;
comparação 30 vs 250 documentada.

### 8.2 — Reavaliar `Selector` no nativo (maior lever de p99)

O `Selector` foi descartado na Issue 06 com base num teste **confundido pelo hang do warmup**
(já removido) — pode funcionar agora. Um selector bloqueante **dorme quando ocioso**, devolve
a cota de CPU ao CFS (não estrangula) e acorda na chegada de I/O → mata a cauda. arthurd3 usa
selector single-thread e tem p99 ~2.5ms.

- Protótipo: `NioHttpServer` com `Selector` (single-thread, `select()` bloqueante;
  opcional `selectNow()` antes, padrão arthurd3) e medir no nativo.
- Se funcionar: substitui o busy-poll. Se **não** funcionar (gira/não serve): manter
  busy-poll e mitigar com backoff CFS-friendly (cuidado: `parkNanos` teve resolução grossa
  no nativo — testar `onSpinWait`/yield ou park só quando 0 trabalho por N iterações).

**Aceite:** p99 mediano bem abaixo de 62ms (alvo ~15-20ms) sem regressão de detecção;
decisão Selector vs busy-poll documentada com números.

### 8.3 — sysctls (infra de cauda do arthurd3)

- `docker-compose.yml`: `sysctls` por serviço — `net.core.somaxconn=1024`,
  `net.ipv4.tcp_fastopen=3`. Parte do salto cpuset+sysctls do arthurd3 (p99 27→14.59ms).
- Revisar HAProxy (`tune.bufsize`, `maxconn`, timeouts) coerente com o nº de conexões reduzido.

**Aceite:** p99 melhora vs 8.2 sem regredir detecção.

## Critérios de aceite (issue)

- [ ] Conexões/VUs do benchmark configuráveis por env (default 30)
- [ ] Comparação 30 vs 250 VUs documentada (efeito no p99)
- [ ] Selector reavaliado no nativo; decisão (adotar/descartar) com números
- [ ] p99 mediano reduzido de ~62ms para o alvo (~15-20ms ou melhor)
- [ ] sysctls aplicados; HAProxy revisado
- [ ] Detecção intacta (0 FP/FN) em todos os passos
- [ ] Memória da stack reavaliada (com menos conexões, o lb pode voltar de 60MB)

## Bloqueada por

- [Issue 06: Servidor NIO](06-nio-server.md) e [Issue 07: MappedByteBuffer](07-mappedbytebuffer-search.md) — concluídas.

## Notas

- Com menos conexões (8.1), o HAProxy aloca menos pipes de `splice` → o rebalanceamento de
  memória 145/145/60 (Issue 02/04) pode voltar para algo perto de 165/165/20.
- Só depois de domar o p99 vale revisitar o tuning de K/nprobe (hoje todas as configs
  empatam ~4200 porque o p99 domina). Candidatos atuais: `K4096/np2` / `K2048/np2`.
