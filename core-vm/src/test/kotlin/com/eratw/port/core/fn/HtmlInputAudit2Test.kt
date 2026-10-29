package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 审计二轮 L07（HTML 显示 / 输入 / 鼠标）钉住的六处语义修正，逐条回 C# 原文：
 *
 * 1. **`Convert.ToInt32(str, base)` 口径**（HtmlManager.cs:821 / :1692 / :1714）：
 *    语料 `DLC/透视图优化.ERB:11-12` 的 `<font color='#0x696969'>` 在原版合法
 *    （`Convert.ToInt32("0x696969", 16)` = 6908265），旧实现用 `toIntOrNull(16)` 会误抛。
 *    实测表见 `netParseInt32` 的 KDoc（PowerShell 5.1 / .NET Framework 4.8）。
 * 2. **数字表只认 ASCII**（同一支 `Convert.ToInt32`）：全角/阿拉伯-印度数字在原版是
 *    FormatException，而 JDK 的 `Character.digit` 认它们 —— 续任审计补修的第二处同族分叉。
 * 3. **`<shape>` 的 AltText 不做百分比归一**（ConsoleShapePart.cs:20-31 直接拼 `num`）：
 *    与 `<img>` 的 `Utils.AddTagMixedNumArg`（Utils.cs:364-371 → BuilderString :87-98）不同。
 * 4. **`face=''` 归一成缺省字体**（StringStyle.cs:41-44 `string.IsNullOrEmpty`）。
 * 5. **`bcolor` 等于 `Config.FocusColor` 时不写**（HtmlManager.cs:887-891 是值比较）。
 * 6. **`long.TryParse` 认首尾空白**（HtmlManager.cs:1543）：`value=' 12 '` 仍是整型按钮。
 *
 * 断言值全部来自 C# 语义与 PC 基准，不由本实现反推；未修的原版行为（如 `&#0x41;` 非法）一并钉住防回退。
 */
private class H2BaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("H2BaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun h2Ctx(): BuiltinEvalContext {
    val store = VariableStore()
    val base = H2BaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, object : BuiltinHost {})
}

private fun h2Len(html: String): Long =
    (h2Ctx().callFunction("HTML_STRINGLEN", listOf(Value.StrV(html))) as Value.IntV).v

private fun h2Error(html: String): String = assertFailsWith<CodeEE> { h2Len(html) }.message ?: ""

private fun h2Text(html: String): String = htmlPrintDocument(html).rows.single().visibleText

private fun h2Run(html: String): HtmlPrintRun = htmlPrintDocument(html).rows.single().runs.single()

class HtmlInputAudit2Test {

    // ===== 1. 十六进制解析：Convert.ToInt32(str, 16) 的 0x 前缀与符号/空白口径 =====

    @Test
    fun `颜色接受 0x 前缀 语料 透视图优化 的两行不再误抛`() {
        // DLC/透视图优化.ERB:11-12：<font color='#0x696969'>（# 之后是 0x696969）
        // HtmlManager.cs:1692 Convert.ToInt32("0x696969", 16) = 6908265，合法颜色
        assertEquals(0x696969, h2Run("<font color='#0x696969'>AB</font>").style.fore)
        // 语料另一处同形：魔改内容/qol/qol_update.ERB:145/147/484/486 的 `color='#0x90EE90'`
        assertEquals(0x90EE90, h2Run("<font color='#0x90EE90'>AB</font>").style.fore)
        // 0X 大写前缀同样认（实测 "0X10" base 16 = 16）
        assertEquals(0x10, h2Run("<font color='#0X10'>A</font>").style.fore)
        // 接线侧回归：HTML_STRINGLEN 走同一解析器，旧实现在这里抛 CodeEE
        assertEquals(1L, h2Len("<font color='#0x696969'>A</font>"))
    }

    @Test
    fun `颜色十六进制段的失败文案与原版一致（空白、负号、0x 落空）`() {
        // Convert 不接受空白：" 10" → FormatException → CanNotInterpretNumValue（:1694-1697）
        assertEquals(" 10无法被解析为数值", h2Error("<font color='# 10'>A</font>"))
        // "0x" 之后没有数字 → FormatException
        assertEquals("0x无法被解析为数值", h2Error("<font color='#0x'>A</font>"))
        // 十六进制负号在原版是 ArgumentException（实测 "-10" base 16 失败）→ 同一文案
        assertEquals("-1无法被解析为数值", h2Error("<font color='#-1'>A</font>"))
        // 32 位回绕后为负/超 0xFFFFFF → OoRColorValue 被裸 catch 吞掉 → CanNotInterpretNumValue
        assertEquals("FFFFFFFF无法被解析为数值", h2Error("<font color='#FFFFFFFF'>A</font>"))
        assertEquals("1234567无法被解析为数值", h2Error("<font color='#1234567'>A</font>"))
    }

