#!/usr/bin/env python3
import os, sys, json, time
import urllib.parse, urllib.request
from statistics import median

PROM_URL = os.environ.get("PROM_URL")
QUERY = os.environ.get("QUERY", 'sum(rate(http_server_requests_seconds_count[5m]))')
ZSCORE = float(os.environ.get("ZSCORE", "3.0"))
WINDOW = int(os.environ.get("WINDOW", "49"))  # нечётное
STEP = os.environ.get("STEP", "60s")

if not PROM_URL:
    print("PROM_URL required", file=sys.stderr); sys.exit(2)

def prom_range(q, start, end, step):
    params = urllib.parse.urlencode({"query": q, "start": start, "end": end, "step": step})
    with urllib.request.urlopen(f"{PROM_URL}/api/v1/query_range?{params}") as r:
        data = json.load(r)
        if data.get("status")!="success": raise RuntimeError("prom error")
        # берём первую серию (агрегированная метрика)
        v = data["data"]["result"][0]["values"] if data["data"]["result"] else []
        return [(int(t), float(x)) for t,x in v]

def medf(seq, k):
    m = []
    h = k//2
    for i in range(len(seq)):
        win = [seq[j][1] for j in range(max(0,i-h), min(len(seq), i+h+1))]
        m.append((seq[i][0], median(win)))
    return m

def detect(ts, baseline, z):
    out = []
    # робастная sigma по MAD
    vals = [v for _,v in ts]
    bs = [b for _,b in baseline]
    diffs = [abs(a-b) for a,b in zip(vals,bs)]
    mad = median(diffs) or 1e-9
    sigma = 1.4826*mad
    for (t,v),(t2,b) in zip(ts, baseline):
        score = 0 if sigma==0 else (v-b)/sigma
        if abs(score) >= z:
            out.append({"ts": t, "val": v, "base": b, "z": score})
    return out

def main():
    end = int(time.time())
    start = end - 24*3600
    series = prom_range(QUERY, start, end, STEP)
    if not series: 
        print(json.dumps({"ok": True, "anomalies": [], "points": 0})); return
    base = medf(series, WINDOW)
    anomalies = detect(series, base, ZSCORE)
    rep = {
        "ok": True,
        "query": QUERY,
        "points": len(series),
        "anomalies": anomalies[:200]
    }
    print(json.dumps(rep))

if __name__ == "__main__":
    main()
