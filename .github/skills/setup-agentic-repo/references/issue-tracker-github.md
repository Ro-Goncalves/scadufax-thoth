# Rastreador de Issues: GitHub

Issues e PRDs deste repositório ficam como issues do GitHub. Use o CLI `gh` para todas as operações.

## Convenções

- **Criar um issue**: `gh issue create --title "..." --body "..."`. Use um heredoc para corpos com várias linhas.
- **Ler um issue**: `gh issue view <number> --comments`, filtrando os comentários com `jq` e também obtendo os rótulos.
- **Listar issues**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'` com os filtros `--label` e `--state` apropriados.
- **Comentar em um issue**: `gh issue comment <number> --body "..."`
- **Aplicar / remover rótulos**: `gh issue edit <number> --add-label "..."` / `--remove-label "..."`
- **Fechar**: `gh issue close <number> --comment "..."`

Inferir o repositório a partir de `git remote -v` — o `gh` faz isso automaticamente quando executado dentro de um clone.

## Quando uma skill disser "publicar no rastreador de issues"

Crie um issue no GitHub.

## Quando uma skill disser "buscar o ticket relevante"

Execute `gh issue view <number> --comments`.
