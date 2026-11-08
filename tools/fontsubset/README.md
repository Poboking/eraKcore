# tools/fontsubset —— 语料码点闭包与字体子集规格（M4-FONT / t-font）

五个脚本 + 闭包/探针产出。**本目录不含任何字体二进制**：成品子集与 sidecar 落在
`render/src/main/resources/fonts/`，本目录只放生成脚本与可审计的文本读数。

| 文件 | 作用 |
|---|---|
| `scan_codepoints.py` | 只读扫游戏目录，产出确定性码点闭包（唯一语义输入） |
| `probe_metrics.py` | 纯标准库 sfnt 探针：复核参照字体的 advance 比例与覆盖率 |
| `probe_fallback.py` | 缺字回退参照族（BIZ UDGothic 等）的覆盖实测 |
| `build_subset.py` | Saitamaar 专用子集生成器（历史产物，`guard_source` 只放行 saitamaar） |
| `build_font_pack.py` | **现役**多族子集生成器：6 个源族 → 子集 + sidecar + manifest |
| `required-codepoints.txt` | **主产物**：7956 个码点，逐行 `U+XXXX`，升序 |
| `scan-report.txt` | 作用域/编码统计、剔除项、区段命中、基准交叉验证 |
| `scan-manifest.txt` | 逐文件（相对路径、字节数、编码判定、码点数），审计用 |
| `probe-report.txt` | simhei/Saitamaar/msgothic 的度量与覆盖实测 |
| `missing-in-simhei.txt` | PC 参照字体自身没有的 630 个码点（缺字回退的真实规模） |
| `missing-in-Saitamaar*.txt` | Saitamaar 源字体与子集各缺的 2787 个码点 |

## 1 复现命令

```bash
# 工作目录即本目录；语料用 ERATW_GAMEDIR 指到 4.981 出货包
python scan_codepoints.py --selfcheck
python probe_metrics.py            # 需要本机存在 C:/Windows/Fonts/simhei.ttf
# 现役子集生成（六族，见 §7.1 的源与 env 变量）
pip install fonttools==4.67.0
python build_font_pack.py          # 重出六族子集 + sidecar + manifest，并自校验
python build_font_pack.py --check  # 只报覆盖率，不落盘
```

游戏目录解析顺序：位置参数 > `ERATW_GAMEDIR` > `<work-root>/eratw-安卓移植版…` 内置默认值。
**脚本只以 rb 打开语料，并显式拒绝把输出目录设进游戏目录**（`assert_outside_game_dir`）。

## 2 闭包口径（决定清单对不对，先看这段）

* 作用域 = `ERB/` + `CSV/` 下的 `.erb .erh .erd .csv .als`（扩展名集合照抄
  `Preload.cs:59`），4.981 出货包实测 **4560** 个文件。`README集`/`resources`/更新记录里的
  额外码点**不入闭包**——引擎不加载这些目录（验收报告一·4：只加载 `ERB/`）。
* 解码 = 逐文件自动探测，**不是** `Config.Encode`（那个只用于写配置/日志，
  `ConfigData.cs:429,646`）。复刻 `EncodingHandler.cs:15-40` 的三分支：
  BOM → UTF-8；否则严格 UTF-8；否则 CP932。实测 **4130 带 BOM / 430 无 BOM 但严格
  UTF-8 可解 / 0 个走 CP932**，所以 CP932 表外字的口径分歧在本语料上不会发生。
  真的落到该分支时脚本 `errors = "strict"` 直接失败，不猜。
* 行终止符与不可渲染字符照 `.NET File.ReadAllLines`（`EraStreamReader.cs:52`）的语义剔除，
  共 6 个：`U+0007 U+0009 U+200B U+200D U+202C U+206A`（4.981 实际出现 5 个，`U+0007` 为 0 次）。
  `U+0009` 单独说明：它在测量前就被换成 8 个空格（`StringMeasure.cs:59-61`），不需要字形。
* 结果：**观察 7961 → 请求 7956**（剔除 5 个非渲染码点）；黄金基准 `pc-golden-root.txt`
  的 **312 个码点全部被闭包覆盖（缺失 0）**（`scan-report.txt` 的交叉验证节，仍是 4.980
  时期的快照，只作「闭包够用」的实证，不当分母）。
* 排序键只有码点整数，输出固定 LF，不写时间戳/绝对路径/Unicode 名称，
  `--selfcheck` 已验证两次运行逐字节相同（`required-codepoints.txt`
  sha256 `0e43b860…0040c8d9`）。

