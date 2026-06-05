# Roadmap: Tesseract V3+

> Pesquisa completa em `.scratch/competitive-analysis/` — 5 repositórios analisados.

## Decisão da V2 (issue 05 — HITL pendente)

A matriz K×nprobe está medida. O vencedor é **K=1024, nprobe=4**:

| Métrica | Valor |
|---|---|
| p99 | 36.95ms |
| final_score | 2.766,95 |
| score_p99 | 1.432,39 |
| score_det | 1.334,56 |
| recall | 99.57% |
| FP / FN | 105 / 102 |
| Erros HTTP | 0 |

---

## O que os competidores ensinaram

Cinco implementações analisadas. A descoberta mais importante não foi sobre
latência.

### A qualidade vale mais que a velocidade no scoring

AndDev741 (Java) tem p99=87ms — **2× mais lento que nós** — mas score=4.056 —
**1.5× melhor que nós**. Motivo: 0 FP e 0 FN. Detecção perfeita via busca exata.

Nosso IVF com nprobe=4 visita 0.4% dos vetores. Queries na fronteira de cluster
têm seus vizinhos reais em clusters não visitados — gerando os 105 FP e 102 FN
que comprimem nosso `score_det` para ~1.334. Com busca exata, `score_det`
potencialmente dobra, levando o `final_score` para 3.400+.

### Comparativo de posições

| Implementação | Lang | Score | p99 | FP/FN | Técnica central |
|---|---|---|---|---|---|
| **Nós (V2)** | Java | 2.767 | 36.95ms | 105/102 | IVF aprox. int8 |
| AndDev741 | Java | 4.057 | 87.73ms | 0/0 | IVF exato + bbox pruning |
| arthurd3 | Java+Rust | 5.731 | 1.86ms | 0/0 | KD-tree + Rust FFI + fd-passing |
| Papagaio | Rust | 6.000 | 0.13ms | 0/0 | MLP router + IVF |
| lucasmontano | Rust+C | 6.000 | 0.252264ms | 0/0 | KD-tree + C LB + busy-poll |

### O que NÃO funciona (confirmado empiricamente)

**Java Panama Vector API / SIMD: NÃO implementar.**
arthurd3 removeu completamente — foi 3.8× mais lento que scalar e quebra o
build do GraalVM Native Image. Nenhum top performer Java usa Vector API.
Removido do roadmap.

---

## Por que a V2 parou em 36ms e FP/FN ≠ 0

**Latência residual:**
1. Page fault penalty — mmap frio; observado empiricamente: 130ms → 36ms entre runs
2. JIT warmup — kernel de distância compila iterativamente; penaliza primeiros requests
3. JVM overhead — ~80MB de RAM deixam só ~195MB para Page Cache do `.v2`

**FP/FN ≠ 0:**
IVF é busca **aproximada**. nprobe=4 de 1024 clusters = 0.4% dos vetores visitados.
Queries de fronteira têm vizinhos reais em clusters não visitados.

---

## V3: Celeritas — Remoção de overhead — rápido de implementar

> **Plano de execução detalhado em [`v3/01-celeritas.md`](v3/01-celeritas.md).**
> Escopo final acordado: **pré-requisito (pin K=1024/nprobe=4) + V3-A + V3-B + V3-D**.
> O **V3-C foi removido/adiado** e o **V3-D foi puxado do V4-D**.

Nenhum item exige mudança no algoritmo de busca.

### Pré-requisito: fixar o envelope K=1024 / nprobe=4

O build default ainda está na config de experimentação (`Dockerfile`:
`NUM_CLUSTERS=256`; `docker-compose.yml`: `NPROBE` default `8`). A V2 fechou em
**K=1024, nprobe=4**. Antes de qualquer medição da V3, fixar `NUM_CLUSTERS=1024` e
`NPROBE=4` — sem isso, "antes/depois" mediria a config errada (~257ms, não 36ms).

### V3-A: Page pre-warming

Leitura sequencial do `.v2` inteiro antes de aceitar conexões.
Confirmado por AndDev741, arthurd3 e lucasmontano como técnica padrão.

```java
// No bootstrap, antes de /ready retornar true
try (FileChannel ch = FileChannel.open(v2Path, StandardOpenOption.READ)) {
    ByteBuffer buf = ByteBuffer.allocateDirect(1 << 20);
    while (ch.read(buf) != -1) buf.clear();
}
```

