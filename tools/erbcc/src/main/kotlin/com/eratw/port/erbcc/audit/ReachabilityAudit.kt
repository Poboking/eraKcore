package com.eratw.port.erbcc.audit

import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.erbcc.Symbols
import com.eratw.port.erbcc.compile.CorpusCompile
import com.eratw.port.erbcc.compile.CorpusScan
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * 可达性 / 可移植性报告（MODERN-R6 最小版：**只报告**，不预热、不改脚本）。
 *
 * 权威与范围：
 * - 根集合 = 事件名（`IdentifierDictionary.cs:72-89`，9 个）∪ 系统名（`:91-135`，含
 *   `COM` / `COM_ABLE` / `ABLUP` 三个正则 `:631-649`）。两者一律经
 *   [Symbols.isEventLabelName] / [Symbols.isSystemLabelName] 判定，本文件不另建表。
 * - 调用图是**文本级**的静态近似，且报告是**下界**：`CALLFORM` 模板、变量目标
 *   （`TRYCALLLIST` 等）、运行期拼出来的名字都不可静态穷举，一律落成可见的
 *   `dynamic-call` 行（带 `file:line` 与原文），**不**猜测展开。
 * - 可移植性诊断按 `persist` / `display` / `unknown` 三档分级，判据见 [PortabilityGrade]。
 *
 * 行号口径：1 基物理行号 = `LogicalLine.lineNo + 1`（`EraStreamReader.cs:30` 的 LineNo 是
 * 0 基），报告读者可直接跳到编辑器同一行。
 *
 * 确定性：文件按绝对路径字典序枚举（[CorpusCompile.listCorpusFiles]），行按
 * `(kind, grade, file, line, function, detail)` 排序，输出里没有绝对路径 / 时间戳 /
 * 主机名 / 耗时。
 */
object ReachabilityAudit {

    /**
     * 扫一遍语料，产出可达集 + 动态调用点 + 可移植性诊断。
     *
     * @param corpusRoot 语料根（通常是 `<gameRoot>/ERB`；[CorpusCompile.listCorpusFiles] 同口径）
     * @param extraRoots 额外根（`--roots A,B`）：名字命中的函数按 depth=0 追加为根
     * @param fileLimit 只扫前 N 个文件（调试档；与 `audit --limit` 同口径）
     * @param bootMetadata 已算好的侧车（`audit` 里由 [SymbolLinkAudit] 的编译产出直接复用，
     *   不再编译第二遍）；缺省 null ⇒ 持久化标志只取源码里 `#DIM` 声明的修饰符原文
     */
    fun audit(
        corpusRoot: Path,
        extraRoots: List<String> = emptyList(),
        fileLimit: Int? = null,
        bootMetadata: BootMetadata? = null,
    ): ReachabilityReport {
        val root = corpusRoot.toAbsolutePath().normalize()
        val all = try {
            CorpusCompile.listCorpusFiles(root)
        } catch (_: Exception) {
            emptyList()
        }
        val files = if (fileLimit != null && fileLimit in 1 until all.size) all.take(fileLimit) else all
        val scan = CorpusScan.scan(files)
        val graph = buildGraph(root, files, scan.macroNames)
        val resolver = DeclResolver(scan, graph, bootMetadata)

        // ---- BFS：根 = 事件名 ∪ 系统名（∪ 显式追加）----
        val depth = HashMap<String, Int>()
        val reachedFrom = HashMap<String, String>()
        val queue = ArrayDeque<FuncBlock>()
        val roots = ArrayList<FuncBlock>()
        for (fn in graph.functions) {
            if (!isRootName(fn.nameUpper)) continue
            if (depth.putIfAbsent(fn.key, 0) != null) continue
            reachedFrom[fn.key] = fn.nameUpper
            roots.add(fn)
            queue.addLast(fn)
        }
        for (raw in extraRoots) {
            val name = raw.trim().uppercase(Locale.ROOT)
            if (name.isEmpty()) continue
            for (fn in graph.byName[name] ?: emptyList()) {
                if (depth.putIfAbsent(fn.key, 0) != null) continue
                reachedFrom[fn.key] = fn.nameUpper
                roots.add(fn)
                queue.addLast(fn)
            }
        }

        val rows = ArrayList<ReachabilityRow>()
        val dynamicRows = ArrayList<ReachabilityRow>()
        while (queue.isNotEmpty()) {
            val fn = queue.removeFirst()
            val d = depth[fn.key] ?: 0
            val from = reachedFrom[fn.key] ?: fn.nameUpper
            if (d > 0) {
                rows.add(
                    ReachabilityRow(
                        ReachabilityKind.REACHABLE, REACH_GRADE_NONE, fn.nameUpper, fn.file, fn.line,
                        "depth=$d root=$from",
                    ),
                )
            }
            for (line in fn.code) {
                val stmt = statementOf(line.content)
                if (stmt !in CALL_STATEMENTS && stmt !in JUMP_STATEMENTS) continue
                val rest = line.content.substring(identEnd(line.content, 0))
                when (val target = parseCallTarget(stmt, rest)) {
                    is CallTarget.Func -> {
                        for (def in graph.byName[target.nameUpper] ?: emptyList()) {
                            if (depth.putIfAbsent(def.key, d + 1) != null) continue
                            reachedFrom[def.key] = from
                            queue.addLast(def)
                        }
                    }
                    is CallTarget.Label -> {
                        val def = resolveLabel(graph, fn, target.nameUpper) ?: continue
                        if (depth.putIfAbsent(def.key, d + 1) != null) continue
                        reachedFrom[def.key] = from
                        queue.addLast(def)
                    }
                    is CallTarget.Dynamic -> dynamicRows.add(
                        ReachabilityRow(
                            ReachabilityKind.DYNAMIC_CALL, REACH_GRADE_NONE, fn.nameUpper, fn.file,
                            line.lineNo + 1, "${stmt} ${snippet(line.content)}",
                        ),
                    )
                }
            }
        }
        // 根自身也占一行（depth=0），报告里一眼能看出「哪些名字被判成了根」
        for (fn in roots) {
            rows.add(
                ReachabilityRow(
                    ReachabilityKind.REACHABLE, REACH_GRADE_NONE, fn.nameUpper, fn.file, fn.line,
                    "depth=0 root=${fn.nameUpper}",
                ),
            )
        }

        // ---- 可移植性诊断：只扫可达函数 ----
        for (fn in graph.functions) {
            if (fn.key !in depth) continue
            for (line in fn.code) {
                val token = portabilityToken(line.content) ?: continue
                val grade = gradeLine(resolver, fn, line.content)
                rows.add(
                    ReachabilityRow(
                        ReachabilityKind.PORTABILITY, grade.label, fn.nameUpper, fn.file,
                        line.lineNo + 1, "$token ${snippet(line.content)}",
                    ),
                )
            }
        }

        val allRows = (rows + dynamicRows).sortedWith(
            compareBy(
                { it.kind.label },
                { it.grade },
                { it.file },
                { it.line },
                { it.function },
                { it.detail },
            ),
        )
        return ReachabilityReport(
            roots = roots.size,
            reachable = depth.size,
            rows = allRows,
        )
    }

