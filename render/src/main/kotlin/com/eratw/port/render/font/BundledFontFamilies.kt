package com.eratw.port.render.font

/**
 * 随包字体族的一条登记。
 *
 * 族名（[family]）是**唯一键**，且必须与 `render/src/main/resources/fonts/<slug>-advance.txt`
 * 里 `#family` 指令逐字相同（slug 由 `FontSidecarCatalog.slugOf` 折叠：
 * ASCII 小写、空白/`-`/`/`/反斜杠 折成单个 `-`）—— 度量侧按族名查 sidecar，
 * 渲染侧按族名查 Typeface，两处必须同一个字符串，否则会静默退化成缺字分档。
 *
 * @property asset APK assets 里的相对路径（`:render` 的 resources 由 Gradle 同步进 assets）
 * @property designPx 源字体的设计点阵尺寸（12 = 12px 点阵；16 = 16px 点阵）。
 *   轮廓族（Sarasa / 霞鹜文楷 / Maple）没有点阵档，登记 18 = 原版正文的实际像素字号
 *   （`ConfigData.cs:66`）。只作溯源信息，渲染字号仍由 [com.eratw.port.render.glyph.CellMetrics] 决定。
 * @property coverageHit 该子集对 `tools/fontsubset/required-codepoints.txt`（7956 码点，由
 *   4.981 出货语料重扫）的命中数
 * @property coverageTotal 闭包总码点数（= 7956）
 * @property occurrenceCoverage 按**语料实际出现次数**加权的覆盖率（0..1）。
 *   这是「读起来卡不卡」的真读数 —— 种类覆盖会骗人（见类文档），**两者是两个数**：
 *   本值必须是 `tools/fontsubset/build_font_pack.py` 的 `load_corpus_freq` × 该族 cmap
 *   实测（分母 = 4.981 语料 `ERB/` 总出现次数 108,464,789，`fonts/font-pack-manifest.txt`
 *   各族的 `occurrence-coverage` 行），不许拿 [coverage] 的比例抄进来。
 *   设置页显示的正是本值（`app/.../SettingsActivity.kt` 的 `fontNote + coverage`）。
 * @property occurrenceMissing 按出现次数算的缺字次数（越少越好）。
 *   **0 是一个实测断言（「一次都没缺过」），不是「没测过」的空白** —— 未登记的族一律先测
 *   再登记；`occurrenceCoverage < 1.0` 而本值为 0 是自相矛盾（缺字次数与字数覆盖率必须
 *   同源于同一次测量），由 `font/BundledFontOccurrenceLedgerTest` 拦。
 * @property selectable 是否进设置页的「正文字体」选择列表。当前八族全部可选。
 * @property note 一行说明（来源/许可/宽度口径），进设置页副标题与日志。
 * @property displayName 设置页展示名（可带中文习惯名）；默认等于 [family]。
 *   与 [family] 分开是因为 `family` 是 Typeface / sidecar 的机器键（改名会同时打断
 *   度量与渲染两条查表），展示名只是文案。
 */
data class BundledFontFamily(
    val id: String,
    val family: String,
    val asset: String,
    val designPx: Int,
    val coverageHit: Int,
    val coverageTotal: Int,
    val occurrenceCoverage: Double,
    val occurrenceMissing: Int,
    val selectable: Boolean,
    val note: String,
    val displayName: String = family,
) {

    /** 码点种类覆盖率（0..1）。 */
    val coverage: Double get() = if (coverageTotal <= 0) 0.0 else coverageHit.toDouble() / coverageTotal
}

