# MYRAA V2 — Windows and Android development candidate

MYRAA is a desktop AI assistant with a modular real-time 3D anime character system.

Current autonomous development status: [MYRAA-V2-PROGRESS.md](MYRAA-V2-PROGRESS.md),
[test ledger](MYRAA-V2-TEST-REPORT.md), and [release gates](MYRAA-V2-RELEASE-CHECKLIST.md).
The former 48-hour deadline is removed. See [Google key troubleshooting](docs/GOOGLE-KEY-TROUBLESHOOTING.md)
for the RC2 authentication/credential-redaction repair.

The existing Electron/React/Three.js application is preserved. Android uses the
same PMX character runtime with native lifecycle, provider networking, Keystore,
speech recognition and TTS, and operates without a Windows PC.

See [V2 build instructions](docs/V2-BUILD.md), [test and release status](docs/V2-TEST-REPORT.md),
[model inventory](docs/V2-MODELS.md), [changes](docs/V2-RELEASE-NOTES.md),
and [Android source/build](android/README.md). Release candidates are not labelled
production-ready. Third-party character assets require separate redistribution rights.

## Workspace contract

The repository is intentionally split into two top-level areas:

```text
MYRAA TO 3D/
├── myraa-ai-assistant/   Application and runtime-ready character assets
└── MYRAA 3d MODELS/     Raw source models and their original textures
```

Keep raw PMX files and original packages in `MYRAA 3d MODELS`. Original assets
are never overwritten. Additional characters are imported into the user's local
library under `%APPDATA%/MYRAA/characters` and resolved from their rig profiles.
Use `npm run audit:models` to discover every source model, including archive
variants and props; do not assume a fixed count. The V2 audit discovered 12
humanoid variants and seven distinct props across the supplied packages.

## Character controls

| Control | Action |
| --- | --- |
| `W A S D` | Smooth free camera orbit, including side and back views |
| `Q / E` | Zoom out / in |
| `L` | Lock or unlock the current view |
| `F` | Toggle eyes following the mouse |
| `R` | Reset the camera to the default front view |
| `1` | Front preset |
| `2` | Three-quarter preset |
| `3` | Side preset |
| `4` | Back preset |

The preview also exposes clickable **View lock**, **Eyes**, **Front**, **¾**, **Side**, and **Back** controls. While view lock is active, orbit, zoom, reset, and preset changes are intentionally blocked.

## Character architecture

The implementation under `src/character` is divided by responsibility:

- `core`: stage, render loop, model lifecycle, camera and system orchestration
- `loaders`: PMX parsing and staged texture resolution
- `materials`: anime shading, toon ramps, outlines and ambient occlusion
- `lighting`: body and camera-relative portrait lighting rigs
- `animation`: layered pose, idle, gaze and grant solving
- `face`: expressions, morphs, blinking and real-time lip sync
- `behaviour`: random natural actions and activity-aware direction
- `physics`: secondary motion for hair, clothing and accessories
- `config/characters`: all model-specific bones, morphs, materials and tuning

New characters should be added through configuration and staged assets rather than by hardcoding model details into the shared runtime.

## Development

Prerequisites: Node.js and npm.

```powershell
npm ci
npm run dev
```

Open the standalone tuning harness at:

`http://localhost:5178/character-preview.html`

Use these checks before shipping character changes:

```powershell
npm run lint
npm run build
```

Configure your own API key in MYRAA's AI settings to use protected storage.
Development `.env` files are ignored and must never be committed. The clean
source export omits third-party assets: set `VITE_MYRAA_PUBLIC=1` for development
without a bundled character, or use `npm run dist:public` and import your own model.

The backend also maintains an internal public-API capability registry sourced
from `public-apis/public-apis`. See [`docs/API_HUB.md`](docs/API_HUB.md) for its
cache, provider states, discovery endpoints, and code-free adapter boundary.

## Cognitive runtime

The event-driven cognitive backend extends the existing application without
changing the UI or character system. Its architecture, safety boundaries,
feature flags, storage files, APIs, tests, and phased roadmap are documented in
[`docs/COGNITIVE_ARCHITECTURE.md`](docs/COGNITIVE_ARCHITECTURE.md).

```powershell
npm run test:cognition
npm run test:api-hub
npm run test:screen-vision
npm run test:python
npm run simulate:cognition
```
