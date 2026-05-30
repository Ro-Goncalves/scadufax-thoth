Essa é uma dúvida fantástica e mostra que você está pensando como um verdadeiro Engenheiro de Performance.

A resposta curta é: **Não. Você não terá absolutamente nenhum impacto negativo de performance ou de memória ao deixar várias implementações de código prontas no projeto, desde que apenas uma seja instanciada e executada por vez.**

Na verdade, essa é a abordagem mais profissional (conhecida como *Pluggable Architecture* ou *Strategy Pattern*) para participar de competições de performance como a Rinha de Backend. Você deve ter o seu laboratório pronto para testar diferentes motores.

Para você entender o *porquê* disso e ter segurança na sua arquitetura, vamos olhar para como a Máquina Virtual Java (JVM) gerencia isso nos bastidores:

### 1. Memória Heap (Os 150MB) vs. Metaspace

Como conversamos antes, a memória *Heap* (onde moram os objetos criados com `new`) é o nosso bem mais precioso.

* Quando você tem 10 classes diferentes que implementam `DistanceCalculator` (ex: `EuclideanCalculator`, `CosineCalculator`, `ManhattanCalculator`, etc.), mas no seu `AppConfig` você faz o `new` em apenas **uma** delas, as outras 9 jamais tocarão na memória *Heap*.
* *"Mas o código escrito ocupa espaço na RAM?"* Sim, ocupa um espaço ínfimo numa área chamada **Metaspace**. Cada classe a mais adiciona apenas alguns kilobytes nessa área. A menos que você crie dezenas de milhares de classes, o impacto no Metaspace é completamente invisível e não vai estourar o seu contêiner.

### 2. A Mágica do JIT Compiler (Inlining e Monomorfismo)

Você poderia pensar: *"Se eu usar uma Interface (`DistanceCalculator`), o Java vai perder tempo tentando descobrir qual das implementações ele deve chamar a cada milissegundo?"*

Se fosse no C++ antigo ou em linguagens não otimizadas, a resposta seria sim (devido ao custo do *Virtual Method Dispatch*). Mas no Java moderno, nós temos um "monstro" trabalhando a nosso favor: o **JIT (Just-In-Time) Compiler**.

* **O Cenário Monomórfico:** Quando a sua aplicação subir e o JIT Compiler perceber que, apesar de existirem 5 implementações da interface perdidas no projeto, o seu `MmapBruteForceSearcher` está chamando **sempre a mesma** (ex: `EuclideanCalculator`), ele classifica essa chamada como *Monomórfica* (uma única forma).
* **A Otimização (Inlining):** O JIT Compiler vai, em tempo de execução, apagar a interface da memória da CPU e "copiar e colar" o código matemático de dentro da calculadora diretamente para dentro do laço `for` do buscador. É o famoso *Inlining*.
* **O Resultado:** O custo da chamada do método desaparece completamente. A performance será idêntica a se você tivesse escrito a matemática diretamente dentro da classe do buscador (mas mantendo o código limpo e modular para você trabalhar).

### Como estruturar o seu "Laboratório de Testes"

A melhor forma de fazer isso na Rinha sem precisar ficar reescrevendo código é usar **Variáveis de Ambiente** no momento de subir o Docker.

No seu `AppConfig` ou `JavalinBootstrap`, você pode fazer algo assim:

```java
public void startServer() {
    // Lê a variável do docker-compose ou do sistema
    String strategy = System.getenv().getOrDefault("SEARCH_STRATEGY", "EUCLIDEAN");

    DistanceCalculator calculator;
    
    // Escolhe a estratégia baseada na variável
    if ("COSINE".equals(strategy)) {
        calculator = new CosineSimilarityCalculator();
    } else if ("QUANTIZED".equals(strategy)) {
        calculator = new QuantizedDistanceCalculator(); // Aquele insight do Go!
    } else {
        calculator = new EuclideanDistanceCalculator(); // O nosso atual
    }

    Arena globalArena = Arena.ofShared();
    VectorSearcher searcher = new MmapBruteForceSearcher("dataset.bin", globalArena, calculator);
    TransactionVectorizer vectorizer = new TransactionVectorizer(normMap, mccMap);

    SearchHandler searchHandler = new SearchHandler(searcher, vectorizer);

    Javalin app = Javalin.create()
        .post("/api/analyze", searchHandler)
        .start(8080);
}

```

Dessa forma, você cria várias configurações no seu `docker-compose.yml`, roda os testes com o **K6**, e descobre empiricamente qual delas te dá o menor tempo de resposta (latência) e o maior *throughput* (requisições por segundo). A que vencer, você manda para a submissão final!