    /** `reachable` / `dynamic-call` 的 grade 列固定写 `-`（契约 §3.5）。 */
    private const val REACH_GRADE_NONE = "-"

    /**
     * 根判定：事件名 ∪ 系统名。
     *
     * `IdentifierDictionary.IsSystemLabelName` 的名单已经包含全部事件名
     * （`IdentifierDictionary.cs:95-113` 与 `:76-84` 同源），两个判定取并集只为可读性。
     */
    private fun isRootName(name: String): Boolean =
        Symbols.isEventLabelName(name) || Symbols.isSystemLabelName(name)

    // ------------------------------------------------------------------
    // 语句位调用边
    // ------------------------------------------------------------------

    /** 语句位可产生调用边的关键字（契约 §3.2.3）。 */
    private val CALL_STATEMENTS: Set<String> = setOf(
        "CALL", "CALLFORM", "CALLF", "CALLFORMF",
        "TRYCALL", "TRYCALLFORM", "TRYCCALL", "TRYCCALLFORM",
        "TRYCALLLIST", "TRYJUMPLIST", "TRYGOTOLIST", "CALLEVENT",
    )

    /** 同文件跳转：目标先在本函数内找标签，再退到本文件（契约 §3.2.3）。 */
    private val JUMP_STATEMENTS: Set<String> = setOf("JUMP", "GOTO")

    /** 首参是 FORMS 串的调用：`CALLFORM 名前(…)`。 */
    private val FORM_CALL_STATEMENTS: Set<String> =
        setOf("CALLFORM", "CALLFORMF", "TRYCALLFORM", "TRYCCALLFORM")

    /** 目标是列表变量（`TRYCALLLIST FOO, …`）的调用：一律动态。 */
    private val LIST_CALL_STATEMENTS: Set<String> =
        setOf("TRYCALLLIST", "TRYJUMPLIST", "TRYGOTOLIST")

