# PRD: Tesseract V4 — Veritas

Status: ready-for-agent

> Plano de execução técnico de referência: [`docs/knowledge/v4/01-veritas.md`](../../docs/knowledge/v4/01-veritas.md).
> Roadmap de versões: [`docs/knowledge/roadmap.md`](../../docs/knowledge/roadmap.md).
> Benchmark de fechamento da V3: [`docs/knowledge/v3/06-benchmark-celeritas.md`](../../docs/knowledge/v3/06-benchmark-celeritas.md).

## Declaração do problema

A V3 (Celeritas) eliminou o overhead de latência e saturou o `score_p99` — medianas
na faixa de 13–30ms. Porém, o `final_score` continua preso em ~2.862 porque o
`score_det` estagna em ~1.335.

A causa é estrutural: o IVF com nprobe=4 visita apenas 0,4% dos clusters. Queries
na fronteira entre clusters têm seus vizinhos reais em clusters não visitados, gerando
105 FP e 102 FN por rodada. Nenhuma combinação de K×nprobe rompe esse teto (confirmado
na matriz re-medida da V3). A única alavanca de score que resta é **detecção perfeita**.

Para referência, AndDev741 — 2× mais lento em p99 — tem score=4.056 (1,5× superior)
exclusivamente por ter 0 FP e 0 FN, via busca exata. O `score_det` que sobra com
busca perfeita vale ~2.000+; o `final_score` estimado cruza 3.600, território de
top-10 Java.

## Solução

A V4 (Veritas) transforma a busca aproximada em **busca provadamente exata** via
bounding-box pruning por cluster, sem aumentar o nprobe nem trocar o algoritmo base.

A sequência tem quatro pilares:

- **V4-0 — Diagnóstico de percentis:** adicionar p50/p95 ao K6 para separar cauda de
  GC (hipótese A) de ruído de host (hipótese B) no spread residual da V3. Decide se
  o V4-C entra no escopo.
- **V4-A Passo 0-A — Parametrização i8/i16:** tornar o tipo de quantização um
  parâmetro de build (`ARG DTYPE`), para que a investigação e o benchmark possam
  comparar int8 e int16 com um único `docker build`.
- **V4-A Passos 1-3 — Bounding-box pruning:** (1) medir quantos dos 207 erros são de
  quantização vs. de aproximação e decidir o dtype; (2) persistir `bboxMin`/`bboxMax`
  por cluster no artefato; (3) iterar todos os clusters no `search`, podando
  geometricamente os que não podem melhorar o top-k atual.
- **V4-C — Parser JSON custom:** substituir o Jackson no parse de entrada por um
  parser cursor-based zero-alocação. Entra **somente** se o V4-0 confirmar cauda de GC.

## Histórias de usuário

1. Como operador de benchmark, eu quero que o K6 reporte p50 e p95 além do p99, para
   que eu possa distinguir se o spread de cauda vem do GC ou do host.
2. Como mantenedor, eu quero saber se o p50/p95 estabiliza entre runs, para que a
   decisão de incluir o parser custom seja baseada em evidência, não em suposição.
3. Como mantenedor, eu quero que o builder do artefato aceite um parâmetro de tipo de
   quantização (i8 ou i16), para que eu possa comparar as duas configurações sem
   reescrever código.
4. Como operador de benchmark, eu quero fazer `docker build --build-arg DTYPE=i16`
   para obter um artefato int16, para comparar o score com o artefato int8 padrão.
5. Como engenheiro de detecção, eu quero medir quantos dos 207 erros (105 FP + 102 FN)
   são causados por quantização e quantos por aproximação de IVF, para decidir se devo
   migrar de int8 para int16.
6. Como engenheiro de detecção, eu quero comparar int8 full-scan contra float32
   brute-force no dataset real, para isolar a perda de precisão atribuída à quantização.
7. Como engenheiro de detecção, eu quero comparar int16 full-scan contra float32
   brute-force no dataset real, para medir o ganho de precisão que int16 oferece.
8. Como mantenedor, eu quero tomar a decisão de dtype (int8 vs int16) baseado em
   dados medidos, para não migrar por suposição e não desperdiçar memória
   desnecessariamente.
9. Como engenheiro de build, eu quero que o artefato `.v2` armazene `bboxMin` e
   `bboxMax` por cluster, para que o buscador possa calcular o lower-bound geométrico
   sem reler o dataset em runtime.
10. Como engenheiro de build, eu quero que os bboxes sejam calculados durante a fase
    de atribuição K-means, para não exigir uma passagem extra sobre os dados.
