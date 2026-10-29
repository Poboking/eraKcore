package com.eratw.port.core.fn

import com.eratw.port.core.expr.Value
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 字符串族**补丁规格的可执行对拍**（S1 / S3 / S5 / S6 / S8）。
 *
 * 本文件分两层，逐条断言都同时钉住两侧：
 * - **规格侧**：下面几个 `ref…` 函数是「按原版口径重写之后应得的读数」的独立实现
 *   （[refDotNetExpand] / [refStrlen] / [refConvert] / [refSimpleUpper] / [refSimpleLower] / [refEscape]）。
 * - **产品侧**：同一组向量再走一遍产品入口（REPLACE / STRLENS / CONVERT / TOUPPER / TOLOWER / ESCAPE），
 *   经 [BuiltinEvalContext] 的真注册表求值。
 *
 * 期望值全部来自本机 dotnet 8.0.421 net8.0 探针实测（与 `Emuera.csproj` 的 `netcoreapp8.0` 同代），
 * 不是推算。分歧编号与 C# 行号见 `StringFamilyAuditTest` 的类 KDoc。
 */

// ---------------------------------------------------------------------------
// S3 —— .NET Regex 替换语言（spec: FunctionMethodCreator.cs:5687 `regex.Replace(str, replacement)`）
// ---------------------------------------------------------------------------

/**
 * .NET `Regex.Replace` 的替换串展开（substitution）规格实现。
 *
 * 语法（.NET 实测）：
 * - `$$` → `$`；`$&` → 整个匹配；`$0`/`${0}` → 整个匹配
 * - `$` + 反引号 → 匹配**左侧**的整段输入（未替换的原文）；`$'` → 右侧；`$_` → 整段输入
 * - `$+` → 最后一个**成功捕获**的组
 * - `$N` / `$NN` / `${N}` → 第 N 组
 * - `${名}` → 命名组；名字不存在 / `}` 未闭合 ⇒ 整段原样输出
 * - 数字组号**不做逐位回退**：整段数字一起解析，越界就整段原样（`$10` 只有 1 组 ⇒ 输出 `$10`）
 * - `\` 没有转义语义（原样输出）；`$` 后跟其它字符 ⇒ `$` 原样输出
 * - 模式 mode=1 的数组替换走 MatchEvaluator，返回值**不再二次展开**（本仓已是该口径）
 */
internal fun refDotNetExpand(input: String, m: MatchResult, replacement: String): String =
    buildString {
        val groups = m.groups
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            if (c != '$') { append(c); i++; continue }
            if (i + 1 >= replacement.length) { append('$'); i++; continue }
            when (val n = replacement[i + 1]) {
                '$' -> { append('$'); i += 2 }
                '&' -> { append(m.value); i += 2 }
                '`' -> { append(input.substring(0, m.range.first)); i += 2 }
                '\'' -> { append(input.substring(m.range.last + 1)); i += 2 }
                '_' -> { append(input); i += 2 }
                '+' -> {
                    // 无任何捕获组时 .NET 回落到整个匹配（实测 `$+` on "abc"/"b" ⇒ "abc"）
                    var last = m.value
                    for (g in 1 until groups.size) {
                        val gr = groups[g]
                        if (gr != null && gr.value != null && gr.range != IntRange.EMPTY) last = gr.value
                    }
                    append(last)
                    i += 2
                }
                '{' -> {
                    val close = replacement.indexOf('}', i + 2)
                    if (close < 0) {
                        append(replacement.substring(i))
                        i = replacement.length
                    } else {
                        val body = replacement.substring(i + 2, close)
                        val num = body.toIntOrNull()
                        val captured: String? = when {
                            body.isEmpty() -> null
                            num != null -> if (num in 0 until groups.size) groups[num]?.value ?: "" else null
                            else -> runCatching { m.groups[body] }.getOrNull()?.let { gr -> gr.value ?: "" }
                        }
                        if (captured == null) append(replacement.substring(i, close + 1)) else append(captured)
                        i = close + 1
                    }
                }
                in '0'..'9' -> {
                    var j = i + 1
                    while (j < replacement.length && replacement[j].isDigit()) j++
                    val num = replacement.substring(i + 1, j).toIntOrNull()
                    if (num != null && num < groups.size) {
                        append(groups[num]?.value ?: "")
                        i = j
                    } else {
                        append(replacement.substring(i, j))
                        i = j
                    }
                }
                else -> { append('$'); i++ }
            }
        }
    }

/** 整串替换的规格入口（对应 `regex.Replace(str, replacement)` 的替换语言部分）。 */
internal fun refRegexReplace(input: String, pattern: String, replacement: String): String =
    Regex(pattern).replace(input) { m -> refDotNetExpand(input, m, replacement) }