    @Test
    fun `裸十六进制名的探测沿用 Convert 口径（0x 与正号算数，空白不算）`() {
        // :1712-1720：能按 16 进制解析的裸串 → InvalidColorName2（提示补 #）
        val hint = "所指定的颜色名\"%s\"无效(用16进制数表示颜色时须在数值前添加#符号)"
        assertEquals(hint.format("0x10"), h2Error("<font color='0x10'>A</font>"))
        assertEquals(hint.format("+1f"), h2Error("<font color='+1f'>A</font>"))
        // " 1f" 在 Convert 下不成立 → 落到 :1718 的未 Format 模板（{0} 原样上屏）
        assertEquals("指定的颜色名\"{0}\"无效", h2Error("<font color=' 1f'>A</font>"))
    }

    @Test
    fun `数字实体：x 标记后允许 0x 前缀 32 位回绕走范围错误`() {
        // :821 Convert.ToInt32("0x41", 16) = 65 → 'A'（&#x0x41; 的 digits 是 "0x41"）
        assertEquals("A", h2Text("&#x0x41;"))
        assertEquals("A", h2Text("&#x41;"))
        // 没有 x 标记时 fromBase 仍是 10："0x41" 在十进制下非法（原版行为，勿修）
        assertEquals("&#0x41;不是有效的转义字符", h2Error("&#0x41;"))
        // "FFFFFFFF" base 16 = -1（32 位回绕）→ 范围分支，不是「不是有效的转义字符」
        assertEquals("&#xFFFFFFFF;超过Unicode的范围(不支持代理对)", h2Error("&#xFFFFFFFF;"))
        // 砍掉 `#x` 之后仍可带 `0x` 前缀：&#x0xFFFFFFFF; 的 digits 是 `0xffffffff`，
        // Convert.ToInt32("0xffffffff", 16) = -1（实测）→ 还是范围分支
        assertEquals("&#x0xFFFFFFFF;超过Unicode的范围(不支持代理对)", h2Error("&#x0xFFFFFFFF;"))
        // 少一个 0 就成 9 位有效数字（`xxFFFFFFFF` 砍 `#x` 后是 `xffffffff`）→ Convert 溢出
        // → 无效转义，而不是范围错误（:818-826 的 catch 与 :827-830 的分支不对称，照抄）
        assertEquals("&#xxFFFFFFFF;不是有效的转义字符", h2Error("&#xxFFFFFFFF;"))
        // 十六进制负号在 Convert 下失败 → 无效转义；十进制负号能解析 → 范围错误
        assertEquals("&#x-1;不是有效的转义字符", h2Error("&#x-1;"))
        assertEquals("&#-1;超过Unicode的范围(不支持代理对)", h2Error("&#-1;"))
        // 边界：0xFFFF 合法、0x10000 越界
        assertEquals("\uFFFF", h2Text("&#xFFFF;"))
        assertEquals("&#x10000;超过Unicode的范围(不支持代理对)", h2Error("&#x10000;"))
    }

    @Test
    fun `数字实体与颜色都只认 ASCII 数字 全角与阿拉伯-印度数字判失败`() {
        // Convert.ToInt32 的数字表只有 ASCII（PowerShell 5.1 实测：U+FF10 / U+0660 → FormatException）；
        // JDK 的 Character.digit 却把它们认成 0（Temurin 17 实测），照抄会把非法实体解成合法字符。
        assertEquals("&#x１;不是有效的转义字符", h2Error("&#x１;"))   // 全角 1
        assertEquals("&#١;不是有效的转义字符", h2Error("&#١;"))   // 阿拉伯-印度 1
        assertEquals("&#x1０;不是有效的转义字符", h2Error("&#x1０;")) // 半角 1 + 全角 0
        // 颜色侧同口径（HtmlManager.cs:1692）：digits 原样进 CanNotInterpretNumValue 文案
        assertEquals("1０无法被解析为数值", h2Error("<font color='#1０'>A</font>"))
        // :1714 的裸十六进制探测同样不认全角 → 落到 :1718 的未 Format 模板
        assertEquals("指定的颜色名\"{0}\"无效", h2Error("<font color='１f'>A</font>"))
    }

    // ===== 2. shape 的 AltText 保留原值 =====

