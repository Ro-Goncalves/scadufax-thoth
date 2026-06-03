#!/usr/bin/env bash
# Matriz de benchmark Issue 04: K × nprobe
#
# Para cada valor de K (NUM_CLUSTERS no Dockerfile):
#   1. Reconstrói a imagem Docker com o novo K.
#   2. Para cada valor de nprobe: sobe o stack, roda o K6, salva resultados.
#
# Uso:
#   ./run-benchmark.sh
#   K_VALUES="64 256" NPROBE_VALUES="4 8" ./run-benchmark.sh   (sobrescrever matriz)
#
# Pré-requisitos: docker, docker compose, jq, curl
# Tempo estimado: ~2-3 min por execução K6 × (K × nprobe) combinações

set -euo pipefail

# ── Matriz de experimentos ────────────────────────────────────────────────────
K_VALUES=(${K_VALUES:-1024 2048 4096})
NPROBE_VALUES=(${NPROBE_VALUES:-2 4 6})

RESULTS_DIR="${RESULTS_DIR:-benchmark-results}"
READY_URL="http://localhost:9999/ready"
READY_TIMEOUT=60   # segundos
K6_PROFILE="test"   # profile no test/docker-compose.yml

# ── Helpers ───────────────────────────────────────────────────────────────────

log()  { echo "[$(date '+%H:%M:%S')] $*"; }
sep()  { echo ""; echo "──────────────────────────────────────────────"; }

wait_ready() {
  local deadline=$((SECONDS + READY_TIMEOUT))
  log "Aguardando API em $READY_URL..."
  while [ $SECONDS -lt $deadline ]; do
    if curl -sf "$READY_URL" > /dev/null 2>&1; then
      log "API pronta."
      return 0
    fi
    sleep 2
  done
  log "ERRO: API não ficou pronta em ${READY_TIMEOUT}s."
  return 1
}

extract_json() {
  local file="$1" key="$2"
  jq -r "$key" "$file" 2>/dev/null || echo "N/A"
}

# ── Main ──────────────────────────────────────────────────────────────────────

mkdir -p "$RESULTS_DIR"

for K in "${K_VALUES[@]}"; do
  sep
  log "Construindo imagem com NUM_CLUSTERS=$K..."
  docker compose build \
    --build-arg NUM_CLUSTERS="$K" \
    --build-arg KMEANS_ITERATIONS=20 \
    --build-arg KMEANS_SEED=42 \
    2>&1 | tail -5
  log "Build concluído para K=$K."

  for NPROBE in "${NPROBE_VALUES[@]}"; do
    RUN_DIR="$RESULTS_DIR/K${K}_nprobe${NPROBE}"
    mkdir -p "$RUN_DIR"

    sep
    log "Iniciando stack: K=$K  nprobe=$NPROBE"

    # Exporta NPROBE para que docker-compose.yml use ${NPROBE:-8}
    export NPROBE

    docker compose up -d --no-build
    if ! wait_ready; then
      log "FALHA: pulando K=$K nprobe=$NPROBE"
      docker compose down --remove-orphans || true
      echo "{\"error\":\"api_not_ready\"}" > "$RUN_DIR/results.json"
      continue
    fi

    log "Rodando K6 (K=$K nprobe=$NPROBE)..."
    RUN_START=$SECONDS
    (cd test && docker compose --profile "$K6_PROFILE" run --rm k6) || true
    log "K6 encerrado em $((SECONDS - RUN_START))s."

    if [ -f "test/results.json" ]; then
      cp "test/results.json" "$RUN_DIR/results.json"
      log "Resultado salvo em $RUN_DIR/results.json"
    else
      log "AVISO: test/results.json não encontrado."
      echo "{\"error\":\"no_results\"}" > "$RUN_DIR/results.json"
    fi

    docker compose down --remove-orphans
    log "Stack encerrado."
  done
done

# ── Sumário ───────────────────────────────────────────────────────────────────

sep
echo ""
echo "=== SUMÁRIO DO BENCHMARK ==="
printf "%-8s %-8s %-12s %-14s %-14s %-12s\n" \
  "K" "nprobe" "p99" "score_p99" "score_det" "final_score"
echo "────────────────────────────────────────────────────────────────────────"

for K in "${K_VALUES[@]}"; do
  for NPROBE in "${NPROBE_VALUES[@]}"; do
    RUN_DIR="$RESULTS_DIR/K${K}_nprobe${NPROBE}"
    F="$RUN_DIR/results.json"
    if [ -f "$F" ] && jq -e '.p99' "$F" > /dev/null 2>&1; then
      P99=$(extract_json "$F" '.p99')
      SP99=$(extract_json "$F" '.scoring.p99_score.value')
      SDET=$(extract_json "$F" '.scoring.detection_score.value')
      SFIN=$(extract_json "$F" '.scoring.final_score')
    else
      P99="N/A"; SP99="N/A"; SDET="N/A"; SFIN="N/A"
    fi
    printf "%-8s %-8s %-12s %-14s %-14s %-12s\n" \
      "$K" "$NPROBE" "$P99" "$SP99" "$SDET" "$SFIN"
  done
done

echo ""
echo "Resultados completos em: $RESULTS_DIR/"
