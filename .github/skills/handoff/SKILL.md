---
name: handoff
description: Compacta a conversa atual em um documento de repasse (handoff) para que outro agente possa assumir o contexto.
argument-hint: "Para que a próxima sessão será usada?"
---

Utilize suas ferramentas internas para analisar a sessão de chat atual. Com base nessa análise, escreva um documento de repasse resumindo a conversa para que um novo agente possa continuar o trabalho com o contexto correto.

Salve o resumo no caminho: `.scratch/<YYYY-MM-DD - SECTION_ID>` (certifique-se de substituir `<YYYY-MM-DD>` pela data atual e `<SECTION_ID>` pelo identificador ou nome da sessão).

Inclua uma seção de "habilidades sugeridas" (suggested skills) no documento, indicando as habilidades que o próximo agente deverá invocar.

Não duplique o conteúdo já capturado em outros artefatos (PRDs, planos, ADRs, issues, commits, diffs). Em vez disso, referencie-os através de seu caminho (path) ou URL correspondente.

Oculte e remova qualquer informação sensível do resumo final, como chaves de API, senhas ou informações de identificação pessoal.

Se o usuário passar argumentos, trate-os como uma descrição do foco principal da próxima sessão e adapte o documento de acordo.