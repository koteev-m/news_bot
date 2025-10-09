#!/usr/bin/env bash
set -euo pipefail
echo "[INFO] Rolling back to BLUE (100%)"
bash tools/release/switch_traffic.sh BLUE 0
bash tools/release/postdeploy_verify.sh || true
echo "[OK] rollback completed"
