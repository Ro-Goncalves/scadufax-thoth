# Issue 07: Busca nativa-rápida via MappedByteBuffer (substitui MemorySegment/FFM)

Status: done

## Issue pai

[PRD: V5 — Opus](../PRD.md). Corrige a história de usuário 28 (falsificada na Issue 04).

## Contexto

Com o servidor NIO (Issue 06) resolvendo o Jetty, o nativo ainda falhava ~92% sob carga.
Causa isolada: o `V2IndexSearcher` lê o índice mmap via `java.lang.foreign.MemorySegment`
(FFM). **O GraalVM CE sem PGO não otimiza o acesso FFM** — ~10ms/req no nativo vs 0,7ms no
JVM (medido: K=64 dava 400ms, K=2048 dava 10ms — escala com o nº de acessos ao segment).
A 0,45 CPU isso rende ~45 req/s, longe dos ~450 necessários.

### Evidência externa (arthurd3, top performer Java)

`github.com/arthurd3/fraud-detection` confirma o diagnóstico e a cura:
- Evita FFM de propósito — lê o índice via **`MappedByteBuffer`** (`KdMmap.java`:
  *"no FFM/Unsafe — safe under GraalVM native-image"*).
- Kernel de distância = **loop escalar sobre array/ByteBuffer** que o GraalVM
  **auto-vetoriza (AVX2)**; o `MemorySegment` não é vetorizado.
- Pure Java nativo + AOT/PGO dele: ~4393–4836 de score.

## O que foi construído

Troca cirúrgica do leitor do índice, mantendo layout/semântica do artefato `.v2`:

- `DistanceCalculator` e `EuclideanDistanceCalculator`: assinaturas `MemorySegment` →
  **`ByteBuffer`**. Hot path `calculateI16` usa `buffer.getShort(off)` (LE) em loop escalar;
  `calculateI8` usa `buffer.get(off)`; o path float (só teste) lê BIG_ENDIAN explícito.
- `V2IndexSearcher`: `Arena.ofShared()` + `MemorySegment.map()` → `FileChannel.map()` →
  **`MappedByteBuffer`** em `LITTLE_ENDIAN` (sem `Arena`). Acessos a label e prewarm via
  `get(int)`. `close()` vira no-op (mmap liberado pelo Cleaner).
- `prewarm()` passa a chamar **`MappedByteBuffer.load()`** (MADV_WILLNEED) + touch por
  página — mantém o índice residente (antecipa parte da infra de cauda).
- Acesso absoluto (`getShort(int)`) não muta a posição → seguro no reactor single-thread.
- Índice ~90MB < limite de 2GB do `MappedByteBuffer` — ok.

## Resultado (2026-06-05)

Lever **decisivo**. Detecção idêntica (V2QualityGuardTest 0 FP/FN), 37 testes verdes.

| Configuração | p50 | p99 | falha | score |
|---|---|---|---|---|
| Antes (MemorySegment, nativo) | 2001ms | 2237ms | 92% | −6000 |
| Depois — host, sem limite CPU | 0.68ms | 2.35ms | 0% | **5628** |
| Depois — stack real (0.45 CPU, haproxy) | 2.13ms | 63.9ms | 0% | **4194** |

De **−6000 → +4194**, busca nativa igualou o JVM (0.68 vs 0.7ms). Bônus: variância
run-to-run colapsou (1 run de benchmark passou a bastar).

## Critérios de aceite

- [x] `mvn test` verde, detecção intacta (V2QualityGuardTest 0 FP/FN)
- [x] Build nativo OK; latência keep-alive nativa **sub-ms** (era ~10ms)
- [x] Load test nativo: 0% falha, 0 FP/FN, score positivo
- [x] Nenhum `MemorySegment`/`java.lang.foreign` no código de runtime

## Notas

- Restou o **p99 ~62ms** na stack constrangida — não é custo de busca (p50 ótimo, e o p99
  é igual em todas as configs de K/nprobe). É a cauda do busy-poll sob CFS. **Issue 08.**

## Bloqueada por

- [Issue 06: Servidor NIO](06-nio-server.md) — a busca rápida só importa com o servidor
  que despacha (o Jetty serializava antes).
