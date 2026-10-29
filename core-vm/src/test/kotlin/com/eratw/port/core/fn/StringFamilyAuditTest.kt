package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 字符串族逐条对拍（权威 = `reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * 的类体 + `MinorShift.Emuera.Runtime.Utils/LangManager.cs`）。
 *
 * ## 对拍表（C# 行号 → 本仓实现 → 结论）
 *
 * | C# 类体 | 函数 | 本仓实现 | 结论 |
 * |---|---|---|---|
 * | 5269-5272 StrlenMethod | STRLENS | `PureMethods.kt:999-1001` → `LangCodec.strlen`（FormStringTerm.kt:383-394） | **S1 已修**（逐码元） |
 * | 5284-5287 StrlenuMethod | STRLENSU | `PureMethods.kt:1001-1002` | 一致 |
 * | 5311-5325 SubstringMethod | SUBSTRING | `PureMethods.kt:1003-1009` → `LangCodec.subString`（:414-439） | **S2 已修**（S1 派生） |
 * | 5349-5382 SubstringuMethod | SUBSTRINGU | `PureMethods.kt:1010-1023` | 一致（逐分支同构） |
 * | 5411-5429 StrfindMethod | STRFIND / STRFINDU | `PureMethods.kt:1024-1042` | **S1 派生已修**（前缀字节数） |
 * | 5446-5457 StrCountMethod | STRCOUNT | `PureMethods.kt:1043-1056` | **S13 已修**（`(?U)`；2026-10-22 真机 ICU 炸 → dotNetRegex 探测降级） |
 * | 5481-5497 ToStrMethod | TOSTR | `PureMethods.kt:1057-1062` + `formatDotNet` | 一致（另见 DotNetFormatTest 4,627 行实测） |
 * | 5509-5547 ToIntMethod | TOINT | `PureMethods.kt:1063-1086` | 一致 |
 * | 5578-5593 StrChangeStyleMethod | TOUPPER / TOLOWER / TOHALF / TOFULL | `PureMethods.kt:1087-1105` + `StrConv` 段 | **S6 已修**；**S7 已修**（2052/GBK 口径） |
 * | 5653-5687 ReplaceMethod | REPLACE | `PureMethods.kt:693-733` + `dotNetExpand`（:618-681） | **S3 已修**（替换语言）；S4 保留可诊断路径 |
 * | 5700-5719 UnicodeMethod | UNICODE | `PureMethods.kt` | 一致（行为）；文案已对齐 zhs 模板（ArgIsOutOfRange/InvalidUnicode，见 StringErrorTextTest） |
 * | 5732-5738 UnicodeByteMethod | UNICODEBYTE | `PureMethods.kt` | S10 崩溃不复刻（登记）；未配对代理改按 .NET UTF32 替换字符 U+FFFD（StringErrorTextTest） |
 * | 5754-5762 ConvertIntMethod | CONVERT | `PureMethods.kt:1140-1153` | **S5 已修**（无符号补码） |
 * | 5774-5811 IsNumericMethod | ISNUMERIC | `PureMethods.kt:1155-1173` | 一致 |
 * | 5823-5826 EscapeMethod | ESCAPE | `PureMethods.kt:1174-1177` + `vStrEscape`（:1238-1250） | **S8 已修**（\t \n \r \f） |
 * | 5849-5866 EncodeToUniMethod | ENCODETOUNI | `PureMethods.kt:1178-1186` | S11 崩溃不复刻（登记） |
 * | 5882-5891 CharAtMethod | CHARATU | `PureMethods.kt:1187-1192` | 一致 |
 * | 5924-5939 StrFormMethod | STRFORM | `PureMethods.kt:1193-1215` | **S9 已修**（EoL + CodeEE 包装） |
 * | 3893-3919 MoneyStrMethod | MONEYSTR | `PureMethods.kt:1216-1227` | 一致（单位来自宿主，默认值见 Builtins.kt:36-39） |
 * | 3761-3767 BarStringMethod | BARSTR | `PureMethods.kt:966-978` + `barChars`（HostMethods.kt:317-344） | **S12 已修**（Config.BarChar1/2） |
 *
 * ## 判据口径
 *
 * 每条「一致」断言的是**具体读数**；历史「分歧」条目已在本波翻到**正确值**（.NET 实测读数），
 * 注释里保留原现状与 C# 行号，回归时一旦再漂就能立刻看到差在哪。
 * .NET 侧读数不是推算：本机 dotnet 8.0.421 探针（net8.0，与 `Emuera.csproj` 的
 * `netcoreapp8.0` 同代）逐条实测，构造参数即本文件同名断言。
 *
 * ## 分歧处置清单
 *
 * - S1 `LangCodec.strlen` 慢路径改**逐 UTF-16 码元**累加（.NET 的 CP932/936 GetByteCount
 *   对代理对两半各回退 1 字节 ⇒ 2；原实现整串走 JDK 编码器折成 1）。spec: LangManager.cs:15-22。
 * - S2 由 S1 派生：`SUBSTRING("😀",0,1)` 现在按 .NET 口径只吐高位代理（spec: LangManager.cs:63-69）。
 * - S3 REPLACE 替换串改走本文件的 .NET 替换语言展开（`$&` / `$$` / `$` 反引号 / `$'` / `$_` /
 *   `$+` / `${名}` / `$N`），不再把 `Matcher` 的 IllegalArgumentException 漏出去（spec: :5687）。
 * - S4 字面替换（mode=2）空模式串：原版 `String.Replace("",…)` 抛 ArgumentException（未捕获，spec: :5684）；
 *   本仓**保留可诊断路径**（按 Kotlin 语义逐位插入），偏离登记待组长入 verify-notes。
 * - S5 CONVERT 负数 + 进制 2/8/16 改 `Long.toUnsignedString`（spec: :5761）。
 * - S6 TOUPPER/TOLOWER 改逐字符简单映射（spec: :5587-5588）。
 * - S7 TOFULL/TOHALF：原版 `Strings.StrConv(…, Config.Language)`（:5590-5591），而 `Config.Language`
 *   与 `LangManager.setEncode` 同源（Config.cs:412-429）。`vbLCMapString` 在 DBCS 区域先按区域码页
 *   编码、`LCMapStringA` 映射、再解回（dotnet/runtime Strings.vb）⇒ 2052/936 下
 *   `ｱｲｳ → ？？？`、`アイウ → ???`、`¥ → ￥`。**ADR-005：照抄这套怪口径，不按日文表修好。**
 *   残留（同一 KDoc 的 TODO(verify)）：362 个 CJK 兼容字的 .NET best-fit 未复刻（`U+3192 → 一`），
 *   且 932/949/950 的 LCMapString 表未采集（这几种语言保留 ASCII 区规则 + 非 ASCII 恒等）。
 * - S8 ESCAPE 补 `\t` / `\n` / `\r` / `\f` 的「反斜杠 + 字母」输出（spec: :5825 `Regex.Escape`）。
 * - S9 STRFORM 结束符改 `FormStrEndWith.EoL`，内部 CodeEE 包成 InvalidFormString 文案
 *   （lang.emuera-zhs.xml:930），其余异常包成 UnexectedFormStringErr（:931）（spec: :5929-5938）。
 * - S10 UNICODEBYTE 空串：原版 `BitConverter.ToInt32(new byte[0],0)` 抛 ArgumentOutOfRangeException
 *   （未捕获，:5735-5737）；本仓返回 0 —— **崩溃不复刻**，保留可诊断路径，偏离登记待组长入 verify-notes。
 * - S11 ENCODETOUNI 指向低位代理：原版 `char.ConvertToUtf32` 抛 ArgumentException（:5865）；
 *   本仓返回该码元 —— **崩溃不复刻**，偏离登记待组长入 verify-notes。
 * - S12 BARSTR 填充字符改读 `Config.BarChar1/BarChar2`（默认 `*` / `.`，`_Replace.csv` 可覆盖；
 *   spec: ExpressionMediator.cs:155-156 + ConfigData.cs:164-165）。
 * - S13 正则 `\d`/`\w`/`\s` 加 `(?U)`（.NET 默认 Unicode 属性；spec: RegexFactory.cs:19）。
 *
 * F6 不属本族：它是 ENUMFILES 第 4 参不是变量时的原版空引用崩溃（spec: FunctionMethodCreator.cs:370），
 * 由 `FileFamilyAuditTest` 的 F6 用例钉住，处置口径与本文件 S4/S10/S11 一致
 * （不复刻崩溃、保留可诊断路径）。
 */
