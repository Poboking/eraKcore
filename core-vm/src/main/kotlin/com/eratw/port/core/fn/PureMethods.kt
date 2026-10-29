package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.IdentifierResolver
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.FormStrEndWith
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.expr.langCodecOf
import com.eratw.port.core.expr.LangCodec
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

// ---------------------------------------------------------------------------
// 取参辅助
// ---------------------------------------------------------------------------

internal fun iv(v: Value): Long = (v as? Value.IntV)?.v ?: throw CodeEE("函数参数类型错误：需要数值")
internal fun sv(v: Value): String = (v as? Value.StrV)?.v ?: throw CodeEE("函数参数类型错误：需要字符串")

/** 可省略参数位（null 对应原版参数列表的空位）。 */
internal fun optInt(args: List<Value?>, i: Int, def: Long): Long =
    if (i < args.size && args[i] != null) iv(args[i]!!) else def

/**
 * `OmitStart` 之前的必填字符串实参。空位在原版装载期报 `ArgCanNotBeNull`
 * （spec: FunctionMethod.cs:198-205；zhs 文案 `emuera-zhs.xml:887`
 * = `{0}函数: 第{1}个参数不能省略`），不是运行期 NPE。
 */
internal fun requiredStrArg(args: List<Value?>, i: Int, fn: String): String =
    if (i < args.size && args[i] != null) sv(args[i]!!) else throw CodeEE("${fn}函数: 第${i + 1}个参数不能省略")

internal fun codeEE(fn: String, msg: String): CodeEE = CodeEE("函数 $fn：$msg")

/**
 * TOSTR 与 MONEYSTR 共用的 .NET 数字格式实现（原版为 long.ToString(format)）。
 *
 * 标准格式支持 C/D/E/F/G/N/P/X（.NET Framework 下 B/R 对 Int64 非法，走抛错）；自定义格式支持 0 与 # 占位、逗号分组与千分缩放、
 * 小数点、百分号与千分号、字面量、单双引号与反斜杠转义、分号分节（正/负/零）。
 * 非法格式抛与原实现一致的 CodeEE 文案。
 *
 * 全程使用 BigInteger/BigDecimal，Long.MIN_VALUE 不经过取负，不会溢出。
 * 文化与 .NET InvariantCulture 对齐：货币符号 ¤、分组 ,、小数点 .、后缀 " %"。
 * TODO(verify)：Emuera 未显式设置 CurrentCulture，ja-JP 机器上的 C/P 符号会不同。
 */
internal fun formatDotNet(value: Long, format: String, fn: String): String {
    if (format.isEmpty()) return value.toString()
    if (looksLikeStandardNumberFormat(format)) return formatStandardNumber(value, format, fn)
    return formatCustomNumber(value, format)
}

/** 标准格式精度上限。.NET 允许到 999999999，这里收紧到一百万以免单条脚本撑爆堆。 */
private const val MAX_STANDARD_PRECISION = 1_000_000

private fun illegalNumberFormat(fn: String, format: String): CodeEE =
    codeEE(fn, "第 2 个参数的数字格式串 \"$format\" 非法")

/**
 * .NET 判定标准格式的口径：首字符是 ASCII 字母且其余字符全是 ASCII 数字（也可以是单个字母）。
 * 因此 "abc"、"Qx"、"C2X"、"D-2" 都走自定义格式，而 "Q9"、"Q" 走标准格式并抛错。
 */
private fun looksLikeStandardNumberFormat(format: String): Boolean {
    val head = format[0]
    if (head !in 'A'..'Z' && head !in 'a'..'z') return false
    for (i in 1 until format.length) if (format[i] !in '0'..'9') return false
    return true
}

private fun formatStandardNumber(value: Long, format: String, fn: String): String {
    val spec = format[0].uppercaseChar()
    // .NET Framework 对 Int64 的合法标准数字格式说明符只有 C D E F G N P X。框架实测
    // ([long]5).ToString("B") / ("R") / ("R5") 一律抛 FormatException「格式说明符无效」，
    // 故 TOSTR / MONEYSTR 的第 2 参为这些时原版走 InvalidFormat 分支抛 CodeEE，不能当合法格式。
    if (spec !in "CDEFGNPX") throw illegalNumberFormat(fn, format)
    val precision = if (format.length == 1) {
        null
    } else {
        val p = format.substring(1).toIntOrNull() ?: throw illegalNumberFormat(fn, format)
        if (p > MAX_STANDARD_PRECISION) throw illegalNumberFormat(fn, format)
        p
    }
    val lower = format[0] in 'a'..'z'
    return when (spec) {
        'C' -> formatCurrencyNumber(value, precision ?: 2)
        'D' -> formatDecimalNumber(value, precision ?: 0)
        'E' -> formatExponentialNumber(value, precision ?: 6, lower)
        'F' -> numberSign(value) + formatFixedMagnitude(value, precision ?: 2, group = false)
        'G' -> formatGeneralNumber(value, precision, lower)
        'N' -> numberSign(value) + formatFixedMagnitude(value, precision ?: 2, group = true)
        'P' -> formatPercentNumber(value, precision ?: 2)
        'X' -> formatHexNumber(value, precision ?: 0, lower)
        else -> throw illegalNumberFormat(fn, format)
    }
}

private fun numberSign(value: Long): String = if (value < 0) "-" else ""

private fun absDigits(value: Long): String = BigInteger.valueOf(value).abs().toString()

private fun formatDecimalNumber(value: Long, precision: Int): String {
    val digits = absDigits(value)
    val body = if (digits.length >= precision) digits else "0".repeat(precision - digits.length) + digits
    return numberSign(value) + body
}

private fun formatHexNumber(value: Long, precision: Int, lower: Boolean): String {
    val raw = java.lang.Long.toHexString(value)
    val padded = if (raw.length >= precision) raw else "0".repeat(precision - raw.length) + raw
    return if (lower) padded else padded.uppercase()
}

private fun groupDigits(digits: String): String {
    if (digits.length <= 3) return digits
    val sb = StringBuilder(digits.length + digits.length / 3)
    for ((i, ch) in digits.withIndex()) {
        if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
        sb.append(ch)
    }
    return sb.toString()
}

private fun formatFixedMagnitude(value: Long, decimals: Int, group: Boolean): String {
    val digits = absDigits(value)
    val intPart = if (group) groupDigits(digits) else digits
    return if (decimals > 0) intPart + "." + "0".repeat(decimals) else intPart
}

private fun formatCurrencyNumber(value: Long, decimals: Int): String {
    val body = formatFixedMagnitude(value, decimals, group = true)
    return if (value < 0) "(¤$body)" else "¤$body"
}

private fun formatPercentNumber(value: Long, decimals: Int): String {
    val digits = BigInteger.valueOf(value).multiply(BigInteger.valueOf(100)).abs().toString()
    val intPart = groupDigits(digits)
    val body = if (decimals > 0) intPart + "." + "0".repeat(decimals) else intPart
    return numberSign(value) + body + " %"
}

/**
 * 标准 E 格式：尾数 1 位整数 + [decimals] 位小数，指数至少 3 位且总带符号。
 * 舍入按十进制有效数字做，远离零；不经过 double，Long 的 19 位数字可原样参与。
 */
private fun formatExponentialNumber(value: Long, decimals: Int, lower: Boolean): String {
    val (mantissa, exponent) = roundToSignificantDigits(absDigits(value), decimals + 1)
    val sb = StringBuilder()
    if (value < 0) sb.append('-')
    sb.append(mantissa[0])
    if (decimals > 0) {
        sb.append('.')
        sb.append(mantissa, 1, mantissa.length)
    }
    sb.append(if (lower) 'e' else 'E').append('+')
    val exp = exponent.toString()
    if (exp.length < 3) sb.append("0".repeat(3 - exp.length))
    sb.append(exp)
    return sb.toString()
}

/**
 * 把十进制数字串舍入到 [sig] 位有效数字（0.5 远离零进位），返回尾数与十进制指数。
 * 尾数固定补齐到 [sig] 位；进位导致长度增加时指数加一。
 */
private fun roundToSignificantDigits(digits: String, sig: Int): Pair<String, Int> {
    if (digits == "0") return "0".padEnd(sig, '0') to 0
    if (digits.length <= sig) return digits.padEnd(sig, '0') to (digits.length - 1)
    var prefix = digits.substring(0, sig)
    var exponent = digits.length - 1
    if (digits[sig] >= '5') {
        prefix = incrementDigits(prefix)
        if (prefix.length > sig) {
            prefix = prefix.substring(0, sig)
            exponent++
        }
    }
    return prefix to exponent
}

private fun incrementDigits(digits: String): String {
    val chars = digits.toCharArray()
    var i = chars.size - 1
    while (i >= 0) {
        if (chars[i] != '9') {
            chars[i] = chars[i] + 1
            return String(chars)
        }
        chars[i] = '0'
        i--
    }
    return "1" + String(chars)
}

/**
 * 标准 G/R 格式。无精度（或精度 0）时输出最短的精确十进制形态；有精度时按有效数字舍入：
 * 指数不小于精度走科学计数法（指数至少 2 位，尾数去尾零），否则原样输出整数位。
 * 字母大小写只影响指数标记 E/e。
 */
private fun formatGeneralNumber(value: Long, precision: Int?, lower: Boolean): String {
    if (precision == null || precision == 0) return value.toString()
    if (value == 0L) return "0"
    val digits = absDigits(value)
    val sb = StringBuilder()
    if (value < 0) sb.append('-')
    if (digits.length <= precision) {
        sb.append(digits)
        return sb.toString()
    }
    var mantissa = digits.substring(0, precision)
    var exponent = digits.length - 1
    if (digits[precision] >= '5') {
        mantissa = incrementDigits(mantissa)
        if (mantissa.length > precision) {
            mantissa = mantissa.substring(0, precision)
            exponent++
        }
    }
    var end = mantissa.length
    while (end > 1 && mantissa[end - 1] == '0') end--
    sb.append(mantissa[0])
    if (end > 1) {
        sb.append('.')
        sb.append(mantissa, 1, end)
    }
    sb.append(if (lower) 'e' else 'E').append('+')
    val exp = exponent.toString()
    if (exp.length < 2) sb.append('0')
    sb.append(exp)
    return sb.toString()
}

// ---------------------------------------------------------------------------
// .NET 自定义数字格式
// ---------------------------------------------------------------------------

private sealed class FmtToken {
    class Digit(val mandatory: Boolean) : FmtToken()
    class Literal(val text: String) : FmtToken()
    object DecimalPoint : FmtToken()
    object CommaSeparator : FmtToken()
    object PercentSign : FmtToken()
    object PermilleSign : FmtToken()
}

/** 按 .NET 口径把格式串拆成最多三段（正、负、零）。第三段之后的内容被忽略。 */
private fun splitFormatSections(format: String): List<String> {
    val sections = ArrayList<String>(3)
    var start = 0
    var i = 0
    while (i < format.length && sections.size < 2) {
        when (format[i]) {
            '\\' -> i += 2
            '\'', '"' -> {
                val quote = format[i]
                i++
                while (i < format.length && format[i] != quote) i++
                if (i < format.length) i++
            }
            ';' -> {
                sections.add(format.substring(start, i))
                start = i + 1
                i++
            }
            else -> i++
        }
    }
    if (sections.size < 2) {
        sections.add(format.substring(start))
        return sections
    }
    var end = start
    while (end < format.length) {
        when (format[end]) {
            '\\' -> end += 2
            '\'', '"' -> {
                val quote = format[end]
                end++
                while (end < format.length && format[end] != quote) end++
                if (end < format.length) end++
            }
            ';' -> break
            else -> end++
        }
    }
    sections.add(format.substring(start, minOf(end, format.length)))
    return sections
}

