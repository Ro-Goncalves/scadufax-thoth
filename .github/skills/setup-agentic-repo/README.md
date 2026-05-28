# Como esta skill funciona

Esta skill existe para preparar um repositório para as demais skills de engenharia. Ela não cria produto, não implementa funcionalidade e não resolve bugs diretamente. O papel dela é definir a organização mínima para que o restante do fluxo entenda onde ficam os issues, como a triagem funciona, onde mora a documentação de domínio e quais convenções devem ser usadas para ADRs.

Se você costuma trabalhar com arquivos `.md`, a ideia aqui é muito próxima disso: a skill transforma o repositório em um conjunto de arquivos bem definidos, com convenções estáveis, para que uma automação consiga ler e escrever sem adivinhar nada.

## O que é um issue

Um issue é uma unidade rastreável de trabalho. Ele pode representar um bug, uma melhoria, uma dúvida, uma refatoração, uma tarefa de documentação ou uma descoberta técnica.

Na prática, um issue serve para:

- dar nome ao trabalho
- registrar contexto e decisões
- acompanhar conversa e histórico
- marcar o estado atual da tarefa
- permitir que outras pessoas retomem o assunto depois

Se você já usa notas em markdown, pense em um issue como uma nota estruturada, mas com um ciclo de vida mais claro. Em vez de ficar solta em um arquivo qualquer, ela ganha regras de criação, leitura, triagem e encerramento.

## GitHub e markdown local

Esta skill aceita dois modos principais de organização.

### GitHub

No GitHub, o issue vive dentro do próprio repositório, como uma página nativa da plataforma. Isso é útil quando você quer colaboração centralizada, comentários encadeados, labels, busca e integração com a interface do GitHub.

O fluxo costuma ser:

1. alguém identifica um trabalho
2. isso vira um issue no GitHub
3. a equipe comenta e adiciona labels
4. o trabalho é feito
5. a issue é fechada

A skill usa esse modelo quando o repositório está configurado para GitHub. O arquivo [references/issue-tracker-github.md](references/issue-tracker-github.md) documenta esse comportamento.

### Markdown local

Se você prefere trabalhar de forma textual, o fluxo local é mais natural. Nesse modo, issues e PRDs deixam de ser páginas da plataforma e passam a ser arquivos markdown versionados no próprio repositório.

Esse formato combina bem com quem gosta de manter tudo próximo do código. Em vez de depender da interface do GitHub, você usa a árvore de arquivos como sistema de rastreamento.

O arquivo [references/issue-tracker-local.md](references/issue-tracker-local.md) descreve essa convenção.

## Como a triagem funciona

Depois que um issue existe, ele normalmente passa por estados de triagem. Esta skill reconhece cinco papéis canônicos:

- `needs-triage` — precisa ser avaliado
- `needs-info` — aguarda informações
- `ready-for-agent` — está pronto para uma execução automática ou assíncrona
- `ready-for-human` — precisa de implementação humana
- `wontfix` — não será trabalhado

Esses nomes podem ser mapeados para os rótulos reais do seu repositório. O arquivo [references/triage-labels.md](references/triage-labels.md) registra essa correspondência.

Na prática, a triagem é a parte que diz: o que fazer com este item agora? Ele está claro o suficiente? Falta contexto? Já pode ser executado? Não vale a pena seguir?

## Onde ficam os documentos de domínio

Além dos issues, a skill também prepara a documentação de contexto do projeto.

Os arquivos principais são:

- CONTEXT.md, que define o vocabulário do projeto
- CONTEXT-MAP.md, quando o repositório tem mais de um contexto
- docs/adr/, onde ficam as decisões arquiteturais registradas

Isso importa porque outras skills precisam usar o nome certo para os conceitos do domínio. Se o projeto chama algo de um jeito específico, a automação precisa respeitar esse vocabulário.

O arquivo [references/domain.md](references/domain.md) explica como esses documentos devem ser lidos.

## Como ela prepara ADRs

Além do contrato de issue tracker e domínio, esta skill também publica dois artefatos em `docs/agents/` para suportar fluxos de ADR:

- `adr-template.md`, que define a estrutura base para novas ADRs
- `adrs-labels.md`, que define o vocabulário e o mapeamento de status usados pelos ADRs do repositório

Esses dois arquivos não dependem, em geral, de uma nova rodada de perguntas. A skill os deriva a partir dos arquivos-base em `references/`, salvo quando o repositório já tiver uma convenção explícita diferente.

## O que esta skill faz, passo a passo

O fluxo da skill é simples, mas importante:

1. garante, via `setup-agent-instructions`, que `AGENTS.md` exista e tenha uma base estável; depois inspeciona o repositório atual
2. descobre onde os issues vivem
3. identifica quais rótulos representam cada estado de triagem
4. verifica se a documentação de domínio é de contexto único ou múltiplo
5. publica `docs/agents/adr-template.md` e `docs/agents/adrs-labels.md` a partir dos arquivos-base em `references/`
6. escreve ou atualiza o contrato em `AGENTS.md` e os arquivos de apoio em `docs/agents/`

## Bootstrap do AGENTS.md

A criação inicial de `AGENTS.md` foi separada em `setup-agent-instructions`. Essa skill só garante a existência e a base estável do arquivo raiz; depois, `setup-agentic-repo` continua com o contrato de issue tracker, triagem e docs de domínio.

Em outras palavras, ela cria o contrato que as outras skills vão seguir.

## Quais arquivos ela mantém

Esta pasta funciona como um pequeno pacote de configuração para o repositório. Os arquivos-base desta skill ficam em `references/`:

- [SKILL.md](SKILL.md)
- [README.md](README.md)
- [references/issue-tracker-github.md](references/issue-tracker-github.md)
- [references/issue-tracker-local.md](references/issue-tracker-local.md)
- [references/triage-labels.md](references/triage-labels.md)
- [references/domain.md](references/domain.md)
- [references/adr-template.md](references/adr-template.md)
- [references/adrs-labels.md](references/adrs-labels.md)

O `SKILL.md` descreve o processo inteiro. Os arquivos em `references/` são a base que a skill materializa em `docs/agents/`.

## Em uma frase

Esta skill existe para transformar um repositório em um ambiente legível por outras skills, usando uma estrutura simples, explícita e compatível com o jeito como você já gosta de trabalhar em markdown.

## Origem e autor

Esta skill foi adaptada a partir de: https://github.com/mattpocock/skills/tree/main/skills/engineering/setup-matt-pocock-skills

Para mais informações sobre o autor original, acesse: https://www.aihero.dev/