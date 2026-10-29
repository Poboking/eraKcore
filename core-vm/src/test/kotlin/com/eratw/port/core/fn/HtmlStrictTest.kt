package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * HTML_STRINGLEN 的严格口径（HTML-CORE-DIV / ADR-005）。
 *
 * 判据分三组：
 * 1. **合法矩阵**：基准 `fixtures/golden/baseline/pc-golden-root.txt:297` 的
 *    `HTML_STRINGLEN=4 PLAIN=36`（输入 `<i>AB</i>CD`，`GOLDEN_02_PRINT.ERB:255`）
 *    必须逐字节不变；其余合法形态按原版宽度口径（像素 = 半角格 × FontSize/2）。
 * 2. **畸形矩阵**：原版 `HtmlLength` 走完整 `Html2DisplayLine`（HtmlManager.cs:168-182），
 *    畸形即抛 CodeEE。这里逐条断言**与原版同文案**（模板取
 *    `MinorShift.Emuera.Properties.lang.emuera-zhs.xml:792-822`）。
 * 3. **MiniHtml 对照表**：`render/.../html/MiniHtml.kt` 是另一路产物（只读引用）。
 *    该表记录它对这些输入抛的 `MiniHtmlErrorKind` 名，用来锁住「两路口径一致」；
 *    `:core-vm` 测试源集没有 `:render` 依赖（build.gradle.kts 不在本路文件面），
 *    故此处只做静态对照，真跑 MiniHtml 需组长接线。
 */
private class HsBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("HsBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun hsCtx(): BuiltinEvalContext {
    val store = VariableStore()
    val base = HsBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, object : BuiltinHost {})
}

/** 默认第二参（省略）= 全角字数；[plain] = 原始像素宽。 */
private fun hsLen(ctx: BuiltinEvalContext, html: String, plain: Boolean = false): Long {
    val args = if (plain) listOf(Value.StrV(html), Value.IntV(1)) else listOf(Value.StrV(html))
    return (ctx.callFunction("HTML_STRINGLEN", args) as Value.IntV).v
}

private fun hsError(html: String): String {
    val e = assertFailsWith<CodeEE> { hsLen(hsCtx(), html) }
    return e.message ?: ""
}

class HtmlStrictTest {

    // ===== 1. 合法矩阵（基准 297 行为首条） =====

    @Test
    fun `基准 297 行不变 HTML_STRINGLEN 4 与 PLAIN 36`() {
        val ctx = hsCtx()
        assertEquals(4L, hsLen(ctx, "<i>AB</i>CD"))
        assertEquals(36L, hsLen(ctx, "<i>AB</i>CD", plain = true))
    }

    @Test
    fun `合法输入矩阵宽度不变`() {
        // 期望值 = 半角格数（默认 FontSize=18 时 1 格 = 9px）；MiniHtml.plainLength 同单位
        val cases = listOf(
            "" to 0L,
            "ABCD" to 4L,
            "<b>x</b>" to 1L,
            "<p align='center'><nobr>AB</nobr></p>" to 2L,
            "<font color='#ff0000'>AB</font>" to 2L,
            // 命名色读 ColorNames（140 权威名单，COLOR-HTMLSTRICT）：命中即通过，大小写不敏感
            "<font color='red'>AB</font>" to 2L,
            "<font color='LIGHTGOLDENRODYELLOW'>AB</font>" to 2L,
            "<font bcolor='white'>AB</font>" to 2L,
            // FN-02 半边：div 子文本不计入父行宽（ConsoleDivPart.cs:217-219 SetWidth 空实现、
            // AConsoleDisplayNode.cs:27 缺省 0 ⇒ ConsoleButtonString.cs:266-270 累加得 0），
            // div-only 组零宽按 CalcWidth :273-276 记 -1（换算 HtmlMethods.kt:83-85：-1px → -1）
            "<div width='1' height='1' color='gray' bcolor='red,blue'>AB</div>" to -1L,
            "<font face='A'>AB</font>" to 2L,
            "<b><i>AB</i></b>" to 2L,
            "<u><s>AB</s></u>" to 2L,
            // FN-01：图元不可解析时原版把整串标签当 AltText 量宽（ConsoleImagePart.cs:60-64、
            // :104-115）⇒ 17 个半角 ×9px = 153px，加 `AB` 18px = 171px → 2*171/18 = 19 格。
            // 改前这里是 2L（图元记 0 宽，即 FN-01 的缺陷读数）。
            "<img src='a.png'>AB" to 19L,
            "<shape type='rect' param='1,2,3,4'>AB" to 2L,
            "<!-- note -->AB" to 2L,
            "&amp;" to 1L,
            // FN-02 半边：同上，div 子文本 "AB" 不计宽，div-only 组零宽记 -1
            "<div width='10' height='10'>AB</div>" to -1L,
            "<nobr><button value='1'>AB</button></nobr>" to 2L,
            "<clearbutton>AB" to 2L,
        )
        for ((html, want) in cases) {
            assertEquals(want, hsLen(hsCtx(), html), "输入：$html")
        }
    }