private class SaBase : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("SaBase 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun saCtx(overrides: Map<String, String> = emptyMap()): BuiltinEvalContext {
    val store = VariableStore()
    return BuiltinEvalContext(TestEvalContext(store), store, object : BuiltinHost {
        override val configOverrides: Map<String, String> get() = overrides
    })
}

private fun saI(ctx: BuiltinEvalContext, name: String, vararg a: Any): Long =
    (ctx.callFunction(name, a.map { saV(it) }) as Value.IntV).v

private fun saS(ctx: BuiltinEvalContext, name: String, vararg a: Any): String =
    (ctx.callFunction(name, a.map { saV(it) }) as Value.StrV).v

private fun saV(x: Any): Value = if (x is String) Value.StrV(x) else Value.IntV((x as Number).toLong())

class StringFamilyAuditTest {

    // ---------------------------------------------------------------
    // STRLENS / STRLENSU —— LangManager.cs:15-22 / 24-47
    // ---------------------------------------------------------------

    @Test
    fun `STRLENS 按配置码页字节数 STRLENSU 按 UTF-16 长度`() {
        // spec: LangManager.cs:17-21 —— 整串 ASCII 走长度快路径，否则 GetByteCount
        val ja = saCtx() // 出厂默认 JAPANESE ⇒ 932（ConfigData.cs:103）
        assertEquals(3L, saI(ja, "STRLENS", "abc"))
        assertEquals(4L, saI(ja, "STRLENS", "あい"))
        assertEquals(2L, saI(ja, "STRLENS", "①"))       // U+2460 在 CP932 是 2 字节
        assertEquals(1L, saI(ja, "STRLENS", "ｱ"))       // 半角片假名 1 字节
        assertEquals(2L, saI(ja, "STRLENSU", "あい"))    // spec: :5286 string.Length
        assertEquals(2L, saI(ja, "STRLENSU", "😀"))      // 代理对 = 2 个码元
    }

