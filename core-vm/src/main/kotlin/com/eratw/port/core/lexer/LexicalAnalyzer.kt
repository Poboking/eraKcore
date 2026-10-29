package com.eratw.port.core.lexer

/**
 * LexicalAnalyzer —— 移植自 MinorShift.Emuera.Runtime.Script.Parser/LexicalAnalyzer.cs
 *
 * 只做词法分析（单逻辑行内 Token 切分），不做表达式求值与 ERB 语义。
 * `#DEFINE` 宏展开见 [analyse] 末尾：宏表由 [LexerConfig.macros] 注入，
 * 缺省空表 = 不展开（独立单测与不读 ERB 的运行期路径）。
 */
class ErbLexer(private val config: LexerConfig = LexerConfig()) {

    /**
     * FORM 内三连记号（`***`/`+++`/`===`/`///`/`$$$`）是否**不**展开。
     *
     * spec: LexicalAnalyzer.cs:1138 `if (!Config.SystemIgnoreTripleSymbol && st.TripleSymbol())`。
     * 语句层另有一份「到行尾」的 FORM 分析实现（core.stmt.analyseFormToEol，因 core.lexer 的
     * [FormStrEndWith] 缺 EoL 分支），它必须与本词法器服从**同一个**开关，否则同一句在同一
     * 引擎内会有两种 StrForm。此处把开关以只读属性暴露给该实现，避免两份配置源。
     */
    val ignoreTripleSymbol: Boolean get() = config.systemIgnoreTripleSymbol

    /** 非致命警告收集（对应 ParserMediator.Warn 的词法部分） */
    val warnings = mutableListOf<LexWarning>()

    private fun warn(message: String, filename: String, lineNo: Int, level: Int) {
        warnings.add(LexWarning(message, filename, lineNo, level))
    }

    // ------------------------------------------------------------------
    // 标识符
    // ------------------------------------------------------------------

    /**
     * 标识符终止字符全集 —— spec: LexicalAnalyzer.cs:28
     * " \u3000.+-[SLASH]*%=!<>|&^~?#)}],:({[$\\'\"@;\t"（[SLASH] 即 '/'，此处避免注释提前闭合）
     * 注意：'\r' '\n' 不在集合内；标识符可含全角字符（不在终止集内的任意字符）。
     */
    private val identifierTerminators =
        " \u3000.+-*/%=!<>|&^~?#)}],:({[\$\\'\"@;\t".toSet()

    /**
     * spec: LexicalAnalyzer.cs:315-328 ReadSingleIdentifierROS
     * 从当前位置读取标识符；遇到终止字符停止（终止字符不被消费），游标跳到终止字符处。
     */
    fun readSingleIdentifier(st: CharStream): String {
        val row = st.rowString
        val start = st.currentPosition
        var found = -1
        for (i in start until row.length) {
            if (row[i] in identifierTerminators) {
                found = i - start
                break
            }
        }
        return if (found != -1) {
            st.jump(found)
            row.substring(start, start + found)
        } else {
            st.jump(row.length - start)
            if (start >= row.length) "" else row.substring(start)
        }
    }

    /** spec: LexicalAnalyzer.cs:268-276 ReadFirstIdentifierWord（空标识符报错） */
    fun readFirstIdentifier(st: CharStream, lineNo: Int): Token.Identifier {
        val code = readSingleIdentifier(st)
        if (code.isEmpty()) {
            throw LexError("不正な文字で行が始まっています", lineNo = lineNo, column = st.currentPosition)
        }
        return Token.Identifier(code, lineNo, st.currentPosition - code.length)
    }

    // ------------------------------------------------------------------
    // 整数
    // ------------------------------------------------------------------

    /** spec: LexicalAnalyzer.cs:20-24 */
    private val hexadecimalDigits = "abcdefABCDEF".toSet()

    /**
     * spec: LexicalAnalyzer.cs:30-90 ReadInt64
     * 支持 10/16(0x)/2(0b) 进制，以及 e/E(×10^n)、p/P(×2^n) 指数后缀。
     */
    fun readInt64(st: CharStream, retZero: Boolean, lineNo: Int): Long {
        var expBase = 0
        var exp = 0
        val startPos = st.currentPosition
        var fromBase = 10
        if (st.current == '0') {
            when (st.next) {
                'X', 'x' -> { fromBase = 16; st.shiftNext(); st.shiftNext() }
                'B', 'b' -> { fromBase = 2; st.shiftNext(); st.shiftNext() }
            }
        }
        if (retZero && st.current != '+' && st.current != '-' && !st.current.isDigit()) {
            if (fromBase != 16) return 0L
            if (st.current !in hexadecimalDigits) return 0L
        }
        val value = readDigits(st, fromBase, lineNo)
        if (st.current == 'p' || st.current == 'P') {
            expBase = 2
        } else if (st.current == 'e' || st.current == 'E') {
            expBase = 10
        }
        if (expBase != 0) {
            st.shiftNext()
            exp = readDigits(st, fromBase, lineNo).toInt()
        }
        val endPos = st.currentPosition
        var result = value
        if (expBase != 0 && exp != 0) {
            val d = value * Math.pow(expBase.toDouble(), exp.toDouble())
            if (d.isNaN() || d.isInfinite() || d > 9.223372036854776E18 || d < -9.223372036854776E18) {
                // 官方口径（ADR-005）照抄：LexicalAnalyzer.cs:85 把**结束位置**当 Substring 的
                // 第二参（长度）传入，再经 CharStream.cs:88-99 的越界钳制（start+length > len
                // ⇒ 取到行尾）——故报错文本是「数字起点到本行末尾」，不是「数字本身」。
                throw LexError(
                    "\"${st.substring(startPos, endPos)}\"は64ビット符号付き整数の範囲を超えています",
                    lineNo = lineNo, column = startPos,
                )
            }
            result = d.toLong()
        }
        return result
    }

