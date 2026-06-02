# Registo Arquitetural V2: Estabilidade de Máquina e Micro-Otimizações

Este documento regista as descobertas de desempenho e as otimizações de baixo nível aplicadas ao `V2IndexSearcher`. Durante os testes de carga, identificámos comportamentos de latência não-determinísticos e gargalos de memória invisíveis que impediam a aplicação de atingir o limite dos sub-milissegundos.

## O Paradoxo das Flutuações de Latência

Durante a validação do hiperparâmetro $K=2048$, o *Score P99* apresentou uma variação drástica (de 133ms para 103ms) entre execuções sem qualquer alteração no código. Documentamos aqui os três "fantasmas" do estado da máquina que causam este comportamento:

* **O Fantasma do Page Cache (Cold vs. Hot Reads):** Como utilizamos *Memory-Mapped Files* (FFM API), a leitura do ficheiro `.bin` depende do Sistema Operacional. Na primeira execução (*Cold*), ocorrem *Page Faults* enquanto o disco SSD transfere blocos para a RAM, elevando a latência. Na segunda execução (*Hot*), os dados já residem no *Page Cache* físico, permitindo leitura direta à velocidade da memória, resultando numa queda drástica do P99.
* **Aceleração do JIT Compiler (Warm-up):** O motor da JVM (Just-In-Time Compiler) necessita de processar milhões de iterações do laço de cálculo de distância antes de o otimizar internamente (utilizando instruções de *hardware* SIMD vetoriais). Os primeiros segundos do teste K6 sofrem a penalização de correr código "não otimizado".

## 2. A Regra da Raiz Quadrada para Clusters ($K$)

Testes com $K=256$ revelaram que as "caixas" ficavam excessivamente pesadas (aprox. 11.718 vetores por cluster). Para aumentar o *Recall* (precisão) sem destruir o P99 com um `nprobe` elevado, adotámos a heurística padrão da indústria de *Approximate Nearest Neighbors*:

$$K \approx \sqrt{N}$$

Para os nossos 3.000.000 de registos, o número ideal de centróides situa-se na casa dos **1024 a 2048**. Isto reduz a quantidade de vetores por caixa (aprox. 1.400 a 2.900), permitindo que um `nprobe` de 4 a 8 visite uma área geométrica ampla lendo um número globalmente inferior de *bytes*.

## 3. Vazamentos Críticos e Soluções *Zero-Alocação*

A análise do código do `V2IndexSearcher` expôs dois gargalos mortais que ancoravam o P99. Foram aplicadas as seguintes soluções de *micro-otimização*:

### A Bomba do *Garbage Collector* (Auto-Boxing no Rank)

* **O Problema:** A ordenação das distâncias dos centróides utilizava `Integer[]`, o que forçava o Java a instanciar um objeto para cada índice. Com $K=2048$ e tráfego elevado, isto gerava mais de **100 milhões de objetos inúteis**, causando pausas severas de *Stop-The-World* no *Garbage Collector*.
* **A Solução (Bit-Shift):** Substituímos o *array* de objetos por um primitivo `long[]`. Empacotamos a distância calculada nos 32 bits da esquerda (alta) e o índice do *cluster* nos 32 bits da direita (baixa). Como o Java ordena de forma natural pelos bits mais altos, a instrução `Arrays.sort()` processa as distâncias com **zero alocações de memória**.

```java
// Otimização por Empacotamento de Bits (Bit-Shift)
long[] distAndIdx = new long[numClusters];
for (int c = 0; c < numClusters; c++) {
    long dist = centroidDist(q, centroids[c]);
    distAndIdx[c] = (dist << 32) | c; // Distância no topo, Índice na base
}
Arrays.sort(distAndIdx); // Ordenação puramente nativa

```

### Desperdício de I/O (*Lazy Evaluation* do Rótulo)

* **O Problema:** No laço interno, a aplicação lia da memória nativa o *byte* representativo da fraude (`labelByte`) e instanciava a `String` correspondente para **todos** os vetores analisados, mesmo sabendo que a grande maioria seria descartada pelo Max-Heap (`PriorityQueue`).
* **A Solução (Avaliação Preguiçosa):** A lógica foi invertida. O motor agora apenas calcula a distância. A chamada custosa `file.get(ValueLayout.JAVA_BYTE, ...)` e a atribuição da `String` só ocorrem **se e só se** o vetor provar matematicamente ter o direito de entrar no *Top K* da fila.

```java
// Avaliação Preguiçosa (Lazy)
double dist = calculator.calculateI8(q, file, recordBase + 1, DIMS);

if (pq.size() < k || dist < pq.peek().distance()) {
    // I/O nativo atrasado: Lê a string estritamente se entrar no Top 5
    byte labelByte = file.get(ValueLayout.JAVA_BYTE, recordBase);
    String label = labelByte == 1 ? "fraud" : "legitimate";
    // ... gestão da Priority Queue ...
}

```