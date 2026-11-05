# MYRAA V2 development record

Started 3 November 2026 (Asia/Kolkata). Existing source: MYRAA 1.5.2.
Work remains a development candidate until the checks below have evidence.

## Protected baseline

- Git branch `myraa-v2`, tag `baseline-1.5.2-20261103`.
- Independent source copy: `../MYRAA-V2-checkpoints/baseline-20261103`.
- Original models, credentials, conversations and user settings are not edited.
- No Blender, OBS changes, public uploads or newly generated models.

## Audit

- Electron 43, Node/Express backend, React 19, TypeScript 5.8, Vite 6,
  Three.js 0.180; custom PMX loader and procedural avatar runtime.
- Python desktop agent; existing PyInstaller spec and NSIS/portable packaging.
- Gemini conversation and Live voice, with a provider registry/router for text.
- Existing user-data character library, rig inference, expressions, gaze, IK,
  spring strands/colliders and vertex clothing; preserve and repair these.
- Existing task loop observes, plans, checks permissions, executes and verifies;
  a separate legacy voice tool route also needs inspection.
- Memory: structured cognition stores, contacts, working memory and history.
- No Android source or Android SDK found in initial inspection. Java 21 exists.
- Baseline `npm test` FAILED at TypeScript: old exported source under
  `release-public/packs` was included and referenced omitted character config.
- App launch default was permissive. Session grants ignored paths/commands.
- Confirmation broker retains abort listeners and misses already-aborted scopes.
- Behaviour interruption replaces the active gesture immediately.
- Late async character loads can leak model resources after disposal.
- Existing historical test claims in FINAL-UPDATE-REPORT.md are not V2 evidence.

## Priorities and evidence

- [x] Audit architecture and make a recoverable source baseline.
- [x] Inventory 19 distinct source models, deduplicate hashes, stage three absent humanoids safely.
- [x] Repair build scope; automated suites record 142 passed and one skipped.
- [x] Enforce app-launch consent in task and legacy voice routes; test denial and native approved file opening.
- [x] Repair approval cancellation, scoped grants and emergency stop regressions.
- [x] Repair gestures/transition continuity and disposal on model switching.
- [x] Exercise all 12 humanoids: final full run passes 180/180 runtime checks and all wave regressions.
- [x] Connect graphics presets to simulation cost; record scoped CPU/memory/frame measurements.
- [x] Improve voice error classification, bounded recovery and independent text continuity; positive provider checks remain gated below.
- [x] Run real Windows app; verify renderer/GPU recovery and package private installer/portable candidates.
- [x] Implement standalone Android with the existing renderer and native networking/history/speech services.
- [x] Build/install APK and pass 17 emulator checks; explicitly record microphone, audible speech and hardware gaps.
- [x] Compile the clean source export, verify final installer and preserve build/test evidence.
- [x] Final source/evidence exports and artifact manifest; paths are in V2-HANDOFF.md.

## Open release gates

- Owner is replacing the rejected Gemini key securely. Successful live text and
  Live voice requests must be retested after the owner confirms readiness.
- Android has emulator evidence only. Physical-device rendering, voice input,
  audible TTS, battery and thermal tests are outstanding.
- Long-duration stability, additional PC/GPU/multi-monitor/DPI combinations and
  the remaining development-tool advisories require further review.
- Binaries are private development candidates with unsigned Windows executables,
  Android development signing and third-party assets. They are not production
  releases and are not cleared for public distribution.

Only executed successful checks may be marked passed. Model author restrictions
must accompany local copies; private test assets cannot enter public source releases.
