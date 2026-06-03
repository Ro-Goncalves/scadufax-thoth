# Celeritas: Plano de Execução

## Objetivo

A V2 fechou em **K=1024, nprobe=4** com p99=36.95ms e `final_score`=2.766,95. O
gargalo restante não é o algoritmo — é **overhead e variância**:

1. **Page fault penalty (cold reads):** primeiro benchmark da sessão roda frio;
   observado 130ms → 36ms entre runs.
2. **Alocação no hot path:** Jackson serializa a resposta por request, e a busca
   gera um `SearchResult` + uma `String` por candidato varrido (~11.720 por request).
3. **JIT warmup:** já mitigado pelo `WarmupService` existente.

A V3 (Celeritas) é **remoção de overhead, sem mudar o algoritmo de busca**. A meta
é eliminar a variância cold/hot e o lixo por request, para medir o p99 real em
regime quente desde o primeiro request — e fechar a folga até a meta de 25ms da V2.

---

## Escopo acordado

| Item | O que é | Status |
|---|---|---|
| **Pré-requisito** | Fixar o build no envelope vencedor K=1024 / nprobe=4 | Entra |
| **V3-A** | Page pre-warming do `.v2` antes do `/ready` | Entra |
| **V3-B** | Respostas pré-serializadas (tabela `byte[][]`) | Entra |
| **V3-D** | Hot path de busca sem alocação (insertion sort K=5) — puxado do V4-D | Entra |
| **V3-C** | nginx stream + Unix domain sockets | **Removido / adiado** |

### Por que o V3-C saiu

O V3-C é, na prática, duas mudanças com custos opostos:

- **nginx `http{}` → `stream{}` (barato, só config):** ganho ~nulo e com custo
  escondido — perde o keepalive e o balanceamento em nível HTTP que o `nginx.conf`
  atual já explora (`keepalive 256`, `proxy_set_header Connection ""`). Pode até
  piorar.
- **Javalin/Jetty escutando em Unix domain socket (caro e frágil):** é onde mora o
  valor real, mas exige customizar `Server`/`ServerConnector` do Jetty na mão
  (`UnixDomainServerConnector`), por dentro de uma API que o Javalin não expõe de
  forma limpa. Frágil em upgrade de versão.

Decisivo: a parte cara é exatamente a camada que o **V4-E (servidor NIO custom)** e o
**V6 (fd-passing)** vão reescrever do zero. Investir em UDS+Jetty agora é trabalho que
vira lixo em duas versões. Adiado — sem perda, com a justificativa registrada.

---

## Pré-requisito: fixar o envelope K=1024 / nprobe=4

A V2 fechou em K=1024/nprobe=4, mas o build default ainda está na config de
experimentação:

| Local | Hoje | Vai para |
|---|---|---|
| `Dockerfile` → `ARG NUM_CLUSTERS` | 256 | **1024** |
| `docker-compose.yml` → `NPROBE` default | 8 | **4** (api01 e api02) |

Sem isso, qualquer medição de "antes/depois da V3" mediria a config errada (a faixa
de ~257ms do smoke, não os 36ms). É **pré-requisito de medição**, não detalhe. O
K-means paralelo (estágio de build, todos os cores) já absorve o custo de K=1024
(~150s, confirmado na V2).

---

## V3-A: Page pre-warming

### Problema

Com mmap, o SO carrega páginas do `.v2` sob demanda. O primeiro request de uma sessão
paga page faults; os seguintes voam. O K6 mede o mix, inflando o p99.

### Decisão de design: tocar o `MemorySegment`, não um `FileChannel` à parte

O roadmap sugeria ler o arquivo inteiro por um `FileChannel` separado. Isso aquece o
**page cache do SO**, mas o mmap usado no runtime ainda sofreria um *soft fault* na
primeira vez que cada página fosse acessada pela FFM API.

