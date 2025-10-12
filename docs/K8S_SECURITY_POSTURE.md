# P64 — K8s security posture (OPA Gatekeeper + kube-linter + kube-bench)

## Gatekeeper
- Установка: workflow **Install OPA Gatekeeper**
- Шаблоны: `k8s/gatekeeper/templates/*` (ConstraintTemplates)
- Правила (staging/prod): `k8s/gatekeeper/constraints/*`
- Темы:
  - required labels
  - allowed repos
  - no privileged
  - drop NET_RAW
  - require probes
  - require requests/limits
  - disallow hostPath

## kube-linter (CI)
- Рендер Helm → YAML и статическая проверка манифестов.
- Workflow: `.github/workflows/kube-linter.yml`

## kube-bench (CIS)
- Запуск в кластере как Job: `k8s/kube-bench/job.yaml` (ручной старт).
- Сбор выводов вручную/через лог-агента.

## Порядок внедрения
1. Установить Gatekeeper, применить `ConstraintTemplate` и constraints в `staging`.
2. Исправить нарушения, затем раскатить в `prod`.
3. Включить CI `kube-linter` для PR.
4. Выполнить `kube-bench` и составить план по устранению замечаний.

> Не храните реальные секреты/DSN в репозитории. Политики «Enforce» можно временно переключать в `dryrun` на этапе адаптации.