    @Test
    fun `STRLENS 对代理对按两码元各 1 字节 分歧 S1 已修`() {
        // S1：.NET `Encoding.GetEncoding(932|936).GetByteCount("😀")=2`
        // （每个 UTF-16 码元各自一次回退、各 1 字节）。原实现整串走 JDK 编码器，合法代理对
        // 被折成一个替换字节 ⇒ 1；现在 LangCodec.strlen 逐码元累加，与 .NET 同值。
        val ja = saCtx()
        assertEquals(2L, saI(ja, "STRLENS", "😀"))
        val hans = saCtx(mapOf("DEFAULT ANSI ENCODING" to "CHINESE_HANS"))
        assertEquals(2L, saI(hans, "STRLENS", "😀"))
        assertEquals(6L, saI(hans, "STRLENS", "😀😀あ"))   // 2 + 2 + 2
        // 码页本身的口径（S1 之外）与 .NET 一致：U+2014 在 932 是 1（回退）、在 936 是 2
        assertEquals(1L, saI(ja, "STRLENS", "—"))
        assertEquals(2L, saI(hans, "STRLENS", "—"))
        // S1 派生：FORM 串 %…% 的宽度折算用同一份 strlen（StrForm.cs:72-73 / :74-77）
        // 5 - (2 - 2) = 5 ⇒ 左补 3 个空格；旧口径 5 - (1 - 2) = 6 ⇒ 补 4 个
        assertEquals("   😀", saS(ja, "STRFORM", "%\"😀\",5%"))
    }

    // ---------------------------------------------------------------
    // SUBSTRING —— LangManager.cs:49-95
    // ---------------------------------------------------------------

    @Test
    fun `SUBSTRING 按字节切且长度走越界折回`() {
        val ja = saCtx()
        assertEquals("あ", saS(ja, "SUBSTRING", "あいう", 0, 2))
        assertEquals("い", saS(ja, "SUBSTRING", "あいう", 2, 2))
        assertEquals("い", saS(ja, "SUBSTRING", "あいう", 1, 2))   // 中间切：从整字符边界起
        assertEquals("あいう", saS(ja, "SUBSTRING", "あいう"))      // length 省略 = -1 ⇒ 全长
        assertEquals("ab", saS(ja, "SUBSTRING", "abc", -1, 2))     // start<=0 且 length≠全长
        assertEquals("", saS(ja, "SUBSTRING", "abc", 0, 0))        // length==0 早退（:52）
        assertEquals("", saS(ja, "SUBSTRING", "abc", 3, 1))        // start>=strlen 早退
        assertEquals("", saS(ja, "SUBSTRING", "abc", 9, 1))
        assertEquals("bc", saS(ja, "SUBSTRING", "abc", 1, 99))     // length 超长折回 strlen（:56-59）
    }

    @Test
    fun `SUBSTRING 对代理对只吐高位代理 分歧 S2 已修`() {
        // S2（S1 派生）：len == strlenLang 时 :63-69 直接返回整串。
        // .NET strlenLang=2、length=1 ⇒ 循环只吃高位代理 ⇒ 返回 1 个码元（孤立 \uD83D）；
        // 现在 strlenLang 也是 2，两端口径一致。
        val ja = saCtx()
        val r = saS(ja, "SUBSTRING", "😀", 0, 1)
        assertEquals(1, r.length)
        assertEquals("\uD83D", r)
        // length 省略（-1 ⇒ 折回 strlenLang=2）时整体返回，代理对完整（:63-69）
        assertEquals("😀", saS(ja, "SUBSTRING", "😀"))
        // SUBSTRINGU 不碰码页（:5362-5382 只按 Length 判），同参数下给 1 个码元 —— 与原版一致
        assertEquals(1, saS(ja, "SUBSTRINGU", "😀", 0, 1).length)
    }

    // ---------------------------------------------------------------
    // STRFIND —— FunctionMethodCreator.cs:5411-5429
    // ---------------------------------------------------------------

    @Test
    fun `STRFIND 返回字节下标且起始位置按字节解释`() {
        val ja = saCtx()
        assertEquals(4L, saI(ja, "STRFIND", "あいうえお", "う"))       // :5427 前缀字节数
        assertEquals(4L, saI(ja, "STRFIND", "あいうえお", "う", 2))    // :5418 GetUFTIndex(2) ⇒ 字符 2
        assertEquals(4L, saI(ja, "STRFIND", "あいうえお", "う", 3))
        assertEquals(-1L, saI(ja, "STRFIND", "abc", "z"))             // :5422
        assertEquals(0L, saI(ja, "STRFIND", "abc", ""))               // idx==0 不走字节换算
        assertEquals(-1L, saI(ja, "STRFIND", "abc", "c", 3))          // uStart>=Length ⇒ -1
        assertEquals(2L, saI(ja, "STRFINDU", "あいうえお", "う"))       // unicode 版不下标换算
    }

    @Test
    fun `STRFIND 的前缀字节数随 S1 一起对齐 分歧 S1 派生已修`() {
        // .NET `GetStrlenLang("😀")=2` ⇒ STRFIND("😀a","a")=2；S1 修好后本仓同值。
        val ja = saCtx()
        assertEquals(2L, saI(ja, "STRFIND", "😀a", "a"))
        // 起始位置同样按字节走 uftIndex（:5418），代理对占 2 字节 ⇒ 从 2 起才看得到 'a'
        assertEquals(2L, saI(ja, "STRFIND", "😀a", "a", 1))
        assertEquals(-1L, saI(ja, "STRFIND", "😀a", "a", 3))
    }

