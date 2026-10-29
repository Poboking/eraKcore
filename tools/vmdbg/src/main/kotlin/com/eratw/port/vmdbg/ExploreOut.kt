package com.eratw.port.vmdbg

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionProbe
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.StatementProbe
import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * 发现类别（CONTRACT-DBG6 §4.E2.2 四类；findings.jsonl 的 kind 字段取 [name]）。
 *
 * 与 OutcomeKind 的映射见 [ExploreOut.findingKindOf]：CRASH/WALL_LIMIT/STEP_LIMIT 三类由
 * 驱动器在终态时显式落账；CODEEE_FLOOD 由台账对 console 尾的「错误」行连续计数自动判定。
 */
enum class FindingKind { CRASH, WALL_LIMIT, STEP_LIMIT, CODEEE_FLOOD }

/**
 * boot 食谱行渲染（与 E1 的 --boot 食谱同构，CONTRACT-DBG6 §4.E1.4：每行 `输入 ;; 期望子串`、
 * `#` 注释）。replay 文件与 [parseBootRecipeLine] 共用本语法，保证可往返（§4.E2.3④）。
 *
 * - 分隔符两侧各一空格（`输入 ;; 注记`，合同 §4.E1.4 示意格式；解析两侧 trim，不敏感）；
 * - 输入为空（PassAnyKey）时**必须**带 `;;` 分隔符，否则与空行/注释无法区分；
 * - 注记为空且输入非空 = 裸输入行（不落悬挂分隔符）；
 * - 渲染不保证首尾空白保真（解析按 trim 口径，食谱是手写文件格式）；
 * - FeedStr 取值含 `;;` 或以 `#` 开头时无法无损表达（语法歧义）——此类行重放读回会被
 *   拆错，权威记录在 findings.jsonl 的 chain 数组，replay 文件是尽力而为的重放链。
 */
fun renderRecipeLine(input: String, note: String): String {
    if (input.isNotEmpty() && note.isEmpty()) return input
    return input + " ;;" + if (note.isEmpty()) "" else " " + note
}

/**
 * 解析一行 boot 食谱：返回 (输入, 注记)；空行与 `#` 注释行返回 null。
 * `;;` 首现处拆分，两侧各 trim；无分隔符整行作为输入、注记为空串。
 */
fun parseBootRecipeLine(line: String): Pair<String, String>? {
    val t = line.trim()
    if (t.isEmpty() || t.startsWith("#")) return null
    val sep = t.indexOf(";;")
    return if (sep < 0) Pair(t, "") else Pair(t.substring(0, sep).trim(), t.substring(sep + 2).trim())
}

/** 整份食谱文本解析（逐行走 [parseBootRecipeLine]，跳过注释/空行）。 */
fun parseBootRecipe(text: String): List<Pair<String, String>> =
    text.lineSequence().mapNotNull { parseBootRecipeLine(it) }.toList()

