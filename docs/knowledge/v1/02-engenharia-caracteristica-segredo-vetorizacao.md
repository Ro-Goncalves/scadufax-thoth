# Engenharia de Características (Feature Engineering): O Segredo da Vetorização

O nosso algoritmo K-Nearest Neighbors de força bruta não consegue "ler" um JSON e entender o que significa um número de parcelas ou se o cartão estava presente fisicamente. A matemática por trás do cálculo da distância lida exclusivamente com matrizes e vetores numéricos.

O papel do componente `TransactionVectorizer` é atuar como um tradutor universal: ele pega nas dezenas de campos complexos de uma requisição de transação e transforma-os num *array* unidimensional com exatamente **14 posições numéricas**.

## A Ilusão da "Perda de Precisão"

A regra de negócio exige que todos os valores fiquem estritamente entre `0.0` e `1.0`. Mas será que ao fazer isso não estamos a perder precisão nos nossos dados? A resposta é: **Sim, perdemos precisão absoluta, mas ganhamos equilíbrio matemático.**

Imagine calcular a distância Euclidiana sem normalizar:

* O campo `amount` (valor da compra) pode variar de R$ 0 a R$ 50.000.
* O campo `is_online` varia apenas de 0 a 1.
* O campo `hour_of_day` varia de 0 a 23.

Se colocarmos estes números puros na fórmula de Distância Euclidiana, o campo `amount` vai **esmagar e dominar** completamente o cálculo. A diferença de R$ 1.000 entre duas transações tornaria qualquer diferença de horário ou de presença do cartão estatisticamente irrelevante para o algoritmo. O modelo ficaria "cego" a tudo o que não fosse dinheiro.

O limite máximo diz ao modelo: *"Qualquer transação acima de 10 mil reais é considerada de Altíssimo Risco/Valor Extremo"*.

Para o algoritmo encontrar padrões de fraude, a diferença entre gastar 15 mil ou 50 mil não importa; ambas são anomalias que atingiram o teto da escala. Troca-se a precisão de centavos pelo alinhamento de todas as 14 dimensões numa escala perfeitamente justa e comparável.

## Normalização. O Conceito do *Clamp*

Em Machine Learning e cálculos de distância vetorial, se uma dimensão do seu vetor variar de `0` a `100` e a outra variar de `0` a `10000`, a dimensão maior vai "esmagar" a menor. O cálculo da distância euclidiana seria inteiramente dominado por atributos como `amount` em detrimento da `hour_of_day`.

Para manter o equilíbrio, o `REGRAS_DE_DETECCAO.md` estabelece limites máximos. Nós dividimos o valor da transação pelo limite, transformando tudo em uma escala percentual decimal (`0.0` a `1.0`).

Implementamos o conceito matemático de *Clamp*. Se uma transação vier no valor de `R$ 15.000`, a divisão daria `1.5`. A função `clamp` força esse valor de volta para o limite máximo de `1.0`. Valores negativos seriam forçados a `0.0`. 

No desenvolvimento do `TransactionVectorizer`, nosso objetivo é garantir que os 14 valores calculados fiquem estritamente entre `0.0` e `1.0`.

Historicamente no Java, a técnica para atingir esse teto e piso era aninhar métodos: `Math.max(0.0f, Math.min(1.0f, valor))`. Isso exigia a avaliação de duas funções distintas pela CPU.

Como utilizamos uma JVM moderna (Java 21+), adotamos a API nativa `Math.clamp(valor, min, max)`.

Além de expressar a nossa intenção de forma muito mais clara, o método `clamp` nativo sinaliza à JVM o que queremos fazer. Isso permite que a máquina virtual utilize instruções embutidas e otimizadas do próprio processador para calcular o limite em uma única operação de ciclo de máquina, elevando o nosso *throughput* nas operações matemáticas.

## O Valor Sentinela e o Espaço Vetorial

As posições `[5]`, minutos desde a última transação e `[6]`, km desde a última transação, possuem uma peculiaridade: clientes novos não têm "última transação".

Um desenvolvedor inexperiente poderia pensar: *"Se não existe histórico, eu coloco `0.0`"*. Se fizesse isso, o KNN assumiria que o cliente novo acabou de fazer uma transação a zero minutos atrás e a zero quilómetros de distância. Ele seria agrupado por engano com clientes hiperativos (um forte indício de fraude).

