#!/usr/bin/env bash
set -euo pipefail

if [[ "${APP_PROFILE:-}" == "prod" ]]; then
  echo "[demo-seed] APP_PROFILE=prod detected. Refusing to run." >&2
  exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../.." && pwd)
SQL_FILE="$PROJECT_ROOT/deploy/seed/demo_seed.sql"

if [[ ! -f "$SQL_FILE" ]]; then
  echo "[demo-seed] SQL file not found at $SQL_FILE" >&2
  exit 1
fi

if ! command -v psql >/dev/null 2>&1; then
  echo "[demo-seed] psql command not found in PATH" >&2
  exit 1
fi

DB_URL="${DATABASE_URL:-}"
if [[ -z "$DB_URL" ]]; then
  DB_USER="${DATABASE_USER:-}"
  DB_PASSWORD="${DATABASE_PASSWORD:-}"
  DB_HOST="${DATABASE_HOST:-localhost}"
  DB_PORT="${DATABASE_PORT:-5432}"
  DB_NAME="${DATABASE_NAME:-}"

  if [[ -z "$DB_USER" || -z "$DB_NAME" ]]; then
    echo "[demo-seed] DATABASE_URL or DATABASE_USER/DATABASE_NAME must be provided" >&2
    exit 1
  fi

  if [[ -n "$DB_PASSWORD" ]]; then
    DB_URL="postgresql://$DB_USER:$DB_PASSWORD@$DB_HOST:$DB_PORT/$DB_NAME"
  else
    DB_URL="postgresql://$DB_USER@$DB_HOST:$DB_PORT/$DB_NAME"
  fi
fi

echo "[demo-seed] Applying $SQL_FILE"
psql "$DB_URL" -v ON_ERROR_STOP=1 -f "$SQL_FILE"
echo "[demo-seed] Seed completed successfully"
