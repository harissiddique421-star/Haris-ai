# MYRAA V2 autonomous development checkpoint

Updated 4 November 2026 (Asia/Kolkata). The user's latest master brief removes
the 48-hour deadline. Continue Windows stabilization/companion work before
expanding Android. Do not label either platform production ready.

## Operating constraints

- Preserve Steam and its Warzone download. Read-only Steam library metadata
  locates Call of Duty on **I:**; never write there for MYRAA development.
- No Steam/process/service restarts, download/cache/settings changes, network
  changes, reboot, unrelated cleanup, OBS changes or public uploads.
- Use installed dependencies/Android SDK. No new large downloads are needed.
- Run development builds/tests serially at BelowNormal process priority; inspect
  free space first. G: had about 5.6 GB free after RC2 packaging. Avoid more
  duplicate large packages while developing small source/test changes.
- No paid model calls/resources without explicit authorization. Live inference
  is currently blocked by Google's credential/account state in any event.
- Do not manipulate the owner's Google credentials or Cloud account settings.
  The owner must replace the exposed key and resolve inactive/suspended resources.

## Protected working state

Repository: `G:/MYRAA TO 3D/myraa-ai-assistant`, branch `myraa-v2`.
Baseline tag `baseline-1.5.2-20261103`; source checkpoint in
`../MYRAA-V2-checkpoints/baseline-20261103`. RC1 implementation `b72684d`,
documentation `8c4c680`, tag `myraa-v2-rc.1-private`.
Do not stage unrelated untracked `.claude`, Desktop, Downloads, notes or launch
files. Do not delete user data, original assets or old recoverable candidates.

## Completed and evidenced

- Existing Electron/React/Three.js/PMX architecture retained. No Blender/new models.
- Inventory: 19 distinct PMX files, 12 humanoid variants plus seven props;
  three missing humanoids safely staged as new local IDs. See docs/V2-MODELS.md.
- Permission scoping/defaults, task and legacy voice authorization, cancellation,
  origin isolation, text history/fallback, bounded voice recovery and local TTS.
- Wave/rest-pose repairs, gesture crossfades, model/GPU cleanup, bounded context
  recovery, persistent idle-gesture control and real preset/physics budgets.
- RC1: all 180 real runtime checks across all 12 characters and all wave probes
  passed. Real main-window crash/GPU recovery, five native file/consent checks,
  and eight clean-sandbox installer checks passed. This is not a long soak test.
- Standalone Android candidate exists, reusing the real renderer with native
  HTTPS, Keystore, conversation history, delegated STT and installed TTS.
  APK/signature/install and 17 Android 35 emulator checks passed. No phone tests.
- Existing measured PC Low/Medium/High: 30/59/60 fps in the scoped benchmark;
  Android host-GPU sample about 30 fps. See docs/V2-TEST-REPORT.md for limits.

## Current authentication repair (RC2)

AQ. is Google's legitimate new authorization-key format. The owner's local
log shows ACCOUNT_STATE_INVALID (linked service account disabled/deleted), then
CONSUMER_SUSPENDED. This is not evidence of a personal Google-login failure.
Do not retest or copy the key pasted into chat.

Implemented precise safe diagnostics, AQ redaction in server/shared/Electron
logs and old-log views, bounded key validation, and honest unverified-save UI.
Full automated suite: **146 passed, one skipped**. Six actual backend-route
checks passed against a deterministic Google transport fixture; no real key
or Google request was used by that fixture. Existing encrypted key survives a
rejected replacement. Windows RC2 built separately in `release/auth-fix/`.
Real application smoke verification passed all eight startup/policy/history/
renderer/GPU-recovery checks. Evidence is preserved separately in
`artifacts/auth-fix-app-smoke.json`. Preserve prior candidates and the active
owner app while continuing development.

## Current Windows physics checkpoint (source; not packaged)

