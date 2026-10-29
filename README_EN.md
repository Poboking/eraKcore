<div align="center">

# eraKcore

[简体中文](README.md) | **English** | [日本語](README_JA.md)

[![License: MIT](https://img.shields.io/badge/license-MIT-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eraKcore/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eraKcore/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![JDK](https://img.shields.io/badge/JDK-17-263146?style=flat-square)
![Platform](https://img.shields.io/badge/platform-JVM-6a737d?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**A pure-JVM ERA engine, implemented in Kotlin.**

eraKcore started from a plain wish: to play EraTW natively on Android instead of
wrapping an emulator. [eratw-pocket](https://github.com/Poboking/eratw-pocket) is
the answer to that wish — the first native-Android EraTW client built on this
engine (upstream: the EraTW fork 画蛇添足版 4.980), and it runs more smoothly than
the emulator on some low-end devices. The engine itself ships no game data and
builds and runs standalone on desktop, server, and CI.

</div>

---

## What it is

- **A semantics layer, not a frontend.** eraKcore owns the game rules: ERB
  bytecode execution, the variable and character systems, saves, built-in
  functions. Rendering is emitted as a display list for frontends to present.
- **Emuera is the behavioral baseline.** Every conclusion must land in the
  differential specs under `specs/`, citing the Emuera source (`file:line`).
- **No Android dependency.** It is plain JVM and is tested in headless
  environments — which is what makes continuous differential auditing on CI
  possible at all.
- **Self-sufficient.** Building, running tests, and reproducing issues require
  no game data whatsoever.

`specs/` is the semantic authority of this project, covering the ISA, built-ins,
variables, loading, save format, and RNG.


## Modules

| Module | Description |
|---|---|
| `core-vm` | ERA semantic VM: ERB bytecode execution, variable & character systems, save I/O, built-in functions, SFMT RNG |
| `render` | Pure-JVM rendering: glyph cache, text grid, button hit regions, display list, graphics layers |
| `tools/erbcc` | ERB / ERH → `erbc.v1` bytecode container compiler |
| `tools/vmdbg` | Non-interactive, agent-oriented debug CLI: call / trace / where / var / disasm / sweep / ledger |
| `tools/uipreview` | Desktop render preview: drives the real core-vm + render, viewable in a browser |
| `tools/fontsubset` | Font subsetting scripts (Python) that maintain `render/src/main/resources/fonts` |

## Build & Test

Requires JDK 17+. Run all commands from the repository root.

```bash
./gradlew :core-vm:test             # per-module tests
./gradlew :tools:erbcc:installDist  # compiler CLI
```

A few tests depend on real corpora or profiling artifacts and are gated by the
`ERATW_CORPUS` / `ERATW_GAMEDIR` / `ERATW_ERBC_DIR` environment variables: with
the variables unset, those cases are simply not generated; everything else is
self-contained. CI runs exactly that tier.

## Repository Layout

```
eraKcore (this repo, MIT)      eratw-pocket (GPL-3.0)
├── core-vm   ←────────────    ├── render-canvas
├── render    ←────────────    ├── platform
└── erbcc                      └── app (Compose shell)
```

eratw-pocket knows nothing about game semantics — on purpose. The authoritative
definitions of script behavior, built-in functions, and save formats all live in
this repo's `core-vm` and `specs/`, and shell changes are not allowed to alter
game behavior. So if something "plays wrong", please file it here rather than on
[eratw-pocket](https://github.com/Poboking/eratw-pocket), even when it happens on
a phone.

## Architecture & Design Decisions

**Spec-first differential development.** The six specs under `specs/` were all
written before the implementation, each conclusion backed by `file:line`
citations from the decompiled Emuera sources. Differential reading catches what
documentation alone cannot — for instance, the engine's default RNG is not
`System.Random` but a hand-rolled SFMT19937 (period 2^19937−1), reproduced
as-is. One simple rule governs the project: when the specs and the
implementation disagree, the specs win; and if the implementation proves a spec
wrong, fix the spec first and the implementation second — never the other way
around.

**AOT bytecode + mmap zero-parse loading.** ERB/ERH sources are compiled at
build time by erbcc into `.erbc` containers (little-endian, i64 values, string
interning, fixed 4×u32 instructions, section table). At runtime they are
memory-mapped and executed with no parsing at all — the direct source of fast
cold loads on Android.

**Deterministic compilation is tested, not assumed.** Output is byte-for-byte
reproducible (`ErbcDeterminismTest`), backed by a load-order audit: if the
relative order of duplicate definitions ever follows filesystem enumeration
order, a non-zero audit counter fails the build. Results therefore match across
machines and platforms.

**Pure-JVM layering.** `core-vm` bans Android APIs and `kotlin.random` and has
no static mutable globals (state lives in the store's aux slots), so the engine
is fully testable on desktop and CI. `render` is likewise a pure-JVM
display-list model; any front end (Compose, desktop preview) is merely a
consumer.

**Version reconciliation at build time.** erbcc's version has a single source
of truth in `Version.kt` under `core-vm`. Downstream builds verify the bytecode
artifact's compiler version and source fingerprint, and any drift fails the
build — stale artifacts never ship alongside a newer engine.

**Three-tier state-hash regression.** The L3 harness observes the entire VM
state (variables, XML, sprite pools, intern tables) exclusively through
`VariableStore`'s public read/write surface, summarizes it cluster by cluster
into a hash chain, and compares it byte-for-byte against a full PC-baseline
run. It never touches a line of engine code: internal layout refactors leave
the metric untouched, while semantic changes land faithfully in the chain
values.

## Corpus & Copyright

This repository distributes **no** era game data (scripts, images, audio, saves,
configs). The `GOLDEN_*.ERB` files under `fixtures/golden/` are fixtures written
by this project and contain no game text. Bring your own legally obtained game
data and point the environment variables at it.

See [NOTICE.md](NOTICE.md) for attribution and modification statements regarding
Emuera.

## Contributing

Development is ongoing and the differential audit against Emuera is not yet
complete; known issues live in the issue tracker. Issues and PRs of all kinds are
welcome. Semantic changes carry one prerequisite: write down the evidence in
`specs/` first (with `file:line` citations), then touch the implementation. If
you merely suspect a behavior difference and are not sure, just open an issue —
no need to draft a spec first.

## Acknowledgments

This project stands on work the era community has accumulated over the years.

- **MinorShift** and **妊）|дﾟ)の中の人**, authors of Emuera — every compatibility
  judgment in this engine defers to their work.
- **まだ名前は無い人**, the modifier/creator credited in eraThe World (eraTW)'s
  `GameBase.csv`, along with everyone who has written eraTW dialogues, events,
  data, and documentation.
- The era community's script authors, content creators, and tool maintainers.

These acknowledgments do not alter the copyright or license terms of any
third-party material; when using or redistributing such material, defer to the
files that accompany it.

## License

[MIT](LICENSE). Bundled fonts carry their own open-source licenses — see the
font table in [NOTICE.md](NOTICE.md).

