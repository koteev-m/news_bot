# Post-Deploy Verification / Пост-деплой проверки

## 🔍 Checklist (10–15 steps)
1. Confirm deployment metadata (commit SHA, build number) matches release notes.
2. Run `BASE_URL=... PROM_URL=... WEBHOOK_SECRET=... TG_USER_ID=... bash tools/release/postdeploy_verify.sh` and ensure `[OK] post-deploy verified`.
3. Manually curl `${BASE_URL}/healthz` and `${BASE_URL}/health/db` for HTTP 200 (redundant sanity).
4. Open `${BASE_URL}/metrics` and ensure scrape succeeds; verify `http_server_requests_seconds_count` increments.
5. Execute PromQL in Prometheus UI:
   - `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) < 0.02`
   - `histogram_quantile(0.95, sum(rate(webhook_duration_seconds_bucket{integration="xtr"}[5m])) by (le)) < 1.5`
6. Review Grafana dashboard `P21 / Core SLO` for traffic, latency, error budget burn.
7. Validate alertmanager silence state; ensure no critical alerts suppressed unexpectedly.
8. Trigger synthetic probe via `BASE_URL=... JWT=... bash tools/ops/synthetic_probe.sh` (if JWT available).
9. Check webhook delivery log for the two XTR payment simulations (idempotent 200/200).
10. Validate queue/backlog metrics (e.g., `pending_jobs`) remain within thresholds post deploy.
11. Inspect application logs for new errors since deploy timestamp.
12. Confirm notification webhooks (Slack/TG) delivered release announcement to on-call.
13. Verify background jobs (retention, billing sync) executed at least once after deploy.
14. Ensure feature flags align with rollout plan; canary population monitored.
15. Update release journal with verification evidence and attach CI artifacts.
