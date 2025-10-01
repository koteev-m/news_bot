#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $(basename "$0") on|off" >&2
}

if [[ $# -ne 1 ]]; then
  usage
  exit 1
fi

ACTION=$(echo "$1" | tr '[:upper:]' '[:lower:]')

if [[ "$ACTION" != "on" && "$ACTION" != "off" ]]; then
  usage
  exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
COMPOSE_FILE="$REPO_ROOT/deploy/compose/docker-compose.bluegreen.yml"
TOGGLE_FILE="$REPO_ROOT/deploy/compose/nginx/conf.d/maintenance_toggle.conf"

if [[ ! -f "$COMPOSE_FILE" ]]; then
  echo "[error] compose file not found: $COMPOSE_FILE" >&2
  exit 1
fi

if [[ ! -f "$TOGGLE_FILE" ]]; then
  echo "[error] toggle file not found: $TOGGLE_FILE" >&2
  exit 1
fi

TMP_FILE=$(mktemp "$(dirname "$TOGGLE_FILE")/maintenance_toggle.XXXXXX")
trap 'rm -f "$TMP_FILE"' EXIT

printf 'set $maintenance %s;\n' "$ACTION" >"$TMP_FILE"

mv "$TMP_FILE" "$TOGGLE_FILE"
trap - EXIT

echo "[info] maintenance mode set to $ACTION"

docker compose -f "$COMPOSE_FILE" ps

docker compose -f "$COMPOSE_FILE" exec nginx nginx -s reload

docker compose -f "$COMPOSE_FILE" exec nginx wget -qO- http://127.0.0.1/healthz >/dev/null

docker compose -f "$COMPOSE_FILE" exec nginx wget -qO- http://127.0.0.1/metrics >/dev/null

echo "[info] /healthz and /metrics remain reachable"
