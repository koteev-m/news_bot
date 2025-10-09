#!/usr/bin/env bash
set -euo pipefail

red(){ printf '\033[31m%s\033[0m\n' "$*"; }
green(){ printf '\033[32m%s\033[0m\n' "$*"; }

echo "[INFO] Trivy FS scan"
trivy fs --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed --format table --exit-code 0 --timeout 5m --ignorefile .trivyignore .

echo "[INFO] Build image"
docker build -t newsbot-app:local -f Dockerfile .

echo "[INFO] Trivy Image scan"
trivy image --severity HIGH,CRITICAL --ignore-unfixed --format table --exit-code 0 --timeout 5m newsbot-app:local

echo "[INFO] Trivy Config scan"
trivy config --severity HIGH,CRITICAL --format table --exit-code 0 --timeout 5m .

green "[OK] Trivy local scans completed"
