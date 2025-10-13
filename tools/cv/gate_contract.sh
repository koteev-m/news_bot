#!/usr/bin/env bash
set -euo pipefail
NEED_DIFF="${NEED_DIFF:-true}"
[ "$NEED_DIFF" = "true" ] || { echo "[SKIP] Contract diff disabled"; exit 0; }

# Требует файл docs/api/openapi.yaml в текущей ветке и main
git fetch origin main --depth=1 >/dev/null 2>&1 || true
if git show origin/main:docs/api/openapi.yaml >/dev/null 2>&1; then
  mkdir -p .cv
  git show origin/main:docs/api/openapi.yaml > .cv/openapi-base.yaml
  redocly lint docs/api/openapi.yaml
  redocly diff .cv/openapi-base.yaml docs/api/openapi.yaml --fail-on-errors
  echo "[OK] OpenAPI diff passed"
else
  echo "[WARN] No base openapi.yaml on main — skipping diff"
fi
