package com.eratw.port.vmdbg

import com.eratw.port.core.code.FunctionInfo
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.StatementExecutionCounter
import com.eratw.port.core.proc.StatementProbe
import java.io.PrintStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.Locale

/**
 * `sweep`：全量函数清扫（CONTRACT-DBG1 路2 本波核心交付）。
 *
 * ## 清单与过滤
 *
 * 清单 = [com.eratw.port.core.loader.BootMetadata] 的 `functions` 全量，**跳过**：
 * - `isEvent`（事件函数由 BEGIN 状态机驱动，直调会破坏会话状态机 —— 契约规格）；
 * - 编译器合成的「事件入口」（position = `·event-entry·` / `synthetic-event-entry`，
 *   与 ProcessRuntime.kt:411 把它们剔出符号表的判据同源；它们与事件同名，
 *   callFunction 会以事件名报错，纯噪音）；
 * - key 含 `#` 的根合成节（ProcessRuntime.kt:412 的同一剔除判据；ERB 标识符不含 `#`，
 *   不会误伤用户函数）。
 *
 * `--only <regex>` 对 name 或 key 做 find；`--skip-methods` 剔除 `#FUNCTION(S)` 方法；
 * `--shard i/n` 按**原清单下标** `idx % n == i` 分片（i 从 0 起）—— 分片只看原下标，
 * 与其它过滤正交，n 个分片并跑恰好覆盖全量且互不相交。已知取舍：不按 key 去重，
 * 被遮蔽定义（同 key 多 meta）会以相同 key 调到同一个生效体，结果重复属预期噪音。
 *
 * ## 每函数执行
 *
 * 伪造实参（`argIntCount` 个 0 + `argStrCount` 个空串，FuncMeta.kt:42-44 的形参计数）→
 * `runtime.callFunction` → 异常全捕获折成状态（Status.classify）。**首错截断不中断批跑**；
 * 同一会话逐函数复用 —— 状态串染是已知限制：**sweep 结果是挖虫线索，不是功能判据；
 * PASS 不证明该函数正确**（本句同时写进 summary.md）。
 *
 * ## 挂起检测与 sweep.jsonl
 *
 * 每函数执行**前**先追加一行 RUNNING 占位并 flush；执行完把文件截断回占位行起点、
 * 原地覆写为终态记录（单线程下占位行必是文件末行，截断无副作用）。进程挂死时
 * 最后一行 RUNNING 的 fn 就是肇事者。`--resume` 先读旧文件、剔除一切 RUNNING 行、
 * 保留终态记录（同 key 以最后一条为准）并重写文件，随后只跑没有终态记录的函数。
 *
 * 墙钟兜底： ImmediateInputSink 挂 [WallBudget]，逐函数换装（见 Assembler.kt）。
 *
 * ## 输出
 *
 * `--out <目录>`（必填）：`sweep.jsonl`（每函数一条终态记录，字段 fn/key/status/cls/
 * line/pc/msg/steps/elapsedMs/covered/stmts/coverPct）+ `summary.md`（状态计数 +
 * 失败指纹 TOP 表 + 语句覆盖率仪表，指纹算法见 [Ledger.fingerprint]）。
 * 目录不落时间戳，两次同语料 sweep 可逐字节 diff（墙钟类状态除外）。
 *
 * ## 语句覆盖率（DBG1 增量 2026-10-25）
 *
 * 分母 = 函数指令区间内的语句 marker 数（op ∈ [Opcode.INTERNAL_COUNT]..
 * [Opcode.MAX_STATEMENT_OPCODE]，逐指令扫一遍）；分子 = 本次调用执行过的 distinct
 * marker pc（[StatementExecutionCounter] 按区间过滤 —— 嵌套被调方的 pc 不计入本函数）。
 * 「执行过」≠「成功」：错误路径的回调同样计入分子；函数体外代码段不存在（boot-report
 * 实测差额 0），区间末端 = 下一函数 entryPc（[FuncMeta.index] = Program.functions 下标）。
 * 已知近似：同一条 Statement 若被去重共享到多个 marker，分母分子都按 marker 口径走，
 * 覆盖率不因此越界；墙钟/步数截断的函数分子只反映截断前执行到的部分（线索价值正在于此）。
 */
