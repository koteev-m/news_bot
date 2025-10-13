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
