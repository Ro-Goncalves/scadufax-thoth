
## Resultado do K6

### int16

```
█ THRESHOLDS 

  checks
  ✓ 'rate==1.0' rate=100.00%

  http_req_failed
  ✓ 'rate==0.0' rate=0.00%


█ TOTAL RESULTS 

  checks_total.......: 20      7.800187/s
  checks_succeeded...: 100.00% 20 out of 20
  checks_failed......: 0.00%   0 out of 20

  ✓ status is 200
  ✓ body is json
  ✓ approved is boolean
  ✓ fraud_score is number

  HTTP
  http_req_duration..............: avg=512.03ms min=85.08ms med=585.95ms max=813.39ms p(90)=758.45ms p(95)=785.92ms
    { expected_response:true }...: avg=512.03ms min=85.08ms med=585.95ms max=813.39ms p(90)=758.45ms p(95)=785.92ms
  http_req_failed................: 0.00%  0 out of 5
  http_reqs......................: 5      1.950047/s

  EXECUTION
  iteration_duration.............: avg=512.75ms min=85.55ms med=586.43ms max=813.77ms p(90)=759.42ms p(95)=786.59ms
  iterations.....................: 5      1.950047/s
  vus............................: 1      min=1      max=1
  vus_max........................: 1      min=1      max=1

  NETWORK
  data_received..................: 945 B  369 B/s
  data_sent......................: 2.9 kB 1.1 kB/s
```

### int8

```
█ THRESHOLDS 

  checks
  ✓ 'rate==1.0' rate=100.00%

  http_req_failed
  ✓ 'rate==0.0' rate=0.00%


█ TOTAL RESULTS 

  checks_total.......: 20      8.259263/s
  checks_succeeded...: 100.00% 20 out of 20
  checks_failed......: 0.00%   0 out of 20

  ✓ status is 200
  ✓ body is json
  ✓ approved is boolean
  ✓ fraud_score is number

  HTTP
  http_req_duration..............: avg=481.24ms min=32.28ms med=177.63ms max=1.09s p(90)=1.08s p(95)=1.08s
    { expected_response:true }...: avg=481.24ms min=32.28ms med=177.63ms max=1.09s p(90)=1.08s p(95)=1.08s
  http_req_failed................: 0.00%  0 out of 5
  http_reqs......................: 5      2.064816/s

  EXECUTION
  iteration_duration.............: avg=483.92ms min=32.5ms  med=177.95ms max=1.09s p(90)=1.08s p(95)=1.09s
  iterations.....................: 5      2.064816/s
  vus............................: 1      min=1      max=1
  vus_max........................: 1      min=1      max=1

  NETWORK
  data_received..................: 945 B  390 B/s
  data_sent......................: 2.9 kB 1.2 kB/s
```