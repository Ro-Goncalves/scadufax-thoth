# Estágio 1: Build (Compila o código e gera os artefatos de dataset)
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

COPY pom.xml .
COPY src ./src
RUN mvn -q -B -DskipTests package

# Busca o references.json.gz oficial do repositório da rinha.
# ADD com URL é cacheado pelo Docker enquanto a URL não mudar —
# reconstrução só acontece quando o arquivo remoto muda ou cache é limpo.
ADD https://github.com/zanfranceschi/rinha-de-backend-2026/raw/main/resources/references.json.gz \
    /build/src/main/resources/references.json.gz

# Parâmetros de build do K-means (variáveis para experimentos sem recompilar)
ARG NUM_CLUSTERS=1024
ARG KMEANS_ITERATIONS=20
ARG KMEANS_SEED=42

# Gera o artefato binário V2 com múltiplos clusters via K-means.
RUN java -Xmx512m -cp target/scadufax-thoth.jar \
    br.com.rgbrainlabs.scadufaxthoth.prep.V2ArtifactBuilder \
    src/main/resources/references.json.gz /build/data/index.v2 \
    ${NUM_CLUSTERS} ${KMEANS_ITERATIONS} ${KMEANS_SEED}

# Estágio 2: Runtime
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app

COPY --from=build /build/target/scadufax-thoth.jar /app/api.jar
COPY --from=build /build/data /data

# Tuning JVM para contêiner com 1 CPU / 350 MB (stack completa):
#   -Xms / -Xmx      : heap pequeno para deixar memória para o mmap do SO.
#   -XX:+UseSerialGC  : GC mais leve para workloads single-core/low-memory.
ENV JAVA_OPTS="-Xms50m -Xmx80m -XX:+UseSerialGC -XX:MaxDirectMemorySize=10m --enable-native-access=ALL-UNNAMED"

ENV V2_ARTIFACT_PATH=/data/index.v2

EXPOSE 8080

CMD ["sh", "-c", "java $JAVA_OPTS -jar /app/api.jar"]
