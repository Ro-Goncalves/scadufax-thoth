---
name: semantic-tag
description: Padroniza a criação de tags Git anotadas seguindo o Versionamento Semântico (SemVer). Use quando o usuário quiser lançar uma nova versão (release), aplicar uma correção crítica (hotfix) ou marcar um marco histórico (milestone) no repositório.
argument-hint: "Qual é o tipo de atualização (Major, Minor, Patch) ou o marco que esta tag representa?"
---

# Tags Semânticas (SemVer)

Use esta skill para criar tags Git profissionais que documentem lançamentos, correções e marcos importantes do projeto, facilitando o rastreamento do histórico.

## Objetivo

Gerar tags anotadas utilizando o padrão Semantic Versioning (vX.Y.Z) para representar o estado oficial e estável do código.

## Convenção adotada

- Use **sempre** tags anotadas com o comando `git tag -a`.
- Siga o padrão SemVer com o prefixo `v`: `v<Major>.<Minor>.<Patch>`.
  - **Major (v2.0.0):** Mudanças grandes e incompatíveis.
  - **Minor (v1.1.0):** Novas funcionalidades compatíveis com a versão atual.
  - **Patch (v1.0.1):** Correções de bugs (Hotfixes).
- Para marcos históricos ou versões de teste (Milestones), use sufixos padronizados: `-beta`, `-rc` (Release Candidate), ou `-alpha` (Ex: `v1.1.0-beta`).
- Não faça push da tag sem pedido explícito do usuário.

## Fluxo de trabalho

1. Verifique se o repositório está no estado esperado com `git status --porcelain` (idealmente, a árvore de trabalho deve estar limpa).
2. Liste as tags existentes com `git tag --list` para identificar a versão atual e calcular a próxima.
3. Pergunte ao usuário (se não informado) qual o escopo da mudança (Major, Minor ou Patch).
4. Monte o nome da tag seguindo a convenção `vX.Y.Z`.
5. Crie a tag anotada com um título claro e uma lista em bullets (Changelog) resumindo o que há de novo.
6. Valide a tag com `git show --no-patch --decorate <nome-da-tag>`.

## Mensagem da tag

A anotação deve servir como um mini-changelog. Prefira este formato:

```text
Release vX.Y.Z

Novidades:
- Adiciona funcionalidade X
- Melhora a performance do componente Y

Correções (Hotfixes):
- Resolve o travamento na tela Z

```

## Regras

* Apenas crie tags em commits que representem um estado funcional e testado do código (evite "taguear" código quebrado, a não ser que seja para marcar onde quebrou).
* Se o usuário pedir para marcar um status temporário e não uma versão oficial, sugira criar uma branch temporária ou explique a diferença.
* Nunca substitua, mova ou force (`-f`) tags existentes sem confirmação explícita, pois tags devem ser imutáveis.
* Ao solicitar o push, utilize `git push origin <nome-da-tag>`.

## Exemplos

* Lançamento inicial: `v1.0.0`
* Nova funcionalidade: `v1.1.0`
* Correção de bug em produção (Hotfix): `v1.1.1`
* Marco histórico de teste: `v2.0.0-beta`

## Comandos típicos

```text
# Criação da tag
git tag -a v1.1.0 -m "Release v1.1.0" -m "Novidades: - Feature X"

# Verificação
git show --no-patch --decorate v1.1.0

# Envio para o repositório remoto (apenas se solicitado)
git push origin v1.1.0

```

## Conduta

* Trate a tag como uma fotografia imutável de uma entrega.
* Oriente o usuário sobre a importância de manter um histórico limpo.
* Ao final da execução, informe o nome da tag gerada e, se aplicável, o comando que o usuário deve usar caso queira enviar a tag para o repositório remoto (`git push origin <tag>`).
