package com.eratw.port.core.lexer

/**
 * 词法错误 —— 对应原版 CodeEE（LexicalAnalyzer 抛出的解析失败）。
 *
 * message 使用与原版 `Lang.Error` 相同的日文文案（含 `{0}` 占位符已展开）：
 * 内核报错保日文原文，口径见 [LangKey] 类头的第 1 条。
 *
 * 两种构造方式并存：
 * 1. `LexError(message: String, …)` —— 主构造，调用方自带成品串（现存 43 处 throw 原样不动）；
 * 2. `LexError(key: LangKey, vararg args)` —— 官方键 + 实参，message 由 [LangKey.renderJa] 展开，
 *    并把 key/args 留在字段上供上层重渲染或按语言切换。
 * 需要带词法位置的键构造用 [at]。
 *
 * @property filename 源文件名
 * @property lineNo 0 基行号（原版 `ScriptPosition.LineNo` 同为 0 基）
 * @property column 0 基列号
 * @property key 官方键；null = 调用方成品串
 * @property args 展开 [key] 的实参（长度 = [LangKey.argCount]）
 */
class LexError(
    message: String,
    val filename: String? = null,
    /** 原版 ScriptPosition.LineNo 为 0 基（EraStreamReader.LineNo => curNo），此处保持一致 */
    val lineNo: Int = -1,
    val column: Int = -1,
    val key: LangKey? = null,
    val args: List<Any?> = emptyList(),
) : RuntimeException(message) {

    /** 官方键构造：message 取日文原文模板展开。 */
    constructor(key: LangKey, vararg args: Any?) : this(
        key.renderJa(*args),
        key = key,
        args = args.toList(),
    )

    companion object {
        /** 官方键 + 词法位置（LexicalAnalyzer 接线用；行号/列号仍是 0 基）。 */
        fun at(
            key: LangKey,
            filename: String?,
            lineNo: Int,
            column: Int,
            vararg args: Any?,
        ): LexError = LexError(key.renderJa(*args), filename, lineNo, column, key, args.toList())
    }
}

/**
 * 非致命警告（对应 ParserMediator.Warn）。
 *
 * 与 [LexError] 同口径：message 是日文原文；官方键路径用 [at] 构造，键与实参留在字段上。
 *
 * @property key 官方键；null = 调用方成品串
 * @property args 展开 [key] 的实参（长度 = [LangKey.argCount]）
 */
data class LexWarning(
    val message: String,
    val filename: String,
    val lineNo: Int,
    val level: Int,
    val key: LangKey? = null,
    val args: List<Any?> = emptyList(),
) {
    companion object {
        /** 官方键 + 位置（LexicalAnalyzer 接线用）。 */
        fun at(
            key: LangKey,
            filename: String,
            lineNo: Int,
            level: Int,
            vararg args: Any?,
        ): LexWarning = LexWarning(key.renderJa(*args), filename, lineNo, level, key, args.toList())
    }
}
