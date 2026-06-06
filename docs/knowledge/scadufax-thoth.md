# Documento de Arquitetura e Diretrizes Técnicas

## Projeto Scadufax — RG Brain Labs

### Visão Geral e Restrições de Arquitetura

O sistema deve operar sob uma restrição estrita de infraestrutura: o limite combinado para todos os contêineres da solução (Load Balancer e APIs) é de **1 CPU e 350 MB de memória RAM**.

Para viabilizar a execução dentro destes limites e manter a competitividade (latência p99 inferior a 1ms), a estratégia central estabelece a **eliminação de bancos de dados externos**. A aplicação deve rodar de forma autossuficiente, com os dados carregados e processados inteiramente em memória. A alocação tradicional de vetores em `float32` (4 bytes por dimensão) resultaria em aproximadamente 168 MB apenas para os dados brutos, inviabilizando o uso de frameworks tradicionais. O projeto exige técnicas avançadas de compactação e estruturas de dados primitivas.

### Algoritmos de Busca Vetorial

A dimensionalidade do sistema (14 dimensões) inviabiliza o uso de métodos exatos baseados em divisão espacial geométrica.

* **Evitar Árvores Exatas:** Estruturas como KD-Tree, VP-Tree ou Ball Tree sofrem degradação de performance a partir de 10 dimensões. A busca nessas estruturas aproxima-se da complexidade da força bruta, calculando a distância exata contra quase toda a base de dados.
* **Adotar Approximate Nearest Neighbors (ANN):** A performance ideal exige a flexibilização da precisão perfeita em favor da velocidade, buscando os vizinhos mais próximos de forma aproximada.

| Algoritmo | Complexidade de Busca | Caso de Uso Recomendado |
| --- | --- | --- |
| **Força Bruta** | $\mathcal{O}(N \times D)$ | Utilização estrita como linha de base (baseline) para validação de integridade e precisão dos resultados iniciais. |
| **IVF (Inverted File Index)** | $\mathcal{O}(\sqrt{N})$ | Recomendado para implementação nativa. Agrupa vetores em partições ("baldes"), restringindo a busca linear apenas à partição mais relevante. |
| **HNSW (Hierarchical Navigable Small World)** | $\mathcal{O}(\log N)$ | Estrutura de grafos em camadas de altíssima performance. Recomendado apenas se houver integração viável com bibliotecas nativas, dado o alto consumo de memória. |

A escolha final deve respeitar não apenas a complexidade assintótica, mas principalmente a viabilidade operacional dentro das regras da Rinha. Na prática, a combinação entre limite de memória, custo de build e simplicidade de depuração favorece uma implementação própria baseada em IVF antes de qualquer tentativa de adoção de HNSW.

### Estratégia de Implementação de Busca e Evolução para IVF

A arquitetura alvo evolui para o modelo IVF aliado a técnicas de quantização e métricas de distância otimizadas para CPU. Contudo, a primeira implementação funcional adotará busca exata por força bruta, com k-NN e distância euclidiana, para maximizar aderência à especificação e simplificar a validação inicial do pipeline.

* **Baseline inicial:** A primeira entrega deve usar brute force com k-NN exato e distância euclidiana. A escolha existe para reduzir variáveis, facilitar a comparação com a regra oficial e garantir uma referência de corretude antes de qualquer aproximação.
* **Particionamento:** Em uma fase posterior, a base de 3 milhões de vetores deve ser dividida em partições através de algoritmos como K-Means. A busca em tempo de requisição ocorrerá apenas nas partições mais promissoras, reduzindo drasticamente o escopo de comparação. Como eixo de otimização, o número de partições (`K`) e a quantidade de partições visitadas por busca (`nprobe`) devem ser tratados como parâmetros experimentais. A primeira varredura operacional deve avaliar combinações como `K = 256`, `512` e `1024`, com `nprobe = 1`, `2` e `4`.
* **Quantização (int8):** Os valores dimensionais normalizados entre `0.0` e `1.0` devem ser convertidos para inteiros de 1 byte usando a faixa `0..127`. O valor `-128` deve ser reservado como sentinela para dimensões especiais, como ausência de `last_transaction`. O payload vetorial cai para cerca de ~42 MB, e o artefato completo permanece na casa de dezenas baixas de megabytes mesmo com rótulo binário e metadados de cluster, criando folga operacional para o Garbage Collector. É recomendável testar abordagens de quantização com pesos dinâmicos por dimensão e manter uma implementação sem quantização (`float32`) apenas como baseline de acurácia.
* **Métrica de Distância:** A baseline inicial adotará distância euclidiana (L2), por ser a forma mais direta de implementar o k-NN exato e a referência mais segura para validação de corretude. A distância Euclidiana exige operações custosas de multiplicação e raiz quadrada:

