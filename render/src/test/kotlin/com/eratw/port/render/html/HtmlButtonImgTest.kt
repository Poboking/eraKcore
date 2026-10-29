package com.eratw.port.render.html

import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `<button>` 区间内图形段的按钮身份（总账 BUG-GFX-02 供给侧，B5 L2）。
 *
 * ## 判据来源（原版逐行）
 *
 * 原版一条按钮的 `AConsoleDisplayNode[] strArray` **同时**装文本片与图片片
 * （`ConsoleButtonString.cs:19`）：`html2DisplayLine` 把文本片
 * （`HtmlManager.cs:559,573` 的 `list.Add(new ConsoleStyledString(...))`）与标签节点
 * （`HtmlManager.cs:596-604` 追加 `tagAnalyze` 的产物；img 分支 `:1221` 产出
 * `ConsoleImagePart`）追加进**同一个** `list`，`</button>` 置 `FlagButton`
 * （`:1005-1011`）后由 `cssToButton` 把整张列表打包成**一条** `ConsoleButtonString`
 * （`:659-661`、`:836-854`），按钮值取自 `LastButtonTag` 的
 * `ButtonValueInt/ButtonValueStr`（`:1543-1545`）——整条按钮（含其中的图片片）共享一份值。
 * 图片片 `Text` 是空串、字符跨度记 0（`ConsoleImagePart.cs:41`），像素盒却非零
 * （`ConsoleImagePart.cs:66-82` 写死的 `Width`；`ConsoleButtonString.cs:292-303` 的
 * `CalcPointX` 逐节点按 `Width` 推进），所以它在按钮里照样占一段命中区。
 *
 * 于是展平侧的口径：`<button>` 区间内切出的 `RunKind.IMAGE`/`SHAPE` 段必须带
 * `buttonValue` 与 `buttonId`，且**同一源按钮的多段共用同一编号**（命中层按源身份
 * 合并，不按「值相同」并 —— 原版嵌套按钮直接 CodeEE，`HtmlManager.cs:1479-1482`，
 * 两个同值按钮天然是两条）。区间外保持 null/-1：原版只有源按钮区间才带可点身份，
 * 连无 `value` 属性的 `<button>` 都 `IsButton = text7 != null` 判假（`:1547`）。
 *
 * 语料形态：`ERB/BODY_INFO/GET_BODY_SETS.ERB:115` 的部位按钮
 * `<button value='{90000+bitPart}'><img src='%src%' srcb='%srcb%' width='{width}px'
 * height='{height}px'></button>` —— 按钮里只有图、没有文字片，命中区全靠图段带身份。
 *
 * 折 Long 口径（`ButtonLayer.selectedString` 的 INT 闸只放行 Long）沿用
 * `HtmlRunFlatten.openTag` 的 button 分支既有实现，本组用例断言的是**透传**：
 * 纯数字落 Long、非数字落 String。
 *
 * 夹具同 `adapter/HtmlRunFlattenTest`：字号 18px ⇒ 半角步进 9.36px，
 * `width='60px'` → 7 格、`width='20px'` / `param='20px'` → 3 格（ceil 手算字面值）。
 */
class HtmlButtonImgTest {

    private val metrics = CellMetrics(18)

    private fun htmlRun(html: String) =
        TextRun(html, fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.HTML)

    private fun flatten(html: String, flat: HtmlRunFlatten = HtmlRunFlatten(metrics)): List<TextRun> =
        flat.flatten(htmlRun(html))

    /** 语料那条部位按钮（GET_BODY_SETS.ERB:115 形态）：唯一的图段带值、带编号，kind 仍是 IMAGE。 */
    @Test
    fun `button 内的 img 段落 buttonValue 与 buttonId`() {
        val runs = flatten("<button value='90001'><img src='bodyGraph_A' srcb='bodyGraph_B' width='60px' height='30px'></button>")
        val run = runs.single()
        assertEquals(RunKind.IMAGE, run.kind, "接缝形态：kind 不改 BUTTON，命中层按 buttonId 收图段")
        assertTrue(run.graphic is InlineGraphic.Image)
        assertEquals(7, (run.graphic as InlineGraphic).widthCells, "ceil(60 / 9.36) = 7")
        assertEquals(90001L, run.buttonValue, "纯数字折 Long（与文本段同口径，INT 闸只放行 Long）")
        assertEquals(1, run.buttonId)
    }