    @Test
    fun `换行只量第一条显示行但仍校验整串`() {
        // 原版只累加 array[0]（HtmlManager.cs:176-181）；br 之后的文本不计宽
        assertEquals(2L, hsLen(hsCtx(), "AB<br>CD"))
        assertEquals(2L, hsLen(hsCtx(), "AB\nCD"))
        // 前导 br 造出空的第一行（PrintStringBuffer.cs:209-216 无条件成行）
        assertEquals(0L, hsLen(hsCtx(), "<br>AB"))
        // br 之后同样要解析：原版解析完整串后才取第一行
        assertEquals("存在没有结尾的标签", hsError("AB<br><b>"))
        assertEquals(
            "在解析html字符串\"AB<br><unknown>\"的标签时发生错误",
            hsError("AB<br><unknown>"),
        )
    }

    @Test
    fun `div 内的 br 不结束外层首行量宽`() {
        // 原版 `<div>` 走递归子解析（HtmlManager.cs:606-648）：子调用拿的是新 HtmlAnalzeState
        // （:537-544，FlagBr 不继承），div 内的 `<br>`（:590）只结子行；父行在 `</div>` 后继续，
        // 所以 `Y` 仍属于第一条显示行。div 节点本身对父行贡献 0 宽（FN-02 半边：
        // ConsoleDivPart.cs:217-219 SetWidth 空实现 + AConsoleDisplayNode.cs:27 缺省 0
        // ⇒ ConsoleButtonString.cs:266-270 累加得 0），子行的 `a`/`b` 也不进父行宽
        // （HtmlManager.cs:636-642：子行宽是递归调用自己的产物）。
        // 读数：`X`（1 格）+ div(0) + `Y`（1 格）= 2；修复前 div 子文本重复计入 → 3。
        assertEquals(2L, hsLen(hsCtx(), "X<div width='10' height='10'>a<br>b</div>Y"))
        // 对照：同样形状但 br 在外层 —— 第二行不量宽
        assertEquals(2L, hsLen(hsCtx(), "Xa<br>bY"))
    }

    // ===== 2. 畸形矩阵：文案逐字对齐 lang.emuera-zhs.xml =====

