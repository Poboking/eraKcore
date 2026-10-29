package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.CellWidth
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * FN-01（`<img>` / `<shape>` 的打印宽）+ FN-03（按 `Config.DrawableWidth` 自动折行）。
 *
 * ## 本文件钉的是**原版**的哪几步
 *
 * 1. `HtmlManager.HtmlLength`（HtmlManager.cs:168-182）：解析整串后只累加
 *    `array[0].Buttons[].Width` ⇒ 读数 = **第一条显示行**的像素宽（单位与
 *    `HTML_STRINGLEN(html, 1)` 一致）。
 * 2. `ConsoleImagePart`（ConsoleImagePart.cs:60-80、:104-115）与
 *    `ConsoleShapePart.CreateShape`（ConsoleShapePart.cs:46-79）+ 三个子类的 `SetWidth`
 *    （ConsoleSpacePart.cs:18-23、ConsoleRectangleShapePart.cs:50-58、
 *    ConsoleErrorShapePart.cs:28-37）：图元段按**属性折算成像素**计入，不是 0（FN-01）。
 * 3. `PrintStringBuffer.ButtonsToDisplayLines`（PrintStringBuffer.cs:205、:217-241）+
 *    `getDivideIndex`（:414-451、:453-496）：`PointX + Width > DrawableWidth` 时折行，
 *    第一条显示行在**能放下的最长前缀**处收尾；`nobr` 不折（:698 把 `FlagNobr` 传进去）（FN-03）。
 * 4. `ConsoleButtonString.CalcWidth`（ConsoleButtonString.cs:262-276）：组宽 <= 0 时写 **-1**
 *    ⇒ 零宽组在 `HTML_STRINGLEN` 里记 -1px（官方怪癖，ADR-005 照抄）。
 * 5. `HtmlStringLenMethod.GetIntValue`（FunctionMethodCreator.cs:54-66）：省略第二参 ⇒
 *    `2 * 像素 / FontSize`（向上取整），非 0 ⇒ 原始像素。
 *
 * 量具只有一把：文本段宽度走注入的 `measure`，生产侧 = `HtmlMethods.approxTextWidth`，
 * 其全角/半角名单唯一取自 [CellWidth]（装载期 `GETLINESTR` 同源）；本文件的夹具度量
 * [pwMeasure] 也按同一把尺写（`CellWidth.of(s) * FontSize/2`），不另建表。
 *
 * ## 改前 vs 改后（契约 §3.1 第一步登记，逐条）
 *
 * 改动前本文件曾断言过下列读数（即缺陷总账 FN-01 / FN-03 记的现状），现已按原版口径改写：
 * - `<img src='a.png'>` → 0px（FN-01：图元记 0）⇒ 现 153px
 * - `<img src='a.png'>AB` → 2 格 ⇒ 现 19 格
 * - `<shape type='space' param='50px'>` → 0px ⇒ 现 50px
 * - `<shape type='rect' param='1,2,3,4'>AB` → 18px（形状记 0）⇒ 现仍 18px
 *   （四参 rect 的折算宽 `(int)(0.18f + 0.54f) = 0`，与「记 0」同值但**是算出来的**，
 *   见 [HtmlStrictParser.shapeGroupNode]）
 * - 200 个半角 `A`（带标签、走解析器）→ 1800px（FN-03：不折行）⇒ 现 1791px
 *   （DrawableWidth 1797 内最多 199 字）；纯文本输入另见下方「只能落地一半」第 1 条
 * - `<div …>12 个半角</div>`（M11-B L10 / FN-02 半边）→ 108px（子文本重复计入）⇒
 *   现 -1px（div 节点 0 宽：ConsoleDivPart.cs:217-219 + AConsoleDisplayNode.cs:27 ⇒
 *   ConsoleButtonString.cs:266-270；零宽组记 -1 见 :273-276）
 *
 * `DrawableWidth` 缺省取 **1797px**（PC 基准环境：`emuera.config:12 WINDOW WIDTH:1800`
 * + `:22 FONT SIZE:18`，`Config.cs:461-466`）；真机读数随设备屏宽变化，
 * 取舍与接线需求逐条写在 `HtmlStrictParser.drawableWidthPx` 的 KDoc。
 *
 * ## 本路只能落地一半的地方（逐条见交付报告「需组长接线」）
 *
 * 1. **纯文本快速路径绕过折行**：`HTML_STRINGLEN` 的入口 `HtmlMethods.htmlLength`（
 *    `HtmlMethods.kt:344-349`）对「无 `<`、无 `&`、无裸换行」的输入直接量字符串，
 *    **不进本解析器** ⇒ 200 个 `A` 走函数面仍是 1800px，而原版是 1791px
 *    （原版 `HtmlLength` 对任何输入都跑 `Html2DisplayLine`）。改那一行不在本路文件面，
 *    故本文件把纯文本折行的验收面钉在解析器层（[pwLen]，见「基准可绘制宽」用例），
 *    **不**断言函数面那个 1800（免得把缺陷钉成期望值）。
 * 2. `htmlLength` 也没把 `host.fontSize` / 可绘制宽传给解析器（现在恒用缺省 18 与 1797px）
 *    ⇒ `HTML_STRINGLEN` 的 `FontSize` 覆写与真机屏宽都要求组长一并接。
 */
