#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${OUT_DIR:-.governance}"
mkdir -p "$OUT_DIR"

collect() {
  local name="$1" url="$2"
  if curl -fsS "$url" -o "$OUT_DIR/$name" 2>/dev/null; then
    echo "[OK] $name"
  else
    echo "[WARN] cannot fetch $name"
  fi
}

echo "[INFO] collecting evidence…"
# Примеры источников (подставляются из CI как переменные/URLs артефактов):
[ -n "${EVID_SARIF_CODEQL_URL:-}" ] && collect "codeql.sarif" "$EVID_SARIF_CODEQL_URL"
[ -n "${EVID_TRIVY_FS_URL:-}" ]     && collect "trivy-fs.sarif" "$EVID_TRIVY_FS_URL"
[ -n "${EVID_TRIVY_IMG_URL:-}" ]    && collect "trivy-image.sarif" "$EVID_TRIVY_IMG_URL"
[ -n "${EVID_GITLEAKS_URL:-}" ]     && collect "gitleaks.sarif" "$EVID_GITLEAKS_URL"
[ -n "${EVID_CV_REPORT_URL:-}" ]    && collect "cv_report.md" "$EVID_CV_REPORT_URL"
[ -n "${EVID_FINOPS_URL:-}" ]       && collect "finops_daily.txt" "$EVID_FINOPS_URL"

# Локальные артефакты, если есть
[ -f "docs/api/openapi.yaml" ] && cp docs/api/openapi.yaml "$OUT_DIR/openapi.yaml" || true

echo "[INFO] pack evidence list"
ls -1 "$OUT_DIR" | sed 's/^/ - /' > "$OUT_DIR/_manifest.txt"
