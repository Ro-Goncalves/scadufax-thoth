# V4-C Post-Mortem: ThreadLocal × Virtual Threads — por que o parser "zero-alocação" degradou a aplicação

Data: 2026-06-05. Envelope: K=1024 / nprobe=4, dtype i16.

Este documento é a autópsia de uma regressão. O `FraudRequestParser` (Issue 06,
documentado em [`06-parser-json-custom.md`](06-parser-json-custom.md)) foi escrito para
**eliminar a alocação de memória no caminho quente** — e o código de parsing está
correto. Mesmo assim, quando entrou no benchmark, a aplicação **despencou**: o p99 saiu
de ~40 ms para mais de **1 segundo**, e o `final_score` ficou **negativo**.

A causa não é um bug de parsing. É uma colisão arquitetural entre duas decisões que,
isoladas, parecem boas: usar `ThreadLocal` para reaproveitar buffers, e usar **virtual
threads** no servidor HTTP. Juntas, elas se cancelam — e no ambiente apertado da rinha
(0,45 CPU, 165 MB, GC serial) o tiro sai pela culatra.

O texto é escrito para quem está começando: cada conceito (virtual thread, `ThreadLocal`,
stop-the-world, backpressure) é explicado antes de ser usado. Ao final, há a decisão de
correção e por que ela está alinhada com o destino do projeto (V5/V6).

---

## Parte I — O sintoma: o que o benchmark mostrou

Mesmo envelope, mesmo protocolo (boots frios completos), só trocando o caminho de parse
de entrada (Jackson → `FraudRequestParser`):

| Métrica (i16, K=1024/nprobe=4) | Antes (Jackson) | Depois (parser custom) |
|---|---|---|
| **p50** (mediana) | 0,91 ms | **11,16 ms** |
| **p95** | 3,29 ms | **498 ms** |
| **p99** | 40,5 ms | **1116 ms** (máx. 2002 ms) |
| **final_score** | **+4394** | **−1329** |
| **falhas HTTP** | 0 % | **4,12 %** |
| FP / FN (detecção) | 0 / 0 | **0 / 0** |

Três leituras saltam dessa tabela, e cada uma é uma pista:

### Pista 1 — A detecção continua perfeita (0 FP / 0 FN)

O parser **não tem bug de lógica**. Ele produz exatamente o mesmo vetor que o caminho
antigo (o teste de contrato já garantia isso; o benchmark confirma: zero falsos positivos,
zero falsos negativos). Então o problema é **100 % latência/GC**, não correção. Isso é
importante: não vamos jogar o parser fora — vamos consertar o ambiente em que ele roda.

### Pista 2 — O p50 (a mediana!) piorou 12×

Esta é a pista mais forte e a mais contraintuitiva. Uma pausa de GC ocasional afeta
**só a cauda** (p99): a maioria das requisições passa rápido, e umas poucas, azaradas,
pegam a pausa. Mas aqui **a mediana** — a requisição *típica* — saiu de 0,9 ms para 11 ms.

Quando *toda* requisição fica lenta, não é "uma pausa aqui e ali": é a **CPU inteira
sendo roubada o tempo todo**. Algo está consumindo o processador continuamente, por baixo
de cada requisição. Guarde isso: aponta para GC constante, não para GC esporádico.

### Pista 3 — A "impressão digital" do timeout

O p99 máximo foi **2002 ms**. O k6 (gerador de carga) está configurado com
`timeout: '2001ms'`. Os dois números coincidirem **não é acaso**: significa que a cauda
da distribuição não é "parsing devagar" — são requisições que **bateram no teto do
timeout e viraram erro HTTP**. O servidor ficou tão preso que as respostas não chegaram
a tempo.

> **Variância entre execuções.** Em alguns boots o sistema só fica lento (ex.: um run com
> p99 = 807 ms, 29 erros HTTP); em outros ele entra em colapso (p99 = 2002 ms, detecção
> caindo pela metade por puro throughput perdido). Essa instabilidade — às vezes aguenta,
> às vezes desaba — é a assinatura clássica de um sistema **no limiar de saturação de
> memória**. Não é ruído de host: é o próprio código no limite.

---

## Parte II — Os dois conceitos que colidiram

Para entender a causa, você precisa de dois conceitos. Vamos do zero.

### O que é uma *virtual thread*

