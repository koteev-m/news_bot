# P54 — Error tracking & release health (Sentry)

## Backend (Ktor)
- DSN/ENV: `SENTRY_DSN`, `SENTRY_ENV`, `SENTRY_RELEASE`.
- Инициализация: `installSentry()`; события отправляются на уровне `ERROR` (Logback SentryAppender).
- PII: отключены (`sendDefaultPii=false`), `beforeSend` маскирует токены; traceId добавляется из MDC (`requestId`).

## Frontend (Mini App)
- DSN/ENV: `VITE_SENTRY_DSN`, `VITE_SENTRY_ENV`, `VITE_SENTRY_RELEASE`.
- Инициализация: `initSentry()` в `src/main.tsx`.
- Source maps upload — опциональный workflow `sentry-sourcemaps.yml` (нужны секреты Sentry).

## Быстрый smoke
```bash
# Backend: сгенерировать исключение
curl -s $BASE/api/trigger-error  # временный маршрут для smoke (локально)
# Frontend: открытие Mini App генерирует init; ошибки UI попадут в Sentry
```

Безопасность
- Не передавайте реальные токены в DSN/логах/в тестах.
- В Sentry не хранить PII; пользователи анонимизированы.

