package com.eratw.port.render.html

import com.eratw.port.render.Grid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 对拍族 ③「文本着色 / 转义控制序列」—— M6-RENDER-AUDIT。
 *
 * 语义权威 `reference/emuera-cs/MinorShift.Emuera.UI.Game/HtmlManager.cs`（下称 HM）。
 * 逐条口径（C# 出处 → 本仓落地 → 结论）：
 *
 * | # | C# 出处 | 本仓落地 | 结论 |
 * |---|---|---|---|
 * | 1 | HM `:719-742` `Escape` 单趟查 `repDic`（`:164-166`） | [MiniHtml.escape] 的 `ESCAPE_TABLE` | 一致（`& > < " '` 五字符，`'`→`&apos;`） |
 * | 2 | HM `:744-834` `Unescape` 逐 `&…;` 表驱动 | [MiniHtml.unescape] | 一致（含 `:783-785` 的 `&nbsp;`→半角空格、`:780` 的 `ToLower` 大小写不敏感） |
 * | 3 | HM `:807-821` 数字引用：`x` 前缀走 `Convert.ToInt32(digits, 16)`，否则基 10 | `parseInt32Hex` / `toIntOrNull(10)` | 基 10 一致；基 16 已修（见下「真分歧」） |
 * | 4 | HM `:827-830` `num4 < 0 \|\| > 65535` → `OoRUnicodeHtml` | [MiniHtmlErrorKind.ENTITY_OUT_OF_RANGE] | 一致（`&#-1;` 也走这支） |
 * | 5 | HM `:1687-1701` `#` 颜色：`Convert.ToInt32(text,16)`，越界抛在 try 内被裸 catch 吞成 `CanNotInterpretNumValue` | `stringToColor` 的 `#` 分支 → [MiniHtmlErrorKind.COLOR_UNPARSABLE] | 一致（`COLOR_OUT_OF_RANGE` 是原版死代码） |
 * | 6 | HM `:1705-1706` 命名色走 `Color.FromName().A == 0` 判「认不认」 | `ColorNames.rgb` / `isTransparent`（core 唯一表） | 一致（core 侧 `ColorNamesTest` 逐名等长等值） |
 * | 7 | HM `:1712-1720` 裸十六进制「能解析却仍抛 InvalidColorName2」 | `parseInt32Hex(value) != null` → [MiniHtmlErrorKind.COLOR_NAME_REJECTED] | 一致 |
 * | 8 | HM `:1601-1669` font 帧：缺属性继承外层（`:1651-1667`），`face` 的缺省是 null | `openFont` 的继承 + `currentFont` 的空串落默认 | 一致（显式 `face=''` 不继承外层） |
 * | 9 | HM `:1061-1080` 样式位 `\|=`、`:977-983` 闭合 `^=` 不比对标签名 | `styleOpen` / `closeTag` | 一致（交叉闭合合法） |
 * | 10 | HM `:650-658` `FlagBr` → `cssToButton + null` 分隔符 | `flushLine`（空段也成行） | 一致（`PrintStringBuffer.cs:207-215` 逐条 null 成行；串尾不加空行，`:675-678`） |
 * | 11 | HM `:128-149` `GetSS` 取**最内层**帧的 `/FontName/Color/BColor` | `currentFore` / `currentFont` | 一致（样式在入段时快照，`ConsoleStyledString.cs:26-43`） |
 * | 12 | HM `:1129` `Unescape(value).ToLower()` 取 align 值 | `openP` 的 `unescape(value).lowercase(Locale.ROOT)` | 一致（`ToLower()` 受当前区域影响，本机 zh-CN 下与 ROOT 同值；TODO(verify) 见类尾） |
 *
 * ## 本族真分歧（已修）
 *
 * 1. **8 位十六进制按 32 位回绕**：.NET 的 `Convert.ToInt32(s, 16)` 用 `uint` 累加后 `(int)` 强转，
 *    `"ffffffff"` = -1；Kotlin 的 `toIntOrNull(16)` 以 `Int.MAX_VALUE` 为上限给 null。
 *    受影响的是**错误类别**：`&#xffffffff;` 原版本应落 `OoRUnicodeHtml`（HM `:827-830`）却报
 *    `InvalidCharacterReference`；`color='ffffffff'` 本应报 `InvalidColorName2`（HM `:1714`→`:1720`）
 *    却报 `InvalidColorName`。
 * 2. **只吃一个前导 `+`**：第一版只剥一个 `+` 就交给 `toIntOrNull(16)`，而 Java/Kotlin 的
 *    `parseInt` 还会再吃一个符号 —— `"++41"`、`"+-41"`、`"0x+41"`、`"0x-41"` 在 .NET 全是
 *    FormatException（⇒ HM `:825` 的 `InvalidCharacterReference` / `:1700` 的
 *    `CanNotInterpretNumValue` / `:1718` 的 `InvalidColorName`），本仓却给出 65 / -255。
 *
 * 修法：剥符号与 `0x` 前缀各只做一次、每步之后复验，再手写「逐位累加 + 上限 `0xFFFFFFFF` +
 * `toInt()` 回绕」。逐值表见 [MiniHtml.parseInt32Hex] 的 KDoc；下面 `基 16 逐值表` 用例把
 * .NET 9.0.200 的实测读数钉在断言里。
 *
 * ## 未对拍项
 *
 * HM `:780` 的 `ToLower()` 是**当前区域**小写化，本仓用 `Locale.ROOT`：在 tr-TR 下
 * `&LT;` 会被 C# 小写成 `&lt;`（`I`→`ı`）而本仓仍能识别。语料与基准都在 zh-CN 下采集
 * （`emuera.config:76`），无可比读数。TODO(verify)：真机多语言环境接入后按 `Config.Language` 复核，
 * 卡点：无 tr-TR 采集。
 */
