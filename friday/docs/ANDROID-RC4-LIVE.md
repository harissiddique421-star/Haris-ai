# Android RC4 — in-app Gemini Live voice

The owner clarified that mobile must use the same live audio conversation as PC.
RC3's Android recognizer / TTS conversation loop is replaced by Gemini Live audio.

The PC MyraaAudioSession now accepts an optional transport. Mobile reuses its
16kHz PCM microphone capture, 24kHz Gemini playback, output analyser/lip sync,
playback scheduling, echo guard and interruption handling. Desktop transport
remains the default. MobileLiveTransport maps the Gemini Live WebSocket protocol
onto that engine; it does not call Android's speech recognizer or TTS for Live.

Native Android code requests microphone permission, keeps the saved durable key
in Keystore-protected storage, and provisions one-use Live-only ephemeral tokens
with a 60-second new-session window and 30-minute expiry. Only that short-lived
token reaches the bundled WebView. The Live model defaults to the PC default
(gemini-3.1-flash-live-preview), with Aoede native voice; model is editable in
Settings. No desktop tools are declared on mobile.

Audio capture permission is restricted to the bundled app origin. Stop,
backgrounding, socket errors, cancellation and late credentials are handled by
closing the socket and microphone tracks. Console logs omit page error contents
so WebSocket credential URLs cannot leak through console diagnostics.

Protocol reference: https://aistudio.google.com/docs/live-api/get-started-websocket
Token reference: https://aistudio.google.com/docs/live-api/ephemeral-tokens

Validation: TypeScript passed; signed Android build passed; shared-engine mobile
UI smoke 16 passed using real WebAudio, a fake audio device and simulated provider.
Real Android instrumentation is recorded separately. No live Google success is
claimed without an active project/key with access to the selected Live model.
Previous RC3 platform-speech evidence does not validate this implementation.

Real Android instrumentation: 37 passed. Confirmed native token provisioning, actual WebView microphone PCM capture, Gemini PCM playback without platform TTS, captions and Stop cleanup. Owner preferences were byte-for-byte unchanged. The missing MODIFY_AUDIO_SETTINGS permission was found and corrected during native testing. Actual Google calls were simulated. APK installed and reopened in the emulator; software rendering was used after the host GPU boot stalled.
