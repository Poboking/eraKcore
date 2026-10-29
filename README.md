<div align="center">

# eraKcore

**简体中文** | [English](README_EN.md) | [日本語](README_JA.md)

[![License: MIT](https://img.shields.io/badge/license-MIT-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eraKcore/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eraKcore/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![JDK](https://img.shields.io/badge/JDK-17-263146?style=flat-square)
![Platform](https://img.shields.io/badge/platform-JVM-6a737d?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**Kotlin 重写的纯 JVM ERA 引擎。**

对 Emuera 的可观察行为逐条对拍复刻，官方 bug 原样保留。
不含游戏数据，不依赖 Android——在桌面、服务端、CI 上都能独立构建与测试。

</div>

---

## 模块

| 模块 | 说明 |
|---|---|
| `core-vm` | ERA 语义虚拟机：ERB 字节码执行、变量与角色系统、存档读写、内建函数、SFMT 随机数 |
| `render` | 纯 JVM 渲染层：字形缓存、文本网格、按钮命中区、显示列表、图形层 |
| `tools/erbcc` | ERB / ERH → `erbc.v1` 字节码容器编译器 |
| `tools/vmdbg` | 面向 agent 的无交互调试 CLI：call / trace / where / var / disasm / sweep / ledger |
| `tools/uipreview` | 桌面渲染预览：驱动真实的 core-vm + render，浏览器查看画面 |
| `tools/fontsubset` | 字体子集再生成脚本（Python），维护 `render/src/main/resources/fonts` |

`specs/` 是本项目的语义权威：ISA、内建函数、变量、装载、存档格式、RNG 的对拍规约，
每条结论都带 Emuera 源码的 `文件:行号` 出处。规格与实现不一致时，以规格为准——这是我们的约定。

## 构建与测试

需要 JDK 17+。

```bash
./gradlew :core-vm:test             # 单模块测试
./gradlew :tools:erbcc:installDist  # 编译器 CLI
```

部分测试依赖真实语料或性能产物，由 `ERATW_CORPUS` / `ERATW_GAMEDIR` / `ERATW_ERBC_DIR`
环境变量门控：变量未设置时这些用例不会生成，其余用例自足。CI 上跑的就是这一档。

## 仓库关系

```
eraKcore（本仓库，MIT）        eratw-pocket（GPL-3.0）
├── core-vm   ←────────────  ├── render-canvas
├── render    ←────────────  ├── platform
└── erbcc                    └── app（Compose 壳层）
```

[eratw-pocket](https://github.com/Poboking/eratw-pocket) 以 Gradle composite build
依赖本仓库，要求与本仓同级检出。同作者的 [kojo-editor](https://github.com/Poboking/kojo-editor)
是面向口上作者的独立编辑器，与本引擎无构建依赖，但共享同一套 era 语义认知。

## 语料与版权

本仓库不分发任何 era 游戏数据（脚本、图片、音频、存档、配置）。
`fixtures/golden/` 下的 `GOLDEN_*.ERB` 是本项目自写的语义夹具，不含游戏文本。
请自备合法获得的游戏数据，用环境变量指向它。

Emuera 的出处声明与修改说明见 [NOTICE.md](NOTICE.md)。

## 状态

项目仍在活跃开发中，语义对拍尚未收口；已知问题见 issue tracker。
欢迎 issue 与 PR——语义类改动请附上与 Emuera 行为的对拍依据（`文件:行号`），规格先行。

## 许可

[MIT](LICENSE)。内置字体另有各自的开源许可，见 [NOTICE.md](NOTICE.md) 的字体表。