    // ---------------------------------------------------------------
    // TOINT / ISNUMERIC —— :5509-5547 / :5774-5811
    // ---------------------------------------------------------------

    @Test
    fun `TOINT 的进制前缀 小数尾与拒绝形态`() {
        val ja = saCtx()
        assertEquals(12L, saI(ja, "TOINT", "12"))
        assertEquals(12L, saI(ja, "TOINT", "+12"))
        assertEquals(-12L, saI(ja, "TOINT", "-12"))
        assertEquals(12L, saI(ja, "TOINT", "12.9"))     // :5532-5544 小数尾只验数字、按整段截断
        assertEquals(12L, saI(ja, "TOINT", "12."))
        assertEquals(16L, saI(ja, "TOINT", "0x10"))     // :5529 ReadInt64 认 0X/0B
        assertEquals(5L, saI(ja, "TOINT", "0b101"))
        assertEquals(100000L, saI(ja, "TOINT", "1e5"))  // :5529 指数分支
        // 拒绝形态（一律 0，spec: :5512-5528）
        for (s in listOf("", "+", "-", ".5", "12 ", " 12", "12a", "--1", "+-1", "abc", "１２")) {
            assertEquals(0L, saI(ja, "TOINT", s), "TOINT(\"$s\") 应为 0")
        }
        // 含非 ASCII 的「长度 < 字节数」判据（:5516）
        assertEquals(0L, saI(ja, "TOINT", "１２"))
    }

    @Test
    fun `TOINT 溢出抛 CodeEE 与 ISNUMERIC 不查溢出`() {
        val ja = saCtx()
        // :5529 → LexicalAnalyzer.cs:153-156 OverflowException ⇒ OoRInt64
        val e = assertFailsWith<CodeEE> { saI(ja, "TOINT", "9223372036854775808") }
        assertTrue(e.message!!.contains("9223372036854775808"), "溢出文案要点名原串：${e.message}")
        assertEquals(Long.MIN_VALUE, saI(ja, "TOINT", "-9223372036854775808"))
        assertEquals(Long.MAX_VALUE, saI(ja, "TOINT", "9223372036854775807"))
        // ISNUMERIC 只做语法判定（:5790 NumericCheck），不读值 ⇒ 溢出串仍算数值
        assertEquals(1L, saI(ja, "ISNUMERIC", "9223372036854775808"))
        assertEquals(1L, saI(ja, "ISNUMERIC", "1e5"))
        assertEquals(1L, saI(ja, "ISNUMERIC", "12."))
        assertEquals(0L, saI(ja, "ISNUMERIC", "12 "))
        assertEquals(0L, saI(ja, "ISNUMERIC", "１２"))
    }

    // ---------------------------------------------------------------
    // TOSTR / MONEYSTR —— :5481-5497 / :3893-3919
    // ---------------------------------------------------------------

    @Test
    fun `TOSTR 无格式与 dotnet 格式串`() {
        val ja = saCtx()
        assertEquals("42", saS(ja, "TOSTR", 42))            // :5486 intValue.ToString()
        assertEquals("00000042", saS(ja, "TOSTR", 42, "D8"))
        assertEquals("FFFFFFFFFFFFFFFF", saS(ja, "TOSTR", -1, "X"))
        assertEquals("3.00", saS(ja, "TOSTR", 3, "F2"))
        assertEquals("1,234", saS(ja, "TOSTR", 1234, "N0"))
        assertEquals("5", saS(ja, "TOSTR", 5, ""))          // 空格式 == ToString()
        val e = assertFailsWith<CodeEE> { saS(ja, "TOSTR", 5, "Q") }
        // 文案分歧（族级）：原版 Lang.Error.InvalidFormat = "{0}函数: 第{1}个参数的格式错误"
        // （lang.emuera-zhs.xml:919），本仓自带措辞。
        assertTrue(e.message!!.contains("TOSTR"), e.message!!)
    }

    @Test
    fun `MONEYSTR 单位位置与格式串`() {
        // 默认单位 "$" / 前置（Builtins.kt:36-39 对应 ConfigData.cs:159-160 的出厂值）
        val ja = saCtx()
        assertEquals("$1234", saS(ja, "MONEYSTR", 1234))
        assertEquals("$1,234", saS(ja, "MONEYSTR", 1234, "N0"))
        // 宿主覆写：label=G、后置（:3898-3918 两个分支都要按 Config.MoneyFirst 选边）
        val store = VariableStore()
        val host = object : BuiltinHost {
            override val moneyLabel: String get() = "G"
            override val moneyLabelFirst: Boolean get() = false
        }
        val alt = BuiltinEvalContext(TestEvalContext(store), store, host)
        assertEquals("1234G", saS(alt, "MONEYSTR", 1234))
        assertEquals("1,234G", saS(alt, "MONEYSTR", 1234, "N0"))
    }

    // ---------------------------------------------------------------
    // REPLACE —— :5653-5687
    // ---------------------------------------------------------------

    @Test
    fun `REPLACE 字面模式与正则分组`() {
        val ja = saCtx()
        // mode=2 走 String.Replace（:5684）：序数、字面、非重叠
        assertEquals("a-b-c", saS(ja, "REPLACE", "aXbXc", "X", "-", 2))
        assertEquals("ab", saS(ja, "REPLACE", "aXb", "X", "", 2))
        // 默认 mode=0 走 Regex.Replace（:5687）
        assertEquals("a[1]b[22]c", saS(ja, "REPLACE", "a1b22c", "([0-9]+)", "[$1]"))
        assertEquals("abc", saS(ja, "REPLACE", "abc", "b", "$0"))     // $0 = 整个匹配
        assertEquals("-a-b-c-", saS(ja, "REPLACE", "abc", "", "-"))   // 空模式：两侧一致
    }

