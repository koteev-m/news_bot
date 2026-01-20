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
- `integrations.mtproto.baseUrl`
- `integrations.mtproto.apiKey`
- `MTPROTO_GATEWAY_BASE`
- `MTPROTO_GATEWAY_KEY` (опционально)

По умолчанию в `application.conf` эти ключи заполняются через env vars:

```hocon
integrations.mtproto.baseUrl = ${?MTPROTO_GATEWAY_BASE}
integrations.mtproto.apiKey = ${?MTPROTO_GATEWAY_KEY}
```

Пример блока конфигурации:

```hocon
integrations {
  mtproto {
    enabled = true
    baseUrl = "https://mtproto-gw.example.com"
    apiKey = "secret-key"
  }
}
```

`baseUrl` может быть как с trailing `/`, так и без него. Клиент сам добавляет путь
`/messages.getMessagesViews`, если `baseUrl` не заканчивается этим путём.

## Валидация запроса

- `channel`:
  - trim
  - добавляется префикс `@`, если его нет
  - приводится к lowercase через `Locale.ROOT`
- `ids`:
  - список через запятую
  - каждый id должен быть положительным int (> 0)
  - непарсабельные значения (не int) отбрасываются; `400` только если после фильтрации список пустой
  - дубликаты удаляются
  - максимум 1000 id в запросе после фильтрации/дедупликации
  - если после фильтрации список пустой → `400`

## Ответы и ошибки

- `200 OK` — JSON-объект вида `{ "<post_id>": <views> }`, где ключи строковые.
- `400 Bad Request` — отсутствует `channel`, отсутствует `ids`, все `ids` невалидны, либо gateway вернул validation error.
- `403 Forbidden` — неверный internal token.
- `501 Not Implemented` — MTProto отключён (`integrations.mtproto.enabled=false`) или сервис не инициализирован.
- `503 Service Unavailable` — internal token не настроен (`alerts.internalToken` пустой).
- `502 Bad Gateway` — ошибки gateway (HTTP статус, сеть, десериализация, прочие ошибки).
- `504 Gateway Timeout` — таймаут при запросе к gateway.

Примеры тела:

- `200 OK`: `{"10":123,"20":456}`
- error: `{"error":"..."}`

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