/**
 * 随包字体族注册表（GFX-12 换字体波）。
 *
 * ## 为什么要有这张表
 *
 * 换字体之前，`:render-canvas` 与 `:platform` 两处都把「字体名 → Typeface」硬编码成
 * **单一**随包字体 `fonts/saitamaar-subset.ttf`，`GlyphKey.fontName` / `GlyphRequest.fontName`
 * 完全不参与选族。而 Saitamaar 是**字符画用的比例字体**，正文观感与覆盖率都不理想
 * （upem 1280 的比例 advance，闭包只覆盖 64.97%）。
 *
 * 于是本表把随包字体做成**多族**，由 `Config.FontName`
 * （`ConfigKey.FontName`，`ConfigSpec.kt:122` 默认 `ＭＳ ゴシック`）经
 * `GameSession` → `LineBufferConsoleSink.defaultFontName` 一路传下来的族名选族。
 *
 * ## 原版字体（黑体 / SimHei）为什么不随包
 *
 * 原版 PC 的 `emuera.config` 写的是 `FONT NAME:黑体`（SimHei，18px，微软专有字体），
 * **不可随包分发**（中易/微软授权；仓内 `tools/fontsubset/guard_source` 也直接拒绝）。
 * 因此「用原版字体」落地为**最接近的合法替身**：**Sarasa Mono SC**
 * （思源黑体同源、OFL-1.1、严格 1:2、18px），它比原版黑体多了完整的日文/符号覆盖面，
 * 中文简体字形与黑体同类（无衬线、等宽），是本仓的默认正文族。
 * `Config.FontName` 里的 `黑体` / `SimHei` / `ＭＳ ゴシック` 一族全部经别名表折到它。
 *
 * ## 候选是怎么筛出来的（读数见 `fonts/font-pack-manifest.txt`；美咲不在那份 manifest 里 ——
 * 它不是 `build_font_pack.py` 的候选族，两个口径的实测出处写在 [MISAKI] 的文档里）
 *
 * 判据是**三条**：语料码点闭包的**种类**覆盖、按语料**实际出现次数**加权的覆盖
 * （后者才是「读起来卡不卡」）、以及 **advance 是否严格 1:2**（布局侧 `Grid` 的格数口径，
 * PC 基准 199 列 = `EmueraConsole.cs:3255` 的 `GetByteCount`）。逐字体实测
 * （2026-11-05，4.981 出货语料，闭包 7956 码点 / 总出现 108,464,789 次）：
 *
 * | 字体 | 许可 | upem | 种类覆盖 | 字数覆盖 | advance 主体 | 结论 |
 * |---|---|---|---|---|---|---|
 * | **Sarasa Mono SC** | OFL-1.1 | 1000 | 7880/7956 = 99.04% | **99.999%** | 500 : 1000 | **默认**（原版黑体替身） |
 * | **霞鹜文楷等宽** | OFL-1.1（RFN，子集已改名） | 1000 | 7651/7956 = 96.17% | 99.961% | 500 : 1000 | 备选（楷体观感） |
 * | **Maple Mono CN** | OFL-1.1 | 1000 | 7213/7956 = 90.66% | 99.182% | 600 : 1200 | 备选（圆润等宽） |
 * | **Unifont 15.0.01** | OFL-1.1 / GPLv2+FE | 1024 | 7909/7956 = 99.41% | **100.000%** | 512 : 1024 | **兜底族**（只差 47 个码点） |
 * | **Fusion Pixel 12px** | OFL-1.1 | 1200 | 7136/7956 = 89.69% | 99.917% | 600 : 1200 | 备选（像素） |
 * | Ark Pixel 12px | OFL-1.1 | 1200 | 7146/7956 = 89.82% | 99.515% | 600 : 1200 | 备选（像素，点字更全） |
 * | **Saitamaar** | 公有领域 | 1280 | 5169/7956 = 64.97% | 95.300% | 比例 | 字符画族（`SETFONT "Saitamaar"`） |
 * | **MisakiGothic** | 自由使用（門真なむ） | 2048 | 4977/7956 = 62.56% | 95.031% | 严格 1:2 | 观感档 |
 *
 * Cubic 11 与 DotGothic16 仍**淘汰**：前者 169 个码点 advance = 1300（upem 1200），
 * 超出一个全角格、屏上会压到下一格；后者是 JIS X 0208 口径，中文简体字大面积缺失。
 *
 * ## 缺字回落（逐字形，确定性）
 *
 * 原版缺字时靠 GDI 的 font linking 挂到系统字体，结果随机器安装的字体变化。
 * 移植侧改成**固定的随包链**（全链由 [GlyphFallback.chainFor] 组装，本表只给兜底段
 * [fallbacksFor]）：
 *
 * ```
 * 选中族 -> Sarasa（除自身）-> Unifont -> .notdef（方框）
 * ```
 *
 * 判据是 `Paint.hasGlyph`（API 23+；minSdk 21 时整链退化为选中族 + 系统回退）。
 * 整链只含随包族 ⇒ 同一份 APK 在任何机器上缺字表现一致；Unifont 覆盖 7909/7956，
 * 链尾只剩 47 个码点（9 个私用区 + 38 个增补平面 emoji/音乐符号），屏上是最后一个方框。
 *
 * ## 名称解析
 *
 * ```
 * resolve(requested):  requested 空/未登记 -> DEFAULT（Sarasa Mono SC）
 *                      requested 命中登记族名 -> 该族
 *                      别名表（黑体 / SimHei / ＭＳ ゴシック …）-> DEFAULT
 * ```
 *
 * **永不抛、永不返回 null** —— 与 `FontFactory.GetFont` 的 try/catch → null 同向
 * （spec: `FontFactory.cs:52-54`），只是移植侧把 null 提前折成默认族，省掉每个调用点的空判。
 */
