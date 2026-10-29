package com.eratw.port.core.lexer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * 逻辑行 —— 一个物理行，或 {}/} 行连续块合并后的整体。
 */
data class LogicalLine(
    val kind: LineKind,
    val filename: String,
    /** 逻辑行的 0 基行号；{} 块时为闭合 '}' 所在物理行号（与原版一致，spec: ErbLoader.cs:389） */
    val lineNo: Int,
    /** 行内容：从第一个非空白字符开始（含 '@' '$' '#' 前导标记） */
    val content: String,
)

/**
 * 预处理状态 —— spec: ErbLoader.cs:25-181 PPState
 *
 * @param macroNames `#DEFINE` 宏名集合（大小写不敏感，内部按 [Locale.ROOT] 归一）。
 *   空集 = 宏表未提供：此时 `[IF 名]` 恒按「未定义」处理，与原版
 *   `IdentifierDictionary.GetMacro(token2) == null`（ErbLoader.cs:94）在「宏表确实没有该名」时同值。
 *   宏表由调用方给出（ERH 全量装载先于 ERB，ErbLoader.cs:40-75）。
 */
class PreprocessorState(
    private val debugMode: Boolean,
    macroNames: Set<String> = emptySet(),
    private val warn: (String) -> Unit,
) {
    /** 归一后的宏名集合（原版 GetMacro 用 `Config.StrComper` = OrdinalIgnoreCase，Config.cs:299-308）。 */
    private val macroKeys: Set<String> = macroNames.mapTo(HashSet()) { it.uppercase(Locale.ROOT) }

    private var skip = false
    private var done = false

    /** 当前块是否禁用（SKIPSTART / 未命中的 IF 分支内） */
    var disabled: Boolean = false
        private set

    private val disabledStack = ArrayDeque<Boolean>()
    private val doneStack = ArrayDeque<Boolean>()
    private val ppMatch = ArrayDeque<String>()

    /**
     * spec: ErbLoader.cs:39-167 AddKeyWord
     * 支持的关键字：SKIPSTART SKIPEND IF_DEBUG IF_NDEBUG IF ELSEIF ELSE ENDIF
     */
    fun addKeyword(token: String, token2: String) {
        when (token) {
            "SKIPSTART" -> {
                if (token2.isNotEmpty()) { warn("\"$token\"に余分な引数があります"); return }
                if (skip) { warn("[SKIPSTART]が重複して使用されています"); return }
                ppMatch.addLast("SKIPEND")
                disabledStack.addLast(disabled)
                doneStack.addLast(done)
                skip = true
                disabled = true
                done = false
            }
            "IF_DEBUG" -> {
                if (token2.isNotEmpty()) { warn("\"$token\"に余分な引数があります"); return }
                ppMatch.addLast("ELSEIF")
                disabledStack.addLast(disabled)
                doneStack.addLast(done)
                disabled = !debugMode
                done = !disabled
            }
            "IF_NDEBUG" -> {
                if (token2.isNotEmpty()) { warn("\"$token\"に余分な引数があります"); return }
                ppMatch.addLast("ELSEIF")
                disabledStack.addLast(disabled)
                doneStack.addLast(done)
                disabled = debugMode
                done = !disabled
            }
            "IF" -> {
                if (token2.isEmpty()) { warn("\"$token\"に引数がありません"); return }
                ppMatch.addLast("ELSEIF")
                disabledStack.addLast(disabled)
                doneStack.addLast(done)
                // spec: ErbLoader.cs:94 / :109 —— Disabled = GetMacro(token2) == null
                disabled = !isMacroDefined(token2)
                done = !disabled
            }
            "ELSEIF" -> {
                if (token2.isEmpty()) { warn("\"$token\"に引数がありません"); return }
                if (ppMatch.isEmpty() || ppMatch.removeLast() != "ELSEIF") {
                    warn("不適切な[ELSEIF]です"); return
                }
                ppMatch.addLast("ELSEIF")
                disabled = done || !isMacroDefined(token2)
                done = done || !disabled
            }
            "ELSE" -> {
                if (token2.isNotEmpty()) { warn("\"$token\"に余分な引数があります"); return }
                if (ppMatch.isEmpty() || ppMatch.removeLast() != "ELSEIF") {
                    warn("不適切な[ELSE]です"); return
                }
                ppMatch.addLast("ENDIF")
                disabled = done
                done = true
            }
            "SKIPEND" -> {
                if (token2.isNotEmpty()) { warn("\"$token\"に余分な引数があります"); return }
                if ((if (ppMatch.isEmpty()) "" else ppMatch.removeLast()) != "SKIPEND") {
                    warn("[SKIPSTART]と対応しない[SKIPEND]です"); return
                }
                skip = false
                disabled = disabledStack.removeLast()
                done = doneStack.removeLast()
            }
            "ENDIF" -> {
                if (token2.isNotEmpty()) { warn("\"$token\"に余分な引数があります"); return }
                val text = if (ppMatch.isEmpty()) "" else ppMatch.removeLast()
                if (text != "ENDIF" && text != "ELSEIF") {
                    warn("対応する[IF]のない[ENDIF]です"); return
                }
                disabled = disabledStack.removeLast()
                done = doneStack.removeLast()
            }
            else -> warn("認識できないプリプロセッサです")
        }
        if (skip) disabled = true
    }

    /** spec: ErbLoader.cs:94/109 —— `IdentifierDictionary.GetMacro(token2) == null`（大小写不敏感）。 */
    private fun isMacroDefined(token: String): Boolean = token.uppercase(Locale.ROOT) in macroKeys

    /** spec: ErbLoader.cs:169-180 FileEnd */
    fun fileEnd() {
        if (ppMatch.isNotEmpty()) {
            var text = ppMatch.removeLast()
            if (text == "ELSEIF") text = "ENDIF"
            warn("[$text]がありません")
        }
    }
}