11. Como mantenedor, eu quero que a mudança no formato do artefato seja encapsulada
    pelos mesmos módulos de builder e searcher, para que o restante do sistema não
    precise saber do novo layout.
12. Como engenheiro de busca, eu quero que o `search` itere todos os clusters após
    os nprobe iniciais, usando o lower-bound geométrico para podar os que não podem
    melhorar o top-k, para que a busca se torne provadamente exata.
13. Como engenheiro de busca, eu quero que o cálculo do lower-bound opere em
    aritmética inteira (não float), para não perder o ganho de performance da
    quantização no caminho de pruning.
14. Como engenheiro de busca, eu quero que o pruning seja aplicado antes de varrer
    qualquer vetor do cluster, para não desperdiçar ciclos em clusters que nunca podem
    contribuir para o top-k.
15. Como engenheiro de busca, eu quero que a ordem de iteração dos clusters restantes
    favoreça os mais próximos do centróide primeiro, para que o top-k se popule de
    bons candidatos rapidamente e aumente a taxa de poda dos seguintes.
16. Como mantenedor, eu quero que `TopKSelector` exponha a pior distância atual no
    top-k, para que o pruning possa compará-la com o lower-bound sem acessar internos
    do seletor.
17. Como avaliado na Rinha, eu quero zero FP e zero FN após o bbox pruning, para que
    o `score_det` suba de ~1.335 para ~2.000+.
18. Como avaliado na Rinha, eu quero que o `score_p99` não regrida mais que ~5ms em
    relação à mediana da V3, para que o ganho em detecção não cancele o ganho em
    latência.
19. Como mantenedor, eu quero que `V2QualityGuardTest` valide 100% de acordo com
    float32 brute-force (zero divergências), para confirmar que a busca é
    provadamente exata e não apenas aproximada-boa.
20. Como mantenedor, eu quero que a interface `VectorSearcher` não mude, para que
    testes e a guarda de qualidade existentes continuem válidos sem reescrita.
21. Como mantenedor, eu quero que os arrays de centróides e bboxes sejam carregados
    como `int[][]` (widened) internamente no searcher, para unificar o código de
    distância entre i8 e i16 sem duplicar lógica.
22. Como mantenedor, eu quero que o searcher detecte o dtype do artefato no header e
    despache para o cálculo de distância correto, para que um único binário suporte
    os dois formatos sem configuração manual.
23. Como engenheiro de performance, eu quero que o hot path de busca não aloque por
    candidato varrido (conforme V3-D), mesmo com o novo loop de pruning, para não
    regredir o trabalho de garbage já eliminado.
24. Como operador de benchmark, eu quero rodar 5 boots frios após o V4-A e comparar
    `score_det` antes e depois, para confirmar empiricamente que os FP/FN zeraram.
25. Como operador de benchmark, eu quero um gate de decisão explícito após o V4-A,
    para replanejar V5 e V6 com os números reais de score na mão.
26. Como engenheiro de parse, eu quero substituir o Jackson no parse de entrada por
    um parser cursor-based zero-alocação (condicional ao V4-0), para eliminar a última
    fonte de alocação por requisição no hot path.
27. Como engenheiro de parse, eu quero que o parser custom converta timestamps
    ISO-8601 para epoch sem alocar `Instant` nem `ZonedDateTime`, para manter zero
    alocação no caminho de parse.
28. Como mantenedor, eu quero que a remoção do Jackson no parse simplifique o caminho
    para GraalVM Native Image (V5), eliminando a necessidade de configuração de
    reflexão para o desserializador.
29. Como mantenedor, eu quero que os resultados medidos de cada passo (V4-0, V4-A
    passo 1, V4-A pós-pruning, V4-C se entrar) sejam registrados na seção "Resultados"
    do doc da V4, para manter a documentação no mesmo padrão das versões anteriores.
30. Como mantenedor, eu quero que a decisão de dtype (int8 vs int16) e os números da
    investigação de quantização fiquem documentados, para que a escolha possa ser
    revisitada se o dataset mudar.

## Decisões de implementação

### V4-0 — Diagnóstico de percentis

- Adicionar `p(50)` e `p(95)` ao array `summaryTrendStats` do script K6. Mudança de
  uma linha; não altera código da aplicação.
- O critério de decisão: se apenas o p99 balança entre runs (p50/p95 estáveis) →
  cauda de GC → V4-C entra no escopo. Se p50/p95 também balançam → ruído de host →
  V4-C fica fora.

### V4-A Passo 0-A — Parametrização i8/i16 (módulo: V2ArtifactBuilder + V2IndexSearcher)

