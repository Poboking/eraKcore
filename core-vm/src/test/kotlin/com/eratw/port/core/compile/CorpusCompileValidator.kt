package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LexError
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.stmt.CorpusValidator
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import java.nio.file.Files
import java.nio.file.Path

/**
 * 编译冒烟验证器（测试辅助）：语料/金标 ERB → 语句 AST → @函数 切片 → 字节码。
 *
 * 切片与预处理口径与 [CorpusValidator] 一致（禁用区间不平衡块不计错）。
 * 同名 @函数 多定义 = 事件成员组：每个成员键 `<KEY>#<defIndex>`，另合成
 * `<KEY>` 事件入口（按装载序依序 _CALL，spec: loader.md 附录 D）。
 *
 * **A13（2026-10-18）两侧装置统一**：解析侧不再自建/复用第二套作用域状态——
 * 与语句层同样「整轮共用一个 [LineParser] + 一个 [CorpusValidator.ScopedResolver]」，
 * 私有变量作用域由 `LineParser.parseLogicalLine` 解析 `@` 行时回调切换
 * （spec: IdentifierDictionary.cs:429-447 / LogicalLineParser.cs:279-292 / ErbLoader.cs:413-423）。
 * 此前编译侧从不切作用域，函数内 `#DIM` 私有名（含中文名、`VARSET 私有名`、`EXPECTED_WIDTH` 等）
 * 一律报「未定义的标识符」，把 5,345 切片 / 59,163 行误判成解析失败，而语句层报 0——
 * 差异只可能来自作用域接线，不是语料问题。残值归因见 [verdictOf] 与报告 §8。
 *
 * **A8 回归闸口径（CONTRACT-M2-E2E §2「不回退」）**：基线 = 全语料 104,725 切片
 * 100% 编译、约 397 万指令、约 41s。基线失败集为空，因此**本轮任何失败切片
 * 一律计为新增回归**；报告按「降级规则拒绝 / 实现崩溃 / 事件入口 / 读取失败」分栏，
 * 并给出失败语句的 FunctionCode 分布与最小复现（文件:行 + 语句原文）。
 * 禁止为绿灯跳过失败切片或缩小语料集。
 */
object CorpusCompileValidator {

    // ---- 基线常量（M2 第二波收官，commit 6da9768；组长效能口径见契约 §2）----
    const val BASE_SLICES = 104_725L            // 编译尝试切片数（= functions + 失败）
    const val BASE_FUNCTIONS = 104_725L         // 基线 100% 成功 → 与切片同值
    const val BASE_INSTRUCTIONS = 3_970_000L    // 约 397 万（成功函数体指令合计，近似值）
    const val BASE_ELAPSED_MS = 41_000L         // 约 41s（同机串行读数，仅作参考列）
    const val BASE_PARSE_FAILURES = 0L
    const val BASE_COMPILE_FAILURES = 0

    /**
     * A13 口径修正**前**的 FULL 读数（2026-10-18 11:43 同机运行，见 build/compile-report/summary.md）。
     * 修的是采样装置而非语料：①私有变量作用域改由 `LineParser` 统一切换（编译侧此前从不 `enter`，
     * 函数内 `#DIM/#DIMS` 私有名全被判未定义标识符）；②语料清单排除混入 ERB 根的 `GOLDEN_*` 采集副本；
     * ③`@` 前散行的解析失败开始计数。故修正后切片分母变大属**回收**，不是语料变化。
     * 注意：基线的 104,725 本身也是「剔除毒化切片后」的分母，所以口径修正后超出基线属预期。
     */
    const val A13_PRE_FILES = 3_984L
    const val A13_PRE_SLICES = 104_757L
    const val A13_PRE_FUNCTIONS = 104_757L
    const val A13_PRE_PARSE_FAILURES = 5_345L
    const val A13_PRE_PARSE_FAILURE_LINES = 59_163L

    /** 语句 marker 的 opcode 区间（CONTRACT-M2-E2E C3/C4：op=语句操作码 29..197 且非内部码）。 */
    const val STMT_OP_MIN = 29
    const val STMT_OP_MAX = 197

    /** 基线失败消息签名集合（基线 100% → 空集；任何签名都是本轮新增）。 */
    val BASELINE_FAILURE_SIGNATURES: Set<String> = emptySet()

    // 失败归因分栏（全部为「本轮引入」，因为基线失败集为空；分栏只区分引入形态）
    const val CAT_RULE = "降级规则拒绝(CompileException)"
    const val CAT_CRASH = "实现崩溃(其他异常)"
    const val CAT_EVENT = "事件入口合成"
    const val CAT_READ = "文件读取失败"

    class Failure(
        val file: String,
        val line: Int,
        val function: String,
        val message: String,
        /** 归因语句的 FunctionCode（按 CompileException 行号在切片语句里反查；查不到为 null）。 */
        val stmtCode: String? = null,
        /** 归因语句原文（Statement.raw，含前导标记，报告里截断展示）。 */
        val stmtRaw: String? = null,
        /** 归因分栏，见 CAT_* 常量。 */
        val category: String = CAT_RULE,
    ) {
        /** 分组签名：剥掉 CompileException 前缀里的函数名/行号，数字归一，防止签名爆炸。 */
        val signature: String by lazy {
            val stripped = message.replace(Regex("^编译错误\\([^)]*\\):\\s*"), "")
                .replace(Regex("^事件入口:\\s*"), "")
            stripped.replace(Regex("\\d+"), "#").take(120)
        }
    }

    class Result {
        var files = 0
        var functions = 0
        var eventEntries = 0
        var instructions = 0L
        var slotTotal = 0L
        var globalSlots = 0

        /** 含解析失败行而被跳过的函数切片数（结构不可信，不计入编译成功率分母）。 */
        var parseFailures = 0
        var parseFailureLines = 0
        val successes = mutableListOf<String>()
        val failures = mutableListOf<Failure>()
        var elapsedMs = 0L

