# P87.verify — Global Failover GameDay (RTO/RPO/DNS/k6)

## Цели
- Проверить **RTO** (время до восстановления 200/OK на SECONDARY).
- Проверить **RPO** (потеря данных / лаг репликации).
- Зафиксировать **DNS переход** и TTL.
- Убедиться, что пользовательские пути выдерживают переключение (k6 smoke).

## Подготовка
- PRIMARY и SECONDARY развернуты и доступны.
- Route53 настроен на failover по `/healthz`.
- У вас есть kubeconfig PRIMARY (base64 в GitHub Secrets).

## Процедура
1. Запустите workflow **Global Failover Verify (GameDay)**:
   - `hostname`: глобальный DNS.
   - `expected_cname` (по желанию): CNAME SECONDARY.
   - `base_url`: `https://<hostname>`.
   - `primary_kubeconfig_secret`: секрет с kubeconfig (base64).
2. Workflow:
   - Скейлит ingress на PRIMARY → health-check падает.
   - Запускает `dns_probe.sh` и `measure_rto.sh`.
   - Параллельно крутит `k6` smoke с мягкими порогами.
3. Скачайте артефакты: `dns_probe.csv`, `rto.env`.

## Интерпретация
- **RTO_SECONDS** ≤ целевой (например, ≤ 120s).
- **dns_probe.csv** — когда CNAME/TTL сменился на SECONDARY.
- **k6 thresholds** — error rate < 5%, p95 < 2.5s (см. CI).
- **RPO**: выполните `tools/ha/measure_rpo.sh` локально с доступом к БД:
  ```bash
  PRIMARY_URL=postgres://... SECONDARY_URL=postgres://... \
  bash tools/ha/measure_rpo.sh
  ```
  Стремиться к секундам/десяткам секунд.

## Failback
- Верните ingress на PRIMARY (`kubectl scale ... --replicas=1`) и дождитесь green.
- Дождитесь автоматического возврата Route53 на PRIMARY по health-check или переключите вручную.

## Безопасность и ограничения
- Не запускайте GameDay в пиковое время.
- Проверьте Alertmanager и SLO burn — не должен превышать пороги на длительный период.
- Следите за стоимостью: traffic во второй регион = деньги (см. P69).
