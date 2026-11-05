# V2 execution evidence — 3 November 2026

Status: private development candidates, **not production ready**. Positive Gemini
requests are blocked by the configured key being rejected. The owner is updating
the key securely. No replacement credential was requested in chat, and no public
release, GitHub upload or store submission was performed.

## Automated source checks

`npm test` executes TypeScript checking, seven Node test suites and the Python
desktop-agent suite. The final full run recorded **142 passed, one skipped,
zero failed**:

| Suite | Passed | Skipped |
| --- | ---: | ---: |
| Agent/core/models/presence/chat | 48 | 0 |
| Character importer/rig pipeline | 8 | 1 |
| V2 permission/chat/voice/gesture/disposal regressions | 16 | 0 |
| Cognition | 24 | 0 |
| API hub | 6 | 0 |
| Screen vision | 6 | 0 |
| Browser | 2 | 0 |
| Python agent | 32 | 0 |

Evidence: `artifacts/windows-tests-final.log`, `artifacts/agent-tests-final.log`.
The skipped legacy archive test searches a Downloads location; supplied archives
were separately inventoried, parsed and exercised by the runtime tests below.
Provider error/quota unit tests inject failures; they do not establish live
provider availability. Screen-vision denial is deliberately exercised by tests.

## Real Windows runtime

`npx electron tests/windows-app-smoke.cjs` launches the real Electron entry and
backend with an isolated profile and seeded conversation. Startup, populated UI,
API responses, launch consent defaults, camera denial, renderer crash recovery,
history preservation and induced WebGL context-loss recovery passed. The recovered
avatar uses a fresh canvas and resumes rendering. The final UI remains responsive.
Evidence: `artifacts/windows-app-smoke.json`, `artifacts/windows-app.png`.

`MYRAA_QA_OPEN_NOTE=1 node --import tsx scripts/qa-desktop-actions.ts` ran the
frozen desktop agent on its own loopback port. All five checks passed: frozen
startup, actual file creation, actual file reading, denied file opening with no
native launch call, and one-time approved opening of the exact test note in a
real application. Its uniquely named note window was closed afterwards. Normal
MYRAA permissions and user files were untouched. This test found and fixed an
existing `fs.open` dispatch fall-through into `selectFiles`.
Evidence: `artifacts/desktop-actions.json` and `.log`.

## Models, motion and physics

Discovery parsed 19 distinct PMX files without parse errors: 12 humanoid variants
and seven separate props. Three previously absent humanoids were imported as new
local library IDs. See V2-MODELS.md for hashes, geometry/rig counts and limitations.

The final full renderer run passed **180/180 real WebGL/rig/physics checks**,
15 per character across all 12 humanoids, including Vivian. All happy-wave
regressions passed: finite transforms, no straight horizontal arm frames from a
neutral start, and maximum hand displacement below 1.85% of model height per
simulation frame. Evidence: `artifacts/renderer-results.json`.

Failures investigated during development:

- Vivian rendered black after repeated model switches, while its isolated test
  passed. Explicit context disposal and bounded shader/context recovery were
  added. Subsequent full switching runs rendered all 12 characters.
- A wave probe counted an outgoing random stretch as a wave starting pose.
  The wave regression now starts from a settled neutral pose. The original
  transition probe is retained in `renderer-transition-probe.json`.
- A later Jane Doe cloth settling test failed while random idle gestures could
  still move the skeleton. The idle-behaviour toggle was being overwritten each
  frame; this also affected companion walking. The toggle now persists, and the
  runtime test holds random gestures off while measuring a controlled impulse.
  Breathing and spring simulation remain active. The failed probe is retained
  in `renderer-random-settle-probe.json`; the final full rerun passed.
- Vivian's sleeve could occupy a portrait-frame corner, producing a false
  opaque-background failure. The check now temporarily hides the character,
  measures the whole background's alpha, restores the character and separately
  verifies visible coverage. The earlier probe remains in
  `renderer-before-transparency-fix.json`.

The 15 checks per model cover rendering, texture decoding, skeleton mapping,
body editing/reset, hand/foot IK, fingers, morphs, procedural idle, hair and
clothing impulse response/settling, transparent companion rendering, dragging,
sitting and cursor gaze. No authored animation clip import or full fabric
self-collision is claimed.

## Installer and packaging

The NSIS candidate was installed and uninstalled inside Windows Sandbox. All
eight checks passed: installer exit, launcher/runtime files, backend startup on
a clean environment without separately installed Node/Python, launch-consent
default, absent provider key, served UI, uninstall and preservation of the test
profile. The owner's installed MYRAA version was not replaced.

Evidence: `artifacts/sandbox/installer-result.json`; harness:
`scripts/sandbox-install-test.ps1`. The disposable sandbox was stopped after the
test. A rebuild including the last command/runtime repairs passed the same eight
checks in a fresh sandbox (`installer-result-final.json`). An earlier recheck
exited with code 2 before installation; its failed result is retained in
`installer-recheck-failed.json`. The archive integrity check and fresh-sandbox
retry passed. The exact final handoff installer in `release/final` was then
installed, started and uninstalled successfully, passing all eight checks:
`artifacts/sandbox/installer-result-handoff.json`. Windows executables are unsigned.

An initial combined NSIS/portable packaging run hit a temporary 7-Zip file lock
after producing NSIS. The failed build log is retained. Final packaging uses a
fresh candidate directory, with compression level 4 to reduce build time; it
does not alter runtime features. No platform security setting was disabled.

## Android execution

