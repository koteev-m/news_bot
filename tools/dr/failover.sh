#!/usr/bin/env bash
set -euo pipefail
echo "[WARN] Triggering DNS failover to SECONDARY..."
echo "[INFO] Ensure ArgoCD app 'newsbot-secondary' is Synced & Healthy."
kubectl -n argocd get app newsbot-secondary
echo "[INFO] Switching traffic by disabling PRIMARY health or editing Route53 records (terraform apply)."
echo "[HINT] To force failover: set health check to failed or swap record via terraform."
