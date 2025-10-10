# P61 — Argo Rollouts Canary + Analysis + Auto-rollback

## Состав
- Helm-чарт `newsbot`: флаг `rollouts.enabled=true` переключает **Deployment → Rollout**.
- Сервисы `stable`/`canary`.
- AnalysisTemplate `slo-analysis`: проверяет p95 и 5xx rate через Prometheus.

## Канареечные шаги
В `values.yaml`:
```yaml
rollouts:
  enabled: true
  canary:
    steps:
      - setWeight: 10
      - pause: { duration: 60 }
      - analysis: { templates: [ "slo-analysis" ] }
      - setWeight: 30
      - pause: { duration: 120 }
      - analysis: { templates: [ "slo-analysis" ] }
      - setWeight: 60
      - pause: { duration: 120 }
      - analysis: { templates: [ "slo-analysis" ] }
      - setWeight: 100
```

Команды
• Установка контроллера: workflow Install Argo Rollouts.
• Продвижение/пауза/отмена: workflow Rollouts Promote/Abort.

Локально:

kubectl-argo-rollouts get rollout newsbot-newsbot -n newsbot-staging
kubectl-argo-rollouts promote newsbot-newsbot -n newsbot-staging

Мониторинг
• Метрики контроллера на :8090/metrics.
• Анализ использует Prometheus (/metrics приложения уже экспортируется).
