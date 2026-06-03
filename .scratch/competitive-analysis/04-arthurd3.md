# Análise: arthurd3/fraud-detection

- Repositório: https://github.com/arthurd3/fraud-detection
- Linguagem: **Java 21 + Rust (híbrido)**
- Score: **5.730,57/6000**
- p99: **1.86ms**
- Accuracy: E=0 (zero mismatches em 54.100 queries)
- Arquitetura: NIO reactor Java + busca Rust AVX2 + LB Rust fd-passing

## Por que é o melhor em Java

O maior em Java puro (antes de Rust) tinha score ~4.393 (Onda 5).
A evolução aconteceu em ondas documentadas. A migração do search de Java
para Rust (Onda 33) deu -27% p99 (2.55ms → 1.86ms). Antes disso, a
troca de HAProxy por Rust fd-passing (Onda 30) deu -80% de p99.

## HTTP Server

Custom NIO reactor single-threaded:
- `java.nio.channels.Selector` com single reactor thread
- Dual-mode: TCP normal (`start()`) ou fd-passing via Rust LB (`startLapadaMode()`)
- **Onda 32 optimization:** `selectNow()` antes de `select()` — evita syscall quando dados já buffered
- `ConnectionState` com `ByteBuffer` direto de 4096 bytes (off-heap), reutilizado
- TCP_NODELAY ativo
- Respostas escritas inline no path de leitura (elimina um `epoll_wait` por request — Onda 31)

## JSON Parsing

Zero-copy, zero-allocation:
- `FraudRequestParser.java` — opera em posições/índices do ByteBuffer
- Single-pass com índice de chaves: localiza todas as chaves em uma varredura
  - Top-level: 5 chaves; nested objects: 2–3 cada → total ~6 varreduras vs ~17 no naive
- Sem String creation, HashMap, nem regex
- Extrai vetor 14-dim diretamente de offsets de bytes
- `isoEpochSec()`: ISO 8601 → epoch seconds sem alocar `Instant`/`ZonedDateTime`

## Algoritmo de Busca — KD-TREE (BBF + Beam-of-2)

**NÃO usa IVF** — usa KD-tree com Best-First Branch-and-Bound.

Estrutura (formato RKD6):
- 19 shorts por nó (38 bytes) — 19 vs 20 economiza ~6MB para 3M nós
- Left-child index (28 bits) + split dimension (4 bits) packed em i32
- Bounding boxes nos 18 níveis superiores (32 shorts: min/max para 8+6 dims)
- Stride 20 shorts por nó no heap; MappedByteBuffer no modo mmap

**Fase 1 — Prime (Beam-of-2 Greedy Descent):**
- Duas descidas root-to-leaf (~44 visitas total) em vez de fan-out
- Primeira descida rastreia far-children promissores
- Segunda descida explora o melhor far-subtree

**Fase 2 — Best-First Branch-and-Bound (BBF Heap):**
- Min-heap processa candidatos por distância de slab crescente
- Capacidade: 1024 entradas (suporta árvore de profundidade 22)
- Poda global com lower-bounds de bounding boxes

**Fase 3 — Double Rerank:**
- Converte candidatos i16 para double-precision
- Deduplica por `origId`
- Strict less-than para tie-breaking → bit-idêntico ao reference C

## Quantização

**i16** escalado por 10.000:
- Java: `Math.round(val * 10000)` como short
- Rust: implementação Java-compatible rounding (`round.rs`) — bit-exato
- `queryRound4` pré-computado fora do inner loop (elimina 14 `Math.round()` por candidato — Onda 2026-05-21)

## Java Vector API SIMD — REMOVIDA

**Removida por ser 3.8× mais lenta que scalar!**
- Testada nas Ondas 2–4
- Também quebra o build do GraalVM Native Image
- Lição: não implementar Panama Vector API no nosso roadmap

## Memória

- KD-tree: `MappedByteBuffer` (~163 MB no arquivo `references.kdt`)
- `MappedByteBuffer.load()` → `madvise(MADV_WILLNEED)` no startup (pin memória)
- Page pre-warming: lê 1 byte por página 4KB para causar page faults antecipados
- Per-connection: `DirectByteBuffer` de 4096 bytes input + 512 bytes output, reutilizados
- `KdScratch` thread-local: workspace pré-alocado com versioned-visited (sem memset por query)
  - Shrinkado de 256KB → 4KB (target L1D cache)

## Concorrência