private fun tokenizeNumberSection(section: String): List<FmtToken> {
    val out = ArrayList<FmtToken>(section.length)
    var i = 0
    while (i < section.length) {
        when (val ch = section[i]) {
            '\\' -> {
                if (i + 1 < section.length) {
                    out.add(FmtToken.Literal(section[i + 1].toString()))
                    i += 2
                } else {
                    i++
                }
            }
            '\'', '"' -> {
                val close = section.indexOf(ch, i + 1)
                val stop = if (close >= 0) close else section.length
                if (stop > i + 1) out.add(FmtToken.Literal(section.substring(i + 1, stop)))
                i = if (close >= 0) close + 1 else section.length
            }
            '0' -> {
                out.add(FmtToken.Digit(mandatory = true))
                i++
            }
            '#' -> {
                out.add(FmtToken.Digit(mandatory = false))
                i++
            }
            '.' -> {
                out.add(FmtToken.DecimalPoint)
                i++
            }
            ',' -> {
                out.add(FmtToken.CommaSeparator)
                i++
            }
            '%' -> {
                out.add(FmtToken.PercentSign)
                i++
            }
            '‰' -> {
                out.add(FmtToken.PermilleSign)
                i++
            }
            else -> {
                out.add(FmtToken.Literal(ch.toString()))
                i++
            }
        }
    }
    return out
}

/**
 * 自定义格式的指数段（"0E+0" / "00E0" / "0.##E+0" 一族）。
 * 成立条件按 .NET 口径：E/e 之前有未转义的数字占位符（0/#），之后是可选 +/- 加至少一个 '0'。
 * 语义权威是 C# 侧 `ToStrMethod` 的 `intValue.ToString(strValue)`（FunctionMethodCreator.cs:5490），
 * 即 .NET `Int64.ToString` 本身；读数来自本机 dotnet 8.0.27 逐例探针（钉在 DotNetFormatTest）。
 * 指数 0 串之后的剩余字符全部按字面量输出（"0E+0x" 12345 → "1E+4x"、"0E05" 12345 → "1E45"），
 * 其中未转义的 %/‰ 仍参与百分/千分缩放（"0E+0%" 7 → "7E+2%"）。
 */
private class ExponentPart(
    /** E/e 之前的尾数格式段（去掉指数段后的剩余部分）。 */
    val mantissaSection: String,
    /** E/e 的大小写，输出沿用（"0e+0" 输出小写 e）。 */
    val upper: Boolean,
    /** 指数前是显式 '+'（符号总输出）还是 '-'/无（仅负指数输出 '-'）。 */
    val explicitPlus: Boolean,
    /** 指数最少位数（'0' 的个数）。 */
    val minDigits: Int,
    /** 指数 0 串之后的字面量段（已按转义/引号规则解出）。 */
    val suffix: String,
) {
    val suffixPercent: Int = suffix.count { it == '%' }
    val suffixPermille: Int = suffix.count { it == '‰' }
}

/** 按 [ExponentPart] 的成立条件扫描格式段，命中则切出尾数段与指数段，未命中给 null。 */
private fun splitExponent(section: String): ExponentPart? {
    var hasPlaceholder = false
    var i = 0
    while (i < section.length) {
        when (val ch = section[i]) {
            '\\' -> i += 2
            '\'', '"' -> {
                val close = section.indexOf(ch, i + 1)
                i = if (close >= 0) close + 1 else section.length
            }
            '0', '#' -> {
                hasPlaceholder = true
                i++
            }
            'E', 'e' -> {
                var j = i + 1
                var explicitPlus = false
                if (j < section.length && (section[j] == '+' || section[j] == '-')) {
                    explicitPlus = section[j] == '+'
                    j++
                }
                val zerosStart = j
                while (j < section.length && section[j] == '0') j++
                if (j > zerosStart && hasPlaceholder) {
                    return ExponentPart(
                        mantissaSection = section.substring(0, i),
                        upper = ch == 'E',
                        explicitPlus = explicitPlus,
                        minDigits = j - zerosStart,
                        suffix = literalizeTail(section.substring(j)),
                    )
                }
                i++
            }
            else -> i++
        }
    }
    return null
}

/** 把指数段之后的剩余文本按字面量解出（反斜杠转义与单/双引号包裹，与 [tokenizeNumberSection] 同口径）。 */
private fun literalizeTail(tail: String): String {
    if (tail.none { it == '\\' || it == '\'' || it == '"' }) return tail
    val out = StringBuilder(tail.length)
    var i = 0
    while (i < tail.length) {
        when (val ch = tail[i]) {
            '\\' -> {
                if (i + 1 < tail.length) {
                    out.append(tail[i + 1])
                    i += 2
                } else {
                    i++
                }
            }
            '\'', '"' -> {
                val close = tail.indexOf(ch, i + 1)
                val stop = if (close >= 0) close else tail.length
                if (stop > i + 1) out.append(tail, i + 1, stop)
                i = if (close >= 0) close + 1 else tail.length
            }
            else -> {
                out.append(ch)
                i++
            }
        }
    }
    return out.toString()
}

/**
 * 自定义格式的单段解析结果。分节选择要先按候选段试算「舍入后是否为零」，
 * 因此把解析、缩放舍入与渲染拆开，避免同一段被反复解析。
 */
private class CustomSection(
    val tokens: List<FmtToken>,
    val pointIndex: Int,
    val intPlaceholders: List<Boolean>,
    val scalingCommas: Int,
    val grouping: Boolean,
    val fracStart: Int,
    val fracPlaceholderCount: Int,
    val percentCount: Int,
    val permilleCount: Int,
    /** 非 null 时本段是自定义指数段，渲染走 [renderExponentialSection]。 */
    val exp: ExponentPart? = null,
) {
    val intTokenCount: Int = if (pointIndex >= 0) pointIndex else tokens.size

    val hasDigitPlaceholder: Boolean = intPlaceholders.isNotEmpty() || fracPlaceholderCount > 0

    /** 按本段的口径做百分/千分放大、千分缩放，再舍入到本段小数位数。 */
    fun scaledRounded(magnitude: BigInteger): BigDecimal {
        var scaled = BigDecimal(magnitude)
        if (percentCount > 0) scaled = scaled.multiply(BigDecimal(100).pow(percentCount))
        if (permilleCount > 0) scaled = scaled.multiply(BigDecimal(1000).pow(permilleCount))
        if (scalingCommas > 0) scaled = scaled.movePointLeft(3 * scalingCommas)
        return scaled.setScale(fracPlaceholderCount, RoundingMode.HALF_UP)
    }

    /** .NET 用本段格式把非零值舍入成零时，会改走零段（或回退到第一段）。 */
    fun roundsToZero(magnitude: BigInteger): Boolean {
        if (magnitude.signum() == 0) return true
        if (!hasDigitPlaceholder) return false
        return scaledRounded(magnitude).signum() == 0
    }
}

private fun parseCustomSection(section: String, exp: ExponentPart?): CustomSection {
    val tokens = tokenizeNumberSection(exp?.mantissaSection ?: section)
    val pointIndex = tokens.indexOfFirst { it === FmtToken.DecimalPoint }
    val intTokenCount = if (pointIndex >= 0) pointIndex else tokens.size
    val fracStart = if (pointIndex >= 0) pointIndex + 1 else tokens.size

    val intPlaceholders = ArrayList<Boolean>()
    var firstDigitToken = -1
    var lastDigitToken = -1
    for (i in 0 until intTokenCount) {
        val token = tokens[i]
        if (token is FmtToken.Digit) {
            if (firstDigitToken < 0) firstDigitToken = i
            lastDigitToken = i
            intPlaceholders.add(token.mandatory)
        }
    }

    var scalingCommas = 0
    var grouping = false
    for (i in 0 until intTokenCount) {
        if (tokens[i] !== FmtToken.CommaSeparator) continue
        val hasDigitBefore = firstDigitToken >= 0 && firstDigitToken < i
        val hasDigitAfter = lastDigitToken > i
        when {
            !hasDigitBefore -> Unit
            !hasDigitAfter -> scalingCommas++
            else -> grouping = true
        }
    }

    var fracPlaceholderCount = 0
    for (i in fracStart until tokens.size) {
        if (tokens[i] is FmtToken.Digit) fracPlaceholderCount++
    }
    var percentCount = 0
    var permilleCount = 0
    for (token in tokens) {
        when (token) {
            FmtToken.PercentSign -> percentCount++
            FmtToken.PermilleSign -> permilleCount++
            else -> Unit
        }
    }
    // 指数段之后的字面量 %/‰ 同样参与缩放（.NET 实测 "0E+0%" 7 → "7E+2%"）。
    if (exp != null) {
        percentCount += exp.suffixPercent
        permilleCount += exp.suffixPermille
    }
    return CustomSection(
        tokens = tokens,
        pointIndex = pointIndex,
        intPlaceholders = intPlaceholders,
        scalingCommas = scalingCommas,
        grouping = grouping,
        fracStart = fracStart,
        fracPlaceholderCount = fracPlaceholderCount,
        percentCount = percentCount,
        permilleCount = permilleCount,
        exp = exp,
    )
}

/**
 * 自定义格式主流程：分节选择、缩放舍入、整数位分配、分组与小数字符渲染。
 *
 * 分节按 .NET 文档与实际行为：负值优先第二段；非零值在某段里舍入成零时改走第三段，
 * 没有第三段则回退到第一段。第二段为空时第一段通用于所有非零值并补自动负号；
 * 只要真正用了第二段，就不再有自动负号。单段格式下负值被缩放或舍入成零时，
 * 自动负号同样省略（格式里完全没有数字占位符时除外）。
 *
 * 整数位分配按 .NET 实测口径：先把数值右对齐到最后一个占位符；位数多于占位符时，
 * 多出的数字全部挂在第一个占位符上。0 强制显示，未拿到数字的 0 位补 '0'。
 * 形如 "0#0#" 的格式在数值为 0 时会补齐到「首个 0 到末个占位符」的宽度。
 * 逗号只在前面已有整数占位符时才有意义：后面没有占位符做千分缩放，否则做分组标记；
 * 开头与小数点后的逗号完全忽略。分组逗号按整段数字每三位从右插入，会落在占位符内部。
 *
 * 指数段（"0E+0" 一族，见 [splitExponent]）成立时整段走 [renderExponentialSection]，
 * 其余照旧。E 前没有占位符的形态（"E1"、"E+2"）不在本函数之列："E1"/"E0" 一类在
 * [looksLikeStandardNumberFormat] 就走标准格式，"E+2"/"E-2" 在 .NET 是字面量，占位符
 * 照常渲染即一致。
 */
private fun formatCustomNumber(value: Long, format: String): String {
    val rawSections = splitFormatSections(format)
    val expParts = rawSections.map { splitExponent(it) }
    val sections = rawSections.mapIndexed { i, raw -> parseCustomSection(raw, expParts[i]) }
    val magnitude = BigInteger.valueOf(value).abs()
    val hasZeroSection = rawSections.size >= 3 && rawSections[2].isNotEmpty()

    var sectionIndex = 0
    var numeric = magnitude
    var autoMinus = false
    if (value < 0) {
        if (rawSections.size >= 2 && rawSections[1].isNotEmpty()) {
            sectionIndex = 1
            if (sections[1].roundsToZero(magnitude)) {
                numeric = BigInteger.ZERO
                sectionIndex = if (hasZeroSection) 2 else 0
            }
        } else {
            autoMinus = true
            if (hasZeroSection && sections[0].roundsToZero(magnitude)) {
                numeric = BigInteger.ZERO
                sectionIndex = 2
                autoMinus = false
            }
        }
    } else if (hasZeroSection && sections[0].roundsToZero(magnitude)) {
        numeric = BigInteger.ZERO
        sectionIndex = 2
    }
    val chosen = sections[sectionIndex]
    val exp = chosen.exp ?: return renderCustomSection(chosen, numeric, autoMinus)
    return renderExponentialSection(chosen, exp, numeric, autoMinus)
}

