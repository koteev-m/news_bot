# P55 — Lighthouse перф-бюджеты по страницам и Web Vitals → Prometheus

## Lighthouse Budgets
- Конфиг: `miniapp/lighthouserc.json`
- Бюджеты: `miniapp/lighthouse-budgets.json` (пер-страничные лимиты ресурсов и таймингов).
- CI: workflow **Frontend Performance Budgets**.

Локально:
```bash
(cd miniapp && pnpm build && npx @lhci/cli@0.13.0 autorun)
```

## Web Vitals → Prometheus
- Mini App собирает LCP/CLS/FID/INP/TTFB (`web-vitals`) и отправляет на `POST /vitals`.
- Backend агрегирует в Micrometer: `web_vitals_lcp_seconds`, `web_vitals_cls`, `web_vitals_fid_ms`, `web_vitals_inp_ms`, `web_vitals_ttfb_ms`.
- Теги: `page`, `nav`.

Проверка:

```bash
curl -s -X POST "$BASE/vitals" -H 'content-type: application/json' \
  -d '[{"name":"LCP","value":1800,"page":"/#/","navType":"navigate"}]' -i
```
