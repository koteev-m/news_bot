# P72 — Continuous Verification (Release Gates)

## Gates
1. **Availability**: `/healthz`, `/health/db` → 200.
2. **SLO**: p95, error rate, burn-rate (Prometheus).
3. **Observability**: Loki error-rate, Tempo доступность, `/metrics`.
4. **k6 smoke**: быстрый e2e путь.
5. **API Contract**: Redocly diff с `main`.
6. **LHCI**: per-page метрики (perf/a11y).

Пороги настраиваются в `cv/thresholds.yml`.

## Запуск
- Вручную:
  ```bash
  gh workflow run "Continuous Verification (Release Gates)" \
    -f base_url=https://staging.example.com \
    -f prom_url=https://prom.example.com \
    -f loki_url=https://loki.example.com \
    -f tempo_url=https://tempo.example.com
  ```

Интеграция с релизами
	•	Рабочие процессы релиза (P58) могут вызывать continuous-verification.yml через workflow_call после выката на RC/Canary.

Практика
	•	Держите гейты строгими для prod и мягче для staging.
	•	Храните историю cv-report как артефакты, добавляйте ссылки в релизные заметки.
