# Runbook: Import by URL DoS mitigation

- **Severity guidance**: SEV-1 при массовом отказе `/api/portfolio/{id}/trades/import/by-url` приводящем к деградации всей очереди; SEV-2 при целевом DoS одного клиента; SEV-3 при обнаружении подозрительного источника без видимого ущерба.
- **Impact**: канал импорта сделок через URL (CSV) блокируется, клиенты не могут обновить портфель, инфраструктура перегружена.

## Контекст
- Маршрут: `routes/PortfolioImportRoutes.kt` (`post("/api/portfolio/{id}/trades/import/by-url")`).
- Guard: `security/UploadLimits.kt` (`installUploadGuard()` в `App.kt`).

## First Response Checklist
1. Подтвердите алерт/инцидент, уведомите Incident Commander.
2. Включите rate limit через API gateway (если доступно) и зафиксируйте источник (IP/host без PII).

## Диагностика
1. Проверить логи:
   ```bash
   docker compose logs -f app | grep "import-by-url"
   kubectl logs deploy/newsbot-app -c app --since=10m | grep "import"
   ```
2. Проверить размер входящих файлов (метрики UploadGuard, логи с `payload_too_large`).
3. Проверить очереди задач или нагрузку БД.
4. Audit логи: убедиться, что PII не сохраняется; фиксируем только хэши URL или ID пользователя.

## Действия по смягчению
1. **Смещение лимитов UploadGuard**: обновить `upload.csvMaxBytes` в конфиге (через ConfigMap/Secret) и выполнить `kubectl rollout restart deploy/newsbot-app`.
2. **Блокировка источника**: добавить правило в WAF/ingress для подозрительного домена/IP.
3. **Временное отключение by-url**: активировать feature flag (env `IMPORT_BY_URL_DISABLED=true`) или временно вернуть HTTP 503. При отсутствии флага — закомментировать маршрут и деплоить hotfix.
4. **Очистка очереди**: при использовании внешнего сториджа очистить отложенные задания (см. `storage` модуль).

## Восстановление сервиса
- После снижения нагрузки вернуть лимиты UploadGuard.
- Подтвердить нормализацию SLI (latency/5xx).
- Провести ретроспективу и при необходимости внедрить captcha/авторизацию на импорт.

## Пост-инцидент
- Заполнить шаблон инцидента и обновить `docs/ONCALL_ROTATION.md` handover (если применимо).
- Описать принятые меры в пост-мортеме и добавить задач по автоматическому rate limiting.
