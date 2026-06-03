#!/usr/bin/env bash
# Matriz de benchmark: K × nprobe, com múltiplos boots frios por config.
#
# Para cada valor de K (NUM_CLUSTERS no Dockerfile):
#   1. Reconstrói a imagem Docker com o novo K.
#   2. Para cada nprobe: repete RUNS vezes um BOOT FRIO COMPLETO
#      (up → /ready → k6 → down) e agrega mediana + spread (min/max).
#
# Por que boot frio a cada rodada?
#   A rinha mede um único tiro: cold boot → warmup → /ready → carga. Repetir o
#   boot frio N vezes reproduz esse cenário N vezes. A mediana remove o ruído do
#   host (WSL2/CPU contendida); o spread (min/max) revela a variância intrínseca
#   do artefato — que é o que a rinha vai sortear no tiro único. Spread apertado
#   importa mais que mediana boa.
#
# Uso:
#   ./run-benchmark.sh
#   K_VALUES="1024" NPROBE_VALUES="4" RUNS=5 ./run-benchmark.sh
#
# Pré-requisitos: docker, docker compose, jq, curl
# Dica: rode com o host quieto (sem IDE/build concorrente) para reduzir ruído.

set -euo pipefail

# ── Matriz de experimentos ────────────────────────────────────────────────────
K_VALUES=(${K_VALUES:-1024 2048 4096})
NPROBE_VALUES=(${NPROBE_VALUES:-2 4 6})
RUNS="${RUNS:-1}"               # boots frios por config
SETTLE_SECS="${SETTLE_SECS:-5}" # pausa entre rodadas para o host assentar

RESULTS_DIR="${RESULTS_DIR:-benchmark-results}"
READY_URL="http://localhost:9999/ready"
READY_TIMEOUT="${READY_TIMEOUT:-90}"   # segundos (warmup com platô pode levar mais)
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

# Mediana numérica de stdin (uma valor por linha). Para N par, média dos dois centrais.
median() {
  sort -n | awk '
    { v[NR] = $1 }
    END {
      if (NR == 0) { print "N/A"; exit }
      if (NR % 2) { print v[(NR + 1) / 2] }
      else        { print (v[NR/2] + v[NR/2 + 1]) / 2.0 }
    }'
}