Reproduced and repaired two solver defects: registered secondary bones lost
their physical pose on display frames between fixed simulation ticks, and the
solver replaced primary animation/twist instead of adding a constrained offset.
Pinned bones now retain the user's pose, including between simulation ticks.
All secondary bones return to the primary pose before each animation evaluation.
The existing SOFT_BODY material remains unchanged (6-degree angular limit).

Five targeted mathematical regression tests and TypeScript checks passed.
The latest full automated suite passed: 158 tests, one skipped, zero failures
(`artifacts/overlay-full-tests.log`, including seven overlay contracts).
The actual Evelyn Low virtual-desktop scene completed 15 phases with finite,
healthy physics, measured mesh deformation, listening mouth closure, speaking
state articulation, return to idle and deterministic sit/stand completion.
Rendered final sit/stand images were inspected; peak and final poses are saved
separately. This tests animation state, not real audio or the OS overlay.
Garment clipping and overall naturalness still need broader visual review.
Evidence: `artifacts/secondary/evelyn-low/`, with peak motion, upper-body detail,
final-pose PNGs and `result.json`.

The full 12-model × three-preset matrix completed **36 cases / 540 phases**
against character-runtime commit `0611c3a`, with no numeric, mouth-state or
sit/stand failures. Chest deviation stayed within 6 degrees. **33 cases / 11
models** showed weighted-mesh deformation. The maid's two simulated bones
have no directly weighted descendants; its authored grants drive weighted
bones on a separate hierarchy. GrantSolver currently runs before physics,
so this authored deformation path misses the physical result. Inspect and
repair post-physics grant propagation, preserving the primary pose and avoiding
double application; do not invent a new deformation or mark the maid complete.
Read-only evidence: `artifacts/secondary/azur-promilia-199001-p1-rig.json`.
Matrix: `artifacts/secondary-matrix.json` and `secondary-matrix-summary.csv`.
The 5-GiB guard stopped after 23 cases; a bounded continuation with 4-GiB reserve
preserved those results and finished the other 13. No Steam files/settings changed.

## Windows gaps to address next

1. Audit and test upper-body/chest secondary dynamics visually and numerically
   on the existing rigs. Every current humanoid reports chest-class chains, but
   previous tests did not establish useful chest deformation. SOFT_BODY currently
   has a 6-degree limit and strong damping/rest pull. Investigate rather than
   adding arbitrary oscillation or changing model identity.
2. Establish explicit desktop transition/physics tests: walking/stopping/turning,
   sit/stand, dance, listening/speaking and model/preset changes. Include rendered
   evidence; numeric health alone is not visual quality.
3. Validate actual transparent overlay, click-through/focus behavior, dragging,
   scaling, saved home, monitor/DPI mapping, hidden/gaming render suspension and
   cleanup. Some implementation exists; do not treat it as fully verified.
4. Add restrained per-model upper-body controls/limits where supported, retaining
   authored rig/physics metadata. Preserve unsupported models with clear reports.
5. Improve the homepage/controls/accessibility and exactly three primary quality
   presets, keeping optional Auto as selection assistance rather than a fourth
   primary preset. Address remaining automation and resource measurements.
6. Re-test Windows installation/upgrade, prolonged stability and provider/voice
   success when the owner supplies working credentials and any required approvals.
7. Harden the Python agent transport before production: its low-level HTTP
   execute/observe endpoints currently rely on loopback/CORS and do not require
   a backend session credential. Add authenticated managed-process transport,
   isolated-port ownership and cleanup; never terminate the active owner's
   legacy agent to make a test pass. The Node consent layer does not replace
   authentication on this lower-level endpoint.

## Overlay source changes (not yet packaged)

