# Especificação Arquitetural V2: Projeto Tesseract

## Visão Geral e Objetivo

A Linha de Base provou a estabilidade da nossa infraestrutura de memória, mas esbarrou no limite matemático da complexidade `O(N)`. Com uma latência média de `1.11s` para varrer 3 milhões de registros, o processador atinge 100% de uso apenas calculando distâncias de ponto flutuante.

O **Projeto Tesseract** tem o objetivo de tirar o backend do território de segundos e levá-lo para uma faixa competitiva de milissegundos baixos. Nesta V2, o objetivo não será prometer `1ms` imediatamente, e sim consolidar uma redução agressiva de latência com baixo risco arquitetural, preservando corretude e preparando o terreno para perseguir `1ms` em uma V3.

### Meta Realista da V2

Como a V1 opera na faixa de `1.11s` de média e quase `2s` nos percentis altos, a V2 será considerada bem-sucedida se atingir os seguintes critérios:

* **Meta principal:** reduzir o `p99` do teste oficial para **até 25ms**.
* **Meta esticada:** aproximar-se da faixa de **10ms** através de calibração de `K` e `nprobe`.
* **Meta de estabilidade:** manter `0` erros HTTP e taxa total de falhas de detecção confortavelmente abaixo do corte de `15%`.

Essa meta já representa uma melhoria de mais de uma ordem de grandeza sobre a V1, é compatível com o modelo de pontuação da Rinha e ainda deixa espaço claro para uma V3 focada em micro-otimizações.

## Pilar 1: Quantização Escalar

Na V1, cada dimensão do vetor foi armazenada como `Float` (4 bytes). Um vetor de 14 dimensões ocupa 56 bytes.
Nesta nova versão, aplicaremos a **Quantização Escalar**, convertendo todos os valores para inteiros de 1 byte.

### A Matemática da Compressão

Como o nosso `TransactionVectorizer` já aplica uma função de *Clamp* garantindo que quase todos os valores residam no intervalo entre `0.0` e `1.0`, podemos mapear esse espectro diretamente para o limite positivo de um byte. A única exceção são as dimensões que hoje usam o valor sentinela `-1.0` quando `last_transaction` é nulo.

* **Decisão:** reservar o valor `-128` como sentinela fixo para ausência de transação anterior.
* A fórmula de quantização dos valores normalizados válidos será: `(byte) (valorFloat * 127)`.
* Exemplos: `-1.0` sentinela torna-se `-128` | `0.0` torna-se `0` | `0.5` torna-se `63` | `1.0` torna-se `127`.

Essa decisão evita colisão entre dados reais e o caso especial de ausência de histórico, preserva a semântica do vetor original e mantém a aritmética da distância em domínio inteiro.

### Benefícios Arquiteturais

1. **Redução Drástica de I/O e Memória:** O payload vetorial cairá para a casa de **~42MB**, e o artefato completo da V2 ficará próximo de **~48MB + metadados compactos**. Isso reduz fortemente a pressão no *Page Cache* do Sistema Operacional quando comparado ao layout atual em `float32`.
2. **Vetorização SIMD (Single Instruction, Multiple Data):** Processadores modernos processam operações aritméticas de subtração e multiplicação de números inteiros de forma imensamente mais rápida e paralelizada do que números de ponto flutuante. A carga sobre a vCPU será mitigada instantaneamente.

### Formato Binário Fechado da V2

O artefato binário da V2 deixa de ser um formato textual travestido de binário e passa a ser explicitamente orientado à busca vetorial.

#### Layout do Arquivo

`[Header Fixo] -> [Diretório de Clusters] -> [Blocos de Registros]`

#### Header Fixo

O cabeçalho da V2 deve registrar, no mínimo:

* versão do formato;
* total de dimensões (`14`);
* modo de quantização (`int8` com sentinela `-128`);
* quantidade de clusters;
* offset do diretório de clusters;
* offset da área de dados.

#### Diretório de Clusters

Cada entrada do diretório de clusters deve conter:

* centróide quantizado do cluster;
* raio máximo do cluster no mesmo domínio da métrica de distância;
* offset inicial do bloco daquele cluster;
* quantidade de registros no bloco.

#### Registro de Vetor

Cada vetor persistido em disco terá registro físico de tamanho fixo, favorecendo salto direto por offset:

* `1 byte` para o rótulo: `0 = legit`, `1 = fraud`;
* `14 bytes` para o vetor quantizado;
* `1 byte` reservado para alinhamento e uso futuro.

Com isso, cada registro ocupa **16 bytes fixos**, e o runtime deixa de carregar `String`, tamanho de `label` ou dimensão por registro. A identidade do vizinho não é relevante para o cálculo final; a API só precisa saber se ele representa fraude ou legítimo.

## Pilar 2: Particionamento de Espaço

Para deixar de ser `O(N)`, não podemos calcular a distância contra todo o banco de dados. Precisamos dividir o espaço vetorial em "bairros" ou "caixas".

### A Maldição da Dimensionalidade e a Rejeição da Ordenação Interna

Em um plano 1D (uma régua), poderíamos dividir os dados em caixas e ordenar os números internamente do menor para o maior. No entanto, operamos num espaço de **14 dimensões**. Não existe um conceito absoluto de "maior" ou "menor" universal que permita uma ordenação linear simples e eficiente de um vetor inteiro. Tentar manter uma árvore ordenada de 14D no momento da busca destruiria a performance.

**Decisão:** Não haverá ordenação interna dentro dos agrupamentos. O motor lerá os blocos de forma sequencial, confiando na velocidade bruta da CPU operando sobre `int8`.

### A Solução: Esferas Geométricas

Em vez de "caixas" quadradas, o espaço será particionado em **Esferas Geométricas** utilizando o algoritmo não-supervisionado *K-Means*.