    /** 一条调用语句解析出的目标。 */
    private sealed class CallTarget {
        /** 静态函数名（已大写）。 */
        class Func(val nameUpper: String) : CallTarget()

        /** 静态标签名（已大写），只对 `JUMP` / `GOTO` 产生。 */
        class Label(val nameUpper: String) : CallTarget()

        /** 静态不可判定：模板 / 变量 / 列表 / 表达式 —— 落成 `dynamic-call` 行。 */
        object Dynamic : CallTarget()
    }

    /**
     * 解析语句位的调用目标。
     *
     * 只有「整串都是标识符」才算静态目标：
     * - `CALL FOO(…)` / `CALL "FOO"`（不带 `%`）⇒ [CallTarget.Func]；
     * - `CALLFORM "FOO"` / `CALLFORM FOO(…)`（不带 `%`）⇒ [CallTarget.Func]；
     * - 含 `%` 的 FORMS 模板、`TRYCALLLIST` 一类列表目标、以 `(` 开头的表达式 ⇒ [CallTarget.Dynamic]。
     */
    private fun parseCallTarget(stmt: String, restRaw: String): CallTarget {
        if (stmt in LIST_CALL_STATEMENTS) return CallTarget.Dynamic
        val rest = restRaw.trimStart()
        if (rest.isEmpty()) return CallTarget.Dynamic
        val literal = quotedLiteral(rest)
        val rawName: String? = when {
            stmt in FORM_CALL_STATEMENTS -> {
                if (literal == null || '%' in literal) return CallTarget.Dynamic
                literal.trim().trim('{', '}').trim()
            }
            literal != null -> {
                if ('%' in literal) return CallTarget.Dynamic
                literal.trim()
            }
            rest[0] == '%' || rest[0] == '(' || rest[0] == '[' -> return CallTarget.Dynamic
            else -> {
                val end = identEnd(rest, 0)
                if (end == 0) return CallTarget.Dynamic
                rest.substring(0, end)
            }
        }
        if (rawName.isNullOrEmpty() || !isIdentifier(rawName)) return CallTarget.Dynamic
        return if (stmt in JUMP_STATEMENTS) {
            CallTarget.Label(rawName.uppercase(Locale.ROOT))
        } else {
            CallTarget.Func(rawName.uppercase(Locale.ROOT))
        }
    }

    /** `"…"` 字面量（不处理转义：ERB 串里没有反斜杠转义，双引号即结束）。 */
    private fun quotedLiteral(text: String): String? {
        if (text.isEmpty() || text[0] != '"') return null
        val end = text.indexOf('"', 1)
        if (end < 0) return null
        return text.substring(1, end)
    }

    private fun isIdentifier(text: String): Boolean =
        text.isNotEmpty() && text.all { it.isLetterOrDigit() || it == '_' }

