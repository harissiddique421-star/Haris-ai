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
missing services or languages produce a clear error. The microphone and screen
capture run in the background only during a call or wake listener you turned on
(see below).

The WebView serves only bundled assets at a fixed HTTPS origin; file/content URL
access, external pages, cookies, iframes and WebView permission requests are
disabled. Android app settings opens only this app's settings, after a button tap.

## Live call and screen sharing in other apps (rc.48)

A Live call you start keeps running while you use other apps. It runs in one
foreground service (`LiveVoiceService`, types microphone, mediaPlayback and,
while you share, mediaProjection) with an ongoing notification: Pause or Resume
listening, Stop sharing and Stop. The "Wake up FRIDAY" listener
(`WakeWordService`, microphone type) is a separate light service and pauses
while a call runs, so only one of them uses the microphone at a time.

Why sharing used to stop after you left FRIDAY:

1. Gemini Live ends a session after about 2 minutes once images are sent
   (15 minutes for voice only) unless context compression is on. The call then
   reconnected, but the reconnect also stopped the screen share, and Android 14+
   does not allow the same consent to be used twice. Now the call asks for
   sliding-window compression (with a fallback if Google refuses it), and a
   reconnect keeps the running share.
2. The page that runs the call is a separate WebView process, which Android may
   close as soon as FRIDAY is not visible, for example when YouTube needs memory.
   While a call runs, that process now keeps the call's importance.
3. On Android 14+ Android's prompt also offered "share one app". Sharing only
   FRIDAY shows FRIDAY nothing once you open another app, so FRIDAY now asks for
   the whole screen.
4. The notification kept only microphone types when it was updated during a
   share. It now keeps mediaProjection for as long as the share runs.

What Android still decides: locking the phone, tapping Stop in the status bar
or cast panel, or closing FRIDAY from Recents ends the share. FRIDAY then says
so and you tap Share screen again. Android shows its consent prompt every time,
and FRIDAY never starts a share or a call by itself. Some phone makers (Xiaomi,
Oppo, Vivo, Samsung "deep sleep") stop background apps more aggressively. Allow
FRIDAY to run unrestricted in battery settings for the best results.

## Unlock by voice (rc.49)

