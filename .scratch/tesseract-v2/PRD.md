# PRD: Projeto Tesseract V2

Status: ready-for-agent

## Declaração do problema

O backend atual provou corretude funcional e estabilidade operacional, mas a estratégia de busca por Força Bruta ainda está presa ao custo O(N). Na prática, isso empurra a API para uma faixa de latência incompatível com a competitividade da Rinha, mesmo com contrato HTTP correto, memória controlada e cálculo de fraude aderente à regra oficial.

Do ponto de vista do usuário da API, o problema é simples: a decisão de fraude chega tarde demais quando o volume cresce. Do ponto de vista do produto, isso transforma uma solução correta em uma solução não competitiva. Do ponto de vista operacional, a CPU passa tempo demais calculando distância sobre toda a base, a pressão sobre o Page Cache continua alta por causa do layout em float32, e o Runtime segue pagando caro por trabalho que poderia ter sido deslocado para o Build Time.

## Solução

A solução da V2 é reorganizar o sistema em torno de dois pilares complementares.

O primeiro pilar é a quantização escalar dos vetores, trocando a representação atual em float32 por uma representação compacta em int8 com sentinela reservado para ausência de last_transaction. Isso reduz I/O, reduz footprint do artefato e prepara o motor para operar com matemática inteira.

O segundo pilar é a introdução de um índice IVF construído em Build Time. Em vez de comparar a query com todo o dataset, o sistema passa a organizar os vetores em clusters e visitar apenas os agrupamentos mais promissores em Runtime. A consulta continua obedecendo ao mesmo contrato externo e continua produzindo fraud_score a partir dos 5 vizinhos mais próximos, mas passa a fazer isso sobre um artefato binário orientado à busca, com diretório de clusters, offsets e blocos contíguos.

Esta solução mantém a arquitetura atual do produto enxuta, preserva o contrato HTTP já aceito, respeita as restrições de 1 vCPU e 350 MB do stack completo e desloca o máximo possível de custo para o Build Time, onde ele é pago uma única vez.

## Histórias de usuário

1. Como participante da Rinha, eu quero que a API responda em milissegundos baixos, para que a solução volte a ser competitiva.
2. Como consumidor do endpoint de fraude, eu quero continuar usando o mesmo contrato HTTP, para que a evolução interna não quebre integração.
3. Como mantenedor do backend, eu quero substituir a leitura em float32 por uma representação quantizada compacta, para que o Runtime gaste menos CPU e menos memória.
4. Como mantenedor do pipeline de dados, eu quero que a construção do dataset aconteça em Build Time, para que a API suba pronta para servir tráfego.
5. Como engenheiro de performance, eu quero que o artefato binário tenha layout fixo e previsível, para que a leitura por offset seja barata e segura.
6. Como engenheiro de dados, eu quero reservar um sentinela explícito para ausência de transação anterior, para que a semântica do vetor seja preservada após a quantização.
7. Como mantenedor do motor de busca, eu quero separar o dataset em clusters, para que a query evite varrer a base inteira a cada requisição.
8. Como mantenedor do índice vetorial, eu quero armazenar metadados de cluster, para que o Runtime consiga saltar diretamente para os blocos corretos.
9. Como responsável por corretude, eu quero comparar o resultado do novo motor com uma referência exata, para que a busca otimizada não degrade silenciosamente a decisão de fraude.
10. Como responsável por benchmark, eu quero rodar experimentos A/B com diferentes valores de K e nprobe, para que a configuração final seja guiada por medição.
11. Como operador do sistema, eu quero manter zero erros HTTP durante a carga, para que o score de detecção não seja destruído por falha operacional.
12. Como operador do sistema, eu quero manter a taxa total de falhas bem abaixo do corte de 15%, para que a melhoria de latência não venha com regressão de qualidade.
13. Como mantenedor do Build Time, eu quero que a clusterização offline seja previsível, para que o docker build não vire um gargalo operacional.
14. Como mantenedor do artefato binário, eu quero registrar versão, dimensões e offsets no cabeçalho, para que futuras leituras sejam compatíveis e auditáveis.
15. Como engenheiro de busca vetorial, eu quero visitar apenas os clusters mais promissores no Runtime, para que a complexidade prática da busca caia drasticamente.
16. Como engenheiro de aplicação, eu quero manter o cálculo final de approved e fraud_score intacto, para que a mudança continue aderente às regras da competição.
17. Como responsável por testes, eu quero módulos profundos e isoláveis no pipeline de dataset e no motor de busca, para que a validação não dependa apenas de testes de ponta a ponta.
18. Como mantenedor do boot da aplicação, eu quero carregar um artefato já organizado, para que a inicialização permaneça curta e previsível.
19. Como responsável pela documentação arquitetural, eu quero registrar claramente o que entra na V2 e o que fica para depois, para que a implementação não escorregue para escopo indefinido.
20. Como responsável pelo roadmap, eu quero deixar parser manual de data, SIMD explícito e tuning extremo fora do escopo desta entrega, para que a equipe preserve foco no ganho principal.
21. Como engenheiro de confiabilidade, eu quero que o dataset carregado em Runtime preserve consistência entre vetores, labels e diretório de clusters, para que o motor não opere sobre offsets incorretos.
22. Como engenheiro de benchmark, eu quero uma meta factível de p99 para a V2, para que a evolução seja medida contra uma referência realista e não contra um alvo prematuro.
23. Como mantenedor da camada web, eu quero que a troca do motor de busca não exija refazer a camada HTTP nesta versão, para que a evolução permaneça incremental.
24. Como desenvolvedor do projeto, eu quero uma estratégia clara para abortar ou revisar a quantização se a qualidade cair demais, para que a V2 não se torne rápida às custas da detecção.
25. Como avaliador da solução, eu quero ver que a V2 reduz latência em mais de uma ordem de grandeza em relação à V1, para que a mudança tenha impacto material e mensurável.

