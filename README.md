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

## Como testar

O fluxo principal usa `run-benchmark.sh`, que constrói a imagem, sobe a stack e roda o K6 em matriz de experimentos.

### Pré-requisito único: gerar configs do Tracing Agent

Antes do primeiro build nativo, ou quando uma dependência (Javalin, Jetty) mudar, execute o script que coleta os metadados de reflexão necessários pelo GraalVM:

```bash
./generate-native-configs.sh
```

O script sobe a aplicação com o Tracing Agent via Docker, roda o smoke K6 como carga e grava os configs em `src/main/resources/META-INF/native-image/`. Commit os arquivos gerados; não precisa rodar novamente a cada build.

### Smoke — verificação rápida após cada mudança

Valida que a stack sobe, `/ready` responde e `/fraud-score` retorna scores corretos:

```bash
docker compose up --build -d
docker compose -f test/docker-compose.yml --profile smoke up \
    --abort-on-container-exit --exit-code-from k6-smoke
docker compose down
```

> **Atenção:** com o Dockerfile nativo, `docker compose build` compila o binário GraalVM — leva ~5–10 min na primeira vez. Rodadas seguintes com o mesmo código são cacheadas pelas camadas do Docker.

### Benchmark completo — `run-benchmark.sh`

Executa uma matriz de DTYPE × K × nprobe com múltiplos boots frios por configuração. Agrega mediana e spread (min/max) de p99 e score de detecção.

**Uso básico (padrões: i16, K=2048, nprobe=1/2/4/6, 3 rodadas):**

```bash
./run-benchmark.sh
```

**Configuração pontual:**

```bash
# Um dtype, um K, um nprobe, 5 rodadas
DTYPE_VALUES="i16" K_VALUES="2048" NPROBE_VALUES="4" RUNS=5 ./run-benchmark.sh

# Comparação i8 × i16 lado a lado
DTYPE_VALUES="i8 i16" K_VALUES="2048" NPROBE_VALUES="4 6" RUNS=3 ./run-benchmark.sh
```

Os resultados individuais ficam em `benchmark-results/<dtype>_K<k>_nprobe<n>/` e o resumo é impresso no terminal ao final. O arquivo `results.json` de cada rodada fica em `test/results.json` (sobrescrito a cada rodada; os arquivos por-rodada são copiados para `benchmark-results/`).

