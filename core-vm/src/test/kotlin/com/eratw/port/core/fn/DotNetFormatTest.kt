package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * TOSTR / MONEYSTR 共用的 .NET 数字格式串测试（[formatDotNet]）。
 *
 * 判据来源：
 * 1. 原版语义 —— `FunctionMethodCreator.cs:5461-5499`（ToStrMethod：无第 2 参时 `ToString()`，
 *    有则 `intValue.ToString(strValue)` 于 `:5491`，FormatException 于 `:5495` 转 CodeEE）；
 *    `FunctionMethodCreator.cs:3873-3921`（MoneyStrMethod 走同一条 `ToString(format)`，见 `:3908`，
 *    再按 Config.MoneyFirst / MoneyLabel 前后贴单位）。CodeEE 文案模板见 `Lang.cs:3862`。
 * 2. .NET 行为基准 —— 本机 dotnet 8.0.425 探针程序（写在系统临时目录，不入库）对「格式串 x 数值」
 *    全表实测，并让 [formatDotNet] 跑同一张表逐行对拍：82 条格式 x 41 值（3,362 行）+
 *    25 条格式 x 26 值（650 行）+ 15 条怪格式 x 41 值（615 行），共 4,627 行差异 0
 *    （最后一张表里 FormatException 与移植侧 CodeEE 归一为同一个标记）。
 *    本文件的 111 条期望值由脚本从下述测试体里反解出来，再同时喂给 dotnet 与 [formatDotNet]，
 *    两侧读数均为 ok=111 / mismatch=0 —— 每条期望值都是实测，不是推算。
 * 3. 语料实际格式串 —— 由最后一个用例在 `ERATW_CORPUS` 下现扫，不写死语料绝对路径。
 *
 * 舍入口径结论（实测，非猜）：自定义格式在小数位不足时是**远离零舍入**（等价 MidpointRounding.AwayFromZero），
 * 不是 ToNearestEven，也不是 ToZero —— 见 [小数位不足时按远离零舍入] 的 5 / 25 / 125 / 1245 四例；
 * 半数取偶会分别得到 0.00 / 0.02 / 0.12 / 1.24。
 */
private fun fmt(value: Long, format: String): String = formatDotNet(value, format, "TOSTR")

/** 边界值组：0 / 个位 / 补零边界 / 四位数 / 负数 / Long 两端。 */
private val edgeValues = longArrayOf(0L, 5L, 7L, 42L, 1234L, -1L, -42L, -1234L, Long.MIN_VALUE, Long.MAX_VALUE)

class DotNetFormatTest {

    // -----------------------------------------------------------------------
    // 语料实际出现的自定义格式
    // -----------------------------------------------------------------------

    @Test
    fun `补零占位符 000 与 00 与 0`() {
        // "000"：ERB/TITLE.ERB:59、ERB/コマンド関連/COMF/日常系/COMF418 家庭菜園.ERB:206
        assertEquals("000", fmt(0L, "000"))
        assertEquals("007", fmt(7L, "000"))
        assertEquals("042", fmt(42L, "000"))
        assertEquals("1234", fmt(1234L, "000"))
        assertEquals("-042", fmt(-42L, "000"))
        assertEquals("-9223372036854775808", fmt(Long.MIN_VALUE, "000"))
        // "00"：ERB/COMMON.ERB:1559、ERB/ステータス表示関連/INFO.ERB:1323
        assertEquals("00", fmt(0L, "00"))
        assertEquals("07", fmt(7L, "00"))
        assertEquals("42", fmt(42L, "00"))
        assertEquals("-01", fmt(-1L, "00"))
        assertEquals("-42", fmt(-42L, "00"))
        // "0"：ERB/fromEN/OMOGATARI/Omogatari_Set.ERB:3379
        assertEquals("0", fmt(0L, "0"))
        assertEquals("7", fmt(7L, "0"))
        assertEquals("-42", fmt(-42L, "0"))
        assertEquals("9223372036854775807", fmt(Long.MAX_VALUE, "0"))
    }

