# Glossário Arquitetural: Scadufax Thoth

## 1. Memória e Acesso ao Sistema Operacional

* **Heap:** A área de memória RAM principal e tradicional gerenciada pela JVM (Java Virtual Machine). É onde todos os objetos criados com a palavra reservada `new` vivem. Se encher, a aplicação sofre lentidão ou trava com `OutOfMemoryError`.
* **Off-Heap (Memória Nativa):** Memória RAM alocada diretamente no Sistema Operacional, fora da jurisdição e dos limites da Heap da JVM. O Java não limpa essa memória automaticamente, ela precisa ser gerenciada manualmente ou via *Arena*.
* **FFM API (Foreign Function & Memory API):** Introduzida recentemente no Java (via *Project Panama*), é a API segura e moderna que permite ao Java conversar com bibliotecas escritas em C/C++ (Foreign Functions) e manipular a memória nativa/Off-Heap sem usar métodos perigosos do passado (como o `sun.misc.Unsafe`).
* **Memória Virtual:** Uma ilusão criada pelo Sistema Operacional para os programas. O SO junta a RAM física e partes do disco rígido e finge que é um bloco contínuo e infinito de memória.
* **Memory-Mapped Files (mmap):** Um truque do Sistema Operacional onde ele "encaixa" um arquivo físico do disco diretamente dentro do espaço de Memória Virtual do processo. A aplicação lê o arquivo usando ponteiros de memória em vez de conexões tradicionais (`FileInputStream`).
* **Page Cache:** Uma área invisível da RAM onde o Sistema Operacional guarda partes de arquivos recentemente lidos do disco. Se mapearmos um arquivo via *mmap*, o SO inteligentemente usará o Page Cache para manter os dados mais acessados na memória física.
* **Page Fault:** Ocorre quando o nosso leitor tenta acessar um byte do arquivo mapeado que não está na RAM no momento. O SO pausa a nossa *thread* por uma fração de milissegundo, vai buscar o pedaço no SSD, carrega na RAM e deixa a *thread* continuar.
* **Zero-Copy:** Uma técnica de altíssima performance onde nós evitamos copiar o mesmo dado entre diferentes áreas da memória. Em vez de copiar os dados do arquivo para dentro da Heap do Java para depois ler, a CPU lê o dado diretamente no *buffer* do Sistema Operacional.

## 2. Estruturação e Gravação de Dados

* **Endianness:** A convenção sobre a ordem em que a CPU lê e escreve os bytes de números inteiros e flutuantes na memória.
* **BIG_ENDIAN:** A ordem "natural" para leitura humana. O byte mais significativo (o "maior" valor) é gravado no menor endereço de memória. É o padrão de protocolos de rede e do formatador nativo do Java.
* **LITTLE_ENDIAN:** A ordem inversa. O byte menos significativo vem primeiro. É o padrão da esmagadora maioria dos processadores Intel/AMD (x86) e arquiteturas modernas.
* **Type-Length-Value (TLV):** Padrão de design binário para serialização de dados. Você primeiro avisa o que é (Tipo), depois o tamanho exato em bytes (Length) e, por fim, o valor da carga (Value). Dispensa completamente o uso de aspas e vírgulas.
* **Length-Prefixed:** Sub-técnica do TLV utilizada no nosso dataset, onde o tamanho de uma string dinâmica é cravado na frente dela mesma na memória para evitar buscas "byte a byte" pelo caractere finalizador.
* **Downcasting:** O ato deliberado de converter um tipo de dado de alta precisão e alto custo (como `Double` de 64 bits) para um tipo de menor precisão (como `Float` de 32 bits) com o intuito primário de economizar espaço em disco e RAM.

## 3. Comportamento em Tempo de Execução e Otimizações

* **On-the-fly:** Literalmente "em voo". Processar, descompactar ou converter dados em tempo real na memória (Stream) enquanto o arquivo está sendo lido, sem nunca criar um arquivo temporário gigante no disco.
* **Build Time:** Fase de construção do software (por exemplo, quando o Docker está empacotando o projeto). Fazer um processamento *at build time* (como o K-Means ou DatasetBuilder) significa gastar CPU uma única vez para poupar CPU durante o *Runtime* (quando a aplicação está online recebendo requisições).
* **JIT Compiler (Just-In-Time):** O motor da JVM que vigia o código enquanto ele roda, identifica métodos chamados milhões de vezes (como o nosso `EuclideanDistanceCalculator`) e os compila "em tempo real" para a linguagem de máquina (Assembly) mais rápida possível que a sua CPU atual suportar.
* **Hardware Intrinsics:** Uma otimização profunda onde o JIT Compiler do Java ignora a sua implementação e substitui uma instrução por um atalho direto do hardware do processador. O nosso uso do `Math.clamp` aciona intrinsics do processador para ganho de velocidade.
* **Garbage Collector (GC):** O zelador da memória Java. Ele vasculha a Heap procurando por objetos soltos que não estão mais sendo usados e os destrói. Quando a carga é extrema, o GC pode "congelar" a aplicação (*Stop-the-World pause*). O nosso motor é *Zero-Copy* justamente para deixar o GC desempregado.

## 4. Estruturas de Dados e Falhas Críticas

* **Min-Heap:** Estrutura de dados em árvore (como a `PriorityQueue` nativa do Java) onde o "menor" elemento de todos está sempre garantido na raiz (o topo) da árvore.
* **Max-Heap:** A versão invertida da estrutura acima, onde o "maior" elemento impera no topo. Usamos essa técnica de inversão para criar a fila do KNN: mantemos o "pior dos vizinhos" no topo para ser facilmente arrancado quando encontrarmos uma distância menor.
* **JPMS (Java Platform Module System):** O sistema moderno de módulos e segurança do Java (desde o Java 9) que bloqueia acessos internos indesejados. É ele que nos obriga a passar a flag `--enable-native-access` para usar a FFM API.
* **Segmentation Fault (SegFault):** O pesadelo de programadores C/C++. É um sinal de aborto fatal disparado pelo Sistema Operacional quando um processo tenta acessar um pedaço de memória que não foi alocado para ele ou tenta escrever numa área "Apenas Leitura". Errar a lógica do nosso cursor *offset* causaria isso em linguagens nativas.
