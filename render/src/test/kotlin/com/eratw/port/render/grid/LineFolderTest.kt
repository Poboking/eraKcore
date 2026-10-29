package com.eratw.port.render.grid

import com.eratw.port.render.DisplayRow
import com.eratw.port.render.Grid
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `LineFolder` 的折行判据。
 *
 * 期望值全部由 C# 行号推出的规则手算得到（不是回读实现）：
 * 每格宽度按半角 1 / 全角 2（`EmueraConsole.cs:3255` 的 Shift-JIS 字节数口径 ——
 * WIDTH-SINGLE 的裁定 main@166dbd7 把核心层唯一表 [com.eratw.port.core.host.CellWidth]
 * 定成这张表，所以委托对 `:render` 的折行落点**逐码点无变化**；逐点账与两条证据在
 * `grid/GridWidthSingleSourceTest`），
 * 折行位置取「当前行还放得下的最大前缀」（`PrintStringBuffer.cs:414-496`），
 * 行首标志（`PrintStringBuffer.cs:206,212,243`）、行尾标志（`PrintStringBuffer.cs:183` +
 * `ConsoleDisplayLine.cs:16`）、按钮继承（`ConsoleButtonString.cs:247-251`）。
 */
class LineFolderTest {

    private fun text(s: String) = TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic")

    private fun numButton(s: String, v: Long) =
        TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic", kind = RunKind.BUTTON, buttonValue = v)

    private fun strButton(s: String, v: String) =
        TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic", kind = RunKind.BUTTON, buttonValue = v)

    private fun line(vararg runs: TextRun, isLogicalLine: Boolean = true, isLineEnd: Boolean = true) =
        RenderLine(runs = runs.toList(), isLogicalLine = isLogicalLine, isLineEnd = isLineEnd)

    private fun texts(row: DisplayRow): List<String> = row.runs.map { it.text }

    @Test
    fun `半角段恰好占满一行时不产生空尾行`() {
        // 10 个半角 = 10 格，判定式 PointX + Width <= divWidth（PrintStringBuffer.cs:217）成立即入行，
        // 循环结束时 list2 非空才补一行（PrintStringBuffer.cs:259-262），故只有 1 行、没有尾巴。
        val rows = LineFolder(clientWidth = 10).fold(line(text("0123456789")))
        assertEquals(1, rows.size)
        assertEquals(10, rows[0].cells)
        assertEquals(listOf("0123456789"), texts(rows[0]))
        assertTrue(rows[0].isLogicalLine)
        assertTrue(rows[0].isLineEnd)
    }

    @Test
    fun `全半角混排在边界上不拆字符`() {
        // 手算：a1 b1 c1 あ2 = 5 格。宽 4 时最大前缀 = "abc"（3 格，加 あ 会到 5 > 4），
        // 余下 "あ" 重排到次行行首（PrintStringBuffer.cs:227-234 与 244-249）。
        val rows = LineFolder(clientWidth = 4).fold(line(text("abcあ")))
        assertEquals(2, rows.size)
        assertEquals(listOf("abc"), texts(rows[0]))
        assertEquals(3, rows[0].cells)
        assertEquals(listOf("あ"), texts(rows[1]))
        assertEquals(2, rows[1].cells)
    }

    @Test
    fun `恰满的多段拼接只有一行`() {
        // 三段 4 + 2 + 4 = 10 格，宽 6 时前两段正好铺满（4 + 2 = 6 <= 6），
        // 第三段在行首（pointX = 6）放不下且前缀为 0 → 断行后整段入次行。
        val rows = LineFolder(clientWidth = 6).fold(line(text("abcd"), text("ef"), text("ghい")))
        assertEquals(2, rows.size)
        assertEquals(listOf("abcd", "ef"), texts(rows[0]))
        assertEquals(6, rows[0].cells)
        assertEquals(listOf("ghい"), texts(rows[1]))
        assertEquals(4, rows[1].cells)
        assertTrue(rows[0].isLogicalLine)
        assertFalse(rows[1].isLogicalLine)
    }

