# P56 — CodeQL + Dependabot + Trivy (SAST/SCA/Image scan)

## CodeQL (SAST)
- Workflow: `.github/workflows/codeql.yml` — анализ Kotlin/Java и JS/TS, еженедельно и по PR.
- Алёрты в GitHub **Code scanning alerts**.

## Dependabot (SCA)
- Конфиг: `.github/dependabot.yml`
  - Gradle (root)
  - npm (miniapp)
  - GitHub Actions
  - Docker
- Периодичность — weekly.

## Trivy
- Workflow: `.github/workflows/trivy.yml`
  - **FS**: уязвимости в зависимостях (os+library).
  - **Image**: сборка `Dockerfile` → скан образа.
  - **Config**: IaC (compose, monitoring).
  - SARIF репорты → Code scanning.
- Локально:
  ```bash
  bash tools/security/trivy_local.sh
  ```

### Пороговая политика
- CodeQL — default «security-and-quality».
- Trivy — блокирующие уровни: HIGH/CRITICAL (в workflow — через SARIF и PR review).

### Локальные подавления
- `.trivyignore` — добавляйте CVE только с оформлением причины и сроком пересмотра.

### Best Practices
- Не хранить секреты в репозитории.
- Регулярно закрывать PR Dependabot; при необходимости объединять батчами.