/**
 * 自定义指数段渲染（"0E+0" / "00E0" / "0.##E+0" 一族）。口径全部来自本机 dotnet 8.0.27
 * 对 `Int64.ToString` 的逐例探针（钉在 DotNetFormatTest）：
 * - 百分/千分与千分逗号缩放先于指数折算（"0E+0%" 7 → "7E+2%"）；
 * - 指数 = 缩放后数值的小数点位置 − 尾数整数占位符数（"00E0" 7 → "70E-1"）；
 * - 尾数按剩余格式段正常渲染，小数按 HALF_UP 舍入（"0.0E+0" 105 → "1.1E+2"）；
 *   舍入进位使整数部分超出占位符数时，尾数左移一位、指数加一（"0E+0" 99 → "1E+2"）；
 * - 零值的指数固定为 0（"00E0" 0 → "00E0"，不是 "00E-1"）；
 * - 显式 '+' 总输出符号；'-' 或无符号只在负指数输出 '-'（"0E0" 12345 → "1E4"、
 *   "00E0" 7 → "70E-1"、"0E-0" 12345 → "1E4"）。
 */
private fun renderExponentialSection(
    section: CustomSection,
    exp: ExponentPart,
    magnitude: BigInteger,
    autoMinus: Boolean,
): String {
    val intDigits = section.intPlaceholders.size
    val fracDigits = section.fracPlaceholderCount
    var scaled = BigDecimal(magnitude)
    if (section.percentCount > 0) scaled = scaled.multiply(BigDecimal(100).pow(section.percentCount))
    if (section.permilleCount > 0) scaled = scaled.multiply(BigDecimal(1000).pow(section.permilleCount))
    if (section.scalingCommas > 0) scaled = scaled.movePointLeft(3 * section.scalingCommas)
    var exp10: Int
    val mantissa: BigDecimal
    if (magnitude.signum() == 0) {
        exp10 = 0
        mantissa = BigDecimal.ZERO.setScale(fracDigits)
    } else {
        val point = scaled.precision() - scaled.scale()
        exp10 = point - intDigits
        var m = scaled.movePointRight(intDigits - point).setScale(fracDigits, RoundingMode.HALF_UP)
        val carryLimit = BigDecimal.ONE.movePointRight(intDigits)
        while (m.compareTo(carryLimit) >= 0) {
            m = m.movePointLeft(1).setScale(fracDigits, RoundingMode.HALF_UP)
            exp10++
        }
        mantissa = m
    }
    val sb = StringBuilder(renderCustomSection(section, magnitude, autoMinus, preScaled = mantissa))
    sb.append(if (exp.upper) 'E' else 'e')
    if (exp10 < 0) {
        sb.append('-')
    } else if (exp.explicitPlus) {
        sb.append('+')
    }
    val digits = (if (exp10 < 0) -exp10 else exp10).toString()
    if (digits.length < exp.minDigits) sb.append("0".repeat(exp.minDigits - digits.length))
    sb.append(digits)
    sb.append(exp.suffix)
    return sb.toString()
}

private fun renderCustomSection(
    section: CustomSection,
    magnitude: BigInteger,
    autoMinus: Boolean,
    preScaled: BigDecimal? = null,
): String {
    val tokens = section.tokens
    val pointIndex = section.pointIndex
    val intTokenCount = section.intTokenCount
    val fracStart = section.fracStart
    val intPlaceholders = section.intPlaceholders

    val rounded = preScaled ?: section.scaledRounded(magnitude)
    val plain = rounded.toPlainString()
    val dot = plain.indexOf('.')
    val intText = if (dot >= 0) plain.substring(0, dot) else plain
    val fracText = if (dot >= 0) plain.substring(dot + 1) else ""

    val firstMandatory = intPlaceholders.indexOfFirst { it }
    val slotTexts = Array(intPlaceholders.size) { "" }
    if (slotTexts.isNotEmpty()) {
        val minWidth = if (firstMandatory >= 0) intPlaceholders.size - firstMandatory else 0
        var digitsForSlots = intText
        if (digitsForSlots.length < minWidth) {
            digitsForSlots = "0".repeat(minWidth - digitsForSlots.length) + digitsForSlots
        }
        if (firstMandatory >= 0 || digitsForSlots != "0") {
            if (digitsForSlots.length >= slotTexts.size) {
                val extra = digitsForSlots.length - slotTexts.size
                slotTexts[0] = digitsForSlots.substring(0, extra + 1)
                for (i in 1 until slotTexts.size) {
                    slotTexts[i] = digitsForSlots[extra + i].toString()
                }
            } else {
                val leading = slotTexts.size - digitsForSlots.length
                for (i in 0 until leading) {
                    slotTexts[i] = if (intPlaceholders[i]) "0" else ""
                }
                for (i in leading until slotTexts.size) {
                    slotTexts[i] = digitsForSlots[i - leading].toString()
                }
            }
        }
    }

    var displayLen = 0
    var fracIndex = 0
    for (i in fracStart until tokens.size) {
        val token = tokens[i]
        if (token is FmtToken.Digit) {
            val digit = if (fracIndex < fracText.length) fracText[fracIndex] else '0'
            if (token.mandatory || digit != '0') displayLen = fracIndex + 1
            fracIndex++
        }
    }

    val totalIntDigits = slotTexts.sumOf { it.length }
    val sb = StringBuilder()
    val showMinus = autoMinus && (rounded.signum() != 0 || !section.hasDigitPlaceholder)
    if (showMinus) sb.append('-')
    var slotIndex = 0
    var digitRank = 0
    fracIndex = 0
    for (i in tokens.indices) {
        when (val token = tokens[i]) {
            is FmtToken.Literal -> sb.append(token.text)
            is FmtToken.Digit -> {
                if (i < intTokenCount) {
                    for (ch in slotTexts[slotIndex]) {
                        if (section.grouping && digitRank > 0 && (totalIntDigits - digitRank) % 3 == 0) sb.append(',')
                        sb.append(ch)
                        digitRank++
                    }
                    slotIndex++
                } else {
                    if (fracIndex < displayLen) sb.append(fracText[fracIndex])
                    fracIndex++
                }
            }
            FmtToken.DecimalPoint -> {
                if (i == pointIndex) {
                    if (slotTexts.isEmpty() && intText != "0") sb.append(intText)
                    if (displayLen > 0) sb.append('.')
                }
            }
            FmtToken.CommaSeparator -> Unit
            FmtToken.PercentSign -> sb.append('%')
            FmtToken.PermilleSign -> sb.append('‰')
        }
    }
    return sb.toString()
}

// ---------------------------------------------------------------------------
// StrConv（TOFULL / TOHALF）—— FunctionMethodCreator.cs:5578-5593
// ---------------------------------------------------------------------------
//
// 原版两条都是 `Microsoft.VisualBasic.Strings.StrConv(str, VbStrConv.Wide/Narrow,
// Config.Language)`（:5590-5591）。三条事实决定这里的写法：
//
// 1. `Config.Language` 与 `LangManager.setEncode` 同源：都是配置项 `ConfigCode.useLanguage`
//    （ConfigData.cs:103 的 "Default ANSI encoding"）—— `Config.cs:412-429` 的四条 case
//    同时写 `Language = 1041/1042/2052/1028` 与 `setEncode(932/949/936/950)`。
//    故本文件的「界面语言」直接复用 [LangCodec]（core/expr 的 `langCodecOf` 通道）。
// 2. StrConv 在 DBCS 区域的实现**不是**逐码位查表：`Strings.vb` 的 `vbLCMapString` 先
//    `enc.GetBytes(s)` 编成**区域 ANSI 字节流**，再 `LCMapStringA(lcid, dwMapFlags, bytes)`，
//    最后 `enc.GetString(...)` 解回来（dotnet/runtime release/8.0 的 Strings.vb，DBCS 分支）。
//    于是「区域码页装不下的字符 → 0x3F → 解回 ？/ ?」，这就是 2052 下
//    `ｱｲｳ → ？？？`、`アイウ → ???` 的来源（ADR-005 复刻，不按日文表「修好」）。
// 3. `Encoding.GetEncoding(936)` 的编码器带 best-fit：`U+3192 → 一`（映射到 GBK 里已有的
//    字节）之类。JDK 的 GBK 编码器没有 best-fit，这部分无法用字符集直接复现，
//    见下方 [STR_CONV_WIDE_EXTRA] 的登记与 KDoc 的残留说明。
//
// 表的数据来源（可复现）：本机 dotnet 8.0.27 探针，`Encoding.RegisterProvider` 后对
// 0x0000-0xFFFF（跳过代理区）逐码位调用 `Strings.StrConv(1 码元串, Wide/Narrow, 2052)`，
// 把与输入不同的码位全量导出；本文的两张 latin 表与 extras 表就是从该导出的全量差分里
// 原样抄下来的（不是推演），对拍读数见 `StringFamilyAuditTest`。

/**
 * TOFULL。spec: FunctionMethodCreator.cs:5590 `Strings.StrConv(strValue, VbStrConv.Wide, Config.Language)`。
 *
 * ASCII 区两条规则与语言无关（半角空格 → 全角空格、`!`..`~` 平移 0xFEE0，**唯独 `\` 不动**）；
 * 其余码位只对 2052（码页 936，本作语料 `emuera.config:56` = `DEFAULT ANSI ENCODING:CHINESE_HANS`）
 * 复刻，其他语言保留「非 ASCII 原样」的旧口径（表没采）。
 */
private fun toFull(s: String, codec: LangCodec): String = buildString(s.length) {
    for (c in s) append(fullWidthOf(c, codec))
}

/** TOHALF。spec: FunctionMethodCreator.cs:5591 `Strings.StrConv(strValue, VbStrConv.Narrow, Config.Language)`。 */
private fun toHalf(s: String, codec: LangCodec): String = buildString(s.length) {
    for (c in s) append(halfWidthOf(c, codec))
}

/** [toFull] 的单码位映射：0x0000-0xFFFF 之外不可能（输入是 UTF-16 码元），不作处理。 */
private fun fullWidthOf(c: Char, codec: LangCodec): String {
    if (c == ' ') return "\u3000"                                     // 0x20 → U+3000
    if (c in '!'..'~' && c != '\\') return (c.code + 0xFEE0).toChar().toString() // 0x5C 是唯一例外
    if (c.code < 0x80) return c.toString()                           // 控制字符原样
    if (codec.codepage != CHINESE_HANS_CODEPAGE) return c.toString() // 只落了 2052 的表
    if (c.code <= 0xFF) return LATIN_WIDE[c.code - 0x80].toChar().toString()
    widthExtra(c.code, STR_CONV_WIDE_EXTRA)?.let { return it }
    // 区域码页装不下 ⇒ 编码器落到 0x3F，LCMAP_FULLWIDTH 再把它拉成全角 '？'（U+FF1F）。
    return if (canEncodeIn(codec, c)) c.toString() else "\uFF1F"
}

/** [toHalf] 的单码位映射。`32` 是 2052 的固定输出：`ガ` 这类有半角形的假名在本区域没有单字节形。 */
private fun halfWidthOf(c: Char, codec: LangCodec): String {
    if (c == '\u3000') return " "                                    // U+3000 → 0x20
    // 全角 ASCII 平移回来；`＼`(U+FF3C) 与 TOFULL 的反斜杠例外对称 —— 导出实测它原样不动。
    if (c.code in 0xFF01..0xFF5E && c.code != 0xFF3C) return (c.code - 0xFEE0).toChar().toString()
    if (c.code < 0x80) return c.toString()
    if (codec.codepage != CHINESE_HANS_CODEPAGE) return c.toString()
    if (c.code <= 0xFF) return LATIN_NARROW[c.code - 0x80].toChar().toString()
    if (c.code == 0x30FE) return "\u30FD?"   // ヾ：LCMAP_HALFWIDTH 给「ヽ」+ 回退 '?'（导出实测）
    // 浊点/半浊点假名：半角形是「基字 + ﾞ/ﾟ」两个字符，两者都落回退字节 ⇒ 两个 '?'。
    if (c.code in DAKUTEN_KANA) return "??"
    widthExtra(c.code, STR_CONV_NARROW_EXTRA)?.let { return it }
    if (!canEncodeIn(codec, c)) return "?"      // 同上：编码器回退字节 0x3F
    if (halfWidthKilled(c.code)) return "?"     // 有半角形、但本区域没有单字节形（片假名一族）
    return c.toString()
}

