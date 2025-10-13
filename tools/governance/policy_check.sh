#!/usr/bin/env bash
set -euo pipefail
# Требуется conftest и Helm
CHART="${CHART:-helm/newsbot}"
OUT_RENDER="${OUT_RENDER:-.governance/rendered.yaml}"
helm template newsbot "$CHART" -f helm/newsbot/values.yaml > "$OUT_RENDER"
echo "[INFO] Running conftest…"
conftest test "$OUT_RENDER" -p governance/policies
