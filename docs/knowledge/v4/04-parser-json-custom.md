# V4-C: Parser JSON Custom — Zero-Alocação na Entrada

Este documento explica as técnicas usadas na `FraudRequestParser`, com detalhes suficientes
para um desenvolvedor júnior entender e replicar o padrão em outro contexto.

> ⚠️ **Errata (2026-06-05).** As técnicas de *parsing* deste documento estão corretas (o
> parser produz o vetor certo: 0 FP / 0 FN no benchmark). Mas a estratégia de zero-alocação
> via `ThreadLocal` **colide com `useVirtualThreads = true`** e degradou a aplicação
> (p99 40 ms → 1116 ms, `final_score` +4394 → −1329). A análise da **seção 7** estava
> errada; veja a correção inline lá e a autópsia completa em
> [`07-postmortem-parser-virtual-threads.md`](07-postmortem-parser-virtual-threads.md). A
> correção adotada (desligar virtual threads, usar pool de platform threads) **mantém** o
> `ThreadLocal` — ele volta a funcionar como projetado sob um pool fixo de threads.

---

## 1. Por que o Jackson aloca tanto?

Quando você escreve `ctx.bodyAsClass(TransactionRequest.class)`, o Jackson:

1. Lê o corpo HTTP como `String` (alocação).
2. Cria um `JsonParser` interno e percorre tokens.
3. Para cada campo JSON, cria uma `String` para o nome do campo e para o valor string.
4. Constrói os objetos `TransactionData`, `CustomerData`, etc. (reflections + alocações).
5. Cria um `ArrayList<String>` para `known_merchants`.
6. Parseia timestamps com `OffsetDateTime.parse()`, que internamente aloca vários objetos de calendário.

Com ~900 req/s e K=5, isso produz dezenas de objetos por requisição. O Garbage Collector
(GC) precisa coletar esses objetos curtos, e as pausas aparecem como picos de p99.

**Regra de ouro:** em hot paths, cada `new` é um custo de GC. O objetivo é chegar a zero.

---

## 2. O que é um parser cursor-based?

Um parser cursor-based lê o JSON como um array de bytes e mantém um índice inteiro `pos`
que aponta para o caractere atual. Ele avança `pos` a cada caractere lido, sem criar objetos
intermediários.

Compare as abordagens:

| Abordagem | Alocações por requisição | Velocidade relativa |
|---|---|---|
| Jackson (DOM) | Muitas (árvore de objetos) | 1× (linha de base) |
| SAX/Jackson Streaming | Moderadas (eventos) | ~3× |
| Cursor-based custom | Zero (após JIT warmup) | 10–20× |

O DOM (Document Object Model) constrói uma árvore de objetos em memória. O SAX dispara
eventos. O cursor-based lê um byte por vez e toma decisões imediatas sem guardar nada além
de variáveis locais.

### Estrutura básica

```java
int pos = 0;
pos = skipWs(buf, pos, len);   // pula espaço em branco
// buf[pos] == '{'
pos++;

while (true) {
    pos = skipWs(buf, pos, len);
    if (buf[pos] == '}') break;        // fim do objeto
    if (buf[pos] == ',') { pos++; continue; } // separador

    // lê o nome do campo em buf, sem criar String
    pos = readKey(buf, pos + 1, len, keyBuf, keyLen);

    pos = skipColon(buf, pos, len);    // pula ':'

    // despacha pelo nome do campo
    if (keyIs(keyBuf, keyLen[0], K_AMOUNT)) {
        pos = readDouble(buf, pos, len, dbl);
        txAmount = dbl[0];
    } else {
        pos = skipValue(buf, pos, len); // campo desconhecido: pula
    }
}
```

---

## 3. Comparação de nomes de campo sem `String`

Jackson converte `"requested_at"` do JSON para a propriedade `requestedAt` do record via
reflexão e alocação de strings. Nosso parser evita isso completamente.

### A técnica: `static final byte[]`

```java
// Declarados uma única vez na classe — imutáveis, carregados no início da JVM
private static final byte[] K_AMOUNT       = "amount".getBytes(UTF_8);
private static final byte[] K_REQUESTED_AT = "requested_at".getBytes(UTF_8);
```

### Comparação byte a byte

```java
private static boolean keyIs(byte[] scratch, int scrLen, byte[] key) {
    if (scrLen != key.length) return false;  // pré-filtro rápido por tamanho
    for (int i = 0; i < scrLen; i++) {
        if (scratch[i] != key[i]) return false;
    }
    return true;
}
```

Por que isso é rápido? Porque:
- O pré-filtro por tamanho elimina a maioria dos campos em 1 operação.
- Os bytes estão em cache (L1) porque foram lidos há pouco.
- Não há boxing, não há criação de `String`, não há `hashCode()`.