/** 本作语料配置对应的码页 —— `Config.cs:422-425` CHINESE_HANS ⇒ 2052 / 936。 */
private const val CHINESE_HANS_CODEPAGE = 936

/**
 * extras 表的查表（成对存放：码位, 目标码位）。命中给单码位字符串，未命中 null。
 *
 * 表里没有多码位输出：本模型里唯一的多码位形态（浊点假名的 `??`、`ヾ` 的 `ヾ?`）
 * 都在 [halfWidthOf] 里按结构单独处理，不进表。
 */
private fun widthExtra(code: Int, ints: IntArray): String? {
    var i = 0
    while (i < ints.size) {
        if (ints[i] == code) return ints[i + 1].toChar().toString()
        i += 2
    }
    return null
}

/**
 * 2052 下 0x80-0xFF 的整表（索引 = 码位 - 0x80）。导出实测：这一段**不是**恒等，
 * 也不是统一落 `？` —— 例如 0xA4 在 GBK 里占 2 字节（A1E8）故原样，0xA2 → `￠`(U+FFE0)，
 * 0xA5 → `￥`(U+FFE5)，0xC0 → `à`(U+00E0)（GBK 只有小写重音字母，编码器 best-fit）。
 */
private val LATIN_WIDE = intArrayOf(
    0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F,
    0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F,
    0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F,
    0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F,
    0xFF1F, 0xFF1F, 0xFFE0, 0xFFE1, 0x00A4, 0xFFE5, 0xFF5C, 0x00A7,
    0x00A8, 0xFF1F, 0xFF41, 0xFF1F, 0xFF1F, 0xFF0D, 0xFF1F, 0x02C9,
    0x00B0, 0x00B1, 0xFF12, 0xFF13, 0x2032, 0x03BC, 0xFF1F, 0x00B7,
    0xFF1F, 0xFF11, 0xFF4F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F,
    0x00E0, 0x00E1, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F,
    0x00E8, 0x00E9, 0x00EA, 0xFF1F, 0x00EC, 0x00ED, 0xFF1F, 0xFF1F,
    0xFF24, 0xFF1F, 0x00F2, 0x00F3, 0xFF1F, 0xFF1F, 0xFF1F, 0x00D7,
    0xFF1F, 0x00F9, 0x00FA, 0xFF1F, 0x00FC, 0xFF39, 0xFF34, 0xFF1F,
    0x00E0, 0x00E1, 0xFF41, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F, 0xFF1F,
    0x00E8, 0x00E9, 0x00EA, 0xFF1F, 0x00EC, 0x00ED, 0xFF1F, 0xFF1F,
    0xFF45, 0xFF1F, 0x00F2, 0x00F3, 0xFF1F, 0xFF1F, 0xFF1F, 0x00F7,
    0xFF1F, 0x00F9, 0x00FA, 0xFF1F, 0x00FC, 0xFF59, 0xFF54, 0xFF1F,
)

/** 2052 下 0x80-0xFF 的 TOHALF 整表（索引 = 码位 - 0x80）；与 [LATIN_WIDE] 同一次导出。 */
private val LATIN_NARROW = intArrayOf(
    0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F,
    0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F,
    0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F,
    0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F,
    0x003F, 0x003F, 0xFFE0, 0xFFE1, 0x00A4, 0xFFE5, 0x007C, 0x00A7,
    0x00A8, 0x003F, 0x0061, 0x003F, 0x003F, 0x002D, 0x003F, 0x02C9,
    0x00B0, 0x00B1, 0x0032, 0x0033, 0x2032, 0x03BC, 0x003F, 0x00B7,
    0x003F, 0x0031, 0x006F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F,
    0x00E0, 0x00E1, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F,
    0x00E8, 0x00E9, 0x00EA, 0x003F, 0x00EC, 0x00ED, 0x003F, 0x003F,
    0x0044, 0x003F, 0x00F2, 0x00F3, 0x003F, 0x003F, 0x003F, 0x00D7,
    0x003F, 0x00F9, 0x00FA, 0x003F, 0x00FC, 0x0059, 0x0054, 0x003F,
    0x00E0, 0x00E1, 0x0061, 0x003F, 0x003F, 0x003F, 0x003F, 0x003F,
    0x00E8, 0x00E9, 0x00EA, 0x003F, 0x00EC, 0x00ED, 0x003F, 0x003F,
    0x0065, 0x003F, 0x00F2, 0x00F3, 0x003F, 0x003F, 0x003F, 0x00F7,
    0x003F, 0x00F9, 0x00FA, 0x003F, 0x00FC, 0x0079, 0x0074, 0x003F,
)

/**
 * TOFULL 在 2052 下「码页装得下、输出却不是原码位」的例外（成对：码位, 目标）。
 * 两条来源：① GBK 编码器的 best-fit（`U+0100 → ā`：GBK 只有小写重音字母）；
 * ② `LCMAP_FULLWIDTH` 把 0x3F 以外的回退字符拉成别的形（`U+309B → '?'` 保持半角问号）。
 */
private val STR_CONV_WIDE_EXTRA = intArrayOf(
    0x0100, 0x0101, 0x0112, 0x0113, 0x011A, 0x011B, 0x012A, 0x012B,
    0x014C, 0x014D, 0x016A, 0x016B, 0x01C1, 0x2225, 0x01CD, 0x01CE,
    0x01CF, 0x01D0, 0x01D1, 0x01D2, 0x01D3, 0x01D4, 0x01D5, 0x01D6,
    0x01D7, 0x01D8, 0x01D9, 0x01DA, 0x01DB, 0x01DC, 0x203E, 0xFFE3,
    0x2218, 0x00B0, 0x223C, 0xFF5E, 0x309B, 0x003F, 0x309C, 0x003F,
    0x2295, 0x2295, 0x2641, 0xFF1F, 0xE76C, 0xE76C, 0xF8F5, 0xF8F5,
)

/** TOHALF 的同类例外。含 `LCMAP_HALFWIDTH` 的 ASCII 化（`‘’“”`、`～`、`￣`、`￤`）。 */
private val STR_CONV_NARROW_EXTRA = intArrayOf(
    0x0100, 0x0101, 0x0112, 0x0113, 0x011A, 0x011B, 0x012A, 0x012B,
    0x014C, 0x014D, 0x016A, 0x016B, 0x01C1, 0x2225, 0x01CD, 0x01CE,
    0x01CF, 0x01D0, 0x01D1, 0x01D2, 0x01D3, 0x01D4, 0x01D5, 0x01D6,
    0x01D7, 0x01D8, 0x01D9, 0x01DA, 0x01DB, 0x01DC, 0x2018, 0x0027,
    0x2019, 0x0027, 0x201C, 0x0022, 0x201D, 0x0022, 0x203E, 0x02C9,
    0x2218, 0x00B0, 0x223C, 0x007E, 0xFFE3, 0x02C9, 0xFFE4, 0x007C,
    0x2295, 0x2295, 0x2641, 0x003F, 0xE76C, 0xE76C, 0xF8F5, 0xF8F5,
)

/**
 * TOHALF 在 2052 下「码页装得下、但半角形在本区域没有单字节形」的码位 —— 输出 `?`。
 *
 * 导出实测的 90 个码位正好是这 9 段（片假名 + 日文标点 + `￢`）：
 * `LCMAP_HALFWIDTH` 认得它们的半角形，但半角假名在 GBK 里占 2 字节（0xA5 行），
 * 不是「narrow」要的单字节，于是落到默认字符 `?`。
 */
private fun halfWidthKilled(code: Int): Boolean =
    code in 0x3001..0x3002 || code in 0x300C..0x300D || code in 0x309B..0x309C ||
        code in 0x30A1..0x30ED || code == 0x30EF || code in 0x30F2..0x30F4 ||
        code == 0x30FC || code == 0xFFE2

/**
 * 浊点（゛）/半浊点（゜）假名：`LCMAP_HALFWIDTH` 的半角形是「基字 + ﾞ/ﾟ」两个字符，
 * 而 2052 下这两半都装不进 GBK 的单字节 ⇒ 输出两个 `?`（导出实测的 27 个码位）。
 */
private val DAKUTEN_KANA = intArrayOf(
    0x30AC, 0x30AE, 0x30B0, 0x30B2, 0x30B4, 0x30B6, 0x30B8, 0x30BA,
    0x30BC, 0x30BE, 0x30C0, 0x30C2, 0x30C5, 0x30C7, 0x30C9, 0x30D0,
    0x30D1, 0x30D3, 0x30D4, 0x30D6, 0x30D7, 0x30D9, 0x30DA, 0x30DC,
    0x30DD, 0x30F4,
)

/**
 * 单码位能否用 [codec] 的码页编码（.NET 侧 = `enc.GetBytes` 不落回退字符）。
 *
 * `canEncode` 走 JDK 的严格编码器，与 .NET CP936 的差别只剩 best-fit（见类注释第 3 条）。
 * 全 BMP 对拍实测的其余两类差异（已用 [STR_CONV_WIDE_EXTRA] / [STR_CONV_NARROW_EXTRA] 钉死）：
 * - CJK 兼容字 362 个/方向（`U+3192 → 一` 这类 best-fit）本仓按「装不下 ⇒ ？/ ?」处理，
 *   语料未出现该形态 —— TODO(verify)；
 * - JDK GBK 与 .NET CP936 编码集不同的 4 个码位：`U+2295`/`U+E76C`/`U+F8F5` 在 .NET 可编码、
 *   在 JDK GBK 不可；`U+2641` 相反。
 */
private fun canEncodeIn(codec: LangCodec, c: Char): Boolean =
    runCatching { codec.charset.newEncoder().canEncode(c) }.getOrDefault(false)

// ---------------------------------------------------------------------------
// REPLACE（spec: FunctionMethodCreator.cs:5611-5690 ReplaceMethod）
// ---------------------------------------------------------------------------

private fun badReplaceArray(): CodeEE =
    // spec: FunctionMethodCreator.cs:5666-5670 —— Lang.Error.ArgIsNotNDStrArray
    // （zhs emuera-zhs.xml:899 = `{0}函数: 第{1}个参数不是字符串型{2}维数组变量`，实参 Name/3/1）
    CodeEE("REPLACE函数: 第3个参数不是字符串型1维数组变量")

/**
 * 原版 `RegexFactory.GetRegex` 的等价物：`new Regex(pattern, RegexOptions.Compiled)`
 * （spec: RegexFactory.cs:19 —— 默认选项，没有 CultureInvariant / ECMAScript）。
 *
 * `(?U)` = JDK 的 UNICODE_CHARACTER_CLASS，把 `\d` / `\w` / `\s` / `\b` 换成 Unicode 版，
 * 这正是 .NET 预定义字符类的默认口径（`\d` 命中全角数字、`\w` 命中全角字母、`\s` 命中
 * 全角空格）；不加时 JDK 只吃 ASCII（REPLACE / STRCOUNT / REGEXPMATCH 三处都靠这一层）。
 *
 * **平台差异（2026-10-22 真机 vivo V2199A / API 34 实测）**：Android 的 `java.util.regex`
 * 是 ICU 包装，`(?U)` inline flag 直接 `PatternSyntaxException`（"Syntax error in regexp
 * pattern near index 3"），title → 开始游戏的开场脚本一调 STRCOUNT 就启动失败；
 * 且 `Pattern.UNICODE_CHARACTER_CLASS` 字段 API 24 才存在（minSdk 21），不能改用常量。
 * 对策 = 进程内探测一次 inline flag 可用性：HotSpot 保留 flag；ICU 上剥掉——ICU 的
 * `\d` / `\w` / `\s` 默认就是 Unicode 属性，语义同样对齐 .NET，两端殊途同归。
 *
 * TODO(verify)：`\w` 的集合仍有残差 —— .NET 是 `[\p{L}\p{Mn}\p{Nd}\p{Pc}]`，JDK 的 Unicode `\w`
 * 还含 Mc/Me 与 Join_Control（U+200C/U+200D）。卡点 = 无 .NET 运行时逐码位对拍，语料未出现该形态。
 */