Vamos um passo além: **tocar o próprio `MemorySegment`** que o hot path usa — um byte
a cada página de 4KB —, faltando as páginas direto na tabela de páginas do processo.
Elimina disco **e** soft fault.

```java
// V2IndexSearcher.prewarm() — encapsulado, opera sobre o próprio mapeamento
public void prewarm() {
    long size = file.byteSize();
    long sink = 0;
    for (long off = 0; off < size; off += 4096) {
        sink += file.get(ValueLayout.JAVA_BYTE, off);
    }
    sink += file.get(ValueLayout.JAVA_BYTE, size - 1);   // última página
    PREWARM_SINK = sink;                                  // evita dead-code elimination
}
```

`PREWARM_SINK` é um campo `static volatile` só para impedir que o JIT descarte o laço.

### Onde chamar

O `WarmupService.warmup` já roda **síncrono** no `create()`, antes do `.start()` —
logo o `/ready` só responde 200 depois dele. Adicionamos `searcher.prewarm()` como
**primeira etapa** do warmup (page-warm → JIT-warm). A propriedade de gating se mantém.

### Custo / risco

- Startup aumenta < 1s (leitura sequencial de ~45–48MB). Aceitável.
- Memória: as páginas faltadas são file-backed e reclamáveis sob pressão; 45MB
  dentro do limite de 165MB por API convive com `-Xmx80m`. Sem risco de OOM.

---

## V3-B: Respostas pré-serializadas

### Problema

`SearchHandler` monta um `TransactionResponse` (record) e chama `ctx.json(...)`, que
aciona o Jackson **a cada request** — alocação de `String` + pressão de GC para um
payload que só tem **6 formas possíveis**.

### Decisão de design: tabela parametrizada por `kNeighbors`

Os resultados possíveis são `fraudCount ∈ {0 .. kNeighbors}` →
`kNeighbors + 1` respostas. Pré-serializamos todas no bootstrap como `byte[]`:

```java
// Construída no SearchHandler (já recebe kNeighbors e fraudThreshold)
private final byte[][] responses;

private static byte[][] buildResponses(int k, double threshold) {
    byte[][] table = new byte[k + 1][];
    for (int i = 0; i <= k; i++) {
        double score    = (double) i / k;
        boolean approved = score < threshold;
        String json = "{\"approved\":" + approved + ",\"fraud_score\":" + score + "}";
        table[i] = json.getBytes(StandardCharsets.UTF_8);
    }
    return table;
}
```

No hot path, zero serialização:

```java
ctx.contentType(ContentType.JSON).result(responses[fraudCount]);
```

### Cuidado registrado

- **Formato do `fraud_score`:** com `k=5`, `score ∈ {0.0, 0.2, 0.4, 0.6, 0.8, 1.0}`.
  `Double.toString` produz exatamente `"0.2"`, `"0.4"`, … `"1.0"` — idêntico ao que o
  Jackson emitia. Sem regressão no contrato (`docs/rinha/API.md` mostra `1.0`).
- **Tabela parametrizada, não hardcoded:** se `K_NEIGHBORS` mudar por env, a tabela
  se ajusta sozinha. Nada de assumir "6 entradas".
- **Escopo:** V3-B resolve só o **lado da resposta**. O parse de entrada
  (`ctx.bodyAsClass`, Jackson) continua até o **V4-C** (parser custom). Explícito.

---

## V3-D: Hot path de busca sem alocação (puxado do V4-D)

### Problema

`V2IndexSearcher.search` usa um `PriorityQueue<SearchResult>` e, para cada candidato
que entra no top-k, aloca um `SearchResult` e uma `String` (`"fraud"`/`"legitimate"`).
Em K=1024/nprobe=4 são ~11.720 candidatos varridos por request — a maior fonte de
garbage do hot path.

### Decisão de design: refatorar o interior, preservar a interface

A interface `VectorSearcher` (`List<SearchResult> search(float[], int)`) é usada por
testes e pela guarda de qualidade. **Não quebramos a assinatura.** Em vez disso,
trocamos o miolo:

- max-heap de objetos → **insertion sort sobre arrays primitivos** de tamanho k:
  `double[] topDist` (distância) + `byte[] topLabel` (label int8).
- comparação `dist < pq.peek().distance()` → `dist < topDist[k-1]` (pior atual).
- O label vira `byte` na varredura; só convertemos para `String` ao **materializar
  os k=5 resultados finais**.

```java
// 5 slots, mantidos ordenados por distância crescente (k pequeno → O(k) por insert)
void tryInsert(double dist, byte label, double[] topDist, byte[] topLabel, int size, int k) {
    if (size < k || dist < topDist[size - 1]) {
        int pos = (size < k) ? size : k - 1;
        while (pos > 0 && topDist[pos - 1] > dist) {
            topDist[pos]  = topDist[pos - 1];
            topLabel[pos] = topLabel[pos - 1];
            pos--;
        }
        topDist[pos]  = dist;
        topLabel[pos] = label;
    }
}
```

Resultado: o lixo por candidato (milhares de objetos) some; sobra a materialização de
5 `SearchResult` no fim — desprezível e mantida só por compatibilidade de interface/teste.

### Concorrência

Os arrays do top-k são **locais à chamada** (k=5 → alocação trivial). O `V2IndexSearcher`
é compartilhado entre requests concorrentes (virtual threads), então nada de campos de
instância mutáveis. Não usamos `ThreadLocal` — com virtual threads ele multiplicaria por
thread e venceria o propósito.

### Nota para o futuro (V4)

O `SearchHandler` só precisa do **número de fraudes no top-k**, não das distâncias nem
da ordem. Um método `int nearestFraudCount(...)` evitaria até os 5 objetos finais — mas
isso é refinamento do V4, fora do escopo da V3. Registrado para não esquecer.

---

## Como vamos medir

A V3 é sobre **variância**, então a medição importa tanto quanto o código.

1. **Baseline:** rebuild já com K=1024/nprobe=4 (pré-requisito), rodar o K6 oficial
   (`run-benchmark.sh`) **sem** V3-A/B/D. Capturar p99, avg, e a curva cold→hot.
2. **Incremental:** aplicar A, depois B, depois D, medindo cada um. Isolar o ganho de
   cada técnica (especialmente A, que deve achatar a cauda cold).
3. **Sinais esperados:**
   - V3-A: p99 do request 1 cai de ~130ms para a faixa de regime (~36ms). Variância
     cold/hot some.
   - V3-B + V3-D: avg e p99 em regime quente caem (menos GC, menos trabalho por
     request). Sem impacto em FP/FN.

---

## Critérios de aceite

- **Guarda de qualidade intacta:** `V2QualityGuardTest` e `V2IvfSearchTest` continuam
  verdes — o refactor do V3-D é puramente estrutural, recall idêntico (mesmos vizinhos,
  mesma ordem). Mudança no resultado da busca = bug.
- **Contrato de API intacto:** resposta byte-a-byte idêntica à do Jackson para os
  `kNeighbors + 1` casos. Adicionar teste que compara a tabela pré-serializada com a
  serialização Jackson de referência.
- **Zero erros HTTP** no K6, como na V2.
- **`/ready` só sobe após page-warm + JIT-warm** (gating preservado).

---

## Sequência de implementação

```
0. Pré-requisito — pin K=1024/nprobe=4 (Dockerfile + compose)  ← desbloqueia medição
1. Baseline K6 com o envelope correto
2. V3-A  page pre-warming        → medir (achata cauda cold)
3. V3-B  respostas pré-serializadas → medir
4. V3-D  insertion sort sem alocação → medir
5. Rodar guarda de qualidade + teste de contrato de resposta
6. Registrar resultados nesta página (seção "Resultados")
```

## Resultados

> A preencher quando a V3 fechar.