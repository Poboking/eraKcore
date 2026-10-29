package com.eratw.port.render.grid

import com.eratw.port.core.host.CellWidth
import com.eratw.port.render.Grid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WIDTH-SINGLE 的收口闸 + CP932 字节数口径的**逐点账**：`:render` 的等宽网格只认一张宽度表，
 * 而这张表在 main@166dbd7 的裁定里被定成 **CP932(Shift-JIS) 字节数**口径 ——
 * `Grid.isFullWidth` 与 `Grid.cellWidth` 全体委托 [CellWidth]，本文件做三件事：
 *
 * 1. **单源自证**：0x0000..0xFFFF 上 `Grid` 的两个宽度入口逐码点等于 [CellWidth]，
 *    以及整串路径与 [CellWidth.of] 同值（[Grid 的宽度判定逐码点等于 CellWidth]、
 *    [整串格数在无代理项时与 CellWidth 同值]）；
 * 2. **与委托前那张表的差集**：只剩 **D 类 3 段共 35 个码点**（C0 控制符 / DEL / U+0080 /
 *    U+00A5）—— 2026-10-21 按 `.NET` 932 码页的 `GetByteCount` 实测把这几个码点从 2 格
 *    改成 1 格，方向唯一（旧 2 格 → 新 1 格），反方向差集必须为空
 *    （[委托只在 D 类 35 个码点上改变读数]）；
 * 3. **记账**：为什么留下的是字节数口径而不是近似表 —— A 类的逐点 `sjis` 字节序列、
 *    B 类的表外字分叉、D 类的单字节控制/未定义区，以及基准里被这些符号撑出来的格数
 *    （[A 类符号在唯一表里记 2 格]、[B 类符号是 CP932 表外字两口径必然分叉]、
 *    [D 类单字节控制符与 U+00A5 记 1 格]、[委托不改变这些基准行的格数]）。
 *
 * ## 唯一表的位置与两条独立证据
 *
 * 表 = [CellWidth.isFullWidth]（`core/host/CellWidth.kt`，判定式「半角仅
 * `U+0000..U+001F`、`U+0020..U+007E`、`U+007F`、`U+0080`、`U+00A5`、`U+FF61..U+FF9F`，
 * 其余一律 2 格」；单码位入口 [CellWidth.ofCodePoint]、整串入口 [CellWidth.of]）。
 * `Grid` 侧只剩换算与 TAB 展开（`render/.../Kernel.kt:148,151,163-167,170`）。
 *
 * - **口径证据**：补齐宽度原样就是字节数 —— `EmueraConsole.cs:3255` 的
 *   `Encoding.GetEncoding("Shift-JIS").GetByteCount(str)`；`STRLEN` 一族同理
 *   `LangManager.cs:15-22,39,74,90`；码页由 `Config.cs:412-416` 定成 932。
 *   EAW 的 Ambiguous 一类（`─ ━ │ ┃ ┼ ■ □ ▲ ▼ ◆ ○ ● ★ ♪ ※ ① ± × ― ‐ “ ” … ℃ № → √ ≠ ≡ ≦ ≧ ¢ § ¬ ° ´`）
 *   在 CP932 里全是**双字节**（逐点见 [CLASS_A_SYMBOLS]），近似表只算 1 格，与该式子直接冲突。
 * - **屏宽证据**：`fixtures/golden/baseline/pc-golden-root.txt:61` 是 **99 个 U+2501**
 *   （`ERB/TITLE.ERB:62` 的 `CUSTOMDRAWLINE ━` 的采集）；`:253` 的 199 个 `=` 把半角步进钉成 9px、
 *   可绘制宽度 1797px（`Config.cs:461-466` 的 `DrawableWidth = WindowX - max(2, FontSize/6)`，
 *   `EmueraConsole.cs:3420-3437` 的 `getStBar` 追加到超宽再逐字删回）。99 字填满 1797px
 *   只能是单字 18px = **2 个半角格**，即 `─` 类符号全角。
 *
 * 委托只统一了**数值表**，没有统一**度量口径**：等宽网格 vs 真机像素仍并存
 * （契约 M4-C 0.5，真机以 `glyph/GlyphCache.measureCells` 为准）；
 * 而 `Grid.cells` 逐**码元**、[CellWidth.of] 逐**码位**，增补平面字符上是已登记的驻留差异
 * （[增补平面字符是已登记的驻留差异]）。
 */
class GridWidthSingleSourceTest {

    /**
     * 委托前 `Grid.isFullWidth` 的判定式，逐字抄自被删掉的那三行
     * （main@M4-C 的 `render/.../Kernel.kt:112-116`；`0x20..0x7E` 写作 `' '..'~'`，
     * `0xFF61..0xFF9F` 写作 `'｡'..'ﾟ'`）。裁定后它与唯一表**同值**，
     * 于是这个参照物从「分歧的另一侧」变成「委托没动过任何码点」的自证基准。
     * 只作参照物，不参与任何生产计算。
     */
    private fun legacyIsFullWidth(ch: Char): Boolean = when (ch) {
        in ' '..'~' -> false
        in '｡'..'ﾟ' -> false
        else -> true
    }

