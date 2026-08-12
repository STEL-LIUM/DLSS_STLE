# Contributing

Thanks for looking. This is a rendering mod, so most of what makes a change
easy to accept is about **evidence** rather than style.

## Building

```
git clone https://github.com/STEL-LIUM/DLSS_STLE.git
cd DLSS_STLE
./gradlew build
```

Requires JDK 17. The jar lands in `build/libs/`.

`libs/` is not in this repository. The Embeddium and Iris jars used as
compile-time references belong to their authors and are not redistributed here —
download them yourself and drop them in `libs/` to compile
`compat/DSEmbeddiumIntegration` and the `mixin/iris` package. Without them the
rest of the mod still builds.

### The dev workspace cannot test the shader path

`runClient` will not load Iris/Oculus or Embeddium: they ship SRG-mapped
`@Shadow` members and the dev workspace uses official mappings, and `fg.deobf`
does not bridge it. **Anything touching the shadow cache, the Iris hooks or a
shader pack must be tested in a real Forge instance** — build the jar, drop it
in `mods/`, launch normally. Nothing else is a test of that code.

Also: never overwrite the jar while the client is running. Forge loads classes
lazily, so replacing the file deletes classes the game has not read yet and it
crashes on the next one it needs.

## What a performance PR needs

A number, measured the same way twice:

- **Same world, same seed, same position, same time of day.** Standing in a
  different spot is worth more fps than most optimisations.
- **Same shader pack and version**, or none — say which.
- **Before and after**, as fps *and* frame time. Frame time is the honest one:
  254 → 460 fps sounds like +81%, and it is, but 3.94 ms → 2.17 ms is what you
  actually removed.
- Your GPU, resolution, and the preset you tested at. A gain that only exists at
  50% render scale is a different claim from one that holds at DLAA.

"It feels smoother" is not a measurement, and neither is a single F3 reading
taken once. Let it settle.

## What tends to break

Four of the five real breakages in this mod's history were **framebuffer
lifecycle** — creation, resize, attachment, and who owns the bound target — and
none were shader maths. If a bug only appears windowed, only appears
fullscreen, or only appears after an alt-tab, look at size and attachment paths
first.

Symptoms that look like a kernel bug usually aren't.

## The debugging lever

Create an empty file named `DUMP_BUFFERS` in `.minecraft`. The next frame writes
every stage of the pipeline as PNGs to `logs/buffers/`. This found essentially
every bug listed above; use it before guessing, and attach the PNGs to the
issue.

## House rules for the code

- **The Iris hooks stay in `dlssstyle.iris.mixins.json`.** That config is
  declared separately so an install with no shader mod never loads it, and an
  Iris update that moves a method costs the shadow cache rather than the game.
  Do not move an Iris mixin into the main config.
- **New behaviour that takes something over is opt-in.** Frame pacing is off by
  default for exactly this reason: frame delivery is the one thing here a player
  cannot see going wrong. Effects a player can see and judge may default on.
- **Fail soft.** Every config read has a fallback for the not-yet-loaded case,
  and every hook checks that the thing it is patching is actually there. A mod
  that crashes the game is worse than a mod that quietly does nothing.
- Match the surrounding style: 4 spaces, no wildcard imports, comments that
  explain *why* a thing is the way it is — several here record a regression, and
  those comments are load-bearing.

## Pull requests

Keep one change per PR. Say what you measured and on what. If it is a fix,
describe how to see the bug before the patch.

By contributing you agree your work is licensed under this project's
[MIT license](LICENSE).