private class PwBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("PwBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/**
 * 装置宿主。
 *
 * `configOverrides` 里写 `WINDOW WIDTH:1800` 是为了把**可绘制宽钉回 PC 基准环境的 1797px**：
 * `htmlLength` 现在按真值接线（`EffectiveConfig.drawableWidth` = `Config.cs:461-466`），
 * 而条目表缺省是 `ConfigSpec` 的 760（`:113`）⇒ 不钉这一条，本文件的折行用例量的就不是
 * 基准那一屏（语料 `emuera.config:12` 正是 `WINDOW WIDTH:1800`）。
 */
private fun pwCtx(): BuiltinEvalContext {
    val store = VariableStore()
    val base = PwBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, object : BuiltinHost {
        override val configOverrides: Map<String, String> = mapOf("WINDOW WIDTH" to "1800")
    })
}

/** `HTML_STRINGLEN(html, 1)` = 第一条显示行的**像素**宽。 */
private fun pwPx(html: String): Long =
    (pwCtx().callFunction("HTML_STRINGLEN", listOf(Value.StrV(html), Value.IntV(1))) as Value.IntV).v

/** `HTML_STRINGLEN(html)` = `2 * 像素 / FontSize`（向上取整）。 */
private fun pwCells(html: String): Long =
    (pwCtx().callFunction("HTML_STRINGLEN", listOf(Value.StrV(html))) as Value.IntV).v

/** 夹具度量：与生产侧同一把尺（[CellWidth] 的格数 × 半角步进 `FontSize/2`）。 */
private fun pwMeasure(fontSize: Long): (String) -> Long = { s -> CellWidth.of(s).toLong() * (fontSize / 2) }

/** 直接驱动解析器，以便用小折行宽做可读的断言（0 = 不折行）。 */
private fun pwLen(html: String, drawablePx: Long, fontSize: Long = 18L): Long =
    HtmlStrictParser(html, pwMeasure(fontSize), { it }, fontSize, 0xFFFF00, drawablePx, collectRows = false)
        .length().toLong()

class HtmlPrintedWidthTest {

    // ===== FN-01：img / shape 的打印宽（像素） =====

    @Test
    fun `img 无 width 属性时按 AltText 上屏 宽度即该文案的度量`() {
        // ConsoleImagePart.cs:60-64 + :110-114：取不到图元 ⇒ Text = AltText，宽 = 文本度量
        // AltText 文案由 openImg 按 ConsoleImagePart.cs:45-58 的固定次序重建
        assertEquals("<img src='a.png'>", htmlPrintDocument("<img src='a.png'>").rows.single().runs.single().text)
        assertEquals(153L, pwPx("<img src='a.png'>")) // 17 个半角字符 × 9px
        assertEquals(17L, pwCells("<img src='a.png'>"))
        assertEquals(171L, pwPx("<img src='a.png'>AB")) // 图元段 + 文本段同组相加
        assertEquals(19L, pwCells("<img src='a.png'>AB"))
    }

