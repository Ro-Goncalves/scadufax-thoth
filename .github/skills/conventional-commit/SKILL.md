---
name: conventional-commit
description: Planeja e executa commits Git no padrão Conventional Commits, separando mudanças por intenção e escrevendo mensagens válidas no formato <type>[scope]: <description>. Use quando o usuário pedir para commitar alterações, revisar o staging, dividir mudanças em commits coerentes ou padronizar mensagens de commit.
argument-hint: "Quais arquivos ou mudanças devem entrar no(s) commit(s)?"
---

# Commits Convencionais

Use esta skill quando a tarefa envolver criar um ou mais commits Git seguindo Conventional Commits.

## Objetivo

Produzir commits pequenos, coerentes e rastreáveis no formato:

```text
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

## Regras

- Sempre leia o status do Git antes de decidir o commit.
- Leia o diff, os nomes dos arquivos ou o contexto mínimo necessário para entender a intenção da mudança.
- Não misture alterações sem relação no mesmo commit.
- Se houver mais de um tipo plausível, separe em múltiplos commits.
- Use assunto no imperativo, curto e específico.
- O `type` é obrigatório e deve refletir a intenção principal da mudança.
- O `scope` é opcional, mas deve ser usado quando reduzir ambiguidade.
- Use body apenas quando o contexto adicional realmente ajudar.
- Use footer para referências, coautoria ou `BREAKING CHANGE` quando aplicável.
- Não faça push sem pedido explícito do usuário.

## Tipos comuns

- `feat`: nova funcionalidade
- `fix`: correção de bug
- `docs`: documentação
- `refactor`: reorganização sem mudar regra de negócio
- `style`: formatação sem efeito funcional
- `test`: testes
- `build`: build ou dependências com impacto no build
- `ci`: pipeline ou automação de CI
- `chore`: manutenção que não altera comportamento da aplicação
- `perf`: melhora de desempenho
- `revert`: reversão de commit anterior

## Fluxo de trabalho

1. Inspecione `git status` e identifique arquivos modificados, novos ou deletados.
2. Leia apenas o diff necessário para agrupar mudanças por intenção.
3. Proponha um agrupamento quando houver mais de um commit plausível.
4. Faça staging seletivo por grupo de mudança.
5. Escreva a mensagem no formato `type(scope): descrição`.
6. Adicione body e footers somente quando trouxerem contexto útil.
7. Execute o commit e valide o resultado com `git log -1 --stat` ou `git show --stat --oneline HEAD`.

## Critérios para escolher o type

- Use `feat` quando a mudança adicionar comportamento novo perceptível.
- Use `fix` quando a mudança corrigir comportamento incorreto.
- Use `refactor` quando apenas reorganizar a implementação sem mudar comportamento.
- Use `docs` quando alterar apenas documentação.
- Use `chore` para manutenção, configuração ou rotina de desenvolvimento sem efeito funcional.
- Use `build` quando a mudança afetar empacotamento, build ou dependências relevantes para o build.

## Breaking changes

Use `!` após o type ou scope, ou adicione um footer:

```text
BREAKING CHANGE: descreva o impacto e o que mudou
```

## Exemplos

- `feat(formulario): adiciona envio com validação por etapa`
- `fix(upload): corrige falha ao anexar arquivo maior que 5 MB`
- `docs(readme): documenta configuração do Apps Script`
- `refactor(api): simplifica montagem do payload`
- `feat(api)!: remove campo legado da resposta`

## Conduta

- Preserve alterações do usuário que não fazem parte do pedido.
- Prefira staging seletivo a `git add .` quando houver risco de misturar contextos.
- Se a intenção da mudança estiver ambígua, explique o agrupamento proposto antes de commitar.