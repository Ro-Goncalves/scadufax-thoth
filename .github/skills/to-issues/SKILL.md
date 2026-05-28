---
name: to-issues
description: Quebre um plano, especificação ou PRD em issues independentes no rastreador do projeto usando fatias verticais do tipo tracer bullet. Use quando o usuário quiser converter um plano em issues, criar tickets de implementação ou decompor trabalho em issues.
---

# Para Issues

Quebre um plano em issues independentes usando fatias verticais do tipo tracer bullet.

O rastreador de issues e o vocabulário de labels de triagem devem ter sido fornecidos — execute `/setup-agentic-repo` se não.

## Processo

### 1. Coletar contexto

Trabalhe a partir do que já estiver no contexto da conversa. Se o usuário passar uma referência de issue como argumento, como número, URL ou caminho, busque-a no rastreador e leia o corpo completo e os comentários.

### 2. Explorar o código-base (opcional)

Se você ainda não explorou o código-base, faça isso para entender o estado atual do código. Os títulos e as descrições das issues devem usar o vocabulário do glossário de domínio do projeto e respeitar ADRs na área que você estiver tocando.

### 3. Propor fatias verticais

Quebre o plano em issues do tipo **tracer bullet**. Cada issue deve ser uma fatia vertical fina que atravesse TODAS as camadas de integração de ponta a ponta, e NÃO uma fatia horizontal de uma única camada.

As fatias podem ser `HITL` ou `AFK`. Fatias `HITL` exigem interação humana, como uma decisão arquitetural ou uma revisão de design. Fatias `AFK` podem ser implementadas e integradas sem interação humana. Prefira `AFK` em vez de `HITL` sempre que possível.

<vertical-slice-rules>
- Cada fatia entrega um caminho estreito, mas COMPLETO, por todas as camadas, como schema, API, UI e testes
- Uma fatia concluída pode ser demonstrada ou verificada por conta própria
- Prefira muitas fatias finas em vez de poucas fatias grossas
</vertical-slice-rules>

### 4. Validar com o usuário

Apresente a decomposição proposta como uma lista numerada. Para cada fatia, mostre:

- **Título**: nome curto e descritivo
- **Tipo**: HITL / AFK
- **Bloqueada por**: quais outras fatias, se existirem, precisam ser concluídas antes
- **Histórias de usuário cobertas**: quais histórias esta fatia cobre, se o material de origem as tiver

Pergunte ao usuário:

- O nível de granularidade parece correto, ou está grosso ou fino demais?
- As relações de dependência estão corretas?
- Alguma fatia deveria ser unida ou dividida ainda mais?
- As fatias corretas estão marcadas como HITL e AFK?

Itere até o usuário aprovar a decomposição.

### 5. Publicar as issues no rastreador

Para cada fatia aprovada, publique uma nova issue no rastreador. Use o template de corpo abaixo. Essas issues são consideradas prontas para agentes `AFK`, então publique-as com o rótulo de triagem correto, a menos que haja outra instrução.

Publique as issues em ordem de dependência, com os bloqueadores primeiro, para que você possa referenciar identificadores reais no campo `Blocked by`.

<issue-template>
## Issue pai

Uma referência à issue pai no rastreador, se a origem tiver sido uma issue existente. Caso contrário, omita esta seção.

## O que construir

Uma descrição concisa desta fatia vertical. Descreva o comportamento de ponta a ponta, não a implementação camada por camada.

Evite caminhos de arquivo específicos ou trechos de código, porque eles envelhecem rápido. Exceção: se um protótipo tiver produzido um trecho que represente uma decisão com mais precisão do que a prosa, como máquina de estados, reducer, schema ou shape de tipo, inclua-o aqui e indique brevemente que ele veio de um protótipo. Mantenha apenas as partes ricas em decisão, não um demo funcional.

## Critérios de aceite

- [ ] Critério 1
- [ ] Critério 2
- [ ] Critério 3

## Bloqueada por

- Uma referência ao ticket bloqueador, se houver

Ou `Nenhum - pode começar imediatamente` se não houver bloqueadores.

</issue-template>

NÃO feche nem modifique nenhuma issue pai.
