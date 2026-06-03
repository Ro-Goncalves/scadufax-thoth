# Análise: lucasmontano/rinha-backend-2026-detecta-fraude

- Repositório: https://github.com/lucasmontano/rinha-backend-2026-detecta-fraude
- Linguagem: **Rust + C (load balancer)**
- Score: não documentado explicitamente (posicionado como "melhor de todos")
- p99: < 1ms (estimado por análise de código)
- Accuracy: exata (exact vector search)
- Stack: `["rust", "c", "exact vector search", "kd-tree", "scm_rights", "epoll", "avx2"]`

## HTTP Server

**Custom epoll nativo** em Rust (`src/server.rs`, 817 linhas):
- Zero frameworks — só `libc` e syscalls diretas
- Non-blocking I/O com `MSG_DONTWAIT` e `MSG_NOSIGNAL`
- TCP_NODELAY + TCP_QUICKACK
- Pool pré-alocado de conexões (`CONN_POOL_CAP=512`)
- Single-threaded por worker (2 workers, um por CPU)

**Busy-poll epoll** (configurável via env):
```
EPOLL_SPIN_US=0
EPOLL_IDLE_US=60
EPOLL_BUSY_POLL_US=100
EPOLL_BUSY_POLL_BUDGET=8
EPOLL_PREFER_BUSY_POLL=1
```

## Load Balancer — C ESTÁTICO com FD-PASSING

`src/bin/lb_c.c` (218 linhas), compilado com:
```
gcc -O3 -march=haswell -static -flto -fno-stack-protector -DNDEBUG -s
```

Funcionalidades:
- `poll()` para I/O
- Accept batching: até 128 conexões por batch (`LB_ACCEPT_BATCH`)
- `sendmsg()` + SCM_RIGHTS para passar file descriptors aos workers
- Round-robin entre backends
- `TCP_DEFER_ACCEPT`: kernel espera dados antes de acordar accept
- `SO_SNDBUF=256KB`
- Backlog: 65.535
- Unix domain sockets para comunicação com workers

CPU affinity via docker-compose:
- lb: CPUs 2-3
- api1: CPU 0
- api2: CPU 1

## JSON Parsing

Custom zero-copy byte scanner (`src/parse.rs`, 424 linhas):
- Sem `serde_json`, sem `serde`
- Exploita **ordem determinística dos campos** do data generator
- `memchr` para localização de delimitadores
- `read_array_raw()`: armazena slice raw do array JSON sem parsear conteúdo
- Matching de known_merchants: linear scan no JSON raw array
- Early exit em qualquer erro de parse

## Algoritmo de Busca — KD-TREE HÍBRIDO + IVF FALLBACK

### Versão primária: KD-tree particionado (KD_PAIR_VERSION = v6)

**Particionamento em 2 níveis:**

1. **Coarse (256 buckets):** `partition_key()` — 8 bits derivados de 8 features:
   - Bit 0: is_online (boolean)
   - Bit 1-3: comportamento de transação do merchant
   - Bit 4-5: faixas de amount
   - Bit 6-7: buckets de frequência/geográfico

2. **Fine (binary space partitioning):** KD-tree dentro de cada bucket
   - Profundidade ~22, folhas com 128 elementos
   - Bounding boxes: min/max vectors por nó

**Early termination:** se distância do 5º vizinho ≤ 140 milliscaled units (140² em distância quadrática) → para busca nos buckets restantes. Economiza 30–50% do tempo em queries confident.

**Formato de nó (Pair Layout):**
- 8 lanes × 14 dims × i16 = 224 bytes por bloco
- Dimensões interleaved entre lanes para cache efficiency com SIMD
- Labels: u8 por vetor, paddedo para blocos de 8 lanes

### Versão fallback: IVF (IVF_VERSION = v5)

- 4.096 clusters (√N para N=3M ≈ 1.732, mas usam 4.096)
- NPROBE=12 clusters visitados por query
- **Repair mechanism:** se fraud_count ∈ {1,2,3,4} OU distância > threshold → scan clusters adicionais até 1.024 candidatos
  - Resolve casos ambíguos na fronteira de decisão
- Scan em blocos AVX2 de 8 lanes

## Distance Calculation — AVX2 int16

Quantização: i16 escalada por 10.000 (`const SCALE: f64 = 10_000.0`):
- 14 dims usadas, 16 armazenadas (2 padding para alinhamento SIMD)
- Fórmula: `(v * 10_000).round() as i16`
- Valores negativos → -SCALE; ≥1.0 → SCALE

