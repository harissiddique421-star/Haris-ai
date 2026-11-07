# On-device voice models

Both models run inside the app in plain Java (no native libraries, no internet). Weights are
stored as safetensors (8-byte header length, JSON header, little-endian float32 tensors).

| File | What it does | Source | Licence | SHA-256 |
|---|---|---|---|---|
| `silero_vad_16k.safetensors` | Speech detection (Silero VAD v5, 16 kHz) | `silero_vad/data/silero_vad_16k_op15.onnx` from `pip download silero-vad==6.2.3`, weights extracted with `scripts/voice-models/silero_onnx_to_safetensors.py`. Outputs are identical to the package's default `silero_vad.onnx` at 16 kHz. | MIT, Copyright (c) 2020-present Silero Team | `671509ad5ab8e5a32e0f44af434117afeb019ec3193eb3927bead955bbe59b95` |
| `voice_encoder.safetensors` | Speaker voiceprints (GE2E speaker encoder, 256-d) | `resemblyzer/pretrained.pt` from `pip download resemblyzer==0.1.4`, converted with `scripts/voice-models/resemblyzer_to_safetensors.py` (no PyTorch needed) | Apache-2.0, Resemble AI | `147e0e16674dfe2d51a940e648ab9707533b804d057c2b640031546f3de058c2` |

The Java code (`SileroVad.java`, `VoiceEncoder.java`) is checked against independent reference
outputs by `scripts/test-voice-models.sh`, which CI runs before building the APK. To regenerate
the reference file after changing a model, see `scripts/voice-models/make_reference.py`.

The test fixtures in `android/tests/jvm/fixtures` are synthetic speech made with espeak-ng; those in
`fixtures/voiceid` are 4 s cuts of real LibriSpeech recordings (CC BY 4.0) for the Voice ID checks.
