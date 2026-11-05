#!/usr/bin/env bash
# Linux/CI port of scripts/build-android.ps1. Builds a PRIVATE test APK.
# Uses android/local-debug.keystore if present; otherwise ANDROID_KEYSTORE_BASE64
# (a CI secret) or, last, a throwaway key. A throwaway key cannot upgrade an APK
# signed with the original Windows key: uninstall the old app first.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:?set ANDROID_HOME}"; BT="$SDK/build-tools/35.0.0"; JAR="$SDK/platforms/android-35/android.jar"
for f in "$JAR" "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner"; do [ -e "$f" ] || { echo "Missing $f" >&2; exit 1; }; done
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' android/app/src/main/AndroidManifest.xml)
npx vite build --config vite.mobile.config.ts
ASSETS=android/app/src/main/assets
if [ -d assets/characters/evelyn ]; then mkdir -p "$ASSETS/characters"; cp -r assets/characters/evelyn "$ASSETS/characters/"; else echo "NOTE: character model not in source (licence forbids redistribution); APK has no 3D character." >&2; fi
node --import tsx scripts/export-mobile-personality.ts
OUT=android/app/build/ci; rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/res/drawable"
javac -encoding UTF-8 -source 8 -target 8 -classpath "$JAR" -d "$OUT/classes" $(find android/app/src/main/java -name '*.java')
(cd "$OUT/classes" && jar --create --file ../classes.jar .)
"$BT/d8" --lib "$JAR" --min-api 26 --output "$OUT/dex" "$OUT/classes.jar"
ICON=build/icon.png; [ -f "$ICON" ] || ICON=android/ci/placeholder-icon.png
cp "$ICON" "$OUT/res/drawable/myraa_icon.png"; cp -r android/app/src/main/res/* "$OUT/res/"
"$BT/aapt2" compile --dir "$OUT/res" -o "$OUT/resources.zip"
"$BT/aapt2" link --manifest android/app/src/main/AndroidManifest.xml -I "$JAR" -A "$ASSETS" --min-sdk-version 26 --target-sdk-version 35 -o "$OUT/unsigned.apk" "$OUT/resources.zip"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes*.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS=android/local-debug.keystore
if [ ! -f "$KS" ]; then
  if [ -n "${ANDROID_KEYSTORE_BASE64:-}" ]; then echo "$ANDROID_KEYSTORE_BASE64" | base64 -d > "$KS"
  else keytool -genkeypair -keystore "$KS" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=FRIDAY CI Throwaway,O=FRIDAY,C=IN'; fi
fi
mkdir -p artifacts/android; APK="artifacts/android/FRIDAY-Mobile-$VERSION-private.apk"
"$BT/apksigner" sign --ks "$KS" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$APK"
"$BT/aapt2" dump badging "$APK" | head -3
sha256sum "$APK"; echo "PRIVATE Android candidate: $APK"