$$\text{dist}(q, r) = \sum_{i=1}^{14} (q_i - r_i)^2$$

Em contrapartida, a distância L1 baseia-se em subtrações absolutas, sendo processada de forma significativamente mais rápida em vetores quantizados:

$$\text{dist}(q, r) = \sum_{i=1}^{14} |q_i - r_i|$$

L1 permanece como hipótese de otimização posterior, a ser avaliada somente depois de consolidada a baseline exata e quando houver medição objetiva de ganho em latência sem degradação inaceitável na qualidade de detecção.

* **Estratégia de Evolução:** O desenvolvimento deve começar com uma busca por força bruta estritamente voltada à validação funcional do pipeline de vetorização, ranqueamento e cálculo de score. A evolução para IVF ocorre apenas após a confirmação de aderência às regras de detecção, evitando otimizações prematuras sobre uma base incorreta.

### Estratégia de Implementação Incremental

O projeto deve evoluir em fases curtas e verificáveis, preservando comparabilidade entre versões.

1. **Baseline funcional:** Implementar normalização, vetorização, brute force com k-NN exato, distância euclidiana e cálculo de `fraud_score` para validar corretude.
2. **Compactação de memória:** Introduzir quantização e armazenamento em arrays primitivos para reduzir footprint e pressão de GC.
3. **Busca aproximada:** Substituir a busca exata por IVF, começando com `nprobe = 1` e calibrando `K` conforme latência e qualidade.
4. **Micro-otimizações:** Avaliar SIMD via Vector API, ajustes de GC, compilação AOT e outras otimizações finas somente após estabilidade funcional.

Essa ordem é mandatória para evitar que ganhos de performance escondam desvios de comportamento em relação à especificação da Rinha.

### Ciclo de Vida do Sistema e Pré-processamento

O processamento do arquivo estrutural `.json.gz` não deve ocorrer durante a inicialização (boot) do contêiner. O ciclo de execução divide-se em duas fases:

**Fase A: Pré-processamento (Build Time)**

* Script acionado durante o `docker build`.
* Responsável inicialmente pela leitura do JSON e pela preparação dos dados para a baseline exata. Em fases posteriores, passa a assumir também normalização persistida, quantização e agrupamento dos vetores para IVF.
* Gera como artefato final um arquivo binário customizado (`dataset.bin`), estruturado puramente em bytes.

#### Formato do Artefato Binário

O layout do `dataset.bin` passa a ser uma decisão fechada da arquitetura de evolução para IVF.

* **Header fixo:** versão, quantidade de dimensões, modo de quantização, quantidade de clusters e offsets das tabelas internas.
* **Diretório de clusters:** centróide do cluster, raio máximo, offset inicial do bloco e quantidade de vetores daquele bloco.
* **Blocos de vetores:** registros de tamanho fixo, sem `String` e sem dimensão serializada por item.

Cada registro persistido deve conter:

* `1 byte` de rótulo binário (`0 = legit`, `1 = fraud`);
* `14 bytes` do vetor quantizado;
* `1 byte` reservado para alinhamento e evolução futura.

Com isso, o artefato passa a privilegiar salto por offset, leitura sequencial enxuta e custo mínimo de parsing no runtime.

**Fase B: Execução (Runtime)**

* A aplicação inicia executando o *dump* direto do arquivo `.bin` para matrizes primitivas unidimensionais em memória (ex: `byte[]`). O uso de abstrações orientadas a objetos (como `List<Transaction>`) é estritamente proibido para garantir alocação contígua em memória, eficiência de cache L1/L2 e eliminação do overhead de parsing JSON durante o boot.
* As requisições recebidas são vetorizadas e submetidas à busca. Em fases posteriores de otimização, poderão também ser quantizadas dinamicamente antes da etapa de consulta, culminando sempre no cálculo da razão de fraudes baseada no limiar (*threshold*) de `0.6`.

Essa separação entre build e runtime existe para tornar a subida do contêiner previsível e curta, reduzindo o custo de inicialização e evitando processamento estrutural em tempo de serviço.

### Stack Tecnológica e Frameworks

