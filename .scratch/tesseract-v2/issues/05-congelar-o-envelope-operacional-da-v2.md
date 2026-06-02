# Issue 05: Congelar o envelope operacional da V2

Status: ready-for-human
Tipo: HITL

## O que construir

Conduzir a decisão final da V2 a partir dos resultados das fatias anteriores. Esta fatia consolida a configuração operacional padrão, registra se a estratégia em int8 fica aprovada para a versão e fecha o escopo da V2 sem puxar itens de V3 para dentro da entrega.

O objetivo é transformar os resultados técnicos em uma decisão explícita de produto e arquitetura, pronta para orientar a versão que será realmente perseguida.

## Critérios de aceite

- [ ] Existe uma decisão explícita sobre a configuração padrão de K e nprobe que seguirá para a V2.
- [ ] Existe uma decisão explícita sobre manter o caminho em int8 ou abrir revisão controlada da estratégia por risco de qualidade.
- [ ] O rastreamento da V2 registra a configuração escolhida, o racional e o que permaneceu fora de escopo para fases futuras.

## Bloqueada por

- [Issue 04: Matriz de benchmark para K e nprobe](04-matriz-de-benchmark-para-k-e-nprobe.md)

## Comments

- 2026-06-01: issue criada a partir do [PRD](../PRD.md) após aprovação da decomposição em fatias.

## Referências

- [PRD Completo](../PRD.md)
- [Documentação Técnica da V2](../knowledge/v2/01-tesseract.md)