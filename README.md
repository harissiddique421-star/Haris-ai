# Haris AI — Native Android Starter

Kotlin + Jetpack Compose starter with a dark assistant UI and a first set of native Android actions.

## Included in this revision
- Android speech recognition launched when the user taps the microphone (not always-on).
- Android TextToSpeech spoken responses (voice quality depends on voices installed on the phone).
- Contact lookup by name after the user grants Contacts permission.
- Voice/text command pattern: `call Ali` opens the Android dialer with the matched number.
- Voice/text command pattern: `WhatsApp Ali message Assalam o alaikum` opens a WhatsApp draft via `wa.me`.
- Confirmation card before opening the dialer or WhatsApp.
- Optional WhatsApp UI auto-send prototype; when enabled, a WhatsApp command skips the in-app confirmation and attempts a single Send tap only after the expected message text is visible.
- GitHub Actions workflow to attempt a debug APK build.

## Not included / not verified
- No connected cloud AI model; chat fallback responses are local demo responses.
- No always-listening wake phrase. Android background microphone access requires a separate foreground-service design and testing.
- No automatic WhatsApp sending, direct WhatsApp call control, or guaranteed contact matching for every naming style.
- No screen/camera vision, unrestricted phone control, or multi-agent backends yet.
- This project has not been built on this environment. The APK is **not yet available** until the GitHub Actions build succeeds.

## Build using an Android phone
1. Download and extract this ZIP on your phone.
2. In GitHub, create a repository named `HarisAI`.
3. Upload the extracted files and folders, preserving their paths.
4. Open the repository's **Actions** tab and enable Actions if prompted.
5. Run **Build Haris AI APK** (or push to `main`).
6. If the workflow succeeds, download `HarisAI-debug-apk` from that run's Artifacts section.
7. Extract `app-debug.apk`, install it, and grant microphone/contacts permissions only if you want those features.

## Important
- This is a debug/testing APK, not a Play Store release.
- Do not commit API keys or private credentials to a public repository.
- If the build fails, share the first meaningful error from the Actions log so it can be fixed accurately.


## Optional WhatsApp auto-send prototype (not device-tested)
- The app now includes an opt-in toggle and an AccessibilityService prototype. With the toggle enabled, a WhatsApp command opens a prefilled `wa.me` draft without asking for a second in-app confirmation, then attempts one Send tap only when the exact expected message text is visible.
- Both the in-app toggle and the Android Accessibility service must be enabled. Auto-send is armed only by a WhatsApp action launched from Haris AI and disarms after a successful click. Use the in-app “Auto-Send setup” shortcut to open Android Accessibility settings.
- This is best-effort UI automation, not a WhatsApp API integration. WhatsApp layouts, language, dialogs, or updates may prevent it from working or may cause the wrong UI control to be tapped. Test only with a trusted contact and harmless message first. Disable it if it behaves unexpectedly.
- The implementation has not been built or tested on a physical phone in this environment. It does not guarantee successful sending. The always-on wake phrase remains unimplemented.