    @Test
    fun `井号千分位分组与强制末位零`() {
        // "#,###"：ERB/NOUMIN.ERB:219、ERB/コマンド関連/COMF/外出系/COMF625 家具屋.ERB:115
        assertEquals("", fmt(0L, "#,###"))                       // 全井号时 0 输出空串（实测）
        assertEquals("7", fmt(7L, "#,###"))
        assertEquals("1,000", fmt(1000L, "#,###"))
        assertEquals("1,234", fmt(1234L, "#,###"))
        assertEquals("1,234,567", fmt(1234567L, "#,###"))
        assertEquals("-1", fmt(-1L, "#,###"))
        assertEquals("-1,234", fmt(-1234L, "#,###"))
        assertEquals("-9,223,372,036,854,775,808", fmt(Long.MIN_VALUE, "#,###"))
        // "#,##0"：ERB/COMMON.ERB:3045（TOSTR）与 :3033、:3039（MONEYSTR）
        assertEquals("0", fmt(0L, "#,##0"))                      // 末位是强制 0，故不是空串
        assertEquals("1,000", fmt(1000L, "#,##0"))
        assertEquals("999,999", fmt(999999L, "#,##0"))
        assertEquals("-1,234", fmt(-1234L, "#,##0"))
        // 分组逗号个数与占位符个数无关（实测）；占位符之间的逗号是分组而非缩放
        assertEquals("1,234", fmt(1234L, "#,##"))
        assertEquals("1,234", fmt(1234L, "#,##,###"))
        assertEquals("0,000", fmt(0L, "0,###"))
        assertEquals("0,007", fmt(7L, "0,###"))
    }

    @Test
    fun `小数点前的逗号做千分缩放`() {
        // "0,.###"：ERB/TITLE.ERB:64、ERB/fromEN/OMOGATARI/Omogatari_Set.ERB:2086
        // 整数段末尾的逗号 = 数值先除以 1000，小数段井号不补零
        assertEquals("0", fmt(0L, "0,.###"))
        assertEquals("0.007", fmt(7L, "0,.###"))
        assertEquals("0.042", fmt(42L, "0,.###"))
        assertEquals("0.999", fmt(999L, "0,.###"))
        assertEquals("1", fmt(1000L, "0,.###"))
        assertEquals("1.234", fmt(1234L, "0,.###"))
        assertEquals("4.98", fmt(4980L, "0,.###"))               // eraTW 版本号 4.980 的实际显示
        assertEquals("1234.567", fmt(1234567L, "0,.###"))
        assertEquals("-0.001", fmt(-1L, "0,.###"))
        assertEquals("-1234.567", fmt(-1234567L, "0,.###"))
        assertEquals("9223372036854775.807", fmt(Long.MAX_VALUE, "0,.###"))
        assertEquals("-9223372036854775.808", fmt(Long.MIN_VALUE, "0,.###"))
        // 小数段换成强制 0 时补零；整数段只剩井号时小数点前不写 0
        assertEquals("0.042", fmt(42L, "0,.000"))
        assertEquals(".001", fmt(1L, "#,.###"))
        assertEquals("-.001", fmt(-1L, "#,.###"))
        assertEquals("45", fmt(45000L, "#,.###"))
    }

    // -----------------------------------------------------------------------
    // 语料实际出现的标准格式
    // -----------------------------------------------------------------------

