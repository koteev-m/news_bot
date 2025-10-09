#!/usr/bin/env bash
set -euo pipefail
echo "[INFO] Promote to GA (100% GREEN)"
bash tools/release/switch_traffic.sh GREEN 0
bash tools/release/postdeploy_verify.sh
echo "[OK] GA promote verified"