Elimina variância 130ms → 36ms. O K6 passa a medir steady state desde o request 1.

**Risco:** startup aumenta alguns segundos. `/ready` só retorna `200` após completar.

### V3-B: Respostas pré-serializadas

6 possíveis resultados (`fraud_count` ∈ {0..5}). Pré-alocar como `byte[]` no bootstrap.
Confirmado por todos os top performers (EdnaldoLuiz, AndDev741, arthurd3, lucasmontano).

```java
static final byte[][] RESPONSES = new byte[6][];
static {
    for (int i = 0; i < 6; i++) {
        boolean approved = (double) i / 5 < FRAUD_THRESHOLD;
        double score = (double) i / 5;
        RESPONSES[i] = ("{\"approved\":" + approved
            + ",\"fraud_score\":" + score + "}").getBytes(UTF_8);
    }
}
```

Zero serialização por request. Elimina alocação de String e GC pressure.

### V3-C: nginx stream + Unix domain sockets — ❌ REMOVIDO / ADIADO

São duas mudanças com custos opostos: o `http{}` → `stream{}` é barato mas de ganho
~nulo (perde o keepalive/balanceamento HTTP que o `nginx.conf` atual já explora), e a
parte valiosa — Jetty escutando em Unix domain socket — é cara e frágil (customizar
`Server`/`ServerConnector` na mão). Decisivo: essa camada é exatamente o que o **V4-E
(servidor NIO custom)** e o **V6 (fd-passing)** reescrevem do zero — investir agora
vira lixo em duas versões. Adiado. Detalhes em [`v3/01-celeritas.md`](v3/01-celeritas.md).

### V3-D: Hot path de busca sem alocação (puxado do V4-D)

`V2IndexSearcher.search` aloca um `SearchResult` + uma `String` por candidato varrido
(~11.720 por request em K=1024/nprobe=4) — a maior fonte de garbage do hot path.
Refatorar o **interior** do `search` (sem quebrar a interface `VectorSearcher`): trocar
`PriorityQueue<SearchResult>` por insertion sort sobre arrays primitivos (`double[]`
distância + `byte[]` label), materializando os 5 `SearchResult` só no final. Recall
idêntico, garbage por candidato eliminado. É o V4-D antecipado para a V3 — ver detalhes
e a nota sobre `nearestFraudCount` em [`v3/01-celeritas.md`](v3/01-celeritas.md).

---

## V4: Veritas — Busca exata (correção de detecção) — ✅ ENTREGUE

> **Resultado:** migração i8→i16 (−91% erros de quantização) + bounding-box pruning
> (busca provadamente exata) + parser JSON custom (zero-alocação na entrada).
> `score_det` atingiu o teto do sistema (**3.000**, 0 FP/FN). `final_score` ~4.394
> em 2 rodadas de validação; medição rigorosa de 5 boots pendente (Issue 07).
> p99 mediano ~40ms — cauda de GC, não custo de busca.

Tema **Veritas** — a verdade da detecção. Itens abaixo documentados para referência histórica.

### V4-0: Diagnóstico de percentis (abre a V4) — barato

Antes de otimizar, medir. Adicionar `p(50)` e `p(95)` ao K6 (uma linha em
`test/test.js`) e rodar o envelope algumas vezes. Separa as duas causas do spread de
cauda residual da V3:

- **Só o p99 balança** (p50/p95 estáveis) → cauda de GC. Justifica o **V4-C**.
- **p50/p95 balançam junto** → host sem CPU. É ruído de medição; V4-C não ajuda.

Detalhes e as duas hipóteses em [`v3/05-diagnostico-percentis.md`](v3/05-diagnostico-percentis.md).
Custo: uma linha mudada + uma rodada. **Decide se o V4-C entra.**

### V4-A: Bounding-box pruning por cluster ⭐ o coração da V4

Transforma o IVF aproximado em busca **provadamente exata**. Confirmado por AndDev741
como responsável pelos 0 FP e 0 FN que valem 4.056 de score com p99=87ms.

