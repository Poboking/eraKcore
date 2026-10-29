package com.eratw.port.core.lexer

/**
 * Token 模型：type + text + 行号 + 列号。
 * 行号为逻辑行的 0 基行号（与原版 ScriptPosition 一致，spec: EraStreamReader.cs:30）；
 * 列号为 Token 首字符在该逻辑行 content 字符串中的 0 基偏移（原版 Word 无位置信息，此为调试增强）。
 */
sealed class Token {
    abstract val type: TokenType
    abstract val text: String
    abstract val line: Int
    abstract val col: Int

    /** IdentifierWord */
    data class Identifier(
        val code: String,
        override val line: Int,
        override val col: Int,
    ) : Token() {
        override val type get() = TokenType.IDENTIFIER
        override val text get() = code
    }

    /** LiteralIntegerWord */
    data class Integer(
        val value: Long,
        override val line: Int,
        override val col: Int,
    ) : Token() {
        override val type get() = TokenType.INTEGER
        override val text get() = value.toString()
    }

    /** LiteralStringWord */
    data class StringLiteral(
        val value: String,
        override val line: Int,
        override val col: Int,
    ) : Token() {
        override val type get() = TokenType.STRING
        override val text get() = "\"$value\""
    }

    /** OperatorWord */
    data class Operator(
        val code: OperatorCode,
        override val line: Int,
        override val col: Int,
    ) : Token() {
        override val type get() = TokenType.OPERATOR
        override val text get() = code.name
    }

    /** SymbolWord：单个分隔符字符 ( ) [ ] : , . @ */
    data class Symbol(
        val char: Char,
        override val line: Int,
        override val col: Int,
    ) : Token() {
        override val type get() = TokenType.SYMBOL
        override val text get() = char.toString()
    }

    /** StrFormWord：@"..." 格式化字符串或 \@ 三项条件式整体 */
    data class FormStr(
        val form: StrForm,
        override val line: Int,
        override val col: Int,
    ) : Token() {
        override val type get() = TokenType.STR_FORM
        override val text: String get() = "<FORM>"
    }
}

/**
 * SubWord 层级 —— 格式化字符串内部的嵌入结构。
 * spec: SubWord.cs / CurlyBraceSubWord.cs / PercentSubWord.cs / TripleSymbolSubWord.cs / YenAtSubWord.cs
 */
sealed class SubWord

/** {表达式} 嵌入 —— spec: CurlyBraceSubWord.cs */
class CurlyBraceSubWord(val words: List<Token>) : SubWord()

/** %表达式% 嵌入 —— spec: PercentSubWord.cs */
class PercentSubWord(val words: List<Token>) : SubWord()

/** 三连记号 $$$ *** /// === +++ —— spec: TripleSymbolSubWord.cs */
class TripleSymbolSubWord(val code: Char) : SubWord()

/** \@条件?左#右\@ 三项条件式 —— spec: YenAtSubWord.cs（right 可为 null：缺 # 分支时） */
class YenAtSubWord(
    val condition: List<Token>,
    val left: StrForm,
    val right: StrForm?,
) : SubWord()

/**
 * 格式化字符串：常量片段与嵌入子词交替。
 * spec: StrFormWord.cs —— strs.size == subWords.size + 1
 */
class StrForm(val strs: List<String>, val subWords: List<SubWord>) {
    override fun toString(): String = "<FORM>"
}