// ---------------------------------------------------------------------------
// S1 —— 字节数按「UTF-16 码元逐个算」再求和（spec: LangManager.cs:15-22 + :39/:74/:90）
// ---------------------------------------------------------------------------

/** 规格：整串 ASCII 走长度快路径；否则**逐码元**取配置码页字节数再累加（代理对各算 1+1）。 */
internal fun refStrlen(s: String, perCharBytes: (Char) -> Int): Int =
    if (s.all { it.code < 0x80 }) s.length else s.sumOf { perCharBytes(it) }

// ---------------------------------------------------------------------------
// S5 —— Convert.ToString(long, radix)（spec: FunctionMethodCreator.cs:5761）
// ---------------------------------------------------------------------------

/** 规格：2/8/16 进制按无符号 64 位补码，10 进制走有符号。 */
internal fun refConvert(v: Long, radix: Int): String = when (radix) {
    2, 8, 16 -> java.lang.Long.toUnsignedString(v, radix)
    10 -> v.toString()
    else -> throw IllegalArgumentException("radix")
}

// ---------------------------------------------------------------------------
// 大小写映射（spec: FunctionMethodCreator.cs:5587-5588）—— fixcore B1（2026-10-26）订正：
// .NET 8 走 ICU 全映射、可扩长（Unicode SpecialCasing.txt 无条件行），旧「逐字符简单映射、
// 绝不扩长」前提不成立（ß→SS、ﬁ→FI、İ→i + U+0307）。
// ---------------------------------------------------------------------------

internal fun refSimpleUpper(s: String): String = s.uppercase()

internal fun refSimpleLower(s: String): String = s.lowercase()

// ---------------------------------------------------------------------------
// S8 —— Regex.Escape 的转义集（空白含 \t \n \r \f；\v 与全角空格不转）
// ---------------------------------------------------------------------------

internal fun refEscape(s: String): String = buildString {
    for (c in s) {
        when (c) {
            in "\\*+?|{[()^$.#" -> { append('\\'); append(c) }
            ' ' -> append("\\ ")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\u000C' -> append("\\f")
            else -> append(c)
        }
    }
}

// ---------------------------------------------------------------------------
// 产品侧入口（经真注册表求值）
// ---------------------------------------------------------------------------

private fun specCtx(overrides: Map<String, String> = emptyMap()): BuiltinEvalContext {
    val store = VariableStore()
    return BuiltinEvalContext(TestEvalContext(store), store, object : BuiltinHost {
        override val configOverrides: Map<String, String> get() = overrides
    })
}

private fun specV(x: Any): Value = if (x is String) Value.StrV(x) else Value.IntV((x as Number).toLong())

/** 产品字符串函数。 */
private fun specS(ctx: BuiltinEvalContext, name: String, vararg a: Any): String =
    (ctx.callFunction(name, a.map { specV(it) }) as Value.StrV).v

/** 产品整型函数。 */
private fun specI(ctx: BuiltinEvalContext, name: String, vararg a: Any): Long =
    (ctx.callFunction(name, a.map { specV(it) }) as Value.IntV).v

/** 规格侧与产品侧必须同时等于 dotnet 实测值（任一侧漂了都红）。 */
private fun assertVector(expected: String, ref: String, prod: String, label: String) {
    assertEquals(expected, ref, "$label（规格草案）")
    assertEquals(expected, prod, "$label（产品）")
}

class StringPatchSpecTest {

    private val ja = specCtx()                                              // 出厂 JAPANESE ⇒ 932
    private val hans = specCtx(mapOf("DEFAULT ANSI ENCODING" to "CHINESE_HANS")) // ⇒ 936

    private fun replaceVec(input: String, pattern: String, replacement: String, expected: String) =
        assertVector(
            expected,
            refRegexReplace(input, pattern, replacement),
            specS(ja, "REPLACE", input, pattern, replacement),
            "REPLACE(${input.quote()}, ${pattern.quote()}, ${replacement.quote()})",
        )