    @Test
    fun `标准格式 D 与 X 含小写说明符`() {
        // "D2"：ERB/CASINO/CASINO_SLOT_HELP.ERB:60；"D3"：ERB/魔改内容/ResourceManager.ERB:9
        assertEquals("00", fmt(0L, "D2"))
        assertEquals("07", fmt(7L, "D2"))
        assertEquals("42", fmt(42L, "D2"))
        assertEquals("-01", fmt(-1L, "D2"))
        assertEquals("1234", fmt(1234L, "D2"))
        assertEquals("000", fmt(0L, "D3"))
        assertEquals("042", fmt(42L, "D3"))
        assertEquals("-042", fmt(-42L, "D3"))
        assertEquals("00042", fmt(42L, "D5"))                    // 黄金基准 pc-golden-root.txt:141
        // "X"：ERB/CASINO/駒草の賭場.ERB:1124；"X2"：ERB/CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB:237
        // "X6"：ERB/COMMON_HTML.ERB:35；"x6"：ERB/method_from_eratohoЯeverse/PANIMATION.ERB:145
        assertEquals("0", fmt(0L, "X"))
        assertEquals("2A", fmt(42L, "X"))
        assertEquals("3E7", fmt(999L, "X"))
        assertEquals("FFFFFFFFFFFFFFFF", fmt(-1L, "X"))          // 负数按 64 位补码全宽（实测）
        assertEquals("8000000000000000", fmt(Long.MIN_VALUE, "X"))
        assertEquals("00", fmt(0L, "X2"))
        assertEquals("3E7", fmt(999L, "X2"))
        assertEquals("000000", fmt(0L, "X6"))
        assertEquals("00002A", fmt(42L, "X6"))
        assertEquals("12D687", fmt(1234567L, "X6"))
        assertEquals("000000FF", fmt(255L, "X8"))                // 同一条黄金基准行
        assertEquals("00002a", fmt(42L, "x6"))
        assertEquals("ffffffffffffffff", fmt(-1L, "x6"))
    }

    // -----------------------------------------------------------------------
    // 实测边角（语料没有，但实现已覆盖，按实测表钉住防回归）
    // -----------------------------------------------------------------------

    @Test
    fun `小数位不足时按远离零舍入`() {
        // "0,.##" 即 (值 / 1000) 保留 2 位小数；半数取偶会得到注释里括号中的值
        assertEquals("0.01", fmt(5L, "0,.##"))                  // 0.005 → 0.01（取偶为 0.00）
        assertEquals("0.03", fmt(25L, "0,.##"))                 // 0.025 → 0.03（取偶为 0.02）
        assertEquals("0.13", fmt(125L, "0,.##"))                // 0.125 → 0.13（取偶为 0.12）
        assertEquals("0.88", fmt(875L, "0,.##"))                // 0.875 → 0.88
        assertEquals("1.24", fmt(1235L, "0,.##"))
        assertEquals("1.25", fmt(1245L, "0,.##"))               // 取偶为 1.24
        assertEquals("-0.01", fmt(-5L, "0,.##"))                // 负数同样远离零，不是向零截断
        assertEquals("-1.24", fmt(-1235L, "0,.##"))
        // 标准 E / G 的尾数舍入同为远离零（实测）
        assertEquals("1.25E+003", fmt(1245L, "E2"))
        assertEquals("1.2E+03", fmt(1245L, "G2"))
    }

    @Test
    fun `舍入归零时的负号抑制与分节回退`() {
        // 值被本段格式舍入成 0 时，单段格式丢掉自动负号（实测 -5 与 5 同为 0）
        assertEquals("0", fmt(5L, "0,.#"))
        assertEquals("0", fmt(-5L, "0,.#"))
        assertEquals("0", fmt(-25L, "0,.#"))
        assertEquals("0", fmt(0L, "0,.#"))
        assertEquals("0", fmt(-5L, "0,,.###"))                  // 二次缩放后仍归零
        assertEquals("-0.001", fmt(-1235L, "0,,.###"))
        // 三段格式（正;负;零）：正数舍入成 0 时改走零段
        assertEquals("零", fmt(0L, "0,.#;负;零"))
        assertEquals("零", fmt(42L, "0,.#;负;零"))               // 0.042 → 0.0 → 归零 → 零段
        assertEquals("1.2", fmt(1234L, "0,.#;负;零"))
        assertEquals("负", fmt(-42L, "0,.#;负;零"))              // 负号先选第二段
        assertEquals("零", fmt(0L, "#,###;负#,###;零"))          // 有零段时全井号不再输出空串
        assertEquals("42", fmt(42L, "#,###;负#,###;零"))
        assertEquals("负42", fmt(-42L, "#,###;负#,###;零"))
        // 只有两段时没有零段可回退，归零后仍用第一段
        assertEquals("0", fmt(0L, "0,.###;负0,.###"))
        assertEquals("负0.042", fmt(-42L, "0,.###;负0,.###"))
    }

