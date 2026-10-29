package com.eratw.port.perf

import com.eratw.port.boot.BootModel
import com.eratw.port.boot.RealCorpus
import com.eratw.port.boot.RealCorpusCompile
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.perf.PoolDecomp
import com.eratw.port.core.perf.PoolDecompResult
import com.eratw.port.core.perf.RawBill
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.sem.SystemOps
import com.eratw.port.core.vars.VariableRegistry
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.e2e.LineConsoleSink
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.system.exitProcess

/** 一段常驻读数：`id` / 说明 / 段前常驻 / 段后常驻 / 段内峰值 / 段内净工作时长。 */
internal class StageRec(
    val id: String,
    val what: String,
    val before: Long,
    val after: Long,
    val peak: Long,
    val workMs: Long,
    /** 该段里额外的事实（读了多少字节/字符等），写进报告备注列。 */
    val detail: String = "",
)

internal class BeginRec(
    val type: BeginType,
    val executed: Boolean,
    val error: Throwable?,
    val ms: Long,
    val linesBefore: Int,
    val linesAfter: Int,
    val lastPc: Int,
    val lastFunc: String?,
)

internal class Blocker(
    val kind: String,
    val message: String,
    val function: String?,
    val line: Int,
    val pc: Int,
    val stmt: String,
    val file: String?,
)

/** 语句 args 规范化去重的实测读数（[PerfBaseline] §2d）。 */
internal class DupApplyRec(
    val before: Long,
    val after: Long,
    val ms: Long,
    val internedObjects: Int,
    val mergedObjects: Int,
    val check: String,
    /** 去重后的逐类普查表（Markdown）。 */
    val afterTable: String,
)

/**
 * 全量语料内存 / 冷启动基线（PERF-1 测量装置，JavaExec 入口 `:core-vm:perfBaseline`）。
 *
 * **只测量，不改语义**：编译路径原样复用 [RealCorpusCompile]（全量切片 → 单一 `Program`），
 * 开局路径原样复刻 `GameBootSmoke.run` 的装配序列（CSV → store → #DIM → ProcessRuntime →
 * 标签表/数据节 → begin）。本文件不碰任何 `main` 源文件，也不为省内存改动产物结构。
 *
 * 四组常驻（7 段；每段读数前连打 N 轮 `System.gc()` 取最小值，见 [HeapMeter]）：
 * - `S0` 空 JVM 基线（main 首行，仅 JVM 启动类）；
 * - `S1` 全量 `.ERB/.ERH` 读入（字节）；
 * - `S1b` 解码为字符后（与 `LogicalLineReader.decode` 同规则：UTF-8 严格 → 回退 Shift-JIS）；
 * - `S1r` 释放原文后的残留（验证「读入」确实是一次性占用）；
 * - `S2` `Program` 构建后；
 * - `S3` `ProcessRuntime` 建立后；
 * - `S4` `begin(TITLE)` 后；`S5` `begin(FIRST)` 后（TITLE 成功才跑）。
 *
 * 环境变量：`ERATW_CORPUS`（语料 ERB 目录）、`ERATW_PERF_ROUNDS`（GC 轮数）、
 * `ERATW_PERF_REPORT`（报告路径，缺省 `build/perf-report.md`，cwd = 模块目录）。
 */
object PerfBaseline {

    private const val RAND_SEED = 20260226L
    private const val BUDGET_BYTES = 400L * 1024L * 1024L

    fun run(): Int {
        val r = Run()
        val code = try {
            r.execute()
        } catch (t: Throwable) {
            System.err.println("[perf] 测量失败: ${t::class.simpleName}: ${t.message}")
            t.printStackTrace()
            runCatching { r.writeFailure(t) }
            1
        }
        return code
    }

    /** 一次完整测量。字段可变但都在实例上（不违反「禁静态可变全局」）。 */
    private class Run {
        private val stamp: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)
        private val startedAtMs = System.currentTimeMillis()
        private val stages = ArrayList<StageRec>()
        private val notes = ArrayList<String>()

        private var corpus: RealCorpus? = null
        private var model: BootModel? = null
        private var program: Program? = null
        private var census: Census? = null
        private var rawBill: RawBill? = null
        private var dupCensus: ArgDupCensus.Report? = null
        private var dupApply: DupApplyRec? = null
        private var dupCensusMs = 0L
        private var afterInternStage = 0L
        private var decomp: PoolDecompResult? = null
        private var title: BeginRec? = null
        private var first: BeginRec? = null
        private var blocker: Blocker? = null

        private var s0 = 0L
        private var s0Spread = ""
        private var byteTotal = 0L
        private var charTotal = 0L
        private var readMs = 0L
        private var decodeMs = 0L
        private var compileMs = 0L
        private var loadMs = 0L
        private var titleMs = 0L
        private var firstMs: Long? = null
        private var censusMs = 0L
        private var peakAll = 0L
        private var degraded = false
        private var consoleLines = 0
        private var bootToMainMs = -1L
        private var head = "(unknown)"
        private var reportPath: Path = Path.of("build", "perf-report.md").toAbsolutePath().normalize()

