# tools/fontsubset —— 语料码点闭包与字体子集规格（M4-FONT / t-font）

三个脚本 + 四份产出。**本目录不含任何字体二进制**：本机既没装 `fonttools`，也没有
Sarasa Mono SC 原件，此时生成字体就是伪造（契约 M4-B §2 t-font 明令禁止）。

| 文件 | 作用 |
|---|---|
| `scan_codepoints.py` | 只读扫游戏目录，产出确定性码点闭包（M4 的唯一语义输入） |
| `probe_metrics.py` | 纯标准库 sfnt 探针：复核参照字体的 advance 比例与覆盖率 |
| `build_subset.py` | 需要 `fonttools` 的子集生成器（本波只交付脚本，未执行） |
| `required-codepoints.txt` | **交付主产物**：8076 个码点，逐行 `U+XXXX`，升序 |
| `scan-report.txt` | 作用域/编码统计、剔除项、区段命中、基准交叉验证 |
| `scan-manifest.txt` | 逐文件（相对路径、字节数、编码判定、码点数），审计用 |
| `probe-report.txt` | simhei/Saitamaar/msgothic 的度量与覆盖实测 |
| `missing-in-simhei.txt` | PC 参照字体自身没有的 625 个码点（缺字回退的真实规模） |

## 1 复现命令

```bash
# 全闸由组长跑；本路只跑 python（工作目录即本目录）
python scan_codepoints.py --selfcheck \
  --golden "D:/DSH/eratw-安卓移植计划/eratw-android/fixtures/golden/baseline/pc-golden-root.txt"
python probe_metrics.py            # 需要本机存在 C:/Windows/Fonts/simhei.ttf
# 子集生成（等有 fonttools + Sarasa 原件的机器再做）
pip install fonttools==4.54.1
python build_subset.py --font /path/SarasaMonoSC-Regular.ttf --family "Eratw Mono SC" --verify
```

游戏目录解析顺序：位置参数 > `ERATW_GAMEDIR` > `D:/DSH/eratw-安卓移植版…` 内置默认值。
**脚本只以 rb 打开语料，并显式拒绝把输出目录设进游戏目录**（`assert_outside_game_dir`）。

## 2 闭包口径（决定清单对不对，先看这段）

* 作用域 = `ERB/` + `CSV/` 下的 `.erb .erh .erd .csv .als`（扩展名集合照抄
  `Preload.cs:59`），共 **4193** 个文件。`README集`/`resources`/更新记录里的 446 个
  额外码点**不入闭包**——引擎不加载这些目录（验收报告一·4：只加载 `ERB/`）。
* 解码 = 逐文件自动探测，**不是** `Config.Encode`（那个只用于写配置/日志，
  `ConfigData.cs:429,646`）。复刻 `EncodingHandler.cs:15-40` 的三分支：
  BOM → UTF-8；否则严格 UTF-8；否则 CP932。实测 **4138 带 BOM / 55 无 BOM 但严格
  UTF-8 可解 / 0 个走 CP932**，所以 CP932 表外字的口径分歧在本语料上不会发生。
  真的落到该分支时脚本 `errors = "strict"` 直接失败，不猜。
* 行终止符与不可渲染字符照 `.NET File.ReadAllLines`（`EraStreamReader.cs:52`）的语义剔除，
  共 6 个：`U+0007 U+0009 U+200B U+200D U+202C U+206A`。
  `U+0009` 单独说明：它在测量前就被换成 8 个空格（`StringMeasure.cs:59-61`），不需要字形。
* 结果：**观察 8082 → 请求 8076**；黄金基准 `pc-golden-root.txt` 的 312 个码点
  **全部被闭包覆盖（缺失 0）**，这条是闭包「够用」的实证。
* 排序键只有码点整数，输出固定 LF，不写时间戳/绝对路径/Unicode 名称，
  `--selfcheck` 已验证两次运行逐字节相同（`required-codepoints.txt`
  sha256 `de4573ac…943eae`，重构前后同一哈希）。

## 3 子集规格

