# Private RC5 / Android RC3 UI update

Desktop: compact chat dock, inline voice button, collapsed character controls.
Settings focus uses preventScroll so the sliding dialog cannot scroll the stage.
Real desktop smoke: 19 checks including four repeated Settings open/close cycles.

Mobile: character fills available space; full-body framing defaults on, short
recent-message strip and explicit expandable History. Talk button has microphone
icon and starts a foreground voice conversation: Android speech recognition,
automatic chat submission, Android TTS reply, then next listening turn. Stop,
recognizer cancellation and provider/speech errors end the voice loop.
This uses Android platform speech services, not Gemini Live streaming audio.
An installed speech recognition service, TTS voice and active Gemini project key
are required. No background microphone listening is implemented.

Mobile bundled UI smoke: 12 checks with simulated native speech/chat, no live
Google or recorded audio. APK signature/build verified; real emulator screenshot
is separate from the simulated UI evidence. Prior RC4 files remain unchanged.

RC5 packaging: original compression failed with insufficient memory; low-memory store build succeeded at release/layout-rc5/MYRAA-Portable-2.0.0-rc.5.exe. Portable archive verification 7/7 passed. Packaged worker digest/source bundle checks passed, but authenticated worker readiness failed twice; do not report packaged runtime smoke as passing. Temporary test candidate processes stopped. Emulator exited twice before updated APK installation; no installed-avatar or live audio success claimed. APK RC3 signed, bundled mobile UI checks 12/12, desktop real-window checks 19/19. Live Google still requires active project credentials.
