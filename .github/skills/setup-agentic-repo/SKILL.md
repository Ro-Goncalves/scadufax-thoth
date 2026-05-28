---
name: setup-agentic-repo
description: Configura o contrato entre as skills em `AGENTS.md` e `docs/agents/`, incluindo issue tracker, rótulos de triagem, docs de domínio, template de ADR e status de ADR, e delega a inicialização de `AGENTS.md` para uma skill auxiliar quando o arquivo ainda não existir ou estiver vazio. Execute antes do primeiro uso de `to-issues`, `to-prd`, `triage`, `grill-adr`, `diagnose`, `tdd`, `improve-codebase-architecture` ou `zoom-out` — ou se essas skills parecerem não ter contexto sobre o issue tracker, os rótulos de triagem, o template de ADR, os status de ADR ou os docs de domínio.
disable-model-invocation: true
---

# Configurar as skills do Matt Pocock

Cria a configuração por-repositório que as skills de engenharia esperam:

- **Issue tracker** — onde os issues são registrados (GitHub por padrão; markdown local também é suportado por padrão)
- **Rótulos de triagem** — os nomes usados para os cinco papéis canônicos de triagem
- **Docs de domínio** — onde `CONTEXT.md` e ADRs ficam, e as regras de consumo para lê-los
- **Template de ADR** — a estrutura base para novas ADRs em `docs/adr/`
- **Status de ADR** — o mapeamento entre os status canônicos de ADR e as strings reais usadas neste repositório

Esta é uma skill orientada por prompts, não um script determinístico. Explore, apresente o que encontrou, confirme com o usuário e então escreva.

## Processo

### 0. Bootstrap do AGENTS.md

Se `AGENTS.md` não existir ou estiver vazio, chame primeiro `/setup-agent-instructions` para criar a base do arquivo. Depois disso, continue com os passos abaixo.

### 1. Explorar

Olhe o repositório atual para entender seu estado inicial. Leia o que existir; não presuma:

- `git remote -v` e `.git/config` — este é um repositório GitHub? Qual?
- `AGENTS.md` na raiz do repositório — ele existe? Já há uma seção `## Agent Skills`?
- `CONTEXT.md` e `CONTEXT-MAP.md` na raiz do repositório
- `docs/adr/` e quaisquer diretórios `src/*/docs/adr/`
- `docs/agents/` — a saída anterior desta skill já existe? Há `issue-tracker.md`, `triage-labels.md`, `domain.md`, `adr-template.md` e `adrs-labels.md`?
- `.scratch/` — sinal de que uma convenção de issue tracker baseada em markdown local já está em uso

### 2. Apresentar achados e perguntar

Resuma o que está presente e o que está faltando. Em seguida, guie o usuário pelas três decisões **uma de cada vez** — apresente uma seção, obtenha a resposta do usuário, depois passe para a próxima. Não apresente as três de uma só vez.

Os arquivos `docs/agents/adr-template.md` e `docs/agents/adrs-labels.md` não exigem uma quarta rodada de perguntas por padrão. Derive-os diretamente dos arquivos em `./references/`, a menos que o repositório já tenha uma convenção explícita conflitante ou que o usuário peça customização.

Assuma que o usuário não sabe o que esses termos significam. Cada seção começa com uma breve explicação (o que é, por que as skills precisam disso, o que muda se escolherem diferente). Em seguida, mostre as opções e o padrão.

**Section A — Issue tracker.**

> Explicação: O "issue tracker" é onde os issues são registrados para este repositório. Skills como `to-issues`, `triage`, `to-prd` e `qa` leem e escrevem nele — elas precisam saber se devem chamar `gh issue create`, escrever um arquivo markdown em `.scratch/`, ou seguir outro fluxo que você descreva. Escolha o local onde vocês realmente rastreiam o trabalho para este repositório.

Postura padrão: essas skills foram projetadas para o GitHub. Se um `git remote` apontar para o GitHub, proponha isso. Caso contrário (ou se o usuário preferir), ofereça:

- **GitHub** — issues vivem nas Issues do GitHub do repositório (usa o CLI `gh`)
- **Local markdown** — issues vivem como arquivos em `.scratch/<feature>/` neste repositório (bom para projetos solo ou repositórios sem remote)
- **Other** (Jira, Linear, etc.) — peça ao usuário para descrever o fluxo em um parágrafo; a skill registrará isso como texto livre

**Section B — Triage label vocabulary.**