    /** spec: LexicalAnalyzer.cs:92-165 readDigits */
    private fun readDigits(st: CharStream, fromBase: Int, lineNo: Int): Long {
        val startPos = st.currentPosition
        if (st.current == '-' || st.current == '+') {
            st.shiftNext()
        }
        when (fromBase) {
            10 -> while (!st.eos) {
                if (!st.current.isDigit()) break
                st.shiftNext()
            }
            16 -> while (!st.eos) {
                if (!st.current.isDigit() && st.current !in hexadecimalDigits) break
                st.shiftNext()
            }
            2 -> while (!st.eos) {
                if (!st.current.isDigit()) break
                if (st.current != '0' && st.current != '1') {
                    throw LexError("二進法表記の中で使用できない文字が使われています", lineNo = lineNo, column = st.currentPosition)
                }
                st.shiftNext()
            }
        }
        val s = st.substring(startPos, st.currentPosition - startPos)
        // C# 中 FormatException → CanNotConvertToInt，OverflowException → OoRInt64，分别处理
        if (fromBase == 10) {
            // .NET long.Parse 只接受 ASCII 数字与符号；全角数字已被循环消费但无法解析
            if (s.isEmpty() || s == "+" || s == "-" || s.any { it != '+' && it != '-' && it !in '0'..'9' }) {
                throw LexError("\"$s\"を整数値に変換できません", lineNo = lineNo, column = startPos)
            }
            return try {
                s.toLong()
            } catch (e: NumberFormatException) {
                throw LexError(
                    "\"$s\"は64ビット符号付き整数の範囲を超えています", lineNo = lineNo, column = startPos,
                )
            }
        }
        return parseNonDecimal(s, fromBase, startPos, lineNo)
    }

    /**
     * 基数 2 / 16 的字面量解析 —— 逐条复刻 .NET `Convert.ToInt64(s, fromBase)`
     * （spec: LexicalAnalyzer.cs:147 的调用；下列四档行为在 .NET 9 运行时实测得到，
     * 与 JVM `Long.parseLong(s, radix)` **并不等价**，故不能直接改用 parseLong）。
     *
     * 1. **空数字串**（`0x` / `0b` / `0xG`：前缀之后的首位不属于该基数）
     *    → `ArgumentOutOfRangeException` → 被 spec: LexicalAnalyzer.cs:157-162 的
     *    `s.IsEmpty` 分支接住，报 **CanNotInterpretNum**「数値として認識できません」
     *    （Lang.cs:3502），**不是** CanNotConvertToInt。
     * 2. **带负号**（`0x-1` / `0b-1`）
     *    → `ArgumentException`「String cannot contain a minus sign if the base is not 10」；
     *    spec: LexicalAnalyzer.cs:149-164 的三个 catch（Format/Overflow/ArgumentOutOfRange）
     *    都不覆盖 ArgumentException，原版装载期因此抛**非 CodeEE** 异常。
     *    移植侧降级成词法错误（结果同样是该行装载失败），文案沿用 CanNotConvertToInt 形态；
     *    若要按 ADR-005 升级为致命错误，需装载层（非本路文件）裁决。TODO(wiring)。
     *    注意：正号是合法的（`Convert.ToInt64("+1", 2) == 1`），只单独出现的 `+`
     *    → FormatException → CanNotConvertToInt。
     * 3. **满位宽的十六进制/二进制按补码 reinterpret**（原版最容易丢的一条）
     *    .NET 先按**无符号** 64 位解析再原样按二进制补码取 Long：
     *    `0xFFFFFFFFFFFFFFFF` → -1、`0x8000000000000000` → -9223372036854775808、
     *    `0b` + 64 个 1 → -1。JVM 的 `parseLong` 对这些一律抛异常，故这里必须走
     *    [java.lang.Long.parseUnsignedLong]（其溢出判据与 .NET 一致，前导 0 不计入位宽：
     *    `0x0FFFFFFFFFFFFFFFF` 仍是 -1）。
     * 4. **真正超范围**（去前导 0 后仍超过 64 位，如 17 位十六进制、65 个二进制 1）
     *    → `OverflowException` → spec: LexicalAnalyzer.cs:153-156 → OoRInt64。
     */
    private fun parseNonDecimal(s: String, fromBase: Int, startPos: Int, lineNo: Int): Long {
        if (s.isEmpty()) {
            throw LexError("数値として認識できません", lineNo = lineNo, column = startPos)
        }
        val body = if (s[0] == '+') s.substring(1) else s
        if (body.isEmpty() || '-' in body || body.any { it !in '0'..'9' && it !in hexadecimalDigits }) {
            throw LexError("\"$s\"を整数値に変換できません", lineNo = lineNo, column = startPos)
        }
        return try {
            java.lang.Long.parseUnsignedLong(body, fromBase)
        } catch (e: NumberFormatException) {
            throw LexError(
                "\"$s\"は64ビット符号付き整数の範囲を超えています", lineNo = lineNo, column = startPos,
            )
        }
    }

