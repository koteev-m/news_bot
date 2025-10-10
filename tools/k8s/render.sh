#!/usr/bin/env bash
set -euo pipefail
CHART="helm/newsbot"
OUT="k8s/rendered"
REL="${1:-newsbot}"
NS="${2:-newsbot-staging}"
VALUES="${3:-helm/newsbot/values.yaml}"

mkdir -p "$OUT"
helm template "$REL" "$CHART" --namespace "$NS" -f "$VALUES" > "$OUT/all.yaml"
echo "[OK] rendered to $OUT/all.yaml"