        fun execute(): Int {
            reportPath = reportPathOf()
            head = gitHead()
            // T0 必须在任何 GC 之前取：进程启动 → 本行（含 JVM/类加载，不含装置自身的测量开销）
            val startInst = ProcessHandle.current().info().startInstant()
            bootToMainMs = if (startInst.isPresent) startedAtMs - startInst.get().toEpochMilli() else -1L

            // ---- S0：空 JVM 基线（还没碰语料、还没建任何装置对象）----
            val (min0, max0, all0) = HeapMeter.settle()
            s0 = min0
            s0Spread = "${all0.joinToString("/") { mib(it) }}（min/max 差 ${mib(max0 - min0)}）"
            peakAll = max0
            println("[perf] S0 空 JVM 常驻 ${mib(s0)}（${HeapMeter.rounds} 轮 GC 最小值）")

            val c = RealCorpus.load()
            corpus = c
            if (c == null) {
                System.err.println("[perf] 未找到真实语料（ERATW_CORPUS 或仓库根 eratw-画蛇添足版eratw4.980/ERB）")
                writeReport()
                return 2
            }
            println("[perf] 语料: $c")

            // ---- S1：全量原文读入（字节驻留）----
            HeapMeter.resetPeaks()
            val t1 = System.nanoTime()
            val rawBytes = ArrayList<ByteArray>(c.files.size)
            var bytes = 0L
            for (p in c.files) {
                val b = Files.readAllBytes(p)
                bytes += b.size
                rawBytes.add(b)
            }
            byteTotal = bytes
            readMs = msSince(t1)
            val s1 = HeapMeter.settled()
            val peak1 = HeapMeter.peak()
            peakAll = maxOf(peakAll, peak1)
            stages += StageRec(
                "S1", "全量 .ERB/.ERH 读入（字节）", s0, s1, peak1, readMs,
                "${c.files.size} 文件 / ${mib(byteTotal)}",
            )
            println("[perf] S1 原文字节 ${mib(s1)}（+${mib(s1 - s0)}，$byteTotal B）")

            // ---- S1b：解码为字符驻留（同 LogicalLineReader 规则）----
            HeapMeter.resetPeaks()
            val t1b = System.nanoTime()
            val texts = ArrayList<String>(rawBytes.size)
            var chars = 0L
            for (b in rawBytes) {
                val s = decodeCorpus(b)
                chars += s.length
                texts.add(s)
            }
            charTotal = chars
            decodeMs = msSince(t1b)
            val s1b = HeapMeter.settled()
            val peak1b = HeapMeter.peak()
            peakAll = maxOf(peakAll, peak1b)
            stages += StageRec(
                "S1b", "解码为字符驻留", s1, s1b, peak1b, decodeMs,
                "$charTotal 字符（含换行）",
            )
            println("[perf] S1b 字符驻留 ${mib(s1b)}（+${mib(s1b - s1)}，$charTotal chars）")

            // ---- S1r：释放原文，确认读入是一次性占用 ----
            HeapMeter.resetPeaks()
            val t1r = System.nanoTime()
            texts.clear()
            rawBytes.clear()
            val s1r = HeapMeter.settled()
            val peak1r = HeapMeter.peak()
            peakAll = maxOf(peakAll, peak1r)
            stages += StageRec(
                "S1r", "释放原文后残留", s1b, s1r, peak1r, msSince(t1r),
                "残留 ${mib(s1r - s0)} 相对空 JVM",
            )
            println("[perf] S1r 释放后常驻 ${mib(s1r)}（残留 ${mib(s1r - s0)}）")

            // ---- S2：全量编译 → Program ----
            HeapMeter.resetPeaks()
            val t2 = System.nanoTime()
            val m = try {
                RealCorpusCompile.compile(c, null)
            } catch (e: OutOfMemoryError) {
                System.gc()
                degraded = true
                notes += "**OOM 降级**：全量编译 OutOfMemoryError，已退回前 400 个文件；本段读数不可与全量直接比较。"
                RealCorpusCompile.compile(c, 400)
            }
            compileMs = msSince(t2)
            model = m
            program = m.program
            val s2 = HeapMeter.settled()
            val peak2 = HeapMeter.peak()
            peakAll = maxOf(peakAll, peak2)
            stages += StageRec(
                "S2", "Program 构建后", s1r, s2, peak2, compileMs,
                "${m.stats.instructions} 指令 / ${m.stats.statements} 语句",
            )
            println(
                "[perf] S2 Program ${mib(s2)}（+${mib(s2 - s1r)}，峰值 +${mib(peak2 - s1r)}，" +
                    "指令 ${m.stats.instructions}，编译 ${compileMs}ms）",
            )

            // ---- 池分解（在 S2 读数之后做，不干扰读数）----
            val prog = program
            if (prog != null) {
                HeapMeter.resetPeaks()
                val tc = System.nanoTime()
                census = PoolCensus.census(prog, m, SizeModel(SizeModel.detectRefWidth()))
                censusMs = msSince(tc)
                val cut = HeapMeter.peak()
                peakAll = maxOf(peakAll, cut)
                System.gc()
                println("[perf] 池分解完成（${censusMs}ms）")
                // 语句原文账单必须在建 ProcessRuntime 之前采（构造期会 releaseStatementRaws）
                val rb = PoolDecomp.rawBill(prog)
                rawBill = rb
                println(
                    "[perf] 语句原文账单：${rb.statements} 条 / ${rb.chars} 字符 = ${mib(rb.bytes)}" +
                        "（latin1 ${rb.latin1} / UTF-16 ${rb.utf16}）",
                )
                // ---- 语句 args 子树结构重复率（只测量：不改程序，读数落 §2c）----
                val tdup = System.nanoTime()
                val dup = ArgDupCensus.census(prog, SizeModel(SizeModel.detectRefWidth()))
                dupCensus = dup
                dupCensusMs = msSince(tdup)
                dup.logLines().forEach { println(it) }
                System.gc()

                // ---- S2i：语句 args 规范化去重（装载期一次摊销；去重前 = S2 读数）----
                HeapMeter.resetPeaks()
                val beforeIntern = HeapMeter.settled()
                // 逐语句结构哈希快照（去重前）：全量对拍「每条语句的参数树同构」。
                // 先读一次 `args`：无参语句（标签/`#` 行）的参数槽是**惰性**建立的，
                // 不先落槽会让快照把「null 槽」与「VoidArgument 槽」算成不同形态。
                val th0 = System.nanoTime()
                var beforeHashes: LongArray? = LongArray(prog.statementCount) { i ->
                    val st = prog.statement(i)
                    st.args
                    ArgDupCensus.statementHash(st)
                }
                val hashMs = msSince(th0)
                HeapMeter.resetPeaks()
                val tIntern = System.nanoTime()
                val stats = prog.internStatementArgs()
                val internMs = msSince(tIntern)
                // 全量同构对拍：每条语句的参数树/自带表达式字段的值不许变
                var changed = 0
                val changedKinds = HashMap<String, Int>()
                run {
                    val s = beforeHashes ?: return@run
                    for (i in 0 until prog.statementCount) {
                        val st = prog.statement(i)
                        if (ArgDupCensus.statementHash(st) != s[i]) {
                            changed++
                            val k = st.javaClass.simpleName ?: st.javaClass.name
                            changedKinds.merge(k, 1) { a, b -> a + b }
                        }
                    }
                }
                val checkMs = msSince(tIntern) - internMs
                // 快照数组在建读数前**显式丢弃**（1.7M × 8B = 13.7 MiB，否则会算进「去重后常驻」）
                beforeHashes = null
                val peakI = HeapMeter.peak()
                val afterIntern = HeapMeter.settled()
                val peakAllI = maxOf(peakAll, peakI)
                peakAll = peakAllI
                // 自检：去重后同一形态不该再有第二个实例（普查逐类 instances == forms）
                val after = ArgDupCensus.census(prog, SizeModel(SizeModel.detectRefWidth()))
                val residual = after.rows.filter { it.dup > 0 }
                    .joinToString("，") { "${it.cls} ${it.dup}" }
                    .ifEmpty { "无残留重复实例" }
                val check = "遍历 ${stats.nodes} 结点 / 形态 ${stats.forms} / 合并 ${stats.merged}；" +
                    "逐语句结构哈希差异 $changed / ${prog.statementCount}" +
                    (if (changed == 0) "" else "（${changedKinds.entries.sortedByDescending { it.value }.take(5).joinToString("，") { "${it.key} ${it.value}" }}）") +
                    "（快照 ${hashMs}ms、对拍 ${checkMs}ms）；" +
                    "复测重复率 ${if (after.instances == 0) 0 else after.dup * 100 / after.instances}%（$residual）"
                dupApply = DupApplyRec(
                    before = beforeIntern,
                    after = afterIntern,
                    ms = internMs,
                    internedObjects = after.instances,
                    mergedObjects = stats.merged,
                    check = check,
                    afterTable = after.renderTable(),
                )
                stages += StageRec(
                    "S2i", "语句 args 规范化去重后", beforeIntern, afterIntern, peakI, internMs,
                    "合并 ${stats.merged} 个实例 / 形态 ${stats.forms}；复测 ${after.instances} 对象",
                )
                println(
                    "[perf] S2i args 去重 ${mib(beforeIntern)} → ${mib(afterIntern)}" +
                        "（${signed(afterIntern - beforeIntern)}，${internMs}ms，合并 ${stats.merged} 实例）",
                )
                afterInternStage = afterIntern
            } else {
                notes += "Program 构建失败：${m.buildError}；后续运行期段全部跳过。"
                writeReport()
                return 1
            }

            // ---- S3：ProcessRuntime 建立（CSV → store → #DIM → 构造 → 装表）----
            HeapMeter.resetPeaks()
            val t3 = System.nanoTime()
            val console = LineConsoleSink(barWidth = 199, printCLength = 25)
            val gameData = GameDataLoader.load(c.root.parent?.resolve("CSV") ?: c.root)
            val store = VariableStore(gameData?.registry ?: VariableRegistry())
            if (gameData != null) {
                for (t in gameData.templates) store.registerTemplate(t)
                store.loadItemPrice(gameData.itemPrices)
                store.reloadStrNames()
                notes += "CSV 已装载：模板 ${gameData.templates.size} 条，警告 ${gameData.warnings.size} 条"
                gameData.warnings.take(5).forEach { notes += "CSV 警告: $it" }
            } else {
                notes += "CSV 未装载（目录缺失）——系统变量按缺省注册表运行"
            }
            if (gameData != null && store.template(0L) != null) store.addCharacter(0L)
            else repeat(4) { store.characters.addBare() }

            var globalOk = 0
            var globalFail = 0
            for (spec in m.globalVarDecls) {
                runCatching { store.declareUserVar(spec) }
                    .onSuccess { globalOk++ }
                    .onFailure { globalFail++ }
            }
            var privateOk = 0
            var privateFail = 0
            for ((fk, spec) in m.userVarDecls) {
                runCatching { store.declareUserVar(spec, fk) }
                    .onSuccess { privateOk++ }
                    .onFailure { privateFail++ }
            }
            notes += "用户变量注册：全局 $globalOk 成功 / $globalFail 失败，私有 $privateOk 成功 / $privateFail 失败"

            val maxSteps = System.getenv("ERATW_BOOT_MAX_STEPS")?.toLongOrNull() ?: 5_000_000L
            val bootOps = object : SystemOps {
                override fun saveGlobal() {}
                override fun loadGlobal(): Boolean = false
            }
            val rt = ProcessRuntime(
                prog,
                store,
                m.table,
                VmConfig(maxStepsPerRun = maxSteps),
                consoleSink = console,
                semConfig = SemConfig(printCPerLine = 5),
                systemOps = bootOps,
                randSeed = RAND_SEED,
            )
            rt.installLabelTable(object : ProcessRuntime.LabelTable {
                override fun labelPc(functionKey: String, label: String): Int? =
                    m.labelPcs[functionKey.uppercase()]?.get(label.uppercase())
            })
            rt.installDataSections(ProcessRuntime.DataSectionSource { index -> m.dataSections[prog.statement(index)] })
            loadMs = msSince(t3)
            val s3 = HeapMeter.settled()
            val peak3 = HeapMeter.peak()
            peakAll = maxOf(peakAll, peak3)
            stages += StageRec(
                "S3", "ProcessRuntime 建立后",
                if (afterInternStage > 0) afterInternStage else s2, s3, peak3, loadMs,
                "store + CSV + #DIM + 标签表/数据节",
            )
            println("[perf] S3 运行期 ${mib(s3)}（+${mib(s3 - afterInternStage)}，装载 ${loadMs}ms）")

            // ---- 装载后逐池分解（互斥口径；在 S3 读数之后、begin 之前）----
            val pauseMs = System.getenv("ERATW_PERF_PAUSE_MS")?.toLongOrNull() ?: 0L
            if (pauseMs > 0) {
                println("[perf] PAUSE pid=${ProcessHandle.current().pid()} ${pauseMs}ms（jcmd 采样窗口）")
                Thread.sleep(pauseMs)
            }
            val rb = rawBill
            if (rb != null) {
                val tdec = System.nanoTime()
                val dec = runCatching {
                    PoolDecomp.measure(
                        prog, m, store, gameData, rt,
                        SizeModel(SizeModel.detectRefWidth()), s3, s0, rb,
                    )
                }.onFailure {
                    notes += "逐池分解失败：${it::class.simpleName}: ${it.message}"
                }.getOrNull()
                decomp = dec
                if (dec != null) {
                    dec.logLines().forEach { println(it) }
                    dec.postWalkUsed = HeapMeter.settled()
                    println("[perf] 遍历后复读常驻 ${mib(dec.postWalkUsed)}（分解前 ${mib(s3)}）")
                    println("[perf] 逐池分解耗时 ${msSince(tdec)}ms")
                }
                HeapMeter.resetPeaks()
            } else {
                notes += "逐池分解跳过：语句原文账单缺失"
            }

            // ---- S4 / S5：begin 到首个阻塞点 ----
            HeapMeter.resetPeaks()
            val t4 = System.nanoTime()
            val tr = runBegin(rt, BeginType.TITLE, console)
            titleMs = msSince(t4)
            title = tr
            val s4 = HeapMeter.settled()
            val peak4 = HeapMeter.peak()
            peakAll = maxOf(peakAll, peak4)
            val titleErr = tr.error
            stages += StageRec(
                "S4", "begin(TITLE) 后", s3, s4, peak4, tr.ms,
                "${if (titleErr == null) "无异常" else titleErr::class.simpleName} / +${tr.linesAfter - tr.linesBefore} 行",
            )
            println(
                "[perf] S4 TITLE ${mib(s4)}（${tr.ms}ms，" +
                    "${titleErr?.let { it::class.simpleName + ": " + it.message } ?: "无异常"}）",
            )

            if (tr.error == null && tr.executed) {
                HeapMeter.resetPeaks()
                val t5 = System.nanoTime()
                val fr = runBegin(rt, BeginType.FIRST, console)
                firstMs = msSince(t5)
                first = fr
                val s5 = HeapMeter.settled()
                val peak5 = HeapMeter.peak()
                peakAll = maxOf(peakAll, peak5)
                val firstErr = fr.error
                stages += StageRec(
                    "S5", "begin(FIRST) 后", s4, s5, peak5, fr.ms,
                    "${if (firstErr == null) "无异常" else firstErr::class.simpleName} / +${fr.linesAfter - fr.linesBefore} 行",
                )
                println("[perf] S5 FIRST ${mib(s5)}（${fr.ms}ms）")
            } else {
                notes += "TITLE 未成功（${tr.error ?: "目标不存在"}），FIRST 未运行——首个阻塞点即 TITLE 的阻塞点。"
            }

            consoleLines = console.snapshot().size
            val failed = listOfNotNull(title, first).firstOrNull { it.error != null || !it.executed }
            if (failed != null) blocker = makeBlocker(prog, m, failed)

            writeReport()
            println("[perf] 报告: $reportPath")
            return 0
        }