    // ------------------------------------------------------------------
    // 运算符
    // ------------------------------------------------------------------

    /**
     * spec: LexicalAnalyzer.cs:402-509 ReadOperator
     * 运算符全集以原版 switch 为准：++ -- * / % =(==/Assignment) != !& !| ! <= << < >= >> > || | && & ^^ ^ ~ ? #
     */
    fun readOperator(st: CharStream, allowAssignment: Boolean, lineNo: Int): OperatorCode {
        val c1 = st.current
        st.shiftNext()
        val c2 = st.current
        return when (c1) {
            '+' -> if (c2 == '+') { st.shiftNext(); OperatorCode.Increment } else OperatorCode.Plus
            '-' -> if (c2 == '-') { st.shiftNext(); OperatorCode.Decrement } else OperatorCode.Minus
            '*' -> OperatorCode.Mult
            '/' -> OperatorCode.Div
            '%' -> OperatorCode.Mod
            '=' -> when {
                c2 == '=' -> { st.shiftNext(); OperatorCode.Equal }
                allowAssignment -> OperatorCode.Assignment
                else -> throw LexError(
                    "予期しない代入演算子'='を発見しました(等価比較には'=='を使用してください)",
                    lineNo = lineNo, column = st.currentPosition - 1,
                )
            }
            '!' -> when (c2) {
                '=' -> { st.shiftNext(); OperatorCode.NotEqual }
                '&' -> { st.shiftNext(); OperatorCode.Nand }
                '|' -> { st.shiftNext(); OperatorCode.Nor }
                else -> OperatorCode.Not
            }
            '<' -> when (c2) {
                '=' -> { st.shiftNext(); OperatorCode.LessEqual }
                '<' -> { st.shiftNext(); OperatorCode.LeftShift }
                else -> OperatorCode.Less
            }
            '>' -> when (c2) {
                '=' -> { st.shiftNext(); OperatorCode.GreaterEqual }
                '>' -> { st.shiftNext(); OperatorCode.RightShift }
                else -> OperatorCode.Greater
            }
            '|' -> if (c2 == '|') { st.shiftNext(); OperatorCode.Or } else OperatorCode.BitOr
            '&' -> if (c2 == '&') { st.shiftNext(); OperatorCode.And } else OperatorCode.BitAnd
            '^' -> if (c2 == '^') { st.shiftNext(); OperatorCode.Xor } else OperatorCode.BitXor
            '~' -> OperatorCode.BitNot
            '?' -> OperatorCode.Ternary_a
            '#' -> OperatorCode.Ternary_b
            else -> throw LexError(
                "'$c1'は演算子として認識できません",
                lineNo = lineNo, column = st.currentPosition - 1,
            )
        }
    }

    /**
     * spec: LexicalAnalyzer.cs:511-620 ReadAssignmentOperator
     * 支持 '= += -= *= /= %= == \'= <<= >>= |= &= ^=' 及 ++/--
     */
    fun readAssignmentOperator(st: CharStream, lineNo: Int): OperatorCode {
        var op = OperatorCode.NULL
        val c1 = st.current
        st.shiftNext()
        val c2 = st.current
        when (c1) {
            '+' -> when (c2) {
                '+' -> op = OperatorCode.Increment
                '=' -> op = OperatorCode.Plus
            }
            '-' -> when (c2) {
                '-' -> op = OperatorCode.Decrement
                '=' -> op = OperatorCode.Minus
            }
            '*' -> if (c2 == '=') op = OperatorCode.Mult
            '/' -> if (c2 == '=') op = OperatorCode.Div
            '%' -> if (c2 == '=') op = OperatorCode.Mod
            '=' -> if (c2 == '=') op = OperatorCode.Equal else return OperatorCode.Assignment
            '\'' -> if (c2 == '=') op = OperatorCode.AssignmentStr
            '<' -> if (c2 == '<') {
                st.shiftNext()
                if (st.current != '=') {
                    throw LexError("\"<\"は代入演算子として認識できません", lineNo = lineNo, column = st.currentPosition)
                }
                op = OperatorCode.LeftShift
            }
            '>' -> if (c2 == '>') {
                st.shiftNext()
                if (st.current != '=') {
                    throw LexError("\">\"は代入演算子として認識できません", lineNo = lineNo, column = st.currentPosition)
                }
                op = OperatorCode.RightShift
            }
            '|' -> if (c2 == '=') op = OperatorCode.BitOr
            '&' -> if (c2 == '=') op = OperatorCode.BitAnd
            '^' -> if (c2 == '=') op = OperatorCode.BitXor
        }
        if (op == OperatorCode.NULL) {
            throw LexError("\"$c1\"は代入演算子として認識できません", lineNo = lineNo, column = st.currentPosition)
        }
        st.shiftNext()
        return op
    }

    // ------------------------------------------------------------------
    // 空白
    // ------------------------------------------------------------------

    /** spec: LexicalAnalyzer.cs:622-636 SkipAllSpace（仅空格/制表/全角空格） */
    fun skipAllSpace(st: CharStream): Int {
        var num = 0
        while (st.current == '\t' || st.current == ' ' || st.current == '\u3000') {
            num++
            st.shiftNext()
        }
        return num
    }