    /** 一条按钮的多段（文本片 + 图片片 + shape 片）共用同一编号与同一份值（cssToButton 整表打包）。 */
    @Test
    fun `同一按钮的文本段与图形段共用一个编号`() {
        val runs = flatten("<button value='77'>甲<img src='a' width='20px' height='20px'>乙<shape type='rect' param='20px'>丙</button>")
        assertEquals(listOf("甲", "   ", "乙", "   ", "丙"), runs.map { it.text })
        assertEquals(
            listOf(RunKind.BUTTON, RunKind.IMAGE, RunKind.BUTTON, RunKind.SHAPE, RunKind.BUTTON),
            runs.map { it.kind },
        )
        assertEquals(listOf(1, 1, 1, 1, 1), runs.map { it.buttonId }, "编号只在开标签处递增")
        assertTrue(runs.all { it.buttonValue == 77L }, "整条按钮共享一份值（HtmlManager.cs:836-854）")
    }

    /** 两条同值按钮是两条源按钮：值相同、编号必须不同（命中层按源身份合并，不按值相同并）。 */
    @Test
    fun `相邻同值按钮各发一个编号`() {
        val runs = flatten(
            "<button value='5'><img src='a' width='20px' height='20px'></button>" +
                "<button value='5'><img src='b' width='20px' height='20px'></button>",
        )
        assertEquals(2, runs.size)
        assertEquals(listOf(RunKind.IMAGE, RunKind.IMAGE), runs.map { it.kind })
        assertEquals(listOf(5L, 5L), runs.map { it.buttonValue })
        assertEquals(listOf(1, 2), runs.map { it.buttonId })
    }

    /** 按钮区间外的图段值为 null、编号 -1（原版非按钮区间的节点不产候选）。 */
    @Test
    fun `按钮外的 img 段不带身份`() {
        val lone = flatten("<img src='a' width='20px' height='20px'>").single()
        assertEquals(RunKind.IMAGE, lone.kind)
        assertNull(lone.buttonValue)
        assertEquals(-1, lone.buttonId)

        // 闭合之后的图段同样不带：closeTag 的复位要能盖到图形段
        val after = flatten(
            "<button value='9'><img src='a' width='20px' height='20px'></button>" +
                "<img src='b' width='20px' height='20px'>",
        )
        assertEquals(2, after.size)
        assertEquals(9L, after[0].buttonValue)
        assertEquals(1, after[0].buttonId)
        assertNull(after[1].buttonValue, "`</button>` 之后是普通区间")
        assertEquals(-1, after[1].buttonId)
    }

    /** 无 value 属性的 `<button>`：原版 `IsButton = text7 != null` 判假（HtmlManager.cs:1547），图段不带身份。 */
    @Test
    fun `无 value 的 button 内 img 段仍是 null`() {
        val run = flatten("<button><img src='a' width='20px' height='20px'></button>").single()
        assertEquals(RunKind.IMAGE, run.kind)
        assertNull(run.buttonValue)
        assertEquals(-1, run.buttonId)
    }

    /** 非数字值保持字符串（与文本段既有口径一致），编号照发。 */
    @Test
    fun `非数字按钮值的 img 段落字符串`() {
        val run = flatten("<button value='NEXT'><img src='a' width='20px' height='20px'></button>").single()
        assertEquals("NEXT", run.buttonValue)
        assertEquals(1, run.buttonId)
    }

    /**
     * 逐串 HTML 复位按钮态：原版每串 HTML 新建一份 `HtmlAnalzeState`
     * （`HtmlManager.cs:536`），上一串未闭合的 `<button>` 不许把下一串（下一行）
     * 区间外的图段折成可点。
     */
    @Test
    fun `未闭合按钮不泄漏到下一串 html 的图段`() {
        val flat = HtmlRunFlatten(metrics)
        val first = flatten("<button value='3'>文字", flat)
        assertEquals(RunKind.BUTTON, first.single().kind, "本串内未闭合的按钮照常带身份（宽容口径）")
        assertEquals(3L, first.single().buttonValue)
        val second = flatten("<img src='a' width='20px' height='20px'>", flat)
        val run = second.single()
        assertEquals(RunKind.IMAGE, run.kind)
        assertNull(run.buttonValue, "下一串是新状态（HtmlManager.cs:536），泄漏即违背「只有源按钮区间才带可点身份」")
        assertEquals(-1, run.buttonId)
    }
}
