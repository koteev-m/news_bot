## P81 — Usage Metering & Invoicing

- DB migrations: `V13__usage_billing.sql`
- Core: `billing/UsageModels.kt`, `billing/UsageService.kt`
- Repo: `repo/UsageBillingRepository.kt`
- API: `billing/UsageRoutes.kt`
- Cron: `tools/billing/invoice_monthly.sh`

Пример:
```bash
curl -X POST $BASE/api/usage/ingest -H 'Content-Type: application/json' \
  -H "X-Tenant-Slug: demo" \
  -d '{"metric":"api.calls","quantity":5}'

curl -X POST $BASE/api/billing/invoice/draft -H 'Content-Type: application/json' \
  -H "X-Tenant-Slug: demo" \
  -d '{"from":"2025-09-01T00:00:00Z","to":"2025-10-01T00:00:00Z"}'
```
