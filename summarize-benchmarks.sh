#!/usr/bin/env bash
# Lê todos os resultados de benchmark e exibe tabela resumida ordenada por final_score desc.
set -euo pipefail

RESULTS_DIR="${1:-benchmark-results}"

if ! command -v jq &>/dev/null; then
  echo "Erro: jq não encontrado. Instale com: sudo apt install jq" >&2
  exit 1
fi

# Cores ANSI
BOLD='\033[1m'
CYAN='\033[36m'
GREEN='\033[32m'
YELLOW='\033[33m'
RED='\033[31m'
RESET='\033[0m'

# Coleta dados: aggregate.json enriquecido > results_run*.json > results.json (legado)
collect_rows() {
  for dir in "$RESULTS_DIR"/K*/; do
    local name
    name=$(basename "$dir")
    local K nprobe
    K=$(echo "$name" | grep -oP 'K\K[0-9]+')
    nprobe=$(echo "$name" | grep -oP 'nprobe\K[0-9]+')

    local json=""
    if [[ -f "$dir/aggregate.json" ]] && jq -e '.scoring.breakdown' "$dir/aggregate.json" >/dev/null 2>&1; then
      json="$dir/aggregate.json"
    else
      for f in "$dir"/results_run*.json; do
        [[ -f "$f" ]] && json="$f" && break
      done
      [[ -z "$json" && -f "$dir/results.json" ]] && json="$dir/results.json"
    fi
    [[ -z "$json" ]] && continue

    jq -r --arg K "$K" --arg nprobe "$nprobe" '
      .scoring as $s |
      .scoring.breakdown as $b |
      ($b.true_positive_detections  // 0) as $tp |
      ($b.false_positive_detections // 0) as $fp |
      ($b.true_negative_detections  // 0) as $tn |
      ($b.false_negative_detections // 0) as $fn |

      # Precision, Recall, F1
      (if ($tp + $fp) > 0 then $tp / ($tp + $fp) else 0 end) as $precision |
      (if ($tp + $fn) > 0 then $tp / ($tp + $fn) else 0 end) as $recall |
      (if ($precision + $recall) > 0
        then 2 * $precision * $recall / ($precision + $recall)
        else 0 end) as $f1 |

      # False positive rate = FP / (FP + TN)
      (if ($fp + $tn) > 0 then $fp / ($fp + $tn) else 0 end) as $fpr |

      # Cut flags
      (if ($s.p99_score.cut_triggered        // false) then "Y" else "N" end) as $cut_p99 |
      (if ($s.detection_score.cut_triggered  // false) then "Y" else "N" end) as $cut_det |

      # p99: prefere string .p99 (presente em results_run*.json e aggregate enriquecido),
      # depois calcula de .p99_ms.median (aggregate antigo)
      (.p99 // (if .p99_ms.median then (.p99_ms.median | tostring) + "ms" else "N/A" end)) as $p99_disp |

      [$K,
       $nprobe,
       $p99_disp,
       (($s.p99_score.value       // 0) | . * 100 | round | . / 100 | tostring),
       (($s.detection_score.value // 0) | . * 100 | round | . / 100 | tostring),
       (($s.final_score            // 0) | . * 100 | round | . / 100 | tostring),
       ($fp | tostring),
       ($fn | tostring),
       ($s.failure_rate // "N/A"),
       ($recall   * 100 | . * 100 | round | . / 100 | tostring),
       ($precision* 100 | . * 100 | round | . / 100 | tostring),
       ($f1       * 100 | . * 100 | round | . / 100 | tostring),
       ($fpr      * 100 | . * 100 | round | . / 100 | tostring),
       $cut_p99,
       $cut_det
      ] | @tsv
    ' "$json"
  done
}

# Cabeçalho
print_header() {
  printf "${BOLD}${CYAN}"
  printf "%-6s  %-7s  %-10s  %-10s  %-10s  %-12s  %-4s  %-4s  %-8s  %-8s  %-9s  %-6s  %-6s  %-7s  %-7s\n" \
    "K" "nprobe" "p99" "score_p99" "score_det" "final_score" \
    "FP" "FN" "fail%" \
    "recall%" "precis%" "F1%" "FPR%" \
    "cut_p99" "cut_det"
  printf "${RESET}"
  printf '%0.s─' {1..140}; echo
}

# Linha de dados com destaque para melhores scores
print_row() {
  local K=$1 nprobe=$2 p99=$3 sp99=$4 sdet=$5 fscore=$6
  local fp=$7 fn=$8 failrate=$9
  local recall=${10} prec=${11} f1=${12} fpr=${13}
  local cutp=${14} cutd=${15}

  local color="$RESET"
  # Verde se final_score >= 2800, amarelo >= 2600
  local fscore_int
  fscore_int=$(echo "$fscore" | cut -d. -f1)
  if   (( fscore_int >= 2800 )); then color="$GREEN"
  elif (( fscore_int >= 2600 )); then color="$YELLOW"
  fi

  printf "${color}%-6s  %-7s  %-10s  %-10s  %-10s  %-12s  %-4s  %-4s  %-8s  %-8s  %-9s  %-6s  %-6s  %-7s  %-7s${RESET}" \
    "$K" "$nprobe" "$p99" "$sp99" "$sdet" "$fscore" \
    "$fp" "$fn" "$failrate" \
    "$recall" "$prec" "$f1" "$fpr" \
    "$cutp" "$cutd"
  [[ "$cutp" == "Y" || "$cutd" == "Y" ]] && printf "${RED} ⚠${RESET}"
  printf "\n"
}

# Coleta, ordena por final_score desc (campo 6) e imprime
echo
echo -e "${BOLD}Benchmark Summary — $(date '+%Y-%m-%d %H:%M')${RESET}"
echo -e "Diretório: ${RESULTS_DIR}"
echo

print_header

# Ordena por final_score descrescente (campo 6, numérico)
collect_rows \
  | sort -t$'\t' -k6 -rn \
  | while IFS=$'\t' read -r K nprobe p99 sp99 sdet fscore fp fn failrate recall prec f1 fpr cutp cutd; do
      print_row "$K" "$nprobe" "$p99" "$sp99" "$sdet" "$fscore" \
                "$fp" "$fn" "$failrate" "$recall" "$prec" "$f1" "$fpr" "$cutp" "$cutd"
    done

echo
echo -e "${BOLD}Legenda:${RESET}"
echo "  FP/FN       — falsos positivos / falsos negativos absolutos"
echo "  fail%       — taxa de erros HTTP (failure_rate)"
echo "  recall%     — % dos fraudes reais detectados  (TP / (TP+FN))"
echo "  precis%     — % dos alertas que eram fraudes  (TP / (TP+FP))"
echo "  F1%         — média harmônica de recall e precision"
echo "  FPR%        — taxa de alarme falso em transações legítimas  (FP / (FP+TN))"
echo "  cut_p99/det — se o corte de penalidade máxima foi ativado (Y = score zerado)"
echo
echo -e "Cores: ${GREEN}●${RESET} final_score ≥ 2800  ${YELLOW}●${RESET} ≥ 2600  ${RESET}● < 2600"
echo
