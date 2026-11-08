# MYRAA Mobile V1

Standalone Android application: native Java handles encrypted provider credentials,
HTTPS requests, local conversation history, Android speech recognition, TTS and
lifecycle. Bundled HTML/TypeScript runs the same Three.js/PMX character engine as
desktop, without a PC server or remote viewer. Chat requires the user's own Gemini
key and an available text model. No credentials are bundled.

Android 8.0/API 26 or later, Android System WebView 100 or later, and OpenGL ES 3
are required. Target API 35. Missing/outdated WebView shows a native recovery
message. Mobile presets cap
rendering at 30 fps and disable shadow maps and vertex clothing; spring-bone hair
and accessory simulation remains active. Native TTS provides speech timing;
mouth motion is procedural, not phoneme-accurate.

## Build (Windows)

Install Node dependencies with `npm ci`. Install JDK 21 and Android SDK platform
35/build-tools 35.0.0. No Android Studio, Gradle, Kotlin, NDK or Blender is needed.
From the repository root:

```powershell
pwsh -NoProfile -File scripts/build-android.ps1 -SdkRoot 'C:\path\to\android-sdk' -JavaRoot 'C:\path\to\jdk-21'
```

The script invokes Vite, javac, D8, AAPT2, zipalign and apksigner, checking every
exit code. Output: `artifacts/android/MYRAA-Mobile-1.0.0-rc.1-private.apk`.
Local debug signing material stays in ignored `android/local-debug.keystore`.
This is a development signature, not a public release identity.

## Per-phone activation

Every phone needs its own activation key before FRIDAY opens. On first launch the
app shows only an activation screen with this phone's **Device ID** (Android's
per-app device ID, which stays the same after reinstalling). A key is the owner's
ECDSA P-256 signature over that Device ID: it works on that one phone only, and
only the owner's private key can make one. The APK contains just the public key.

One-time setup (owner, on a PC with Node):

```powershell
node scripts/friday-activation.mjs init
```

This saves the **private key** to `%USERPROFILE%\.friday-activation\owner-private.pem`
(back it up; never commit or share it) and writes the public key to
`android/activation-public-key.txt`. Commit that public file; the build scripts
refuse to build without it. Then rebuild the APK.

Make a key for a phone (the person sends you the Device ID from its screen):

```powershell
node scripts/friday-activation.mjs key A1B2-C3D4-E5F6-0718 --name "Ammi ka phone"
```

Send the printed key back; they paste it and tap Activate. Issued keys are
logged in `.friday-activation\issued-keys.txt`.

From a phone instead of a PC: add the contents of `owner-private.pem` as the
repository secret `FRIDAY_ACTIVATION_PRIVATE_KEY`, then run the **FRIDAY
activation key** workflow from GitHub Actions with the Device ID. The key appears
in the run summary (safe: it works only on that phone).

If the private key is lost, no new phones can be activated: run `init --force`,
commit the new public key, rebuild, and give every phone a new key. Instrumented
tests launch `MainActivity`, so activate the test phone first.

## Installation and privacy

```powershell
adb install -r artifacts/android/MYRAA-Mobile-1.0.0-rc.1-private.apk
adb shell am start -n com.myraa.mobile/.MainActivity
```

Open Settings in the app to enter a Gemini key and model available to your Google
project. The key is AES-GCM encrypted with Android Keystore. It is never returned
to JavaScript. Chat sends the message and up to 20 recent turns directly to Google.
The shared personality is bundled; this app does not sync Windows memories.

Voice input opens an installed Android speech recognition activity. Review its
transcript and tap Send. The speech service manages microphone consent and may
use its own network service. TTS uses an installed engine and selected language;
missing services or languages produce a clear error. There is no background
microphone, accessibility service, screen capture or filesystem permission.

The WebView serves only bundled assets at a fixed HTTPS origin; file/content URL
access, external pages, cookies, iframes and WebView permission requests are
disabled. Android app settings opens only this app's settings, after a button tap.

## Asset licensing

**The local APK includes the existing Evelyn model for private testing only. Do
not redistribute it.** The model's author restrictions are preserved in the
source assets. A publicly distributable variant needs user model import or
explicit redistribution rights; neither a source-code licence nor this build
script grants model rights.

## Validation status

Compilation/signing, startup, actual rendering, permissions, lifecycle and voice
checks must be recorded in `../docs/V2-TEST-REPORT.md`. Successful compilation
alone does not establish device compatibility or a production-ready release.

## Self-development (rc.45)

Settings > Self-development shows FRIDAY's own health and improvement ideas. Everything stays on the phone.

- **Measurements:** daily counters for text replies, failures by reason, reply time, Live errors and reconnects, voice reply time, unclear voice, agent failures and crashes. Numbers only; no words, contacts or audio. Kept 28 days.
- **Self-analysis:** the last 7 days against the 7 before. Clear problems become findings with the measured evidence, a suggestion and an approval level (1 FRIDAY alone, 2 developer build, 3 owner). Say "self report" or "apna analysis karo" to hear it.
- **Self-healing (text chat):** a timeout or Google outage is retried once; quota or "model unavailable" switches to a backup text model (`SelfImprovement.TEXT_MODELS`) and the reply says which model answered. Key problems stop at once.
- **Development memory:** bugs (crashes are recorded by type and place only), fixes, versions and notes. A bug with no repeat for 7 days is marked "quiet", never "fixed".
- **Approvals:** ideas are proposed, then approved or dismissed by the owner. "Send to developer" opens a prefilled GitHub issue that the owner submits. FRIDAY never installs or replaces itself.
- **Ask Gemini for ideas:** sends only the numbers, findings and memory titles to Gemini for suggestions. Nothing changes from it.

Logic: `SelfImprovement.java` (checked by `SelfImprovementCheck` in `scripts/test-voice-models.sh`); storage and Android hooks: `SelfDevelopment.java`.

## Updates and rollback (rc.46)

- **Publishing (owner only):** GitHub > Actions > **Publish FRIDAY update** > Run workflow. It runs the tests, builds `main`, refuses to publish without the `FRIDAY_KEYSTORE_BASE64` secret or when the version tag already exists, and publishes release `friday-v<versionName>` with the APK, its SHA-256, the signing certificate and a list of changes (`scripts/publish-friday-release.sh`). Raise `versionCode`/`versionName` in the manifest before each publish.
- **On the phone:** Settings > Self-development > Updates. FRIDAY checks GitHub at most once a day (can be turned off) and only looks. Download and check verifies the published SHA-256, package name, newer version and that the APK is signed with the same key as the installed FRIDAY; a file that fails is deleted. Install first saves the installed APK and a data backup to `Downloads/FRIDAY` (Android 10+), then Android's own installer asks you to confirm. App data is kept.
- **Rollback:** Android doesn't install an older version over a newer one, so: uninstall FRIDAY, install the saved `FRIDAY-<version>.apk` from `Downloads/FRIDAY`, activate it again, and restore the saved backup in Settings > Advanced. The API key and chats are not in the backup.
- Logic: `UpdatePlan.java` (checked by `UpdatePlanCheck`); Android side: `Updater.java`.
