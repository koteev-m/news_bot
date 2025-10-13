#!/usr/bin/env bash
set -euo pipefail
BASE_PRIMARY="${BASE_PRIMARY:?PRIMARY base required}"
BASE_SECONDARY="${BASE_SECONDARY:?SECONDARY base required}"
echo "[Primary] $BASE_PRIMARY -> $(curl -sS -o /dev/null -w '%{http_code}' "$BASE_PRIMARY/healthz")"
echo "[Secondary] $BASE_SECONDARY -> $(curl -sS -o /dev/null -w '%{http_code}' "$BASE_SECONDARY/healthz")"