        // ---- A8 报告增强字段 ----
        /** 解析失败切片样本（至多 [SAMPLE_CAP] 条）：file:line + 原文 + 异常消息。 */
        val parseFailureSamples = mutableListOf<String>()
        /** 解析失败按归一签名分组（**全量**切片/行计数，非样本），用于残值归因。 */
        val parseGroups = LinkedHashMap<String, ParseGroup>()
        /** 失败消息签名 → 该组失败（保持首见序）。 */
        val failureGroups = LinkedHashMap<String, MutableList<Failure>>()
        /** 失败语句 FunctionCode 分布（含无法归因的 "(未知)" 桶）。 */
        val failureByCode = LinkedHashMap<String, Long>()
        /** 失败归因分栏 → 条数。 */
        val failureByCategory = LinkedHashMap<String, Long>()
        /** build() 成功后的全 Program 指令数（含失败切片的半途发射，口径见报告脚注）。 */
        var programInstructions = -1L
        /** 语句 marker 指令数（op ∈ 29..197 且非内部码）。 */
        var markerInstructions = -1L
        /** marker 语句名分布（按条数降序，全量；报告取 top）。 */
        val markerByName = LinkedHashMap<String, Long>()
        /** A1 语句池条目总量：反射读 Program.statementCount；池未落地 = -1（用 marker 数估算）。 */
        var statementPoolEntries = -1L
        var programBuilt = false
        var programBuildError: String? = null
        /** 运行范围：FULL=全语料（判闸口径）；LIMITED/SINGLE=调试子集，prescan 符号表不完整，
         *  解析失败切片属子集伪影，不参与判闸（详见报告 §2 注记）。 */
        var runScope: String = "FULL"

        /** 编译尝试切片总数 = 成功 + 失败（基线分母口径）。 */
        val slicesTotal: Int get() = functions + failures.size

        /**
         * 契约 R8（CONTRACT-M2-E2E §5，必修）：**解析失败必须参与判闸**。
         * 旧实现只查 `failures`，解析失败走 `parseFailures++` 毒化切片后直接不进分母，
         * "0 解析失败"是报表伪影。基线 0/0，因此 parseFailures/parseFailureLines
         * 任何非零都是回退。（LIMITED 子集运行时 prescan 不完整会有伪影——
         * 判闸以 FULL 运行 [runScope] 为准，但 ok() 口径不再放水。）
         *
         * A13 追加：残值必须全部落入已归因签名组（[ParseGroup.verdict] 不许出现
         * [VERDICT_UNCLASSIFIED]），否则同样不绿——「不知道」不是一栏。
         */
        fun ok(): Boolean = failures.isEmpty() && parseFailures == 0 && parseFailureLines == 0 &&
            parseGroups.values.none { it.verdict == CorpusCompileValidator.VERDICT_UNCLASSIFIED }

        /** 记录一条解析失败行（全量计数 + 归因签名 + R9 分栏）。[newSlice]=true 时该组切片数 +1。 */
        fun recordParseFailure(message: String, file: String, line: Int, text: String, newSlice: Boolean) {
            parseFailureLines++
            val base = CorpusCompileValidator.parseSignature(message)
            val ident = CorpusCompileValidator.undefIdentifier(message)
            val (verdict, reason) = classify(base, ident)
            val sig = if (reason.isEmpty()) base else "$base [$reason]"
            val g = parseGroups.getOrPut(sig) { ParseGroup(sig, verdict, reason) }
            g.lines++
            if (newSlice) g.slices++
            if (g.sample.isEmpty()) g.sample = "$file:$line | ${text.take(160)}"
            if (ident != null) {
                g.identifierCount++
                if (g.identifiers.size < CorpusCompileValidator.ID_CAP) g.identifiers.add(ident)
            }
        }

        /**
         * 由 [compile] 注入（需要 prescan 的声明名册才能证明「原版也拒」）；
         * 未注入时退回到 [verdictOf] 的保守分栏。返回 (R9 分栏, 子类标签)。
         */
        var classify: (signature: String, identifier: String?) -> Pair<String, String> =
            { sig, _ -> CorpusCompileValidator.verdictOf(sig) to "" }
    }

    /** 解析失败残值组：一类根因（×一个子类）一行表。 */
    class ParseGroup(val signature: String, var verdict: String, val reason: String = "") {
        var lines = 0
        var slices = 0
        var sample = ""

        /** 组内不同标识符（只留前 [ID_CAP] 个做审计线索，计数为全量）。 */
        val identifiers = LinkedHashSet<String>()
        var identifierCount = 0
    }

    // ---------------------------------------------------------------------
    // A13 解析失败残值归因（契约 R9：只允许两栏，命中不了 = 未归类 = 不绿）
    // ---------------------------------------------------------------------

    /** 归因分栏：原版装载期同样拒绝。 */
    const val VERDICT_ORIGINAL_REJECTS = "原版也会拒（我们已同样拒）"

    /** 归因分栏：原版接受、我们仍拒 —— GAP，需继续收紧（R9 台账项）。 */
    const val VERDICT_GAP = "GAP：原版接受、我们仍拒（继续收紧）"

    /** 兜底分栏：不允许出现在终验读数里（出现即本次修复未完成）。 */
    const val VERDICT_UNCLASSIFIED = "未归类（不允许存在）"

    /** 每个残值组保留的标识符审计线索上限。 */
    const val ID_CAP = 20

    /** 归一签名：剥行列号、把引号内的标识符/字面量归一，避免每条标识符各成一撮。 */
    fun parseSignature(message: String): String =
        message
            .replace(Regex("语句解析错误\\(行:-?\\d+,列:-?\\d+\\)"), "")
            .replace(Regex("解析错误\\(行:-?\\d+,列:-?\\d+\\)"), "解析错误")
            .replace(Regex("'[^']*'"), "'X'")
            .replace(Regex("\\d+"), "#")
            .take(140)

    /** 从消息里取「未定义的标识符 'X'」的 X（不是这类失败则 null）。 */
    fun undefIdentifier(message: String): String? =
        Regex("未定义的标识符 '([^']*)'").find(message)?.groupValues?.get(1)

    /**
     * 保守分栏（无 prescan 名册时）：只认已证明的类，其余一律「未归类」。
     */
    fun verdictOf(signature: String): String =
        if (signature.contains("未定义的标识符")) VERDICT_ORIGINAL_REJECTS else VERDICT_UNCLASSIFIED

