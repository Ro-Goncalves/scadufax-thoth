# ── Estágio de build JVM: JAR + artefato V2 ──────────────────────────────────
FROM maven:3.9-eclipse-temurin-25 AS jar-build
WORKDIR /build

COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

# ADD com URL é cacheado pelo Docker enquanto a URL não mudar.
ADD https://github.com/zanfranceschi/rinha-de-backend-2026/raw/main/resources/references.json.gz \
    /tmp/references.json.gz

ARG NUM_CLUSTERS=2048
ARG KMEANS_ITERATIONS=20
ARG KMEANS_SEED=42
ARG DTYPE=i16

RUN mkdir -p /data \
 && java -Xmx512m --enable-native-access=ALL-UNNAMED \
         -cp target/scadufax-thoth.jar \
         br.com.rgbrainlabs.scadufaxthoth.prep.V2ArtifactBuilder \
         /tmp/references.json.gz /data/index.v2 \
         ${NUM_CLUSTERS} ${KMEANS_ITERATIONS} ${KMEANS_SEED} ${DTYPE}

# ── Estágio native-build: compila o binário nativo ────────────────────────────
# Fallback: container-registry.oracle.com/graalvm/jdk:25
FROM ghcr.io/graalvm/native-image-community:25 AS native-build

# A imagem community não inclui Maven; copiamos da imagem oficial do Maven.
# Compilar VIA native-maven-plugin (e não native-image cru) é essencial: o
# plugin habilita o graalvm-reachability-metadata repository, que traz a
# configuração de threading do Jetty. Sem ela o servidor sobe SEM pool de
# threads no binário nativo e processa requisições em série (timeouts sob carga).
COPY --from=maven:3.9-eclipse-temurin-25 /usr/share/maven /usr/share/maven
RUN ln -sf /usr/share/maven/bin/mvn /usr/local/bin/mvn

WORKDIR /build
COPY --from=jar-build /root/.m2 /root/.m2
COPY pom.xml .
COPY src ./src

# O perfil 'native' (pom.xml) invoca native-maven-plugin com os buildArgs e a
# metadata repository. Gera target/scadufax-thoth (imageName do pom).
RUN mvn -q -Pnative -DskipTests package \
 && cp target/scadufax-thoth /app/scadufax-thoth

# ── Estágio runner: distroless sem JRE ───────────────────────────────────────
FROM gcr.io/distroless/cc-debian12 AS runner

COPY --from=native-build /app/scadufax-thoth                /app/scadufax-thoth
COPY --from=jar-build    /data/index.v2                     /data/index.v2
# distroless/cc não inclui zlib; o binário GraalVM a exige
COPY --from=native-build /usr/lib64/libz.so.1 /usr/lib/x86_64-linux-gnu/libz.so.1

ENV V2_ARTIFACT_PATH=/data/index.v2
ENV PORT=9999

EXPOSE 9999

# Limite de heap explícito: sem isto o binário nativo usa 80% da RAM do cgroup
# (~132 MB no container de 165 MB) e é morto por OOM sob carga. Espelha o
# -Xms50m -Xmx80m do antigo runtime JVM. O Serial GC já é o default na imagem
# community, e o índice é mmap (file-backed, evictável) fora do heap.
ENTRYPOINT ["/app/scadufax-thoth", "-Xms50m", "-Xmx80m"]