    @Test
    fun `REPLACE 的替换串走 NET 替换语言 分歧 S3 已修`() {
        // S3：.NET 替换语言（:5687 `regex.Replace(str, replacement)`）里
        // `$&`=整个匹配、`$$`=`$`、`$` 反引号=匹配前缀、`$'`=后缀、`$_`=整串、
        // 孤立 `$` 与 `\` 是普通字符、`${名}` 取命名组；原实现直接把替换串交给 Java Matcher，
        // 这些形态一律抛 IllegalArgumentException（连 CodeEE 都不是 ⇒ TRYC 接不住）。
        val ja = saCtx()
        assertEquals("a<1>b<22>c", saS(ja, "REPLACE", "a1b22c", "[0-9]+", "<$&>"))
        assertEquals("a\$c", saS(ja, "REPLACE", "abc", "b", "$"))
        assertEquals("a\\c", saS(ja, "REPLACE", "abc", "b", "\\"))
        assertEquals("a\${x}c", saS(ja, "REPLACE", "abc", "b", "\${x}"))
        assertEquals("a\$c", saS(ja, "REPLACE", "abc", "b", "$$"))
        assertEquals("aac", saS(ja, "REPLACE", "abc", "b", "$`"))
        assertEquals("acc", saS(ja, "REPLACE", "abc", "b", "$'"))
        assertEquals("aabcc", saS(ja, "REPLACE", "abc", "b", "\$_"))
        // 命名组两侧都支持（Java 语法同形）
        assertEquals("ab!c", saS(ja, "REPLACE", "abc", "(?<g>b)", "\${g}!"))
        // `$+` 无捕获组 ⇒ 整个匹配；`$N` 越界 / 数字组号不回退 ⇒ 整段原样
        assertEquals("abc", saS(ja, "REPLACE", "abc", "b", "$+"))
        assertEquals("a\$2c", saS(ja, "REPLACE", "abc", "b", "$2"))
        assertEquals("a\$10c", saS(ja, "REPLACE", "abc", "(b)", "$10"))
        assertEquals("abc", saS(ja, "REPLACE", "abc", "(b)", "$01"))
        assertEquals("a-1b2-2", saS(ja, "REPLACE", "a1b22", "([0-9])([0-9])?", "$2-$1"))
        assertEquals("[]b", saS(ja, "REPLACE", "ab", "(a)(x)?", "[$2]"))
    }

    @Test
    fun `REPLACE 字面模式空模式串与 mode=1 值路径 分歧 S4`() {
        val ja = saCtx()
        // S4：.NET `"abc".Replace("", "-")` 抛 ArgumentException（oldValue 零长，:5684），
        // 原版不捕获 ⇒ 未捕获崩溃。**本仓不复刻崩溃**：保留「逐位插入」的可诊断路径
        // （Kotlin 语义，结果可预期、可回归），偏离登记待组长入 verify-notes。
        assertEquals("-a-b-c-", saS(ja, "REPLACE", "abc", "", "-", 2))
        // mode=1 需要变量身份（:5675-5678）：值路径只能报错，文案为族级差异
        val e = assertFailsWith<CodeEE> { saS(ja, "REPLACE", "abc", "b", "z", 1) }
        assertTrue(e.message!!.contains("REPLACE"), e.message!!)
    }

    // ---------------------------------------------------------------
    // CONVERT —— :5754-5762
    // ---------------------------------------------------------------

    @Test
    fun `CONVERT 非负值与进制校验`() {
        val ja = saCtx()
        assertEquals("ff", saS(ja, "CONVERT", 255, 16))
        assertEquals("1010", saS(ja, "CONVERT", 10, 2))
        assertEquals("17", saS(ja, "CONVERT", 15, 8))
        assertEquals("-15", saS(ja, "CONVERT", -15, 10))   // 10 进制按有符号（Convert.ToString 契约）
        val e = assertFailsWith<CodeEE> { saS(ja, "CONVERT", 10, 7) }
        // 模板 ArgShouldBeSpecificValue（zhs:927）三参 = 函数名/位次/"2, 8, 10, 16"，
        // 原文不含越界值本身（FunctionMethodCreator.cs:5755-5758）
        assertEquals("CONVERT函数: 传入第2个参数必须是2, 8, 10, 16的其中一个", e.message)
    }

    @Test
    fun `CONVERT 负数非 10 进制按无符号补码 分歧 S5 已修`() {
        // S5：.NET `Convert.ToString(long,int)` 对 2/8/16 进制把值当无符号 64 位
        // （-1,16 = 16 个 f；-15,8 = 1777777777777777777761；MinValue,16 = 8000000000000000）。
        val ja = saCtx()
        assertEquals("ffffffffffffffff", saS(ja, "CONVERT", -1, 16))
        assertEquals("1".repeat(64), saS(ja, "CONVERT", -1, 2))
        assertEquals("1777777777777777777761", saS(ja, "CONVERT", -15, 8))
        assertEquals("8000000000000000", saS(ja, "CONVERT", Long.MIN_VALUE, 16))
        // 10 进制两侧都走有符号（Convert.ToString 契约），无符号化不越界
        assertEquals(Long.MAX_VALUE.toString(), saS(ja, "CONVERT", Long.MAX_VALUE, 10))
        assertEquals(Long.MIN_VALUE.toString(), saS(ja, "CONVERT", Long.MIN_VALUE, 10))
    }