        // ------------------------------------------------------------------
        // begin / 阻塞点
        // ------------------------------------------------------------------

        private fun runBegin(rt: ProcessRuntime, type: BeginType, console: LineConsoleSink): BeginRec {
            val before = console.snapshot().size
            val t = System.nanoTime()
            var executed = false
            var err: Throwable? = null
            try {
                executed = rt.begin(type)
            } catch (e: Throwable) {
                err = e
            }
            val ms = msSince(t)
            var pc = -1
            var fn: String? = null
            if (err != null) {
                val loc = vmLocation(rt)
                pc = loc.first
                fn = loc.second
            }
            return BeginRec(type, executed, err, ms, before, console.snapshot().size, pc, fn)
        }

        private fun makeBlocker(program: Program, m: BootModel, rec: BeginRec): Blocker {
            val e = rec.error
            val code = e as? CodeEE
            val pc = code?.pc?.takeIf { it >= 0 } ?: rec.lastPc
            val func = code?.funcName ?: rec.lastFunc
            val srcLine = when {
                pc in 0 until program.instructionCount && program.lineOf(pc) > 0 -> program.lineOf(pc)
                else -> code?.line ?: -1
            }
            val file = func?.let { m.fileBySigKey[it.uppercase()] }
                ?: func?.let { f ->
                    m.table.all().firstOrNull { it.key.equals(f, true) }?.position?.substringBefore(':')
                }
            val kind = when {
                e == null -> "BEGIN_${rec.type.name}_NO_FUNCTION"
                else -> e::class.simpleName ?: "Throwable"
            }
            val msg = e?.message ?: "${rec.type.targetFunction} 不存在（begin 返回 false），未执行任何语句"
            return Blocker(kind, msg, func, srcLine, pc, describeStatement(program, pc), file)
        }

