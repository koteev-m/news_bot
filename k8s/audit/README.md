# Kubernetes Audit Policy

- Файл `audit-policy.yaml` — пример политики.
- Для managed кластеров (EKS/GKE/AKS) включение аудита делается на уровне control-plane.
- Рекомендуется слать kube-apiserver audit logs в Loki/Cloud Logs и ставить алерты на чувствительные операции.
