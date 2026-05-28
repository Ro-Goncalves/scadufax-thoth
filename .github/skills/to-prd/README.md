# Como esta skill funciona

Esta skill existe para transformar um contexto já discutido em um PRD utilizável. Ela não é a etapa principal de descoberta do produto e não substitui uma conversa exploratória longa. O papel dela é pegar o que já foi entendido sobre uma feature, cruzar isso com o estado atual do repositório e publicar uma especificação que possa orientar implementação.

Na prática, ela serve para a fase em que a ideia já deixou de ser vaga o bastante para virar um documento de produto mais formal.

## Qual problema ela resolve

Depois de uma conversa de descoberta, é comum que o entendimento da feature fique espalhado entre mensagens, decisões soltas, termos de domínio e observações sobre o código atual. Isso dificulta transformar a conversa em trabalho executável.

Esta skill resolve esse problema ao sintetizar o contexto em um PRD com:

- problema e solução do ponto de vista do usuário
- histórias de usuário extensas
- decisões de implementação já assumidas
- decisões de teste
- limites de escopo e notas complementares

Em vez de depender da memória da conversa, você passa a ter um artefato claro, rastreável e pronto para virar planejamento ou execução.

## Quando usar

Use esta skill quando você já tiver contexto suficiente sobre uma funcionalidade e quiser consolidar isso em um PRD.

Ela funciona melhor quando:

- a funcionalidade já foi discutida antes
- o problema do usuário já está razoavelmente delimitado
- o fluxo principal e os casos importantes já foram explorados
- o repositório já tem, ou já deveria ter, seu rastreador configurado por `setup-agentic-repo`

Se a ideia ainda estiver muito aberta, a etapa certa costuma ser uma skill de descoberta, como a `grill-with-docs`, antes de chamar a `to-prd`.

## Como utilizá-la

O uso esperado é simples:

1. discuta o produto ou a funcionalidade até chegar a um contexto sólido
2. garanta que o repositório já saiba onde ficam issues e PRDs
3. invoque a skill `to-prd`
4. deixe a skill sintetizar o contexto atual, ler o glossário e as ADRs relevantes e gerar o PRD

O fluxo interno da skill é este:

1. ela explora o repositório para entender o estado atual do código
2. usa o vocabulário de domínio do projeto para manter o texto consistente
3. respeita ADRs já existentes na área tocada
4. esboça os módulos principais e identifica oportunidades de módulos profundos e testáveis
5. valida com o usuário se esses módulos e prioridades de teste fazem sentido
6. escreve o PRD e publica no rastreador configurado
7. aplica o rótulo canônico `ready-for-agent`

Se o rastreador ainda não estiver configurado, a skill depende de `setup-agentic-repo` para descobrir o contrato de publicação.

## Quais artefatos ela gera

O principal artefato gerado por esta skill é um PRD da funcionalidade discutida.

Esse PRD inclui:

- declaração do problema
- solução
- histórias de usuário
- decisões de implementação
- decisões de teste
- fora do escopo
- notas adicionais

Além do PRD em si, a skill também publica esse conteúdo no rastreador do projeto e marca o item com o estado de triagem equivalente a `ready-for-agent`.

## Onde ela gera os artefatos

Esta skill publica o resultado no rastreador de issues definido para o repositório. Há dois modos possíveis.

### Markdown local

Quando o projeto usa rastreamento local em markdown, o PRD é salvo em:

- `.scratch/<feature-slug>/PRD.md`

Se o diretório da feature ainda não existir, ele é criado. Nesse mesmo espaço também podem existir issues de implementação relacionados à mesma feature.

### GitHub

Quando o projeto usa GitHub como rastreador, o PRD é publicado como um issue do GitHub no repositório atual.

Nesse modo, o artefato final não aparece como arquivo dentro da árvore do projeto. Ele passa a existir como issue remoto, com corpo estruturado e rótulo de triagem aplicado.

## O que esta skill lê antes de escrever

Para montar um PRD coerente, esta skill depende do contexto que já existe. Em geral, ela consulta:

- a conversa atual
- o estado atual do código
- o glossário do domínio em `CONTEXT.md` ou `CONTEXT-MAP.md`
- ADRs já registradas em `docs/adr/`
- a configuração de rastreador preparada por `setup-agentic-repo`

Por isso, ela funciona melhor como skill de síntese, não como primeira conversa sobre um produto novo.

## Em uma frase

Esta skill existe para transformar contexto já descoberto em um PRD rastreável, consistente com o domínio do projeto e pronto para orientar implementação.

## Origem e autor

Esta skill foi adaptada a partir de: https://github.com/mattpocock/skills/tree/main/skills/engineering/to-prd

Para mais informações sobre o autor original, acesse: https://www.aihero.dev/
