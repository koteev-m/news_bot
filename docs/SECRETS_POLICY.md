# P30 — Secrets & ENV Policy / Политика секретов

## 1. Цель / Scope
**EN:** Define a unified policy for managing secrets and environment variables across modules `:app`, `:bot`, `:miniapp`, `:monitoring`, and deployment tooling. Owners: Platform & Security team. Secrets cover Telegram bot tokens, JWT signing material, database credentials, monitoring webhooks, stage load credentials, and admin passwords.

**RU:** Определяет единые правила хранения и использования секретов и переменных окружения для модулей `:app`, `:bot`, `:miniapp`, `:monitorинг`, а также deployment-инструментов. Ответственные — команда Platform & Security. К секретам относятся токены Telegram-бота, JWT-секреты, учетные данные БД, вебхуки алертов/Slack, данные стейджа для нагрузочных тестов и админские пароли.

## 2. Матрица секретов
| Имя | Назначение | Где хранить | Кто имеет доступ | Срок / ротация |
| --- | --- | --- | --- | --- |
| TELEGRAM_BOT_TOKEN | Авторизация Telegram-бота (`:bot`, `:app`) | GitHub Secrets / Vault / локально в `.env` | Bot maintainers, on-call engineers | При смене владельца/инциденте или каждые 90 дней |
| TELEGRAM_WEBHOOK_SECRET | Подпись входящих Telegram вебхуков (`:app`) | GitHub Secrets / Vault / локально в `.env` | Platform team, on-call engineers | При утечке, при обновлении бота |
| JWT_SECRET | Подпись и проверка JWT в `:app`, `:miniapp` (память) | GitHub Secrets / Vault / локально в `.env` | Platform & Security engineers | После инцидентов, минимум раз в 180 дней |
| DATABASE_URL | JDBC строка для `:app`, `:storage`, compose | GitHub Secrets / Vault / `.env` | Platform engineers, DBAs | При изменении инфраструктуры |
| DATABASE_USER | Пользователь базы данных | GitHub Secrets / Vault / `.env` | Platform engineers, DBAs | При смене персонала, ежегодно |
| DATABASE_PASS | Пароль пользователя базы данных | GitHub Secrets / Vault / `.env` | Platform engineers, DBAs | При смене персонала, каждые 90 дней |
| ALERT_WEBHOOK_URL | Интеграция алертов (`:alerts`) | GitHub Secrets / Vault / `.env` | SRE/Alerts owners | При утечке канала |
| SLACK_WEBHOOK_URL | Slack-оповещения мониторинга | GitHub Secrets / Vault / `.env` | SRE/Alerts owners | При утечке или смене канала |
| STAGE_BASE_URL | URL стейджа для нагрузочных тестов | GitHub Secrets (`load-nightly`) | QA, Load-test owners | По мере обновления стенда |
| STAGE_JWT | JWT стейджа для k6 сценариев | GitHub Secrets (`load-nightly`) | QA, Load-test owners | Каждые 60 дней или при утечке |
| STAGE_WEBHOOK_SECRET | Секрет подписи стейджа | GitHub Secrets (`load-nightly`) | QA, Load-test owners | При компрометации |
| STAGE_TG_USER_ID | Тестовый Telegram user ID для нагрузочных тестов | GitHub Secrets (`load-nightly`) | QA, Load-test owners | По запросу / при изменении тестовых аккаунтов |
| GF_SECURITY_ADMIN_PASSWORD | Админский пароль Grafana (`deploy/monitoring`) | GitHub Secrets / Vault / `.env` | Monitoring team | Каждые 90 дней или при смене владельца |

## 3. Где хранить секреты
- **Локально:** используйте `.env` файлы в корне репозитория, `deploy/compose/.env`, `deploy/monitoring/.env`. Не коммитьте реальные значения.
- **CI:** храните значения в GitHub Environments/Secrets (например, `STAGE_*` для workflow `load-nightly`). Назначайте доступ по средам.
- **Прод:** используйте централизованное хранилище (HashiCorp Vault или SOPS + KMS). Значения синхронизируются в рантайм через секрет-менеджер.

## 4. Как прокидывать переменные
- **Docker Compose:**
  ```bash
  cp deploy/compose/.env.example deploy/compose/.env
  docker compose -f deploy/compose/docker-compose.yml --env-file deploy/compose/.env up -d
  ```
- **Gradle run (`:app`):**
  ```bash
  APP_PROFILE=local JWT_SECRET=local_secret TELEGRAM_BOT_TOKEN=token ./gradlew :app:run
  ```
