#!/usr/bin/env bash
set -euo pipefail

echo "[INFO] Running ktlint auto-format"
./gradlew --no-daemon --stacktrace ktlintFormat
