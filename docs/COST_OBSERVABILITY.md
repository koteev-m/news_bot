# P44 — Cost observability

## Что собираем
- **CPU seconds** и **Memory bytes** по сервисам из cAdvisor (`container_label_com_docker_compose_service` → `service`).
- **RPS** из Ktor (`http_server_requests_seconds_count`).
- **Recording rules**:
  - `container_cpu_seconds_rate_by_service`
  - `container_mem_bytes_avg_by_service`
  - `service_cpu_cost_usd_per_hour` / `service_mem_cost_usd_per_hour` / `service_total_cost_usd_per_hour`
  - `cpu_seconds_per_request`

> Стоимость CPU/RAM задана в `costs.rules.yml` как константы (`0.015` и `0.0065` USD/час). Отредактируйте под ваш провайдер.

## Grafana
- Дашборд **NewsBot / Cost Observability**: CPU/Memory, USD/hour, CPU per request.

## Отчёты
- Ежедневный отчёт запускается workflow **Cost Daily** и публикует артефакт `cost_daily.txt`.
- Локально:
  ```bash
  PROM_URL=http://localhost:9090 bash tools/cost/estimate_daily.sh
  ```

## Безопасность
- Секреты не используются; Prometheus URL задаётся через Secrets/ENV.
- В логах нет чисел из биллинга провайдера, только агрегированные оценки.
