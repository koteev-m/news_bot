# P90 — Access Reviews & SoD (Role Attestations, PAM)

## Что включено
- **Access Reviews** — периодические ревизии ролей; reviewer подтверждает/отклоняет доступ.
- **SoD Policies** — запрет сочетаний ролей (например, BILLING+OWNER).
- **PAM (Break-Glass)** — временные привилегии по запросу с аудированием и сроком действия.

## API
- `POST /api/access/review/start { dueAtIso }`
- `POST /api/access/review/decide { itemId, keep }`
- `GET  /api/access/sod/check`
- `POST /api/access/pam/request { reason, roles[], ttlMinutes }`
- `POST /api/access/pam/approve { sessionId, approverId }`
- `POST /api/access/pam/revoke { sessionId, by? }`

## Практики
- Ежеквартальные ревью владельцами тенанта (OWNER/ADMIN).
- Все PAM-сессии ограничены по времени, причины обязательны.
- Встраивайте SoD-валидацию в процесс выдачи ролей и SCIM-синхронизацию.

> Не храните PII в полях комментариев; все решения логируются в Audit Ledger (P84).
