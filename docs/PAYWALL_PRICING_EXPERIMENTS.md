# P51 — Paywall & pricing experiments

- Эксперименты: `paywall_copy (A/B)`, `price_bundle (A/B/C)`. Назначение через ExperimentsService (см. P38).
- API:
  - `GET /api/pricing/offers` → `{ copyVariant, priceVariant, headingEn, subEn, ctaEn, offers:[{tier,priceXtr,starsPackage}] }`
  - `POST /api/pricing/cta` → фиксирует `paywall_cta_click`.
  - Admin:
    - `POST /api/admin/pricing/override` — `{key,variant,tier,priceXtr,starsPackage?}`
    - `POST /api/admin/pricing/copy` — `{key,variant,headingEn,subEn,ctaEn}`
- Analytics события: `paywall_view`, `paywall_cta_click`, конверсия через `stars_payment_succeeded`.

Отчёт (пример)
```sql
-- Конверсия по вариантам price_bundle
SELECT props->>'variant' AS variant,
       COUNT(*) FILTER (WHERE type='paywall_cta_click') AS cta,
       COUNT(*) FILTER (WHERE type='stars_payment_succeeded') AS pay
FROM events
WHERE ts >= now() - interval '30 days'
GROUP BY 1;
```