**Passo 1 — investigação de quantização (antes de codar o pruning):**
O bbox pruning remove o erro de **aproximação** do IVF, mas não o erro de
**quantização** int8. Nossos 207 erros (105 FP + 102 FN) misturam os dois. AndDev741
chega a 0/0 com **int16** (×10.000) e relata que int8 perdeu qualidade. Antes de
assumir, medir: comparar **int8-exato-BF vs float32-BF** para isolar quanto erro é
quantização.

- Poucos erros de quantização → fica **int8** (bbox de 28KB, artefato intacto).
- Muitos → reabre a decisão **int8 → int16** (dobra os vetores ~28→56MB, aperta o
  budget de ~195MB de page cache). Bifurcação real — só com prova na mão.

**Build time:** para cada cluster, calcular e persistir `bboxMin[14]` e `bboxMax[14]`
(int8 ou int16, conforme o passo 1). ~28KB (int8) adicionais no artefato `.v2`.

**Query time:** após ranquear centroides e varrer os `nprobe` clusters, iterar sobre
**TODOS** os clusters restantes e podar por lower-bound geométrico:

```java
private int bboxLowerBound(byte[] query, byte[] bboxMin, byte[] bboxMax) {
    int lb = 0;
    for (int d = 0; d < DIMS; d++) {
        int q = query[d];
        int lo = bboxMin[d], hi = bboxMax[d];
        if      (q < lo) { int diff = lo - q; lb += diff * diff; }
        else if (q > hi) { int diff = q - hi; lb += diff * diff; }
    }
    return lb;
}
```

Se `lb > currentWorst` (distância int do 5º vizinho atual) → skip o cluster.
Prova por triângulo: nenhum vetor dentro do cluster pode melhorar o top-5. Iterar
**todos** os clusters (não só nprobe) é o que torna a busca exata — e é também o que
torna o V4-B desnecessário (ver abaixo). Resultado: exatidão com ~95% dos clusters
podados sem varredura.

**Impacto estimado:** com 0 FP e 0 FN, `score_det` sobe de ~1.335 para ~2.000+.
`final_score` estimado: ~1.600 (p99 pós-V3) + ~2.000 (det) = **3.600+**, território
top-10 Java.

**Mudanças necessárias:**
- `V2ArtifactBuilder`: calcular min/max por cluster durante build, persistir no diretório
- `V2IndexSearcher`: ler bboxes, aplicar pruning iterando todos os clusters
- `V2QualityGuardTest`: validar 100% de acordo com float32 BF (e medir o passo 1)

### V4-B: IVF repair para queries ambíguas — ❌ CORTADO (subsumido pelo V4-A)

> O repair (lucasmontano) reescaneia clusters quando o IVF **aproximado** devolve
> resultado ambíguo. Mas o V4-A, iterando todos os clusters com poda por bbox, já
> entrega top-5 **exato** — não sobra ambiguidade para reparar. O V4-B resolve um
> problema que o V4-A elimina. Cortado; mantido aqui só como registro da decisão.

### V4-C: Custom JSON parser (zero-alocação) — condicionado ao V4-0

> **Entra só se o diagnóstico (V4-0) apontar cauda de GC.** Pós-V3, o Jackson no
> parse de **entrada** é a última fonte de alocação por request — a suspeita nº 1 do
> spread de p99. O impacto é **risco/cauda**, não mediana de score (o `score_p99` já
> saturou). Bônus: remove uma fonte de reflection, simplificando o V5 (GraalVM).

Jackson aloca por request. Para schema fixo e payload pequeno, cursor-based
é 10–20× mais rápido e zero-alocação. Confirmado por todos os top performers.

Referências de implementação:
- `JsonReader.java` do AndDev741 (~300 linhas) — Java, cursor-based
- `FraudRequestParser.java` do arthurd3 — single-pass com índice de chaves

Técnica chave: ISO-8601 → epoch seconds sem `Instant`/`ZonedDateTime`, usando
algoritmo de Howard Hinnant (daysFromCivil). O caller passa `float[]` pré-alocado;
o parser escreve in-place — zero `new` no hot path.

**✅ ENTREGUE (com regressão corrigida).** `FraudRequestParser` implementado;
`JavalinJackson` removido como mapper HTTP; 56/56 testes verdes. **Porém o `ThreadLocal`
de zero-alocação colidiu com `useVirtualThreads = true` e degradou o benchmark**
(p99 40 ms → 1116 ms; `final_score` +4394 → −1329). Correção: desligar virtual threads e
usar pool pequeno de platform threads (o `ThreadLocal` volta a reaproveitar). Autópsia
completa em `docs/knowledge/v4/07-postmortem-parser-virtual-threads.md`. Detalhes de
parsing em `docs/knowledge/v4/06-parser-json-custom.md`.

