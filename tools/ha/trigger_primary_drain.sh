#!/usr/bin/env bash
set -euo pipefail
# Симулирует деградацию PRIMARY, чтобы Route53 health-check переключил трафик.
# Требуется: kubectl контекст PRIMARY в KUBECONFIG_PRIMARY, namespace, имя gateway/ingress/rollout.
# Вариант A: scale ingressgateway до 0 (или rollout app до 0) → healthz 503.
# Вариант B: добавить failing VirtualService (не используется тут).

NS="${NS:-istio-system}"
GW_DEPLOY="${GW_DEPLOY:-istio-ingressgateway}"
KUBECONFIG_PRIMARY="${KUBECONFIG_PRIMARY:?path to kubeconfig for primary cluster}"

export KUBECONFIG="${KUBECONFIG_PRIMARY}"
echo "[INFO] Scaling ${GW_DEPLOY} in ${NS} to 0 replicas on PRIMARY…"
kubectl -n "${NS}" scale deploy "${GW_DEPLOY}" --replicas=0
kubectl -n "${NS}" rollout status deploy "${GW_DEPLOY}" --timeout=120s || true
echo "[WARN] PRIMARY ingress drained. DNS failover should start shortly."
