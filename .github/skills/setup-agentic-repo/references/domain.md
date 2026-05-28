# Docs de Domínio

Como as skills de engenharia devem consumir a documentação de domínio deste repositório ao explorar a base de código.

## Antes de explorar, leia estes arquivos

- **`CONTEXT.md`** na raiz do repositório, ou
- **`CONTEXT-MAP.md`** na raiz do repositório, se existir — ele aponta para um `CONTEXT.md` por contexto. Leia cada um que for relevante para o tópico.
- **`docs/adr/`** — leia ADRs que afetem a área em que você vai trabalhar. Em repositórios multi-contexto, verifique também `src/<context>/docs/adr/` para decisões específicas do contexto.

Se algum desses arquivos não existir, **prossiga silenciosamente**. Não aponte a ausência; não sugira criá-los de antemão. A skill produtora (`/grill-with-docs`) os cria sob demanda quando termos ou decisões forem realmente resolvidos.

## Estrutura de arquivos

Repositório de contexto único (a maioria dos repositórios):

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-event-sourced-orders.md
│   └── 0002-postgres-for-write-model.md
└── src/
```

Repositório multi-contexto (presença de `CONTEXT-MAP.md` na raiz):

```
/
├── CONTEXT-MAP.md
├── docs/adr/                          ← decisões de sistema
└── src/
    ├── ordering/
    │   ├── CONTEXT.md
    │   └── docs/adr/                  ← decisões específicas do contexto
    └── billing/
        ├── CONTEXT.md
        └── docs/adr/
```

## Use o vocabulário do glossário

Quando sua saída nomear um conceito de domínio (em um título de issue, uma proposta de refatoração, uma hipótese, nome de teste), use o termo conforme definido em `CONTEXT.md`. Não mude para sinônimos que o glossário explicitamente evita.

Se o conceito que você precisa ainda não estiver no glossário, isso é um sinal — ou você está inventando linguagem que o projeto não usa (reconsidere) ou há uma lacuna real (registre-a para `/grill-with-docs`).

## Sinalizar conflitos em ADRs

Se sua saída contradiz uma ADR existente, exponha isso explicitamente em vez de sobrescrever silenciosamente:

> _Contradiz a ADR-0007 (ordens baseadas em eventos) — mas vale a pena reabrir porque…_
