# PRD: Tesseract V3 — Celeritas

Status: ready-for-agent

> Plano de execução técnico de referência: [`docs/knowledge/v3/01-celeritas.md`](../../docs/knowledge/v3/01-celeritas.md).
> Roadmap de versões: [`docs/knowledge/roadmap.md`](../../docs/knowledge/roadmap.md).
> Fechamento da V2 (linha de base): [`docs/knowledge/v2/07-benchmark-teseract.md`](../../docs/knowledge/v2/07-benchmark-teseract.md).

## Declaração do problema

A V2 (Tesseract) fechou em **K=1024, nprobe=4** com p99=36.95ms e `final_score`=2.766,95.
O algoritmo de busca (IVF + quantização int8) já não é o gargalo — o que sobra é
**overhead e variância de latência**:

1. **Variância cold/hot (Page Fault penalty):** o artefato `.v2` é acessado via *mmap*.
   No primeiro benchmark de uma sessão, as páginas ainda não estão no *Page Cache*, então
   o SO paga *Page Faults* sob demanda. Observado empiricamente: ~130ms no frio vs. ~36ms
   no quente. O K6 mede um mix dos dois, inflando o p99 reportado.
2. **Garbage no hot path:** a cada requisição, a resposta é serializada pelo Jackson, e a
   busca aloca um objeto de resultado + uma `String` de label **por candidato varrido**
   (~11.720 por requisição em K=1024/nprobe=4). Isso pressiona o *Garbage Collector* num
   contêiner de 1 CPU / 165MB por API.
3. **Risco de medição:** o build default ainda está numa configuração de experimentação
   (`NUM_CLUSTERS=256`, `NPROBE=8`), não no envelope vencedor. Qualquer comparação
   "antes/depois" mediria a configuração errada.

Além disso, a meta de p99 ≤ 25ms da V2 não foi atingida (parou em 36.95ms); a folga
restante está justamente na variância e no overhead acima.

## Solução

A V3 (Celeritas) é **remoção de overhead, sem mudar o algoritmo de busca**. Quatro
entregas independentes:

- **Pré-requisito — pin do envelope:** fixar o build em K=1024 / nprobe=4 para que toda
  medição passe a refletir a configuração real de produção.
- **V3-A — Page pre-warming:** aquecer todas as páginas do mapeamento do `.v2` no
  bootstrap, antes do `/ready` retornar 200, eliminando a variância cold/hot. O K6 passa
  a medir regime quente desde a primeira requisição.
- **V3-B — Respostas pré-serializadas:** como só existem `K_NEIGHBORS + 1` respostas
  possíveis, pré-serializá-las uma vez no bootstrap como `byte[]` e devolver os bytes
  prontos por requisição — zero serialização e zero alocação de resposta no hot path.
- **V3-D — Busca sem alocação:** substituir o *Max-Heap* de objetos por um seletor top-k
  baseado em insertion sort sobre arrays primitivos, eliminando o lixo por candidato e
  deixando o GC ocioso. (Técnica antecipada do V4-D.)

O **V3-C (nginx stream + Unix domain sockets) foi removido/adiado** — a parte barata não
rende e a parte valiosa (UDS no Jetty) seria reescrita pelo V4-E/V6.

## Histórias de usuário

1. Como mantenedor do Tesseract, eu quero que o build default construa o artefato no
   envelope vencedor (K=1024), para que a imagem de produção seja a mesma que vencemos no
   benchmark.
2. Como mantenedor, eu quero que o `NPROBE` default seja 4 nos dois serviços de API, para
   que o runtime sonde a mesma quantidade de clusters da configuração campeã.
3. Como operador de benchmark, eu quero rodar o K6 e obter um p99 que reflita a config
   real, para que minhas comparações "antes/depois" da V3 sejam confiáveis.
4. Como operador, eu quero que a aplicação aqueça todas as páginas do `.v2` antes de
   aceitar tráfego, para que a primeira requisição não pague *Page Fault penalty*.
5. Como operador, eu quero que o `/ready` só retorne 200 depois do aquecimento de página
   e de JIT, para que o load balancer só direcione tráfego quando a API estiver em regime
   quente.
6. Como operador, eu quero que o aquecimento de página acrescente menos de ~1s ao
   startup, para que o ganho de latência não custe um tempo de inicialização proibitivo.
7. Como mantenedor, eu quero que o aquecimento toque exatamente o mapeamento usado no hot
   path (o `MemorySegment` do searcher), para eliminar inclusive o *soft fault*, não só o
   acesso a disco.
8. Como integrador da API, eu quero que a resposta do `/fraud-score` seja byte-a-byte
   idêntica à que o Jackson produzia, para que o contrato com o avaliador da Rinha não
   regrida.
