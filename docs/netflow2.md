# Netflow2 ingestion

Рабочий ingestion для MOEX Netflow2 с лимитом 1000 строк на запрос.

* **Доменные модели**: `Netflow2Row` хранит (date, ticker, p30/p70/p100, pv30/pv70/pv100, vol, oi).
* **Окна**: `Netflow2PullWindow.split(fromInclusive, tillExclusive)` режет диапазон на окна ≤3 лет (MOEX отдаёт не более 1000 строк). Левая граница включительно, правая — эксклюзивно; `splitInclusive` и `ofInclusive` удобны для работы с правой границей по включительно; `toMoexQueryParams()` возвращает пару (from, tillInclusive) для HTTP.
* **Клиент**: `data.moex.Netflow2Client` строит запросы к `/iss/analyticalproducts/netflow2/securities/{SEC}.csv|json?from=YYYY-MM-DD&till=YYYY-MM-DD`, добавляет таймаут, ретраи с экспоненциальным backoff+jitter, circuit breaker, парсит CSV (разделитель `;`, ищет строку заголовка с DATE/TRADEDATE и SECID/TICKER, пустые ячейки → null). Формат выбирается конфигом, по умолчанию CSV. Неизвестный `sec` даёт 404/ValidationError.
* **Сервис**: `Netflow2Loader` режет диапазон на окна ≤3 лет, вызывает клиент по каждому окну и upsert-ит строки через `PostgresNetflow2Repository`. Результат включает число окон/строк и `maxDate` реально загруженных данных.
* **База**: миграции `V21__moex_netflow2.sql`/`V22__moex_netflow2_realign.sql`/`V23__moex_netflow2_ticker_constraint.sql` создают `moex_netflow2` с PK(date, ticker) и CHECK на отсутствие whitespace в ticker. `V23` аккуратно чистит данные через временную таблицу и перестраивает constraint без риска конфликтов.
* **Метрики**: `FeedMetrics`/`Netflow2Metrics` публикуют `feed_pull_success_total`, `feed_pull_error_total`, `feed_last_ts`, `feed_pull_latency_seconds` с лейблом `src="netflow2"`. `feed_last_ts` обновляется по `max(date)` в реально загруженных строках.
* **Админ-роут**: `POST /admin/netflow2/load?sec=SBER&from=YYYY-MM-DD&till=YYYY-MM-DD` (защита `X-Internal-Token`). Ответ: `{ "sec": "...", "from": "...", "till": "...", "windows": N, "rows": M, "maxDate": "YYYY-MM-DD" }`. При неизвестном `sec` возвращает 404, при некорректном формате тикера — 400.

## Конфигурация

`application.conf` / env:

* `integrations.netflow2.baseUrl` — базовый URL ISS (по умолчанию `https://iss.moex.com`).
* `integrations.netflow2.format` — `CSV` или `JSON` (по умолчанию `CSV`).
* `integrations.netflow2.requestTimeoutMs` — таймаут на запрос (по умолчанию 30000 мс).
* Тикер `sec` должен соответствовать `^[A-Z0-9._-]+$` без whitespace; любые другие символы/пробелы приводят к 400.

## Быстрый запуск тестов

```bash
# Unit-тесты (без Docker)
./gradlew core:test storage:test :app:test --console=plain

# Интеграционные тесты (Docker/Testcontainers обязателен)
./gradlew storage:integrationTest :app:integrationTest --console=plain
```

Unit-тесты не требуют внешних сервисов. Integration-тесты используют Testcontainers PostgreSQL; нужен Docker (они корректно пропускаются, если его нет).
