# Synthetics & Uptime

## GitHub Actions (встроенные / built-in)
- Workflow **Uptime Synthetics** (`.github/workflows/uptime-synthetics.yml`) запускается каждые 15 минут и вручную через `workflow_dispatch`.
- Checks / проверки:
  - `GET /healthz` → ожидание 200 OK.
  - `GET /health/db` → ожидание 200 OK.
  - `GET /api/quotes/closeOrLast?instrumentId=1&date=2025-09-20` → допускается 200 или 404 (оба засчитываются как успех, сервис отвечает).
  - `POST /telegram/webhook` с заголовком `X-Telegram-Bot-Api-Secret-Token` и минимальным XTR-update → ожидание 200 OK.
- Политика фейла / failure policy:
  - Любой обязательный чек (`healthz`, `health/db`, `webhook`) со статусом ≠200 → workflow завершится с ошибкой.
  - Совокупно более одного сбоя из четырёх чеков → workflow завершается с ошибкой.
- Артефакты (сохраняются в Actions → Artifacts):
  - `synthetics_report.json` — подробные таймстемпы/коды.
  - `synthetics_junit.xml` — отчёт в формате JUnit для статуса релиза.

## Внешние мониторы / External monitors (UptimeRobot, Pingdom)
Рекомендуемые проверки (интервал 1–5 минут):

| Тип / Type | Метод / Method | URL | Ожидаемый статус / Expected status |
| --- | --- | --- | --- |
| HTTP | GET | `https://<host>/healthz` | 200 |
| HTTP | GET | `https://<host>/health/db` | 200 |
| HTTP | GET | `https://<host>/api/quotes/closeOrLast?instrumentId=1&date=2025-09-20` | 200 или 404 |
| HTTP | POST | `https://<host>/telegram/webhook` | 200 (секрет в заголовке) |

**Советы / Tips**
- Секрет `X-Telegram-Bot-Api-Secret-Token` храните внутри аккаунта мониторинга; не логируйте его.
- Тестовое тело запроса повторяет минимальный XTR-платёж, чтобы бэкенд вернул быстрый ACK.
- Для UptimeRobot используйте тип `Advanced HTTP` → метод `POST` → добавьте header и JSON body. Для Pingdom — `Custom HTTP` → `Request body` + `Custom header`.
- Алерты направляйте на email/on-call Slack webhook (не на общий чат).

## Запуск локально / Local run
1. `cp deploy/synthetics/.env.example deploy/synthetics/.env` — заполните значениями staging/production.
2. `export $(grep -v '^#' deploy/synthetics/.env | xargs)` — экспортируйте переменные (или используйте `direnv`).
3. `bash tools/synthetics/check_endpoints.sh` — выполнит проверки и создаст `synthetics_report.json` (exit code >0 при сбоях).
4. `node tools/synthetics/report_to_junit.js synthetics_report.json synthetics_junit.xml` — конвертация отчёта в JUnit.

## Security & Compliance
- Секреты и базовые URL передаются только через переменные окружения (`.env`, GitHub Secrets, настройки мониторинга). В репозитории нет токенов.
- Скрипты не печатают payload/секреты; статусные логи содержат только имя проверки, код ответа и факт таймаута.
- JUnit-отчёт хранит только HTTP-коды, что соответствует требованиям QA/Release.

## Troubleshooting / Диагностика
- Если падают `healthz`/`health/db`, проверьте сервис и базу данных (`kubectl logs`, `/metrics`).
- Для `webhook` убедитесь, что очередь Stars/Payments не backlog'ится и секрет совпадает.
- Изучите `synthetics_report.json` — там сохранены таймстемпы и `rc` (`curl` exit code). Это помогает отличить сетевой таймаут (124) от логической ошибки (404/500).
- История запусков доступна в GitHub Actions; используйте фильтр по workflow «Uptime Synthetics».