9. Como mantenedor, eu quero que a tabela de respostas seja derivada de `K_NEIGHBORS` e
   `FRAUD_THRESHOLD` em tempo de bootstrap, para que mudar esses parâmetros por env não
   exija recompilar nem reescrever respostas à mão.
10. Como engenheiro de performance, eu quero que o hot path da resposta não aloque
    `String` nem objetos por requisição, para reduzir a pressão sobre o GC.
11. Como engenheiro de performance, eu quero que a varredura de candidatos da busca não
    aloque um objeto + `String` por candidato, para que ~11.720 alocações por requisição
    deixem de existir.
12. Como mantenedor, eu quero um seletor top-k extraído como módulo isolado, para que a
    lógica de "manter os k menores" seja testável sem subir um searcher inteiro.
13. Como mantenedor, eu quero que o seletor top-k produza exatamente os mesmos vizinhos e
    a mesma ordem da implementação atual (Max-Heap), para que o recall não mude.
14. Como mantenedor, eu quero que a interface `VectorSearcher` permaneça intacta, para que
    os testes e a guarda de qualidade existentes continuem válidos sem reescrita.
15. Como avaliado na Rinha, eu quero zero erros HTTP sob a carga do K6, para que a
    pontuação de detecção não seja penalizada por falhas de infraestrutura.
16. Como mantenedor, eu quero que `V2QualityGuardTest` e `V2IvfSearchTest` continuem
    verdes após o V3-D, para garantir que a refatoração foi puramente estrutural.
17. Como operador de benchmark, eu quero medir cada item (A, B, D) isoladamente, para
    isolar a contribuição de cada técnica ao p99 e ao avg.
18. Como mantenedor, eu quero registrar os resultados medidos na seção "Resultados" do
    doc da V3, para que a fase fique documentada no mesmo padrão da V2.
19. Como desenvolvedor que aprende o domínio, eu quero documentação explicando o porquê de
    cada decisão (tocar o `MemorySegment` vs `FileChannel`, insertion sort vs Max-Heap),
    para entender os trade-offs além do código.
20. Como mantenedor, eu quero que o V3-C fique explicitamente fora de escopo e com
    justificativa, para que ninguém o reabra sem rever o motivo do adiamento.

## Decisões de implementação

### Pré-requisito — pin do envelope operacional

- Fixar `NUM_CLUSTERS=1024` no estágio de build (ARG do Dockerfile) e `NPROBE` default `4`
  nos dois serviços de API (compose). Continua parametrizável por env para experimentos.
- O K-Means paralelo já existente (passo de atribuição com `IntStream.parallel()`) absorve
  o custo de K=1024 no estágio 1 do Docker (~150s, todos os cores). Não muda o runtime.

### V3-A — Page pre-warming (módulo: Page pre-warmer)

- Adicionar uma capacidade de aquecimento ao `V2IndexSearcher` que percorre o próprio
  `MemorySegment` mapeado, tocando um byte por página (passo de 4KB) e somando num *sink*
  para impedir *dead-code elimination* do JIT. Decisão: tocar o **mapeamento exato do hot
  path**, não um `FileChannel` separado — isso elimina disco **e** *soft fault* da tabela
  de páginas do processo.
- Orquestração: o `WarmupService` (que já roda síncrono antes do `start()`) chama o
  aquecimento de página **antes** do aquecimento de JIT. Sequência: page-warm → JIT-warm →
  `/ready` libera. A propriedade de gating do `/ready` é preservada.
- Restrição de memória: as páginas faltadas são *file-backed* e reclamáveis sob pressão;
  ~45–48MB dentro do limite de 165MB por API convive com a Heap (`-Xmx80m`). Sem risco de
  `OutOfMemoryError`.

### V3-B — Respostas pré-serializadas (módulo: Tabela de respostas)

- Módulo profundo e puro: dada a aridade `K_NEIGHBORS` e o `FRAUD_THRESHOLD`, produzir uma
  tabela `byte[][]` indexada por `fraudCount ∈ {0 .. K_NEIGHBORS}`, onde cada posição é o
  corpo JSON já serializado em UTF-8 (`approved` = `score < threshold`, `score = i/k`).
- O handler de busca passa a escrever os bytes prontos da posição `fraudCount` (via
  `result(byte[])` com content-type JSON), sem montar objeto de resposta nem acionar o
  Jackson.
- Contrato: a saída deve ser **byte-a-byte idêntica** à serialização atual do Jackson. Com
  `k=5`, os `score` possíveis (`0.0, 0.2, 0.4, 0.6, 0.8, 1.0`) têm representação textual
  idêntica entre `Double.toString` e o Jackson — sem regressão de contrato.
- Escopo: V3-B trata **apenas a resposta**. O parse de entrada (Jackson na desserialização
  do request) permanece e só será substituído no V4-C (parser custom).

### V3-D — Busca sem alocação (módulo: Seletor top-k)