## 3 子集规格

区段命中（完整表见 `scan-report.txt`，4.981 语料）：CJK 基本 6656、日文假名 174、
点字 175、半角假名/半角符号 63、全角 ASCII 94、框线 54、符号杂项 40、一般标点 27、
西里尔 51、希腊 35、组合附加符号 89、罗马数字 13、几何图形 21、块元素 9、箭头 9、
CJK 符号标点 27、拉丁扩展 A 35、ASCII 可打印 95、**CJK 扩展 A 8**、
**辅助平面 38 个**（emoji 33 + 音乐符号 2 + `U+20CD0`/`U+26E99`/`U+299C3`）、**私用区 9 个**。
易漏的是最后四组加扩展 A：整段下载体时容易被「只取 CJK 基本区」的默认策略削掉。

方案：**多族、每族单重**（Regular 一档），同一 `required-codepoints.txt` 各出一版；
样式位里的斜体/删除线/下划线（`Kernel.kt:85-88` 的 2/4/8）**不靠字体斜体文件**，
由渲染层自绘（等宽族都没有斜体原件，硬找斜体反而易破坏 1:2 宽度）。
族名是 Typeface 与 sidecar 的唯一机器键，必须与 Kotlin 侧 `BundledFontFamilies` 逐字相同：
默认族 `Sarasa Mono SC`，霞鹜文楷因 OFL-1.1 的 Reserved Font Name 条款在子集里改名成
`Eratw WenKai Mono`（展示名仍是原名）。

宽度口径（这是出口条件的命门）：`Grid.isFullWidth` 认定「ASCII + `U+FF61..FF9F` 为
1 格，其余一律 2 格」，闭包里 **1 格 158 个 / 2 格 7798 个**。用 `probe_metrics.py` 实测
本包 `emuera.config`（`FONT NAME:黑体`）指向的 `simhei.ttf`：unitsPerEm=256，
ASCII advance=128，而 CJK/假名/全角标点/框线（`U+2500`、`U+2503`）/块元素/箭头/罗马数字/
希腊/西里尔/注音**全部 256**；24 组取样里有字形的 19 组**全部严格 1:2，0 组偏离**，
另 5 组是 `simhei.ttf` 自己缺字（见第 4 节）。
结论：Grid 的 1:2 模型与 PC 实际绘制字体在取样范围内**严格一致**，可作截图对齐的依据；
东亚马宽（Ambiguous）字符在 SimHei 里确实是全角，所以那 410 个 Ambiguous 必须出成 2 格。
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

安卓侧落地规则（现役实现见 `BundledFontFamilies.fallbacksFor` 与
`CanvasConsoleView.effectiveFamily` / `AndroidGlyphMaskHost.typefaceFor`）：

```
resolveFont(requested):  requested ∈ {"", "Sarasa Mono SC"} -> DEFAULT("Sarasa Mono SC")
                         requested 命中登记族名（八族，见 §7） -> 该族
                         PC 字体名（黑体 / SimHei / ＭＳ ゴシック …） -> DEFAULT   # 永不抛
glyphFor(font, cp):      选中族命中 -> 画；未命中 -> Sarasa 命中 -> 画；
                         Sarasa 未命中 -> Unifont 命中 -> 画；
                         整链未命中 -> 画子集 .notdef 方框（advance 按 Grid 2 格）
```

与 PC 的差别：原版靠 GDI font linking 借系统字体，缺字表现随机器安装的字体变化；
移植侧改成**固定的随包链**（`选中族 → Sarasa → Unifont`），同一份 APK 在任何机器上读数相同。
判据是 `Paint.hasGlyph`（API 23+；minSdk 21 时整链退化为选中族，系统回退不该被当成契约）。

规模参考：`simhei.ttf` 自己对闭包就缺 **630/7956（7.92%）**（见 `missing-in-simhei.txt`，
含半角假名 `U+FF61/FF71`、`U+00A5`、`U+2600` 类杂项符号、点字），PC 上这些是靠
GDI 字体链接借别的字体画出来的，安卓没有链接机制 → 由随包链兜或方框，属**已知截图差异**。
因此子集必须 `notdef_outline = True`（`build_font_pack.py` 已设），否则回退末端没有可画的框。
链尾 Unifont 覆盖 7909/7956，真正落到方框的只有 47 个码点（9 个私用区 + 38 个辅助平面）。

## 5 授权与来源