* **Linguagem Core:** Java 25 LTS com uso extensivo de *Virtual Threads* para suportar alta simultaneidade sem o custo de memória inerente a um *Thread Pool* tradicional. O uso eventual da Vector API deve ser avaliado conforme estabilidade da toolchain adotada no JDK 25 e benefício mensurável sobre o cálculo vetorial.
* **Build tool:** Maven, escolhido como ferramenta inicial de build por oferecer bootstrap simples, baixo atrito para a primeira estrutura do projeto e integração direta com o fluxo mais tradicional de empacotamento Java.
* **Framework HTTP:** Javalin, adotado como padrão da aplicação por oferecer inicialização leve, API objetiva para handlers HTTP e baixo atrito para rodar na JVM padrão dentro das restrições da competição.
* **Biblioteca JSON:** Jackson, inicialmente adotada por integração direta com Javalin, ecossistema maduro e velocidade adequada para a primeira versão funcional.
* **Load Balancer:** Nginx, escolhido como load balancer inicial, configurado com buffers reduzidos e logs desabilitados. O teto de consumo do LB deve ser fixado entre 15 e 20 MB.
* **Micro-frameworks vs. Spring Boot:** Frameworks tradicionais (como Spring Boot) exigem rotinas de Reflexão e Injeção de Dependência que consomem de 100 MB a 150 MB de RAM apenas para inicialização, exigindo obrigatoriamente a compilação *Ahead-of-Time* (AOT) via GraalVM para viabilidade. O projeto adota Javalin, um micro-framework sobre Jetty, para manter o consumo de memória baixo e preservar controle explícito sobre o ciclo de vida da aplicação.

#### Justificativa da Escolha por Maven

Maven foi escolhido para a fase inicial porque reduz o custo de bootstrap do projeto, favorece convenção sobre configuração e simplifica o caminho até a primeira aplicação executável. A decisão não parte de uma hipótese de maior performance em runtime, já que o impacto de Maven está concentrado no build e não na execução da API, mas sim de uma preferência por previsibilidade e menor atrito no início do desenvolvimento.

#### Justificativa da Escolha por Jackson

Jackson foi escolhida como biblioteca JSON inicial por ser uma opção madura, bem integrada ao ecossistema Java e suficiente para o contrato da API desta competição. Do ponto de vista de performance, seu impacto principal está no parsing e na serialização dos payloads HTTP, além das alocações transitórias associadas aos DTOs.

Para a baseline inicial, esse custo é aceitável porque a etapa dominante tende a estar no cálculo vetorial por brute force e não na camada JSON. Ainda assim, a escolha deve ser tratada como pragmática, não definitiva: se perfis futuros mostrarem que parsing e serialização estão pressionando latência ou memória, a substituição ou redução de overhead na camada JSON deve entrar no ciclo de otimização.

#### Justificativa da Escolha por Nginx

Nginx foi escolhido como load balancer inicial por ser estável, amplamente conhecido, simples de configurar para round-robin e compatível com uma estratégia de footprint reduzido. Em termos de performance, ele adiciona uma camada extra de proxy à requisição, o que inevitavelmente traz algum custo de cópia, bufferização e agendamento. Ainda assim, esse overhead tende a ser pequeno quando comparado ao custo do processamento principal da API, desde que a configuração permaneça enxuta.

Os riscos práticos para latência estão mais ligados a configuração excessiva do que ao Nginx em si. Por isso, a diretriz é operar com logs desabilitados, buffers pequenos, número de workers controlado e sem lógica adicional no balanceador.

#### Diretriz para Escolha de Framework

* **Spring Boot não é a opção inicial:** Embora produtivo em cenários corporativos, o custo de memória base e a complexidade do ecossistema tornam sua adoção inadequada para a primeira versão competitiva da solução.
* **Compilação nativa é uma otimização posterior:** AOT com GraalVM reduz drasticamente o footprint e o tempo de inicialização, mas introduz atrito de build, limitações com reflexão e maior complexidade de depuração. Deve ser tratada como etapa de refinamento, não como pré-requisito para validar a arquitetura.
* **Javalin é o framework escolhido:** A decisão prioriza simplicidade operacional, baixo overhead, startup rápido e integração direta com uma abordagem de handlers enxutos.

#### PGO exige Oracle GraalVM (não Community Edition)

* **Trade-off de toolchain:** O *Profile-Guided Optimization* (`--pgo`/`--pgo-instrument`) do Native Image é recurso **exclusivo do Oracle GraalVM**, distribuído sob a licença GFTC (gratuito para este uso). A *Community Edition* (`ghcr.io/graalvm/native-image-community`) **rejeita** o build com PGO. Por isso o estágio de build nativo do Dockerfile usa `container-registry.oracle.com/graalvm/native-image:25` — a imagem community vira o fallback que não habilita PGO.
* **Geração do perfil:** o `default.iprof` é gerado por um binary instrumentado rodando em container sob carga K6 real (smoke + ramp de 120s), committado em `src/main/resources/pgo/`, e re-injetado no build de produção via `--pgo`. O perfil é gerado sobre o hot path final (NioHttpServer + MappedByteBuffer + SearchState); só precisa ser regerado se o caminho de busca mudar. Ver `pgo-profile.sh` e [`v5/benchmark-opus.md`](v5/benchmark-opus.md) (seção V5-4).
* **Efeito medido:** o PGO sozinho dá ganho marginal de p50/p95. O salto real veio do **fix CFS-aware do busy-poll** (Issue 08): sob a stack constrangida (HAProxy + 2× 0,45 CPU), a combinação native+PGO+fix entrega **p50 0.67ms / p95 1.06ms / p99 1.344ms / score 5871.58** (mediana de 5 boots, spread de p99 de 12µs) — ver `v5/benchmark-opus.md` (V5-5). A cauda de ~57ms anterior era throttle de CFS causado por spin puro no reactor, não custo de busca nem limite do PGO.

