package com.eratw.port.core.fn

import com.eratw.port.core.expr.LangCodec
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * `TOFLOAT` 的字符串实参：`double.TryParse(str, out result)`（fork-src Creator.Method.cs:6161）
 * 的等价物。
 *
 * 原版**不带** `CultureInfo.InvariantCulture` ⇒ 走 .NET 的当前文化；该重载的默认
 * `NumberStyles` 是 `Float | AllowThousands`（.NET 文档口径：「TryParse(String, Double) uses
 * NumberStyles.Float and NumberStyles.AllowThousands」）。本仓的「当前文化」取
 * [Locale.getDefault]，与存档浮点读写的 `locale` 默认值同口径（`SaveTextFormat.write/read`）。
 *
 * 实测口径（本机 dotnet 8.0.425 探针：切 `CultureInfo.CurrentCulture` 后逐条 `TryParse`）：
 * - 前后空白只认 ASCII 集合（空格、`\t`、`\n`、`\u000B`、`\f`、`\r`）：`" 1.5 "` 成立，
 *   `"1.5\u3000"` 与 `"\u00A01.5"` 失败；
 * - 数字只认 ASCII `0..9`（全角 `１２` 失败）；符号只认一个**前导**正负；
 * - 小数点与分组符按文化互换：de-DE `"1,5"` ⇒ 1.5、`"1.5"` ⇒ **15**（`.` 是千分位，被忽略）、
 *   `"1.234,5"` ⇒ 1234.5；小数点在前后都不许再出现分组符（de-DE `"1,5.5"` 失败），
 *   分组符必须出现在第一个数字之后（de-DE `".5"` 失败）但可以尾随（`"5."` ⇒ 5）；
 * - 指数 `e`/`E` 只在数字之后出现，指数段不含分组符与小数点（`"1e"`、`"1e5,5"` 失败）；
 * - 特值：文化 NaN 符号大小写不敏感（`"nan"`/`"NAN"`/`"NaN"` 都成立），可带前导符号且符号被
 *   丢弃；无穷只认文化正/负无穷符号（常见为 `∞` 与 `-∞`，允许前导 `+`），文本 `"Infinity"`
 *   **不**接受；
 * - 溢出回 ±∞、下溢回 0（`"1e400"` ⇒ +∞、`"1e-400"` ⇒ 0），不是失败路径。
 *
 * 可达性提醒：`TOFLOAT` 在调本函数之前还有 `str.Length < GetStrlenLang(str)` 的长度门
 * （fork-src Creator.Method.cs:6159-6160），CP932/936/949/950 下任何含非 ASCII 的串都被它
 * 先拦成 0.0 ⇒ 文化的 `∞`（多字节）走不到 TryParse，只有 ASCII 的 `NaN` 能到。两个分支都
 * 保留：长度门按配置码页变化，这里不假设码页。
 *
 * 已知残差（TODO(verify)）：文化分组符本身是空白时（fr-FR 的 U+202F），.NET 还额外接受
 * ASCII 空格当分组符（实测 `"1 000"` 成立）——该分支已按 .NET IsWhiteSpace 口径补齐
 * （2026-11-02，见下方分支注释）。ar-SA 一类「负号 = U+061C + `-`」「NaN 符号是本地文字」
 * 的文化只经 [DecimalFormatSymbols] 取符号，未与 .NET 的 `NumberFormatInfo` 逐字段对拍。
 *
 * @param s 待解析串（任意长度；空串返回 null，与 .NET 一致）
 * @param locale 当前文化，默认 [Locale.getDefault]
 * @return 解析成功给该值；失败给 null（调用方落 0.0）
 */
