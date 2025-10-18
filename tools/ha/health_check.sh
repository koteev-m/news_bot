#!/usr/bin/env bash
set -euo pipefail
HOST="${HOSTNAME:?}"
echo "[INFO] Checking ${HOST}/healthz"
code=$(curl -sS -o /dev/null -w '%{http_code}' "https://${HOST}/healthz" || echo 000)
echo "HTTP ${code}"
[ "$code" = "200" ] || exit 1
