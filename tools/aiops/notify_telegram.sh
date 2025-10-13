#!/usr/bin/env bash
set -euo pipefail
BOT="${BOT_TOKEN:?}"
CHAT="${CHAT_ID:?}"
TEXT="${1:-"AIOps report"}"

curl -sS -X POST "https://api.telegram.org/bot${BOT}/sendMessage" \
  -d "chat_id=${CHAT}" \
  -d "text=${TEXT}" \
  -d "parse_mode=Markdown" >/dev/null && echo "[OK] telegram sent"
