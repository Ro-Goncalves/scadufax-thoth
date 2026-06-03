# Issue 03: Comparação exata e guarda de qualidade

Status: done
Tipo: AFK

## O que construir

Adicionar uma guarda de qualidade que compare o resultado da V2 com uma referência exata antes de aceitar mudanças de performance. A fatia deve executar um conjunto representativo de queries pelos dois caminhos, medir divergência de ranking e de decisão final e sinalizar quando a otimização começa a comprometer a detecção.

O objetivo é impedir que a V2 fique rápida às custas de perda silenciosa de qualidade, deixando a comparação reproduzível e utilizável em futuras iterações.

## Critérios de aceite

- [x] Existe um check reproduzível que executa as mesmas queries contra a referência exata e contra o caminho IVF e registra divergências relevantes.
- [x] O check falha quando a divergência ultrapassa o limite definido para proteger a decisão final de fraude.
- [x] O resultado da comparação deixa claro se a perda observada veio da quantização, do particionamento ou da combinação experimental escolhida.

## Bloqueada por

- [Issue 02: Busca IVF real no caminho da requisição](02-busca-ivf-real-no-caminho-da-requisicao.md)

## Comments

- 2026-06-01: issue criada a partir do [PRD](../PRD.md) após aprovação da decomposição em fatias.
- 2026-06-02: implementado `V2QualityGuardTest.java`. Três caminhos comparados por query: float32 BF (ground truth) → V2 full-scan (isola quantização) → V2 IVF nprobe=2 (isola particionamento). Limites: ≥95% acordo quantização, ≥80% acordo IVF. Resultado na execução atual: 100% nos dois caminhos, 0 divergências. `docker-compose.yml` ajustado para aceitar NPROBE via env var. `run-benchmark.sh` criado para Issue 04 (desktop).

## Referências

- [PRD Completo](../PRD.md)
- [Documentação Técnica da V2](../knowledge/v2/01-tesseract.md)