1. **O Centróide:** Durante o *Build Time*, o algoritmo agrupará os 3 milhões de registros em um número experimental de clusters. A V2 **não congela** esse valor em definitivo.
2. **O Raio de Contenção:** Para cada cluster, calcularemos a distância do Centróide até o vetor mais distante que pertence àquele grupo. Este "Raio" atua como a fronteira limite da nossa esfera.
3. **Busca com `nprobe`:** No momento da requisição, a API calculará primeiro a distância da transação para todos os centróides e visitará apenas os `nprobe` clusters mais promissores.
4. **Poda Algorítmica:** Se a `Distância até o Centróide - Raio` for **maior** do que a pior distância que já temos no nosso Top 5 atual, é matematicamente impossível que o vizinho ideal esteja dentro daquela esfera.
5. **Ação:** O buscador "pula" essa esfera inteira, evitando o cálculo de milhares de vetores.

### Parâmetros Experimentais da V2

Para permitir testes A/B reais sem reescrever o motor a cada rodada, a V2 tratará os dois parâmetros centrais do IVF como variáveis de experimento:

* **Quantidade de clusters (`K`)**: faixa inicial de testes `256`, `512` e `1024`.
* **Quantidade de clusters visitados (`nprobe`)**: faixa inicial de testes `1`, `2` e `4`.

O primeiro baseline funcional pode começar em `K = 256` e `nprobe = 1`, mas isso será apenas um ponto de partida operacional e não uma decisão arquitetural definitiva.

### Como o K-Means Entrará no Build Sem Explodir o Docker

O ponto crítico da clusterização offline é o custo de *build*. O K-Means "de livro" escolheria centróides, varreria os 3 milhões de vetores, recalcularia os centróides e repetiria esse ciclo várias vezes até convergir. Na prática, isso faria o `docker build` reler quase toda a base muitas vezes, consumindo CPU de forma agressiva e tornando o tempo de build imprevisível.

Para a V2, a decisão será mais pragmática:

1. gerar centróides iniciais a partir de amostragem ou mini-batch;
2. executar uma passada linear sobre a base completa para descobrir a qual cluster cada vetor pertence, quantos vetores cada cluster terá e qual é o seu raio máximo;
3. executar uma segunda passada linear para gravar os blocos finais no formato IVF, usando offsets já conhecidos.

Em outras palavras: a V2 evita um K-Means acadêmico completo até convergência global e adota um processo offline previsível, mais linear e mais compatível com a esteira de build do projeto.

## Roadmap de Implementação

Para garantir a estabilidade e rastrear exatamente onde ganhamos performance, a V2 será implementada e avaliada em três etapas discretas:

### Fase 2.1: Implementação da Quantização

* **Escopo:** Alterar o `DatasetBuilder` para gravar os vetores como bytes, usando o domínio `0..127` para valores normalizados e `-128` como sentinela. Atualizar o `TransactionVectorizer` e o `DistanceCalculator` para operar com matemática de inteiros.
* **Validação:** Rodar o K6 contra o motor de Força Bruta atual, agora lendo bytes.
* **Meta:** Validar se a matemática continua correta e obter uma queda clara de latência sem regressão funcional, preparando um baseline quantizado confiável para o IVF.

### Fase 2.2: Clusterização Offline

* **Escopo:** Adicionar a clusterização offline no `DatasetBuilder`, tratando `K` como parâmetro experimental. Gerar centróides iniciais, agrupar os vetores, calcular os raios e regravar o arquivo `.bin` no formato estruturado da V2 (`[Header] -> [Diretório de Clusters] -> [Blocos de Vetores]`).
* **Validação:** Medir o tempo de `docker build`, validar o tamanho final do artefato e garantir que a geração do IVF permaneça previsível e reprodutível.
* **Meta:** Produzir um artefato pronto para salto por offset, com build aceitável e sem transformar a esteira em gargalo operacional.

### Fase 2.3: O Motor IVF

* **Escopo:** Substituir o `MmapBruteForceSearcher` pelo novo `IvfVectorSearcher`. Implementar a lógica de busca em duas etapas: varrer os centróides, visitar apenas os `nprobe` clusters selecionados e aplicar a lógica de salto de borda (*Pruning*).
* **Validação:** Rodar o K6 Oficial.
* **Meta mínima de sucesso:** `p99 <= 25ms`, `0` erros HTTP e taxa de falhas muito abaixo do corte da competição.
* **Meta esticada:** aproximar-se de `10ms` através de A/B de `K`, `nprobe` e ordenação de visita dos clusters.

## Fazer Depois: Candidatos Naturais para a V3

Os itens abaixo ficam explicitamente fora do escopo desta V2. Eles entram como próximos candidatos de otimização depois que quantização e IVF estiverem estáveis e medidos.

1. **Parser numérico de timestamp:** remover `OffsetDateTime.parse()` do hot path e extrair hora e dia da semana diretamente da string ISO 8601.
2. **SIMD via Vector API:** acelerar a distância intra-cluster e o cálculo contra centróides com instruções vetoriais explícitas.
3. **Avaliação de L1 em vez de L2:** medir se a distância Manhattan sobre `int8` mantém qualidade suficiente com custo computacional menor.
4. **Quantização e pesos por dimensão:** testar se algumas dimensões merecem escala ou peso específico para melhorar ranking sem estourar o orçamento de CPU.
5. **Tuning fino de runtime:** revisar GC, flags da JVM e eventual AOT apenas se profiling mostrar ganho real depois que o motor de busca estiver consolidado.
6. **Meta agressiva de latência:** perseguir `p99 <= 1ms` como objetivo de refinamento extremo, e não como pré-condição desta V2.