- `V2ArtifactBuilder` recebe um parâmetro de tipo de quantização (`Dtype`) com dois
  valores: `I8` (scale=127, sentinela=`Byte.MIN_VALUE`) e `I16` (scale=10.000,
  sentinela=`Short.MIN_VALUE`).
- As constantes de layout variam por dtype:

  | Constante | I8 | I16 |
  |---|---|---|
  | `RECORD_SIZE` | 16 (1+14+1) | 30 (1+28+1) |
  | `CLUSTER_ENTRY_SIZE` (pós-V4-A) | 58 | 100 |
  | `DTYPE` byte no header | `1` | `2` |

- `encodeI16()` espelha `encodeI8()` usando `short[]` e scale=10.000.
- `V2IndexSearcher.readHeader()` passa a aceitar ambos os dtypes; centroids e bboxes
  são carregados como `int[][]` (widened) para unificar o código. O searcher despacha
  para `calculateI8` ou `calculateI16` conforme o dtype lido.
- `Dockerfile` recebe `ARG DTYPE=i8`, repassado ao builder na fase 1.
- `EuclideanDistanceCalculator.calculateI16()` já está implementado e não muda.

### V4-A Passo 1 — Investigação de quantização

- Executar três comparações no dataset real (`references.json.gz`): float32
  brute-force (ground truth), int8 full-scan (`nprobe=numClusters`), int16 full-scan.
- Critério de decisão (decidido nesta conversa):
  - int8 full-scan < ~10 divergências → fica **int8**.
  - int8 full-scan > ~20 divergências → migra para **int16**.
- Resultado registrado no documento antes de avançar para o Passo 2.

### V4-A Passo 2 — Bboxes no build (módulo: V2ArtifactBuilder)

- Durante a fase de distribuição dos vetores por cluster (após K-means), calcular
  `bboxMin[DIMS]` e `bboxMax[DIMS]` (mínimo e máximo por dimensão) para cada cluster.
- Persistir imediatamente após o campo `count` de cada entrada do diretório.
- Layout de cada entrada do diretório após a extensão:

  ```
  [centróide: 14 bytes (i8) | 28 bytes (i16)]
  [radius: 4 bytes float]
  [offset: 8 bytes long]
  [count: 4 bytes int]
  [bboxMin: 14 bytes (i8) | 28 bytes (i16)]
  [bboxMax: 14 bytes (i8) | 28 bytes (i16)]
  ```

  (Protótipo do plano de execução `docs/knowledge/v4/01-veritas.md`)

- `VERSION` permanece `2` — nenhum artefato antigo precisa ser lido em runtime (sempre
  reconstruído no build).

### V4-A Passo 3 — Bbox pruning no search (módulos: V2IndexSearcher + TopKSelector)

- Após varrer os `nprobe` clusters iniciais, iterar os clusters restantes em ordem de
  distância ao centróide (do mais próximo ao mais distante). Para cada cluster:
  - Calcular o lower-bound geométrico com base nos bboxes (operação inteira pura).
  - Se `lb > topK.worstDist()` → pular o cluster inteiro.
  - Caso contrário → varrer normalmente.
- Lower-bound: para cada dimensão, a distância mínima possível entre a query e qualquer
  ponto dentro do bbox. Se a query está dentro do bbox na dimensão d → contribuição 0;
  se está fora → `(diff_ao_lado_mais_próximo)²`. Soma sobre todas as dimensões.

  (Protótipo do roadmap — a lógica codifica a decisão mais precisamente que prosa)
  ```java
  // Exemplo de cálculo do lower-bound (protótipo do roadmap)
  int lb = 0;
  for (int d = 0; d < DIMS; d++) {
      int q = query[d];
      int lo = bboxMin[d], hi = bboxMax[d];
      if      (q < lo) { int diff = lo - q; lb += diff * diff; }
      else if (q > hi) { int diff = q - hi; lb += diff * diff; }
  }
  ```

- `TopKSelector` ganha o método `worstDist()`, que retorna `topDist[k-1]` (pior
  distância no top-k atual). Interface existente de `tryInsert` + `materialize` não
  muda.
- Nenhuma alocação nova por candidato — a invariante de zero-alocação do V3-D é
  mantida no novo loop de pruning.

### V4-C — Parser JSON custom (módulo: FraudRequestParser)

- Entra somente se o V4-0 confirmar Hipótese A (cauda de GC).
- Parser cursor-based para o schema fixo do request. O caller passa `float[]`
  pré-alocado; o parser escreve in-place — zero `new` no hot path.
