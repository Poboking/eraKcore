package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Value
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.vars.VariableStore
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `TOFLOAT` 字符串实参（4.981 新增）逐条对拍。
 *
 * 权威 = fork-src `Creator.Method.cs:6145-6165`（commit 48494165，`argumentTypeArray = [String]`、
 * `double.TryParse` 不带 InvariantCulture）；1.824 快照（`reference/emuera-cs`）无此函数。
 * 期望值不是推算：本机 dotnet 8.0.425 探针切 `CultureInfo.CurrentCulture` 后逐条实测
 * `double.TryParse(str, out _)`，形状与 [com.eratw.port.core.fn.parseDoubleCurrentCulture] 的 KDoc 一一对应。
 *
 * ## 同族字符串实参形态对拍（fork `Creator.Method.cs` → 本仓）
 *
 * | 函数 | fork 形态 | 本仓 | 结论 |
 * |---|---|---|---|
 * | TOFLOAT | `:6145-6165` `argumentTypeArray=[String]`（:6150）、`TryParse` 当前文化（:6161） | `PureMethods.kt` 的 `floatC("TOFLOAT")` + `FloatConversion.kt` | **本路已对齐**（字符串解析；数值实参见下「偏离 B1」） |
 * | TOINT | `:6094-6142` `argumentTypeArray=null`（:6099，任意类型）；Float 走 `(long)` 截断（:6105-6106）；字符串分支 `:6107-6141` | `PureMethods.kt` `intC("TOINT")` | 字符串主体一致（空串/长度门/首字符/`ReadInt64`/小数尾）；**偏离 A1**（溢出：fork `:6118-6125` catch→0） |
 * | TOI | 不存在（`Creator.cs:143-150` 无该注册名，全仓 grep 0 命中） | 不存在 | 一致（都不存在） |
 * | TOSTR | `:6047-6092` `ArgTypeList{Int, String}` + `OmitStart=1`（:6053-6055）；`long.ToString(format)`，`FormatException` ⇒ InvalidFormat CodeEE（:6087-6089） | `PureMethods.kt` `str("TOSTR")` + `formatDotNet` | 行为一致；**偏离 B1**（类型门在装载期 vs 运行期）、**偏离 B2**（arity 文案，无 `omitStart`） |
 *
 * ## 偏离处置（ADR-005）
 *
 * - **A1 TOINT 溢出**：1.824（`reference` `FunctionMethodCreator.cs:5529`）无 catch ⇒ 溢出抛 CodeEE；
 *   4.981 在 `:6118-6125` 加了 `catch (Exception) { return 0; }`。本路**按任务口径不动 int 既有行为**
 *   （`BuiltinsTest` / `StringFamilyAuditTest` 的溢出闸仍是 1.824 读数），差额登记给组长决定是否在
 *   4.981 对齐批次里翻面。
 * - **B1 装载期类型门**：fork 用 `argumentTypeArray` / `ArgTypeList` 在装载期静态拒绝错类型
 *   （`FunctionMethod.cs:304-308`、`:174-246`）；移植侧内建分派只看运行期值，`TOFLOAT` 保留数值
 *   实参兜底，`TOSTR` 对浮点实参抛运行期 CodeEE。忠实语料里这两种形态装载即失败、运行期不可达。
 * - **B2 arity 文案**：`TOSTR` 在 fork 走 `OmitStart=1` 分支（`:182-283` ⇒「参数过多 / 至少需要1个参数」），
 *   本仓仍走旧式 `minArgs..maxArgs` 文案（`PcOracleGapTest` 已登记的 METHOD_ARG_TYPE 缺口）。
 */
private fun fcCtx(): BuiltinEvalContext {
    val store = VariableStore()
    return BuiltinEvalContext(TestEvalContext(store), store, object : BuiltinHost {})
}

/** 在 [locale] 下跑 [body]，退出时把 JVM 默认文化还原。 */
private fun <T> withLocale(locale: Locale, body: () -> T): T {
    val old = Locale.getDefault()
    Locale.setDefault(locale)
    try {
        return body()
    } finally {
        Locale.setDefault(old)
    }
}