    @Test
    fun `结构类畸形抛同文案 CodeEE`() {
        val cases = listOf(
            // Error.TagIsNotClosed :796
            "<i>AB" to "存在没有结尾的标签",
            "<button>" to "存在没有结尾的标签",
            "<div width='1' height='1'>" to "存在没有结尾的标签",
            // Error.DuplicateTag :806（开标签原样回显）
            "<b><b>" to "<b>标签重复使用",
            "<nobr><nobr>" to "<nobr>标签重复使用",
            // Error.UnexpectedCloseTag :803
            "</b>" to "</b>之前没有<b>",
            "<b>x</p>" to "</p>之前没有<p>",
            "</font>" to "</font>之前没有<font>",
            "</clearbutton>" to "</clearbutton>之前没有<clearbutton>",
            // Error.CanNotInterpretCloseTag :804
            "</foo>" to "无法解析的结束标签</foo>",
            // Error.AttributeSetToTag :805
            "<b x='y'>" to "<b>标签设置了属性",
            "<br x='1'>" to "<br>标签设置了属性",
            "<nobr x='1'>" to "<nobr>标签设置了属性",
            // Error.TagIsNotBegin :807
            "x<nobr>" to "<nobr>被使用在行首以外的位置",
            "x<p align='left'>" to "<p>被使用在行首以外的位置",
            // Error.NestedButtonTag :815 / NestedTag :816
            "<button><button>" to "<button>或者<nonbutton>嵌套了自身",
            "<div width='1' height='1'><div width='1' height='1'>" to "<div>嵌套了自身",
            "<clearbutton><clearbutton>" to "<clearbutton>嵌套了自身",
            // Error.HtmlTagError :818（{0} = 整串）
            "<>" to "在解析html字符串\"<>\"的标签时发生错误",
            "< >" to "在解析html字符串\"< >\"的标签时发生错误",
            "<unknown>" to "在解析html字符串\"<unknown>\"的标签时发生错误",
            "<font color=red>" to "在解析html字符串\"<font color=red>\"的标签时发生错误",
            "<p align='left' x='1'>" to "在解析html字符串\"<p align='left' x='1'>\"的标签时发生错误",
            // Error.NotFoundTerminateTag :795
            "<" to "找不到标签的结束符'>'",
            "<i" to "找不到标签的结束符'>'",
            "<i AB" to "找不到标签的结束符'>'",
            // Error.NotFoundCloseTag :794
            "<!-- x" to "找不到注释的结束标签\"-->\"",
        )
        for ((html, want) in cases) {
            assertEquals(want, hsError(html), "输入：$html")
        }
    }

    @Test
    fun `属性与实体类畸形抛同文案 CodeEE`() {
        val cases = listOf(
            // Error.TagHasNotAttribute :808 / NotSetAttribute :814
            "<p>" to "<p>标签没有设置属性",
            "<font>" to "<font>标签没有设置属性",
            "<img>" to "<img>标签没有设置属性",
            "<shape>" to "<shape>标签没有设置属性",
            "<img width='1'>" to "<img>标签没有设定src属性",
            "<div>" to "<div>标签没有设定width属性",
            "<div width='1'>" to "<div>标签没有设定height属性",
            "<shape type='rect'>" to "<shape>标签没有设定param属性",
            // Error.CanNotInterpretAttribute :810 / PAttribute :809 / Name :813
            "<p align='middle'>" to "无法解析属性值middle",
            "<p aling='left'>" to "<p>标签的属性名aling无法解析",
            "<font bad='x'>" to "<font>标签的属性名bad无法解析",
            // Error.DuplicateAttribute :811
            "<font color='red' color='blue'>" to "<font>标签的color属性重复指定",
            "<img src='a' src='b'>" to "<img>标签的src属性重复指定",
            // Error.AttributeCanNotInterpretNum :812
            "<img src='a' width='xx'>" to "<img>标签的width属性的属性值无法被解析为数值",
            "<button value='1' pos='x'>" to "<button>标签的pos属性的属性值无法被解析为数值",
            // 颜色（HtmlManager.cs:1680-1725）
            "<font color=''>" to "需要表示颜色的单词或者#RRGGBB值",
            "<font color='#zzzzzz'>" to "zzzzzz无法被解析为数值",
            "<font color='transparent'>" to "不能将透明(Transparent)指定为颜色",
            "<font color='abcdef'>" to
                "所指定的颜色名\"abcdef\"无效(用16进制数表示颜色时须在数值前添加#符号)",
            // 实体（Error.MissingSemicolon/ContinuouslyAndSemicolon/InvalidCharacterReference/OoRUnicodeHtml）
            "&" to "没有找到与'&'对应的';'",
            "&;" to "连续出现'&'与';'",
            "&abc;" to "&abc;不是有效的转义字符",
            "&#xZZ;" to "&#xZZ;不是有效的转义字符",
            "&#99999999;" to "&#99999999;超过Unicode的范围(不支持代理对)",
        )
        for ((html, want) in cases) {
            assertEquals(want, hsError(html), "输入：$html")
        }
    }

