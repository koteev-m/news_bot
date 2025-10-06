#!/usr/bin/env bash
set -euo pipefail

echo "[INFO] Running strict lint (ktlint + detekt)"
./gradlew --no-daemon --stacktrace ktlintCheck detekt -PstrictLint=true