    /** spec: LexicalAnalyzer.cs:638-645 */
    fun isWhiteSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\u3000'

    /**
     * spec: LexicalAnalyzer.cs:647-690 SkipWhiteSpace
     * 跳过前导空白；遇到 ';' 注释分三种：
     *  ";#;"（仅 DebugMode）、";!;"、";^;" → 跳过 3 字符继续；
     *  其余 → Seek(End)，即整行剩余部分视为注释。
     */
    fun skipWhiteSpace(st: CharStream): Int {
        var num = 0
        while (true) {
            val c = st.current
            if (c.code <= 32) {
                if (c != '\t' && c != ' ') break
            } else {
                if (c == ';') {
                    if (config.debugMode && st.currentEqualTo(";#;")) {
                        st.jump(3)
                        continue
                    }
                    if (st.currentEqualTo(";!;") || st.currentEqualTo(";^;")) {
                        st.jump(3)
                        continue
                    }
                    st.seekEnd()
                    return num
                }
                if (c != '\u3000') break
                if (!config.systemAllowFullSpace) return num
            }
            num++
            st.shiftNext()
        }
        return num
    }

    /** spec: LexicalAnalyzer.cs:692-701 SkipHalfSpace */
    fun skipHalfSpace(st: CharStream): Int {
        var num = 0
        while (st.current == ' ') {
            num++
            st.shiftNext()
        }
        return num
    }

    // ------------------------------------------------------------------
    // 字符串
    // ------------------------------------------------------------------

    /**
     * spec: LexicalAnalyzer.cs:330-400 ReadString
     * 转义：\\s→' '、\\S→'\u3000'、\\t→'\t'、\\n→'\n'、\\<其他>→该字符本身、\\+\n→无、\\+\0→错误
     */
    fun readString(st: CharStream, endWith: StrEndWith, lineNo: Int): String {
        val buffer = StringBuilder(100)
        loop@ while (true) {
            when (val c = st.current) {
                CharStream.END_OF_STRING -> return buffer.toString()
                '"' -> if (endWith == StrEndWith.DoubleQuotation) return buffer.toString()
                '\'' -> if (endWith == StrEndWith.SingleQuotation) return buffer.toString()
                ',' -> if (endWith == StrEndWith.Comma || endWith == StrEndWith.LeftParenthesis_Bracket_Comma_Semicolon) return buffer.toString()
                '(', ';', '[' ->
                    if (endWith == StrEndWith.LeftParenthesis_Bracket_Comma_Semicolon) return buffer.toString()
                '\\' -> {
                    st.shiftNext()
                    when (val e = st.current) {
                        CharStream.END_OF_STRING -> throw LexError(
                            "エスケープ文字\\の後に文字がありません", lineNo = lineNo, column = st.currentPosition,
                        )
                        's' -> buffer.append(' ')
                        'S' -> buffer.append('\u3000')
                        't' -> buffer.append('\t')
                        'n' -> buffer.append('\n')
                        '\n' -> {} // 行内换行：不追加
                        else -> buffer.append(e)
                    }
                    st.shiftNext()
                    continue@loop
                }
            }
            buffer.append(st.current)
            st.shiftNext()
        }
    }

    // ------------------------------------------------------------------
    // 主分析循环
    // ------------------------------------------------------------------