    @Test
    fun `段边界内拆分的三段拼接逐字符落位`() {
        // 宽 4。三条非按钮 run 先并成一个段（fromCssToButton，PrintStringBuffer.cs:280-292），
        // 字符流是 ab|cdえ|おf（2 + 4 + 3 = 9 格），拆分点按「整块放得下的部件 + 部件内前缀」算
        // （getDivideIndex，PrintStringBuffer.cs:414-451）：
        // ab(2 格) 整块放得下；cdえ 在 x=2 处 2+4>4 → 部件内前缀取 "cd"（余格 2）→ 拆点 4 个字符；
        // 断行后余 "え|おf"（2+3=5 格）在新行 5>4 → 前缀 "え"(2 格) + 部件内 "お"(2 格，f 会到 5)→ 拆点 2 个字符；
        // 余 "f" 入第三行。
        val rows = LineFolder(clientWidth = 4).fold(line(text("ab"), text("cdえ"), text("おf")))
        assertEquals(3, rows.size)
        assertEquals(listOf("ab", "cd"), texts(rows[0]))
        assertEquals(4, rows[0].cells)
        assertEquals(listOf("え", "お"), texts(rows[1]))
        assertEquals(4, rows[1].cells)
        assertEquals(listOf("f"), texts(rows[2]))
        assertEquals(1, rows[2].cells)
    }

    @Test
    fun `空内容逻辑行不产生显示行`() {
        // PrintStringBuffer.cs:198-201 —— buttonList.Count == 0 直接返回空数组。
        // 屏上的空行由调用侧先塞一个空格制造（EmueraConsole.cs:3379-3382），不归本类。
        val rows = LineFolder(clientWidth = 10).fold(line())
        assertEquals(0, rows.size)
    }

    @Test
    fun `空串 run 仍产出一行且 cells 为 0`() {
        // 有一条 run 就有一个 ConsoleButtonString（buttonList.Count 不为 0），空段的 Width 在原版
        // 被写成哨兵 -1（ConsoleButtonString.cs:273-276）；fit 判定 PointX + Width <= num 恒成立
        // → 照样入行（整数网格里按 0 格处理，见 LineFolder 的「已知取舍」）。
        val rows = LineFolder(clientWidth = 10).fold(line(text("")))
        assertEquals(1, rows.size)
        assertEquals(0, rows[0].cells)
        assertEquals(listOf(""), texts(rows[0]))
    }

    @Test
    fun `空串段随字符流并入不额外占行`() {
        // 宽 3、字符流 abc|""|d 并成一个段（4 格）：拆分点 = 整块放得下的 "abc"（3 格）的 3 个字符，
        // 空部件 0 格也判为「放得下」但不计入宽度；而拆分点恰等于 "abc" 的长度 →
        // 走 DivideAt 的 `divIndex == num + length` 分支，空部件连同 "d" 一起归右半，
        // 不产生空行（ConsoleButtonString.cs:220-224）。
        val rows = LineFolder(clientWidth = 3).fold(line(text("abc"), text(""), text("d")))
        assertEquals(2, rows.size)
        assertEquals(listOf("abc"), texts(rows[0]))
        assertEquals(3, rows[0].cells)
        assertEquals(listOf("", "d"), texts(rows[1]))
        assertEquals(1, rows[1].cells)
    }

    @Test
    fun `单个全角字符超一列时整段溢出而不劈字符`() {
        // 宽 1：あ 占 2 格 > 1 → divideIndex = 0 且当前行为空 → 整段原样入行
        // （PrintStringBuffer.cs:236-240），cells 允许大于 clientWidth。
        val rows = LineFolder(clientWidth = 1).fold(line(text("あいう")))
        assertEquals(1, rows.size)
        assertEquals(6, rows[0].cells)
        assertEquals(listOf("あいう"), texts(rows[0]))
    }

