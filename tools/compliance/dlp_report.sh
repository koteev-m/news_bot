#!/usr/bin/env bash
set -euo pipefail
python3 tools/compliance/dlp_scan.py
jq -r '
  ["# DLP Report", "", "- Timestamp: \(.timestamp)", "- Total: \(.total)", ""],
  ["| Severity | Rule | File | Match |"],
  ["|---|---|---|---|"],
  (.findings[] | "| " + .severity + " | " + .rule + " | " + (.file|tostring) + " | `" + (.match|tostring) + "` |")
  ' dlp_report.json | tee dlp_report.md
if jq '.total' dlp_report.json | awk '$1>0{exit 1}'; then echo "[OK] No sensitive data detected"; fi