stat_min() { sort -n | head -1; }
stat_max() { sort -n | tail -1; }
_med_arr() { if [[ $# -gt 0 ]]; then printf '%s\n' "$@" | median; else echo "N/A"; fi; }

# ── Main ──────────────────────────────────────────────────────────────────────

mkdir -p "$RESULTS_DIR"
SUMMARY_ROWS=()

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
    export NPROBE

    P99_VALUES=()
    SCORE_VALUES=()
    TP_VALUES=(); FP_VALUES=(); TN_VALUES=(); FN_VALUES=()
    SP99_VALUES=(); SDET_VALUES=(); FAILRATE_VALUES=()
    CUT_P99_VALUES=(); CUT_DET_VALUES=()

    for run in $(seq 1 "$RUNS"); do
      sep
      log "K=$K nprobe=$NPROBE — rodada $run/$RUNS (boot frio)"

      docker compose up -d --no-build
      if ! wait_ready; then
        log "FALHA: pulando rodada $run de K=$K nprobe=$NPROBE"
        docker compose down --remove-orphans || true
        continue
      fi

      log "Rodando K6..."
      RUN_START=$SECONDS
      (cd test && docker compose --profile "$K6_PROFILE" run --rm k6) || true
      log "K6 encerrado em $((SECONDS - RUN_START))s."

      RUN_FILE="$RUN_DIR/results_run${run}.json"
      if [ -f "test/results.json" ]; then
        cp "test/results.json" "$RUN_FILE"
        _row=$(jq -r '[
          (.scoring.raw.p99_ms                          // ""),
          (.scoring.raw.final_score                     // ""),
          (.scoring.breakdown.true_positive_detections  // ""),
          (.scoring.breakdown.false_positive_detections // ""),
          (.scoring.breakdown.true_negative_detections  // ""),
          (.scoring.breakdown.false_negative_detections // ""),
          (.scoring.p99_score.value                     // ""),
          (.scoring.detection_score.value               // ""),
          (.scoring.failure_rate                        // ""),
          (.scoring.p99_score.cut_triggered             // false | tostring),
          (.scoring.detection_score.cut_triggered       // false | tostring)
        ] | @tsv' "$RUN_FILE" 2>/dev/null) || _row=""
        IFS=$'\t' read -r P99 SCORE TP FP_VAL TN FN_VAL SP99 SDET FAILRATE CUT_P99 CUT_DET <<< "$_row" || true
        [ -n "$P99"      ] && P99_VALUES+=("$P99")
        [ -n "$SCORE"    ] && SCORE_VALUES+=("$SCORE")
        [ -n "$TP"       ] && TP_VALUES+=("$TP")
        [ -n "$FP_VAL"   ] && FP_VALUES+=("$FP_VAL")
        [ -n "$TN"       ] && TN_VALUES+=("$TN")
        [ -n "$FN_VAL"   ] && FN_VALUES+=("$FN_VAL")
        [ -n "$SP99"     ] && SP99_VALUES+=("$SP99")
        [ -n "$SDET"     ] && SDET_VALUES+=("$SDET")
        [ -n "$FAILRATE" ] && FAILRATE_VALUES+=("$FAILRATE")
        CUT_P99_VALUES+=("${CUT_P99:-false}")
        CUT_DET_VALUES+=("${CUT_DET:-false}")
        log "Rodada $run: p99=${P99:-N/A}ms  final_score=${SCORE:-N/A}"
      else
        log "AVISO: test/results.json não encontrado na rodada $run."
      fi

      docker compose down --remove-orphans
      if [ "$run" -lt "$RUNS" ] && [ "$SETTLE_SECS" -gt 0 ]; then
        log "Assentando ${SETTLE_SECS}s..."
        sleep "$SETTLE_SECS"
      fi
    done

    # ── Agregação da config ───────────────────────────────────────────────────
    if [ "${#P99_VALUES[@]}" -gt 0 ]; then
      P99_MED=$(printf '%s\n' "${P99_VALUES[@]}" | median)
      P99_MIN=$(printf '%s\n' "${P99_VALUES[@]}" | stat_min)
      P99_MAX=$(printf '%s\n' "${P99_VALUES[@]}" | stat_max)
    else
      P99_MED="N/A"; P99_MIN="N/A"; P99_MAX="N/A"
    fi
    SCORE_MED=$(   _med_arr "${SCORE_VALUES[@]}")
    SP99_MED=$(    _med_arr "${SP99_VALUES[@]}")
    SDET_MED=$(    _med_arr "${SDET_VALUES[@]}")
    TP_MED=$(      _med_arr "${TP_VALUES[@]}")
    FP_MED=$(      _med_arr "${FP_VALUES[@]}")
    TN_MED=$(      _med_arr "${TN_VALUES[@]}")
    FN_MED=$(      _med_arr "${FN_VALUES[@]}")
    FAILRATE_MED=$(  _med_arr "${FAILRATE_VALUES[@]}")

    CUT_P99_ANY="false"; CUT_DET_ANY="false"
    for v in "${CUT_P99_VALUES[@]}"; do
      if [[ "$v" == "true" ]]; then CUT_P99_ANY="true"; break; fi
    done
    for v in "${CUT_DET_VALUES[@]}"; do
      if [[ "$v" == "true" ]]; then CUT_DET_ANY="true"; break; fi
    done

    jq -n \
      --argjson runs     "${#P99_VALUES[@]}" \
      --arg  p99_med     "$P99_MED"          \
      --arg  p99_min     "$P99_MIN"          \
      --arg  p99_max     "$P99_MAX"          \
      --arg  score_med   "$SCORE_MED"        \
      --arg  sp99_med    "$SP99_MED"         \
      --arg  sdet_med    "$SDET_MED"         \
      --arg  tp_med      "$TP_MED"           \
      --arg  fp_med      "$FP_MED"           \
      --arg  tn_med      "$TN_MED"           \
      --arg  fn_med      "$FN_MED"           \
      --arg  fr_med      "$FAILRATE_MED"     \
      --argjson cut_p99  "$CUT_P99_ANY"      \
      --argjson cut_det  "$CUT_DET_ANY"      \
      --argjson p99_list "$(printf '%s\n' "${P99_VALUES[@]:-}" | jq -R 'select(length>0)|tonumber' | jq -s '.')" \
      '{
        runs: $runs,
        p99_ms: {
          median: ($p99_med | tonumber?), min: ($p99_min | tonumber?),
          max:    ($p99_max | tonumber?), samples: $p99_list
        },
        p99: (if $p99_med != "N/A" then (($p99_med | tonumber | . * 10 | round | . / 10 | tostring) + "ms") else null end),
        scoring: {
          p99_score:       { value: ($sp99_med  | tonumber?), cut_triggered: $cut_p99 },
          detection_score: { value: ($sdet_med  | tonumber?), cut_triggered: $cut_det },
          final_score:     ($score_med | tonumber?),
          failure_rate:    ($fr_med    | tonumber?),
          breakdown: {
            true_positive_detections:  ($tp_med | tonumber?),
            false_positive_detections: ($fp_med | tonumber?),
            true_negative_detections:  ($tn_med | tonumber?),
            false_negative_detections: ($fn_med | tonumber?)
          }
        },
        final_score_median: ($score_med | tonumber?)
      }' \
      > "$RUN_DIR/aggregate.json" 2>/dev/null || true

    SUMMARY_ROWS+=("$(printf '%-6s %-7s %-6s %-10s %-10s %-10s %-12s' \
      "$K" "$NPROBE" "${#P99_VALUES[@]}" "$P99_MED" "$P99_MIN" "$P99_MAX" "$SCORE_MED")")
  done
done

# ── Sumário ───────────────────────────────────────────────────────────────────
sep
echo ""
echo "=== SUMÁRIO DO BENCHMARK (mediana de $RUNS boots frios) ==="
printf "%-6s %-7s %-6s %-10s %-10s %-10s %-12s\n" \
  "K" "nprobe" "runs" "p99_med" "p99_min" "p99_max" "score_med"
echo "──────────────────────────────────────────────────────────────────────────"
for row in "${SUMMARY_ROWS[@]}"; do echo "$row"; done

echo ""
echo "Spread (p99_max - p99_min) apertado = artefato estável = seguro no tiro único da rinha."
echo "Resultados por rodada e aggregate.json em: $RESULTS_DIR/"
