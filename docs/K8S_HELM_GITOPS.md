# P59 — Kubernetes (Helm) + GitOps

## Структура
- `helm/newsbot` — чарт: Deployment/Service/Ingress/HPA/PDB/Secrets (stringData placeholders)
- `k8s/overlays/*` — Kustomize оверлеи (пример)
- `tools/k8s/render.sh` — рендер чарта → `k8s/rendered/all.yaml`
- `tools/k8s/kubectl_apply.sh` — применить манифест

## Быстрый старт (staging)
```bash
helm upgrade --install newsbot helm/newsbot \
  --namespace newsbot-staging --create-namespace \
  -f helm/newsbot/values.yaml \
  --set image.repository=ghcr.io/ORG/REPO --set image.tag=rc
```

Параметры
- HPA: cpu utilization 70%, min=2, max=6
- PDB: minAvailable=1
- Ingress: класс nginx, TLS secret newsbot-tls

Секреты
- Файл templates/secret.yaml использует stringData; значения прокидываются через values.yaml или --set-file/--set-string (не храните реальные секреты в репозитории).

GitHub Actions
- deploy-staging.yml / deploy-prod.yml — Helm upgrade на нужное пространство имен.
