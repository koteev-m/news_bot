## P63 — SLO as Code (Sloth)

- SLO спеки: `slo/api-slo.yaml`, `slo/miniapp-slo.yaml`
- Генерация правил: workflow **SLO Generate (Sloth)** → `deploy/monitoring/prometheus/slo.rules.yaml`
- Prometheus включает `slo.rules.yaml` в `rule_files`.
- Rollouts анализ: `k8s/rollouts/analysis-templates-slo.yaml`

Локально:
```bash
docker run --rm -v "$PWD":/work -w /work ghcr.io/slok/sloth:v0.11.0 \
  generate -i slo/api-slo.yaml -o deploy/monitoring/prometheus
```