object CmdSweep {

    fun run(a: Args, out: PrintStream, err: PrintStream): Int {
        val outDir = a.value("--out") ?: throw CliError("sweep 需要 --out <输出目录>")
        val outPath = Path.of(outDir).toAbsolutePath().normalize()
        val only = a.value("--only")?.let {
            runCatching { Regex(it) }.getOrElse { e -> throw CliError("--only 不是合法正则: $it（$e）", e) }
        }
        val skipMethods = a.flag("--skip-methods")
        val shard = a.value("--shard")?.let { parseShard(it) }
        val resume = a.flag("--resume")
        val wallMs = a.longOr("--wall-ms", Cli.DEFAULT_WALL_MS)

        val assembled = Assembler.assemble(a)
        val rt = assembled.session.runtime
        val sink = assembled.inputSink
        val stepsCap = a.longOr("--steps", Cli.DEFAULT_SWEEP_STEPS)
        val program = assembled.program
        val functions = program.functions
        // 本片函数群触碰过的 distinct 语句（全程序 marker pc 口径；跨函数共享语句只计一次）
        val globalPcs = BitSet(program.instructionCount)

        // 覆盖 --steps：装配期已按 sweep 缺省/显式值建会话（Assembler 读同一选项），
        // 这里只做一致性提示 —— maxStepsPerRun 是会话构造参数，无法事后改。
        val all = assembled.metadata.functions
        val selected = ArrayList<FuncMeta>()
        val skippedEvent = all.count { it.isEvent || it.position == "·event-entry·" || it.position == "synthetic-event-entry" }
        Files.createDirectories(outPath)
        val jsonl = outPath.resolve("sweep.jsonl")
        if (!resume) {
            // 全新跑（非 --resume）从空文件开始：appendLine 是 CREATE+APPEND，--out 复用
            // 旧目录时上一轮的终态行会原样混进本次结果（2026-10-25 合并 8 片时实测翻倍）。
            Files.deleteIfExists(jsonl)
        }
        // --resume：先清 RUNNING 并收终态 key 集合；有终态记录的函数跳过（契约：跳过一切
        // 非 RUNNING 记录续跑），只跑没有终态记录的函数。
        val resumedKeys = HashSet<String>()
        if (resume && Files.isRegularFile(jsonl)) {
            resumedKeys.addAll(rewriteWithoutRunning(jsonl))
        }
        all.forEachIndexed { idx, m ->
            if (m.isEvent || m.position == "·event-entry·" || m.position == "synthetic-event-entry") return@forEachIndexed
            if ('#' in m.key) return@forEachIndexed
            if (skipMethods && m.isMethod) return@forEachIndexed
            if (only != null && !only.containsMatchIn(m.name) && !only.containsMatchIn(m.key)) return@forEachIndexed
            if (shard != null && idx % shard.n != shard.i) return@forEachIndexed
            if (m.key in resumedKeys) return@forEachIndexed
            selected.add(m)
        }

        out.println("sweep: 清单 ${all.size} 跳过事件/合成入口 $skippedEvent 本次清扫 ${selected.size}" +
            (if (shard != null) " shard=${shard.i}/${shard.n}" else "") +
            (if (resume) " resume(复用终态 ${resumedKeys.size})" else "") +
            " steps=$stepsCap wallMs=$wallMs")
        val t0 = System.currentTimeMillis()
        var pass = 0
        val records = ArrayList<SweptRecord>(selected.size)
        for ((i, m) in selected.withIndex()) {
            val offset = if (Files.exists(jsonl)) Files.size(jsonl) else 0L
            appendLine(jsonl, Jsonl.obj(listOf("fn" to m.name, "key" to m.key, "status" to "RUNNING")))

            // 覆盖率仪表：每函数一枚区间过滤计数器（嵌套被调方不计入），同一探针顺带喂
            // 全片 globalPcs（不过滤）。函数区间/分母不可得时 covered/stmts 记 -1（不适用）。
            val range = functionBounds(functions, m.index, program.instructionCount)
            val stmtTotal = if (range == null) -1 else countMarkers(program, range.first, range.endExclusive)
            val counter = if (range == null) null else StatementExecutionCounter(range.first, range.endExclusive)
            if (counter != null) {
                rt.statementProbe = StatementProbe { op, index, e, o, er, ns ->
                    counter.onStatement(op, index, e, o, er, ns)
                    globalPcs.set(e.pc)
                }
            }

            sink.budget = WallBudget(wallMs)
            val fnStart = System.nanoTime()
            var error: Throwable? = null
            try {
                rt.callFunction(m.key, LongArray(m.argIntCount), Array(m.argStrCount) { "" })
            } catch (t: Throwable) {
                error = t
            }
            val elapsedMs = (System.nanoTime() - fnStart) / 1_000_000L
            sink.budget = null
            rt.statementProbe = null
            val steps = StepReader.executedSteps(rt)
            val rec = SweptRecord.of(
                m, error, steps, elapsedMs,
                covered = counter?.distinctStatements() ?: -1,
                stmts = stmtTotal,
            )
            records.add(rec)

            // 原地覆写：截断回占位行起点，再写终态记录（挂死窗口 = 截断到写之间，微秒级；
            // 该窗口挂死时本函数无记录，--resume 视为待重跑，安全方向一致）。
            RandomAccessFile(jsonl.toFile(), "rw").use { raf ->
                raf.setLength(offset)
                raf.seek(offset)
                raf.write((rec.toJson() + "\n").toByteArray(Charsets.UTF_8))
            }
            if (rec.status == "PASS") pass++
            if ((i + 1) % 500 == 0 || i + 1 == selected.size) {
                err.println("sweep 进度 ${i + 1}/${selected.size}（PASS $pass，失败 ${i + 1 - pass}，${System.currentTimeMillis() - t0} ms）")
            }
        }

        val summary = renderSummary(
            all.size, skippedEvent, records, shard, resumedKeys.size,
            programStmtTotal = program.statementCount,
            touchedStmts = globalPcs.cardinality(),
        )
        Files.writeString(outPath.resolve("summary.md"), summary, Charsets.UTF_8)
        out.println("sweep 完成：清扫 ${records.size}（PASS $pass / 失败 ${records.size - pass}）")
        out.println("out=$outPath")
        return 0
    }

