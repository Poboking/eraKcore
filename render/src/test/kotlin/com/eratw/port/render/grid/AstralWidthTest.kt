package com.eratw.port.render.grid

import com.eratw.port.core.host.CellWidth
import com.eratw.port.render.Grid
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 增补平面（U+10000 以上）在**三层**的格数账 —— WIDTH-ASTRAL 的钉子。
 *
 * ## 本波取「维持现状」（契约 M4-F §2 f-widthastral 的出路 b），边界已写进代码
 *
 * - [Grid.cells] 逐 **UTF-16 码元**累加：一对合法代理（一个增补字符）= 2 + 2 = **4 格**；
 * - [CellWidth.of] 逐 **码位**累加：同一个字符 = **2 格**（`core/host/CellWidth.kt:66-75`）；
 * - [LineFolder] 的段宽与前缀宽度都按 `Char` 索引 + 逐码元宽度 = **4 格**（与 [Grid.cells] 同式）。
 *
 * 「为什么不统一」的三条理由（原版折行路径的索引空间就是 `Char`、原版宽度读数是像素、
 * 换口径要重钉别人独占的已登记用例且闸看不见）写在 `render/.../Kernel.kt` 的 `Grid` KDoc
 * 「增补平面（U+10000 以上）的口径边界」节与 `grid/LineFolder.kt` 的类 KDoc。
 * 本文件只做一件事：把现状**逐值钉死**，并把「三层对同一字符串各给多少格」列成参数化表。
 *
 * ## 为什么钉在 render 侧就够（口径的原文出处）
 *
 * 原版没有任何「码位」概念：折行的索引空间是 UTF-16 `Char`（`PrintStringBuffer.cs:453-496`
 * 在 `text.Length` 上二分、`:471,483,485-486` 取 `Slice(0, num4)` 量前缀宽；
 * `ConsoleStyledString.cs:54-73` 的 `DivideAt(index)` 直接用 `Substring`），宽度是 GDI 像素
 * （`StringMeasure.cs:42-49` 的 `TextRenderer.MeasureText`、`:51-72` 的 `MeasureCharacterRanges`）。
 * 一对代理项在 GDI 眼里是**一个字形**，既不是 4 格也不是 2 格；CP932 口径（`EmueraConsole.cs:3255`
 * 的 `GetByteCount`）对 U+10000 以上同样给不出 4（表外字，替换回退，逐点登记在
 * `GridWidthSingleSourceTest` 的 B 类表）。两个整数读数都是兜底近似。
 *
 * 代价（下游按这条处理）：核心层的生产折行按码位（`core/host/LineBufferConsoleSink.kt:770-778`），
 * 本层 `adapter/ConsoleAdapter.fold` 又用 [LineFolder] 再折一次 ⇒ 含增补字符的行可能被再断开、
 * 按钮列区间后移 2 格/字。语料里这类字符存在（`ERB` 目录实测 13 个文件共 74 个：
 * `PRINTBUTTON @"%UNICODE(0x3000) * 2%[<U+1F6AA>检查房间]", 100` 那类按钮标签在
 * `M_KOJO_K11_2_イベント.ERB:4402` 一带，`MAP_06.ERB:15-17,158-160,196` 有 24 个 U+1F480 地图行），
 * 但基准整份没有（`fixtures/golden/baseline/pc-golden-root.txt` 700 行 / 22,694 字节 / 0 个）
 * ⇒ 四道闸都看不见这条。
 *
 * ## 与既有登记用例的关系：**只复述，不改写**
 *
 * `GridWidthSingleSourceTest` 的「增补平面字符是已登记的驻留差异」与 `LineFolderTest` 的
 * 「代理对不被劈成半个字符」本轮一字未动（按契约不属本路独占面）；本文件把它们的期望值
 * **再复述一遍**（见「既有登记用例的期望值在本文件复述」），谁改累加口径，三个文件一起报红。
 */
class AstralWidthTest {

    private fun text(s: String) =
        TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic")

