# DLSS Style

DLSS-style temporal rendering for **Minecraft 1.20.1 (Forge)**. Client-side only.

Most performance mods make Minecraft faster by drawing fewer pixels. This one
can do that too — but most of its gain comes from **not redoing work whose
inputs have not changed**, which pays at full native resolution as well. The
default preset is DLAA: native resolution, nothing downscaled.

Measured on an RTX 3080 Ti at 1080p with BSL Classic v8.4, **at native
resolution with no upscaling**:

| | fps | frame time |
|---|---|---|
| Without the mod | 254 | 3.94 ms |
| With the mod | 376–460 | 2.66–2.17 ms |

## Install

1. Install **Minecraft 1.20.1** with **Forge 47.x** (Java 17+).
2. Download `dlss-style-1.0.0.jar` from the
   [Releases](https://github.com/STEL-LIUM/DLSS_STLE/releases) page.
3. Drop it in your `mods` folder.

Nothing else is required. A shader mod is optional — see
[Compatibility](#compatibility).

## Controls

| | |
|---|---|
| **Ctrl+U** | cycle presets in game, with a chat confirmation |
| **Video Settings** | a *DLSS Style* button, top-right (vanilla and Embeddium screens) |

## Presets

| Preset | Render scale | What it is |
|---|---|---|
| **DLAA** *(default)* | 100% | The temporal pass as pure anti-aliasing. No quality sacrificed. |
| **Quality** | 67% | DLSS's own ratio. |
| **Balanced** | 58% | |
| **Performance** | 50% | |
| **Dynamic** | moves | Holds a framerate target by itself. |
| **Off** | 100% | Nothing runs. |

Percentages are per axis, the way DLSS quotes them.

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

## Configuration

Everything has a tuned default; the preset is the only control most people
need. The rest lives in `config/dlssstyle-client.toml`.

| Option | Default | |
|---|---|---|
| `preset` | `DLAA` | The preset, as above. Also set by Ctrl+U and the button. |
| `sharpness` | `0.35` | Edge crispness added back after upscaling. Too high looks gritty. |
| `dynamicTargetFps` | `0` | The fps the Dynamic preset holds. `0` = your fps cap, or the monitor's refresh rate when uncapped. |
| `beatSync` | `false` | **Opt-in.** Releases frames on a steady grid at an even divisor of your refresh rate. Aimed at stutter on G-Sync / FreeSync panels. Stands down under vsync, in menus and when unfocused. Off by default: frame delivery is the one thing here you cannot see going wrong, so the mod does not take it over uninvited. |
| `jitter` | `false` | **Experimental.** Sub-pixel camera jitter — more real detail, but it may shimmer. |
| `menuBoost` | `false` | **Experimental.** Halves the scale while a menu is open. Each open/close currently costs a framebuffer resize. |
| `debugDump` | `true` | Lets a `DUMP_BUFFERS` marker file write the pipeline's buffers as PNGs to `logs/buffers/`. |

## Compatibility

- Minecraft 1.20.1, Forge 47.x, Java 17+, **client-side only** — it does not
  need to be on the server, and it does not need to be on other players' clients
- Embeddium / Rubidium / Sodium forks — supported
- Iris / Oculus / Mekalus — supported; shader packs get the full treatment
- Fabulous graphics — the mod stands down automatically
- Shadow and sky caching require an Iris-family mod; upscaling and DLAA do not

The Iris hooks live in their own optional mixin config, so an install without a
shader mod never loads them, and a future Iris that moves those methods costs
the shadow cache rather than the game.

## Troubleshooting

**Shadows look a frame behind.** The cache refreshes at a bounded rate. If you
can see it on your pack, report it with the pack name and version.

**No fps change with a shader pack.** Check that Iris/Oculus loaded — without
one, only the upscaling and DLAA paths run.

**Something looks wrong and you want to show me.** Create an empty file named
`DUMP_BUFFERS` in your `.minecraft` folder; the next frame writes the pipeline's
buffers as PNGs to `logs/buffers/`. Attach those to the issue.

## Building

    ./gradlew build

`libs/` is excluded from this repository: the Embeddium and Iris jars used as
compile-time references belong to their authors and are not redistributed here.
Drop them in yourself to build the integration classes.

## Contributing

Bug reports, performance reports and feature ideas all have
[templates](https://github.com/STEL-LIUM/DLSS_STLE/issues/new/choose) that ask
for what actually gets a report solved. [CONTRIBUTING.md](CONTRIBUTING.md)
covers building, what a performance PR needs to include, and the two things in
this codebase that break most often. Security issues go
[here](SECURITY.md), privately. Everyone is expected to follow the
[Code of Conduct](CODE_OF_CONDUCT.md).

## License

[MIT](LICENSE).
