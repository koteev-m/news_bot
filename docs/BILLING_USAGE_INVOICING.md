# P81 — Multi-tenant Billing: Usage + Rate Cards + Invoicing

## Потоки
- **usage ingest** → `usage_events`
- **aggregate** → `UsageService.aggregateByMetric(tenant, from, to)`
- **rating** → по `rate_items` (tiers)
- **invoicing** → `invoices` + `invoice_lines` (+ `invoice_runs` для аудита)

## REST API
- `POST /api/usage/ingest` — `{metric, quantity, occurredAt?, dedupKey?}`
- `POST /api/billing/invoice/draft` — `{from,to,taxRate?}`
- `POST /api/billing/invoice/issue` — создаёт запись в БД

## Rate Cards
- `rate_cards` + `rate_items` — метрика, unit, tiered price.
- `tenant_pricing` — какая карта применяется.

## Stars (опционально)
- `StarsInvoicer.createInvoiceLink(tenant, total)` — мок для интеграции с Telegram Stars.

## Cron
- `tools/billing/invoice_monthly.sh` — ручной run/CI.