- **Extrair** o seletor top-k como módulo isolado (decisão do usuário). Ele mantém os k
  menores via insertion sort sobre dois arrays primitivos paralelos: distância e label
  int8. Operações: `tryInsert(distância, label)` e materialização final dos k resultados.
- O `V2IndexSearcher.search` deixa de usar `PriorityQueue<SearchResult>` e passa a
  alimentar o seletor com (distância, label-byte) por candidato. A construção de objetos
  `SearchResult` e de `String` de label acontece **uma única vez no final**, ao
  materializar os k resultados — eliminando o garbage por candidato.
- **A interface `VectorSearcher` (`List<SearchResult> search(float[], int)`) NÃO muda.** A
  refatoração é interna; o tipo de retorno e a semântica (mesmos vizinhos, mesma ordem
  crescente de distância) são idênticos.
- Concorrência: os arrays do seletor são **locais à chamada** (k pequeno → alocação
  trivial). O searcher é compartilhado entre requisições concorrentes (virtual threads),
  então o seletor não pode manter estado mutável de instância no searcher, e não se usa
  `ThreadLocal` (multiplicaria por virtual thread).
- Nota de futuro (fora de escopo da V3): o handler só precisa da **contagem de fraudes** no
  top-k; um caminho `nearestFraudCount(...)` evitaria até os k objetos finais — refinamento
  do V4.

## Decisões de teste

Um bom teste aqui exercita **comportamento externo observável**, não detalhe de
implementação: o byte de saída, o conjunto/ordem de vizinhos, e o efeito do aquecimento —
nunca a estrutura interna de arrays ou a contagem de chamadas privadas. Os testes novos
seguem o estilo dos existentes (`V2IvfSearchTest`, `V2QualityGuardTest`,
`QuantizedBruteForceSearcherTest`).

Módulos com teste dedicado nesta fase (confirmado com o usuário):

1. **Tabela de respostas pré-serializadas** — teste de **contrato**: para vários
   `K_NEIGHBORS`/`FRAUD_THRESHOLD`, cada `byte[]` da tabela deve ser igual à serialização
   Jackson de referência do par `(approved, fraud_score)` correspondente. Cobre os limites
   (`fraudCount=0` e `fraudCount=k`) e a fronteira de decisão do `threshold`.
2. **Seletor top-k** — teste de **equivalência**: para entradas variadas (distâncias
   aleatórias, empates, mais/menos candidatos que k), o seletor retorna exatamente os mesmos
   k vizinhos e na mesma ordem que uma referência (PriorityQueue/Max-Heap ou ordenação
   completa). Garante recall idêntico.
3. **Page pre-warmer** — teste **leve**: sobre um `.v2` de teste, o aquecimento percorre o
   mapeamento sem lançar exceção e toca o número esperado de páginas (cobertura completa do
   arquivo). Não testa latência (não-determinística em CI).

Guarda de regressão (existente, deve permanecer verde): `V2QualityGuardTest` e
`V2IvfSearchTest` confirmam que o resultado da busca e o recall não regridem após o V3-D.
`V2EndToEndTest` confirma o fluxo build → HTTP com a resposta pré-serializada.

## Fora do escopo

- **V3-C (nginx stream + Unix domain sockets):** removido/adiado. A troca `http{}`→`stream{}`
  rende ~nada e perde keepalive/balanceamento HTTP; a parte valiosa (Jetty em UDS) é cara,
  frágil e seria reescrita pelo V4-E (servidor NIO) e V6 (fd-passing).
- **Parser de entrada custom (V4-C):** o Jackson na desserialização do request permanece. A
  V3-B só elimina a serialização da resposta.
- **Bounding-box pruning / busca exata (V4-A):** a V3 não muda o algoritmo; FP/FN
  estruturais do IVF aproximado permanecem inalterados.
- **Caminho `nearestFraudCount` (contagem direta sem materializar k objetos):** refinamento
  do V4.
- **GraalVM Native Image (V5), fd-passing (V6), knowledge distillation (V7):** fases
  posteriores.

## Notas adicionais

- **Ordem de execução e medição:** (0) pin do envelope → (1) baseline K6 com a config
  correta → (2) V3-A e medir → (3) V3-B e medir → (4) V3-D e medir → (5) guarda de
  qualidade + teste de contrato → (6) registrar resultados na seção "Resultados" do doc da
  V3. A V3 é sobre variância, então a medição incremental importa tanto quanto o código.
- **Sinais esperados:** V3-A achata a cauda cold (request 1 de ~130ms para a faixa de
  regime); V3-B + V3-D reduzem avg e p99 em regime quente (menos GC, menos trabalho por
  requisição), sem impacto em FP/FN.
- **Documentação:** ao fechar a V3, atualizar `docs/knowledge/v3/01-celeritas.md` com os
  números medidos, conforme a preferência de documentar decisões técnicas (o porquê, não só
  o quê) na subpasta versionada.