**Por que o parser produz `float[14]` e não `int16` diretamente:**
`float32` é o espaço lógico do domínio — valores normalizados em `[0, 1]` com sentinela
`-1.0f`. A quantização para int8/int16 é responsabilidade do `V2IndexSearcher.quantizeQuery()`,
que conhece o `dtype` e o `scale` do artefato. O parser não deve conhecer detalhes de
armazenamento. Parsear em int16 economizaria ~50–100 ns de aritmética por requisição —
negligenciável frente ao p99 de ~40 ms.

**Alocações residuais no `V2IndexSearcher.search()` (próxima micro-otimização):**
Após o parser, o caminho quente ainda aloca por requisição:
- `quantizeQuery()` → `new int[14]`
- `toI16Query()` → `new short[14]`
- `rankClusters()` → `new long[K]` + `new int[K]` (~16 KB para K=1024)

Padrão de correção: `ThreadLocal<SearchState>` em `V2IndexSearcher`, exatamente como o
`ParseState` no parser. Candidato à Issue 08 ou parte da Issue 07 (benchmark rigoroso).

> ⚠️ **Pré-condição:** este `ThreadLocal<SearchState>` só é seguro **depois** de desligar
> as virtual threads (Opção 1, ver `v4/07-postmortem-parser-virtual-threads.md`). Sob
> virtual-thread-por-requisição ele reproduziria a mesma regressão do `ParseState`. Com
> pool fixo de platform threads o padrão reaproveita e fica correto — mas a ordem importa:
> corrigir o modelo de threads primeiro.

### V4-D: Insertion sort para K=5 — ✅ JÁ ENTREGUE NA V3-D

> Antecipado para a V3 e entregue (commit do V3-D). Detalhes em
> [`v3/03-busca-sem-alocacao.md`](v3/03-busca-sem-alocacao.md). Fora do escopo da V4.

### V4-E: Custom HTTP server NIO — ➡️ MOVIDO PARA PERTO DA V6

> Reescrita de ~500 linhas cujo payoff é **latência** — que a V3 já saturou. O ganho
> de latência que sobra está no V6 (fd-passing, -80% p99 no arthurd3) e no V5 (GraalVM,
> zero JIT warmup). Além disso o servidor NIO é **pré-requisito do fd-passing** (precisa
> de `injectChannel()` para receber fds via `SCM_RIGHTS`). Faz mais sentido junto da V6
> do que solto na V4. A técnica está preservada na seção V6.

---

## V5: Opus — GraalVM Native Image + PGO

**Objetivo:** eliminar JVM do contêiner, liberar ~65MB de RAM para Page Cache.

| Dimensão | JVM atual | GraalVM Native |
|---|---|---|
| RAM de repouso | ~80MB | ~15MB |
| Page Cache disponível | ~195MB | ~260MB (+65MB) |
| Startup | segundos | milissegundos |
| JIT warmup | sim | zero (AOT) |

**Nota crítica: usar PGO (Profile-Guided Optimization).**
arthurd3 usa `default.iprof` no build do Native Image. Sem PGO, o ganho é menor.
Processo:
1. Rodar a app com agent de instrumentação: `native-image --pgo-instrument`
2. Executar K6 smoke para gerar o perfil
3. Recompilar com o perfil: `native-image --pgo=default.iprof`

### V5-0: ThreadLocal\<SearchState\> no V2IndexSearcher

O hot path ainda aloca por requisição mesmo após o parser V4-C:
- `quantizeQuery()` → `new int[14]`
- `toI16Query()` → `new short[14]`
- `rankClusters()` → `new long[K]` + `new int[K]` (~16 KB para K=1024)

Padrão idêntico ao `ParseState` do parser: `ThreadLocal<SearchState>` no
`V2IndexSearcher`. Com o pool fixo de platform threads, o ThreadLocal
reutiliza de verdade — zero `new` no hot path de busca. Reduz GC pressure antes
do build native, onde não há JIT para compensar alocações extras.