    @Test
    fun `img 的 width 属性 px 与百分比两条折算`() {
        assertEquals(100L, pwPx("<img src='a' width='100px'>")) // ConsoleImagePart.cs:72-75
        assertEquals(12L, pwCells("<img src='a' width='100px'>")) // 2*100/18 向上取整
        assertEquals(18L, pwPx("<img src='a' width='100'>")) // :77-79 百分比 = FontSize*100/100
        assertEquals(27L, pwPx("<img src='a' width='150'>")) // 18*150/100 = 27
        // `num == 0` 的属性整条不写（Utils.AddTagMixedNumArg，Utils.cs:364-371），
        // 于是 openImg 重建出的 AltText 里没有 width ⇒ 落到「取不到图元」的文案回落，
        // 读数 = measure("<img src='a.png'>") + measure("AB") = 153 + 18。
        assertEquals(153L + 18L, pwPx("<img src='a.png' width='0px'>AB"))
    }

    @Test
    fun `img 的百分比余数进亚像素并被后续 shape 吸收`() {
        // Utils/ConsoleImagePart.cs:78-79：18*111/100 = 19（整除），余数 0.98 进 XsubPixel
        // ConsoleSpacePart.cs:18-23：shape 的宽 = (int)(subPixel + WidthF)
        assertEquals(19L + 5L, pwPx("<img src='a' width='111'><shape type='space' param='5px'>"))
        assertEquals(19L, pwPx("<img src='a' width='111'>"))
    }

    @Test
    fun `shape space 与 rect 的宽按 param 折算`() {
        // ConsoleShapePart.cs:53 / :59：非 px 时 WidthF = num * FontSize / 100f（浮点！）
        assertEquals(50L, pwPx("<shape type='space' param='50px'>"))
        assertEquals(9L, pwPx("<shape type='space' param='50'>")) // 50*18/100f = 9.0 ⇒ (int)9
        // 四参 rect 把自己的横向偏移也吃进宽（ConsoleRectangleShapePart.cs:27）
        assertEquals(4L, pwPx("<shape type='rect' param='0, 30, 24, 64'>")) // 0.0 + 24*18/100 = 4.32 ⇒ 4
        assertEquals(120L, pwPx("<shape type='rect' param='20px,0,100px,10'>")) // 20 + 100 = 120
        // 校验不过（ConsoleShapePart.cs:64 的 x>=0 && w>0 && h>0）⇒ 错误形态，宽 = 文案度量
        // 未知 type：文案 = "<shape type='circle' param='10'>"（32 个半角字）
        assertEquals(32L * 9L, pwPx("<shape type='circle' param='10'>"))
        // 四参但 x < 0：文案 = "<shape type='rect' param='-1, 0, 100, 10'>"（42 个半角字）
        assertEquals(42L * 9L, pwPx("<shape type='rect' param='-1,0,100,10'>"))
    }

    @Test
    fun `零宽组记 -1px 官方怪癖照抄`() {
        // ConsoleButtonString.CalcWidth :273-276：累加后 Width <= 0 ⇒ 写成 -1
        // 单个 `param='5'` 的 space：WidthF = 5*18/100f = 0.9 ⇒ (int)(0.0 + 0.9) = 0 ⇒ 组宽 -1
        assertEquals(-1L, pwPx("<shape type='space' param='5'>"))
        // 同组内两段：余数累加 0.9 + 0.9 = 1.8 ⇒ 第二段得 1px ⇒ 组宽 1
        assertEquals(1L, pwPx("<shape type='space' param='5'><shape type='space' param='5'>"))
    }

    @Test
    fun `四参 rect 的既有读数不因 FN-01 变化`() {
        // HtmlStrictTest 的合法矩阵钉的是 `2 格`：折算宽本身是 0（(int)(0.18f + 0.54f)），
        // 与旧的「记 0」巧合同值 —— 现在这个 0 是算出来的，不是近似。
        assertEquals(18L, pwPx("<shape type='rect' param='1,2,3,4'>AB"))
        assertEquals(2L, pwCells("<shape type='rect' param='1,2,3,4'>AB"))
    }

    // ===== FN-03：按 DrawableWidth 折行 =====

    @Test
    fun `基准可绘制宽 1797px 内最多 199 个半角字`() {
        // PrintStringBuffer.cs:205 的 num = Config.DrawableWidth、:217 的 `PointX + Width <= num`
        // getDivideIndex 的二分（:453-496）取「整段前缀宽 <= 剩余额度」的最大字符数
        val a199 = "A".repeat(199)
        val a200 = "A".repeat(200)
        // 走解析器（带标签）才是本路的验收面：1791 <= 1797 ⇒ 不折；1800 > 1797 ⇒ 折到 199 字
        assertEquals(1791L, pwPx("<b>$a199</b>"))
        assertEquals(1791L, pwPx("<b>$a200</b>"))
        assertEquals(199L, pwCells("<b>$a200</b>"))
        // 纯文本同样在解析器里折（原版对任何输入都跑 Html2DisplayLine）
        assertEquals(1791L, pwLen(a200, 1797L))
        assertEquals(1791L, pwLen(a199, 1797L))
        // 对照：显式关掉折行 = 改动前的读数
        assertEquals(1800L, pwLen(a200, 0L))
    }

