# Incident Response Guide

## Severity Levels
| Severity | Criteria | Examples |
| --- | --- | --- |
| **SEV-1** | Критичная деградация, ≥50% error budget расходуется за сутки, критические алерты `WebhookP95High` или `Http5xxRateHigh` >5% более 10 мин, массовые двойные платежи. | `/telegram/webhook` недоступен, HTTP 5xx >10%, DoS на импорт блокирует всех клиентов. |
| **SEV-2** | Умеренная деградация, заметный рост ошибок, warning-уровни алертов дольше 15 мин, влияние на часть пользователей. | HTTP 5xx 2–10%, задержки webhook >1.5 s, дубликаты 1–2%. |
| **SEV-3** | Ограниченные сбои, деградация не влияет на большинство пользователей, быстрый авто-восстановление. | Импорт by-url медленный, локальные шумовые алерты. |

## Roles & Responsibilities
- **Incident Commander (IC)** — управляет расследованием, держит контакт с заинтересованными сторонами, следит за временем реакции.
- **Communications Lead** — готовит и распространяет сообщения (Slack статус, внешние апдейты).
- **Ops/SRE** — проводит техническую диагностику, применяет mitigation, обновляет Grafana/Prometheus настройки.
- **App Owner** — владеет соответствующим модулем (`billing`, `alerts`, `news`), реализует фиксы и кодовые изменения.

## Escalation Matrix
| Severity | Slack Channel | Alertmanager Route | Первичное реагирование |
| --- | --- | --- | --- |
| SEV-1 | `#newsbot-incident` + `@oncall-sre` | `route: webhook` (critical) | 5 минут для подтверждения, IC обязателен |
| SEV-2 | `#newsbot-incident` | `route: webhook` (warning) | 15 минут для подтверждения |
| SEV-3 | `#newsbot-reliability` | `route: webhook` (info) | 60 минут, возможно асинхронно |

## Escalation Steps
1. Alertmanager отправляет уведомление в указанный Slack.
2. On-call подтверждает алерт и сообщает «Investigating ...».
3. IC назначает роли (Communications, Ops/SRE, App Owner).
4. Если требуется внешняя коммуникация, Communications Lead готовит сообщение согласно шаблону.

## Communication Templates
### Internal (Slack)
```
[INCIDENT][SEV-x] <краткое описание>
Start: <UTC time>
Owner: <IC>
Impact: <кратко>
Next update: <+30m>
```

### External Status (если требуется)
```
Incident: <summary>
Impact: Users may experience <description>.
Mitigation: We are actively working on remediation.
Next update: <time>
```

## Post-Incident
- Заполнить `docs/TEMPLATES/INCIDENT_TEMPLATE.md` (первичный отчёт) и `docs/TEMPLATES/POSTMORTEM_TEMPLATE.md` при SEV-1/2.
- Обновить расход error budget в `docs/SLA_SLO_POLICY.md` и on-call handoff.
- Создать задачи на follow-up в backlog (P0/P1/P2) с назначенными ответственными.