---

## 4. Parsear números sem `Double.parseDouble`

`Double.parseDouble("41.12")` cria uma `String` internamente e passa por um caminho
genérico que lida com notação científica, infinito, NaN e outros casos que não existem
no nosso schema. Nosso parser lê os dígitos diretamente dos bytes:

```java
private static int readDouble(byte[] buf, int pos, int len, double[] out) {
    boolean neg = pos < len && buf[pos] == '-';
    if (neg) pos++;

    long intPart = 0;
    while (pos < len && buf[pos] >= '0' && buf[pos] <= '9') {
        intPart = intPart * 10 + (buf[pos++] - '0');
    }

    double frac = 0;
    if (pos < len && buf[pos] == '.') {
        pos++;
        long fracDigits = 0;
        int  fracCount  = 0;
        while (pos < len && buf[pos] >= '0' && buf[pos] <= '9') {
            fracDigits = fracDigits * 10 + (buf[pos++] - '0');
            fracCount++;
        }
        if (fracCount > 0) {
            frac = fracDigits / Math.pow(10, fracCount);
        }
    }
    out[0] = neg ? -(intPart + frac) : (intPart + frac);
    return pos;
}
```

**Por que acumular `fracDigits` como inteiro?** Se fizéssemos `frac += digit * factor;
factor *= 0.1;`, o erro de ponto flutuante se acumularia a cada multiplicação por 0.1
(que não é representável exatamente em binário). Ao acumular como inteiro e dividir
uma única vez no final (`fracDigits / 10^fracCount`), há apenas um erro de arredondamento.

**Por que usar `out[0]` em vez de retornar `double`?** Java não tem múltiplos valores de
retorno. Usar `double[]` de 1 elemento pré-alocado no `ParseState` é zero-alocação. Retornar
um `Double` (boxed) alocaria um objeto por chamada.

---

## 5. O algoritmo de Howard Hinnant: `daysFromCivil`

O timestamp `"2026-03-11T18:45:53Z"` precisa ser convertido em:
- Hora do dia (índice 3 do vetor): `18 / 23.0f`
- Dia da semana (índice 4): quarta-feira → `(3-1) / 6.0f`
- Epoch-seconds (para calcular minutos desde a última transação)

O caminho padrão Java — `OffsetDateTime.parse(...)` — aloca vários objetos de calendário.
O algoritmo de Hinnant faz tudo com aritmética inteira pura.

### Como funciona

`daysFromCivil(y, m, d)` retorna o número de dias desde 1970-01-01. A ideia central é
reorganizar o ano para que março seja o mês 0 (eliminando o caso especial de fevereiro em
anos bissextos):

```java
private static int daysFromCivil(int y, int m, int d) {
    // Março = mês 0; assim fevereiro (o mês irregular) fica no final do "ano"
    if (m <= 2) { y -= 1; m += 9; } else { m -= 3; }

    int era = Math.floorDiv(y, 400);   // ciclo de 400 anos (146.097 dias)
    int yoe = y - era * 400;           // ano dentro do ciclo [0, 399]
    int doy = (153 * m + 2) / 5 + d - 1;   // dia dentro do ano [0, 365]
    int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy; // dia dentro do ciclo
    return era * 146097 + doe - 719468;     // subtrai offset até 1970-01-01
}
```

### Verificação manual para 2026-03-11

```
m=3 > 2  → m = 3-3 = 0, y = 2026
era = floor(2026 / 400) = 5         (5×400 = 2000)
yoe = 2026 - 2000 = 26
doy = (153×0 + 2)/5 + 11 - 1 = 0 + 10 = 10
doe = 26×365 + 26/4 - 26/100 + 10
    = 9490 + 6 - 0 + 10 = 9506
days = 5×146097 + 9506 - 719468
     = 730485 + 9506 - 719468 = 20523
```

Para saber o dia da semana: 1970-01-01 foi uma quinta-feira (ISO weekday = 4).
Portanto, `Math.floorMod(days + 3, 7) + 1` retorna 1=Seg … 7=Dom.

```
floorMod(20523 + 3, 7) + 1 = floorMod(20526, 7) + 1
20526 = 2932×7 + 2   →   floorMod = 2
weekday = 2 + 1 = 3   →  quarta-feira ✓
```

O vetor espera `(weekday - 1) / 6.0f = (3-1)/6.0f = 0.3333f`. ✓

### Epoch-seconds e minutos desde a última transação

```java
long epochSecs = (long) daysFromCivil(y, mo, d) * 86400L + h * 3600L + mn * 60L + s;
```