private val unicodeInlineFlagSupported: Boolean =
    runCatching { Regex("(?U)x") }.isSuccess

/**
 * 原版 `RegexFactory.GetRegex` 的**唯一**平台安全构造点（internal：VarMethods 的
 * FINDELEMENT/FINDLASTELEMENT 也走这里）。
 *
 * 2026-10-23 真机红补充：`FINDELEMENT` 一度自己 `Regex("(?U)$pattern")`，ICU 上 inline
 * flag 直接抛 `Syntax error in regexp pattern near index 3` —— 开场脚本 GET_TALENTNAME:369
 * 一调就整局启动失败。把 .NET 正则语义带进移植侧的地方一律经由本函数。
 */
internal fun dotNetRegex(pattern: String): Regex =
    if (unicodeInlineFlagSupported) Regex("(?U)$pattern") else Regex(pattern)

/**
 * .NET `Regex.Replace(input, replacement)` 的**替换语言**（substitution）展开。
 * spec: FunctionMethodCreator.cs:5687 —— 原版把第 3 个参数直接交给 `Regex.Replace`，
 * 于是 `$&` / `$$` / `$` 反引号 / `$'` / `$_` / `$+` / `${名}` / `$N` 全是语法；
 * JDK 的 `Matcher` 方言不同（`$&`、`$`` 等直接抛 IllegalArgumentException），故自己展开。
 *
 * 语法（本机 dotnet 8.0.421 net8.0 实测，20 条向量见 `StringPatchSpecTest`）：
 * - `$$` → `$`；`$&` → 整个匹配；`$0` / `${0}` → 整个匹配
 * - `$` + 反引号 → 匹配**左侧**的整段原文；`$'` → 右侧；`$_` → 整段原文
 * - `$+` → 最后一个成功捕获的组（一个捕获组都没有时 .NET 回落到整个匹配）
 * - `$N` / `$NN` / `${N}` → 第 N 组；数字组号**整段解析、不逐位回退**（`$10` 只有 1 组 ⇒ 原样）
 * - `${名}` → 命名组；名字不存在、`}` 未闭合、`${}` 空名 ⇒ 整段原样输出
 * - `\` 没有转义语义；`$` 后跟其它字符 ⇒ `$` 原样
 */
