# P48 — Trace IDs & Correlation

- **Nginx** присваивает `X-Request-Id` (если отсутствует) из `$request_id` и прокидывает в upstream.
- **Ktor** (Observability): читает `X-Request-Id`/`traceparent`, генерирует UUID при отсутствии; добавляет `X-Request-Id`/`Trace-Id` в **ответ**; кладёт в MDC и корутинный контекст (`TraceContext`).
- **Integrations HttpClient** автоматически добавляет `X-Request-Id`/`Trace-Id` из текущего `TraceContext` в исходящие запросы.
- **k6 synthetics**: пример `deploy/load/k6/with_request_id.js` — посылает `X-Request-Id` для удобной корреляции.

## Быстрый запуск
```bash
# локально, проверить эхо в ответах
curl -sI -H 'X-Request-Id: test-req-123' http://localhost:8080/healthz

# k6
BASE_URL=http://localhost:8080 k6 run deploy/load/k6/with_request_id.js
```

Греп в логах

Искать по `requestId` в MDC (CallLogging), `X-Request-Id` в Nginx access-логах.
