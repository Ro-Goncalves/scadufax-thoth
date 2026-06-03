# Próximo passo (proposta): diagnóstico de percentis

> Este documento **não é uma implementação** — é uma proposta para você ler com calma
> e decidir se vale a pena agora. É um experimento barato (mudar uma linha do K6) que
> responde a uma pergunta antes de a gente gastar esforço otimizando.

## De onde vem a pergunta

Depois do fix do warmup, as 5 rodadas ficaram assim no p99:

```
run1: 17.5ms   run2: 52.3ms   run3: 29.6ms   run4: 30.6ms   run5: 21.0ms
                 ↑ outlier
```

Quatro rodadas se agrupam entre 17 e 31ms, e uma (run2) saltou para 52ms. A detecção
é idêntica em todas, então não é o algoritmo. **De onde vem esse salto de 52ms?**

Existem duas explicações possíveis, e elas pedem **respostas diferentes**. Antes de
escolher uma cura, precisamos saber qual é a doença.

---

## Primeiro, o que é um percentil?

Imagine as 54.100 requisições do teste enfileiradas da mais rápida para a mais lenta.
Um percentil é "onde você corta a fila":

```
todas as requisições ordenadas por latência (rápida → lenta)
│■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■■│
                          ↑                  ↑     ↑
                         p50                p95   p99
                       (mediana)
```

- **p50 (mediana):** metade das requisições foi mais rápida que isso. É o caso "típico".
- **p95:** 95% foram mais rápidas. Começa a pegar as lentas.
- **p99:** 99% foram mais rápidas. É a **cauda** — só o 1% pior. Em 54.100 requisições,
  o p99 é decidido pelas ~541 mais lentas.

O scoring da rinha usa o **p99**. Por isso ele importa — mas justamente por ser a cauda,
ele é o número mais sensível a soluços pontuais. Uma única pausa de poucos
milissegundos que pegue um punhado de requisições em voo já move o p99 bastante, mesmo
que a esmagadora maioria das requisições continue rápida.

**Hoje o K6 só guarda o p99** (veja `test/test.js`, `summaryTrendStats: ['p(99)']`).
Estamos olhando só a ponta do iceberg e não enxergamos o corpo da distribuição.

---

## As duas hipóteses

### Hipótese A — é cauda (GC ou soluço do host)

O corpo da distribuição (p50, p95) é **estável e rápido** entre as rodadas, e só o p99
balança. Isso significa que quase toda requisição está ótima, e o que sobe o p99 são
poucos eventos raros — tipicamente:

- **Pausa de GC:** o coletor de lixo do Java para todas as threads por alguns
  milissegundos para limpar memória. Mesmo com a V3-D tendo cortado a alocação da busca,
  o Jackson ainda **aloca ao parsear cada requisição JSON** — a 900 req/s isso gera lixo
  suficiente para disparar coletas frequentes na geração jovem.
- **Soluço do host:** o Windows/WSL2 tira a CPU do container por um instante.

```
Hipótese A (só a cauda balança):

         p50    p95    p99
run1:    3ms    8ms    17ms
run3:    3ms    8ms    30ms
run2:    3ms    9ms    52ms   ← p50/p95 iguais, só p99 disparou
         └──── estável ────┘   └─ cauda nervosa ─┘
```

### Hipótese B — é o host inteiro disputando CPU

O corpo **também** balança — p50 e p95 sobem junto nas rodadas ruins. Isso significa que
não é um soluço pontual: é a máquina inteira sem CPU para o container, deixando *tudo*
mais lento.

```
Hipótese B (a distribuição inteira balança):

         p50    p95    p99
run1:    3ms    8ms    17ms
run2:    6ms   20ms    52ms   ← p50/p95 subiram junto
         └─ tudo mais lento ─┘
```

---

## O experimento que separa as duas

Trivial: adicionar `p(50)` e `p(95)` ao K6, ao lado do `p(99)` que já existe.

```js
// test/test.js
summaryTrendStats: ['p(50)', 'p(95)', 'p(99)'],
```

Rodar o benchmark de novo (mesmas 3–5 rodadas, host quieto) e olhar a tabela:

- **Se só o p99 balança e p50/p95 ficam parados → Hipótese A (cauda).**
- **Se p50/p95 balançam junto → Hipótese B (host).**

Custo: uma linha mudada e mais uma rodada de benchmark. Nenhum risco — não muda o
código da aplicação, só o que o K6 reporta.

---

## O que cada resultado significa para o que fazemos

| Resultado | Diagnóstico | Cura |
|---|---|---|
| Só p99 balança | Cauda de GC | Cortar alocação por request (parser JSON custom — o **V4-C** do roadmap) e/ou tunar o GC. O artefato em si está rápido. |
| p50/p95 balançam junto | Host sem CPU | É ruído de medição, não do código. Foco no protocolo (host quieto) e nada a "consertar" no app. |

A peça importante: **a cura provável da Hipótese A já está no roadmap como V4-C**
(parser JSON sem alocação). Ou seja, mesmo que confirmemos a cauda de GC agora, o
conserto natural acontece na V4 — não é um trabalho extra fora do plano.

---

## Por que isto é só um diagnóstico, e não uma tarefa urgente

O score final é `score_p99 + score_det`. Hoje:

- `score_p99` ≈ 1.500–1.750 (e o fix do warmup já o levou para cá).
- `score_det` ≈ 1.334 — **e é aqui que mora a maior folga**: a V4-A (busca exata por
  bounding-box) pode levar `score_det` de ~1.334 para ~2.000+, somando ~700 pontos.

Apertar a cauda do p99 ganha dezenas de pontos e reduz o risco de uma rodada azarada.
A busca exata da V4 ganha **centenas**. Por isso este diagnóstico é útil para *entender*
a distribuição, mas não compete em prioridade com a V4 — e, como vimos, a própria cura
(V4-C) já vive lá dentro.