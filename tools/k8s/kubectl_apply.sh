#!/usr/bin/env bash
set -euo pipefail
FILE="${1:-k8s/rendered/all.yaml}"
kubectl apply -f "$FILE"
