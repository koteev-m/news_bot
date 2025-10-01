# Go-live Cutover Runbook / План вывода в прод

## Pre-checks / Предварительные проверки
- ✅ Ensure `deploy/compose/.env` exists (`cp deploy/compose/.env.example deploy/compose/.env`) and contains production-like secrets via Vault/SOPS.
- ✅ Bootstrap the blue/green stack:
  ```bash
  docker compose -f deploy/compose/docker-compose.bluegreen.yml up -d --build
  docker compose -f deploy/compose/docker-compose.bluegreen.yml ps
  ```
- ✅ Confirm health gates:
  - `curl -fsSL https://localhost:8443/healthz` → `ok` (or use `http://localhost:8081/healthz` without TLS).
  - `curl -fsSL https://localhost:8443/metrics | head` to ensure the scrape endpoint responds.
  - `curl -fsSL https://localhost:8443/health/db | jq .` from both `app_blue` and `app_green` containers if deeper DB check required.
- ✅ Check Grafana dashboards from P21 for latency SLOs and error rates prior to traffic shifts.

## Canary / Канарейка
- 🔁 Start with BLUE as primary (100%) and GREEN as 10% canary:
  ```bash
  bash tools/release/switch_traffic.sh BLUE 10
  ```
- 👀 Observe for 10–15 minutes:
  - Grafana panels: latency, error rate, saturation for the canary pod.
  - Alertmanager silence status — no critical alerts should fire.
  - `docker compose -f deploy/compose/docker-compose.bluegreen.yml exec nginx nginx -T | grep app_` (optional) to inspect live weights.
- ✅ Health gates during canary:
  - `curl -fsSL https://localhost:8443/metrics | head`
  - `curl -fsSL https://localhost:8443/health/db`
  - Application smoke via `/api/...` where applicable.

## Promote / Промоут
- ⬆️ When GREEN is stable, move full traffic:
  ```bash
  bash tools/release/switch_traffic.sh GREEN 0
  ```
- 🔄 Re-run health gates and Grafana checks to confirm GREEN owns 100% of the traffic and BLUE sits idle.
- 📌 Update incident channel / release notes about completion.

## Rollback / Откат
- 🚨 If canary or promote fails, revert immediately:
  ```bash
  # return to BLUE primary, disable GREEN canary
  bash tools/release/switch_traffic.sh BLUE 0
  ```
- ✅ Validate `/metrics`, `/healthz`, `/health/db` again.
- 📝 Create incident note with timeline and observed failures; schedule a postmortem if needed.

## Maintenance / Обслуживание
- 🧰 Use maintenance mode during controlled outages (schema migrations, long-running ops):
  ```bash
  bash tools/release/maintenance.sh on
  ```
- 🌐 Users receive `503` with `deploy/compose/nginx/maintenance.html`, while `/healthz` and `/metrics` stay accessible for monitors.
- 🔁 Disable maintenance promptly after work:
  ```bash
  bash tools/release/maintenance.sh off
  ```
- 📣 Announce start/end times in status channels.

## Post-deploy / Постпроверки
- 📊 Re-verify Grafana dashboards and alert silence status.
- 🧪 Run smoke tests (`curl` critical APIs, Telegram webhook validation) to ensure business flows succeed.
- 🗂️ Archive switch logs (`tools/release/switch_traffic.sh` output) in the release ticket and update runbook deviations if any occurred.
