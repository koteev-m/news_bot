# P58 — Release freeze gates (RC → Canary → GA)

## Definitions
- **RC**: release candidate tag `vX.Y.Z-rc.N`
- **Canary**: rollout GREEN N% (≤50%) on staging, verify SLO
- **GA**: 100% GREEN on production after approvals

## Gates (must pass)
1. Preflight (P40): `tools/release/preflight.sh`
2. Security (P56): CodeQL/Trivy/Gitleaks — no HIGH/CRITICAL
3. SLO (P21): webhook p95 ≤ 1.5s, API 5xx ≤ 2% (5m)
4. Uptime synthetics (P43) — all mandatory checks 200
5. Error budget — no active burn (last 24h)

## RC → Canary (staging)
```bash
gh workflow run "Release RC → Canary" -f version=v1.3.0-rc.1 -f canary_percent=10

• Builds image ghcr.io/<org>/<repo>:v1.3.0-rc.1 + :rc
• Switch traffic GREEN=10%
• Runs postdeploy verify (P40)
```

## Promote → GA (production)

```bash
gh workflow run "Promote RC → GA (100%)" -f version=v1.3.0

• Requires environment production approvals
• Tags latest, traffic 100% → GREEN
```

## Freeze policy
```
• File .release-freeze or secret RELEASE_FREEZE=true activates guard.
• PRs must have label release-exception to merge.
```

## Rollback

```bash
bash tools/release/rollback.sh
```
