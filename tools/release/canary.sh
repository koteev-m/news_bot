#!/usr/bin/env bash
set -euo pipefail
PCT="${1:-10}"
if ! [[ "$PCT" =~ ^[0-9]+$ ]] || [ "$PCT" -lt 0 ] || [ "$PCT" -gt 50 ]; then
  echo "Usage: canary.sh <0..50>"; exit 2
fi
echo "[INFO] Enabling CANARY: GREEN=${PCT}%"
bash tools/release/switch_traffic.sh GREEN "$PCT"
echo "[INFO] Verifying canary..."
bash tools/release/postdeploy_verify.sh
echo "[OK] Canary deployed and verified"