    /**
     * 函数 [index] 的指令区间（first = entryPc 含，endExclusive = 下一函数 entryPc /
     * 程序末尾）：函数在 code 上连续（boot-report 实测「函数体外代码段差额 0」）。
     * [FuncMeta.index] = Program.functions 下标（FuncMeta.kt:37）；越界/退化返回 null
     * （记录 covered/stmts = -1）。
     */
    private fun functionBounds(functions: List<FunctionInfo>, index: Int, instructionCount: Int): IntRange? {
        if (index !in functions.indices) return null
        val start = functions[index].entryPc
        if (start !in 0 until instructionCount) return null
        val end = (if (index + 1 < functions.size) functions[index + 1].entryPc else instructionCount)
            .coerceIn(start, instructionCount)
        return start until end
    }

    /** 覆盖率分母：区间内语句 marker 数（op ∈ INTERNAL_COUNT..MAX_STATEMENT_OPCODE）。 */
    private fun countMarkers(program: Program, start: Int, endExclusive: Int): Int {
        var n = 0
        for (pc in start until endExclusive) {
            if (program.op(pc) in Opcode.INTERNAL_COUNT..Opcode.MAX_STATEMENT_OPCODE) n++
        }
        return n
    }

    /** `--shard i/n`（i 从 0 起）。 */
    private fun parseShard(raw: String): Shard {
        val parts = raw.split('/')
        val i = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: throw CliError("--shard 形如 i/n: $raw")
        val n = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: throw CliError("--shard 形如 i/n: $raw")
        if (n <= 0 || i !in 0 until n) throw CliError("--shard 越界（要求 0 <= i < n）: $raw")
        return Shard(i, n)
    }

    private data class Shard(val i: Int, val n: Int)