    /** 标识符字符数（语句名、函数名、变量名的**词边界**）：字母（含日文）/ 数字 / `_`。 */
    private fun identEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c.isLetterOrDigit() || c == '_') i++ else break
        }
        return i
    }

    /** 语句名（行首 token，大写）；行首不是标识符时返回空串。 */
    private fun statementOf(text: String): String =
        text.substring(0, identEnd(text, 0)).uppercase(Locale.ROOT)

    /** 标签解析：先本函数，再本文件（同名标签按先到者胜，契约 §3.2.3）。 */
    private fun resolveLabel(graph: CorpusGraph, fn: FuncBlock, labelUpper: String): FuncBlock? {
        if (labelUpper in fn.labels) return fn
        return graph.labelOwner[fn.file]?.get(labelUpper)
    }

    // ------------------------------------------------------------------
    // 可移植性分级
    // ------------------------------------------------------------------

    /**
     * 可移植性令牌（specs 权威 builtin-functions.yaml 的条目行，实现锚在反编译源）。
     *
     * - `CLIENTWIDTH`（yaml:1448）/ `CLIENTHEIGHT`（yaml:1456）：`ClientSizeMethod`，
     *   `FunctionMethodCreator.cs:6777-6800` —— 返回 PC 主窗口客户区宽高；
     * - `GETFONT`（yaml:1384）：`FunctionMethodCreator.cs:3732-3746` —— 返回当前字体名；
     * - `GGETFONT`（yaml:1536）/ `GGETFONTSIZE`（:1544）/ `GGETFONTSTYLE`（:1552）：
     *   `FunctionMethodCreator.cs:6197-6224` / `:6162-6196` —— 读图形缓冲区的字体设置；
     * - `GDRAWTEXT`（yaml:1696）：`FunctionMethodCreator.cs:6475-6531` —— 用当前字体往
     *   图形缓冲区写字（像素级文字度量）。
     *
     * 判据是「词边界命中」（大小写不敏感）：`GETFONT` 不会误命中 `GGETFONT`。
     */
    private val PORTABILITY_TOKENS: List<String> = listOf(
        "CLIENTWIDTH", "CLIENTHEIGHT", "GETFONT",
        "GGETFONT", "GGETFONTSIZE", "GGETFONTSTYLE", "GDRAWTEXT",
    )

    /**
     * `GETCONFIG` / `GETCONFIGS` 的**显示类键名**白名单。
     *
     * 契约只允许纳入「键名能在反编译源里找到原文」的键。上表逐条对过
     * `ConfigData.cs:55-67` 的条目原文（日文名 / 英文名两档都是官方原文）：
     * `描画インターフェース`/`Drawing interface`、`ウィンドウ幅`/`Window width`、
     * `ウィンドウ高さ`/`Window height`、`PRINTCを並べる数`/`Items per line for PRINTC`、
     * `PRINTCの文字数`/`Number of Item characters for PRINTC`、`フォント名`/`Font name`、
     * `フォントサイズ`/`Font size`、`一行の高さ`/`Line height`；派发口径见
     * `ConfigData.cs:350-425`（`GetConfigValueInERB` 的 `case ConfigCode.*` 分组）。
     *
     * 其余键（`FPS` / `TEXT COLOR` 一类）**不**纳入：前者的键名是 `フレーム毎秒` /
     * `FPS`（`ConfigData.cs:75`，与帧率有关、不是几何/字体），后者在本版没有对应条目原文
     * （语料里 `GETCONFIG("TEXT COLOR")` 其实是运行期 `InvalidConfigName` 错误）。
     */
    private val GETCONFIG_DISPLAY_KEYS: Set<String> = setOf(
        "描画インターフェース", "Drawing interface",
        "ウィンドウ幅", "Window width",
        "ウィンドウ高さ", "Window height",
        "PRINTCを並べる数", "Items per line for PRINTC",
        "PRINTCの文字数", "Number of Item characters for PRINTC",
        "フォント名", "Font name",
        "フォントサイズ", "Font size",
        "一行の高さ", "Line height",
    )

    /**
     * 一行里命中的第一个可移植性令牌（固定顺序 ⇒ 结果确定）。无命中返回 null。
     */
    private fun portabilityToken(text: String): String? {
        for (token in PORTABILITY_TOKENS) {
            val hit = indexOfWord(text, token)
            if (hit >= 0) return token
        }
        // GETCONFIG("<显示类键>") / GETCONFIGS("<显示类键>")
        for (fnName in listOf("GETCONFIG", "GETCONFIGS")) {
            var from = 0
            while (true) {
                val at = indexOfWord(text, fnName, from)
                if (at < 0) break
                from = at + fnName.length
                val open = text.indexOf('(', from)
                if (open < 0) continue
                val literal = quotedLiteral(text.substring(open + 1).trimStart()) ?: continue
                if (literal in GETCONFIG_DISPLAY_KEYS) return "$fnName($literal)"
            }
        }
        return null
    }

    /** 词边界查找（大小写不敏感）：命中的 `from` 之后第一个下标，未命中 -1。 */
    private fun indexOfWord(text: String, word: String, from: Int = 0): Int {
        var i = from
        while (true) {
            val at = text.indexOf(word, i, ignoreCase = true)
            if (at < 0) return -1
            val before = if (at == 0) ' ' else text[at - 1]
            val afterIdx = at + word.length
            val after = if (afterIdx >= text.length) ' ' else text[afterIdx]
            if (!isIdentChar(before) && !isIdentChar(after)) return at
            i = at + 1
        }
    }

    private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * 给一条命中令牌的行定档。
     *
     * 判据按契约 §3.3：
     * - 该行是赋值语句，且**任一**赋值目标声明的修饰符带 `SAVEDATA` 或 `GLOBAL` ⇒ [PortabilityGrade.PERSIST]；
     * - 该行没有赋值（打印/绘图/格式化/几何）⇒ [PortabilityGrade.DISPLAY]；
     * - 赋值目标解析不出声明（宏名、`%` 目标、未知名）⇒ [PortabilityGrade.UNKNOWN]（照实输出，不降成 display）。
     */
    private fun gradeLine(resolver: DeclResolver, fn: FuncBlock, text: String): PortabilityGrade {
        val targets = assignmentTargets(text)
        if (targets.isEmpty()) return PortabilityGrade.DISPLAY
        var unknown = false
        for (name in targets) {
            when (resolver.classify(fn, name)) {
                TargetClass.PERSIST -> return PortabilityGrade.PERSIST
                TargetClass.UNKNOWN -> unknown = true
                TargetClass.PLAIN -> Unit
            }
        }
        return if (unknown) PortabilityGrade.UNKNOWN else PortabilityGrade.DISPLAY
    }

    /**
     * 一行里的赋值目标名（未经解析）。空列表 = 该行不是赋值语句；
     * 列表里的 null = 目标不可静态解析（`%` 目标、表达式、字面量）。
     */
    private fun assignmentTargets(text: String): List<String?> {
        val stmtEnd = identEnd(text, 0)
        val stmt = text.substring(0, stmtEnd).uppercase(Locale.ROOT)
        if (stmt == "SETVAR" || stmt == "SETVARS" || stmt == "SETVAREX" || stmt == "SETVARSX") {
            val first = text.substring(stmtEnd).trimStart().substringBefore(',').trim()
            return listOf(leadingIdentifier(first))
        }
        if (stmt == "VARSET" || stmt == "VARSETS") {
            val first = text.substring(stmtEnd).trimStart().substringBefore(',').trim()
            return listOf(leadingIdentifier(first))
        }
        val op = findAssignmentOperator(text)
        if (op < 0) return emptyList()
        val lhs = text.substring(0, op).trimEnd()
        if (lhs.isEmpty()) return listOf(null)
        return listOf(leadingIdentifier(lhs.trimStart()))
    }

    /** 形如 `BUF:0` / `BUF` / `BUF[0]` 的左侧取变量名；以 `%` / `(` / `"` 开头 ⇒ null。 */
    private fun leadingIdentifier(text: String): String? {
        if (text.isEmpty()) return null
        if (text[0] == '%' || text[0] == '(' || text[0] == '"' || text[0] == '{') return null
        val end = identEnd(text, 0)
        if (end == 0) return null
        return text.substring(0, end)
    }

    /**
     * 顶层（不在字符串内）的赋值运算符下标：`=` 与复合赋值 `+=` `-=` `*=` `/=` `%=` `'=`。
     *
     * 比较运算符（`==` / `!=` / `<=` / `>=`）不算赋值：`==` 整体跳过，`!=`/`<=`/`>=` 的 `=`
     * 因 next 不是 `=` 且 prev 不在复合集里 —— 这里按 prev 判：`!`/`<`/`>` 不在复合集 ⇒ 命中。
     * 为此 `!`/`<`/`>` 也必须显式排除，否则 `IF X >= 1` 会被当成赋值。
     */
    private fun findAssignmentOperator(text: String): Int {
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '"') {
                inString = !inString
                i++
                continue
            }
            if (!inString && c == '=') {
                val next = if (i + 1 < text.length) text[i + 1] else ' '
                if (next == '=') {
                    i += 2
                    continue
                }
                val prev = if (i > 0) text[i - 1] else ' '
                if (prev == '!') { i++; continue }
                // 复合赋值（`+=` / `-=` / `*=` / `/=` / `%=` / `'=`）的算子位置
                if (prev == '+' || prev == '-' || prev == '*' || prev == '/' || prev == '%' || prev == '\'') {
                    return i - 1
                }
                // `<` / `>` / `!` 开头的比较符（单字符形态不会出现，这里只是兜底）
                if (prev == '<' || prev == '>') { i++; continue }
                return i
            }
            i++
        }
        return -1
    }

    private fun snippet(text: String): String {
        val flat = text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').trim()
        return if (flat.length <= DETAIL_MAX) flat else flat.take(DETAIL_MAX - 1) + "…"
    }

    private const val DETAIL_MAX = 160
}

