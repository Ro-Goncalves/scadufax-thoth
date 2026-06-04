# Issue 03 — V4-A Passo 1: Investigação de quantização

Status: done

## Issue pai

[PRD: Tesseract V4 — Veritas](../PRD.md)

## O que construir

Medir quantos dos 207 erros de detecção (105 FP + 102 FN) da V3 são causados por
**quantização** (int8 perde precisão) versus **aproximação de IVF** (nprobe=4 não
visita o cluster certo). Essa distinção decide o dtype do artefato para o resto da V4.

O IVF com bbox pruning (Issues 04 e 05) elimina o erro de aproximação, mas não o erro
de quantização. Se int8 já é preciso o suficiente em busca exata, fica int8. Se int8
perde qualidade mesmo sem a aproximação do IVF, migra para int16.

### Experimento

Comparar três buscas no dataset real (`references.json.gz`), contando divergências de
**decisão** (aprovado vs. rejeitado) em relação ao float32 brute-force:

| Busca | nprobe | Dtype | O que isola |
|---|---|---|---|
| float32 brute-force | N/A (scan total) | float32 | Ground truth |
| int8 full-scan | `numClusters` (=1024) | int8 | Perda de quantização int8 |
| int16 full-scan | `numClusters` (=1024) | int16 | Perda de quantização int16 |

"Divergência" = a decisão (aprovado/rejeitado) difere do float32 brute-force.

### Critério de decisão (acordado)

| int8 full-scan divergências | Decisão |
|---|---|
| < ~10 | Fica **int8** — erro era só particionamento, não quantização |
| > ~20 | Migra para **int16** |

## Critérios de aceite

- [x] O experimento foi rodado com o dataset real e produziu três contagens de
      divergência (float32-BF como ground truth, int8-full-scan, int16-full-scan).
- [X] Os números estão registrados em `docs/knowledge/v4/01-veritas.md`.
- [X] A decisão de dtype (**int8** ou **int16**) está registrada com justificativa
      baseada nos números medidos.
- [X] As Issues 04 e 05 não começam antes deste resultado estar registrado.

## Bloqueada por

Issue 02 (parametrização de dtype) — necessária para rodar int16 full-scan.