Uma **thread** é uma linha de execução: um pedaço de trabalho que a CPU toca. A thread
tradicional do Java (*platform thread*) é cara — cada uma reserva ~512 KB a 1 MB de pilha
(*stack*) de memória e é gerenciada pelo sistema operacional. Por isso você não cria
milhões delas; você cria um **pool** (um conjunto fixo, digamos 8 ou 200) e as reaproveita.

A **virtual thread** (Projeto Loom, Java 21+) é o oposto: é barata, leve, e a JVM pode
criar **milhões** delas. A ideia é: para cada requisição HTTP, crie uma virtual thread
nova, use-a, **descarte-a**. Não há pool — há um fluxo de threads efêmeras.

```
Platform threads (pool):     Virtual threads (uma por requisição):
┌──────────────────┐         req1 → [VT nova] → descarta
│ 8 threads fixas, │         req2 → [VT nova] → descarta
│ reaproveitadas   │         req3 → [VT nova] → descarta
│ por toda a vida  │         req4 → [VT nova] → descarta
└──────────────────┘         (cada uma vive só o tempo de UMA requisição)
```

No nosso `JavalinBootstrap`, havia exatamente isto:

```java
javalinConfig.concurrency.useVirtualThreads = true;
```

Cada `POST /fraud-score` roda em sua **própria virtual thread nova e descartável**.

### O que é um `ThreadLocal`

Um `ThreadLocal<T>` é uma variável cujo valor é **privado de cada thread**. Pense num
**armário pessoal**: cada funcionário (thread) tem o seu, e ninguém mexe no do outro.

```java
private static final ThreadLocal<float[]> QUERY_VEC =
        ThreadLocal.withInitial(() -> new float[14]);
// ...
float[] v = QUERY_VEC.get();   // primeira vez na thread: cria. Depois: devolve o mesmo.
```

A primeira vez que uma thread chama `.get()`, o `withInitial` roda e **cria** o objeto.
Da segunda chamada em diante, **na mesma thread**, devolve o objeto já criado — sem alocar
nada. Esse é o truque de "zero-alocação": você cria o buffer **uma vez por thread** e o
reaproveita por todas as requisições que aquela thread atender.

O parser apostou pesado nisso. Ele guarda num `ThreadLocal<ParseState>` um objeto gordo
de rascunho — vários arrays reutilizáveis:

```java
private static final class ParseState {
    final double[]  dbl   = new double[1];
    final int[]     ints  = new int[1];
    final boolean[] bools = new boolean[1];
    final long[]    ts    = new long[3];
    final long[]    merch = new long[32];   // ← 256 bytes só aqui
    final byte[]    keyBuf = new byte[32];
    final int[]     keyLen = new int[1];
}
private static final ThreadLocal<ParseState> STATE =
        ThreadLocal.withInitial(ParseState::new);
```

A intenção: alocar esse `ParseState` **uma vez** e reusar para sempre. Tão raro que pode
até ser gordo — não importa, é uma vez só.

---

## Parte III — A colisão: por que o truque se desfaz

Agora junte as duas peças. A pergunta-chave é:

> **Com quantas requisições uma thread vive?**

- **Com platform threads (pool):** uma thread vive "para sempre" e atende **milhares** de
  requisições. O `withInitial` roda **uma vez**; depois é só reaproveitamento. Zero
  alocação. **O truque funciona.**

- **Com virtual threads (uma por requisição):** uma thread vive **uma requisição** e
  morre. O "armário pessoal" é montado e jogado fora a cada vez. O `withInitial` roda
  **em toda requisição**. **O truque se desfaz por completo.**

Sob virtual threads, então, cada `POST /fraud-score` faz:

```
QUERY_VEC.get()  → cache vazio (thread nova) → aloca float[14]      + entrada no mapa
STATE.get()      → cache vazio (thread nova) → aloca ParseState     + entrada no mapa
                                               (7 arrays internos, ~450 bytes de scratch)
```

Em vez de **zero** alocação, viramos **alocação garantida** — e de um objeto
**deliberadamente gordo**, justamente o que se queria evitar. O parser foi otimizado para
um modelo de threads (pool fixo) que a aplicação **não usa**.

### O detalhe que transforma "ruim" em "catastrófico": retenção

Alocar um objeto e descartá-lo rápido é barato — o coletor de lixo (GC) limpa isso de
forma eficiente na "geração jovem". Se fosse só isso, o parser seria *um pouco* pior, não
**28× pior**. Falta um ingrediente: **retenção**.