## Decisões de implementação

- O escopo desta iniciativa cobre apenas a V2 definida na especificação do Projeto Tesseract. Itens marcados como trabalho posterior ficam fora deste PRD.
- A arquitetura continua baseada em Build Time pesado e Runtime leve. O custo estrutural de preparação do índice deve ser pago durante a geração do artefato, não durante o atendimento das requisições.
- A representação do vetor será quantizada para int8 com domínio 0..127 para valores normalizados e com o valor -128 reservado como sentinela para dimensões que hoje usam ausência de last_transaction.
- O rótulo de cada vizinho deixa de ser persistido como texto e passa a ser persistido como valor binário compacto, suficiente para distinguir fraude de legítimo.
- O artefato binário da V2 terá cabeçalho fixo, diretório de clusters e blocos contíguos de registros. O formato é orientado a salto por offset e leitura sequencial, e não a parsing textual.
- O registro físico do vetor passa a ter tamanho fixo, reduzindo branchs de leitura e simplificando o acesso em Runtime.
- A clusterização offline será introduzida como parte do pipeline do dataset. O sistema deve gerar centróides iniciais, atribuir vetores aos clusters, calcular estatísticas de bloco e gravar a estrutura final do índice.
- A quantidade de clusters e a quantidade de clusters visitados por consulta serão parâmetros experimentais da V2. A implementação deve permitir rodadas de benchmark com diferentes combinações sem exigir reescrita do motor.
- A busca em Runtime será refeita em torno de um módulo profundo de índice IVF, cuja interface externa permaneça simples: receber o vetor da query e devolver os 5 melhores vizinhos segundo a métrica vigente.
- O novo motor de busca deve operar sobre o artefato estruturado da V2, consumir metadados de cluster e visitar apenas os agrupamentos mais promissores.
- A lógica final de decisão de fraude permanece inalterada: o sistema continua calculando fraud_score a partir da fração de fraudes dentro do top-5 e continua usando o limiar oficial para approved.
- O pipeline de vetorização da query permanece compatível com o espaço vetorial atual, mas deve passar a conversar corretamente com a nova representação quantizada.
- O pipeline de geração do dataset deve ser organizado em módulos testáveis: leitura do dataset de origem, quantização, clusterização, montagem do diretório de clusters e serialização final do artefato.
- O módulo de busca vetorial deve ser profundo e testável de forma isolada, encapsulando a navegação pelo índice, a seleção dos clusters visitados e o ranking dos vizinhos.
- O sistema deve preservar a possibilidade de validação contra uma referência exata. A busca atual por Força Bruta continua útil como ground truth para comparar resultados durante a transição.
- O Build Time precisa ser determinístico o suficiente para permitir repetição de medições e reprodutibilidade do artefato.
- A meta arquitetural desta versão é levar o p99 para até 25 ms, com meta esticada próxima de 10 ms, sem mexer ainda na camada de transporte HTTP ou em micro-otimizações extremas.
- Existe um risco técnico explícito na escolha por int8: se a quantização reduzir demais a qualidade do ranking e impactar a detecção, a estratégia deverá ser reavaliada em uma iteração posterior. Essa possível revisão não faz parte do escopo principal deste PRD.