private fun fcD(ctx: BuiltinEvalContext, vararg a: Any?): Double =
    (ctx.callFunction(
        "TOFLOAT",
        a.map { if (it is Value) it else if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
    ) as Value.FloatV).v

private fun fcI(ctx: BuiltinEvalContext, name: String, s: String): Long =
    (ctx.callFunction(name, listOf(Value.StrV(s))) as Value.IntV).v

class FloatConversionTest {

    @Test
    fun `TOFLOAT 字符串实参按当前文化解析 小数点为点`() = withLocale(Locale.JAPAN) {
        val c = fcCtx()
        assertEquals(1.5, fcD(c, "1.5"))          // :6161 直给
        // 默认 NumberStyles 带 AllowThousands ⇒ ',' 是千分位，被忽略
        // （dotnet 8.0.425 实测 ja-JP TryParse("1,5") == 15）
        assertEquals(15.0, fcD(c, "1,5"))
        assertEquals(1234.5, fcD(c, "1,234.5"))
        // TryParse 自己吃 ASCII 空白（与 TOINT 的「首字符必须是数字」不同）
        assertEquals(1.5, fcD(c, " 1.5 "))
        assertEquals(1000.0, fcD(c, "1e3"))
        assertEquals(12.0, fcD(c, "12."))
        assertEquals(0.5, fcD(c, ".5"))
        // 负零保留（.NET TryParse("-0.0") 给 -0，与 0 的位形不同）
        assertEquals(
            java.lang.Double.doubleToRawLongBits(-0.0),
            java.lang.Double.doubleToRawLongBits(fcD(c, "-0.0")),
        )
    }

    @Test
    fun `TOFLOAT 在逗号小数点文化下按当前文化解析`() = withLocale(Locale.GERMANY) {
        val c = fcCtx()
        assertEquals(1.5, fcD(c, "1,5"))          // de-DE 实测
        assertEquals(15.0, fcD(c, "1.5"))         // '.' 是千分位 ⇒ 15（不是 1.5，也不是 0）
        assertEquals(1234.5, fcD(c, "1.234,5"))
        assertEquals(0.0, fcD(c, ".5"))           // 分组符不能出现在首个数字之前
        assertEquals(0.0, fcD(c, "1,5.5"))        // 小数点之后不再认分组符
    }

    @Test
    fun `TOFLOAT 空串 长度门 解析失败一律给 0_0`() = withLocale(Locale.JAPAN) {
        val c = fcCtx()
        assertEquals(0.0, fcD(c, ""))             // :6157-6158
        assertEquals(0.0, fcD(c, "ABC"))          // :6161-6163 TryParse 失败
        assertEquals(0.0, fcD(c, "١٢"))           // 非 ASCII 数字：码页按替换字符计 1 字节，
                                                  //   长度门放行，TryParse 只认 ASCII 数字 ⇒ 0
        assertEquals(0.0, fcD(c, "１２"))          // :6159-6160 长度门（2 码元 < CP932 4 字节）
        assertEquals(0.0, fcD(c, "1.5あ"))         // 同上：含多字节字符即 0，不做部分解析
        assertEquals(0.0, fcD(c, "1.5\u3000"))     // .NET 只吃 ASCII 空白
        assertEquals(0.0, fcD(c, "1e"))           // 指数缺数字
        assertEquals(0.0, fcD(c, "1.5.5"))         // 小数点重复（ja-JP 下 '.' 是小数点）
        // 特值：NaN 是 ASCII，过得了长度门（:6159）⇒ 走 TryParse 的 NaN 分支；
        // 文化的无穷符号 ∞ 在 CP932 是 2 字节 ⇒ **长度门先落 0**，.NET 的「∞ 可解析」在
        // 这条路径上不可达（与 fork 同值；解析器侧的 ∞ 分支见下一个用例）。
        assertTrue(fcD(c, "NaN").isNaN())
        assertEquals(0.0, fcD(c, "∞"))
        assertEquals(0.0, fcD(c, "-∞"))
        assertEquals(0.0, fcD(c, "Infinity"))   // .NET Core 3.0+ 不认文本 Infinity
        // 溢出回 ±∞、下溢回 0（不是失败路径）
        assertEquals(Double.POSITIVE_INFINITY, fcD(c, "1e400"))
        assertEquals(0.0, fcD(c, "1e-400"))
    }

    @Test
    fun `parseDoubleCurrentCulture 与 dotnet TryParse 的逐条口径`() = withLocale(Locale.JAPAN) {
        // 这一层不带 TOFLOAT 的长度门（:6159），只对 .NET TryParse 本身
        fun p(s: String, l: Locale = Locale.getDefault()) = parseDoubleCurrentCulture(s, l)
        assertEquals(1.5, p("1.5"))
        assertEquals(15.0, p("1,5"))                 // AllowThousands：',' 是千分位
        assertEquals(0.5, p(".5"))
        assertEquals(12.0, p("12."))
        assertEquals(1.5, p(" 1.5 "))
        assertEquals(null, p("1.5\u3000"))            // U+3000 不算空白（.NET 只吃 ASCII 六字符）
        assertEquals(null, p("\u00A01.5"))
        assertEquals(null, p("Infinity"))
        assertTrue(p("nan")!!.isNaN())               // NaN 符号大小写不敏感
        assertEquals(Double.POSITIVE_INFINITY, p("∞"))   // 文化正无穷符号
        assertEquals(Double.NEGATIVE_INFINITY, p("-∞"))
        assertEquals(Double.POSITIVE_INFINITY, p("1e400"))
        assertEquals(0.0, p("1e-400"))

        val de = Locale.GERMANY
        assertEquals(1.5, p("1,5", de))
        assertEquals(15.0, p("1.5", de))             // '.' 是千分位
        assertEquals(1234.5, p("1.234,5", de))
        assertEquals(null, p(".5", de))              // 分组符不能在首个数字之前
        assertEquals(null, p("1,5.5", de))           // 小数点之后不再认分组符
        assertEquals(15.0, p("1.5,", de))            // 尾随分组符允许

        // 分组符本身是空白时，.NET 还额外认 ASCII 空格（fr-FR 实测 "1 000" 成立）；
        // NBSP 仍失败。这是 KDoc 里登记的残差复刻。
        val fr = Locale.FRANCE
        assertEquals(1000.0, p("1 000", fr))
        assertEquals(null, p("1\u00A0000", fr))
    }

    @Test
    fun `TOFLOAT 数值实参保留恒等回退 与 arity 门`() {
        val c = fcCtx()
        // 偏离 B1：fork 在装载期就拒绝非字符串实参；移植侧按运行期值兜底
        assertEquals(3.0, fcD(c, 3L))
        assertEquals(2.5, fcD(c, Value.FloatV(2.5)))
        // argumentTypeArray.Length == 1（:6150）⇒ 只看个数
        assertFailsWith<CodeEE> { c.callFunction("TOFLOAT", emptyList()) }
        assertFailsWith<CodeEE> {
            c.callFunction("TOFLOAT", listOf(Value.StrV("1"), Value.StrV("2")))
        }
    }

    @Test
    fun `TOFLOAT 与 TOINT 的字符串口径差异 同串不同落点`() = withLocale(Locale.JAPAN) {
        val c = fcCtx()
        // TOINT 只吃「前导整数 + 纯数字小数尾」（:6126-6140），TOFLOAT 走 TryParse
        assertEquals(1L, fcI(c, "TOINT", "1.5"))
        assertEquals(1.5, fcD(c, "1.5"))
        // TOINT 不吃空白（首字符必须是数字/符号），TOFLOAT 吃
        assertEquals(0L, fcI(c, "TOINT", " 1.5 "))
        assertEquals(1.5, fcD(c, " 1.5 "))
        // ',' 在 TOINT 里是非法字符（⇒0）；在 TOFLOAT 的 ja-JP 里是千分位（⇒15）
        assertEquals(0L, fcI(c, "TOINT", "1,5"))
        assertEquals(15.0, fcD(c, "1,5"))
        // 长度门两族共用同一判据（:6110 / :6159）
        assertEquals(0L, fcI(c, "TOINT", "１２"))
        assertEquals(0.0, fcD(c, "１２"))
        // TOI 在 fork（Creator.cs:143-150）与本仓都不存在
        assertFailsWith<CodeEE> { fcI(c, "TOI", "1") }
        // TOSTR 的实参是 Int（ArgTypeList{Int,String}），浮点实参在 fork 是装载错误，
        // 本仓退回运行期类型错误（偏离 B1）
        assertFailsWith<CodeEE> { c.callFunction("TOSTR", listOf(Value.FloatV(1.5))) }
        // 表达式体必须以 Unit 结尾，否则返回 CodeEE 的方法会被 JUnit 当成非测试方法静默跳过
        Unit
    }
}
