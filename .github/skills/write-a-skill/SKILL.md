---
name: write-a-skill
description: Cria skills de agente com estrutura adequada, divulgação progressiva e recursos agrupados. Use quando o usuário quiser criar, escrever ou construir uma nova skill.
---

# Escrever Skills

## Processo

1. **Coletar requisitos** - pergunte ao usuário sobre:
   - Qual tarefa/domínio a skill cobre?
   - Quais casos de uso específicos ela deve atender?
   - Ela precisa de scripts executáveis ou apenas instruções?
   - Quais materiais de referência incluir?

2. **Redigir a skill** - crie:
   - SKILL.md com instruções concisas
   - Arquivos de referência adicionais se o conteúdo exceder 500 linhas
   - Scripts utilitários se operações determinísticas forem necessárias

3. **Revisar com o usuário** - apresente o rascunho e pergunte:
   - Isso cobre seus casos de uso?
   - Falta algo ou algo ficou pouco claro?
   - Alguma seção deve ser mais ou menos detalhada?

## Estrutura da Skill

```
skill-name/
├── SKILL.md           # Instruções principais (obrigatório)
├── REFERENCE.md       # Documentação detalhada (se necessário)
├── EXAMPLES.md        # Exemplos de uso (se necessário)
└── scripts/           # Scripts utilitários (se necessário)
    └── helper.js
```

## Modelo de SKILL.md

```md
---
name: skill-name
description: Breve descrição da capacidade. Use when [gatilhos específicos].
---

# Nome da Skill

## Início rápido

[Exemplo mínimo funcional]

## Fluxos de trabalho

[Processos passo a passo com checklists para tarefas complexas]

## Recursos avançados

[Link para arquivos separados: veja [REFERENCE.md](REFERENCE.md)]
```

## Requisitos da descrição

A descrição é **a única coisa que seu agente vê** ao decidir qual skill carregar. Ela aparece no prompt do sistema junto com todas as outras skills instaladas. Seu agente lê essas descrições e escolhe a skill relevante com base na solicitação do usuário.

**Objetivo**: dar ao seu agente apenas o suficiente para saber:

1. Que capacidade esta skill fornece
2. Quando/por que acioná-la (palavras-chave, contextos, tipos de arquivo específicos)

**Formato**:

- Máximo de 1024 caracteres
- Escreva na terceira pessoa
- Primeira frase: o que ela faz
- Segunda frase: "Use quando [gatilhos específicos]."

**Bom exemplo**:

```
Extract text and tables from PDF files, fill forms, merge documents. Use when working with PDF files or when user mentions PDFs, forms, or document extraction.
```

**Mau exemplo**:

```
Ajuda com documentos.
```

O mau exemplo não dá ao seu agente meios de distinguir isso de outras skills de documentos.

## Quando adicionar scripts

Adicione scripts utilitários quando:

- A operação for determinística (validação, formatação)
- O mesmo código seria gerado repetidamente
- Erros precisarem de tratamento explícito

Scripts economizam tokens e melhoram a confiabilidade em relação ao código gerado.

## Quando dividir arquivos

Divida em arquivos separados quando:

- SKILL.md exceder 100 linhas
- O conteúdo tiver domínios distintos (financeiro vs. esquemas de vendas)
- Recursos avançados forem raramente necessários

## Checklist de revisão

Depois de redigir, verifique:

- [ ] A descrição inclui gatilhos ("Use quando...")
- [ ] SKILL.md tem menos de 100 linhas
- [ ] Não há informações sensíveis ao tempo
- [ ] Terminologia consistente
- [ ] Exemplos concretos incluídos
- [ ] Referências com um nível de profundidade apenas
