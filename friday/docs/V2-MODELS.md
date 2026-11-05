# Existing model compatibility inventory

The read-only discovery script parsed source PMX files and PMX entries inside ZIP
archives, then deduplicated the model bytes by SHA-256. It found **19 distinct PMX
files: 12 humanoid variants and seven props**. Evelyn is included in that count.
No animation clips were found. Every file parsed successfully. Originals remain
under `G:/MYRAA TO 3D/MYRAA 3d MODELS` and the existing project assets folder.
The complete source locations, full hashes and per-feature analysis are in the
local `artifacts/model-inventory.json`. Run `npm run audit:models` to reproduce.

## Humanoids

| Character / variant | Library ID | SHA-256 prefix | Vertices | Bones | Spring chains / colliders |
| --- | --- | --- | ---: | ---: | ---: |
| Evelyn | evelyn (built in) | 804e92823135 | 30,788 | 293 | 24 / 27 |
| Astra | astra | 63214f9f7f1d | 38,649 | 386 | 28 / 32 |
| Astra, second source variant | astra-2 | 51b6709ea4a3 | 44,479 | 398 | 29 / 32 |
| Star traveler maid outfit | azur-promilia-199001-p1 | 0b197f80cbfc | 47,518 | 477 | 51 / 24 |
| Heron Thoth, white outfit | character-d055bfba | 9104e523fcc0 | 34,030 | 695 | 15 / 27 |
| Ellen | ellen | 18c41c59994c | 29,287 | 487 | 15 / 41 |
| Jane Doe | janedoe | 869e15780356 | 27,959 | 297 | 21 / 34 |
| Richie | richie | 888a7f97210a | 69,638 | 428 | 11 / 26 |
| Caesar | v2-108159a03ad0 | 108159a03ad0 | 26,798 | 439 | 23 / 35 |
| Burnice | v2-15ab88130b4d | 15ab88130b4d | 38,619 | 366 | 17 / 34 |
| Heron Thoth, black outfit | v2-9acd3d5f01b8 | 9acd3d5f01b8 | 33,511 | 555 | 15 / 27 |
| Vivian | vivian | c6b0f02794a1 | 31,709 | 479 | 25 / 35 |

The last three absent source variants were imported into new library directories;
existing IDs and asset files were retained. The normal Windows character selector
now exposes all 12 humanoids. Android currently bundles Evelyn only and has no
user model import. Runtime test results are recorded in V2-TEST-REPORT.md.

All humanoids map the 15 essential body bones. Japanese/English/Chinese naming
normalization and model-specific rest transforms feed the existing pose/IK system.
Rig-derived physics profiles classify hair, ribbons, skirts, jackets, accessories
and other supported chains, retaining the models' authored colliders. Different
models naturally have different chain counts and facial morph sets.

## Separate props

| Source context / PMX filename | SHA-256 prefix | Vertices | Bones |
| --- | --- | ---: | ---: |
| Astra / 武器.pmx (weapon) | ddd280915918 | 4,499 | 7 |
| Astra / 补妆镜.pmx (mirror) | b789d660f0ca | 538 | 3 |
| Vivian / 伞.pmx (umbrella) | 3649d6ac9514 | 8,255 | 38 |
| Vivian / 技能.pmx (effect) | 743795baae99 | 1,832 | 5 |
| Ellen / 武器.pmx | f79c492576ec | 4,244 | 10 |
| Jane Doe / 武器.pmx | eefb741e9f94 | 2,448 | 10 |
| Caesar / 武器.pmx | 4f83ea15ca88 | 5,291 | 3 |

These are preserved and parsed, but are not offered as standalone humanoids.
They lack a complete humanoid skeleton; automatic accessory attachment and their
special effects are not implemented. Selecting them as full characters would
give misleading or broken body controls.

## Motion, physics and limits

Idle, breathing, blinking, gaze, speech motion, wave, sit/stand and desktop walking
are procedural. The new dance is an upper-body emote in place. No authored dance
clip, mocap asset or external animation pack was added. Keyframed clip import is
not implemented. Missing arm/elbow slots use a small nod instead of a broken wave.
Facial and lip controls fall back to the channels present in the rig.

Hair and supported clothing/accessory chains use spring simulation, damping,
constraints and body colliders. Desktop Medium/High additionally retain the
existing vertex-cloth path; Low and mobile use skeletal secondary motion.
There is no full fabric self-collision solver or guarantee against all clipping.
The body/IK and physics tests cover representative impulses and sitting, not every
possible user edit or collision pose.

## Rights

Author readmes and restrictions accompany local copies. Several explicitly
prohibit redistribution, commercial use or extracting parts. Missing licence text
does not grant permission. Neither the MIT application licence nor a successful
import grants model rights. Third-party model files are excluded from the clean
source export; the local Windows and Android binaries are private test builds.