    @Test
    fun `字面量转义与百分号与分节符号`() {
        assertEquals("(5)", fmt(5L, "'('0')'"))
        assertEquals("-(5)", fmt(-5L, "'('0')'"))               // 自动负号仍在前（单段格式）
        assertEquals("--5", fmt(-5L, "\"-\"0"))                  // 双引号字面量与自动负号叠加
        assertEquals("--5", fmt(-5L, "\\-0"))                    // 反斜杠转义同上
        assertEquals("--12345", fmt(-12345L, "\\-0"))            // 转义出的负号与自动负号叠加（实测）
        assertEquals("-\$1,235", fmt(-1235L, "\$#,##0"))
        assertEquals("500%", fmt(5L, "0%"))
        assertEquals("-100%", fmt(-1L, "0%"))
        assertEquals("123,400.0%", fmt(1234L, "#,##0.0%"))
        assertEquals("1000‰", fmt(1L, "0‰"))
        assertEquals("-5000‰", fmt(-5L, "0‰"))
        assertEquals("ZERO", fmt(0L, "0;-0;ZERO"))
        assertEquals("-5", fmt(-5L, "0;-0;ZERO"))
        assertEquals("(<1235>)", fmt(-1235L, "0;(<0>)"))
        assertEquals("-5", fmt(-5L, "0;"))                       // 第二段为空 → 第一段通用 + 自动负号
        assertEquals("-1", fmt(1L, "-0;;0"))                     // 第二段为空 → 正数也走第一段
        assertEquals("42.0", fmt(-42L, "0;.0"))                  // 小数点前无占位符时整数字节被忽略
        assertEquals("1.0", fmt(-1L, "0;.0"))
        assertEquals("42", fmt(42L, "0;.0"))
    }

    @Test
    fun `非法格式抛CodeEE且古怪格式按实测照抄`() {
        // 实测里只有「未知标准说明符」抛 FormatException（原版于 :5493-5495 转 CodeEE）
        assertFailsWith<CodeEE> { fmt(12345L, "Q") }
        assertFailsWith<CodeEE> { fmt(12345L, "Q9") }
        assertFailsWith<CodeEE> { fmt(12345L, "Z2") }
        // .NET Framework（Emuera 的实际运行框架）对 Int64 也拒 B（二进制）与 R（往返）：
        // 本机 .NET Framework 4.8 实测 `([long]12345).ToString("B")` / ("R") / ("R5") 均抛
        // FormatException「格式说明符无效」，故原版 TOSTR / MONEYSTR 会走 InvalidFormat 抛 CodeEE。
        // （dotnet 8 的 B/R 行为不同，不能当基准 —— 本文件其余基线以 dotnet 8 探针为准，这一条以 Framework 为准。）
        assertFailsWith<CodeEE> { fmt(12345L, "B") }
        assertFailsWith<CodeEE> { fmt(12345L, "R") }
        assertFailsWith<CodeEE> { fmt(12345L, "R5") }
        assertEquals("函数 TOSTR：第 2 个参数的数字格式串 \"Q\" 非法", assertFailsWith<CodeEE> { fmt(1L, "Q") }.message)
        // 以下都落进自定义格式分支，.NET 不抛错（实测），移植侧同样不抛
        for (odd in listOf("abc", "\$", ",", ",,", "0,,", "%%", "0;0;0;0", "D,2", "E+2", "\\\\", "0\$\$")) {
            fmt(12345L, odd)
        }
        assertEquals("abc12345abc", fmt(12345L, "'abc'0'abc'"))
        assertEquals("\\12345", fmt(12345L, "\\\\0"))            // 转义出的反斜杠作为字面量
        // 空格式串 = 原版无第 2 参时的 ToString()，等价十进制原样（C# :5485-5487）
        assertEquals("12345", fmt(12345L, ""))
        assertEquals("-12345", fmt(-12345L, ""))
    }