    /**
     * spec: LexicalAnalyzer.cs:704-940 Analyse
     * 单逻辑行词法分析主入口。endWith 决定提前返回的结束条件，flag 为 LexAnalyzeFlag 位标志。
     */
    fun analyse(st: CharStream, endWith: LexEndWith, flag: Int, lineNo: Int): MutableList<Token> {
        val ret = mutableListOf<Token>()
        var nestBracketS = 0
        var nestBracketL = 0
        loop@ while (true) {
            when (st.current) {
                CharStream.END_OF_STRING, '\n' -> break@loop
                '\t', ' ' -> st.shiftNext()
                '\u3000' -> {
                    // spec: LexicalAnalyzer.cs:748-754
                    if (!config.systemAllowFullSpace) {
                        // {0} = Config.GetConfigName(SystemAllowFullSpace) = "Text/EngText"
                        // （spec: LexicalAnalyzer.cs:751 + Config.cs:291-294 + ConfigData.cs:204-214）
                        throw LexError(
                            "予期しない全角スペースを発見しました(この警告はシステムオプション「${config.systemAllowFullSpaceName}」により無視できます)",
                            lineNo = lineNo, column = st.currentPosition,
                        )
                    }
                    st.shiftNext()
                }
                in '0'..'9' -> {
                    val col = st.currentPosition
                    val v = readInt64(st, retZero = false, lineNo = lineNo)
                    ret.add(Token.Integer(v, lineNo, col))
                }
                '>', '!', '#', '%', '&', '*', '+', '-', '/', '<', '=', '?', '^', '|', '~' -> {
                    // spec: LexicalAnalyzer.cs:767-792
                    if (st.current == '>' && endWith == LexEndWith.GreaterThan) break@loop
                    if (nestBracketS == 0 && nestBracketL == 0 &&
                        (endWith == LexEndWith.Operator ||
                            (endWith == LexEndWith.Percent && st.current == '%') ||
                            (endWith == LexEndWith.Question && st.current == '?'))
                    ) break@loop
                    val col = st.currentPosition
                    val op = readOperator(st, flag and LexAnalyzeFlag.ALLOW_ASSIGNMENT != 0, lineNo)
                    ret.add(Token.Operator(op, lineNo, col))
                }
                ')' -> {
                    ret.add(Token.Symbol(')', lineNo, st.currentPosition))
                    nestBracketS--
                    st.shiftNext()
                }
                ']' -> {
                    ret.add(Token.Symbol(']', lineNo, st.currentPosition))
                    nestBracketL--
                    st.shiftNext()
                }
                '(' -> {
                    ret.add(Token.Symbol('(', lineNo, st.currentPosition))
                    nestBracketS++
                    st.shiftNext()
                }
                '[' -> {
                    // spec: LexicalAnalyzer.cs:808-831 —— "[[" 为替换名语法，在读取层（EraStreamReader）处理；
                    // Analyse 层无论 RenameDic 是否存在都会抛错，本移植统一按 UnexpectedCharacter 报错
                    if (st.next == '[') {
                        // spec: LexicalAnalyzer.cs:809-813 —— RenameDic == null 分支的实参是字面量 "[["
                        // （不是单个 '['）；本移植的替换名在读行层完成（同 EraStreamReader.cs:111-122），
                        // 到 Analyse 的 "[[..]]" 一律按「未注册替换名」报这一支，故实参照抄 "[["
                        throw LexError(
                            "字句解析中に予期しない文字'[['を発見しました", lineNo = lineNo, column = st.currentPosition,
                        )
                    }
                    ret.add(Token.Symbol('[', lineNo, st.currentPosition))
                    nestBracketL++
                    st.shiftNext()
                }
                ':' -> {
                    ret.add(Token.Symbol(':', lineNo, st.currentPosition))
                    st.shiftNext()
                }
                ',' -> {
                    // spec: LexicalAnalyzer.cs:836-843
                    if (endWith == LexEndWith.Comma && nestBracketS == 0) break@loop
                    ret.add(Token.Symbol(',', lineNo, st.currentPosition))
                    st.shiftNext()
                }
                '\'' -> {
                    // spec: LexicalAnalyzer.cs:844-870
                    if (flag and LexAnalyzeFlag.ALLOW_SINGLE_QUOTATION_STR != 0) {
                        st.shiftNext()
                        val col = st.currentPosition
                        val s = readString(st, StrEndWith.SingleQuotation, lineNo)
                        if (st.current != '\'') {
                            throw LexError("\"'\"が閉じられていません", lineNo = lineNo, column = st.currentPosition)
                        }
                        st.shiftNext()
                        ret.add(Token.StringLiteral(s, lineNo, col))
                    } else if (flag and LexAnalyzeFlag.ANALYZE_PRINT_V == 0) {
                        if (endWith == LexEndWith.Operator && nestBracketS == 0 && nestBracketL == 0 && st.next == '=') break@loop
                        throw LexError(
                            "字句解析中に予期しない文字'${st.current}'を発見しました", lineNo = lineNo, column = st.currentPosition,
                        )
                    } else {
                        st.shiftNext()
                        val col = st.currentPosition
                        val s = readString(st, StrEndWith.Comma, lineNo)
                        ret.add(Token.StringLiteral(s, lineNo, col))
                        if (st.current != ',') break@loop
                        // goto ',' case —— spec: LexicalAnalyzer.cs:866-870
                        if (endWith == LexEndWith.Comma && nestBracketS == 0) break@loop
                        ret.add(Token.Symbol(',', lineNo, st.currentPosition))
                        st.shiftNext()
                    }
                }
                '}' -> {
                    // spec: LexicalAnalyzer.cs:871-876
                    if (endWith == LexEndWith.RightCurlyBrace) break@loop
                    throw LexError(
                        "字句解析中に予期しない文字'${st.current}'を発見しました", lineNo = lineNo, column = st.currentPosition,
                    )
                }
                '"' -> {
                    // spec: LexicalAnalyzer.cs:877-885
                    st.shiftNext()
                    val col = st.currentPosition
                    val s = readString(st, StrEndWith.DoubleQuotation, lineNo)
                    if (st.current != '"') {
                        // NotClosed 模板是 "{0}"が閉じられていません（Lang.cs:3598），实参 = 一个半角双引号
                        // ⇒ 三连双引号；此前多写了一个 '\' 变成 "\"\"（照抄纪律见 LangKey 类头）
                        throw LexError("\"\"\"が閉じられていません", lineNo = lineNo, column = st.currentPosition)
                    }
                    st.shiftNext()
                    ret.add(Token.StringLiteral(s, lineNo, col))
                }
                '@' -> {
                    // spec: LexicalAnalyzer.cs:886-903
                    if (st.next != '"') {
                        ret.add(Token.Symbol('@', lineNo, st.currentPosition))
                        st.shiftNext()
                    } else {
                        val col = st.currentPosition
                        st.shiftNext()
                        st.shiftNext()
                        val form = analyseFormattedString(st, FormStrEndWith.DoubleQuotation, trim = false, lineNo)
                        if (st.current != '"') {
                            // 同 :536 —— NotClosed 的双引号形是三连双引号（Lang.cs:3598）
                            throw LexError("\"\"\"が閉じられていません", lineNo = lineNo, column = st.currentPosition)
                        }
                        st.shiftNext()
                        ret.add(Token.FormStr(form, lineNo, col))
                    }
                }
                '.' -> {
                    ret.add(Token.Symbol('.', lineNo, st.currentPosition))
                    st.shiftNext()
                }
                '\\' -> {
                    // spec: LexicalAnalyzer.cs:908-915 —— \@ 三项条件式
                    if (st.next != '@') {
                        throw LexError(
                            "字句解析中に予期しない文字'${st.current}'を発見しました", lineNo = lineNo, column = st.currentPosition,
                        )
                    }
                    val col = st.currentPosition
                    st.jump(2)
                    val form = StrForm(listOf("", ""), listOf(analyseYenAt(st, lineNo)))
                    ret.add(Token.FormStr(form, lineNo, col))
                }
                '$', '{' -> {
                    // spec: LexicalAnalyzer.cs:916-918
                    throw LexError(
                        "字句解析中に予期しない文字'${st.current}'を発見しました", lineNo = lineNo, column = st.currentPosition,
                    )
                }
                ';' -> {
                    // spec: LexicalAnalyzer.cs:919-933 —— 注意此处 ";^;" 不跳过（与 SkipWhiteSpace 不同）
                    if (st.currentEqualTo(";#;") && config.debugMode) {
                        st.jump(3)
                    } else {
                        if (!st.currentEqualTo(";!;")) {
                            st.seekEnd()
                            break@loop
                        }
                        st.jump(3)
                    }
                }
                else -> {
                    val col = st.currentPosition
                    val code = readSingleIdentifier(st)
                    ret.add(Token.Identifier(code, lineNo, col))
                }
            }
        }
        if (nestBracketS != 0 || nestBracketL != 0) {
            // spec: LexicalAnalyzer.cs:710-728
            if (nestBracketS < 0) throw LexError("'('に対応する')'が見つかりません", lineNo = lineNo)
            if (nestBracketS > 0) throw LexError("構文解析中に予期しない')'を発見しました", lineNo = lineNo)
            throw LexError("'['に対応する']'が見つかりません", lineNo = lineNo)
        }
        return expandMacros(ret, lineNo)
    }

