#!/usr/bin/env bash
set -euo pipefail

GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"
RESET="\033[0m"

log_ok() { printf "%b[OK]%b %s\n" "$GREEN" "$RESET" "$1"; }
log_warn() { printf "%b[WARN]%b %s\n" "$YELLOW" "$RESET" "$1"; }
log_fail() { printf "%b[FAIL]%b %s\n" "$RED" "$RESET" "$1"; }

if [[ "${APP_PROFILE:-}" == "prod" ]]; then
  log_fail "APP_PROFILE=prod is not allowed for backups."
  exit 2
fi

if ! command -v pg_dump >/dev/null 2>&1; then
  log_fail "pg_dump is required but was not found in PATH."
  exit 3
fi

HAD_PGPASSWORD=0
ORIGINAL_PGPASSWORD=""
LOCAL_PGPASSWORD_SET=0
if [[ -v PGPASSWORD ]]; then
  HAD_PGPASSWORD=1
  ORIGINAL_PGPASSWORD="$PGPASSWORD"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BACKUPS_DIR="$REPO_ROOT/backups"
mkdir -p "$BACKUPS_DIR"

normalize_dsn() {
  local raw="$1"
  python - <<'PY' "$raw"
import sys
from urllib.parse import urlparse, quote
raw = sys.argv[1]
if raw.startswith("jdbc:"):
    raw = raw[5:]
parsed = urlparse(raw)
if parsed.scheme not in {"postgres", "postgresql"}:
    print("", end="")
    sys.exit(0)
username = parsed.username or ""
password = parsed.password or ""
host = parsed.hostname or ""
port = parsed.port
path = parsed.path.lstrip("/")
query = ("?" + parsed.query) if parsed.query else ""
netloc = ""
if username:
    netloc += quote(username, safe="")
    if password:
        netloc += ":" + quote(password, safe="")
    netloc += "@"
netloc += host
if port:
    netloc += f":{port}"
print(f"postgresql://{netloc}/{path}{query}")
PY
}

DATABASE_URL="${DATABASE_URL:-}"
DB_CONN=""

if [[ -n "$DATABASE_URL" ]]; then
  if [[ "$DATABASE_URL" == jdbc:postgresql://* ]]; then
    DB_CONN="$(normalize_dsn "$DATABASE_URL")"
  else
    DB_CONN="$DATABASE_URL"
  fi
  if [[ -z "$DB_CONN" ]]; then
    log_fail "Unsupported DATABASE_URL format."
    exit 1
  fi
else
  : "${DATABASE_HOST:?DATABASE_HOST is required when DATABASE_URL is not set}"
  : "${DATABASE_NAME:-${DATABASE_DB:-}}"
  if [[ -z "${DATABASE_NAME:-${DATABASE_DB:-}}" ]]; then
    log_fail "DATABASE_NAME (or DATABASE_DB) must be set when DATABASE_URL is absent."
    exit 1
  fi
  DB_HOST="$DATABASE_HOST"
  DB_PORT="${DATABASE_PORT:-5432}"
  DB_NAME="${DATABASE_NAME:-${DATABASE_DB:-}}"
  DB_USER="${DATABASE_USER:?DATABASE_USER is required when using discrete variables}"
  DB_PASSWORD="${DATABASE_PASSWORD:-${DATABASE_PASS:-}}"
  if [[ -n "$DB_PASSWORD" ]]; then
    export PGPASSWORD="$DB_PASSWORD"
    LOCAL_PGPASSWORD_SET=1
  fi
  DB_CONN_ARGS=("--host" "$DB_HOST" "--port" "$DB_PORT" "--username" "$DB_USER" "--dbname" "$DB_NAME")
fi

TIMESTAMP="$(date -u +"%Y%m%d_%H%M%S")"
TARGET_DIR="$BACKUPS_DIR/$TIMESTAMP"
mkdir -p "$TARGET_DIR"
DUMP_FILE="$TARGET_DIR/db.dump"

VERSION_FILE="$TARGET_DIR/VERSION.txt"
APP_VERSION="unknown"
if [[ -f "$REPO_ROOT/gradle.properties" ]]; then
  APP_VERSION="$(grep -E '^version=' "$REPO_ROOT/gradle.properties" | head -n1 | cut -d'=' -f2-)"
fi
DATE_UTC="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
{
  echo "version=${APP_VERSION}"
  echo "generated_at=${DATE_UTC}"
  echo "profile=${APP_PROFILE:-unknown}"
} >"$VERSION_FILE"

log_ok "Starting PostgreSQL backup into $TARGET_DIR"

if [[ -n "$DB_CONN" ]]; then
  pg_dump --dbname="$DB_CONN" --format=custom --file="$DUMP_FILE"
else
  pg_dump "${DB_CONN_ARGS[@]}" --format=custom --file="$DUMP_FILE"
fi

log_ok "Backup completed: $DUMP_FILE"

RETENTION_COUNT="${BACKUP_RETENTION_COUNT:-7}"
if [[ "$RETENTION_COUNT" =~ ^[0-9]+$ ]] && (( RETENTION_COUNT > 0 )); then
  mapfile -t EXISTING < <(find "$BACKUPS_DIR" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort)
  TOTAL=${#EXISTING[@]}
  if (( TOTAL > RETENTION_COUNT )); then
    TO_DELETE_COUNT=$(( TOTAL - RETENTION_COUNT ))
    for ((i=0; i<TO_DELETE_COUNT; i++)); do
      OLD_DIR="$BACKUPS_DIR/${EXISTING[i]}"
      rm -rf "$OLD_DIR"
      log_warn "Removed old backup: ${EXISTING[i]}"
    done
  fi
else
  log_warn "Invalid BACKUP_RETENTION_COUNT ('$RETENTION_COUNT'), skipping rotation."
fi

if (( LOCAL_PGPASSWORD_SET )); then
  if (( HAD_PGPASSWORD )); then
    export PGPASSWORD="$ORIGINAL_PGPASSWORD"
  else
    unset PGPASSWORD
  fi
fi

log_ok "Backup routine finished successfully."
