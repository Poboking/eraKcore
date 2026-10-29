package com.eratw.port.core.font

/**
 * CONTRACT-M4-C §3 sidecar 的**唯一解析实现**（GLYPH-KEY-SIZE / M4-REALCHECK ②③）。
 *
 * ## 为什么在 `:core-vm` 而不是 `:render`
 *
 * `GGETTEXTSIZE` 与 `GDRAWTEXT` 在 `:core-vm` 里就要按真实 advance 量宽，而依赖方向是
 * `:render -> :core-vm`（单向，core 不许反向 import 渲染层）。度量表若两处各写一份解析器，
 * 就会像 WIDTH-SINGLE 那条口径分叉一样各自漂移，所以解析器与字号/字体表统一落在这里，
 * `:render` 的 `com.eratw.port.render.glyph.TextMetrics` 反过来调用本包、只加「像素 ↔ 半角格」
 * 的换算与折行口径（契约 §0.5：两条宽度口径不许互相冒充）。
 *
 * 原版每个方法开头都有 `Config.TextDrawingMode == WINAPI → CodeEE` 的守卫
 * （如 `FunctionMethodCreator.cs:6173-6176`）。移植版只有软件缓冲一条路径（等价于 TEXTRENDERER），
 * 故不再复刻该分支。
 */

/**
 * sidecar 文本读取口。
 *
 * 同一个解析器要服务两个模块，而两边拿文件的通道不同：`:core-vm` 只能经宿主
 * [com.eratw.port.core.host.FileOps]（core 不许假定工作目录），`:render` 侧成品在
 * render 模块 resources 下的 fonts 目录，只能走 classpath。故把"读一个路径"抽成这个口，
 * 解析与缓存只有一份实现（`:render -> :core-vm` 单向依赖，反过来不行）。
 */
fun interface FontSidecarReader {

    /** 读不到（文件不存在、宿主未接线）返回 null；读到了就原样返回文本。 */
    fun read(path: String): String?
}

/**
 * §3 sidecar 的格式违规 —— **未知构造一律抛，并说明行号**（契约 §3 末条：不许静默给 0）。
 *
 * 类型选 [IllegalArgumentException] 而不是 CodeEE：这是"随包的度量表坏了"（资产/构建错误），
 * 不是脚本用错函数。运行期测量入口只把**缺失**当退化处理，不吞格式错误之外的信号 ——
 * 见 [FontSidecarCatalog.load] 与 `GraphicsMethods.measureTextPixels` 的口径分工。
 */
class FontSidecarFormatException(
    val origin: String,
    val lineNo: Int,
    val rawLine: String,
    reason: String,
) : IllegalArgumentException("字体度量 sidecar \"$origin\" 第 $lineNo 行畸形：$reason（原行「$rawLine」）")

/**
 * 一份 §3 sidecar 的解析产物：家族名 + unitsPerEm + 逐码位的设计单元度量。
 *
 * 数值一律是**字体设计单元**（不是像素、不是半角格），换算只发生在 [designToPixels]：
 * `design * size / unitsPerEm`（契约 §3 第 3 条「消费者按 design * size / unitsPerEm 换算，
 * 宽度模型不许在读取时二次近似」）。`#size` 只是生成时的参考字号，不参与换算。
 *
 * 实测对照（本机 fontTools 4.67.0 读语料 `eratw-画蛇添足版eratw4.980/font/Saitamaar.ttf`）：
 * name 表 nameID1 = `Saitamaar`、`head.unitsPerEm` = 1280、hmtx 给出
 * 空格 U+0020 = 400、数字 U+0030 = 640、拉丁 U+0041 = 800、U+3042 = 1200、U+4E2D = 1280（lsb 80），
 * 与仓内 `tools/fontsubset/probe-report.txt:32-59` 逐项一致 —— 比例宽度是真的不等宽
 * （M4-REALCHECK ②：不许为凑 1:2 网格伪造）。
 *
 * @property generator `#generator` 的值（溯源用，可缺省；本类不使用）
 * @property family `#family` 的值 —— name 表家族名**原样**
 * @property unitsPerEm `head.unitsPerEm`，必须为正
 * @property referenceSizePx `#size` 的值（可缺省；换算不用它）
 * @property advances 码位 → advance（设计单元）；cmap 没有的码位**没有条目**（§3：缺行即缺字）
 * @property lsbs 码位 → left side bearing（设计单元，可为负）
 */