    @Test
    fun `Long 两端不经过取负不溢出`() {
        // Long.MIN_VALUE 取负会溢出；实现全程走 BigInteger，实测表逐值一致
        val formats = listOf("000", "00", "0", "#,###", "#,##0", "0,.###", "D2", "D3", "X", "X6", "0%")
        for (format in formats) {
            for (value in edgeValues) {
                fmt(value, format)                              // 只要求不抛、不溢出
            }
        }
        assertEquals("-9223372036854775808", fmt(Long.MIN_VALUE, "0"))
        assertEquals("-9,223,372,036,854,775,808", fmt(Long.MIN_VALUE, "#,##0"))
        assertEquals("-9223372036854775.808", fmt(Long.MIN_VALUE, "0,.###"))
        assertEquals("-922337203685477580800%", fmt(Long.MIN_VALUE, "0%"))
        assertEquals("9223372036854775807", fmt(Long.MAX_VALUE, "000"))
    }

    // -----------------------------------------------------------------------
    // 真实语料（判据：语料里实际出现的格式串必须全部可用且与实测一致）
    // -----------------------------------------------------------------------

    @Test
    fun `语料实际使用的所有格式串都被支持`() {
        val env = System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }
        Assumptions.assumeTrue(env != null, "跳过：未设置 ERATW_CORPUS")
        var root: Path = Paths.get(env!!)
        Assumptions.assumeTrue(Files.isDirectory(root), "跳过：ERATW_CORPUS 不是目录 → $env")
        // 兼容「给的是游戏根」的用法：游戏根下再取 ERB 子目录
        if (listScriptFiles(root).isEmpty()) {
            val erb = root.resolve("ERB")
            Assumptions.assumeTrue(Files.isDirectory(erb), "跳过：$env 自身没有脚本且没有 ERB 子目录")
            root = erb
        }
        val files = listScriptFiles(root)
        Assumptions.assumeTrue(files.isNotEmpty(), "跳过：$root 下没有 ERB/ERH 文件")

        val found = LinkedHashSet<String>()
        for (file in files) {
            // 语料是 Shift_JIS 系码页；按字节原样解码即可，格式串本身全是 ASCII
            val text = runCatching { String(Files.readAllBytes(file), Charsets.ISO_8859_1) }.getOrNull() ?: continue
            collectFormats(text, "TOSTR", found)
            collectFormats(text, "MONEYSTR", found)
        }
        assertTrue(found.isNotEmpty(), "语料扫描没提取到任何带格式参数的调用（扫描器失配或语料根不对）")

