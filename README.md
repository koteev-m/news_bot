# P60 — Terraform IaC + External Secrets + Argo CD

- Terraform: каталог `terraform/` разворачивает namespaces, External Secrets Operator, Argo CD и S3-бакет для бэкапов.
- External Secrets: примеры конфигураций в `k8s/external-secrets/*` для стейджинга и прода.
- Argo CD: приложения `newsbot-staging` и `newsbot-prod` указывают на Helm-чарт `helm/newsbot`.

## Команды
```bash
(cd terraform && terraform init && terraform plan -var='s3_backups_bucket=<unique-bucket>')
(cd terraform && terraform apply -var='s3_backups_bucket=<unique-bucket>')
```

## GitHub Actions
- Terraform Plan — выполняет план в PR (`.github/workflows/terraform-plan.yml`).
- Terraform Apply — ручное применение через workflow_dispatch (`.github/workflows/terraform-apply.yml`).