object BundledFontFamilies {

    /** 默认正文族 —— 原版黑体的合法替身；设置页没选、配置值是未知字体名时都落这里。 */
    const val DEFAULT_FAMILY: String = "Sarasa Mono SC"

    /** 字符画族（`SETFONT "Saitamaar"` 的落点，语料里 14 处）。 */
    const val ASCII_ART_FAMILY: String = "Saitamaar"

    /** 兜底族：缺字链的最后一档（覆盖 7909/7956，剩下的才是真方框）。 */
    const val FALLBACK_FAMILY: String = "Unifont"

    /** 闭包总码点数（`tools/fontsubset/required-codepoints.txt` 的行数，4.981 语料重扫）。 */
    const val REQUIRED_CODEPOINTS: Int = 7956

    /**
     * Sarasa Mono SC（**默认正文族**）：原版黑体（SimHei）的合法替身。
     *
     * 来源：npm `@be5invis/sarasa-gothic-sc` 0.36.0 的 `sarasa-mono-sc-regular.ttf`
     * （OFL-1.1，无 Reserved Font Name）。字形同源思源黑体（Source Han Sans），
     * 半角 500 / 全角 1000（upem 1000），严格 1:2，与布局侧 `Grid` 的格数口径一致。
     *
     * 读数：种类覆盖 7880/7956 = 99.04%，按出现次数加权 99.999%（108,463,733 / 108,464,789 次），
     * 只缺 1056 次 —— 八族里字数覆盖最高、缺字最少的一档，且缺的码点由 [UNIFONT] 兜。
     * 原版 18px 正文在这里按 [designPx] 登记为 18（轮廓族无点阵档）。
     */
    val SARASA: BundledFontFamily = BundledFontFamily(
        id = "sarasa",
        family = DEFAULT_FAMILY,
        asset = "fonts/sarasa-mono-sc-subset.ttf",
        designPx = 18,
        coverageHit = 7880,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.9999903,
        occurrenceMissing = 1056,
        selectable = true,
        note = "思源黑体同源 · OFL-1.1 · 原版黑体替身 · 严格 1:2",
        displayName = "更纱黑体等宽（Sarasa Mono SC）",
    )

    /**
     * 霞鹜文楷等宽（LXGW WenKai Mono）：楷体观感档。
     *
     * 来源：`lxgw/LxgwWenKai` v1.530 的 `LXGWWenKaiMono-Regular.ttf`（OFL-1.1）。
     * **该字体声明了 Reserved Font Name**，所以子集在 `build_font_pack.py` 里用
     * `name` 表改名成 `Eratw WenKai Mono` 后再分发（OFL-1.1 §3 要求）；
     * [family] 就是改名后的机器键，展示名仍是原名（[displayName]）。
     *
     * 读数：种类覆盖 7651/7956 = 96.17%，字数覆盖 99.961%（缺 42,354 次）。
     */
    val LXGW_WENKAI: BundledFontFamily = BundledFontFamily(
        id = "lxgw",
        family = "Eratw WenKai Mono",
        asset = "fonts/lxgw-wenkai-mono-subset.ttf",
        designPx = 18,
        coverageHit = 7651,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.9996095,
        occurrenceMissing = 42354,
        selectable = true,
        note = "霞鹜文楷等宽 · OFL-1.1 · 楷体观感 · 严格 1:2",
        displayName = "霞鹜文楷等宽（LXGW WenKai Mono）",
    )

    /**
     * Maple Mono CN：圆润等宽档。
     *
     * 来源：npm `maple-mono-cn` 7.0.0 的 `MapleMono-CN-Regular.ttf`（OFL-1.1，无 RFN）。
     * 半角 600 / 全角 1200（upem 1000），严格 1:2。
     *
     * 读数：种类覆盖 7213/7956 = 90.66%，字数覆盖 99.182%（缺 887,528 次 ——
     * 缺的是若干高频简体字，因此只作观感档，不作默认）。
     */
    val MAPLE_MONO: BundledFontFamily = BundledFontFamily(
        id = "maple",
        family = "Maple Mono CN",
        asset = "fonts/maple-mono-cn-subset.ttf",
        designPx = 18,
        coverageHit = 7213,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.9918173,
        occurrenceMissing = 887528,
        selectable = true,
        note = "Maple Mono CN · OFL-1.1 · 圆润等宽 · 严格 1:2",
        displayName = "Maple Mono CN（圆润等宽）",
    )

