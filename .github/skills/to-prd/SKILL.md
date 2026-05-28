---
name: to-prd
description: Transforme o contexto da conversa atual em um PRD e publique no rastreador de issues do projeto. Use quando o usuário quiser criar um PRD a partir do contexto atual.
---

Esta skill transforma o contexto da conversa atual e o entendimento do código do repositório em um PRD. NÃO entreviste o usuário — apenas sintetize o que você já sabe.

O rastreador de issues e o vocabulário de labels de triagem devem ter sido fornecidos — execute `/setup-agentic-repo` se não.

## Processo

1. Explore o repositório para entender o estado atual do código, caso ainda não o tenha feito. Use o vocabulário do glossário de domínio do projeto ao longo do PRD e respeite quaisquer ADRs na área que estiver tocando.

2. Esboce os módulos principais que serão necessários para construir ou modificar para completar a implementação. Procure ativamente oportunidades de extrair módulos "profundos" que possam ser testados isoladamente.

Um módulo profundo (em oposição a um módulo raso) é aquele que encapsula muita funcionalidade em uma interface simples e testável que raramente muda.

Confirme com o usuário se esses módulos correspondem às expectativas dele. Verifique com o usuário em quais módulos ele quer que sejam escritos testes.

3. Escreva o PRD usando o template abaixo e então publique-o no rastreador de issues do projeto. Aplique o rótulo de triagem `ready-for-agent` — não é necessária triagem adicional.

<prd-template>

## Declaração do problema

O problema que o usuário está enfrentando, do ponto de vista do usuário.

## Solução

A solução para o problema, do ponto de vista do usuário.

## Histórias de usuário

Uma lista LONGA, numerada, de histórias de usuário. Cada história deve estar no formato:

1. Como um <ator>, eu quero um <recurso>, para que <benefício>

<user-story-example>
1. Como cliente de banco móvel, eu quero ver o saldo das minhas contas, para que eu possa tomar decisões mais informadas sobre meus gastos
</user-story-example>

Esta lista de histórias deve ser extremamente extensa e cobrir todos os aspectos da funcionalidade.

## Decisões de implementação

Uma lista das decisões de implementação que foram tomadas. Isso pode incluir:

- Os módulos que serão construídos/modificados
- As interfaces desses módulos que serão modificadas
- Esclarecimentos técnicos do desenvolvedor
- Decisões arquiteturais
- Mudanças de esquema
- Contratos de API
- Interações específicas

NÃO inclua caminhos de arquivo ou trechos de código específicos. Eles podem ficar desatualizados muito rapidamente.

Exceção: se um protótipo produziu um trecho que codifica uma decisão mais precisamente do que a prosa (máquina de estados, reducer, formato de schema, shape de tipo), inclua-o dentro da decisão relevante e indique brevemente que ele veio de um protótipo. Corte para as partes ricas em decisão — não um demo funcional, apenas os trechos que importam.

## Decisões de teste

Uma lista das decisões de teste que foram tomadas. Inclua:

- Uma descrição do que faz um bom teste (testar apenas comportamento externo, não detalhes de implementação)
- Quais módulos serão testados
- Referências anteriores para os testes (por exemplo, tipos de testes similares no código-base)

## Fora do escopo

Descrição das coisas que estão fora do escopo deste PRD.

## Notas adicionais

Quaisquer notas adicionais sobre a funcionalidade.

</prd-template>
