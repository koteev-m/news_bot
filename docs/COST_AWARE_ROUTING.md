# P88 — Cost-aware Global Routing

## Цель
Минимизировать затраты и углеродный след при сохранении SLA:
- трафик направляется в «зелёный» регион, если стоимость < порога и latency OK.
- ежедневный пересчёт регионов на основе FinOps-метрик (P69).

## Поток
1. Скрипт `regions_cost_fetch.py` формирует `finops_regions.json`.  
2. `choose_region.py` выбирает регион с минимальным комбинированным score.  
3. Terraform обновляет Route53 / Cloudflare записи (`preferred_region` → PRIMARY).  
4. CI workflow выполняется ежедневно или вручную.

## Метрики
- `finops_region_cost_usd{region}`, `carbon_factor_g` — Prometheus экспортеры.  
- KPI: `$ per 1k req`, `gCO₂ / req`, `effective_latency_ms`.

## Политика
- Не переключаться чаще, чем 1× в день.  
- Не опускать SLA (latency < 250 мс P95).  
- Логировать все решения (governance evidence → P74/P86).

```bash
gh workflow run "Cost-aware Global Routing"
```
