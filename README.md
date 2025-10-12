## P65 — Runtime security & audit visibility

- Falco + Falcosidekick (HTTP → Loki), кастомные правила.
- Kubernetes Audit logs (policy пример).
- Promtail собирает потоки: `app`, `falco`, `kube-audit`.
- CI: `falcoctl-test.yml` для smoke-валидации правил.
