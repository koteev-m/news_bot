# P69 — FinOps: Cost Optimization & Carbon Footprint

## Что считает пайплайн
- Стоимость (USD/hour) и (24h) на основе записей `service_total_cost_usd_per_hour` (см. P44).
- Энергопотребление (kWh/hour) ← CPU seconds × WATT_PER_VCPU / 1000.
- Углеродный след (gCO2/hour) ← kWh/hour × CARBON_GCO2_PER_KWH.

## Настройка параметров
- По умолчанию: 15 W/vCPU, 400 gCO2/kWh (региональная средняя).
- Переопределяется через **GitHub Variables** `WATT_PER_VCPU`, `CARBON_GCO2_PER_KWH` или через запись в Prometheus (override recording rules).

## Интеграции Grafana Cloud
- Логи отчёта → Loki (push).
- Маркеры отчётов → Grafana Annotations API.
- Дашборд: `deploy/monitoring/grafana/dashboards/finops-daily.json`.

## Запуск локально
```bash
export PROM_URL=http://localhost:9090
export LOKI_URL=http://localhost:3100/loki/api/v1/push
export LOKI_USER=user  # если требуется
export LOKI_API_KEY=token
bash tools/finops/daily_finops_report.sh
```

## Практика оптимизации
- Смотрите панели CPU seconds / request и Service cost. Ищите «дорогие» сервисы и пики.
- Снижайте MaxRAMPercentage / tuning pools / кэш TTL (см. P39).
- Для carbon: перенос нагрузки в «зелёные» окна (если доступна переменная CI по региону).