    // ---------------------------------------------------------------
    // StrChangeStyle —— :5578-5593
    // ---------------------------------------------------------------

    @Test
    fun `TOUPPER TOLOWER 的全映射（旧 S6 由 fixcore B1 订正）`() {
        val ja = saCtx()
        assertEquals("ABC", saS(ja, "TOUPPER", "aBc"))
        assertEquals("abc", saS(ja, "TOLOWER", "aBc"))
        assertEquals("Ǆ", saS(ja, "TOUPPER", "ǅ"))
        assertEquals("ǆ", saS(ja, "TOLOWER", "Ǆ"))
        // fixcore B1（2026-10-26）：.NET 走 ICU 全映射、可扩长（Unicode SpecialCasing.txt
        // 无条件行）—— 旧 S6「逐字符不扩长」前提不成立：ß→SS、ﬁ→FI。
        assertEquals("SS", saS(ja, "TOUPPER", "ß"))
        assertEquals("FI", saS(ja, "TOUPPER", "ﬁ"))
        // İ → i + U+0307（ToLower 的 CurrentCulture 口径在 ja/zh 机器与 invariant 等价）
        assertEquals("i\u0307", saS(ja, "TOLOWER", "İ"))
        // 希腊文终体 sigma：ς → Σ
        assertEquals("Σ", saS(ja, "TOUPPER", "ς"))
        // 全角拉丁：ａ → Ａ
        assertEquals("Ａ", saS(ja, "TOUPPER", "ａ"))
    }

    @Test
    fun `TOFULL TOHALF 的 ASCII 区间与 2052 片区口径 分歧 S7 已修`() {
        // 出厂默认 JAPANESE ⇒ 码页 932；ASCII 区两条规则与语言无关
        val ja = saCtx()
        assertEquals("ａｂｃ　１２３", saS(ja, "TOFULL", "abc 123"))
        assertEquals("abc 123", saS(ja, "TOHALF", "ａｂｃ　１２３"))
        assertEquals("～", saS(ja, "TOFULL", "~"))      // 0x7E → U+FF5E
        assertEquals("~", saS(ja, "TOHALF", "\uFF5E"))
        // NLS 实测：反斜杠是 ASCII 区唯一的例外（0x5C 不拉全角），0x7F 也不动
        assertEquals("ａ\\ｂ", saS(ja, "TOFULL", "a\\b"))
        assertEquals("ａ\u007Fｂ", saS(ja, "TOFULL", "a\u007Fb"))

        // S7 裁决（ADR-005）：Config.Language 与 LangManager.setEncode **同源**
        // （Config.cs:412-429 同一 switch；ConfigData.cs:103 "Default ANSI encoding"）。
        // 本作语料 `emuera.config:56` = DEFAULT ANSI ENCODING:CHINESE_HANS ⇒ 2052 / 936，
        // 于是 `Strings.StrConv` 走 vbLCMapString 的 DBCS 分支：先按 GBK 编成字节、
        // LCMapStringA 映射、再解回。GBK 装不下的（半角假名、全角片假名、GBK 外符号）
        // 在编码步就落 0x3F ⇒ 输出 `？` / `?`。**照抄，不按日文表修好。**
        val hans = saCtx(mapOf("DEFAULT ANSI ENCODING" to "CHINESE_HANS"))
        assertEquals("？？？", saS(hans, "TOFULL", "ｱｲｳ"))
        assertEquals("???", saS(hans, "TOHALF", "アイウ"))
        // 半角假名的半角→半角（TOHALF）同样是逐字节回退：3 个半角假名 ⇒ 3 个 '?'
        assertEquals("???", saS(hans, "TOHALF", "ｱｲｳ"))
        // 浊点假名的半角形是「基字 + ﾞ」两个字符 ⇒ 两半都落回退字节 ⇒ 2 个 '?'（ガ = ｶﾞ）
        assertEquals("??", saS(hans, "TOHALF", "ガ"))
        assertEquals("??", saS(hans, "TOHALF", "ｶﾞ"))
        // 汉字在 GBK 内 ⇒ 两个方向都原样（TOHALF 只动有半角形的字符）
        assertEquals("一", saS(hans, "TOFULL", "一"))
        assertEquals("一", saS(hans, "TOHALF", "一"))
        // GBK 外的符号 ⇒ ？/ ?（U+0400 Ѐ 不在 GBK；U+2192 → 反而在 GB2312 的 A1FB）
        assertEquals("？", saS(hans, "TOFULL", "\u0400"))
        assertEquals("?", saS(hans, "TOHALF", "\u0400"))
        assertEquals("\u2192", saS(hans, "TOFULL", "\u2192"))
        // Latin-1 段走 GBK 的单字节表（导出实测）：¥(00A5) → ￥(FFE5)、‾(203E) → ￣(FFE3)
        assertEquals("￥", saS(hans, "TOFULL", "¥"))
        assertEquals("￣", saS(hans, "TOFULL", "\u203E"))
        assertEquals("￥", saS(hans, "TOHALF", "¥"))     // 单字节回退解回来还是 ￥
        // LCMAP_HALFWIDTH 的 ASCII 化：弯引号 → 直引号、`￣` → ˉ(02C9)
        assertEquals("''\"\"", saS(hans, "TOHALF", "‘’“”"))
        assertEquals("\u02C9", saS(hans, "TOHALF", "\u203E"))
        // 语言没配成 936 的宿主保留旧口径（932/949/950 的表未采集，见 TODO(verify)）
        assertEquals("ｱｲｳ", saS(ja, "TOFULL", "ｱｲｳ"))
    }

