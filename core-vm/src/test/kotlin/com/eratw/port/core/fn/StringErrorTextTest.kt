package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 字符串族报错文案与 UNICODEBYTE 代理对口径的钉桩（第四波 str-misc）。
 *
 * 文案真源 = `Lang.Error` 键 + zhs 译文表
 * `reference/emuera-cs/MinorShift.Emuera.Properties.lang.emuera-zhs.xml`：
 * - InvalidRegexArg   zhs:297 = `{0}函数: 第{1}参数无法解析为正则表达式: {2}`
 *   （抛出点：FunctionMethodCreator.cs:1002-1005 REGEXPMATCH / :5451-5455 STRCOUNT / :5653-5657 REPLACE）
 * - ArgIsNotNDStrArray zhs:899 = `{0}函数: 第{1}个参数不是字符串型{2}维数组变量`
 *   （抛出点：FunctionMethodCreator.cs:5666-5670 REPLACE mode=1）
 * - ArgCanNotBeNull   zhs:887 = `{0}函数: 第{1}个参数不能省略`（FunctionMethod.cs:198-205）
 * - ArgIsOutOfRange   zhs:917 = `{0}函数: 第{1}个参数({2})在从{3}到{4}的范围外`
 *   （抛出点：FunctionMethodCreator.cs:5704-5706 UNICODE）
 * - InvalidUnicode    zhs:926 = `{0}函数: 传入了控制字符对应的值(0x{1:X})`
 *   （抛出点：FunctionMethodCreator.cs:5707-5716 UNICODE 告警系统行）
 * - ArgShouldBeSpecificValue zhs:927 = `{0}函数: 传入第{1}个参数必须是{2}的其中一个`
 *   （抛出点：FunctionMethodCreator.cs:5755-5758 CONVERT）
 * - MaxBarNotPositive / BarNotPositive / TooLongBar zhs:234-236
 *   （抛出点：ExpressionMediator.cs:135-145 CreateBar，BARSTR 与 BAR 语句共享、不带函数名前缀）
 *
 * UNICODEBYTE 的未配对代理读数来自本机 dotnet 9.0.200 对原版代码形态
 * （`Encoding.UTF32.GetEncoder().GetBytes(..., flush: false)`，FunctionMethodCreator.cs:5723-5740）
 * 的逐例探针："\uD800" / "\uDC00" / "\uD800A" → 替换字符 U+FFFD（65533），
 * 配对代理 → 码点，空串 → BitConverter 抛错（S10 崩溃不复刻，见 StringFamilyAuditTest）。
 */
class StringErrorTextTest {

    private class CaptureCtx : EvalContext {
        val lines = mutableListOf<String>()
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("StringErrorTextTest 不应被直接调用：$name")

        override fun printSystemLine(message: String) {
            lines += message
        }
    }

    private fun ctx(): BuiltinEvalContext {
        val store = VariableStore()
        val base = CaptureCtx()
        base.variables = store
        return BuiltinEvalContext(base, store)
    }

    private fun iv(v: Value): Long = (v as Value.IntV).v
    private fun sv(v: Value): String = (v as Value.StrV).v
    private fun iArg(v: Long): Value = Value.IntV(v)
    private fun sArg(v: String): Value = Value.StrV(v)

