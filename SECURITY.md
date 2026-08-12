# Security Policy

## Supported versions

| Version | Supported |
|---|---|
| 1.0.x | yes |
| older | no |

Only the latest release gets fixes. There is no long-term branch.

## What this mod actually touches

Worth knowing before you file: DLSS Style is **client-side only**. It opens no
sockets, makes no network requests, reads no files outside `.minecraft`, and is
never installed on a server. Its attack surface is what any Forge mod has —
mixins into the game's rendering classes, a config file, and one debug path that
writes PNGs.

The one thing worth scrutiny is that debug path: with `debugDump` enabled
(default), the presence of a file named `DUMP_BUFFERS` in `.minecraft` causes
the next frame's framebuffers to be written to `logs/buffers/`. It writes only
inside `.minecraft`, and it only ever writes rendered game frames. Set
`debugDump = false` in `config/dlssstyle-client.toml` to disable it outright.

## Reporting a vulnerability

**Do not open a public issue.** Report it privately:

1. Go to [Security → Report a vulnerability](https://github.com/STEL-LIUM/DLSS_STLE/security/advisories/new)
2. Describe what you found, what an attacker could do with it, and how to
   reproduce it
3. Include the mod version, Minecraft and Forge versions, and any other mods
   installed

You should get a first response within a week. If a fix is needed, you'll be
credited in the release notes unless you'd rather not be.

## Out of scope

- Crashes and rendering glitches — those are [bugs](https://github.com/STEL-LIUM/DLSS_STLE/issues/new/choose), file them publicly
- Vulnerabilities in Minecraft, Forge, Iris/Oculus, or Embeddium themselves —
  report those to their maintainers
- Anything requiring the attacker to already have write access to the victim's
  `.minecraft` folder, since at that point they can simply replace the jar
