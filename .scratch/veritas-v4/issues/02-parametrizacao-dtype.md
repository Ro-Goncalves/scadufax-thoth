# Issue 02 — V4-A Passo 0-A: Parametrização de dtype (i8/i16)

Status: open

## Issue pai

[PRD: Tesseract V4 — Veritas](../PRD.md)

## O que construir

Tornar o tipo de quantização um parâmetro de build — `ARG DTYPE=i8` no Dockerfile —
para que a investigação de quantização (Issue 03) possa comparar int8 e int16 com
builds separados, sem reescrever código.

Hoje o pipeline suporta apenas int8; o buscador rejeita qualquer artefato que não seja
i8. O objetivo é que um único binário produza artefatos i8 ou i16 conforme o
`ARG DTYPE` passado no build, e que o buscador identifique e use o tipo correto
automaticamente a partir do byte `dtype` já presente no header do artefato.

As constantes de layout por dtype codificam as decisões de escala e sentinela
(protótipo: `docs/knowledge/v4/01-veritas.md`):

| Constante | I8 | I16 |
|---|---|---|
| `DTYPE` byte no header | `1` | `2` |
| Scale de quantização | `127` | `10.000` |
| `RECORD_SIZE` | `16` (1+14+1) | `30` (1+28+1) |
| Sentinela (`-1.0f`) | `Byte.MIN_VALUE` | `Short.MIN_VALUE` |

O cálculo de distância int16 (`EuclideanDistanceCalculator.calculateI16`) já está
implementado e não muda.

## Critérios de aceite

- [ ] `docker build --build-arg DTYPE=i8` produz artefato i8 funcional (comportamento
      atual inalterado).
- [ ] `docker build --build-arg DTYPE=i16` produz artefato i16 funcional (header com
      `DTYPE_I16`, vetores como shorts, centróides como shorts).
- [ ] O `V2IndexSearcher` lê e busca corretamente em artefatos de ambos os dtypes.
- [ ] `V2QualityGuardTest` passa para ambos os dtypes com a fixture existente.
- [ ] Nenhum teste existente regride.

## Bloqueada por

Issue 01 (V4-0) — pode rodar em paralelo com o diagnóstico, mas os resultados
precisam estar prontos antes da Issue 03 (investigação de quantização).