区段命中（完整表见 `scan-report.txt`）：CJK 基本 6775、日文假名 174、点字 175、
半角假名/半角符号 63、全角 ASCII 93、框线 54、符号杂项 39、一般标点 28、西里尔 51、
希腊 35、组合附加符号 89、罗马数字 13、块元素 9、箭头 9、**CJK 扩展 A 9**、
**辅助平面 33 个**（emoji 29 + 音乐符号 2 + `U+26E99`/`U+299C3`）、**私用区 9 个**。
易漏的是最后四组加扩展 A：整段下载体时容易被「只取 CJK 基本区」的默认策略削掉。

方案：**单族两重**（Regular + Bold），同一 `required-codepoints.txt` 各出一版；
样式位里的斜体/删除线/下划线（`Kernel.kt:85-88` 的 2/4/8）**不靠字体斜体文件**，
由渲染层自绘（Sarasa 无斜体原件，硬找斜体反而易破坏 1:2 宽度）。
族名固定 `Eratw Mono SC`——它是 `:render` 默认字体名的唯一合法值，两边必须逐字一致。

宽度口径（这是 M4 出口条件的命门）：`Grid.isFullWidth` 认定「ASCII + `U+FF61..FF9F` 为
1 格，其余一律 2 格」，闭包里 1 格 158 个 / 2 格 7918 个。用 `probe_metrics.py` 实测
本包 `emuera.config`（`FONT NAME:黑体`）指向的 `simhei.ttf`：unitsPerEm=256，
ASCII advance=128，而 CJK/假名/全角标点/框线（`U+2500`、`U+2503`）/块元素/箭头/罗马数字/
希腊/西里尔/注音**全部 256**；24 组取样里有字形的 19 组**全部严格 1:2，0 组偏离**，
另 5 组是 `simhei.ttf` 自己缺字（见第 4 节）。
结论：Grid 的 1:2 模型与 PC 实际绘制字体在取样范围内**严格一致**，可作截图对齐的依据；
东亚马宽（Ambiguous）字符在 SimHei 里确实是全角，所以那 412 个 Ambiguous 必须出成 2 格。
两个已知的口径裂缝，都要 `TODO(verify)` 到真机：

1. `U+00A5`：Grid 判 2 格，但 SimHei **没有这个字形**，msgothic 给的 advance=128（1 格）。
2. `Saitamaar.ttf` 是**比例字体**（advance 空格 400 / 数字 640 / 拉丁 800 / CJK 1280），
   整数网格对它完全不成立；语料里 14 处 `SETFONT "Saitamaar"` 做字符画，
   这条路径必须由 m-glyph 的「像素→半角格」换算兜底，不能套 Grid。

## 4 缺字回退链（与 `:render` 的 glyph 路对齐）

原版链条与证据：请求名空 → `Config.FontName`（`FontFactory.cs:13-17`）；
请求名不在私有字体集 → GDI 通用替换（`FontFactory.cs:33-48`）；
启动时配置的字体整体缺失 → 落到 DLL 内置的 `BIZ UDGothic-Regular.ttf`
（`Config.cs:502-524`，资源名取处 `Config.cs:509`，回退常量 `Config.cs:24`，`UsingFallbackFont` 判定 `Config.cs:35`）；
最终单个码点无字形 → 由 GDI 画该字体的 `.notdef`（方框），**不抛异常**。

安卓侧落地规则（m-glyph 可直接照抄）：

```
resolveFont(requested):  requested ∈ {"", "Eratw Mono SC"} -> DEFAULT("Eratw Mono SC")
                         requested ∈ 已登记集合(如 "Saitamaar") -> 该族
                         其它任意微软/日文字体名                -> DEFAULT      # 永不抛
glyphFor(font, cp):      font 命中 -> 画；未命中 -> DEFAULT 命中 -> 画；
                         DEFAULT 也未命中 -> 画子集 .notdef 方框（advance 按 Grid 2 格）
```

规模参考：`simhei.ttf` 自己对闭包就缺 **625/8076（7.74%）**（见 `missing-in-simhei.txt`，
含半角假名 `U+FF61/FF71`、`U+00A5`、`U+2600` 类杂项符号、点字），PC 上这些是靠
GDI 字体链接借别的字体画出来的，安卓没有链接机制 → 只能方框，属**已知截图差异**。
因此子集必须 `notdef_outline = True`（`build_subset.py` 已设），否则回退末端没有可画的框。

## 5 授权与来源（口径沿用验收报告一·5）

