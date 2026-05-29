# Estratégia de Pré-processamento: Geração do Dataset Binário

Como parte dos requisitos arquiteturais do projeto, a nossa aplicação possui uma **restrição severa de memória**. Carregar um arquivo de dados gigante diretamente na memória da aplicação durante a inicialização causaria um inevitável `OutOfMemoryError`.

Para contornar isso, o ciclo de vida do sistema foi dividido em duas fases. O `DatasetBuilder` é o responsável pela **Fase A: Pré-processamento**. O objetivo dele é ler os dados brutos e gerar um arquivo binário altamente otimizado, que será posteriormente consumido pela aplicação sem onerar a memória *Heap* do Java.

## Técnicas Aplicadas e Benefícios de Performance

### Leitura de Dados em Fluxo

A técnica mais crítica aplicada no construtor do dataset é o uso da **Streaming API do Jackson**.

Normalmente, mapeamos um JSON inteiro para uma lista de objetos na memória. Isso cria milhares de objetos simultaneamente, consumindo uma quantidade massiva de memória para armazenar os dados e os "cabeçalhos" que o Java adiciona internamente para cada objeto.

Utilizamos a leitura baseada em *tokens*. O sistema abre o arquivo e lê os dados como uma esteira de produção: ele lê **apenas um** objeto JSON por vez, converte esse objeto, grava no arquivo binário e, em seguida, descarta esse objeto da memória para ler o próximo.

O consumo de memória RAM do processo torna-se constante e próximo a zero, independentemente de o arquivo JSON ter 10 mil ou 10 milhões de registros.

### Descompactação "On-the-fly"

O dataset original é fornecido compactado em formato `.gz`.

Em vez de extrair o arquivo inteiro para o disco ou para a memória, utilizamos o `GZIPInputStream`.

O arquivo é descompactado em pequenos blocos à medida que o `JsonParser` avança a leitura. Os dados são processados instantaneamente sem nunca existir um "JSON gigante e descompactado" ocupando a RAM ou o disco desnecessariamente.

### Downcasting de Precisão

No Java, ao ler números decimais de um JSON, o padrão é armazená-los como `Double`, que ocupa 64 bits ou 8 bytes por número.

Como não precisamos de precisão científica extrema para o cálculo de distância vetorial nesta *baseline*, fazemos o *cast* de cada dimensão do vetor para `Float`, que ocupa 32 bits ou 4 bytes.

Essa simples conversão **corta pela metade** o espaço em disco exigido pelo arquivo `dataset.bin`. Mais importante ainda: quando a aplicação mapear esse arquivo para a memória na Fase B, o consumo de memória virtual será 50% menor, otimizando o uso do *Page Cache* do sistema operacional.

## Estrutura Interna do Arquivo Binário

Para garantir altíssima performance de leitura e manter o consumo de memória estritamente abaixo do limite de 150MB, abandonamos formatos estruturados legíveis em favor de um formato binário denso e sequencial.

A estrutura adotada baseia-se no padrão de engenharia de dados conhecido como **TLV (Type-Length-Value)** ou protocolos com prefixo de comprimento. Essa abordagem permite que a aplicação navegue pela memória com precisão cirúrgica, sem alocar objetos na *Heap*.

Para cada registro processado, gravamos a seguinte sequência exata de bytes:

### `[Tamanho do Label em int]` (4 bytes)

Strings em arquivos binários possuem tamanhos variáveis. Sem um delimitador, o leitor precisaria escanear o arquivo byte a byte para descobrir onde a palavra termina, desperdiçando ciclos de CPU.

Gravamos um número inteiro logo no início informando exatamente a quantidade de bytes que a *string* ocupa.

Quando a API lê esse inteiro utilizando *Memory-Mapped Files*, ela sabe exatamente o tamanho do salto necessário na memória. Isso permite uma leitura em tempo real `O(1)`, habilitando técnicas de *Zero-Copy*.

### `[Bytes do Label]` (N bytes)

Contém a carga útil de texto convertida para a tabela UTF-8. O leitor extrai exatamente a quantidade de bytes informada no passo anterior, garantindo isolamento total de lixo de memória adjacente.

### `[Quantidade de Dimensões em int]` (4 bytes)

Garantir que o sistema seja robusto e adaptável a mudanças nos dados de entrada.

Antes de gravar as coordenadas numéricas, gravamos um inteiro indicando o tamanho do vetor.

Se em implementações futuras o *dataset* for alterado para utilizar *embeddings* de 8 ou 1536 dimensões, o algoritmo de leitura continuará funcionando sem quebrar. O leitor utiliza esse valor para definir o limite exato do laço de repetição de leitura matemática.

### `[Valores do Vetor em floats]` (N * 4 bytes)

Todos os valores do vetor são gravados sequencialmente em blocos contíguos no formato `Float`, 32 bits, com o objetivo de acessar milhares de números de ponto flutuante em nanossegundos durante o cálculo de distância vetorial.

Agrupar os valores matemáticos de forma sequencial tira o máximo proveito da arquitetura dos processadores modernos: ao solicitar o primeiro *float*, a CPU realiza o *prefetch* dos próximos valores diretamente para o Cache L1/L2, evitando a "viagem" lenta até a memória RAM primária, algo que ocorreria frequentemente se utilizássemos coleções padrão de objetos na *Heap* do Java.

### Estruturação Sequencial

Em vez de salvar o resultado como outro JSON ou em um banco de dados embutido, o script escreve bytes puros de forma contígua.

Para cada registro, gravamos sequencialmente: `[Tamanho do Label em int] -> [Bytes do Label] -> [Quantidade de Dimensões em int] -> [Valores do Vetor em floats]`.

Removemos 100% da "gordura" estrutural. Não há chaves de dicionário, não há aspas, vírgulas ou colchetes. Isso cria um arquivo incrivelmente denso e compacto, pronto para ser lido matematicamente pelo algoritmo de busca sem necessidade de *parsers* complexos durante a execução da API.

### Ordem dos Bytes e Compatibilidade

Ao manipular arquivos binários que transitam entre diferentes APIs ou linguagens, a ordem em que os bytes de um número são gravados é crítica.

O script `DatasetBuilder` utiliza a classe `DataOutputStream` nativa do Java, que grava os tipos primitivos utilizando o padrão **Big-Endian**.

Muitas APIs modernas de acesso à memória e processadores tendem a ler a memória no formato **Little-Endian** por padrão.

Quando a aplicação for mapear este `dataset.bin` para a memória na Fase B, é **obrigatório** configurar o leitor para utilizar a ordem `ByteOrder.BIG_ENDIAN` ou `ByteOrder.nativeOrder()` dependendo de como o buffer for configurado. Se houver divergência no *Endianness*, um valor como `0.5` será interpretado pelo leitor como um número astronômico ou completamente corrompido, arruinando a precisão da busca vetorial.

### Resumo para a Engenharia

O `DatasetBuilder` aplica o conceito de **"Assar os Dados"**. Gastamos o processamento (CPU e disco) de forma inteligente durante a construção da imagem Docker para garantir que, quando a aplicação principal subir para receber requisições de busca, ela encontre os dados em sua forma mais limpa, compacta e performática possível.