# MTProto views sync

## Назначение

Синхронизация просмотров через MTProto gateway (режим `increment=false`).

## Endpoint

`GET /internal/post_views/sync?channel=@xxx&ids=1,2,3`

Требуется header `INTERNAL_TOKEN_HEADER` (тот же, что и для alerts internal routes).

## Конфиг/ENV

- `integrations.mtproto.enabled`
- `MTPROTO_GATEWAY_BASE`
- `MTPROTO_GATEWAY_KEY` (опционально)

## Семантика метрики

`post_views_total{post_id}` инкрементится на delta (только рост).

Delta-cache in-memory и per-instance:

- рестарт процесса сбрасывает baseline;
- при нескольких репликах без sticky routing возможны искажения.

## Важное предположение

`post_id` должен быть уникален в контексте использования; если `ids` — это
`message_id` и используются разные каналы, возможны коллизии.