Quando um valor está num `ThreadLocal`, ele fica **preso à thread** enquanto a thread
viver. Com virtual threads, a thread vive o tempo todo da requisição — incluindo a parte
**lenta** (a busca vetorial). Então o `ParseState` não morre quando o parse acaba; ele
fica vivo, agarrado à virtual thread, até a requisição inteira terminar.

Agora misture com o **modelo de carga**. O k6 usa `ramping-arrival-rate` mirando
**900 requisições por segundo** — ou seja, ele despeja requisições nesse ritmo
**independ­entemente** de o servidor dar conta. Com apenas 0,45 CPU, o servidor **não dá
conta**. As requisições se **acumulam**. Como cada uma é uma virtual thread, e cada
virtual thread segura seu `ParseState`...

```
1 virtual thread em voo   →   1 ParseState vivo
500 virtual threads em voo →  500 ParseStates vivos  +  500 float[14]  +  500 mapas
```

A memória viva cresce **proporcionalmente ao número de requisições empilhadas**. O caminho
Jackson anterior **não fazia isso**: os objetos do Jackson morriam ao fim do parse, antes
da parte lenta; uma requisição empilhada esperando a busca não segurava esse peso todo.
O `ThreadLocal` inverteu essa propriedade — e foi isso que quebrou.

---

## Parte IV — Por que vira catástrofe *neste* ambiente

Retenção crescente sozinha já é ruim. O ambiente da rinha transforma "ruim" em "colapso".
Veja o que a aplicação tem de orçamento (do `Dockerfile` e do `docker-compose.yml`):

```
-Xms50m -Xmx80m        → heap de no máximo 80 MB
-XX:+UseSerialGC       → coletor de lixo serial (single-thread, "para o mundo")
cpus: '0.45'           → menos de meia CPU
memory: '165MB'        → container inteiro em 165 MB
```

Dois desses são gasolina no fogo:

**`UseSerialGC` — "parar o mundo".** O GC serial é o mais simples: quando precisa limpar,
ele **congela todas as threads** (*stop-the-world*), varre, e só então libera. Não há
coleta concorrente. Quanto mais lixo você gera, mais vezes ele congela tudo. É como parar
a fábrica inteira para varrer o chão — e fazer isso o tempo todo.

**Heap de 80 MB.** A área onde objetos novos nascem (a "geração jovem") é uma fração
desses 80 MB. Com alta taxa de alocação **e** retenção crescente (centenas de
`ParseState` vivos), essa área enche num piscar; o GC roda sem parar; objetos que deveriam
morrer jovens sobrevivem (porque ainda estão presos às virtual threads em voo) e são
**promovidos** para a geração velha; quando a velha enche, vem o **full GC** serial — a
pausa mais longa e mais cara que existe.

**0,45 CPU.** Cada pausa de GC, medida em tempo de relógio, fica **mais longa** porque há
menos CPU para terminá-la. E a CPU que o GC consome é CPU que **some** do trabalho real —
por isso até a requisição mediana (p50) ficou 12× mais lenta.

### A cadeia causal, ponta a ponta

```
useVirtualThreads = true
        │  (1 virtual thread nova por requisição)
        ▼
ThreadLocal NUNCA reaproveita  →  aloca ParseState gordo + float[14] por requisição
        │
        ▼
ramping-arrival-rate 900 rps  +  0,45 CPU  →  virtual threads se EMPILHAM
        │  (cada uma retém seu ParseState até a busca terminar)
        ▼
memória viva ∝ nº de requisições em voo  →  heap de 80 MB satura
        │
        ▼
SerialGC dispara sem parar (stop-the-world)  →  rouba a CPU de TODOS
        │
        ▼
p50 12× pior  +  cauda batendo no timeout de 2001 ms  →  erros HTTP
        │
        ▼
score de p99 vira penalidade  →  final_score NEGATIVO
```

### "Mas o Jackson alocava mais e era mais rápido!"

Verdade — e é justamente o que prova que o vilão não é "alocar", é **reter sem
backpressure**. Três diferenças explicam:

