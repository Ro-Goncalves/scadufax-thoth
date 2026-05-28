# Como esta skill funciona

Esta skill existe para transformar um plano, uma especificação ou um PRD em issues pequenas, independentes e executáveis. Ela não é a etapa de descoberta do produto e não substitui a escrita do PRD. O papel dela é pegar algo já delineado e quebrar isso em fatias verticais que possam ser implementadas, validadas e publicadas no rastreador do projeto.

Na prática, ela serve para a passagem entre entendimento do trabalho e execução do trabalho.

## Qual problema ela resolve

Mesmo quando uma funcionalidade já tem contexto ou até um PRD completo, ainda costuma faltar uma decomposição de implementação boa o bastante para orientar execução. Sem isso, o trabalho tende a virar tickets genéricos demais, pacotes grandes demais ou tarefas separadas por camada técnica.

Esta skill resolve esse problema ao decompor o material de origem em issues do tipo tracer bullet, isto é, fatias verticais pequenas e completas.

Isso ajuda a evitar problemas comuns como:

- tickets grandes demais para serem concluídos com segurança
- divisão horizontal por backend, frontend, banco ou testes
- dependências confusas entre tarefas
- falta de critérios claros para dizer quando uma entrega está pronta

Em vez de transformar o plano em uma lista difusa de tarefas, ela organiza o trabalho em unidades rastreáveis e verificáveis.

## Quando usar

Use esta skill quando você já tiver um plano, uma spec ou um PRD e quiser quebrar isso em issues de implementação.

Ela funciona melhor quando:

- a funcionalidade já foi explorada o suficiente para não estar mais vaga
- já existe um PRD ou algum material equivalente de referência
- você quer publicar tickets pequenos e independentes no rastreador
- o repositório já tem, ou já deveria ter, seu rastreador configurado por `setup-agentic-repo`

Se a ideia ainda estiver aberta demais, a etapa anterior costuma ser uma skill de descoberta, como `grill-with-docs`. Se a funcionalidade ainda não tem uma especificação consolidada, a etapa anterior costuma ser `to-prd`.

## Como utilizá-la

O uso esperado é este:

1. tenha em mãos um plano, uma spec, um PRD ou uma issue pai com contexto suficiente
2. garanta que o contrato do rastreador do projeto já esteja configurado
3. invoque a skill `to-issues`
4. revise com a skill a decomposição proposta
5. aprove a granularidade e as dependências
6. deixe a skill publicar as issues no rastreador

O fluxo interno da skill é este:

1. ela parte do contexto já existente na conversa ou busca um ticket de referência no rastreador
2. opcionalmente explora o código para usar o vocabulário correto do domínio e respeitar ADRs relevantes
3. propõe fatias verticais do tipo tracer bullet
4. classifica essas fatias como `HITL` ou `AFK`
5. valida com o usuário se a granularidade e as dependências fazem sentido
6. publica as issues aprovadas em ordem de dependência

A skill não deve fechar nem modificar a issue pai de origem.

## O que é uma fatia vertical aqui

Nesta skill, uma boa fatia vertical é uma issue pequena, mas completa. Ela precisa atravessar o fluxo necessário de ponta a ponta, em vez de isolar apenas uma camada técnica.

Uma fatia boa tende a ter estas propriedades:

- entrega um comportamento observável
- pode ser demonstrada ou verificada sozinha
- não depende de um pacote grande de outras tasks para fazer sentido
- descreve resultado, não apenas implementação interna

A skill usa dois tipos de fatia:

- `AFK` — pode ser implementada e integrada sem interação humana adicional
- `HITL` — depende de intervenção humana, como decisão arquitetural ou revisão de design

## Quais artefatos ela gera

O principal artefato gerado por esta skill é um conjunto de issues de implementação.

Cada issue costuma incluir:

- referência à issue pai, quando houver
- descrição do que construir
- critérios de aceite
- bloqueadores
- classificação implícita para execução no fluxo do projeto

Além disso, as issues são publicadas em ordem de dependência, para que a referência entre bloqueadores aponte para tickets reais já existentes.

## Onde ela gera os artefatos

Esta skill publica o resultado no rastreador de issues definido para o repositório. Há dois modos possíveis.

### Markdown local

Quando o projeto usa rastreamento local em markdown, cada issue de implementação é salva em:

- `.scratch/<feature-slug>/issues/<NN>-<slug>.md`

O PRD da mesma feature, quando existir, tende a ficar em:

- `.scratch/<feature-slug>/PRD.md`

Ou seja: a `to-issues` normalmente entra depois do PRD e passa a preencher a pasta `issues/` da feature.

### GitHub

Quando o projeto usa GitHub como rastreador, cada fatia aprovada é publicada como um issue do GitHub no repositório atual.

Nesse modo, o artefato final não aparece como arquivo dentro da árvore do projeto. Ele passa a existir como issue remoto, com corpo estruturado e rótulo de triagem aplicado conforme o contrato do repositório.

## O que esta skill lê antes de escrever

Para decompor corretamente o trabalho, esta skill normalmente consulta:

- a conversa atual
- um plano, spec, PRD ou issue de referência
- o estado atual do código, quando isso for necessário para calibrar a decomposição
- o glossário do domínio em `CONTEXT.md` ou `CONTEXT-MAP.md`
- ADRs já registradas em `docs/adr/`
- a configuração de rastreador preparada por `setup-agentic-repo`

Por isso, ela funciona melhor como skill de decomposição posterior, não como ponto de partida para explorar um produto novo.

## Em uma frase

Esta skill existe para transformar um plano já entendido em issues pequenas, verticais e rastreáveis, prontas para orientar execução real.

## Origem e autor

Esta skill foi adaptada a partir de: https://github.com/mattpocock/skills/tree/main/skills/engineering/to-issues

Para mais informações sobre o autor original, acesse: https://www.aihero.dev/
