# Formato do AGENTS.md

Use este arquivo como guia ao criar ou revisar `AGENTS.md`.

## Objetivo

O `AGENTS.md` deve ajudar um agente a trabalhar com segurança e velocidade no repositório real. O arquivo precisa ser curto, específico, grepável e operacional.

## Regras de escrita

- Escreva em bullets curtos e acionáveis.
- Prefira headings estáveis e fáceis de localizar com grep.
- Use comandos reais do projeto.
- Se um detalhe não puder ser confirmado, omita.
- Preserve instruções já existentes que continuem corretas.
- Remova placeholders antes de salvar.

## Conteúdo recomendado

Inclua apenas o que mudar o comportamento do agente:

- Como instalar ou preparar o ambiente, se isso já estiver documentado no repositório.
- Como executar a aplicação ou o fluxo principal.
- Como tomar decisões de edição: declarar suposições, preferir a menor solução útil e validar por objetivo.
- Como validar mudanças com testes, lint, format e typecheck.
- Onde ficam cenários de teste e descobertas persistentes de execução, quando existirem.
- Onde ficam código, testes, scripts e docs.
- Convenções de código que reduzam erro de edição automática.
- Invariantes, dependências externas, segredos e limites operacionais.
- O bloco `## Agent skills`, mantido por `setup-agentic-repo`.

## Template base

```md
# Agent Instructions

Estas instruções orientam agentes que trabalham neste repositório. Mantenha mudanças pequenas, verificáveis e alinhadas aos comandos e convenções reais do projeto.

## Workflow

- Leia primeiro: `<arquivos ou diretórios que dão contexto rápido>`.
- Setup: `<comando real, se existir>`.
- Execução local: `<comando real, se existir>`.
- Antes de editar, localize a área dona da mudança e prefira validação focada.

## Decision Making

- Declare suposições quando houver ambiguidade relevante.
- Se existirem múltiplas interpretações plausíveis, exponha-as em vez de escolher em silêncio.
- Prefira a menor mudança que resolva o pedido.
- Evite refatorações adjacentes não exigidas pela tarefa.
- Defina um check pequeno e falsificável antes de ampliar o escopo.

## Validation

- Teste principal: `<comando real>`.
- Lint: `<comando real, se existir>`.
- Format: `<comando real, se existir>`.
- Typecheck: `<comando real, se existir>`.
- Para correções locais, rode primeiro o menor check que possa falsificar a mudança.

## Code Style

- Prefira funções e módulos pequenos, com responsabilidade única.
- Use nomes específicos e grepáveis; evite nomes vagos e genéricos.
- Preserve o estilo existente do arquivo e faça mudanças cirúrgicas.
- Quando a linguagem suportar, prefira tipos explícitos.
- Prefira retornos antecipados a aninhamento profundo.
- Use o formatador já adotado pelo repositório.

## Comments And Docs

- Preserve comentários existentes que carreguem intenção, contexto ou proveniência.
- Ao adicionar comentário, escreva o porquê; não descreva o óbvio.
- Em APIs públicas, prefira docstrings curtas com intenção e exemplo quando isso já for padrão no projeto.

## Tests

- Reutilize cenários ou checklists existentes em `<paths reais>`.
- Leia descobertas persistentes de execução em `<path real do arquivo cumulativo de aprendizados de teste>`, quando existir, antes de testar.
- Se a execução confirmar um detalhe novo de ambiente, timing, fixtures, navegação ou evidência, atualize esse arquivo.
- Toda mudança de comportamento deve vir com validação correspondente.
- Correção de bug pede teste de regressão, quando a base permitir.
- Faça mock de I/O externo com fakes ou camadas de abstração já usadas no projeto.

## Repository Shape

- Código principal: `<paths reais>`.
- Testes: `<paths reais>`.
- Scripts e automações: `<paths reais>`.
- Documentação de apoio: `<paths reais>`.
- Descobertas persistentes de teste: `<path real, se existir>`.

## Sharp Edges

- `<restrição operacional real>`.
- `<dependência externa ou segredo que o agente não deve presumir>`.
- `<invariante importante do domínio ou do fluxo>`.

## Agent skills

Este bloco é mantido por `setup-agentic-repo`.
```

## Checklist final

- O arquivo cabe em uma ou poucas leituras.
- Cada seção contém informação verificável no repositório.
- Os comandos são reais e específicos.
- O texto evita filosofia ampla e redundância.
- O bloco `## Agent skills` está preservado.