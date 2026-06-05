#!/usr/bin/env bash
# Gera os arquivos de configuração do GraalVM Tracing Agent usando o smoke K6
# como carga. Requer Docker. GraalVM local não é necessário.
#
# Os arquivos gerados em src/main/resources/META-INF/native-image/ devem ser
# commitados. Rode novamente apenas se uma dependência (Javalin, Jetty) mudar.

set -euo pipefail

# ── Localiza Maven ────────────────────────────────────────────────────────────
MVN="${MVN:-mvn}"
if ! command -v "$MVN" &>/dev/null; then
    _found=$(find "$HOME/.m2/wrapper/dists" -name "mvn" -type f 2>/dev/null | head -1)
    if [ -n "$_found" ]; then
        MVN="$_found"
    else
        echo "Erro: mvn não encontrado. Instale Maven ou defina MVN=/caminho/para/mvn"
        exit 1
    fi
fi

# ── Localiza Java ─────────────────────────────────────────────────────────────
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA="${JAVA:-java}"
if ! command -v "$JAVA" &>/dev/null; then
    _jfound=$(find "$HOME/.m2/wrapper/dists" -name "java" -type f 2>/dev/null | head -1)
    if [ -n "$_jfound" ]; then
        JAVA="$_jfound"
    elif [ -x "/usr/lib/jvm/temurin-25/bin/java" ]; then
        JAVA="/usr/lib/jvm/temurin-25/bin/java"
    else
        echo "Erro: java não encontrado. Defina JAVA_HOME ou JAVA=/caminho/para/java"
        exit 1
    fi
fi

JAR="target/scadufax-thoth.jar"
OUT="src/main/resources/META-INF/native-image"
DATA_DIR="$(pwd)/data"
CONTAINER="thoth-agent"
READY_TIMEOUT=90   # seconds; JVM + Tracing Agent leva mais que o normal

echo "==> Build do JAR..."
"$MVN" -q -DskipTests package

# ── Gera artefato V2 mínimo se não existir ────────────────────────────────────
mkdir -p "$DATA_DIR"
if [ ! -f "$DATA_DIR/index.v2" ]; then
    echo "==> Gerando artefato V2 para o Tracing Agent (64 clusters)..."
    "$JAVA" -Xmx512m --enable-native-access=ALL-UNNAMED \
        -cp "$JAR" \
        br.com.rgbrainlabs.scadufaxthoth.prep.V2ArtifactBuilder \
        src/main/resources/references.json.gz \
        "$DATA_DIR/index.v2" \
        64 5 42 i16
fi

mkdir -p "$OUT"
rm -f "$OUT/.lock"

cleanup() {
    echo ""
    echo "==> Removendo container $CONTAINER..."
    docker rm -f "$CONTAINER" > /dev/null 2>&1 || true
}
trap cleanup EXIT

# Para e remove container de execução anterior, se existir
docker rm -f "$CONTAINER" 2>/dev/null || true

echo "==> Iniciando app com Tracing Agent..."
# --entrypoint: a imagem native-image-community roteia tudo para native-image;
#   precisamos chamar o java diretamente.
# --network host: app e K6 (também em host network) compartilham o namespace de rede.
docker run -d \
    --name "$CONTAINER" \
    --network host \
    --entrypoint /usr/lib64/graalvm/graalvm-community-java25/bin/java \
    -e V2_ARTIFACT_PATH=/data/index.v2 \
    -v "$(pwd)/${JAR}:/app/app.jar:ro" \
    -v "${DATA_DIR}:/data:ro" \
    -v "$(pwd)/${OUT}:/out" \
    ghcr.io/graalvm/native-image-community:25 \
        -agentlib:native-image-agent=config-output-dir=/out \
        --enable-native-access=ALL-UNNAMED \
        -jar /app/app.jar

echo "==> Aguardando /ready (timeout ${READY_TIMEOUT}s)..."
deadline=$((SECONDS + READY_TIMEOUT))
while [ $SECONDS -lt $deadline ]; do
    if curl -sf http://localhost:9999/ready > /dev/null 2>&1; then
        echo " pronto!"
        break
    fi
    # Aborta cedo se o container saiu
    if ! docker ps -q --filter "name=^${CONTAINER}$" | grep -q .; then
        echo ""
        echo "Erro: container $CONTAINER saiu inesperadamente. Logs:"
        docker logs "$CONTAINER" 2>&1 | tail -30
        exit 1
    fi
    printf "."
    sleep 2
done

if ! curl -sf http://localhost:9999/ready > /dev/null 2>&1; then
    echo ""
    echo "Erro: /ready não respondeu em ${READY_TIMEOUT}s. Logs do container:"
    docker logs "$CONTAINER" 2>&1 | tail -30
    exit 1
fi

echo "==> Rodando smoke K6..."
docker compose -f test/docker-compose.yml --profile smoke up \
    --abort-on-container-exit --exit-code-from k6-smoke

echo ""
echo "==> Configs gerados em ${OUT}:"
ls -1 "$OUT"