    @Test
    fun `超一列时先放得下的半角仍入首行`() {
        // 宽 1：a(1 格) 放得下、あ(2 格) 放不下 → 部件内前缀 = 1 个字符 → 就地拆；
        // 余下的 "あ" 重排到新行行首（pointX=0），仍 2 格 > 1 且该行非空段之前无位置 →
        // divideIndex 为 0 且当前行为空 → 整段入行并溢出（PrintStringBuffer.cs:236-240）。
        val rows = LineFolder(clientWidth = 1).fold(line(text("aあ")))
        assertEquals(2, rows.size)
        assertEquals(listOf("a"), texts(rows[0]))
        assertEquals(1, rows[0].cells)
        assertEquals(listOf("あ"), texts(rows[1]))
        assertEquals(2, rows[1].cells)
        assertFalse(rows[1].isLogicalLine)
    }

    @Test
    fun `数值按钮跨行拆分后每行各成一段且值相同`() {
        // 宽 4、按钮段 "abcdefgh"(8 格)：默认 ButtonWrap=false（ConfigData.cs:83）→
        // 判定式（PrintStringBuffer.cs:222）为真 → 就地拆；后半继承 Input/Inputs
        // （ConsoleButtonString.cs:247-251）→ 两行各一段、值都是 42。
        val rows = LineFolder(clientWidth = 4).fold(line(numButton("abcdefgh", 42L)))
        assertEquals(2, rows.size)
        assertEquals(listOf("abcd"), texts(rows[0]))
        assertEquals(listOf("efgh"), texts(rows[1]))
        for (r in rows) {
            assertEquals(1, r.runs.size)
            assertEquals(RunKind.BUTTON, r.runs[0].kind)
            assertEquals(42L, r.runs[0].buttonValue)
            assertEquals(4, r.cells)
        }
    }

    @Test
    fun `字符串按钮跨行拆分同样保持同值`() {
        // INPUTBUTTONS 走 string 版构造（PrintStringBuffer.cs:103-112），
        // 拆分继承同一条（ConsoleButtonString.cs:247-251）。
        val rows = LineFolder(clientWidth = 4).fold(line(strButton("goto1234", "goto:12")))
        assertEquals(2, rows.size)
        assertEquals(listOf("goto"), texts(rows[0]))
        assertEquals(listOf("1234"), texts(rows[1]))
        assertEquals("goto:12", rows[0].runs[0].buttonValue)
        assertEquals("goto:12", rows[1].runs[0].buttonValue)
        assertEquals(RunKind.BUTTON, rows[1].runs[0].kind)
    }

    @Test
    fun `按钮段与普通段同宽时按各自成段入行`() {
        // 宽 6：ab(2) 与按钮 cdえ(4) 拼成 2+4=6 恰满 → 同一行两段（PrintStringBuffer.cs:217）。
        val rows = LineFolder(clientWidth = 6).fold(line(text("ab"), numButton("cdえ", 7L)))
        assertEquals(1, rows.size)
        assertEquals(listOf("ab", "cdえ"), texts(rows[0]))
        assertEquals(6, rows[0].cells)
        assertEquals(RunKind.TEXT, rows[0].runs[0].kind)
        assertEquals(RunKind.BUTTON, rows[0].runs[1].kind)
    }

    @Test
    fun `默认配置下按钮不整体下移而是就地拆`() {
        // 宽 6：ab(2) + 按钮 defg(4) → 2+4=6 恰满一行，不断行；再加 2 格的按钮才拆。
        val rows = LineFolder(clientWidth = 6).fold(line(text("ab"), numButton("defghi", 9L)))
        assertEquals(2, rows.size)
        assertEquals(listOf("ab", "defg"), texts(rows[0]))
        assertEquals(6, rows[0].cells)
        assertEquals(listOf("hi"), texts(rows[1]))
        assertEquals(2, rows[1].cells)
        assertEquals(9L, rows[0].runs[1].buttonValue)
        assertEquals(9L, rows[1].runs[0].buttonValue)
        assertEquals(RunKind.BUTTON, rows[1].runs[0].kind)
    }

