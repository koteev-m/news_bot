#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $(basename "$0") <new_primary>" >&2
  exit 1
fi

NEW_PRIMARY="$1"
if [[ -z "$NEW_PRIMARY" ]]; then
  echo "New primary secret must not be empty" >&2
  exit 1
fi

ENV_FILE="${ENV_FILE:-deploy/compose/.env}"
COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.yml}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ENV file not found: $ENV_FILE" >&2
  exit 1
fi

CURRENT_PRIMARY=$(grep -E '^JWT_SECRET_PRIMARY=' "$ENV_FILE" | tail -n1 | cut -d '=' -f2-)
if [[ -z "$CURRENT_PRIMARY" ]]; then
  echo "JWT_SECRET_PRIMARY is not defined in $ENV_FILE" >&2
  exit 1
fi

TMP_FILE=$(mktemp)
trap 'rm -f "$TMP_FILE"' EXIT

SECONDARY_UPDATED=false
PRIMARY_UPDATED=false

while IFS= read -r line || [[ -n "$line" ]]; do
  case "$line" in
    JWT_SECRET_PRIMARY=*)
      echo "JWT_SECRET_PRIMARY=$NEW_PRIMARY" >>"$TMP_FILE"
      PRIMARY_UPDATED=true
      ;;
    JWT_SECRET_SECONDARY=*)
      echo "JWT_SECRET_SECONDARY=$CURRENT_PRIMARY" >>"$TMP_FILE"
      SECONDARY_UPDATED=true
      ;;
    *)
      echo "$line" >>"$TMP_FILE"
      ;;
  esac
done <"$ENV_FILE"

if [[ "$SECONDARY_UPDATED" = false ]]; then
  echo "JWT_SECRET_SECONDARY=$CURRENT_PRIMARY" >>"$TMP_FILE"
fi

if [[ "$PRIMARY_UPDATED" = false ]]; then
  echo "JWT_SECRET_PRIMARY=$NEW_PRIMARY" >>"$TMP_FILE"
fi

mv "$TMP_FILE" "$ENV_FILE"
trap - EXIT

unset TMP_FILE

echo "[rotate_jwt_secret] Updated secrets in $ENV_FILE (primary -> secondary, new primary applied)."

echo "[rotate_jwt_secret] Reloading application stack via docker compose."
docker compose -f "$COMPOSE_FILE" up -d --force-recreate

echo "[rotate_jwt_secret] Rotation staged. Keep JWT_SECRET_SECONDARY for 24-48h before final cleanup."