    // ------------------------------------------------------------------
    // #DEFINE 宏展开（spec: LexicalAnalyzer.cs:942-1006 expandMacro）
    // ------------------------------------------------------------------

    /**
     * 把 token 流里的 [Token.Identifier] 按 [LexerConfig.macros] 原地展开。
     *
     * 只在**标识符 token** 上查表：字符串字面量、整数、运算符都不会被误伤
     * （C# 的 `expandMacro` 同样只遍历 `IdentifierWord`）。展开体用一份**不带宏表**的
     * 同配置词法器重新切分，再在同一个循环里继续查表 —— 等价于 C# 的递归展开，
     * 但把递归改成迭代，便于按 `num > 100` 的原始口径计上限（spec: :954-959）。
     *
     * 位置信息：展开体 token 沿用调用点的 `lineNo`（同一逻辑行）；列号保留展开体内的
     * 相对位置。报错定位到宏使用点所在行，与原版 `ScriptPosition` 的行粒度一致。
     *
     * CMP-37 修法（只改宏展开体的重切 flag，不动任何调用点 flag）：原版 `#DEFINE` 体在
     * **声明时**就用 `LexAnalyzeFlag.AllowAssignment` 解析成既成 `WordCollection`
     * （`ErhLoader.cs:166` → `DefineMacro.cs:22`），使用点是把这个**已解析 token 流**原地
     * 拼接进当前流（`LexicalAnalyzer.cs:967` `wc.InsertRange(macro.Statement)`），不再词法化。
     * 本实现存的是展开体原文、每次使用重切，因此重切 flag 必须与声明时口径一致，即
     * `ALLOW_ASSIGNMENT` —— 否则 `#DEFINE M A=1` 每次使用都抛「予期しない代入演算子」，
     * 而原版会把 `'='` 收成 Assignment 既成 token 拼给下游。
     *
     * 影响面（为何不伤普通表达式与语料）：`ALLOW_ASSIGNMENT` 在 `analyse` 主循环里唯一的
     * 消费点是 `readOperator(st, allowAssignment, ...)`（本文件 `flag and ALLOW_ASSIGNMENT`
     * 那处），两个 flag 的差异**只在裸 `'='`**：NONE 抛、ALLOW_ASSIGNMENT 产出
     * `OperatorCode.Assignment`；`'=='`、`'!='`、`'<='`、`'>='` 等全部走双字符分支，token
     * 流逐字节相同。语料 `#DEFINE` 共 25 处（`DIM.ERH:181/183`、`TEMP_CHARA_LIST_BY_DT.ERH`
     * 等），展开体实测 0 处含裸 `'='`（`A=1` 形态），故现有编译 token 流与读数不变；
     * 使用点自身的 flag 由各自调用方决定（如 `tokenizeLine` 已带 ALLOW_ASSIGNMENT），
     * 本改动不改变任何 `analyse(...)` 调用点的 flag。
     */
    private fun expandMacros(tokens: MutableList<Token>, lineNo: Int): MutableList<Token> {
        val macros = config.macros
        if (macros.isEmpty()) return tokens
        val lookup: Map<String, String> = if (config.macroIgnoreCase) {
            macros.entries.associate { it.key.uppercase(java.util.Locale.ROOT) to it.value }
        } else {
            macros
        }
        fun keyOf(name: String): String =
            if (config.macroIgnoreCase) name.uppercase(java.util.Locale.ROOT) else name

        var current: MutableList<Token> = tokens
        var expanded = 0
        while (true) {
            var changed = false
            val out = ArrayList<Token>(current.size)
            for (t in current) {
                val ident = t as? Token.Identifier
                val body = ident?.let { lookup[keyOf(it.code)] }
                if (ident == null || body == null) {
                    out.add(t)
                    continue
                }
                expanded++
                if (expanded > 100) {
                    // spec: LexicalAnalyzer.cs:954-959 / :960-963 —— 文案是 Lang.Error.MacroOverLimit
                    // = Lang.cs:3554，实参 = 100（Token.ToString 的十进制，不带单位）
                    throw LexError(
                        "マクロの展開数が1文あたりの上限値100を超えました(自己参照・循環参照のおそれ)",
                        lineNo = lineNo,
                    )
                }
                // CMP-37：flag 与原版声明时口径一致（ErhLoader.cs:166 AllowAssignment）
                val sub = ErbLexer(config.copy(macros = emptyMap()))
                    .analyse(CharStream(body), LexEndWith.EoL, LexAnalyzeFlag.ALLOW_ASSIGNMENT, lineNo)
                out.addAll(sub)
                changed = true
            }
            if (!changed) return out
            current = out
        }
    }

