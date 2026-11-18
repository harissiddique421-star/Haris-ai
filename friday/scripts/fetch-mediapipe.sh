#!/usr/bin/env bash
# Downloads MediaPipe's on-device gesture recogniser for FRIDAY's hand shapes (see HandShapes.java and
# android/app/src/mediapipe) and checks every file against the SHA-256 pinned in android/models/mediapipe.sha256:
#   - MediaPipe Tasks Vision and Core 0.10.14 (Apache-2.0), Google Maven
#   - their runtime libraries from Maven Central: Guava + failureaccess (Apache-2.0), Flogger (Apache-2.0),
#     protobuf-javalite (BSD-3-Clause)
#   - the gesture_recognizer.task model (Apache-2.0), float16, from Google's MediaPipe model storage
#
#   scripts/fetch-mediapipe.sh OUT   ->  OUT/jars/*.jar (classes), OUT/lib/<abi>/*.so, OUT/assets/mediapipe/gesture_recognizer.task
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:?output directory}"
PINS=android/models/mediapipe.sha256
CACHE="${MEDIAPIPE_CACHE:-$HOME/.cache/friday-mediapipe}"; mkdir -p "$CACHE" "$OUT/jars" "$OUT/assets/mediapipe"
GOOGLE=https://dl.google.com/dl/android/maven2
CENTRAL=https://repo1.maven.org/maven2
V=0.10.14

fetch() {
  local name="$1" url="$2" f="$CACHE/$1"
  if [ ! -s "$f" ]; then curl -fsSL --retry 6 --retry-delay 10 --retry-all-errors -o "$f.part" "$url"; mv "$f.part" "$f"; fi
  local want; want=$(awk -v n="$name" '$2==n{print $1}' "$PINS")
  local got; got=$(sha256sum "$f" | cut -d' ' -f1)
  [ -n "$want" ] || { echo "Pin $name in $PINS first (got $got)." >&2; exit 1; }
  [ "$want" = "$got" ] || { echo "Checksum mismatch for $name: expected $want, got $got" >&2; rm -f "$f"; exit 1; }
}

fetch "tasks-vision-$V.aar" "$GOOGLE/com/google/mediapipe/tasks-vision/$V/tasks-vision-$V.aar"
fetch "tasks-core-$V.aar" "$GOOGLE/com/google/mediapipe/tasks-core/$V/tasks-core-$V.aar"
fetch guava-27.0.1-android.jar "$CENTRAL/com/google/guava/guava/27.0.1-android/guava-27.0.1-android.jar"
fetch failureaccess-1.0.1.jar "$CENTRAL/com/google/guava/failureaccess/1.0.1/failureaccess-1.0.1.jar"
fetch flogger-0.6.jar "$CENTRAL/com/google/flogger/flogger/0.6/flogger-0.6.jar"
fetch flogger-system-backend-0.6.jar "$CENTRAL/com/google/flogger/flogger-system-backend/0.6/flogger-system-backend-0.6.jar"
fetch protobuf-javalite-4.26.1.jar "$CENTRAL/com/google/protobuf/protobuf-javalite/4.26.1/protobuf-javalite-4.26.1.jar"
fetch gesture_recognizer.task "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"

T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
for a in tasks-vision tasks-core; do
  mkdir -p "$T/$a"; unzip -q -o "$CACHE/$a-$V.aar" -d "$T/$a"
  cp "$T/$a/classes.jar" "$OUT/jars/mediapipe-$a-$V.jar"
done
# Only the processors FRIDAY ships for (as for sherpa-onnx): 64-bit and 32-bit ARM.
for abi in arm64-v8a armeabi-v7a; do
  so="$T/tasks-vision/jni/$abi/libmediapipe_tasks_vision_jni.so"
  [ -s "$so" ] || { echo "MediaPipe $abi library missing" >&2; exit 1; }
  mkdir -p "$OUT/lib/$abi"; cp "$so" "$OUT/lib/$abi/"
done
for j in guava-27.0.1-android.jar failureaccess-1.0.1.jar flogger-0.6.jar flogger-system-backend-0.6.jar protobuf-javalite-4.26.1.jar; do cp "$CACHE/$j" "$OUT/jars/"; done
cp "$CACHE/gesture_recognizer.task" "$OUT/assets/mediapipe/"
echo "MediaPipe hand shapes ready in $OUT"
