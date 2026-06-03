# Análise: VitorNathanG/rinha-de-papagaio

- Repositório: https://github.com/VitorNathanG/rinha-de-papagaio
- Linguagem: **Rust**
- Score: **6.000/6.000 (perfeito)**
- p99: **0.13ms**
- Accuracy: TP=24.037, TN=30.023, FP=0, FN=0

## HTTP Server

Hyper 1.x raw (sem Axum), Tokio `current_thread` (single-threaded por réplica).
- 0 dependências de framework
- Match manual de `(method, uri)` para roteamento
- TCP_NODELAY ativo

## Load Balancer

**nginx em modo stream (TCP puro):**
```nginx
stream {
    upstream papagaio_api {
        server unix:/var/run/papagaio/api1.sock;
        server unix:/var/run/papagaio/api2.sock;
    }
    server {
        listen 9999 reuseport backlog=4096;
        proxy_pass papagaio_api;
    }
}
```
- nginx NÃO parseia HTTP — forward de bytes crus
- Unix domain sockets entre nginx e API (sem overhead de TCP loopback)
- 1 worker, backlog 4096

## JSON Parsing

Zero-copy byte-level parser:
- Sem `serde_json`, sem `serde`
- Exploita **ordem determinística dos campos** do data generator da Rinha
  (confirmado via análise do `data-generator/main.c:774`)
- `memchr::memmem` para localização de chaves, `memchr` para delimitadores
- Retorna slices no buffer original (zero copy)

## Algoritmo de Busca — KNOWLEDGE DISTILLATION (inovação principal)

**Dois caminhos:**

**Fast path (~96.5% das queries — ~1µs):**
- MLP 3 camadas (14→64→64→3, 5.315 parâmetros, 20.8 KB)
- Classifica query em: A-Legítimo, A-Fraude, ou B (incerto/fronteira)
- Se P(B) ≤ 0.5: decide diretamente sem tocar no IVF
- P(A-Legit) > P(A-Fraud) → approved; caso contrário → fraud

**Slow path (~3.5% das queries — ~6µs):**
- Acionado quando P(B) > 0.5 (query na região de fronteira)
- IVF k=5 sobre subconjunto curado de ~213k referências de fronteira ("Box-B")
- NLIST=256 clusters, NPROBE=16
- 100% recall vs brute-force no stress test

## MLP Router — Detalhes de Treinamento

- Ativação: GELU com aproximação tanh (`nn.GELU(approximate="tanh")`)
- Loss: cross-entropy com **pesos por frequência inversa** de classe
  (sem pesos → modelo colapsa para "sempre prediz classe A")
- Early stopping: **primeiro epoch com zero misroutes B→A sobre 3M vetores**
  (não val_loss — val_loss e misroutes divergem)
- Ground truth: busca exata float32 sobre os 3M vetores originais

Implementação GELU em Rust (Padé[7/6] rational approximation de tanh):
```rust
fn tanh_approx(x: f32) -> f32 {
    if x.abs() >= 4.97 { return x.signum(); }
    let x2 = x * x;
    let num = x * (135135.0 + x2 * (17325.0 + x2 * (378.0 + x2)));
    let den = 135135.0 + x2 * (62370.0 + x2 * (3150.0 + x2 * 28.0));
    num / den
}
```
~2× mais rápido que `libm::tanhf`, precisão ~2e-7.

## Quantização

**int16 escalado por 10.000** com **round4 grid crítico:**
```rust
let k = (valor * 10_000.0).round();
out_f32[i] = k / 10_000.0;  // para o MLP
out_i16[i] = k as i16;       // para o IVF
```

O round4 deve corresponder EXATAMENTE ao que o data generator da Rinha aplica
antes de calcular os k-NN de referência. Descoberto empiricamente: query 5472
falhou porque tie-break de rank 5 vs 6 dependia de arredondamento. +120 pontos
quando corrigido.

## Memória

- Arquivos mmap'd via `memmap2::Mmap`, leaked como `&'static [u8]`
- `Advice::WillNeed` hint + page-touch manual no startup

```rust
fn touch_pages(bytes: &[u8]) {
    let mut acc = 0u64;
    for i in (0..bytes.len()).step_by(4096) {
        acc = acc.wrapping_add(bytes[i] as u64);
    }
    std::hint::black_box(acc); // impede otimização pelo compilador
}
```

- Software prefetch 8 refs à frente: `_mm_prefetch::<{_MM_HINT_T0}>(ptr + 8*64)`
- Layout CSR: refs ordenadas por cluster, scan linear dentro do cluster

## Recursos computacionais

- 2 réplicas: 0.425 CPU, 120MB RAM cada
- nginx: 0.15 CPU, 30MB RAM
- Total: ~0.95 CPU, 270MB RAM

## Jornada de otimização (0.30ms → 0.13ms)

| Passo | Mudança | p99 |
|---|---|---|
| Baseline | axum + serde_json + multi-thread tokio | 0.30ms |
| Byte parser + static responses | Custom vectorize + RESPONSES array | 0.24ms |
| hyper-direct + current_thread + TCP_NODELAY | Remove axum, single-thread | 0.16ms |
| UNIX domain sockets nginx ↔ API | Skip TCP stack | 0.15ms |
| IVF slow-path | Fit working set em L2/L3 | — |
| Padding & cache-line alignment | 16 floats/ref = 64B = 1 cache line | — |
| **Final** | **Tudo acima** | **0.13ms** |

## Acoplamentos numéricos críticos documentados

1. **round4**: vetorização deve usar mesmo round4 do data generator
2. **GELU(tanh) formula**: deve corresponder ao `nn.GELU(approximate="tanh")` do PyTorch
3. **Padé[7/6] tanh**: deve corresponder ao treinamento
4. **Dimensões do MLP hardcoded**: D_IN=14, H=64, D_OUT=3, DEPTH=2 no Rust

## O que é aproveitável para nós

1. **nginx stream mode** — language agnostic, podemos usar hoje
2. **Unix domain sockets** — language agnostic
3. **Pre-computed responses** — já no roadmap V3-B
4. **Page pre-warming** — já no roadmap V3-A
5. **Knowledge distillation** — possível em Java (MLP é só multiplicações de matriz)

## O que é Rust-specific

- `_mm256_madd_epi16` AVX2 SIMD direto
- Padé approximation para GELU
- `memmap2` / `Advice::WillNeed` (Java tem equivalente via `MappedByteBuffer.load()`)
- `tokio current_thread` (Java teria NIO reactor)
