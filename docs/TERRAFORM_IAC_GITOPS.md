# P60 — Terraform IaC + External Secrets + Argo CD (GitOps)

## Предпосылки
- Kubernetes кластер доступен через `~/.kube/config` (контекст по умолчанию).
- AWS credentials сконфигурированы (`aws configure`) или через переменные окружения `AWS_*`.

## Шаги
1. Инициализация:
   ```bash
   (cd terraform && terraform init)
   ```
2. План:
   ```bash
   (cd terraform && terraform plan -var='s3_backups_bucket=<unique-bucket>')
   ```
3. Применение:
   ```bash
   (cd terraform && terraform apply -var='s3_backups_bucket=<unique-bucket>')
   ```

## External Secrets
- ESO устанавливается Helm-чартом; `ClusterSecretStore` указывает на AWS Secrets Manager.
- Примеры `ExternalSecret` см. в `k8s/external-secrets/*`.

## Argo CD
- Helm-релиз argo-cd.
- Applications `newsbot-staging` и `newsbot-prod` смотрят на `helm/newsbot` в этом репозитории.
- Синхронизация: автоматическая с `prune` и `selfHeal`.

## CI
- `.github/workflows/terraform-plan.yml` — Terraform plan в PR.
- `.github/workflows/terraform-apply.yml` — ручное применение (workflow_dispatch).

> Не храните реальные секреты в репозитории. Используйте AWS Secrets Manager или SSM Parameter Store.
