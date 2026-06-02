# Artefato V2: Formato Binário e Decisões de Implementação

## Por que um único arquivo binário?

O formato anterior usava múltiplos arquivos separados (`vectors-i8.bin`, `labels.bin`, `meta.properties`). Isso funciona, mas tem duas desvantagens para o runtime de busca:

1. **Dois `mmap` em vez de um.** Cada arquivo aberto com `mmap` consome uma entrada na tabela de descritores de arquivo e um intervalo do espaço de endereços virtual. Com um único arquivo, o runtime abre, mapeia e esquece o descritor numa só operação.
2. **Metadados separados do dado.** O `meta.properties` era lido linha a linha como texto antes de qualquer busca. Com o cabeçalho binário embutido no arquivo, o parser de texto some do hot path de inicialização.

O V2 consolida tudo num único `.v2`: cabeçalho + diretório de clusters + registros contíguos. O runtime só precisa abrir um arquivo.

---

## Layout do arquivo V2

```
┌──────────────────────────────────────────────────────────┐
│  HEADER  (24 bytes, big-endian)                          │
│                                                          │
│  Offset  Tamanho  Campo              Valor (Issue 01)    │
│  0       1 byte   version            2                   │
│  1       2 bytes  dims               14                  │
│  3       1 byte   dtype              1 (I8)              │
│  4       4 bytes  num_clusters       1                   │
│  8       8 bytes  cluster_dir_offset 24                  │
│  16      8 bytes  data_offset        54                  │
│                                 (24 header + 30 cluster) │
├──────────────────────────────────────────────────────────┤
│  DIRETÓRIO DE CLUSTERS  (1 entrada × 30 bytes = 30 bytes)│
│                                                          │
│  Offset  Tamanho  Campo                                  │
│  24      14 bytes centróide (14 × byte)                  │
│  38      4 bytes  radius (float)                         │
│  42      8 bytes  offset do bloco (long, rel. a data)    │
│  50      4 bytes  count (número de registros)            │
├──────────────────────────────────────────────────────────┤
│  REGISTROS  (N × 16 bytes)                               │
│                                                          │
│  Offset dentro do registro:                              │
│  0       1 byte   label  — 0=legítimo, 1=fraude          │
│  1       14 bytes vetor  — int8, sentinela −128          │
│  15      1 byte   padding — reservado, sempre 0          │
└──────────────────────────────────────────────────────────┘
```

**Por que big-endian?**
`DataOutputStream` (usado no builder) e `DataInputStream` (usado no leitor de cabeçalho) operam em big-endian por padrão — é o byte order da serialização Java. Para os registros em si, os campos são todos `byte`, que não têm byte order. Por isso o `mmap` via `MemorySegment` funciona sem conversão na leitura dos registros.

---

## O sentinela −128

O `TransactionVectorizer` emite `−1.0f` nas dimensões 5 e 6 quando `last_transaction` é `null`. No formato anterior, `encodeI8` mapeava `−1.0f × 127 = −127`. O V2 reserva `−128` (o menor valor possível de um `byte` Java: `Byte.MIN_VALUE`) como sentinela.

**Por que mudar de −127 para −128?**

Com `−127` como sentinela, o domínio dos valores normais era `[−127, 127]`, o que incluía `−127` como valor legítimo para uma dimensão com valor `−1.0f`. Reservar `−128` isola o sentinela fora do domínio alcançável pela quantização normal (`round(v × 127)` para `v ∈ [0, 1]` produz `[0, 127]`), eliminando qualquer ambiguidade.

**Regra de encode em código:**

```java
if (v == -1.0f) {
    b = Byte.MIN_VALUE;   // −128, sentinela de ausência
} else {
    int q = Math.round(v * 127);
    if (q < -127) q = -127;
    if (q > 127)  q = 127;
    b = (byte) q;
}
```

A **mesma regra** é usada no `V2ArtifactBuilder.encodeI8()` (para os registros persistidos) e no `V2IndexSearcher.quantizeQuery()` (para o vetor de consulta). Isso garante que a distância entre dois vetores sentinela seja zero: `(−128) − (−128) = 0`.

---

## O cluster único

Na Issue 01, o diretório contém sempre **1 cluster** que engloba todos os registros:

- **Centróide**: vetor zero (placeholder; não é usado na busca brute force)
- **Raio**: `Float.MAX_VALUE` (indica que o cluster contém tudo)
- **Offset**: `0` (o bloco começa exatamente em `data_offset`)
- **Count**: `N` (total de registros)

A lógica de seleção de cluster (calcular distância ao centróide, visitar apenas os `nprobe` mais próximos) não existe ainda — o `V2IndexSearcher` percorre todos os registros do único cluster, exatamente como o antigo `QuantizedBruteForceSearcher`. O IVF real entra na Issue 02.

---

## Duas passagens no builder

O builder não pode escrever o cabeçalho antes de saber o valor de `count` — e `count` só é conhecido após varrer o JSON inteiro. Há duas saídas clássicas:

| Abordagem | Trade-off |
|-----------|-----------|
| Acumular tudo em memória, depois escrever | Simples, mas estoura RAM com 3 M registros |
| Escrever registros num arquivo temporário, depois compor o arquivo final | Usa disco, mas mantém heap baixo (< 2 MB de overhead) |

O V2ArtifactBuilder usa a segunda abordagem: **Pass 1** grava só os registros no `.tmp`, **Pass 2** monta o arquivo final copiando o temp após o cabeçalho. O arquivo temporário é apagado ao final.

---

## Como o leitor (V2IndexSearcher) funciona

**Inicialização:**

1. Abre o arquivo com `DataInputStream` e lê os 54 bytes de header + cluster entry para extrair `dataOffset` e `count`. O stream é fechado logo em seguida.
2. Abre o mesmo arquivo com `FileChannel.map()` para criar um `MemorySegment` (mmap). O `FileChannel` pode ser fechado imediatamente após o `map` — o mapeamento sobrevive enquanto a `Arena` estiver aberta.

**Por que fechar o `FileChannel` depois do `map`?**

`FileChannel.map()` cria o mapeamento de memória no nível do sistema operacional. Fechar o `FileChannel` em Java não desfaz o mapeamento; ele permanece válido até a `Arena` ser fechada (ou o processo encerrar). Manter o canal aberto desnecessariamente consumiria um descritor de arquivo sem benefício.

**Busca:**

```
Para i = 0 até count − 1:
  recordBase = dataOffset + i × 16
  labelByte  = file[recordBase]           → 0 ou 1
  vectorBase = recordBase + 1
  dist = calculateI8(queryBytes, file, vectorBase, 14)
  mantém top-k com PriorityQueue (max-heap por distância)
```

O `PriorityQueue` funciona como um **max-heap**: o topo sempre guarda a pior (maior) distância entre os `k` candidatos atuais. Ao chegar um novo vizinho com distância menor que o topo, o topo é removido e o novo entra. Ao final, a fila contém exatamente os `k` mais próximos.

---

## Configuração da aplicação

A variável de ambiente `V2_ARTIFACT_PATH` aponta para o arquivo `.v2` gerado pelo builder. O padrão é `./data/index.v2`.

```
V2_ARTIFACT_PATH=./data/index.v2 java -jar app.jar
```

O `AppConfig` mantém os campos legados (`dataDir`, `datasetPath`) para que os benchmarks e comparações contra o formato V1 continuem funcionando durante a transição.

---

## Testes da Issue 01

### Testes unitários do builder (`V2ArtifactBuilderTest` — a criar)

Padrão esperado: validar o `encodeI8` para o sentinela, para valores normais e para valores nos limites, e validar que o `build()` produz exatamente `24 + 30 + N × 16` bytes com o conteúdo correto.

### Teste de integração (`V2EndToEndTest`)

Cobre o critério de aceite completo:

1. **Build**: chama `V2ArtifactBuilder.build()` com um dataset pequeno em memória (6 registros).
2. **Bootstrap**: cria `V2IndexSearcher` + `TransactionVectorizer` + `Javalin` na porta 0 (SO escolhe uma porta livre).
3. **Requisição com `last_transaction` presente**: verifica HTTP 200 e campos `approved`/`fraud_score`.
4. **Requisição com `last_transaction: null`**: verifica que o sentinela −128 é tratado sem erro e o endpoint responde normalmente.

O uso da porta 0 (em vez de uma porta fixa) evita falhas de teste por porta já em uso.