#### Busy-poll vs. cota de CFS fracionária (lição central)

Um reactor em **busy-poll puro** (spin sem ceder CPU) só é ótimo num **core dedicado sem cota**. Sob `cpus<1.0` (cota de CFS, como os 0,45 vCPU/instância da Rinha), o spin queima a cota inteira girando e o kernel estrangula a instância pelo resto do período (~55ms a cada 100ms) — jogando p95/p99 para ~55ms **independente de K/nprobe**. A correção é ceder a CPU (`parkNanos`) sempre que um ciclo não acha I/O pronto, mantendo o uso abaixo da cota. Custo: ~50µs de latência por park, desprezível. Alternativa de custo zero: `Selector`/epoll, que dorme no kernel quando ocioso (é o que o Jetty fazia). O sintoma diagnóstico é **p99 alto e idêntico em todas as configs** + CPU pregada no teto da cota mesmo com pouca carga real.

#### Justificativa da Escolha por Java 25 LTS

Java 25 LTS permanece como a linguagem principal por equilibrar produtividade, controle de memória em baixo nível, suporte maduro a concorrência e acesso a otimizações modernas em uma versão de suporte estendido. A combinação de Virtual Threads, arrays primitivos e eventual uso de SIMD via Vector API fornece um caminho coerente entre baseline funcional e otimização extrema.

#### Justificativa da Escolha por Javalin

Javalin foi escolhido por entregar uma camada HTTP pequena, previsível e suficiente para os endpoints exigidos pela competição. A decisão reduz o custo estrutural da aplicação, simplifica o bootstrap do projeto e evita a complexidade adicional de frameworks maiores em um ambiente com restrição agressiva de memória.

### Gerenciamento de Memória e Garbage Collector (GC)

O gerenciamento de memória possui impacto direto no p99 de latência. Pausas de limpeza (*Stop-The-World*) podem gerar picos de latência destrutivos para a avaliação final.

Do ponto de vista operacional, o Garbage Collector atua como o mecanismo de limpeza da memória heap. Em cenários de alta pressão e baixa latência, pausas mesmo curtas podem deslocar o p99 para muito além da meta competitiva. Por isso, o impacto do GC deve ser tratado como uma variável arquitetural e não apenas como detalhe de runtime.

* **Afinamento (Tuning):** O uso de coletores conservadores como `SerialGC` deve ser avaliado para minimizar o rastro de memória (footprint) da própria JVM. Configurações que favorecem ciclos rápidos e frequentes mitigam congelamentos sistêmicos.
* **Benefício esperado:** O tuning do GC busca dois resultados concretos: reduzir a memória consumida pela própria JVM e limitar pausas perceptíveis durante picos de carga.
* **Faseamento:** O tuning do GC deve ser postergado para a fase de micro-otimização. A versão *baseline* deve concentrar-se na integridade funcional e na acurácia do cálculo de fraudes, permitindo que a JVM opere com suas heurísticas padrão nas iterações iniciais de desenvolvimento.

### Eixos Formais de Experimentação

Para facilitar rastreabilidade técnica e uso do repositório como portfólio, os experimentos devem ser organizados explicitamente nos seguintes eixos:

* **Estratégia de busca:** Força bruta, IVF com diferentes valores de `K` e IVF com diferentes valores de `nprobe`.
* **Representação do vetor:** `float32` como baseline de precisão, `int8` como estratégia principal e pesos por dimensão quando houver evidência empírica de ganho.
* **Métrica de distância:** L2 como baseline inicial aderente à referência de corretude e L1 como hipótese de otimização posterior.
* **Runtime:** JVM padrão como baseline operacional, AOT como etapa opcional de refinamento.
* **Camada de infraestrutura:** Nginx como load balancer inicial e Jackson como biblioteca JSON inicial, ambos sujeitos a revisão somente se profiling indicar gargalo real.

Cada alteração nesses eixos deve ser registrada com motivação, impacto esperado em memória e latência, e eventual efeito observado na qualidade de detecção.