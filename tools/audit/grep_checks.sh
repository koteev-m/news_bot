#!/usr/bin/env bash
set -euo pipefail

status=0
red()   { printf "\033[31m%s\033[0m\n" "$*"; }
green() { printf "\033[32m%s\033[0m\n" "$*"; }
yellow(){ printf "\033[33m%s\033[0m\n" "$*"; }

echo "== Grep checks: conflicts / TODO / wildcards / secrets / miniapp JWT =="

# 1) Merge-conflict markers (FAIL)
if git grep -nE '^(<<<<<<<|=======|>>>>>>>)' -- . ':!**/.github/**' >/dev/null; then
  red   "[FAIL] merge-conflict markers found"
  status=1
else
  green "[OK]   no merge-conflict markers"
fi

# 2) TODO/FIXME/HACK (WARN)
if git grep -nE '\b(TODO|FIXME|HACK)\b' \
  -- '**/*.*' ':!**/node_modules/**' ':!**/build/**' >/dev/null; then
  yellow "[WARN] TODO/FIXME/HACK found (review required)"
else
  green  "[OK]   no TODO/FIXME/HACK"
fi

# 3) Wildcard imports in Kotlin (FAIL)
if git grep -nE 'import\s+\*' -- '**/*.kt' ':!**/build/**' >/dev/null; then
  red   "[FAIL] wildcard imports detected (*.kt)"
  status=1
else
  green "[OK]   no wildcard imports (*.kt)"
fi

# 4) Potential secrets/tokens in code/logs (WARN)
if git grep -nE '(Bearer\s+[A-Za-z0-9._-]+|X-Telegram-Bot-Api-Secret-Token|initData=|bot[ _-]?token)' \
  -- '**/*.*' ':!**/node_modules/**' ':!**/build/**' ':!**/docs/**' >/dev/null; then
  yellow "[WARN] potential secrets/log leaks found (verify masking in logback.xml)"
else
  green  "[OK]   no obvious secret-like strings"
fi

# 5) JWT must NOT be stored in localStorage in miniapp (FAIL)
if git grep -nE 'localStorage\.setItem[^)]*JWT' -- 'miniapp/**/*.ts*' >/dev/null; then
  red   "[FAIL] JWT stored in localStorage in miniapp"
  status=1
else
  green "[OK]   JWT not stored in localStorage (miniapp)"
fi

exit "${status}"
