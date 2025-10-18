#!/usr/bin/env python3
"""
Собирает стоимость 1h/1GB трафика и энерго-углеродные факторы по регионам.
Данные — из Prometheus (P69 metrics) или статические справочники.
"""
import json
import datetime as dt
from pathlib import Path

OUT = Path("finops_regions.json")
regions = {
  "eu-central-1": {"usd_per_req": 0.0000012, "carbon_g": 18},
  "us-east-1": {"usd_per_req": 0.0000010, "carbon_g": 26},
  "ap-south-1": {"usd_per_req": 0.0000015, "carbon_g": 12}
}
snapshot = {
  "ts": dt.datetime.utcnow().isoformat()+"Z",
  "regions": regions
}
OUT.write_text(json.dumps(snapshot, indent=2))
print("[OK] finops_regions.json written")