* `msgothic.ttc` / `msmincho.ttc`（随包在 `font/`）：微软字体，**不可再分发**，
  也**不能当子集母体**——`build_subset.py` 与 `build_font_pack.py` 的 `guard_source`
  直接拒绝这类文件名（实测 name ID 13 无子集再分发权，原文见
  `render/src/main/resources/fonts/LICENSE-Saitamaar.txt` §1.3）。
  附带发现：引擎只把 `font/` 里的 `*.ttf`/`*.otf` 注册成私有字体（`Program.cs:131-140`），
  这俩 `.ttc` 在 PC 上**从未被加载**。
* **SimHei（原版正文 `黑体`）同样不可随包**——这也是默认族取 Sarasa 替身的原因（§7）。
* 现役六族子集全部 OFL-1.1（Unifont 另加 GPLv2+FE 选项），各带一份许可证全文：
  `LICENSE-Sarasa.txt` / `LICENSE-LXGWWenKai.txt` / `LICENSE-MapleMono.txt` /
  `LICENSE-FusionPixel.txt` / `LICENSE-ArkPixel.txt` / `LICENSE-Unifont.txt`。
* 霞鹜文楷声明了 **Reserved Font Name**（`LXGW` / `霞鹜文楷`）：OFL-1.1 §3 要求修改版
  （子集即修改版）不得沿用保留名 ⇒ 子集在 `build_font_pack.py` 里用 `name` 表改名为
  `Eratw WenKai Mono` 再分发，展示名仍写原名。其余五族无 RFN，族名原样。
* `Saitamaar.ttf`：公有领域（作者 keage 本人声明，取证见
  `LICENSE-Saitamaar.txt` §1.1.1），子集可随包；语料 `SETFONT` 用到 14 处。
* MisakiGothic：作者 門真なむ，自由使用；许可证文本在 `LICENSE-Misaki.txt`。
* `BIZ UDGothic`：原版内置回退字体（`Config.cs:511` 的资源名），若要做第二回退族，
  需先核对 IPA 系许可证条款 → TODO(verify)。

## 6 需要组长接线的三件事（本路无权改）

1. **CHKFONT 语义分叉**：4.981 语料有 **55 行** `CHKFONT`/`CHKFONTNAME` 分支
   （`FunctionMethodCreator.cs:3476-3509` 同时查系统字体集与私有字体集）。安卓上
   `CHKFONT("ＭＳ Ｐゴシック")` 只能返回 0，而 PC 返回 1 → **走的是不同语句**，
   这是 VM 行为差异不是渲染差异。core-vm 的 CHKFONT 实现需要明确策略（建议登记一个
   与 PC 同名的假族使分支一致），并由 golden 复核。
2. **辅助平面 38 码点的格宽**：`Grid.cellWidth(ch: Char)` 收到代理对会算成 2+2=4 格，
   而 `GlyphKey.codePoint: Int` 是码点级。两者对不上，需要 m-grid 决定按码点折叠。
3. 设置页的 `FONT NAME` 现在是**八族机器键**：`Sarasa Mono SC`（默认）/ `Eratw WenKai Mono` /
   `Maple Mono CN` / `Saitamaar` / `MisakiGothic` / `Fusion Pixel 12px` / `Ark Pixel 12px` /
   `Unifont`。`ConfigSpec.kt:122` 的默认值仍是 `ＭＳ ゴシック`（经别名折到 Sarasa），
   若要让新装包直接写 Sarasa，需 core-vm 侧改默认值 —— 本路未动。

## 7 换字体波（GFX-12 收口 2026-10-24；八族+默认族改判 2026-11-05）

§5 的「只有 Saitamaar 可随包」已经过时了：现在随包的是**八个登记族**，六个由
`build_font_pack.py` 从 OFL 源生成子集（Saitamaar / 美咲两个另有来源），装配侧按
`Config.FontName` 选族（注册表在
`render/src/main/kotlin/com/eratw/port/render/font/BundledFontFamilies.kt`）。
**默认正文族 = Sarasa Mono SC**（原版黑体的合法替身）。

判据是三条硬指标：**种类覆盖** + **按出现次数加权的字数覆盖** + **advance 是否严格 1:2**
（`Grid` 的格数口径，PC 基准 199 列 = `EmueraConsole.cs:3255` 的 `GetByteCount`）。
实测读数（2026-11-05，4.981 出货语料，闭包 7956 码点 / 总出现 **108,464,789** 次；
`render/src/main/resources/fonts/font-pack-manifest.txt` 是前六族的同一份数）：