- Timestamps ISO-8601 convertidos para epoch seconds pelo algoritmo de Howard Hinnant
  (`daysFromCivil`), sem alocar `Instant` ou `ZonedDateTime`.
- Referências: `JsonReader.java` do AndDev741, `FraudRequestParser.java` do arthurd3.

## Decisões de teste

Um bom teste exercita **comportamento externo observável**: o conjunto de vizinhos
retornado, o acordo com a referência float32, e o efeito dos bboxes na poda — nunca
a estrutura interna de arrays, contagem de chamadas privadas, ou a ordem em que o
pruning poda internamente. O estilo segue `V2QualityGuardTest`, `TopKSelectorTest` e
`V2IvfSearchTest`.

Módulos com teste dedicado (confirmados com o usuário):

1. **V4-A Passo 1 — Investigação de quantização:** não é um teste automatizado de
   regressão, mas um benchmark de análise rodado uma vez sobre o dataset real. Compara
   três buscas (float32-BF, int8-full-scan, int16-full-scan) e reporta divergências
   por tipo. Serve de evidência para a decisão de dtype; resultado registrado em doc.

2. **V4-A Passo 2 — Bboxes no build:** verificar que, para um artefato de fixture
   conhecido, o `bboxMin` e `bboxMax` de cada cluster contêm corretamente os limites
   por dimensão de todos os vetores atribuídos àquele cluster. Teste de propriedade:
   nenhum vetor do cluster pode ter qualquer dimensão menor que `bboxMin[d]` nem
   maior que `bboxMax[d]`.

3. **V4-A Passo 3 — Correção do pruning (guarda de qualidade estendida):** estender
   `V2QualityGuardTest` para exigir **zero divergências** contra float32 brute-force
   (não apenas o threshold mínimo da V2). Se o pruning for correto, todas as queries
   devem retornar o mesmo conjunto de vizinhos da busca exata. Referência direta:
   `V2QualityGuardTest` existente (estilo e fixture já prontos).

4. **`TopKSelector.worstDist()`:** adicionar asserção ao `TopKSelectorTest` existente:
   após inserções variadas, `worstDist()` deve retornar exatamente `topDist[k-1]` e
   evoluir monotonicamente decrescente conforme candidatos melhores entram.

5. **V4-C — Parser JSON custom:** para um corpus de requests de referência (amostrado
   do dataset real ou gerado a partir de fixture), o parser custom deve produzir o
   mesmo `float[]` de vetor que o Jackson produziria — incluindo o sentinela `-1.0f`
   para `last_transaction` ausente e a extração de hora e dia da semana do timestamp.

Guarda de regressão (existente, deve permanecer verde em todos os passos):
`V2QualityGuardTest`, `V2IvfSearchTest`, `TopKSelectorTest`,
`EuclideanDistanceCalculatorTest`.

## Fora do escopo

- **V4-B (IVF repair):** cortado — subsumido pelo V4-A. O repair corrige buscas
  aproximadas ambíguas; o V4-A entrega busca exata e não deixa ambiguidade.
- **V4-E (servidor HTTP NIO custom):** movido para a V6, onde a latência volta a pagar
  e onde o fd-passing (que depende do NIO) está planejado.
- **Re-avaliação do envelope K×nprobe:** com bbox pruning o nprobe deixa de afetar
  *correctness*; a decisão de novo envelope fica para o gate pós-V4-A, com os números
  reais de score na mão.
- **V5 (GraalVM Native Image), V6 (fd-passing), V7 (Knowledge Distillation):** fases
  posteriores ao gate.
- **SIMD / Vector API Java:** confirmado empiricamente por arthurd3 como 3,8× mais
  lento que scalar e incompatível com GraalVM. Não entra em nenhuma versão.

## Notas adicionais

- **Gate explícito após V4-A:** medir score com 5 boots frios antes de planejar V5/V6.
  O planejamento das próximas versões depende do número real, não da estimativa.
- **Implicação de migrar para int16:** artefato cresce de ~42 MB para ~84 MB. O budget
  de ~195 MB de page cache acomoda os dois, mas deve-se confirmar que o artefato i16
  ainda cabe dentro do limite de 165 MB por contêiner (com Heap + overhead).
- **Documentação:** ao fechar cada passo, atualizar a seção "Resultados" de
  `docs/knowledge/v4/01-veritas.md`, conforme o padrão das versões anteriores.
- **V4-C é condicional, não opcional por preguiça:** a decisão de incluí-la ou não
  é técnica, baseada no diagnóstico de percentis. Registrar o resultado do V4-0 antes
  de qualquer decisão sobre o escopo do V4-C.