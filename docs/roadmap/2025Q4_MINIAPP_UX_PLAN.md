# 2025Q4 Mini App UX / i18n / a11y Plan

## Onboarding
- **Task**: Guided progress bar & checklist
  - **Impact**: +15 pp completion, снижение time-to-first-action до <2 мин.
  - **Scope**: прогресс-бар из 4 шагов (auth, портфель, настройки алертов, подбор digest); contextual tips.
  - **Acceptance**: 95% событий `miniapp_auth` инициируют прогресс, ≥70% завершают все шаги; telemetry в events.
  - **A/B**: нет, rollout 100% (стабильность требуется для trial messaging).
  - **Metric**: onboarding completion (events `miniapp_auth` → `portfolio_import`/`alert_click`), CSAT >4.4.
- **Task**: Trial pitch card localization
  - **Impact**: повышение trial adoption +6 pp в RU/EN сегментах.
  - **Scope**: RU/EN copy, акценты на "отмена в 1 клик"; unit tests на plural forms.
  - **Acceptance**: verified by UX writer + localization QA; screenshot diff в Percy.
  - **A/B**: нет.
  - **Metric**: `cta_click` на trial CTA / `miniapp_auth` в регионе.

## Loading & Error States
- **Task**: Skeleton screens for portfolio dashboard
  - **Impact**: снижение bounce rate при загрузке >1.5 сек с 22% → 12%.
  - **Scope**: skeleton cards, shimmer animation, accessible ARIA labels.
  - **Acceptance**: Lighthouse a11y ≥95; skeleton исчезает при получении данных <100 мс delay.
  - **A/B**: нет.
  - **Metric**: session drop-off при `miniapp_auth` без последующего `portfolio_import`.
- **Task**: Retry CTA for import errors
  - **Impact**: +10 pp import retry rate, -25% support tickets.
  - **Scope**: новый state с кнопкой "Повторить" и ссылкой на FAQ.
  - **Acceptance**: error screen локализован RU/EN; analytics event `portfolio_import` c prop `status=retry`.
  - **A/B**: да, holdback 10% для контроля.
  - **Metric**: retry→success conversion (`portfolio_import` success / retry).

## Import Flow Polish
- **Task**: CSV auto-mapping wizard
  - **Impact**: -25% ошибок формата, +12% `import_success`.
  - **Scope**: автоматическое сопоставление колонок, превью 10 строк, подсказки по валюте.
  - **Acceptance**: unit coverage >80%, QA чек-лист (разные брокеры), support макрос обновлён.
  - **A/B**: rollout 20% → 100% после 2 недель по SLO.
  - **Metric**: `portfolio_import` success rate.
- **Task**: Inline validation for manual entries
  - **Impact**: уменьшение отказов при ручном вводе -18%.
  - **Scope**: проверка ticker, даты, количества; mask для чисел.
  - **Acceptance**: error rate <2%; доступность (screen reader) подтверждена.
  - **A/B**: нет.
  - **Metric**: manual entry completion (`miniapp_manual_entry`) / start.

## Performance
- **Task**: Client bundle trimming
  - **Impact**: P95 загрузки Mini App <2.5 сек на 3G.
  - **Scope**: code splitting, tree-shake charts lib, lazy load heatmap.
  - **Acceptance**: bundle size ≤450 KB gz; WebPageTest median <3 сек.
  - **A/B**: нет.
  - **Metric**: Prometheus synthetic `miniapp_tti`.
- **Task**: Edge caching for digest feed
  - **Impact**: снижение API latency 30%, удержание CTR.
  - **Scope**: Cloudflare worker, cache key per locale/tier, purge webhook.
  - **Acceptance**: latency <250 мс P95; cache hit rate ≥75%.
  - **A/B**: да, gradual rollout 20% traffic, сравнение CTR digest.
  - **Metric**: `alerts_push_sent` → `cta_click` conversion.

## i18n / a11y
- **Task**: Full RU/EN parity for paywall & billing
  - **Impact**: +5 pp conversion среди EN аудитории.
  - **Scope**: ICU messages, QA checklist, screenshot diffs.
  - **Acceptance**: linguist sign-off, automated screenshot diff, no untranslated keys.
  - **A/B**: нет.
  - **Metric**: `cta_click` rate EN locale.
- **Task**: Screen reader navigation audit
  - **Impact**: соответствие WCAG 2.1 AA, снижение bounce для users с assistive tech.
  - **Scope**: landmarks, focus trap, keyboard order на модалках.
  - **Acceptance**: axe-core score 100; manual QA с NVDA/VoiceOver.
  - **A/B**: нет.
  - **Metric**: support tickets по доступности (<3/мес).
- **Task**: High-contrast toggle
  - **Impact**: -20% ошибок при вводе у пользователей с нарушением зрения.
  - **Scope**: настройка в профиле, сохранение в user prefs, соответствующий CSS variables.
  - **Acceptance**: contrast ratio ≥7:1; persists через relogin.
  - **A/B**: нет.
  - **Metric**: активные пользователи high-contrast (prefs) ≥5%.
