# Reproduce MYRAA candidates

Prerequisites: Windows x64, Node.js/npm, and the dependency lock. For Android,
JDK 21 and Android SDK platform 35/build-tools 35.0.0 are also required. Existing
source models remain outside the code export. No Blender, NDK or framework
migration is required.

Source exports also omit the owner's artwork because redistribution rights are
unverified. Windows/Android build scripts generate a plain M icon if artwork is
missing. Existing artwork is never overwritten. Android builds still require
the owner's permitted Evelyn assets at `assets/characters/evelyn`; the source
archive does not contain those files. Use the public Windows target for a build
that expects user model import.

## Desktop

```powershell
npm ci
node node_modules/electron/install.js
npm test
npm run dist
```

The explicit Electron installer downloads the package's pinned binary and checks
its packaged checksums; it is also usable where npm lifecycle scripts are disabled.
The local build produces:

- `release/MYRAA-Setup-2.0.0-rc.1.exe`
- `release/MYRAA-Portable-2.0.0-rc.1.exe`
- `release/win-unpacked/MYRAA.exe` (launcher; runtime alongside it)

The final local handoff is in `release/final/`, including its clean
`win-unpacked/MYRAA.exe` snapshot. Final NSIS/portable packaging used
`ELECTRON_BUILDER_COMPRESSION_LEVEL=4` and a copy of `electron-builder.yml` whose
output directory is `release/final`. This changes compression cost, not
application features. The ordinary `npm run dist` uses the default compression.

These personal builds contain Evelyn and are for private local testing. They are
not signed with a trusted release certificate. `npm run dist:public` omits the
bundled character and supports local import; public publication remains subject
to the owner's approval and rights review.

The packaged Python agent comes from `agent_dist/myraa-agent`. Rebuild changed
worker code using the installed Python/PyInstaller (no dependency download):

```powershell
npm run build:agent
$env:MYRAA_QA_AGENT_MODE='frozen'
node --import tsx scripts/qa-desktop-session.ts
node --import tsx scripts/qa-desktop-actions.ts
```

The build writes a protocol-2 executable digest stamp. Packaging refuses an old
or mismatched frozen worker. The backend creates a fresh authenticated session
on a free loopback port; do not start a separate agent on port 8765.
User data remains under
`%APPDATA%/MYRAA`; reinstalling does not require deleting it. `MYRAA_DATA_DIR`
selects an isolated profile for tests, and `MYRAA_CHARACTERS_DIR` can point at a
separate model library. Secrets use the existing Windows DPAPI store.

## Android

```powershell
pwsh -NoProfile -File scripts/build-android.ps1 -SdkRoot 'C:\Android\sdk' -JavaRoot 'C:\Java\jdk-21'
pwsh -NoProfile -File scripts/build-android-tests.ps1 -SdkRoot 'C:\Android\sdk' -JavaRoot 'C:\Java\jdk-21'
adb install -r artifacts/android/MYRAA-Mobile-1.0.0-rc.1-private.apk
adb install -r artifacts/android/MYRAA-Mobile-tests.apk
adb shell am instrument -w com.myraa.mobile.tests/com.myraa.mobile.tests.SmokeInstrumentation
```

The SDK tools compile Java, DEX and resources, normalize Windows ZIP paths,
align and sign the APK, then verify its signature. Local test signing material
is ignored by Git and is never part of the source export. The main APK contains
no API key; each user configures their own. The test APK uses an obviously invalid
key to exercise provider failures and clears it afterwards.

An Android public build with user model import is not implemented. Do not share
the private APK's third-party assets publicly.

## Renderer and real desktop verification

```powershell
node --import tsx scripts/qa-server.ts
# In another terminal, from this repository:
npx electron tests/renderer-smoke.cjs
npx electron tests/renderer-benchmark.cjs
npx electron tests/windows-app-smoke.cjs
node --import tsx scripts/qa-desktop-actions.ts
```

The QA server binds only `127.0.0.1:5189`, disables HMR, reads actual profiles and
assets, and makes no AI requests. Renderer tests execute all discovered local
humanoids plus Evelyn, exercising the real WebGL/IK/physics systems. Benchmark
results describe an offscreen GPU compositor, not hardware guarantees.

The application smoke test launches the real Electron entry/backend with a fresh
profile, verifies permission denial, crashes its own renderer, and checks history
survives recovery. It copies only the encrypted provider configuration for an
optional live test. Set `MYRAA_SMOKE_LIVE=1` only when intentionally testing the
configured provider. It never prints a secret. Test profiles are in the OS temp
directory; normal settings and memory remain untouched.

The desktop-actions script starts the frozen agent on port 18766 and operates
only on its own temporary note and isolated policy. Set `MYRAA_QA_OPEN_NOTE=1`
to include one-time consent, opening the note in its default app, verification
of that uniquely named window, and closing it. It briefly changes desktop focus.

For installer testing, create a disposable Windows Sandbox with the release
folder mapped read-only to `C:\MYRAAInput` and a dedicated test-output folder
mapped to `C:\MYRAAOutput`. Copy `scripts/sandbox-install-test.ps1` into that
output folder and execute it in the sandbox's logged-in user session. It installs
the candidate, checks startup/policy, uninstalls it and writes
`C:\MYRAAOutput\installer-result.json`. Never run this harness against the owner's
host installation. The final source report records the actually executed run.

The development toolchain installed for this sprint is under
`../MYRAA-V2-checkpoints/android-sdk`; `G:/MYRAA_V2_SDK` is a junction to avoid
emulator issues with spaces in SDK paths. The dedicated emulator is `MYRAA_V2_QA`.