class FontAdvanceTable private constructor(
    val generator: String?,
    val family: String,
    val unitsPerEm: Int,
    val referenceSizePx: Int?,
    val advances: Map<Int, Int>,
    val lsbs: Map<Int, Int>,
) {

    /** 表覆盖的码位集合（升序，读入序 = §3 要求的码点升序）。 */
    val codePoints: Set<Int> get() = advances.keys

    /** cmap 是否覆盖该码位（false = §3 的"缺行"，由调用方走回退链）。 */
    fun covers(codePoint: Int): Boolean = advances.containsKey(codePoint)

    /** advance（设计单元）；缺行返回 null。 */
    fun advanceDesign(codePoint: Int): Int? = advances[codePoint]

    /** lsb（设计单元）；缺行返回 null。 */
    fun lsbDesign(codePoint: Int): Int? = lsbs[codePoint]

    /** 设计单元 → 像素：`design * size / unitsPerEm`（Float，与原版 float 中间量同精度）。 */
    fun designToPixels(designUnits: Int, sizePx: Long): Float =
        designUnits.toFloat() * sizePx.toFloat() / unitsPerEm.toFloat()

    /** 该码位在 [sizePx] 字号下的 advance（像素，未截断）；缺行返回 null。 */
    fun advancePixels(codePoint: Int, sizePx: Long): Float? {
        val design = advances[codePoint] ?: return null
        return designToPixels(design, sizePx)
    }

    /** 该码位在 [sizePx] 字号下的 lsb（像素，未截断）；缺行返回 null。 */
    fun lsbPixels(codePoint: Int, sizePx: Long): Float? {
        val design = lsbs[codePoint] ?: return null
        return designToPixels(design, sizePx)
    }

    override fun toString(): String =
        "FontAdvanceTable(family=$family, unitsPerEm=$unitsPerEm, referenceSizePx=$referenceSizePx, glyphs=${advances.size})"

    companion object {
        /** §3 的文件名后缀：`fonts/<family-slug>-advance.txt`。 */
        const val SIDECAR_SUFFIX: String = "-advance.txt"

        /** §3 认得的指令名（一字不差）；其余 `#标识符` 视为未知构造 → 抛。 */
        private val DIRECTIVE_KEYS = setOf("generator", "family", "unitsPerEm", "size")

        /**
         * 严格解析 §3 sidecar。
         *
         * 规则（逐条对应契约 §3）：
         * - `#` 开头且紧跟标识符的行是**指令**，只允许 generator / family / unitsPerEm / size，
         *   未知指令名或重复即抛；`#` 后接空格或非标识符字符的行按注释忽略。
         * - 数据行恰好三个空白分隔字段：码点（十六进制**大写**、无前缀）、advance、lsb（十进制整数，可负）。
         * - 码点必须严格递增（§3「按码点升序」，重复即抛）、无空行（末行尾随换行不算行）。
         * - 必需指令：family（非空）、unitsPerEm（正整数）；generator 与 size 只是溯源信息，允许缺省
         *   （手搓夹具/老表可以没有它们，消费不依赖）。
         *
         * @param origin 出错文案里带的来源（路径或 "<inline>"），便于首差定位
         * @throws FontSidecarFormatException 任何未知构造（带行号与原行）
         */
        fun parse(text: String, origin: String = "<inline>"): FontAdvanceTable {
            val lines = splitLines(text)
            var generator: String? = null
            var family: String? = null
            var unitsPerEm: Int? = null
            var referenceSizePx: Int? = null
            val advances = LinkedHashMap<Int, Int>()
            val lsbs = LinkedHashMap<Int, Int>()
            var previousCodePoint = -1
            for ((index, rawLine) in lines.withIndex()) {
                val lineNo = index + 1
                if (rawLine.startsWith("#")) {
                    val token = rawLine.substring(1).takeWhile { it != ' ' && it != '\t' }
                    if (token.isEmpty() || !isIdentifierToken(token)) {
                        continue
                    }
                    if (token !in DIRECTIVE_KEYS) {
                        throw FontSidecarFormatException(
                            origin, lineNo, rawLine,
                            "未知构造 #$token（§3 只允许 #generator / #family / #unitsPerEm / #size）",
                        )
                    }
                    val value = rawLine.substring(1 + token.length).trim()
                    when (token) {
                        "generator" -> {
                            if (generator != null) {
                                throw FontSidecarFormatException(origin, lineNo, rawLine, "#generator 重复")
                            }
                            generator = value
                        }
                        "family" -> {
                            if (family != null) {
                                throw FontSidecarFormatException(origin, lineNo, rawLine, "#family 重复")
                            }
                            if (value.isEmpty()) {
                                throw FontSidecarFormatException(origin, lineNo, rawLine, "#family 的家族名为空")
                            }
                            family = value
                        }
                        "unitsPerEm" -> {
                            if (unitsPerEm != null) {
                                throw FontSidecarFormatException(origin, lineNo, rawLine, "#unitsPerEm 重复")
                            }
                            unitsPerEm = positiveInt(value)
                                ?: throw FontSidecarFormatException(
                                    origin, lineNo, rawLine, "unitsPerEm 必须是正十进制整数（实际「$value」）",
                                )
                        }
                        else -> {
                            if (referenceSizePx != null) {
                                throw FontSidecarFormatException(origin, lineNo, rawLine, "#size 重复")
                            }
                            referenceSizePx = positiveInt(value)
                                ?: throw FontSidecarFormatException(
                                    origin, lineNo, rawLine, "size 必须是正十进制整数（实际「$value」）",
                                )
                        }
                    }
                    continue
                }
                if (rawLine.isEmpty()) {
                    throw FontSidecarFormatException(origin, lineNo, rawLine, "不允许空行（§3：每行一个码点、无空行）")
                }
                val fields = splitFields(rawLine)
                if (fields.size != 3) {
                    throw FontSidecarFormatException(origin, lineNo, rawLine, "数据行必须是「码点 advance lsb」三个字段：实际 ${fields.size} 个")
                }
                val codePoint = parseCodePoint(fields[0])
                    ?: throw FontSidecarFormatException(
                        origin, lineNo, rawLine, "码点必须是十六进制大写字面量且不超过 U+10FFFF（实际「${fields[0]}」）",
                    )
                if (codePoint <= previousCodePoint) {
                    throw FontSidecarFormatException(
                        origin, lineNo, rawLine, "码点必须严格升序且不重复（上一行 U+${previousCodePoint.toString(16).uppercase()}，本行 U+$codePoint）",
                    )
                }
                val advance = parseDesignInt(fields[1])
                    ?: throw FontSidecarFormatException(origin, lineNo, rawLine, "advance 必须是十进制整数（实际「${fields[1]}」）")
                val lsb = parseDesignInt(fields[2])
                    ?: throw FontSidecarFormatException(origin, lineNo, rawLine, "lsb 必须是十进制整数（实际「${fields[2]}」）")
                advances[codePoint] = advance
                lsbs[codePoint] = lsb
                previousCodePoint = codePoint
            }
            val resolvedFamily = family
                ?: throw FontSidecarFormatException(origin, 0, "", "缺少必需指令 #family")
            val resolvedUnits = unitsPerEm
                ?: throw FontSidecarFormatException(origin, 0, "", "缺少必需指令 #unitsPerEm")
            return FontAdvanceTable(
                generator = generator,
                family = resolvedFamily,
                unitsPerEm = resolvedUnits,
                referenceSizePx = referenceSizePx,
                advances = advances,
                lsbs = lsbs,
            )
        }

        /** 按 LF 切行并吃掉 CRLF 的 CR；末尾换行不额外造一个空行。 */
        private fun splitLines(text: String): List<String> {
            val out = ArrayList<String>()
            var start = 0
            while (start <= text.length) {
                val nl = text.indexOf('\n', start)
                if (nl < 0) {
                    if (start < text.length) {
                        out += stripCr(text.substring(start))
                    }
                    break
                }
                out += stripCr(text.substring(start, nl))
                start = nl + 1
                if (start == text.length) {
                    break
                }
            }
            return out
        }

        private fun stripCr(s: String): String = if (s.endsWith("\r")) s.substring(0, s.length - 1) else s

        private fun splitFields(line: String): List<String> {
            val out = ArrayList<String>(3)
            val current = StringBuilder()
            for (ch in line) {
                if (ch == ' ' || ch == '\t') {
                    if (current.isNotEmpty()) {
                        out += current.toString()
                        current.setLength(0)
                    }
                } else {
                    current.append(ch)
                }
            }
            if (current.isNotEmpty()) {
                out += current.toString()
            }
            return out
        }

        private fun isIdentifierToken(token: String): Boolean {
            val first = token[0]
            if (first !in 'a'..'z' && first !in 'A'..'Z') {
                return false
            }
            return token.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        }

        /** 码点字段：十六进制大写、无前缀、0..U+10FFFF；不合法返回 null。 */
        private fun parseCodePoint(field: String): Int? {
            if (field.isEmpty() || field.length > 6) {
                return null
            }
            for (ch in field) {
                if (ch !in '0'..'9' && ch !in 'A'..'F') {
                    return null
                }
            }
            val value = field.toInt(16)
            return if (value <= 0x10FFFF) value else null
        }

        /** 设计单元字段：十进制整数（可带负号）；不合法或超 Int 范围返回 null。 */
        private fun parseDesignInt(field: String): Int? = field.toIntOrNull()

        /** 正十进制整数（无符号、无空白）；不合法返回 null。 */
        private fun positiveInt(field: String): Int? {
            if (field.isEmpty() || field.any { it !in '0'..'9' }) {
                return null
            }
            val value = field.toIntOrNull() ?: return null
            return if (value > 0) value else null
        }
    }
}

