# eraKcore — Kotlin 重写的 ERA 运行时

[EraTW Pocket（安卓移植版）](https://github.com/Poboking/eratw-pocket) 所依赖的纯 JVM ERA 引擎：把 Emuera 的 ERB 语义逐条对拍复刻成 Kotlin，官方 bug 原样保留（ADR-005）。

## 模块

| 模块 | 说明 |
|---|---|
| `core-vm` | ERA 语义虚拟机：ERB 字节码执行、变量/角色/存档、内建函数、RNG（SFMT） |
| `render` | 纯 JVM 渲染层：字形缓存、文本网格、按钮命中区、显示列表、图形层 |
| `tools/erbcc` | ERB/ERH → `erbc.v1` 字节码容器编译器 |
| `tools/vmdbg` | 面向 agent 的无交互 VM 调试 CLI（call / trace / sweep / ledger） |
| `tools/uipreview` | 桌面渲染预览：跑真 core-vm + 真 render，浏览器看画面 |
| `tools/fontsubset` | 字体内子集再生成（Python，维护 `render/src/main/resources/fonts`） |

`specs/` 是语义权威：ISA / 内建函数 / 变量 / 装载 / 存档格式 / RNG 的对拍规约，每条结论带 C# `文件:行号` 出处。

## 构建

要求 JDK 17+。

```bash
./gradlew --configure-on-demand :core-vm:test   # 定向跑受影响用例
./gradlew :tools:erbcc:installDist              # 编译器 CLI
```

## 语料说明

本仓库**不包含、也不分发**任何游戏语料（ERB 脚本、图片、存档、配置）。`ERATW_CORPUS` / `ERATW_GAMEDIR` 环境变量指向你本地的语料目录，语料依赖的测试用例在变量缺失时自动不生成。

`fixtures/golden/` 下的 `GOLDEN_*.ERB` 是本项目自写的语义对拍夹具（作者为本项目，非游戏文本）。

## 与 eratw-pocket 的关系

- **eraKcore**（本仓库）：引擎 + 编译器 + 渲染，纯 JVM，可在桌面/服务端独立运行与测试。
- **eratw-pocket**：Android 壳层（Compose 界面、资源解包、存档/音频平台绑定），构建时以 [composite build](https://docs.gradle.org/current/userguide/composite_builds.html) 方式依赖本仓库（与本仓库同级检出即可）。

## 许可

- 本仓库以 [MIT](LICENSE) 发布。
- eraKcore 对 Emuera 的语义复刻遵循其原许可条款：出处与修改声明见 [NOTICE.md](NOTICE.md)。
- 内置字体均为开源许可，见 `render/src/main/resources/fonts/LICENSE-*.txt` 与 [NOTICE.md](NOTICE.md) 的字体表。
