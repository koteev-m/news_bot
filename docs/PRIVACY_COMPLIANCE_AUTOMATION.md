# P73 — Privacy & Compliance Automation (DLP + DSAR)

## DLP
- Конфигурация: `compliance/dlp_rules.yml`
- Скрипты: `tools/compliance/dlp_scan.py`, `tools/compliance/dlp_report.sh`
- CI: workflow **Compliance Gate** (DLP + DSAR)
- Отчёты: `dlp_report.json` + `dlp_report.md` (артефакты в PR)

## DSAR API
- Эндпоинт: `POST /api/dsar {userId, action: "export"|"erase"}`
- Поведение: возвращает статус обработки без выдачи PII.
- Можно подключить интеграцию с реальной CRM или storage backend.

## Практика
1. Запускайте DLP-скан на каждый PR.
2. Ежемесячно сохраняйте DLP отчёты в защищённое хранилище.
3. Для DSAR — ведите аудит (кто и когда запросил).
4. Не логируйте тела DSAR запросов (PII).

## Порог
CI фейлится, если найдено ≥1 срабатывание DLP.  
Рекомендуется анализировать, а не игнорировать ложные срабатывания.
