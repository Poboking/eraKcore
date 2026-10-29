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

This project exists for a native Android port: [eratw-pocket](https://github.com/Poboking/eratw-pocket)
is the first native-Android EraTW client built on it (upstream: the EraTW fork
画蛇添足版 4.980), which plays more smoothly than the PC emulator on some low-end
devices. The engine ships no game data and builds and runs standalone on desktop,
server, and CI.

</div>

---

## Project Goals

When goals conflict, they are prioritized as follows:

1. Consistency with Emuera's observable behavior, as determined by the differential
   specs in `specs/`;
2. Platform independence: the engine does not depend on Android, runs on the plain
   JVM, and frontends only consume its display list;
3. Self-sufficiency: the project builds, tests, and reproduces without any game data.

This ordering exists only to settle conflicts. Our convention is that when the specs
and the implementation disagree, the specs win; if the specs themselves turn out to
be wrong, fix the specs first, then the implementation. Every semantic change should
land in `specs/` in writing first (with the Emuera source citation `file:line`),
before any code is touched.

## Modules

| Module | Description |
|---|---|
| `core-vm` | ERA semantic VM: ERB bytecode execution, variable & character systems, save I/O, built-in functions, SFMT RNG |
| `render` | Pure-JVM rendering: glyph cache, text grid, button hit regions, display list, graphics layers |
| `tools/erbcc` | ERB / ERH → `erbc.v1` bytecode container compiler |
| `tools/vmdbg` | Non-interactive, agent-oriented debug CLI: call / trace / where / var / disasm / sweep / ledger |
| `tools/uipreview` | Desktop render preview: drives the real core-vm + render, viewable in a browser |
| `tools/fontsubset` | Font subsetting scripts (Python) that maintain `render/src/main/resources/fonts` |

`specs/` is the semantic authority of this project: differential specs for the ISA,
built-ins, variables, loading, save format, and RNG — every statement cites its
Emuera source location (`file:line`).

## Architecture & Design Decisions

**Spec-first differential development.** The six specs in `specs/` were written before
the implementation, each conclusion backed by `file:line` citations from the decompiled
Emuera sources. Differential reading catches what documentation alone cannot — for
instance, the engine's default RNG is not `System.Random` but a hand-rolled SFMT19937
(period 2^19937−1), which we reproduced as-is.

**AOT bytecode + mmap zero-parse loading.** ERB/ERH sources are compiled at build time
by erbcc into `.erbc` containers (little-endian, i64 values, string interning, fixed
4×u32 instructions, section table). At runtime they are memory-mapped and executed with
no parsing at all — the direct source of fast cold loads on Android.

**Deterministic compilation is tested, not assumed.** Output is byte-for-byte
reproducible (`ErbcDeterminismTest`), plus a load-order audit: if the relative order of
duplicate definitions shifts with filesystem enumeration order, the audit counter turns
non-zero and fails. Builds match across machines and platforms.

**Pure-JVM layering.** `core-vm` bans Android APIs, `kotlin.random`, and static mutable
globals (state lives in the store's aux slots), so the engine is fully testable on
desktop and CI. `render` is likewise a pure-JVM display-list model; any front end
(Compose, desktop preview) is merely a consumer.

**Version reconciliation at build time.** erbcc's single source of truth is `Version.kt`
in `core-vm`; downstream builds verify the bytecode artifact's compiler version and
source fingerprint, and any drift fails the build — stale artifact + new engine never
ships.

**Three-level state-hash regression.** The test-side L3 harness observes the entire VM
state (variables, XML, sprite pools, intern table) strictly through `VariableStore`'s
public read surface, digesting it cluster by cluster into a hash chain compared
byte-for-byte against PC golden runs. It touches no engine code: internal layout
refactors leave the baseline untouched, semantic changes show up in the chain.

## Build & Test

Requires JDK 17+. All commands run from the repository root by default.

```bash
./gradlew :core-vm:test             # per-module tests
./gradlew :tools:erbcc:installDist  # compiler CLI
```

Some tests require real game corpora or profiling artifacts and are gated by the
`ERATW_CORPUS` / `ERATW_GAMEDIR` / `ERATW_ERBC_DIR` environment variables: with the
variables unset, those cases are simply not generated; everything else is
self-contained. CI runs exactly that tier.

## Repository Layout

```
eraKcore (this repo, MIT)      eratw-pocket (GPL-3.0)
├── core-vm   ←────────────    ├── render-canvas
├── render    ←────────────    ├── platform
└── erbcc                      └── app (Compose shell)
```

eratw-pocket has zero knowledge of game semantics: the authoritative definitions of
script behavior, built-in functions, and save formats all live in this repo's
`core-vm` and `specs/`. Game-behavior issues belong here rather than in
[eratw-pocket](https://github.com/Poboking/eratw-pocket); shell changes must not
alter any game behavior.

## Corpus & Copyright

This repository distributes **no** era game data (scripts, images, audio, saves,
configs). The `GOLDEN_*.ERB` files under `fixtures/golden/` are fixtures written by
this project and contain no game text. Bring your own legally obtained game data
and point the environment variables at it.

See [NOTICE.md](NOTICE.md) for attribution and modification statements regarding Emuera.

## Status

Under active development; the differential audit against Emuera is not yet complete,
and known issues live in the issue tracker. Issues and PRs are welcome. For semantic
changes, please cite the differential evidence against Emuera behavior (`file:line`)
and update `specs/` before touching the implementation; when in doubt about a behavior
difference, an issue first is always fine.

## Acknowledgments

This project builds on work the era community has accumulated over many years. Our
thanks go to:

- **MinorShift** and **妊）|дﾟ)の中の人**, the authors of Emuera, whose behavior this
  engine takes as its compatibility reference;
- **まだ名前は無い人**, the modifier/creator credited in eraThe World (eraTW)'s
  `GameBase.csv`;
- the writers and porters behind eraTW's character dialogues, events, data, and docs;
- every script/content author and tool maintainer in the era community.

These acknowledgments do not alter the copyright, attribution, license, or terms of
use of any third-party material; when using or redistributing such material, defer
to the files that accompany it.

## License

[MIT](LICENSE). Bundled fonts carry their own open-source licenses — see the font
table in [NOTICE.md](NOTICE.md).
