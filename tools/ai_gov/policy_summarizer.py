#!/usr/bin/env python3
import os, glob, hashlib, json, datetime as dt
from pathlib import Path

POLICY_DIR = Path(os.environ.get("POLICY_DIR","governance/policies"))
OUT = Path(os.environ.get("POLICY_SUMMARY","policy_summary.md"))

def digest(p: Path):
    return hashlib.sha256(p.read_bytes()).hexdigest()[:12]

lines = [f"# Policy Summary — {dt.datetime.utcnow().isoformat()}Z", ""]
for p in sorted(POLICY_DIR.rglob("*.rego")):
    h = digest(p)
    content = p.read_text(errors="ignore")
    title = content.splitlines()[0].strip() if content else p.name
    lines += [f"## {p}", f"- sha256: `{h}`", "- rules:", ""]
    # простая выжимка deny/violation сообщений
    for ln in content.splitlines():
        ln = ln.strip()
        if ln.startswith("deny[") or "violation[" in ln:
            lines.append(f"  - `{ln}`")
    lines += [""]
OUT.write_text("\n".join(lines))
print(f"[OK] Policy summary -> {OUT}")