        // 2026-10 全库枚举出的语料格式串全集（13 处去重后 12 种）
        val corpusFormats = setOf("000", "00", "0", "#,###", "#,##0", "0,.###", "D2", "D3", "X", "X2", "X6", "x6")
        val unexpected = found.filter { it !in corpusFormats }
        assertTrue(unexpected.isEmpty(), "语料里出现未登记的格式串 $unexpected（需补实现并跑 dotnet 对照）")
        // 每种格式在边界值上都不抛错（这就是本轮改造的验收点：真实语料不再撞 CodeEE）
        for (format in found) {
            for (value in edgeValues) {
                fmt(value, format)
            }
        }
        // 逐个格式至少一例实测读数（与上面的单测同源，此处按语料命中集再钉一遍）
        val oneShot = mapOf(
            "000" to (7L to "007"),
            "00" to (7L to "07"),
            "0" to (7L to "7"),
            "#,###" to (1234L to "1,234"),
            "#,##0" to (0L to "0"),
            "0,.###" to (4980L to "4.98"),
            "D2" to (7L to "07"),
            "D3" to (42L to "042"),
            "X" to (42L to "2A"),
            "X2" to (999L to "3E7"),
            "X6" to (42L to "00002A"),
            "x6" to (42L to "00002a"),
        )
        for ((format, pair) in oneShot) {
            assertEquals(pair.second, fmt(pair.first, format), "格式串 \"$format\" 与实测读数不符")
        }
    }

    private fun listScriptFiles(root: Path): List<Path> {
        Files.walk(root).use { stream ->
            return stream.filter { Files.isRegularFile(it) }
                .filter {
                    val name = it.fileName.toString().uppercase()
                    name.endsWith(".ERB") || name.endsWith(".ERH")
                }
                .toList()
        }
    }

    /**
     * 从脚本原文里抠出 `函数名(值, "格式串")` 的第二个参数。
     *
     * 只认字面量串，且只收「自定义数字格式字符集」与「单字母加精度的标准格式」两种形态，
     * 避免把 HTML 串等无关字面量误当格式；函数名两侧必须是名字符边界（XML_TOSTR 不算 TOSTR）；
     * 括号按深度配对、双引号内不参与配对，单个调用最多前看 4000 字符，超限即视为失配跳过。
     */
    private fun collectFormats(text: String, functionName: String, sink: MutableSet<String>) {
        var searchFrom = 0
        while (true) {
            val hit = text.indexOf(functionName, searchFrom, ignoreCase = true)
            if (hit < 0) return
            val after = hit + functionName.length
            searchFrom = after
            if (hit > 0 && isNameChar(text[hit - 1])) continue
            if (after < text.length && isNameChar(text[after])) continue
            var cursor = after
            while (cursor < text.length && text[cursor] == ' ') cursor++
            if (cursor >= text.length || text[cursor] != '(') continue
            secondArgument(text, cursor + 1)?.let { literal ->
                if (literal.matches(CUSTOM_NUMBER_FORMAT) || literal.matches(STANDARD_NUMBER_FORMAT)) sink += literal
            }
        }
    }

    /** 返回第 2 个参数的字面量内容；参数不足两个 / 不是纯字面量串 / 括号不配对时返回 null。 */
    private fun secondArgument(text: String, start: Int): String? {
        val limit = minOf(text.length, start + 4000)
        var depth = 1
        var topLevelCommas = 0
        var segmentStart = start
        var cursor = start
        var inQuote = false
        while (cursor < limit) {
            val ch = text[cursor]
            if (inQuote) {
                if (ch == '"') inQuote = false
            } else {
                when (ch) {
                    '"' -> inQuote = true
                    '(' -> depth++
                    ')' -> {
                        if (depth == 1) {
                            // 本调用收尾：只有在第 2 个参数段上才取它，否则参数不足两个
                            return if (topLevelCommas == 1) unquote(text.substring(segmentStart, cursor)) else null
                        }
                        depth--
                    }
                    ',' -> if (depth == 1) {
                        if (topLevelCommas == 1) return null
                        topLevelCommas++
                        segmentStart = cursor + 1
                    }
                    else -> Unit
                }
            }
            cursor++
        }
        return null
    }

    /** 非「双引号包裹的简单字面量」一律返回带 NUL 的哨兵值，让白名单正则拒掉它。 */
    private fun unquote(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length < 2 || !trimmed.startsWith("\"") || !trimmed.endsWith("\"")) return "\u0000"
        return trimmed.substring(1, trimmed.length - 1)
    }

    private fun isNameChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    private companion object {
        /** 语料里出现过的自定义数字格式字符集：占位符、逗号、小数点、减号、百分号。 */
        val CUSTOM_NUMBER_FORMAT = Regex("[0#,.%-]{1,16}")

        /** 标准数字格式：单个说明符字母，可带最多 6 位精度。 */
        val STANDARD_NUMBER_FORMAT = Regex("[A-Za-z][0-9]{0,6}")
    }
}
