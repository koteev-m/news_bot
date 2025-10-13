#!/usr/bin/env bash
set -euo pipefail
ACTION="${1:?promote|abort|scale}"
NS="${NS:-newsbot-staging}"
ROLLOUT="${ROLLOUT:-newsbot-newsbot}"

case "$ACTION" in
  promote) kubectl-argo-rollouts promote "$ROLLOUT" -n "$NS" ;;
  abort)   kubectl-argo-rollouts abort "$ROLLOUT" -n "$NS" ;;
  scale)
    REPLICAS="${REPLICAS:-3}"
    kubectl -n "$NS" scale rollout "$ROLLOUT" --replicas="$REPLICAS"
    ;;
  *) echo "Usage: remediate.sh promote|abort|scale"; exit 2;;
esac

kubectl-argo-rollouts get rollout "$ROLLOUT" -n "$NS"