/** 一条报告行的类别（TSV 的 `kind` 列）。 */
enum class ReachabilityKind(val label: String) {
    /** 可达函数（`detail` = `depth=<n> root=<name>`）。 */
    REACHABLE("reachable"),

    /** 静态不可判定的调用目标（`detail` = `<语句名> <原文>`）。 */
    DYNAMIC_CALL("dynamic-call"),

    /** 可移植性诊断（`grade` 三档）。 */
    PORTABILITY("portability"),
}

/**
 * 可移植性分级（TSV 的 `grade` 列）。
 *
 * - [PERSIST]：命中令牌且该行写持久变量（声明带 `SAVEDATA` 或 `GLOBAL`）—— 存档格式面；
 * - [DISPLAY]：命中令牌但不写持久变量 —— 展示适配面（打印/绘图/格式化/几何）；
 * - [UNKNOWN]：赋值目标解析不出声明（宏 / `%` 目标 / 未知名）—— 照实输出，不默认成 display。
 */
enum class PortabilityGrade(val label: String) {
    PERSIST("persist"),
    DISPLAY("display"),
    UNKNOWN("unknown"),
}

/** 一条报告行（TSV 一行；[grade] 对 `reachable` / `dynamic-call` 恒为 `-`）。 */
class ReachabilityRow(
    val kind: ReachabilityKind,
    val grade: String,
    val function: String,
    val file: String,
    val line: Int,
    val detail: String,
)