Explicit Passive/Interactive/Focus/Hidden/Gaming modes, renderer suspension
messages, timer cancellation/resume, legacy click-through migration, a separate
saved home, monitor targeting/recovery, display/DPI refresh and a show/hide
shortcut are implemented in source. Seven shell-contract tests passed
(`artifacts/companion-unit-tests.log`). The real-window/production CompanionApp
test passed 14 checks (`artifacts/companion-native-smoke.json`): real Evelyn
rendering/alpha, native nonfocusable/always-on-top flags, passive interaction
guards, hidden/gaming render and shell-timer suspension, fullscreen lifecycle,
saved-home DIP placement, monitor targeting and cleanup. Test foreground flags
are injected; no actual game or other app is activated or inspected. One
physical display at 100% scaling was connected. Initial background-color getter
assertion was corrected after reading Electron's API: it returns RGB without
alpha; the rendered-alpha assertion verifies transparency independently.
Do not equate fixture geometry tests with physical mixed-DPI hardware coverage.
Settings now present three primary presets with a separate Auto switch.
Remote font fetching was removed; installed system fonts provide offline fallbacks.
Home-screen controls have stronger contrast, plain voice status/labels and
an opaque conversation dock. UI animation settings and OS reduced-motion
preferences now disable decorative particles/animation. Settings focus is
contained and restored on close. All **17 actual-app UI/recovery checks passed**
after a fresh source build (`artifacts/overlay-app-smoke.json`): offline startup
without borrowing owner credentials, keyboard navigation, reduced motion,
presets/modes, draft retention, renderer crash and GPU context recovery.
The first focus assertion used a fixed 400-ms wait shorter than the closing
spring; polling confirmed correct removal and focus restoration. This was a
test timing correction, not permission to ignore a failing product behavior.
Remaining native visual issue: check shoe clipping at the companion canvas's
bottom edge and correct framing/contact without moving original assets.
These changes are not yet packaged into a new Windows candidate.

## Active source work after checkpoint b052fdd

Post-physics grant propagation is implemented. Four mathematical grant tests
cover primary-pose preservation, idempotence, dependent grants and independent
physics ownership. The real maid pilot completed 15 phases: four authored
grant targets now affect 2,031 weighted vertices, with measured chest deformation,
maximum helper angle 4.83 degrees and peak mesh displacement about 0.42% of model
height. Original assets/profiles remain untouched. Evidence is separate from
the earlier baseline: `artifacts/secondary-grants-pilot/`.

Per-model upper-body controls are implemented in the Physics panel for imported
and built-in characters: enable, strength, stiffness, damping, gravity, maximum
angle (0–6 degrees), whole-character spring budget and reset. Defaults preserve
the prior SOFT_BODY material; authored smaller angle limits remain effective.
Wind no longer excites soft-body chains. Controls persist in app settings by
model ID, rather than rewriting authored profiles. Four real backend-route /
disk / fresh-process persistence checks passed (`artifacts/upper-body-routes.json`).

The latest full suite passed **170 tests, one skipped**
(`artifacts/upper-body-full-tests.log`). A fresh frontend/backend build passed
(`artifacts/upper-body-build.log`). Native overlay verification now passes
**17 checks**, including live settings, avoiding GPU alpha tests in Passive
mode and corrected shoe framing. The native resting image had 33 opaque pixels
at its bottom edge before the perspective-floor correction, and zero afterwards.
This fixes static framing/contact estimation; it is not a planted-foot gait fix.
Alpha hit-test boundary mapping at 150% render scale also has a regression test.

Five actual PhysicsPanel/native-input checks passed with a simulated transport,
labelled as such (`artifacts/upper-body-controls-ui.json`). Real Evelyn Low and
maid Medium pilots each passed all 19 phases at the actual 300×560 companion
aspect (`artifacts/secondary-upper-pilot/`): off/zero remove chest deformation,
the reduced limit is respected, and defaults restore. No live provider calls.
The matrix runner now supports separate run names and a managed local asset
server, so interrupted sessions can resume without overwriting baseline evidence.
The expanded matrix is complete on frozen source `829bc67`: **36 cases / 684
phases passed**, all 12 humanoids across Low/Medium/High. All seven outcome flags
passed per case, including measured mesh motion and upper-body off/zero/limit/reset.
Maximum chest angle was 6 degrees (floating-point tolerance). Summaries are in
`artifacts/secondary-upper-summary.json` and `secondary-upper-matrix-summary.csv`.
High-preset peak/sit/stand contact sheets were inspected. These samples do not
establish natural animation across every frame: transitional hair excursions,
pose-specific lower-edge framing, clothing contact and planted-foot walking
still need visual work. Mouth checks use animation states, not real audio.

