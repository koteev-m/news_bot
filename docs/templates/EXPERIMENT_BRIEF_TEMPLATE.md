# Experiment Brief — {{Experiment key}}

## Hypothesis
Кратко сформулированная гипотеза с ожидаемым влиянием на KPI.

## Variants
- Control (A): описание
- Treatment (B/...): описание

## Assignment & Traffic Split
- Ключ эксперимента (`cta_copy`, `digest_layout`, `import_hint`, etc.)
- Целевая аудитория, фильтры (tier, locale)
- Доли трафика (%), holdout/guardrail сегменты

## Sample Size Calculation
- Базовая метрика (например, CTR=0.16)
- Желаемый uplift (например, +0.03)
- Альфа/бета (0.05 / 0.2)
- Формула приблизительно: `n_per_variant = 16 * p * (1 - p) / delta^2`
- Приложите расчёт и источник данных

## Success Metrics
- Primary: событие (`cta_click`, `stars_payment_succeeded`, `import_success`)
- Secondary: guardrails (NPS, churn, latency)

## Guardrails
- Верхние/нижние пороги отмены (stop-loss).
- Мониторинг (Prometheus, SQL).

## Duration & Schedule
- Старт/стоп даты
- Минимальная длительность (≥14 дней или ≥2 полных цикла)

## Analysis Plan
- Статистика: Frequentist/Bayesian, инструмент (Python notebook / Metabase)
- Сегментация: tier, locale, referral
- Как документируется решение (issue с лейблом `experiment`)

## Launch Checklist
- [ ] Фича-флаг или конфиг создан
- [ ] QA / smoke пройден
- [ ] Dashboards обновлены
- [ ] Stakeholders уведомлены
- [ ] Decision log шаблон подготовлен
