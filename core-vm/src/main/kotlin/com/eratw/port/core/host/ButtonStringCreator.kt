package com.eratw.port.core.host

/**
 * `PrintStringBuffer` 的**自动按钮**切分器：普通文本里的 `[123]` 会被原版变成可点段。
 *
 * ## 为什么在 core/host
 *
 * 原版这条逻辑挂在 `PrintStringBuffer.fromCssToButton` 里（`PrintStringBuffer.cs:280-292`）：
 * flush 时把还没变成按钮的**样式段**（`ConsoleStyledString`，即本仓 [LineBufferConsoleSink.Segment]）
 * 交给 `createButtons`（`:326-345`），后者先拼出整串文本、调
 * `ButtonStringCreator.SplitButton`（`:333`，实现 `ButtonStringCreator.cs:24-27` / `:29-161`），
 * 再把切出的每条 primitive 落成一个 `ConsoleButtonString`（带 `Input`，`:378-386`）。
 * 也就是说「哪些列可点、点了喂什么值」是**打印缓冲**的产物，与本仓
 * [LineBufferConsoleSink] 同一层；渲染侧的 `ConsoleAdapter` 只搬运已经切好的段
 * （其 KDoc 第 1 条写明「绝不重解析」，第 2 条的 TODO 也把这条归给核心层）。
 *
 * PRINTPLAIN 走的是 `createPlainButton`（`PrintStringBuffer.cs:125-134` / `:318-324`），
 * **不**做这层切分，故调用方只对 `Kind.TEXT` 段调用本对象。
 *
 * ## 算法（逐字对应 `ButtonStringCreator.cs:29-161`）
 *
 * 1. **前置剪枝**：文本里没有 `[` 或没有 `]` ⇒ 一条不可选 primitive（`:36-37` 的守卫）；
 * 2. **词法**：`lex`（`:194-248`）在 `[` … `]` 与空白处切词；括号嵌套、落单的 `]`
 *    一律返回 null ⇒ 整串一条不可选 primitive（`:38-39,136-144`）；
 * 3. **计数**：数字按钮 token 的个数 `num`，以及「首个数字按钮之前是否有正文」（`flag`）、
 *    「最后一个非空白 token 是否正文」（`flag2`）（`:45-62`）；
 * 4. **`num <= 1`**：**整串**合成一条，`CanSelect = num >= 1`，`Input = 那个数`
 *    （`:63-73`）—— 一行里只有一个 `[n]` 时，**整行**都可点，这是原版行为；
 * 5. **`num >= 2`**：第二遍按 `num2` 的两个位（bit0 = 缓冲区以可选段结尾、
 *    bit1 = 缓冲区含正文）与 `flag3/flag4/flag5` 分派（`:74-135`+`:145-160`），
 *    把前后正文粘到相邻按钮上：只有尾部正文 ⇒ 粘到最后一个按钮；只有前导正文 ⇒
 *    粘到第一个按钮；前后都有 ⇒ 各粘各的；
 * 6. **数值**：`isButtonCore`（`:168-192`）先过正则
 *    `\[\s*([0][xXbB])?[+-]?[0-9]+([eEpP][0-9]+)?\s*]`（`:11`），再把括号内文本按
 *    `LexicalAnalyzer.ReadInt64` 解析（`LexicalAnalyzer.cs:30-90`：0x/0b 前缀、
 *    正负号、`e`/`p` 指数；`SkipAllSpace` 只跳空格/TAB/全角空格，`:622-636`）。
 *    解析失败一律当「不是按钮」（`:180-187` 的 catch）。
 *
 * ## 已知边界
 *
 * - 空白判定是 `LexicalAnalyzer.IsWhiteSpace` 的三个字符（空格、TAB、`U+3000`，`:638-644`），
 *   不用通用空白类；`\n`/`\r` 算正文（原版同样如此，因为 `print` 已在换行处断开）。
 * - `char.IsDigit` 在 .NET 上还认 Unicode 十进制数字（`Nd`），本仓用 Kotlin 的
 *   `Char.isDigit`（同为 `Nd`）等价；括号里出现这类数字时 `toLongOrNull` 解析失败 ⇒
 *   按「不是按钮」处理（与原版 `Convert` 抛 `FormatException` 同结论）。
 * - 正则的数字段是 `[0-9]+`（`:11`），所以含十六进制/二进制**字母**的 token（如 `[0x1A]`）
 *   在正则这一关就被否掉，`0x`/`0b` 前缀只有后跟纯十进制数字时才生效（`[0x12]`、`[0b101]`）。
 * - 指数溢出在原版抛 `CodeEE`、被 `isButtonCore` 的 catch 吞掉 ⇒「不是按钮」；本仓同样返回 null。
 * - 单测：`core-vm/src/test/kotlin/com/eratw/port/core/host/ButtonStringCreatorTest.kt`。
 */
