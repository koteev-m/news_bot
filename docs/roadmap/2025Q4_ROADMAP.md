# 2025Q4 Roadmap 2.0

## TL;DR (Goals & Themes)
- Revenue: увеличить MRR на 28% за счёт апсейла на Pro+/VIP и пакетных предложений с Telegram Stars.
- Activation: довести долю завершивших Mini App onboarding до 82% через гайды и прогресс-бар.
- Retention: удерживать 30-дневный возврат в Mini App на уровне ≥55% путём улучшения импорта и персональных дайджестов.
- Quality: снизить среднее время ответа Mini App API <250 мс (P95) и удержать SLO 99.5% по критичным маршрутам.
- Alerts/Growth: расширить охват новостных алертов до 65% активных Pro пользователей и повысить CTR digest уведомлений до 18%.
- Data Excellence: автоматизировать отчёты по воронкам и экспериментам, добившись публикации дашбордов T+1 без ручных шагов.

## Themes → Epics → Features
| Theme | Epic | Feature | Tier | KPI | Owner | Milestone |
| --- | --- | --- | --- | --- | --- | --- |
| Monetization Flywheel | Pro+ Bundles | Telegram Stars auto-topup для Pro+ | Pro+ | +18% GMV Stars, 40% подключений автоплатежей | Growth Lead | Nov Sprint 1 |
| Monetization Flywheel | Pro+ Bundles | Exclusive breaking news push (ограничено до 10/сутки) | VIP | +12% VIP retention MoM | Head of News | Dec Sprint 1 |
| Monetization Flywheel | Trial Acceleration | 7-дневный Pro trial с авто конверсией | Pro | 22% trial→pay rate | Monetization PM | Oct Sprint 2 |
| Mini App Delight | Guided Onboarding | Прогресс-бар и чек-листы первого запуска | Free | +15 pp onboarding completion | UX Lead | Oct Sprint 1 |
| Mini App Delight | Import Polish | CSV auto-mapping и предпросмотр ошибок | Free/Pro | -25% импорт-ошибок, +12% import_success | UX Lead | Nov Sprint 1 |
| Intelligence & Alerts | Adaptive Digests | A/B digest_layout (карточки vs список) | All | +4 pp digest CTR | Data Lead | Nov Sprint 2 |
| Intelligence & Alerts | Alert Precision | Новые фильтры новостей по волатильности | Pro | +10% alert_click→start | Alerts PM | Dec Sprint 2 |
| Platform Reliability | Observability Revamp | Mini App synthetic monitor (PWA) | All | 99.5% uptime, 5 мин MTTR | SRE Lead | Oct Sprint 3 |
| Platform Reliability | Scaling Billing | Billing reconciliation dashboard | Pro+/VIP | <0.3% отклонений Stars payouts | Billing Eng Lead | Dec Sprint 1 |
| Data & Insights | Growth Analytics | Единый дашборд Post→Pay + эксперименты | Internal | Доступ T+1, usage ≥3 команды | Analytics Lead | Nov Sprint 3 |

## Timeline (Oct / Nov / Dec)
- **October**
  - Sprint 1 (Oct 1–11): Guided Onboarding (progress bar), подготовка A/B framework для `digest_layout`, DoD readiness checks.
  - Sprint 2 (Oct 14–25): Pro trial автоконверсия, AB test scaffolding, release checklist dry-run.
  - Sprint 3 (Oct 28–Nov 8): Synthetic monitoring rollout, аналитический пайплайн для trial cohort.
- **November**
  - Sprint 1 (Nov 11–22): CSV auto-mapping + предпросмотр ошибок, старт Pro+ Stars auto-topup беты.
  - Sprint 2 (Nov 25–Dec 6): Digest layout A/B в проде (CTA `cta_copy` связка), bundle landing localization RU/EN.
  - Sprint 3 (Dec 9–13): Growth dashboard T+1, подготовка VIP alerts контента.
- **December**
  - Sprint 1 (Dec 16–27): Exclusive breaking news push, billing reconciliation dashboard, финализация импорт-подсказок.
  - Sprint 2 (Dec 30–Jan 10): Волатильность фильтры alerts, stop-loss решений A/B, квартальное ретроспективное ревью.

### Cross-team dependencies
- **News**: требует редакционного SLA по VIP breaking news (≤15 мин от публикации до пуша).
- **Alerts**: обновление правил волатильности и ленты событий для фильтров.
- **Billing**: интеграция Stars auto-topup c Telegram API v2 (sandbox Oct, prod Nov).
- **Mini App**: внедрение onboarding прогресса и локализации (в связке с i18n).

## Risk Register
| Risk | Impact | Probability | Mitigation |
| --- | --- | --- | --- |
| Telegram Stars API v2 задержит rollout | Высокий (задержка Pro+ bundle) | Medium | Early sandbox тесты (Oct 5), fallback на manual топап, контракт с Telegram support.
| Перегрузка Mini App API при запуске guided onboarding | Medium (latency >300 мс) | Medium | Предварительный нагрузочный тест (Oct 7), авто-масштабирование pods 2→4, synthetic monitor alerts.
| Недостаточный трафик для `digest_layout` A/B | Medium | Low | Расширить выборку push & email digest, удлинить эксперимент до 3 недель.
| VIP контент не успевает редакция | High | Medium | Зафиксировать SLA, резервный пул аналитиков, weekly sync.
| Growth dashboard T+1 ломается из-за отсутствия данных | Medium | Medium | Дублировать пайплайн (dbt fallback), мониторинг Freshness alert в Prometheus.

## Dependencies
- **External APIs**: Telegram Stars (auto-topup), MOEX price feed (alerts волатильность), Currencylayer (FX для digest).
- **Internal Teams**: Data Platform (ETL T+1), Security (review auto-topup flows), Support (FAQ обновления), Legal (trial terms RU/EN).
- **Tech**: Feature flags (`billingStars`, `miniApp`), experiment service (`cta_copy`, `digest_layout`), analytics events (`miniapp_auth`, `cta_click`, `portfolio_import`, `stars_payment_succeeded`).

## Definition of Done (per release)
1. Build: CI green (`run_all.sh`) и container image помечен `2025Q4.<sprint>`.
2. QA: тест-кейсы в Zephyr покрыты, regression Mini App + billing smoke завершён (<2 blocker defects).
3. SLO: dashboards Prometheus/Grafana обновлены, мониторинги Mini App/alerts/stars без активных алертов 24 ч.
4. Docs: README/faq обновлены, release notes в GitHub опубликованы с лейблами `release`, `theme/*`.
5. Smoke: post-deploy чек (P40) пройден, synthetic monitor подтверждает SLA, A/B assignments валидированы (>=98% ответа API).