The real private APK was compiled with Java/D8/AAPT2, ZIP-normalized, aligned,
development-signed and signature-verified. It installed on the dedicated Android
35 x86_64 emulator, running independently of the Windows MYRAA backend.

All **17 instrumentation checks passed**: Activity/WebView startup, actual PMX
loading, skeleton and spring nodes, bundled HTTPS origin, no bundled key, happy
wave, sit, missing-key error, persisted native settings, settings dialog, actual
Medium-preset rebuild, real Google rejection of a deliberately invalid key,
history preservation on failure, Stop, pause and resume.

An installed TTS engine emitted a speech-start callback. The emulator runs with
audio disabled, so audible output was not verified. Android reports a speech
recognition activity is available; microphone transcription has not been tested.
The test invalid credential was cleared. The final APK rerun after the shared
idle-toggle repair passed all 17 checks; its measured sample was 30.00 fps,
33.33 ms between frames, 4.95 ms CPU update/render submission and 1.52 ms spring
step time. Adaptive physics selected 30 Hz under the concurrent test workload.

Evidence: `artifacts/android-instrumentation-final.log`, `artifacts/android-build.log`,
`artifacts/android/verified-host-app.png`. Earlier software-GPU results are kept
separately; the final run uses the host NVIDIA GPU.

## Performance

The normal hidden-window QA run is
compositor-throttled near 1 fps and is **not** used as an interactive performance
claim. `tests/renderer-benchmark.cjs` uses Electron's offscreen GPU compositor at
960×800 and 60 Hz, warms each preset for eight seconds, and samples actual
per-process CPU/memory counters every second. Frame cost measures CPU update and
render submission, not total GPU execution time. These are scoped measurements
on this PC, not device-wide guarantees or a long-duration soak.

Host: Intel Core i3-10105F (eight logical processors), approximately 16 GiB RAM,
NVIDIA RTX 5060 Ti using ANGLE/D3D11. Background user applications and a sandbox
were active; these are not isolated laboratory comparisons. Medium/High timing
differences should not be interpreted as a strict ranking.

| PC preset | Measured fps | Frame interval | CPU update/submission | Effective spring rate |
| --- | ---: | ---: | ---: | ---: |
| Low | 30.00 | 33.33 ms | 10.10 ms | 30 Hz |
| Medium | 59.01 | 16.95 ms | 14.60 ms | 30 Hz (adaptive) |
| High | 60.00 | 16.67 ms | 12.37 ms | 60 Hz (adaptive) |

All three samples retained 135 spring nodes, 27 colliders and 62 draw calls.
The 52 one-second Electron process samples recorded:

| Process role | Mean reported CPU % | Peak working set |
| --- | ---: | ---: |
| Browser/main | 1.08 | 814 MiB |
| GPU service | 6.74 | 1,117 MiB |
| Renderer | 5.75 | 432 MiB |
| Utility | 0.05 | 54 MiB |

CPU values are Electron's per-process `percentCPUUsage` measurements. The GPU
service row is its **CPU** usage, not a GPU utilization percentage. Working sets
include caches/shared pages and sequential preset loading; adding their peaks
does not produce a unique physical-memory total. GPU utilization, dedicated VRAM
and network/AI latency under successful provider requests were not measured.
Memory remains substantial on this Electron/PMX stack; extended soak profiling
and lower-end hardware testing remain necessary. Evidence:
`artifacts/renderer-benchmark.json`.

Build-artwork fallback was exercised in a fresh temporary source layout: missing
PNG/ICO files generated a neutral M mark, and running against the owner's existing
artwork left it intact. Original artwork is not part of the clean source archive.

The committed source (`b72684d`) was archived and extracted into a separate clean
directory with no model assets, user data or artwork. Its public frontend/backend
build and C# launcher build passed, including generation of the neutral icon.
The check reused installed locked dependencies through a `node_modules` junction;
it was not a fresh dependency download. Evidence: `artifacts/source-export-build.log`.

Android host-GPU instrumentation has measured about 29–30 fps at the 30-fps cap;
software SwiftShader measured about 16 fps after mobile texture/resolution
optimizations. The GPU strings identify an NVIDIA RTX 5060 Ti. A separate Android
main-process memory sample was about 88 MiB PSS / 222 MiB RSS; it excludes separate
WebView renderer/GPU processes. It is not a whole-app memory total. No physical
phone, battery or thermal measurements are available.

## Security, source and release limits

The production npm audit reported zero findings after compatible updates and a
`qs` override. Fifteen moderate development-tool findings remain. No forced major
dependency upgrade was applied. Core dependency licence metadata is recorded in
`artifacts/dependency-licenses.json`; source notices do not grant model rights.
The staged source scan found no known Gemini/OpenAI credential patterns or
private-key blocks. This is a targeted scan, not a comprehensive security audit.

Implemented but not fully verified: successful Gemini text replies/persistence
against a valid key, Live voice success/fallback against real provider failures,
audible local TTS, Hindi/Hinglish microphone accuracy, multi-monitor/DPI combinations,
extended unattended task/soak tests, phone hardware/battery/thermal behaviour.

Partial or not implemented: Android model import/multiple characters, Windows
memory sync or secure pairing, unrestricted Android automation, imported animation
clips, advanced fabric self-collision, public Android asset-free distribution,
trusted Windows signing and production Android signing. Mobile has conversation
context rather than the full Windows long-term memory/task system. It currently
supports Gemini REST rather than the entire desktop provider registry.

Private binaries include existing third-party Evelyn assets. They are not cleared
for redistribution. Application source excludes models, user data, API keys,
signing material, generated releases and Python bytecode.
