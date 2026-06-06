#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# Loop PGO (Issue 05): gera src/main/resources/pgo/default.iprof.
#
# Por que em container: PGO exige Oracle GraalVM (o estágio native-build do
# Dockerfile), e não há GraalVM local. Buildamos a imagem INSTRUMENTADA, rodamos
# o binário sob carga K6 real (smoke + profile de carga), e o native-image escreve
# o default.iprof no shutdown. Esse perfil, committado, alimenta o --pgo do build
# de produção (default do ARG NATIVE_IMAGE_PGO no Dockerfile).
#
# Uso:   ./pgo-profile.sh
# Saída: src/main/resources/pgo/default.iprof
# Pré-requisitos: docker, docker compose, curl
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# Config — espelha a config campeã do benchmark de fechamento.
DTYPE="${DTYPE:-i16}"
NUM_CLUSTERS="${NUM_CLUSTERS:-2048}"
NPROBE="${NPROBE:-2}"
IMAGE="scadufax-thoth:pgo-instrument"
CONTAINER="pgo-gen"
READY_URL="http://localhost:9999/ready"
READY_TIMEOUT="${READY_TIMEOUT:-90}"
PROFILE_OUT="src/main/resources/pgo/default.iprof"
STOP_GRACE="${STOP_GRACE:-30}"   # tempo p/ o shutdown hook flushar o iprof

log() { echo "[$(date '+%H:%M:%S')] $*"; }

cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT

wait_ready() {
  local deadline=$((SECONDS + READY_TIMEOUT))
  log "Aguardando API em $READY_URL..."
  while [ $SECONDS -lt $deadline ]; do
    if curl -sf "$READY_URL" >/dev/null 2>&1; then
      log "API pronta."
      return 0
    fi
    sleep 2
  done
  log "ERRO: API não ficou pronta em ${READY_TIMEOUT}s."
  return 1
}

# ── 1. Build instrumentado (Oracle GraalVM + --pgo-instrument) ────────────────
log "Buildando imagem instrumentada (DTYPE=$DTYPE K=$NUM_CLUSTERS)..."
docker build \
  --build-arg NATIVE_IMAGE_PGO=--pgo-instrument \
  --build-arg DTYPE="$DTYPE" \
  --build-arg NUM_CLUSTERS="$NUM_CLUSTERS" \
  -t "$IMAGE" .

# ── 2. Sobe o binário instrumentado (sem cpuset: queremos cobertura ampla) ────
cleanup
log "Subindo container instrumentado..."
docker run -d --name "$CONTAINER" -p 9999:9999 "$IMAGE" >/dev/null
wait_ready

# ── 3. Carga real: smoke (contrato) + profile de carga (cobertura de hot path) ─
# O smoke valida o contrato; o profile 'test' (ramp 120s) popula o perfil com o
# hot path de busca sob carga representativa — bem mais rico que as 5 iterações
# do smoke isolado.
log "Carga smoke..."
( cd test && docker compose --profile smoke run --rm k6-smoke ) || true

log "Carga representativa (profile test, ramp ~120s)..."
NPROBE="$NPROBE" VUS="${VUS:-30}" MAX_VUS="${MAX_VUS:-50}" RATE="${RATE:-900}" \
  bash -c 'cd test && docker compose --profile test run --rm k6' || true

# ── 4. Shutdown gracioso → native-image flusha o default.iprof no CWD (/) ──────
log "Parando container (grace ${STOP_GRACE}s) para flushar o perfil..."
docker stop -t "$STOP_GRACE" "$CONTAINER" >/dev/null

# ── 5. Extrai o perfil (docker cp funciona em distroless, sem shell) ──────────
mkdir -p "$(dirname "$PROFILE_OUT")"
log "Copiando default.iprof para $PROFILE_OUT..."
docker cp "$CONTAINER:/default.iprof" "$PROFILE_OUT"

if [ ! -s "$PROFILE_OUT" ]; then
  log "ERRO: $PROFILE_OUT vazio ou ausente. O shutdown hook flushou o perfil?"
  exit 1
fi

log "OK: $PROFILE_OUT gerado ($(wc -c < "$PROFILE_OUT") bytes)."
log "Próximo passo: git add $PROFILE_OUT && docker build . (default usa --pgo)."