class MiniHtmlAuditTest {

    private val m = MiniHtml()

    private fun expect(kind: MiniHtmlErrorKind, html: String) {
        val e = assertFailsWith(MiniHtmlException::class, "应抛 $kind：$html") { m.parse(html) }
        assertEquals(kind, e.kind, "错误类别不符：$html")
    }

    /**
     * 第 3 条：`Convert.ToInt32(s, 16)` 逐值表。
     *
     * 左列是本机 `dotnet 9.0.200` 的实测读数（`try { Convert.ToInt32(s,16) } catch (e) { e.GetType().Name }`），
     * 右列是本仓返回值。`null` 对应 .NET 的三种异常 —— 调用方对它们只需「解析失败」这一个结论
     * （HM `:823-826` 裸 catch）。
     */
    @Test
    fun `基 16 逐值表`() {
        // 正常值 / 前导零 / 8 位回绕（.NET 与 Java 同值）
        assertEquals(65, MiniHtml.parseInt32Hex("41"))
        assertEquals(65, MiniHtml.parseInt32Hex("00000041"))
        assertEquals(2147483647, MiniHtml.parseInt32Hex("7fffffff"))
        // 8 位按 32 位回绕（.NET 累加器是 uint）：Kotlin 的 toIntOrNull 会在这三处给 null
        assertEquals(-1, MiniHtml.parseInt32Hex("ffffffff"))
        assertEquals(Int.MIN_VALUE, MiniHtml.parseInt32Hex("80000000"))
        assertEquals(-1, MiniHtml.parseInt32Hex("0xffffffff"))
        assertEquals(45313, MiniHtml.parseInt32Hex("0b101")) // 0b 不是前缀，按十六进制数字算
        // 前导 + 与 0x/0X 前缀（.NET 都吃；Java 的 toIntOrNull 不吃 0x）        assertEquals(65, MiniHtml.parseInt32Hex("+41"))
        assertEquals(65, MiniHtml.parseInt32Hex("0x41"))
        assertEquals(65, MiniHtml.parseInt32Hex("0X41"))
        assertEquals(65, MiniHtml.parseInt32Hex("+0x41"))
        assertEquals(2147483647, MiniHtml.parseInt32Hex("0x7fffffff"))
        // .NET 抛异常的那些：FormatException / ArgumentException / OverflowException / ArgumentOutOfRange
        assertNull(MiniHtml.parseInt32Hex("0x")) // FormatException（前缀后必须还有数字）
        assertNull(MiniHtml.parseInt32Hex("0xFF__")) // FormatException
        assertNull(MiniHtml.parseInt32Hex("0x0x41")) // FormatException
        assertNull(MiniHtml.parseInt32Hex("-ff")) // ArgumentException（基 16 不收负号）
        assertNull(MiniHtml.parseInt32Hex("-0x41")) // ArgumentException
        assertNull(MiniHtml.parseInt32Hex(" 41")) // FormatException（不吃空白）
        assertNull(MiniHtml.parseInt32Hex("41 ")) // FormatException
        assertNull(MiniHtml.parseInt32Hex("")) // ArgumentOutOfRangeException
        assertNull(MiniHtml.parseInt32Hex("FFFFFFFFF")) // OverflowException（> 32 位）
        // 本族真分歧的四条：.NET 只吃**一个**前导 +，剥完前缀也要复验
        assertNull(MiniHtml.parseInt32Hex("++41"))
        assertNull(MiniHtml.parseInt32Hex("+-41"))
        assertNull(MiniHtml.parseInt32Hex("0x+41"))
        assertNull(MiniHtml.parseInt32Hex("0x-41"))
    }

