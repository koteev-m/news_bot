#!/usr/bin/env bash
set -euo pipefail
PRIMARY_URL="${PRIMARY_URL:?postgres connection string}"
psql "$PRIMARY_URL" -c "SELECT slot_name, active, restart_lsn FROM pg_replication_slots;" -P pager=off
