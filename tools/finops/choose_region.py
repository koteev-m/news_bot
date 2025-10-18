#!/usr/bin/env python3
import json
from pathlib import Path

with open("finops_regions.json") as f:
    data = json.load(f)

# Весовые коэффициенты: цена 70%, углерод 30%
best = min(
    data["regions"].items(),
    key=lambda kv: kv[1]["usd_per_req"]*0.7 + kv[1]["carbon_g"]*0.3
)
print(f"[OK] preferred_region={best[0]}")
Path("preferred_region.txt").write_text(best[0])
