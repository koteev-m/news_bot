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
- `alert_delivered_total{reason=AlertDeliveryReasons.DIRECT|AlertDeliveryReasons.QUIET_HOURS_FLUSH|AlertDeliveryReasons.PORTFOLIO_SUMMARY}` (values: `direct|quiet_hours_flush|portfolio_summary`)
- `alert_suppressed_total{reason=AlertSuppressionReasons.COOLDOWN|AlertSuppressionReasons.BUDGET|AlertSuppressionReasons.QUIET_HOURS|AlertSuppressionReasons.DUPLICATE|AlertSuppressionReasons.NO_VOLUME|AlertSuppressionReasons.BELOW_THRESHOLD}` (values: `cooldown|budget|quiet_hours|duplicate|no_volume|below_threshold`)

The FSM is exposed via internal routes:
- `POST /internal/alerts/snapshot` to drive the state machine.
- `GET /internal/alerts/state?userId=<id>` to inspect per-user state.