    /**
     * COLOR-HTMLSTRICT（契约 CONTRACT-M4-D §2 d-htmlcolor）：接 ColorNames 权威表后，
     * 未知名必抛（原版 HtmlManager.cs:1705-1720 的 `A == 0` 分支没有「放过」出口）。
     * :1718 抛的是**未经 string.Format** 的模板（TranslatableString.Text 原样返回，Lang.cs:23-33），
     * 故文案里的 `{0}` 原样上屏（官方怪癖，ADR-005）；模板取 emuera-zhs.xml:451。
     */
    @Test
    fun `未知名颜色必抛 InvalidColorName 未Format模板`() {
        val raw = "指定的颜色名\"{0}\"无效"
        assertEquals(raw, hsError("<font color='notacolor'>AB</font>"))
        // grey 系拼写框架实测 A == 0（ColorNames KDoc 取证），按未知名处理
        assertEquals(raw, hsError("<font color='grey'>AB</font>"))
        assertEquals(raw, hsError("<font color='darkgrey'>AB</font>"))
        // 33 个系统色名不入表（取值随 Windows 主题变化）——本层按未知名，与 PC 有行为差，见交付报告
        assertEquals(raw, hsError("<font color='window'>AB</font>"))
        // 框架不 trim 名字（实测 "red " 的 A == 0；ColorNames.key 同口径不去空白）
        assertEquals(raw, hsError("<font color='red '>AB</font>"))
        // 逗号分隔在 <font> 里不是列表（HM :1637 整串交给 stringToColorInt32），同样必抛
        assertEquals(raw, hsError("<font bcolor='red,notacolor'>AB</font>"))
        // <div> 的 bcolor 才走 1..4 段列表（Utils.cs:328-368 逐段 stringToColorInt32）
        assertEquals(raw, hsError("<div width='1' height='1' bcolor='notacolor'>AB</div>"))
        assertEquals(raw, hsError("<shape type='rect' param='1' color='notacolor'>AB</shape>"))
    }

    @Test
    fun `p-nobr 之后还有文本、pos 约束`() {
        assertEquals("</p>之后没有文字", hsError("<p align='left'>AB</p>CD"))
        assertEquals("</nobr>后没有文字", hsError("<nobr>AB</nobr>CD"))
        assertEquals(
            "设定了<nobr>的行不能使用pos属性",
            hsError("<button value='1' pos='5'>A</button>"),
        )
        assertEquals(
            "align不能在非left的行使用pos属性",
            hsError("<p align='center'><nobr><button value='1' pos='5'>A</button></nobr></p>"),
        )
    }

    // ===== 3. MiniHtml 对照表（只读引用；见类注释的接线说明） =====

    private data class ParityCase(val input: String, val core: String, val mini: String)

