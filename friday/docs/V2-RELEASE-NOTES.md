# MYRAA PC 2.0.0-rc.1 / Mobile 1.0.0-rc.1

Private development candidates built from the existing MYRAA application. See
V2-TEST-REPORT.md for executed checks and release blockers. Neither candidate is
labelled production ready. No public upload or store submission was performed.

## Windows

- App launches now require consent by default, including browser/file launches
  and the legacy voice tool route. Existing implicit launch grants migrate to
  Ask with a policy backup. Explicit persistent approvals remain supported.
- Session grants are scoped to the actual target. Denial overrides stale grants;
  sensitive permanent deletes cannot reuse session consent. Cancellation cleans
  up pending confirmations. Device permissions and HTTP/WebSocket origins are
  checked instead of being granted to arbitrary local pages.
- Typed chat has an independent text-model route when Live voice fails, a local
  conversation history, cancellation and installed offline read-aloud support.
  Voice errors distinguish authentication, quota and network failures; configured
  fallback attempts are bounded. Provider access still requires a valid key.
- Waving bends the elbow before raising the arm, gestures crossfade, and explicit
  happy waves are no longer cancelled by automatic emotion changes. A restrained
  dance in place is available from Character Studio and local commands.
- Model switching releases textures, skeleton buffers and WebGL contexts. GPU
  shader failures become actionable errors with bounded recovery. Settings rebuild
  the avatar while retaining the chat interface. Physics respects preset limits
  and uses hysteresis when adapting its update rate.
- FPS, frame interval, CPU submission time, draw calls and GPU resource counts
  are exposed in diagnostics. Low/Medium/High and existing Potato/Auto modes
  retain animation, with different rendering and physics budgets.
- Offline English, Hindi and Hinglish character commands cover wave, sit, stand,
  dance, cursor tracking and model cycling. Desktop side placement walks toward
  its destination before sitting. Companion click-through and always-on-top
  preferences are applied.
- File opening now dispatches the exact requested path rather than falling
  through into Explorer file selection. A real native-tool test verifies both
  denied and one-time approved opening. The companion's idle-gesture toggle now
  persists across frames so random gestures stay suppressed during walking.
- The model audit found 12 distinct humanoid variants and seven separate props.
  Three missing humanoids were added to the owner's local library without
  overwriting existing characters or original source assets.

## Android

A standalone native Android Activity bundles the existing Three.js/PMX renderer.
It needs no Windows server. Native HTTPS chat talks directly to Gemini; local
history, Keystore-protected credentials, settings, delegated speech input and
installed TTS are integrated. Transcripts are reviewed before sending.

The homepage includes Evelyn, basic emotes, sit/stand, portrait/full-body views,
chat, history, Stop and privacy controls. Mobile presets cap at 30 fps, limit
textures and disable vertex cloth/shadows while retaining spring motion. Rendering
and speech stop in the background. Only the Internet permission is requested;
the speech service manages its own microphone consent. Device settings is the
supported explicit Android action; unrestricted device control is not provided.

## Dependencies and source

Compatible dependency patches were installed and locked, including Electron
43.8.6. The production npm audit has zero findings at the recorded check; the
development toolchain still has 15 moderate findings. Build-only Vite plugins
were moved out of runtime dependencies. No new model, Blender integration or
rendering framework was introduced.

Application source carries an MIT licence and third-party notices. Model assets,
credentials, user data, Android signing keys and compiled agent files are excluded
from the clean source export. Private binaries contain third-party Evelyn assets
and are not cleared for redistribution. See V2-MODELS.md and V2-BUILD.md.