    /** 引用路径调用；null 实参表示原版装载期的「空位」。 */
    private fun refCall(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
        ctx.callFunctionRef(
            name,
            args.map {
                when (it) {
                    null -> null
                    is Long -> IntLiteral(it)
                    is Int -> IntLiteral(it.toLong())
                    is String -> StrLiteral(it)
                    else -> throw IllegalArgumentException("$it")
                }
            },
        )

    // ------------------------------------------------------------------
    // BARSTR —— ExpressionMediator.cs:135-145 CreateBar（与 BAR 语句同源、无函数名前缀）
    // ------------------------------------------------------------------

    @Test
    fun `BARSTR 三条报错对齐 CreateBar 的 zhs 文案`() {
        val ctx = ctx()
        assertEquals(
            "BAR的最大值不为正值",
            assertFailsWith<CodeEE> { call(ctx, "BARSTR", iArg(1), iArg(0), iArg(10)) }.message,
            "spec: ExpressionMediator.cs:135-137（zhs emuera-zhs.xml:234）",
        )
        assertEquals(
            "BAR的长度不为正值",
            assertFailsWith<CodeEE> { call(ctx, "BARSTR", iArg(1), iArg(10), iArg(0)) }.message,
            "spec: ExpressionMediator.cs:139-141（zhs :235）",
        )
        assertEquals(
            "BAR过长",
            assertFailsWith<CodeEE> { call(ctx, "BARSTR", iArg(1), iArg(10), iArg(100)) }.message,
            "spec: ExpressionMediator.cs:143-145（zhs :236）",
        )
    }

    // ------------------------------------------------------------------
    // 非法正则 —— InvalidRegexArg（zhs:297）
    // ------------------------------------------------------------------

    @Test
    fun `STRCOUNT REPLACE REGEXPMATCH 非法正则走 InvalidRegexArg 模板`() {
        val ctx = ctx()
        fun assertRegex(fn: String, block: () -> Unit) {
            val e = assertFailsWith<CodeEE> { block() }
            assertTrue(
                e.message!!.startsWith("${fn}函数: 第2参数无法解析为正则表达式: "),
                "实际文案：${e.message}",
            )
        }
        assertRegex("STRCOUNT") { call(ctx, "STRCOUNT", sArg("abc"), sArg("[")) }
        assertRegex("REPLACE") { call(ctx, "REPLACE", sArg("abc"), sArg("["), sArg("x")) }
        assertRegex("REGEXPMATCH") { refCall(ctx, "REGEXPMATCH", "abc", "[") }
    }

    // ------------------------------------------------------------------
    // REPLACE mode=1 非数组 —— ArgIsNotNDStrArray（zhs:899，实参 Name/3/1）
    // ------------------------------------------------------------------

    @Test
    fun `REPLACE mode1 第3参不是1维字符串数组走 ArgIsNotNDStrArray 模板`() {
        val ctx = ctx()
        val e = assertFailsWith<CodeEE> { refCall(ctx, "REPLACE", "abc", "a", "x", 1L) }
        assertEquals("REPLACE函数: 第3个参数不是字符串型1维数组变量", e.message)
    }

    // ------------------------------------------------------------------
    // 必填位空缺 —— ArgCanNotBeNull（zhs:887，原版装载期校验下移到求值期）
    // ------------------------------------------------------------------

    @Test
    fun `REPLACE REGEXPMATCH 必填位空缺走 ArgCanNotBeNull 模板`() {
        val ctx = ctx()
        assertEquals(
            "REPLACE函数: 第3个参数不能省略",
            assertFailsWith<CodeEE> { call(ctx, "REPLACE", sArg("abc"), sArg("a"), null) }.message,
            "值路径（OmitStart=3，第3位必填，spec: FunctionMethodCreator.cs:5625-5634）",
        )
        assertEquals(
            "REPLACE函数: 第3个参数不能省略",
            assertFailsWith<CodeEE> { refCall(ctx, "REPLACE", "abc", "a", null) }.message,
            "引用路径同一条校验",
        )
        assertEquals(
            "REGEXPMATCH函数: 第2个参数不能省略",
            assertFailsWith<CodeEE> { refCall(ctx, "REGEXPMATCH", "abc", null) }.message,
            "spec: FunctionMethodCreator.cs:986-1000（OmitStart=2）",
        )
    }

    // ------------------------------------------------------------------
    // UNICODE —— ArgIsOutOfRange（zhs:917）+ InvalidUnicode 告警（zhs:926）
    // ------------------------------------------------------------------

    @Test
    fun `UNICODE 越界走 ArgIsOutOfRange 模板`() {
        val ctx = ctx()
        assertEquals(
            "UNICODE函数: 第1个参数(-1)在从0到65535的范围外",
            assertFailsWith<CodeEE> { call(ctx, "UNICODE", iArg(-1)) }.message,
        )
        assertEquals(
            "UNICODE函数: 第1个参数(65536)在从0到65535的范围外",
            assertFailsWith<CodeEE> { call(ctx, "UNICODE", iArg(65536)) }.message,
        )
    }

    @Test
    fun `UNICODE 控制字符告警走 InvalidUnicode 文案并返回空串`() {
        val store = VariableStore()
        val base = CaptureCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store)
        assertEquals("", sv(call(ctx, "UNICODE", iArg(30))))
        assertEquals(
            listOf("UNICODE函数: 传入了控制字符对应的值(0x1E)"),
            base.lines,
            "spec: FunctionMethodCreator.cs:5707-5716（{1:X} 大写十六进制、不补零）",
        )
        base.lines.clear()
        assertEquals("", sv(call(ctx, "UNICODE", iArg(0))))
        assertEquals(listOf("UNICODE函数: 传入了控制字符对应的值(0x0)"), base.lines)
        base.lines.clear()
        assertEquals("", sv(call(ctx, "UNICODE", iArg(159))))
        assertEquals(listOf("UNICODE函数: 传入了控制字符对应的值(0x9F)"), base.lines)
        // 10/13 与 31 以上普通码位不告警（spec: :5708 条件）。
        base.lines.clear()
        assertEquals("\n", sv(call(ctx, "UNICODE", iArg(10))))
        assertEquals("\r", sv(call(ctx, "UNICODE", iArg(13))))
        assertEquals(" ", sv(call(ctx, "UNICODE", iArg(32))))
        assertTrue(base.lines.isEmpty())
    }