    @Test
    fun `ButtonWrap 打开时按钮整段挪到次行`() {
        // Config.ButtonWrap=true → 判定式（PrintStringBuffer.cs:222）为假 → 直接断行，
        // 按钮不拆；重排后 pointX=0、4 <= 6 → 整段落次行（PrintStringBuffer.cs:244-249）。
        val rows = LineFolder(clientWidth = 6, buttonWrap = true).fold(line(text("abc"), numButton("defg", 5L)))
        assertEquals(2, rows.size)
        assertEquals(listOf("abc"), texts(rows[0]))
        assertEquals(3, rows[0].cells)
        assertEquals(listOf("defg"), texts(rows[1]))
        assertEquals(4, rows[1].cells)
        assertEquals(5L, rows[1].runs[0].buttonValue)
        assertFalse(rows[1].isLogicalLine)
    }

    @Test
    fun `isLogicalLine 只在首行且 isLineEnd 只在末行`() {
        // PrintStringBuffer.cs:206 firstLine=!subDiv、212/243 断行后置 false；
        // isLineEnd 默认 true（ConsoleDisplayLine.cs:16），只有 array[^1] 被赋 lineEnd
        // （PrintStringBuffer.cs:183）→ PRINTN 的未结束只落在最后一行。
        // 宽 2、"abcdef"(6 格) → 三行 ab / cd / ef。
        val rows = LineFolder(clientWidth = 2).fold(line(text("abcdef"), isLogicalLine = true, isLineEnd = false))
        assertEquals(3, rows.size)
        assertEquals(listOf("ab", "cd", "ef"), rows.map { texts(it).joinToString("") })
        assertTrue(rows[0].isLogicalLine)
        assertFalse(rows[1].isLogicalLine)
        assertFalse(rows[2].isLogicalLine)
        assertTrue(rows[0].isLineEnd)
        assertTrue(rows[1].isLineEnd)
        assertFalse(rows[2].isLineEnd)
    }

    @Test
    fun `续接行的首行也不标逻辑行首`() {
        // 上层（PRINTN 并入）传入 isLogicalLine=false 时，首行也必须 false。
        val rows = LineFolder(clientWidth = 4).fold(line(text("abcdef"), isLogicalLine = false))
        assertEquals(2, rows.size)
        assertFalse(rows[0].isLogicalLine)
        assertFalse(rows[1].isLogicalLine)
        assertTrue(rows[1].isLineEnd)
    }

    @Test
    fun `TAB 折行前先展开成八个空格`() {
        // StringMeasure.cs:57-62：测量前 s.Replace("\t", "        ")（8 个空格，不做制表位对齐）。
        // 宽 8、"a\tb" → 展开成 a + 8 空格 + b（10 格）→ 最大前缀 8 个字符 = "a"+7 空格，
        // 次行为 1 空格 + b（2 格）。
        val rows = LineFolder(clientWidth = 8).fold(line(text("a\tb")))
        assertEquals(2, rows.size)
        assertEquals("a" + " ".repeat(7), rows[0].runs[0].text)
        assertEquals(8, rows[0].cells)
        assertEquals(" b", rows[1].runs[0].text)
        assertEquals(2, rows[1].cells)
        for (r in rows) {
            for (run in r.runs) {
                assertFalse(run.text.contains('\t'), "折行产物不应残留制表符")
            }
        }
    }

    @Test
    fun `noBr 不折行只出一行`() {
        // nobr 分支（PrintStringBuffer.cs:217）无条件把段收进当前行，
        // 对应 FlushSingleLine 的单行产物（PrintStringBuffer.cs:170-177）。
        val rows = LineFolder(clientWidth = 4).fold(line(text("abcdefghij")), noBr = true)
        assertEquals(1, rows.size)
        assertEquals(10, rows[0].cells)
        assertEquals(listOf("abcdefghij"), texts(rows[0]))
        assertTrue(rows[0].isLogicalLine)
        assertTrue(rows[0].isLineEnd)
    }

