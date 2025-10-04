#!/usr/bin/env bash
set -euo pipefail
# Requires gh CLI logged in; applies labels from .github/labels.yml
if ! command -v gh >/dev/null; then echo "gh CLI required"; exit 2; fi
repo="${1:-$(gh repo view --json nameWithOwner -q .nameWithOwner)}"
while IFS= read -r line; do
  name=$(echo "$line" | awk -F':' '{print $1}')
  color=$(echo "$line" | awk -F':' '{print $2}')
  gh label create "$name" --color "$color" --force --repo "$repo" >/dev/null || true
done < <(yq -r '.labels[] | "\(.name):\(.color)"' .github/labels.yml)
echo "[OK] labels applied to $repo"
