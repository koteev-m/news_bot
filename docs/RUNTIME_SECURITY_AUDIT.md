# P65 — Runtime security & audit visibility

## Falco
- Установка: workflow **Install Falco**, values в `helm/falco/values.yaml`
- Пользовательские правила: `k8s/falco/rules/falco_rules.local.yaml`
- Отправка событий → Loki (через Falcosidekick)

## Kubernetes Audit
- Политика: `k8s/audit/audit-policy.yaml`
- Настройка зависит от провайдера; цели — audit logs в Loki/Cloud.

## Promtail/Loki
- Пайплайн: `promtail.yml` собирает `falco`, `kube-audit`, `app` логи в разные лейблы.

## CI
- `falcoctl-test.yml` — тест валидности правил.

## Аналитика
- Рекомендуется отдельная Grafana dashboard для Falco событий и audit logs с фильтрами по `namespace`/`user`/`verb`.
