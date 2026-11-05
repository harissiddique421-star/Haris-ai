# Android RC5 — voice app actions

Adds Gemini Live `mobile_action` function calls wired through the shared PC audio
engine to bounded native Android actions. Includes local typed command parsing,
so common opening/search/draft commands also work without a cloud connection.

Supported actions:
- Open WhatsApp (including WP/WA aliases), YouTube, Chrome and installed launcher
  apps by name. Missing apps are reported rather than claimed as opened.
- Search YouTube using its app or a browser fallback.
- Search the web in Chrome or the default browser.
- Resolve a named contact locally, choose among ambiguous matches, and open the
  correct WhatsApp draft with the exact requested text. Full international phone
  numbers need no Contacts permission. The owner explicitly chose to tap Send
  in WhatsApp; no automatic sending or accessibility service is implemented.

An explicitly started Live call uses a microphone/media foreground service and
an ongoing Stop notification. The call can continue when another app opens.
For further voice app switching from behind another app, enable Settings >
Enable floating voice controls and allow display over other apps. A small
microphone/Stop control is visible only during a Live call. This permission is
required by Android for that background app-launch workflow; without it, return
to MYRAA or tap the ongoing notification before issuing another app launch.
Contacts permission is requested only when resolving a named recipient. Contact
lookup/selection runs locally; the selected name and action result can be sent
back to Gemini. No contacts list, phone screen or files are uploaded by actions.

Examples:
- Open WhatsApp.
- Open YouTube and search for Hindi songs.
- Chrome search for weather in Delhi.
- Message Rahul on WhatsApp: I'll be ten minutes late.

Verification:
- TypeScript and signed Android build passed.
- 4 local command tests, including negation and exact message punctuation.
- 25 bundled shared-audio/UI checks, including Live tool request/results,
  duplicates, unsupported tools, background-call state and notification Stop.
- 39 real Android auth/audio checks, including actual microphone PCM continuing
  while another APK is foreground, native audio playback, notification Stop and
  byte-for-byte unchanged owner preferences. Google token/socket responses were
  simulated; no successful live Google conversation is claimed by these tests.
- 18 native action checks using fixture apps/contacts, including a real production
  native call which brought the installed Chrome app to the foreground. No real
  WhatsApp message was sent. WhatsApp is absent in this emulator; YouTube and
  Chrome are installed. Real WhatsApp draft behavior still needs phone testing.
- The first native-actions run hit a harness observation timeout at the native
  permission gate. The harness was corrected to observe native bridge replies,
  and the complete action run passed. First-run evidence was retained.

References:
https://faq.whatsapp.com/5913398998672934
https://developer.android.com/about/versions/14/changes/fgs-types-required
https://developer.android.com/training/package-visibility/declaring
