# P49 — Structured JSON logging & shipping (Loki/Promtail)

- **App**: JSON logback (traceId → `requestId` из MDC), маскирование токенов.
- **Nginx**: JSON access-лог, `request_id` → `X-Request-Id`.
- **Promtail** собирает `/var/log/nginx/access.json` и контейнерные логи приложения в Loki.

## Быстрый старт (стейдж)
```bash
cd deploy/monitoring
cp .env.example .env
docker compose -f docker-compose.monitoring.yml up -d loki promtail grafana

В Grafana добавьте Loki datasource и постройте запрос:

{job="nginx"} |= "request_id"

или

{job="app"} |= "traceId"
```