    /**
     * spec: LexicalAnalyzer.cs:1073-1229 AnalyseFormattedString
     * @"..."、DATAFORM 等格式化字符串。嵌入：%expr% / {expr} / 三连记号 / \@三项式。
     */
    fun analyseFormattedString(st: CharStream, endWith: FormStrEndWith, trim: Boolean, lineNo: Int): StrForm {
        val strs = mutableListOf<String>()
        val subwords = mutableListOf<SubWord>()
        val sb = StringBuilder(100)
        while (true) {
            val c = st.current
            var doBreak = false
            when (c) {
                // 结束字符不匹配时按普通字符追加（spec: LexicalAnalyzer.cs:1083-1112 走 IL_030a）
                '"' -> if (endWith == FormStrEndWith.DoubleQuotation) doBreak = true else sb.append(c)
                '#' -> if (endWith == FormStrEndWith.Sharp) doBreak = true else sb.append(c)
                ',' -> if (endWith == FormStrEndWith.Comma || endWith == FormStrEndWith.LeftParenthesis_Bracket_Comma_Semicolon) doBreak = true else sb.append(c)
                '(', ';', '[' ->
                    if (endWith == FormStrEndWith.LeftParenthesis_Bracket_Comma_Semicolon) doBreak = true else sb.append(c)
                '%' -> {
                    // spec: LexicalAnalyzer.cs:1113-1122
                    strs.add(sb.toString())
                    sb.setLength(0)
                    st.shiftNext()
                    val words = analyse(st, LexEndWith.Percent, LexAnalyzeFlag.NONE, lineNo)
                    if (st.current != '%') {
                        throw LexError("'%'が使われましたが対応する'%'が見つかりません", lineNo = lineNo, column = st.currentPosition)
                    }
                    subwords.add(PercentSubWord(words))
                }
                '{' -> {
                    // spec: LexicalAnalyzer.cs:1123-1132
                    strs.add(sb.toString())
                    sb.setLength(0)
                    st.shiftNext()
                    val words = analyse(st, LexEndWith.RightCurlyBrace, LexAnalyzeFlag.NONE, lineNo)
                    if (st.current != '}') {
                        throw LexError("'{'が使われましたが対応する'}'が見つかりません", lineNo = lineNo, column = st.currentPosition)
                    }
                    subwords.add(CurlyBraceSubWord(words))
                }
                '$', '*', '+', '/', '=' -> {
                    // spec: LexicalAnalyzer.cs:1133-1147
                    if (!config.systemIgnoreTripleSymbol && st.tripleSymbol()) {
                        strs.add(sb.toString())
                        sb.setLength(0)
                        st.jump(3)
                        subwords.add(TripleSymbolSubWord(c))
                        continue
                    }
                    sb.append(c)
                }
                '\\' -> {
                    // spec: LexicalAnalyzer.cs:1148-1201
                    st.shiftNext()
                    val e = st.current
                    when {
                        e == CharStream.END_OF_STRING -> throw LexError(
                            "エスケープ文字\\の後に文字がありません", lineNo = lineNo, column = st.currentPosition,
                        )
                        e == '\n' -> {} // 不追加，随后统一 shift
                        e.code <= 64 -> {
                            if (e == '@') {
                                if (endWith != FormStrEndWith.YenAt && endWith != FormStrEndWith.Sharp) {
                                    strs.add(sb.toString())
                                    sb.setLength(0)
                                    st.shiftNext()
                                    subwords.add(analyseYenAt(st, lineNo))
                                    continue
                                }
                                doBreak = true // \@ 是结束符
                            } else {
                                sb.append(e)
                            }
                        }
                        e == 'S' -> sb.append('\u3000')
                        e == 'n' -> sb.append('\n')
                        e == 's' -> sb.append(' ')
                        e == 't' -> sb.append('\t')
                        else -> sb.append(e)
                    }
                }
                CharStream.END_OF_STRING, '\n' -> doBreak = true
                else -> sb.append(c)
            }
            if (doBreak) break
            st.shiftNext()
        }
        strs.add(sb.toString())
        if (trim && strs.isNotEmpty()) {
            // spec: LexicalAnalyzer.cs:1223-1227
            strs[0] = strs[0].trimStart(' ', '\t')
            strs[strs.size - 1] = strs[strs.size - 1].trimEnd(' ', '\t')
        }
        return StrForm(strs, subwords)
    }