    @Test
    fun `nobr 行不折行 与原版把 FlagNobr 交给折行函数一致`() {
        // HtmlManager.cs:698 → ButtonsToDisplayLines(nobr = state.FlagNobr) → :217 短路
        assertEquals(1800L, pwPx("<nobr>" + "A".repeat(200)))
    }

    @Test
    fun `折行宽度小样 半角与全角都按最长可放前缀收尾`() {
        assertEquals(90L, pwLen("ABCDEFGHIJ", 90L)) // 正好放满
        assertEquals(90L, pwLen("ABCDEFGHIJK", 90L)) // 99px > 90 ⇒ 切到 10 字
        assertEquals(90L, pwLen("ABCDEFGHIJKL", 90L))
        assertEquals(108L, pwLen("ABCDEFGHIJKL", 0L)) // 不折行
        // 全角字每字 18px：6 字 = 108px > 90 ⇒ 最多 5 字 = 90px
        assertEquals(90L, pwLen("１２３４５６", 90L))
    }

    @Test
    fun `折行按按钮组比宽 组与组之间整组挪行`() {
        // 组边界 = FlagButton（HtmlManager.cs:1011/:1019/:1559 → :659-661 cssToButton）
        // 第一组 "ABCDE" = 45px；第二组 7 字 = 63px ⇒ 45 + 63 = 108 > 90 ⇒ 二分取 45px 前缀
        assertEquals(90L, pwLen("ABCDE<button value='1'>FGHIJKL</button>", 90L))
        // 整组放不下且拆不出（组内只有不可拆的图元段）⇒ 空行也容它一条（:236-239）
        assertEquals(100L, pwLen("<img src='a' width='100px'>", 90L))
        // 图元段本身超宽，但它前面有放得下的文本 ⇒ 文本留下、图元挪行（DivideAt :198-229）
        assertEquals(18L, pwLen("AB<img src='a' width='100px'>CD", 90L))
    }

    @Test
    fun `br 仍然结束第一条显示行 折行不改变它的口径`() {
        // 换行与折行都是「第一条显示行的终点」，两者叠加时取先到者
        assertEquals(90L, pwLen("ABCDEFGHIJKL<br>ABC", 90L))
        assertEquals(90L, pwLen("ABCDEFGHIJKL\nABC", 90L)) // 裸换行同 br（HtmlManager.cs:588-592）
        assertEquals(36L, pwLen("ABCD<br>EFGH", 90L))
        assertEquals(36L, pwLen("ABCD\nEFGH", 90L))
    }

    @Test
    fun `div 子解析不按 DrawableWidth 折行 FN-02 不在本路`() {
        // 子层的折行宽是 SubDivisionWidth（HtmlManager.cs:619-635 + :698 的 subDiv），
        // 而浮动块的版面语义是 FN-02 ⇒ 本路只保证子层不按父行宽折。
        // FN-02 半边（本波）：div 子内容（12 字 = 108px）不进父行宽 —— ConsoleDivPart.SetWidth
        // 空实现（ConsoleDivPart.cs:217-219）+ AConsoleDisplayNode.cs:27 缺省 0
        // ⇒ ConsoleButtonString.cs:266-270 累加得 0，div-only 组零宽按 CalcWidth :273-276
        // 记 -1（修复前子文本重复计入 → 108）。
        assertEquals(-1L, pwLen("<div width='1' height='1'>ABCDEFGHIJKL</div>", 90L))
    }

    // ===== 语法校验不因本路改变（ADR-005） =====

    @Test
    fun `畸形输入的抛错口径不变`() {
        val e = assertFailsWith<CodeEE> { pwPx("<img src='a' width='xx'>AB") }
        assertEquals("<img>标签的width属性的属性值无法被解析为数值", e.message)
        val e2 = assertFailsWith<CodeEE> { pwPx("<shape type='rect'>AB") }
        assertEquals("<shape>标签没有设定param属性", e2.message)
    }
}
