# Issue 02 — V3-A: Page pre-warming antes do /ready

Status: done

## Issue pai

[PRD: Tesseract V3 — Celeritas](../PRD.md)

## O que construir

Eliminar a variância cold/hot de latência aquecendo todas as páginas do mapeamento do
artefato `.v2` durante o bootstrap, **antes** do `/ready` retornar 200. Com o
*mmap*, o SO carrega páginas sob demanda e a primeira requisição da sessão paga *Page
Fault penalty* (~130ms vs ~36ms em regime quente). Após esta fatia, o `/ready` só
libera tráfego quando a API já está quente, e o K6 mede regime estável desde a primeira
requisição.

O aquecimento deve tocar **o mapeamento exato usado no hot path** (o `MemorySegment` do
searcher), percorrendo o arquivo inteiro com passo de uma página (4KB) e somando os
bytes lidos num *sink* para impedir que o JIT descarte o laço (*dead-code elimination*).
Tocar o próprio mapeamento — em vez de ler o arquivo por um `FileChannel` separado —
elimina não só o acesso a disco, mas também o *soft fault* na tabela de páginas do
processo.

A orquestração entra no serviço de warmup que já roda síncrono antes de o servidor
aceitar conexões: a sequência passa a ser **page-warm → JIT-warm → `/ready` libera**,
preservando a propriedade de gating já existente.

## Critérios de aceite

- [x] No bootstrap, todas as páginas do `.v2` são tocadas antes de o `/ready` retornar 200.
- [x] O aquecimento percorre o `MemorySegment` do searcher (o mesmo usado na busca), não
      um canal de leitura separado.
- [x] O `/ready` só responde 200 após page-warm **e** JIT-warm concluírem.
- [x] O acréscimo ao tempo de startup é da ordem de ~1s (leitura sequencial de ~45–48MB).
- [x] Não há regressão de memória: a aplicação sobe e opera dentro do limite de 165MB por
      API (sem `OutOfMemoryError`).
- [x] **Teste do page pre-warmer (leve):** sobre um `.v2` de teste, o aquecimento percorre
      o mapeamento sem lançar exceção e cobre o número esperado de páginas (cobertura
      completa do arquivo). Não testa latência.

## Bloqueada por

Nenhum - pode começar imediatamente.
