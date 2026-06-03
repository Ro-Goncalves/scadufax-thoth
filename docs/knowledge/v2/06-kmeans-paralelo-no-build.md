# Registro Arquitetural V2: K-means Paralelo no Build

Este documento registra a decisão de paralelizar o passo de atribuição do K-means
(`KMeansClusterer`) executado durante a geração do artefato `index.v2`. O gatilho
foi um build que parecia travar ao subir o número de clusters para `K=2048`.

## O Sintoma: "Travamento" no K=2048

Ao gerar o artefato com `K=2048`, o build estacionava na mensagem
`rodando K-means k=2048 iter=20` por vários minutos, sem qualquer saída posterior,
dando a impressão de processo congelado (deadlock ou loop infinito).

Não havia travamento real. O laço de iterações é finito (`maxIterations=20`, com
`break` ao convergir) e cada vetor é atribuído de forma independente — não existe
trava nem espera. O problema era **custo computacional puro sem feedback**.

## A Causa: O Passo de Atribuição é O(n · K · DIMS)

O passo dominante do Lloyd's K-means é a atribuição (`assignStep`): para cada vetor,
mede-se a distância a **todos** os `K` centróides e escolhe-se o mais próximo. O custo
por iteração é:

$$\text{ops} \approx n \times K \times \text{DIMS}$$

Para o dataset real (`references.json.gz`):

* `n = 3.000.000` vetores
* `DIMS = 14`

| K (clusters) | Ops / iteração | Custo relativo |
| ------------ | -------------- | -------------- |
| 256 (padrão) | ~10,7 bilhões  | 1×             |
| 2048         | ~86 bilhões    | 8×             |

Em uma única thread, cada iteração com `K=2048` levava ~70s, totalizando ~25 min só
no K-means — e, como **não havia log entre iterações**, o build ficava mudo o tempo
todo. A combinação "muito lento" + "silencioso" foi lida como "travado".

## A Decisão: Paralelizar a Atribuição + Logar Progresso

A correção tem duas frentes, ambas em `KMeansClusterer.java`.

### 1. Atribuição paralela (ganho ~10×)

O `assignStep` é trivialmente paralelo: cada vetor é atribuído de forma independente,
escrevendo em um índice distinto de `assignments[]`. Não há corrida de dados, e como
`dist()` é puro, o resultado é **bit-a-bit idêntico ao serial** — ou seja, o artefato
gerado continua **determinístico para uma dada seed**. Isso era inegociável: a
clusterização precisa ser reproduzível entre builds.

O passo de atualização (`updateStep`) permanece serial: é apenas O(n · DIMS) ≈ 42M
operações, desprezível perto da atribuição, e acumula em um buffer compartilhado.

```java
private static int assignStep(byte[][] vectors, byte[][] centroids, int[] assignments) {
    int k = centroids.length;
    return IntStream.range(0, vectors.length).parallel().map(i -> {
        byte[] v        = vectors[i];
        int    best     = assignments[i];
        int    bestDist = dist(v, centroids[best]);
        for (int c = 0; c < k; c++) {
            if (c == best) continue;
            int d = dist(v, centroids[c]);
            if (d < bestDist) { bestDist = d; best = c; }
        }
        if (best != assignments[i]) {
            assignments[i] = best;
            return 1;            // mudou de cluster
        }
        return 0;
    }).sum();                    // total de reatribuições (0 = convergiu)
}
```

O método agora retorna o **número de reatribuições** (antes era `boolean changed`),
que serve tanto para detectar convergência (`== 0`) quanto para alimentar o log.

> **Nota sobre o ambiente:** o paralelismo é seguro porque o K-means roda no
> **estágio 1 do Docker (build)**, que tem acesso a todos os cores da máquina. A
> restrição de 1 CPU / 350 MB vale apenas para o estágio 2 (runtime), que carrega o
> artefato já pronto e nunca executa K-means.

### 2. Log de progresso por iteração

Mesmo rápido, um build silencioso é indistinguível de um travado. Cada iteração agora
imprime contagem de reatribuições e tempo, e a convergência é explicitada:

```
[kmeans] iter 1/20: 2998903 reatribuicoes (8.2s)
[kmeans] iter 2/20: 698595 reatribuicoes (6.8s)
...
[kmeans] iter 20/20: 20556 reatribuicoes (8.4s)
```

A curva decrescente de reatribuições é, de quebra, um diagnóstico de qualidade da
clusterização — fica visível se o K-means está convergindo bem ou oscilando.

## Resultado Medido

Build real com `K=2048` sobre os 3.000.000 de vetores (`-Xmx512m`, 12 cores):

| Métrica                  | Antes (serial) | Depois (paralelo) |
| ------------------------ | -------------- | ----------------- |
| Tempo por iteração       | ~70s           | ~7s               |
| K-means total (20 iter)  | ~25 min        | ~150s             |
| Determinismo do artefato | sim            | sim (idêntico)    |
| Feedback de progresso    | nenhum         | por iteração      |

O `user` time (~26 min) versus o `real` time (~2m30s) confirma o aproveitamento dos
cores (~10×). A suíte de testes (37 testes, incluindo `V2IvfSearchTest` e
`V2EndToEndTest`, que exercitam a clusterização) continua verde, confirmando que o
comportamento não mudou.

## Por Que Não Outra Abordagem

* **K-means de Elkan/Hamerly (poda por desigualdade triangular):** reduziria o custo
  assintótico, mas adiciona estado por vetor e complexidade considerável. O ganho de
  ~10× com paralelismo de algumas linhas já tornou o build prático; a poda fica como
  otimização futura se o número de clusters crescer muito.
* **Reduzir `maxIterations`:** mascararia o sintoma sem resolver a causa e
  degradaria a qualidade da clusterização. A curva de reatribuições mostra que as 20
  iterações ainda agregam refinamento real.
