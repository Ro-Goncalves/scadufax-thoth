---
name: setup-agent-instructions
description: Inicializa e mantém o `AGENTS.md` raiz do repositório com instruções curtas, grepáveis e específicas do projeto. Use quando o repositório precisar criar, restaurar ou atualizar o `AGENTS.md`, especialmente se o arquivo não existir ou estiver vazio.
---

# Inicializar ou melhorar o AGENTS.md

Esta skill cuida só do arquivo de instruções sempre ativas do repositório. Ela não escreve o contrato de `docs/agents/`; essa parte continua com `setup-agentic-repo`.

Use esta skill para produzir um `AGENTS.md` realmente útil para agentes: curto, direto, grepável e ancorado no repositório real. Não escreva um manifesto genérico.

## Princípios

- Escreva para agentes, não para humanos.
- Mantenha alta densidade de sinal: bullets curtos, verbos no imperativo e headings grepáveis.
- Não invente comandos, caminhos ou convenções. Extraia tudo do repositório antes de escrever.
- Preserve conteúdo do usuário e atualize in-place sempre que possível.
- Prefira defaults operacionais, invariantes e sharp edges; não filosofia ampla.
- Mantenha o arquivo curto. Mire 80 a 200 linhas; evite passar de 300.

## Processo

1. Explore o repositório antes de escrever:
	- Leia `AGENTS.md` se existir, além de `README.md`, docs de setup e onboarding, manifests e arquivos de automação como `package.json`, `pyproject.toml`, `requirements.txt`, `Makefile`, `justfile`, CI, lint e format.
	- Descubra comandos reais de setup, execução, teste, lint, format e typecheck.
	- Se houver `docs/tests/` ou diretório equivalente de apoio à execução de testes, leia os arquivos de descobertas persistentes antes de escrever o `AGENTS.md`.
	- Descubra a estrutura do projeto e os diretórios mais importantes.
	- Descubra invariantes, dependências externas, restrições operacionais e partes frágeis já documentadas.
2. Decida a estratégia:
	- Se `AGENTS.md` não existir, crie-o a partir de [AGENTS-FORMAT.md](./AGENTS-FORMAT.md).
	- Se existir mas estiver vazio, escreva uma primeira versão completa usando [AGENTS-FORMAT.md](./AGENTS-FORMAT.md).
	- Se já tiver conteúdo útil, preserve o que estiver correto e preencha apenas lacunas.
3. Escreva o arquivo:
	- Use apenas seções com instruções acionáveis.
	- Cite comandos exatos do projeto.
	- Explicite como validar mudanças localmente.
	- Quando o repositório depender de testes manuais, browser automation ou fluxos frágeis, inclua uma seção `## Tests` apontando para um arquivo persistente de descobertas de teste e instruindo agentes a lê-lo e atualizá-lo.
	- Inclua regras curtas de tomada de decisão quando elas reduzirem ambiguidade e mudanças excessivas.
	- Registre convenções de código que ajudem agentes a editar com segurança.
	- Mantenha uma seção `## Agent skills` para o contrato mantido por `setup-agentic-repo`.
4. Revise antes de salvar:
	- Remova conselhos genéricos que não apontem para este repositório.
	- Corte texto redundante e parágrafos longos.
	- Verifique se cada bullet muda o comportamento do agente.
	- Se algum comando não puder ser confirmado, omita em vez de adivinhar.

## O que um bom AGENTS.md precisa cobrir

- Objetivo curto do repositório, se isso ajudar a orientar decisões.
- Fluxo de trabalho local: setup, execução, teste, lint, format e typecheck.
- Modo de decisão e edição: explicitar suposições, preferir a solução mais simples, fazer mudanças cirúrgicas e validar por objetivo.
- Testes: onde ficam cenários e descobertas persistentes de execução, quando existirem.
- Estrutura previsível: onde ficam código, testes, docs e pontos de entrada.
- Convenções de edição: funções e módulos pequenos, nomes grepáveis, tipos explícitos quando houver suporte, pouco aninhamento e DI quando fizer sentido.
- Comentários e docstrings: preservar intenção e proveniência; escrever o porquê, não o óbvio.
- Validação: qual comando rodar depois de editar e como fazer checks focados.
- Restrições reais: segredos, serviços externos, partes frágeis e limitações de ambiente.
- Seção `## Agent skills` como ponto de integração com `setup-agentic-repo`.

## O que evitar

- Manifestos longos.
- Repetir o `README.md` inteiro.
- Regras que o repositório não consegue cumprir.
- Comandos inventados ou desatualizados.
- Explicações abstratas sem consequência operacional.

## Template

Use [AGENTS-FORMAT.md](./AGENTS-FORMAT.md) como ponto de partida. Adapte o template à linguagem, às ferramentas e aos comandos reais do repositório.

## Resultado esperado

- `AGENTS.md` existe na raiz.
- O arquivo contém instruções específicas o suficiente para orientar um agente sem sobrecarregar contexto.
- Quando houver testes manuais ou automação sensível a ambiente, o arquivo orienta o agente a usar e manter um registro persistente de descobertas de teste.
- `setup-agentic-repo` consegue anexar o bloco `## Agent skills` sem disputar ownership do restante do arquivo.