* `msgothic.ttc` / `msmincho.ttc`（随包在 `font/`）：微软字体，**不可再分发**，
  也**不能当子集母体**——`build_subset.py` 的 `guard_source` 直接拒绝这类文件名。
  附带发现：引擎只把 `font/` 里的 `*.ttf`/`*.otf` 注册成私有字体（`Program.cs:131-140`），
  这俩 `.ttc` 在 PC 上**从未被加载**。
* Sarasa Mono SC：SIL OFL，可子集可再分发，须随包附 OFL 全文与版权声明。
* `Saitamaar.ttf`：随包存在且被语料 `SETFONT` 用到 14 处，但**包内没有许可证文本**
  （`font/字体与显示.txt` 只说「游戏必须」）→ TODO(verify)，卡点是找不到上游授权原文。
* `BIZ UDGothic`：原版内置回退字体（`Config.cs:511` 的资源名），若要做第二回退族，
  需先核对 IPA 系许可证条款 → TODO(verify)。

## 6 需要组长接线的三件事（本路无权改）

1. **CHKFONT 语义分叉**：语料有 **56 行** `CHKFONT`/`CHKFONTNAME` 分支
   （`FunctionMethodCreator.cs:3476-3509` 同时查系统字体集与私有字体集）。安卓上
   `CHKFONT("ＭＳ Ｐゴシック")` 只能返回 0，而 PC 返回 1 → **走的是不同语句**，
   这是 VM 行为差异不是渲染差异。core-vm 的 CHKFONT 实现需要明确策略（建议登记一个
   与 PC 同名的假族使分支一致），并由 golden 复核。
2. **辅助平面 33 码点的格宽**：`Grid.cellWidth(ch: Char)` 收到代理对会算成 2+2=4 格，
   而 `GlyphKey.codePoint: Int` 是码点级。两者对不上，需要 m-grid 决定按码点折叠。
3. `:render` 默认字体名必须钉成 `Eratw Mono SC`（与 `build_subset.py --family` 同值），
   且 `SETFONT "Saitamaar"` 若真要支持，得让 GlyphCache 允许第二个登记族。

## 7 换字体波（GFX-12 收口，2026-10-24）

§5 的「只有 Saitamaar 可随包」已经过时了：现在随包的是**四个登记族**，由
`build_font_pack.py` 从各自的 OFL 源生成子集，装配侧按 `Config.FontName` 选族
（注册表在 `render/src/main/kotlin/com/eratw/port/render/font/BundledFontFamilies.kt`）。

判据是两条硬指标：**语料码点闭包覆盖率** + **advance 是否严格 1:2**（`Grid` 的格数口径）。
实测读数（`render/src/main/resources/fonts/font-pack-manifest.txt` 是同一份数）：

| 字体 | 许可 | upem | 种类覆盖 | **字数覆盖** | advance 主体 | 结论 |
|---|---|---|---|---|---|---|
| **Fusion Pixel 12px** | OFL-1.1 | 1200 | 7262/8076 = 89.92% | **99.916%** | 600 : 1200 | 备选（**字数覆盖最优**） |
| Ark Pixel 12px | OFL-1.1 | 1200 | 7252/8076 = 89.80% | 99.522% | 600 : 1200 | 备选（点字更全，缺高频简体字） |
| **Unifont 15.0.01** | OFL-1.1 / GPLv2+FE | 1024 | 8034/8076 = 99.48% | **100.000%** | 512 : 1024 | 备选（**零方框**，只差 84 次 emoji/私用区） |
| Cubic 11 | OFL-1.1 | 1200 | 6868/8076 = 85.04% | — | 169 字 advance 1300 | **淘汰**：破 1:2，屏上会压下一格 |
| DotGothic16 | OFL-1.1 | 1000 | 5156/8076 = 63.84% | — | — | **淘汰**：JIS X 0208 口径，比现役 Saitamaar 还低 |
| **Saitamaar** | 公有领域 | 1280 | 5268/8076 = 65.23% | 95.921% | 比例 | **默认族**（2026-10-25 主人指定）· 比例按 1:2 兜底 |