        /** 首错语句：marker 码直取语句池（与 `GameBootSmoke.kt:311-322` 同口径）。 */
        private fun describeStatement(program: Program, pc: Int): String {
            if (pc !in 0 until program.instructionCount) return "?"
            val op = program.op(pc)
            if (!Opcode.isInternal(op) && op in 29..197) {
                val a = program.a(pc)
                if (a in 0 until program.statementCount) {
                    val s = program.statement(a)
                    return "${s.code.name}@L${s.line} | ${s.raw.trim().take(120)}"
                }
            }
            return Opcode.nameOf(op)
        }

        /**
         * 异常退出瞬间的 `(lastPc, 函数名)`：反射读 `ProcessRuntime.vm.lastPc`
         * （与 `GameBootSmoke.kt:557-565` 同一实现细节，上层若补公开读数 API 应改回直调）。
         */
        private fun vmLocation(rt: ProcessRuntime): Pair<Int, String?> {
            val p = rt.program
            for (field in listOf("activeVm", "vm")) {
                val vm = readDeclaredField(rt, field) ?: continue
                val pc = readIntProperty(vm, "lastPc") ?: continue
                if (pc in 0 until p.instructionCount) return pc to functionAt(p, pc)
            }
            return -1 to null
        }

        private fun functionAt(program: Program, pc: Int): String? =
            program.functions.filter { it.entryPc in 0..pc }.maxByOrNull { it.entryPc }?.name

        private fun readDeclaredField(target: Any, name: String): Any? {
            var c: Class<*>? = target.javaClass
            while (c != null) {
                val f = c.declaredFields.firstOrNull { it.name == name }
                if (f != null) {
                    return runCatching {
                        f.isAccessible = true
                        f.get(target)
                    }.getOrNull()
                }
                c = c.superclass
            }
            return null
        }

        private fun readIntProperty(target: Any, name: String): Int? = runCatching {
            val getter = target.javaClass.methods.firstOrNull {
                it.parameterCount == 0 && it.name == "get" + name.replaceFirstChar(Char::uppercaseChar)
            } ?: return@runCatching null
            getter.isAccessible = true
            (getter.invoke(target) as? Number)?.toInt()
        }.getOrNull()

