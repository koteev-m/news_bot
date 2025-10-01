#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage: $(basename "$0") BLUE|GREEN <canary_weight>
  BLUE|GREEN     Primary environment receiving 100% of the steady traffic.
  canary_weight  Integer 0..50 defining the secondary weight for the other environment.
USAGE
}

if [[ $# -ne 2 ]]; then
  usage >&2
  exit 1
fi

TARGET=$(echo "$1" | tr '[:lower:]' '[:upper:]')
CANARY_WEIGHT="$2"

if [[ "$TARGET" != "BLUE" && "$TARGET" != "GREEN" ]]; then
  echo "[error] target must be BLUE or GREEN" >&2
  usage >&2
  exit 1
fi

if ! [[ "$CANARY_WEIGHT" =~ ^[0-9]+$ ]]; then
  echo "[error] canary weight must be an integer" >&2
  exit 1
fi

if (( CANARY_WEIGHT < 0 || CANARY_WEIGHT > 50 )); then
  echo "[error] canary weight must be between 0 and 50" >&2
  exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
COMPOSE_FILE="$REPO_ROOT/deploy/compose/docker-compose.bluegreen.yml"
WEIGHTS_FILE="$REPO_ROOT/deploy/compose/nginx/conf.d/upstream_weights.conf"

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "[error] compose file not found: $COMPOSE_FILE" >&2
  exit 1
fi

if [[ ! -f "$WEIGHTS_FILE" ]]; then
  echo "[error] weights file not found: $WEIGHTS_FILE" >&2
  exit 1
fi

case "$TARGET" in
  BLUE)
    BLUE_WEIGHT=100
    GREEN_WEIGHT=$CANARY_WEIGHT
    ;;
  GREEN)
    BLUE_WEIGHT=$CANARY_WEIGHT
    GREEN_WEIGHT=100
    ;;
  *)
    echo "[error] unsupported target $TARGET" >&2
    exit 1
    ;;
esac

TMP_FILE=$(mktemp "$(dirname "$WEIGHTS_FILE")/upstream_weights.XXXXXX")
trap 'rm -f "$TMP_FILE"' EXIT

cat <<CONFIG >"$TMP_FILE"
server app_blue:8080 weight=$BLUE_WEIGHT;
server app_green:8080 weight=$GREEN_WEIGHT;
CONFIG

mv "$TMP_FILE" "$WEIGHTS_FILE"
trap - EXIT

echo "[info] updated weights: app_blue=$BLUE_WEIGHT app_green=$GREEN_WEIGHT"

docker compose -f "$COMPOSE_FILE" ps

docker compose -f "$COMPOSE_FILE" exec nginx nginx -s reload

docker compose -f "$COMPOSE_FILE" exec nginx wget -qO- http://127.0.0.1/healthz >/dev/null

echo "[info] nginx reload complete and healthz reachable"
