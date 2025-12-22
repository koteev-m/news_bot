# Netflow2 ingestion (prep)

Подготовительный слой для Netflow2 (без реального HTTP ingestion):

* **Доменные модели**: `Netflow2Row` хранит (date, ticker, p30/p70/p100, pv30/pv70/pv100, vol, oi).
* **Окна**: `Netflow2PullWindow.split(fromInclusive, tillExclusive)` режет диапазон на окна ≤3 лет (максимум 1000 строк на запрос у MOEX). Левая граница включительно, правая — эксклюзивно, чтобы не было пересечения. `splitInclusive` и `ofInclusive` позволяют явно работать с включительной правой границей; `toMoexQueryParams()` возвращает пару (from, tillInclusive) для HTTP-параметров.
* **База**: миграции `V21__moex_netflow2.sql`/`V22__moex_netflow2_realign.sql` создают `moex_netflow2` с PK(date, ticker), CHECK на непустой после trim ticker, индексом по (ticker, date) и COMMENT. `V22` аккуратно отрабатывает сценарий с устаревшей таблицей (sec → ticker) и гарантирует наличие CHECK-constraint даже если таблица появилась в `V21`. Upsert через `ON CONFLICT` идемпотентен.
* **Репозиторий**: `Netflow2Repository` + `PostgresNetflow2Repository` делают batch upsert в Postgres, нормализуя ticker в верхний регистр.
* **Клиент**: `Netflow2Client` — заготовка с таймаут-дедлайном, retry с jitter, circuit breaker и Sequence API (`windowsSequence`).
* **Метрики**: `FeedMetrics`/`Netflow2Metrics` публикуют `feed_pull_success_total`, `feed_pull_error_total`, `feed_last_ts`, `feed_pull_latency_seconds` с лейблом `src="netflow2"`.

## Быстрый запуск тестов

```bash
# Unit-тесты (без Docker)
./gradlew core:test storage:test --console=plain

# Интеграционные тесты (Docker/Testcontainers обязателен)
./gradlew storage:integrationTest --console=plain
```

Unit-тесты не требуют внешних сервисов. Integration-тесты для репозитория используют Testcontainers PostgreSQL; нужен Docker (они корректно пропускаются, если его нет).
