#!/usr/bin/env bash
# Build the release APK and publish it as a GitHub release for beta testing.
#
# Usage:
#   ./sendit.sh           # use versionName from app/build.gradle.kts
#   ./sendit.sh 1.3.0     # explicit version
#
# Requires ./gradlew, git and an authenticated `gh` CLI.
set -euo pipefail

cd "$(dirname "$0")"

VERSION="${1:-$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)}"
if [[ -z "${VERSION}" ]]; then
  echo "error: could not determine version from app/build.gradle.kts" >&2
  exit 1
fi

TAG="v${VERSION}"
APK="app/build/outputs/apk/release/app-release.apk"
DIST="${DIST:-PDF-Converter.apk}"

echo ">> building release APK (${VERSION})"
./gradlew :app:assembleRelease

echo ">> packaging ${DIST}"
cp -f "${APK}" "${DIST}"

echo ">> tagging ${TAG}"
git tag -f "${TAG}"

echo ">> pushing ${TAG}"
git push origin "${TAG}"

echo ">> publishing GitHub release"
gh release create "${TAG}" "${DIST}" \
  --title "apdf ${VERSION}" \
  --notes "apdf ${VERSION} beta build for testing." \
  --repo "$(git remote get-url origin | sed -E 's#.*[:/]([^/]+/[^/]+)(\.git)?#\1#')"

echo ">> done: https://github.com/$(git remote get-url origin | sed -E 's#.*[:/]([^/]+/[^/]+)(\.git)?#\1#')/releases/tag/${TAG}"
