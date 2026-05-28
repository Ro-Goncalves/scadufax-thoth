# Jackson Curado

Documentação curada a partir das consultas feitas em 2026-05-27.

## Fontes

* Context7: `/fasterxml/jackson`
* Context7: `/fasterxml/jackson-databind`

## Pontos úteis para este repositório

* Jackson Databind é a base de data binding JSON para Java.
* Javalin usa Jackson como mapper JSON padrão quando Jackson está disponível no classpath.
* A configuração do mapper pode ser refinada no Javalin via `config.jsonMapper(...)`.

## Impacto prático no bootstrap

* Para a primeira fase do projeto, Jackson entra como biblioteca madura e suficiente para DTOs e serialização básica.
* O custo esperado de performance está no parsing e na serialização JSON, além das alocações transitórias dos objetos de request e response.
* Na baseline inicial, esse custo não deve dominar o tempo total, já que o gargalo principal tende a estar na busca vetorial exata.

## Aplicação no projeto

No bootstrap inicial do Scadufax Thoth, Jackson foi mantida como dependência explícita para:

* deixar a escolha da stack refletida no build;
* permitir customização futura do mapper sem alterar a base do projeto;
* manter coerência com a decisão arquitetural registrada em `docs/knowledge/scadufax-thoth.md`.