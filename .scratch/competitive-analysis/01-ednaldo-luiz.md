# Análise: EdnaldoLuiz/rinha-de-backend-2026-java

- Repositório: https://github.com/EdnaldoLuiz/rinha-de-backend-2026-java
- Linguagem: Java 21
- Score: não documentado na pesquisa
- p99: não documentado

## HTTP Server

`JDK HttpServer` (Sun API), NÃO usa framework.
- Thread pool fixo via env `SERVER_THREADS` (default 2, mínimo 1)
- Endpoints: `GET /ready`, `POST /fraud-score`
- Padrão Decorator: `ErrorShieldHandler` para tratamento central de exceções
- Respostas pré-serializadas como `byte[]` — zero serialização no hot path

## JSON Parsing

Custom parser caractere-a-caractere (`PayloadParser`).
- Single-pass, bounds checking explícito, sem tokenização
- Suporta notação científica
- Extrai diretamente: amount, installments, requested_at, avg_amount, tx_count_24h, known_merchants, merchant id/mcc/avg_amount, terminal is_online/card_present/km_from_home

## Algoritmo de Busca

**Bucketing multidimensional** (alternativa ao IVF, NÃO é k-means):
- 2.688 buckets: `(binaryBucket × 24 + hour) × 7 + day × 4 + txBucket`
- Binary bucket (3 bits): card presence + online + merchant flags → 8 variantes
- Hour bucket: hora do dia (0–23)
- Day bucket: dia da semana (0–6)
- Transaction bucket: frequência 24h (4 níveis)

Busca (`ExactKnnSearchEngine`):
1. Exact bucket match primeiro
2. Expansão de vizinhança: hora ±1, transação ±1 no mesmo dia
3. Fallback: varre buckets restantes se necessário
4. Early abort por distância: para computation ao exceder pior top-5 atual
5. Top-5 com insertion sort (sem heap)

## Quantização

`float32` — sem quantização int8/int16. Vetores em float.

## Memória

- Columnar storage: `float[] vectors`, `byte[] labels`, `Bucket[] buckets`
- Stride 16 floats por vetor (14 dims + 2 padding de alinhamento)
- Bounding boxes por bucket: min/max por dimensão
- Carga completa em heap (não usa mmap)
- Thread-local reuse: `Top5Selector` e arrays de priority reutilizados por thread

## Pre-computed Responses

Seis respostas fixas pré-computadas no startup:
- Indexadas por `fraud_count` (0–5)
- Cada uma é um `byte[]` com JSON completo
- Zero serialização por request

## Pré-processamento / Build

Pipeline:
1. Lê JSON de referência (suporta `.gz`)
2. Extrai vetores 14-dim, codifica em bucket keys
3. Agrupa por bucket, calcula bounding boxes
4. Serializa para formato binário com metadados

Formato binário (Versão 2):
- Magic: `0x52484E32` ("RHN2")
- Header: version, stride (16), dims (14), vectorCount, bucketCount
- Metadados: key, startIdx, count, min[14], max[14] por bucket
- Dados: vetores contíguos (vectorCount × 16 floats), labels ao final

## Build / Deploy

- Maven multi-módulo: `app`, `tools/preprocessor`, `tools/inspector`
- Suporte a GraalVM Native Image via `native-maven-plugin`
- HAProxy como load balancer
- HAProxy: round-robin, TCP mode, max 4096 conexões

## Fallback Rules-Based Scorer

Quando vectorização/busca falha:
- Amount > 3× média do cliente: +1
- Distância > 100km de casa: +1
- Frequência > 30 tx em 24h: +1
- Card-not-present + offline: +1
- Score máximo: 5

## O que é aproveitável para nós

- Pre-computed responses (6 `byte[]` estáticos) ← trivial de implementar
- Insertion sort para K=5 em vez de PriorityQueue
- Thread-local reuse de arrays de busca
- Ideia de bounding boxes por cluster (já mapeado no V4-A do roadmap)

## O que NÃO é aproveitável

- Bucketing multidimensional: abordagem diferente do IVF, não é melhor
- HAProxy como LB: todos os top performers trocaram por fd-passing
- Sem quantização: nosso int8 é mais compacto e performático