## Authenticated desktop worker integration

The source Node bridge now owns one random-port Python worker with a fresh
private session. Every Python route requires authentication; browser Origins
and nonloopback hosts/clients are rejected. The session and provider credentials
are stripped from child environments before user tools can launch applications.
The worker watches its backend/launcher identity and exits after an abrupt owner
failure. Legacy workers are never adopted or terminated; the owner's existing
agent remains untouched. Packaged workers require a protocol/digest stamp.

Seven transport unit tests, six Python authentication/watchdog tests and 15 real
source-worker HTTP/lifecycle checks passed. The latter never observed the desktop
or executed PC actions: an unauthenticated disposable-note write was rejected,
and authenticated dispatch used an unknown no-op tool. The full suite now passes
**183 tests, one skipped** (`artifacts/desktop-session-full-tests.log`). One Host
test initially used Node fetch, which normalized the supplied Host; raw HTTP
exercised the intended forged header and passed. The rebuilt frozen worker also
passed all 15 HTTP/lifecycle checks. Six bundled-backend integration checks and
five authenticated native file/denial checks passed; the last used only its own
temporary note and did not open an application. All 17 actual-app UI/recovery
checks passed again after the transport build. Evidence: `desktop-session-frozen.json`,
`desktop-bridge-smoke.json`, `desktop-actions-authenticated.json` and
`desktop-session-app-smoke.json` under artifacts. Source build succeeds with the
four existing CommonJS/import.meta warnings. Packaging/installer validation is
next; this is not yet a completed Windows release.

The shell-loss regression reproduced a further gap: a hard Electron main-process
crash left the backend alive because no SIGTERM arrived. The backend now exits
when its private shell IPC channel disconnects. Seven bundled-backend checks pass,
and **10 real packaged-runtime checks pass**, including forced termination of the
exact test-owned Electron shell and verifying that its backend, worker and API
port disappear. Standalone non-IPC backend settings/restart checks still pass.
Evidence: `artifacts/desktop-shell-loss-baseline.json`, `desktop-bridge-smoke.json`
and `rc3-packaged-runtime.json`. Owner processes/settings were not terminated.
The initial RC3 package preceded this fix. Distributables were regenerated from
the corrected, smoke-tested unpacked directory. All **14 archive/payload checks
passed**: both outer archives and embedded payloads, plus exact launcher, Electron
runtime, backend, worker and protocol-stamp hashes. This does not establish
installer/upgrade or portable-wrapper execution. Available RAM was about 4.5 GiB;
another Windows Sandbox would breach the 3-GiB host reserve, so no new VM was
launched. Keep the exact RC3 installer/upgrade gate open.

## Android follow-up (after Windows stability)

Physical-device voice/render/battery/thermal testing, valid-key conversation,
provider/memory parity where practical, supported app-opening intents, per-model
settings/import and public asset-free packaging remain incomplete. Pairing is
optional. Do not replace the working standalone candidate with a remote viewer.

## Evidence and current candidates

