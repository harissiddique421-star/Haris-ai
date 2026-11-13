#!/usr/bin/env bash
# Checks the sherpa-onnx engines (TitaNet Voice ID and Voice Guardian, wake phrase spotting) on a plain
# JVM with the linux-x64 native library, using the models the APK carries. See SherpaCheck.java.
# Needs espeak-ng for the synthetic wake phrases (skipped with a note when it is missing).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=android/app/build/sherpa-tests; rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/wake"
./scripts/fetch-sherpa.sh jvm "$OUT/engines"
JSON="$OUT/json-20240303.jar"
curl -fsSL --retry 4 --retry-delay 5 -o "$JSON" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
echo "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed  $JSON" | sha256sum -c -
SRC=android/app/src/main/java/com/myraa/mobile
javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$JSON" -d "$OUT/classes" \
  $(find android/app/src/main/java/com/k2fsa -name '*.java') \
  "$SRC/Safetensors.java" "$SRC/SileroVad.java" "$SRC/Voiceprinter.java" "$SRC/VoiceEncoder.java" "$SRC/TitaNet.java" "$SRC/WakeSpotter.java" \
  "$SRC/SpeechSegmenter.java" "$SRC/SpeakerProfile.java" "$SRC/NoiseReducer.java" "$SRC/VoiceGate.java" "$SRC/VoiceGateSession.java" "$SRC/ControlLog.java" \
  android/tests/jvm/android/content/Context.java android/tests/jvm/android/util/Log.java \
  android/tests/jvm/com/myraa/mobile/VoiceModelsCheck.java android/tests/jvm/com/myraa/mobile/SpeechSegmenterCheck.java \
  android/tests/jvm/com/myraa/mobile/VoiceGateCheck.java android/tests/jvm/com/myraa/mobile/VoiceLatencyBench.java android/tests/jvm/com/myraa/mobile/SherpaCheck.java
WAKE=()
if command -v espeak-ng >/dev/null; then
  i=0
  for p in "Wake up Friday" "Friday, wake up" "Hey Friday"; do
    for v in "en-us 160" "en-gb 160" "en-us+f3 190" "en-gb+m3 145" "en-us+m7 170"; do
      set -- $v; i=$((i+1)); espeak-ng -v "$1" -s "$2" -w "$OUT/wake/w$i.raw.wav" "$p"
      # espeak-ng writes 22.05 kHz; the check reads any rate and resamples to 16 kHz.
      mv "$OUT/wake/w$i.raw.wav" "$OUT/wake/$(printf %02d $i)-$(echo "$p" | tr -cd 'A-Za-z ' | tr ' ' _)-${1//+/_}.wav"
    done
  done
  WAKE=("$OUT/wake")
else echo "NOTE: espeak-ng not installed, wake phrase recall not checked" >&2; fi
LD_LIBRARY_PATH="$OUT/engines/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" java -cp "$OUT/classes:$JSON" com.myraa.mobile.SherpaCheck \
  "$OUT/engines/lib" "$OUT/engines/models" android/tests/jvm/fixtures/voiceid android/models "${WAKE[@]}"
