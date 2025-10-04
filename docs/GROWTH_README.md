# Growth & Monetization Ops (2025Q4)

## Running Funnel Reports
1. Убедитесь, что `DATABASE_URL` указывает на staging/prod replica (без PII). Экспортируем только аггрегированные данные.
2. Запустите квартальный отчёт:
   ```bash
   export DATABASE_URL=postgres://readonly:***@db:5432/analytics
   psql "$DATABASE_URL" -f tools/analytics/funnels_q4.sql
   ```
3. Результаты сохраняйте в Metabase (`Growth > Funnels > 2025Q4`). Добавляйте теги `Post→Click→Start→Pay` и экспериментальные ключи (`cta_copy`, `digest_layout`).
4. Для недельных апдейтов используйте `tools/analytics/funnels.sql` (семидневное окно) и сравнивайте с квартальными трендами.

## Dashboards & Alerts
- **Metabase**: коллекции `Growth/Experiments 2025Q4`, `Revenue/Stars`, `Activation/Onboarding`.
- **Grafana (P21)**: dashboards `MiniApp Latency`, `Alerts Delivery`, `Billing Stars` (SLO 99.5%).
- **Prometheus alerts**: `miniapp_tti_p95`, `stars_payment_error_ratio`, `alerts_push_latency`.
- **Experiment tracker**: Google Sheet "P45 Experiments" автосинхронизируется с GitHub issues (`experiment` label).

## Weekly Growth Review (Agenda)
1. **Wins & learnings** (10 мин) — апдейты по KR, закрытые эксперименты.
2. **KPI dashboard walkthrough** (15 мин) — проверяем Activation, Funnel, Retention, Revenue.
3. **Experiment deep-dive** (20 мин) — status `cta_copy`, `digest_layout`, `import_hint`, `vip_paywall_copy`.
4. **Monetization pipeline** (10 мин) — Stars auto-topup, VIP upsell, trial cohort churn.
5. **Risks & actions** (5 мин) — блокеры, решения, новые гипотезы.
6. **Next steps** — owners/дедлайны, обновление labels (`ready`, `in-progress`, `risk/*`).

## Outputs & Documentation
- После каждого weekly review публикуется summary в Confluence "P45 Growth" и прикрепляется к issue "Growth Weekly".
- Решения по экспериментам добавляются в соответствующие GitHub issues (шаблон [Experiment Brief](docs/templates/EXPERIMENT_BRIEF_TEMPLATE.md)).
- Все SQL и dashboards версионируются: PR с лейблом `growth`, reviewer — Analytics Lead.
- Счётчики PII не выгружаются: только агрегаты и псевдонимизированные идентификаторы (см. P33).

## Contacts
- Growth PM — главный координатор weekly review.
- Monetization PM — отвечает за Stars & billing.
- UX Research — владелец качественных интервью и NPS.
- Analytics Lead — держатель SQL/дашбордов и мониторинга.