/**
 * 一次可达性 / 可移植性扫描的结果。
 *
 * [rows] 已按 `(kind, grade, file, line, function, detail)` 排序；[file] 是语料根相对路径
 * （`/` 分隔），输出不含绝对路径 / 时间戳 / 主机名 / 耗时 ⇒ 同一语料两次运行逐字节相同。
 */
class ReachabilityReport(
    /** 根函数个数（事件名 ∪ 系统名 ∪ 显式追加，按定义计）。 */
    val roots: Int,
    /** 可达函数个数（含根）；`roots` 为 0 时它也是 0。 */
    val reachable: Int,
    val rows: List<ReachabilityRow>,
) {
    val dynamicCalls: Int get() = rows.count { it.kind == ReachabilityKind.DYNAMIC_CALL }

    val portability: Int get() = rows.count { it.kind == ReachabilityKind.PORTABILITY }

    val persist: Int get() = rows.count {
        it.kind == ReachabilityKind.PORTABILITY && it.grade == PortabilityGrade.PERSIST.label
    }

    /** CLI 的 summary 行（`--reachability` 时才打印）。 */
    fun summaryLine(): String =
        "reachability: roots=$roots reachable=$reachable dynamic-calls=$dynamicCalls " +
            "portability=$portability persist=$persist"

    /** 写 TSV（`kind/grade/function/file/line/detail`，首行表头），父目录不存在则创建。 */
    fun writeTsv(out: Path) {
        out.toAbsolutePath().normalize().parent?.let { Files.createDirectories(it) }
        val sb = StringBuilder("kind\tgrade\tfunction\tfile\tline\tdetail\n")
        for (r in rows) {
            sb.append(r.kind.label).append('\t')
                .append(r.grade).append('\t')
                .append(clean(r.function)).append('\t')
                .append(clean(r.file)).append('\t')
                .append(r.line).append('\t')
                .append(clean(r.detail)).append('\n')
        }
        Files.writeString(out, sb.toString())
    }

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}

// ---------------------------------------------------------------------------
// 扫描内部模型
// ---------------------------------------------------------------------------

/**
 * 一个 `@函数` 定义块。
 *
 * [key] = `文件:声明行` 作为定义身份：同名多定义（事件桶的 4 个成员、跨文件重名）各自成节点，
 * 与原版「每个 FunctionLabelLine 各持一份私有作用域」一致（`FunctionLabelLine.cs:15`、`:98-117`）。
 */
private class FuncBlock(val nameUpper: String, val file: String, val line: Int) {
    val key: String = "$file:$line"

    /** 本函数体内的代码逻辑行（`#` / `$` / `@` 行不在内）。 */
    val code = ArrayList<LogicalLine>()

    /** 本函数体内的 `$标签`（大写），供 `JUMP` / `GOTO` 解析。 */
    val labels = HashSet<String>()

    /** 本函数体内的 `#DIM` 声明：变量名（大写）→ 是否持久化（`SAVEDATA`/`GLOBAL`）。 */
    val privateDecls = HashMap<String, Boolean>()
}

private class CorpusGraph {
    val functions = ArrayList<FuncBlock>()

    /** 函数名（大写）→ 全部定义（装载序）。 */
    val byName = HashMap<String, MutableList<FuncBlock>>()

    /** 文件 → `$标签`（大写）→ 首个声明它的函数（同文件回退用）。 */
    val labelOwner = HashMap<String, HashMap<String, FuncBlock>>()

    /** 函数体外的 `#DIM`（含 ERH）：变量名（大写）→ 是否持久化。 */
    val globalDecls = HashMap<String, Boolean>()
}

private class DimDecl(val name: String, val persisted: Boolean)

/** 行首 `@` 后到 `(` / `,` / `;` / 空白之前的函数名（原样，未大写）。`ErbLoader.cs:388-427`。 */
private fun labelNameOf(content: String): String =
    content.removePrefix("@").takeWhile { it != '(' && it != ',' && it != ';' && !it.isWhitespace() }

