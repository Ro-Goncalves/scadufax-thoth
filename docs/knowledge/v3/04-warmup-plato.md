# Warmup JIT: detecção de platô

> Esta entrega substitui o warmup de **50 buscas fixas** por um warmup que para
> sozinho quando o motor esquenta. Foi o que estabilizou a partida da V3.

## O problema

A V2 fechou com p99 de ~36ms, mas durante a V3 o benchmark dava resultados que
pulavam de **74ms a 195ms** entre execuções do mesmo código. A detecção (quais
transações eram fraude) era idêntica em toda execução — só a **latência** variava.

Quando só a latência varia e o resultado lógico não, o problema quase nunca é o
algoritmo. É o **motor ainda frio**: a JVM não tinha terminado de se otimizar
quando o tráfego começou.

---

## O que é o JIT e por que o motor "esquenta"

Java não roda o seu código direto como código de máquina. No começo, ele
**interpreta** o bytecode — lê instrução por instrução, devagar. Conforme um método
é chamado muitas vezes, a JVM percebe que ele é "quente" e aciona o **JIT**
(*Just-In-Time compiler*): um compilador que, em tempo de execução, traduz aquele
método para código de máquina nativo e rápido.

O JIT tem níveis (*tiers*):

```
Interpretador  →  C1 (rápido de compilar, código mediano)  →  C2 (lento de compilar, código ótimo)
   devagar              ~1.500 chamadas                            ~10.000 chamadas
```

O **C2** é o que entrega a velocidade final — e ele só entra em ação por volta de
**10.000 chamadas** de um método. Antes disso, o método roda numa versão mais lenta.

O warmup antigo fazia **50 buscas**. Isso nem chega perto das 10.000 chamadas que o
C2 precisa. Resultado: quando o `/ready` liberava o tráfego, o hot path da busca
ainda estava na versão lenta. A compilação para C2 acontecia **durante** a medição do
K6 — e roubava CPU das requisições enquanto compilava, criando picos de latência que
inflavam o p99.

```
Latência por busca conforme o motor esquenta:

us/busca
  │
  │ ●                         ← interpretado (frio)
  │  ●
  │   ●●                      ← C1 entrou
  │     ●●●
  │        ●●●●●              ← C2 entrou
  │             ●●●●●●●●●●●●  ← platô (motor quente)
  └────────────────────────────────► nº de buscas
       ↑                ↑
    parar aqui       parar aqui
    = motor frio     = motor quente
    (warmup de 50)   (detecção de platô)
```

---

## Por que não simplesmente "fazer mais buscas"?

A pergunta óbvia é: "se 50 é pouco, por que não fixar 20.000?". Porque o número
**certo depende do host**. Quantos núcleos tem, qual fatia de CPU o container recebe,
quão rápido o C2 dispara — tudo isso muda de máquina para máquina.

Um número fixo erra dos dois lados:

- **Baixo demais** → o motor ainda está frio quando o tráfego chega (o bug original).
- **Alto demais** → desperdiça tempo de partida aquecendo além do necessário.

A solução não é adivinhar o número. É **medir** e parar quando o motor parar de
esquentar.

---

## A solução: detecção de platô

A ideia: rodar as buscas em **janelas** (lotes de 2.000), medir a latência média de
cada janela, e parar quando essa média **parar de cair**. Latência que não cai mais =
C2 já fez seu trabalho = motor quente.

```
janela 1: 180 us/busca   (caindo muito)
janela 2: 120 us/busca   (caindo muito)
janela 3:  70 us/busca   (caindo)
janela 4:  52 us/busca   (caiu < 2%)  ← estável 1
janela 5:  51 us/busca   (caiu < 2%)  ← estável 2
janela 6:  51 us/busca   (caiu < 2%)  ← estável 3  →  PLATÔ, pode parar
```

Regra: se a melhora de uma janela para a outra for **menor que 2%**, contamos como
"estável". Depois de **3 janelas estáveis seguidas**, declaramos platô e paramos.

### Pisos e tetos de segurança

A detecção de platô sozinha tem dois riscos, então colocamos limites:

| Limite | Valor padrão | Por quê |
|---|---|---|
| Piso de buscas | 12.000 | Garante passar das ~10.000 chamadas do C2, mesmo que o ruído faça a latência parecer estável cedo |
| Teto de buscas | 400.000 | Num host onde a latência nunca platô claramente, não roda pra sempre |
| Teto de tempo | 25.000 ms | Limite absoluto da partida — para com o que já aqueceu |

Os três são configuráveis por variável de ambiente (`WARMUP_MIN_SEARCHES`,
`WARMUP_MAX_SEARCHES`, `WARMUP_MAX_MS`) — dá pra ajustar o orçamento de partida da
rinha sem recompilar.

### O `blackhole`

O laço de warmup faz buscas mas não usa o resultado para nada "visível". O JIT é
esperto: se ele percebe que o resultado é jogado fora, pode **apagar a busca inteira**
(otimização chamada *dead-code elimination*) — e aí não aquece nada.

Para impedir isso, somamos um pedaço de cada resultado num campo `static volatile`
chamado `blackhole`. O `volatile` força a JVM a realmente gravar o valor na memória,
então ela não pode provar que a busca é inútil e é obrigada a executá-la de verdade.

```java
localBlackhole += results.size();
if (!results.isEmpty()) {
    localBlackhole += results.get(0).distance();
}
// ... ao final:
blackhole = (long) localBlackhole;   // campo static volatile
```

---

## Como o `/ready` se beneficia disso de graça

O `WarmupService.warmup()` roda **de forma síncrona** dentro do
`JavalinBootstrap.create()`, **antes** de o servidor começar a aceitar conexões:

```
1. V2IndexSearcher instanciado (mmap aberto)
2. searcher.prewarm()        ← aquece as páginas do mmap (V3-A)
3. WarmupService.warmup()    ← aquece o JIT até o platô  ◄── esta entrega
4. Javalin ... start()       ← só agora o servidor sobe
5. /ready responde 200       ← tráfego liberado, motor já quente
```

Como o servidor só sobe depois do warmup terminar, **aumentar o warmup atrasa
automaticamente o `/ready`**. O custo do JIT sai inteiro da janela medida — que é
exatamente o objetivo.

---

## Resultado medido

K=1024 / nprobe=4, 5 boots frios completos, host quieto:

| Cenário | p99 | observação |
|---|---|---|
| Baseline V2 (1 amostra) | 36.95ms | melhor caso num host limpo |
| Fase V3, warmup de 50 (5 amostras) | 74–195ms | motor frio, variância enorme |
| **Warmup com platô (5 amostras)** | **17.5–52ms, mediana 29.55ms** | distribuição inteira desabou |

Quatro das cinco rodadas ficaram **abaixo** do baseline V2, e a mediana (29.55ms)
bateu o baseline. O `detection_score` foi idêntico até a 13ª casa decimal nas cinco
rodadas — confirmando que **toda** a variância anterior era cold start, nunca o
algoritmo.

---

## Onde está o código

| Artefato | Localização |
|---|---|
| Laço de platô | `bootstrap/WarmupService.java` — método `warmup()` |
| Constantes e env vars | `WarmupService.java` — `MIN_SEARCHES`, `MAX_SEARCHES`, `MAX_DURATION_MS` |
| Campo `blackhole` | `WarmupService.java` — campo `static volatile` |
| Chamada de orquestração | `JavalinBootstrap.java` — `WarmupService.warmup(...)`, antes de `start()` |