Seguindo o modelo do *dataset*, nós injetamos o valor sentinela `-1.0`. Como todos os outros valores normalizados estão entre `0.0` e `1.0`, colocar um `-1.0` cria uma "ilha" isolada no espaço multidimensional. Automaticamente, a nossa matemática fará com que clientes sem histórico fiquem "longe" de clientes normais, mas incrivelmente "próximos" de *outros* clientes sem histórico no banco de dados. A geometria resolve a regra de negócio sozinha.

## Armadilhas Ocultas Tratadas

Para garantir que a aplicação não sofra *crashes* silenciosos em produção, o nosso vetorizador trata duas armadilhas críticas:

### Divisão por Zero (`amount_vs_avg`)

Se um cliente novo tem a média de gastos zerada, tentar dividir o valor da transação atual por zero geraria um `NaN` ou `Infinity` no Java.

Injetar um `NaN` no cálculo de distância Euclidiana destruiria completamente a `PriorityQueue`, pois não é possível comparar matematicamente se `NaN` é maior ou menor que um número real. A aplicação pararia de funcionar corretamente. Adicionámos uma proteção explícita para prevenir isto.

### O Perigo dos Fusos Horários

A fórmula para extrair a hora do dia assume que o sistema processa a data a partir da *string* UTC.

Utilizamos a classe moderna `OffsetDateTime.parse` do Java 8+, que retém o contexto do fuso horário original. Se tivéssemos utilizado classes antigas, o servidor em produção calcularia a hora local da máquina, alterando completamente a dimensão do vetor e invalidando a comparação com o *dataset* oficial.

### Tratamento de Datas em UTC

O nosso dataset utiliza datas no formato ISO 8601 UTC (ex: `2026-03-11T18:45:53Z`). 

Utilizamos o `OffsetDateTime` do Java, que é extremamente performático para analisar a letra `Z` (Zulu/UTC) e não sofre com as conversões perigosas de fuso horário local que a antiga `Date` sofria.

A regra pede que a Segunda-feira seja `0` e o Domingo seja `6`. Como o padrão do Java é `1` a `7`, nós fazemos a subtração simples `-1` durante a vetorização.

### O Custo Oculto da Conversão de Datas

Embora o uso do `OffsetDateTime.parse()` garanta precisão absoluta de fuso horário, assumimos um trade-off de alocação de memória. Internamente, o motor nativo do Java realiza alocações de objetos na Heap para fazer o parse da string ISO 8601. Sob carga extrema, essas alocações criam pressão no Garbage Collector, causando flutuações na latência de percentil 99. Assumimos este custo temporário em prol da segurança e estabilidade da primeira versão, com o registro arquitetural de que, em otimizações futuras de nível extremo, a extração da hora e do dia da semana deverá ser feita através de um parser numérico direto, eliminando 100% da criação de objetos.

## Performance e Alocação

Note que o `TransactionVectorizer` possui as constantes e o mapa de riscos de MCC  como atributos privados finalizados. 

Estes dados são carregados a partir dos ficheiros `.json` **apenas uma vez** durante o *boot* da aplicação Javalin. Quando uma requisição chega, a classe gasta zero ciclos de CPU lendo o disco e realiza apenas operações aritméticas brutas diretamente da memória RAM, mantendo a latência na casa dos sub-milissegundos.

## Fallbacks e Dados Desconhecidos

Uma premissa fundamental em sistemas de alta disponibilidade é que o mundo real enviará dados imprevistos. O nosso mapa de mcc_risk contém os códigos conhecidos. Contudo, se a transação trouxer um código de MCC que não existe no nosso mapa em memória, assumimos uma postura de risco neutro/médio. O vetorizador injeta automaticamente o valor de fallback 0.5. Isso impede que a transação seja incorretamente classificada como totalmente segura ou como fraude absoluta apenas por falta de mapeamento.

Da mesma forma, avaliamos o campo known_merchants. Se o lojista atual não estiver na lista de lojistas conhecidos do cliente, assumimos a premissa de anomalia, e a dimensão unknown_merchant recebe o valor máximo 1.0.

## A Ordem Contratual do Espaço Vetorial

O `TransactionVectorizer` assume um compromisso matemático estrito com a estrutura gerada pelo `DatasetBuilder`. A conversão para o `float[14]` não é arbitrária. Assumimos como contrato inquebrável que a posição [0] será sempre o `amount`, a [3] será sempre `hour_of_day`, até a posição [13]. Qualquer alteração acidental na ordem de inserção neste array durante a vetorização causaria uma colisão de dimensões, o que destruiria silenciosamente a eficácia do modelo KNN sem gerar nenhum erro no compilador.