/**
 * 每 VM 一份的 sidecar 目录：按家族名查表、缺失走替代、原因留在原地。
 *
 * ## 回退链
 *
 * 请求名（空名先归一到 [defaultFamily]，spec: FontFactory.cs:13-17）→ [substituteFamily]
 * （装配层声明的"随包子集"）→ 没有。`chainFor` 返回**有序**候选表，调用方逐码位在链上取
 * advance；整条链都取不到才退化。两条实现事实要写清（都是**本端口的装配决策**）：
 * - 候选名去重后按顺序逐跳 `load`，同一份表既挂在请求名下又挂在替代族名下时**只出现一次**
 *   （按表对象身份去重，`put` 带 alias 就会撞到这种情况）。
 * - 替代族**无条件**排在第二跳：请求族自己有表时它也在链里，用来补请求族缺行的码位
 *   ——不是"请求族整族无表才参与"。
 *
 * TODO(verify)：`Config.cs:501-523` 支撑不了上面第二条，也支撑不了"逐请求名"这件事。那一段只写了
 * **开局一次**的替换：`SetupDefaultFont` 先 `CheckFont(FontName)`（调用 :504，判据 :525-534），
 * 失败才把内嵌的 BIZUDGothic 解到临时目录、`GlobalStatic.Pfc.AddFontFile`（:520），
 * 并把 `Config.FontName` **改写**成替换族（:521-522）——即触发条件是"配置里那个默认族不可用"，
 * 替换之后全局只剩一个族。脚本请求一个系统没装的族时，原版什么都不做：字符串直接喂给
 * `new Font(name, size, style, Pixel)`（spec: FunctionMethodCreator.cs:6584），兜底宽度由 GDI+
 * 内部决定，Emuera 这层看不见、反编译也读不出来。
 * 卡点：基准里没有可对拍的测量读数（`fixtures/golden/baseline/pc-golden-root.txt:273-281` 只有
 * FONT / FONT_DEFAULT / CHKFONT，:646-648 的 GDRAWTEXT 只记返回值 1，GGETTEXTSIZE 一条都没有），
 * 所以"替代族参与范围"要收敛成"仅整族无表"还是保持"逐码位补洞"，必须等 PC 实机对拍或平台字体
 * 兜底取证 → 属组长接线。本波按装配决策保留现状，并把分歧写在上面，不许当成原版结论读。
 *
 * ## 确定性
 *
 * 缓存用 `HashMap<String, FontAdvanceTable?>` 但**只按键查、从不读迭代序**，候选顺序由
 * 上面那条链（显式列表）决定，所以同输入必同输出；不读时间、不用随机。
 *
 * ## 缺失 vs 畸形（两种处理，都不静默）
 *
 * - 缺文件 / 没有读取口 → 记原因、返回空链，调用方退化（契约 §2：不许抛、不许冒充真实度量）。
 * - 表存在但格式坏 → [FontAdvanceTable.parse] 抛 [FontSidecarFormatException]（带行号），
 *   本类把它收成原因文本并视同为"这一跳没有表"，因为脚本层不该被随包资产的构建错误打死；
 *   原因文本保留行号，首差时能一眼看出是哪一行坏了（不许静默给 0）。
 */
