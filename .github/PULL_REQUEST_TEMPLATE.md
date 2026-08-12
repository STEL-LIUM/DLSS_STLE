# What this changes

<!-- One paragraph. If it fixes an issue, write "Fixes #123". -->

## How it was tested

<!--
Say where it ran. The dev workspace cannot load Iris/Oculus or Embeddium
(SRG @Shadow vs official mappings), so anything touching the shadow cache, the
Iris hooks or a shader pack has to be tested in a real Forge instance to count.
-->

- [ ] Built and launched in a real Forge instance, not just `runClient`
- [ ] Tested **windowed and fullscreen** — several past breakages showed up in only one
- [ ] Tested with a shader pack, and without one
- [ ] Tested at DLAA and at a reduced preset

## Numbers, if this is a performance change

<!-- Same world, same seed, same spot, same time of day, same pack. -->

| | fps | frame time |
|---|---|---|
| Before | | |
| After | | |

GPU / resolution / shader pack:

## Anything reviewers should look at closely

<!--
If you touched framebuffer creation, resize, or attachment, say so here.
Four of five real breakages in this codebase were framebuffer lifecycle;
none were shader maths.
-->

---

By submitting this PR you agree your contribution is licensed under the
project's [MIT license](../LICENSE).
