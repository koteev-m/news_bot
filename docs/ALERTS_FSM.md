# Alerts FSM v2

This module implements a simple anti-noise finite state machine for market alerts with quiet hours, cooldowns, budget control, hysteresis, a volume gate, and portfolio summary triggers.

## States
- `IDLE`
- `ARMED(armedAtEpochSec)`
- `COOLDOWN(untilEpochSec)`
- `QUIET(buffer)`
- `BUDGET_EXHAUSTED`

Portfolio summary alerts are still generated as `portfolio_summary` pending alerts when large daily moves or drawdowns occur, but they do not use a dedicated FSM state.

## Configuration
- Confirmation window (`confirmT`): 10–15 minutes for fast signals (daily signals are immediate).
- Cooldown (`cooldownT`): 60–120 minutes after a push.
- Quiet hours: 23:00–07:00 local time (start inclusive, end exclusive). Alerts buffer during this window and flush once after 07:00.
- Daily budget: up to six pushes per user per calendar day.
- Hysteresis: exit `ARMED` when the signal drops below 75% of the trigger threshold.
- Volume gate: requires `volume >= k * avgVolume` when both are provided.
- Thresholds: class/window thresholds optionally scaled by `proK = clamp(ATR/σ, 0.7..1.3)`.

Morning flush respects budget: buffered alerts are delivered up to the daily budget, leftovers are suppressed with reason `budget`. After a flush, the FSM enters `COOLDOWN` when at least one alert is delivered and budget remains, otherwise `BUDGET_EXHAUSTED`.

Each suppression reason is only counted once per snapshot.

Candidate selection ordering: highest score (`pctMove - threshold`), then `daily` before `fast`, then `classId` and `ticker` lexicographically.

## Metrics
- `alert_fire_total{class,ticker,window}`
- `alert_delivered_total{reason=direct|quiet_hours_flush|portfolio_summary}`
- `alert_suppressed_total{reason=cooldown|budget|quiet_hours|duplicate|no_volume|below_threshold}`

Metric label values are defined in `app/src/main/kotlin/alerts/AlertReasons.kt`, which is the source of truth.

The FSM is exposed via internal routes:
- `POST /internal/alerts/snapshot` to drive the state machine.
- `GET /internal/alerts/state?userId=<id>` to inspect per-user state.

## Persistence
- FSM state is stored in `alerts_fsm_state(user_id, state_json, updated_at)`.
- Daily budgets are stored in `alerts_daily_budget(user_id, day, push_count, updated_at)` with a non-negative check on `push_count`.
- The Postgres-backed repository is the default when `db.jdbcUrl` is configured. The service fails fast on DB init/ping errors unless `alerts.allowMemoryFallbackOnDbError=true` is explicitly set (dev-only safety valve).

## Security
- Internal routes require the `alerts.internalToken` configuration and the `X-Internal-Token` header.
- If the token is unset or blank, the endpoints return **503 Service Unavailable** with `{ "error": "internal token not configured" }`.
- If the header is missing or invalid, the endpoints return **403 Forbidden** with `{ "error": "forbidden" }` before any business validation.

## Configuration example

```
alerts {
  internalToken = "super-secret"
  allowMemoryFallbackOnDbError = false
  quietHours { startHour = 23, endHour = 7 }
  dailyBudgetPushMax = 6
  hysteresisExitFactor = 0.75
  volumeGateK = 1.0
  confirmT { minMinutes = 10, maxMinutes = 15 }
  cooldownT { minMinutes = 60, maxMinutes = 120 }
  zoneId = "UTC"
  thresholds {
    breakout { fast = 1.2, daily = 2.5 }
    mean_revert { fast = 0.8, daily = 1.8 }
  }
}
```
