---
name: pt-br-acentuacao
description: Aplica acentuação e ortografia do português brasileiro em texto corrido, preservando código, identificadores, comandos e literais técnicos. Use quando o agente for escrever ou editar documentação, comentários, logs, prompts, README, changelogs ou qualquer texto em PT-BR dentro de arquivos.
---

# Acentuação PT-BR

## Objetivo

Manter texto em português brasileiro com acentuação correta sempre que houver escrita em arquivos.

## Início rápido

Ao escrever em PT-BR:
- Use acentos, cedilha, hífen e maiúsculas conforme a norma.
- Preserve nomes de funções, variáveis, classes, caminhos, URLs, comandos, chaves JSON e trechos de código exatamente como estão.
- Não corrija termos técnicos, siglas ou nomes próprios que já estejam definidos pelo contexto.

## Fluxo de trabalho

1. Identifique se o conteúdo é texto natural em PT-BR.
2. Reescreva apenas a parte textual, sem tocar em código ou literais.
3. Corrija acentuação, concordância e pontuação quando isso não afetar o significado técnico.
4. Se houver dúvida entre texto humano e literal técnico, mantenha o literal intacto.

## Regra prática

Se o arquivo tiver documentação, comentário, log, prompt ou texto de interface em português, escreva sempre com acentuação correta.

## Exemplo

Entrada:

"essa documentacao explica como o agente escreve logs e comentarios"

Saída:

"Essa documentação explica como o agente escreve logs e comentários."
