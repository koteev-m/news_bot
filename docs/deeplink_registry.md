# Deeplink Registry (start / startapp)

## Limits
- `start` — до **64** символов, `A–Z a–z 0–9 _ -`, base64url без паддинга → decode обязателен.
- `startapp` — до **512** символов (рекоменд.), те же правила → decode обязателен.

## Каталог payload’ов (v1)

| ID               | JSON (минимум)                                      | Пример base64url (≤64)                                |
|------------------|------------------------------------------------------|-------------------------------------------------------|
| TICKER_SBER      | `{"v":1,"t":"w","s":"SBER","b":"TQBR","a":"A2"}`     | `eyJ2IjoxLCJ0IjoidyIsInMiOiJTQkVSIiwiYiI6IlRRQlIiLCJhIjoiQTIifQ` |
| TICKER_BTC_2PCT  | `{"v":1,"t":"w","s":"BTC","h":"2p","a":"B3"}`        | `eyJ2IjoxLCJ0IjoidyIsInMiOiJCVEMiLCJoIjoiMnAiLCJhIjoiQjMifQ`       |
| TOPIC_CBRATE     | `{"v":1,"t":"topic","i":"CBRATE","a":"C1"}`          | `eyJ2IjoxLCJ0IjoidG9waWMiLCJpIjoiQ0JSQVRFIiwiYSI6IkMxIn0`           |
| PORTFOLIO        | `{"v":1,"t":"p"}`                                    | `eyJ2IjoxLCJ0IjoicCJ9`                                               |

## Редиректы

- `/r/cta/{postId}?ab=A2&start=...` → `302` на `https://t.me/<bot>?start=...`  
  Метрика: `cta_click_total{post_id,ab,payload}` где `payload` — канонический ID.

- `/r/app?startapp=...` → `302` на `https://t.me/<bot>?startapp=...`

> Логи не содержат значение payload; только длины и валидность.