private fun dotNetExpand(input: String, m: MatchResult, replacement: String): String = buildString {
    val groups = m.groups
    var i = 0
    while (i < replacement.length) {
        val c = replacement[i]
        if (c != '$') {
            append(c)
            i++
            continue
        }
        if (i + 1 >= replacement.length) {
            append('$')
            i++
            continue
        }
        when (val n = replacement[i + 1]) {
            '$' -> { append('$'); i += 2 }
            '&' -> { append(m.value); i += 2 }
            '`' -> { append(input.substring(0, m.range.first)); i += 2 }
            '\'' -> { append(input.substring(m.range.last + 1)); i += 2 }
            '_' -> { append(input); i += 2 }
            '+' -> {
                var last = m.value
                for (g in 1 until groups.size) {
                    val gr = groups[g] ?: continue
                    if (gr.range != IntRange.EMPTY) last = gr.value
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

/** 按 .NET 替换语言展开后替换（每个匹配一次展开，展开结果**不再二次展开**）。spec: :5687 */
private fun replaceExpanded(regex: Regex, s: String, replacement: String): String =
    regex.replace(s) { m -> dotNetExpand(s, m, replacement) }

private fun compileReplaceRegex(from: String): Regex =
    try {
        dotNetRegex(from)
    } catch (e: Exception) {
        // spec: FunctionMethodCreator.cs:5653-5657 —— Lang.Error.InvalidRegexArg
        // （zhs emuera-zhs.xml:297 = `{0}函数: 第{1}参数无法解析为正则表达式: {2}`）
        throw CodeEE("REPLACE函数: 第2参数无法解析为正则表达式: ${e.message}")
    }

/** 值路径：3 参正则替换；mode=2 字面替换。mode=1 需要数组身份，只能走引用路径。 */
private fun replaceValue(a: List<Value?>): String {
    val s = requiredStrArg(a, 0, "REPLACE")
    val from = requiredStrArg(a, 1, "REPLACE")
    val to = requiredStrArg(a, 2, "REPLACE")
    return when (optInt(a, 3, 0).toInt()) {
        // spec: :5684 —— String.Replace(oldValue, newValue)：序数、字面、非重叠。
        // 空 oldValue 在原版抛 ArgumentException（未捕获）；本移植保留「逐位插入」的可诊断路径，
        // 偏离已登记（见 StringFamilyAuditTest 的 S4 用例 KDoc）。
        2 -> s.replace(from, to)
        1 -> throw badReplaceArray()
        // spec: :5687 —— Regex.Replace(str, replacement)，替换串走 .NET 替换语言
        else -> replaceExpanded(compileReplaceRegex(from), s, to)
    }
}

/** 引用路径：mode=1 时按匹配序号从 1 维字符串数组取替换串，越界补空串。 */
private fun replaceRef(c: BuiltinContext, e: List<Expr?>): String {
    // 必填位空缺对应原版装载期 ArgCanNotBeNull（spec: FunctionMethod.cs:198-205；
    // zhs emuera-zhs.xml:887 = `{0}函数: 第{1}个参数不能省略`，见 [requiredStrArg]）。
    fun arg(i: Int): String =
        sv(e[i]?.toValue(c) ?: throw CodeEE("REPLACE函数: 第${i + 1}个参数不能省略"))
    val s = arg(0)
    val from = arg(1)
    val mode = if (e.size >= 4) e[3]?.getIntValue(c)?.toInt() ?: 0 else 0
    if (mode == 2) return s.replace(from, arg(2))
    val regex = compileReplaceRegex(from)
    // spec: :5687 —— 3 参正则形态同样走 .NET 替换语言
    if (mode != 1) return replaceExpanded(regex, s, arg(2))
    val ref = e[2] as? VarRef ?: throw badReplaceArray()
    val info: VarInfo? = c.store.resolveVarInfo(ref.name)
    if (info == null || info.isCharacterData || info.dimension != 1 ||
        info.operandType != OperandType.STR || info.isConst || c.store.isConst(ref.name)
    ) {
        throw badReplaceArray()
    }
    val items = List(c.store.getLength(ref.name)) { c.store.readStr(ref.name, longArrayOf(it.toLong())) }
    var idx = 0
    return regex.replace(s) { if (idx < items.size) items[idx++] else "" }
}

// ---------------------------------------------------------------------------
// REGEXPMATCH（spec: FunctionMethodCreator.cs:984-1065 RegexpMatchMethod）
// ---------------------------------------------------------------------------

private fun badRegexMatchArray(): CodeEE =
    CodeEE("函数 REGEXPMATCH 的第 4 个参数必须是 1 维字符串数组变量")

/** 组数含 0 组（对应 .NET Regex.GetGroupNumbers().Length）。 */
private fun regexGroupCount(regex: Regex): Int = regex.toPattern().matcher("").groupCount() + 1

private fun regexMatches(s: String, pattern: String): Pair<Regex, List<MatchResult>> {
    val regex = try {
        dotNetRegex(pattern)
    } catch (e: Exception) {
        // spec: FunctionMethodCreator.cs:1002-1005 —— InvalidRegexArg（zhs emuera-zhs.xml:297）
        throw CodeEE("REGEXPMATCH函数: 第2参数无法解析为正则表达式: ${e.message}")
    }
    return regex to regex.findAll(s).toList()
}

/** 组值按“匹配序 × 组序（含 0 组）”平铺；超出目标数组长度即停（spec: :1015-1031 Output）。 */
private fun writeMatchGroups(
    c: BuiltinContext,
    regex: Regex,
    matches: List<MatchResult>,
    dest: String,
    cap: Int,
) {
    var n = 0
    for (m in matches) {
        for (g in 0 until regexGroupCount(regex)) {
            if (n >= cap) return
            c.store.writeStr(dest, longArrayOf(n.toLong()), m.groups[g]?.value ?: "")
            n++
        }
    }
}

/** 值路径：2 参只计数；3 参非 0 时写 RESULT:1（组数）与 RESULTS（spec: :1045-1054）。 */
private fun regexMatchValue(c: BuiltinContext, a: List<Value?>): Long {
    val (regex, matches) = regexMatches(
        requiredStrArg(a, 0, "REGEXPMATCH"),
        requiredStrArg(a, 1, "REGEXPMATCH"),
    )
    if (a.size >= 4) throw CodeEE("函数 REGEXPMATCH 的第 3/4 个参数必须是变量引用（需要引用参数路径）")
    if (a.size >= 3 && optInt(a, 2, 0) != 0L) {
        c.store.writeInt("RESULT", longArrayOf(1), regexGroupCount(regex).toLong())
        if (matches.isNotEmpty()) {
            writeMatchGroups(c, regex, matches, "RESULTS", c.store.getLength("RESULTS"))
        }
    }
    return matches.size.toLong()
}

/**
 * 引用路径（表达式层的首选入口：`FuncCall` 一律走 callFunctionRef，而该入口**优先** evalRef，
 * spec: Builtins.kt:737-747 ⇒ 值路径的 [regexMatchValue] 不会被表达式调用命中，3 参分支只能
 * 落在这里）：
 * - 3 实参：第 3 位是 Int 开关，非 0 时写 `RESULT[1]`（组数，含 0 组）与 `RESULTS`
 *   （spec: FunctionMethodCreator.cs:1047-1054 `arguments.Count == 3` 分支）；
 * - 4 实参：第 3 位 RefInt 收组数、第 4 位 RefString1D 收组值（spec: :1055-1062）；
 * - 2 实参：只计数，不碰任何结果容器。
 */
private fun regexMatchRef(c: BuiltinContext, e: List<Expr?>): Long {
    // 必填位空缺 → 装载期 ArgCanNotBeNull 文案（spec: FunctionMethod.cs:195-205，见 [replaceRef]）。
    fun arg(i: Int): String =
        sv(e[i]?.toValue(c) ?: throw CodeEE("REGEXPMATCH函数: 第${i + 1}个参数不能省略"))
    val (regex, matches) = regexMatches(arg(0), arg(1))
    if (e.size == 3) {
        // spec: :1047-1054 —— 第 3 参非 0 时 RESULT_ARRAY[1] = 组数（含 0 组），RESULTS 接组值
        if ((e[2]?.getIntValue(c) ?: 0L) != 0L) {
            c.store.writeInt("RESULT", longArrayOf(1), regexGroupCount(regex).toLong())
            if (matches.isNotEmpty()) {
                writeMatchGroups(c, regex, matches, "RESULTS", c.store.getLength("RESULTS"))
            }
        }
    } else if (e.size == 4) {
        val countRef = e[2] as? VarRef
            ?: throw CodeEE("函数 REGEXPMATCH 的第 3 个参数必须是整数变量")
        val idx = LongArray(countRef.indices.size) { countRef.indices[it].getIntValue(c) }
        c.store.writeInt(countRef.name, idx, regexGroupCount(regex).toLong())
        val arrRef = e[3] as? VarRef ?: throw badRegexMatchArray()
        val info: VarInfo? = c.store.resolveVarInfo(arrRef.name)
        if (info == null || info.isCharacterData || info.dimension != 1 ||
            info.operandType != OperandType.STR || info.isConst || c.store.isConst(arrRef.name)
        ) {
            throw badRegexMatchArray()
        }
        if (matches.isNotEmpty()) {
            writeMatchGroups(c, regex, matches, arrRef.name, c.store.getLength(arrRef.name))
        }
    } else if (e.size == 3 && (e[2]?.getIntValue(c) ?: 0L) != 0L) {
        // 3 参开关非 0：与值路径 [regexMatchValue] 同一副作用的同一份口径（spec: :1047-1054）
        c.store.writeInt("RESULT", longArrayOf(1), regexGroupCount(regex).toLong())
        if (matches.isNotEmpty()) {
            writeMatchGroups(c, regex, matches, "RESULTS", c.store.getLength("RESULTS"))
        }
    }
    return matches.size.toLong()
}

// ---------------------------------------------------------------------------
// 组内比较族的同型实参取值（ArgType.VariadicSameAsFirst）
// ---------------------------------------------------------------------------

/**
 * 取第 [i] 个实参（0 基）并按**首参类型**校验同型性；报错文案用 1 基序号。
 *
 * 原版 `ArgType.VariadicSameAsFirst`（FunctionMethodCreator.cs:4521 / :4569 / :4618 三处同形）
 * 在装载期要求「第 j 个实参的操作数类型 == 第 0 个」（FunctionMethod.cs:207 的 SameAsFirst 分支），
 * 不同型时按**首参**类型挑文案（FunctionMethod.cs:256-257）：
 * 首参 STR → `Lang.Error.ArgIsNotStr` = `{0}函数: 第{1}个参数不是字符串`（Lang.cs:3746）、
 * 首参 INT → `Lang.Error.ArgIsNotInt` = `{0}函数: 第{1}个参数不是整数`（Lang.cs:3750）；
 * 本移植取 zh 译文（lang 目录的 emuera-zhs.xml:890-891），并把原版的**装载期**校验
 * 下移到求值期（与文件顶部 iv/sv 同一口径）。
 *
 * 空位实参（null）原版在装载期直接跳过（FunctionMethod.cs:198-205：OmitStart = -1 时既不报
 * ArgCanNotBeNull 也不进比较），随后 `GetIntValue`/`GetStrValue` 对 null 解引用崩溃
 * （FunctionMethodCreator.cs:4536 / :4547 等）；这里以同样的 NPE 复刻（ADR-005）。
 */
private fun sameAsFirstInt(fn: String, a: List<Value?>, i: Int): Long =
    (a[i]!! as? Value.IntV)?.v ?: throw CodeEE("${fn}函数: 第${i + 1}个参数不是整数")

/** 字符串侧的 [sameAsFirstInt]（同一条 SameAsFirst 判定，文案换 ArgIsNotStr）。 */
private fun sameAsFirstStr(fn: String, a: List<Value?>, i: Int): String =
    (a[i]!! as? Value.StrV)?.v ?: throw CodeEE("${fn}函数: 第${i + 1}个参数不是字符串")

/** [ALLSAMES] 值路径：实参已由调用方求值，短路只影响比较次数，不再影响求值。 */
private fun allsamesValue(a: List<Value?>): Long {
    val same = if (a[0] is Value.IntV) {
        val v = sameAsFirstInt("ALLSAMES", a, 0)
        (1 until a.size).all { sameAsFirstInt("ALLSAMES", a, it) == v }
    } else {
        val v = sameAsFirstStr("ALLSAMES", a, 0)
        (1 until a.size).all { sameAsFirstStr("ALLSAMES", a, it) == v }
    }
    return if (same) 1L else 0L
}

/** [ALLSAMES] 引用路径：逐个求值，遇不同立即返回 0（spec: FunctionMethodCreator.cs:4632 / :4642）。 */
private fun allsamesRef(c: BuiltinContext, e: List<Expr?>): Long {
    fun value(i: Int): Value =
        e[i]?.toValue(c) ?: throw CodeEE("ALLSAMES函数: 第${i + 1}个参数不能省略")
    val first = value(0)
    if (first is Value.IntV) {
        for (i in 1 until e.size) {
            val v = value(i) as? Value.IntV
                ?: throw CodeEE("ALLSAMES函数: 第${i + 1}个参数不是整数")
            if (v.v != first.v) return 0L
        }
    } else {
        val target = first as? Value.StrV
            ?: throw CodeEE("ALLSAMES函数: 第1个参数不是字符串")
        for (i in 1 until e.size) {
            val v = value(i) as? Value.StrV
                ?: throw CodeEE("ALLSAMES函数: 第${i + 1}个参数不是字符串")
            if (v.v != target.v) return 0L
        }
    }
    return 1L
}

// ---------------------------------------------------------------------------
// 注册
// ---------------------------------------------------------------------------

internal fun registerPure(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR
    fun int(name: String, min: Int, max: Int, fn: (List<Value?>) -> Long) {
        r.register(BuiltinMethod(name, I, min, max) { _, a -> Value.IntV(fn(a)) })
    }
    /** 需要码页（`BuiltinHost.configOverrides`）的整型函数走这条。 */
    fun intC(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> Long) {
        r.register(BuiltinMethod(name, I, min, max) { c, a -> Value.IntV(fn(c, a)) })
    }
    fun str(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> String) {
        r.register(BuiltinMethod(name, S, min, max) { c, a -> Value.StrV(fn(c, a)) })
    }

    // ---- 数学 ----

    // spec: FunctionMethodCreator.cs:4075-4138 MaxMethod —— 参数 1 个起，可变个数
    int("MIN", 1, -1) { a -> a.minOf { iv(it!!) } }
    int("MAX", 1, -1) { a -> a.maxOf { iv(it!!) } }
    // spec: FunctionMethodCreator.cs:4139-4158 AbsMethod
    int("ABS", 1, 1) { a ->
        val v = iv(a[0]!!)
        if (v == Long.MIN_VALUE) throw codeEE("ABS", "不能对 -9223372036854775808 应用 ABS")
        Math.abs(v)
    }
    // spec: FunctionMethodCreator.cs:4159-4192 PowerMethod
    int("POWER", 2, 2) { a ->
        val b = iv(a[0]!!); val e = iv(a[1]!!)
        val d = Math.pow(b.toDouble(), e.toDouble())
        if (d.isNaN()) throw codeEE("POWER", "结果为 NaN")
        if (d.isInfinite()) throw codeEE("POWER", "结果为无穷大")
        if (d >= 9.223372036854776E18 || d <= -9.223372036854776E18)
            throw codeEE("POWER", "结果 $d 超出 Int64 范围")
        d.toLong()
    }
    // spec: FunctionMethodCreator.cs:4193-4212 SqrtMethod
    int("SQRT", 1, 1) { a ->
        val v = iv(a[0]!!)
        if (v < 0) throw codeEE("SQRT", "第 1 个参数 $v 为负数")
        Math.sqrt(v.toDouble()).toLong()
    }
    // spec: FunctionMethodCreator.cs:4213-4232 CbrtMethod
    int("CBRT", 1, 1) { a ->
        val v = iv(a[0]!!)
        if (v < 0) throw codeEE("CBRT", "第 1 个参数 $v 为负数")
        Math.pow(v.toDouble(), 1.0 / 3.0).toLong()
    }
    // spec: FunctionMethodCreator.cs:4233-4277 LogMethod（LOG=自然对数，LOG10=以 10 为底）
    int("LOG", 1, 1) { a ->
        val v = iv(a[0]!!)
        if (v <= 0) throw codeEE("LOG", "第 1 个参数 $v 不大于 0")
        Math.log(v.toDouble()).toLong()
    }
    int("LOG10", 1, 1) { a ->
        val v = iv(a[0]!!)
        if (v <= 0) throw codeEE("LOG10", "第 1 个参数 $v 不大于 0")
        Math.log10(v.toDouble()).toLong()
    }
    // spec: FunctionMethodCreator.cs:4278-4305 ExpMethod
    int("EXPONENT", 1, 1) { a ->
        val d = Math.exp(iv(a[0]!!).toDouble())
        if (d.isNaN()) throw codeEE("EXPONENT", "结果为 NaN")
        if (d.isInfinite()) throw codeEE("EXPONENT", "结果为无穷大")
        if (d >= 9.223372036854776E18 || d <= -9.223372036854776E18)
            throw codeEE("EXPONENT", "结果 $d 超出 Int64 范围")
        d.toLong()
    }
    // spec: FunctionMethodCreator.cs:4306-4320 SignMethod
    int("SIGN", 1, 1) { a ->
        val v = iv(a[0]!!)
        if (v > 0) 1L else if (v < 0) -1L else 0L
    }
    // spec: FunctionMethodCreator.cs:4321-4351 GetLimitMethod
    int("LIMIT", 3, 3) { a ->
        val v = iv(a[0]!!); val lo = iv(a[1]!!); val hi = iv(a[2]!!)
        if (v < lo) lo else if (v > hi) hi else v
    }
    // spec: FunctionMethodCreator.cs:4766-4791 GetbitMethod
    int("GETBIT", 2, 2) { a ->
        val v = iv(a[0]!!); val bit = iv(a[1]!!)
        if (bit < 0 || bit > 63) throw codeEE("GETBIT", "第 2 个参数 $bit 越界（0-63）")
        (v shr bit.toInt()) and 1L
    }
    // ---- 组内比较族：ArgType.Any + ArgType.VariadicSameAsFirst ----
    //
    // 首实参的操作数类型决定整族走哪条分支：数值走 GetIntValue、字符串走 GetStrValue
    // （spec: FunctionMethodCreator.cs:4531/4544 GROUPMATCH、:4578/4590 NOSAMES、:4627/4638 ALLSAMES）。
    // 参数下限 2：ArgTypes 表长 2 且尾项是 Variadic（:4514-4524 / :4562-4572 / :4611-4621），
    // FunctionMethod.cs:183 的 flag3 要求 arguments.Count >= ArgTypes.Count，否则走 :283 的 NotEnoughArgs。

    // spec: FunctionMethodCreator.cs:4509-4556 GroupMatchMethod
    // 首参 STR → 每个候选与首参逐项等值比较（:4544-4551）；首参 INT → 数值比较（:4531-4541）。
    int("GROUPMATCH", 2, -1) { a ->
        val first = a[0]
        if (first is Value.IntV) {
            (1 until a.size).count { sameAsFirstInt("GROUPMATCH", a, it) == first.v }.toLong()
        } else {
            val target = sameAsFirstStr("GROUPMATCH", a, 0)
            (1 until a.size).count { sameAsFirstStr("GROUPMATCH", a, it) == target }.toLong()
        }
    }
    // spec: FunctionMethodCreator.cs:4557-4605 NosamesMethod
    // 全部实参（含第 0 个）取值后求去重数：与个数相等即互不相同（:4580-4588 / :4592-4600）。
    int("NOSAMES", 2, -1) { a ->
        val allDistinct = if (a[0] is Value.IntV) {
            val vs = a.indices.map { sameAsFirstInt("NOSAMES", a, it) }
            vs.size == vs.toSet().size
        } else {
            val vs = a.indices.map { sameAsFirstStr("NOSAMES", a, it) }
            vs.size == vs.toSet().size
        }
        if (allDistinct) 1L else 0L
    }
    // spec: FunctionMethodCreator.cs:4606-4652 AllsamesMethod
    // 逐项与首参比较，遇不同即 0（:4627-4647）。原版 :4632 在不等时立即 return，
    // 后续实参**不再求值**；表达式层因此走 evalRef 逐个取值（Builtins.kt:753-760），
    // 而不是值路径的整体求值。值路径保留给语句层的已求值调用，无法回退副作用。
    r.register(
        BuiltinMethod("ALLSAMES", I, 2, -1, evalRef = { c, e -> Value.IntV(allsamesRef(c, e)) }) { _, a ->
            Value.IntV(allsamesValue(a))
        },
    )
    // spec: FunctionMethodCreator.cs:4984-5010 InRangeMethod
    int("INRANGE", 3, 3) { a ->
        val v = iv(a[0]!!); val lo = iv(a[1]!!); val hi = iv(a[2]!!)
        if (v in lo..hi) 1L else 0L
    }
    // spec: FunctionMethodCreator.cs:3747-3769 BarStringMethod + ExpressionMediator.cs:130-157
    // :135-145 —— 三条 CodeEE 都从共享的 CreateBar 抛出（BAR 语句同源，FunctionIdentifier.cs:1306），
    // 文案不带函数名前缀（zhs emuera-zhs.xml:234-236；与 sem/StatementExecutor.kt createBar 同一条）。
    // :146-156 —— fill 夹在 [0,length]；填充字符取 Config.BarChar1 / BarChar2（ExpressionMediator.cs:155-156）
    str("BARSTR", 3, 3) { c, a ->
        val value = iv(a[0]!!); val max = iv(a[1]!!); val length = iv(a[2]!!)
        if (max <= 0) throw CodeEE("BAR的最大值不为正值")
        if (length <= 0) throw CodeEE("BAR的长度不为正值")
        if (length >= 100) throw CodeEE("BAR过长")
        var fill = (value * length / max).toInt()
        if (fill < 0) fill = 0
        if (fill > length) fill = length.toInt()
        val (barChar1, barChar2) = barChars(c)
        "[" + barChar1.toString().repeat(fill) + barChar2.toString().repeat(length.toInt() - fill) + "]"
    }
    // spec: FunctionMethodCreator.cs:3808-3836 ColorFromNameMethod
    // 名单唯一权威是 ColorNames（GDI+ KnownColor 表，收口前本文件自带一份 46+14 名副本）。
    // 未知名返回 -1（spec: FunctionMethodCreator.cs:3832），transparent 抛错（:3828），口径不变。
    int("COLOR_FROMNAME", 1, 1) { a ->
        val name = sv(a[0]!!)
        if (ColorNames.isTransparent(name)) throw codeEE("COLOR_FROMNAME", "transparent 不能转换为颜色值")
        ColorNames.rgbOrUnknown(name)
    }
    // spec: FunctionMethodCreator.cs:3838-3872 ColorFromRGBMethod
    int("COLOR_FROMRGB", 3, 3) { a ->
        val r = iv(a[0]!!); val g = iv(a[1]!!); val b = iv(a[2]!!)
        for ((i, v) in listOf(r, g, b).withIndex()) {
            if (v < 0 || v > 255) throw codeEE("COLOR_FROMRGB", "第 ${i + 1} 个参数 $v 越界（0-255）")
        }
        (r shl 16) or (g shl 8) or b
    }

    // ---- 字符串 ----

    // spec: FunctionMethodCreator.cs:5260-5274 StrlenMethod
    // 唯一实现与码页来源见 core/expr/FormStringTerm.kt 的 LangCodec（Config.cs:412-430）。
    intC("STRLENS", 1, 1) { c, a -> langCodecOf(c).strlen(sv(a[0]!!)).toLong() }
    // spec: FunctionMethodCreator.cs:5275-5289 StrlenuMethod —— 与 STRLENS 口径不同（UTF-16 长度）
    int("STRLENSU", 1, 1) { a -> sv(a[0]!!).length.toLong() }
    // spec: FunctionMethodCreator.cs:5290-5327 SubstringMethod
    str("SUBSTRING", 1, 3) { c, a ->
        val s = sv(a[0]!!)
        val start = optInt(a, 1, 0).toInt()
        val len = optInt(a, 2, -1).toInt()
        langCodecOf(c).subString(s, start, len)
    }
    // spec: FunctionMethodCreator.cs:5328-5385 SubstringuMethod —— 边界怪行为原样复刻
    str("SUBSTRINGU", 1, 3) { _, a ->
        val s = sv(a[0]!!)
        var start = optInt(a, 1, 0).toInt()
        var len = optInt(a, 2, -1).toInt()
        if (start >= s.length || len == 0) return@str ""
        if (len < 0 || len > s.length) len = s.length
        if (start <= 0) {
            if (len == s.length) return@str s
            start = 0
        }
        if (start + len > s.length) len = s.length - start
        s.substring(start, start + len)
    }
    // spec: FunctionMethodCreator.cs:5386-5431 StrfindMethod —— OmitStart=2（:5404）：3 个槽位，
    // 第 1、2 位（str、search）不可省略，第 3 位起始位置可缺省（默认 0）。
    // 个数闸是 FunctionMethod.cs:183 的 `arguments.Count >= OmitStart`，不足走 :283 的
    // NotEnoughArgs（下限取 OmitStart，移植文案 `至少需要2个参数`）；`j < OmitStart` 的空位走
    // :198-204 的 ArgCanNotBeNull（移植文案 `第{N}个参数不能省略`）。⇒ 注册必须带 omitStart=2：
    // 远程主线那侧只把 minArgs 抬到 2、实参仍按 `a[0]!!`/`a[1]!!` 取值，空位会运行期 NPE、
    // 个数不足的文案也不是原版那条；两版对「1 个实参」同样只报 CodeEE（BuiltinsTest 已钉）。
    r.register(BuiltinMethod("STRFIND", I, 2, 3, omitStart = 2) { c, a ->
        val s = requiredStrArg(a, 0, "STRFIND")
        val search = requiredStrArg(a, 1, "STRFIND")
        val codec = langCodecOf(c)
        val start = optInt(a, 2, 0).toInt()
        val uStart = codec.uftIndex(s, start)  // 非 unicode 版按字节下标转字符下标
        val idx = if (uStart < 0 || uStart >= s.length) {
            -1L
        } else {
            val hit = s.indexOf(search, uStart)
            if (hit <= 0) hit.toLong() else codec.strlen(s.substring(0, hit)).toLong()
        }
        Value.IntV(idx)
    })
    // 同一份 StrfindMethod 的 unicode 分支：起始位直接用 UTF-16 下标（spec: :5418 只在
    // !unicode 时走 GetUFTIndex）、命中值不做码页换算（spec: :5425 的 `num2 > 0 && !unicode`）；
    // 个数下限同样是 2（同一份 ArgTypeList，OmitStart=2，spec: :5404）。
    r.register(BuiltinMethod("STRFINDU", I, 2, 3, omitStart = 2) { _, a ->
        val s = requiredStrArg(a, 0, "STRFINDU")
        val search = requiredStrArg(a, 1, "STRFINDU")
        val start = optInt(a, 2, 0).toInt()
        Value.IntV(if (start < 0 || start >= s.length) -1L else s.indexOf(search, start).toLong())
    })
    // spec: FunctionMethodCreator.cs:5433-5460 StrCountMethod —— 正则匹配计数
    // 正则方言走 dotNetRegex：`new Regex(pattern, RegexOptions.Compiled)`（RegexFactory.cs:19），
    // 于是 `\d` / `\w` / `\s` 是 Unicode 属性而不是 JDK 默认的 ASCII 集。
    int("STRCOUNT", 2, 2) { a ->
        val s = sv(a[0]!!)
        val pattern = sv(a[1]!!)
        val regex = try {
            dotNetRegex(pattern)
        } catch (e: Exception) {
            // spec: FunctionMethodCreator.cs:5451-5455 —— InvalidRegexArg（zhs emuera-zhs.xml:297）
            throw CodeEE("STRCOUNT函数: 第2参数无法解析为正则表达式: ${e.message}")
        }
        // TODO(verify): .NET Regex.Matches 与 Kotlin findAll 均为非重叠匹配，计数口径一致；
        // 但两边正则方言仍有残差（如 .NET 的 balancing group），极端用例可能不同
        regex.findAll(s).count().toLong()
    }
    // spec: FunctionMethodCreator.cs:5461-5499 ToStrMethod
    str("TOSTR", 1, 2) { _, a ->
        val v = iv(a[0]!!)
        if (a.size < 2 || a[1] == null) return@str v.toString()
        formatDotNet(v, sv(a[1]!!), "TOSTR")
    }
    // spec: FunctionMethodCreator.cs:5500-5557 ToIntMethod —— 失败路径一律返回 0
    intC("TOINT", 1, 1) { c, a ->
        val s = sv(a[0]!!)
        if (s.isEmpty()) return@intC 0L
        if (s.length < langCodecOf(c).strlen(s)) return@intC 0L  // 含全角字符 → 0
        val st = CharStream(s)
        if (!st.current.isDigit() && st.current != '+' && st.current != '-') return@intC 0L
        if ((st.current == '+' || st.current == '-') && !st.next.isDigit()) return@intC 0L
        // 数字部分：溢出/非法进制与原版一致地抛错（spec: LexicalAnalyzer.cs:95-166 readDigits）
        val result = try {
            ErbLexer().readInt64(st, retZero = true, lineNo = 1)
        } catch (e: LexError) {
            throw CodeEE(e.message ?: "TOINT 解析失败")
        }
        if (!st.eos) {
            if (st.current != '.') return@intC 0L
            st.shiftNext()
            while (!st.eos) {
                if (!st.current.isDigit()) return@intC 0L
                st.shiftNext()
            }
        }
        result
    }
    // spec: FunctionMethodCreator.cs:5558-5595 StrChangeStyleMethod
    // :5587 `ToUpper(CultureInfo.InvariantCulture)` / :5588 `ToLower()`（CurrentCulture；ja/zh
    // 机器与 invariant 等价，tr/az 差异登记不修）走 .NET ICU **全映射**，可以扩长：
    // ß→SS、ﬁ→FI、İ 的小写是 i̇（0069 0307）（Unicode SpecialCasing.txt 无条件映射行）。
    // Kotlin 无参 uppercase()/lowercase() = toUpperCase(Locale.ROOT) 同为全映射 —— 整串映射复刻。
    // （2026-10-26 fixcore 审查横切 B1 订正：旧实现的「.NET 逐字符 1:1 绝不扩长」前提不成立，
    // 旧逐码元 Character.toUpperCase/toLowerCase 对 ß/ﬁ/İ 与原版分叉。语料 TOUPPER 18 处 /
    // TOLOWER 134 处，ASCII 键面读数不变。）
    str("TOUPPER", 1, 1) { _, a ->
        val s = sv(a[0]!!)
        if (s.isEmpty()) "" else s.uppercase()
    }
    str("TOLOWER", 1, 1) { _, a ->
        val s = sv(a[0]!!)
        if (s.isEmpty()) "" else s.lowercase()
    }
    str("TOHALF", 1, 1) { c, a ->
        val s = sv(a[0]!!)
        if (s.isEmpty()) "" else toHalf(s, langCodecOf(c))
    }
    str("TOFULL", 1, 1) { c, a ->
        val s = sv(a[0]!!)
        if (s.isEmpty()) "" else toFull(s, langCodecOf(c))
    }
    // spec: FunctionMethodCreator.cs:5611-5690 ReplaceMethod
    // 第 4 参 mode=1 时第 3 参是 1 维字符串数组（按匹配序号取替换串），
    // 需要变量身份 → 走 evalRef；值路径保留 mode 0/2（原版 3 参走正则、mode 2 走字面替换）。
    r.register(
        BuiltinMethod("REPLACE", S, 3, 4, evalRef = { c, e -> Value.StrV(replaceRef(c, e)) }) { _, a ->
            Value.StrV(replaceValue(a))
        },
    )
    // spec: FunctionMethodCreator.cs:984-1065 RegexpMatchMethod
    r.register(
        BuiltinMethod("REGEXPMATCH", I, 2, 4, evalRef = { c, e -> Value.IntV(regexMatchRef(c, e)) }) { c, a ->
            Value.IntV(regexMatchValue(c, a))
        },
    )
    // spec: FunctionMethodCreator.cs:5691-5722 UnicodeMethod
    // :5704-5706 越界 = Lang.Error.ArgIsOutOfRange（zhs emuera-zhs.xml:917 =
    // `{0}函数: 第{1}个参数({2})在从{3}到{4}的范围外`）。
    str("UNICODE", 1, 1) { c, a ->
        val code = iv(a[0]!!)
        if (code < 0 || code > 65535) throw CodeEE("UNICODE函数: 第1个参数($code)在从0到65535的范围外")
        if ((code < 31L && code != 10L && code != 13L) || (code in 127L..159L)) {
            // spec: :5707-5716 —— 原版把 InvalidUnicode（zhs emuera-zhs.xml:926，{1} 为大写
            // 十六进制）套进 WarnPrefix（zhs :192，见 [unicodeWarnPrefix]）打系统行；
            // getCurrentLine == null 时回落无前缀（:5713-5716）。
            // 当前行通道 = [BuiltinContext.currentScriptLine]（VM 每语句刷新，见
            // ProcessRuntime.executeStatement）；未接线（内存编译/测试）时走 null 回落。
            val body = "UNICODE函数: 传入了控制字符对应的值(0x${code.toString(16).uppercase()})"
            val cur = currentScriptLine(c)
            c.printSystemLine(if (cur == null) body else unicodeWarnPrefix(cur.first, cur.second, body))
            return@str ""
        }
        code.toChar().toString()
    }
    // spec: FunctionMethodCreator.cs:5723-5740 UnicodeByteMethod —— 字符串首个 UTF-32 码点。
    // 原版走 `Encoding.UTF32` 编码器（flush:false）取前 4 字节：配对代理 = 码点；未配对代理
    // 分两样（fn-strings 路 2026-10-26 实测 .NET 10，订正旧探针记录）：**串尾孤立高位代理**
    // 字节计数 = 0 ⇒ `BitConverter.ToInt32` 抛 ArgumentOutOfRange、原版未捕获崩溃（本仓按
    // verify-notes §12 政策返 0xFFFD 不复刻崩溃）；`"\uDC00"` / `"\uD800A"` 走替换字符
    // U+FFFD 出 4 字节（读数 65533 成立），故不能直接 codePointAt（它对孤立代理原样返回
    // 0xD800 一类）。
    int("UNICODEBYTE", 1, 1) { a ->
        val s = sv(a[0]!!)
        if (s.isEmpty()) return@int 0L
        val c0 = s[0]
        when {
            Character.isHighSurrogate(c0) && s.length >= 2 && Character.isLowSurrogate(s[1]) ->
                s.codePointAt(0).toLong()
            Character.isSurrogate(c0) -> 0xFFFDL
            else -> c0.code.toLong()
        }
    }
    // spec: FunctionMethodCreator.cs:5741-5764 ConvertIntMethod
    // :5761 `Convert.ToString(long, int)`：10 进制按有符号；2 / 8 / 16 进制把值当**无符号 64 位**
    // 补码（-1,16 ⇒ 16 个 f；MinValue,16 ⇒ 8000000000000000）。
    str("CONVERT", 2, 2) { _, a ->
        val v = iv(a[0]!!)
        val radix = iv(a[1]!!)
        if (radix !in longArrayOf(2, 8, 10, 16))
            // spec: FunctionMethodCreator.cs:5755-5758 —— Lang.Error.ArgShouldBeSpecificValue
            // （zhs emuera-zhs.xml:927，{2} = "2, 8, 10, 16"）
            throw CodeEE("CONVERT函数: 传入第2个参数必须是2, 8, 10, 16的其中一个")
        when (radix.toInt()) {
            2 -> java.lang.Long.toUnsignedString(v, 2)
            8 -> java.lang.Long.toUnsignedString(v, 8)
            16 -> java.lang.Long.toUnsignedString(v, 16)
            else -> v.toString()
        }
    }
    // spec: FunctionMethodCreator.cs:5765-5813 IsNumericMethod
    // spec: FunctionMethodCreator.cs:5765-5813 IsNumericMethod —— NumericCheck 后还要求到串尾（或小数尾）
    intC("ISNUMERIC", 1, 1) { c, a ->
        val s = sv(a[0]!!)
        if (s.length < langCodecOf(c).strlen(s)) return@intC 0L
        val st = CharStream(s)
        if (!st.current.isDigit() && st.current != '+' && st.current != '-') return@intC 0L
        if ((st.current == '+' || st.current == '-') && !st.next.isDigit()) return@intC 0L
        if (!numericCheck(st)) return@intC 0L
        if (!st.eos) {
            if (st.current != '.') return@intC 0L
            st.shiftNext()
            while (!st.eos) {
                if (!st.current.isDigit()) return@intC 0L
                st.shiftNext()
            }
        }
        1L
    }
    // spec: FunctionMethodCreator.cs:5814-5828 EscapeMethod —— Regex.Escape
    str("ESCAPE", 1, 1) { _, a ->
        vStrEscape(sv(a[0]!!))
    }
    // spec: FunctionMethodCreator.cs:5829-5868 EncodeToUniMethod
    int("ENCODETOUNI", 1, 2) { a ->
        val s = sv(a[0]!!)
        if (s.isEmpty()) return@int -1L
        val idx = optInt(a, 1, 0)
        if (idx < 0) throw codeEE("ENCODETOUNI", "第 2 个参数 $idx 为负数")
        if (idx >= s.length) throw codeEE("ENCODETOUNI", "第 2 个参数 $idx 超出字符串 \"$s\" 的长度")
        s.codePointAt(idx.toInt()).toLong()
    }
    // spec: FunctionMethodCreator.cs:5869-5893 CharAtMethod —— 越界返回空串
    str("CHARATU", 2, 2) { _, a ->
        val s = sv(a[0]!!)
        val idx = iv(a[1]!!)
        if (idx < 0 || idx >= s.length) "" else s[idx.toInt()].toString()
    }
    // spec: FunctionMethodCreator.cs:5914-5967 StrFormMethod —— 运行期解析 {}/%/三连记号形式串
    str("STRFORM", 1, 1) { c, a ->
        val format = sv(a[0]!!)
        try {
            // :5929 —— 结束符是 FormStrEndWith.EoL（值里的 '"' 是普通字符），trim: false
            // 词法配置走 VM 条目表：{}/%% 子表达式经 Analyse 吃 SystemAllowFullSpace
            // （spec: LexicalAnalyzer.cs:748-754 + Config.cs:370）、三连记号吃
            // SystemIgnoreTripleSymbol（spec: LexicalAnalyzer.cs:1138 + Config.cs:372）。
            val form = ErbLexer(ConfigMethods.lexerConfig(c)).analyseFormattedString(
                CharStream(format), FormStrEndWith.EoL, false, 1,
            )
            FormStringTerm.fromLexerStrForm(form, storeResolver(c), c.parserConfig, 1, 1).getStrValue(c)
        } catch (e: CodeEE) {
            // :5931-5934 —— 内部 CodeEE 包成 InvalidFormString（lang.emuera-zhs.xml:930）
            throw CodeEE("STRFORM函数: 字符串\"$format\"展开错误: ${e.message}")
        } catch (e: LexError) {
            // 词法层的 LexError 在原版就是 CodeEE（LexicalAnalyzer 抛 CodeEE），故走同一个
            // InvalidFormString 分支，而不是下面的兜底文案。
            throw CodeEE("STRFORM函数: 字符串\"$format\"展开错误: ${e.message}")
        } catch (e: Exception) {
            // :5935-5938 —— 其余异常包成 UnexectedFormStringErr（lang.emuera-zhs.xml:931）。
            // 原版这里连非 CodeEE 异常一起吞；本移植保留同一条可诊断出口，
            // 不让任何形态的异常漏到 TRYC 外面。
            throw CodeEE("STRFORM函数: 字符串\"$format\"展开时发生错误")
        }
    }
    // spec: FunctionMethodCreator.cs:3873-3921 MoneyStrMethod
    str("MONEYSTR", 1, 2) { c, a ->
        val v = iv(a[0]!!)
        val label = c.host.moneyLabel
        val first = c.host.moneyLabelFirst
        if (a.size < 2 || a[1] == null) {
            if (first) label + v else v.toString() + label
        } else {
            val text = formatDotNet(v, sv(a[1]!!), "MONEYSTR")
            if (first) label + text else text + label
        }
    }
}

/**
 * .NET `Regex.Escape` 的移植（spec: FunctionMethodCreator.cs:5825）。
 *
 * 转义集（本机 dotnet 8.0.421 实测，向量见 `StringPatchSpecTest`）：
 * `\ * + ? | { [ ( ) ^ $ . #` 一律加反斜杠；空格 → 反斜杠 + 空格；
 * 制表 / 换行 / 回车 / 换页 → 反斜杠 + 对应字母（`\t` `\n` `\r` `\f`）。
 * 不转义：`]` `}` `-` `"` 以及垂直制表 U+000B、全角空格 U+3000（实测原样）。
 */
private fun vStrEscape(s: String): String = buildString {
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

/** GETVAR/GETVARS/STRFORM 共用：以变量池 + 内建函数表为后盾的解析期 resolver。 */
internal fun storeResolver(c: BuiltinContext): IdentifierResolver = object : IdentifierResolver {
    override fun resolveVariable(name: String, subKey: String?): VarInfo? =
        c.store.resolveVarInfo(name, c.store.currentFunction)

    override fun resolveFunction(name: String): OperandType? =
        Builtins.registry.returnType(name)
}

/** spec: LexicalAnalyzer.cs:167-221 NumericCheck（含前导符号检查；不要求消费到串尾） */
internal fun numericCheck(st: CharStream): Boolean {
    var fromBase = 10
    if (st.current == '0') {
        when (st.next) {
            'X', 'x' -> { fromBase = 16; st.shiftNext(); st.shiftNext() }
            'B', 'b' -> { fromBase = 2; st.shiftNext(); st.shiftNext() }
        }
    }
    if (!st.current.isDigit() && st.current != '+' && st.current != '-') {
        if (fromBase != 16) return false
        if (st.current !in "abcdefABCDEF") return false
    }
    fun readDigits() {
        // spec: LexicalAnalyzer.cs:92-96 readDigits 先跳过前导符号
        if (st.current == '+' || st.current == '-') st.shiftNext()
        while (!st.eos) {
            val c = st.current
            val ok = when (fromBase) {
                10 -> c.isDigit()
                16 -> c.isDigit() || c in "abcdefABCDEF"
                else -> c == '0' || c == '1'
            }
            if (!ok) break
            st.shiftNext()
        }
    }
    readDigits()
    var expBase = 0
    if (st.current == 'p' || st.current == 'P') expBase = 2
    else if (st.current == 'e' || st.current == 'E') expBase = 10
    if (expBase != 0) {
        st.shiftNext()
        if (st.eos || !st.current.isDigit()) return false
        readDigits()
    }
    return true
}

// ---------------------------------------------------------------------------
// UNICODE 控制码警告的「当前脚本行」通道（FunctionMethodCreator.cs:5709-5712）
// ---------------------------------------------------------------------------

/**
 * 当前脚本行 = C# `GlobalStatic.Process.getCurrentLine.Position`：
 * [Pair.first] 是 `Filename`（装载期显示名，如 `ERB` 目录里带相对路径的 ERB 名）、
 * [Pair.second] 是 `LineNo`（**0 基**：EraStreamReader.cs:48 置 0、:80-84 首行即 0；
 * 移植侧 LogicalLine.lineNo 同基）。
 *
 * null = 通道未接线（`BuiltinContext.currentScriptLine` 的缺省）⇒ 走 C# `getCurrentLine == null`
 * 的回落（:5713-5716，无前缀）。生产接线：`ProcessRuntime.executeStatement` 每条语句把
 * `(fileByKey[functionKey], env.line)` 存进快照，`BuiltinEvalContext` 经 provider 现读。
 */
private fun currentScriptLine(c: BuiltinContext): Pair<String, Int>? = c.currentScriptLine

/** WarnPrefix（zhs emuera-zhs.xml:192 = `注意: {0}的第{1}行: {2}`）的逐字拼接。 */
internal fun unicodeWarnPrefix(filename: String, lineNo: Int, body: String): String =
    "注意: ${filename}的第${lineNo}行: $body"