/** 行首 `$` 后的标签名（原样，未大写）。 */
private fun gotoLabelNameOf(content: String): String =
    content.removePrefix("$").takeWhile { it != ';' && !it.isWhitespace() }

/** `#DIM` 的修饰符表（与 `CorpusScan.MODIFIERS` / `UserDefinedVariableData.cs:54-264` 同口径）。 */
private val DIM_MODIFIERS: List<String> =
    listOf("CONST", "REF", "DYNAMIC", "STATIC", "GLOBAL", "SAVEDATA", "CHARADATA")

/**
 * 解析一条 `#DIM` / `#DIMS` / `#DIMF` 声明（`text` 含前导 `#`），返回名字与「是否持久化」。
 *
 * 持久化判据 = 修饰符里带 `GLOBAL` 或 `SAVEDATA`（契约 §3.3；位定义见
 * `UserDefinedVariableData.cs:54-264` 与 `VariableData.cs:3961-3997` 的存档筛选）。
 * 修饰符按**词级**判定（忽略大小写、忽略词界之后的字母数字）——与 `CorpusScan` 的 A2 修复
 * 同口径：旧实现 `startsWith` 会把 `#DIMS CONST CHARADATA_TYPES` 的变量名误当 `CONST`。
 */
private fun parseDimDecl(text: String): DimDecl? {
    var s = text.trim()
    if (!s.startsWith("#")) return null
    s = s.substring(1).trim()
    val kw = s.takeWhile { !it.isWhitespace() }.uppercase(Locale.ROOT)
    if (kw != "DIM" && kw != "DIMS" && kw != "DIMF") return null
    var head = s.substring(kw.length).trim()
    var persisted = false
    var changed = true
    while (changed) {
        changed = false
        for (mod in DIM_MODIFIERS) {
            if (head.length < mod.length) continue
            if (!head.regionMatches(0, mod, 0, mod.length, ignoreCase = true)) continue
            val nextIsIdentChar = head.length > mod.length &&
                (head[mod.length].isLetterOrDigit() || head[mod.length] == '_')
            if (nextIsIdentChar) continue
            head = head.substring(mod.length).trim()
            if (mod == "GLOBAL" || mod == "SAVEDATA") persisted = true
            changed = true
        }
    }
    val name = head.takeWhile { it != ',' && it != '=' && it != ';' && !it.isWhitespace() }.trim()
    if (name.isEmpty()) return null
    return DimDecl(name, persisted)
}

/**
 * 语料静态扫描：逻辑行 → 函数块 / `$标签` / `#DIM` 声明 / 代码行。
 *
 * 逻辑行来自 [LogicalLineReader]（`EraStreamReader.ReadEnabledLine` 的移植：`{}` 续行、
 * `[IF]` 一族预处理、`;` 注释都已在 reader 里处理），因此本函数只做分类：
 * `@` 起新函数块、`$` 归当前函数、`#` 解析声明、其余进代码行。
 *
 * 读失败 / 二进制 / 词法错行**跳过**（不炸整批）——报告是下界，缺的行不冒充已覆盖。
 */
private fun buildGraph(root: Path, files: List<Path>, macroNames: Set<String>): CorpusGraph {
    val graph = CorpusGraph()
    for (path in files) {
        val rel = root.relativize(path).toString().replace('\\', '/')
        val bytes = try {
            Files.readAllBytes(path)
        } catch (_: Exception) {
            continue
        }
        if (hasNul(bytes)) continue
        val reader = try {
            LogicalLineReader(LogicalLineReader.readAllLines(bytes), rel, macroNames = macroNames)
        } catch (_: Exception) {
            continue
        }
        var cur: FuncBlock? = null
        while (true) {
            val line = try {
                reader.readLogicalLine() ?: break
            } catch (_: LexError) {
                continue
            }
            when (line.kind) {
                LineKind.FUNCTION_LABEL -> {
                    val name = labelNameOf(line.content)
                    if (name.isEmpty()) {
                        cur = null
                        continue
                    }
                    val fn = FuncBlock(name.uppercase(Locale.ROOT), rel, line.lineNo + 1)
                    graph.functions.add(fn)
                    graph.byName.getOrPut(fn.nameUpper) { ArrayList() }.add(fn)
                    cur = fn
                }
                LineKind.GOTO_LABEL -> {
                    val label = gotoLabelNameOf(line.content)
                    if (label.isEmpty()) continue
                    val key = label.uppercase(Locale.ROOT)
                    cur?.labels?.add(key)
                    cur?.let { owner ->
                        graph.labelOwner.getOrPut(rel) { HashMap() }.putIfAbsent(key, owner)
                    }
                }
                LineKind.SHARP -> {
                    val decl = parseDimDecl(line.content) ?: continue
                    val table = cur?.privateDecls ?: graph.globalDecls
                    table[decl.name.uppercase(Locale.ROOT)] = decl.persisted
                }
                LineKind.CODE -> cur?.code?.add(line)
            }
        }
        runCatching { reader.fileEnd() }
    }
    return graph
}

