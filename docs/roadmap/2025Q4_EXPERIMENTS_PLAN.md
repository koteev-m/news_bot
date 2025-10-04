# 2025Q4 Experiments Plan

## Active Experiments
| Key | Hypothesis | Split | KPI | Start | Decision Check | Owner |
| --- | --- | --- | --- | --- | --- | --- |
| `cta_copy` | Новая формулировка CTA увеличит trial start rate на +3 pp | A/B 50/50 | `cta_click` → `stars_payment_succeeded` | 2025-10-14 | 2025-10-28 | Growth PM |
| `digest_layout` | Карточный digest повышает CTR +4 pp | A/B 45/45, 10% holdout | `alerts_push_sent` → `cta_click`, WAU retention | 2025-11-25 | 2025-12-16 | Data Lead |
| `import_hint` | Inline hint повысит import success на +8 pp | A/B 40/40, 20% control | `portfolio_import` success rate | 2025-11-11 | 2025-12-02 | UX Lead |
| `vip_paywall_copy` | Улучшенное описание VIP выгод повысит upgrade rate на +5 pp | A/B 50/50 | `cta_click` VIP → `stars_payment_succeeded` | 2025-12-06 | 2025-12-27 | Monetization PM |
| `auto_topup_offer` | Пакет 5k Stars увеличит auto-topup take rate +6 pp | Multi-variant (A control, B 2k, C 5k) 34/33/33 | `stars_payment_succeeded` with `auto_topup` | 2025-11-22 | 2025-12-13 | Billing Lead |

## Analysis Workflow
1. **Assignment**: `experiments` сервис (P38) хранит конфиг; при включении обновляем через admin API и логируем ссылку на issue `experiment`.
2. **Data capture**: события `cta_click`, `stars_payment_succeeded`, `portfolio_import`, `alerts_push_sent`, `alerts_push_blocked`, `miniapp_auth` используются для метрик.
3. **Dashboarding**: Metabase → коллекция `Growth/Experiments 2025Q4`; SQL базируется на `tools/analytics/funnels_q4.sql` и витрине `experiment_daily`.
4. **Decision log**: итог фиксируем в GitHub issue с шаблоном [Experiment Brief](../templates/EXPERIMENT_BRIEF_TEMPLATE.md); добавляем метку `ready` или `stop`.

## Rotation & Stop-loss Strategy
- **Cadence**: еженедельный Experiment review (каждый вторник). Проверяем данные T+7, затем T+14.
- **Stop-loss правила**:
  - Если primary KPI падает >5 pp относительно контроля, выключаем treatment и создаём `risk/high` issue.
  - Если guardrail (NPS, churn, latency) превышает пороги (NPS drop >4, churn +2 pp, latency >300 мс P95), эксперимент ставится на паузу.
- **Traffic rotation**: не более 3 параллельных экспериментов на одну аудиторию. `cta_copy` — baseline, остальные staggered (digest → import → vip).
- **Post-mortem**: после завершения публикуем summary в Growth weekly и обновляем Roadmap статус.

## Tooling & Ownership
- Growth Analyst обеспечивает выборку и расчёт мощности (формула см. шаблон).
- Data Engineering мониторит `experiments` API latency и assignment success ≥98%.
- UX Research координирует качественные интервью при необходимости (особенно для `import_hint`).
- Monetization PM следит за корректностью биллинга и Stars квитанций.
