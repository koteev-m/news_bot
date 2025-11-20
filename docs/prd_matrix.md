# Матрица соответствия PRD → код

| Фича | Статус | Файлы |
| --- | --- | --- |
| Deeplink registry (?start / ?startapp) | PARTIAL | [growth/DeepLinkRegistry.kt](../app/src/main/kotlin/growth/DeepLinkRegistry.kt) |
| Stars Billing getMyStarBalance | PARTIAL | [billing/stars/StarBalance.kt](../app/src/main/kotlin/billing/stars/StarBalance.kt) |
| Воронка Post→Click→Start, SLO | PARTIAL | [observability/funnel/FunnelMetrics.kt](../app/src/main/kotlin/observability/funnel/FunnelMetrics.kt) |
| Остальные элементы PRD | MISSING | Требуют реализации |

## Пояснения
- Реестр deeplink валидирует base64url payload и лимиты 64/512 байт.
- Stars billing добавлен интерфейс баланса и заготовка репозитория подписок.
- Метрики воронки используют Micrometer счётчики и таймер для SLO.
