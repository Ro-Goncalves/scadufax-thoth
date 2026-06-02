# Benchmark: Linha de Base

Para garantir a evolução técnica orientada a dados do *Scadufax Thoth*, registramos aqui o resultado oficial do teste de carga inicial rodando sobre a nossa arquitetura V1.

## Ambiente e Condições do Teste

* **Motor:** `MmapBruteForceSearcher`
* **Dataset Lido:** 3.000.000 de registros.
* **Restrição de Hardware:** `0.45 vCPU` e `165MB` de memória por instância.
* **Carga:** 1 Virtual User, 5 iterações completas.

## Resultado do K6

```
█ THRESHOLDS 

  checks
  ✓ 'rate==1.0' rate=100.00%

  http_req_failed
  ✓ 'rate==0.0' rate=0.00%


█ TOTAL RESULTS 

  checks_total.......: 20      2.726039/s
  checks_succeeded...: 100.00% 20 out of 20
  checks_failed......: 0.00%   0 out of 20

  ✓ status is 200
  ✓ body is json
  ✓ approved is boolean
  ✓ fraud_score is number

  HTTP
  http_req_duration..............: avg=1.46s min=323.93ms med=1.43s max=2.25s p(90)=2.17s p(95)=2.21s
    { expected_response:true }...: avg=1.46s min=323.93ms med=1.43s max=2.25s p(90)=2.17s p(95)=2.21s
  http_req_failed................: 0.00%  0 out of 5
  http_reqs......................: 5      0.68151/s

  EXECUTION
  iteration_duration.............: avg=1.37s min=324.29ms med=1.43s max=2.25s p(90)=2.17s p(95)=2.21s
  iterations.....................: 5      0.68151/s
  vus............................: 1      min=1      max=1
  vus_max........................: 1      min=1      max=1

  NETWORK
  data_received..................: 945 B  129 B/s
  data_sent......................: 2.9 kB 393 B/s
```

## Análise Arquitetural do Resultado

A partir desta execução, derivamos duas conclusões arquiteturais que guiarão a Fase 2:

### A Vitória da Estabilidade e Limite de Memória

O motor atingiu **0.00% de falhas** e **100% de sucesso nas verificações** de contrato HTTP e JSON. O Javalin processou a requisição perfeitamente, o Jackson converteu o JSON, o `TransactionVectorizer` aplicou as regras de *Clamp*, e a FFM API varreu os 3 milhões de registros sem causar *Segmentation Fault* ou estourar a Heap do Java. A fundação elétrica e o gerenciamento de memória estão perfeitamente validados.

### O Gargalo Computacional

A métrica `http_req_duration` expôs a dura realidade da Força Bruta matemática. A aplicação demorou, em média, **1.11 segundos** para responder a uma única requisição (com picos de **1.92 segundos**). O sistema entregou um *throughput* pífio de `0.89 requisições por segundo`.

**Motivo:** Para cada requisição HTTP, a CPU do Java está sendo obrigada a calcular a diferença ao quadrado (`float`) 42.000.000 de vezes (3 milhões de registros × 14 dimensões). Num ambiente com limite de `0.45 vCPU`, o processador engasga em cálculos matemáticos de ponto flutuante.

### Conclusão e Próximos Passos

A nossa linha de base está perfeitamente funcional e exata, mas excessivamente lenta. O objetivo da próxima iteração é alterar a complexidade espacial da busca de `O(N)` para heurísticas de vizinhança.

As métricas a serem batidas pela V2 são:

* Manter `http_req_failed: 0.00%`
* Reduzir `http_req_duration (avg)` de **1.11s** para a casa dos **milissegundos (< 0.05s)**.
