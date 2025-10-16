#!/usr/bin/env python3
import os, json, glob, re, sys, datetime as dt
from pathlib import Path

BASE = Path(os.environ.get("EVIDENCE_DIR", "."))
OUT  = Path(os.environ.get("OUT_JSON", "ai_gov_rca_input.json"))

def read_text(p: Path, limit=200_000):
    try:
        t = p.read_text(errors="ignore")
        return t[:limit]
    except Exception:
        return ""

def load_json(p: Path):
    try:
        return json.loads(p.read_text())
    except Exception:
        return None

def find_first(globs):
    for g in globs:
        for p in BASE.glob(g):
            return p
    return None

res = {
    "timestamp": dt.datetime.utcnow().isoformat() + "Z",
    "inputs": {}
}

# Governance report (P74)
gov = find_first([".governance/governance-report.json", "governance-report.json"])
if gov:
    res["inputs"]["governance"] = load_json(gov)

# CV report (P72)
cv = find_first(["cv_report.md", ".**/cv_report.md"])
if cv:
    res["inputs"]["cv_report_md"] = read_text(cv, 200_000)

# FinOps (P69)
fin = find_first(["finops_daily.txt", ".**/finops_daily.txt"])
if fin:
    res["inputs"]["finops_daily"] = read_text(fin, 50_000)

# DLP / Gitleaks / Trivy / Grype
for name, pattern in [
    ("dlp_report_json", "dlp_report.json"),
    ("dlp_report_md", "dlp_report.md"),
    ("gitleaks_sarif", "gitleaks.sarif"),
    ("trivy_fs_sarif", "trivy-fs.sarif"),
    ("trivy_img_sarif", "trivy-image.sarif"),
    ("grype_sarif", "grype.sarif"),
]:
    p = find_first([pattern, f".**/{pattern}", f"**/{pattern}"])
    if p: res["inputs"][name] = read_text(p, 300_000)

# Audit Ledger checkpoint (P84)
chk = find_first(["checkpoint.json", ".**/checkpoint.json"])
if chk:
    res["inputs"]["audit_checkpoint"] = load_json(chk)

# Sloth SLO burn snapshot (если выгружен заранее)
slo = os.environ.get("SLO_BURN_JSON")
if slo and Path(slo).exists():
    res["inputs"]["slo_burn"] = load_json(Path(slo))

# Falco (через выгрузки в Loki → сохраняем как текст, если есть)
falco = find_first(["falco.log", "falco.json", ".**/falco*"])
if falco:
    res["inputs"]["falco"] = read_text(falco, 200_000)

OUT.write_text(json.dumps(res, indent=2))
print(f"[OK] RCA input -> {OUT}")
