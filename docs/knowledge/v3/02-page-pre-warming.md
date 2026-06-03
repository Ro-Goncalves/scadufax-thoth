# V3-A: Page Pre-Warming

## O problema

A V2 fechou com p99 de ~36ms em regime quente, mas o **primeiro benchmark da sessão** marcava ~130ms.
A causa: o artefato `.v2` é carregado via **mmap** (memory-mapped file), e o sistema operacional
carrega as páginas do arquivo sob demanda, não de uma vez só.

---

## O que é mmap e por que ele causa esse problema?

Quando a JVM abre o arquivo `.v2` com `FileChannel.map()`, o SO não copia o arquivo inteiro para a RAM.
Em vez disso, ele cria uma **janela de endereços virtuais** que aponta para o arquivo em disco.
Cada bloco de 4 KB dessa janela se chama **página**.

A página só é carregada de fato na memória quando o programa tenta ler aquele endereço pela primeira vez.
Esse evento se chama **page fault** (falta de página):

```
Acesso com page fault (arquivo frio):
  código → endereço de memória → SO: "essa página não está na RAM"
         → lê 4 KB do disco → mapeia na tabela de páginas → retorna dado
         → ~130 ms 😱

Acesso sem page fault (arquivo quente):
  código → endereço de memória → SO: "página já está na RAM"
         → retorna dado imediatamente
         → ~36 ms 🚀
```

O K6 mede desde a primeira requisição. Se o arquivo ainda está frio, essa requisição paga o custo de
disco e infla o p99 da sessão inteira.

---

## A solução: tocar todas as páginas durante o bootstrap

Se percorrermos o `MemorySegment` inteiro **antes** do `/ready` responder 200, todas as páginas são
carregadas na RAM antecipadamente. Quando as requisições reais chegarem, o arquivo já estará quente.

O percurso é simples: lemos **1 byte a cada 4 KB** (o tamanho de uma página). Não precisamos ler o
arquivo inteiro — basta tocar qualquer byte dentro de cada página para forçar o page fault durante o
bootstrap.

```java
// V2IndexSearcher.prewarm()
for (long off = 0; off < size; off += 4096) {
    sink += file.get(ValueLayout.JAVA_BYTE, off);
}
```

---

## Por que tocar o `MemorySegment` e não ler o arquivo por um `FileChannel` separado?

Esta é a decisão de design mais importante da V3-A.

Se lêssemos o arquivo com um `FileChannel` separado, aquecemos o **page cache do SO** — o buffer de disco
que o kernel mantém para acelerar leituras repetidas. Mas o `MemorySegment` que o hot path usa continuaria
com as páginas ausentes da **tabela de páginas do processo**.

Na primeira busca real, o kernel ainda precisaria de um **soft fault** para mapear a página do cache para
o espaço de endereços do processo. É uma operação muito mais rápida que um page fault de disco
(~microsegundos em vez de ~milissegundos), mas é um overhead desnecessário.

Ao tocar o próprio `MemorySegment` — exatamente o mesmo objeto que `V2IndexSearcher.search()` usa —
eliminamos **os dois** overheads:

1. O acesso ao disco (hard fault)
2. O mapeamento tardio na tabela de páginas do processo (soft fault)

---

## Por que precisamos do campo `PREWARM_SINK`?

O laço de pré-aquecimento lê bytes do arquivo mas **não usa o resultado para nada observável** pelo
programa. O compilador JIT (compilador em tempo de execução da JVM) detecta isso e pode eliminar o laço
inteiro — uma otimização chamada **dead-code elimination** (eliminação de código morto).

Para impedir isso, acumulamos os bytes em um campo `static volatile`:

```java
private static volatile long PREWARM_SINK = 0;

// ... dentro do prewarm():
PREWARM_SINK = sink;
```

A palavra-chave `volatile` força a JVM a realmente ler e gravar o valor na memória (sem cache em
registrador). Com isso, o compilador não pode provar que a operação é inócua e precisa manter o laço.

---

## Onde o prewarm entra na orquestração de boot

A sequência de inicialização do `JavalinBootstrap.create()` fica:

```
1. V2IndexSearcher instanciado (mmap aberto, mas frio)
2. searcher.prewarm()          ← page-warm: toca todas as páginas
3. WarmupService.warmup()      ← JIT-warm: 50 buscas reais compilam o hot path
4. Javalin.create().start()    ← servidor aceita conexões
5. /ready responde 200         ← tráfego liberado
```

A propriedade de **gating** já existente se mantém: o `/ready` só responde 200 depois que o servidor
subiu, e o servidor só sobe depois que o warmup completo (page + JIT) terminou.

---

## Custo e risco

| Aspecto | Detalhe |
|---|---|
| Tempo de startup | +<1 s para ~45–48 MB lidos sequencialmente |
| Memória | Páginas file-backed — reclamáveis pelo SO sob pressão de memória |
| Limite de 165 MB | 45 MB de mmap convive com `-Xmx80m` sem risco de OOM |
| Regressão de comportamento | Nenhuma — `prewarm()` só lê, não altera o índice |

---

## Onde está o código

| Artefato | Localização |
|---|---|
| Método `prewarm()` | `V2IndexSearcher.java` — após `close()` |
| Campo `PREWARM_SINK` | `V2IndexSearcher.java` — campos de classe |
| Chamada de orquestração | `JavalinBootstrap.java` linha 40, antes de `WarmupService.warmup()` |
| Teste de cobertura | `V2IndexSearcherPrewarmTest.java` |