    @Test
    fun `shape 的 param 原样回显 不做 FontSize 百分比归一`() {
        // ConsoleShapePart.cs:22-26：param[i].num.ToString()（isPx 才追 "px"）
        assertEquals(
            "<shape type='space' param='50'>",
            h2Run("<shape type='space' param='50'>").text,
        )
        // 语料形态：魔改内容/PRINT系/UI_Components_&_Tables.ERB:735/748/755 的
        // `param='0,{Y_OFFSET},{W},{H}'`（非 px 百分比，原样回显）
        assertEquals(
            "<shape type='rect' param='0, 10, 100, 70'>",
            h2Run("<shape type='rect' param='0,10,100,70'>").text,
        )
        // px 段保留 px；PC 基准直接可验：pc-golden-root.txt:24 的
        // `<shape type='rect' param='0, 30, 24, 64' …>` ×100 就是原值（归一后应成 `0, 5, 4, 11`）
        // 逗号两侧空格按 ", " 规范化（HtmlManager.cs:1433 逐段 Trim）；语料两种形态都有：
        // DLC/神签/神签.ERB:83 无空格、CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB:415 有空格
        assertEquals(
            "<shape type='rect' param='0, 1px, 440px, 2px'>",
            h2Run("<shape type='rect' param='0,1px,440px,2px'>").text,
        )
        assertEquals(
            "<shape type='rect' param='0, 1px, 440px, 2px'>",
            h2Run("<shape type='rect' param='0 , 1px , 440px , 2px'>").text,
        )
        // 对照：img 走 BuilderString（Utils.cs:87-98），非 px 仍按 FontSize/100 归一
        assertEquals("<img src='a' height='1'>", h2Run("<img src='a' height='10'>").text)
    }

    // ===== 3/4. font 帧归一：face='' 回缺省、bcolor == FocusColor 等于没写 =====

    @Test
    fun `face 空串回缺省且不继承外层`() {
        // StringStyle.cs:41-46：string.IsNullOrEmpty(fontname) → Config.FontName
        assertNull(h2Run("<font face=''>A</font>").style.fontName)
        // 显式空串非 null，不触发 :1651-1667 的继承 ⇒ 内层回缺省而不是跟着外层 A
        val rows = htmlPrintDocument("<font face='A'><font face=''>X</font>Y</font>").rows
        assertNull(rows[0].runs[0].style.fontName)
        assertEquals("A", rows[0].runs[1].style.fontName)
        // 非空 face 原样保留
        assertEquals("MS Gothic", h2Run("<font face='MS Gothic'>A</font>").style.fontName)
    }

    @Test
    fun `bcolor 等于 FocusColor 视为没写 其余保留`() {
        // HtmlManager.cs:887-891：ButtonColor != Config.FocusColor 才写 bcolor
        assertNull(h2Run("<font bcolor='#FFFF00'>A</font>").style.buttonColor)
        assertEquals(0xEE2C2C, h2Run("<font bcolor='#EE2C2C'>A</font>").style.buttonColor)
        // color 与 bcolor 并存（合成用例：HtmlManager.cs:1630/:1637 两个属性各自记账）
        val run = h2Run("<font color='#EE2C2C' bcolor='#EE2C2C'>A</font>")
        assertEquals(0xEE2C2C, run.style.fore)
        assertEquals(0xEE2C2C, run.style.buttonColor)
        // 内层不写 bcolor 时继承外层的值
        val rows = htmlPrintDocument("<font bcolor='#EE2C2C'><font color='red'>X</font></font>").rows
        assertEquals(0xEE2C2C, rows[0].runs[0].style.buttonColor)
    }

    // ===== 5. button value 的 long.TryParse 认首尾空白 =====

    @Test
    fun `button value 首尾空白仍是整型按钮`() {
        // HtmlManager.cs:1543 long.TryParse：NumberStyles.Integer 允许首尾空白与一个符号
        val blank = htmlPrintDocument("<button value=' 12 '>A</button>").rows[0].runs[0].button!!
        assertTrue(blank.valueIsInt)
        assertEquals(12L, blank.valueInt)
        assertEquals(" 12 ", blank.value)
        val plus = htmlPrintDocument("<button value='+12'>A</button>").rows[0].runs[0].button!!
        assertTrue(plus.valueIsInt)
        assertEquals(12L, plus.valueInt)
        // 非整数串仍是字符串按钮（result3 留在 0）
        val str = htmlPrintDocument("<button value='12abc'>A</button>").rows[0].runs[0].button!!
        assertEquals(false, str.valueIsInt)
        assertEquals(0L, str.valueInt)
        // 千分位不被接受（NumberStyles.Integer 不含 AllowThousands）
        val comma = htmlPrintDocument("<button value='1,000'>A</button>").rows[0].runs[0].button!!
        assertEquals(false, comma.valueIsInt)
    }
}
