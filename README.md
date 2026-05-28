# Scadufax Thoth

Repositório de desenvolvimento da solução da RG Brain Labs para a Rinha de Backend 2026.

Este repositório é, ao mesmo tempo, a base de implementação e o registro das decisões arquiteturais, experimentos e trade-offs adotados ao longo do projeto.

## Objetivo

Construir uma API de detecção de fraude por busca vetorial, aderente às regras da Rinha, respeitando o limite total de 1 CPU e 350 MB de RAM para a stack completa.

## Decisões já tomadas

* **Linguagem principal:** Java 25, a LTS mais atual.
* **Framework HTTP:** Javalin.
* **Arquitetura de dados:** sem banco de dados externo; processamento e consulta em memória.
* **Pré-processamento:** geração de artefatos binários no build para evitar parsing pesado em runtime.
* **Estratégia de evolução:** baseline funcional, compactação de memória, busca aproximada e micro-otimizações em fases separadas.

## Documentação principal

* [Documento de arquitetura](docs/knowledge/scadufax-thoth.md)
* [Documentação oficial da Rinha](docs/rinha/README.md)
* [Contrato da API](docs/rinha/API.md)
* [Regras de detecção](docs/rinha/REGRAS_DE_DETECCAO.md)
* [Restrições de arquitetura da competição](docs/rinha/ARQUITETURA.md)

As decisões de arquitetura, escolhas de stack, trade-offs de performance e pendências técnicas ficam centralizadas em [docs/knowledge/scadufax-thoth.md](docs/knowledge/scadufax-thoth.md).

## Como este repositório será usado

* Implementação da API e do pipeline de pré-processamento.
* Registro das decisões técnicas e das justificativas de arquitetura.
* Comparação entre experimentos de performance, memória e qualidade de detecção.
* Base pública de portfólio do processo técnico adotado no projeto.

## Próximos passos

1. Estruturar a aplicação Java 25 com Javalin.
2. Implementar `GET /ready` e o esqueleto de `POST /fraud-score`.
3. Criar o pipeline inicial de normalização e vetorização.
4. Preparar a primeira rodada de validação local com os testes disponíveis em [test](test).