- **k6 load tests:**
  ```bash
  BASE_URL=https://stage.example.com JWT=jwt_token APP_PROFILE=staging \
    k6 run deploy/load/k6/portfolio_ramp.js
  ```
- **Miniapp (Vite):** только публичные переменные с префиксом `VITE_`, приватные значения не встраиваются в бандл. Настраивайте через `.env.local` (не коммитить).

## 5. Redaction и логи
- В `logback` включена регулярная маска для редактирования токенов (маскирование значений, совпадающих с шаблонами токенов/паролей).
- Запрещено логировать PII и содержимое секретов в явном виде. Используйте идентификаторы запросов/хэши.
- Аудит выполняется скриптом `tools/audit/grep_checks.sh`, который ищет утечки токенов/ключевых слов. Запускайте его локально и в CI.

## 6. Ротация
- **Bot Token / JWT / DB Password:** ротация при смене ответственных, инцидентах, подозрении на утечку или по расписанию (90–180 дней).
- **Webhook Secrets (Telegram, Stage):** смена при утечке, после интеграционных изменений.
- **Grafana Admin:** обновление каждые 90 дней или при смене SRE on-call.
- Процедуру запускает владелец секрета; результаты фиксируются в change log.

## 7. Incident handling
1. Немедленно отозвать секрет (revoke/disable). Для Telegram — `/revoke` в BotFather, для JWT — смена ключа и инвалидирование сессий.
2. Обновить секреты в Vault/GitHub Secrets, перезапустить зависимые сервисы/воркфлоу.
3. Провести аудит логов и репозитория, задокументировать пост-мортем, уведомить команду безопасности.

## 8. Приложение: список переменных
| Имя | Модуль | Пример | Комментарий |
| --- | --- | --- | --- |
| APP_PROFILE | `:app`, `deploy/load` | `staging` | Контроль профиля (prod запрещен для нагрузочных тестов) |
| TELEGRAM_BOT_TOKEN | `:bot`, `:app` | `replace_me` | Токен Telegram Bot API |
| TELEGRAM_WEBHOOK_SECRET | `:app` | `replace_me` | HMAC подпись входящих webhook |
| TELEGRAM_CHANNEL_ID | `:news` | `-1000000000000` | Целевой Telegram-канал |
| JWT_SECRET | `:app`, `:miniapp` (runtime) | `replace_me` | Ключ подписи JWT (хранится только на сервере) |
| POSTGRES_DB | `deploy/compose` | `newsbot` | Имя БД для compose |
| POSTGRES_USER | `deploy/compose` | `app` | Пользователь Postgres |
| POSTGRES_PASSWORD | `deploy/compose` | `app_pass` | Пароль Postgres |
| POSTGRES_PORT | `deploy/compose` | `5432` | Порт контейнера Postgres |
| DATABASE_URL | `:app`, `:storage` | `jdbc:postgresql://db:5432/newsbot` | JDBC строка подключения |
| DATABASE_USER | `:app`, `:storage` | `app` | Пользователь БД |
| DATABASE_PASS | `:app`, `:storage` | `app_pass` | Пароль БД |
| MOEX_BASE_URL | `:integrations` | `https://iss.moex.com` | Публичный URL MOEX |
| COINGECKO_API_BASE | `:integrations` | `https://api.coingecko.com` | Публичный URL CoinGecko |
| CBR_XML_URL | `:integrations` | `https://www.cbr.ru/scripts/XML_daily.asp` | Публичный URL ЦБ РФ |
| NGINX_HTTP_PORT | `deploy/compose` | `8081` | Внешний HTTP порт Nginx |
| NGINX_HTTPS_PORT | `deploy/compose` | `8443` | Внешний HTTPS порт Nginx |
| GF_SECURITY_ADMIN_PASSWORD | `deploy/monitoring` | `replace_me` | Пароль Grafana admin |
| ALERT_WEBHOOK_URL | `:alerts` | `http://example.com/hook` | Вебхук для алертов |
| SLACK_WEBHOOK_URL | `:alerts` | `https://hooks.slack.com/...` | Slack Incoming webhook |
| STAGE_BASE_URL | `deploy/load` | `https://stage.example.com` | URL стейджа для k6 |
| STAGE_JWT | `deploy/load` | `replace_me` | JWT стейджа |
| STAGE_WEBHOOK_SECRET | `deploy/load` | `replace_me` | Секрет для webhook тестов |
| STAGE_TG_USER_ID | `deploy/load` | `7446417641` | Тестовый Telegram user ID |