    // ------------------------------------------------------------------
    // CONVERT —— ArgShouldBeSpecificValue（zhs:927）
    // ------------------------------------------------------------------

    @Test
    fun `CONVERT 非法进制走 ArgShouldBeSpecificValue 模板`() {
        val ctx = ctx()
        assertEquals(
            "CONVERT函数: 传入第2个参数必须是2, 8, 10, 16的其中一个",
            assertFailsWith<CodeEE> { call(ctx, "CONVERT", iArg(255), iArg(3)) }.message,
        )
    }

    // ------------------------------------------------------------------
    // UNICODEBYTE —— UTF32 编码器口径（FunctionMethodCreator.cs:5723-5740 + dotnet 探针）
    // ------------------------------------------------------------------

    @Test
    fun `UNICODEBYTE 未配对代理按 UTF32 替换字符 0xFFFD`() {
        val ctx = ctx()
        assertEquals(65533L, iv(call(ctx, "UNICODEBYTE", sArg("\uD800"))), "孤立高位代理")
        assertEquals(65533L, iv(call(ctx, "UNICODEBYTE", sArg("\uDC00"))), "孤立低位代理")
        assertEquals(65533L, iv(call(ctx, "UNICODEBYTE", sArg("\uD800A"))), "高位代理后跟非低位")
        assertEquals(65533L, iv(call(ctx, "UNICODEBYTE", sArg("\uD800\uD800"))), "高位代理后跟又一高位")
    }

    @Test
    fun `UNICODEBYTE 正常码点与代理对不变`() {
        val ctx = ctx()
        assertEquals(65L, iv(call(ctx, "UNICODEBYTE", sArg("A"))))
        assertEquals(12354L, iv(call(ctx, "UNICODEBYTE", sArg("あ"))))
        assertEquals(128512L, iv(call(ctx, "UNICODEBYTE", sArg("\uD83D\uDE00"))), "代理对 → 码点")
        assertEquals(65L, iv(call(ctx, "UNICODEBYTE", sArg("A\uD83D\uDE00"))), "首字符非代理只取首字符")
    }

    private fun call(ctx: BuiltinEvalContext, name: String, vararg args: Value?): Value =
        ctx.callFunction(name, args.toList())
}
