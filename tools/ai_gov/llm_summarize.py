#!/usr/bin/env python3
import os, sys, json, textwrap, subprocess, shlex, datetime as dt
from pathlib import Path

IN  = Path(os.environ.get("RCA_INPUT", "ai_gov_rca_input.json"))
OUT = Path(os.environ.get("RCA_REPORT", "ai_gov_rca_report.md"))
OPENAI_API_KEY = os.environ.get("OPENAI_API_KEY", "")

def basic_summarize(data: dict) -> str:
    lines = []
    ts = data.get("timestamp","")
    gov = data.get("inputs",{}).get("governance",{})
    overall = gov.get("summary",{}).get("overall")
    sec = gov.get("scores",{}).get("security")
    rel = gov.get("scores",{}).get("reliability")
    comp= gov.get("scores",{}).get("compliance")
    lines += [f"# AIOps Governance RCA — {ts}", ""]
    if overall is not None:
        lines += [f"- Governance overall: **{overall}** (sec={sec}, rel={rel}, comp={comp})", ""]
    cv = data.get("inputs",{}).get("cv_report_md","")
    if "FAIL" in cv or "fail" in cv or "error" in cv.lower():
        lines += ["## CV Gate signals", "One or more gates reported failures.", ""]
    dlp = data.get("inputs",{}).get("dlp_report_json")
    if isinstance(dlp, dict) and dlp.get("total",0) > 0:
        lines += [f"## DLP", f"- Findings: **{dlp.get('total')}** (review required)", ""]
    for k in ("gitleaks_sarif","trivy_fs_sarif","trivy_img_sarif","grype_sarif"):
        if data.get("inputs",{}).get(k):
            lines += [f"- Scan present: `{k}`"]
    if "finops_daily" in data.get("inputs",{}):
        lines += ["", "## FinOps", "Daily cost/energy/carbon report attached."]
    lines += ["", "## Suggested Actions", "- Review failing gates (if any).", "- Prioritize HIGH/CRITICAL from security scans.", "- Verify audit checkpoint signature.", ""]
    return "\n".join(lines)

def openai_summary(prompt: str) -> str:
    # Безопасный вызов через curl, если ключ задан
    content = prompt.replace("\\","\\\\").replace("\"","\\\"")
    cmd = f'''curl -sS https://api.openai.com/v1/chat/completions \
 -H "Authorization: Bearer {OPENAI_API_KEY}" \
 -H "Content-Type: application/json" \
 -d "{{\"model\":\"gpt-4o-mini\",\"messages\":[{{\"role\":\"system\",\"content\":\"You are a senior SRE. Summarize incidents and propose actions succinctly.\"}},{{\"role\":\"user\",\"content\":\"{content}\"}}],\"temperature\":0.2}}"'''
    try:
        out = subprocess.check_output(cmd, shell=True, text=True)
        j = json.loads(out)
        return j["choices"][0]["message"]["content"]
    except Exception as e:
        return basic_summarize(json.loads(prompt))

def main():
    data = json.loads(IN.read_text())
    prompt = json.dumps(data)
    if OPENAI_API_KEY:
        md = openai_summary(prompt)
    else:
        md = basic_summarize(data)
    OUT.write_text(md)
    print(f"[OK] RCA report -> {OUT}")

if __name__ == "__main__":
    main()
