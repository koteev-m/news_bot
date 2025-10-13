#!/usr/bin/env bash
set -euo pipefail
echo "[INFO] Restoring PRIMARY health & traffic..."
echo "[STEP] 1) Confirm primary cluster healthy: /healthz OK, SLO < threshold"
echo "[STEP] 2) terraform apply to set PRIMARY as healthy endpoint"
echo "[STEP] 3) ArgoCD: newsbot-primary Synced, newsbot-secondary Alive as standby"
