#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage: $(basename "$0") <slug>
Creates incidents/INCIDENT_<YYYYMMDD>_<slug>.md from docs/TEMPLATES/INCIDENT_TEMPLATE.md
USAGE
}

if [[ $# -ne 1 ]]; then
  usage >&2
  exit 1
fi

raw_slug="$1"
normalized_slug=$(echo "$raw_slug" | tr '[:upper:]' '[:lower:]')
normalized_slug=${normalized_slug//[^a-z0-9-]/-}
normalized_slug=${normalized_slug##-}
normalized_slug=${normalized_slug%%-}

if [[ -z "$normalized_slug" ]]; then
  echo "[error] slug must contain at least one alphanumeric character" >&2
  exit 1
fi

current_date=$(date -u +%Y%m%d)
incident_file="incidents/INCIDENT_${current_date}_${normalized_slug}.md"
incident_title="INCIDENT-${current_date}-${normalized_slug}"

if [[ -e "$incident_file" ]]; then
  echo "[error] incident file already exists: $incident_file" >&2
  exit 1
fi

if [[ ! -f docs/TEMPLATES/INCIDENT_TEMPLATE.md ]]; then
  echo "[error] template not found: docs/TEMPLATES/INCIDENT_TEMPLATE.md" >&2
  exit 1
fi

mkdir -p incidents
sed "s|INCIDENT-<YYYYMMDD>-<slug>|$incident_title|g" docs/TEMPLATES/INCIDENT_TEMPLATE.md > "$incident_file"

echo "$incident_file"
