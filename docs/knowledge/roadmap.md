# 🚀 Roadmap: Migração para GraalVM Native Image

## 1. O Paradigma: Por que estamos fazendo isso?

No modelo tradicional, o seu contêiner precisa carregar um Sistema Operacional base + a JVM completa + as dependências + o seu código (JAR). Ao executar, a JVM compila o código em tempo real (JIT), o que consome picos de CPU e aloca dezenas de megabytes de RAM instantaneamente.

Com o **GraalVM Native Image (AOT - Ahead-of-Time Compilation)**, nós eliminamos a JVM do contêiner. O código Java é compilado durante o processo de *build* para um binário executável de máquina, específico para Linux.

* **Uso de RAM em Repouso:** Cai de ~80MB para **~15MB**.
* **Startup:** Cai de segundos para **milissegundos** (Fim absoluto do *cold start*).
* **OS Page Cache:** Sobram mais de **125MB livres** para o Sistema Operacional gerenciar o `mmap`, garantindo latências baixíssimas mesmo sob bombardeio de 900 RPS.

---

## 2. O Caminho das Pedras (Passo a Passo)

### Fase 1: Validação de Dependências

O GraalVM é rigoroso. Como ele compila tudo "antes", ele precisa saber exatamente qual código será executado.

* **O Desafio:** Bibliotecas que usam muita *Reflection*, *Dynamic Proxies* ou leitura de recursos dinâmicos (como alguns serializadores JSON antigos) podem quebrar se não forem mapeadas.
* **A Ação:** O Javalin já possui suporte nativo excelente, mas verifique qual biblioteca você usa para JSON (Jackson, Gson, Moshi). O Jackson, por exemplo, requer configurações específicas de AOT ou o uso da biblioteca `jackson-module-blackbird`.

### Fase 2: Configuração do Build Tool

Você precisará adicionar o plugin oficial do GraalVM ao seu gerenciador de dependências (Maven ou Gradle).

**Exemplo (Maven - `pom.xml`):**

```xml
<plugin>
    <groupId>org.graalvm.buildtools</groupId>
    <artifactId>native-maven-plugin</artifactId>
    <version>0.10.1</version>
    <executions>
        <execution>
            <goals>
                <goal>build</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <imageName>fraud-score-api</imageName>
        <buildArgs>
            <buildArg>--no-fallback</buildArg>
            <buildArg>-H:+ReportExceptionStackTraces</buildArg>
        </buildArgs>
    </configuration>
</plugin>

```

### Fase 3: Geração de Metadados (O Pulo do Gato)

Para resolver problemas de bibliotecas que usam *Reflection* (que não são detectadas no AOT), o GraalVM oferece o **Tracing Agent**.
Você deve rodar a sua aplicação localmente uma vez usando esse agente e disparar o seu K6 de *Smoke Test*. O agente vai "assistir" a aplicação rodando, identificar tudo o que foi acessado dinamicamente e gerar arquivos JSON de configuração automaticamente.

**Comando:**

```bash
java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image -jar target/sua-api.jar

```

Após rodar isso e fazer algumas requisições, os arquivos `reflect-config.json`, `resource-config.json`, etc., serão gerados e o GraalVM saberá compilar tudo perfeitamente.

### Fase 4: O Novo Dockerfile (Multi-stage Build)

O seu *pipeline* de deploy vai mudar. A compilação demora mais tempo e exige bastante CPU/RAM (prepare o ambiente de CI/CD). O Dockerfile terá dois estágios: um para compilar e um "vazio" apenas para rodar o binário.

```dockerfile
# Estágio 1: Builder (Pesado, precisa de RAM/CPU)
FROM ghcr.io/graalvm/native-image-community:21 AS builder
WORKDIR /app
COPY . .
# Compila o executável nativo (pode demorar alguns minutos)
RUN ./mvnw native:compile -Pnative

# Estágio 2: Runner (Minúsculo e Enxuto)
# Usamos distroless ou alpine por segurança e tamanho
FROM cgr.dev/chainguard/glibc-dynamic:latest 
WORKDIR /app
# Copia APENAS o binário do estágio anterior. Nada de Java, nada de JAR.
COPY --from=builder /app/target/fraud-score-api /app/fraud-score-api

# Expõe a porta e roda o binário diretamente
EXPOSE 9999
CMD ["/app/fraud-score-api"]

```

---

## 3. Trade-offs Arquiteturais (Atenção)

Para documentar formalmente e apresentar as escolhas de design:

1. **Tempo de Build Aumentado:** O tempo para gerar a imagem Docker vai pular de segundos para alguns minutos. A otimização em tempo de compilação é um processo pesado.
2. **Latência de Pico (Max Throughput):** O JIT tradicional do Java (com muita RAM e tempo aquecendo) *pode* atingir um throughput bruto ligeiramente maior em longo prazo porque ele otimiza o código com base no perfil exato de uso do processador em tempo real. No entanto, para o nosso cenário restrito (145MB e necessidade de previsibilidade no P99), **a estabilidade térmica e de memória do AOT vence de lavada**.
3. **Observabilidade:** Ferramentas tradicionais de profiling de JVM (como JConsole, VisualVM via JMX) não funcionam em imagens nativas. O monitoramento deverá ser puramente focado nas métricas expostas pela API (Prometheus/Grafana via logs ou endpoints `/metrics`).

Testar servidor http de: https://github.com/EdnaldoLuiz/rinha-de-backend-2026-java