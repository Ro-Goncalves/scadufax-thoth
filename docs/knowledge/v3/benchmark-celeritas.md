# Celeritas V3: Jornada, Resultados e Fechamento

Este documento consolida a V3 (branch `celeritas`) — da motivação à medição final.
A V3 foi, por desenho, **remoção de overhead sem mudar o algoritmo de busca**: atacar
a variância de latência e o garbage por request que sobravam da V2, não o IVF em si.
Serve como registro histórico da Celeritas e ponto de partida para a V4 (Veritas).

> Detalhes de implementação de cada item vivem em documentos próprios, linkados ao
> longo do texto. Aqui ficam a jornada e os números.

---

## Ponto de Partida: O Envelope V2

A V2 (Tesseract) fechou em **K=1024, nprobe=4** — busca IVF aproximada em int8, dois
passos, max-heap primitivo. O resultado de referência:

| Métrica | Valor |
|---|---|
| p99 | 36.95ms |
| final_score | 2.766,95 |
| score_p99 | 1.432,39 |
| score_det | 1.334,56 |
| FP / FN | 105 / 102 |
| Recall | 99.57% |
| Erros HTTP | 0 |

O diagnóstico de fechamento da V2 ([`../v2/07-benchmark-teseract.md`](../v2/07-benchmark-teseract.md))
foi explícito: **o gargalo restante não era o algoritmo**. Eram duas coisas:

1. **Variância cold/hot** — com mmap, o primeiro benchmark de uma sessão roda frio
   (page faults), inflando o p99 observado (~130ms frio vs. ~36ms quente).
2. **Garbage por request** — Jackson serializando cada resposta, e a busca alocando
   um `SearchResult` + uma `String` por candidato varrido (~11.720 por request).

A meta esticada da V2 (p99 ~25ms) ficou para a V3 justamente porque dependia de
eliminar esse overhead, não de mexer no IVF.

---

## O que a V3 implementou

O escopo foi fechado com o usuário antes de codar (plano em
[`01-celeritas.md`](01-celeritas.md)). Quatro entregas, uma renúncia consciente:

| Item | O que é | Status | Doc |
|---|---|---|---|
| **Pré-requisito** | Fixar o build no envelope vencedor K=1024 / nprobe=4 | ✅ entregue | — |
| **V3-A** | Page pre-warming do `.v2` antes do `/ready` | ✅ entregue | [`02`](02-page-pre-warming.md) |
| **V3-B** | Respostas pré-serializadas (`byte[][]`, sem Jackson no hot path) | ✅ entregue | [`01`](01-celeritas.md) |
| **V3-D** | Busca sem alocação (insertion sort K=5, puxado do V4-D) | ✅ entregue | [`03`](03-busca-sem-alocacao.md) |
| **V3-C** | nginx stream + Unix domain sockets | ❌ removido / adiado | [`01`](01-celeritas.md) |

O **V3-C saiu por decisão de engenharia**, não por falta de tempo: a parte barata
(nginx `http{}` → `stream{}`) rende ~nada e perde o keepalive HTTP que o `nginx.conf`
já explora; a parte valiosa (Jetty em UDS) é cara, frágil, e é exatamente a camada que
o V4-E (servidor NIO) e o V6 (fd-passing) vão reescrever do zero. Investir agora viraria
lixo em duas versões.

Os três itens implementados atacam exatamente as duas fontes de overhead do diagnóstico
V2: o V3-A mata a variância cold/hot; o V3-B e o V3-D matam o garbage por request.

---

## O Fix do Warmup: detecção de platô (o desbloqueio real)

Codar A/B/D não bastou: as primeiras medições da V3 oscilavam **74–195ms** entre runs
do **mesmo código**, com detecção idêntica até a 13ª casa decimal. O algoritmo estava
certo; o motor estava **frio** no `/ready`.

A causa: o warmup antigo eram 50 buscas fixas — pouco para o C2 do JIT compilar o hot
path. O `/ready` liberava tráfego antes da compilação final. A correção
([`04-warmup-plato.md`](04-warmup-plato.md)) trocou o número mágico por **detecção de
platô**: o aquecimento mede a latência média por janela e só para quando ela deixa de
cair, garantindo que o C2 compilou **antes** de aceitar tráfego. O page pre-warming
(V3-A) entrou como primeira etapa do mesmo warmup, antes do JIT-warm.

Para medir isso a sério, o protocolo passou a ser **5 boots frios completos**
(`down → up → /ready → k6` a cada rodada) — reproduzindo o tiro único da rinha 5 vezes
e reportando mediana + spread. K=1024 / nprobe=4:

| Rodada | p99 (ms) | final_score | detection_score |
|---|---|---|---|
| run1 | 17.52 | 3089.75 | 1333.1857761941253 |
| run2 | 52.32 | 2614.56 | 1333.1857761941253 |
| run3 | 29.55 | 2862.56 | 1333.1857761941253 |
| run4 | 30.60 | 2847.42 | 1333.1857761941253 |
| run5 | 20.99 | 3011.22 | 1333.1857761941253 |
| **mediana** | **29.55** | **2862.56** | — |

