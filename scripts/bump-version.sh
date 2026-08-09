#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

# Guard against the re-trigger loop: the commit pushed by this script
# re-triggers the workflow; the bot-authored commit must not bump again.
LAST_AUTHOR=$(git log -1 --format='%an')
if [[ "$LAST_AUTHOR" == "github-actions[bot]" ]]; then
  echo "skipped=true"
  exit 0
fi

PROPS="agent/gradle.properties"

VERSION_NAME=$(grep -E '^VERSION_NAME=' "$PROPS" | cut -d= -f2)
VERSION_CODE=$(grep -E '^VERSION_CODE=' "$PROPS" | cut -d= -f2)

# Most recently created tag (commit-date order, since version sort is unreliable
# with mixed "vX.Y.Z" and "X.Y.Z" tags)
LAST_TAG=$(git tag --sort=-creatordate | head -1 || true)
LAST_TAG_CLEAN="${LAST_TAG#v}"

# versionCode must always increase (Play/app stores require monotonic codes)
NEW_VERSION_CODE=$((VERSION_CODE + 1))

# versionName: auto patch bump if it matches the last release; otherwise a
# manual minor/major bump was made in the merge commit — keep it.
NEW_VERSION_NAME="$VERSION_NAME"
if [[ -z "$LAST_TAG_CLEAN" || "$LAST_TAG_CLEAN" == "$VERSION_NAME" ]]; then
  IFS='.' read -r MAJOR MINOR PATCH <<< "$VERSION_NAME"
  NEW_VERSION_NAME="${MAJOR}.${MINOR}.$((PATCH + 1))"
fi

sed -i.bak -E \
  -e "s/^(VERSION_NAME=).*/\1$NEW_VERSION_NAME/" \
  -e "s/^(VERSION_CODE=).*/\1$NEW_VERSION_CODE/" \
  "$PROPS"
rm -f "$PROPS.bak"

git config user.name "github-actions[bot]"
git config user.email "github-actions[bot]@users.noreply.github.com"
git add "$PROPS"
git commit -m "Bump version to $NEW_VERSION_NAME (version code $NEW_VERSION_CODE)"
git tag "v$NEW_VERSION_NAME"

echo "version=$NEW_VERSION_NAME"
echo "version_code=$NEW_VERSION_CODE"
