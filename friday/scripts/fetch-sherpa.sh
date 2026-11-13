#!/usr/bin/env bash
# Downloads the sherpa-onnx voice engines FRIDAY uses (see SherpaVoice.java) and checks each file
# against the SHA-256 pinned in android/models/sherpa-onnx.sha256:
#   - sherpa-onnx native library (JNI + onnxruntime), Apache-2.0 / MIT
#   - NeMo TitaNet small speaker model (CC-BY-4.0), sherpa-onnx export
#   - English keyword spotting model (zipformer, gigaspeech, 3.3M parameters), Apache-2.0
#
#   scripts/fetch-sherpa.sh android OUT   OUT/lib/<abi>/*.so and OUT/assets/sherpa/... for the APK
#   scripts/fetch-sherpa.sh jvm OUT       OUT/lib/*.so (linux-x64) and OUT/models/... for the JVM checks
#
# keywords.txt (the wake phrases as the model's word pieces) is committed in android/models,
# made with sherpa_onnx.text2token from the model's bpe.model.
set -euo pipefail
cd "$(dirname "$0")/.."
MODE="${1:?android or jvm}"; OUT="${2:?output directory}"
VER=2.0.1
REL=https://github.com/k2-fsa/sherpa-onnx/releases/download
PINS=android/models/sherpa-onnx.sha256
CACHE="${SHERPA_CACHE:-$HOME/.cache/friday-sherpa}"; mkdir -p "$CACHE" "$OUT"

# fetch NAME URL: downloads into the cache once, then checks the pinned hash.
fetch() {
  local name="$1" url="$2" f="$CACHE/$1"
  if [ ! -s "$f" ]; then curl -fsSL --retry 4 --retry-delay 5 -o "$f.part" "$url"; mv "$f.part" "$f"; fi
  local want; want=$(awk -v n="$name" '$2==n{print $1}' "$PINS")
  local got; got=$(sha256sum "$f" | cut -d' ' -f1)
  if [ -z "$want" ]; then
    echo "UNPINNED $got  $name" >&2
    [ "${SHERPA_ALLOW_UNPINNED:-0}" = 1 ] || { echo "Pin $name in $PINS first." >&2; exit 1; }
  elif [ "$want" != "$got" ]; then
    echo "Checksum mismatch for $name: expected $want, got $got" >&2; rm -f "$f"; exit 1
  fi
}

KWS=sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01
fetch titanet_small.onnx "$REL/speaker-recongition-models/nemo_en_titanet_small.onnx"
fetch "$KWS.tar.bz2" "$REL/kws-models/$KWS.tar.bz2"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
tar -xjf "$CACHE/$KWS.tar.bz2" -C "$T"
K="$T/$KWS"
pick() { find "$K" -maxdepth 1 -name "$1" | sort | head -1; }
ENC=$(pick 'encoder-*chunk-16-left-64.int8.onnx'); DEC=$(pick 'decoder-*chunk-16-left-64.onnx'); JOI=$(pick 'joiner-*chunk-16-left-64.int8.onnx')
for f in "$ENC" "$DEC" "$JOI" "$K/tokens.txt"; do [ -s "$f" ] || { echo "Keyword model file missing in $KWS: $f" >&2; ls "$K" >&2; exit 1; }; done

if [ "$MODE" = android ]; then
  A="$OUT/assets/sherpa"; mkdir -p "$A/kws"
  cp "$CACHE/titanet_small.onnx" "$A/titanet_small.onnx"
  fetch "sherpa-onnx-v$VER-android.tar.bz2" "$REL/v$VER/sherpa-onnx-v$VER-android.tar.bz2"
  tar -xjf "$CACHE/sherpa-onnx-v$VER-android.tar.bz2" -C "$T"
  for abi in arm64-v8a armeabi-v7a; do
    d=$(find "$T" -type d -path "*/$abi" | head -1)
    [ -n "$d" ] && [ -s "$d/libsherpa-onnx-jni.so" ] && [ -s "$d/libonnxruntime.so" ] || { echo "Android $abi libraries missing in the sherpa-onnx archive" >&2; find "$T" -name '*.so' >&2; exit 1; }
    mkdir -p "$OUT/lib/$abi"; cp "$d/libsherpa-onnx-jni.so" "$d/libonnxruntime.so" "$OUT/lib/$abi/"
  done
  KD="$A/kws"
elif [ "$MODE" = jvm ]; then
  mkdir -p "$OUT/models/kws" "$OUT/lib"
  cp "$CACHE/titanet_small.onnx" "$OUT/models/titanet_small.onnx"
  fetch "sherpa-onnx-v$VER-linux-x64-jni.tar.bz2" "$REL/v$VER/sherpa-onnx-v$VER-linux-x64-jni.tar.bz2"
  tar -xjf "$CACHE/sherpa-onnx-v$VER-linux-x64-jni.tar.bz2" -C "$T"
  for so in libsherpa-onnx-jni.so libonnxruntime.so; do
    f=$(find "$T" -name "$so*" -type f | sort | head -1)
    [ -n "$f" ] || { echo "$so missing in the linux-x64 JNI archive" >&2; find "$T" -name '*.so*' >&2; exit 1; }
    cp "$f" "$OUT/lib/$so"
  done
  # onnxruntime may be linked by its versioned name.
  for f in $(find "$T" -name 'libonnxruntime.so.*' -type f); do cp "$f" "$OUT/lib/"; done
  KD="$OUT/models/kws"
else
  echo "Mode must be android or jvm" >&2; exit 1
fi
cp "$ENC" "$KD/encoder.onnx"; cp "$DEC" "$KD/decoder.onnx"; cp "$JOI" "$KD/joiner.onnx"; cp "$K/tokens.txt" "$KD/tokens.txt"
if [ -s android/models/sherpa-keywords.txt ]; then cp android/models/sherpa-keywords.txt "$KD/keywords.txt"; else echo "Missing android/models/sherpa-keywords.txt" >&2; [ "${SHERPA_ALLOW_UNPINNED:-0}" = 1 ] || exit 1; fi
if [ -n "${SHERPA_KEEP_BPE:-}" ]; then cp "$K/bpe.model" "$SHERPA_KEEP_BPE"; fi
echo "sherpa-onnx $VER engines ready in $OUT"
