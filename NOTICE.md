# NOTICE — 第三方归因

## Emuera

eraKcore 是对 [Emuera](https://osdn.net/projects/emuera/)（原作者：MinorShift，2008–）所实现的 era 游戏引擎语义的 **Kotlin 独立重写**：不是衍生自其源码的移植，但语义规约（`specs/`）以其可观察行为为对拍基准，并按原样复刻其官方 bug。

Emuera 原版的许可声明（译文摘要与原文要点）：

> Copyright (C) 2008- MinorShift
>
> 本软件按「现状」提供，不附任何明示或默示的保证；因使用本软件产生的任何损害，作者概不负责。
> 在遵守以下限制的前提下，任何人可为包括商业应用在内的任意目的使用、自由修改并再分发本软件：
> 1. 不得对本软件的出处作虚假表示；不得主张你是原创软件的作者。
> 2. 若修改了源码，必须明示这一点；不得虚假表示其为原版软件。
> 3. 不得删除或修改本声明及其内容。

eraKcore 遵守上述条款：本文件即出处声明；对 Emuera 行为的复刻在代码注释与 `specs/` 中均有明示（带 `文件:行号` 出处）。若你再分发 eraKcore 的修改版，请保留本声明。

era 游戏运行时的原始概念源自 eramaker（by 佐藤敏 / サークル獏）。

## 内置字体（render/src/main/resources/fonts/）

| 字体 | 许可 | 声明文件 |
|---|---|---|
| Ark Pixel（方舟像素） | OFL 1.1 | `LICENSE-ArkPixel.txt` |
| Fusion Pixel（缝合像素） | OFL 1.1 | `LICENSE-FusionPixel.txt` |
| 美咲ゴシック（Misaki Gothic） | 其作者公布的自定义开放条款 | `LICENSE-Misaki.txt` |
| さいたまフォント（Saitamaar） | 公有领域 / mplus 开放条款（见文件） | `LICENSE-Saitamaar.txt` |
| GNU Unifont | SIL OFL 1.1 / GPL-2.0+ 双许可 | `LICENSE-Unifont.txt` |