"FRIDAY, unlock my phone and open YouTube" (also WhatsApp, Chrome, Maps,
Instagram, Gmail, Spotify, and Hindi/Urdu forms such as "phone unlock karo aur
WhatsApp kholo"). Voice never unlocks the phone:

1. The wake listener hears the whole sentence (on a locked phone only with
   "Also listen when the phone is locked" on) and queues one task (`UnlockTask`).
2. FRIDAY turns the screen on and asks Android for its own lock screen
   (`requestDismissKeyguard`). You unlock with fingerprint, face, PIN or pattern.
3. Only when Android reports success and the keyguard is really gone does FRIDAY
   open the app. A plain "unlock my phone" starts a normal Live call.

The task waits at most one minute and runs once. A cancelled or failed unlock
drops it. FRIDAY never asks for, sees, logs or stores your PIN or pattern, never
captures the lock screen and never enters anything on it. Turn it off in
Settings > Assistant > Wake word. Before rc.49 the wake on a locked phone showed
FRIDAY over the lock screen but never asked for the unlock; that is fixed too.

## WhatsApp voice notes (rc.55)

WhatsApp keeps voice notes in its own storage and encrypts them, so FRIDAY never opens them by itself.

- **Notification only:** with Notification access FRIDAY sees "🎤 Voice message (0:12)" and knows who sent a voice note and how long it is. She does not know what was said, and says so.
- **Share to FRIDAY:** in WhatsApp long-press the voice note, tap Share, choose "FRIDAY: explain voice note". The audio is sent once to Gemini with your own key. You get the language, the transcript (Urdu/Hindi/English/Roman Urdu/Hinglish, `[unclear]` where it is not clear), a Roman Urdu summary, questions, tasks, times, urgency and a suggested reply.
- The audio is not stored. Only the text result is kept in the app's private storage (last 20). The same audio shared twice is recognised by its SHA-256 and not sent again.
- Ask by voice: "Ali ke voice note mein kya kaha?", "koi voice note aaya?", "voice notes delete karo". Replies go through the normal WhatsApp send with one confirmation.
- Delete all results: Settings > WhatsApp voice notes.
- Checked on the JVM (`VoiceNotesCheck`): detection, parsing, storage, duplicates, delete. Not yet measured on a phone: transcription accuracy, time and cost.

## WhatsApp: one confirmation, no repeat loops, faster send check (rc.54)

Audit of the existing WhatsApp flow (kept as it is: `MobileActions` resolves the WhatsApp contact from the
rows WhatsApp syncs into Contacts, returns one `needs_confirmation` with a token, and `PhoneController`
presses Send once the chat shows exactly the approved text, then checks the box was cleared).

Root causes of the repeated questions and the slowness:
1. The Live prompt said "for risky actions still read back and confirm as before" and its multi-step example
   was "open WhatsApp, draft a message to Ali": Gemini often asked its own question, sometimes opened WhatsApp
   first, then asked again when the phone returned `needs_confirmation`. Each extra question is a full voice
   round trip.
2. When Gemini repeated the request after the user's yes (without the token), the phone made a new token and
   a new question.
3. The send check always waited a fixed 900 ms.

Fixes:
- New ONE CONFIRMATION rule in the Live prompt and the tool description: call the action at once with the
  recipient and the final words, read the phone's question back once, and after a yes call again with only
  the token, without asking about internal steps. The multi-step example no longer uses WhatsApp.
- `askConfirmation` and WhatsApp calls reuse the open token for exactly the same action, recipient and text,
  and tell Gemini to go ahead if the user already said yes; a changed recipient or text still gets a new
  question.
- The same message to the same person within 2 minutes is flagged in the question ("already sent N s ago").
- The send check polls every 150 ms for up to 2 s. The result says "sent from this phone"; delivered and read
  ticks are not claimed.

Unchanged: contact matching and the "which one?" question for several matches, draft fallback when
accessibility is off, the token expiry, and the shared single Send job (both accessibility switches use one
job, so Send is pressed once).

## Voice intelligence: low-confidence repeat, mic quality and last-call summary (rc.53)

Audit of the existing pipeline against the "next-generation voice intelligence" request. Already in place
before rc.53: on-phone speaker verification before Gemini hears anything (Silero VAD + GE2E voiceprint,
calibrated threshold, encrypted profile, 12 enrollment sentences in several styles plus room-echo copies,
outlier takes dropped, "Add more samples"), WebRTC echo cancellation / noise suppression / AGC / voice
isolation on the call microphone (the same chain registration uses since rc.51), an early voice check during
the end-of-turn silence, overlap ("unclear") detection, a turn watchdog and the self-development metrics.

Root cause of "FRIDAY just keeps listening": a refused voice was replaced by silence with no feedback, unless
the owner had already been accepted once in the call. rc.53:

- **Low confidence** (`VoiceGate.LOW` = 0.08): a voice rejected within 0.08 of the threshold is reported as
  `low`. Nothing is let through and nothing runs; FRIDAY asks once to repeat closer to the phone (at most every
  10 s, at most 3 times per call, since a near miss that repeats is more likely someone else).
- Every refusal shows a short note with the match and what was needed (at most every 8 s).
- **Mic quality** per call: background level (slow floor tracker, dBFS) and clipping (% of samples at full scale).
- **Last call summary** kept on the phone (counts, closest refused match, needed match, background level,
  clipping, check time; no audio or words), shown under Voice ID in Settings with a suggestion (Add more
  samples / Relaxed when it was close, Re-register when it was far, move the phone away when clipping).
- New JVM check: the owner's sentence just under the threshold is `low` with nothing let through; far under it
  is `unknown`. The rc.50 scenarios still pass (owner at simulated 1-3 m with room echo 18/18 accepted, other
  voices 0/60 on the recorded test set). These are simulated distances, not measurements on the user's phone.

Not done, on purpose: swapping GE2E for ECAPA-TDNN (no on-device test set from this user to show it is better),
RNNoise on top of WebRTC noise suppression (double suppression hurts voiceprints), and replay detection (no
validated on-device method; Voice ID is never used alone for security or payments).

## Diagnostics that survive a closed app, and a share that cannot close FRIDAY (rc.52)

A user reported that the Live call and screen share stopped as soon as they left FRIDAY. The diagnostics
showed FRIDAY had started again, but the in-memory log of the call was gone with the old process.

- The control log is also kept in a small private file (`control-log.txt`, at most 80 lines, same
  content rules: no message text, no numbers). After a restart the previous run's lines show in
  Settings > Advanced > Diagnostics marked `[last run]`, and diagnostics now shows the last 50 lines.
- A crash writes `CRASH: <error type> at <class.method:line>` to that log before the app closes.
- On Android 11+, each start logs why Android says the last run ended (crash, low memory, ANR,
  swiped away, ...), from `ApplicationExitInfo`.
- The call service no longer lets a refused foreground start (for example screen capture) close the
  app: the share is stopped with an explanation and the call keeps running. A screen frame that runs
  out of memory is skipped instead of closing FRIDAY.

## Voice ID in real calls: same microphone for registration and calls (rc.51)

Before rc.51, Voice ID registered and tested your voice through FRIDAY's own Android microphone
(AudioRecord, VOICE_RECOGNITION, platform noise suppressor), while the Live call hears you through the
WebView's microphone (WebRTC echo cancellation, noise suppression, gain control and voice isolation, then
an 85 Hz high-pass). The two chains colour a voice differently, so a profile that matched well in the
Settings test could score below the threshold in a call. The gate then sent silence and FRIDAY stayed on
"listening".