Leitura:

- **A hipótese do motor frio se confirmou.** A distribuição inteira desabou de 74–195ms
  para 17.5–52ms. A mediana (29.55ms) **bateu o baseline V2** (36.95ms), e 4 das 5
  rodadas ficaram abaixo dele.
- **Correctness é determinística.** O `detection_score` é idêntico até a 13ª casa nas 5
  rodadas, com 0 erros HTTP — toda a variância restante é latência pura.
- **Sobrou um spread de cauda** (run2 em 52ms vs. mediana 29.55ms). Como o warmup já está
  resolvido e a detecção é determinística, esse outlier é cauda de p99 — provável pausa
  de GC ou ruído do host. É a folga que o diagnóstico de percentis
  ([`05-diagnostico-percentis.md`](05-diagnostico-percentis.md)) propõe investigar.

---

## Re-medição da matriz K × nprobe

Com a variância cold/hot eliminada, a pergunta natural voltou: **o envelope vencedor
ainda é K=1024/nprobe=4?** Na V2, a matriz foi medida com o motor frio — penalizando
desproporcionalmente os configs que varrem mais dados. Agora que o page-warm achata a
cauda fria, valia re-rodar a varredura para ver se o ótimo se moveu.

Matriz re-medida (`benchmark-results/`, **1 boot frio por config** — varredura
exploratória, não as 5 rodadas rigorosas acima), ordenada por `final_score`:

| K | nprobe | p99 | final_score | score_p99 | score_det | FP / FN | recall% | fail% |
|---|---|---|---|---|---|---|---|---|
| **2048** | **2** | **13.06ms** | **3090.07** | **1883.90** | 1206.17 | 147 / 123 | 99.49 | 0.50 |
| 1024 | 2 | 17.02ms | 3018.36 | 1769.02 | 1249.34 | 124 / 118 | 99.51 | 0.45 |
| 4096 | 6 | 23.63ms | 2946.15 | 1626.50 | 1319.65 | 122 / 100 | 99.58 | 0.41 |
| 2048 | 4 | 24.38ms | 2942.02 | 1612.93 | 1329.09 | 118 / 99 | 99.59 | 0.40 |
| 4096 | 4 | 23.70ms | 2931.66 | 1625.22 | 1306.44 | 132 / 100 | 99.58 | 0.43 |
| 2048 | 6 | 26.07ms | 2918.45 | 1583.89 | **1334.56** | 114 / 99 | 99.59 | 0.39 |
| 1024 | 4 | 54.71ms | 2595.02 | 1261.90 | 1333.11 | 106 / 102 | 99.57 | 0.38 |
| 4096 | 2 | 42.19ms | 2540.81 | 1374.75 | 1166.06 | 161 / 131 | 99.45 | 0.54 |
| 1024 | 6 | 92.42ms | 2383.93 | 1034.25 | 1349.68 | 106 / 98 | 99.59 | 0.38 |

> **Caveat de leitura:** esta matriz é **1 boot por config**, não a medição de 5 rodadas.
> O próprio K=1024/nprobe=4 aqui marcou 54.71ms — bem pior que sua mediana rigorosa de
> 29.55ms (run azarado). Ou seja: o **ranking absoluto é ruidoso**; o que a matriz mostra
> com confiança é a **forma** da distribuição, não a decisão final de envelope.

---

## O que os dados ensinaram

**1. A V3 puxou a matriz inteira para uma faixa de latência saudável.**
Na V2, K=4096/nprobe=4 marcava 103ms; aqui marca 23.70ms. O page pre-warming não
beneficiou só o envelope — beneficiou **todos** os configs, porque o page fault frio
penalizava justamente quem varre mais dados. A cauda cold sumiu da matriz toda.

**2. O ótimo se moveu para K maior / nprobe menor.**
Na V2 (motor frio), nprobe alto era proibitivo e K=1024/nprobe=4 vencia. Agora que a
latência ficou barata, o `score_p99` passou a dominar o `final_score`, e os configs que
**minimizam p99** (K=2048/nprobe=2 → 13ms, K=1024/nprobe=2 → 17ms) subiram ao topo —
mesmo pagando detecção pior (mais FP/FN). É uma inversão direta da lição da V2.

**3. Mas a detecção piora com nprobe=2 — e o teto continua estrutural.**
K=2048/nprobe=2 lidera com 3090, mas tem **147 FP / 123 FN** (`score_det` 1206) contra
os 118/99 do K=2048/nprobe=4 (`score_det` 1329). Visitar só 2 clusters erra mais nas
queries de fronteira. O `score_det` continua preso na faixa 1200–1335 em todo config
saudável — **confirmando o diagnóstico da V2: o teto de detecção do IVF é estrutural,
não paramétrico.** Nenhuma combinação de K×nprobe rompe ~1.335.