        // ------------------------------------------------------------------
        // 报告
        // ------------------------------------------------------------------

        fun writeFailure(t: Throwable) {
            val sb = StringBuilder()
            sb.appendLine("# 全量语料内存 / 冷启动基线（PERF-1）")
            sb.appendLine()
            sb.appendLine("- 生成时间：${stamp}")
            sb.appendLine("- git HEAD：`$head`")
            sb.appendLine("- 结果：**测量中途失败** `${t::class.simpleName}: ${t.message}`")
            writeText(sb.toString())
        }

        private fun writeReport() {
            writeText(render())
        }

        private fun writeText(text: String) {
            runCatching {
                Files.createDirectories(reportPath.parent)
                Files.writeString(reportPath, text)
            }.onFailure { System.err.println("[perf] 报告写入失败 $reportPath: ${it.message}") }
        }

        private fun render(): String {
            val sb = StringBuilder()
            val m = model
            val st = m?.stats

            sb.appendLine("# 全量语料内存 / 冷启动基线（PERF-1）")
            sb.appendLine()
            sb.appendLine("## 0. 读数元信息")
            sb.appendLine()
            sb.appendLine("| 项 | 值 |")
            sb.appendLine("|---|---|")
            sb.appendLine("| 读数时间戳 | ${stamp} |")
            sb.appendLine("| git HEAD | `$head` |")
            sb.appendLine("| 语料根 | `${corpus?.root ?: "(未找到)"}` |")
            sb.appendLine("| 语料来源 | `${corpus?.source ?: "-"}` |")
            if (st != null) {
                sb.appendLine("| 语料规模 | ${st.corpusFiles} 文件（${st.erbFiles} ERB + ${st.erhFiles} ERH）/ ${st.slices} 切片 |")
                sb.appendLine("| 编译结果 | 成功 ${st.compiled}，编译失败 ${st.compileFailures}，解析失败 ${st.parseFailures}，事件入口 ${st.eventEntries} |")
                sb.appendLine("| Program | ${st.instructions} 指令 / ${st.statements} 语句池条目 |")
            }
            sb.appendLine("| JVM | ${HeapMeter.jvmName()} |")
            sb.appendLine("| 堆 | max ${mib(HeapMeter.max())}；args `${HeapMeter.inputArgs().joinToString(" ")}` |")
            sb.appendLine("| GC | ${HeapMeter.gcNames()}；每段读数轮数 ${HeapMeter.rounds}（取最小值） |")
            sb.appendLine("| 机器 | ${HeapMeter.osName()} |")
            sb.appendLine("| 已加载类 | ${HeapMeter.loadedClasses()} |")
            if (degraded) sb.appendLine("| **降级** | **是**（全量编译 OOM，退回前 400 文件） |")
            sb.appendLine()
            sb.appendLine("> 口径：`used = Runtime.totalMemory() - freeMemory()`，每段前连打 ${HeapMeter.rounds} 轮")
            sb.appendLine("> `System.gc()` 取**最小值**（≈ 常驻）。`峰值` 是该段 `resetPeakUsage()` 之后各 heap pool")
            sb.appendLine("> `peakUsage` 之和，用于盯「瞬时尖峰」，对短于一个 GC 周期的尖峰会低估。")
            sb.appendLine()

            // ---- 1. 四段常驻 ----
            sb.appendLine("## 1. 分段常驻（post-GC used）")
            sb.appendLine()
            sb.appendLine("| 段 | 时点 | 段前 | 段后常驻 | 增量 | 段内峰值 | 峰值增量 | 净工作时长 | 备注 |")
            sb.appendLine("|---|---|---:|---:|---:|---:|---:|---:|---|")
            for (s in stages) {
                sb.appendLine(
                    "| ${s.id} | ${s.what} | ${mib(s.before)} | ${mib(s.after)} | " +
                        "${signed(s.after - s.before)} | ${mib(s.peak)} | ${signed(s.peak - s.before)} | " +
                        "${s.workMs} ms | ${s.detail} |",
                )
            }
            sb.appendLine()
            sb.appendLine("- 空 JVM 基线 S0 = **${mib(s0)}**；S0 的 ${HeapMeter.rounds} 次读数 = $s0Spread")
            sb.appendLine("- 全程堆峰值（各段 peak 的最大值）= **${mib(peakAll)}**（max heap ${mib(HeapMeter.max())}）")
            val resident = stages.lastOrNull()?.after ?: s0
            sb.appendLine("- **开局后常驻（末段）= ${mib(resident)}**")
            sb.appendLine()
            sb.appendLine("读法：`S1`/`S1b` 是**一次性**占用（`S1r` 证明释放在常驻口径下真的退回去了）；")
            sb.appendLine("`S2` 是编译产物的常驻；`S3`/`S4` 是运行期新增。判预算用**末段常驻**，判 OOM 风险用**峰值**。")
            sb.appendLine()

            // ---- 2. 池分解 ----
            val cen = census
            if (cen != null) {
                sb.appendLine("## 2. Program 池逐项计数与估算占用")
                sb.appendLine()
                sb.appendLine("> 计数精确（`Program` 公开 count 与下标访问）；字节是 [SizeModel] 的布局估算：")
                sb.appendLine("> ${SizeModel.describeLayout(SizeModel.detectRefWidth())}，String 按 compact strings 内容判定。")
                sb.appendLine("> 测算耗时 ${censusMs} ms（在 S2 读数之后做，不影响 S2 读数）。")
                sb.appendLine()
                sb.appendLine("| 分组 | 项 | 计数 | 估算占用 | 占 S2 增量 | 口径 |")
                sb.appendLine("|---|---|---|---:|---:|---|")
                val s2Delta = (stages.firstOrNull { it.id == "S2" }?.let { it.after - it.before }) ?: 0L
                var total = 0L
                for (row in cen.rows) {
                    total += row.bytes
                    val pct = percent(row.bytes, s2Delta)
                    sb.appendLine(
                        "| ${row.group} | ${row.name} | ${row.count} | ${mib(row.bytes)} | $pct | ${row.note} |",
                    )
                }
                sb.appendLine("| | **测算合计** | | **${mib(total)}** | " +
                    "${percent(total, s2Delta)} | " +
                    "上限（`statements.args` 与 `exprs` 池重复计入共享实例） |")
                sb.appendLine("| | **JVM 实测 S2 增量** | | **${mib(s2Delta)}** | 100% | 权威读数 |")
                sb.appendLine()
                sb.appendLine("- `exprs` 池类型分布（前 8）：${cen.exprTop}")
                sb.appendLine("- `statements` 池类型分布（前 8）：${cen.stmtTop}")
                sb.appendLine()
            }

            // ---- 2b. 装载后逐池分解（互斥口径）----
            decomp?.let { sb.appendLine(it.render()) }

            // ---- 2c. 语句 args 子树结构重复率（可回收上限）----
            dupCensus?.let { d ->
                sb.appendLine("## 2c. 语句 args 子树结构重复率（去重前的可回收上限）")
                sb.appendLine()
                sb.appendLine("> 口径见 [ArgDupCensus]：根集合 = 每条语句除 `rawSlot` 外的全部对象字段；")
                sb.appendLine("> 实例数按**身份**去重（跨语句共享只算一次），形态数按递归结构哈希去重。")
                sb.appendLine("> 普查耗时 ${dupCensusMs} ms（只测量，不改程序）。")
                sb.appendLine()
                sb.appendLine("| 类 | 实例 | 形态 | 重复 | 重复率 | 浅尺寸 | 可回收上限 |")
                sb.appendLine("|---|---:|---:|---:|---:|---:|---:|")
                for (r in d.rows) {
                    sb.appendLine(
                        "| ${r.cls} | ${r.instances} | ${r.forms} | ${r.dup} | ${r.dupPct}% | " +
                            "${d.bytes(r.shallowBytes)} | ${d.bytes(r.reclaimBytes)} |",
                    )
                }
                sb.appendLine(
                    "| **合计** | **${d.instances}** | **${d.forms}** | **${d.dup}** | " +
                        "**${if (d.instances == 0) 0 else d.dup * 100 / d.instances}%** | " +
                        "**${d.bytes(d.shallowBytes)}** | **${d.bytes(d.reclaimBytes)}** |",
                )
                sb.appendLine()
                sb.appendLine(
                    "- 可回收上限占浅尺寸 ${percent(d.reclaimBytes, d.shallowBytes)}；" +
                        "这是「所有结构相等的节点都归一到单实例」的理论上限，实际值看 §2d 的实测增量。",
                )
                sb.appendLine()
            }

            // ---- 2d. 语句 args 去重实测（同一次运行内，去重前 = S2 读数）----
            dupApply?.let { a ->
                sb.appendLine("## 2d. 语句 args 规范化去重（实测，同一次运行）")
                sb.appendLine()
                sb.appendLine("| 项 | 读数 |")
                sb.appendLine("|---|---:|")
                sb.appendLine("| 去重前常驻（S2 读数） | ${mib(a.before)} |")
                sb.appendLine("| 去重后常驻（GC 稳定后） | ${mib(a.after)} |")
                sb.appendLine("| **回收** | **${signed(a.after - a.before)}**（${percent(a.before - a.after, a.before)}） |")
                sb.appendLine("| 归一后节点数 | ${a.internedObjects}（合并掉 ${a.mergedObjects} 个实例） |")
                sb.appendLine("| 装载期一次性耗时 | ${a.ms} ms（编译 ${compileMs} ms 的 ${percent(a.ms, compileMs)}） |")
                sb.appendLine("| 语义校验 | ${a.check} |")
                sb.appendLine()
                sb.appendLine(
                    "- 该段是**结构规范化**：语句对象与池下标不变，只把 `args`/条件/内嵌值子树" +
                        "换成结构相等的共享实例；每条语句的读取面（`Statement.args` 等）形状不变。",
                )
                sb.appendLine()
                sb.appendLine("<details><summary>去重后逐类普查（残留重复即「唯一父结点下的重复子结点」）</summary>")
                sb.appendLine()
                sb.append(a.afterTable)
                sb.appendLine()
                sb.appendLine("</details>")
                sb.appendLine()
            }

            // ---- 3. 预算对照 ----
            sb.appendLine("## 3. 对照 <400MB 预算")
            sb.appendLine()
            val budgetDelta = BUDGET_BYTES - resident
            val verdict = when {
                m == null -> "**无法判定**（未完成测量：语料缺失）"
                resident <= BUDGET_BYTES -> "**达标**"
                else -> "**超预算**"
            }
            sb.appendLine("| 口径 | 读数 | 对 400 MiB |")
            sb.appendLine("|---|---:|---:|")
            sb.appendLine("| 开局后常驻（末段） | ${mib(resident)} | $verdict（余量 ${signed(budgetDelta)}） |")
            sb.appendLine("| 全程堆峰值 | ${mib(peakAll)} | ${if (peakAll <= BUDGET_BYTES) "**达标**" else "**超预算**"}（余量 ${signed(BUDGET_BYTES - peakAll)}） |")
            sb.appendLine()
            val topRows = cen?.rows?.sortedByDescending { it.bytes }?.take(3).orEmpty()
            sb.appendLine("**结论**：$verdict。")
            if (topRows.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("最大三项（测算，S2 增量占比）：")
                var acc = 0L
                for ((i, r) in topRows.withIndex()) {
                    acc += r.bytes
                    sb.appendLine("${i + 1}. **${r.name}** — ${mib(r.bytes)}（${r.count}）")
                }
                sb.appendLine()
                sb.appendLine("三项合计 ${mib(acc)}，占 S2 增量 ${mib(stages.firstOrNull { it.id == "S2" }?.let { it.after - it.before } ?: 0L)} 的 " +
                    "${percent(acc, stages.firstOrNull { it.id == "S2" }?.let { it.after - it.before } ?: 0L)}。")
            }
            sb.appendLine()

            // ---- 4. Android 直读 ----
            sb.appendLine("## 4. Android 侧 `.erbc` / Asset 直读能省掉哪一段（数字）")
            sb.appendLine()
            val s1Peak = stages.firstOrNull { it.id == "S1b" }?.let { it.after - it.before } ?: 0L
            val s1Bytes = stages.firstOrNull { it.id == "S1" }?.let { it.after - it.before } ?: 0L
            val compileResidue = stages.firstOrNull { it.id == "S2" }?.let { it.after - it.before } ?: 0L
            val compilePeakOnly = stages.firstOrNull { it.id == "S2" }?.let { it.peak - it.before } ?: 0L
            val codeBytes = program?.let { it.instructionCount.toLong() * 16 } ?: 0L
            val lineBytes = program?.let { it.instructionCount.toLong() * 4 } ?: 0L
            val fileCount = corpus?.files?.size ?: 0
            sb.appendLine("| 项 | 桌面实测 | Android 可省 | 机制 |")
            sb.appendLine("|---|---:|---:|---|")
            sb.appendLine(
                "| 语料原文（字节形态） | ${mib(s1Bytes)} | 全部 | " +
                    "`AssetManager` 直读 `.erbc`，不再 `readAllBytes` $fileCount 个源文件 |",
            )
            sb.appendLine(
                "| 语料原文（解码后字符） | ${mib(s1Peak)}（在字节之上） | 全部（含一次性解码峰值） | " +
                    "不需要 `String(bytes, charset)` 解 $charTotal 字符源码 |",
            )
            sb.appendLine(
                "| 编译瞬时（切片 AST + 编译中间态） | 峰值 +${mib(compilePeakOnly)}（相对段前） | 全部 | " +
                    "`.erbc` 离线产出，装机后只装载不再编译 |",
            )
            sb.appendLine(
                "| 编译产物常驻 | ${mib(compileResidue)} | 不省（仍要装载） | `.erbc` 反序列化后仍是同一份 `Program` |",
            )
            sb.appendLine(
                "| 其中 code + lines | ${mib(codeBytes + lineBytes)}（${mib(codeBytes)} + ${mib(lineBytes)}） | " +
                    "可省出 Java 堆 | `AssetFileDescriptor` → `mmap` 只读映射，VM 按偏移读；需改 `Program` 取指路径 |",
            )
            sb.appendLine()
            sb.appendLine("净效果（算式）：")
            sb.appendLine("- **装机后不需要编译** → 冷启动的 `T1 编译` 整段消失，且 S2 的瞬时峰值不再出现。")
            sb.appendLine("- 若 `.erbc` 全量读入堆：常驻 ≈ `${mib(compileResidue)}`（S2 增量）+ 反序列化临时量。")
            sb.appendLine("- 若 code/lines 走 mmap：再省 `${mib(codeBytes + lineBytes)}`，常驻 ≈ " +
                "`${mib((compileResidue - codeBytes - lineBytes).coerceAtLeast(0))}`。")
            sb.appendLine("- dex / 类加载不在本报告测量范围（TODO(verify)），只影响代码段常量，不影响上表的堆对象。")
            sb.appendLine()

            // ---- 5. 冷启动 ----
            sb.appendLine("## 5. 冷启动（到首个阻塞点）")
            sb.appendLine()
            val execTotal = titleMs + (firstMs ?: 0L)
            sb.appendLine("| 段 | 内容 | 耗时 |")
            sb.appendLine("|---|---|---:|")
            if (bootToMainMs >= 0) {
                sb.appendLine("| T0 | JVM 进程启动 → `main()` 首行（类加载，不含测量开销） | ${bootToMainMs} ms |")
            }
            sb.appendLine("| T1 | 全量编译：符号表 + 切片 + `Program` 冻结 | $compileMs ms |")
            sb.appendLine("| T2 | 装载：CSV + store + #DIM + `ProcessRuntime` + 装表 | $loadMs ms |")
            sb.appendLine("| T3 | 执行：`begin(TITLE)` 到阻塞点/返回 | $execTotal ms（TITLE $titleMs" +
                (firstMs?.let { " + FIRST $it" } ?: "") + "） |")
            sb.appendLine()
            val coldTotal = (if (bootToMainMs > 0) bootToMainMs else 0L) + compileMs + loadMs + execTotal
            sb.appendLine("- **桌面冷启动合计 ≈ ${coldTotal} ms**（T0 + T1 + T2 + T3；不含报告/池分解的测量开销）")
            sb.appendLine("- **其中 Android 可消除的部分 = T1 ${compileMs} ms**（`.erbc` 直读后只剩 T2/T3）。")
            sb.appendLine()
            sb.appendLine("### 真机换算")
            sb.appendLine()
            sb.appendLine("> **这是桌面基线**。真机（ARM 中端 SoC + ART）需要乘系数，本报告**没有实测数据**，")
            sb.appendLine("> 故只给区间与理由，不给单点结论：")
            sb.appendLine("> - 计算密集段（T1 编译、T2 装载里的解析/符号表）经验系数 **1.5–3×**：")
            sb.appendLine(">   依据是同一 JVM 字节码在中端 ARM 上的单核整数性能通常为桌面 x86 的 1/2–1/3，")
            sb.appendLine(">   且 ART 的 JIT 预热窗口比 HotSpot C2 短（长循环更吃亏）。")
            sb.appendLine("> - I/O 密集段（S1 读 ${corpus?.files?.size ?: 0} 个文件）在手机上受 flash 随机读与 `assets` 压缩影响，")
            sb.appendLine(">   系数不可移植；`.erbc` 单文件顺序读会显著好于 3,971 次小文件读。")
            sb.appendLine("> - T0（JVM/ART 启动）不可比：ART 启动 + 类校验通常比 HotSpot 冷启动慢。")
            sb.appendLine("> - TODO(verify)：需真机 `am start` + `System.nanoTime()` 埋点实测后替换上述区间。")
            sb.appendLine()

            // ---- 6. 首个阻塞点 ----
            sb.appendLine("## 6. 首个阻塞点")
            sb.appendLine()
            val b = blocker
            if (b == null) {
                sb.appendLine("- 无阻塞：`begin` 未抛错、目标函数存在。这不等于可玩（空体桩/默认输入仍在）。")
            } else {
                sb.appendLine("- 类型：`${b.kind}`")
                sb.appendLine("- message：${b.message}")
                sb.appendLine("- 函数：`${b.function ?: "?"}`；源文件：`${b.file ?: "?"}`；行：${b.line}；pc：${b.pc}")
                sb.appendLine("- 语句：`${b.stmt}`")
            }
            sb.appendLine("- 控制台累计行数：$consoleLines")
            sb.appendLine()

            // ---- 7. 备注 / 局限 ----
            sb.appendLine("## 7. 装置备注与局限")
            sb.appendLine()
            sb.appendLine("- 编译路径 = `RealCorpusCompile.compile(corpus, null)`（全量，无 `ERATW_BOOT_LIMIT`）。")
            sb.appendLine("- 开局装配顺序逐条复刻 `GameBootSmoke.run`（CSV → store → 全局 #DIM → 私有 #DIM →")
            sb.appendLine("  `ProcessRuntime` → `installLabelTable` → `installDataSections` → `begin`），未改语义。")
            sb.appendLine("- `T1` 内含 `symbolTable`/切片/编译/`build()` 全过程（`RealCorpusCompile` 不暴露子段计时，")
            sb.appendLine("  未拆分——拆分需要改 `boot` 源文件，超出本任务文件所有权）。")
            sb.appendLine("- 同一 JVM 内顺序测量：后段读数包含前段未回收的残留；`S1r` 用于确认一次性占用确实退回。")
            sb.appendLine("- 池估算是布局模型，不是 `Instrumentation.getObjectSize`；与实测的差额见 §2 两行合计。")
            sb.appendLine("- TODO(verify)：Android ART 上的实际堆上限（largeHeap/`ActivityManager.getMemoryClass`）需真机读取；")
            sb.appendLine("  400MB 预算是否按 `dalvik.vm.heapsize` 还是进程 RSS 计，需组长确认口径。")
            for (n in notes) sb.appendLine("- $n")
            sb.appendLine()

            // ---- 8. 复跑 ----
            sb.appendLine("## 8. 复跑")
            sb.appendLine()
            sb.appendLine("```bash")
            sb.appendLine("source D:/DSH/eratw-安卓移植计划/.tooling/env.sh")
            sb.appendLine("cd <worktree>/eratw-android")
            sb.appendLine("ERATW_CORPUS=\"\$ERATW_CORPUS\" ./gradlew :core-vm:perfBaseline")
            sb.appendLine("# 报告：core-vm/build/perf-report.md")
            sb.appendLine("# 轮数可调（默认 5，越大越稳越慢）：ERATW_PERF_ROUNDS=9")
            sb.appendLine("```")
            sb.appendLine()
            sb.appendLine("读数稳定性判据：连续两次复跑，末段常驻差异应 < 2%，否则先看 `S0` 的 min/max 展布。")
            return sb.toString()
        }
    }

}