    /** 委托前的单字符格数（同一处旧式的 `if (isFullWidth(ch)) 2 else 1`）。 */
    private fun legacyCellWidth(ch: Char): Int = if (legacyIsFullWidth(ch)) 2 else 1

    /** 委托前的整串格数：同样先展开 TAB（`StringMeasure.cs:57-61`），再逐码元累加。 */
    private fun legacyCells(s: String): Int = Grid.expandTabs(s).sumOf { legacyCellWidth(it) }

    // ------------------------------------------------------------------
    // 委托本身：单源等式
    // ------------------------------------------------------------------

    /** BMP 每个码点都过一遍：`Grid` 的两个宽度入口必须逐点等于 [CellWidth] 的表。 */
    @Test
    fun `Grid 的宽度判定逐码点等于 CellWidth`() {
        for (cp in 0..0xFFFF) {
            val ch = cp.toChar()
            assertEquals(CellWidth.isFullWidth(cp), Grid.isFullWidth(ch), "U+%04X".format(cp))
            assertEquals(CellWidth.ofCodePoint(cp), Grid.cellWidth(ch), "U+%04X".format(cp))
        }
    }

    /** 整串：无代理项时 `Grid.cells` 必须与 [CellWidth.of] 同值（TAB 由 `Grid` 先展开）。 */
    @Test
    fun `整串格数在无代理项时与 CellWidth 同值`() {
        val samples = listOf(
            "", " ", "abc", "0123456789", "あいうえお", "漢字全角Ａ", "ｱｲｳｴｵ", "ﾟ",
            "±10", "※※※已创建Log文件GOLDEN_LOG.txt※※※", "a…b", "①②③", "━".repeat(9),
            "汉化版仅为学习日语的交流之用，禁止对该版本的一切盗用、商用行为",
            "   快Ｃ[..........]     0",
        )
        for (s in samples) {
            assertEquals(CellWidth.of(Grid.expandTabs(s)), Grid.cells(s), s)
        }
        for (cp in 0..0xFFFF) {
            if (cp in 0xD800..0xDFFF) {
                continue // 代理项半不是字符；增补平面由「驻留差异」那条用例专门处理
            }
            if (cp == 0x0009) {
                continue // TAB 在测量前就被展开成 8 空格（StringMeasure.cs:57-61），整串路径必然不同值
            }
            val s = cp.toChar().toString()
            assertEquals(CellWidth.of(s), Grid.cells(s), "U+%04X".format(cp))
        }
    }

    /**
     * TAB 展开仍归 `Grid`（委托没有把它交给核心层）：8 个空格，不做制表位对齐。
     *
     * 裸码元 `U+0009` 现在按 **1 格**（D 类：`.NET` 932 的 C0 区是单字节直传），
     * 委托前那张表给 2 格 —— 这正是 D 类 35 个码点里的一个；整串路径一律先展开成 8 个空格，
     * 故基准 `:18 :20 :22` 那三条含 TAB 的行格数仍是 8。
     */
    @Test
    fun `TAB 展开仍由 Grid 负责且不属于宽度表`() {
        // spec: StringMeasure.cs:57-61 的 s.Replace 成 8 个空格
        assertEquals(8, Grid.expandTabs("\t").length)
        assertEquals(8, Grid.cells("\t"))
        assertEquals(10, Grid.cells("a\tb"))
        assertEquals(8, legacyCells("\t"), "整串口径没变：先展开再数")
        // 裸码元 0x09 在 .NET 932 里是 1 字节（D 类读数），委托前的旧表给 2 格。
        // 变的只有直接对码元调 cellWidth 的路径（`glyph/CellMetrics.kt:94,105`）；
        // 整串路径一律先展开 TAB，故基准 `:18 :20 :22` 那三条含 TAB 的行格数不变（都是 8）。
        assertEquals(1, CellWidth.of("\t"))
        assertEquals(1, Grid.cellWidth('\t'))
        assertEquals(2, legacyCellWidth('\t'), "委托前旧表：落在「其余一律 2 格」")
    }

    // ------------------------------------------------------------------
    // 委托 = 只有 D 类 35 个码点变了读数（方向唯一）
    // ------------------------------------------------------------------