class FontSidecarCatalog(
    /** 空字体名的归一目标（原版 `Config.FontName`，spec: FontFactory.cs:13-17）。 */
    var defaultFamily: String = DEFAULT_FAMILY,
    /** sidecar 目录名（§3：`fonts/<family-slug>-advance.txt`）。 */
    val sidecarDir: String = DEFAULT_SIDECAR_DIR,
    /**
     * 替代族（装配层声明的"随包子集"家族名）；null = 不替换。
     * 它是**装配期**的一个名字，不是原版结论 —— 参与范围见类文档的 TODO(verify)。
     */
    var substituteFamily: String? = null,
    /** 读取口；null = 本 VM 没接文件能力（一律按缺失退化）。 */
    var reader: FontSidecarReader? = null,
) {

    private val resolved = HashMap<String, FontAdvanceTable?>()
    private val missReasons = HashMap<String, String>()

    /** 最近一次"没拿到表"的可读原因（验收/首差从这里读，不许把退化当真实度量宣称）。 */
    var lastReason: String? = null
        private set

    /** 直查某家族上次留下的原因。 */
    fun reasonFor(family: String): String? = missReasons[family]

    /** §3 的路径口径：`<sidecarDir>/<slug>-advance.txt`。 */
    fun sidecarPath(family: String): String = "$sidecarDir/${slugOf(family)}${FontAdvanceTable.SIDECAR_SUFFIX}"

    /**
     * 登记一份表（装配期/测试直灌，绕开文件读取）。
     *
     * 登记之后 [chainFor] 的每一跳都直接从缓存命中这张表，**不再查 [reader]**（读取口只服务
     * 没灌过的名字）——所以"只有夹具"的世界也能把回退链跑通，不必随包真有成品文件。
     *
     * @param alias 额外别名（例如脚本侧用的 "黑体" 实际由随包子集代画，就把子集表同时挂到两名下）
     */
    fun put(table: FontAdvanceTable, alias: String? = null): FontAdvanceTable {
        resolved[table.family] = table
        missReasons.remove(table.family)
        if (alias != null && alias.isNotEmpty() && alias != table.family) {
            resolved[alias] = table
            missReasons.remove(alias)
        }
        return table
    }

    /** 单跳便捷入口（= [chainFor] 的第一张表）。 */
    fun tableFor(fontName: String): FontAdvanceTable? = chainFor(fontName).firstOrNull()

    /**
     * 这个名字**自己**能不能解析出一张表（不含替代族那一跳）。
     *
     * `GSETFONT` 用它判断「请求名可解析」，从而决定 [fallbackFamily] 要不要顶上
     * ——口径对齐原版 `GSETFONT` 的两分支：先逐个精确比对 `GlobalStatic.Pfc.Families`
     * （spec: FunctionMethodCreator.cs:6385-6396，Pfc = `PrivateFontCollection`，
     * spec: GlobalStatic.cs:38，即**游戏自己 AddFontFile 进去的那批**，spec: Program.cs:129-141、
     * Config.cs:509-522），命中就走 `Font(FontFamily, ...)`；一个都不命中才落到
     * 字符串构造 `Font(strValue, ...)`（spec: :6400），后者由 GDI+ 自己解析系统字体
     * 并在解析失败时**静默换族**（spec: GraphicsImage.cs:60 回读的是 `font.Name`，
     * 即解析后的名字；2026-10-21 本机 System.Drawing 实测
     * `new Font("NoSuchFamilyZZZ", 16f, Regular, Pixel).Name` = `Microsoft Sans Serif`）。
     *
     * 与 [tableFor] 的区别：[tableFor] 会顺带回退到替代族那张表，因而对任何名字都可能返回非空，
     * 不能用来判「可解析」；本函数只看这个名字这一跳。空名先归一到 [defaultFamily]
     * （spec: FontFactory.cs:13-17），与 [chainFor] 同一条归一。
     */
    fun hasOwnTable(fontName: String): Boolean = load(if (fontName.isEmpty()) defaultFamily else fontName) != null

    /**
     * 请求名解析不到时，移植侧**实际拿去画**的那个族名 —— 移植版的「GDI+ 静默换族」。
     *
     * 取替代族（装配层声明的随包子集），没有替代族时取默认族。原版在这条分支上交给
     * GDI+ 的兜底族（基准环境实测 `Microsoft Sans Serif`），移植版不分发微软版权字体，
     * 兜底的是自己的子集 ⇒ 两者**不同族但同构**（`GGETFONT` 的读数差异登记在
     * `TextMeasureAuditTest` 的 GGETFONT 一节，属平台字体表差异，不是移植口径错）。
     */
    val fallbackFamily: String
        get() {
            val sub = substituteFamily
            return if (sub != null && sub.isNotEmpty()) sub else defaultFamily
        }

    /**
     * 回退链上**实际存在**的表，按优先级排列：请求族 → 替代族（替代族无条件排第二跳，
     * 用于补请求族缺行的码位；同一张表被两个名字命中时只留一次）。
     * 与原版"开局一次替换默认族"的分歧见类文档的 TODO(verify)。
     * 空列表 = 整条链都没拿到表，调用方必须退化并把 [lastReason] 带出去。
     */
    fun chainFor(fontName: String): List<FontAdvanceTable> {
        val names = ArrayList<String>(2)
        names += if (fontName.isEmpty()) defaultFamily else fontName
        val sub = substituteFamily
        if (sub != null && sub.isNotEmpty() && sub != names[0]) {
            names += sub
        }
        val out = ArrayList<FontAdvanceTable>(names.size)
        for (name in names) {
            val table = load(name)
            if (table != null && !out.contains(table)) out += table
        }
        return out
    }

    /** 丢掉已解析的表与原因（RESTART / 换宿主文件口之后重建）。 */
    fun clearCache() {
        resolved.clear()
        missReasons.clear()
        lastReason = null
    }

    private fun load(family: String): FontAdvanceTable? {
        if (resolved.containsKey(family)) {
            return resolved[family]
        }
        val r = reader
        if (r == null) {
            // 没有读取口时**不写缓存**：宿主稍后接上还能再查（缓存只记"查过且没有"）。
            lastReason = "字体度量 sidecar 未接入：本 VM 没有文件读取口（reader == null），按 §3 缺失退化"
            return null
        }
        val path = sidecarPath(family)
        if (!isContainedSidecarPath(path, sidecarDir)) {
            // 路径越界守卫：family 是脚本给的自由串（FONT / GDRAWTEXT 的 fontname），slug 规则
            // 之外还可能出现「..」「/」这类成分；[FontSidecarReader] 是任意宿主缝（目录树、zip、
            // classpath），core 不能假定读取口自己会做 canonical 归一（目录形态的 `RootedFileOps`
            // 在 `resolvePath` 里 normalize + startsWith 拒越界，BootHosts.kt:409-415，但那只是
            // 其中一种读取口）。越界条目按「缺失」处理并告警，不去碰读取口。
            missReasons[family] = "sidecar 路径越界：family「$family」归一出非法条目路径 $path，按缺失退化"
            lastReason = missReasons[family]
            resolved[family] = null
            return null
        }
        val text = try {
            r.read(path)
        } catch (e: Exception) {
            missReasons[family] = "sidecar 读取抛错：$path（${e::class.simpleName}: ${e.message}）"
            lastReason = missReasons[family]
            resolved[family] = null
            return null
        }
        if (text == null) {
            missReasons[family] = "sidecar 缺失：$path 不存在（c-fontsub 成品未随包，或该家族没有度量表）"
            lastReason = missReasons[family]
            resolved[family] = null
            return null
        }
        val table = try {
            FontAdvanceTable.parse(text, origin = path)
        } catch (e: FontSidecarFormatException) {
            missReasons[family] = "sidecar 畸形：${e.message}"
            lastReason = missReasons[family]
            resolved[family] = null
            return null
        }
        resolved[family] = table
        if (table.family != family && !resolved.containsKey(table.family)) {
            resolved[table.family] = table
        }
        return table
    }

    companion object {
        /** §3 的目录：render 模块 resources 下的 fonts 目录（classpath 侧同样叫 fonts）。 */
        const val DEFAULT_SIDECAR_DIR: String = "fonts"

        /**
         * 空字体名的归一目标取**基准环境**的 `Config.FontName`
         * （spec: `emuera.config:21` FONT NAME:黑体、`fixtures/golden/baseline/pc-golden-root.txt:279-280`）。
         * 注意与 `ConfigData.cs:65` 的出厂默认「ＭＳ ゴシック」不同 —— 后者是没读配置时的值。
         */
        const val DEFAULT_FAMILY: String = "黑体"

        /**
         * 条目路径是否被钉在 [sidecarDir] 内（canonical 校验的 core 侧形态）：
         * 必须恰好是「`sidecarDir` 一层 + 单个条目名」，不允许绝对路径、`..` 段、空段与
         * 反斜杠（`/` 与 `\` 都按分隔符算，原版 Windows 路径口径见 `HostMethods.getValidPath`）。
         * 这是把「解包/读取前先归一、越界即拒」钉在**生成侧**：路径由本类拼出，越界只可能
         * 来自 family 串，必须在交给读取口之前拒绝。
         */
        fun isContainedSidecarPath(path: String, sidecarDir: String = DEFAULT_SIDECAR_DIR): Boolean {
            if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\")) return false
            if (path.contains('\\')) return false
            val dirPrefix = if (sidecarDir.endsWith("/")) sidecarDir else "$sidecarDir/"
            if (!path.startsWith(dirPrefix)) return false
            val entry = path.removePrefix(dirPrefix)
            if (entry.isEmpty() || entry.contains('/') || entry.contains('\\')) return false
            if (entry == "." || entry == ".." || entry.startsWith(".")) return false
            return entry.endsWith(FontAdvanceTable.SIDECAR_SUFFIX)
        }

        /**
         * 家族名 → §3 文件名里的 slug。
         *
         * §3 只写了 `<family-slug>-advance.txt`，没写 slug 的折叠规则，这里定死一条并让装配层
         * 优先用 [put] 直挂表（不依赖文件名）：ASCII 字母小写；空白、`-`、`/`、`\` 都算分隔符，
         * 连续分隔符折成一个 `-`，首尾 `-` 去掉；其余字符（含 CJK 与 `.`）原样保留。
         * 路径分隔符折成 `-` 是**遍历守卫的一半**：family 里即便混进「a/b」也只会落成
         * sidecar 目录下的 `a-b-…`，另一半（`..` 段拒绝）见 [isContainedSidecarPath] 与
         * [FontSidecarCatalog.load] 的越界分支。
         * TODO(verify)：c-fontsub 的 slug 必须与本规则一致，否则成品文件名对不上；由组长对齐
         * （render 模块 resources 下的 fonts 目录归 c-fontsub，本路不许碰）。
         */
        fun slugOf(family: String): String {
            val out = StringBuilder(family.length)
            var pendingDash = false
            for (ch in family) {
                if (ch.isWhitespace() || ch == '　' || ch == '-' || ch == '/' || ch == '\\') {
                    pendingDash = out.isNotEmpty()
                } else {
                    if (pendingDash) {
                        out.append('-')
                        pendingDash = false
                    }
                    out.append(if (ch in 'A'..'Z') ch + 32 else ch)
                }
            }
            var end = out.length
            while (end > 0 && out[end - 1] == '-') {
                end--
            }
            return out.substring(0, end)
        }
    }
}

