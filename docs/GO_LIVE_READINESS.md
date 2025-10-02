# P40 — Go-Live Readiness / Готовность к релизу

## ✅ Readiness Checklist / Чек-лист готовности
- [ ] Build, lint, unit tests (`./gradlew ktlintCheck detekt test build`) completed with green status.
- [ ] Release Gates CI workflow (`Release Gates`) finished successfully for the release branch.
- [ ] `tools/audit/grep_checks.sh` and `tools/audit/run_all.sh --skip-build` report no findings.
- [ ] Monitoring stack (P21) deployed; SLO dashboards (P22) up-to-date and reviewed.
- [ ] Alerting rules triggered in staging and acknowledged; pager routing confirmed.
- [ ] Security hardening (P35) enforced: CSP/HSTS headers, rate-limiting, JWT dual-key rotation verified.
- [ ] Privacy controls (P36) active: data-retention scheduler running; erasure dry-run executed in staging.
- [ ] Backups & DR (P31): nightly backup succeeded; latest 7-day restore validated on staging.
- [ ] Blue/Green (P34): canary toggle exercised with rollback path documented.
- [ ] E2E suite (P20) green via `pnpm test:e2e -- --reporter=list` (miniapp) or recorded waiver.
- [ ] k6 smoke (P23) dry-run for `deploy/load/k6/*_scenario.js` passes with no errors.

## 🔁 Command Reference / Команды
```bash
# локально / local
bash tools/release/preflight.sh

# после раскатки на стенд/прод / after deploy to stage/prod
BASE_URL=https://<host> PROM_URL=http://prometheus:9090 WEBHOOK_SECRET=*** TG_USER_ID=*** \
bash tools/release/postdeploy_verify.sh
```
