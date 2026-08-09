#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

PROPS="agent/gradle.properties"

# Most recently created release tag (commit-date order — version sort is
# unreliable with mixed "vX.Y.Z" and "X.Y.Z" tags)
LAST_TAG=$(git tag --sort=-creatordate | head -1 || true)
LAST_TAG_CLEAN="${LAST_TAG#v}"

CURRENT_VERSION_NAME=$(grep -E '^VERSION_NAME=' "$PROPS" | cut -d= -f2)
CURRENT_VERSION_CODE=$(grep -E '^VERSION_CODE=' "$PROPS" | cut -d= -f2)

# Version as of the last release tag's commit (fall back to the working tree
# if that tag predates version properties in gradle.properties)
PREV_VERSION_NAME=$(git show "$LAST_TAG:$PROPS" 2>/dev/null | grep -E '^VERSION_NAME=' | cut -d= -f2 || true)
PREV_VERSION_CODE=$(git show "$LAST_TAG:$PROPS" 2>/dev/null | grep -E '^VERSION_CODE=' | cut -d= -f2 || true)
PREV_VERSION_NAME="${PREV_VERSION_NAME:-$CURRENT_VERSION_NAME}"
PREV_VERSION_CODE="${PREV_VERSION_CODE:-$CURRENT_VERSION_CODE}"

# versionCode must always increase
NEW_VERSION_CODE=$((PREV_VERSION_CODE + 1))

# versionName: if the merged PR manually bumped it (minor/major), keep it;
# otherwise auto patch-bump the last release
if [[ "$CURRENT_VERSION_NAME" == "$PREV_VERSION_NAME" ]]; then
  BASE_NAME="${LAST_TAG_CLEAN:-$CURRENT_VERSION_NAME}"
  IFS='.' read -r MAJOR MINOR PATCH <<< "$BASE_NAME"
  NEW_VERSION_NAME="${MAJOR}.${MINOR}.$((PATCH + 1))"
else
  NEW_VERSION_NAME="$CURRENT_VERSION_NAME"
fi

git tag "v$NEW_VERSION_NAME"

echo "version=$NEW_VERSION_NAME"
echo "version_code=$NEW_VERSION_CODE"
echo "tag=v$NEW_VERSION_NAME"
