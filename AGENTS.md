# Agent Instructions

Estas instruções orientam agentes que trabalham neste repositório. Mantenha mudanças pequenas, verificáveis e alinhadas aos comandos e convenções reais do projeto.

## Workflow

- Leia primeiro: README.md, docs/knowledge/scadufax-thoth.md e docs/rinha/.
- Ao tocar a stack HTTP ou o bootstrap da aplicação, leia também docs/javalin/README.md e src/main/java/br/com/rgbrainlabs/scadufaxthoth/.
- Build rápido: mvn -q compile.
- Antes de editar, localize a área dona da mudança e prefira validação focada.

## Decision Making

- Declare suposições quando houver ambiguidade relevante.
- Prefira a menor mudança útil que resolva o pedido.
- Evite refatorações adjacentes não exigidas pela tarefa.
- Preserve o baseline funcional antes de otimizações maiores.
- Se a mudança tocar detecção, vetorização ou score, compare a proposta com as restrições já registradas em docs/knowledge/scadufax-thoth.md.

## Validation

- Check principal: mvn -q compile.
- Smoke HTTP: docker compose -f test/docker-compose.yml --profile smoke up --abort-on-container-exit --exit-code-from k6-smoke.
- Carga local: docker compose -f test/docker-compose.yml --profile test up --abort-on-container-exit --exit-code-from k6.
- Os scripts k6 esperam o backend disponível em http://localhost:9999.
- Para correções locais, rode primeiro o menor check que possa falsificar a mudança.

## Code Style

- Preserve o estilo existente do arquivo e faça mudanças cirúrgicas.
- Mantenha classes e métodos pequenos, com nomes específicos e grepáveis.
- Preserve os pacotes Java sob br.com.rgbrainlabs.scadufaxthoth.
- Em hot paths de dados e busca vetorial, evite abstrações orientadas a objetos desnecessárias.
- Prefira retornos antecipados a aninhamento profundo.

## Comments And Docs

- Preserve comentários que carreguem intenção, contexto ou proveniência.
- Ao adicionar comentário, explique o porquê e não o óbvio.
- Centralize decisões arquiteturais e trade-offs em docs/knowledge/scadufax-thoth.md.
- Curadoria de documentação externa deve ficar em docs/javalin/ e docs/jackson/.
- Em texto corrido em português, use acentuação correta sem alterar identificadores ou literais técnicos.

## Tests

- Reutilize test/smoke.js, test/test.js e test/test-data.json.
- O smoke profile valida POST /fraud-score com poucas iterações; use-o após mudanças no contrato HTTP ou bootstrap.
- O profile test gera test/results.json com o resumo de scoring; use-o para validar comportamento sob carga local.
- Toda mudança de comportamento deve vir com validação correspondente.

## Repository Shape

- Código principal: src/main/java/br/com/rgbrainlabs/scadufaxthoth/.
- Recursos e tabelas de apoio: resources/.
- Scripts e utilitários: generate-data.sh, estimated-requests.sh, run.sh e data-generator/.
- Harness local e carga: test/.
- Documentação principal: README.md, docs/knowledge/scadufax-thoth.md, docs/rinha/, docs/javalin/ e docs/jackson/.

## Sharp Edges

- O alvo operacional da Rinha é 1 CPU e 350 MB para a stack completa.
- A arquitetura atual evita banco de dados externo; processamento e consulta permanecem em memória.
- O baseline técnico atual prioriza brute force com k-NN exato e distância euclidiana antes de ANN e IVF.
- Não trate ausência de CONTEXT.md ou docs/adr/ como erro; siga o contrato em docs/agents/.

## Agent Skills

- Issue tracker: issues e PRDs vivem em arquivos markdown em .scratch/. Veja docs/agents/issue-tracker.md.
- Triage labels: use o vocabulário canônico padrão needs-triage, needs-info, ready-for-agent, ready-for-human e wontfix. Veja docs/agents/triage-labels.md.
- Domain docs: layout single-context; CONTEXT.md e docs/adr/ ficam na raiz quando existirem. Veja docs/agents/domain.md.
- ADR template: use docs/agents/adr-template.md como base para novas ADRs.
- ADR status labels: use docs/agents/adrs-labels.md para mapear os status canônicos de ADR para as strings reais do repositório.