private fun hasNul(bytes: ByteArray): Boolean {
    val end = minOf(bytes.size, 8192)
    for (i in 0 until end) if (bytes[i] == 0.toByte()) return true
    return false
}

/** 赋值目标的一档判定（内部用语，与 [PortabilityGrade] 一一对应）。 */
private enum class TargetClass { PERSIST, PLAIN, UNKNOWN }

/**
 * 持久化声明的查询面。
 *
 * 主源是扫描出的 `#DIM` 修饰符原文（与编译器解析的是同一行文本）；[bootMetadata] 给出时
 * 作为**权威覆盖**参与判定：
 * - `privateDims`（`BootMetadata.PrivateDim.spec.isGlobal` / `.isSavedata`，`UserVariables.kt:43-96`）按函数键归属；
 * - `globalDims`（`BootMetadata.RawDim.raw`）是 `#DIM` 声明原文，按 [parseDimDecl] 重新取修饰符
 *   （侧车把它当原文存，解析在装载期做）。
 *
 * 覆盖表优先于扫描表：侧车只登记**解析成功**的声明，同名的失败声明不该被当成持久变量。
 */
private class DeclResolver(
    private val scan: CorpusScan,
    private val graph: CorpusGraph,
    bootMetadata: BootMetadata?,
) {
    private val privateOverride: Map<String, Map<String, Boolean>> = run {
        if (bootMetadata == null) return@run emptyMap()
        val table = HashMap<String, MutableMap<String, Boolean>>()
        for (pd in bootMetadata.privateDims) {
            val spec = pd.spec
            // 位定义：core.vars.DimSpec.isGlobal / isSavedata（`UserDefinedVariableData.cs:54-264`）
            val persisted = spec.isGlobal || spec.isSavedata
            table.getOrPut(pd.functionKey.uppercase(Locale.ROOT)) { HashMap() }[spec.name.uppercase(Locale.ROOT)] =
                persisted
        }
        table
    }

    private val globalOverride: Map<String, Boolean> = buildMap {
        if (bootMetadata == null) return@buildMap
        for (gd in bootMetadata.globalDims) {
            val decl = parseDimDecl(gd.raw) ?: continue
            put(decl.name.uppercase(Locale.ROOT), decl.persisted)
        }
    }

    /**
     * 一个赋值目标名（null = 不可静态解析）落在哪一档。
     *
     * 解析顺序：宏名 ⇒ unknown；侧车覆盖 ⇒ 扫描出的私有声明 ⇒ 扫描出的全局声明 ⇒
     * 内建/系统变量表（[CorpusScan.global]，含 `BuiltinSymbols` 兜底）⇒ 都没有 ⇒ unknown。
     *
     * TODO(verify)：系统变量（`MONEY` / `DAY` 一类）经 `scan.global` 只判定「名字存在」，
     * 它们的 `isSavedata` 位在运行期 `VariableCode` 里（`VariableToken.cs:88-154`），本报告
     * 拿不到 ⇒ 现按 PLAIN 处理（可能少报 persist）。补锚需要把 `VariableRegistry` 的位表
     * 引进静态面，属跨模块接线，见交付回执的「需组长接线」。
     */
    fun classify(fn: FuncBlock, name: String?): TargetClass {
        if (name == null) return TargetClass.UNKNOWN
        val upper = name.uppercase(Locale.ROOT)
        if (upper in scan.macroNames) return TargetClass.UNKNOWN
        val flags = listOfNotNull(
            privateOverride[fn.nameUpper]?.get(upper),
            fn.privateDecls[upper],
            globalOverride[upper],
            graph.globalDecls[upper],
        )
        return when {
            flags.any { it } -> TargetClass.PERSIST
            flags.isNotEmpty() -> TargetClass.PLAIN
            scan.global.resolveVariable(name, null) != null -> TargetClass.PLAIN
            else -> TargetClass.UNKNOWN
        }
    }
}
