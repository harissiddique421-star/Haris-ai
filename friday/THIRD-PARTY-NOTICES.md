# Third-party software and assets

MYRAA application source is provided under the MIT licence. Dependencies retain
their own licences and notices. The package lock records exact dependency
versions; installed package metadata contains their licence declarations.

Core runtime dependencies include Electron (MIT), React (MIT), Three.js (MIT),
Express (MIT), ws (MIT), dotenv (BSD-2-Clause), Google's GenAI SDK (Apache-2.0),
Motion (MIT), Lucide (ISC), Koffi (MIT) and mmd-parser (MIT). Build dependencies
include TypeScript (Apache-2.0), Vite (MIT), esbuild (MIT), Tailwind CSS (MIT),
and electron-builder (MIT). Validate the installed declarations and any
transitive notices before a public release; this list is not a legal clearance.

The Python desktop agent bundles third-party software whose own notices must
remain in its PyInstaller distribution. It includes Windows UI automation and
screen-capture libraries. No Blender integration is used.

## Character assets are excluded from the application source licence

Existing characters and textures belong to their respective creators. The local
character library preserves author readmes, source restrictions and original
model bytes. Some author notices explicitly prohibit redistribution. Missing
or unrecognized licence text does not grant redistribution rights.

The personal Windows installer and private Android APK include the existing
Evelyn model **for the project owner's private local testing only**. They must
not be publicly shared without the necessary rights. Imported models remain
under `%APPDATA%/MYRAA/characters` and are not included in Windows installers.

`npm run dist:public` excludes bundled character files and supports local model
import on first run. No public distribution has been authorized or performed.
An Android public variant with model import is not implemented.

Android SDK tools, emulator and system images are external build/test tools
under Google's SDK agreements, not MYRAA runtime source. Android platform code
and system services retain their original licences. Debug signing keys are local
development artifacts and are excluded from source exports.

The owner's existing application artwork is also excluded from the source
export because redistribution rights have not been verified. Private local
builds preserve it. Source-only builds generate a neutral letter M icon when
artwork is absent; this does not create or substitute a character model.