> ⚠️ **Pré-condição:** virtual threads desligadas. Com VTs o padrão
> reproduziria a regressão do `ParseState`. Confirmar Issue 07 antes de implementar.

**Caminho de implementação do GraalVM:**
1. Adicionar `native-maven-plugin` ao `pom.xml`
2. Rodar Tracing Agent com K6 smoke (captura reflection/resources de Javalin)
3. Gerar `reflect-config.json`, `resource-config.json` em `META-INF/native-image`
4. Novo Dockerfile: build com `ghcr.io/graalvm/native-image-community:25`, runner `distroless`
5. Adicionar loop de PGO (instrumento → smoke → recompila)
6. Validar `V2QualityGuardTest` e smoke K6

**Nota:** V4-C eliminou Jackson — principal fonte de reflection. Javalin ainda está
presente; o Tracing Agent captura o que resta.

**Referências:**
- arthurd3: binário final de 12MB, score 5.731 partindo de ~4.393 sem Rust

### V5-1: HAProxy L4 (TCP mode)

Trocar o Nginx pelo HAProxy operando em modo TCP puro (L4). O Nginx em configuração
padrão opera em L7: reconstrói o HTTP inteiro, faz parse dos cabeçalhos e abre uma
nova conexão para o backend — trabalho que consome ~0.3 vCPU do orçamento global de
1.5 vCPU da stack. O HAProxy em modo TCP é um "cano burro": recebe pacotes na porta
9999 e distribui por round-robin sem tocar no payload.

**Custo:** apenas config — `docker-compose.yml` + `haproxy.cfg`. Sem mudança em Java.

```haproxy
# haproxy.cfg
frontend rinha
    bind *:9999
    mode tcp
    default_backend apis

backend apis
    mode tcp
    balance roundrobin
    server api1 api1:8080 check
    server api2 api2:8080 check
```

**Limitação:** HAProxy L4 ainda aceita e entrega cada conexão TCP — ocupa um fd e
ciclos de CPU por conexão. O salto para fd-passing (V6) elimina esse overhead por
completo: o LB transfere o fd do cliente para a API via `SCM_RIGHTS` e sai do caminho.
O V5-1 é um degrau intermediário; o ganho definitivo de infra está na V6.

---

## V6: Pontifex — File descriptor passing (maior salto de infra)

Substituir nginx/HAProxy por um LB minimal que passa client fds via SCM_RIGHTS.

**arthurd3 Onda 30:** HAProxy → Rust LB fd-passing deu **-80% de p99**
(2.65ms → 1.86ms). Maior ganho único de infra em toda a jornada dele.

### Pré-requisito desta versão: servidor HTTP NIO custom (ex-V4-E)

Recebido da V4 — só faz sentido aqui, porque é onde a latência volta a pagar e
porque o fd-passing **depende** dele (precisa injetar fds recebidos num Selector
próprio, coisa que o Javalin/Jetty não expõe). Substituir Javalin por NIO reactor
(~500 linhas, single-threaded — elimina context switches com 0.45 vCPU). Confirmado
por arthurd3 e AndDev741. Micro-otimizações do arthurd3 a incorporar:

- `selectNow()` antes de `select()` — evita syscall quando dados já buffered
- Inline response write — escreve a resposta no path de leitura (elimina um `epoll_wait`)
- `injectChannel()` — registra no Selector os fds recebidos do LB via `recvmsg()`

**Pré-requisito do servidor NIO:** o V4-C (parser JSON custom), para desacoplar
completamente do framework. Se o V4-C não tiver entrado na V4, entra aqui antes do
servidor.

**Mecanismo:**
1. LB aceita TCP connection na porta 9999
2. Passa o file descriptor do client via `sendmsg()` com `SCM_RIGHTS`
3. API Java recebe o fd via `recvmsg()`, wraps em `SocketChannel`
4. Nenhum byte de payload passa pelo LB — só o fd é transferido

**Implementações de referência:**
- lucasmontano: C estático, 218 linhas, `-O3 -march=haswell -static -flto`
  - `TCP_DEFER_ACCEPT`: kernel aguarda dados antes de wakeup
  - Accept batching: 128 conexões por batch
- arthurd3: Rust ~50 linhas de lógica real, FFI exposta para Java

**Java recebendo fds:**
arthurd3 usa reflection para fazer `FdWrap.wrapFd(fd)` → `SocketChannel`.
Requer `--add-opens` no GraalVM ou acesso via sun.nio interno.

