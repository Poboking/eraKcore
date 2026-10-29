package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.CellWidth
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FN-02 半边（M11-B L10）：`<div>` 子文本**不**计入父行首行宽，div 节点本身按 0 宽参与。
 *
 * ## 对应的原版语义（每条带 C# 行号，相对 reference/emuera-cs/）
 *
 * 1. `ConsoleDivPart` 进入**父**节点表（HtmlManager.cs:646），但它的 `SetWidth` 是
 *    **空实现**（ConsoleDivPart.cs:217-219）⇒ `Width` 停在 `AConsoleDisplayNode` 的
 *    字段缺省 0（AConsoleDisplayNode.cs:27），`ConsoleButtonString.CalcWidth` 逐节点
 *    累加时 div 贡献 0（ConsoleButtonString.cs:266-270）。
 * 2. div 子行的宽是递归子解析自己的产物（HtmlManager.cs:636-642 把
 *    `html2DisplayLine` 的返回值挂到 `CurrentDivTag.Lines`），父行的
 *    `Buttons[].Width`（HtmlManager.cs:176-181）完全不吃这部分 —— 修复前本层把子组
 *    宽累计进父行，就是缺陷总账 FN-02 的「子文本重复计入首行宽」。
 * 3. div 段 `CanDivide = false`（ConsoleDivPart.cs:66）、`Text = string.Empty`（:102）
 *    ⇒ 折行拆分里贡献 0 个字符（PrintStringBuffer.cs:438 的 `Text.Length`）；
 *    0 宽节点恒放得下（:427 的 `num + 0 > divWidth` 恒假），不触发折行。
 * 4. div-only 的组零宽 ⇒ `CalcWidth :273-276` 记 **-1**（与 shape 零宽组同口径，
 *    修复前读数见 [HtmlPrintedWidthTest] 的「改前 vs 改后」节）。
 * 5. 缺省单位换算（HtmlMethods.kt:83-85）：`-1px` → `-2/18 - 1 = -1` 格。
 *
 * 与 [HtmlStrictTest] / [HtmlPrintedWidthTest] 的分工：那边是回归矩阵，这里集中钉
 * div 宽度口径本身（修钉 4 处之外的增量用例都落本文件）。
 */
private class DwBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("DwBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun dwCtx(): BuiltinEvalContext {
    val store = VariableStore()
    val base = DwBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, object : BuiltinHost {
        override val configOverrides: Map<String, String> = mapOf("WINDOW WIDTH" to "1800")
    })
}

/** `HTML_STRINGLEN(html, 1)` = 第一条显示行的像素宽。 */
private fun dwPx(html: String): Long =
    (dwCtx().callFunction("HTML_STRINGLEN", listOf(Value.StrV(html), Value.IntV(1))) as Value.IntV).v

/** `HTML_STRINGLEN(html)` = `2 * 像素 / FontSize`（HtmlMethods.kt:83-85 的向零/背向取整）。 */
private fun dwCells(html: String): Long =
    (dwCtx().callFunction("HTML_STRINGLEN", listOf(Value.StrV(html))) as Value.IntV).v

/** 直接驱动解析器（小折行宽做可读断言；0 = 不折行），与 pwLen 同形。 */
private fun dwLen(html: String, drawablePx: Long, fontSize: Long = 18L): Long =
    HtmlStrictParser(html, { CellWidth.of(it).toLong() * (fontSize / 2) }, { it }, fontSize, 0xFFFF00, drawablePx, collectRows = false)
        .length().toLong()

class HtmlDivWidthTest {

    @Test
    fun `div 子文本不计入父行宽 前后文本照常相加`() {
        // 证据 1/2：div 节点 0 宽（ConsoleDivPart.cs:217-219 + AConsoleDisplayNode.cs:27
        // ⇒ ConsoleButtonString.cs:266-270），子行宽留在递归层（HtmlManager.cs:636-642）
        assertEquals(36L, dwPx("AB<div width='10px' height='10px'>x</div>CD")) // 18 + 0 + 18
        assertEquals(4L, dwCells("AB<div width='10px' height='10px'>x</div>CD"))
        // div 内带 <br> 的多子行同样整体不进父行（修复前子行宽从 closeDiv/子 br 漏进父行）
        assertEquals(36L, dwPx("AB<div width='10px' height='10px'>x<br>y<br>z</div>CD"))
        // div 内的按钮组/图元段也不进父行（语料 TEMP_CHARA_LIST_BY_DT.ERB:438 形状的最小化）；
        // 组在子层被丢弃（openButtonTag / closeButtonGroup 的 commitGroup 落在 div 帧内）
        assertEquals(-1L, dwPx("<div width='10px' height='10px'><button value='7'>设置</button></div>"))
        assertEquals(-1L, dwPx("<div width='10px' height='10px'><img src='a.png'></div>"))
    }

    @Test
    fun `div only 组零宽记 -1 与 shape 零宽组同口径`() {
        // 证据 4：CalcWidth 累加 <= 0 ⇒ Width = -1（ConsoleButtonString.cs:273-276）；
        // div 的 0 来自空 SetWidth（ConsoleDivPart.cs:217-219），不是记「无节点」
        assertEquals(-1L, dwPx("<div width='10px' height='10px'>AB</div>"))
        assertEquals(-1L, dwCells("<div width='10px' height='10px'>AB</div>")) // -2/18-1 = -1
    }

    @Test
    fun `div 零宽不占折行预算 也不触发折行`() {
        // 证据 3：getDivideIndex 的 `num + 0 > divWidth` 恒假（PrintStringBuffer.cs:427），
        // CanDivide=false / Text.Length=0（ConsoleDivPart.cs:66/:102）⇒ 拆分零贡献
        // 第一组 10 字正好 90px；div(0) 仍放得下；第三组放不下 ⇒ 二分后首行止于 90px
        assertEquals(90L, dwLen("ABCDEFGHIJ<div width='10px' height='10px'>x</div>ABCDEFGHIJK", 90L))
        // div 前后文本同组、总宽不超 ⇒ 原样相加
        assertEquals(36L, dwLen("AB<div width='10px' height='10px'>x</div>CD", 90L))
    }

    @Test
    fun `首行已结束后的 div 不复活父行量宽`() {
        // measuring 在首个 <br> 已关：div 与其后文本都只校验不量宽（修复前后一致，回归哨兵）
        assertEquals(18L, dwPx("AB<br><div width='10px' height='10px'>x</div>CD"))
        assertEquals(18L, dwPx("AB\n<div width='10px' height='10px'>x</div>CD"))
    }

    @Test
    fun `既有非 div 读数不因本路变化`() {
        // 基准 pc-golden-root.txt:297（GOLDEN_02_PRINT.ERB:255）与纯文本快速路径哨兵
        assertEquals(4L, dwCells("<i>AB</i>CD"))
        assertEquals(36L, dwPx("<i>AB</i>CD"))
        assertEquals(4L, dwCells("ABCD"))
        // FN-01 既有读数（HtmlPrintedWidthTest 同款）：img AltText 与 space 折算不受 div 改动影响
        assertEquals(153L, dwPx("<img src='a.png'>"))
        assertEquals(-1L, dwPx("<shape type='space' param='5'>"))
    }
}