/**
 * explore 的落盘台账（CONTRACT-DBG6 §4.E2.2）。四个产物，全部在 [outDir] 下：
 *
 * - **explore.jsonl** —— 逐步台账，一行一步（Jsonl 平面对象）：
 *   `ts`(epoch 毫秒) / `episode` / `fp`(指纹) / `req`(request 摘要) / `btns`(按钮值逗号串) /
 *   `plan`(候选动作计划，[exploreActionLabel] 竖线连接) / `action` / `outcome`(OutcomeKind 名)。
 * - **findings.jsonl** —— 发现台账（[FindingKind] 四类），每条带完整输入链与 console 尾；
 *   数组字段（chain/chainTimeout/consoleTail）手拼 JSON（Jsonl.obj 只出平面对象，
 *   手拼先例 = CmdSnap.buildSidecar）。行字段：
 *   `ts` / `kind` / `episode` / `fp` / `req` / `stackTop` / `detail` / `replay`(相对路径) /
 *   `chain`(整链食谱行字符串数组) / `chainTimeout`(逐位 asTimeout) / `consoleTail`。
 * - **replay/<n>.txt** —— 每条发现一个可重放链：种子以来全部已喂输入按食谱语法逐行落盘
 *   （含引导链与探索步），直接喂给 --boot 格式复用；编号 = 发现序号（--resume 时续号）。
 *   注意：FeedInt(asTimeout=true) 的超时位在 replay 行里退化成普通取值（TINPUT 超时交付的
 *   就是该值，ISTIMEOUT 敏感的分支要复现需人工限时）——位本体保真在 chainTimeout。
 * - **coverage.txt** —— 收工（[close]）时写：executed 函数/语句集计数 + 对照 sweep 基线
 *   12.3% 的增量说明 + sweep 台账计数级对照（[sweepLedgers] 存在才对照，缺席留 TODO(verify)
 *   并注明数据源路径）+ 逐函数 covered/stmts 表（探针挂法对照 Sweep.kt:135-138）。
 *
 * **完整输入链**由本类自维护：[logBootStep]（引导链步）与 [logStep]（探索步）逐次追加，
 * 弃局（Abandon）不喂输入、不入链 —— 链 = 字面喂过的输入序列，restore 不回删（重放语义）。
 * 洪泛（CODEEE_FLOOD）在 [logStep] 内自动判定与去重（同一连续洪泛段只记一条）。
 *
 * 线程口径（ADR-003 的工程化修订）：链与写句柄的正常写入点都在驱动器锁内（泵线程 sink 回调 /
 * 喂入线程队列面互斥），但驱动器终态收口（异常折发现）不持驱动器锁也能到达 —— 本类因此自带
 * 一把内部互斥锁（[mutex]），全部可变态经它串行；引擎严格串行保证锁近乎无竞争，代价可忽略。
 * 覆盖计数（[coverage]）仍按无同步口径：探针回调只在 VM 线程（引擎串行的执行线程）发生，
 * 读数点在驱动器收工（线程已 join，happens-before 成立）。
 */