    private fun numButton(s: String, v: Long) =
        TextRun(s, fore = 0xFFFFFF, back = 0x000000, styleMask = 0, fontName = "msgothic", kind = RunKind.BUTTON, buttonValue = v)

    private fun line(vararg runs: TextRun) =
        RenderLine(runs = runs.toList(), isLogicalLine = true, isLineEnd = true)

    // ------------------------------------------------------------------
    // 1 三层对照表（参数化）
    // ------------------------------------------------------------------

    /**
     * 三层对**同一个字符串**各给多少格：参数化逐行断言 [Grid.cells]（逐码元）、
     * [CellWidth.of]（逐码位）与 [LineFolder] 的收行格数，并断言两层差值的**精确形式**：
     *
     *     逐码元 - 逐码位 == 2 × 合法代理对个数
     *
     * 每个合格代理对在渲染侧多算 2 格（一对 4 格 vs 一个码位 2 格），所以 `astral == 0` 的行
     * （BMP、TAB、孤立代理项半）三层**逐值相等**；分叉只发生在合法代理对上。
     * [LineFolder] 的格数经「宽度恰为串宽时整串一行」的收行结果读出（段宽 = `FoldPiece.cells`
     * = [Grid.cells]，前缀宽度 = [LineFolder.pieceCellsAt] 逐码点求和，两者必须同式）。
     */
    @Test
    fun `三层对同一字符串的格数账`() {
        for (row in ROWS) {
            val core = CellWidth.of(Grid.expandTabs(row.text))
            assertEquals(row.grid, Grid.cells(row.text), "Grid.cells 逐码元：${row.label}")
            assertEquals(row.core, core, "CellWidth.of 逐码位（core/host/CellWidth.kt:66-75）：${row.label}")
            assertEquals(2 * row.astral, row.grid - core, "两层差值 = 合法代理对数 × 2：${row.label}")

            val rows = LineFolder(clientWidth = row.grid).fold(line(text(row.text)))
            assertEquals(1, rows.size, "宽度恰为串宽 ⇒ 整串一行：${row.label}")
            assertEquals(row.grid, rows[0].cells, "LineFolder 收行格数 = Grid.cells（段宽与前缀宽度同式）：${row.label}")
        }
    }

    /**
     * 孤立代理项半（未成对）与不成对序列：三层**同值**，这是「分叉只发生在合法代理对」的另一面。
     *
     * [CellWidth.of] 的 `codePointAt` 对孤立半原样返回该半（不合成码位），落进
     * `CellWidth.isFullWidth` 的「其余一律全角」⇒ 2 格（`core/host/CellWidth.kt:104` 明说
     * 「代理项半单独传入按全角 2 计」）；[Grid.cellWidth] 同值；[LineFolder.pieceCellsAt] 同值、
     * [LineFolder.step] 只走 1 个 `Char`（不成对 ⇒ 不是码点起点）。
     */
    @Test
    fun `孤立代理项半上三层同值而合法代理对才分叉`() {
        for (row in ROWS.filter { it.astral == 0 }) {
            assertEquals(
                Grid.cells(row.text),
                CellWidth.of(Grid.expandTabs(row.text)),
                "无合法代理对 ⇒ 逐码元 == 逐码位：${row.label}",
            )
        }

        // 单独一个半：2 格、步进 1（逐值来源同 GridWidthSingleSourceTest 的登记口径）。
        for (half in listOf("\uD840", "\uDC00")) {
            assertEquals(2, Grid.cells(half), "单个代理项半逐码元 2 格")
            assertEquals(2, CellWidth.of(half), "单个代理项半逐码位同值（codePointAt 返回该半）")
            assertEquals(2, LineFolder.pieceCellsAt(half, 0), "折行前缀宽度同值")
            assertEquals(1, LineFolder.step(half, 0), "不成对 ⇒ 步进 1 个 Char")
        }

        // 高代理项半 + ASCII：'A' 不是低代理 ⇒ 不成对，三层仍同值（2 + 1 = 3）。
        assertEquals(3, Grid.cells("\uD840A"))
        assertEquals(3, CellWidth.of("\uD840A"))
        assertEquals(2, LineFolder.pieceCellsAt("\uD840A", 0), "索引 0 是半，不是对的起点")
        assertEquals(1, LineFolder.step("\uD840A", 0))
        assertEquals(1, LineFolder.pieceCellsAt("\uD840A", 1), "索引 1 是 ASCII 'A'")
        // 低 + 高（逆序）：两个半各自孤立 ⇒ 2 + 2 = 4，三层同值。
        assertEquals(4, Grid.cells("\uDC00\uD840"))
        assertEquals(4, CellWidth.of("\uDC00\uD840"))
        assertEquals(1, LineFolder.step("\uDC00\uD840", 0), "低代理项半不是对的起点")

        // 反向：整对上是整倍关系（4 = 2 × 2），这正是「两层差一倍」的最小见证。
        val wide = "\uD840\uDC00"
        assertEquals(4, Grid.cells(wide))
        assertEquals(2, CellWidth.of(wide))
        assertEquals(2 * CellWidth.of(wide), Grid.cells(wide), "一整对：渲染侧 = 2 × 核心侧")
    }

