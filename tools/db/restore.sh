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
  log_fail "APP_PROFILE=prod is not allowed for restore."
  exit 2
fi

if [[ "${I_UNDERSTAND:-}" != "1" ]]; then
  log_fail "Set I_UNDERSTAND=1 to acknowledge the destructive restore operation."
  exit 1
fi

if ! command -v pg_restore >/dev/null 2>&1; then
  log_fail "pg_restore is required but was not found in PATH."
  exit 5
fi

if ! command -v psql >/dev/null 2>&1; then
  log_fail "psql is required for database preparation."
  exit 5
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

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

parse_dsn() {
  local dsn="$1"
  python - <<'PY' "$dsn"
import sys
from urllib.parse import urlparse
raw = sys.argv[1]
parsed = urlparse(raw)
print(parsed.hostname or "")
print(parsed.port or 5432)
print(parsed.username or "")
print(parsed.password or "")
print(parsed.path.lstrip("/") or "")
PY
}

build_dsn() {
  local host="$1" port="$2" user="$3" password="$4" db="$5"
  python - <<'PY' "$host" "$port" "$user" "$password" "$db"
import sys
from urllib.parse import quote
host, port, user, password, db = sys.argv[1:]
netloc = ""
if user:
    netloc += quote(user, safe="")
    if password:
        netloc += ":" + quote(password, safe="")
    netloc += "@"
netloc += host
if port:
    netloc += f":{port}"
print(f"postgresql://{netloc}/{db}")
PY
}

jdbc_url_from_dsn() {
  local dsn="$1"
  python - <<'PY' "$dsn"
import sys
from urllib.parse import urlparse
parsed = urlparse(sys.argv[1])
host = parsed.hostname or "localhost"
port = parsed.port or 5432
db = parsed.path.lstrip("/") or ""
print(f"jdbc:postgresql://{host}:{port}/{db}")
PY
}

dump_path=""
target_url=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dump)
      dump_path="$2"
      shift 2
      ;;
    --target-url)
      target_url="$2"
      shift 2
      ;;
    *)
      log_fail "Unknown argument: $1"
      exit 1
      ;;
  esac
done

if [[ -z "$dump_path" ]]; then
  log_fail "--dump path/to/db.dump is required."
  exit 1
fi

if [[ ! -f "$dump_path" ]]; then
  log_fail "Dump file not found: $dump_path"
  exit 4
fi

TARGET_DSN=""
TARGET_HOST=""
TARGET_PORT=""
TARGET_USER=""
TARGET_PASSWORD=""
TARGET_DB=""

if [[ -n "$target_url" ]]; then
  TARGET_DSN="$(normalize_dsn "$target_url")"
  if [[ -z "$TARGET_DSN" ]]; then
    log_fail "Unsupported --target-url format."
    exit 1
  fi
  mapfile -t parsed < <(parse_dsn "$TARGET_DSN")
  TARGET_HOST="${parsed[0]}"
  TARGET_PORT="${parsed[1]}"
  TARGET_USER="${parsed[2]}"
  TARGET_PASSWORD="${parsed[3]}"
  TARGET_DB="${parsed[4]}"
else
  : "${RESTORE_HOST:?RESTORE_HOST is required when --target-url is not provided}"
  : "${RESTORE_DB:-${RESTORE_NAME:-}}"
  TARGET_HOST="$RESTORE_HOST"
  TARGET_PORT="${RESTORE_PORT:-5432}"
  TARGET_USER="${RESTORE_USER:-}"
  TARGET_PASSWORD="${RESTORE_PASSWORD:-${RESTORE_PASS:-}}"
  TARGET_DB="${RESTORE_DB:-${RESTORE_NAME:-}}"
  TARGET_DSN="$(build_dsn "$TARGET_HOST" "$TARGET_PORT" "$TARGET_USER" "$TARGET_PASSWORD" "$TARGET_DB")"
  if [[ -z "$TARGET_DSN" ]]; then
    log_fail "Failed to construct target connection string."
    exit 1
  fi
fi

if [[ -z "$TARGET_DB" ]]; then
  log_fail "Target database name could not be determined."
  exit 1
fi

PSQL_ARGS=(--host "$TARGET_HOST" --port "$TARGET_PORT")
if [[ -n "$TARGET_USER" ]]; then
  PSQL_ARGS+=(--username "$TARGET_USER")
fi

HAD_PGPASSWORD=0
ORIGINAL_PGPASSWORD=""
LOCAL_PGPASSWORD_SET=0
if [[ -v PGPASSWORD ]]; then
  HAD_PGPASSWORD=1
  ORIGINAL_PGPASSWORD="$PGPASSWORD"
fi
if [[ -n "$TARGET_PASSWORD" ]]; then
  export PGPASSWORD="$TARGET_PASSWORD"
  LOCAL_PGPASSWORD_SET=1
fi
cleanup() {
  if (( LOCAL_PGPASSWORD_SET )); then
    if (( HAD_PGPASSWORD )); then
      export PGPASSWORD="$ORIGINAL_PGPASSWORD"
    else
      unset PGPASSWORD
    fi
  fi
}
trap cleanup EXIT

log_warn "Preparing database '$TARGET_DB' on $TARGET_HOST:$TARGET_PORT"

if psql "${PSQL_ARGS[@]}" --dbname "$TARGET_DB" -c '\q' >/dev/null 2>&1; then
  psql "${PSQL_ARGS[@]}" --dbname "$TARGET_DB" -v ON_ERROR_STOP=1 -c "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public;" >/dev/null
else
  DEFAULT_DB="${RESTORE_ADMIN_DB:-postgres}"
  psql "${PSQL_ARGS[@]}" --dbname "$DEFAULT_DB" -v ON_ERROR_STOP=1 -v dbname="$TARGET_DB" -c "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'dbname') THEN EXECUTE format('CREATE DATABASE %I', :'dbname'); END IF; END $$;" >/dev/null
fi

log_ok "Database prepared."

log_warn "Running pg_restore from $dump_path"
if pg_restore --clean --if-exists --no-owner --no-privileges --dbname="$TARGET_DSN" "$dump_path"; then
  log_ok "pg_restore completed."
else
  log_fail "pg_restore failed."
  exit 5
fi

REQUIRED_TABLES=(users portfolios trades news_items)
MISSING=()
for table in "${REQUIRED_TABLES[@]}"; do
  if ! psql "${PSQL_ARGS[@]}" --dbname "$TARGET_DB" -At -c "SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='${table}' LIMIT 1;" | grep -q 1; then
    MISSING+=("$table")
  fi
done

if (( ${#MISSING[@]} > 0 )); then
  log_fail "Missing tables after restore: ${MISSING[*]}"
  exit 5
fi

log_ok "Sanity check: required tables present."

if [[ -x "$REPO_ROOT/gradlew" ]]; then
  JDBC_URL="$(jdbc_url_from_dsn "$TARGET_DSN")"
  log_warn "Running Gradle Flyway info check."
  if env ORG_GRADLE_PROJECT_db_url="$JDBC_URL" \
         ORG_GRADLE_PROJECT_db_user="$TARGET_USER" \
         ORG_GRADLE_PROJECT_db_password="$TARGET_PASSWORD" \
         "$REPO_ROOT/gradlew" :storage:flywayInfo >/dev/null; then
    log_ok "Flyway info completed."
  else
    log_fail "Gradle Flyway info check failed."
    exit 5
  fi
else
  log_warn "Gradle wrapper not executable; skipping Flyway check."
fi

log_ok "Restore completed successfully."
