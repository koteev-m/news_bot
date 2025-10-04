# 2025Q4 Release Calendar & Milestones

## Release Table
| Release | Window | Scope | Test Plan | Backout Checklist |
| --- | --- | --- | --- | --- |
| 2025.10.11 | Oct 7–11 | Guided onboarding progress, trial copy localization, synthetic monitor beta | Mini App regression + onboarding flow (Zephyr TC-MA-001), load test (`run_all.sh`, Lighthouse) | Feature flag rollback (`miniApp.guidedOnboarding`), revert experiment config, redeploy prev tag |
| 2025.10.25 | Oct 21–25 | Pro trial autoconvert GA, monitoring alarms | Billing smoke (Stars sandbox), QA billing scenarios, postdeploy P40 | Disable `proTrialAutoconvert`, revoke cron job, restore billing config snapshot |
| 2025.11.22 | Nov 18–22 | CSV auto-mapping, auto-topup beta (200 users), digest_layout experiment launch | Import wizard regression, integration test `portfolio_import`, experiment API tests | Hold beta list, turn off `billing.autoTopupBeta`, revert schema migration (no-op) |
| 2025.12.06 | Dec 2–6 | Digest layout ramp-up, paywall RU/EN parity, growth dashboard T+1 | Frontend visual QA, analytics validation (funnels_q4.sql), dbt freshness checks | Freeze experiment to control, rollback Metabase dashboards to prev version |
| 2025.12.27 | Dec 23–27 | VIP breaking news, heatmap insights, billing reconciliation dashboard | Alerts end-to-end (P40 postdeploy), VIP content smoke, data reconciliation | Pause VIP channel, disable `vipHeatmap`, fallback to legacy digest, rollback dashboard |
| 2026.01.10 | Jan 6–10 | Alert volatility filters, experiment stop-loss decisions, Q4 retro actions | Alerts pipeline load test, experiment assignment audit | Disable new filters, revert experiments to default `A`, reprocess alerts backlog |

## Milestones & Governance
- **Oct 1**: QBR kick-off; roadmap review; labels `theme/*` applied; OKR alignment.
- **Oct 14**: Trial GA go/no-go review с Monetization + Legal.
- **Nov 8**: Mini App UX checkpoint; accessibility audit sign-off.
- **Dec 13**: Data/Analytics sign-off на Growth dashboard T+1.
- **Jan 10**: Q4 retro & OKR scoring; finalize P46 planning inputs.

## Release Readiness Checklist
1. Pre-flight: `bash tools/release/preflight.sh` (P40) + security scan.
2. QA sign-off: Zephyr cases зеленые, экспериментальные сценарии воспроизведены.
3. Analytics: события проверены (P33), dashboards обновлены.
4. Documentation: release notes, FAQ, support макросы актуализированы.
5. Stakeholder comms: TG канал Pro/VIP уведомлён, support в курсе изменений.

## Smoke Teams & Coverage
- **Mini App Squad**: руководит preflight (UI/UX smoke, Percy diffs), owner — UX Lead.
- **Billing/Growth**: проводит Stars payment smoke, owner — Monetization PM; использует `tools/analytics/funnels_q4.sql` для сверки конверсий.
- **Alerts/News**: проверяют VIP pushes, owner — Head of News; используют P40 postdeploy checklist.
- **Data/SRE**: отвечает за dashboards и Prometheus мониторинг; owner — Analytics Lead.

## Milestone Labels (GitHub)
- `release/2025.10.11`, `release/2025.10.25`, `release/2025.11.22`, `release/2025.12.06`, `release/2025.12.27`, `release/2026.01.10` — назначаются на issues/PR для отслеживания готовности.
