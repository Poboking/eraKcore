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
 *   只作溯源信息，渲染字号仍由 [com.eratw.port.render.glyph.CellMetrics] 决定。
 * @property coverageHit 该子集对 `tools/fontsubset/required-codepoints.txt`（8076 码点）的命中数
 * @property coverageTotal 闭包总码点数（= 8076）
 * @property occurrenceCoverage 按**语料实际出现次数**加权的覆盖率（0..1）。
 *   这是「读起来卡不卡」的真读数 —— 种类覆盖会骗人（见类文档）。
 * @property occurrenceMissing 按出现次数算的缺字次数（越少越好）
 * @property selectable 是否进设置页的「正文字体」选择列表。
 *   当前五族全部可选（含 Saitamaar —— 它既是默认正文族，也用于 `SETFONT` 字符画）。
 * @property note 一行说明（来源/许可/宽度口径），进设置页副标题与日志。
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
 * 完全不参与选族（`CanvasConsoleView.kt:65-67` 与 `AndroidGlyphMaskHost.kt:127-134` 的
 * TODO(verify) 就记着这件事）。而 Saitamaar 是**字符画用的比例字体**：
 *
 *   * advance 空格 400 / 数字 640 / 拉丁 800 / CJK 1280（upem 1280），整数 1:2 网格对它不成立；
 *   * 对语料码点闭包只覆盖 **5268/8076（65.23%）**，其余 2808 个码点缺字。
 *
 * 于是本表把随包字体扩成**多族**，由 `Config.FontName`
 * （`ConfigKey.FontName`，`ConfigSpec.kt:122` 默认 `ＭＳ ゴシック`）经
 * `GameSession` → `LineBufferConsoleSink.defaultFontName` 一路传下来的族名选族。
 * 默认族在 2026-10-24 曾换成像素族，2026-10-25 又按主人要求切回 Saitamaar（观感优先）；
 * 设置页五族全部可选，覆盖最优的仍是 Fusion / Unifont。
 *
 * ## 候选是怎么筛出来的（读数见 `fonts/font-pack-manifest.txt`）
 *
 * 判据是**三条**：语料码点闭包的**种类**覆盖、按语料**实际出现次数**加权的覆盖
 * （后者才是「读起来卡不卡」）、以及 **advance 是否严格 1:2**（布局侧 `Grid` 的格数口径，
 * PC 基准 199 列 = `EmueraConsole.cs:3255` 的 `GetByteCount`）。逐字体实测：
 *
 * | 字体 | 许可 | upem | 种类覆盖 | 字数覆盖 | advance 主体 | 结论 |
 * |---|---|---|---|---|---|---|
 * | **Fusion Pixel 12px** | OFL-1.1 | 1200 | 7262/8076 = 89.92% | **99.916%** | 600 : 1200 | 备选（**字数覆盖最优**） |
 * | Ark Pixel 12px | OFL-1.1 | 1200 | 7252/8076 = 89.80% | 99.522% | 600 : 1200 | 备选 |
 * | **Unifont 15.0.01** | OFL-1.1 / GPLv2+FE | 1024 | 8034/8076 = 99.48% | **100.000%** | 512 : 1024 | 备选（**零方框**） |
 * | Cubic 11 | OFL-1.1 | 1200 | 6868/8076 = 85.04% | — | 169 字 advance 1300 | **淘汰**：破 1:2 |
 * | DotGothic16 | OFL-1.1 | 1000 | 5156/8076 = 63.84% | — | — | **淘汰**：JIS X 0208 口径 |
 * | **Saitamaar** | 公有领域 | 1280 | 5268/8076 = 65.23% | 95.921% | 比例 | **默认**（主人指定）· 比例按 1:2 兜底 |
 *
 * **默认族怎么定**：2026-10-25 主人指定默认用 Saitamaar（eratw 早期安卓版的正文观感），
 * 因此「覆盖最优」与「默认」不再同一个族。覆盖排序仍按上表：Fusion 是 12px 档最优
 * （99.916%，Ark 99.522% —— Ark 缺 `恋`（87,504 次）、`然`（56,883 次）、`莉`、`紧`、
 * `热` 这类高频字），Unifont 是零方框档。设置页把五族全部列出，玩家可随时切走；
 * 默认只决定配置为空/未知时的落点。
 *
 * 淘汰 Cubic 11 与 DotGothic16 的理由是硬的：前者 169 个码点 advance = 1300（upem 1200），
 * 超出一个全角格，屏上会压到下一格；后者是 JIS X 0208 口径，中文简体字大面积缺失，
 * 覆盖率还不如现役的 Saitamaar 子集。
 *
 * ## 回落链（照 `tools/fontsubset/README.md` §4 的 m-glyph 口径）
 *
 * ```
 * resolve(requested):  requested 空/未登记 -> DEFAULT（当前 Saitamaar）
 *                      requested == "Saitamaar" -> 该族（默认正文，也供 SETFONT 字符画）
 *                      其它任意微软/日文字体名（ＭＳ ゴシック / 黑体 …）-> DEFAULT
 * ```
 *
 * **永不抛、永不返回 null** —— 与 `FontFactory.GetFont` 的 try/catch → null 同向
 * （spec: `FontFactory.cs:52-54`），只是移植侧把 null 提前折成默认族，省掉每个调用点的空判。
 */