    // ---------------------------------------------------------------
    // ESCAPE / STRCOUNT —— :5823-5826 / :5446-5457
    // ---------------------------------------------------------------

    @Test
    fun `ESCAPE 的转义集与空白 分歧 S8 已修`() {
        val ja = saCtx()
        assertEquals("a\\.b\\*c\\ d", saS(ja, "ESCAPE", "a.b*c d"))
        assertEquals("a\\#b", saS(ja, "ESCAPE", "a#b"))
        assertEquals("a\\[b]b", saS(ja, "ESCAPE", "a[b]b"))   // ']' 不转义（.NET 同）
        assertEquals("a-b", saS(ja, "ESCAPE", "a-b"))         // '-' 不转义（.NET 同）
        // S8：.NET Regex.Escape 把 \t \n \r \f 转成「反斜杠 + 字母」（:5825 实测向量）
        assertEquals("a\\tb", saS(ja, "ESCAPE", "a\tb"))
        assertEquals("a\\nb", saS(ja, "ESCAPE", "a\nb"))
        assertEquals("a\\rb", saS(ja, "ESCAPE", "a\rb"))
        assertEquals("a\\fb", saS(ja, "ESCAPE", "a\u000Cb"))
        // 垂直制表与全角空格**不**转义（.NET 实测原样）
        assertEquals("a\u000Bb", saS(ja, "ESCAPE", "a\u000Bb"))
        assertEquals("a\u3000b", saS(ja, "ESCAPE", "a\u3000b"))
    }

    @Test
    fun `STRCOUNT 的非重叠计数与字符类方言 分歧 S13 已修`() {
        val ja = saCtx()
        assertEquals(2L, saI(ja, "STRCOUNT", "a1b2", "\\d"))
        assertEquals(1L, saI(ja, "STRCOUNT", "aaa", "aa"))          // 非重叠
        assertEquals(1L, saI(ja, "STRCOUNT", " abc ", "\\babc\\b"))
        assertEquals(0L, saI(ja, "STRCOUNT", "a\nb", "a.b"))        // '.' 不吃换行（两侧同）
        // S13：.NET 的 \d \w \s 是 Unicode 属性（RegexFactory.cs:19 的默认 new Regex），
        // 现加 (?U) 与 JDK 的 UNICODE_CHARACTER_CLASS 对齐；
        // 2026-10-22 真机（ICU）上 (?U) inline flag 直接炸 → dotNetRegex 启动探测降级，
        // ICU 的 \d\w\s 默认即 Unicode 属性，下面三条全角断言两端语义一致。
        assertEquals(3L, saI(ja, "STRCOUNT", "１２３", "\\d"))
        assertEquals(3L, saI(ja, "STRCOUNT", "ａｂｃ", "\\w"))
        assertEquals(2L, saI(ja, "STRCOUNT", "　　", "\\s"))
        // 同一个方言要贯穿 REPLACE / REGEXPMATCH（三处共用 dotNetRegex）
        assertEquals("xxx", saS(ja, "REPLACE", "１２３", "\\d", "x"))
        assertEquals(3L, saI(ja, "REGEXPMATCH", "１２３", "\\d"))
        // 真机启动失败回归：开场脚本 STRCOUNT(文本, "依赖") 这类**字面量中文 pattern**
        // 被 dotNetRegex 拼上 "(?U)" 前缀后在 ICU 直接 PatternSyntaxException（vivo V2199A
        // API 34 实测启动失败屏）。钉住：字面量 pattern（含外层 flag）编译+计数不炸。
        assertEquals(3L, saI(ja, "STRCOUNT", "依赖依赖与依赖", "依赖"))
        assertEquals(1L, saI(ja, "STRCOUNT", "文本里的依赖", "依赖"))
    }

    // ---------------------------------------------------------------
    // STRFORM —— :5924-5939
    // ---------------------------------------------------------------

    @Test
    fun `STRFORM 解百分号与花括号记号`() {
        val ja = saCtx()
        assertEquals("abc", saS(ja, "STRFORM", "abc"))
        assertEquals("a,b", saS(ja, "STRFORM", "a,b"))    // EoL 下逗号是普通字符
        assertEquals("a(b", saS(ja, "STRFORM", "a(b"))
        assertEquals("2", saS(ja, "STRFORM", "{1+1}"))
    }