    /**
     * 委托前后**只差 D 类 3 段共 35 个码点**：`U+0000..U+001F`（32 个）、
     * `U+007F..U+0080`（2 个）、`U+00A5`（1 个）。依据是 `EmueraConsole.cs:3255` 的
     * `GetByteCount` 在 `.NET` 932 码页上的实测读数：C0 控制符、DEL、C1 首码点原样单字节直传，
     * `U+00A5` best-fit 回 `0x5C`，四类各 **1 字节** ⇒ 1 格；委托前那张表把它们算进
     * 「其余一律 2 格」⇒ 2 格。方向**唯一**（旧 2 格 → 新 1 格），反方向差集必须为空。
     *
     * 委托那一版（main@166dbd7 之前）这里登记的是 11 个连续块、共 23208 个码点，
     * 全部来自那张 East Asian Wide/Fullwidth 近似表；裁定撤的正是那张表，
     * 所以常量不再保留 —— [divergenceBlocks] 留着，它报红时会把分歧块原样打出来，
     * 谁改动唯一表（往 EAW 方向回去、或加第三张表）都得在这里交代。
     */
    @Test
    fun `委托只在 D 类 35 个码点上改变读数`() {
        assertEquals(
            listOf(0x0000..0x001F, 0x007F..0x0080, 0x00A5..0x00A5),
            divergenceBlocks(legacyHalf = false),
            "旧表 2 格而唯一表 1 格：D 类三段",
        )
        assertEquals(emptyList<IntRange>(), divergenceBlocks(legacyHalf = true), "旧表 1 格而唯一表 2 格：必须为空")
        var same = 0
        for (cp in 0..0xFFFF) {
            val ch = cp.toChar()
            val expect = if (cp in D_CLASS_CODE_POINTS) 1 else legacyCellWidth(ch)
            assertEquals(expect, Grid.cellWidth(ch), "U+%04X".format(cp))
            assertEquals(expect, CellWidth.ofCodePoint(cp), "U+%04X 唯一表同值".format(cp))
            if (legacyIsFullWidth(ch) == CellWidth.isFullWidth(cp)) {
                same++
            }
        }
        assertEquals(35, D_CLASS_CODE_POINTS.size, "D 类码点个数")
        assertEquals(65536 - 35, same, "同结论码点数")
    }