object BundledFontFamilies {

    /** 默认正文族 —— 设置页没选、配置值是未知字体名、或族加载失败时都落这里。 */
    const val DEFAULT_FAMILY: String = "Saitamaar"

    /** 字符画族（`SETFONT "Saitamaar"` 的落点，语料里 14 处）；当前同时是默认正文族。 */
    const val ASCII_ART_FAMILY: String = "Saitamaar"

    /** 闭包总码点数（`tools/fontsubset/required-codepoints.txt` 的行数）。 */
    const val REQUIRED_CODEPOINTS: Int = 8076

    /**
     * 美咲ゴシック（eratw 原版观感）：**备选正文族**（2026-10-25 曾为默认，后按主人要求
     * 让位给 Saitamaar；仍保留在设置页）。
     *
     * 来源：`up9cloud/font-misaki` 的 `misaki_gothic.ttf`（美咲フォント，作者 門真なむ，
     * 自由使用）；advance 严格 1:2（upem 2048：半角 1024 / 全角 2048），正好符合布局侧的
     * `Grid` 格数口径。
     *
     * 代价（诚实记录）：对语料码点闭包只覆盖 **5077/8076 = 62.87%**（Fusion 89.92%、
     * Unifont 99.48%），缺的 2999 个码点会走系统字体回退（观感不统一）。
     */
    val MISAKI: BundledFontFamily = BundledFontFamily(
        id = "misaki",
        family = "MisakiGothic",
        asset = "fonts/misaki-gothic.ttf",
        designPx = 8,
        coverageHit = 5077,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.6287,
        occurrenceMissing = 0,
        selectable = true,
        note = "美咲ゴシック（eratw 原版观感）· 严格 1:2 · 缺字走系统回退",
    )

    val FUSION_PIXEL: BundledFontFamily = BundledFontFamily(
        id = "fusion",
        family = "Fusion Pixel 12px",
        asset = "fonts/fusion-pixel-12px-subset.ttf",
        designPx = 12,
        coverageHit = 7262,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.99916,
        occurrenceMissing = 80516,
        selectable = true,
        note = "缝合像素 12px · OFL-1.1 · 半角 600 / 全角 1200",
    )

    val ARK_PIXEL: BundledFontFamily = BundledFontFamily(
        id = "ark",
        family = "Ark Pixel 12px",
        asset = "fonts/ark-pixel-12px-subset.ttf",
        designPx = 12,
        coverageHit = 7252,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.99522,
        occurrenceMissing = 457668,
        selectable = true,
        note = "方舟像素 12px · OFL-1.1 · 点字（盲文）覆盖更全",
    )