| 字体 | 许可 | upem | 种类覆盖 | **字数覆盖** | advance 主体 | 结论 |
|---|---|---|---|---|---|---|
| **Sarasa Mono SC** | OFL-1.1 | 1000 | 7880/7956 = 99.04% | **99.999%**（缺 1,056 次） | 500 : 1000 | **默认**（原版黑体替身） |
| **霞鹜文楷等宽**（LXGW） | OFL-1.1（RFN，子集改名 `Eratw WenKai Mono`） | 1000 | 7651/7956 = 96.17% | 99.961%（缺 42,354 次） | 500 : 1000 | 备选（楷体观感） |
| **Maple Mono CN** | OFL-1.1 | 1000 | 7213/7956 = 90.66% | 99.182%（缺 887,528 次） | 600 : 1200 | 备选（圆润等宽） |
| **Unifont 15.0.01** | OFL-1.1 / GPLv2+FE | 1024 | 7909/7956 = 99.41% | **100.000%**（缺 495 次） | 512 : 1024 | **兜底族**（只差 47 个码点） |
| **Fusion Pixel 12px** | OFL-1.1 | 1200 | 7136/7956 = 89.69% | 99.917%（缺 89,992 次） | 600 : 1200 | 备选（像素） |
| Ark Pixel 12px | OFL-1.1 | 1200 | 7146/7956 = 89.82% | 99.515%（缺 526,038 次） | 600 : 1200 | 备选（像素，点字更全） |
| **Saitamaar** | 公有领域 | 1280 | 5169/7956 = 64.97% | 95.300%（缺 5,098,238 次） | 比例 | 字符画族（`SETFONT "Saitamaar"`，语料 14 处） |
| **MisakiGothic** | 自由使用（門真なむ） | 2048 | 4977/7956 = 62.56% | 95.031%（缺 5,389,921 次） | 1024 : 2048 | 观感档（eratw 早期安卓版） |

Cubic 11 与 DotGothic16 仍**淘汰**：前者 169 个码点 advance = 1300（upem 1200），
超出一个全角格、屏上会压到下一格；后者是 JIS X 0208 口径，中文简体字大面积缺失。

**两个口径都要看**：「种类覆盖」= 7956 个码点里命中几个；「字数覆盖」= 按语料
108,464,789 个字的**实际出现次数**加权。两者会给出相反结论 —— Ark 与 Fusion 的种类
只差 10 个，缺字数却差 **近 6 倍**（526,038 vs 89,992）：Ark 缺的是 `恋`、`然`、`莉`、
`紧`、`热`、`処`、`変` 这类高频字，多出来的却是点字（175 个）与冷僻字。
**默认族按字数覆盖选**：Sarasa 在八族里缺字最少（1,056 次），且缺的码点由 Unifont 兜。

**为什么不是 Saitamaar**：2026-10-25 至 2026-11-05 它曾是默认正文族（eratw 早期安卓版观感），
但它是**比例**字体（空格 400 / 数字 640 / 拉丁 800 / CJK 1280），整数 1:2 网格对它不严格成立，
且种类覆盖只有 64.97%、字数覆盖 95.30%（缺 510 万次）——屏上方框与制表错位的主因。
现在它回归字符画本职，`SETFONT "Saitamaar"` 仍落这一族。

**为什么是 Sarasa 而不是原版黑体**：原版 `emuera.config` 是 `FONT NAME:黑体`（SimHei），
微软专有、无子集再分发权，**不能随包**（§5）。Sarasa Mono SC 与思源黑体同源（无衬线、
等宽、严格 1:2、同观感），是最接近的合法替身；`黑体` / `SimHei` / `ＭＳ ゴシック` /
`宋体` / `等线` 等写法全部经别名表折到它。

### 7.1 源与复现