internal object ButtonStringCreator {

    /**
     * 一条切分结果：文本 + 是否可选 + 可选时喂给 VM 的数值（`ConsoleButtonString.cs:81-95`
     * 的 long 构造：`Input = input`、`Inputs = input.ToString()`、`IsInteger = true`）。
     */
    data class ButtonPrimitive(val text: String, val canSelect: Boolean, val input: Long)

    /**
     * `ButtonStringCreator.cs:11` 的正则，逐字对应：
     * `\[` + 空白* + 可选 `0x/0X/0b/0B` + 可选符号 + **十进制数字** + 可选 `e/E/p/P` 指数 + 空白* + `]`。
     * 注意数字段是 `[0-9]+` 而不是十六进制/二进制字符类 —— 所以 `[0x1A]` **不是**按钮
     * （连 .NET 的同一条正则都匹配不上），`[0x12]`/`[0b101]` 才是。空白用显式字符类
     * （`\s` 在 Java 与 .NET 上集合不同，见对象 KDoc）。
     */
    private val NUM_WORD = Regex("\\[[ \t\u3000]*([0][xXbB])?[+-]?[0-9]+([eEpP][0-9]+)?[ \t\u3000]*]")

    /**
     * `ButtonStringCreator.SplitButton`（`ButtonStringCreator.cs:24-27` → `syn` `:29-161`）。
     * 结果按文本顺序覆盖入参整串：`pieces.joinToString("") { it.text } == printBuffer`。
     */
    fun splitButton(printBuffer: String): List<ButtonPrimitive> {
        if (printBuffer.isEmpty() || !printBuffer.contains('[') || !printBuffer.contains(']')) {
            return listOf(ButtonPrimitive(printBuffer, canSelect = false, input = 0L))
        }
        val tokens = lex(printBuffer) ?: return listOf(ButtonPrimitive(printBuffer, canSelect = false, input = 0L))

        var flag = false
        var tailIsPlain = false
        var count = 0
        var lastInput = 0L
        for (token in tokens) {
            if (token.isEmpty() || isWhiteSpace(token[0])) {
                continue
            }
            val parsed = buttonInput(token)
            if (parsed != null) {
                lastInput = parsed
                count++
                tailIsPlain = false
                continue
            }
            tailIsPlain = true
            if (count == 0) {
                flag = true
            }
        }
        if (count <= 1) {
            return listOf(ButtonPrimitive(printBuffer, canSelect = count >= 1, input = lastInput))
        }

        // flag3 = 无前导正文、有尾部正文；flag4 = 有前导正文、无尾部正文；flag5 = 其余（前后都有，或全是按钮）
        val flag3 = !flag && tailIsPlain
        val flag4 = flag && !tailIsPlain
        val flag5 = !flag3 && !flag4

        val out = ArrayList<ButtonPrimitive>()
        val buffer = StringBuilder()
        var canSelect = false
        var input = 0L
        var bits = 0

        fun reduce() {
            if (buffer.isNotEmpty()) {
                out.add(ButtonPrimitive(buffer.toString(), canSelect, input))
                buffer.setLength(0)
                canSelect = false
                input = 0L
            }
        }

        for (token in tokens) {
            if (token.isEmpty()) {
                continue
            }
            if (isWhiteSpace(token[0])) {
                if ((bits and 3) == 3 && flag5 && token.length >= 2) {
                    reduce()
                    buffer.append(token)
                    bits = 0
                } else {
                    buffer.append(token)
                }
                continue
            }
            val parsed = buttonInput(token)
            if (parsed == null) {
                buffer.append(token)
                bits = bits or 2
                continue
            }
            if ((bits and 1) == 1 || flag3) {
                reduce()
                buffer.append(token)
                input = parsed
                canSelect = true
                bits = 1
            } else if (flag4) {
                buffer.append(token)
                input = parsed
                canSelect = true
                reduce()
                bits = 0
            } else {
                buffer.append(token)
                input = parsed
                canSelect = true
                bits = 1
            }
        }
        reduce()
        return out
    }

    /** `LexicalAnalyzer.IsWhiteSpace`（`LexicalAnalyzer.cs:638-644`）：空格、TAB、全角空格。 */
    private fun isWhiteSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\u3000'

