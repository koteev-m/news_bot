# P66 — Resilience & Chaos (Litmus + SLO guard + Rollouts)

## Эксперименты
- `k8s/chaos/*.yaml`: pod-delete, network-latency, cpu-hog — применяются в `newsbot-staging`.

## SLO Guard
- `tools/chaos/slo_guard.sh` — проверяет burn-rate SLO из Prometheus; если превышен — job падает.

## CI
- Workflow **Chaos Smoke (staging)** — запускает эксперимент, ждёт, проверяет SLO и состояние Rollout.

## Практика
- Запускайте хаос-тесты по расписанию (nightly/weekly) на staging.
- Фиксируйте выводы в runbook и улучшайте бюджеты/автооткаты.