    @Test
    fun `样式随拆分继承且不丢字符`() {
        // ConsoleStyledString.DivideAt（ConsoleStyledString.cs:54-73）新建实例时抄 Font、
        // Color、ButtonColor、StringStyle → 拆出来的两半除文本外全等。
        val styled = TextRun("一二三四五六", fore = 0x112233, back = 0x445566, styleMask = Grid.STYLE_BOLD, fontName = "msmincho")
        val rows = LineFolder(clientWidth = 6).fold(line(styled))
        assertEquals(2, rows.size)
        assertEquals(listOf("一二三"), texts(rows[0]))
        assertEquals(listOf("四五六"), texts(rows[1]))
        for (r in rows) {
            val run = r.runs[0]
            assertEquals(0x112233, run.fore)
            assertEquals(0x445566, run.back)
            assertEquals(Grid.STYLE_BOLD, run.styleMask)
            assertEquals("msmincho", run.fontName)
        }
        val joined = rows.flatMap { it.runs }.joinToString("") { it.text }
        assertEquals(Grid.cells(styled.text), rows.sumOf { it.cells })
        assertEquals(styled.text, joined)
    }

    @Test
    fun `相邻普通段合成一段不影响折行落点`() {
        // fromCssToButton（PrintStringBuffer.cs:280-292）把多个样式段并成一个
        // ConsoleButtonString，所以折点只由字符流宽度决定：宽 5 的 "abcdefghij"(10 格) → 5 + 5。
        val rows = LineFolder(clientWidth = 5).fold(line(text("abcde"), text("fghij")))
        assertEquals(2, rows.size)
        assertEquals(listOf("abcde"), texts(rows[0]))
        assertEquals(5, rows[0].cells)
        assertEquals(listOf("fghij"), texts(rows[1]))
        assertEquals(5, rows[1].cells)
    }

    @Test
    fun `两按钮相邻各自成段并按流折行`() {
        // AppendButton 每次都新建一个 ConsoleButtonString（PrintStringBuffer.cs:103-123），
        // 所以按钮之间不合并。宽 4：ab(2 格) + cd(2 格) 铺满第一行，ef 起第二行。
        val rows = LineFolder(clientWidth = 4).fold(line(numButton("ab", 1L), numButton("cd", 2L), numButton("ef", 3L)))
        assertEquals(2, rows.size)
        assertEquals(listOf("ab", "cd"), texts(rows[0]))
        assertEquals(4, rows[0].cells)
        assertEquals(listOf("ef"), texts(rows[1]))
        assertEquals(1L, rows[0].runs[0].buttonValue)
        assertEquals(2L, rows[0].runs[1].buttonValue)
        assertEquals(3L, rows[1].runs[0].buttonValue)
    }

    @Test
    fun `代理对不被劈成半个字符`() {
        // 契约要求「不得在字符中间拆」：原版按 UTF-16 索引 Substring
        // （ConsoleStyledString.cs:60-62）会拆坏代理对，本实现按码点取整。
        // 宽度口径（WIDTH-SINGLE 的裁定 main@166dbd7）：`Grid.cellWidth` 委托核心层唯一表
        // `core/host/CellWidth.kt:98`（CP932 字节数：半角仅 `U+0020..U+007E` 与半角假名），
        // 代理项半不在两个半角区间里 → 各 2 格（`CellWidth.kt:96` 明说「代理项半单独传入按全角 2 计」），
        // 于是一个 𠀀(U+20000) 经 `Grid.cells` 的逐**码元**累加是 4 格 —— 委托前后同值。
        // `CellWidth.of` 按**码位**只给 2 格：这条「累加粒度」差是已登记的驻留差异，
        // 逐值表法与为什么不改累加方式，见 `grid/GridWidthSingleSourceTest` 的
        // 「增补平面字符是已登记的驻留差异」用例。
        // 每字 4 格 → 宽 6 一行只放得下一个字（4 <= 6，第二个字会到 8 > 6）。
        val two = "\uD840\uDC00\uD840\uDC00"
        val rows = LineFolder(clientWidth = 6).fold(line(text(two)))
        assertEquals(2, rows.size)
        assertEquals("\uD840\uDC00", rows[0].runs[0].text)
        assertEquals(4, rows[0].cells)
        assertEquals("\uD840\uDC00", rows[1].runs[0].text)
        assertEquals(4, rows[1].cells)
        for (r in rows) {
            val t = r.runs[0].text
            assertEquals(2, t.length, "每行恰好一个增补字符（两个 Char）")
            assertTrue(Character.isHighSurrogate(t[0]) && Character.isLowSurrogate(t[1]), "代理对被劈开了")
        }
    }

