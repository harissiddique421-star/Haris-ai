# MYRAA V2 private RC3 checkpoint

4 November 2026. This is an engineering preview, not a completed production
release. Existing installed MYRAA and the owner's profile were preserved.

Candidate folder: `C:\Users\MSI\MYRAA-V2-builds\companion-rc3`.
It contains `win-unpacked/MYRAA.exe`, `MYRAA-Setup-2.0.0-rc.3.exe` and
`MYRAA-Portable-2.0.0-rc.3.exe`. Output is on C: to keep the project drive above
its free-space reserve. Private owner-provided artwork/models remain private;
the executables are unsigned and nothing was published.

Changes since RC2 include five overlay interaction modes, home/monitor recovery,
three primary graphics presets with separate automatic selection, accessibility
and offline renderer recovery improvements, fixed secondary pose/grant propagation,
per-model restrained upper-body controls, shoe-floor framing and alpha boundary
corrections. The desktop bridge now owns an authenticated random-port native
worker. Browser/direct unauthenticated requests cannot execute native tools.
Worker environments exclude session/provider credentials, and shell/backend
failure closes the owned backend/worker instead of leaving an orphan service.

The source checkpoint for the character matrix is `829bc67`; authentication is
`e9e96db`, and shell-loss cleanup is `f9f5007`. Character runtime source remained
identical through the desktop changes. No original model assets were edited.

Executed verification:

- Full suite: 183 passed, one skipped; final source typecheck and build passed.
- All 12 humanoids × three presets × 19 phases: 684 numerically healthy phases,
  measured mesh motion, mouth animation-state, sit/stand and per-model controls.
- Seven transport and six Python auth/watchdog unit tests; 15 real source-worker
  and 15 real frozen-worker HTTP/lifecycle checks.
- Seven bundled-backend and five authenticated native file/denial checks.
- 17 actual-app UI/recovery checks; 17 native overlay checks and five PhysicsPanel
  native-input checks, with the latter's transport explicitly simulated.
- Ten actual packaged-runtime checks, including forced-shell-crash cleanup.
- Fourteen Setup/Portable archive and exact-component payload checks. The final
  distributables contain the same five component bytes as the tested unpacked app.

The initial archive build preceded the shell-loss fix. The corrected backend and
source map were copied into the unpacked app, its exact bundle was validated and
the real packaged runtime was tested. Final NSIS/portable files were regenerated
using electron-builder `--prepackaged` on that corrected folder. Final hashes and
evidence identify these corrected bytes. No installer was run on the host.

Open gates remain: exact RC3 installation/uninstallation and upgrade/migration;
portable-wrapper launch; extended soak and mixed-DPI hardware; transitional
hair/clothing/frame contact and planted-foot walking; live Google text/voice;
and physical Android behavior/performance. Only about 4.5 GiB host RAM was
available after packaging, so another Sandbox VM would breach the 3-GiB host
reserve. RC1's eight Sandbox installer checks do not count as RC3 checks.

Live voice/text success still depends on a valid Google credential/project.
Earlier provider errors were account/consumer-state failures. The exposed chat
credential was not used for these tests. All current checks were offline or used
isolated test profiles; microphone, camera and screen sharing were not exercised.

Continue with animation/contact polish and native transition/resource checks,
then run the exact installer upgrade in a disposable environment when the host
reserve allows. Complete live-provider tests after the owner fixes Google access.
Keep Android device work after the Windows stability gates. Normal candidate
launches share the existing MYRAA user profile; the automated tests used separate
temporary profiles. Do not treat private candidate artifacts as public clearance.
