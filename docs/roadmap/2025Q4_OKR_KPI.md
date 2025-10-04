# 2025Q4 OKR & KPI

## Objectives & Key Results

### Objective 1 — Accelerate paid growth (Revenue)
- **KR1.1**: Увеличить MRR на 28% QoQ за счёт конверсии trial→Pro 22% и upsell Pro+ 12%.
- **KR1.2**: Доля Pro+ пользователей с auto-topup ≥40% (events `stars_payment_succeeded` с prop `auto_topup=true`).
- **KR1.3**: VIP retention (90 дней) ≥62% благодаря эксклюзивным push и heatmap insights.

### Objective 2 — Delight Mini App users (Activation/Retention)
- **KR2.1**: Onboarding completion ≥82% (путь `miniapp_auth` → `portfolio_import`/`alert_click`).
- **KR2.2**: Import success rate ≥78% благодаря auto-mapping и retry CTA.
- **KR2.3**: 30-дневный возврат пользователей Mini App ≥55% (WAU cohort на событиях `miniapp_auth`).

### Objective 3 — Ship reliably with insight (Quality/Data)
- **KR3.1**: Mini App API latency P95 <250 мс; error budget burn <10% еженедельно (Prometheus SLO, см. P21 dashboards).
- **KR3.2**: Growth dashboard T+1 доступен ≥95% дней (dbt freshness check <18ч).
- **KR3.3**: 100% релизов проходят P40 post-deploy smoke без blocker дефектов.

### Objective 4 — Build experimentation muscle (Growth)
- **KR4.1**: Провести ≥4 эксперимента (`cta_copy`, `digest_layout`, `import_hint`, VIP paywall copy) с анализом T+1.
- **KR4.2**: Время до решения по эксперименту ≤14 дней (назначение variant → decision log).
- **KR4.3**: ≥70% гипотез имеют документированный guardrail (NPS, churn) до запуска.

## KPI Dashboard
| Metric | Target | Data Source | Update Cadence |
| --- | --- | --- | --- |
| Activation rate (`miniapp_auth`→first action) | ≥65% | SQL: `events` (P33 funnels); dashboard Growth | Weekly |
| Import start rate | ≥55% новых users | SQL: `tools/analytics/funnels_q4.sql` | Weekly |
| DAU / WAU ratio | ≥0.42 | Prometheus + analytics (P21 metrics + `events`) | Daily |
| Funnel Post→Click→Start→Pay | Post→Click ≥18%, Click→Start ≥40%, Start→Pay ≥12% | SQL funnel query (P33) | Weekly |
| Retention (30-day) | ≥55% | Cohort query (P33 events) | Monthly |
| Churn (Pro/Pro+/VIP) | <4% / <4% / <3% | Billing ledger export + `stars_payment_succeeded` | Monthly |
| NPS (Mini App) | ≥45 | In-app survey (Typeform export) | Monthly |
| Alert CTR (`alerts_push_sent`→`cta_click`) | ≥18% | Prometheus alert logs + events | Weekly |
| Trial Start volume | 5k / квартал | Events `cta_click` (trial) + billing | Weekly |

## Data & Analytics Sources
- **Prometheus/Grafana (P21)**: latency, error rates, synthetic monitors (`miniapp_tti`, `alerts_push_latency`).
- **PostgreSQL analytics (P33)**: таблица `events` для `miniapp_auth`, `cta_click`, `portfolio_import`, `stars_payment_succeeded`, `alerts_push_sent`, `alerts_push_blocked`.
- **dbt / Airflow pipelines**: ежедневное обновление витрин `funnel_daily`, `retention_weekly`, `billing_revenue`.
- **Growth dashboards**: Metabase коллекция `Growth > 2025Q4` (права: Growth PM, Analytics Lead, Monetization PM).
- **Experiment tracker**: Google Sheets + GitHub issues (лейблы `experiment`), данные подтягиваются из `experiments` API.
