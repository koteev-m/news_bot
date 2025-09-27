#!/usr/bin/env bash
set -euo pipefail

red()    { printf '\033[31m%s\033[0m\n' "$*"; }
green()  { printf '\033[32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }
blue()   { printf '\033[34m%s\033[0m\n' "$*"; }

usage() {
  cat <<'USAGE'
Usage: tools/audit/run_all.sh [--skip-build] [--skip-miniapp] [--only-audit]
  --skip-build     Skip Gradle clean build step
  --skip-miniapp   Skip miniapp npm/pnpm checks
  --only-audit     Only run grep-based audit checks
  -h, --help       Show this message
USAGE
}

skip_build=0
skip_miniapp=0
only_audit=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build)   skip_build=1; shift ;;
    --skip-miniapp) skip_miniapp=1; shift ;;
    --only-audit)   only_audit=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) red "[FAIL] unknown option: $1"; usage; exit 1 ;;
  esac
done

trap 'code=$?; if (( code == 0 )); then green "[OK] all checks passed"; else red "[FAIL] run_all.sh failed (exit ${code})"; fi' EXIT

if [[ "${APP_PROFILE:-}" == "prod" ]]; then
  red "[FAIL] refusing to run with APP_PROFILE=prod"
  exit 2
fi

if [[ ! -f ./gradlew ]]; then
  red "[FAIL] ./gradlew not found in repository root"
  exit 3
fi

blue "== Local revision (lint + tests + audit + miniapp) =="

java_version=$(java -version 2>&1 | head -n 1 | tr -d '\r')
if [[ -n "${java_version}" ]]; then
  yellow "[INFO] ${java_version}"
fi

gradle_version=$(./gradlew -v | awk '/^Gradle / {print; exit}')
if [[ -n "${gradle_version}" ]]; then
  yellow "[INFO] ${gradle_version}"
fi

summary=()

if (( ! only_audit )); then
  yellow "[INFO] Running ktlintCheck and detekt"
  ./gradlew --no-daemon --stacktrace ktlintCheck detekt
  summary+=("ktlint+detekt")

  yellow "[INFO] Running JVM module tests"
  ./gradlew --no-daemon --stacktrace :core:test :storage:test :app:test --console=plain
  summary+=("tests")

  if (( skip_build )); then
    yellow "[WARN] --skip-build enabled, skipping clean build"
  else
    yellow "[INFO] Running clean build"
    ./gradlew --no-daemon --stacktrace clean build --console=plain
    summary+=("build")
  fi
fi

yellow "[INFO] Running audit grep checks"
if [[ ! -x tools/audit/grep_checks.sh ]]; then
  red "[FAIL] tools/audit/grep_checks.sh is missing or not executable"
  exit 4
fi

if ! tools/audit/grep_checks.sh; then
  status=$?
  red "[FAIL] audit grep checks failed"
  exit "$status"
fi
summary+=("audit-grep")

if (( ! only_audit )) && (( ! skip_miniapp )); then
  if [[ -f miniapp/package.json ]]; then
    yellow "[INFO] Running miniapp checks"
    if command -v corepack >/dev/null 2>&1; then
      corepack enable
      corepack prepare pnpm@latest --activate
    else
      yellow "[WARN] corepack not found; attempting pnpm directly"
    fi
    pushd miniapp >/dev/null
    if command -v pnpm >/dev/null 2>&1; then
      pnpm install --frozen-lockfile
      pnpm build
      pnpm test -- --run
    else
      red "[FAIL] pnpm not available"
      popd >/dev/null
      exit 5
    fi
    popd >/dev/null
    summary+=("miniapp")
  else
    yellow "[INFO] miniapp/package.json not found, skipping miniapp checks"
  fi
elif (( skip_miniapp )); then
  yellow "[WARN] --skip-miniapp enabled, skipping miniapp checks"
fi

if (( ${#summary[@]} > 0 )); then
  summary_line="${summary[0]}"
  for entry in "${summary[@]:1}"; do
    summary_line+="; ${entry}"
  done
  green "[OK] summary: ${summary_line}"
fi

exit 0
