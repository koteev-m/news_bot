# Error Catalog

The API returns errors in a single JSON format:

```json
{
  "code": "ERROR_CODE",
  "message": "User-facing English message",
  "details": ["context strings"],
  "traceId": "request-or-trace-id"
}
```

* `code` — symbolic identifier in `UPPER_SNAKE_CASE`.
* `message` — English text from the catalog (the Mini App handles localisation on the client).
* `details` — optional context without PII, used by clients for hints.
* `traceId` — propagated from `X-Request-Id`/`Trace-Id` (CallId plugin).

The catalog lives in [`app/src/main/resources/errors/catalog.json`](../app/src/main/resources/errors/catalog.json) and is loaded
at runtime. Each entry defines an HTTP status and texts for English and Russian locales.

## Codes

| Code | HTTP | Message (EN) | Message (RU) |
| --- | --- | --- | --- |
| `BAD_REQUEST` | 400 | Invalid request. Please check the data and try again. | Некорректный запрос. Проверьте данные и повторите попытку. |
| `UNAUTHORIZED` | 401 | Your session has expired. Please restart the Mini App. | Сессия истекла. Пожалуйста, перезапустите Mini App. |
| `FORBIDDEN` | 403 | This action requires a higher plan. | Эта функция доступна на более высоком тарифе. |
| `NOT_FOUND` | 404 | Requested resource was not found. | Запрошенный ресурс не найден. |
| `UNPROCESSABLE` | 422 | We could not process this request. | Не удалось обработать запрос. |
| `RATE_LIMITED` | 429 | Too many requests. Please try again later. | Слишком много запросов. Попробуйте позже. |
| `PAYLOAD_TOO_LARGE` | 413 | Uploaded file is too large. | Загруженный файл слишком большой. |
| `UNSUPPORTED_MEDIA` | 415 | Unsupported file format. | Неподдерживаемый формат файла. |
| `INTERNAL` | 500 | Unexpected error. Please try again later. | Непредвиденная ошибка. Попробуйте ещё раз позже. |
| `CSV_MAPPING_ERROR` | 422 | CSV mapping failed. Check the column configuration. | Ошибка сопоставления CSV. Проверьте настройки колонок. |
| `SELL_EXCEEDS_POSITION` | 422 | Sell quantity exceeds your current position. | Объём продажи превышает текущую позицию. |
| `IMPORT_BY_URL_DISABLED` | 503 | Import by URL is temporarily unavailable. | Импорт по ссылке временно недоступен. |
| `CHAOS_INJECTED` | 503 | Chaos testing triggered an error. Please retry. | Хаос-тестирование вызвало ошибку. Повторите попытку. |
| `BILLING_DUPLICATE` | 409 | Payment was already processed. | Платёж уже был обработан. |
| `BILLING_APPLY_FAILED` | 502 | We could not apply the payment. Please contact support. | Не удалось применить платёж. Пожалуйста, обратитесь в поддержку. |

### Adding a new code

1. Extend `catalog.json` with the new entry (HTTP status + EN/RU messages).
2. Add a `KnownErrorCode` constant and fallback message in the Mini App (`miniapp/src/lib/errorMessages.ts`).
3. Provide translations in both `errors.en.json` and `errors.ru.json`.
4. Throw an appropriate `AppException` subclass (or `Custom`) on the server side.
5. Cover the new behaviour with tests when possible.
