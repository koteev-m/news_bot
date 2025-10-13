## P80 — Performance Deep Dive

- async-profiler: `tools/perf/profile_async.sh`
- JFR: `tools/perf/record_jfr.sh`
- k6 latency/throughput: `deploy/load/k6/latency_throughput.js`
- CI budgets: `.github/workflows/perf-budgets.yml`

Пример:
```bash
gh workflow run "Performance Budgets (k6 + server SLO)" -f base_url=https://staging.example.com
```