// ----------------------------------------------------------------------------
// 工具（纯函数，文件内可见；放顶层以便 [PerfBaseline] 与其嵌套 [Run] 共用）
// ----------------------------------------------------------------------------

/** 与 `LogicalLineReader.decode` 同规则：UTF-8 严格校验失败 → Shift-JIS（含 BOM 剥离）。 */
private fun decodeCorpus(bytes: ByteArray): String {
    val hasBom = bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    val body = if (hasBom) bytes.copyOfRange(3, bytes.size) else bytes
    return try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(body)).toString()
    } catch (e: CharacterCodingException) {
        String(body, Charset.forName("Shift_JIS"))
    }
}

/** 毫秒精度（长段用整数毫秒即可；子毫秒段由调用方另计）。 */
private fun msSince(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

/** 手写 MiB 格式化（不用 String.format，避免 locale 与 API 差异）。 */
private fun mib(bytes: Long): String {
    val neg = bytes < 0
    val abs = if (neg) -bytes else bytes
    val tenths = (abs * 10 + 524_288) / 1_048_576
    return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10} MiB"
}

private fun signed(bytes: Long): String =
    (if (bytes < 0) "-" else "+") + mib(if (bytes < 0) -bytes else bytes)

private fun percent(part: Long, whole: Long): String {
    if (whole <= 0) return "-"
    val tenths = (part * 1000 + whole / 2) / whole
    return "${tenths / 10}.${tenths % 10}%"
}