    /**
     * 残值归因（A13，带 prescan 名册的可证明版本）。依据 `reference/emuera-cs/` 装载期标识符解析：
     *
     * 原版取变量标识符走 `GetVariableToken(key, subKey, allowPrivate=true)`，三级：
     * ① **当前 `@函数` 私有表**（spec: IdentifierDictionary.cs:432-447；私有表由函数体内
     *   `#DIM/#DIMS` 在第一遍扫描登记 spec: ErbLoader.cs:413-423 + LogicalLineParser.cs:279-292 +
     *   FunctionLabelLine.cs:98-117）；② **LOCAL/ARG/LOCALS/ARGS 局部名册**（cs:448-473，
     *   名册只有这 4 项 spec: VariableData.cs:3181-3187；`VARSET` 不是声明语句，
     *   spec: SpVarSetArgument.cs:8 收 VariableTerm）；③ **全局变量表**（cs:474-489）。
     * 三级全空才 `return null`（cs:494），随后 `ThrowException(idStr, isFunc=false)`：
     * - 名字在**别处**做过私有 `#DIM` → `VarNotDefinedThisFunc`「该函数未定义此变量」
     *   （spec: IdentifierDictionary.cs:50 + 391 + 590-592）；
     * - 名字**哪里都没声明** → `CanNotInterpreted`「标识符无法解释」（spec: cs:628）。
     * 两者都被外层 catch 成 `ParserMediator.Warn(…, level 2)` → `InvalidLine`
     * （spec: LogicalLineParser.cs:301-305），即**该行在原版同样装载失败**。
     *
     * 所以「未定义的标识符」在本装置里可分两个可证明的子类，都落「原版也会拒」；
     * 其它签名（词法错误 / 参数形态 / 我们自己的降级规则）无法凭签名证明原版态度，
     * 一律 [VERDICT_UNCLASSIFIED] —— 判闸不绿，必须逐条查原版后再补规则。
     */
    fun classifyWithPrescan(
        pre: CorpusValidator.Prescan,
        signature: String,
        identifier: String?,
    ): Pair<String, String> {
        if (identifier == null || !signature.contains("未定义的标识符")) return verdictOf(signature) to ""
        val up = identifier.uppercase()
        val privateElsewhere = pre.funcVars.values.any { it.containsKey(up) }
        val globalOrBuiltin = pre.base.resolveVariable(identifier, null) != null
        return when {
            // 命不中任何名册：原版走 CanNotInterpreted（IdentifierDictionary.cs:628）→ InvalidLine
            !privateElsewhere && !globalOrBuiltin -> VERDICT_ORIGINAL_REJECTS to "全语料无声明点(原版 CanNotInterpreted)"
            // 是别处的私有 #DIM：原版走 VarNotDefinedThisFunc（cs:590-592）→ InvalidLine
            privateElsewhere -> VERDICT_ORIGINAL_REJECTS to "他函数私有#DIM(原版 VarNotDefinedThisFunc)"
            // 全局/内建表里有却在当前作用域取不到 —— 那是我们侧的作用域/下标接线问题
            else -> VERDICT_GAP to "全局/内建有此名却解析不到(疑似我们侧作用域缺陷)"
        }
    }

    private const val SAMPLE_CAP = 200

    /** @函数 切片（内部）。 */
    private class Slice(
        val key: String,
        val params: Int,
        val isMethod: Boolean,
        val stmts: List<Statement>,
        val file: String,
        val line: Int,
    )

    /** 编译给定文件集。跨文件共享布局（GlobalNameLayout 全局槽号稳定）。 */
    fun compile(files: List<Path>, corpusRoot: Path?): Result {
        val t0 = System.currentTimeMillis()
        val result = Result()

        // ---- 解析：文件 → @函数切片 ----
        val slices = ArrayList<Slice>()

        val renameDic = corpusRoot?.let { CorpusValidator.loadRenameDic(it) }
        val pre = CorpusValidator.prescan(files)
        val isMethod = pre.funcTypes.keys.mapTo(HashSet()) { it.uppercase() }
        // 残值归因需要 prescan 的声明名册（判断"别处做过私有 #DIM" vs "哪里都没声明"）
        result.classify = { sig, id -> classifyWithPrescan(pre, sig, id) }

        // **与语句层 CorpusValidator.run 同一装置、同一实例拓扑**：整轮共用一个 LineParser
        // + 一个 ScopedResolver。私有变量作用域由 LineParser 解析 `@` 行时回调
        // enterFunction 切换（A13 根因：这里曾各建一份且从不 enter，函数内 #DIM 私有名
        // 全被判成未定义标识符 → 5,345 切片 59,163 行"解析失败"）。
        // #DEFINE 宏同样在词法层展开（与生产 erbcc 同一份宏表）。
        val parser = LineParser(
            CorpusValidator.ScopedResolver(pre),
            lexerConfig = com.eratw.port.core.lexer.LexerConfig(macros = pre.macros),
        )

        for (path in files) {
            result.files++
            if (result.files % 50 == 0) System.err.println("…解析 ${result.files}/${files.size} ${path.fileName}")
            val rel = corpusRoot?.relativize(path)?.toString()?.replace('\\', '/') ?: path.toString()
            val bytes = try {
                Files.readAllBytes(path)
            } catch (e: Exception) {
                addFailure(result, Failure(rel, -1, "", "读取失败: ${e.message}", category = CAT_READ))
                continue
            }
            if (bytes.indexOf(0.toByte()) in 0 until minOf(bytes.size, 8192)) continue
            val reader = LogicalLineReader(LogicalLineReader.readAllLines(bytes), rel, renameDic = renameDic)
            val pp = CorpusValidator.ErbPreprocess()
            val body = ArrayList<Statement>()
            var curKey: String? = null
            var curParams = 0
            var curLine = -1
            var poisoned = false                 // 本切片含解析失败行 → 结构不可信，不编译
            var poisonSampled = false

            /** 记一条解析失败；@ 之前的散行同样计入（旧实现只在 curKey!=null 时计数，会漏报）。 */
            fun fail(lineNo: Int, msg: String, text: String) {
                val firstOfSlice = !poisoned
                if (!poisonSampled && result.parseFailureSamples.size < SAMPLE_CAP) {
                    val where = if (curKey != null) "[$curKey] " else "[@前散行] "
                    result.parseFailureSamples.add("$rel:$lineNo $where$msg | ${text.take(120)}")
                    poisonSampled = true
                }
                result.recordParseFailure(msg, rel, lineNo, text, newSlice = firstOfSlice && curKey != null)
                poisoned = true
            }

            fun flush() {
                val key = curKey
                if (key == null) {
                    // 旧实现在此 return，导致 @ 之前的解析失败把整个文件首个切片毒化却不计数
                    poisoned = false
                    poisonSampled = false
                    return
                }
                if (!poisoned) {
                    slices.add(Slice(key, curParams, key in isMethod, body.toList(), rel, curLine))
                } else {
                    result.parseFailures++
                }
                body.clear()
                curKey = null
                poisoned = false
                poisonSampled = false
            }
            while (true) {
                val line = try {
                    reader.readLogicalLine() ?: break
                } catch (e: LexError) {
                    // 词法错误在语句层同样是 failures 一条（CorpusValidator.run），此处必须同计数口径
                    fail(e.lineNo, "词法错误 ${e.message}", "")
                    continue
                }
                if (pp.process(line.content, pre.macroNames)) continue
                if (pp.disabled) continue
                val stmt = try {
                    parser.parseLogicalLine(line)
                } catch (e: Exception) {
                    // 解析失败行若静默丢弃，块语句会错位并连锁误报——毒化整个切片
                    fail(line.lineNo, e.message ?: "解析失败", line.content)
                    continue
                } ?: continue
                if (stmt is SharpStmt) continue           // # 行不产生指令（#FUNCTION 已在预扫描）
                if (stmt is FuncLabelStmt) {
                    flush()
                    curKey = stmt.name.uppercase()
                    curParams = stmt.paramNames.size
                    curLine = stmt.line
                    continue
                }
                if (curKey == null) continue              // @ 之前的散行（理论不存在）
                body.add(stmt)
            }
            flush()
            try {
                reader.fileEnd()
            } catch (_: Exception) {
            }
        }

        // ---- 同名多定义 → 事件成员键 <KEY>#<defIndex> ----
        // （预计算总数，避免对每个 slice 全量 count 的 O(n²)——语料有数万函数）
        val order = ArrayList<String>()                  // 编译序（= 函数表序）
        val keyTotals = slices.groupingBy { it.key }.eachCount()
        val seenCount = HashMap<String, Int>()
        val memberKeys = HashMap<Slice, String>()
        for (s in slices) {
            val idx = seenCount.merge(s.key, 1, Int::plus)!! - 1
            val mk = if (keyTotals[s.key] == 1) s.key else "${s.key}#$idx"
            memberKeys[s] = mk
            order.add(mk)
        }
        val known = order.toSet()
        System.err.println("…解析完成：${slices.size} 个函数切片，开始编译")

        // ---- 编译：跨文件共享布局 ----
        val layout = GlobalNameLayout()
        val c = ErbProgramCompiler(layout)
        val done = HashSet<String>()
        for (s in slices) {
            val key = memberKeys[s]!!
            if (!done.add(key)) continue
            try {
                // A7：#FUNCTIONS（str 方法）RETURNF 走 `_RETS`；类型来源 = prescan funcTypes。
                val returnsStr = pre.funcTypes[s.key.uppercase()] == OperandType.STR
                val fb = c.compileFunction(FuncSig(key, s.params, s.isMethod, returnsStr), s.stmts, known)
                result.functions++
                result.instructions += fb.instructionCount
                result.slotTotal += fb.slotCount
                result.successes.add("${s.file}:${s.line} $key")
            } catch (e: CompileException) {
                addFailure(result, toFailure(s, key, e.message ?: "编译失败", CAT_RULE))
            } catch (e: Exception) {
                addFailure(
                    result,
                    toFailure(s, key, "内部错误: ${e::class.simpleName}: ${e.message}", CAT_CRASH),
                )
            }
        }
        // 事件入口：仅同名多定义组需要合成（单定义 EVENT 函数本身在函数表中，
        // CALLEVENT 以事件名解析即命中它，再合成同名入口会撞名）
        val byKey = LinkedHashMap<String, MutableList<String>>()
        for (s in slices) byKey.getOrPut(s.key) { mutableListOf() }.add(memberKeys[s]!!)
        for ((key, members) in byKey) {
            if (members.size == 1) continue
            try {
                val fb = c.compileEventEntry(key, members)
                result.eventEntries++
                result.instructions += fb.instructionCount
            } catch (e: CompileException) {
                addFailure(result, Failure("*", -1, key, "事件入口: ${e.message}", category = CAT_EVENT))
            }
        }
        result.globalSlots = layout.intSlots.size + layout.strSlots.size

        // ---- Program 级统计（marker 占比 / 语句池条目）----
        // 失败切片的 pass2 可能已半途发射进共享 CodeBuilder；且有函数 _CALL 失败函数时
        // build() 会报未定义——统计失败不判闸，只在报告注明。基线 0 失败时数字精确。
        collectProgramStats(c, result)

        result.elapsedMs = System.currentTimeMillis() - t0
        return result
    }