    val UNIFONT: BundledFontFamily = BundledFontFamily(
        id = "unifont",
        family = "Unifont",
        asset = "fonts/unifont-subset.ttf",
        designPx = 16,
        coverageHit = 8034,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.99999912,
        occurrenceMissing = 84,
        selectable = true,
        note = "GNU Unifont 16px · OFL-1.1 · 只差 emoji/私用区",
    )

    /**
     * Saitamaar 子集（公有领域）：**默认正文族**（2026-10-25 主人指定），同时是
     * `SETFONT "Saitamaar"` 的字符画落点（语料 14 处）。
     *
     * 它是**比例字体**（upem 1280：空格 400 / 数字 640 / 拉丁 800 / CJK 1280），
     * 1:2 网格对它不严格成立；布局侧仍按 `Grid` 的格数排、单字横向缩放进格内。
     * 种类覆盖 5268/8076 = 65.23%，字数覆盖 95.921%（缺 3,908,779 次）：
     * 默认族是观感选择，覆盖率不是最优 —— 设置页可切到 Fusion/Unifont。
     */
    val SAITAMAAR: BundledFontFamily = BundledFontFamily(
        id = "saitamaar",
        family = ASCII_ART_FAMILY,
        asset = "fonts/saitamaar-subset.ttf",
        designPx = 16,
        coverageHit = 5268,
        coverageTotal = REQUIRED_CODEPOINTS,
        occurrenceCoverage = 0.95921,
        occurrenceMissing = 3908779,
        selectable = true,
        note = "Saitamaar · 公有领域 · 比例字体按 1:2 兜底",
    )

    /** 全部登记族（顺序即设置页展示顺序：默认族在最前）。 */
    val ALL: List<BundledFontFamily> = listOf(SAITAMAAR, MISAKI, FUSION_PIXEL, ARK_PIXEL, UNIFONT)

    /** 可进设置页「正文字体」列表的族。 */
    val SELECTABLE: List<BundledFontFamily> = ALL.filter { it.selectable }

    /** 默认族实体。 */
    val default: BundledFontFamily get() = SAITAMAAR

    /**
     * 等价族名（PC 上的真字体名 / 契约里的历史名）→ 登记族。
     *
     * 为什么要这张别名表：语料里 `SETFONT` / `FONT NAME:` 写的是 PC 字体名
     * （`ＭＳ ゴシック`、`黑体` …），安卓上没有这些字体。全键**小写去空白**后比对，
     * 命中即折到默认族 —— 原版那一步是 GDI 通用替换（`FontFactory.cs:33-48`），
     * 移植侧折到默认族（当前 Saitamaar），语义等价。
     */
    private val ALIASES: Map<String, String> = buildMap {
        // Emuera 默认字体（ConfigSpec.kt:122）与它的几个写法
        for (name in listOf(
            "ｍｓ ゴシック", "ms gothic", "ｍｓ ｐゴシック", "ms p gothic",
            "ｍｓ 明朝", "ms mincho", "黑体", "simhei", "宋体", "simsun",
            "eratu mono sc", "eratw mono sc", "等线", "dengxian",
        )) {
            put(normalize(name), DEFAULT_FAMILY)
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

    /** 按 id 查（`ark` / `fusion` / `unifont` / `saitamaar`）。 */
    fun byId(id: String): BundledFontFamily? = ALL.firstOrNull { it.id == id }

    /** 按族名精确查（大小写不敏感，空白已归一）。 */
    fun byFamily(family: String): BundledFontFamily? {
        val key = normalize(family)
        return ALL.firstOrNull { normalize(it.family) == key }
    }

    /**
     * 配置里的字体名 → 实际使用的族。**永不返回 null、永不抛。**
     *
     * 优先级：登记族名（含 `Saitamaar`）> 别名表 > 默认族。
     */
    fun resolve(requested: String?): BundledFontFamily {
        if (requested.isNullOrBlank()) return default
        byFamily(requested)?.let { return it }
        ALIASES[normalize(requested)]?.let { return byId(it) ?: default }
        return default
    }
}
