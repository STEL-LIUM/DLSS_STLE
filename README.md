# DLSS Style

DLSS-style temporal rendering for Minecraft 1.20.1 (Forge).

Most performance mods make Minecraft faster by drawing fewer pixels. This one
does that too — but most of its gain comes from **not redoing work whose inputs
have not changed**, which pays at full native resolution as well.

Measured on an RTX 3080 Ti at 1080p with BSL Classic v8.4, **at native
resolution with no upscaling**:

| | fps | frame time |
|---|---|---|
| Without the mod | 254 | 3.94 ms |
| With the mod | 376–460 | 2.66–2.17 ms |

## What it does

**Shadow memoisation.** A shader pack redraws the sun's shadow map from scratch
every frame, but that map depends on only three things: the sun's angle, the
shadow camera's position, and whether any blocks changed. Minecraft's sun moves
about 0.005° per frame and blocks change only when someone places or breaks
one — so the identical picture is redrawn dozens of times a second. This reuses
it, refreshing at a bounded rate so nothing visibly lags, and skips the pass
outright when you are underground or the window is not focused.

**Sky and atmosphere caching.** The same idea applied to a pack's prepare
stage, which builds atmospheric-scattering tables that depend on the sun alone —
not the camera. Those survive movement entirely.

**Temporal upscaling and DLAA.** The world renders at a chosen fraction of the
display resolution and is reconstructed with sub-pixel jitter, depth-based
camera reprojection, Lanczos-2 resampling and YCoCg variance clipping. Under a
shader pack the reduced resolution applies to the pack's own gbuffer, deferred
and composite passes, which is where the frames come from on a heavy setup.

Presets follow DLSS's own ratios: **DLAA** (native), **Quality** (67%),
**Balanced** (58%), **Performance** (50%), and **Dynamic**, which moves the
scale to hold a framerate.

## Usage

`Ctrl+U` cycles presets in game. Settings also appear in Video Settings,
including Embeddium's options screen.

## Compatibility

- Minecraft 1.20.1, Forge 47.x, Java 17+, **client-side only**
- Embeddium / Rubidium / Sodium forks — supported
- Iris / Oculus / Mekalus — supported; shader packs get the full treatment
- Fabulous graphics — the mod stands down automatically
- Shadow and sky caching require an Iris-family mod; upscaling and DLAA do not

The Iris hooks live in their own optional mixin config, so an install without a
shader mod never loads them.

## Building

    ./gradlew build

`libs/` is excluded from this repository: the Embeddium and Iris jars used as
compile-time references belong to their authors and are not redistributed here.
Drop them in yourself to build the integration classes.

## License

All rights reserved.
