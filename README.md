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

## Como executar o teste local

O fluxo local usa dois passos separados: primeiro gera os artefatos no filesystem do projeto; depois sobe a aplicação com volumes montados para o benchmark usar esses arquivos sem rebuild da imagem.

### 1. Gerar os datasets localmente

Para reproduzir o cenário completo de validação, gere float32, int8 e int16:

```bash
./build-datasets.sh --types all
```

Se quiser espelhar o cenário da rinha, gere apenas o artefato que sobe no container de produção:

```bash
./build-datasets.sh --types i8
```

Esse comando cria:

- `dataset.bin` com a representação float32 usada pelo benchmark de comparação.
- `data/` com os artefatos quantizados e os metadados usados pela API em runtime e pelo benchmark.

### 2. Buildar a imagem usada no teste local

```bash
docker build -t scadufax-thoth:bench .
```

### 3. Subir a stack local com os volumes montados

```bash
docker compose -f docker-compose.local.yml up
```

Esse compose monta `dataset.bin`, `data/` e `test/` diretamente do disco. Em runtime HTTP, a API usa os artefatos quantizados em `data/`; o `dataset.bin` fica disponível para o benchmark comparar float32, int8 e int16 no mesmo ambiente.

No modo de produção em `docker-compose.yml`, a stack sobe apenas com `DATA_DIR=/data`, sem depender de `DATASET_PATH`.

### 4. Executar o benchmark de quantização

Em outro terminal, rode:

```bash
docker compose -f docker-compose.local.yml run --rm api01 \
	java -cp /app/api.jar br.com.rgbrainlabs.scadufaxthoth.benchmark.QuantizationBenchmark
```

O benchmark compara `float32`, `int8` e `int16` no mesmo conjunto de queries e imprime recall@5, divergência e latência por percentil.

## Como executar testes com o k6

docker compose up --build -d

docker compose --profile smoke up k6-smoke
cd ./test/ &&  docker compose --profile test up k6

K_VALUES="1024" NPROBE_VALUES="4" RUNS=5 ./run-benchmark.sh

