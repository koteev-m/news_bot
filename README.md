## P61 — Progressive Delivery (Argo Rollouts)

- Включить в чарте: `rollouts.enabled=true`.
- Анализ шаблоны: `k8s/rollouts/analysis-templates.yaml`.
- Контроллер/дашборд: workflow **Install Argo Rollouts**.
- Управление канареечными шагами: workflow **Rollouts Promote/Abort**.

Быстрый старт:
```bash
helm upgrade --install newsbot helm/newsbot \
  --namespace newsbot-staging --create-namespace \
  --set rollouts.enabled=true
kubectl apply -f k8s/rollouts/analysis-templates.yaml
```
