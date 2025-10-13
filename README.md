## P65 — Runtime security & audit visibility

- Falco + Falcosidekick (HTTP → Loki), кастомные правила.
- Kubernetes Audit logs (policy пример).
- Promtail собирает потоки: `app`, `falco`, `kube-audit`.
- CI: `falcoctl-test.yml` для smoke-валидации правил.

## P66 — Resilience & Chaos Engineering

- Install Litmus: workflow **Install LitmusChaos**
- Run chaos smoke:
```bash
gh workflow run "Chaos Smoke (staging)" -f experiment=pod-delete
```
- SLO guard: Prometheus burn-rate проверяется в CI.
