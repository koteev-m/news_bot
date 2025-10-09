#!/usr/bin/env bash
set -euo pipefail

red(){ printf '\033[31m%s\033[0m\n' "$*"; }
green(){ printf '\033[32m%s\033[0m\n' "$*"; }
yellow(){ printf '\033[33m%s\033[0m\n' "$*"; }

run_gitleaks() {
  if command -v gitleaks >/dev/null 2>&1; then
    echo "[INFO] Running local gitleaks binary"
    gitleaks detect --redact --config .gitleaks.toml --verbose
  else
    echo "[INFO] Using docker image gitleaks/gitleaks:latest"
    docker run --rm -v "$PWD":/repo -w /repo gitleaks/gitleaks:latest \
      detect --redact --config /repo/.gitleaks.toml --verbose
  fi
}

if run_gitleaks; then
  green "[OK] Gitleaks scan completed"
else
  red "[ERROR] Gitleaks detected potential secrets. Review findings above."
  exit 1
fi
