# Issue 01 — Pin do envelope operacional (K=1024 / nprobe=4)

Status: done

## Issue pai

[PRD: Tesseract V3 — Celeritas](../PRD.md)

## O que construir

Fixar o build e o runtime no envelope vencedor da V2 — **K=1024, nprobe=4** — para que
a imagem default reflita a configuração campeã e qualquer medição "antes/depois" da V3
seja confiável.

Hoje o build default constrói `NUM_CLUSTERS=256` (estágio de build) e o runtime sonda
`NPROBE=8` (compose). Ambos devem passar a refletir o envelope operacional: o artefato
`.v2` é gerado com 1024 clusters e os dois serviços de API sondam 4 clusters por
requisição. Os parâmetros continuam sobrescrevíveis por variável de ambiente para
experimentos futuros — muda apenas o **valor default**.

O K-Means paralelo já existente absorve o custo de K=1024 no estágio 1 do Docker
(todos os cores, ~150s); o runtime não muda de comportamento, apenas de configuração.

## Critérios de aceite

- [x] O build default produz um artefato `.v2` com 1024 clusters (sem passar nenhum
      ARG/env extra).
- [x] Os dois serviços de API sobem com `nprobe=4` por default.
- [x] Ambos os parâmetros permanecem sobrescrevíveis por variável de ambiente.
- [x] `docker compose up --build` conclui sem erro e a API responde `/ready` com 200.
- [x] O tempo de build permanece aceitável (K-Means paralelo no estágio de build).

## Bloqueada por

Nenhum - pode começar imediatamente.