AVX2 (`_mm256_madd_epi16`):
- 8 comparações paralelas de distância por bloco
- 16 i16 diffs → 8 i32 pair-sums em uma instrução
- Software prefetch 8 blocks à frente

Compilação:
```
RUSTFLAGS="-C target-cpu=haswell -C target-feature=+avx2,+fma,+bmi2"
```

## Memória

- Índice ~320 MB mapeado via `memmap2` com `populate()` flag
- `madvise()` calls no startup:
  - `MADV_HUGEPAGE` no índice inteiro
  - `MADV_RANDOM` nos dados de vetores
  - `MADV_WILLNEED` na seção hot
- Prefetch loop explícito: 1 byte a cada 4096 bytes durante inicialização
- `INDEX_MLOCK=1` via env → `mlock()` para residência garantida
- Buffer por conexão: 16KB input + 512B output, pré-alocados e reutilizados

## Pre-computed Responses

6 respostas HTTP estáticas (`src/response.rs`, 49 linhas):
```
fraud_count=0 → score 0.0, approved: true
fraud_count=1 → score 0.2, approved: true
fraud_count=2 → score 0.4, approved: true
fraud_count=3 → score 0.6, approved: false
fraud_count=4 → score 0.8, approved: false
fraud_count=5 → score 1.0, approved: false
```
Zero serialização por request — lookup O(1) por fraud_count.

## Recursos

- lb: CPUs 2-3, 20MB RAM
- api1: CPU 0, 165MB RAM
- api2: CPU 1, 165MB RAM
- Total: ~1.0 CPU, 350MB RAM

## Formato binário do índice

```
Header (64 bytes)
Partitions (76 bytes × 256):
  key (u32) + root (i32) + count (i32) + min[14×i16] + max[14×i16]
Nodes (80 bytes cada):
  left (i32) + right (i32) + start_block (i32) + length (i32) + min[28B] + max[28B]
Vector blocks (224 bytes = 8 lanes × 14 dims × i16):
  dimensões interleaved entre lanes
Labels (u8 por vetor, paddedo para 8 lanes)
MCC lookup table (1024 × i16)
```

## Build

Dockerfile multi-stage:
1. Rust 1.95 slim base
2. Compila `index-builder` (Rust)
3. Download `references.json.gz` durante build
4. Gera índice binário
5. Compila binário principal com `-C target-cpu=haswell +avx2,+fma,+bmi2`
6. Compila C LB com `-march=haswell -O3 -flto -s`
7. Final: `distroless/cc-debian12`

Rust release profile:
```toml
opt-level = 3
codegen-units = 1
lto = "fat"
panic = "abort"
strip = true
overflow-checks = false
```

## O que é aproveitável para nós

### Language-agnostic (aplicável imediatamente):
1. **TCP_DEFER_ACCEPT** no socket do LB — kernel aguarda dados antes de wakeup
2. **Accept batching** — 128 conexões por batch no LB
3. **CPU affinity pinning** via `cpuset_cpus` no docker-compose
4. **Repair mechanism no IVF** — se resultado ambíguo, scan clusters adicionais
5. **Early termination por confiança** — se 5º vizinho ≤ threshold, para busca

### Infra (requer esforço):
6. **C LB com fd-passing** (218 linhas de C) — alternativa ao Rust LB
7. **Busy-poll epoll** — reduz latência em workloads de alta frequência

### Insight de algoritmo:
8. **Partition key por features** — alternativa ao IVF baseada em bits de features
   booleans/categóricos (is_online, card_present, faixa de amount, etc.)
   Coloca queries similares no mesmo bucket por natureza, sem K-means.

### NÃO aplicável em Java:
- AVX2 SIMD direto (Rust/C only)
- `MADV_HUGEPAGE` direto (Java não expõe via MappedByteBuffer)
- `TCP_DEFER_ACCEPT` (precisa de socket raw, não Javalin)
- busy-poll epoll nativo

## Técnicas únicas não vistas em outros

1. **C LB estático minimal** (218 linhas) — mais simples que Rust LB do arthurd3
2. **Partition key por feature bits** — alternativa elegante ao K-means para particionamento
3. **IVF repair mechanism** — scan adicional para queries ambíguas (fraud_count 1-4)
4. **`TCP_DEFER_ACCEPT`** — aceitar conexão só quando dados chegaram
5. **Accept batching no LB** — 128 por loop, reduz syscall overhead
6. **Busy-poll epoll com budget configurável** — equilíbrio entre latência e CPU
