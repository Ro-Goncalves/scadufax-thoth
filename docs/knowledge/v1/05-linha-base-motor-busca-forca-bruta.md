# Linha de Base: Motor de Busca por Força Bruta (K-Nearest Neighbors)

Este documento detalha a implementação da nossa primeira estratégia de busca vetorial. A abordagem de Força Bruta com Distância Euclidiana exata serve como o nosso *ground truth*. Ela garante precisão absoluta nos resultados, servindo de base para compararmos a performance de futuras otimizações heurísticas.

Devido à rigorosa restrição de **150MB de memória RAM**, o design desta camada foge das convenções tradicionais de desenvolvimento web e adota técnicas de alta performance e manipulação de memória de baixo nível.

## Modelos de Domínio

Como precisamos transitar dados em tempo de execução sem gerar *overhead* para o *Garbage Collector*, adotamos o uso de `records` do Java. Eles garantem imutabilidade, são alocados de forma otimizada pela JVM e dispensam código *boilerplate*.

* **`SearchResult`**
* **Responsabilidade:** Armazenar a distância calculada e o rótulo da transação. É a única estrutura instanciada durante o processo de busca, e de forma extremamente controlada.
* **Atributos:** `String label` (`"legit"`, `"fraud"`) e `double distance`.
* *Nota de Design:* O campo foi nomeado como `label` e não `id` pois, para o nosso modelo de detecção, não importa a identidade única do vizinho, mas sim a sua classificação (fraude ou legítimo) para o cálculo final do *score*.

> **Por que não criamos um objeto `ReferenceItem(label, vector)` para cada linha do banco?**
> Se instanciássemos um objeto para cada vetor lido do arquivo, a memória *Heap* do Java estouraria em poucos segundos. O vetor existe apenas como *bytes* na memória nativa e é lido pela CPU sob demanda.

## Conceitos de Infraestrutura e Algoritmo

Para operar em altíssima velocidade consumindo quase zero de RAM, a nossa busca baseia-se em dois pilares fundamentais:

### Acesso à Memória Nativa

No Java 21+, a interface `Arena` gerencia o ciclo de vida da memória *Off-Heap*, memória que pertence diretamente ao Sistema Operacional e fica fora da vigilância do Java. Ao contrário do modelo tradicional, onde o *Garbage Collector* limpa a memória de forma custosa e imprevisível, o `Arena.ofShared()` instrui o SO a mapear o arquivo `dataset.bin` na memória virtual permanentemente.

Esse mapeamento acontece uma única vez e é compartilhado por todas as requisições HTTP de forma segura, sem duplicação de dados, permitindo que a aplicação processe múltiplas buscas concorrentes lendo a mesma área de memória nativa.

### Otimização de Busca: A `PriorityQueue` como "Max-Heap"

A requisição exige que encontremos os `k = 5` vizinhos *mais próximos*. O método ingênuo seria calcular todas as 3 milhões de distâncias, salvar tudo numa lista e ordenar no final. Isso destruiria nossa memória e CPU.

A solução de engenharia elegante é usar uma `PriorityQueue`. Por padrão, o Java implementa filas como *Min-Heap*. Para o nosso caso, nós aplicamos um truque no método `compareTo` do `SearchResult`:

```java
// Invertido propositalmente para a PriorityQueue funcionar como um Max-Heap
return Double.compare(outraBusca.distance, estaBusca.distance); 

```

**Por que transformá-la num Max-Heap?**  
Ao inverter a ordem, **a MAIOR distância sempre fica no topo da fila**.  
Isso significa que mantemos na memória apenas os 5 melhores candidatos atuais. Quando calculamos um novo vetor, nós só precisamos compará-lo com o "pior dos melhores". Se a nova distância for menor, arrancamos o candidato do topo e inserimos o novo.

O resultado é que o consumo de memória espacial cai de `O(N)` para `O(K)`, onde K é sempre 5. A memória utilizada por requisição é praticamente nula, não importa o tamanho do *dataset*.

## Teste de Mesa: O Algoritmo em Ação

Para consolidar o entendimento, vamos simular passo a passo a execução do `BruteForceSearcher`. Imagine um *dataset* simplificado de 6 registros e queremos encontrar os `k = 3` mais próximos.

* **Distâncias reais calculadas sequencialmente:** `[10.0,  2.0,  8.0,  1.0,  9.0,  0.5]`

**Início:** Nossa `PriorityQueue` (`pq`) nasce vazia. Seu limite é 3.