    /** `ButtonStringCreator.lex`（`:194-248`）：按 `[` `]` 与空白切词；括号不配对返回 null。 */
    private fun lex(text: String): List<String>? {
        val out = ArrayList<String>()
        var state = 0
        var start = 0
        var i = 0

        fun reduce() {
            if (i != start) {
                out.add(text.substring(start, i))
                start = i
            }
        }

        while (i < text.length) {
            val c = text[i]
            when {
                c == '[' -> {
                    if (state == 1) {
                        return null // 嵌套的 [
                    }
                    reduce()
                    state = 1
                    i++
                }
                c == ']' -> {
                    if (state != 1) {
                        return null // 落单的 ]
                    }
                    i++
                    reduce()
                    state = 0
                }
                state == 0 && isWhiteSpace(c) -> {
                    reduce()
                    while (i < text.length && isWhiteSpace(text[i])) {
                        i++
                    }
                    reduce()
                }
                else -> i++
            }
        }
        reduce()
        return out
    }

    /** `ButtonStringCreator.isButtonCore`（`:168-192`）：`[` + 数字 + `]` 才算，返回该数值。 */
    private fun buttonInput(token: String): Long? {
        if (token.length < 3 || token[0] != '[' || token[token.length - 1] != ']') {
            return null
        }
        if (!NUM_WORD.containsMatchIn(token)) {
            return null
        }
        return readInt64(token.substring(1, token.length - 1))
    }

    /**
     * `LexicalAnalyzer.ReadInt64`（`LexicalAnalyzer.cs:30-90`）+ `SkipAllSpace`（`:622-636`）+
     * `readDigits`（`:92-165`）。解析不了（含溢出）返回 null —— 对应原版抛 `CodeEE` 后被
     * `isButtonCore` 的 catch 吞掉。
     */
    private fun readInt64(raw: String): Long? {
        var i = 0
        while (i < raw.length && isWhiteSpace(raw[i])) {
            i++
        }
        var base = 10
        if (i < raw.length && raw[i] == '0' && i + 1 < raw.length) {
            when (raw[i + 1]) {
                'x', 'X' -> {
                    base = 16
                    i += 2
                }
                'b', 'B' -> {
                    base = 2
                    i += 2
                }
            }
        }
        val mantissa = readDigits(raw, i, base) ?: return null
        var value = mantissa.first
        i = mantissa.second
        var exponentBase = 0
        if (i < raw.length) {
            if (raw[i] == 'p' || raw[i] == 'P') {
                exponentBase = 2
            } else if (raw[i] == 'e' || raw[i] == 'E') {
                exponentBase = 10
            }
        }
        if (exponentBase != 0) {
            val exp = readDigits(raw, i + 1, base) ?: return null
            val e = exp.first.toInt()
            if (e != 0) {
                val scaled = value.toDouble() * Math.pow(exponentBase.toDouble(), e.toDouble())
                if (scaled.isNaN() || scaled.isInfinite() ||
                    scaled > 9.223372036854776E18 || scaled < -9.223372036854776E18
                ) {
                    return null
                }
                value = scaled.toLong()
            }
        }
        return value
    }

    /** `readDigits`（`LexicalAnalyzer.cs:92-165`）的返回：数值 + 读到的下标。 */
    private fun readDigits(raw: String, from: Int, base: Int): Pair<Long, Int>? {
        var i = from
        val start = i
        if (i < raw.length && (raw[i] == '-' || raw[i] == '+')) {
            i++
        }
        when (base) {
            10 -> while (i < raw.length && raw[i].isDigit()) {
                i++
            }
            16 -> while (i < raw.length && (raw[i].isDigit() || raw[i] in 'a'..'f' || raw[i] in 'A'..'F')) {
                i++
            }
            2 -> while (i < raw.length && raw[i].isDigit()) {
                if (raw[i] != '0' && raw[i] != '1') {
                    return null // 原版抛 CodeEE（CanNotUseBinaryNotate）
                }
                i++
            }
        }
        val text = raw.substring(start, i)
        // 符号与数值一起交给进制解析 —— 与 C# `readDigits` 逐字对应：
        // 基 10 走 `long.Parse(s)`（:145），基 16/2 走 `Convert.ToInt64(s.ToString(), fromBase)`
        // （:147），**`s` 含正负号**（`currentPosition` 取在符号之前，:94-101）。
        //
        // 不许拆成「取绝对值 magnitude = body.toLongOrNull(base)，再按符号取负」：绝对值
        // 在 `Long.MIN_VALUE` 处溢出（|−2^63| = 2^63 > Long.MAX_VALUE），`[-9223372036854775808]`
        // 会被判成「不是按钮」，而原版 `long.Parse("-9223372036854775808")` 正常返回
        // `long.MinValue` ⇒ 该 token 仍是按钮（`ButtonStringCreator.cs:168-192` 的 catch 不触发）。
        // 十六进制同型：`[0x-8000000000000000]` 原版 `Convert.ToInt64("-8000…", 16)` 得 MinValue。
        val value = text.toLongOrNull(base) ?: return null
        return value to i
    }
}
