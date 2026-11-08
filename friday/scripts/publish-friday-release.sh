#!/usr/bin/env bash
# Publishes the APK built by build-android.sh as a GitHub release FRIDAY can offer as an update.
# Called by .github/workflows/friday-release.yml (owner-started). Needs GH_TOKEN and the Android build-tools.
set -euo pipefail
cd "$(dirname "$0")/.."
BT="${ANDROID_HOME:?set ANDROID_HOME}/build-tools/35.0.0"
MANIFEST=android/app/src/main/AndroidManifest.xml
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MANIFEST")
CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$MANIFEST")
APK="artifacts/android/FRIDAY-Mobile-$VERSION-private.apk"
[ -s "$APK" ] || { echo "Missing $APK" >&2; exit 1; }
TAG="friday-v$VERSION"
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
CERT=$("$BT/apksigner" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-256 digest: \([0-9a-f]*\)$/\1/p' | head -1)
[ ${#SHA} -eq 64 ] && [ ${#CERT} -eq 64 ] || { echo "Could not read the APK's SHA-256 or signing certificate." >&2; exit 1; }
# The change report: FRIDAY commits since the previous published FRIDAY version.
PREV=$(git tag -l 'friday-v*' --sort=-creatordate | head -1)
RANGE=${PREV:+$PREV..HEAD}
CHANGES=$(git log --no-merges --pretty='- %s' ${RANGE:-HEAD~20..HEAD} -- . ../.github/workflows/friday-apk.yml ../.github/workflows/friday-release.yml | head -40)
NAME=$(basename "$APK")
META=$(printf '{"versionCode":%s,"versionName":"%s","sha256":"%s","cert":"%s","apk":"%s"}' "$CODE" "$VERSION" "$SHA" "$CERT" "$NAME")
BODY=$(mktemp)
{
  if [ -n "${OWNER_NOTES:-}" ]; then printf '%s\n\n' "$OWNER_NOTES"; fi
  echo "## What changed${PREV:+ since ${PREV#friday-v}}"
  echo "${CHANGES:-- Maintenance build}"
  echo
  echo "Install it from FRIDAY: Settings > Self-development > Updates. FRIDAY checks the SHA-256 and the signing key, saves the current version and a data backup to Downloads/FRIDAY, and Android asks you to confirm."
  echo
  echo "SHA-256: \`$SHA\`"
  echo
  echo "<!-- friday-update $META -->"
} > "$BODY"
gh release create "$TAG" "$APK" --title "FRIDAY $VERSION" --notes-file "$BODY" --target "$(git rev-parse HEAD)"
echo "Published $TAG ($SHA)"
