# Arquitetura da Camada Web: Roteamento e Handler

O ponto de contato direto com o *Load Balancer* e o motor de testes K6 é o nosso `SearchHandler`. Em frameworks corporativos tradicionais, é padrão a adoção de uma arquitetura em camadas rígidas, Controller -> Service -> Repository. Para o *Scadufax Thoth*, que opera com restrições extremas de CPU e necessidade de latência na casa dos microssegundos, essa abstração clássica torna-se um gargalo.

## O Padrão "Service-less" e o Reducionismo da Pilha

Em vez de criar um `FraudService` isolado para calcular a proporção de fraudes, decidimos alocar essa regra de negócio diretamente dentro do `SearchHandler`.

**A justificativa técnica:**

1. **Redução da Pilha de Chamadas (Call Stack):** Cada salto de método entre classes diferentes obriga a JVM a empilhar novos *frames* de execução na memória e a realizar saltos de ponteiro. Colocar a lógica no próprio Handler corta intermediários e mantém a execução no Cache L1 da CPU.
2. **Coesão Extrema:** O Handler é responsável por receber o JSON e devolver o JSON de resposta. Como a regra de aprovação é uma operação de três linhas, a separação dessa lógica adicionaria verbosidade sem trazer nenhum benefício de reuso.

## A Fuga do Garbage Collector: Injeção Estática

Uma armadilha comum em APIs modernas que utilizam lambdas para definição de rotas é a instanciação de objetos por requisição.

O que evitamos ativamente (Anti-Pattern):

```java
// O que NÃO fazer: Instanciar objetos no momento da requisição
app.post("/fraud-score", ctx -> new SearchHandler(searcher, vectorizer).handle(ctx));

```

A abordagem acima obrigaria o *Garbage Collector* a limpar um novo objeto `SearchHandler` a cada requisição processada. Se o tráfego atingir 2.000 requisições por segundo, teríamos 2.000 novos objetos sendo criados e destruídos no mesmo intervalo.

Para mitigar isso, o `SearchHandler` é construído como um **Singleton Puro** na classe de *Bootstrap*. Ele é instanciado uma única vez quando a aplicação sobe e sua referência de memória é injetada estaticamente na rota do Javalin.

## Imutabilidade e Thread-Safety com Virtual Threads

O Javalin foi configurado para processar as requisições utilizando as *Virtual Threads* do Java 21+. Para que essa concorrência massiva funcione sem corromper a memória, o `SearchHandler` e suas dependências foram projetados sob o paradigma da **Imutabilidade**.

1. O `TransactionVectorizer` carrega os mapas de configuração no *boot* e apenas aplica matemática pura.
2. O `VectorSearcher` apenas lê o arquivo em modo *Read-Only* através da *Arena* nativa.
3. O `SearchHandler` atua apenas como um maestro do fluxo de execução.

Essa ausência de estado mutável garante que não precisamos de blocos `synchronized` ou *locks* de memória, permitindo que milhares de *Virtual Threads* atravessem essas classes simultaneamente em velocidade máxima, sem nunca colidirem ou gerarem condições de corrida.