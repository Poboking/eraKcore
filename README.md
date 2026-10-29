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

本项目为 Android 原生移植而写。[eratw-pocket](https://github.com/Poboking/eratw-pocket)
是用它做出来的第一个 Android 原生版 EraTW（上游：画蛇添足版 4.980），在部分
低端设备上的游玩体感优于模拟器。引擎本身不包含游戏数据，在桌面、服务端和
CI 上都能独立构建与测试。

</div>

---

## 项目目标

发生目标冲突时，优先级依次为：

1. 与 Emuera 的可观察行为保持一致，结论以 `specs/` 中的对拍规格为准；
2. 平台无关：引擎不依赖 Android，纯 JVM 运行，前端只消费显示列表；
3. 工程自足：不依赖任何游戏数据即可构建、测试与复现。

这一顺序只用于裁决冲突。我们的约定是：规格与实现不一致时以规格为准；如果实现
过程中发现规格本身有误，先修规格，再改实现。任何语义改动都应先在 `specs/` 中
落到书面（附 Emuera 源码 `文件:行号` 出处），然后才动代码。

## 模块

| 模块 | 说明 |
|---|---|
| `core-vm` | ERA 语义虚拟机：ERB 字节码执行、变量与角色系统、存档读写、内建函数、SFMT 随机数 |
| `render` | 纯 JVM 渲染层：字形缓存、文本网格、按钮命中区、显示列表、图形层 |
| `tools/erbcc` | ERB / ERH → `erbc.v1` 字节码容器编译器 |
| `tools/vmdbg` | 面向 agent 的无交互调试 CLI：call / trace / where / var / disasm / sweep / ledger |
| `tools/uipreview` | 桌面渲染预览：驱动真实的 core-vm + render，浏览器查看画面 |
| `tools/fontsubset` | 字体子集再生成脚本（Python），维护 `render/src/main/resources/fonts` |

`specs/` 是本项目的语义权威：ISA、内建函数、变量、装载、存档格式、RNG 的对拍
规约，每条结论都带 Emuera 源码的 `文件:行号` 出处。

## 架构与设计取舍

**规格先行的对拍开发。** `specs/` 六份规约先于实现成文，逐条附 Emuera 反编译源码
的 `文件:行号` 证据。只看文档发现不了的细节靠对拍抓——例如引擎默认 RNG 并非
`System.Random`，而是自实现的 SFMT19937（周期 2^19937−1），已照原样复刻。

**AOT 字节码 + mmap 零解析装载。** ERB/ERH 在构建期由 erbcc 编译为 `.erbc`
容器（小端、i64 数值、字符串驻留、指令定长 4×u32、段表寻址），运行时经 mmap
直接寻址、不做任何解析——安卓端装载速度的直接来源。

**编译确定性是受测性质，不是假设。** 产物逐字节可复现（`ErbcDeterminismTest`），
另有装载序审计：重名定义文件间的相对次序若受文件系统枚举顺序影响，审计计数
非零即判失败。跨机器、跨平台的编译结果因此一致。

**纯 JVM 分层。** `core-vm` 禁 Android API、禁 `kotlin.random`、无静态可变全局
（状态挂 store 的 aux 槽），引擎在桌面与 CI 上全量可测；`render` 同为纯 JVM 的
显示列表模型，任何前端（Compose / 桌面预览）都只是它的消费者。

**版本对账在构建期完成。** erbcc 版本单源自 `core-vm` 的 `Version.kt`，下游构建
时校验字节码产物的编译器版本与源码指纹，漂移直接构建失败——「旧产物配新引擎」
的错配进不了最终产物。

**三级状态哈希回归。** 测试侧的 L3 装置只经 `VariableStore` 公开读写面观察整个
VM 状态（变量、XML、精灵池、驻留表），逐簇摘要成哈希链，与 PC 基准全量执行
逐字节比对。不改引擎一行：内部布局重构不影响口径，语义变化会如实反映到链值。

## 构建与测试

需要 JDK 17+。所有命令默认从仓库根目录执行。

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

eratw-pocket 对游戏语义零知识：脚本行为、内建函数与存档格式的权威定义全部位于
本仓库的 `core-vm` 与 `specs/`。涉及游戏行为的问题请到 [eratw-pocket](https://github.com/Poboking/eratw-pocket)
之外的本仓库提 issue；壳层改动不应改变任何游戏行为。

## 语料与版权

本仓库不分发任何 era 游戏数据（脚本、图片、音频、存档、配置）。
`fixtures/golden/` 下的 `GOLDEN_*.ERB` 是本项目自写的语义夹具，不含游戏文本。
请自备合法获得的游戏数据，用环境变量指向它。

Emuera 的出处声明与修改说明见 [NOTICE.md](NOTICE.md)。

## 状态

项目仍在活跃开发中，与 Emuera 的语义对拍尚未收口；已知问题见 issue tracker。
欢迎 issue 与 PR。涉及语义的改动请附上与 Emuera 行为的对拍依据（`文件:行号`），
并先改 `specs/` 再动实现；不确定的行为差异欢迎先开 issue 讨论。

## 致谢

本项目的实现与验证建立在 era 生态长期积累的工作之上，谨向下列作者与贡献者致谢：

- **MinorShift** 与 **妊）|дﾟ)の中の人**：Emuera 的著作者。本引擎以 Emuera 的行为
  作为兼容性参考。
- **まだ名前は無い人**：eraThe World（eraTW）项目在 `GameBase.csv` 中署名的修改／制作者。
- **eraTW 的口上与内容作者、改编者**：所有为角色口上、事件、数据与文档作出贡献的创作者。
- **所有 era 社区的脚本／内容作者与工具维护者**。

以上致谢不改变外部第三方材料的著作权、署名、许可或使用条件；使用或再分发这些
材料时，请以其随附文件为准。

## 许可

[MIT](LICENSE)。内置字体另有各自的开源许可，见 [NOTICE.md](NOTICE.md) 的字体表。