    /** 把「旧表与唯一表结论不同」的码点压成升序连续块；[legacyHalf] 选方向（true 查反方向）。 */
    private fun divergenceBlocks(legacyHalf: Boolean): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        var last = -1
        for (cp in 0..0xFFFF) {
            val o = legacyIsFullWidth(cp.toChar())
            val n = CellWidth.isFullWidth(cp)
            val hit = if (legacyHalf) (!o && n) else (o && !n)
            if (hit) {
                if (start < 0) {
                    start = cp
                } else if (cp != last + 1) {
                    out.add(start..last)
                    start = cp
                }
                last = cp
            }
        }
        if (start >= 0) {
            out.add(start..last)
        }
        return out
    }

    /** C 类对照：委托前后一直同结论的代表码点（ASCII、半角假名两端、全角标点/CJK/谚文/段边界）。 */
    @Test
    fun `C 类对照码点委托前后同格`() {
        for (cp in CLASS_C_STABLE) {
            val ch = cp.toChar()
            assertEquals(legacyCellWidth(ch), Grid.cellWidth(ch), "U+%04X 不该变".format(cp))
            assertEquals(legacyCellWidth(ch), CellWidth.ofCodePoint(cp), "U+%04X 唯一表同值".format(cp))
        }
    }

    // ------------------------------------------------------------------
    // 逐点依据（为什么留下的那张是字节数口径）
    // ------------------------------------------------------------------

    /**
     * A 类：CP932 里**占两个字节**的符号（[CLASS_A_SYMBOLS] 的 `sjis` 列就是实测字节序列，
     * Python 的 cp932 编码器与本机 Temurin 17 的 `windows-31J` 字符集 50 点逐个同值）。
     * 委托前旧表、裁定后的唯一表 [CellWidth.isFullWidth]、以及委托入口 `Grid.cellWidth`
     * 三者在这里给的都是 **2 格**。
     *
     * 为什么必须是 2 而不是被撤掉的近似表给的 1：
     * - `EmueraConsole.cs:3255` 的补齐宽度原样问 `Encoding.GetEncoding("Shift-JIS").GetByteCount(str)`，
     *   这些符号实得 2 字节；`STRLEN` 一族同式（`LangManager.cs:15-22,39,74,90`，码页 932 由
     *   `Config.cs:412-416` 定）；
     * - `pc-golden-root.txt:61`（`ERB/TITLE.ERB:62` 的 `CUSTOMDRAWLINE ━`）是 99 个 U+2501，
     *   而 `:253` 的 199 个 `=` 把半角步进钉成 9px、可绘制宽度 1797px（`Config.cs:461-466`），
     *   99 字填满 ⇒ 单字 18px = 2 个半角格（`getStBar` 的填充循环 `EmueraConsole.cs:3420-3437`）。
     * East Asian Width 把这一类记成 Ambiguous → 1 格，与上面两条同时冲突；
     * 近似表在 core 侧的副本（`core/fn/HtmlMethods.kt:239-251`、`DebugMiscMethods.kt:345`）
     * 还留着自己的分叉，收口属 `core/fn` 包的 owner（BOARD 已登记）。
     */
    @Test
    fun `A 类符号在唯一表里记 2 格`() {
        for (row in CLASS_A_SYMBOLS) {
            val ch = row.codePoint.toChar()
            assertEquals(2, legacyCellWidth(ch), "%s 委托前旧表 2 格".format(row.id()))
            assertEquals(2, CellWidth.ofCodePoint(row.codePoint), "%s 唯一表（CP932 双字节）给 2".format(row.id()))
            assertEquals(2, Grid.cellWidth(ch), "%s 委托入口同值".format(row.id()))
        }
        // 基准 :61 那一行就是这一类的整串后果：99 个 ━ = 198 半角格。
        assertEquals(198, Grid.cells("━".repeat(99)))
    }

    /**
     * B 类：CP932 **编不出来**的符号（表外字；[CLASS_A_SYMBOLS] 的 `sjis` 列里没有它们，
     * Python 的 cp932 编码器对这一串一律抛 `UnicodeEncodeError`）。
     * 裁定后仍按唯一表的「其余一律 2 格」记 **2**，与委托前旧表同值 —— 委托在这里也是无变化。
     *
     * ## 两种口径对表外字必然分叉，2 格只是没有度量时的兜底
     * - **字节数口径实得 1**：`.NET` 的 `Encoding.EncoderFallback` 默认替换回退，
     *   不可编码字符按单个 `?`(0x3F) 计，`EmueraConsole.cs:3255` 对这类符号就量到 1 字节；
     *   本机 Temurin 17 的 `windows-31J` 对 `U+00B7` 编出双字节 `0x8145`、
     *   对其余各点一律编出单个 `0x3F`。
     * - **屏上宽度随字体回退**：原版问 GDI 像素（`StringMeasure.cs:42-72`），码页里没有的字符
     *   由字体找替身字形，宽度从 0（`U+200B`、`U+FEFF` 这类零宽）到全角不等；
     *   基准文本里没有可对拍的读数（`:1` 的第一个码元是 `U+FEFF` BOM，但它不进被消费的文本）。
     * TODO(verify)：替换回退 = 1 字节按 `.NET` 文档推定，本机无 `.NET`/Windows GUI 未实测
     *   （卡点：跑不了 PC 侧 `GetByteCount`）；码页实现本身也不唯一 —— 同一个 `U+00B7`，
     *   Python 的 cp932 抛错、Temurin 17 的 `windows-31J` 却编出双字节 `0x8145`；
     *   别名 `Shift_JIS`（Sun 表）还把 `U+2014` 编成 `0x815C`，而那个字节在 CP932 里属于 `U+2015`。
     * 注：`U+00A5` 不在本类 —— `.NET` 932 实测它 1 字节（best-fit 回 `0x5C`），
     * 已归入 [CLASS_D_SYMBOLS] 的 1 格档。
     */
    @Test
    fun `B 类符号是 CP932 表外字两口径必然分叉`() {
        for (row in CLASS_B_SYMBOLS) {
            val ch = row.codePoint.toChar()
            assertEquals(2, legacyCellWidth(ch), "%s 旧表按「其余一律 2 格」给了 2".format(row.id()))
            assertEquals(2, Grid.cellWidth(ch), "%s 裁定后仍是 2 格（表外字兜底）".format(row.id()))
            assertEquals(2, CellWidth.ofCodePoint(row.codePoint), "%s 唯一表同值".format(row.id()))
            assertTrue(row.codePoint !in CLASS_A_CODE_POINTS, "%s 不能同时属于 A 类".format(row.id()))
            assertTrue(row.codePoint !in D_CLASS_CODE_POINTS, "%s 不能同时属于 D 类".format(row.id()))
        }
        // 零宽那一类（U+200B / U+FEFF）三张表都给不出 0 格：旧表与裁定后的唯一表给 2，
        // 被撤掉的近似表给 1 —— 但字节数口径与屏上宽度都是 0。
        // TODO(verify)：真机量到 0 宽时这里必然中跳，卡点同上面那条（要靠 GlyphCache 才看得见）。
        assertEquals(2, Grid.cells("\u200B"))
        assertEquals(2, Grid.cells("\uFEFF"))
    }

    /**
     * D 类：`.NET` 932 码页里**单字节直传 / best-fit 回单字节**的四档，2026-10-21 实测
     * `Encoding.GetEncoding(932).GetByteCount` 各得 **1 字节** ⇒ 唯一表记 **1 格**：
     * - `U+0000..U+001F`（C0 控制符）：0x00..0x1F 原样直传；
     * - `U+007F`（DEL）：0x7F 直传；
     * - `U+0080`：C1 首码点，932 码页把它映射成单字节 0x80；
     * - `U+00A5`（`¥`）：best-fit 回单字节 `0x5C`（与 ASCII `\` 同字节，故两者同宽）。
     *
     * 委托前那张表按「其余一律 2 格」给 2 —— 这 35 个码点就是
     * [委托只在 D 类 35 个码点上改变读数] 里差集的全部内容。
     * 交叉验证：本机 Temurin 17 的 `windows-31J` 对这四档逐点同样得 1 字节
     * （`U+0080` 走替换回退 `0x3F`，仍是 1 字节）。
     *
     * 为什么不是别的宽度：`EmueraConsole.cs:3255` 的补齐宽度原样问 932 码页的字节数，
     * 字节数就是 1；屏上宽度是 GDI 像素，而控制符在等宽日文字体下不占字形前进宽度、
     * `¥` 与 `\` 是同一个半角字形 —— 两条口径都指向 1。
     */
    @Test
    fun `D 类单字节控制符与 U+00A5 记 1 格`() {
        for (row in CLASS_D_SYMBOLS) {
            val ch = row.codePoint.toChar()
            assertEquals(1, CellWidth.ofCodePoint(row.codePoint), "%s .NET 932 单字节 ⇒ 1 格".format(row.id()))
            assertEquals(1, Grid.cellWidth(ch), "%s 委托入口同值".format(row.id()))
            assertEquals(2, legacyCellWidth(ch), "%s 委托前旧表给 2（差集里的那一档）".format(row.id()))
            assertTrue(row.codePoint !in CLASS_A_CODE_POINTS, "%s 不能同时属于 A 类".format(row.id()))
        }
        // 整串后果：一段纯控制符/`U+00A5` 文本现在按 1 格/字计；
        // ASCII 的 `0x5C` 与 `U+00A5` 同格（旧 TODO「同一个字形两个读数」随之消掉）。
        assertEquals(3, Grid.cells("\u0000\u007F\u0080"))
        assertEquals(2, Grid.cells("\u00A5\\"))
        // TAB 在整串路径先展开成 8 空格，故这里仍是 8 格；裸码元由
        // [TAB 展开仍由 Grid 负责且不属于宽度表] 钉成 1 格。
        assertEquals(8, Grid.cells("\t"))
        assertEquals(4, Grid.cells("\u0001\u0002\u0003\u0004"))
    }

    // ------------------------------------------------------------------
    // 基准行（登记，不吸收）
    // ------------------------------------------------------------------

    /**
     * 委托**不改变**这些基准文本行的格数（半角格）：旧表参照物、`Grid.cells` 与逐码位的
     * [CellWidth.of] 三者逐行相等。这些行是裁定后 `:render` 侧宽度的落点账
     * （下游折行、脏区列跨度、按钮切点都由它们撑出来）。
     *
     * ## 这些行落在基准的哪个区段 —— 为什么必须靠算术而不是靠闸
     * - `:24 :61 :67 :68 :74 :79` 全在**boot 区**：`GoldenNormalizer.normalize`
     *   （`core-vm/src/test/kotlin/com/eratw/port/e2e/GoldenNormalizer.kt:32-41`）从
     *   `=== GOLDEN RUN START ===`（基准第 80 行）起才截取，所以 E2E 对拍**看不到**它们；
     * - `:253` 的 199 个 `=` 落在对拍区内，但纯 `=`/纯 `-` 整行被折成 `LINE(=)`/`LINE(-)` 哨兵
     *   （同文件 `:29-30,51-55`），它只贡献「半角 9px / 可绘制 1797px」这条像素口径，不贡献格数；
     * - `:534` 的 ※ 行确实在对拍区里，但对拍比的是**文本本身**，格数不进差；
     * - 后果：近似表那一路（BMP 23208 个码点各少 1 格）能让 `:core-vm:test`、corpusSmoke、
     *   610/610 行 E2E 一路全绿，main@166dbd7 把表换回来对基准同样不可见。宽度表只能靠
     *   本用例的算术 + 上面两条 C# 证据钉住，这正是它存在的意义。
     */
    @Test
    fun `委托不改变这些基准行的格数`() {
        val titleWarn = "※游玩中遇到的问题可以去问一下单独指导里的哆来咪（游玩时注意温馨提示哦）"
        val menuRow = " ".repeat(177) + "【欢落五子棋】"
        val rows = listOf(
            "━".repeat(99) to 198,          // 基准:61 :68 :74 :79（TITLE.ERB:62,72,85 的 CUSTOMDRAWLINE ━）
            titleWarn to 72,                // 基准:67（TITLE.ERB:71 的 ※ 行）
            "※※※已创建Log文件GOLDEN_LOG.txt※※※" to 39, // 基准:534（6 个 ※ 各 2 格）
            "a…b" to 4,                     // 基准:24 那条长行里的 U+2026
            "\t" to 8,                      // 基准:18 :20 :22 的 TAB（先展开再数，口径不变）
            menuRow to 191,                 // 标题屏最宽行 = 脏区列跨度上限的候选（━ 那行的 198 更大）
        )
        for ((text, expected) in rows) {
            assertEquals(expected, legacyCells(text), "委托前旧表：$text")
            assertEquals(expected, Grid.cells(text), "委托后不该改变格数：$text")
            assertEquals(expected, CellWidth.of(Grid.expandTabs(text)), "逐码位同值：$text")
        }
    }

    // ------------------------------------------------------------------
    // 驻留差异：累加粒度
    // ------------------------------------------------------------------

    /**
     * 增补平面字符是**裁定前就存在的驻留差异**，别当新 bug：
     * `Grid.cells`（`render/.../Kernel.kt:163-167`）按 UTF-16 **码元**累加，裁定后的唯一表把
     * 代理项半也记全角（`core/host/CellWidth.kt:96` 明说「代理项半单独传入按全角 2 计」），
     * 于是一个 U+20000 得 **4 格**；[CellWidth.of]（`:58-67`）按**码位**累加得 **2 格**。
     * 委托那一版用的 East Asian Width 近似表里代理项半各 1 格，「逐码元 2」与「逐码位 2」
     * 只是**巧合相等**，裁定把两者拆开 —— 分歧自证（[委托前后逐码位同值且双向差集为空]）只扫 BMP，
     * 增补平面由本用例逐值钉死。
     *
     * 刻意不改 `Grid` 的累加方式：`grid/LineFolder.kt:258-264` 的 `pieceCellsAt` 与
     * 前缀步进按 `Char` 索引（`:267` 的 `step`），改成按码位会让「段宽」与「前缀宽度」两套
     * 计数在代理对上分叉 —— `Kernel.kt:157-161` 写明了这个取舍，
     * `DisplayRow.cells` 与折行判定必须同式。
     * TODO(verify)：基准文本不含增补字符（`pc-golden-root.txt` 整份 UTF-8 里没有码位大于
     *   U+FFFF 的字符，已实测），所以闸看不见这条；真机折行与宽度以
     *   `glyph/GlyphCache.measureCells` 的像素度量为准（契约 M4-C 0.5）。
     */
    @Test
    fun `增补平面字符是已登记的驻留差异`() {
        val wide = "\uD840\uDC00"                                 // U+20000 CJK 扩展 B
        assertEquals(4, Grid.cells(wide), "逐码元：高代理 2 + 低代理 2")
        assertEquals(2, CellWidth.of(wide), "逐码位：一个码位 2 格")
        assertEquals(4, LineFolder.pieceCellsAt(wide, 0), "折行按码元累加，与 Grid.cells 同式")
        assertEquals(2, LineFolder.step(wide, 0), "步进按码位：2 个 Char")
        assertEquals(8, Grid.cells(wide + wide))
        assertEquals(4, CellWidth.of(wide + wide))
        val emoji = "\uD83D\uDE00"                                // U+1F600（CP932 表外字）
        assertEquals(4, Grid.cells(emoji), "逐码元累加：两个代理项各 2 格")
        assertEquals(2, CellWidth.of(emoji), "按码位：表外字按全角兜底 → 2 格")
        assertTrue(RESIDUAL_NOTE.startsWith("TODO(verify)"), RESIDUAL_NOTE)
        // 反向自证：BMP 上没有这个分叉（逐码位 == 逐码元）
        for (cp in 0..0xFFFF) {
            if (cp in 0xD800..0xDFFF) {
                continue
            }
            if (cp == 0x0009) {
                continue // 同上：整串路径先展开 TAB，8 格 vs 裸码元 2 格不是分叉
            }
            val s = cp.toChar().toString()
            assertEquals(Grid.cells(s), CellWidth.of(s), "U+%04X".format(cp))
        }
    }

    // ------------------------------------------------------------------
    // 登记表
    // ------------------------------------------------------------------

    private companion object {
        /** 一条登记：码点 + 注记串（只用于断言消息与清单可读性）。 */
        class Row(val codePoint: Int, val note: String) {
            fun id(): String = "U+%04X %s".format(codePoint, note)
        }

        /**
         * A 类（CP932 双字节；依据 `EmueraConsole.cs:3255` + `LangManager.cs:15-22`，
         * 码页 932 由 `Config.cs:412-416` 定）。
         * `sjis` 列是 Python 的 cp932 编码器（= Windows-31J，与 `.NET` 的 932 码页同源）
         * 实测字节序列；出现频次是语料 ERB 目录里各 ERB 文件的统计（按 UTF-8 读入，
         * 含注释行，只作量级参考）。
         */
        val CLASS_A_SYMBOLS = listOf(
            Row(0x00A2, "CENT SIGN ¢ sjis 8191"),
            Row(0x00A7, "SECTION SIGN § sjis 8198"),
            Row(0x00AC, "NOT SIGN ¬ sjis 81CA"),
            Row(0x00B0, "DEGREE SIGN ° sjis 818B"),
            Row(0x00B1, "PLUS-MINUS SIGN ± sjis 817D（契约点名）"),
            Row(0x00B4, "ACUTE ACCENT ´ sjis 814C（语料 3797 次）"),
            Row(0x00D7, "MULTIPLICATION SIGN × sjis 817E（注释/文案里用得多，如 view/ScreenComposerTest:32）"),
            Row(0x2010, "HYPHEN ‐ sjis 815D（语料 3222 次）"),
            Row(0x2015, "HORIZONTAL BAR ― sjis 815C（语料 17733 次）"),
            Row(0x201C, "LEFT DOUBLE QUOTATION MARK “ sjis 8167（语料 4840 次）"),
            Row(0x201D, "RIGHT DOUBLE QUOTATION MARK ” sjis 8168（语料 4938 次）"),
            Row(0x2025, "TWO DOT LEADER ‥ sjis 8164"),
            Row(0x2026, "HORIZONTAL ELLIPSIS … sjis 8163（基准:24；语料 844616 次）"),
            Row(0x203B, "REFERENCE MARK ※ sjis 81A6（基准:67 :534；契约点名）"),
            Row(0x2103, "DEGREE CELSIUS ℃ sjis 818E"),
            Row(0x2116, "NUMERO SIGN № sjis 8782"),
            Row(0x2121, "TELEPHONE SIGN ℡ sjis 8784"),
            Row(0x2166, "ROMAN NUMERAL SEVEN Ⅶ sjis 875A（glyph/GlyphCacheTest:153）"),
            Row(0x2192, "RIGHTWARDS ARROW → sjis 81A8"),
            Row(0x21D2, "RIGHTWARDS DOUBLE ARROW ⇒ sjis 81CB"),
            Row(0x221A, "SQUARE ROOT √ sjis 81E3"),
            Row(0x2220, "ANGLE ∠ sjis 81DA"),
            Row(0x2260, "NOT EQUAL TO ≠ sjis 8182"),
            Row(0x2261, "IDENTICAL TO ≡ sjis 81DF"),
            Row(0x2266, "LESS-THAN OVER EQUAL TO ≦ sjis 8185（日语用的就是它）"),
            Row(0x2267, "GREATER-THAN OVER EQUAL TO ≧ sjis 8186（日语用的就是它）"),
            Row(0x2460, "CIRCLED DIGIT ONE ① sjis 8740（契约点名）"),
            Row(0x2461, "CIRCLED DIGIT TWO ② sjis 8741"),
            Row(0x2462, "CIRCLED DIGIT THREE ③ sjis 8742"),
            Row(0x2463, "CIRCLED DIGIT FOUR ④ sjis 8743"),
            Row(0x2464, "CIRCLED DIGIT FIVE ⑤ sjis 8744（adapter/ConsoleLogSyncTest）"),
            Row(0x2465, "CIRCLED DIGIT SIX ⑥ sjis 8745"),
            Row(0x2466, "CIRCLED DIGIT SEVEN ⑦ sjis 8746"),
            Row(0x2500, "BOX DRAWINGS LIGHT HORIZONTAL ─ sjis 849F（sprite/BarLineTest:104）"),
            Row(0x2501, "BOX DRAWINGS HEAVY HORIZONTAL ━ sjis 84AA（基准:61 :68 :74 :79）"),
            Row(0x2502, "BOX DRAWINGS LIGHT VERTICAL │ sjis 84A0"),
            Row(0x2503, "BOX DRAWINGS HEAVY VERTICAL ┃ sjis 84AB（语料 5498 次）"),
            Row(0x2518, "BOX DRAWINGS LIGHT UP AND LEFT ┘ sjis 84A3"),
            Row(0x253C, "BOX DRAWINGS VERTICAL AND HORIZONTAL ┼ sjis 84A9（语料 3467 次）"),
            Row(0x25A0, "BLACK SQUARE ■ sjis 81A1（语料 16651 次）"),
            Row(0x25A1, "WHITE SQUARE □ sjis 81A0（语料 3512 次）"),
            Row(0x25B2, "BLACK UP-POINTING TRIANGLE ▲ sjis 81A3"),
            Row(0x25BC, "BLACK DOWN-POINTING TRIANGLE ▼ sjis 81A5（语料 4768 次）"),
            Row(0x25C6, "BLACK DIAMOND ◆ sjis 819F（语料 3024 次）"),
            Row(0x25CB, "WHITE CIRCLE ○ sjis 819B"),
            Row(0x25CE, "BULLSEYE ◎ sjis 819D"),
            Row(0x25CF, "BLACK CIRCLE ● sjis 819C"),
            Row(0x2605, "BLACK STAR ★ sjis 819A"),
            Row(0x2606, "WHITE STAR ☆ sjis 8199"),
            Row(0x266A, "MUSICAL NOTE ♪ sjis 81F4（语料 20066 次）"),
        )

        /** A 类码点集合（供 B 类用例做互斥断言）。 */
        val CLASS_A_CODE_POINTS: Set<Int> = CLASS_A_SYMBOLS.map { it.codePoint }.toSet()

        /**
         * D 类（`.NET` 932 实测 1 字节；[D 类单字节控制符与 U+00A5 记 1 格]）：
         * 控制符与 `U+00A5` 的代表点 + 整档码点集。区间端点单列，其余 32 个 C0 码点由
         * [D_CLASS_CODE_POINTS] 覆盖。
         */
        val CLASS_D_SYMBOLS = listOf(
            Row(0x0000, "NUL（C0 起点）"),
            Row(0x0009, "TAB（裸码元 1 格；整串先展开成 8 空格）"),
            Row(0x001F, "C0 末点 US"),
            Row(0x007F, "DELETE"),
            Row(0x0080, "C1 首码点（932 单字节 0x80）"),
            Row(0x00A5, "YEN SIGN ¥（best-fit 回 0x5C，与 ASCII 反斜杠同格）"),
        )

        /** D 类整档码点：`U+0000..U+001F` + `U+007F` + `U+0080` + `U+00A5`，共 35 个。 */
        val D_CLASS_CODE_POINTS: Set<Int> = (0x0000..0x001F).toSet() + setOf(0x007F, 0x0080, 0x00A5)

        /**
         * B 类（CP932 **无此字节**：Python 的 cp932 编码器对这些码点一律抛
         * `UnicodeEncodeError`，即 Windows-31J 码页表里没有它们）。
         * 唯一表按「其余一律 2 格」给 2 = 没有字体度量时的确定性兜底，
         * 字节数口径对它们实得 1（替换回退）—— 分叉与卡点见 [B 类符号是 CP932 表外字两口径必然分叉]。
         */
        val CLASS_B_SYMBOLS = listOf(
            Row(0x00B7, "MIDDLE DOT ·（日语中间点是 U+30FB，那个两口径都是全角 2 格）"),
            Row(0x00BD, "VULGAR FRACTION ONE HALF ½（glyph/CellMetricsTest:117 的判据字符）"),
            Row(0x200B, "ZERO WIDTH SPACE（语料 109765 次；三张表都不给 0 格，真机应测出 0 宽）"),
            Row(0x2014, "EM DASH —（旧 Kernel.kt:109 的 TODO 点名；语料 41103 次）"),
            Row(0x2236, "RATIO ∶（旧 Kernel.kt:109 的 TODO 点名）"),
            Row(0x2248, "ALMOST EQUAL TO ≈（sprite/SpriteTable 的注释用它）"),
            Row(0x2264, "LESS-THAN OR EQUAL TO ≤（CP932 无位；日语写的是 ≦ U+2266，那个在 A 类）"),
            Row(0x2265, "GREATER-THAN OR EQUAL TO ≥（CP932 无位；日语写的是 ≧ U+2267）"),
            Row(0x2665, "BLACK HEART SUIT ♥（语料 65506 次）"),
            Row(0x2764, "HEAVY BLACK HEART ❤（语料 33245 次）"),
            Row(0x28FF, "BRAILLE PATTERN BLANK ⣿（语料 2772 次）"),
            Row(0xFEFF, "ZERO WIDTH NO-BREAK SPACE / BOM（基准:1 的第一个码元）"),
        )

        /** C 类对照：委托前后一直同格的代表码点（ASCII、半角假名两端、全角标点/CJK/谚文/段边界）。 */
        val CLASS_C_STABLE = listOf(
            0x0020, 0x0041, 0x007E,                         // ASCII 两端（0x5C 反斜杠、0x7E `‾` 都算半角）
            0xFF61, 0xFF76, 0xFF9F,                         // 半角假名区两端与中间
            0x3000, 0x3001, 0x3002, 0x30FB, 0x3042, 0x30A2, // 全角标点与假名
            0x4E00, 0x5F8C, 0x9FFF,                         // CJK 统一表意
            0xFF01, 0xFF21, 0xFF60,                         // 全角 ASCII 变体
            0xFFE0, 0xFFE1, 0xFFE6,                         // 全角符号（CP932 双字节）
            0x1100, 0x115F,                                 // 谚文字母
            0xAC00, 0xD7A3, 0xF900, 0xFAFF,                 // 谚文音节 + CJK 兼容表意
            0xFE30, 0xFE31, 0xFE6F,                         // CJK 兼容形式
        )

        /** 增补平面的累加粒度差异（可 grep 的 TODO 标记，不写在 KDoc 正文里）。 */
        const val RESIDUAL_NOTE =
            "TODO(verify) Grid.cells 逐码元（代理项半各 2 格 → 增补字符 4 格）、" +
                "CellWidth.of 逐码位（增补字符 2 格）；基准文本不含增补字符，" +
                "真机折行以 glyph/GlyphCache.measureCells 的像素度量为准（契约 M4-C 0.5）。"
    }
}
