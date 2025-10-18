# P87 — Multi-Region HA & Global Failover

## Архитектура
- **Regions:** `eu-central-1` (PRIMARY), `us-east-1` (SECONDARY)
- **Traffic:** Route53 Failover (HTTPS /healthz checks)
- **K8s:** topologySpreadConstraints, PriorityClasses, Rollouts
- **DB:** PostgreSQL logical replication (publication `newsbot_pub`, subscription `newsbot_sub`)
- **Monitoring:** Prometheus federation (EU/US → Global)

## Процедуры
### Проверка готовности
1. Развернуть приложение в обоих регионах.
2. Настроить Route53 через Terraform (`terraform/global`).
3. Проверить `/healthz` и логическую репликацию:
   ```bash
   bash tools/db/pg/check_replication.sh
   ```

### Плановая тренировка failover
1. Убедиться, что SECONDARY здоров.
2. Инициировать фейловер (настоящее падение или terraform apply для swap).
3. Проверить: global-health.yml workflow должен стать зелёным.

### Rollback / failback
1. Восстановить PRIMARY.
2. Вернуть Route53 на PRIMARY значением health-check.
3. Проверить федерированные метрики и SLO burn.

## Замечания
- Безопасность: mTLS в сервис-меше (см. P75), Kyverno verifyImages (P62).
- Данные: внимательно тестируйте DDL — logical replication транслирует операции INSERT/UPDATE/DELETE, но не всегда сложные DDL.
