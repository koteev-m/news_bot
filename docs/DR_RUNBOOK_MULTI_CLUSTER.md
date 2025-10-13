# P67 — Multi-Cluster DR (active–passive)

## Архитектура
- **Primary**: Argo CD `newsbot-primary` (in-cluster)
- **Secondary**: Argo CD `newsbot-secondary` (remote cluster API)
- **DNS failover**: Route53 Failover (PRIMARY health check `/healthz`, CNAME → ingress LB)
- **Backups**: S3 (versioning + lifecycle), PITR/restore покрыто в P31/P60

## Процедуры
### 1) Failover (вынужденный)
1. Убедиться, что `newsbot-secondary` — Synced/Healthy.
2. Инициировать failover:
   - вручную: «сломать» health check PRIMARY или
   - `terraform apply` для Route53, чтобы SECONDARY стал активным.
3. Проверить `/healthz`, `/metrics` на SECONDARY; SLO burn < порога.

### 2) Failback (возврат)
1. Восстановить PRIMARY, убедиться в Health + SLO ok.
2. Вернуть DNS на PRIMARY через Route53 (terraform apply).
3. SECONDARY остаётся в режиме standby (Argo CD synced).

## Тесты
- Еженедельный DR exercise: workflow **DR Exercise** (verify/failover/failback).
- Отчёт сохраняем в PR/issue “DR log”.

## Заметки
- Секреты не храним в Git — используем External Secrets (P60).
- Прогрессивные деплои (P61) остаются включёнными в обоих кластерах.
- SLO as Code (P63) — мониторим burn-rate в обоих регионах.
