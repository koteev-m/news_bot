#!/usr/bin/env bash
set -euo pipefail
# Два подключения: PRIMARY_URL/SECONDARY_URL — PostgreSQL URIs.
PRIMARY_URL="${PRIMARY_URL:?}"
SECONDARY_URL="${SECONDARY_URL:?}"
TABLE="${TABLE:-usage_events}"       # таблица-ориентир «последних изменений»
TS_COL="${TS_COL:-occurred_at}"

echo "[INFO] Measuring RPO on table ${TABLE} by ${TS_COL}"
tp=$(psql "$PRIMARY_URL" -Atc "SELECT EXTRACT(EPOCH FROM max(${TS_COL}))::bigint FROM ${TABLE}" || echo 0)
ts=$(psql "$SECONDARY_URL" -Atc "SELECT EXTRACT(EPOCH FROM max(${TS_COL}))::bigint FROM ${TABLE}" || echo 0)
lag=$(( tp - ts ))
[ "$lag" -lt 0 ] && lag=0
echo "RPO_SECONDS=${lag}" | tee rpo.env
echo "[OK] RPO ${lag}s"
