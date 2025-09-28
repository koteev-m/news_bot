# On-call Rotation — News Bot

## Schedule
| Week (UTC) | Primary (role email) | Secondary | Timezone |
| --- | --- | --- | --- |
| 2024-W40 | oncall-sre@newsbot.example | sre-backup@newsbot.example | UTC+1 |
| 2024-W41 | oncall-sre@newsbot.example | sre-backup@newsbot.example | UTC+1 |
| 2024-W42 | sre-rotation-3@newsbot.example | sre-backup@newsbot.example | UTC+3 |
| 2024-W43 | sre-rotation-4@newsbot.example | sre-backup@newsbot.example | UTC+3 |

- Ротация недельная, hand-off каждый понедельник 09:00 UTC.
- Используются роли email/Slack (`@oncall-sre`), персональные контакты не публикуются.

## Handoff Checklist
- [ ] Просмотреть `NewsBot / Observability` dashboard (webhook latency, HTTP 5xx, duplicates).
- [ ] Проверить Alertmanager muted windows и снять временные mute, если срок истёк.
- [ ] Обновить список открытых инцидентов и их статус в `incidents/` каталоге.
- [ ] Проверить расход error budget за прошедшую неделю (`docs/SLA_SLO_POLICY.md`).
- [ ] Прогнать SRE скрипты (пример ниже) и убедиться, что Prometheus доступен.

## Playbooks & Tools
- Runbooks: `docs/RUNBOOKS/*`.
- Incident response: `docs/INCIDENT_RESPONSE.md`.
- Templates: `docs/TEMPLATES/*`.
- SRE scripts: `tools/sre/sli_query.sh`, `tools/sre/incident_new.sh`.

## Communication Channels
- Slack: `#newsbot-incident`, `#newsbot-reliability`.
- Email: oncall-sre@newsbot.example.
- Pager routing: Alertmanager webhook → incident tool.
