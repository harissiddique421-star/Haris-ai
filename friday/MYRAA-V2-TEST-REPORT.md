# Current MYRAA V2 verification ledger

4 November 2026. This extends the detailed RC1 record in
docs/V2-TEST-REPORT.md. Deadline removed; remaining release gates are open.

| Check | Actual result | Evidence |
| --- | --- | --- |
| Latest npm test | 183 passed, one skipped | artifacts/desktop-session-full-tests.log |
| New Google credential regressions | 20 V2 tests passed, including four new cases | artifacts/auth-key-regressions.log |
| Credential backend routes | Six fixture checks passed; no live Google traffic | artifacts/auth-key-route-smoke.json |
| Latest Windows build | RC2 frontend/backend/launcher/NSIS built | artifacts/auth-fix-build.log, auth-fix-package.log |
| RC2 real app smoke | Eight passed; no live inference | artifacts/auth-fix-app-smoke.json |
| All humanoid runtime checks (RC1) | 180/180; all 12 waves passed | artifacts/renderer-results.json |
| Native desktop file/consent (RC1) | Five passed | artifacts/desktop-actions.json |
| Exact RC1 installer lifecycle | Eight passed in sandbox | artifacts/sandbox/installer-result-handoff.json |
| Android emulator | 17 passed, API 35 / host GPU | artifacts/android-instrumentation-final.log |
| Live provider success | Blocked: account/key consumer state | Owner must resolve in Google |
| Physical Android voice/thermal/battery | Not tested | No physical device evidence |
| Secondary solver regressions | Five passed; fixed display-frame pose loss, pinned pose and primary twist preservation | tests/secondaryMotion.test.ts |
| Evelyn Low secondary scene | 15 phases numerically healthy; mesh deformation measured, mouth-state and sit/stand checks passed; final poses inspected | artifacts/secondary/evelyn-low/result.json and PNGs |
| All-model/preset secondary stability | 36 cases / 540 phases passed; 6° chest cap, mouth-state and sit/stand checks; 33 cases show mesh motion | artifacts/secondary-matrix.json, secondary-matrix-summary.csv |
| Maid chest deformation | Authored post-physics grants repaired; 15-phase pilot healthy, 2,031 weighted vertices, peak ≈0.42% height | artifacts/secondary-grants-pilot/azur-promilia-199001-p1-balanced/result.json |
| All-model naturalness/clipping review | Incomplete; numeric stability is not a visual verdict | Further Windows visual review |
| Complete desktop transition/mode/DPI matrix | Incomplete | Next Windows test phase |
| Overlay shell contracts | Seven passed; modes, legacy migration, drag cancellation, home, negative-coordinate monitor and removal | artifacts/companion-unit-tests.log |
| Production CompanionApp/native overlay | 17 passed; real window/renderer, live settings, Passive GPU guard, shoe framing, one physical display at 100%; fullscreen signals injected | artifacts/companion-native-smoke.json |
| Per-model controls settings API | Four passed; model isolation, limits, real settings file and backend restart | artifacts/upper-body-routes.json |
| Actual per-model controls UI | Five passed with native text input, simulated transport/system; separate from runtime tests | artifacts/upper-body-controls-ui.json |
| Narrow companion/control pilots | Evelyn Low and maid Medium each passed 19 phases at 300×560; off/zero/limit/reset verified | artifacts/secondary-upper-pilot/ |
| Latest source build | Frontend/backend built; not a new installer | artifacts/upper-body-build.log |
| Latest actual-app UI/recovery | 17 passed; fresh build, offline/no owner key, contrast, keyboard focus, reduced motion, presets, draft, renderer/GPU recovery | artifacts/overlay-app-smoke.json |
| Expanded all-model/preset controls matrix | 36 cases / 684 phases passed on 829bc67, 300×560; all rigs show mesh motion, 6° cap and off/zero/limit/reset checks | artifacts/secondary-upper-summary.json, secondary-upper-matrix-summary.csv |
| Authenticated worker transport | Seven unit tests passed, including transient health failure without worker restart | artifacts/desktop-session-client-tests.log |
| Python session/watchdog | Six unit tests passed | artifacts/desktop-session-auth-tests.log |
| Real source-worker authentication/lifecycle | 15 passed; direct routes protected, forged Host/Origin rejected, normal and abrupt-owner cleanup | artifacts/desktop-session-source.json |
| Real frozen-worker authentication/lifecycle | 15 passed on rebuilt protocol-2 worker | artifacts/desktop-session-frozen.json |
| Bundled backend/frozen worker integration | Seven passed; private health proxy, one session, shell-IPC-loss shutdown and worker cleanup; baseline fails the new shell-loss check | artifacts/desktop-bridge-smoke.json, desktop-shell-loss-baseline.json |
| Authenticated native file/consent | Five passed; own note create/read, direct execution blocked, launch denial; no application opened | artifacts/desktop-actions-authenticated.json |
| Actual app after transport integration | 17 passed, offline UI and renderer recovery; no live inference | artifacts/desktop-session-app-smoke.json |
| Actual corrected RC3 packaged runtime | 10 passed; exact bundle/stamp, offline/consent APIs, assets, real launcher/shell, forced-shell-crash closes backend and worker | artifacts/rc3-packaged-runtime.json |
| Corrected RC3 distributable payloads | 14 passed; Setup/Portable outer and embedded integrity, five exact component hashes each; not installation or portable launch | artifacts/rc3-payload-verification.json |

The runtime physics solver contains chest-class chains on all current humanoids.
This is inventory information, not proof that natural visible upper-body dynamics
are complete. No test may be marked passed until executed successfully.

Steam protection: Call of Duty library is on I:, discovered through read-only
manifest inspection. No download controls, files or settings were changed.
