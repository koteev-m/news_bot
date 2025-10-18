# P89 — Multi-tenant Isolation Policies

## Цель
- Гарантировать безопасность и предсказуемое использование ресурсов для каждого тенанта.  
- Предотвратить утечки трафика и «ресурсное захватывание».

## Что включено
- **NetworkPolicy** — изоляция Ingress/Egress по namespace (tenant label).  
- **ResourceQuota** — жёсткие лимиты CPU/Mem/PVC.  
- **LimitRange** — дефолтные requests/limits для подов.  
- **PriorityClass** — управление fair-share (кто важнее).  
- **Kyverno/OPA policy** — запрет cross-tenant взаимодействия.  
- **CI-workflow** — проверка политик и YAML валидность.

## Рекомендации
- Настроить мониторинг ResourceQuota events в Prometheus.  
- Добавить `tenant.id` в MDC/логирование (P84).  
- Раз в квартал проверять квоты против фактических пиков.  

## Пример
```bash
kubectl apply -f k8s/tenants/
kubectl apply -f k8s/policies/
```