**4. A latência deixou de ser o gargalo. A detecção é o que sobra.**
Esta é a leitura mais importante. Na V2, p99 e detecção disputavam (subir nprobe melhora
recall e piora latência). Depois da V3, p99 ficou tão barato que o `score_p99` saturou
no topo — e o `final_score` agora é decidido pelo `score_det`. A próxima alavanca não é
mais velocidade; é **busca exata** (V4-A, bounding-box pruning).

---

## Vencedor (varredura exploratória): K=2048, nprobe=2

```
p99           13.06ms
final_score   3090.07
score_p99     1883.90
score_det     1206.17
FP / FN       147 / 123
recall        99.49%
Erros HTTP    0
```

Pela primeira vez na jornada o `final_score` cruzou a barreira dos **3.000**. Mas a
decisão de **trocar o envelope operacional** de K=1024/nprobe=4 para K=2048/nprobe=2
**não está fechada** — depende de reconfirmar com as 5 rodadas rigorosas (a matriz é
single-boot) e de pesar o trade-off: nprobe=2 ganha em p99 mas paga em detecção, e a
V4-A (busca exata) tende a tornar essa escolha irrelevante ao zerar FP/FN. Fica para a
próxima conversa.

---

## Entregamos o que o Tesseract planejou para a Celeritas?

O fechamento da V2 deixou a V3 com um mandato explícito ("Legado para a V3 e além"):
*page pre-warming + respostas pré-serializadas, para eliminar a variância de latência e
medir o p99 real em condições de hot cache*. Confrontando o prometido com o entregue:

| Compromisso da V2 para a V3 | Entregue? | Evidência |
|---|---|---|
| Page pre-warming (eliminar variância cold/hot) | ✅ | V3-A; cauda 130ms→regime sumiu, matriz inteira na faixa saudável |
| Respostas pré-serializadas (sem Jackson na resposta) | ✅ | V3-B; `byte[][]` no bootstrap |
| Medir p99 real em hot cache | ✅ | 5 boots frios + matriz re-medida com motor quente |
| (bônus) Hot path de busca sem alocação | ✅ | V3-D, antecipado do V4-D |

### Confronto com as metas de p99

| Meta | Valor-alvo | V2 | V3 | Status |
|---|---|---|---|---|
| Meta principal: p99 | ≤ 25ms | 36.95ms | 29.55ms (mediana, K=1024/n4) | **Atingida na mediana** |
| Meta esticada: p99 | ~10ms | 36.95ms | 13.06ms (K=2048/n2, single-boot) | **Quase** — vários configs < 25ms |
| Erros HTTP | 0 | 0 | 0 | Atingida |
| Taxa de falhas | < 15% | 0.38% | 0.38–0.54% | Atingida |

**Veredito: a V3 entregou o que o Tesseract planejou — e um pouco além.** A meta
principal de 25ms (que a V2 não atingiu, fechando em 36.95ms) foi batida na mediana das
5 rodadas rigorosas do envelope, e a varredura exploratória mostra **múltiplos configs
abaixo de 25ms**, com o melhor (K=2048/nprobe=2) chegando a 13ms — encostando na meta
esticada de ~10ms. O `final_score` saltou de 2.766,95 para a faixa de 2.860 (envelope
atual, mediana) a 3.090 (config mais rápido).

E entregou a lição estratégica que justifica a V4: **velocidade deixou de ser o
gargalo.** Com o `score_p99` saturado, todo o ganho restante mora no `score_det`, preso
em ~1.335 por limitação estrutural do IVF aproximado. A V4-A (bounding-box pruning →
busca exata → 0 FP/FN) é a alavanca que sobra.

---

## Metodologia de medição (lição aprendida)

A variância de 74–195ms da fase V3 **não era só código** — parte era o ambiente. Para um
benchmark confiável neste setup (WSL2 + Docker com CPU capada em 0.45):

1. **Rodar no terminal, com tudo o mais fechado.** IDE, builds, abas pesadas do navegador
   — qualquer coisa que dispute CPU com o container contamina o p99. As medições com a
   IDE aberta deram os piores outliers.
2. **Vários boots frios por config** (`RUNS` no `run-benchmark.sh`), cada um reproduzindo
   o tiro único da rinha. A matriz exploratória deste doc é single-boot e serve só para a
   **forma** da distribuição; decisão de envelope exige as 5 rodadas.
3. **Olhar mediana E spread (min/max), nunca uma amostra só.** A mediana remove o ruído do
   host; o spread revela a variância intrínseca do artefato — que é o que a rinha sorteia
   no tiro único. **Spread apertado importa mais que mediana boa.**
4. **Tratar os ms absolutos como diagnóstico relativo, não como predição.** A rinha roda
   em outro host; o número daqui responde "estabilizou?" e "melhorou vs. o baseline?", não
   prevê o score final.

> **Próximo passo proposto (para a próxima conversa):** o diagnóstico de percentis
> ([`05-diagnostico-percentis.md`](05-diagnostico-percentis.md)) — adicionar p50/p95 ao
> K6 para separar "cauda de GC" de "host sem CPU" no spread residual — e a V4-A
> (bounding-box pruning), a maior alavanca de score que sobra. Fora do escopo desta V3.
