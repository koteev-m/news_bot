#!/usr/bin/env bash
set -euo pipefail
BASE_URL="${BASE_URL:?}"
CFG="${CFG:?cv/thresholds.yml}"

need=$(yq -r '.performance.lhci.enabled' "$CFG")
[ "$need" = "true" ] || { echo "[SKIP] LHCI disabled"; exit 0; }

perf=$(yq -r '.performance.lhci.min_scores.performance' "$CFG")
a11y=$(yq -r '.performance.lhci.min_scores.accessibility' "$CFG")

npm i -g @lhci/cli@0.13.0 >/dev/null 2>&1
lhci collect --url="${BASE_URL}" --numberOfRuns=2 --settings.locale=en --settings.preset=desktop --outputPath=./.lhci_tmp.json >/dev/null
SCORE_PERF=$(jq -r '.[0].categories.performance.score' ./.lhci_tmp.json)
SCORE_A11Y=$(jq -r '.[0].categories.accessibility.score' ./.lhci_tmp.json)

awk -v v="$SCORE_PERF" -v m="$perf" 'BEGIN{ if(v+0<m+0){ printf("LHCI perf %f < %f\n",v,m); exit 1} }'
awk -v v="$SCORE_A11Y" -v m="$a11y" 'BEGIN{ if(v+0<m+0){ printf("LHCI a11y %f < %f\n",v,m); exit 1} }'
echo "[OK] LHCI perf=${SCORE_PERF} a11y=${SCORE_A11Y}"