    // ------------------------------------------------------------------
    // 2 前缀步进：段宽与前缀宽度必须同式
    // ------------------------------------------------------------------

    /**
     * 沿 [LineFolder.step] 逐码点累加 [LineFolder.pieceCellsAt]，必须**逐值收敛到整串** [Grid.cells]。
     *
     * 这就是「段宽」与「前缀宽度」不许分家的可执行形式：改累加口径时只改一处（例如只把
     * 段宽改成 [CellWidth.of]）会让这条等式在代理对上炸开；两处同时改则这条等式仍成立
     * （但会连带 `GridWidthSingleSourceTest` / `LineFolderTest` 的已登记值 —— 那两处的期望值
     * 见本文件最后一节或原文件）。步进只走 1 或 2 个 `Char` ⇒ 永不劈在代理对中间。
     */
    @Test
    fun `前缀步进沿码点走且求和等于整串格数`() {
        for (row in ROWS) {
            // 生产路径上本类拿到的文本已由 group() 展开过 TAB（LineFolder.group → Grid.expandTabs），
            // 所以按展开后的串走，TAB 行才与 Grid.cells 同值。
            val s = Grid.expandTabs(row.text)
            var index = 0
            var sum = 0
            while (index < s.length) {
                val n = LineFolder.step(s, index)
                assertTrue(n == 1 || n == 2, "步进只可能是 1 或 2 个 Char：${row.label}")
                val expect = Grid.cellWidth(s[index]) + (if (n == 2) Grid.cellWidth(s[index + 1]) else 0)
                assertEquals(expect, LineFolder.pieceCellsAt(s, index), "前缀宽度按码点取值：${row.label}")
                sum += expect
                index += n
            }
            assertEquals(s.length, index, "步进正好走完（不劈代理对）：${row.label}")
            assertEquals(row.grid, sum, "逐码点求和 = 整串 Grid.cells（段宽与前缀宽度同式）：${row.label}")
        }
    }

    // ------------------------------------------------------------------
    // 3 折行落点（现状钉死）
    // ------------------------------------------------------------------