> Explicação: Quando a skill `triage` processa um issue recebido, ela o move por uma máquina de estados — precisa de avaliação, aguardando o reportante, pronto para um agente AFK pegar, pronto para um humano, ou não será corrigido. Para isso, ela precisa aplicar rótulos (ou o equivalente no seu rastreador) que correspondam às strings *que você realmente configurou*. Se seu repositório já usa nomes diferentes (por exemplo `bug:triage` em vez de `needs-triage`), mapeie-os aqui para que a skill aplique os corretos em vez de criar duplicatas.

Os cinco papéis canônicos:

- `needs-triage` — um mantenedor precisa avaliar
- `needs-info` — aguardando informações do reportante
- `ready-for-agent` — totalmente especificado, pronto para um agente AFK (um agente pode pegar sem contexto humano)
- `ready-for-human` — exige intervenção humana
- `wontfix` — não será corrigido

Padrão: o nome de cada papel é igual à sua string. Pergunte ao usuário se deseja sobrescrever algum. Se o rastreador não tiver rótulos existentes, os padrões são adequados.

**Section C — Domain docs.**

> Explicação: Algumas skills (`improve-codebase-architecture`, `diagnose`, `tdd`) leem um arquivo `CONTEXT.md` para aprender a linguagem de domínio do projeto, e `docs/adr/` para decisões arquiteturais anteriores. Elas precisam saber se o repositório tem um contexto global ou múltiplos (por exemplo, um monorepo com contextos separados frontend/backend) para procurar no lugar certo.

Confirme o layout:

- **Single-context** — um `CONTEXT.md` + `docs/adr/` na raiz do repositório. A maioria dos repositórios é assim.
- **Multi-context** — `CONTEXT-MAP.md` na raiz apontando para `CONTEXT.md` por contexto (tipicamente em monorepos).

### 3. Confirmar e editar

Mostre ao usuário um rascunho de:

- o bloco `## Agent Skills` a ser adicionado em `AGENTS.md` (veja as regras de seleção no passo 4)
- o conteúdo de `docs/agents/issue-tracker.md`, `docs/agents/triage-labels.md`, `docs/agents/domain.md`, `docs/agents/adr-template.md` e `docs/agents/adrs-labels.md`

Permita que o usuário edite antes de gravar.

### 4. Escrever

**Escolha o arquivo para editar:**

- Edite `AGENTS.md`.
- Se ele não existir, chame `/setup-agent-instructions` para criá-lo e então edite esse arquivo.

Se um bloco `## Agent Skills` já existir no arquivo escolhido, atualize seu conteúdo in-place em vez de adicionar um duplicado. Não sobrescreva edições do usuário nas seções ao redor.

Se o repositório já usar um estilo local para `## Agent Skills`, preserve esse formato. Neste repositório, prefira bullets curtos e grepáveis.

O bloco:

```markdown
## Agent Skills

- Issue tracker: [resumo em uma linha de onde os issues são rastreados]. Veja `docs/agents/issue-tracker.md`.
- Triage labels: [resumo em uma linha do vocabulário de rótulos]. Veja `docs/agents/triage-labels.md`.
- Domain docs: [resumo em uma linha do layout — "single-context" ou "multi-context"]. Veja `docs/agents/domain.md`.
- ADR template: use `docs/agents/adr-template.md` como base para novas ADRs.
- ADR status labels: use `docs/agents/adrs-labels.md` para mapear os status canônicos de ADR para as strings reais do repositório.
```

Em seguida, escreva os arquivos de documentação abaixo em `docs/agents/`, usando os arquivos-base em `./references/` como ponto de partida:

- [issue-tracker-github.md](./references/issue-tracker-github.md) — rastreador de issues GitHub
- [issue-tracker-local.md](./references/issue-tracker-local.md) — rastreador local em Markdown
- [triage-labels.md](./references/triage-labels.md) — mapeamento de rótulos
- [domain.md](./references/domain.md) — regras de consumo e layout dos docs de domínio
- [adr-template.md](./references/adr-template.md) — template de ADR
- [adrs-labels.md](./references/adrs-labels.md) — mapeamento de status de ADR

Para rastreadores "outros" (por exemplo, Jira, Linear), escreva `docs/agents/issue-tracker.md` do zero usando a descrição do usuário.

### 5. Concluído

Informe ao usuário que a configuração foi concluída e quais skills de engenharia agora lerão esses arquivos, incluindo `to-issues`, `to-prd`, `triage`, `grill-adr`, `diagnose`, `tdd`, `improve-codebase-architecture` e `zoom-out`. Mencione que eles podem editar `docs/agents/*.md` diretamente mais tarde — reexecutar esta skill só é necessário se quiserem mudar de issue tracker, reiniciar o contrato do repositório ou redefinir as convenções de ADR.
