#!/usr/bin/env bash
set -euo pipefail

IMAGE_REF="${IMAGE_REF:-ghcr.io/ORG/REPO:latest}"
OUT_DIR="${OUT_DIR:-.sbom}"
mkdir -p "$OUT_DIR"

# Install syft
if ! command -v syft >/dev/null 2>&1; then
  curl -sSfL https://raw.githubusercontent.com/anchore/syft/main/install.sh | sh -s -- -b /usr/local/bin
fi

# SBOM for image (SPDX JSON)
syft "$IMAGE_REF" -o spdx-json > "$OUT_DIR/sbom-image.spdx.json"
echo "[OK] Image SBOM -> $OUT_DIR/sbom-image.spdx.json"

# SBOM for source (root dir)
syft dir:. -o spdx-json > "$OUT_DIR/sbom-source.spdx.json"
echo "[OK] Source SBOM -> $OUT_DIR/sbom-source.spdx.json"

# Simple diff summary (package counts)
IMG_PKGS=$(jq '.packages|length' "$OUT_DIR/sbom-image.spdx.json")
SRC_PKGS=$(jq '.packages|length' "$OUT_DIR/sbom-source.spdx.json")
printf "SBOM summary:\n- image packages: %s\n- source packages: %s\n" "$IMG_PKGS" "$SRC_PKGS" | tee "$OUT_DIR/summary.txt"
