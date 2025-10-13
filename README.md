## P67 — Multi-Cluster DR (active–passive)

- Argo CD App-of-Apps: `k8s/argocd/app-of-apps/*`
- Route53 failover: `terraform/route53_dr.tf`
- Скрипты: `tools/dr/failover.sh`, `failback.sh`, `verify_pair.sh`
- CI: **DR Exercise (manual)**

Быстрый старт:
```bash
kubectl apply -f k8s/argocd/app-of-apps/root.yaml
(cd terraform && terraform init && terraform apply -var='zone_id=Z...'
  -var='record_name=app.example.com' -var='primary_dns=...' -var='secondary_dns=...')
```

## P68 — OTEL Collector + Tempo/Loki/Prometheus + FinOps

- Helm OTEL Collector: `helm/otel-collector/values.yaml`
- Dashboard: `deploy/monitoring/grafana/dashboards/golden-signals-and-cost.json`
- CI: **Observability Gate** (manual)

Пример:
```bash
gh workflow run "Install OTEL Collector"
gh workflow run "Observability Gate (OTLP/Tempo/Loki/Prometheus)" \
  -f base_url=https://app.example.com \
  -f tempo_url=http://tempo:3200 \
  -f loki_url=http://loki:3100 \
  -f prom_url=http://prom:9090
```