    /**
     * Saitamaar 子集（公有领域）：**字符画族**，`SETFONT "Saitamaar"` 的落点（语料 14 处）。
     *
     * 它是**比例字体**（upem 1280：空格 400 / 数字 640 / 拉丁 800 / CJK 1280），
     * 1:2 网格对它不严格成立；布局侧仍按 `Grid` 的格数排、单字横向缩放进格内。
     * 种类覆盖 5169/7956 = 64.97%，字数覆盖 95.300%（缺 5,098,238 次）——
     * 2026-10-25 至 2026-11-05 曾是默认正文族，现回归字符画本职，正文默认让位 [SARASA]。
     */
    val SAITAMAAR: BundledFontFamily = BundledFontFamily(
        id = "saitamaar",
        family = ASCII_ART_FAMILY,
        asset = "fonts/saitamaar-subset.ttf",
        designPx = 16,
        coverageHit = 5169,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.9529964,
        occurrenceMissing = 5098238,
        selectable = true,
        note = "Saitamaar · 公有领域 · 比例字体按 1:2 兜底",
    )

    /**
     * 美咲ゴシック（eratw 早期安卓版观感）：**备选正文族**。
     *
     * 来源：`up9cloud/font-misaki` 的 `misaki_gothic.ttf`（美咲フォント，作者 門真なむ，
     * 自由使用）；advance 严格 1:2（upem 2048：半角 1024 / 全角 2048），正好符合布局侧的
     * `Grid` 格数口径。
     *
     * 读数（2026-11-05，4.981 语料实测）：种类覆盖 **4977/7956 = 62.56%**，
     * 按出现次数加权是 **95.031%**（缺 5,389,921 次 / 108,464,789 次）——
     * 两个数必须分开写：种类覆盖低是因为缺的多数是冷僻字，正文读起来只缺约 5% 的出现次数。
     * 缺字走**随包回落链**（Sarasa → Unifont），不再依赖系统字体。
     * 旧登记曾把种类比 0.6287 抄进 [BundledFontFamily.occurrenceCoverage]、缺字数写 0，
     * 由 `font/BundledFontOccurrenceLedgerTest` 拦住，不许再退回去。
     */
    val MISAKI: BundledFontFamily = BundledFontFamily(
        id = "misaki",
        family = "MisakiGothic",
        asset = "fonts/misaki-gothic.ttf",
        designPx = 8,
        coverageHit = 4977,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.9503072,
        occurrenceMissing = 5389921,
        selectable = true,
        note = "美咲ゴシック（eratw 原版观感）· 严格 1:2 · 缺字走随包回落",
    )

    /** 缝合像素 12px：像素观感档（半角 600 / 全角 1200）。 */
    val FUSION_PIXEL: BundledFontFamily = BundledFontFamily(
        id = "fusion",
        family = "Fusion Pixel 12px",
        asset = "fonts/fusion-pixel-12px-subset.ttf",
        designPx = 12,
        coverageHit = 7136,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.9991703,
        occurrenceMissing = 89992,
        selectable = true,
        note = "缝合像素 12px · OFL-1.1 · 半角 600 / 全角 1200",
    )

    /** 方舟像素 12px：像素观感档里点字（盲文）覆盖最全的一档。 */
    val ARK_PIXEL: BundledFontFamily = BundledFontFamily(
        id = "ark",
        family = "Ark Pixel 12px",
        asset = "fonts/ark-pixel-12px-subset.ttf",
        designPx = 12,
        coverageHit = 7146,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.9951502,
        occurrenceMissing = 526038,
        selectable = true,
        note = "方舟像素 12px · OFL-1.1 · 点字（盲文）覆盖更全",
    )

    /**
     * GNU Unifont 16px：**兜底族**（[FALLBACK_FAMILY]），也是「零方框」档。
     *
     * 种类覆盖 7909/7956 = 99.41%，字数覆盖 100.000%（只缺 495 次），
     * 剩下的 47 个码点（9 个私用区 + 38 个增补平面 emoji/音乐符号）才是真方框。
     */
    val UNIFONT: BundledFontFamily = BundledFontFamily(
        id = "unifont",
        family = FALLBACK_FAMILY,
        asset = "fonts/unifont-subset.ttf",
        designPx = 16,
        coverageHit = 7909,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.9999954,
        occurrenceMissing = 495,
        selectable = true,
        note = "GNU Unifont 16px · OFL-1.1 · 兜底族（只差 emoji/私用区）",
    )

