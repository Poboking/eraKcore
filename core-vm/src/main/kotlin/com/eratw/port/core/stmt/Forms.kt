package com.eratw.port.core.stmt

import com.eratw.port.core.lexer.CharStream
import com.eratw.port.core.lexer.CurlyBraceSubWord
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.FormStrEndWith
import com.eratw.port.core.lexer.LexAnalyzeFlag
import com.eratw.port.core.lexer.LexEndWith
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.PercentSubWord
import com.eratw.port.core.lexer.StrForm
import com.eratw.port.core.lexer.SubWord
import com.eratw.port.core.lexer.TripleSymbolSubWord

/**
 * 格式化字符串解析（**到行尾**语义）—— 移植 AnalyseFormattedString 的 `FormStrEndWith.EoL` 分支
 * （spec: LexicalAnalyzer.cs:1073-1230）。
 *
 * ## 为什么单独实现
 * core.lexer 的 [FormStrEndWith] 枚举缺少 `EoL` 值（原版 FormStrEndWith.cs:3-12 有），
 * 只有 DoubleQuotation / Sharp / YenAt / Comma / LeftParenthesis_Bracket_Comma_Semicolon。
 * 语句层 3 处需要「整个剩余行都是 FORM 串」的语义：
 * PRINTFORM 家族、字符串变量 `'=` / `=` 赋值、PUTFORM。
 * 若退化为 Comma 结束，`PRINTFORM 色{A},{B}` 会在第一个逗号处被截断（静默错解）；
 * 因此此处按 EoL 语义本地实现，其余子词（%…% / {…} / 三连记号 / \@…\@）仍调用词法器。
 *
 * TODO(verify): 建议 core.lexer 补上 `FormStrEndWith.EoL`（枚举值 + 该分支不设终止字符），
 * 届时本文件可删除。
 */
internal fun analyseFormToEol(
    lexer: ErbLexer,
    st: CharStream,
    lineNo: Int,
    trim: Boolean,
    /**
     * FORM 内是否**不**展开三连记号（原版 `Config.SystemIgnoreTripleSymbol`）。
     * 默认取本词法器自己的开关，保证与 [ErbLexer.analyseFormattedString]
     * （spec: LexicalAnalyzer.cs:1138）同口径；语句层可通过 ArgCtx 覆写。
     */
    ignoreTripleSymbol: Boolean = lexer.ignoreTripleSymbol,
): StrForm {
    val strs = mutableListOf<String>()
    val subWords = mutableListOf<SubWord>()
    val sb = StringBuilder(100)
    loop@ while (true) {
        val c = st.current
        when (c) {
            '%' -> {
                // spec: LexicalAnalyzer.cs:1113-1122
                strs.add(sb.toString())
                sb.setLength(0)
                st.shiftNext()
                subWords.add(PercentSubWord(lexer.analyse(st, LexEndWith.Percent, LexAnalyzeFlag.NONE, lineNo)))
                if (st.current != '%') {
                    throw LexError("'%'が使われましたが対応する'%'が見つかりません", lineNo = lineNo, column = st.currentPosition)
                }
            }
            '{' -> {
                // spec: LexicalAnalyzer.cs:1123-1132
                strs.add(sb.toString())
                sb.setLength(0)
                st.shiftNext()
                subWords.add(CurlyBraceSubWord(lexer.analyse(st, LexEndWith.RightCurlyBrace, LexAnalyzeFlag.NONE, lineNo)))
                if (st.current != '}') {
                    throw LexError("'{'が使われましたが対応する'}'が見つかりません", lineNo = lineNo, column = st.currentPosition)
                }
            }
            '$', '*', '+', '/', '=' -> {
                // spec: LexicalAnalyzer.cs:1133-1147 —— 开关关闭时不展开，按普通字符累加
                if (!ignoreTripleSymbol && st.tripleSymbol()) {
                    strs.add(sb.toString())
                    sb.setLength(0)
                    st.jump(3)
                    subWords.add(TripleSymbolSubWord(c))
                    continue@loop
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
                    e == '\n' -> {} // 不追加
                    e == '@' -> {
                        // EoL 语义下 \@ 一定开启三项条件式（endWith != YenAt/Sharp）
                        strs.add(sb.toString())
                        sb.setLength(0)
                        st.shiftNext()
                        subWords.add(lexer.analyseYenAt(st, lineNo))
                        continue@loop
                    }
                    e.code <= 64 -> sb.append(e)
                    e == 'S' -> sb.append('\u3000')
                    e == 'n' -> sb.append('\n')
                    e == 's' -> sb.append(' ')
                    e == 't' -> sb.append('\t')
                    else -> sb.append(e)
                }
            }
            CharStream.END_OF_STRING, '\n' -> break@loop
            else -> sb.append(c)
        }
        st.shiftNext()
    }
    strs.add(sb.toString())
    if (trim && strs.isNotEmpty()) {
        // spec: LexicalAnalyzer.cs:1223-1227
        strs[0] = strs[0].trimStart(' ', '\t')
        strs[strs.size - 1] = strs[strs.size - 1].trimEnd(' ', '\t')
    }
    return StrForm(strs, subWords)
}
