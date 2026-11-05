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