    /** 全部登记族（顺序即设置页展示顺序：默认族在最前）。 */
    val ALL: List<BundledFontFamily> = listOf(
        SARASA, LXGW_WENKAI, MAPLE_MONO, SAITAMAAR, MISAKI, FUSION_PIXEL, ARK_PIXEL, UNIFONT,
    )

    /** 可进设置页「正文字体」列表的族。 */
    val SELECTABLE: List<BundledFontFamily> = ALL.filter { it.selectable }

    /** 默认族实体。 */
    val default: BundledFontFamily get() = SARASA

    /**
     * 兜底段（**不含选中族自身**，已去重）—— 只回答「选中族之后试谁」，
     * 不是全链；全链（选中族在首位）由 [GlyphFallback.chainFor] 组装，
     * 度量尺与绘制两处都只读那一条（本函数在 `render/src/main` 里只有它一个调用点）。
     *
     * 规则只有一条：选中族 → [SARASA] → [UNIFONT]，去掉重复项。
     * 因此默认族 Sarasa 的兜底段是 `[Unifont]`，Unifont 的是 `[Sarasa]`，
     * 其它族是 `[Sarasa, Unifont]`。整段只含随包族 ⇒ 机型无关。
     */
    fun fallbacksFor(family: BundledFontFamily): List<BundledFontFamily> {
        val chain = ArrayList<BundledFontFamily>(2)
        for (candidate in listOf(SARASA, UNIFONT)) {
            if (candidate.id != family.id) chain.add(candidate)
        }
        return chain
    }

    /**
     * 等价族名（PC 上的真字体名）→ 登记族。
     *
     * 为什么要这张别名表：语料里 `SETFONT` / `FONT NAME:` 写的是 PC 字体名
     * （`ＭＳ ゴシック`、`黑体` …），安卓上没有这些字体。全键**小写去空白**后比对，
     * 命中即折到默认族 —— 原版那一步是 GDI 通用替换（`FontFactory.cs:33-48`），
     * 移植侧折到默认族（当前 Sarasa Mono SC），语义等价。
     */
    private val ALIASES: Map<String, String> = buildMap {
        // 原版正文（黑体 / SimHei）与 Emuera 默认字体（ConfigSpec.kt:122）的各种写法
        for (name in listOf(
            "ｍｓ ゴシック", "ms gothic", "ｍｓ ｐゴシック", "ms p gothic",
            "ｍｓ 明朝", "ms mincho", "黑体", "simhei", "宋体", "simsun",
            "等线", "dengxian",
            "sarasa gothic sc", "sarasa mono", "更纱黑体", "更纱黑体等宽",
        )) {
            put(normalize(name), SARASA.id)
        }
        // 新族的习惯写法（连字符 / 空格差异）折到登记族
        for (name in listOf("lxgw wenkai mono", "lxgw-wenkai-mono", "lxgw wenkai", "霞鹜文楷等宽")) {
            put(normalize(name), LXGW_WENKAI.id)
        }
        for (name in listOf("maple mono cn", "maple-mono-cn", "maple mono", "maple-mono")) {
            put(normalize(name), MAPLE_MONO.id)
        }
        for (name in listOf("saitamaar", "saitama ar")) {
            put(normalize(name), SAITAMAAR.id)
        }
    }

    /** 族名归一：全角/半角空白一律删掉，ASCII 折小写。 */
    private fun normalize(name: String): String =
        buildString(name.length) {
            for (ch in name) {
                if (ch.isWhitespace() || ch == '　') continue
                append(if (ch in 'A'..'Z') ch + 32 else ch)
            }
        }

    /** 按 id 查（`sarasa` / `lxgw` / `maple` / `saitamaar` / `misaki` / `fusion` / `ark` / `unifont`）。 */
    fun byId(id: String): BundledFontFamily? = ALL.firstOrNull { it.id == id }

    /** 按族名精确查（大小写不敏感，空白已归一）。 */
    fun byFamily(family: String): BundledFontFamily? {
        val key = normalize(family)
        return ALL.firstOrNull { normalize(it.family) == key }
    }

    /**
     * 配置里的字体名 → 实际使用的族。**永不返回 null、永不抛。**
     *
     * 优先级：登记族名 > 别名表 > 默认族。
     */
    fun resolve(requested: String?): BundledFontFamily {
        if (requested.isNullOrBlank()) return default
        byFamily(requested)?.let { return it }
        ALIASES[normalize(requested)]?.let { return byId(it) ?: default }
        return default
    }
}