| 族 | 源 | 取法 | env |
|---|---|---|---|
| Sarasa Mono SC | `@be5invis/sarasa-gothic-sc` 0.36.0 | `npm pack @be5invis/sarasa-gothic-sc` → `package/sarasa-mono-sc-regular.ttf` | `ERATW_FONT_SARASA` |
| 霞鹜文楷等宽 | `lxgw/LxgwWenKai` v1.530 | `LXGWWenKaiMono-Regular.ttf`（GitHub release） | `ERATW_FONT_LXGW` |
| Maple Mono CN | npm `maple-mono-cn` 7.0.0 | `npm pack maple-mono-cn` → `package/MapleMono-CN-Regular.ttf` | `ERATW_FONT_MAPLE` |
| Fusion Pixel 12px | `@fontpkg/fusion-pixel` 20220405.0.0 | `npm pack @fontpkg/fusion-pixel` → `package/fusion-pixel.ttf` | `ERATW_FONT_FUSION` |
| Ark Pixel 12px | 仓内 `app/src/main/res/font/ark_pixel.ttf` | 已在仓库，无需下载 | — |
| Unifont 15.0.01 | `@fontpkg/unifont` 15.0.1 | `npm pack @fontpkg/unifont` → `package/unifont-15.0.01.ttf` | `ERATW_FONT_UNIFONT` |
| Saitamaar | 语料 `font/Saitamaar.ttf` | `ERATW_GAMEDIR` 下直读 | `ERATW_GAMEDIR` |
| MisakiGothic | `up9cloud/font-misaki` | 已入仓 `fonts/misaki-gothic.ttf` | — |

```bash
pip install fonttools==4.67.0
ERATW_FONT_SARASA=/path/sarasa-mono-sc-regular.ttf \
ERATW_FONT_LXGW=/path/LXGWWenKaiMono-Regular.ttf \
ERATW_FONT_MAPLE=/path/MapleMono-CN-Regular.ttf \
ERATW_FONT_FUSION=/path/fusion-pixel.ttf \
ERATW_FONT_UNIFONT=/path/unifont-15.0.01.ttf \
python build_font_pack.py            # 重出六族子集 + sidecar + manifest
python build_font_pack.py --check    # 只报覆盖率，不落盘
python build_subset.py --verify      # Saitamaar 子集（独立脚本）
```

`build_font_pack.py` 与 `build_subset.py` 的分工：后者是 Saitamaar 专用（历史产物，
`guard_source` 只放行 saitamaar），前者是多族通用版；两者的拒绝表
（`DENIED_SOURCE_HINTS`）都含微软字体。

### 7.2 缺字回退链（实际落地）

```
resolve(requested):  ""/未知/PC 字体名（ＭＳ ゴシック・黑体・SimHei・宋体…） -> DEFAULT（Sarasa Mono SC）
                     登记族名（八族）                                    -> 该族
                     别名表（更纱黑体 / 霞鹜文楷等宽 / maple-mono …）      -> 对应族
glyphFor(family, cp): 选中族命中 -> 画
                      选中族未命中 -> Sarasa 命中 -> 画
                      Sarasa 未命中 -> Unifont 命中 -> 画
                      整链未命中 -> 子集 .notdef 方框（advance 按 Grid 2 格）
```

整链只含随包族（`BundledFontFamilies.fallbacksFor`），判据是 `Paint.hasGlyph`
（API 23+；minSdk 21 时退化为选中族 + 系统回退）。默认族 Sarasa 的链是 `[Unifont]`，
Unifont 的链是 `[Sarasa]`，其余六族是 `[Sarasa, Unifont]`。
**不再有「回退系统字体」这条**：`StaticGlyphRaster`、`CanvasConsoleView`、
`AndroidGlyphMaskHost` 与 `AndroidTextMeasure` 共用同一张注册表，同一串在不同设备上读数相同。
链尾 Unifont 覆盖 7909/7956，真正方框只剩 47 个码点（9 个私用区 + 38 个辅助平面）。

### 7.3 遗留

* 设置页选择写的是 `emuera.config` 的 `FONT NAME`（`ConfigKey.FontName`），该键在
  `ConfigApplyMode` 表里是 **RESTART**（`GameSession.kt:303` 烘进
  `LineBufferConsoleSink` 构造参数）⇒ 换字体要重开游戏。
* `app/src/main/res/font/ark_pixel.ttf`（5.13 MB 全量）仍被启动遮罩
  `ShellUi.PixelLoadingOverlay` 使用，与随包子集重复约 1.35 MB；遮罩的
  `loadingTitle` 是动态文案，换子集有缺字风险，暂不合并 —— 待定文案闭包后再收。
* 七族有 `<slug>-advance.txt` sidecar（美咲没有，缺行即按 `Grid` 格数走，它恰好严格 1:2）；
  这些 sidecar 目前**没有生产消费点**（`FontSidecarCatalog` 只在测试里装配）。
  宽度仍走 `CellMetrics.fallbackAdvancePixels` 的 0/1/2 档；对严格 1:2 的六族
  （Sarasa / 霞鹜 / Maple / Fusion / Ark / Unifont），两套口径**逐码点同值**，所以没有偏差。
  接线时机：等 `GDRAWTEXT` 需要真实 advance（非整数格缩放）时。
