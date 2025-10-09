#!/usr/bin/env bash
set -euo pipefail
VER="${1:?Usage: tag_rc.sh vX.Y.Z-rc.N}"
git fetch --tags
git tag -a "$VER" -m "release candidate $VER"
git push origin "$VER"
echo "[OK] tagged $VER"
