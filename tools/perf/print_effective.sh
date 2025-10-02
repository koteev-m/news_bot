#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONF_FILE="$ROOT_DIR/app/src/main/resources/application.conf"
DOCKERFILE="$ROOT_DIR/Dockerfile"

read_conf_number() {
  local key="$1"
  local default_value="$2"
  if [[ ! -f "$CONF_FILE" ]]; then
    echo "$default_value"
    return
  fi
  local value
  value="$(rg -o -m1 "^\s*${key}\s*=\s*\d+" "$CONF_FILE" 2>/dev/null | sed -E 's/.*=\s*([0-9]+).*/\1/' | head -n1 || true)"
  if [[ -z "$value" ]]; then
    echo "$default_value"
  else
    echo "$value"
  fi
}

worker_threads="$(read_conf_number 'workerThreads' "$(nproc)")"
db_pool_max_conf="$(read_conf_number 'poolMax' "10")"
db_pool_min_conf="$(read_conf_number 'poolMinIdle' "2")"
http_pool_max_conf="$(read_conf_number 'maxConnectionsPerRoute' "100")"
http_keepalive_conf="$(read_conf_number 'keepAliveSeconds' "30")"
cache_ttl_moex_conf="$(read_conf_number 'moex' "15000")"
cache_ttl_coingecko_conf="$(read_conf_number 'coingecko' "15000")"
cache_ttl_cbr_conf="$(read_conf_number 'cbr' "60000")"

db_pool_max_effective="${DB_POOL_MAX:-$db_pool_max_conf}"
db_pool_min_effective="${DB_POOL_MIN_IDLE:-$db_pool_min_conf}"

java_tool_options="${JAVA_TOOL_OPTIONS:-}"
if [[ -z "$java_tool_options" && -f "$DOCKERFILE" ]]; then
  java_tool_options="$(rg -oP 'JAVA_TOOL_OPTIONS="([^"]+)' --replace '$1' -m1 "$DOCKERFILE" 2>/dev/null || true)"
fi

printf '=== Effective performance profile ===\n'
printf 'workerThreads...............: %s\n' "$worker_threads"
printf 'DB pool max / minIdle.......: %s / %s\n' "$db_pool_max_effective" "$db_pool_min_effective"
printf 'HTTP client max per route...: %s\n' "$http_pool_max_conf"
printf 'HTTP keepAlive (seconds)....: %s\n' "$http_keepalive_conf"
printf 'Cache TTL ms (moex/coin/cbr): %s / %s / %s\n' \
  "$cache_ttl_moex_conf" "$cache_ttl_coingecko_conf" "$cache_ttl_cbr_conf"
printf 'JAVA_TOOL_OPTIONS...........: %s\n' "${java_tool_options:-<not set>}"

printf 'Environment overrides: DB_POOL_MAX=%s, DB_POOL_MIN_IDLE=%s\n' \
  "${DB_POOL_MAX:-<unset>}" "${DB_POOL_MIN_IDLE:-<unset>}"