internal fun parseDoubleCurrentCulture(s: String, locale: Locale = Locale.getDefault()): Double? {
    val t = trimAsciiWhite(s)
    if (t.isEmpty()) return null
    val sym = DecimalFormatSymbols.getInstance(locale)
    specialValueOrNull(t, sym)?.let { return it }

    val dec = sym.decimalSeparator
    val grp = sym.groupingSeparator
    val n = t.length
    var i = 0
    val sb = StringBuilder(n)
    // 前导符号（.NET NumberStyles.Float 只带 AllowLeadingSign）；指数段的符号在分支里单独吃
    if (t[0] == '-' || t[0] == sym.minusSign) {
        sb.append('-')
        i++
    } else if (t[0] == '+') {
        i++
    }
    var digits = 0
    var decimalSeen = false
    var exponentSeen = false
    while (i < n) {
        val ch = t[i]
        when {
            ch in '0'..'9' -> {
                sb.append(ch)
                digits++
                i++
            }
            // 分组符：只在整数段、且必须已经出现过数字；重复与尾随都允许（de-DE "1.2.3" ⇒ 123）
            !exponentSeen && !decimalSeen && ch == grp && grp != dec -> {
                if (digits == 0) return null
                i++
            }
            // 分组符本身是空白时（fr-FR 的 U+202F、ru-RU 一类的 U+00A0），.NET 另认 ASCII 空格
            // （实测 "1 000" 成立）。判「是空白」必须用 .NET IsWhiteSpace 口径：
            // JVM 的 isWhitespace 对 U+202F/U+00A0 恒 false（它们属 isSpaceChar），
            // 用它判会让这条分支成死码（2026-11-02 审查修正）。
            !exponentSeen && !decimalSeen && ch == ' ' && (Character.isSpaceChar(grp) || grp == ' ') && grp != dec -> {
                if (digits == 0) return null
                i++
            }
            // 小数点：可前导（de-DE ",5" ⇒ 0.5）可尾随（"1," ⇒ 1），只许一次
            !exponentSeen && ch == dec -> {
                if (decimalSeen) return null
                decimalSeen = true
                sb.append('.')
                i++
            }
            // 指数：只认 e/E，后接可选符号与至少一位 ASCII 数字
            !exponentSeen && digits > 0 && (ch == 'e' || ch == 'E') -> {
                exponentSeen = true
                sb.append('E')
                i++
                if (i < n && (t[i] == '-' || t[i] == sym.minusSign)) {
                    sb.append('-')
                    i++
                } else if (i < n && t[i] == '+') {
                    i++
                }
                if (i >= n || t[i] !in '0'..'9') return null
            }
            else -> return null
        }
    }
    if (digits == 0) return null
    return sb.toString().toDoubleOrNull()
}

/**
 * `TOFLOAT` 的字符串路径整体：`str == null || IsNullOrEmpty` 与
 * `str.Length < LangManager.GetStrlenLang(str)` 两个前置门（fork-src Creator.Method.cs:6156-6160）
 * 之外，其余失败一律落 0.0（:6163），不抛错。
 */
internal fun toFloatOfString(
    s: String,
    codec: LangCodec,
    locale: Locale = Locale.getDefault(),
): Double {
    if (s.isEmpty()) return 0.0
    if (s.length < codec.strlen(s)) return 0.0
    return parseDoubleCurrentCulture(s, locale) ?: 0.0
}

/** .NET 数字解析的空白集合：`IsWhite` 只认 ASCII 六个字符（U+3000 / U+00A0 都不算）。 */
private fun trimAsciiWhite(s: String): String {
    var start = 0
    var end = s.length
    while (start < end && isAsciiWhite(s[start])) start++
    while (end > start && isAsciiWhite(s[end - 1])) end--
    return if (start == 0 && end == s.length) s else s.substring(start, end)
}

private fun isAsciiWhite(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

/**
 * NaN / ±∞ 的符号形态识别。原版是 `double.TryParse` 内置的 `Number.PositiveInfinitySymbol`
 * 分支（.NET Core 3.0 起认 `NaN` 与文化的 `∞`，**不**认文本 `Infinity`），带一个可选前导符号。
 */
private fun specialValueOrNull(t: String, sym: DecimalFormatSymbols): Double? {
    var body = t
    var negative = false
    when (t[0]) {
        '-', sym.minusSign -> {
            negative = true
            body = t.substring(1)
        }
        '+' -> body = t.substring(1)
    }
    if (body.isEmpty()) return null
    if (body.equals(sym.getNaN(), ignoreCase = true)) return Double.NaN
    if (body == sym.infinity) {
        return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
    }
    return null
}
