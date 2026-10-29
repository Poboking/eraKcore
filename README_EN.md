<div align="center">

# eraKcore

[简体中文](README.md) | **English** | [日本語](README_JA.md)

[![License: MIT](https://img.shields.io/badge/license-MIT-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eraKcore/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eraKcore/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![JDK](https://img.shields.io/badge/JDK-17-263146?style=flat-square)
![Platform](https://img.shields.io/badge/platform-JVM-6a737d?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**A pure-JVM ERA engine, rewritten in Kotlin.**

A faithful, line-by-line behavioral reimplementation of Emuera.
No game data, no Android dependency: builds and runs standalone on desktop, server, and CI.

</div>

---

## Modules

| Module | Description |
|---|---|
| `core-vm` | ERA semantic VM: ERB bytecode execution, variable & character systems, save I/O, built-in functions, SFMT RNG |
| `render` | Pure-JVM rendering: glyph cache, text grid, button hit regions, display list, graphics layers |
| `tools/erbcc` | ERB / ERH → `erbc.v1` bytecode container compiler |
| `tools/vmdbg` | Non-interactive, agent-oriented debug CLI: call / trace / where / var / disasm / sweep / ledger |
| `tools/uipreview` | Desktop render preview: drives the real core-vm + render, viewable in a browser |
| `tools/fontsubset` | Font subsetting scripts (Python) that maintain `render/src/main/resources/fonts` |

`specs/` is the semantic authority of this project: differential specs for the ISA, built-ins,
variables, loading, save format, and RNG — every statement cites its Emuera source location
(`file:line`). When spec and implementation disagree, the spec wins. That is the rule.

## Build & Test

Requires JDK 17+.

```bash
./gradlew :core-vm:test             # per-module tests
./gradlew :tools:erbcc:installDist  # compiler CLI
```

Some tests require real game corpora or profiling artifacts and are gated by the
`ERATW_CORPUS` / `ERATW_GAMEDIR` / `ERATW_ERBC_DIR` environment variables: with the variables
unset, those cases are simply not generated; everything else is self-contained. CI runs exactly that tier.

## Repository Layout

```
eraKcore (this repo, MIT)      eratw-pocket (GPL-3.0)
├── core-vm   ←────────────    ├── render-canvas
├── render    ←────────────    ├── platform
└── erbcc                      └── app (Compose shell)
```

[eratw-pocket](https://github.com/Poboking/eratw-pocket) consumes this repo via a Gradle
composite build and expects a sibling checkout. [kojo-editor](https://github.com/Poboking/kojo-editor),
by the same author, is a standalone editor for era script writers — no build dependency on this
engine, but a shared understanding of era semantics.

## Corpus & Copyright

This repository distributes **no** era game data (scripts, images, audio, saves, configs).
The `GOLDEN_*.ERB` files under `fixtures/golden/` are fixtures written by this project and
contain no game text. Bring your own legally obtained game data and point the environment
variables at it.

See [NOTICE.md](NOTICE.md) for attribution and modification statements regarding Emuera.

## Status

Under active development; the differential audit against Emuera is not yet complete.
Known issues live in the issue tracker. Issues and PRs are welcome — for semantic changes,
please cite the differential evidence against Emuera behavior (`file:line`). Spec first.

## License

[MIT](LICENSE). Bundled fonts carry their own open-source licenses — see the font table in
[NOTICE.md](NOTICE.md).