**CPU affinity:** lucasmontano pina lb nos CPUs 2-3 e workers nos CPUs 0 e 1.
Configurável via `cpuset_cpus` no docker-compose.

---

## V7: Sapientia — Knowledge distillation (longo prazo)

Replicar a abordagem do Papagaio em Java: MLP router que elimina IVF para 96%
das queries.

**Conceito:**
MLP 3 camadas (14→64→64→3, ~5K parâmetros, ~20KB) treinado sobre o ground truth
da busca exata. Classifica cada query em A-Legítimo, A-Fraude, ou B (incerto).

- **Fast path (96.5%):** MLP decide em ~1µs sem tocar no índice
- **Slow path (3.5%):** IVF ou KD-tree sobre subconjunto de fronteira (~213k refs)

**Acoplamento numérico crítico** (lição do Papagaio):
O vetor de entrada do MLP deve usar exatamente o mesmo arredondamento `round4`
que o data generator aplica antes de calcular os k-NN de referência. Uma
divergência de arredondamento numa dimensão pode flip um tie-break e introduzir
erros de classificação. Documentar e testar cada constante.

**Implementação Java:**
- Treino: Python/PyTorch, exporta pesos como `float[]` binário
- Inferência: multiplicações de matriz + GELU — ~200 FLOPs, trivial em Java
- Sem dependência de framework ML em runtime

**Pré-requisito:** V4-A (bbox pruning) estabilizado. O MLP é treinado sobre o
resultado da busca exata — sem busca exata como ground truth, o treinamento
é comprometido.

---

## Sequência recomendada

```
V2 finalizada (K=1024, nprobe=4)
    └── Issue 05: congelar envelope operacional
         │
         ├── V3 Celeritas (velocidade) — ✅ ENTREGUE
         │   ├── V3-0  pin K=1024/nprobe=4      ← pré-requisito de medição
         │   ├── V3-A  page pre-warming        ← elimina variância cold/hot
         │   ├── V3-B  respostas pré-serializadas ← elimina alocação na resposta
         │   ├── V3-D  insertion sort sem alocação ← do V4-D; mata garbage da busca
         │   └── V3-C  nginx stream + Unix sockets  ← ❌ removido/adiado
         │   (resultado: score_p99 saturou; detecção é a folga que sobra)
         │
         └── V4 Veritas (verdade) — busca exata ⭐ ✅ ENTREGUE
                   (0 FP/FN, score_det=3000 teto, final_score~4394)
                    │
                    ├── V4-0  diagnóstico de percentis (p50/p95)  ← ✅ entregue
                    ├── V4-A  bounding-box pruning (+ migração i16) ⭐ ← ✅ entregue
                    ├── V4-B  IVF repair          ← ❌ cortado (subsumido por A)
                    ├── V4-C  custom JSON parser  ← ✅ entregue (regressão VT corrigida)
                    ├── V4-D  insertion sort K=5  ← ✅ entregue na V3-D
                    ├── V4-E  custom HTTP server NIO ← ➡️ movido para a V6
                    │
                    ├── [GATE ✅ CRUZADO — final_score ~4394, score_det=3000 (teto)]
                    │
                    ├── V5 Opus (obra-prima) ← PRÓXIMO
                    │      V5-0  ThreadLocal<SearchState> ← elimina últimas alocações
                    │      V5-1  HAProxy L4 (TCP mode)    ← troca de config, libera ~0.3 vCPU
                    │      GraalVM Native Image + PGO
                    │      (V4-C eliminou Jackson; Javalin coberto pelo Tracing Agent)
                    │
                    ├── V6 Pontifex (pontes)
                    │      servidor NIO custom (ex-V4-E) + fd-passing LB (C/Rust)
                    │      (-80% p99 confirmado por arthurd3)
                    │
                    └── V7 Sapientia (sabedoria)
                           Knowledge distillation ← longo prazo
```

**Gate de decisão principal — ✅ CRUZADO:**
A V4 levou `score_det` de ~1.335 para **3.000** (teto do sistema), com `final_score`
~4.394 — território top-10 Java. A V5 (GraalVM + PGO) e a V6 (servidor NIO +
fd-passing, -80% p99) são os próximos alavancadores para perseguir o top-5.
