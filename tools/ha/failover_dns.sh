#!/usr/bin/env bash
set -euo pipefail
# Terraform-based switch: ensure primary healthcheck or swap records
echo "[INFO] To force failover, apply terraform with swapped LB values"
echo "terraform -chdir=terraform/global apply -var='primary_lb=<down-lb>' -var='secondary_lb=<healthy-lb>' -var='zone_id=Z...' -var='hostname=app.example.com'"
