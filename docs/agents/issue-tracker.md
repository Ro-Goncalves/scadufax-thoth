# Rastreador de Issues: Markdown local

Issues e PRDs deste repositório ficam como arquivos markdown em .scratch/.

## Convenções

- Uma feature por diretório: .scratch/<feature-slug>/
- O PRD fica em .scratch/<feature-slug>/PRD.md
- Issues de implementação ficam em .scratch/<feature-slug>/issues/<NN>-<slug>.md, numerados a partir de 01
- O estado de triagem é registrado como uma linha Status: próximo ao topo de cada arquivo de issue
- Comentários e histórico de conversa ficam ao final do arquivo, sob o cabeçalho ## Comments

## Quando uma skill disser "publicar no rastreador de issues"

Crie um novo arquivo em .scratch/<feature-slug>/ e crie o diretório se ele ainda não existir.

## Quando uma skill disser "buscar o ticket relevante"

Leia o arquivo no caminho referenciado. Normalmente o usuário passará o caminho ou o número do issue diretamente.