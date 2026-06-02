# Issue 00: Quantização baseline e plano B int16

Status: done
Tipo: AFK

## O que construir

Entregar uma fatia anterior ao IVF que valide a troca da representação vetorial atual em float32 por uma representação quantizada sem perder a decisão de fraude de forma inaceitável. A fatia deve produzir um baseline quantizado rodando ainda sobre o caminho de busca exata ou Força Bruta, medir impacto de latência e medir divergência de ranking e de decisão final contra a referência atual.

Se a estratégia em int8 degradar a qualidade acima do limite definido para proteção da detecção, a mesma fatia deve exercitar o plano B em int16 e deixar explícito qual representação segue aprovada para desbloquear a construção do artefato V2.

## Como o teste funciona

O `QuantizationBenchmark` carrega um subconjunto de `test/test-data.json` e transforma cada `TransactionRequest` em vetor numérico com `TransactionVectorizer`. Em seguida ele executa a mesma query em três buscadores:

- `MmapBruteForceSearcher`, que usa o `dataset.bin` float32 como referência.
- `QuantizedBruteForceSearcher` em `int8`.
- `QuantizedBruteForceSearcher` em `int16`.

Para cada query, o benchmark mede o tempo de busca com `System.nanoTime()`, coleta os 5 vizinhos mais próximos e compara os resultados quantizados com o baseline float32.

As contas usadas no relatório são estas:

- `recall@5`: aqui significa a taxa de queries em que a contagem de registros `fraud` no top-5 bate com a referência float32. Se a contagem diverge, a query conta como mismatch.
- `divergência`: percentual de queries em que a decisão final muda. A decisão final é `aprovado` quando a proporção de `fraud` no top-5 fica abaixo de `0.6`.
- `p50` e `p99`: latência por query. O benchmark mede cada consulta individualmente, ordena os tempos e pega o percentil 50 e o percentil 99.

Na prática, o fluxo é:

1. Ler o corpus de teste.
2. Vetorizar cada request.
3. Rodar a busca float32 e guardar o resultado de referência.
4. Rodar a busca quantizada em `int8` e `int16`.
5. Comparar contagem de `fraud`, decisão final e latência.
6. Emitir o veredito com base em `recall@5 >= 99%` e `divergência <= 1%`.

## Resultado executado

Executado dentro do container da aplicação, com o `test/` montado em `/app/test` e `TEST_DATA_PATH=/app/test/test-data.json`.

- Queries avaliadas: `500`
- `float32`: `recall@5=100.00%`, `diverg=0.00%`, `p50=58.43ms`, `p99=111.90ms`
- `int8`: `recall@5=99.20%`, `diverg=0.20%`, `p50=23.13ms`, `p99=59.67ms`
- `int16`: `recall@5=100.00%`, `diverg=0.00%`, `p50=29.46ms`, `p99=57.96ms`

Veredito: `int8` aprovado, então a Issue 01 pode seguir com a representação quantizada em `int8`.

## Critérios de aceite

- [X] Existe um caminho reproduzível que gera e consome um baseline quantizado antes do IVF, preservando o sentinela de ausência de last_transaction e mantendo o contrato externo da API.
- [X] Existe um check automatizado que compara float32 versus quantização no mesmo conjunto de queries, registrando impacto em latência, divergência de ranking e divergência na decisão final de fraude.
- [X] Se int8 ultrapassar o limite de perda definido para proteger a qualidade, a fatia executa a alternativa em int16 e deixa uma recomendação objetiva sobre qual representação desbloqueia a Issue 01.

## Bloqueada por

Nenhum - pode começar imediatamente

## Comments

- 2026-06-01: issue criada para cobrir explicitamente a Fase 2.1 da especificação, antecipando o risco de perda de qualidade da quantização antes da clusterização.
- 2026-06-01: benchmark executado no container da aplicação; `int8` aprovado com `recall@5=99.20%` e `diverg=0.20%`. `int16` também passou, mas ficou como plano B.

## Referências

- [PRD Completo](../PRD.md)
- [Documentação Técnica da V2](../knowledge/v2/01-tesseract.md)
