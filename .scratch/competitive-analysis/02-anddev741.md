# Análise: AndDev741/rinha-de-backend-2026-java

- Repositório: https://github.com/AndDev741/rinha-de-backend-2026-java
- Linguagem: Java
- Score: **4.056,85**
- p99: **87.73ms**
- Accuracy: **100% — 0 FP, 0 FN** (busca exata)

## Por que o score é alto com p99 alto

Score 4.056 > nosso 2.766, mesmo com p99 87ms vs nosso 36ms.
Motivo: **detecção perfeita** (0 FP, 0 FN) eleva score_det a ponto de
compensar latência maior. Confirma que qualidade de detecção é mais valiosa
que latência pura no scoring da Rinha.

## HTTP Server

**Custom NIO HTTP server single-threaded** (~500 linhas, chamado `MicrohttpServer`):
- `java.nio.channels` com epoll, SEM framework
- Single-threaded: elimina context switches com 0.45 vCPU
- Pre-computed response objects para os 6 resultados possíveis
- TCP_NODELAY ativo

## JSON Parsing

Cursor-based, zero-allocation:
- `JsonReader.java` (~14KB, ~300 linhas)
- Primitivas: `skipWs()`, `expect(c)`, `readString()`, `readDouble()`, `readLong()`, `readBoolean()`
- Parse ISO-8601 sem alocar `Instant` nem `ZonedDateTime`
  - Implementa algoritmo de Howard Hinnant (daysFromCivil) para epoch diretamente
- Tabelas de lookup para mês/dia
- Sem String creation, HashMap, nem regex no hot path

## Algoritmo de Busca — BOUNDING-BOX PRUNING (chave da detecção perfeita)

IVF com K=256 clusters + bounding-box pruning por cluster.

**Build time:** para cada cluster armazenar `float[14] bboxMin` e `float[14] bboxMax`.

**Query time:**
1. Calcular distâncias a todos os centroides (já feito no IVF normal)
2. Para clusters FORA do nprobe selecionado, calcular lower-bound geométrico:

```
lower_bound = 0
para cada dimensão d:
  se query[d] < bboxMin[d]: lower_bound += (bboxMin[d] - query[d])²
  se query[d] > bboxMax[d]: lower_bound += (query[d] - bboxMax[d])²
```

3. Se `lower_bound > current_5th_best_distance` → skip o cluster inteiro
4. Caso contrário, varrer os vetores do cluster normalmente

**Prova matemática:** se o lower_bound da bounding-box já excede o pior dos
5 vizinhos, nenhum vetor dentro do cluster pode estar no top-5 — garante
exatidão sem varrer todos os vetores.

**Resultado:** ~95% dos clusters pulados por query. Busca exata com custo
comparável ao IVF aproximado. 0 FP e 0 FN garantidos.

## Quantização

**int16 escalado por 10.000** (não int8):
- `(valor * 10_000).round() as i16`
- Preserva 4 casas decimais de precisão
- Distâncias em int32 (sem float)
- Memória: 2 bytes por dimensão vs 1 byte do int8 (dobro)

Nota: tentaram int8 mas reportam que perdeu qualidade, forçando nprobe=3 e
resultados aproximados. Nossa int8 funciona melhor — nossa implementação de
K-means é superior.

## Memória

- `ShortBuffer` com memory mapping para vetores (lazy page faulting)
- Carga completa em memória para centroides, bboxes, offsets, labels
- **Page pre-warming no startup:** lê todas as páginas antes de aceitar conexões
  - Elimina page faults no primeiro request K6
  - Custo: ~100ms de startup

## Concorrência

- Single-threaded NIO — sem context switches
- KnnSearcher em ThreadLocal (reuso por thread)
- Query vector `float[14]` alocado uma vez, reutilizado
- Zero alocação no hot path: parsing → vectorização → busca

## Top-K Selection

**Insertion sort para K=5** em vez de PriorityQueue:
- Array de 5 slots, mantido em ordem crescente
- O(5) com acesso linear, cache-friendly, zero alocação
- PriorityQueue tem overhead de boxing e reorganização de heap

## nginx

Nginx com keepalive tuning:
- Pool de 64 conexões keepalive para upstream
- Buffering desabilitado (payloads < 1KB)
- Connection reuse com HTTP/1.1

## Hardcoded Constants

Constantes de normalização hardcoded no bytecode (de normalization.json):
- Zero I/O em runtime
- Permite otimizações do compilador

## Lições críticas para nós

1. **Bounding-box pruning = busca exata** — implementar é V4-A do nosso roadmap
2. **int8 funciona para nós** — nossa qualidade de K-means é melhor que a deles
3. **Java Vector API NÃO foi testada aqui** — mas AndDev741 preferiu scalar
4. **Insertion sort para K=5** — remover PriorityQueue
5. **Pre-warming** — já no nosso V3-A
6. **Pre-computed responses** — já no nosso V3-B
