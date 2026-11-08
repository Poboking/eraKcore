package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Error.DuplicateTag`（`<p>标签重复使用` 一族）的判式对齐钉板。
 *
 * 起因：端点上报 `CodeEE：<p>标签重复使用 @ 删除立绘履历 行21`
 * （0.2.35 blackshark SKW-A0，栈走 `BeginFlow.stepTrainCallEventTrain` →
 * `callScriptFunction` → `runEntry`）。上报行号是 **0 基**
 * （`lexer/LogicalLineReader.kt:181-182`，与原版 `EraStreamReader.LineNo` 同口径，
 * `EraStreamReader.cs:30`）⇒ 语句在 `ERB/魔改内容/qol/qol_EVENTCOMEND.ERB` 文件第 22 行
 * `L_STR:LOCAL = <clearbutton>%HTML_GETPRINTEDSTR(LOCAL)%</clearbutton>`，
 * 即**读取回行体**那一条，不是第 26 行的 `HTML_PRINT`（sink 的 `printHtml` 不跑严格解析，
 * 见 `fn/HtmlMethods.kt:310-317` 的推迟口径）。本类只钉「判式与原版逐形相同」这一件事。
 *
 * 原版抛点全集（`reference/emuera-cs/`）：
 * - `HtmlManager.cs:1075-1078`：`b/i/u/s` 开标签，判据 `(state.FontStyle & fontStyle) != 0`，
 *   文案的 `{0}` 用**原样读到的标识符** `text`（不是 ToLower 后的名字）。
 * - `HtmlManager.cs:1097-1100`：`<nobr>`，判据 `state.FlagNobr`；`{0}` 硬编码小写 `nobr`（:1099）。
 * - `HtmlManager.cs:1113-1116`：`<p align=…>`，判据 **`state.FlagNobr`**（不是 `FlagP`！）；
 *   `{0}` 硬编码小写 `p`。=> 官方自己那条「复制粘贴」判式：`<p>` 的重复检查打在 `nobr` 上。
 *   闭标签侧 `</p>`（:985-990）、`</nobr>`（:992-997）只置 `FlagPClosed`/`FlagNobrClosed`，
 *   **不清** `FlagP`/`FlagNobr` ⇒ 同串里第二次开 `<nobr>` 必判重复。
 * - 状态作用域：`HtmlAnalzeState` 在每次 `html2DisplayLine` 调用里新建（:536），
 *   `LineHead` 初值 true（:94）且**只被文本段置 false**（:574），换行/`<br>` 不重置
 *   （:588-592 只置 `FlagBr`）；`<div>` 的递归子解析另起一份状态（:537-544 只搬 5 个字段，
 *   样式位/`LineHead`/`FlagNobr`/`FlagP`/对齐全部重置）。
 * - 抛出后不继续：`tagAnalyze` 直接 `throw new CodeEE`，`HtmlLength`（:168-182）与
 *   `PrintHtml` 同一条路 ⇒ 原版是硬失败，没有任何 `Config` 开关把它降级成警告。
 *
 * 我们侧一一对应（`HtmlStrict.kt`）：样式位 [HtmlStrictParser] 的 `styleOpen`（:604-611）、
 * `nobr`（:584-589）、`p`（:614-629 的 `if (flagNobr)`）；状态生命周期见 `drive()`
 * （:200-247，`lineHead` 只在 :222 文本段置 false）与 `openDiv`（:786-828）。
 * ⇒ **判式集合与触发条件逐条等价，本类不新增也不收窄任何一条**。
 */
private class DupBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("DupBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

private fun dupCtx(): BuiltinEvalContext {
    val store = VariableStore()
    val base = DupBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, object : BuiltinHost {})
}

/** `HTML_STRINGLEN` 的读数；畸形输入由 [dupErrorOrNullQuiet] 那条路径单独断言，这里不允许抛。 */
private fun dupLen(html: String): Long =
    (dupCtx().callFunction("HTML_STRINGLEN", listOf(Value.StrV(html))) as Value.IntV).v

private fun dupOk(html: String) = assertNull(dupErrorOrNullQuiet(html), "不该抛错：$html")

private fun dupErrorOrNullQuiet(html: String): String? =
    try {
        dupLen(html)
        null
    } catch (e: CodeEE) {
        e.message
    }

class HtmlStrictDuplicateTagTest {

    /**
     * 原版会判重复的**全部六种开标签**逐形钉死（HM :1075-1078 / :1097-1100 / :1113-1116）。
     * 文案取 emuera-zhs.xml:806 `Error.DuplicateTag = <{0}>标签重复使用`。
     */
    @Test
    fun `原版判重复的六形逐形同判`() {
        val cases = listOf(
            // b/i/u/s：{0} = 原样读到的标识符（HM :1077 传的是 text，不是 ToLower 结果）
            "<b><b>" to "<b>标签重复使用",
            "<i><i>" to "<i>标签重复使用",
            "<u><u>" to "<u>标签重复使用",
            "<s><s>" to "<s>标签重复使用",
            "<B><B>" to "<B>标签重复使用",
            // 回显的是**第二枚**标签的原样名字（HM :1077 的 text = 当下读到的标识符）
            "<U><u>" to "<u>标签重复使用",
            "<u><U>" to "<U>标签重复使用",
            // nobr：{0} 硬编码小写（HM :1099），标签名大写也照样输出小写
            "<nobr><nobr>" to "<nobr>标签重复使用",
            "<NOBR><nobr>" to "<nobr>标签重复使用",
            // p：判据是 FlagNobr（HM :1113），{0} 硬编码小写 p（:1115）
            "<nobr><p align='left'>" to "<p>标签重复使用",
            "<nobr><P ALIGN='CENTER'>" to "<p>标签重复使用",
            // 闭合后再开：FlagNobr/FlagNobrClosed 不清值（HM :992-997）⇒ 同串仍判重复。
            // LineHead 只在文本段置 false（HM :574）、`<br>` 不重置（:588-592），
            // 所以行首判据（:1093）先命中 ⇒ 带文本的那条形抛 TagIsNotBegin，不是 DuplicateTag。
            "<nobr></nobr><nobr>" to "<nobr>标签重复使用",
            "<p align='left'></p><nobr></nobr><nobr>" to "<nobr>标签重复使用",
        )
        for ((html, want) in cases) {
            val e = assertFailsWith<CodeEE> { dupLen(html) }
            assertEquals(want, e.message, "输入：$html")
        }
    }

    /**
     * 原版**放过**的形状必须照样放过（收窄判式不等于关掉校验：这里钉的是「别把合法的判成重复」）。
     * 每条都带原版行号。
     */
    @Test
    fun `原版放过的正例形状照样过`() {
        // HM :978-982 `</b>` 是 XOR 清位 ⇒ 闭标签后重开不算重复。
        dupOk("<b>x</b><b>y</b>")
        assertEquals(2L, dupLen("<b>AB</b>"))
        // HM :393-402 DisplayLine2Html 的外壳顺序：`<p align=…>` 在前、`<nobr>` 在后。
        // 两枚标签都还在行首（:574 的 LineHead 只被文本置 false），且 `<p>` 的判据是
        // FlagNobr——此刻还没置 ⇒ 不判重复。**这条是回读链的合法形状，钉死它**。
        dupOk("<p align='left'><nobr>AB</nobr></p>")
        assertEquals(2L, dupLen("<p align='left'><nobr>AB</nobr></p>"))
        // 原版 `<p>` 只对 FlagNobr 判重复，**不**对 FlagP 判（HM :1113）⇒ 连开两枚 `<p>`
        // （中间没有文本，LineHead 仍为 true）只是覆盖 Alignment，官方 bug 原样复刻。
        dupOk("<p align='left'><p align='right'>AB</p>")
        // 裸换行同样不脏 LineHead（HM :588-592 只置 FlagBr）⇒ 第二枚 `<p>` 照样合法。
        dupOk("<p align='left'>\n<p align='right'>")
        // `<font>` 是栈（HM :998-1004 / :1601-1672），重复开只压帧，不进 DuplicateTag。
        dupOk("<font color='red'><font color='blue'>AB</font></font>")
        // `<br>` 无重复判式（HM :1081-1087 只置 FlagBr）。
        dupOk("AB<br><br>")
        // `<button>` 顺序重开合法；只有**嵌套**才判 NestedButtonTag（HM :1476-1563）。
        dupOk("<button value='1'>A</button><button value='2'>B</button>")
        // `<div>` 的子解析另起状态（HM :537-544）⇒ div 内重开 p/nobr 与外层无关。
        dupOk("<div width='10' height='10'><p align='left'><nobr>AB</nobr></p></div>")
    }

    /**
     * 差集为空：`DuplicateTag` 的标签集合恰好是 `b/i/u/s/nobr/p`。
     * 其余标签重复出现走**各自**的错误（或干脆合法），不许被并进来。
     */
    @Test
    fun `重复判式只覆盖 b i u s nobr p 六形`() {
        // img 重复开合法（属性重复才判 DuplicateAttribute，HM :1177-1180）
        dupOk("<img src='a'><img src='a'>")
        val dupAttr = assertFailsWith<CodeEE> { dupLen("<img src='a' src='b'>") }
        assertEquals("<img>标签的src属性重复指定", dupAttr.message)
        // clearbutton 重复 = NestedTag（HM :1541-1547），不是 DuplicateTag
        val nested = assertFailsWith<CodeEE> { dupLen("<clearbutton><clearbutton>") }
        assertEquals("<clearbutton>嵌套了自身", nested.message)
        // 同一枚样式标签在**下一条语句/下一个串**里重开：状态每次新建（HM :536），
        // 本类用两次独立调用模拟（第二条不该看到第一条的 FontStyle）。
        dupOk("<b>AB</b>")
        dupOk("<b>CD</b>")
        assertEquals(2L, dupLen("<b>AB</b>"))
        assertEquals(2L, dupLen("<b>CD</b>"))
    }

    /**
     * 官方那条「`<p>` 的重复判据打在 `FlagNobr` 上」的复制粘贴判式（HM :1113）
     * 带来的两条**顺序敏感**差异，逐形钉死，防止后来者顺手改回 `FlagP`。
     */
    @Test
    fun `p 的重复判据打在 FlagNobr 上而非 FlagP`() {
        // ① 先 `<p>` 后 `<nobr>`（原版外壳唯一合法顺序）⇒ 不抛
        assertNull(dupErrorOrNullQuiet("<p align='left'><nobr>AB</nobr></p>"))
        // ② 先 `<nobr>` 后 `<p>` ⇒ 抛的是 `<p>标签重复使用`（不是 `<nobr>…`，也不是 TagIsNotBegin）
        val e = assertFailsWith<CodeEE> { dupLen("<nobr><p align='left'>AB</p></nobr>") }
        assertEquals("<p>标签重复使用", e.message)
        // ③ `<p>` 已经开过（FlagP=true）之后再开一枚 `<p>`：**不**判重复（判据不是 FlagP）
        assertNull(dupErrorOrNullQuiet("<p align='left'><p align='center'>AB"))
        // ④ 行首判据先于重复判据（HM :1109-1112 在 :1113 之前）⇒ 有文本时抛 TagIsNotBegin
        val notBegin = assertFailsWith<CodeEE> { dupLen("<nobr>AB<p align='left'>") }
        assertEquals("<p>被使用在行首以外的位置", notBegin.message)
        // ④' 同一条序在 `<nobr>` 上（HM :1093 先于 :1097）：`<br>` 不重置 LineHead（:588-592），
        // 所以「上一行有文本 ⇒ 本行行首再开 nobr」抛的是行首错，不是重复错。
        val nobrNotBegin = assertFailsWith<CodeEE> { dupLen("<nobr>x</nobr><br><nobr>y</nobr>") }
        assertEquals("<nobr>被使用在行首以外的位置", nobrNotBegin.message)
    }

    /**
     * 玩家上报的那一条：串**是我们自己造的**，不是玩家写的。
     *
     * 两个自产形状各自对应一处生成侧缺陷（均不在本路文件面，见交付报告「需组长接线」）：
     * - `LineBufferConsoleSink.splitHtmlLines` 的行首重放把 `<nobr>` 排在 `<p align=…>`
     *   之前（`LineBufferConsoleSink.kt:1861-1862`），与原版外壳顺序
     *   （`HtmlManager.cs:393-402` 先 `<p>` 后 `<nobr>`）相反；
     * - `HtmlMethods.kt:393` 把 HTML 段的**行体原文**（含其自带的 `<p>`/`<nobr>` 外壳）
     *   原样存进行体，`displayLine2Html`（:1003-1007）再套一层外壳 ⇒ 嵌套第二枚 `<p>`。
     *   原版不会这样：`DisplayLine2Html` 是**从节点重建**（:446-464 走
     *   `getStringStyleStartingTag` :864-915，只输出 `font/s/u/i/b` 与按钮标签），
     *   `Align` 与 `FlagNobr` 是行级标志，永远不会作为内层标签重现。
     *
     * 这里断言的是「严格解析器**照原版**拒绝这两条形」⇒ 修复点在生成侧（把外壳顺序与
     * 行体表示改成原版口径），**不在判式**。判式一条都不动。
     */
    @Test
    fun `自产串两形与原版同判_缺陷在生成侧不在判式`() {
        // 生成侧形状①：行首重放把 nobr 排在 p 之前（多显示行的 HTML_PRINT 行）
        val replay = assertFailsWith<CodeEE> { dupLen("<nobr><p align='left'>AB</p></nobr>") }
        assertEquals("<p>标签重复使用", replay.message)
        // 生成侧形状②：回读时把带外壳的行体原文再套一次外壳
        val rewrap = assertFailsWith<CodeEE> {
            dupLen("<p align='left'><nobr><p align='left'><nobr>AB</nobr></p></nobr></p>")
        }
        assertEquals("<p>标签重复使用", rewrap.message)
        // 对照：按原版口径重建的行体（外壳只有一层、内层只剩样式标签）⇒ 过
        assertNull(dupErrorOrNullQuiet("<p align='left'><nobr><b>AB</b></nobr></p>"))
        assertTrue(
            dupErrorOrNullQuiet("<p align='left'><nobr>AB</nobr></p>") == null,
            "原版外壳顺序必须可解析",
        )
    }

    /**
     * 生成侧接线的出口钉板：sink 把跨行 HTML 切成多条显示行时，行首重放的外壳序必须与原版同序
     * （`HtmlManager.cs:393-402` 先 `&lt;p align='…'&gt;` 后 `&lt;nobr&gt;`），并且**切出来的每一条行体
     * 自身都能过严格解析** —— 玩家上报那条走的正是「`HTML_GETPRINTEDSTR` 回读行体 → 再解析」这条路，
     * 顺序倒置时它必抛「&lt;p&gt;标签重复使用」。判式仍一条不动，这一例只保证我们不造非法串。
     */
    @Test
    fun `sink 回放的外壳序产出的每一行都能过严格解析`() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.printHtml("<p align='left'><nobr>甲行<br>乙行</nobr></p>", false)
        val bodies = s.snapshot().map { line -> line.segments.joinToString("") { it.text } }
        assertTrue(bodies.size >= 2, "br 必须切成两条显示行，实得：$bodies")
        for (b in bodies.drop(1)) {
            assertTrue(b.startsWith("<p align='left'><nobr>"), "行首重放必须 p 先 nobr 后，实得：$b")
            assertNull(dupErrorOrNullQuiet(b), "自产行体必须可再解析：$b")
        }
    }
}
