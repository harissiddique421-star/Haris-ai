# MYRAA V2 private candidate handoff

These are development candidates, not production-ready releases. No public
distribution was performed. Positive Gemini requests remain blocked pending the
owner's secure key update. Say **ready** after saving the key in MYRAA's AI
settings; never paste it into chat.

## Files

All paths below are under `G:/MYRAA TO 3D/myraa-ai-assistant/`.

| Delivery | Path |
| --- | --- |
| Run Windows | `release/final/win-unpacked/MYRAA.exe` |
| Windows installer | `release/final/MYRAA-Setup-2.0.0-rc.1.exe` |
| Windows portable package | `release/final/MYRAA-Portable-2.0.0-rc.1.exe` |
| Android APK | `artifacts/android/MYRAA-Mobile-1.0.0-rc.1-private.apk` |
| Android source | `android/app/src/main/` and `src/mobile/` |
| Clean source archive | `release/final/MYRAA-V2-source.zip` |
| Test evidence archive | `release/final/MYRAA-V2-evidence.zip` |
| File hashes | `release/final/SHA256SUMS.txt` |

The Windows installation test ran in Windows Sandbox. The owner's installed app
was not replaced. Runtime data, original assets and existing character IDs were
preserved. Three absent models were added under new IDs in the existing local
character library. Private Windows/Android binaries contain third-party assets
and the owner's original artwork; they are not cleared for public sharing.

## Verified evidence

- Automated source checks: 142 passed, one legacy archive-location test skipped.
- All 12 humanoids: 180/180 runtime checks, plus passing finite/continuous wave
  regressions with no straight horizontal arm frames from neutral.
- Real Windows app: startup, permission denial, renderer crash/history recovery
  and induced GPU-loss recovery. Screenshots confirm the recovered avatar.
- Real frozen desktop agent: five native file-operation/consent checks passed.
- Exact final installer: eight fresh-sandbox install/start/uninstall checks passed.
- Android APK: signature verification, installation and all 17 emulator checks
  passed. Host-GPU sample reached its 30-fps cap.
- Clean source archive: public frontend/backend and launcher compiled in a
  separate directory without models, artwork or user data, using locked installed
  dependencies. Missing artwork generated the neutral source-build icon.

Windows Low/Medium/High samples measured 30.00/59.01/60.00 fps at 960×800 on an
i3-10105F / RTX 5060 Ti PC. CPU, working-set and frame-time details and measurement
limits are in V2-TEST-REPORT.md. These are not guarantees for other hardware.

## Implementation and remaining work

The application keeps its Electron/React/Three.js/PMX architecture. Permission
defaults/scoping and legacy voice authorization were repaired; typed chat now has
independent text routing/history/cancellation and local read-aloud support. Wave
and gesture transitions, model/GPU cleanup, bounded graphics recovery, preset
limits and companion controls were improved. The avatar-first homepage was
extended with chat/history and control affordances. Existing physics, IK, facial
channels, model profiles and long-term Windows memory were retained.

Android is standalone: native HTTPS Gemini chat, Keystore credentials, local
history, delegated speech input, installed TTS, lifecycle control and the shared
renderer/personality. It currently includes Evelyn only. App settings is its
explicit supported device action; it has no unrestricted Android automation.

Pending verification: valid-key Gemini text/Live voice, successful provider
fallback in real service conditions, audible TTS, microphone transcription,
physical Android devices, battery/thermal behaviour, extended unattended soak,
additional PC/GPU and multi-monitor/DPI combinations. The portable package was
built but not separately launch-tested; the installer/unpacked runtime was tested.

Not implemented: Android model import, full desktop provider/memory parity,
Windows/Android pairing or synchronization, imported animation clips, full cloth
self-collision, production signing or public Android asset-free distribution.
Fifteen moderate development-tool advisories remain; the recorded production npm
audit has zero findings.

## Source and reproduction

Branch: `myraa-v2`. Protected baseline: `baseline-1.5.2-20261103`. Implementation
commit: `b72684d`; a following documentation commit records the final handoff.
The source archive excludes third-party models/videos/artwork, credentials,
user profiles, generated releases, Android signing keys and Python bytecode.
The local baseline source copy remains in `../MYRAA-V2-checkpoints/baseline-20261103`.

See V2-BUILD.md for commands and prerequisites, V2-MODELS.md for model hashes and
compatibility, V2-RELEASE-NOTES.md for changes, V2-TEST-REPORT.md for logs, failures
and limits, and THIRD-PARTY-NOTICES.md for asset/dependency boundaries. Build logs
and failed probes are preserved alongside the final successful evidence.
