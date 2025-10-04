# 2025Q4 Paid Features Plan (Pro / Pro+ / VIP)

## Feature Briefs

### 1. Pro Trial Autoconversion (7-day)
- **Problem**: текущая конверсия free→Pro составляет 12%; пользователи не видят полной ценности, прекращая использование после 1-2 сессий.
- **Solution**: предложить 7-дневный полнофункциональный trial с автопереходом на платный план, уведомлением за 24 часа и ин-апп напоминаниями.
- **UX brief**: миниап onboarding добавляет карточку "7 дней Pro" с прогресс-баром; экран тарифа содержит таймер, CTA `cta_click` ведёт в billing flow; push напоминание отправляется за день до конверсии.
- **Tech outline**: новый статус `trial_pro` в billing service; cron job для авто-конверсии; события `miniapp_auth`, `cta_click`, `stars_payment_succeeded`, `stars_payment_duplicate` обновлены для подсчёта; feature flag `proTrialAutoconvert`.
- **KPI & Success criteria**: trial start rate 25% среди новых пользователей; trial→pay 22%; возврат в Mini App на 14-й день ≥60%.
- **Release gate**: Oct Sprint 2 GA; требуется успешный dry-run на staging (≥50 тестовых аккаунтов), подтверждение Legal текста автопродления, мониторинг churn/chargeback.

### 2. Pro+ Telegram Stars Auto-topup
- **Problem**: пользователи Pro+ сталкиваются с прерыванием сервиса из-за исчерпания Stars-баланса; ручное пополнение приводит к churn 9%.
- **Solution**: автоматическое пополнение Stars при падении баланса ниже 30% месячного расхода; возможность выбора пакета (1k/2k/5k Stars).
- **UX brief**: в Mini App раздел Billing добавляет переключатель "Автопополнение" и модалку выбора пакета; confirmation через Telegram Stars invoice; статусы отображаются на экране подписки.
- **Tech outline**: интеграция с Telegram Stars API v2 (`billingStars` флаг), webhook на событие `stars_payment_succeeded`; cron наблюдает `stars_balance_low`; запись в `billing_ledger`; аналитика через события `stars_payment_succeeded` и `alerts_push_sent` (успешные уведомления о пополнении).
- **KPI & Success criteria**: доля Pro+ с автопополнением 40%; снижение churn Pro+ до <4%; рост GMV Stars на 18% QoQ.
- **Release gate**: Beta Nov Sprint 1 (invite-only 200 аккаунтов), GA Nov Sprint 3 после SLA с Telegram; обязательный мониторинг webhooks и Prometheus алерты на ошибки >1%.

### 3. VIP Exclusive Breaking News Push
- **Problem**: VIP клиенты не видят дополнительной ценности в сравнении с Pro+, retention <50% спустя 2 месяца.
- **Solution**: выделенный канал breaking news с ограничением 10 пушей в сутки и приоритетными инсайтами; доступ только VIP, с настройкой тем.
- **UX brief**: Mini App добавляет раздел "VIP лента" с фильтрами тем; при приходе новости отправляется push + in-app badge; события `alerts_push_sent` и `alerts_push_blocked` помогают отслеживать доставку.
- **Tech outline**: расширение alerts pipeline с новым приоритетом; интеграция редакционного workflow; хранение предпочтений в `user_alert_overrides`; связь с аналитикой через `alert_click`, `miniapp_auth` (возвраты в Mini App).
- **KPI & Success criteria**: VIP retention 3м >=62%; push open rate 45%; CTR VIP digest 25%.
- **Release gate**: Dec Sprint 1 soft launch (50 VIP), Dec Sprint 2 GA; требуются контентные SLA, мониторинг latency <5 мин от события до пуша.

### 4. VIP Portfolio Heatmap Insights
- **Problem**: VIP клиенты ожидают премиальной аналитики по портфелю; текущие отчёты ограничены общими графиками, что снижает upsell.
- **Solution**: интерактивная тепловая карта по активам с фильтрами волатильности и событиями; доступна только в VIP вкладке.
- **UX brief**: Mini App dashboard получает новый блок "Heatmap" с выбором диапазона; tooltip показывает последние новости и `alert_click` статистику.
- **Tech outline**: расширение analytics API для выдачи aggr данных; кэширование через Redis; использование событий `portfolio_import` (качество входных данных) и `post_published` (новости).
- **KPI & Success criteria**: увеличение VIP upsell на +8 pp; 60% VIP используют heatmap еженедельно; уменьшение churn VIP до <3%.
- **Release gate**: Dec Sprint 2 GA после UX-тестов (≥15 респондентов), нагрузочное тестирование 1k concurrent.

## Tier vs Feature Matrix
| Feature | Free | Pro | Pro+ | VIP |
| --- | --- | --- | --- | --- |
| 7-day Pro Trial (autoconvert) | Entry CTA | ✔ | ✔ | – |
| Telegram Stars Auto-topup | – | – | ✔ | ✔ |
| Exclusive Breaking News Push | – | – | – | ✔ |
| Portfolio Heatmap Insights | – | – | – | ✔ |
| Guided Onboarding (premium prompts) | ✔ (teaser) | ✔ | ✔ | ✔ |
| Digest Layout Experiment (`digest_layout`) | Variant B limited | ✔ | ✔ | ✔ |

## Analytics & Conversion Tracking
- **Activation events**: `miniapp_auth`, `cta_click`, `portfolio_import` — используются для оценки пути Free→Trial→Pay.
- **Monetization events**: `stars_payment_succeeded`, `stars_payment_duplicate`, `alerts_push_sent`, `alerts_push_blocked` — определяют успешность платежей и доставку VIP push.
- **Conversion goals**:
  - Free→Pro: trial start → `stars_payment_succeeded` в течение 10 дней.
  - Pro→Pro+: доля включивших auto-topup через событие `stars_payment_succeeded` с тегом `auto_topup` ≥40%.
  - Pro+→VIP: завершённый upgrade flow (событие `cta_click` на VIP offer + `stars_payment_succeeded` c планом VIP) с конверсией ≥12%.
- **Experiment readouts**: для `cta_copy` и `digest_layout` использовать retention split через дашборд Growth; stop-loss — конверсия снижается >5 pp относительно контроля.

## Experimentation & Pricing Tests
- A/B `cta_copy` продолжить как baseline для всех paywall CTA.
- Новый A/B `digest_layout` влияет на VIP upsell за счёт визуального приоритета предложений.
- Ценообразование: тест пакетных скидок для auto-topup (5% при выборе 5k Stars), rolling rollout после подтверждения ARPU.

