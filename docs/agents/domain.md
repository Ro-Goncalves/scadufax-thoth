# Docs de domínio

Este repositório adota layout single-context.

## Antes de explorar, leia estes arquivos

- CONTEXT.md na raiz, quando existir
- docs/adr/ na raiz, quando existir, priorizando ADRs ligadas à área da mudança
- docs/knowledge/scadufax-thoth.md e docs/rinha/ quando o trabalho tocar regras da competição, arquitetura, trade-offs ou decisões já registradas

Se algum desses arquivos não existir, prossiga silenciosamente. Não trate a ausência como erro e não proponha criá-los sem necessidade real.

## Estrutura esperada

- Single-context: um CONTEXT.md na raiz e um docs/adr/ na raiz
- Se o repositório migrar no futuro para multi-context, este contrato deve ser revisado junto com o AGENTS.md

## Vocabulário de domínio

Ao nomear conceitos de domínio em issues, propostas, hipóteses e testes, prefira os termos definidos em CONTEXT.md e, enquanto ele não existir, preserve o vocabulário já usado em docs/knowledge/scadufax-thoth.md e docs/rinha/.

Se um conceito necessário ainda não estiver documentado, trate isso como sinal de lacuna real ou de linguagem inventada e exponha a dúvida.

## Conflitos com ADRs

Se a sua saída contrariar uma ADR existente, exponha isso explicitamente em vez de sobrescrever a decisão em silêncio.