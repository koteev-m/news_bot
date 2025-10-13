## P73 — Privacy & Compliance Automation

- DLP rules: `compliance/dlp_rules.yml`
- Скрипты: `tools/compliance/dlp_scan.py`, `tools/compliance/dlp_report.sh`
- DSAR endpoint: `/api/dsar`
- CI: `.github/workflows/compliance-gate.yml`

Проверка:
```bash
python3 tools/compliance/dlp_scan.py
curl -X POST http://localhost:8080/api/dsar -H 'Content-Type: application/json' \
  -d '{"userId":123,"action":"export"}'
```