Para calcular os minutos entre dois timestamps (espelhando `Duration.between().toMinutes()`):

```java
long diffSecs = reqEpochSecs - lastEpochSecs;
long diffMins = Math.max(0L, diffSecs / 60L);  // trunca em direção a zero, igual ao Java
```

---

## 6. Comparação de merchant IDs sem `HashSet<String>`

O campo `known_merchants` é uma lista de strings como `["MERC-003", "MERC-016"]`, e
`merchant.id` é `"MERC-016"`. A pergunta é: o merchant é conhecido?

`List.contains(String)` usa `equals()` que cria e compara strings. `HashSet<String>` aloca
objetos e faz hashing. Nossa solução: empacotar os primeiros 8 bytes de cada ID em um `long`.

```java
private static long packMerch(byte[] buf, int pos, int sLen) {
    long v = 0;
    int  n = Math.min(sLen, 8);
    for (int i = 0; i < n; i++) {
        v = (v << 8) | (buf[pos + i] & 0xFF);
    }
    v <<= (8 - n) * 8;  // zero-padding à esquerda para IDs menores que 8 bytes
    return v;
}
```

`"MERC-016"` tem exatamente 8 bytes. O long resultante é:
```
'M'=0x4D, 'E'=0x45, 'R'=0x52, 'C'=0x43, '-'=0x2D, '0'=0x30, '1'=0x31, '6'=0x36
→ 0x4D455243 2D303136
```

Armazenamos até 32 hashes em `long[] merch` (parte do `ParseState`). A busca é uma varredura
linear de no máximo 32 comparações de inteiro — mais rápida que qualquer estrutura de hash
para N pequeno.

**Restrição:** IDs com mais de 8 bytes são comparados apenas pelos primeiros 8. Para o
corpus do projeto (todos `MERC-NNN` = 8 chars), não há colisões.

---

## 7. `ThreadLocal` como buffer por thread

O `ParseState` contém os buffers scratch (dbl, ints, bools, ts, merch, keyBuf). Eles precisam
ser reutilizáveis sem sincronização. A solução é `ThreadLocal`:

```java
private static final ThreadLocal<ParseState> STATE =
        ThreadLocal.withInitial(ParseState::new);
```

**Como funciona:** cada thread (inclusive thread virtual com Loom) tem seu próprio mapa de
`ThreadLocal`. Na primeira chamada de `STATE.get()` nessa thread, `ParseState::new` é
invocado e o objeto é armazenado. Nas chamadas seguintes, o mesmo objeto é retornado —
zero alocação.

**Cuidado com Loom (Java 21+):** threads virtuais têm seu próprio `ThreadLocal`. Com muitas
threads virtuais concorrentes, cada uma pode ter seu próprio `ParseState`. Para ~900 req/s
com latência de ~1ms, há talvez 5–10 threads virtuais ativas por vez — ~5–10 instâncias
de `ParseState`, totalizando alguns kilobytes. Completamente negligenciável.

> ❌ **Este parágrafo estava errado — e foi a causa raiz da regressão.** Sob
> `useVirtualThreads = true`, o servidor usa **uma virtual thread nova por requisição**.
> Como a thread morre a cada requisição, o `ThreadLocal` **nunca reaproveita**: o
> `withInitial` roda toda vez, alocando o `ParseState` gordo por requisição — o oposto de
> zero-alocação. Pior: cada virtual thread em voo **retém** seu `ParseState` até a busca
> terminar, então sob `arrival-rate` de 900 rps em 0,45 CPU as threads se acumulam e a
> memória viva cresce com elas. Os dois erros da estimativa acima: (1) não são "5–10
> threads" — quando a latência sobe, são centenas; (2) o que mata não é a memória *viva*,
> é a *taxa de lixo* que dispara o SerialGC stop-the-world. Detalhes em
> [`07-postmortem-parser-virtual-threads.md`](07-postmortem-parser-virtual-threads.md).
> **Regra prática:** `ThreadLocal` para zero-alocação só vale com **pool fixo de platform
> threads**; é o anti-padrão que a JEP 444 desaconselha sob virtual threads.

**Quando usar `ThreadLocal`:** quando você precisa de estado mutável reutilizável sem
sincronização, e o estado é "por execução" (não compartilhado entre threads concorrentes).

---

## 8. Parse + vetorização fundidos em uma passagem

O padrão tradicional tem duas etapas separadas:

```
JSON bytes → [Jackson] → TransactionRequest → [Vectorizer] → float[14]
```

O nosso parser funde as duas:

```
JSON bytes → [FraudRequestParser] → float[14]
```

Por que isso é melhor?

1. **Uma leitura do buffer:** os bytes do JSON são lidos uma única vez. No padrão antigo,
   Jackson lê os bytes e cria objetos; o vetorizador lê os objetos novamente.

