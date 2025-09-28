# SLA / SLO / SLI Policy — News Bot

## Термины / Terms (RU/EN)
- **SLI (Показатель уровня сервиса / Service Level Indicator)** — измеримая метрика качества, построенная на фактических данных Prometheus.
- **SLO (Цель уровня сервиса / Service Level Objective)** — целевой уровень для конкретного SLI, измеряется на скользящем 28-дневном окне.
- **SLA (Соглашение об уровне сервиса / Service Level Agreement)** — внешнее обещание клиентам; в текущем релизе SLA повторяет SLO и публикуется в Pro/Pro+/VIP тарифах.
- **Error budget (Бюджет ошибок)** — доля допустимых отклонений от SLO за окно наблюдения. Бюджет расходуется при нарушении SLO и восстанавливается в новом окне.

## Продуктовые SLI и SLO

### Webhook XTR latency p95
- **PromQL (Prometheus/Micrometer)**: `histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/telegram/webhook"}[5m])))`
- **Источники данных**: `App.kt` (маршрут `post("/telegram/webhook")`) и `observability/Metrics.kt` (инициализация Micrometer; см. `DomainMetrics`).
- **SLO**: 95-й перцентиль времени ответа ≤ 1.5 s в 95% пятиминутных интервалов внутри 28-дневного окна.
- **Связанный алерт**: `WebhookP95High` из `deploy/monitoring/prometheus/alerts.rules.yml` (warning/critical пороги 1.5 s и 3 s).

### API 5xx rate
- **PromQL**: `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))`
- **Источники данных**: метрики Ktor (`http_server_requests_seconds_count`) экспортируются через `observability` модуль.
- **SLO**: доля ответов 5xx ≤ 2% в каждом пятиминутном окне; допускается максимум 5% нарушенных интервалов за 28 дней.
- **Связанный алерт**: `Http5xxRateHigh` (warning 2%, critical 5%) из `alerts.rules.yml`.

### Duplicate Stars payments rate
- **PromQL**: `increase(webhook_stars_duplicate_total[5m]) / clamp_min(increase(webhook_stars_success_total[5m]), 1)`
- **Источники данных**: `DomainMetrics.webhookStarsSuccess` (счётчик успехов) и обработчик `billing/StarsWebhookHandler.kt` (инкременты/guard), интеграция с `BillingService.applySuccessfulPayment` из `core/billing/service/BillingService.kt`.
- **SLO**: доля дубликатов ≤ 1% в каждом пятиминутном интервале; максимум 5% нарушенных интервалов за 28 дней.
- **Связанный алерт**: `StarsDuplicateRateHigh` (warning 1%, critical 2%).

### Alerts push quality (observed KPI)
- **PromQL**: `increase(alerts_budget_reject_total[5m]) / clamp_min(increase(alerts_push_total[5m]), 1)`
- **Статус**: наблюдаемая метрика для внутренних обзоров; не входит в формальный SLA, но отслеживается на дашборде `NewsBot / Observability`.

## Error Budget Management
- **Окно**: 28 дней, дискретизация 5 минут.
- **Инициализация**: бюджет = 100% в начале окна.
- **Расход**:
  - Для latency/5xx — процент интервалов, в которых SLI превышает порог SLO.
  - Для дубликатов — процент интервалов с долей дубликатов > 1%.
- **Политики**:
  - >50% расхода: вводим code freeze на новые фичи в модулях `alerts` и `news`, разрешены только фиксы стабильности.
  - 100% расхода: полный freeze, обязательные корректирующие задачи приоритета P0/P1 в `billing` и `observability` backlog, обязательный пост-мортем.
- **Корреляция с алертами**: предупреждения (`warning`) сигнализируют ускоренный расход бюджета; `critical` означает, что бюджет расходуется быстрее допустимого и требуется немедленная реакция.

## Отчётность и мониторинг
- **Weekly SLO report**: автоматически генерируем из Grafana dashboard `NewsBot / Observability` (панели `Webhook latency`, `HTTP 5xx rate`, `Stars duplicate rate`). Рассылается в канал `#newsbot-reliability` и прикладывается к on-call handoff.
- **Grafana / Prometheus доступ**: поднять стенд согласно `README.md` разделу P21 (см. `deploy/monitoring`).
- **История бюджета**: фиксируется в тикетах `Reliability` со ссылками на графики Prometheus.

## Действия при нарушении SLO
1. Триггерится алерт (`WebhookP95High`, `Http5xxRateHigh`, `StarsDuplicateRateHigh`).
2. On-call запускает соответствующий runbook из `docs/RUNBOOKS`.
3. Incident Commander отслеживает расход error budget и принимает решение о freeze.
4. По итогам инцидента — анализ и задачи по устранению первопричины.

## Аннексы: PromQL-чекеры
```promql
# Latency p95 (manual check)
histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{job="app", uri="/telegram/webhook"}[5m])))

# HTTP 5xx share
sum(rate(http_server_requests_seconds_count{job="app", status=~"5.."}[5m])) /
  sum(rate(http_server_requests_seconds_count{job="app"}[5m]))

# Duplicate payments share
increase(webhook_stars_duplicate_total{job="app"}[5m]) /
  clamp_min(increase(webhook_stars_success_total{job="app"}[5m]), 1)

# Alerts budget rejects (KPI)
increase(alerts_budget_reject_total{job="app"}[5m]) /
  clamp_min(increase(alerts_push_total{job="app"}[5m]), 1)
```
