#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "Usage: tools/release/bump_version.sh <new_version>" >&2
  exit 1
fi

new_version="$1"
if [[ ! "$new_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Version must match X.Y.Z" >&2
  exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
  echo "Working tree must be clean" >&2
  exit 1
fi

gradle_file="gradle.properties"
echo "version=$new_version" > "$gradle_file"

changelog="CHANGELOG.md"
today="$(date +%F)"
if [ ! -f "$changelog" ]; then
  cat > "$changelog" <<EOT
# Changelog

## [Unreleased]

## [v$new_version] - $today

EOT
else
  if grep -q "^## \[v$new_version\]" "$changelog"; then
    echo "Changelog already contains entry for v$new_version" >&2
    exit 1
  fi
  if ! grep -q "^## \[Unreleased\]" "$changelog"; then
    sed -i "1a\\
\\n## [Unreleased]" "$changelog"
  fi
  tmp_file="$(mktemp)"
  replacement=$'## [Unreleased]\n\n## [v'"$new_version"'] - '"$today"
  sed "0,/^## \[Unreleased\]/s//${replacement}/" "$changelog" > "$tmp_file"
  mv "$tmp_file" "$changelog"
fi

git add "$gradle_file" "$changelog"
git commit -m "chore(release): v$new_version"
git tag -a "v$new_version" -m "v$new_version"

echo "git push origin HEAD --tags"