**两个口径都要看**：「种类覆盖」= 8076 个码点里命中几个；「字数覆盖」= 按语料
95,837,334 个字的**实际出现次数**加权。两者会给出相反结论 —— Ark 与 Fusion 的种类
只差 10 个，字数却差 **5.7 倍**（99.522% vs 99.916%，缺 45.8 万次 vs 8.0 万次）：
Ark 缺的是 `恋`（87,504 次）、`然`（56,883 次）、`莉`、`紧`、`热`、`処`、`変` 这类高频字，
多出来的却是点字（175 个）与冷僻字。**所以覆盖率最优的是 Fusion**（仍是设置页里的首推备选）。

**当前默认族是 Saitamaar**（2026-10-25 主人指定，eratw 早期安卓版观感）：它是**比例**字体
（空格 400 / 数字 640 / 拉丁 800 / CJK 1280），整数 1:2 网格对它不严格成立，布局侧仍按
`Grid` 格数排、单字横向缩放进格内；种类覆盖 65.23%，字数覆盖 95.92%（缺 391 万次）。
设置页把五族全部列出，玩家可切到 Fusion / Ark / Unifont；语料里 14 处 `SETFONT "Saitamaar"`
也落同一族。

### 7.1 源与复现

| 族 | 源 | 取法 |
|---|---|---|
| Ark Pixel 12px | 仓内 `app/src/main/res/font/ark_pixel.ttf` | 已在仓库，无需下载 |
| Fusion Pixel 12px | `@fontpkg/fusion-pixel` 20220405.0.0 | `npm pack @fontpkg/fusion-pixel` → `package/fusion-pixel.ttf` |
| Unifont 15.0.01 | `@fontpkg/unifont` 15.0.1 | `npm pack @fontpkg/unifont` → `package/unifont-15.0.01.ttf` |

```bash
pip install fonttools==4.67.0
ERATW_FONT_FUSION=/path/fusion-pixel.ttf \
ERATW_FONT_UNIFONT=/path/unifont-15.0.01.ttf \
python build_font_pack.py            # 重出三族子集 + sidecar + manifest
python build_font_pack.py --check    # 只报覆盖率，不落盘
```

`build_font_pack.py` 与 `build_subset.py` 的分工：后者是 Saitamaar 专用（历史产物，
`guard_source` 只放行 saitamaar），前者是多族通用版，`DENIED_SOURCE_HINTS` 同样拒绝微软字体。

### 7.2 缺字回退链（实际落地）

```
resolve(requested):  ""/未知/PC 字体名（ＭＳ ゴシック・黑体・SimHei…） -> 默认族 Saitamaar
                     "Saitamaar"                                        -> 该族（默认正文，也供 SETFONT）
                     登记族名（MisakiGothic / Fusion / Ark / Unifont）   -> 该族
                     （当前五族全部进设置页的「正文字体」列表）
glyphFor(family, cp): 族命中 -> 画；族未命中 -> 该族 .notdef 方框（advance 按 Grid 2 格）
```

**不再有「回退系统字体」这条**：`StaticGlyphRaster` 与 `CanvasConsoleView` 都只画随包族，
同一串在不同设备上读数相同（`TextMetrics` 的 `fallbackAdvancePixels` 口径）。

### 7.3 遗留

* 设置页选择写的是 `emuera.config` 的 `FONT NAME`（`ConfigKey.FontName`），该键在
  `ConfigApplyMode` 表里是 **RESTART**（`GameSession.kt:303` 烘进
  `LineBufferConsoleSink` 构造参数）⇒ 换字体要重开游戏。
* `app/src/main/res/font/ark_pixel.ttf`（5.13 MB 全量）仍被启动遮罩
  `ShellUi.PixelLoadingOverlay` 使用，与随包子集重复约 1.35 MB；遮罩的
  `loadingTitle` 是动态文案，换子集有缺字风险，暂不合并 —— 待定文案闭包后再收。
* 三份 `<slug>-advance.txt` 目前**没有生产消费点**（`FontSidecarCatalog` 只在测试里装配）。
  宽度仍走 `CellMetrics.fallbackAdvancePixels` 的 0/1/2 档；对 Fusion/Ark/Unifont 这三个严格 1:2
  的族，两套口径**逐码点同值**，所以没有偏差。接线时机：等 `GDRAWTEXT` 需要真实
  advance（非整数格缩放）时。