- Single-threaded NIO reactor explicitamente escolhido sobre virtual threads
- Razão: workload CPU-bound; múltiplas threads numa fração de core criam context-switch waste
- Escalabilidade via multiplexação de processos (2 APIs + 1 Rust LB)

## Load Balancer — RUST FD-PASSING (a maior inovação de infra)

Antes da Onda 30: HAProxy (TCP mode, splice-auto).
Após Onda 30: Rust subprocess com SCM_RIGHTS file descriptor passing.

**Impacto:** p99 de 2.65ms → 1.86ms (Ondas 30–33 combinadas).

Mecanismo:
1. Rust LB aceita TCP connections na porta 9999
2. Para cada client, passa o file descriptor via Unix socket (`sendmsg` + SCM_RIGHTS)
3. Java wraps o fd em `SocketChannel` via reflection (`FdWrap.wrapFd(fd)`)
4. Thread separada recebe fds (`FdReceiver`), nunca bloqueia o loop principal
5. Registro no Selector do NioServer via `injectChannel()`

Recursos: 0.05 CPU, 16MB RAM para o LB Rust.

**O TCP load balancer sai completamente do data path.** Nenhum byte de payload
passa pelo LB após o handshake — só o file descriptor é transferido.

## GraalVM Native Image + PGO

- Build: `native-maven-plugin 0.10.3` com `-Pnative`
- Inclui biblioteca Rust FFI: `-H:CLibraryPath=/src/api/clib`
- **PGO (Profile-Guided Optimization):** usa `default.iprof` no build
  - Roda a app, coleta perfil de execução real
  - Recompila usando esse perfil para otimizar hot paths
- Binário final: **12 MB** (vs JAR + JRE ~200MB)
- Distroless base image (`gcr.io/distroless/base-debian12`)

## Rust Search Engine (Onda 33)

Migração do KD-tree search de Java para Rust via FFI.
- `-27% p99` (2.55ms → 1.86ms)
- Motivo: Java scalar → Rust AVX2 + LLVM `-O3` reduz CPU time por request,
  aliviando throttle CFS e reduzindo latência de cauda

AVX2 (`dist.rs`):
- `_mm256_madd_epi16`: multiplicação e acumulação paralela de i16
- Lanes 0-13: distância; Lanes 14-15: zerados
- Compile-time dispatch: `-C target-cpu=x86-64-v3`

## Jornada de Performance (documentada em PERFORMANCE_LEDGER.md)

| Onda | p99 | Score | Inovação |
|---|---|---|---|
| 5 (anchor) | ~32ms | 4.393 | GraalVM AOT+PGO |
| 13 | 14.59ms | 4.836 | cpuset + HAProxy splice |
| 18 | 35.01ms | 4.455 | removeu visit cap (regrediu) |
| 30 (lapada) | 2.65ms | 5.577 | HAProxy → Rust LB fd-passing |
| 31 | 2.55ms | 5.593 | inline response write |
| 32 | 2.55ms | 5.594 | mlock de memória |
| **33** | **1.86ms** | **5.731** | Java KD → Rust AVX2 |

## Hipóteses Falsificadas (importantes para nós)

| Hipótese | Resultado | Lição |
|---|---|---|
| Visit cap | Falsamente positivo no calib rig | Calib não reflete harness real |
| Java Vector API SIMD | 3.8× mais lento que scalar | NÃO implementar Panama SIMD |
| PGO regen sem mudança de código | ±775 pts de spread | PGO precisa de perfil real |
| Branch-free rerank | +6.4% regressão | Branch predictor funciona bem para K=5 |
| FFM (Foreign Function & Memory) | Quebra GraalVM linking | Usar FFI via headers C diretamente |

## Dimensionamento de container

- lapada (Rust LB): 0.05 CPU, 16MB
- api-1 / api-2: 0.475 CPU cada, 167MB cada
- Sockets compartilhados via volume `/sockets`

## O que é aproveitável para nós

### Alto impacto, factível em Java:
1. **fd-passing LB** (Onda 30) — maior ganho de infra, -80% p99
   - Pode ser feito com Rust minimal (~50 linhas) ou adaptando o artigo
2. **GraalVM Native Image + PGO** — 12MB binary, sem warmup JIT
3. **selectNow() antes de select()** — micro-otimização do NIO reactor
4. **Inline response write** — elimina epoll_wait por request
5. **KdScratch thread-local shrinkado para 4KB** — fit em L1D cache

### Estratégico:
6. **Migrar search para Rust FFI** se Java + GraalVM ainda não chegar no target

### NÃO implementar:
- **Java Vector API / Panama SIMD** — confirmado lento e quebra GraalVM