    /**
     * spec: LexicalAnalyzer.cs:1231-1258 AnalyseYenAt
     * \@条件?左#右\@ 三项条件式。缺少 # 分支时 right 为 null（发出警告）。
     */
    fun analyseYenAt(st: CharStream, lineNo: Int): YenAtSubWord {
        val condition = analyse(st, LexEndWith.Question, LexAnalyzeFlag.NONE, lineNo)
        if (st.current != '?') {
            throw LexError("'\\@'が使われましたが対応する'?'が見つかりません", lineNo = lineNo, column = st.currentPosition)
        }
        st.shiftNext()
        val fsLeft = analyseFormattedString(st, FormStrEndWith.Sharp, trim = true, lineNo)
        if (st.current != '#') {
            if (st.current != '@') {
                throw LexError("'\\@'が使われましたが対応する'#'が見つかりません", lineNo = lineNo, column = st.currentPosition)
            }
            st.shiftNext()
            warn("'\\@'が使われましたが対応する'#'が見つかりません", "", lineNo, 1)
            return YenAtSubWord(condition, fsLeft, null)
        }
        st.shiftNext()
        val fsRight = analyseFormattedString(st, FormStrEndWith.YenAt, trim = true, lineNo)
        if (st.current != '@') {
            throw LexError("'\\@','?','#'が使われましたが対応する'\\@'が見つかりません", lineNo = lineNo, column = st.currentPosition)
        }
        st.shiftNext()
        return YenAtSubWord(condition, fsLeft, fsRight)
    }

    // ------------------------------------------------------------------
    // 行级接口
    // ------------------------------------------------------------------

    /**
     * 普通代码行整行分析。
     * 注意：原版 ParseLine 对赋值行采用两段式（Analyse(Operator 截断) + ReadAssignmentOperator），
     * 见 LogicalLineParser.cs:536-556；语句层解析者应复刻该流程，本方法仅供调试/全行 Token 流使用。
     * TODO(verify): 全行分析使用 AllowAssignment 标志使 '=' 作为 Assignment 运算符通过，与原版 ParseLine 的 None 标志不同。
     */
    fun tokenizeLine(content: String, lineNo: Int): List<Token> {
        val st = CharStream(content)
        return analyse(st, LexEndWith.EoL, LexAnalyzeFlag.ALLOW_ASSIGNMENT, lineNo)
    }

    /**
     * '$' / '@' 行分析 —— spec: LogicalLineParser.cs:314-383 ParseLabelLine 的词法部分。
     * 返回（是否函数行, 标签名, 其余 Token）。
     */
    fun parseLabelLine(content: String, lineNo: Int, filename: String): Triple<Boolean, String, List<Token>> {
        val isFunction = content.startsWith("@")
        val st = CharStream(content)
        st.shiftNext() // 跳过 '@' 或 '$'
        val tokens = analyse(st, LexEndWith.EoL, LexAnalyzeFlag.ALLOW_ASSIGNMENT, lineNo)
        if (tokens.isEmpty() || tokens[0] !is Token.Identifier) {
            throw LexError("関数名が不正であるか存在しません", filename = filename, lineNo = lineNo)
        }
        val name = (tokens[0] as Token.Identifier).code
        if (!isFunction && tokens.size > 1) {
            // spec: LogicalLineParser.cs:341-343 —— $ 标签不允许参数
            warn("\$で始まるラベルに引数が設定されています", filename, lineNo, 1)
        }
        return Triple(isFunction, name, tokens.drop(1))
    }

    /**
     * '#' 行分析 —— spec: LogicalLineParser.cs:20-307 ParseSharpLine 的词法部分。
     * 返回（关键字, 其余 Token）。LOCALSIZE/LOCALSSIZE/DIM/DIMS 会带 Token，
     * SINGLE/LATER/PRI/ONLY/FUNCTION/FUNCTIONS 只有关键字（其余为语义校验，不在此实现）。
     */
    fun parseSharpLine(content: String, lineNo: Int, filename: String): Pair<String, List<Token>> {
        val st = CharStream(content)
        st.shiftNext() // 跳过 '#'
        val keyword = readSingleIdentifier(st)
        if (keyword.isEmpty()) {
            throw LexError("解釈できない#行です", filename = filename, lineNo = lineNo)
        }
        return when {
            keyword.equals("LOCALSIZE", ignoreCase = true) ||
                keyword.equals("LOCALSSIZE", ignoreCase = true) ||
                keyword.equals("DIM", ignoreCase = true) ||
                keyword.equals("DIMS", ignoreCase = true) ->
                // spec: LogicalLineParser.cs:220 / 281 —— Analyse(EoL, AllowAssignment)
                Pair(keyword, analyse(st, LexEndWith.EoL, LexAnalyzeFlag.ALLOW_ASSIGNMENT, lineNo))
            else -> Pair(keyword, emptyList())
        }
    }
}
