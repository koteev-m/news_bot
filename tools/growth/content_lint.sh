#!/usr/bin/env bash
set -euo pipefail
FILE="${1:?markdown template required}"
# простые проверки: длина строки, запрещённые токены, невалидные ссылки
if grep -nP '.{401,}' "$FILE" ; then echo "[FAIL] lines > 400 chars"; exit 1; fi
if grep -niE '(password|token=|apikey=)' "$FILE"; then echo "[FAIL] secret-like tokens in content"; exit 1; fi
# ссылки (http/https) — опционально, проверка формата
grep -oE '(https?://[^ )]+)' "$FILE" | while read -r L; do
  case "$L" in
    http://*|https://*) : ;;
    *) echo "[FAIL] bad link: $L"; exit 1 ;;
  esac
done
echo "[OK] content lint passed"
