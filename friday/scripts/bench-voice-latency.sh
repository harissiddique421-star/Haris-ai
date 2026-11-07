#!/usr/bin/env bash
# Times the phone's share of a voice reply on a plain JVM, in real time: how long after the owner's
# last word the Voice ID gate tells Gemini the turn is over (quiet room and with people talking
# behind). Run after scripts/test-voice-models.sh (it reuses that script's compiled classes).
#   scripts/bench-voice-latency.sh [end-of-turn ms, default 600] [voice check slowdown, default 3]
# A slowdown of 3 makes each voiceprint check take three times as long, like a mid-range phone.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=android/app/build/voice-tests
java -cp "$OUT/classes:$OUT/json-20240303.jar" com.myraa.mobile.VoiceLatencyBench android/models android/tests/jvm/fixtures/voiceid "${1:-600}" "${2:-3}" 3