/**
 * `GDRAWTEXT` 在缓冲自带字体为空（`GraphicsImage.Fnt == null`）时的回退字号（px）。
 * 原版的字面量就是 100：`new Font(Config.FontName, 100f, Console.StringStyle.FontStyle, GraphicsUnit.Pixel)`
 * （spec: FunctionMethodCreator.cs:6518-6523，字面量在 :6522）。
 *
 * 这条分支的三个来源要分开读，别当成一份：
 * - **字号** = 字面量 100（不是 `Config.FontSize`，也不是缓冲里那个没设上的字号）；
 * - **字体名** = `Config.FontName`（配置项，spec: Config.cs:333 写入、`ConfigData.cs:65` 出厂默认
 *   「ＭＳ ゴシック」，基准环境被语料 `emuera.config:21` 覆盖成黑体），
 *   **不是** `Console.StringStyle.Fontname`（控制台现用字体，会被 `SETFONT` 语句改掉）；
 * - **样式** = `Console.StringStyle.FontStyle`（控制台**现用**样式位，这一项确实读控制台）。
 *
 * 移植侧的退化网格在这条分支上 1 半角格 = `100 / 2 = 50` px；2026-10-21 本机 GDI+ 实测
 * 黑体 100 字号的半角步进正是 50（`A` / `a` / `0` 各 50，全角 100）⇒ 退化网格与 PC 实测同值。
 */
const val FALLBACK_MEASURE_FONT_SIZE: Long = 100L

/**
 * `store.aux` 里 §3 sidecar 目录的槽名（命名规约见 RuntimeAux KDoc）。
 *
 * 公开它是因为装配层必须能**预先灌表**：Android 上度量表来自 assets，宿主用
 * `store.aux.slot(SLOT_FONT_SIDECAR) { FontSidecarCatalog() }.put(表, alias = 脚本请求名)`
 * 挂好之后，`GGETTEXTSIZE` / `GDRAWTEXT` 就按真实度量走，不必等 [com.eratw.port.core.host.FileOps]。
 */
const val SLOT_FONT_SIDECAR: String = "fontsidecar"
