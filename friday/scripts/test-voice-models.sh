#!/usr/bin/env bash
# Checks the on-device voice models on a plain JVM (no phone needed):
#  - VoiceModelsCheck: Silero VAD, mel spectrogram and GE2E voiceprints match independent
#    reference outputs (official ONNX model in onnxruntime, librosa, onnxruntime LSTM).
#  - SpeechSegmenterCheck: speech start/end, pauses, silence timeout, noise and background talk.
#  - VoiceGateCheck: HARIS Voice ID on real recorded speech: the owner is let through (and the turn
#    ends 0.6 s after a short command, also with people talking behind); a friend,
#    two people at once, the TV and FRIDAY's own voice are not; the owner in loud steady noise is
#    (background noise removed for the check) and others in the same noise are not; profile calibration and storage.
#  - SelfImprovementCheck: health ledger, self-analysis, development memory, approvals, self-healing.
#  - UpdatePlanCheck: which GitHub releases are offered and which downloaded APKs are accepted.
#  - TextProvidersCheck: OpenRouter / OpenAI-compatible addresses, request conversion, replies, errors, usage.
#  - VoiceNotesCheck: WhatsApp voice note detection, answer parsing, private store, duplicates, delete.
#  - NoteLogicCheck: Second Brain note kinds, tags, titles, secret filter and typo-tolerant search.
#  - LinkSafetyCheck: warning signs in links and messages (look-alike sites, shorteners, scam words).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=android/app/build/voice-tests; rm -rf "$OUT"; mkdir -p "$OUT/classes"
JSON="$OUT/json-20240303.jar"
curl -fsSL -o "$JSON" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
echo "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed  $JSON" | sha256sum -c -
SRC=android/app/src/main/java/com/myraa/mobile
javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$JSON" -d "$OUT/classes" \
  "$SRC/Safetensors.java" "$SRC/SileroVad.java" "$SRC/VoiceEncoder.java" "$SRC/SpeechSegmenter.java" \
  "$SRC/SpeakerProfile.java" "$SRC/NoiseReducer.java" "$SRC/VoiceGate.java" "$SRC/VoiceGateSession.java" "$SRC/ControlLog.java" \
  "$SRC/MemoryRank.java" "$SRC/NoteLogic.java" "$SRC/LinkSafety.java" "$SRC/Persona.java" "$SRC/SelfImprovement.java" "$SRC/UpdatePlan.java" "$SRC/UnlockTask.java" \
  "$SRC/VoiceNotes.java" "$SRC/ContactMatcher.java" "$SRC/TextProviders.java" "$SRC/GeminiAccess.java" android/tests/jvm/android/content/Context.java \
  android/tests/jvm/android/util/Log.java android/tests/jvm/com/myraa/mobile/*.java
java -cp "$OUT/classes:$JSON" com.myraa.mobile.VoiceModelsCheck android/models android/tests/jvm/fixtures
java -cp "$OUT/classes:$JSON" com.myraa.mobile.SpeechSegmenterCheck android/models android/tests/jvm/fixtures
java -cp "$OUT/classes:$JSON" com.myraa.mobile.VoiceGateCheck android/models android/tests/jvm/fixtures/voiceid
java -cp "$OUT/classes:$JSON" com.myraa.mobile.MemoryRankCheck
java -cp "$OUT/classes:$JSON" com.myraa.mobile.SelfImprovementCheck
java -cp "$OUT/classes:$JSON" com.myraa.mobile.UpdatePlanCheck
java -cp "$OUT/classes:$JSON" com.myraa.mobile.UnlockTaskCheck
java -cp "$OUT/classes:$JSON" com.myraa.mobile.VoiceNotesCheck
java -cp "$OUT/classes:$JSON" com.myraa.mobile.TextProvidersCheck
java -cp "$OUT/classes:$JSON" com.myraa.mobile.NoteLogicCheck
java -cp "$OUT/classes:$JSON" com.myraa.mobile.LinkSafetyCheck