/**
 * 逻辑行读取器 —— 移植自 EraStreamReader.ReadEnabledLine（spec: EraStreamReader.cs:96-187）
 * 与 ErbLoader.loadErb 的行分类逻辑（spec: ErbLoader.cs:388-427）。
 *
 * 用法：
 * ```
 * val reader = LogicalLineReader(LogicalLineReader.readAllLines(bytes), "test.erb")
 * while (val line = reader.readLogicalLine()) { ... }
 * reader.fileEnd()
 * ```
 */
class LogicalLineReader(
    physicalLines: List<String>,
    val filename: String = "<erb>",
    private val config: LexerConfig = LexerConfig(),
    /** 置換名辞書（key 为 "[[名]]" 全形）—— spec: EraStreamReader.cs:106-119 useRename 分支 */
    private val renameDic: Map<String, String>? = null,
    /** ERH `#DEFINE` 宏名集合（`[IF 宏]` / `[ELSEIF 宏]` 判定用，spec: ErbLoader.cs:94/109）。 */
    macroNames: Set<String> = emptySet(),
) {
    private val lexer = ErbLexer(config)
    /**
     * 首物理行只剥**一个**前导 BOM：原版先读 3 字节判 `EF BB BF` 得 UTF8BOMEncoding
     * （EncodingHandler.cs:20-26 / Preload.cs:27-31），再由 StreamReader 吞掉**该一个**前导
     * preamble；第二个 BOM 会作为普通字符 U+FEFF 留在行首（全量语料 3,971 文件里双 BOM 0 例）。
     * 位于首行中间或后续行中的 U+FEFF 一律保留。
     */
    private val lines: List<String> = physicalLines.mapIndexed { i, s -> if (i == 0) s.removePrefix("\uFEFF") else s }

    private var curNo = 0
    private var nextNo = 0

    /** 非致命警告（预处理行解析等） */
    val warnings = mutableListOf<LexWarning>()

    private val pp = PreprocessorState(config.debugMode, macroNames) { msg ->
        warnings.add(LexWarning(msg, filename, curNo, 1))
    }

    /** 当前物理行号（0 基，与原版 EraStreamReader.LineNo 一致，spec: EraStreamReader.cs:30） */
    val lineNo: Int get() = curNo

    /** spec: EraStreamReader.cs:77-87 ReadLine */
    private fun readLine(): String? {
        curNo = nextNo
        if (lines.size > curNo) {
            var result = lines[curNo]
            // fix: 对照 EraStreamReader.cs:106-119 —— useRename 时对物理行做 [[置換名]] 替换
            // （{} 块续行也走同一 readLine，对齐原版 match2 分支 EraStreamReader.cs:152-161）
            if (renameDic != null && result.contains("[[")) {
                for (m in RENAME_REGEX.findAll(result)) {
                    renameDic[m.value]?.let { v -> result = result.replace(m.value, v) }
                }
            }
            nextNo++
            return result
        }
        return null
    }

    /**
     * 读取下一条逻辑行。文件结束返回 null。
     * 空行、纯空白行、整行注释（';' 开头被 SkipWhiteSpace 归约的行）被跳过。
     */
    fun readLogicalLine(): LogicalLine? {
        while (true) {
            val text = readLine() ?: return null
            if (text.isEmpty()) continue // spec: EraStreamReader.cs:107-110
            var st = CharStream(text)
            lexer.skipWhiteSpace(st)
            if (st.eos) continue // 空白/注释行归约，spec: EraStreamReader.cs:124-129

            // 行连续块 { } —— spec: EraStreamReader.cs:130-184。
            // 这一段在 ReadEnabledLine 内、且**只在 !disabled 时**生效（EraStreamReader.cs:130），
            // 故必须排在 ErbLoader 的 `[` 判定之前（ErbLoader.cs:387-390 —— `[` 判定吃的是
            // ReadEnabledLine 返回的流，合并后的内容也照判）。
            if (!pp.disabled) {
                if (st.current == '}') {
                    throw LexError("予期しない行連結終端記号'}'が見つかりました", filename = filename, lineNo = curNo)
                }
                if (st.current == '{') {
                    // spec: EraStreamReader.cs:136-141 —— '{' 行必须是单独的 '{'
                    if (text.trim() != "{") {
                        throw LexError("行連結始端記号'{'の行に'{'以外の文字を含めることはできません", filename = filename, lineNo = curNo)
                    }
                    val sb = StringBuilder()
                    while (true) {
                        val t = readLine() ?: throw LexError(
                            "行連結始端記号'{'が使われましたが終端記号'}'が見つかりません", filename = filename, lineNo = curNo,
                        )
                        val span = t.trimStart()
                        if (span.isNotEmpty()) {
                            if (span[0] == '}') {
                                if (span.trim() == "}") break
                                throw LexError("行連結終端記号'}'の行に'}'以外の文字を含めることはできません", filename = filename, lineNo = curNo)
                            }
                            if (span == "{") {
                                throw LexError("予期しない行連結始端記号'{'が見つかりました", filename = filename, lineNo = curNo)
                            }
                        }
                        sb.append(t)
                        // spec: EraStreamReader.cs:179 —— ReplaceContinuationBR 去掉 '"'
                        sb.append(config.replaceContinuationBR.replace("\"", ""))
                    }
                    st = CharStream(sb.toString())
                    lexer.skipWhiteSpace(st)
                    // 块内全是空白：原版 ReadEnabledLine 照样把 EOS 流交回（EraStreamReader.cs:181-183），
                    // ErbLoader 走 ParseLine，后者 EOS 分支 return null（LogicalLineParser.cs:387-391）
                    // ⇒ 不产逻辑行。此前直接 classify 会多吐一条 content='' 的 CODE 行。
                    if (st.eos) continue
                }
            }

            // 预处理行 [...] —— spec: ErbLoader.cs:390-406（无论 disabled 与否都处理，
            // 且对 {'}'/'{'} 合并后的内容同样生效，见上）
            if (st.current == '[' && st.next != '[') {
                st.shiftNext()
                val keyword = lexer.readSingleIdentifier(st)
                lexer.skipWhiteSpace(st)
                val token2 = lexer.readSingleIdentifier(st)
                if (keyword.isEmpty() || st.current != ']') {
                    warnings.add(LexWarning("[]の使い方が不正です", filename, curNo, 1))
                }
                pp.addKeyword(keyword, token2)
                st.shiftNext()
                if (!st.eos) {
                    warnings.add(LexWarning("[$keyword]の後ろは無視されます。", filename, curNo, 1))
                }
                continue
            }

            if (pp.disabled) continue // spec: ErbLoader.cs:408-411
            return classify(st.rowString, st)
        }
    }

    /** 行分类 —— spec: ErbLoader.cs:388-481 */
    private fun classify(text: String, st: CharStream): LogicalLine {
        val content = if (st.currentPosition >= text.length) "" else text.substring(st.currentPosition)
        val kind = when (st.current) {
            '#' -> LineKind.SHARP
            '@' -> LineKind.FUNCTION_LABEL
            '$' -> LineKind.GOTO_LABEL
            else -> LineKind.CODE
        }
        return LogicalLine(kind, filename, curNo, content)
    }

    /** 文件末尾检查未闭合的预处理块（spec: ErbLoader.cs:513） */
    fun fileEnd() = pp.fileEnd()

    companion object {
        /** spec: EraStreamReader.cs:93 —— regexRenameIdentifer：\[\[.*?\]\]（惰性匹配） */
        private val RENAME_REGEX = Regex("""\[\[.*?\]\]""")

        /**
         * 文件字节 → 物理行列表。
         *
         * BOM 探测顺序照 .NET `StreamReader.DetectEncoding`
         * （spec: EncodingHandler.cs:15-41）：UTF-8 BOM 由 `DetectEncoding` 头 3 字节先判
         * （`EncodingHandler.cs:20-26`），其余 BOM 由 `new StreamReader(path, UTF8Encoding)` 的
         * `detectEncodingFromByteOrderMarks` 判（`EncodingHandler.cs:27-32`）——
         * 顺序是 FF FE 00 00（UTF-32 LE）→ FF FE（UTF-16 LE）→ FE FF（UTF-16 BE）→ 00 00 FE FF（UTF-32 BE）。
         * 无 BOM：严格 UTF-8 校验，失败回退 CP932（windows-31j，spec: EncodingHandler.cs:37-40）。
         *
         * TODO(verify): 原版 CP932 解码器是 `DecoderFallback.ExceptionFallback`
         * （`EncodingHandler.cs:67-71`），坏字节会抛 → `EraStreamReader.Open` 吞成 false
         * → **整文件跳过**（`EraStreamReader.cs:50-59`）。本函数无「读失败」回传通道，
         * 回退档仍是 REPLACE；语料 3,818 个 `.ERB` 实测严格 CP932 失败 0 例（不影响读数）。
         */
        fun readAllLines(bytes: ByteArray): List<String> {
            val text: String = when {
                // UTF-8 BOM（EF BB BF）—— EncodingHandler.cs:20-26
                bytes.size >= 3 &&
                    bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                    decode(bytes.copyOfRange(3, bytes.size))
                // UTF-32 LE BOM（FF FE 00 00）—— 必须先于 UTF-16 LE 判（前缀 FF FE 与后者重叠）
                bytes.size >= 4 &&
                    bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
                    bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte() ->
                    String(bytes, 4, bytes.size - 4, charset("UTF-32LE"))
                // UTF-16 LE BOM（FF FE）
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                // UTF-16 BE BOM（FE FF）
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                // UTF-32 BE BOM（00 00 FE FF）
                bytes.size >= 4 &&
                    bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
                    bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte() ->
                    String(bytes, 4, bytes.size - 4, charset("UTF-32BE"))
                else -> decode(bytes)
            }
            return splitLines(text)
        }

        private fun decode(body: ByteArray): String {
            return try {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                decoder.decode(ByteBuffer.wrap(body)).toString()
            } catch (e: CharacterCodingException) {
                // spec: EncodingHandler.cs:37-40 —— 非 UTF-8 回退 CP932；Java 侧用
                // "windows-31j"（= CP932，NEC 选中区 0x8740-0x879C 不再变 U+FFFD），
                // 裸 "Shift_JIS" 缺 NEC/IBM 扩展区（2026-10-26 fixcore 波 B1）。
                // TODO(verify): 原版严格解码回退（坏字节 → 整文件跳过，
                // EncodingHandler.cs:67-71 + EraStreamReader.cs:50-59）本波未接。
                String(body, charset("windows-31j"))
            }
        }

        /**
         * 按 CR / LF / CRLF 拆行（对齐 .NET File.ReadAllLines 的行为）：
         * "a\n" → ["a"]；"\n" → [""]；"" → []。
         */
        fun splitLines(text: String): List<String> {
            val result = mutableListOf<String>()
            var start = 0
            var i = 0
            val len = text.length
            while (i < len) {
                when (text[i]) {
                    '\r' -> {
                        result.add(text.substring(start, i))
                        i += if (i + 1 < len && text[i + 1] == '\n') 2 else 1
                        start = i
                    }
                    '\n' -> {
                        result.add(text.substring(start, i))
                        i++
                        start = i
                    }
                    else -> i++
                }
            }
            if (start < len) result.add(text.substring(start))
            return result
        }
    }
}
