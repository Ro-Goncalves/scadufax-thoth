# Rastreador de Issues: Markdown local

Issues e PRDs deste repositório ficam como arquivos markdown em `.scratch/`.

## Convenções

- Uma feature por diretório: `.scratch/<feature-slug>/`
- O PRD fica em `.scratch/<feature-slug>/PRD.md`
- Issues de implementação ficam em `.scratch/<feature-slug>/issues/<NN>-<slug>.md`, numerados a partir de `01`
- O estado de triagem é registrado como uma linha `Status:` próximo ao topo de cada arquivo de issue (veja `triage-labels.md` para as strings dos papéis)
- Comentários e o histórico de conversas são adicionados ao final do arquivo sob o cabeçalho `## Comments`

## Quando uma skill disser "publicar no rastreador de issues"

Crie um novo arquivo em `.scratch/<feature-slug>/` (crie o diretório, se necessário).

## Quando uma skill disser "buscar o ticket relevante"

Leia o arquivo no caminho referenciado. Normalmente o usuário passará o caminho ou o número do issue diretamente.