    /**
     * 增补字符按 **4 格**参与折行判定（现状的直接可见后果），且永不劈在代理对中间。
     *
     * ①②③是整数网格下的手算落点（规则与逐条出处见 `LineFolderTest` 的类 KDoc：
     * `PrintStringBuffer.cs:414-496` 的最大前缀、`:236-240` 的溢出放行）；
     * ④是语料里真实存在的形态（`PRINTBUTTON @"%UNICODE(0x3000) * 2%[U+1F6AA 检查房间]", 100`
     * 那一类含 emoji 的按钮标签），按钮跨行拆分后每行各成一段且值相同
     * （`ConsoleButtonString.cs:247-251`）。
     */
    @Test
    fun `折行落点按码元口径且不劈代理对`() {
        val wide = "\uD840\uDC00"

        // ① 宽 6、两个 U+20000 各 4 格 ⇒ 一行只放得下一个（4 <= 6，第二个会到 8）。
        //    与 LineFolderTest「代理对不被劈成半个字符」的登记落点同源（那里宽也是 6）。
        val two = LineFolder(clientWidth = 6).fold(line(text(wide + wide)))
        assertEquals(2, two.size)
        assertEquals(listOf(wide, wide), two.map { it.runs[0].text })
        assertEquals(listOf(4, 4), two.map { it.cells })

        // ② 宽 3、单个 U+20000（4 格 > 3）且首行为空 ⇒ 整段入行并允许溢出
        //    （PrintStringBuffer.cs:236-240：宁可行溢出也不把字符劈开）。
        //    这是「维持现状」最直观的一处：按码位口径（2 格）本该放得下，按码元口径放不下。
        val overflow = LineFolder(clientWidth = 3).fold(line(text(wide)))
        assertEquals(1, overflow.size)
        assertEquals(4, overflow[0].cells, "4 > clientWidth(3)：溢出行由下游做列裁剪")
        assertTrue(overflow[0].cells > 3, "cells 允许大于 clientWidth（PrintStringBuffer.cs:236-240）")

        // ③ 宽 5、"ab" + U+20000 + "c"（2 + 4 + 1 = 7 格）⇒ 首行取放得下的最大前缀 "ab"（2 格），
        //    次行 "U+20000c"（5 格）；总格数守恒。（按码位口径整串只有 4 格，本该一行放下。）
        val mixed = LineFolder(clientWidth = 5).fold(line(text("ab" + wide + "c")))
        assertEquals(2, mixed.size)
        assertEquals(listOf("ab", wide + "c"), mixed.map { it.runs[0].text })
        assertEquals(listOf(2, 5), mixed.map { it.cells })
        assertEquals(Grid.cells("ab" + wide + "c"), mixed.sumOf { it.cells }, "折行不改变总格数")

        // ④ 按钮标签：宽 4、"[U+1F6AA]"（1 + 4 + 1 = 6 格）⇒ 三行，每行仍是同一条按钮。
        val emoji = "\uD83D\uDEAA"
        val buttonRows = LineFolder(clientWidth = 4).fold(line(numButton("[$emoji]", 100L)))
        assertEquals(3, buttonRows.size)
        assertEquals(listOf("[", emoji, "]"), buttonRows.map { it.runs[0].text })
        assertEquals(listOf(1, 4, 1), buttonRows.map { it.cells })
        for (r in buttonRows) {
            assertEquals(1, r.runs.size)
            assertEquals(RunKind.BUTTON, r.runs[0].kind)
            assertEquals(100L, r.runs[0].buttonValue, "拆出的每半继承 Input/Inputs（ConsoleButtonString.cs:247-251）")
        }
        assertEquals(Grid.cells("[$emoji]"), buttonRows.sumOf { it.cells })

        // 代理对完整性：上面每一行的每条 run 都不以孤立代理项半开头/结尾。
        for (r in two + overflow + mixed + buttonRows) {
            for (run in r.runs) {
                val t = run.text
                if (t.isEmpty()) {
                    continue
                }
                assertTrue(!Character.isLowSurrogate(t[0]), "run 不以低代理项半开头")
                assertTrue(!Character.isHighSurrogate(t[t.length - 1]), "run 不以高代理项半结尾")
            }
        }
    }

    // ------------------------------------------------------------------
    // 4 既有登记用例的期望值（只复述，不改写）
    // ------------------------------------------------------------------