class ExploreOut(
    private val outDir: Path,
    /** 语句总数与逐函数分母的来源；null 时 coverage.txt 不出百分比。 */
    private val program: Program? = null,
    /** 函数 key → 指令区间下标的侧车（Assembler.Assembled.metadata.functions）；空表 = 无逐函数分母。 */
    private val functions: List<FuncMeta> = emptyList(),
    /** 「错误」行连续阈值（CONTRACT-DBG6 §4.E2.2 钉 N=5）。 */
    private val floodThreshold: Int = DEFAULT_FLOOD_THRESHOLD,
    /** true = 续跑：不删旧产物、findings 编号续前（replay 编号 = 既有 findings 行数起）。
     *  链不跨进程合并：resume 是新进程新种子（引导链重走），本实例的链从本进程的
     *  logBootStep/logStep 重新累积 —— 新发现的链 = 本进程种子以来序列，口径不变。 */
    private val resume: Boolean = false,
    /**
     * sweep 台账候选（coverage.txt 的对照数据源；DBG7 接线）：元素是文件或目录（目录取其下
     * sweep.jsonl），按序探测、首个存在的生效。文件存在才对照（计数级，per-function
     * covered/stmts —— 真机 hawkeye 台账 report.jsonl 无这两个字段，对照不了）；
     * 全部缺席则 coverage.txt 留 TODO(verify) 并注明已探测路径。
     */
    private val sweepLedgers: List<Path> = emptyList(),
) {

    companion object {
        /** 洪泛阈值缺省（合同钉 N=5）。 */
        const val DEFAULT_FLOOD_THRESHOLD = 5

        /**
         * 洪泛判定的错误行标记（合同 §4.E2.2：「错误」行连续 ≥N）。
         *
         * DBG7 闭环（原 TODO(verify) 的实证结论，标记维持「错误」不变）：
         * - **命中**本引擎唯一的控制台错误通道：`printError` 全库仅一落点，SAVEDATA 意外错误
         *   文案「SAVEDATA命令在存档时发生了意料之外的错误」含「错误」（core-vm
         *   sem/StatementExecutor.kt:1038）；
         * - **命中**原版错误控制台行模板：C# 侧脚本错误全走 `console.PrintError`
         *   （Process.cs:618-653），中文模板「{0}错误发生在:{1}」「错误内容:{0}」含「错误」
         *   （emuera-zhs.xml:696/698）——若将来补齐脚本错误的控制台落印通道，同针适用；
         * - **不命中**「由于ERB脚本出现无法解释的行，Emuera停止运行」（emuera-zhs.xml:1021
         *   的宿主停机文案，FLOW.md §3 引）——它不是控制台错误行，且本引擎的 CodeEE 以异常
         *   上抛给宿主（不落 console，无脚本错误 printError 通道），不产生需要本针捕获的错误行。
         */
        const val ERROR_MARKER = "错误"

        const val EXPLORE_JSONL = "explore.jsonl"
        const val FINDINGS_JSONL = "findings.jsonl"
        const val COVERAGE_TXT = "coverage.txt"
        const val REPLAY_DIR = "replay"

        /** sweep 台账文件名（sweepLedgers 候选为目录时取其下此文件；CLI 缺省候选 = outDir 下此名）。 */
        const val SWEEP_LEDGER_NAME = "sweep.jsonl"

        /** sweep 全程序语句覆盖率基线（CONTRACT-DBG6 §4.E2.2 钉值 12.3%）。 */
        const val SWEEP_BASELINE_PCT = 12.3

        /**
         * OutcomeKind → 发现类别（驱动器的显式落账入口用它分流）：
         * CRASH/WALL_LIMIT/STEP_LIMIT 同名映射；ADVANCED/ABANDONED/EPISODE_DONE 非发现，null。
         */
        fun findingKindOf(outcome: OutcomeKind): FindingKind? = when (outcome) {
            OutcomeKind.CRASH -> FindingKind.CRASH
            OutcomeKind.WALL_LIMIT -> FindingKind.WALL_LIMIT
            OutcomeKind.STEP_LIMIT -> FindingKind.STEP_LIMIT
            else -> null
        }

        /**
         * console 尾里含 [marker] 的最长连续行数（[ExploreOut] 的洪泛判据：
         * 连续 ≥ 阈值即 CODEEE_FLOOD）。纯函数，判定矩阵可测（§4.E2.3③）。
         */
        fun maxConsecutiveErrorLines(tail: List<String>, marker: String = ERROR_MARKER): Int {
            var best = 0
            var cur = 0
            for (line in tail) {
                if (line.contains(marker)) {
                    cur++
                    if (cur > best) best = cur
                } else {
                    cur = 0
                }
            }
            return best
        }

        /** request 摘要（explore.jsonl 的 req 字段）：形态/类型 + 缺省 + 限时。 */
        fun requestSummary(wp: WaitPoint): String {
            val req = wp.request
            val head =
                if (wp.anyKey) "ANYKEY/" + req.form.name + "/" + req.type.name
                else req.form.name + "/" + req.type.name
            val def =
                if (!req.hasDef) "-"
                else if (req.type == InputRequest.InputType.INT) req.defInt.toString() else req.defStr
            return head + " def=" + def + " t=" + req.timeLimit
        }
    }

    /** 覆盖计数器（挂卸口见 [CoverageRecorder]；[close] 时随 coverage.txt 落盘）。 */
    val coverage = CoverageRecorder()

    private class ChainStep(val input: String, val note: String, val asTimeout: Boolean)

    /** 内部互斥（线程口径见类 KDoc）：链 / 写句柄 / 洪泛段 / 编号的唯一写仲裁。 */
    private val mutex = Any()

    private val chain = ArrayList<ChainStep>()
    private var opened = false
    private var stepsW: BufferedWriter? = null
    private var findingsW: BufferedWriter? = null
    private var floodActive = false
    private var findingsCount = 0L

    /** 建目录并按 [resume] 语义清理/续接旧产物（幂等；写方法懒开时也会先走这里）。 */
    fun open() {
        synchronized(mutex) {
            if (opened) return
            Files.createDirectories(outDir)
            val replayDir = outDir.resolve(REPLAY_DIR)
            if (resume) {
                Files.createDirectories(replayDir)
                val f = outDir.resolve(FINDINGS_JSONL)
                if (Files.isRegularFile(f)) {
                    findingsCount = Files.readAllLines(f, StandardCharsets.UTF_8).count { it.isNotBlank() }.toLong()
                }
            } else {
                Files.deleteIfExists(outDir.resolve(EXPLORE_JSONL))
                Files.deleteIfExists(outDir.resolve(FINDINGS_JSONL))
                if (Files.isDirectory(replayDir)) {
                    Files.walk(replayDir).use { s ->
                        s.filter { Files.isRegularFile(it) }.forEach { Files.deleteIfExists(it) }
                    }
                }
                Files.createDirectories(replayDir)
            }
            opened = true
        }
    }

    /**
     * 引导链一步（--boot 食谱步或内置链步）：只计进完整输入链（findings/replay 的
     * 种子以来序列），不写 explore.jsonl —— 逐步台账从探索步起记。
     */
    fun logBootStep(input: String, expected: String) {
        synchronized(mutex) { chain.add(ChainStep(input, expected, false)) }
    }

    /**
     * 探索一步：追加输入链（Abandon 不喂输入不入链）+ 写 explore.jsonl 行 +
     * 洪泛自动判定（连续「错误」行达 [floodThreshold] 且不在洪泛段中 → 自动记一条
     * CODEEE_FLOOD；干净的尾会复位洪泛段，同一段不重复记）。
     */
    fun logStep(episode: Long, wp: WaitPoint, action: ExploreAction, outcome: OutcomeKind) {
        synchronized(mutex) {
            open()
            val fp = waitPointFingerprint(wp)
            if (action !is ExploreAction.Abandon) {
                val input = exploreChainInputOf(action) ?: ""
                val asTimeout = (action as? ExploreAction.FeedInt)?.asTimeout == true
                chain.add(ChainStep(input, outcome.name, asTimeout))
            }
            writeLine(stepsWriter(), stepLine(episode, fp, wp, action, outcome))
            val run = maxConsecutiveErrorLines(wp.consoleTail)
            if (run >= floodThreshold) {
                if (!floodActive) {
                    floodActive = true
                    finding(
                        FindingKind.CODEEE_FLOOD,
                        "console 尾连续 " + run + " 行含「" + ERROR_MARKER + "」（阈值 " + floodThreshold + "）",
                        episode,
                        fp,
                        wp,
                        wp.consoleTail,
                    )
                }
            } else {
                floodActive = false
            }
        }
    }

    private fun stepLine(episode: Long, fp: String, wp: WaitPoint, action: ExploreAction, outcome: OutcomeKind): String =
        Jsonl.obj(
            listOf(
                "ts" to System.currentTimeMillis(),
                "episode" to episode,
                "fp" to fp,
                "req" to requestSummary(wp),
                "btns" to wp.buttons.joinToString(",") { it.value.toString() },
                "plan" to exploreCandidateActions(wp).joinToString("|") { exploreActionLabel(it) },
                "action" to exploreActionLabel(action),
                "outcome" to outcome.name,
            ),
        )

    /** 洪泛判定的公开口（驱动器在终态也可显式查）；判定口径 = [maxConsecutiveErrorLines] ≥ 阈值。 */
    fun checkFlood(consoleTail: List<String>): Boolean =
        maxConsecutiveErrorLines(consoleTail) >= floodThreshold

    /**
     * 显式记一条发现（CRASH/WALL_LIMIT/STEP_LIMIT 由驱动器在终态落账，kind 分流用
     * [findingKindOf]）：写 findings.jsonl 行 + 按当前完整输入链写 replay 编号文件。
     * 返回 replay 文件路径。
     */
    fun finding(
        kind: FindingKind,
        detail: String,
        episode: Long = -1L,
        fingerprint: String = "",
        wp: WaitPoint? = null,
        consoleTail: List<String> = emptyList(),
    ): Path = finding(kind.name, detail, episode, fingerprint, wp, consoleTail)

    /**
     * 字符串 kind 版（DBG7 接线口）：驱动器原生发现类（BOOT_FAIL/ILLEGAL_ACTION/
     * REPLAY_DIVERGE/POLICY_CRASH/FEEDER_ERROR）逐字透传 —— 合同 §4.E2.2 的四类是自动分类面，
     * 驱动器显式类的保真优先，不做映射改写。
     */
    fun finding(
        kind: String,
        detail: String,
        episode: Long = -1L,
        fingerprint: String = "",
        wp: WaitPoint? = null,
        consoleTail: List<String> = emptyList(),
    ): Path = synchronized(mutex) {
        open()
        findingsCount++
        val rel = REPLAY_DIR + "/" + findingsCount + ".txt"
        val replayPath = outDir.resolve(REPLAY_DIR).resolve(findingsCount.toString() + ".txt")
        val replayText = chain.map { renderRecipeLine(it.input, it.note) }
            .joinToString("\n", postfix = if (chain.isEmpty()) "" else "\n")
        Files.write(replayPath, replayText.toByteArray(Charsets.UTF_8))
        writeLine(findingsWriter(), findingLine(kind, detail, episode, fingerprint, wp, consoleTail, rel))
        replayPath
    }

    private fun findingLine(
        kind: String,
        detail: String,
        episode: Long,
        fingerprint: String,
        wp: WaitPoint?,
        consoleTail: List<String>,
        replayRel: String,
    ): String = buildString {
        append("{\"ts\":").append(System.currentTimeMillis())
        append(",\"kind\":\"").append(Jsonl.escape(kind)).append('"')
        append(",\"episode\":").append(episode)
        append(",\"fp\":\"").append(Jsonl.escape(fingerprint)).append('"')
        append(",\"req\":\"").append(Jsonl.escape(wp?.let { requestSummary(it) } ?: "")).append('"')
        append(",\"stackTop\":\"").append(Jsonl.escape(wp?.stackTop ?: "")).append('"')
        append(",\"detail\":\"").append(Jsonl.escape(detail)).append('"')
        append(",\"replay\":\"").append(Jsonl.escape(replayRel)).append('"')
        append(",\"chain\":[")
        chain.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append('"').append(Jsonl.escape(renderRecipeLine(s.input, s.note))).append('"')
        }
        append("],\"chainTimeout\":[")
        chain.forEachIndexed { i, s ->
            if (i > 0) append(',')
            append(s.asTimeout)
        }
        append("],\"consoleTail\":[")
        consoleTail.forEachIndexed { i, l ->
            if (i > 0) append(',')
            append('"').append(Jsonl.escape(l)).append('"')
        }
        append("]}")
    }

    /** coverage.txt（计数 + sweep 基线/台账对照 + 逐函数表）；[close] 自动调，也可显式提前落。 */
    fun writeCoverage() {
        synchronized(mutex) {
            ensureDirs()
            Files.writeString(outDir.resolve(COVERAGE_TXT), renderCoverage(), StandardCharsets.UTF_8)
        }
    }

    /** 收工：冲洗并关闭写句柄 + 落 coverage.txt。close 之后不得再记（幂等，二次调用无操作）。 */
    fun close() {
        synchronized(mutex) {
            if (opened) {
                runCatching { stepsW?.flush() }
                runCatching { findingsW?.flush() }
                runCatching { stepsW?.close() }
                runCatching { findingsW?.close() }
                stepsW = null
                findingsW = null
                opened = false
            }
            writeCoverage()
        }
    }

    // ------------------------------------------------------------------
    // 内部：写句柄与渲染
    // ------------------------------------------------------------------

    private fun ensureDirs() {
        Files.createDirectories(outDir)
        Files.createDirectories(outDir.resolve(REPLAY_DIR))
    }

    private fun stepsWriter(): BufferedWriter {
        open()
        if (stepsW == null) {
            stepsW = Files.newBufferedWriter(
                outDir.resolve(EXPLORE_JSONL), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        }
        return stepsW!!
    }

    private fun findingsWriter(): BufferedWriter {
        open()
        if (findingsW == null) {
            findingsW = Files.newBufferedWriter(
                outDir.resolve(FINDINGS_JSONL), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        }
        return findingsW!!
    }

    private fun writeLine(w: BufferedWriter, line: String) {
        w.write(line)
        w.newLine()
        w.flush()
    }

    private fun renderCoverage(): String {
        val sb = StringBuilder()
        val fnKeys = coverage.executedFunctionKeys()
        val stmtTotal = program?.statementCount ?: -1
        val stmtExec = coverage.executedStatementCount()
        sb.append("# explore coverage（CONTRACT-DBG6 E2；探针 = FunctionProbe/StatementProbe，挂法对照 tools/vmdbg 的 Sweep.kt）\n")
        sb.append("executedFunctions=").append(fnKeys.size).append('\n')
        val pct = if (stmtTotal > 0) stmtExec * 100.0 / stmtTotal else -1.0
        sb.append("executedStatements=").append(stmtExec).append('/').append(stmtTotal)
        if (pct >= 0.0) sb.append("（").append(fmt1(pct)).append("%）")
        sb.append('\n')
        sb.append("sweepBaseline=").append(fmt1(SWEEP_BASELINE_PCT)).append("%（CONTRACT-DBG6 §4.E2.2 钉值；sweep 全程序语句池口径）\n")
        if (pct >= 0.0) {
            sb.append("delta=").append(fmt1(pct - SWEEP_BASELINE_PCT)).append("pp（explore 触碰率 − sweep 基线；正值 = 高于基线）\n")
        }
        sb.append('\n')
        renderSweepCompare(sb)
        sb.append('\n')
        sb.append("[functions]\n")
        for (k in fnKeys) {
            val covered = coverage.statementCountOf(k)
            val total = functionMarkerTotal(k)
            sb.append(k).append(" covered=").append(covered).append(" stmts=").append(total)
            if (total > 0) sb.append(" pct=").append(fmt1(covered * 100.0 / total))
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * coverage.txt 的 sweep 对照节（DBG7）：[sweepLedgers] 按序探测，文件存在才对照；
     * 数据源缺席/无覆盖读数则留 TODO(verify) 并注明已探测路径与原因。
     *
     * 对照是**计数级**（per-function distinct 语句数：explore 探针 vs sweep 台账 covered 字段）；
     * pc 位集交集/并集需位级数据，sweep 台账不含（TODO(verify) 常驻）。真机 hawkeye 台账
     * （report.jsonl，字段止于 steps/elapsedMs）与红 J 冒烟现场（只有引导失败三件套）都不是
     * 本对照的数据源 —— 数据源只能是桌面 sweep 的 sweep.jsonl（SweptRecord 落 covered/stmts）。
     */
    private fun renderSweepCompare(sb: StringBuilder) {
        sb.append("[sweep 对照]\n")
        val loaded = loadSweepRows()
        if (loaded == null) {
            sb.append("TODO(verify)：sweep 台账数据源不存在")
            if (sweepLedgers.isEmpty()) {
                sb.append("（--sweep-ledger 未传、CLI 也未给缺省候选）")
            } else {
                sb.append("（已探测：").append(sweepLedgers.joinToString("、") { it.toString() }).append("）")
            }
            sb.append("——跑桌面 sweep --out <目录> 拿 sweep.jsonl（covered/stmts 字段齐），")
            sb.append("用 --sweep-ledger <文件或目录> 重跑 explore 后本节自动对照；")
            sb.append("真机 hawkeye 台账（report.jsonl）无覆盖读数，不能做本对照。\n")
            return
        }
        val (file, rows) = loaded
        sb.append("数据源=").append(file).append("（带 covered/stmts 读数的记录 ").append(rows.size).append(" 条）\n")
        if (rows.isEmpty()) {
            sb.append("TODO(verify)：台账存在但无 covered/stmts 读数（真机 report.jsonl 即此形态）——")
            sb.append("计数对照不可做，拿桌面 sweep 产物重试。\n")
            return
        }
        val byKey = HashMap<String, SweepRow>()
        for (r in rows) byKey[r.key] = r // 同 key 后写胜出（sweep --resume 原地覆写终态记录的口径）
        var both = 0
        var exploreMore = 0
        var sweepMore = 0
        var even = 0
        val diffs = ArrayList<String>()
        for (k in coverage.executedFunctionKeys()) {
            val row = byKey[k] ?: continue
            val mine = coverage.statementCountOf(k)
            both++
            when {
                mine > row.covered -> {
                    exploreMore++
                    diffs.add(k + " explore=" + mine + " sweep=" + row.covered + " (+" + (mine - row.covered) + ")")
                }
                row.covered > mine -> sweepMore++
                else -> even++
            }
        }
        sb.append("口径=计数级（per-function distinct 语句数）；pc 位集交集/并集需位级数据，台账不含 —— TODO(verify)\n")
        sb.append("共测函数=").append(both).append("（explore 执行过且台账有该 key 的覆盖读数）\n")
        sb.append("explore 多触=").append(exploreMore).append("（真实流程触到、sweep 伪造实参直调未触到 —— explore 增量面）\n")
        sb.append("sweep 多触=").append(sweepMore).append(" 持平=").append(even).append('\n')
        if (diffs.isNotEmpty()) {
            sb.append("explore 多触函数（至多列 50 条）:\n")
            diffs.take(50).forEach { sb.append("  ").append(it).append('\n') }
            if (diffs.size > 50) sb.append("  …其余 ").append(diffs.size - 50).append(" 条略\n")
        }
    }

    /** 单条台账记录里参与对照的字段（covered/stmts 皆非负才收；-1 = 区间不可得，不适用）。 */
    private class SweepRow(val key: String, val covered: Int)

    /**
     * 探测 [sweepLedgers]：目录取其下 sweep.jsonl；首个存在的文件逐行解析（[Jsonl.parseLine]
     * 平面对象口径，meta 行无 key 字段自然跳过），返回 (生效文件, 记录表)；一个都不存在返回 null。
     */
    private fun loadSweepRows(): Pair<Path, List<SweepRow>>? {
        for (cand in sweepLedgers) {
            val f = if (Files.isDirectory(cand)) cand.resolve(SWEEP_LEDGER_NAME) else cand
            if (!Files.isRegularFile(f)) continue
            val rows = ArrayList<SweepRow>()
            for (line in Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue
                val m = Jsonl.parseLine(line) ?: continue
                val key = m["key"] ?: continue
                val covered = m["covered"]?.trim()?.toIntOrNull() ?: continue
                val stmts = m["stmts"]?.trim()?.toIntOrNull() ?: continue
                if (covered < 0 || stmts < 0) continue
                rows.add(SweepRow(key, covered))
            }
            return Pair(f, rows)
        }
        return null
    }

    /**
     * 函数 [key] 的语句分母 = 指令区间内 marker 数（op 属于 INTERNAL_COUNT..MAX_STATEMENT_OPCODE）。
     * 区间口径与 Sweep.kt:190-206 同源（函数在 code 上连续，末端 = 下一函数 entryPc）；
     * program/functions 缺席或下标不可得返回 -1（不适用）。
     */
    private fun functionMarkerTotal(key: String): Int {
        val p = program ?: return -1
        val m = functions.firstOrNull { it.key == key } ?: return -1
        val fs = p.functions
        if (m.index !in fs.indices) return -1
        val start = fs[m.index].entryPc
        if (start !in 0 until p.instructionCount) return -1
        val end = (if (m.index + 1 < fs.size) fs[m.index + 1].entryPc else p.instructionCount)
            .coerceIn(start, p.instructionCount)
        var n = 0
        for (pc in start until end) {
            if (p.op(pc) in Opcode.INTERNAL_COUNT..Opcode.MAX_STATEMENT_OPCODE) n++
        }
        return n
    }

    private fun fmt1(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
}

/**
 * 动作 → 输入链条目的「输入」文本（replay/chain 的渲染口径）：
 * FeedInt → 十进制文本；FeedStr → 原文；PassAnyKey → 空串（渲染时恒带 `;;` 分隔，
 * 见 [renderRecipeLine]）；Abandon → null（不喂输入，调用方跳过入链）。
 */
fun exploreChainInputOf(action: ExploreAction): String? = when (action) {
    is ExploreAction.FeedInt -> action.value.toString()
    is ExploreAction.FeedStr -> action.value
    ExploreAction.PassAnyKey -> ""
    is ExploreAction.Abandon -> null
}

/**
 * explore 的覆盖计数器（CONTRACT-DBG6 §4.E2.2：挂 FunctionProbe/StatementProbe 累计
 * executed 函数/语句集）。挂法对照 Sweep.kt:135-138（单槽覆盖式赋值、收工置 null）。
 *
 * - 函数集：[FunctionProbe.onFunctionEnter] 的 meta.key（ProcessRuntime.kt:890 的同步回调；
 *   meta 为 null 的无元数据帧进 nullMeta 计数、不进名字集）；
 * - 语句集：[StatementProbe.onStatement] 的 env.functionKey + env.pc（pc 即语句身份的稳定键，
 *   StatementProbe.kt:51 的 StatementExecutionCounter 同口径；env 是可复用可变对象，
 *   回调内当场复制两字段、不持引用 —— StatementProbe KDoc 的约定）；
 * - 「执行过」不区分成败：error 回调同样计入（与 StatementExecutionCounter 同解）；
 * - 单线程口径（ADR-003）：探针只在 VM 线程回，读数在驱动器收工点（VM 空闲）进行，
 *   内部集合无同步；回调遵守探针的「不得阻塞、不得抛」契约（实现无抛点）。
 */
class CoverageRecorder {

    private val functionKeys = LinkedHashSet<String>()
    private val stmtsByFunction = LinkedHashMap<String, HashSet<Int>>()
    private var nullMetaFrames = 0L

    /**
     * 挂上两个探针（单槽覆盖式：会顶掉 runtime 上已有的探针，与 sweep 同口径）；
     * 配对 [detach] 卸下。重复 attach 即重挂（计数累积不清零）。
     */
    fun attach(rt: ProcessRuntime) {
        rt.functionProbe = FunctionProbe { meta -> onFunctionEnter(meta) }
        rt.statementProbe = StatementProbe { _, _, env, _, _, _ -> onStatement(env.functionKey, env.pc) }
    }

    /** 卸下两个探针（置 null = 生产零行为路径，ProcessRuntime.kt:242/:250）。 */
    fun detach(rt: ProcessRuntime) {
        rt.functionProbe = null
        rt.statementProbe = null
    }

    /** 探针回调（也可测试直喂）：记一个进帧函数。 */
    fun onFunctionEnter(meta: FuncMeta?) {
        if (meta == null) nullMetaFrames++ else functionKeys.add(meta.key)
    }

    /** 探针回调（也可测试直喂）：记一条执行过的语句（函数键 + marker pc）。 */
    fun onStatement(functionKey: String, pc: Int) {
        stmtsByFunction.getOrPut(functionKey) { HashSet() }.add(pc)
    }

    /** executed 函数数（不含 null-meta 帧）。 */
    fun executedFunctionCount(): Int = functionKeys.size

    /** executed 函数键（大写归一，排序输出保证 coverage.txt 稳定）。 */
    fun executedFunctionKeys(): List<String> = functionKeys.toList().sorted()

    /** 无元数据帧计数（诊断用）。 */
    fun nullMetaFrameCount(): Long = nullMetaFrames

    /** 单函数 distinct 执行语句数；该函数没执行过返回 0。 */
    fun statementCountOf(functionKey: String): Int = stmtsByFunction[functionKey]?.size ?: 0

    /** executed 语句总数（pc 归属函数唯一，跨函数求和 = 全集大小）。 */
    fun executedStatementCount(): Int = stmtsByFunction.values.sumOf { it.size }
}