    @Test
    fun `替换语言的 dotnet 读数 分歧 S3`() {
        replaceVec("a1b22c", "[0-9]+", "<$&>", "a<1>b<22>c")
        replaceVec("a1b22c", "([0-9]+)", "[$1]", "a[1]b[22]c")
        replaceVec("abc", "b", "$", "a\$c")
        replaceVec("abc", "b", "$0", "abc")
        replaceVec("abc", "b", "\\", "a\\c")
        replaceVec("abc", "b", "\${x}", "a\${x}c")
        replaceVec("abc", "b", "$$", "a\$c")
        replaceVec("abc", "b", "$`", "aac")
        replaceVec("abc", "b", "$'", "acc")
        replaceVec("abc", "b", "\$_", "aabcc")
        replaceVec("abc", "(?<g>b)", "\${g}!", "ab!c")
        replaceVec("abc", "b", "$+", "abc")
        replaceVec("abc", "b", "$2", "a\$2c")          // 无第 2 组 ⇒ 整段原样
        replaceVec("abc", "(b)", "$10", "a\$10c")      // 数字组号不回退
        replaceVec("abc", "(b)", "$01", "abc")         // 前导零可接受
        replaceVec("abc", "(b)", "\${x", "a\${xc")     // 未闭合 ⇒ 原样
        replaceVec("a1b22", "([0-9])([0-9])?", "$2-$1", "a-1b2-2")
        replaceVec("ab", "(a)(x)?", "[$2]", "[]b")     // 未捕获组 ⇒ 空串
    }

    @Test
    fun `逐码元字节数的 dotnet 读数 分歧 S1`() {
        // CP932 / CP936 都是「不可映射按 ? 一个字节」，代理对合计 2
        val cp932: (Char) -> Int = { it.toString().toByteArray(charset("windows-31J")).size }
        val cp936: (Char) -> Int = { it.toString().toByteArray(charset("GBK")).size }
        fun vec(s: String, bytes932: Int, bytes936: Int) = assertVector(
            "$bytes932/$bytes936",
            "${refStrlen(s, cp932)}/${refStrlen(s, cp936)}",
            "${specI(ja, "STRLENS", s)}/${specI(hans, "STRLENS", s)}",
            "STRLENS(${s.quote()})",
        )
        vec("abc", 3, 3)
        vec("あい", 4, 4)
        vec("😀", 2, 2)          // 原实现整串口径给 1（S1）
        vec("😀😀あ", 6, 6)      // 2 + 2 + 2
        vec("—", 1, 2)           // U+2014 在 CP932 不可映射
        vec("①", 2, 2)
        vec("ｱ", 1, 1)
    }

    @Test
    fun `CONVERT 的无符号读数 分歧 S5`() {
        fun vec(v: Long, radix: Int, expected: String) =
            assertVector(expected, refConvert(v, radix), specS(ja, "CONVERT", v, radix), "CONVERT($v, $radix)")
        vec(255, 16, "ff")
        vec(-1, 16, "ffffffffffffffff")
        vec(-1, 2, "1".repeat(64))
        vec(-1, 8, "1" + "7".repeat(21))   // 2^64-1 的八进制 = 1 后接 21 个 7
        vec(-15, 8, "1777777777777777777761")
        vec(-1, 10, "-1")
        vec(Long.MIN_VALUE, 16, "8000000000000000")
        vec(Long.MIN_VALUE, 10, Long.MIN_VALUE.toString())
        vec(Long.MAX_VALUE, 2, "1".repeat(63))
    }

    @Test
    fun `大小写全映射的 dotnet 读数（旧 S6 由 fixcore B1 订正）`() {
        fun up(s: String, expected: String) =
            assertVector(expected, refSimpleUpper(s), specS(ja, "TOUPPER", s), "TOUPPER(${s.quote()})")
        fun low(s: String, expected: String) =
            assertVector(expected, refSimpleLower(s), specS(ja, "TOLOWER", s), "TOLOWER(${s.quote()})")
        up("aBc", "ABC")
        up("ß", "SS")        // 全映射扩长（fixcore B1：旧 S6「不扩长」前提不成立）
        up("ﬁ", "FI")
        up("ǅ", "Ǆ")
        up("ς", "Σ")
        up("ａ", "Ａ")
        low("aBc", "abc")
        low("İ", "i\u0307") // 全映射：i + U+0307（ja/zh 区域与 invariant 等价）
        low("Ǆ", "ǆ")
    }

    @Test
    fun `Regex Escape 的空白集 分歧 S8`() {
        fun vec(s: String, expected: String) =
            assertVector(expected, refEscape(s), specS(ja, "ESCAPE", s), "ESCAPE(${s.quote()})")
        vec("a.b*c d", "a\\.b\\*c\\ d")
        vec("a#b", "a\\#b")
        vec("a[b]b", "a\\[b]b")
        vec("a-b", "a-b")
        vec("a\tb", "a\\tb")
        vec("a\nb", "a\\nb")
        vec("a\rb", "a\\rb")
        vec("a\u000Cb", "a\\fb")
        vec("a\u000Bb", "a\u000Bb")   // 垂直制表不转义（实测）
        vec("a\u3000b", "a\u3000b")   // 全角空格不转义（实测）
    }
}

/** 断言失败时的可读标签。 */
private fun String.quote(): String = "\"" + this.replace("\\", "\\\\").replace("\n", "\\n") + "\""
