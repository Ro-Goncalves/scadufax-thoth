# V3-D: Busca sem alocação — TopKSelector

## O problema

Toda vez que a API recebe uma requisição de detecção de fraude, ela precisa encontrar os **K vizinhos
mais próximos** do vetor de consulta dentro do índice. Antes desta entrega, a busca era feita assim:

```java
PriorityQueue<SearchResult> pq = new PriorityQueue<>(k);

for (int i = 0; i < blockCount; i++) {
    double dist = calculator.calculateI8(q, file, recordBase + 1, DIMS);

    if (pq.size() < k) {
        String label = labelByte == 1 ? "fraud" : "legitimate";
        pq.offer(new SearchResult(label, dist));   // ← aloca 1 objeto SearchResult
                                                    // ← aloca 1 objeto String
    } else if (dist < pq.peek().distance()) {
        // ... mesmas alocações para substituir o pior elemento
    }
}
```

Para cada **candidato varrido** durante a busca, o código alocava dois objetos Java no heap:

1. Um objeto `SearchResult`
2. Um objeto `String` com o label ("fraud" ou "legitimate")

---

## Quantas alocações acontecem por requisição?

Com a configuração padrão de produção (K=1024, nprobe=4), cada requisição varre aproximadamente
**11.720 candidatos**. Isso resulta em:

```
11.720 candidatos × 2 objetos (SearchResult + String)
= ~23.440 alocações por requisição
```

Essas alocações são de curta duração — os objetos morrem logo após a busca. Em Java, objetos de vida
curta vivem na **geração jovem** do heap. Quando essa área enche, o **Garbage Collector (GC)** precisa
pausar a execução para limpar.

---

## Por que o GC causa variância de latência?

Imagine uma estrada onde os carros trafegam normalmente. O GC é como um semáforo que às vezes fecha
a estrada para todos os carros ao mesmo tempo — mesmo que brevemente. O tempo de pausa pode ser de
poucos milissegundos, mas ele **afeta todas as threads simultaneamente** e aparece inflado no p99.

```
Sem pressão de GC:
  req 1: 8ms ── req 2: 9ms ── req 3: 8ms ── req 4: 8ms
                                              p99 ≈ 9ms

Com pausa de GC em req 3:
  req 1: 8ms ── req 2: 9ms ── req 3: 26ms ── req 4: 8ms
                                               p99 ≈ 26ms
```

Com 23.440 alocações por requisição acontecendo sob carga, o GC dispara com frequência — e isso se
acumula diretamente no p99.

---

## A solução: TopKSelector com arrays primitivos

A ideia central é: **não alocar nada no loop de varredura**. Em vez de criar objetos Java por
candidato, guardamos apenas dois números por slot:

- A **distância** (um `double`)
- O **label** como byte bruto: `1` para fraude, `0` para legítimo

Dois arrays de primitivos, paralelos:

```
topDist  = [ 1.2,  3.5,  7.1,  ...,  ∞ ]   ← double[]
topLabel = [   1,    0,    1,  ...,  0 ]   ← byte[]
             ↑                   ↑
           mais próximo         mais distante
```

Os arrays são mantidos **em ordem crescente de distância** via insertion sort. O último slot
(`topDist[k-1]`) é sempre o pior elemento atual. Quando chega um novo candidato:

- Se `dist < topDist[k-1]` → insere no lugar certo, deslocando os maiores para a direita
- Se não → ignora (o candidato não está no top-k)

```
Antes da inserção (dist=4.0 entra):
  [ 1.2,  3.5,  7.1,  9.0 ]
                 ↑ 4.0 < 7.1 → inserir aqui

Após a inserção:
  [ 1.2,  3.5,  4.0,  7.1 ]  ← 9.0 foi descartado
```

Ao final da varredura, os k objetos `SearchResult` e as k `String` de label são criados **uma única
vez** em `materialize()`.

---

## Por que isso elimina pressão sobre o GC?

Arrays de primitivos (`double[]`, `byte[]`) são alocados **uma vez** por requisição, no início da
busca. Eles não contêm referências a outros objetos — são blocos contíguos de bytes no heap.

```
Antes (por requisição):
  23.440 objetos alocados (SearchResult + String por candidato)

Depois (por requisição):
  2 arrays (double[k] + byte[k]) + k objetos no materialize()
  = k + 2 alocações  ←  no caso K=1024: 1.026 alocações
```

Queda de ~23.440 para ~1.026 alocações por requisição — redução de ~96%.

---

## O seletor não pode ter estado compartilhado

O `V2IndexSearcher` é um objeto **único** compartilhado entre todas as threads virtuais que processam
requisições em paralelo. Por isso, o `TopKSelector` é criado **localmente em cada chamada** de
`search()` — ele nunca é um campo da classe.

```java
// V2IndexSearcher.search()
TopKSelector selector = new TopKSelector(k);  // local à chamada, thread-safe
for (...) {
    selector.tryInsert(dist, labelByte);
}
return selector.materialize();
```

Isso garante que dois pedidos simultâneos nunca pisem nos dados um do outro.

---

## Impacto esperado

| Métrica | Antes | Esperado |
|---|---|---|
| Alocações por requisição | ~23.440 | ~1.026 |
| Pressão sobre o GC | Alta | Baixa |
| Variância de latência (p99) | Inflada por pausas de GC | Mais estável |
| avg e p99 em regime quente | ~36ms / ~37ms | Redução esperada |

---

## Onde está o código

| Artefato | Localização |
|---|---|
| `TopKSelector` (seletor isolado) | `search/TopKSelector.java` |
| Uso em `V2IndexSearcher` | `search/V2IndexSearcher.java` — método `search()` |
| Uso em `QuantizedBruteForceSearcher` | `search/QuantizedBruteForceSearcher.java` — métodos `searchI8()` e `searchI16()` |
| Testes do seletor | `src/test/.../search/TopKSelectorTest.java` |