1. **Retenção.** Os objetos do Jackson morrem ao fim do parse, **antes** da busca lenta.
   O `ParseState` no `ThreadLocal` fica vivo **durante** a busca, agarrado à virtual
   thread. Sob acúmulo de requisições, isso multiplica a memória viva pelo número de
   threads em voo — o Jackson não tinha esse efeito.
2. **Sem freio (backpressure).** Virtual threads aceitam trabalho **ilimitado**: a 901ª
   requisição vira a 901ª thread, e empilha. Um pool fixo de threads, ao contrário,
   *recusa* educadamente — segura a requisição na fila do socket — e impede o colapso.
3. **Objeto gordo.** O `ParseState` foi desenhado para ser alocado **raramente**, então
   ninguém se preocupou com seu tamanho (`long[32]`, `byte[32]`, …). Alocá-lo a **cada**
   requisição paga esse peso a cada vez.

### A análise que estava no próprio código

A [seção 7 do doc do parser](06-parser-json-custom.md) **previu o risco** do Loom, mas
**errou a conta**:

> *"Para ~900 req/s com latência de ~1 ms, há talvez 5–10 threads virtuais ativas por
> vez — ~5–10 instâncias de ParseState, totalizando alguns kilobytes. Completamente
> negligenciável."*

Dois erros:

- **"5–10 threads ativas"** pressupõe que a latência *continua* ~1 ms. Mas é exatamente
  quando a latência sobe que as threads se acumulam — não 5–10, e sim **centenas**. A
  premissa se autodestrói sob carga.
- **"Alguns kilobytes, negligenciável"** mede a coisa errada. O que mata não é a memória
  *viva* num instante; é a **taxa de lixo** (garbage por segundo) que dispara o GC
  stop-the-world. Contar bytes vivos ignora o custo real, que é de CPU gasta coletando.