    /** 第 3/4 条：数字引用与颜色共用同一口径 —— `&#x0x41;` 与 `&#x+41;` 都还原成 `A`（HM :821）。 */
    @Test
    fun `数字引用与颜色共用基 16 口径`() {
        assertEquals("A", m.unescape("&#x0x41;"))
        assertEquals("A", m.unescape("&#x+41;"))
        assertEquals("A", m.unescape("&#X41;"))
        assertEquals("A", m.unescape("&#65;"))
        assertEquals("A", m.unescape("&#065;"))
        // 基 16 负号：Convert 抛 ArgumentException → InvalidCharacterReference（HM :823-826）
        expect(MiniHtmlErrorKind.ENTITY_INVALID_REF, "&#x-1;")
        expect(MiniHtmlErrorKind.ENTITY_INVALID_REF, "&#x++41;")
        // 基 10 负号能解析，落进 num4 < 0 的越界支（HM :827-830）
        expect(MiniHtmlErrorKind.ENTITY_OUT_OF_RANGE, "&#-1;")
        expect(MiniHtmlErrorKind.ENTITY_OUT_OF_RANGE, "&#65536;")
        // 8 位回绕成负数（`Convert.ToInt32("ffffffff",16) == -1`）⇒ 同样是越界支，不是解析失败
        expect(MiniHtmlErrorKind.ENTITY_OUT_OF_RANGE, "&#xffffffff;")
        expect(MiniHtmlErrorKind.ENTITY_OUT_OF_RANGE, "&#x80000000;")
        // 十进制不吃空白（.NET Convert.ToInt32(s,10) 实测 FormatException）→ 解析失败
        expect(MiniHtmlErrorKind.ENTITY_INVALID_REF, "&# 65;")
    }

    /** 第 5/6/7 条：`#` 颜色、命名色、裸十六进制的三条分支（HM :1687-1721）。 */
    @Test
    fun `颜色三分支逐条`() {
        // # 前缀走基 16：0x 前缀与前导 + 都成立（HM :1692）
        assertEquals(0x41, m.toRuns("<font color='#0x41'>A</font>")[0].fore)
        assertEquals(0x41, m.toRuns("<font color='#+41'>A</font>")[0].fore)
        assertEquals(0xFFFFFF, m.toRuns("<font color='#ffffff'>A</font>")[0].fore)
        // 越界 / 不可解析都在裸 catch 里转成 CanNotInterpretNumValue（HM :1690-1701）
        expect(MiniHtmlErrorKind.COLOR_UNPARSABLE, "<font color='#0x+41'>A</font>")
        expect(MiniHtmlErrorKind.COLOR_UNPARSABLE, "<font color='#-ff'>A</font>")
        expect(MiniHtmlErrorKind.COLOR_UNPARSABLE, "<font color='#1000000'>A</font>")
        expect(MiniHtmlErrorKind.COLOR_UNPARSABLE, "<font color='#ffffffff'>A</font>") // 回绕成 -1
        // 裸十六进制：能解析 ⇒ InvalidColorName2（HM :1712-1720），包括 0x 前缀形态
        expect(MiniHtmlErrorKind.COLOR_NAME_REJECTED, "<font color='0x41'>A</font>")
        expect(MiniHtmlErrorKind.COLOR_NAME_REJECTED, "<font color='ffff00'>A</font>")
        // 8 位回绕仍算「能解析」（HM :1714 只问 Convert 成不成功）⇒ InvalidColorName2
        expect(MiniHtmlErrorKind.COLOR_NAME_REJECTED, "<font color='ffffffff'>A</font>")
        // 连基 16 都解析不了 ⇒ InvalidColorName（HM :1718）
        expect(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, "<font color='bogus'>A</font>")
        expect(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, "<font color='0x'>A</font>")
        expect(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, "<font color='++41'>A</font>")
    }