## Decisões de teste

- Um bom teste deve validar comportamento externo observável e propriedades do contrato, não detalhes internos casuais da implementação.
- O módulo de vetorização deve continuar sendo validado contra os exemplos oficiais documentados da regra de detecção.
- O módulo de quantização deve ser testado para garantir preservação do domínio esperado, tratamento correto do sentinela e compatibilidade entre vetor de Runtime e vetor persistido.
- O módulo de serialização do artefato deve ser testado para garantir consistência entre cabeçalho, diretório de clusters, offsets, quantidade de registros e labels.
- O módulo de clusterização offline deve ser testado quanto a invariantes estruturais: total de vetores preservado, offsets consistentes, clusters vazios tratados corretamente e artefato reproduzível.
- O módulo do buscador IVF deve ser testado contra uma referência exata, comparando ranking e fraude final obtida para um conjunto representativo de queries.
- O módulo de integração HTTP deve ser testado para garantir que o contrato externo permaneça estável durante a troca do motor interno.
- Os testes de benchmark e smoke devem continuar sendo usados como validação de comportamento em ambiente semelhante ao de execução real, sem substituir testes determinísticos de corretude.
- As referências anteriores do código-base para este trabalho são os testes já existentes de vetorização, de cálculo de distância e de busca mínima sobre dataset reduzido. A nova suíte deve expandir esse padrão para o artefato IVF e para a comparação contra ground truth.
- Devem existir testes que detectem regressão tanto de corretude quanto de estrutura do índice, para evitar que uma melhoria aparente de latência esconda perda de qualidade de detecção.

## Fora do escopo

- Reescrever a camada HTTP da aplicação.
- Substituir Javalin, Jackson ou a arquitetura web atual.
- Introduzir parser JSON manual nesta versão.
- Remover o parse atual de timestamp do hot path nesta versão.
- Introduzir Vector API explícita, SIMD manual ou tuning extremo de kernel de distância nesta versão.
- Trocar a métrica principal para L1 nesta versão.
- Reabrir o contrato da API ou alterar a regra oficial de approved e fraud_score.
- Reescrever a solução em binário nativo como objetivo central desta versão.
- Implementar servidor HTTP custom, load balancer alternativo ou otimizações de transporte.
- Implementar os candidatos já registrados para V3, como parser numérico de timestamp, tuning fino de runtime, AOT agressivo, pesos por dimensão e meta de p99 em 1 ms.

## Notas adicionais

- Esta iniciativa deve ser tratada como uma evolução incremental da baseline já validada, e não como uma reescrita total do produto.
- O maior ganho esperado da V2 vem da queda de escopo da busca em Runtime e da compactação do artefato, não de truques isolados de micro-otimização.
- A validação precisa respeitar as regras da Rinha e o ambiente realista de Docker com limites de CPU e memória, porque medições fora desse cenário tendem a superestimar ganhos.
- O roadmap desta versão termina quando a V2 estiver estável, mensurada e pronta para benchmark competitivo. O que vier depois deve ser tratado como nova fase, e não como extensão informal deste mesmo PRD.