From rc.51, registration, "add samples" and the Settings test record through the very same chain as the
call (`LIVE_MIC_CONSTRAINTS` and `LIVE_MIC_HIGHPASS_HZ` in `src/lib/audio.ts`). The page streams 16 kHz
PCM to the app (`CallMicPcm`), which segments and embeds it on the phone exactly as before; no audio
leaves the phone and nothing raw is stored. **Re-register after installing rc.51**: a profile learned
through the old microphone path still carries its colouring.

Other people's voices still keep FRIDAY listening; that is by design, they are ignored.

## Voice ID 2.0: far-field and enrollment (rc.50)

Audit of why Voice ID missed the owner (all on the phone, Live call and wake word):

1. **Enrollment at one distance.** Registration used six sentences, all said
   close to the phone. A voice from across a room has room echo, so its
   voiceprint drifts. Measured on real recorded speech (LibriSpeech owner, with
   simulated distance and room echo): before, the owner was accepted close 6/6,
   at about 1 m 4/6, at 2 m 0/6 and at 3 m 0/6.
2. **Loudness gate.** After the owner had spoken once close to the phone, any
   speech more than 9 dB quieter was never even checked, so the same owner from
   across the room was silently ignored ("keeps listening, does nothing").

Changes:

- Registration is twelve sentences: close, arm's length, phone on a table one
  or two metres away, quietly, and with a little noise. Each one is also learned
  as heard across a room (`SpeakerProfile.roomEcho`, computed on the phone). A
  take that does not match the rest (someone else, a cough) is left out instead
  of failing the whole registration. **Add more samples** records four more
  (far, quiet, noisy) without starting over.
- The gate checks any speech up to 20 dB quieter than the owner by voiceprint;
  loudness still separates talkers inside a turn, and the turn watchdog stays.
- Measured with the same tests after the change: owner accepted close 6/6, 1 m
  6/6, 2 m 6/6, 3 m 6/6; other voices (a friend, TV speakers) accepted 0 of 60
  at all distances; a short command's turn still ends 0.54 s after the owner
  stops (0.60 s with people talking behind).

These are simulated distances on a small set of recorded voices, not phone
measurements. On a real phone, use **Test my voice** at different distances and
**Test someone else**; Settings shows the counts. Older profiles keep working;
re-register once to be able to add samples. The profile (voiceprints only, no
audio) stays encrypted with the Android Keystore. Voice ID is never used alone
for payments, unlocking or other sensitive actions.

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

## Development agent (phase 2)

`.github/workflows/friday-dev-agent.yml` runs Google's free Gemini CLI on GitHub for approved ideas.

- **One-time setup (owner):** create a free key at https://aistudio.google.com/apikey and add it as the repository secret `GEMINI_API_KEY` (Settings > Secrets and variables > Actions), and create the label `friday-self-improvement` (Issues > Labels). A separate key from the one inside FRIDAY is best, so the agent never uses up the phone's free quota. The agent uses `gemini-3.5-flash-lite`, because the CLI's default model allows only 20 free requests a day and one task needs roughly 30 to 80. The free tier still has daily limits (see https://ai.dev/rate-limit); if they run out, the run says so and you can comment `@gemini` the next day.
- **Start:** approve an idea in Settings > Self-development, tap **Send to developer**, and press Submit on GitHub. Only issues opened by the repository owner with that label start the agent; the owner can also comment `@gemini` on such an issue to start it again (it continues on the same branch).
- **What it does:** Gemini reads the issue and code and makes the change on the runner, with no GitHub credentials. The workflow then refuses the change if it touches `.github/`, activation, signing or release scripts, or deletes tests; runs the typecheck, mobile tests, JVM checks and APK build itself; pushes a `friday-dev/issue-N` branch; and comments with each check's result and a link to open the PR. The test APK (signed with a throwaway key) is kept as a workflow artifact.
- **What it never does:** edit `.github/`, weaken signing, activation or key handling, skip tests, merge, or publish. The owner reviews and merges the PR, then runs **Publish FRIDAY update** so phones can install it.