    /** 第 11/12 条：着色快照取最内层帧，且命名色大小写不敏感、不串味。 */
    @Test
    fun `着色快照取最内层帧`() {
        val runs = m.toRuns("<font color='red'>A<font color='#00FF00'>B</font>C</font>D")
        assertEquals(listOf(0xFF0000, 0x00FF00, 0xFF0000, MiniHtml.FORE_DEFAULT), runs.map { it.fore })
        assertEquals(listOf("A", "B", "C", "D"), runs.map { it.text })
        // 颜色名大小写不敏感（HM :1705 的 Color.FromName）
        assertEquals(0xFF0000, m.toRuns("<font color='ReD'>A</font>")[0].fore)
        // 同名属性重复 ⇒ DuplicateAttribute（HM :1626-1628），大小写不影响判定
        expect(MiniHtmlErrorKind.DUPLICATE_ATTRIBUTE, "<font color='red' COLOR='blue'>A</font>")
    }

    /** 第 8 条：`face=''` 落**默认字体**而不是外层帧（HM :1663-1666 + StringStyle.cs:41-48）。 */
    @Test
    fun `font face 空串落默认字体`() {
        assertEquals(MiniHtml.FONT_DEFAULT, m.toRuns("<font face=''>A</font>")[0].fontName)
        assertEquals(MiniHtml.FONT_DEFAULT, m.toRuns("<font face='X'><font face=''>A</font></font>")[0].fontName)
        // 不带属性的 <font> 是 TagHasNotAttribute（HM :1603-1606），继承只发生在「有属性但缺 face」时
        assertEquals("X", m.toRuns("<font face='X'><font color='red'>A</font></font>")[0].fontName)
        expect(MiniHtmlErrorKind.FONT_WITHOUT_ATTRIBUTE, "<font face='X'><font>A</font></font>")
        assertEquals(MiniHtml.FONT_DEFAULT, m.toRuns("A")[0].fontName)
    }

    /** 第 1/2 条：转义表与还原表（`&nbsp;` 还原半角空格是官方行为，HM :783-785）。 */
    @Test
    fun `转义与还原`() {
        assertEquals("&lt;a&gt;&amp;&quot;&apos;", m.escape("<a>&\"'"))
        assertEquals("<a>&\"'", m.unescape("&lt;a&gt;&amp;&quot;&apos;"))
        assertEquals("<", m.unescape("&LT;")) // ToLower（HM :780）
        assertEquals("'", m.unescape("&ApOs;"))
        assertEquals(" ", m.unescape("&nbsp;"))
        assertEquals(1, Grid.cells(m.unescape("&nbsp;")))
        assertEquals("A&amp;B", m.escape("A&B"))
    }

    /** 第 9 条：样式位是异或状态机（HM :977-983、:1075-1080）。 */
    @Test
    fun `样式位异或状态机`() {
        val runs = m.toRuns("<b><i>A</b></i>B")
        assertEquals(Grid.STYLE_BOLD or Grid.STYLE_ITALIC, runs[0].styleMask)
        assertEquals(0, runs[1].styleMask)
        expect(MiniHtmlErrorKind.UNEXPECTED_CLOSE_TAG, "<i>A</b>")
        expect(MiniHtmlErrorKind.DUPLICATE_TAG, "<b><b>A</b></b>")
    }

    /** 第 10 条：`<br>` 与裸换行的行切分（含连发 br 的中间空行、串尾不补空行）。 */
    @Test
    fun `换行切分的行数逐条`() {
        assertEquals(listOf("A", "B"), m.parse("A<br>B").lines.map { it.plainText })
        assertEquals(listOf("A", "B"), m.parse("A\nB").lines.map { it.plainText })
        assertEquals(listOf("A", "", "B"), m.parse("A<br><br>B").lines.map { it.plainText })
        assertEquals(1, m.parse("A<br>").lines.size) // HM :675-678 串尾残留为空不成行
        assertEquals(1, m.parse("<br>").lines.size)
        assertEquals(0, m.parse("").lines.size)
    }

    /** `p` 的对齐列换算与 `ConsoleDisplayLine.cs:79-84` 同式（整数除、超宽为负）。 */
    @Test
    fun `align 列换算`() {
        val center = m.parse("<p align='center'>AB")
        assertEquals(MiniHtmlAlign.CENTER, center.align)
        assertEquals(3, center.alignColumn(4, 10)) // 10/2 - 4/2
        assertEquals(MiniHtmlAlign.RIGHT, m.parse("<p align='RIGHT'>AB").align)
        assertEquals(6, m.parse("<p align='right'>AB").alignColumn(4, 10)) // 10 - 4
        assertEquals(-2, m.parse("<p align='right'>AB").alignColumn(12, 10))
    }
}