    @Test
    fun `非法宽度直接拒绝`() {
        val err = runCatching { LineFolder(clientWidth = 0) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException, "期望 require 抛 IllegalArgumentException，实到 " + err)
    }

    @Test
    fun `黄金宽度下一行不折`() {
        // 基准 CLIENTWIDTH=1800 是像素口径（pc-golden-root.txt:689），像素到半角格的换算归 m-glyph；
        // 这里把 Grid.DEFAULT_CLIENT_WIDTH 当格数直接用最宽松的一档：900 个半角 = 900 格 <= 1800 格
        // → 判定式 PointX + Width <= divWidth 成立（PrintStringBuffer.cs:217），一行不折。
        // 文本长度 900 是构造值，不来自基准。
        val rule = "=".repeat(900)
        val rows = LineFolder(clientWidth = Grid.DEFAULT_CLIENT_WIDTH).fold(line(text(rule)))
        assertEquals(1, rows.size)
        assertEquals(900, rows[0].cells)
        assertEquals(rule, rows[0].runs[0].text)
    }

    /**
     * 裁定符号的折行落点**与委托前一致**（登记，不吸收）。
     *
     * M4-D 的 WIDTH-SINGLE 委托曾按核心层那张 East Asian Width 近似表把 `※`(U+203B,
     * CP932 双字节 0x81A6) 这类「EAW 只算 Ambiguous」的符号记 1 格，基准
     * `pc-golden-root.txt:67` 那行（`TITLE.ERB:71`）因此从 72 格缩成 71 格。
     * main@166dbd7 的裁定把唯一表换成 CP932 字节数口径（依据 `EmueraConsole.cs:3255`
     * 的 `GetByteCount` 与基准 `:61` 的 99 个 ━ 填满 `:253` 钉死的 1797px 可绘制宽度），
     * 落点回到委托前：※ 行 72 格 → 宽 71 折两行、宽 72 才放得下；
     * 99 个 ━ 是 198 格（标题屏脏区列跨度那一档由它撑，`view/ConsoleScreenTest:153`）。
     * 逐点清单与两条证据在 `grid/GridWidthSingleSourceTest`。
     */
    @Test
    fun `裁定符号的折行落点与委托前一致`() {
        val titleWarn = "※游玩中遇到的问题可以去问一下单独指导里的哆来咪（游玩时注意温馨提示哦）"
        assertEquals(72, Grid.cells(titleWarn), "36 个全角字符 = 72 格（基准:67）")
        assertEquals(2, LineFolder(clientWidth = 71).fold(line(text(titleWarn))).size)
        assertEquals(1, LineFolder(clientWidth = 72).fold(line(text(titleWarn))).size)
        // 委托前旧表（main@M4-C 的 Kernel.kt:112-116）逐字符自证：委托没有动过这一档。
        var legacy = 0
        for (ch in titleWarn) {
            legacy += if (ch in ' '..'~' || ch in '｡'..'ﾟ') 1 else 2
        }
        assertEquals(72, legacy, "旧口径同值 → 委托 = 行为无变化")
        // ━ 同理：基准 :61 的 99 个 ━ 是 198 格，宽 197 折两行、宽 198 恰好放得下。
        val bar = "━".repeat(99)
        assertEquals(198, Grid.cells(bar))
        assertEquals(2, LineFolder(clientWidth = 197).fold(line(text(bar))).size)
        assertEquals(1, LineFolder(clientWidth = 198).fold(line(text(bar))).size)
        var legacyBar = 0
        for (ch in bar) {
            legacyBar += if (ch in ' '..'~' || ch in '｡'..'ﾟ') 1 else 2
        }
        assertEquals(198, legacyBar, "旧口径同值：198 格而不是近似表的 99 格")
    }
}