A lição para o júnior: **`ThreadLocal` para "zero-alocação" é uma aposta na hipótese
"minhas threads são reaproveitadas".** Se você não pode provar essa hipótese, o padrão
pode se inverter e custar mais do que economiza. É a recomendação explícita da
[JEP 444 (Virtual Threads)](https://openjdk.org/jeps/444): **não** use `ThreadLocal` para
cachear objetos caros sob virtual threads.

---

## Parte V — A dúvida do `float`: o parser produz `float[14]`, mas vamos para int8/int16

Pergunta natural: o índice é quantizado (int8 ou int16) para caber na memória. O parser
produz `float[14]`. Isso é um descasamento que vai dar problema?

**Não.** Pelo contrário — é a fronteira **correta**, e aqui está o porquê.

O `float[14]` é o **espaço lógico** do domínio: 14 valores normalizados em `[0, 1]`, mais
a sentinela `-1.0f` (que marca "sem transação anterior"). É a *semântica* do vetor. A
**quantização** — converter esse float para o número inteiro que o índice usa — é um
detalhe de **armazenamento**, e pertence a quem conhece o `dtype` e a `scale` do artefato:
o `V2IndexSearcher`.

```java
// V2IndexSearcher.quantizeQuery(float[] v) → int[]
if (val == -1.0f)                                  // sentinela tratada ANTES de escalar
    q[d] = (dtype == I16) ? Short.MIN_VALUE : Byte.MIN_VALUE;
else
    q[d] = Math.round(val * scale);                // mesma scale com que o índice foi construído
// e então: toI16Query(qi) → short[]   ou   toI8Query(qi) → byte[]
```

Repare que **a `scale` vem do header do artefato** — a mesma usada para construir o índice.
Query e índice são quantizados pela mesma régua; é isso que mantém as distâncias
comparáveis. Consequências práticas:

- **O parser é agnóstico de dtype.** Migrar entre i8 e i16 troca um `if` e a `scale` lida
  do header — **não toca uma linha do parser**.
- **Separar é o que viabiliza V5/V6.** Se o parser cuspisse int16 direto, ele ficaria
  acoplado ao formato do índice; cada mudança de `scale`/`dtype` exigiria mexer no parser.
  A versão atual o mantém desacoplado — pré-requisito declarado do servidor NIO da V6.
- **O custo é desprezível.** Fazer a normalização em float e quantizar uma vez no fim
  custa ~50–100 ns por requisição. Irrelevante frente a tudo o mais.

> **Uma ressalva — mas é sobre o *teste*, não sobre o código.** O teste de contrato
> compara os floats do parser com os do caminho antigo usando `delta = 1e-4`. Acontece que,
> no i16 com `scale = 10000`, **um passo de quantização vale exatamente `1/10000 = 1e-4`**.
> Ou seja, a tolerância do teste é da ordem de *um degrau* de quantização: em valores bem
> na fronteira, o parser e o Jackson poderiam, em teoria, arredondar para inteiros
> vizinhos. Na prática o benchmark deu 0 FP / 0 FN (não materializou), mas **ao mexer em
> dtype/scale no futuro, faça o teste de contrato comparar os vetores já quantizados (os
> `int`), não só os floats.** Blinda contra um *off-by-one* silencioso.

---

## Parte VI — A decisão: Opção 1 (platform threads) vs Opção 2 (manter VT)

Há duas famílias de correção:

| | **Opção 1 — desligar virtual threads** | **Opção 2 — manter VT, tirar o `ThreadLocal`** |
|---|---|---|
| O que muda | Pool pequeno de platform threads | Alocar o estado localmente em `parse()` |
| `ThreadLocal` | Permanece e **volta a funcionar** (reaproveita) | É removido |
| Zero-alocação | **Sim** (pool reaprovieta os buffers) | Não (aloca por requisição, mas sem reter) |
| Esforço | 1 ponto (config do bootstrap) | Refatorar parser + handler |

A escolha é **Opção 1**, e por três ângulos que convergem:

### Ângulo 1 — A trajetória do projeto (V5 e V6)

O roadmap é explícito sobre o futuro:

- **V5 (Opus): GraalVM Native Image.** Compila a aplicação para binário nativo (sem JIT
  warmup). Platform threads e `ThreadLocal` são triviais nesse modelo; e o parser custom
  já ajuda (remove reflexão do Jackson, simplificando a configuração do Native Image).
- **V6 (Pontifex): servidor HTTP NIO custom + fd-passing.** Aqui está o ponto decisivo. A
  V6 **substitui o Javalin/Jetty** por um *reactor* NIO próprio (pré-requisito do
  fd-passing, que o Jetty não expõe). Um reactor NIO roda sobre um **event-loop com um
  pool pequeno de worker threads de plataforma** — exatamente o modelo onde `ThreadLocal`
  reaproveita. **Virtual threads não fazem parte desse desenho.**

Ou seja: a Opção 1 não é só o melhor remendo de hoje — é o **modelo de concorrência de
destino** do projeto. Adotá-la agora é dar o primeiro passo na direção da V6. A Opção 2
investiria esforço em fazer o parser conviver com virtual threads, um modelo que a V6
**vai apagar** — trabalho jogado fora.

### Ângulo 2 — A análise de mercado

Os competidores da rinha que tentaram virtual threads relataram que **atrapalhou**. Isso
tem base técnica sólida. Virtual threads brilham quando há **muita espera de I/O**
(milhares de conexões paradas esperando banco de dados, rede). O nosso caso é o oposto:

- workload **CPU-bound** (busca vetorial em memória, puro cálculo);
- CPU **severamente limitada** (0,45);
- **sem I/O bloqueante** (não há banco, não há chamada externa);
- heap pequeno (cada thread em voo retendo estado pesa).

Nesse perfil, virtual threads só adicionam overhead de escalonamento, **removem o
backpressure** (empilham até estourar — foi o que medimos) e **quebram o `ThreadLocal`**.
Um pool pequeno de platform threads casa a concorrência com a CPU disponível.

### Ângulo 3 — A evidência empírica

É o próprio benchmark deste documento. A regressão aconteceu **com** virtual threads; o
caminho saudável (Jackson, 40 ms) rodava sob a mesma configuração de VT, mas **sem** o
padrão `ThreadLocal` que colide com ela.

### O bônus da Opção 1: ela resgata o parser

Com um pool fixo de platform threads, o `ThreadLocal` **finalmente cumpre o que prometia**:
o `ParseState` e o `float[14]` são criados uma vez por thread e reaproveitados por todas as
requisições. A premissa "zero-alocação após warmup" deixa de ser uma ficção e vira
verdade. Não desperdiçamos o trabalho da Issue 06 — nós o **destravamos**.

---

## Parte VII — A correção aplicada

A mudança é cirúrgica, concentrada no `JavalinBootstrap`:

```java
// ANTES
javalinConfig.concurrency.useVirtualThreads = true;

// DEPOIS
javalinConfig.concurrency.useVirtualThreads = false;
javalinConfig.jetty.threadPool = new QueuedThreadPool(16, 4); // (maxThreads, minThreads)
```

**Por que não basta `useVirtualThreads = false` sozinho?** Porque, sem um pool explícito,
o Jetty usa o default `max = 200` threads. Mas platform threads têm **pilhas reais** (~512
KB a 1 MB cada): 200 delas reservariam ~100–200 MB **só de stacks** — estourando o
container de 165 MB. Virtual threads não tinham esse custo (pilha cresce sob demanda);
platform threads têm. Por isso **precisamos** dimensionar o pool.

**Por que `16 / 4`?** O trabalho é CPU-bound numa CPU que vale ~0,5. O número de threads
que efetivamente avançam trabalho ao mesmo tempo é ~1–2; o resto serve para a infra do
Jetty (1 acceptor + 1 selector com 1 CPU visível) e para absorver pequenas rajadas. Um
teto de 16 dá folga confortável para o Jetty não reclamar de "insufficient threads", sem
desperdiçar memória nem inflar a troca de contexto. É um ponto de partida conservador —
o número fino pode ser afinado no benchmark de validação (Issue 07).

**O que *não* muda:** os `ThreadLocal` do `FraudRequestParser` e do `SearchHandler`
**permanecem**. Eles não eram o erro em si — eram um padrão correto rodando sob o modelo
de threads errado. Com o pool fixo, eles passam a funcionar como projetados. Os comentários
que falavam em "thread virtual" foram atualizados para "thread do pool", para o código não
mentir sobre o próprio modelo.

---

## Parte VIII — Lições para levar adiante

1. **`ThreadLocal` para zero-alocação assume um pool fixo de threads.** Sempre que usar o
   padrão, escreva ao lado a suposição: *"isto só economiza se as threads forem
   reaproveitadas"*. Sob virtual threads, o padrão se inverte.

2. **Valide a premissa de *performance* sob o servidor real, não só em teste unitário.** O
   teste de contrato provou que o parser é *correto* — e estava certo. Mas "zero-alocação"
   é uma afirmação de *runtime*, sob o modelo de threads de produção, e nunca foi medida
   antes de fechar a issue. Critério de aceite de performance precisa de benchmark, não de
   teste de unidade.

3. **Diagnostique pela forma da distribuição.** Foi o **p50 subir junto** (não só o p99)
   que separou "pausa de GC esporádica" de "CPU roubada continuamente". E foi o **p99 máx.
   ≈ timeout** que revelou que a cauda eram erros HTTP, não lentidão. Olhe os percentis
   inteiros, não só um número.

4. **⚠️ Não propague o padrão.** O `roadmap.md` propõe replicar o mesmo
   `ThreadLocal<SearchState>` no `V2IndexSearcher` (candidato à Issue 08), "exatamente como
   o `ParseState` no parser". **Enquanto a aplicação rodar virtual threads, isso
   multiplicaria o problema.** Depois da Opção 1 (pool fixo), o padrão volta a ser seguro —
   mas a ordem importa: corrigir o modelo de threads **primeiro**.

---

## Apêndice — Como confirmar (checklist de reprodução)

Ao rodar o benchmark de validação (5 boots frios, Issue 07), procure por estes sinais de
que a correção pegou:

- [ ] **p50 volta para ~1 ms.** Se a mediana continuar inflada, a CPU ainda está sendo
      roubada — investigue o GC.
- [ ] **p99 máx. bem abaixo de 2001 ms.** Sem requisições batendo no timeout = sem o
      colapso de retenção.
- [ ] **0 erros HTTP** no resumo do k6.
- [ ] **`final_score` positivo** e na faixa pré-regressão (~4300+ no i16).
- [ ] **Detecção intacta** (0 FP / 0 FN) — deve continuar, já que o parser não mudou.
- [ ] **Memória estável** sob carga: o uso não deve crescer com o número de requisições em
      voo (era o sintoma da retenção).

> Veredito desta investigação: a regressão é arquitetural (modelo de threads), não de
> parsing. A correção é a Opção 1 — desligar virtual threads e usar um pool pequeno de
> platform threads —, escolhida por convergência de três sinais (trajetória V5/V6, mercado
> e benchmark) e validada por reaproveitar, em vez de descartar, o trabalho do parser.
> Números finais ficam para a Issue 07.
