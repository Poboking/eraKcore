<div align="center">

# eraKcore

**简体中文** | [English](README_EN.md) | [日本語](README_JA.md)

[![License: MIT](https://img.shields.io/badge/license-MIT-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eraKcore/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eraKcore/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![JDK](https://img.shields.io/badge/JDK-17-263146?style=flat-square)
![Platform](https://img.shields.io/badge/platform-JVM-6a737d?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**Kotlin 实现的纯 JVM ERA 引擎。**

eraKcore 起源于一个很朴素的需求：想在 Android 上原生地玩 EraTW，而不是套一层
模拟器。[eratw-pocket](https://github.com/Poboking/eratw-pocket) 就是这个需求的
答案——它是用本引擎做出来的第一个 Android 原生版 EraTW（上游：画蛇添足版
4.980），在部分低端设备上比模拟器跑得更顺畅。引擎本身不包含任何游戏数据，在
桌面、服务端和 CI 上都能独立构建与测试。

</div>

---

## 它是什么

- **语义层，不是前端。** eraKcore 负责游戏规则本身：ERB 字节码执行、变量与角色
  系统、存档、内建函数。画面以显示列表输出，交由各前端自行呈现。
- **兼容性看齐 Emuera。** 引擎的行为基准是 Emuera 的实际表现，所有结论都要求
  落到 `specs/` 的对拍规格上，并附 Emuera 源码的 `文件:行号` 出处。
- **不依赖 Android。** 纯 JVM 实现，在无图形环境下照常测试，这也是它能在 CI
  上持续对拍的前提。
- **自足。** 不需要任何游戏数据即可构建、跑测试、复现问题。

`specs/` 是本项目的语义权威，涵盖 ISA、内建函数、变量、装载、存档格式与 RNG。


## 模块

| 模块 | 说明 |
|---|---|
| `core-vm` | ERA 语义虚拟机：ERB 字节码执行、变量与角色系统、存档读写、内建函数、SFMT 随机数 |
| `render` | 纯 JVM 渲染层：字形缓存、文本网格、按钮命中区、显示列表、图形层 |
| `tools/erbcc` | ERB / ERH → `erbc.v1` 字节码容器编译器 |
| `tools/vmdbg` | 面向 agent 的无交互调试 CLI：call / trace / where / var / disasm / sweep / ledger |
| `tools/uipreview` | 桌面渲染预览：驱动真实的 core-vm + render，浏览器查看画面 |
| `tools/fontsubset` | 字体子集再生成脚本（Python），维护 `render/src/main/resources/fonts` |

## 构建与测试

需要 JDK 17+。命令均从仓库根目录执行。

```bash
./gradlew :core-vm:test             # 单模块测试
./gradlew :tools:erbcc:installDist  # 编译器 CLI
```

少数测试依赖真实语料或性能产物，由 `ERATW_CORPUS` / `ERATW_GAMEDIR` /
`ERATW_ERBC_DIR` 环境变量门控：变量未设置时这些用例不会生成，其余用例自足。
CI 上跑的就是这一档。

## 仓库关系

```
eraKcore（本仓库，MIT）        eratw-pocket（GPL-3.0）
├── core-vm   ←────────────  ├── render-canvas
├── render    ←────────────  ├── platform
└── erbcc                    └── app（Compose 壳层）
```

eratw-pocket 对游戏语义一无所知——这是有意为之。脚本行为、内建函数、存档格式
的权威定义全部在本仓库的 `core-vm` 与 `specs/` 里；壳层的改动也不允许改变任何
游戏行为。因此，看起来像「游戏行为不对」的问题，请提到本仓库而不是
[eratw-pocket](https://github.com/Poboking/eratw-pocket)，即使现象发生在手机上。

## 架构与设计取舍

**规格先行的对拍开发。** `specs/` 下的六份规约都成文于实现之前，逐条附有 Emuera
反编译源码的 `文件:行号` 证据。许多只看文档发现不了的细节，正是靠对拍抓出来的——
比如引擎默认 RNG 并不是 `System.Random`，而是自实现的 SFMT19937
（周期 2^19937−1），我们照原样复刻了它。项目内部有一条简单的约定：规格与实现
不一致时以规格为准；实现若证明规格有误，先改规格、再改实现，顺序不能反。

**AOT 字节码 + mmap 零解析装载。** ERB/ERH 在构建期由 erbcc 编译成 `.erbc`
容器（小端、i64 数值、字符串驻留、指令定长 4×u32、段表寻址），运行时经 mmap
直接寻址，不做任何解析。这是安卓端冷启动装载速度的直接来源。

**编译确定性是受测性质，不是假设。** 产物逐字节可复现（`ErbcDeterminismTest`），
另设装载序审计：若重名定义文件之间的相对次序受文件系统枚举顺序影响，审计计数
非零即判失败。跨机器、跨平台的编译结果因此一致。

**纯 JVM 分层。** `core-vm` 禁用 Android API 与 `kotlin.random`，没有静态可变
全局（状态挂在 store 的 aux 槽），因此在桌面和 CI 上可以全量测试；`render`
同样是纯 JVM 的显示列表模型，任何前端（Compose、桌面预览）都只是它的消费者。

**版本对账在构建期完成。** erbcc 版本单源自 `core-vm` 的 `Version.kt`。下游
构建时会校验字节码产物的编译器版本与源码指纹，发现漂移直接构建失败——「旧产物
配新引擎」的错配进不了最终产物。

**三级状态哈希回归。** 测试侧的 L3 装置只通过 `VariableStore` 的公开读写面观察
整个 VM 状态（变量、XML、精灵池、驻留表），逐簇摘要成哈希链，再与 PC 基准的
全量执行逐字节比对。整个过程不改引擎一行：内部布局重构不影响口径，语义变化
则会如实反映到链值上。

## 语料与版权

本仓库不分发任何 era 游戏数据（脚本、图片、音频、存档、配置）。
`fixtures/golden/` 下的 `GOLDEN_*.ERB` 是本项目自写的语义夹具，不含游戏文本。
请自备合法获得的游戏数据，用环境变量指向它。

Emuera 的出处声明与修改说明见 [NOTICE.md](NOTICE.md)。

## 参与贡献

项目仍在活跃开发中，与 Emuera 的语义对拍尚未收口，已知问题见 issue tracker。
欢迎各种 issue 与 PR。涉及语义的改动有一个前置要求：先在 `specs/` 里写清楚
依据（附 `文件:行号`），再动实现。如果只是怀疑某个行为有差异、自己也拿不准，
直接开 issue 讨论就好，不必先写规格。

## 致谢

这个项目能做起来，靠的是 era 社区多年积累的成果。

- **MinorShift** 与 **妊）|дﾟ)の中の人**——Emuera 的著作者。本引擎的一切
  兼容性判断都以他们的作品为参考。
- **まだ名前は無い人**——eraThe World（eraTW）在 `GameBase.csv` 中署名的
  修改／制作者；以及所有为 eraTW 撰写口上、事件、数据与文档的创作者。
- era 社区的各位脚本作者、内容作者和工具维护者。

上述致谢不改变任何第三方材料的著作权与许可条件，使用或再分发时请以随附文件为准。

## 许可

[MIT](LICENSE)。内置字体另有各自的开源许可，见 [NOTICE.md](NOTICE.md) 的字体表。

