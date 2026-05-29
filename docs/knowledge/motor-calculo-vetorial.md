# Motor de Cálculo Vetorial

O motor de cálculo é o coração matemático da nossa aplicação. Como a nossa arquitetura exige processar milhões de comparações em milissegundos, com um limite estrito de 150MB de memória RAM, o design dessas classes foge um pouco do padrão tradicional e se aproxima da programação de baixo nível.

## O Contrato: A Interface `DistanceCalculator`

Em vez de criar um método tradicional como `double calculate(float[] a, float[] b)`, criamos um contrato que entende a nossa infraestrutura de memória mapeada.

### O que é exatamente o `MemorySegment segment`?

No Java 21+, o `MemorySegment` é a "ponte" entre o Java e a memória bruta do Sistema Operacional.
Neste projeto, ele representa o nosso arquivo `dataset.bin` mapeado diretamente na Memória Virtual. Você pode imaginar o `MemorySegment` como um gigantesco *array* de bytes , mas com uma diferença crucial: **ele não vive na *Heap* do Java.** Passar o `segment` e o `offset` para a calculadora significa que **não precisamos criar objetos temporários** para fazer a conta. Nós instruímos a CPU a ir direto na memória do arquivo e fazer o cálculo lá mesmo.

## A Implementação: `EuclideanDistanceCalculator`

Esta classe implementa a **Distância Euclidiana**, que é, geometricamente, a distância em linha reta entre dois pontos no espaço vetorial.

### O Controle de Endianness

```java
private static final ValueLayout.OfFloat JAVA_FLOAT_BE = ValueLayout.JAVA_FLOAT.withOrder(ByteOrder.BIG_ENDIAN);
```

* **Por que `BIG_ENDIAN`?** Como documentado na fase de Geração do Dataset, o nosso arquivo binário foi gravado usando o padrão `BIG_ENDIAN`. O Sistema Operacional e o processador geralmente operam em `LITTLE_ENDIAN`. Se não forçarmos a leitura para `BIG_ENDIAN`, os bytes serão lidos de trás para frente, e um valor como `0.15` será interpretado pelo Java como um número gigantesco ou corrompido.
* **Por que `static final`?** Instanciar regras de formatação é custoso. Como essa classe será chamada milhões de vezes por segundo, deixamos essa configuração estática na memória para ser reutilizada, economizando CPU e Garbage Collector.

### A Gestão do Ponteiro

```java
long currentOffset = offset; // Usamos uma variável local para não alterar o offset do buscador
```

* **O Conceito:** Pense no `offset` como um "cursor" ou "ponteiro". Ele indica o byte exato onde as coordenadas do vetor atual começam.
* **O Motivo:** No Java, tipos primitivos (como `long`) são passados "por valor", o que significa que alterar o `offset` dentro deste método não quebraria o laço principal do buscador de qualquer forma. No entanto, criar a variável local `currentOffset` é uma excelente **prática de legibilidade e segurança**. Ela deixa claro para o desenvolvedor que o `offset` original recebido marca o "início" do registro, enquanto o `currentOffset` é o "caminhante" que vai avançando byte a byte durante o cálculo.

### O Coração do Cálculo

```java
for (int i = 0; i < dimensions; i++) {
    float vectorVal = segment.get(JAVA_FLOAT_BE, currentOffset);
    float diff = queryVector[i] - vectorVal;
    squaredDistance += (diff * diff);
    currentOffset += 4; // Avança 4 bytes (tamanho de um float)
}
```

Este laço `for` é onde a mágica matemática acontece em nanossegundos:

1. `segment.get(...)`: O Java vai até a memória RAM, na posição exata do `currentOffset`, lê 4 bytes, os interpreta como um número `float` e guarda na variável `vectorVal`.
2. `diff`: Calculamos a diferença entre o valor de busca e o valor que estava no dataset.
3. `squaredDistance += (diff * diff)`: Elevamos a diferença ao quadrado e somamos ao acumulador geral.
4. `currentOffset += 4`: **Passo fundamental!** Como um número `Float` ocupa 4 bytes na memória, nós avançamos o nosso cursor em 4 posições. Assim, na próxima volta do laço, ele estará apontando para a próxima dimensão do vetor.

### A Omissão Estratégica da Raiz Quadrada

* **A Pergunta:** Pela fórmula matemática oficial da Distância Euclidiana, no final de tudo deveríamos tirar a raiz quadrada da soma: `return Math.sqrt(squaredDistance);`. Por que não fizemos isso?
* **A Resposta:** A operação `Math.sqrt()` é uma das operações matemáticas mais "caras" e lentas para um processador executar.
Nós estamos construindo um motor de busca cujo único objetivo é **ordenar** quem são os vizinhos mais próximos. Matematicamente, a ordem de grandeza se mantém: se a distância ao quadrado de $A$ é menor que a de $B$, a raiz quadrada de $A$ também será menor que a de $B$.
Portanto, para fins de ordenação, **omitir a raiz quadrada nos dá exatamente o mesmo resultado, mas poupa a CPU de calcular milhões de raízes quadradas por requisição**, aumentando drasticamente a nossa vazão nos testes de carga. Essa técnica é conhecida em engenharia de dados como *Squared Euclidean Distance*.