    @Test
    fun `MiniHtml 与 core 的畸形输入口径对照表`() {
        val table = listOf(
            ParityCase("<i>AB", "存在没有结尾的标签", "TAG_NOT_CLOSED"),
            ParityCase("<b><b>", "<b>标签重复使用", "DUPLICATE_TAG"),
            ParityCase("</b>", "</b>之前没有<b>", "UNEXPECTED_CLOSE_TAG"),
            ParityCase("</foo>", "无法解析的结束标签</foo>", "UNKNOWN_CLOSE_TAG"),
            ParityCase("<unknown>", "在解析html字符串\"<unknown>\"的标签时发生错误", "TAG_ERROR"),
            ParityCase("<>", "在解析html字符串\"<>\"的标签时发生错误", "TAG_ERROR"),
            ParityCase("<font color=red>", "在解析html字符串\"<font color=red>\"的标签时发生错误", "TAG_ERROR"),
            ParityCase("<", "找不到标签的结束符'>'", "TAG_UNTERMINATED"),
            ParityCase("<i", "找不到标签的结束符'>'", "TAG_UNTERMINATED"),
            ParityCase("<!-- x", "找不到注释的结束标签\"-->\"", "COMMENT_UNTERMINATED"),
            ParityCase("<b x='y'>", "<b>标签设置了属性", "ATTRIBUTE_ON_STYLE_TAG"),
            ParityCase("x<nobr>", "<nobr>被使用在行首以外的位置", "TAG_NOT_AT_LINE_HEAD"),
            ParityCase("<p>", "<p>标签没有设置属性", "P_WITHOUT_ATTRIBUTE"),
            ParityCase("<p align='middle'>", "无法解析属性值middle", "UNKNOWN_ALIGN_VALUE"),
            ParityCase("<p aling='left'>", "<p>标签的属性名aling无法解析", "UNKNOWN_P_ATTRIBUTE"),
            ParityCase("<p align='left'>AB</p>CD", "</p>之后没有文字", "TEXT_AFTER_P"),
            ParityCase("<nobr>AB</nobr>CD", "</nobr>后没有文字", "TEXT_AFTER_NOBR"),
            ParityCase("<font>", "<font>标签没有设置属性", "FONT_WITHOUT_ATTRIBUTE"),
            ParityCase("<img>", "<img>标签没有设置属性", "IMG_WITHOUT_ATTRIBUTE"),
            ParityCase("<img width='1'>", "<img>标签没有设定src属性", "IMG_SRC_MISSING"),
            ParityCase("<img src='a' width='xx'>", "<img>标签的width属性的属性值无法被解析为数值", "ATTRIBUTE_NOT_NUMBER"),
            ParityCase("<font color=''>", "需要表示颜色的单词或者#RRGGBB值", "COLOR_REQUIRED"),
            ParityCase("<font color='#zzzzzz'>", "zzzzzz无法被解析为数值", "COLOR_UNPARSABLE"),
            ParityCase("<font color='transparent'>", "不能将透明(Transparent)指定为颜色", "COLOR_TRANSPARENT_UNSUPPORTED"),
            ParityCase("<font color='abcdef'>", "所指定的颜色名\"abcdef\"无效(用16进制数表示颜色时须在数值前添加#符号)", "COLOR_NAME_REJECTED"),
            // COLOR-HTMLSTRICT：未知名两层都必抛；core 文案是未 Format 的模板（HM :1718），
            // MiniHtml 侧对应 kind 见 MiniHtml.kt:466（UNKNOWN_COLOR_NAME，只读对照）。
            ParityCase("<font color='notacolor'>", "指定的颜色名\"{0}\"无效", "UNKNOWN_COLOR_NAME"),
            ParityCase("<font color='grey'>", "指定的颜色名\"{0}\"无效", "UNKNOWN_COLOR_NAME"),
            ParityCase("<font color='window'>", "指定的颜色名\"{0}\"无效", "UNKNOWN_COLOR_NAME"),
            ParityCase("&", "没有找到与'&'对应的';'", "ENTITY_MISSING_SEMICOLON"),
            ParityCase("&;", "连续出现'&'与';'", "ENTITY_AMP_SEMI"),
            ParityCase("&abc;", "&abc;不是有效的转义字符", "ENTITY_INVALID_REF"),
            ParityCase("&#99999999;", "&#99999999;超过Unicode的范围(不支持代理对)", "ENTITY_OUT_OF_RANGE"),
            // 以下三类 MiniHtml 按契约不覆盖（命中即 UNSUPPORTED_TAG），原版会解析；
            // core 走原版口径 —— 这属于「子集 vs 全集」的差异，不是语义冲突。
            ParityCase("<button>", "存在没有结尾的标签", "UNSUPPORTED_TAG"),
            ParityCase("<div>", "<div>标签没有设定width属性", "UNSUPPORTED_TAG"),
            ParityCase("<button><button>", "<button>或者<nonbutton>嵌套了自身", "UNSUPPORTED_TAG"),
        )
        for (c in table) {
            assertEquals(c.core, hsError(c.input), "core 文案：${c.input}")
            assertTrue(c.mini.isNotEmpty(), "MiniHtml 类别未登记：${c.input}")
        }
    }
}
