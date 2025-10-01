#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $(basename "$0") <new_secret>" >&2
  exit 1
fi

NEW_SECRET="$1"
if [[ -z "$NEW_SECRET" ]]; then
  echo "New webhook secret must not be empty" >&2
  exit 1
fi

: "${TELEGRAM_BOT_TOKEN:?TELEGRAM_BOT_TOKEN must be exported}" 
: "${TELEGRAM_WEBHOOK_URL:?TELEGRAM_WEBHOOK_URL must be exported}"

ENV_FILE="${ENV_FILE:-deploy/compose/.env}"
COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.yml}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ENV file not found: $ENV_FILE" >&2
  exit 1
fi

TMP_FILE=$(mktemp)
trap 'rm -f "$TMP_FILE"' EXIT
SECRET_UPDATED=false

while IFS= read -r line || [[ -n "$line" ]]; do
  case "$line" in
    TELEGRAM_WEBHOOK_SECRET=*)
      echo "TELEGRAM_WEBHOOK_SECRET=$NEW_SECRET" >>"$TMP_FILE"
      SECRET_UPDATED=true
      ;;
    *)
      echo "$line" >>"$TMP_FILE"
      ;;
  esac
done <"$ENV_FILE"

if [[ "$SECRET_UPDATED" = false ]]; then
  echo "TELEGRAM_WEBHOOK_SECRET=$NEW_SECRET" >>"$TMP_FILE"
fi

mv "$TMP_FILE" "$ENV_FILE"
trap - EXIT

curl -fsS "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
  --data-urlencode "url=${TELEGRAM_WEBHOOK_URL}" \
  --data-urlencode "secret_token=${NEW_SECRET}" >/dev/null

echo "[rotate_webhook_secret] Updated Telegram webhook and stored secret in $ENV_FILE."

echo "[rotate_webhook_secret] Reloading application stack via docker compose."
docker compose -f "$COMPOSE_FILE" up -d --force-recreate

echo "[rotate_webhook_secret] Rotation completed. Run smoke webhook checks to confirm delivery."