private fun reportPathOf(): Path {
    val override = System.getenv("ERATW_PERF_REPORT")?.trim()?.takeIf { it.isNotEmpty() }
    return if (override != null) {
        Path.of(override).toAbsolutePath().normalize()
    } else {
        Path.of("build", "perf-report.md").toAbsolutePath().normalize()
    }
}

/**
 * 读 git HEAD（不调用 git 可执行文件，直接读 `.git`）。
 * 兼容 worktree：`.git` 是文件（`gitdir: ...`）时顺着 `commondir` 找 refs。
 */
private fun gitHead(): String {
    var dir: Path? = Path.of("").toAbsolutePath().normalize()
    var guard = 0
    while (dir != null && guard++ < 8) {
        val dot = dir.resolve(".git")
        if (Files.exists(dot)) {
            return if (Files.isDirectory(dot)) {
                resolveRef(dot)
            } else {
                val line = runCatching { Files.readAllLines(dot) }.getOrNull()
                    ?.firstOrNull { it.startsWith("gitdir:") }
                val raw = line?.removePrefix("gitdir:")?.trim() ?: return "(unresolved .git file)"
                val p = Path.of(raw)
                resolveRef(if (p.isAbsolute) p else dir.resolve(p).normalize())
            }
        }
        dir = dir.parent
    }
    return "(no .git)"
}

private fun resolveRef(gitDir: Path): String {
    val head = runCatching { Files.readString(gitDir.resolve("HEAD")).trim() }.getOrNull()
        ?: return "(no HEAD)"
    if (!head.startsWith("ref:")) return head
    val ref = head.removePrefix("ref:").trim()
    val common = runCatching {
        val c = Files.readString(gitDir.resolve("commondir")).trim()
        val p = Path.of(c)
        if (p.isAbsolute) p else gitDir.resolve(p).normalize()
    }.getOrDefault(gitDir)
    runCatching { Files.readString(common.resolve(ref)).trim() }.getOrNull()?.let { return it }
    val packed = runCatching { Files.readAllLines(common.resolve("packed-refs")) }.getOrNull()
        ?: return "(unresolved $ref)"
    for (l in packed) {
        if (l.endsWith(" $ref")) return l.substringBefore(' ')
    }
    return "(unresolved $ref)"
}

/**
 * JVM 入口（`mainClass = com.eratw.port.perf.PerfBaselineKt`，见 `core-vm/build.gradle.kts`
 * 的 `perfBaseline` 任务）。退出码：0 = 报告已产出；1 = 中途失败；2 = 语料缺失。
 */
fun main() {
    exitProcess(PerfBaseline.run())
}