    /** 追加一行并 flush（挂起检测的占位行必须立刻可见）。 */
    private fun appendLine(path: Path, line: String) {
        Files.writeString(
            path,
            line + "\n",
            Charsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        // FileChannel.force 太重；writeString 关闭时已落页缓存，进程被 kill -9 也由 OS 落盘。
    }

    /**
     * --resume 的前置清理：读旧 sweep.jsonl，同 key 取最后一条终态记录，剔除一切 RUNNING，
     * 重写为纯终态文件；返回保留的 key 集合（调用方据此跳过已清扫函数）。
     */
    private fun rewriteWithoutRunning(jsonl: Path): Set<String> {
        val lastByKey = LinkedHashMap<String, SweptRecord>()
        for (line in Files.readAllLines(jsonl, Charsets.UTF_8)) {
            if (line.isBlank()) continue
            val fields = Jsonl.parseLine(line) ?: continue
            val status = fields["status"] ?: continue
            if (status == "RUNNING") continue
            val key = fields["key"] ?: continue
            lastByKey[key] = SweptRecord.fromFields(fields) ?: continue
        }
        RandomAccessFile(jsonl.toFile(), "rw").use { raf ->
            raf.setLength(0)
            for (rec in lastByKey.values) {
                raf.write((rec.toJson() + "\n").toByteArray(Charsets.UTF_8))
            }
        }
        return lastByKey.keys
    }

    /** summary.md 渲染（确定性输出：无时间戳、排序稳定）。 */
    private fun renderSummary(
        listSize: Int,
        skippedEvent: Int,
        records: List<SweptRecord>,
        shard: Shard?,
        reused: Int,
        programStmtTotal: Int,
        touchedStmts: Int,
    ): String {
        val sb = StringBuilder()
        sb.append("# vmdbg sweep summary\n\n")
        sb.append("> sweep 结果是挖虫线索，不是功能判据；PASS 不证明该函数正确。\n")
        sb.append("> 已知限制：同一会话逐函数复用（状态串染）——前序函数可能改写全局状态，\n")
        sb.append("> 后序函数的行为与单跑不同；对拍请用 call 单函数验证。\n\n")
        sb.append("- 清单（metadata.functions）: $listSize\n")
        sb.append("- 跳过事件/合成入口: $skippedEvent\n")
        shard?.let { sb.append("- 分片: ${it.i}/${it.n}\n") }
        if (reused > 0) sb.append("- resume 复用终态记录: $reused\n")
        sb.append("- 本次清扫: ${records.size}\n\n")

        val byStatus = records.groupingBy { it.status }.eachCount().toSortedMap()
        sb.append("## 状态计数\n\n| 状态 | 条数 |\n|---|---|\n")
        for ((s, c) in byStatus) sb.append("| $s | $c |\n")
        sb.append("\n")

        val failures = records.filter { it.failed }
        val clusters = failures
            .groupBy { Ledger.fingerprint(it.cls, it.fn, it.line, it.pc, it.msg) }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<SweptRecord>>> { it.value.size }.thenBy { it.key })
            .take(50)
        sb.append("## 失败指纹 TOP（${clusters.size} 簇，按次数降序；指纹口径见 Ledger.fingerprint）\n\n")
        sb.append("| 次数 | 状态 | 异常类 | 函数 | 行 | pc | message(归一化) | 指纹 |\n")
        sb.append("|---|---|---|---|---|---|---|---|\n")
        for ((fp, group) in clusters) {
            val head = group.first()
            sb.append(
                "| ${group.size} | ${head.status} | ${head.cls} | ${head.fn} | ${head.line} | ${head.pc} | " +
                    Ledger.normalizeMessage(head.msg).take(80) + " | ${fp.take(12)} |\n",
            )
        }

        // 语句覆盖率仪表（DBG1 增量）：口径见本文件头「语句覆盖率」节。
        sb.append("\n## 语句覆盖率\n\n")
        val touchedPct = if (programStmtTotal > 0) String.format(Locale.ROOT, "%.1f", touchedStmts * 100.0 / programStmtTotal) else "-1"
        sb.append("- 本片函数群触碰 distinct 语句（全程序语句池 $programStmtTotal 条口径）: $touchedStmts（$touchedPct%）\n")
        val measured = records.filter { it.covered >= 0 && it.stmts > 0 }
        if (measured.isNotEmpty()) {
            val pcts = measured.map { it.covered * 100.0 / it.stmts }.sorted()
            val mean = pcts.average()
            val median = if (pcts.size % 2 == 1) pcts[pcts.size / 2] else (pcts[pcts.size / 2 - 1] + pcts[pcts.size / 2]) / 2.0
            fun bucket(lo: Double, hi: Double) = pcts.count { it >= lo && it < hi }
            sb.append("- 有分母样本 ${measured.size} 条（stmts=0 的函数不适用，计 ${records.size - measured.size} 条）\n")
            sb.append("- 覆盖率均值 ${String.format(Locale.ROOT, "%.1f", mean)}% / 中位 ${String.format(Locale.ROOT, "%.1f", median)}%\n")
            sb.append(
                "- 分布: 0% ${pcts.count { it == 0.0 }} 条 · (0,25)% ${bucket(0.0, 25.0)} · [25,50)% ${bucket(25.0, 50.0)} · " +
                    "[50,75)% ${bucket(50.0, 75.0)} · [75,100)% ${bucket(75.0, 100.0)} · 100% ${pcts.count { it == 100.0 }} 条\n",
            )
        } else {
            sb.append("- （本片无可测样本）\n")
        }
        return sb.toString()
    }
}

