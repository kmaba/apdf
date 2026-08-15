#!/usr/bin/env bash
# Build the release APK and upload it to a file host so it can be shared for
# beta testing. Prints the direct download link.
#
# Usage:
#   ./sendit.sh [version] [apk-file]
#
set -euo pipefail

cd "$(dirname "$0")"

APK="${2:-app/build/outputs/apk/release/app-release.apk}"

# Build unless the caller passed an existing APK path.
if [[ "${2:-}" == "" ]]; then
  echo ">> building release APK"
  ./gradlew :app:assembleRelease
fi

[[ -f "${APK}" ]] || { echo "error: APK not found: ${APK}" >&2; exit 1; }

NAME="PDF-Converter.apk"

upload_catbox() {
  curl -sS -m 120 -F 'reqtype=fileupload' -F "fileToUpload=@${APK};filename=${NAME}" \
    https://catbox.moe/user/api.php
}

upload_tmpfiles() {
  curl -sS -m 120 -F "file=@${APK};filename=${NAME}" \
    https://tmpfiles.org/api/v1/upload
}

echo ">> uploading ${NAME} ($(du -h "${APK}" | cut -f1))"

URL="$(upload_catbox || true)"
if [[ "${URL}" == https://files.catbox.moe/* ]]; then
  echo ">> direct link: ${URL}"
  exit 0
fi

echo ">> catbox failed, trying tmpfiles.org…"
RESP="$(upload_tmpfiles || true)"
URL="$(printf '%s' "${RESP}" | grep -oP '"url":"\K[^"]+' || true)"
if [[ -n "${URL}" ]]; then
  # tmpfiles URLs need /dl/ for a direct download.
  URL="${URL//tmpfiles.org\//tmpfiles.org/dl/}"
  echo ">> direct link: ${URL}"
  echo ">> (tmpfiles links expire after ~60 minutes)"
  exit 0
fi

echo ">> upload failed (catbox response: ${URL:-none}, tmpfiles: ${RESP:-none})" >&2
exit 1
