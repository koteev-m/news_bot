# MTProto views sync

## Назначение

Синхронизация просмотров через MTProto gateway (режим `increment=false`).

## Endpoint

`GET /internal/post_views/sync?channel=@xxx&ids=1,2,3`

Доступ защищён internal token как у alerts internal routes:

- header `X-Internal-Token`
- значение берётся из `alerts.internalToken`

Пример конфигурации:

```hocon
alerts {
  internalToken = "super-secret"
}
```

При запуске можно передать как JVM property: `-Dalerts.internalToken=super-secret`.

## Конфиг/ENV

- `integrations.mtproto.enabled`
- `MTPROTO_GATEWAY_BASE`
- `MTPROTO_GATEWAY_KEY` (опционально)

## Валидация запроса

- `channel`:
  - trim
  - добавляется префикс `@`, если его нет
  - приводится к lowercase через `Locale.ROOT`
- `ids`:
  - список через запятую
  - каждый id должен быть положительным int (> 0)
  - дубликаты удаляются
  - максимум 1000 id в запросе
  - если после фильтрации список пустой → `400`

## Ответы и ошибки

- `200 OK` — JSON-объект вида `{ "<post_id>": <views> }`, где ключи строковые.
- `400 Bad Request` — отсутствует `channel`, отсутствует `ids`, все `ids` невалидны, либо gateway вернул validation error.
- `403 Forbidden` — неверный internal token.
- `501 Not Implemented` — MTProto отключён (`integrations.mtproto.enabled=false`) или сервис не инициализирован.
- `503 Service Unavailable` — internal token не настроен (`alerts.internalToken` пустой).
- `502 Bad Gateway` — ошибки gateway (HTTP статус, сеть, десериализация, прочие ошибки).
- `504 Gateway Timeout` — таймаут при запросе к gateway.

## Примеры

```bash
curl -sS \
  -H 'X-Internal-Token: super-secret' \
  'https://api.example.com/internal/post_views/sync?channel=NewsChannel&ids=10,20,30'
```

```bash
curl -sS \
  -H 'X-Internal-Token: wrong-token' \
  'https://api.example.com/internal/post_views/sync?channel=@news&ids=10' \
  -i
```

## Семантика метрики

`post_views_total{post_id}` инкрементится на delta (только рост).

Delta-cache in-memory и per-instance:

- рестарт процесса сбрасывает baseline;
- при нескольких репликах без sticky routing возможны искажения.

## Важное предположение

`post_id` должен быть уникален в контексте использования; если `ids` — это
`message_id` и используются разные каналы, возможны коллизии.
