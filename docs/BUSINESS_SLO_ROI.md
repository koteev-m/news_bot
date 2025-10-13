# P71 — Business SLO/ROI Layer

## Метрики
- `events_total{type=...}` — серверный экспортер событий (paywall_view / paywall_cta_click / stars_payment_succeeded).
- `cost_per_request_usd`, `cost_per_paywall_click_usd`, `cost_per_payment_usd` — запись правил Prometheus.

## Отчёты
- SQL: `tools/analytics/business_roi.sql` — воронка и ARPU/ARPPU (приближение).
- CI: `Business Daily` — Markdown-сводка.

## Дашборды
- Grafana: `business-roi.json` — стоимость и конверсия.

## Практика
- Сверяйте пороги SLO/стоимости с бизнес-метриками (не ломайте UX ради микросэкономии).
- Используйте эксперименты P38/P51 для изменения конверсии, смотрите `cost_per_payment_usd`.
