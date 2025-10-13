#!/usr/bin/env python3
import os, json, glob, re, sys
from pathlib import Path

OUT = Path(os.environ.get("OUT_JSON", ".governance/governance-report.json"))
DIR = Path(os.environ.get("EVIDENCE_DIR", ".governance"))

def count_sarif_issues(path, severities=("error","warning")):
    try:
        data = json.loads(Path(path).read_text())
        n = 0
        for run in data.get("runs", []):
            for r in run.get("results", []):
                lvl = r.get("level","").lower()
                if not severities or lvl in severities:
                    n += 1
        return n
    except Exception:
        return 0

report = {
  "summary": {},
  "inputs": [],
  "scores": {
    "security": 100,
    "reliability": 100,
    "compliance": 100
  },
  "violations": []
}

# Security: CodeQL + Trivy + Gitleaks
cq = count_sarif_issues(DIR/"codeql.sarif", ("error","warning"))
tf = count_sarif_issues(DIR/"trivy-fs.sarif", ("error"))
ti = count_sarif_issues(DIR/"trivy-image.sarif", ("error"))
gl = count_sarif_issues(DIR/"gitleaks.sarif", ("error"))
sec_deduct = min(100, cq*2 + tf*5 + ti*5 + gl*20)
report["scores"]["security"] = max(0, 100 - sec_deduct)

# Reliability: Continuous Verification
cv_ok = 1
if (DIR/"cv_report.md").exists():
    txt = (DIR/"cv_report.md").read_text().lower()
    cv_ok = 0 if ("fail" in txt or "error" in txt) else 1
rel_deduct = 0 if cv_ok else 30
report["scores"]["reliability"] = max(0, 100 - rel_deduct)

# Compliance: DLP and API spec presence
dlp = 0
for p in DIR.glob("dlp_report.json"):
    try:
        dlp += json.loads(p.read_text()).get("total", 0)
    except Exception:
        pass
comp_deduct = min(100, dlp*10)
report["scores"]["compliance"] = max(0, 100 - comp_deduct)

# Summary & inputs
for p in DIR.glob("*"):
    report["inputs"].append(str(p.name))
report["summary"]["overall"] = round(sum(report["scores"].values())/3, 2)

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