/** 一条 sweep 终态记录（sweep.jsonl 的一行）。 */
class SweptRecord(
    val fn: String,
    val key: String,
    val status: String,
    val cls: String,
    val line: Int,
    val pc: Int,
    val msg: String,
    val steps: Long,
    val elapsedMs: Long,
    /** distinct 执行过的语句 marker pc（区间过滤后）；-1 = 函数区间/分母不可得。 */
    val covered: Int = -1,
    /** 覆盖率分母 = 函数区间内语句 marker 总数；-1 = 不可得，0 = 函数无语句。 */
    val stmts: Int = -1,
) {

    /** 非 PASS 即失败（RUNNING 不会出现在终态文件；手工档里出现也按失败计）。 */
    val failed: Boolean get() = status != "PASS"

    /** 语句覆盖率%（一位小数，Locale.ROOT 免小数逗号）；分母不可得时 "-1" = 不适用。 */
    val coverPct: String
        get() = if (covered < 0 || stmts <= 0) "-1" else String.format(Locale.ROOT, "%.1f", covered * 100.0 / stmts)

    fun toJson(): String = Jsonl.obj(
        listOf(
            "fn" to fn,
            "key" to key,
            "status" to status,
            "cls" to cls,
            "line" to line,
            "pc" to pc,
            "msg" to msg,
            "steps" to steps,
            "elapsedMs" to elapsedMs,
            "covered" to covered,
            "stmts" to stmts,
            "coverPct" to coverPct,
        ),
    )

    companion object {
        /** 异常 → 记录（异常类名进 cls：StepLimitExceeded/CallStackOverflow 是 CodeEE 子类，Status 已先分类）。 */
        fun of(
            m: FuncMeta,
            error: Throwable?,
            steps: Long?,
            elapsedMs: Long,
            covered: Int = -1,
            stmts: Int = -1,
        ): SweptRecord {
            val status = Status.classify(error)
            val cls = when (error) {
                null -> "-"
                else -> error.javaClass.simpleName
            }
            val ee = error as? com.eratw.port.core.err.CodeEE
            return SweptRecord(
                fn = m.name,
                key = m.key,
                status = status,
                cls = cls,
                line = ee?.line ?: -1,
                pc = ee?.pc ?: -1,
                msg = error?.message ?: "",
                steps = steps ?: -1L,
                elapsedMs = elapsedMs,
                covered = covered,
                stmts = stmts,
            )
        }

        /** 从 JSONL 行字段还原（缺字段按零值；解析容错由调用方兜）。 */
        fun fromFields(f: Map<String, String>): SweptRecord? {
            val key = f["key"] ?: return null
            return SweptRecord(
                fn = f["fn"] ?: key,
                key = key,
                status = f["status"] ?: "-",
                cls = f["cls"] ?: "-",
                line = f["line"]?.toIntOrNull() ?: -1,
                pc = f["pc"]?.toIntOrNull() ?: -1,
                msg = f["msg"] ?: "",
                steps = f["steps"]?.toLongOrNull() ?: -1L,
                elapsedMs = f["elapsedMs"]?.toLongOrNull() ?: -1L,
                covered = f["covered"]?.toIntOrNull() ?: -1,
                stmts = f["stmts"]?.toIntOrNull() ?: -1,
            )
        }
    }
}