**Passo 1 (Distância = 10.0):**
* A fila tem menos de 3 elementos? **Sim**.
* Adiciona na fila. -> `[10.0]` *(Topo: 10.0)*

**Passo 2 (Distância = 2.0):**
* A fila tem menos de 3 elementos? **Sim**.
* Adiciona na fila. -> `[10.0, 2.0]` *(Topo: 10.0)*

**Passo 3 (Distância = 8.0):**
* A fila tem menos de 3 elementos? **Sim**.
* Adiciona na fila. -> `[10.0, 8.0, 2.0]` *(Topo: 10.0)*
* ⚠️ *A fila encheu. A partir de agora, para um novo vizinho entrar, o topo precisa sair.*

**Passo 4 (Distância = 1.0):**
* A fila está cheia.
* O novo valor (1.0) é menor que o topo (10.0)? **Sim!**
* Removemos o topo (10.0) e inserimos o 1.0. A estrutura se auto-organiza.
* Nova fila: `[8.0, 2.0, 1.0]` *(Novo Topo: 8.0)*

**Passo 5 (Distância = 9.0):**
* O novo valor (9.0) é menor que o topo (8.0)? **Não**.
* Ignoramos solenemente o registro e avançamos. *(Zero impacto na memória)*

**Passo 6 (Distância = 0.5):**
* O novo valor (0.5) é menor que o topo (8.0)? **Sim!**
* Removemos o 8.0 e inserimos o 0.5.
* Nova fila: `[2.0, 1.0, 0.5]` *(Novo Topo: 2.0)*

**Conclusão da Busca:** O motor de busca pega esses 3 sobreviventes, reverte a ordem e retorna a lista final: `[0.5, 1.0, 2.0]`.

## Comportamento sob Alta Carga

A delegação do trabalho pesado de *parser* e leitura para a memória *Off-Heap*, combinada com o uso do *Max-Heap* que trava a criação de objetos na *Heap* em apenas `K` unidades por requisição, garante que:

1. **A aplicação pode suportar centenas de buscas concorrentes por segundo.**
2. O consumo de memória permanecerá **estável e estritamente abaixo dos 150MB** estipulados pelos requisitos arquiteturais.
3. O único gargalo real do sistema passa a ser a capacidade da CPU de realizar as subtrações e multiplicações vetoriais, um cenário onde a JVM e o *JIT Compiler* são extremamente otimizados para atuar.

### A Mecânica do Cursor de Deslocamento

A navegação pelo `MemorySegment` é feita gerenciando manualmente um ponteiro de memória. Para cada registro, o buscador realiza a seguinte coreografia de baixo nível:

1. Lê os primeiros 4 bytes para descobrir o tamanho do *label*. O cursor avança 4 posições.
2. Lê os próximos *N* bytes estritamente definidos pelo tamanho do *label*, convertendo a matriz de bytes em uma `String` UTF-8. O cursor avança *N* posições.
3. Lê os próximos 4 bytes para descobrir a quantidade de dimensões numéricas do vetor. O cursor avança 4 posições.
4. Delega a leitura das dimensões e o cálculo matemático para o `DistanceCalculator`, que avança o cursor em `(dimensões * 4)` bytes.

Esta arquitetura de *parsing* sequencial e determinístico elimina a necessidade de carregar o arquivo em memória principal ou utilizar delimitadores de texto (como quebras de linha ou vírgulas), o que demandaria uso intenso de *Regex* ou manipulação de *Strings*, destruindo a performance da CPU.

### Thread-Safety Sem Travas

O `MmapBruteForceSearcher` é um componente *Singleton* injetado no Handler. Quando o Javalin recebe um pico de requisições, ele delega o processamento para as *Virtual Threads* do Java 21+. Isso significa que milhares de *threads* podem invocar o método `.search()` simultaneamente.

**Como evitamos a corrupção de memória e as Condições de Corrida?**
1. **Memória Somente-Leitura (Read-Only):** O `MemorySegment` mapeado via `FileChannel.MapMode.READ_ONLY` garante que o Sistema Operacional bloqueie qualquer tentativa de escrita.
2. **Estado Local:** O cursor e a `PriorityQueue` não são variáveis de classe. Eles são instanciados dentro do escopo do método `.search()`. No modelo de memória do Java, variáveis locais ficam presas na pilha de cada *thread*.

Portanto, 5.000 requisições simultâneas resultarão em 5.000 cursores independentes lendo exatamente a mesma região da memória RAM física nativa, sem que uma *thread* bloqueie ou espere a outra, alcançando o *throughput* máximo absoluto do hardware sem utilizar um único bloco `synchronized`.

