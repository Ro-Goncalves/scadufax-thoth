#!/usr/bin/env bash
# build-datasets.sh — gera todos os artefatos de dataset localmente.
#
# Uso:
#   ./build-datasets.sh                # gera float32 + int8 + int16 (padrão: all)
#   ./build-datasets.sh --types i8     # gera float32 + int8 apenas
#   ./build-datasets.sh --types i16    # gera float32 + int16 apenas
#
# Saída:
#   ./dataset.bin        — vetores float32 (busca de produção)
#   ./data/              — artefatos quantizados (vectors-i*.bin, labels.bin, meta.properties)
#
# Pré-requisito: Maven e JDK 25 disponíveis no PATH.
set -euo pipefail

REFS_URL="https://github.com/zanfranceschi/rinha-de-backend-2026/raw/main/resources/references.json.gz"
REFS_PATH="resources/references.json.gz"
TYPES="${2:-all}"  # valor padrão: all

# Processar --types se fornecido
for i in "$@"; do
    if [[ "$i" == "--types" ]]; then
        shift
        TYPES="$1"
        break
    fi
done

JAR="target/scadufax-thoth.jar"
JAVA_OPTS="-Xmx256m"

echo "==> Compilando JAR..."
mvn -q -DskipTests package

echo "==> Baixando references.json.gz (se necessário)..."
if [[ ! -f "$REFS_PATH" ]]; then
    mkdir -p resources
    curl -fsSL "$REFS_URL" -o "$REFS_PATH"
    echo "    baixado: $REFS_PATH"
else
    echo "    usando cache: $REFS_PATH"
fi

echo "==> Gerando dataset float32 (dataset.bin)..."
java $JAVA_OPTS -cp "$JAR" br.com.rgbrainlabs.scadufaxthoth.DatasetBuilder

echo "==> Gerando artefatos quantizados (./data/)  types=$TYPES..."
java $JAVA_OPTS -cp "$JAR" \
    br.com.rgbrainlabs.scadufaxthoth.prep.QuantizedDatasetBuilder \
    "$REFS_PATH" ./data --types "$TYPES"

echo ""
echo "==> Artefatos gerados:"
ls -lh dataset.bin data/ 2>/dev/null || true