    @Test
    fun `STRFORM 的结束符是 EoL 且异常包成 CodeEE 分歧 S9 已修`() {
        // S9：原版 `AnalyseFormattedString(..., FormStrEndWith.EoL, trim:false)`（:5929），
        // 值里的 '"' 只是普通字符（原实现传 DoubleQuotation ⇒ 遇 '"' 立刻收尾）。
        val ja = saCtx()
        assertEquals("a\"b", saS(ja, "STRFORM", "a\"b"))
        // S9 次条：原版把内部 CodeEE 包成 InvalidFormString（:5931-5934，
        // 文案 lang.emuera-zhs.xml:930），其余异常包成 UnexectedFormStringErr（:5935-5938、:931）。
        // 原实现直接抛词法层 LexError（不是 CodeEE，TRYC 接不住）。
        val e = assertFailsWith<CodeEE> { saS(ja, "STRFORM", "100%") }
        assertTrue(e.message!!.contains("STRFORM"), e.message!!)
        assertTrue(e.message!!.contains("100%"), e.message!!)
        assertTrue(e.message!!.contains("展开错误"), e.message!!)
        // 未闭合的 '{' 走词法层 LexError ⇒ 进同一个 CodeEE 出口（不会漏出 LexError）
        val e2 = assertFailsWith<CodeEE> { saS(ja, "STRFORM", "{") }
        assertTrue(e2.message!!.contains("展开错误"), e2.message!!)
    }

    // ---------------------------------------------------------------
    // UNICODEBYTE / ENCODETOUNI / CHARATU / UNICODE
    // ---------------------------------------------------------------

    @Test
    fun `UNICODEBYTE 取首码点 ENCODETOUNI 取指定码点`() {
        val ja = saCtx()
        assertEquals(97L, saI(ja, "UNICODEBYTE", "abc"))          // spec: :5735-5737 UTF-32 首 4 字节
        assertEquals(128512L, saI(ja, "UNICODEBYTE", "😀ab"))     // U+1F600
        assertEquals(56832L, saI(ja, "ENCODETOUNI", "😀", 1))     // 现状 = 低位代理 0xDE00，见 S11
        assertEquals(97L, saI(ja, "ENCODETOUNI", "abc", 0))
        assertEquals(-1L, saI(ja, "ENCODETOUNI", ""))
        val e = assertFailsWith<CodeEE> { saI(ja, "ENCODETOUNI", "abc", 9) }
        assertTrue(e.message!!.contains("9"), e.message!!)
    }

    @Test
    fun `UNICODEBYTE 空串与孤立代理的两处崩溃不复刻 S10 S11`() {
        // S10：原版 `new byte[0]` 交给 BitConverter.ToInt32 会抛
        // ArgumentOutOfRangeException（:5735-5737，未捕获）。**本仓不复刻崩溃**：空串返回 0，
        // 保留可诊断路径（结果可预期、可回归），偏离登记待组长入 verify-notes。
        val ja = saCtx()
        assertEquals(0L, saI(ja, "UNICODEBYTE", ""))
        // S11：原版 `char.ConvertToUtf32(str, i)`（:5865）在 i 指向低位代理时抛
        // ArgumentException。**本仓不复刻崩溃**：返回该码元（0xDE00），偏离同样登记待入 verify-notes。
        assertEquals(56832L, saI(ja, "ENCODETOUNI", "😀", 1))
        // 越界/负数分支两侧一致（:5857-5863）
        assertFailsWith<CodeEE> { saI(ja, "ENCODETOUNI", "abc", -1) }
    }

    @Test
    fun `UNICODE 控制字符告警与 CHARATU 越界`() {
        val ja = saCtx()
        assertEquals("A", saS(ja, "UNICODE", 65))
        assertEquals("", saS(ja, "UNICODE", 0))        // :5707-5717 告警后返回空串
        assertEquals("", saS(ja, "UNICODE", 127))
        assertEquals("\n", saS(ja, "UNICODE", 10))     // 10/13 是例外
        assertFailsWith<CodeEE> { saS(ja, "UNICODE", 65536) }
        assertEquals("b", saS(ja, "CHARATU", "abc", 1))
        assertEquals("", saS(ja, "CHARATU", "abc", 9)) // :5886-5889 越界空串
        assertEquals("", saS(ja, "CHARATU", "abc", -1))
    }

    // ---------------------------------------------------------------
    // BARSTR —— ExpressionMediator.cs:130-159
    // ---------------------------------------------------------------

    @Test
    fun `BARSTR 的填充字符走 Config BarChar 分歧 S12 已修`() {
        val ja = saCtx()
        // :146-156：fill = var*length/max，夹在 [0,length]，两侧同算式
        assertEquals("[***.......]", saS(ja, "BARSTR", 30, 100, 10))
        assertEquals("[**********]", saS(ja, "BARSTR", 200, 100, 10))
        assertEquals("[..........]", saS(ja, "BARSTR", -5, 100, 10))
        // S12：原版填 Config.BarChar1 / BarChar2（出厂 '*' / '.'，ConfigData.cs:164-165），
        // 可被 _Replace.csv 覆盖（ExpressionMediator.cs:155-156）；现同源读宿主 configOverrides
        val alt = saCtx(mapOf("BAR文字1" to "#", "BAR character 2" to "-"))
        assertEquals("[###-------]", saS(alt, "BARSTR", 30, 100, 10))
        // char.TryParse（ConfigItem.cs:119-126）：Trim 后必须恰好一个码元，否则保留出厂值
        val bad = saCtx(mapOf("BAR文字1" to "##", "BAR文字2" to ""))
        assertEquals("[***.......]", saS(bad, "BARSTR", 30, 100, 10))
        val e = assertFailsWith<CodeEE> { saS(ja, "BARSTR", 1, 0, 10) }
        assertTrue(e.message!!.contains("BAR"), e.message!!)
    }
}
