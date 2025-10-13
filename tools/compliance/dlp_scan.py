#!/usr/bin/env python3
import os, re, sys, json, yaml, time
from pathlib import Path

CONF = Path(os.environ.get("DLP_CONF", "compliance/dlp_rules.yml"))
ROOT = Path(os.environ.get("SCAN_ROOT", "."))
REPORT = Path("dlp_report.json")

with open(CONF) as f:
    conf = yaml.safe_load(f)

rules = [(r["id"], re.compile(r["regex"], re.IGNORECASE), r["severity"], r["description"]) for r in conf["rules"]]
exclude_dirs = [Path(p).resolve() for p in conf.get("exclude_dirs", [])]
findings = []

def allowed(path):
    rp = path.resolve()
    return not any(str(rp).startswith(str(ed)) for ed in exclude_dirs)

for dirpath, _, files in os.walk(ROOT):
    dp = Path(dirpath)
    if not allowed(dp):
        continue
    for fn in files:
        fp = dp / fn
        try:
            txt = fp.read_text(errors="ignore")
        except Exception:
            continue
        for rid, rx, sev, desc in rules:
            for m in rx.finditer(txt):
                findings.append({
                    "file": str(fp),
                    "rule": rid,
                    "severity": sev,
                    "match": m.group(0)[:120],
                    "desc": desc
                })

ts = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
rep = {"timestamp": ts, "total": len(findings), "findings": findings}

REPORT.write_text(json.dumps(rep, indent=2))
print(f"[INFO] {len(findings)} findings -> {REPORT}")