2. **Sem objetos intermediários:** `TransactionRequest`, `TransactionData`, `CustomerData`,
   etc. nunca são criados. Isso elimina 6 alocações de record + 1 `ArrayList` por requisição.

3. **Cálculos no ponto de leitura:** ao ler `amount`, calculamos `clamp(amount / maxAmount)`
   imediatamente. Não guardamos o `double` bruto em objeto — o resultado já vai para `out[0]`.

A desvantagem é que o parser é mais complexo e mistura responsabilidades (parse + lógica de
negócio). Mas para hot paths críticos de desempenho, essa fusão é a abordagem padrão da
indústria (usada em Aeron, Chronicle Map, Zero Allocation Hashing, etc.).

---

## 9. Como o `SearchHandler` usa o parser

Antes:

```java
// Aloca: String do body + TransactionRequest + subrecords + ArrayList
TransactionRequest request = ctx.bodyAsClass(TransactionRequest.class);
// Aloca: float[14]
float[] queryVector = vectorizer.vectorize(request);
```

Depois:

```java
// Aloca: byte[] do body (inevitável — vem do framework HTTP)
byte[] body = ctx.bodyAsBytes();
// Reutiliza: float[14] do ThreadLocal — zero alocação
float[] queryVector = QUERY_VEC.get();
// Zero alocação após JIT warmup
parser.parse(body, body.length, queryVector);
```

A alocação do `byte[]` do body é inevitável porque o Javalin precisa copiar os bytes do
buffer interno do Jetty para disponibilizá-los à aplicação. Mas isso é 1 alocação pequena
(~200–300 bytes) versus as dezenas de alocações do Jackson.

---

## 10. Como replicar: checklist para um parser similar

Siga esta sequência ao implementar um parser cursor-based para um schema JSON fixo:

1. **Mapeie o schema:** liste todos os campos que você precisa, seus caminhos (raiz ou
   aninhado) e seus tipos (double, int, boolean, string, array, objeto aninhado, null).

2. **Declare as constantes de chave:** `static final byte[] K_XXX = "xxx".getBytes(UTF_8);`
   para cada nome de campo.

3. **Crie o `ParseState`:** agrupe todos os buffers reutilizáveis num inner class. Use
   `ThreadLocal<ParseState>` para acesso zero-alocação por thread.

4. **Implemente os primitivos:**
   - `skipWs`: avança `pos` sobre whitespace.
   - `readKey`: lê bytes do nome de campo até `"`, grava em `scratch[]`.
   - `skipColon`: avança sobre `:` e whitespace.
   - `readDouble`, `readInt`, `readBoolean`: leem o valor no tipo esperado.
   - `skipValue`: pula qualquer valor JSON (string, número, objeto, array, boolean, null).
   - `skipString`: caso especial de `skipValue` para strings com `\` escape.

5. **Escreva o loop de parse:** estrutura `while(true)` que lê chave, despacha para o campo
   certo ou chama `skipValue`. Replique para cada nível de objeto aninhado.

6. **Monte o resultado no final:** após o loop, aplique os cálculos de negócio nos
   acumuladores lidos e escreva no array de saída.

7. **Escreva um teste de contrato:** para cada entrada de referência, compare a saída do
   parser com a saída do caminho anterior (Jackson + lógica existente). Use um delta pequeno
   (ex.: `1e-4f`) para tolerância de ponto flutuante.

8. **Compile e rode o teste de contrato antes de integrar** ao código de produção.

9. **Integre:** troque o ponto de uso (ex.: `SearchHandler`) e rode a suite completa.

10. **Meça:** compare p99 antes e depois com o mesmo protocolo de benchmark (5 boots frios,
    mediana + spread). O efeito real será visível na redução do spread de p99, não
    necessariamente na mediana — pois o spread de GC é justamente a cauda que o parser ataca.

---

## Resultado desta implementação

| Critério | Status |
|---|---|
| Hot path sem Jackson | ✅ `ctx.bodyAsClass` removido de `SearchHandler` |
| `JavalinJackson` como mapper HTTP | ✅ removido de `JavalinBootstrap` |
| Teste de contrato (50 payloads, incl. null) | ✅ 3/3 testes verdes |
| `V2QualityGuardTest`, `V2IvfSearchTest`, `V2EndToEndTest` | ✅ 56/56 testes verdes |
| `WarmupService` aquece o caminho exato de produção | ✅ usa `parser.parse(bytes, ...)` |
| Benchmark pós-mudança | ❌ regrediu sob virtual threads → corrigido (Opção 1); ver [`07`](07-postmortem-parser-virtual-threads.md) |