    /**
     * `GridWidthSingleSourceTest`「增补平面字符是已登记的驻留差异」与
     * `LineFolderTest`「代理对不被劈成半个字符」的期望值，按**同一组常量**在本文件复述。
     *
     * 那两个文件本轮一字未改（不属本路契约的独占面，语义也未削弱）；这里复述是为了让
     * 「换累加口径」这件事在 render 侧同时炸三个文件而不是一个 —— 谁要统一为按码位，
     * 必须连同这两条已登记用例一起重钉（跨界重钉由组长接线，见 M4-F 交付消息）。
     */
    @Test
    fun `既有登记用例的期望值在本文件复述`() {
        val wide = "\uD840\uDC00"
        assertEquals(4, Grid.cells(wide), "同 GridWidthSingleSourceTest：逐码元 高代理 2 + 低代理 2")
        assertEquals(2, CellWidth.of(wide), "同 GridWidthSingleSourceTest：逐码位 一个码位 2 格")
        assertEquals(4, LineFolder.pieceCellsAt(wide, 0), "同 GridWidthSingleSourceTest：折行按码元累加")
        assertEquals(2, LineFolder.step(wide, 0), "同 GridWidthSingleSourceTest：步进按码位 2 个 Char")
        assertEquals(8, Grid.cells(wide + wide), "同 GridWidthSingleSourceTest")
        assertEquals(4, CellWidth.of(wide + wide), "同 GridWidthSingleSourceTest")

        val emoji = "\uD83D\uDE00"
        assertEquals(4, Grid.cells(emoji), "同 GridWidthSingleSourceTest：逐码元累加 4 格")
        assertEquals(2, CellWidth.of(emoji), "同 GridWidthSingleSourceTest：按码位 2 格")

        val rows = LineFolder(clientWidth = 6).fold(line(text(wide + wide)))
        assertEquals(2, rows.size, "同 LineFolderTest「代理对不被劈成半个字符」：宽 6 一行一个字")
        assertEquals(4, rows[0].cells)
        assertEquals(4, rows[1].cells)
        assertEquals(2, rows[0].runs[0].text.length, "每行恰好一个增补字符（两个 Char）")
    }

    private companion object {
        /** 一条登记串：label / 串 / [Grid.cells] / [CellWidth.of] / 合法代理对（增补码位）个数。 */
        class Row(val label: String, val text: String, val grid: Int, val core: Int, val astral: Int)

        /**
         * 期望值一律**手算**（不读实现）：半角 1、全角 2、代理项半各 2 格
         * （`core/host/CellWidth.kt` 的判定式：半角是 `U+0020..U+007E`、半角假名
         * `U+FF61..U+FF9F`、以及 `.NET` 932 单字节的 C0/DEL/`U+0080`/`U+00A5` 四档），
         * TAB 在测量前展开成 8 个空格（`StringMeasure.cs:57-61`）。
         * `astral` 只数**合法代理对**（`Character.isHighSurrogate` 且后随低代理项半）。
         */
        val ROWS = listOf(
            Row("ASCII 半角", "abc", 3, 3, 0),
            Row("全角 + 半角混", "汉a", 3, 3, 0),
            Row("TAB（先展开成 8 空格）", "\t", 8, 8, 0),
            Row("基准 A 类符号（━×3，基准 :61 那一类）", "━".repeat(3), 6, 6, 0),
            Row("U+20000（CJK 扩展 B）", "\uD840\uDC00", 4, 2, 1),
            Row("U+1F600（emoji，CP932 表外字）", "\uD83D\uDE00", 4, 2, 1),
            Row("两个 U+20000", "\uD840\uDC00\uD840\uDC00", 8, 4, 2),
            Row("ASCII + U+20000", "a\uD840\uDC00", 5, 3, 1),
            Row("ab + U+20000 + c 混排", "ab\uD840\uDC00c", 7, 5, 1),
            Row("BMP 夹一对", "汉\uD83D\uDE00字", 8, 6, 1),
            Row("单独高代理项半", "\uD840", 2, 2, 0),
            Row("单独低代理项半", "\uDC00", 2, 2, 0),
            Row("高代理项半 + ASCII（不成对）", "\uD840A", 3, 3, 0),
            Row("低 + 高代理项半（逆序不成对）", "\uDC00\uD840", 4, 4, 0),
        )
    }
}