RC1: `release/final/` and docs/V2-HANDOFF.md. Mobile:
`artifacts/android/MYRAA-Mobile-1.0.0-rc.1-private.apk`.
RC2: `release/auth-fix/win-unpacked/MYRAA.exe` and
`release/auth-fix/MYRAA-Setup-2.0.0-rc.2.exe` (private, unsigned).
RC3: `C:\Users\MSI\MYRAA-V2-builds\companion-rc3\` (private, unsigned), with
unpacked app, installer and portable. The output is on C: to preserve G: free
space; no game-drive files changed. See `docs/V2-RC3.md` for exact verification
scope and next steps. Current owner installation was not upgraded or replaced.
Auth evidence: artifacts/auth-fix-full-tests.log, auth-key-regressions.log,
auth-key-route-smoke.json, auth-fix-build.log and auth-fix-package.log.
Continue from this file; do not restart the entire audit.

## User-requested pause — 4 November 2026

The owner asked to pause development for a few hours and run the existing Android
APK on this PC. Development builds/tests are stopped; the owned QA asset server
on 5189 was stopped. No automatic resume time was requested. Do not resume
development until the owner requests it. The Android emulator is now being
opened for the owner's interactive use; preserve that session while it is in use.

Work saved after private RC3 / `bf476d5`, currently **uncommitted**:

- New `src/companion/FootContact.ts`: rest-calibrated IK through the pose editor,
  primary-pose constraint callback before grants/physics, actual shell movement
  converted to planted-foot targets. User-owned poses keep authority; teleport,
  resize and suspension discard stale anchors. Original assets are unchanged.
- Walking speed now derives from stride and stance time. All 12 characters
  passed 36 virtual locomotion cases (left/right walk, sneak): maximum normal
  drift 1.972 CSS px, sneak 3.240 px; ground error remains up to 0.969% of character
  height (Ellen sneak), so broader sole/clothing/animation polish remains open.
  Baseline Evelyn/maid drift was approximately 23–25 px for normal walks.
  Evidence: `artifacts/locomotion-baseline/`, `locomotion-pilot/`, `locomotion-models/`.
- Native production CompanionApp passed **23 checks**, including actual window
  travel, mid-walk hide cancellation, resume, previous overlay checks. Native
  asynchronous foot sampling: 79 samples, maximum drift 5.876 CSS px on the single
  physical display. Evidence: `artifacts/contact-native-smoke.json`.
- Fixed orphaned walk/peek movement promises in `electron/companion.cjs` using a
  shared completion path and movement epoch. Six regression cases fail on the
  historical source and pass on the current source; all 15 shell unit tests pass.
  Evidence: `movement-cancellation-baseline.log`, `movement-cancellation-tests.log`.
- Full suite: **194 passed, one skipped** (`artifacts/contact-full-tests.log`).
  Fresh source build passed with the four existing import.meta/CommonJS warnings
  (`artifacts/contact-build.log`). Five new FootContact unit tests are included.
- Matrix runner guards now include FootContact and the locomotion QA module.
  Secondary QA forwards virtual movement/teleport to the actor, matching the real
  CompanionApp. The new 12×3×19 secondary matrix has **not started**. Freeze/commit
  this source before starting it under a new run name, e.g. `secondary-contact`.
  The prior 684-phase matrix belongs to 829bc67; RC3 archives remain immutable.

Next after explicit resume: review saved changes, freeze the contact checkpoint,
run the new secondary matrix, then package a separate candidate. Exact installer
upgrade/portable-wrapper launch, longer soak, Google voice and Android device
testing remain open. Do not restate RC3 or the source-only walking work as a
completed production release.

## Resumed API and manual-control fixes — 4 November 2026

The owner explicitly resumed API fixes on Android and PC, followed by manual
character controls. The earlier pause no longer blocks this limited scope.
Private RC4 includes the saved walking changes above and direct local Drag,
Sit, Stand, left/right, Wave and Stop controls. Exact local typed commands run
before cloud chat. Renderer IPC is restricted to the trusted main window.

Validation: full suite 198 passed / one skipped; fresh build passed; real desktop
window smoke 14 checks passed with zero cloud-chat calls. Android RC2 passed 29
isolated real Activity/WebView/encrypted-settings checks. Failed key replacement
preserves the old key/settings; model access is checked before saving.
Evidence: artifacts/api-controls-full-tests.log, manual-controls-app-smoke.json,
android-auth-verification.json. Test transport was simulated; no live Google
success is claimed. Google's CONSUMER_SUSPENDED response requires an active
project credential or resolution by Google. Never reuse chat-exposed keys.

RC4 packaging/verification records are separate from immutable RC3 evidence.
The new full secondary-physics matrix, installer upgrade, portable-wrapper launch,
long soak and physical Android audio/battery tests remain open.

RC4 private candidate packaged at C:\Users\MSI\MYRAA-V2-builds\controls-rc4. Exact packaged runtime 10/10 and installer/portable archive payload 14/14 passed. Source checkpoint 343d2c5; source/evidence ZIPs, manifest and SHA256SUMS preserved alongside EXE/APK. Portable wrapper execution and installer upgrade remain untested.

## User UI feedback — RC5 / Android RC3
Compact desktop message dock and non-scrolling Settings focus. Desktop smoke 19 passed (four Settings cycles). Mobile full-body default, expanded character area, compact recent chat and explicit microphone Talk mode using Android STT -> chat -> TTS -> next turn. Bundled mobile UI smoke 12 passed with simulated native services; no live audio/provider success. See docs/V2-RC5.md. No original model assets changed.

RC5 packaging: original compression failed with insufficient memory; low-memory store build succeeded at release/layout-rc5/MYRAA-Portable-2.0.0-rc.5.exe. Portable archive verification 7/7 passed. Packaged worker digest/source bundle checks passed, but authenticated worker readiness failed twice; do not report packaged runtime smoke as passing. Temporary test candidate processes stopped. Emulator exited twice before updated APK installation; no installed-avatar or live audio success claimed. APK RC3 signed, bundled mobile UI checks 12/12, desktop real-window checks 19/19. Live Google still requires active project credentials.

## Owner correction: true mobile Live voice
RC3's recognizer/TTS loop did not meet the requirement. Android RC4 now reuses the PC MyraaAudioSession via a direct Gemini Live transport and native ephemeral-token provisioning. RecognizerIntent and NativeBridge.listen are removed; RECORD_AUDIO is requested explicitly. TypeScript and signed build passed; 16 shared-engine UI/audio checks passed with simulated Google. Native Android checks pending in artifacts/android-live-instrumentation.log. See docs/ANDROID-RC4-LIVE.md. Do not claim a successful live provider exchange without evidence.

Android RC4 final verification: 37 native checks passed after adding WebView's required MODIFY_AUDIO_SETTINGS permission; 16 shared PC-engine audio/UI checks passed. Signed APK SHA256 c41bae7425a48688464cd314e3dbe4cb46d1b274e13ac2cd60c81280b74511c8. Saved owner preferences unchanged. Installed and open in emulator. Google token/WebSocket responses were simulated in tests; no live provider exchange claimed. Evidence android-live-verification.json, android-live-instrumentation.log and mobile-live-ui.json.

## Android voice actions — RC5
Owner requested opening apps, YouTube/Chrome searches and WhatsApp messaging by voice; explicitly chose prepared drafts with a final Send tap in WhatsApp. Added Live mobile_action declarations, native tool results, local typed aliases, installed-app intents and local contact resolution/selection. Active Live calls continue via an ongoing microphone foreground service; notification Stop closes audio. Cross-app launches require user-enabled floating controls. No accessibility service or auto-send. Verification: TypeScript/build passed; 4 parser tests, 25 shared UI/audio/tool tests, 39 native auth/audio tests and 18 action tests passed. Actual Chrome foreground launch verified. WhatsApp absent in emulator, so its drafts used fixtures. No live Google success claimed. See docs/ANDROID-RC5-ACTIONS.md and artifacts/android-actions-*.log.
