# P50 — OpenTelemetry traces (OTLP → Tempo/Jaeger)

## Включение / Enablement
- Конфиг `tracing.enabled=true`, `tracing.otlp.endpoint=http://tempo:4317`, `tracing.sampler.ratio=0.1`.
- По умолчанию сервисное имя: `newsbot-app` (можно задать `OTEL_SERVICE_NAME`).

## Слои / Layers
- **Ktor SERVER spans**: перехватчик создаёт спан на каждый запрос, добавляет `X-Request-Id`/`Trace-Id` в ответ, несёт атрибуты метода/пути/статуса.
- **Ktor Client CLIENT spans**: плагин создаёт клиентские спаны и инжектит `traceparent` (W3C) в исходящие запросы.

## Экспорт / Export
- **OTLP gRPC** на Tempo/Jaeger endpoint `:4317`. Пример docker compose: `deploy/monitoring/docker-compose.tracing.yml`.

## Тесты / Tests
- InMemorySpanExporter проверяет, что спаны создаются (server) и traceparent инжектится (client).

## Быстрый старт / Quick start
```bash
# Поднять Tempo+Grafana (в сети compose_default)
docker compose -f deploy/monitoring/docker-compose.tracing.yml up -d

# Включить трассировку
export TRACING_ENABLED=true
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```