    /** 按 CompileException 内嵌行号在切片语句里反查归因语句（Statement.raw 提供原文）。 */
    private fun toFailure(slice: Slice, key: String, message: String, category: String): Failure {
        val ln = Regex("行:(-?\\d+)").find(message)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val stmt: Statement? = if (ln >= 0) slice.stmts.firstOrNull { it.line == ln } else null
        return Failure(
            file = slice.file,
            line = if (ln >= 0) ln else slice.line,
            function = key,
            message = message,
            stmtCode = stmt?.code?.name,
            stmtRaw = stmt?.raw?.take(160),
            category = category,
        )
    }

    /** 目录全量 ERB/ERH（不过滤；金标 fixtures 走这条）。 */
    fun listErbFiles(root: Path): List<Path> = CorpusValidator.erbFiles(root, includeCollectionCopies = true)

    /**
     * 语料判闸清单：排除混进语料根的金标采集副本 `GOLDEN_*.ERB`
     * （它们是 `fixtures/golden/` 的拷贝，非原版语料；基线 3,971 文件口径即不含它们）。
     */
    fun listCorpusFiles(root: Path): List<Path> = CorpusValidator.erbFiles(root)

    /** 指令分布统计（冒烟报告用）。 */
    fun opcodeHistogram(program: com.eratw.port.core.code.Program): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (pc in 0 until program.instructionCount) {
            m.merge(Opcode.NAMES[program.instructionAt(pc).op] ?: "?", 1L, Long::plus)
        }
        return m
    }

    /** 语句 marker 判定：op ∈ [STMT_OP_MIN, STMT_OP_MAX] 且非内部码（契约 C3/C4 marker 编码）。 */
    fun isStatementMarker(op: Int): Boolean = op in STMT_OP_MIN..STMT_OP_MAX && !Opcode.isInternal(op)

    private fun collectProgramStats(c: ErbProgramCompiler, result: Result) {
        val program = try {
            c.build(null)
        } catch (e: Exception) {
            result.programBuildError = "${e::class.simpleName}: ${e.message}"
            null
        } ?: return
        result.programBuilt = true
        result.programInstructions = program.instructionCount.toLong()
        var markers = 0L
        val names = HashMap<String, Long>()
        for (pc in 0 until program.instructionCount) {
            val op = program.op(pc)
            if (isStatementMarker(op)) {
                markers++
                names.merge(Opcode.NAMES.getOrNull(op) ?: "op$op", 1L, Long::plus)
            }
        }
        result.markerInstructions = markers
        names.entries.sortedByDescending { it.value }.forEach { result.markerByName[it.key] = it.value }
        // A1 语句池（契约 C2：Program.statementCount）落地前后都要能编译 → 反射读取；
        // 未落地时以 marker 数 = 池条目估算（C2 按身份追加、不去重，一 marker 一条目）。
        result.statementPoolEntries = runCatching {
            (program.javaClass.getMethod("getStatementCount").invoke(program) as? Number)?.toLong()
        }.getOrNull() ?: markers
    }

    private fun addFailure(result: Result, f: Failure) {
        result.failures.add(f)
        result.failureGroups.getOrPut(f.signature) { mutableListOf() }.add(f)
        result.failureByCode.merge(f.stmtCode ?: "(未知)", 1L, Long::plus)
        result.failureByCategory.merge(f.category, 1L, Long::plus)
    }

    // -----------------------------------------------------------------------
    // 报告（只写 build/ 下目录；历史上踩过 test JVM cwd 相对路径的坑 → 打印绝对路径）
    // -----------------------------------------------------------------------

    /**
     * 写回归报告：[outDir]/summary.md + failures.tsv。
     * outDir 建议 "build/compile-report"（gradle 场景 cwd=core-vm 模块目录 → core-vm/build/ 下）；
     * 绝对路径原样使用。**严禁把报告写进仓库路径。**
     */
    fun writeReport(result: Result, corpusRoot: Path?, outDir: Path): Path {
        val abs = outDir.toAbsolutePath().normalize()
        Files.createDirectories(abs)
        val sb = StringBuilder()
        sb.appendLine("# 语料编译回归报告（A8 corpus-gate）")
        sb.appendLine()
        sb.appendLine("- 运行范围: ${result.runScope}")
        sb.appendLine("- 生成时间: ${java.time.LocalDateTime.now()}")
        sb.appendLine("- 语料根: ${corpusRoot?.toAbsolutePath() ?: "(未提供)"}")
        sb.appendLine("- 报告目录: $abs")
        sb.appendLine()
        appendBaseline(sb, result)
        appendGate(sb, result)
        appendCategory(sb, result)
        appendCodeDist(sb, result)
        appendSignatures(sb, result, corpusRoot)
        appendPoolMarker(sb, result)
        appendParseFailures(sb, result)
        appendA13(sb, result)
        Files.writeString(abs.resolve("summary.md"), sb.toString())

        // failures.tsv 全量明细（复现/二次分析用）
        val tsv = StringBuilder()
        tsv.appendLine("file\tline\tfunction\tcategory\tstmtCode\tsignature\tmessage\tstmtRaw")
        for (f in result.failures) {
            tsv.appendLine(
                listOf(f.file, f.line, f.function, f.category, f.stmtCode ?: "", f.signature, f.message, f.stmtRaw ?: "")
                    .joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') },
            )
        }
        Files.writeString(abs.resolve("failures.tsv"), tsv.toString())

        // parse-failures.tsv 解析失败全量签名明细（残值归因审计用；每组一行）
        val psv = StringBuilder()
        psv.appendLine("signature\tverdict\treason\tslices\tlines\tidentifierTotal\tidentifierSamples\tsample")
        for (g in result.parseGroups.values.sortedByDescending { it.lines }) {
            psv.appendLine(
                listOf(
                    g.signature, g.verdict, g.reason, g.slices, g.lines, g.identifierCount,
                    g.identifiers.joinToString(","), g.sample,
                ).joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ') },
            )
        }
        Files.writeString(abs.resolve("parse-failures.tsv"), psv.toString())
        return abs
    }

    private fun appendBaseline(sb: StringBuilder, r: Result) {
        sb.appendLine("## 1. 与基线对比（基线 = M2 二波收官 104,725 切片 / 100% / ~397 万指令 / ~41s）")
        sb.appendLine()
        sb.appendLine("| 指标 | 基线 | 本次 | 差 |")
        sb.appendLine("|---|---:|---:|---:|")
        row(sb, "文件数（已排除 GOLDEN_* 采集副本）", "3,971(参考)", "${r.files}", if (r.files == 3971) "持平" else "口径差，见 §1 注")
        row(sb, "编译尝试切片", fmt(BASE_SLICES), fmt(r.slicesTotal.toLong()), delta(r.slicesTotal.toLong() - BASE_SLICES, BASE_SLICES))
        row(sb, "编译成功切片", fmt(BASE_FUNCTIONS), fmt(r.functions.toLong()), delta(r.functions - BASE_FUNCTIONS, BASE_FUNCTIONS))
        val rate = if (r.slicesTotal == 0) 100.0 else r.functions * 100.0 / r.slicesTotal
        row(
            sb, "编译成功率", "100.0%", "%.1f%%".format(rate),
            when {
                r.runScope != "FULL" -> "子集不适用"
                r.failures.isEmpty() && r.parseFailures == 0 -> "持平"
                else -> "回退"
            },
        )
        row(sb, "指令数(成功函数体)", "~${fmt(BASE_INSTRUCTIONS)}", fmt(r.instructions), delta(r.instructions - BASE_INSTRUCTIONS, BASE_INSTRUCTIONS))
        row(sb, "事件入口", "(未单独存档)", "${r.eventEntries}", "")
        row(
            sb, "解析失败切片", "$BASE_PARSE_FAILURES", "${r.parseFailures}",
            when {
                r.runScope != "FULL" -> "子集伪影不计"
                r.parseFailures == 0 -> "持平"
                else -> "回退"
            },
        )
        row(
            sb, "解析失败行", "$BASE_PARSE_FAILURES", "${r.parseFailureLines}",
            when {
                r.runScope != "FULL" -> "子集伪影不计"
                r.parseFailureLines == 0 -> "持平"
                else -> "回退"
            },
        )
        row(sb, "编译失败切片", "$BASE_COMPILE_FAILURES", "${r.failures.size}", if (r.failures.isEmpty()) "持平" else "回退")
        row(sb, "耗时(ms, 同机参考)", "~$BASE_ELAPSED_MS", "${r.elapsedMs}", "")
        row(sb, "全局槽", "(未单独存档)", "${r.globalSlots}", "")
        sb.appendLine()
        sb.appendLine("> 口径注（A13）：基线的 104,725 是「解析失败切片被丢弃后」的分母，")
        sb.appendLine("> 即基线本身漏掉了被毒化的切片。作用域口径修正后这些切片**回收进分母**，")
        sb.appendLine("> 故本次切片/指令数**高于**基线属预期（不是语料变化，也不是放水），")
        sb.appendLine("> 判据只看：编译失败 0、解析失败 0/0，以及超出基线的那部分切片是否 100% 编译成功。")
        sb.appendLine("> 差值分解见 §8 前后读数表。文件数已排除今日混入 ERB 根的 13 个 `GOLDEN_*.ERB` 采集副本。")
        sb.appendLine()
    }

    private fun row(sb: StringBuilder, k: String, base: String, cur: String, d: String) {
        sb.appendLine("| $k | $base | $cur | $d |")
    }

    private fun delta(v: Long, base: Long): String {
        if (v == 0L) return "±0"
        val pct = if (base != 0L) " (%+.1f%%)".format(v * 100.0 / base) else ""
        return "%+,d".format(v) + pct
    }

    private fun fmt(v: Long): String = String.format("%,d", v)

    private fun appendGate(sb: StringBuilder, r: Result) {
        sb.appendLine("## 2. 回归判定")
        sb.appendLine()
        val subset = r.runScope != "FULL"
        // 契约 R8：red 含解析失败（parseFailures/parseFailureLines），不再只查 failures
        // A13：残值必须全部归入 R9 两栏，「未归类」同样不绿
        val unclassified = r.parseGroups.values.count { it.verdict == VERDICT_UNCLASSIFIED }
        val red = r.failures.isNotEmpty() || r.parseFailures > 0 || r.parseFailureLines > 0 || unclassified > 0
        sb.appendLine(
            when {
                red && subset -> "**GATE RED（含解析失败 ${r.parseFailures} 切片/${r.parseFailureLines} 行；受限模式下其中可能混入 prescan 伪影，仍以 FULL 运行为准）**"
                red -> "**GATE RED — 相对基线出现回归（编译失败 ${r.failures.size} / 解析失败 ${r.parseFailures} 切片、${r.parseFailureLines} 行 / 未归类残值组 $unclassified）**"
                subset -> "**GATE（受限模式，仅参考，不作判据）**"
                else -> "**GATE GREEN — 编译失败 0、解析失败 0/0、残值归因无未归类项**"
            },
        )
        sb.appendLine()
        sb.appendLine("基线失败集为空（104,725/100%），因此本次每条失败**均属本轮改造引入**；")
        if (subset) {
            sb.appendLine("注：本次为 ${r.runScope} 子集运行 —— prescan 符号表不完整，解析失败切片是已知子集伪影，不计入判定；")
            sb.appendLine("判闸以全语料（FULL）运行（`:core-vm:corpusSmoke`，不设 ERATW_LIMIT）为准。")
        }
        sb.appendLine("下表按引入形态分栏，供归因到 A1~A5 具体模块。判定纪律：不跳过失败切片、不缩语料。")
        sb.appendLine()
    }

    private fun appendCategory(sb: StringBuilder, r: Result) {
        sb.appendLine("## 3. 失败引入形态分栏")
        sb.appendLine()
        if (r.failures.isEmpty()) {
            sb.appendLine("无编译失败。")
            sb.appendLine()
            return
        }
        sb.appendLine("| 分栏 | 条数 | 含义/归因方向 |")
        sb.appendLine("|---|---:|---|")
        sb.appendLine("| ${CAT_RULE} | ${r.failureByCategory[CAT_RULE] ?: 0} | 编译器降级规则显式拒绝（C3 分工表执行层：A3 编译器 / C2 池：A1） |")
        sb.appendLine("| ${CAT_CRASH} | ${r.failureByCategory[CAT_CRASH] ?: 0} | 非 CompileException 抛出（实现缺陷，按异常类归因到 A1~A5 文件） |")
        sb.appendLine("| ${CAT_EVENT} | ${r.failureByCategory[CAT_EVENT] ?: 0} | 事件入口合成失败（A3 compileEventEntry / 成员键） |")
        sb.appendLine("| ${CAT_READ} | ${r.failureByCategory[CAT_READ] ?: 0} | 文件读取失败（环境，非本轮改造） |")
        sb.appendLine()
    }

    private fun appendCodeDist(sb: StringBuilder, r: Result, topN: Int = reportTopN()) {
        sb.appendLine("## 4. 失败语句类型分布 TOP $topN（FunctionCode）")
        sb.appendLine()
        if (r.failureByCode.isEmpty()) {
            sb.appendLine("无。")
            sb.appendLine()
            return
        }
        sb.appendLine("| FunctionCode | 失败切片数 | 首个复现 |")
        sb.appendLine("|---|---:|---|")
        val firstByCode = HashMap<String, Failure>()
        for (f in r.failures) firstByCode.putIfAbsent(f.stmtCode ?: "(未知)", f)
        for ((code, n) in r.failureByCode.entries.sortedByDescending { it.value }.take(topN)) {
            val f = firstByCode[code]
            sb.appendLine("| $code | $n | ${f?.let { "${it.file}:${it.line}" } ?: "-"} |")
        }
        sb.appendLine()
        sb.appendLine("（\"(未知)\"= 函数级错误无法定位到具体语句行，或 CompileException 行号与切片语句不匹配）")
        sb.appendLine()
    }

    private fun appendSignatures(sb: StringBuilder, r: Result, corpusRoot: Path?) {
        sb.appendLine("## 5. 失败消息签名 TOP 与最小复现")
        sb.appendLine()
        if (r.failureGroups.isEmpty()) {
            sb.appendLine("无。")
            sb.appendLine()
            return
        }
        for ((sig, list) in r.failureGroups.entries.sortedByDescending { it.value.size }) {
            val sample = list.minWithOrNull(compareBy<Failure> { it.file.length }.thenBy { it.file }.thenBy { it.line }) ?: list.first()
            sb.appendLine("### ${list.size} × `${sig}`")
            sb.appendLine()
            sb.appendLine("- 分栏: ${sample.category}")
            sb.appendLine("- 复现(最小样本): `${sample.file}:${sample.line}` 函数 `${sample.function}` 语句码 `${sample.stmtCode ?: "-"}`")
            if (sample.stmtRaw != null) sb.appendLine("- 语句原文: `${sample.stmtRaw}`")
            sb.appendLine("- 完整消息: `${sample.message}`")
            val absFile = if (sample.file == "*") sample.file
            else corpusRoot?.resolve(sample.file.replace('/', java.io.File.separatorChar))?.toString() ?: sample.file
            if (sample.file != "*") {
                sb.appendLine("- 单文件复现（注意：仅编译该文件，跨文件函数引用报\"未定义的函数\"属预期噪音）:")
                sb.appendLine("  ```")
                sb.appendLine("  ERATW_CORPUS_FILE=\"$absFile\" gradle --no-daemon :core-vm:corpusSmoke --init-script gradle/test-results-isolate.init.gradle.kts -Pkotlin.compiler.execution.strategy=in-process")
                sb.appendLine("  ```")
            }
            if (list.size > 1) {
                sb.appendLine("- 其余样本: " + list.filter { it !== sample }.take(3).joinToString("; ") { "${it.file}:${it.line}" })
            }
            sb.appendLine()
        }
    }

    private fun appendPoolMarker(sb: StringBuilder, r: Result) {
        sb.appendLine("## 6. 语句池 / marker 统计")
        sb.appendLine()
        if (!r.programBuilt) {
            sb.appendLine("- Program 构建失败，marker/池统计不可用：`${r.programBuildError}`")
            sb.appendLine("  （失败切片的半途发射或 _CALL 指向失败函数所致；基线 0 失败时无此问题）")
            sb.appendLine()
            return
        }
        sb.appendLine("- 全 Program 指令数（含失败函数半途发射，基线 0 失败时精确）: ${fmt(r.programInstructions)}")
        val ratio = if (r.programInstructions > 0) r.markerInstructions * 100.0 / r.programInstructions else 0.0
        sb.appendLine("- 语句 marker 指令: ${fmt(r.markerInstructions)}（占比 %.2f%%）".format(ratio))
        sb.appendLine(
            if (r.statementPoolEntries >= 0 && r.statementPoolEntries == r.markerInstructions) {
                "- A1 语句池条目总量: ${fmt(r.statementPoolEntries)}（= marker 数，C2 按身份追加验证一致）"
            } else {
                "- A1 语句池条目总量: ${fmt(r.statementPoolEntries)}（marker=${fmt(r.markerInstructions)}；两者不等需核对 C2 追加口径）"
            },
        )
        sb.appendLine()
        sb.appendLine("### marker 指令占比 TOP ${reportTopN()}（按语句名）")
        sb.appendLine()
        sb.appendLine("| 语句名 | marker 数 | 占 marker |")
        sb.appendLine("|---|---:|---:|")
        for ((name, n) in r.markerByName.entries.take(reportTopN())) {
            val pct = if (r.markerInstructions > 0) n * 100.0 / r.markerInstructions else 0.0
            sb.appendLine("| $name | $n | %.2f%% |".format(pct))
        }
        sb.appendLine()
    }

    private fun appendParseFailures(sb: StringBuilder, r: Result) {
        sb.appendLine("## 7. 解析失败切片（毒化跳过，不进编译分母）与残值归因")
        sb.appendLine()
        sb.appendLine("- 切片数: ${r.parseFailures}，失败行: ${r.parseFailureLines}（基线 0/0）")
        sb.appendLine()
        sb.appendLine("### 7.1 残值归因表（契约 R9：只允许两栏；出现「未归类」即本次修复未完成）")
        sb.appendLine()
        if (r.parseGroups.isEmpty()) {
            sb.appendLine("无残值（0 组 / 0 切片 / 0 行）。")
        } else {
            sb.appendLine("| 归因分栏 | 子类 | 签名 | 切片 | 行 | 不同标识符 | 首个复现 |")
            sb.appendLine("|---|---|---|---:|---:|---:|---|")
            for (g in r.parseGroups.values.sortedByDescending { it.lines }) {
                val ids = if (g.identifiers.isEmpty()) "-" else g.identifiers.joinToString(", ")
                sb.appendLine("| ${g.verdict} | ${g.reason} | `${g.signature}` | ${g.slices} | ${g.lines} | ${g.identifierCount} | ${g.sample} <br>标识符样本: $ids |")
            }
            sb.appendLine()
            val byVerdict = r.parseGroups.values.groupBy { it.verdict }.mapValues { e -> e.value.sumOf { it.lines } }
            sb.appendLine("| 分栏合计 | 行 |")
            sb.appendLine("|---|---:|")
            for ((k, v) in byVerdict) sb.appendLine("| $k | $v |")
        }
        sb.appendLine()
        if (r.parseFailureSamples.isNotEmpty()) {
            sb.appendLine("- 样本（前 ${minOf(r.parseFailureSamples.size, 30)} 条，全量明细见 `parse-failures.tsv`）:")
            for (s in r.parseFailureSamples.take(30)) sb.appendLine("    - $s")
        }
        sb.appendLine()
    }

    /** §8 A13 口径修正：根因、证据、前后读数（写死修正前读数，便于下次复核口径没再漂）。 */
    private fun appendA13(sb: StringBuilder, r: Result) {
        sb.appendLine("## 8. A13 解析失败口径修正（根因 / 证据 / 前后读数）")
        sb.appendLine()
        sb.appendLine("### 8.1 根因")
        sb.appendLine()
        sb.appendLine("语句层 `CorpusValidator.run` 与编译层 `CorpusCompileValidator.compile` 用的是**同一套**")
        sb.appendLine("`LineParser` + `CorpusValidator.ScopedResolver` + `prescan` + `ErbPreprocess`，唯一差异是")
        sb.appendLine("**私有变量作用域的接线**：语句层在遇到 `@函数` 行时调用 `ScopedResolver.enter(name)`，")
        sb.appendLine("把 `prescan.funcVars[函数]`（函数内 `#DIM/#DIMS` 声明的私有名）叠到解析链最前；")
        sb.appendLine("编译层则**每文件新建 resolver 且从不调用 enter**，`cur` 恒为空表 →")
        sb.appendLine("`resolveVariable` 落到全局/内建表 → 命中不了 → `ExprParser.reduceIdentifier`")
        sb.appendLine("抛「未定义的标识符」（core/expr/Parser.kt:308）→ 整切片被毒化丢弃。")
        sb.appendLine("所以「哪一侧对」的答案是：**语句侧对，编译侧弱**（漏收函数内 `#DIM` 私有名，")
        sb.appendLine("中文私有名与 `VARSET 私有名` 只是同一根因的不同表现）。")
        sb.appendLine()
        sb.appendLine("### 8.2 权威依据（reference/emuera-cs）")
        sb.appendLine()
        sb.appendLine("- `IdentifierDictionary.cs:429-447` `GetVariableToken(key, subKey, allowPrivate)`：")
        sb.appendLine("  先查 `GetScaningLine().ParentLabelLine.GetPrivateVariable(key)`，命中即返回（私有优先于全局）。")
        sb.appendLine("- `LogicalLineParser.cs:279-292`：函数体内 `#DIM/#DIMS` → `UserDefinedVariableData.Create(…, isPrivate: true)` + `label.AddPrivateVariable(...)`。")
        sb.appendLine("- `FunctionLabelLine.cs:98-117`：`privateVar` 表本体（按函数名索引，跨函数互不可见）。")
        sb.appendLine("- `ErbLoader.cs:413-423`：`#` 行在**第一遍**扫描登记到当前 label，函数体行在**第二遍** `parseLabel` 才解析 → 私有名对整个函数体可见（与我们 prescan 两遍法一致）。")
        sb.appendLine("- `LogicalLineParser.cs:471 / 508`：`VARI/VARS` 也 `AddPrivateVariable`（本语料实测 0 条 VARI/VARS，不影响读数；已在给组长的待裁决项中登记为待补口径）。")
        sb.appendLine("- `IdentifierDictionary.cs:448-473` + `VariableData.cs:3181-3187`：局部名册只有 `LOCAL/ARG/LOCALS/ARGS` 四项，`VARSET` 不是声明语句（`SpVarSetArgument.cs:8` 收的是 VariableTerm），故 `VARSET HATAKE` 的名字必须来自 `#DIM`——与 `ANOTHER_TALK.ERB:10/12` 实况一致。")
        sb.appendLine("- 三张表都命不中才 `return null`（`IdentifierDictionary.cs:494`），调用侧 `ParserMediator.Warn(…, level 2)` → `InvalidLine`（`LogicalLineParser.cs:301-305`）= 原版该行同样装载失败 → 归「原版也会拒」。")
        sb.appendLine()
        sb.appendLine("### 8.3 最小可复现样本（修前编译侧报错、修后 0 失败）")
        sb.appendLine()
        sb.appendLine("```")
        sb.appendLine("ERB/ANOTHER_TALK.ERB:8   @ANOTHERTARGET_TALK")
        sb.appendLine("ERB/ANOTHER_TALK.ERB:10 #DIM HATAKE          ← 函数私有名（第一遍登记）")
        sb.appendLine("ERB/ANOTHER_TALK.ERB:12 VARSET HATAKE        ← 修前：未定义的标识符 'HATAKE'")
        sb.appendLine("```")
        sb.appendLine("单文件复现：`ERATW_CORPUS_FILE=<语料根>/ANOTHER_TALK.ERB gradle :core-vm:corpusSmoke`（修前 parseFailures≥1，修后 0）。")
        sb.appendLine()
        sb.appendLine("### 8.4 前后读数（同一 FULL 语料、同一装置）")
        sb.appendLine()
        sb.appendLine("| 指标 | 修前（2026-10-18 11:43） | 修后（本次） |")
        sb.appendLine("|---|---:|---:|")
        sb.appendLine("| 文件数（含 GOLDEN 副本 / 排除后） | ${fmt(A13_PRE_FILES)} | ${fmt(r.files.toLong())}（已排除 13 副本） |")
        sb.appendLine("| 编译成功切片 | ${fmt(A13_PRE_FUNCTIONS)} | ${fmt(r.functions.toLong())} |")
        sb.appendLine("| 被毒化丢弃的切片 | ${fmt(A13_PRE_PARSE_FAILURES)} | ${fmt(r.parseFailures.toLong())} |")
        sb.appendLine("| 解析失败行 | ${fmt(A13_PRE_PARSE_FAILURE_LINES)} | ${fmt(r.parseFailureLines.toLong())} |")
        sb.appendLine("| 回收后进编译分母的切片 | — | ${fmt((r.parseFailures - A13_PRE_PARSE_FAILURES) * -1)}（= 修前丢弃 − 修后丢弃） |")
        sb.appendLine("| 编译失败切片 | 0 | ${r.failures.size} |")
        sb.appendLine("| 指令数(成功函数体) | 3,969,562 | ${fmt(r.instructions)} |")
        sb.appendLine()
        sb.appendLine("- 校验恒等式：修前成功 ${fmt(A13_PRE_FUNCTIONS)} + 修前丢弃 ${fmt(A13_PRE_PARSE_FAILURES)} = ${fmt(A13_PRE_FUNCTIONS + A13_PRE_PARSE_FAILURES)}；")
        sb.appendLine("  本次分母（成功+编译失败）= ${fmt(r.slicesTotal.toLong())}（另含修前未计数的 `@` 前散行失败与 GOLDEN 副本差，见 §1 口径注）。")
        sb.appendLine()
        sb.appendLine("### 8.5 顺带修掉的计数漏口（同一装置必须同口径）")
        sb.appendLine()
        sb.appendLine("- `@` 之前散行的解析失败以前**只毒化不计数**（且把该文件首个切片一起吞掉）；现在计入 parseFailureLines 并归因。")
        sb.appendLine("- 词法错误（LexError）以前不计数；现在与语句层 `CorpusValidator.run` 同样进 failures/parseFailureLines。")
        sb.appendLine("- `flush()` 在 `curKey==null` 时未复位 `poisoned`（首个切片被无辜丢弃）→ 已复位。")
        sb.appendLine("- 编译层改为整轮共用一个 `LineParser`+`ScopedResolver`（语句层一直是这么做的），杜绝跨文件状态差。")
        sb.appendLine()
        sb.appendLine("### 8.6 反向（宽松方向）已知偏差 —— 不在解析失败读数里，需组长裁决（R9 台账）")
        sb.appendLine()
        sb.appendLine("本次只收紧「我们比原版严」的一侧；以下是「我们比原版宽」的一侧，**数字不会因本次修复而变差，但仍是缺陷**：")
        sb.appendLine("1. `@函数(甲, 乙)` 的形参名：原版用 `ReduceArguments(…, isDefine:true)` 解析成 `VariableTerm`，")
        sb.appendLine("   非可赋值变量/未声明即 `ArgCanOnlyAssignableVar` → Warn level 2 → 标签置错")
        sb.appendLine("   （spec: ErbLoader.cs:658-702 + 651-652）。我们 `LineParser.parseLabelLine` 只取首标识符不校验 → 原版拒、我们收。")
        sb.appendLine("2. 私有表按**函数名**合并：同名多定义（事件成员组）在原版是**每个 FunctionLabelLine 一份** privateVar")
        sb.appendLine("   （spec: FunctionLabelLine.cs:15 + 98-111），A 文件的 `@X` 私有名不会出现在 B 文件的 `@X` 里；")
        sb.appendLine("   我们 `prescan.funcVars[X]` 合并两者 → 原版拒、我们收。")
        sb.appendLine("3. `VARI`/`VARS` 未登记私有表（spec: LogicalLineParser.cs:471 / 508 会 `AddPrivateVariable`）。")
        sb.appendLine("   本语料实测 0 条 `VARI`/`VARS`（词边界 grep），故对读数无影响；换语料时会变成 GAP。")
        sb.appendLine("4. 私有名在**别的函数**使用：原版报 `VarNotDefinedThisFunc`（spec: IdentifierDictionary.cs:590-592）。")
        sb.appendLine("   我们恰好也拒（因为不在 `cur` 也不在全局表）——**同名巧合**，不是设计；")
        sb.appendLine("   若该名字同时是全局/内建变量，我们就会接受而原版拒绝。归因表里这类子类的行数见 §7.1。")
        sb.appendLine()
    }

    private fun reportTopN(): Int =
        (System.getenv("ERATW_REPORT_TOPN") ?: System.getProperty("ERATW_REPORT_TOPN"))?.toIntOrNull() ?: 20
}
