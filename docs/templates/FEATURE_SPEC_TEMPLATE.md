# Feature Spec — {{Feature name}}

## Context
- Background / бизнес-проблема
- Связанные OKR / Roadmap Theme / Epic
- События аналитики (`miniapp_auth`, `cta_click`, `portfolio_import`, `stars_payment_succeeded`, и др.)

## UX Copy (RU/EN)
| Screen / Element | RU Copy | EN Copy |
| --- | --- | --- |

Добавьте строки для каждого экрана/состояния, убедившись в соответствии tone of voice.

## User Flow & UX Notes
- Скриншоты/макап ссылки
- A11y (контраст, фокус, screen reader)
- i18n (plural, валюты)

## Technical Outline
- Архитектура, сервисы, флаги, изменения API (REST/gRPC)
- Схемы (JSON, DB) и миграции
- Логирование/метрики/алерты (Prometheus, `events`)

## Experiments / Rollout
- Требуется ли A/B? (ключ `cta_copy`, `digest_layout`, `import_hint`, etc.)
- Трафик, длительность, stop-loss
- Фича-флаги и стадии (dev → beta → GA)

## Metrics & Analytics
- Primary KPI (пример: trial→pay rate, import_success)
- Secondary KPI / guardrails (NPS, churn, latency)
- SQL/дашборды (ссылки на P33/P21/P38)

## Definition of Done
- [ ] Функциональные тесты (unit/integration)
- [ ] UX/UI review пройден
- [ ] A11y/i18n проверены
- [ ] Документация/FAQ/alerts обновлены
- [ ] Мониторинги и алерты настроены
- [ ] Release checklist (P40) пройдено
