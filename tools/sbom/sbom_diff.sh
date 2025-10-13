#!/usr/bin/env bash
set -euo pipefail
BASE_REF="${BASE_REF:-origin/main}"
OUT=".sbom"
mkdir -p "$OUT"

# install syft
if ! command -v syft >/dev/null 2>&1; then
  curl -sSfL https://raw.githubusercontent.com/anchore/syft/main/install.sh | sh -s -- -b /usr/local/bin
fi

git fetch --depth=1 "$BASE_REF" || true
# current
syft dir:. -o spdx-json > "$OUT/sbom.cur.json"
# base
TMP=$(mktemp -d)
git show "${BASE_REF}:." >/dev/null 2>&1 || true
git archive "$BASE_REF" | tar -x -C "$TMP"
syft dir:"$TMP" -o spdx-json > "$OUT/sbom.base.json"

# simple diff: package names changed
CUR_PKGS=$(jq -r '.packages[].name' "$OUT/sbom.cur.json" | sort | uniq)
BASE_PKGS=$(jq -r '.packages[].name' "$OUT/sbom.base.json" | sort | uniq)

ADDED=$(comm -13 <(echo "$BASE_PKGS") <(echo "$CUR_PKGS"))
REMOVED=$(comm -23 <(echo "$BASE_PKGS") <(echo "$CUR_PKGS"))

echo "# SBOM Diff" > "$OUT/diff.md"
echo "## Added packages" >> "$OUT/diff.md"
echo "${ADDED:-<none>}" | sed 's/^/- /' >> "$OUT/diff.md"
echo "## Removed packages" >> "$OUT/diff.md"
echo "${REMOVED:-<none>}" | sed 's/^/- /' >> "$OUT/diff.md"

echo "[OK] SBOM diff at $OUT/diff.md"
