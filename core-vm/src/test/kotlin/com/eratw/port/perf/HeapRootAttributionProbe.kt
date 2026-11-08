package com.eratw.port.perf

import com.eratw.port.core.code.CallArgIndex
import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.BlockRefs
import com.eratw.port.core.loader.BlockU32s
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.perf.ResidentWalker
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.NoSaveSystemOps
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.system.exitProcess

// ===========================================================================
// OBS-HEAPROOT 归因探针：真产物装载到 GameSession 装配完成后，按「根」逐面报数。
//
// 与邻居装置的分工：`deviceTierProbe` 量「哪一段把堆吃穿」（区间），
// `HeapCompositionProbeTest` 量「装载链稳态构成 + 逐窗账单」（JUnit 装置），
// 本探针把出口固化成**正式任务** `:core-vm:heapRootAttribution`，产出 R7 波次
// （codeBase 偏移基座 / 组合池 / 零拷贝行号表）要的「280 MiB 里各面各占多少」逐根表。
//
// 每个面的读数来源逐项标注，**不混为「实测」**：
// - `枚举-精确公式`：该面自己可枚举的容量口径（数组长度 × 元素宽 + SizeModel 对象头）；
// - `枚举-遍历模型`：ResidentWalker 身份去重遍历（对象图；模型误差 ±10～20%，口径声明与
//   HeapCompositionProbeTest 类注同源）；
// - `jcmd直方图`：`jcmd <pid> GC.class_histogram` 的 JVM 权威 live 字节（只作交叉核对，
//   与「同时服务两个面的族」（FormStringTerm/FormPart）的单独成行）；
// - `GC差`：HeapMeter 连打 System.gc 取最小的两次差。
//
// 恒等式（自证，进退出码）：`入账面合计 + 未归类 = live 总额（GC差）`。未归类是兜底行，
// 逐 MiB 可见、不抹平；入账合计越过 live 总额 + 容差（重复计账）时探针 exit 1。
// 账面算术本身钉在 `HeapRootLedgerTest`（定向单测，不依赖产物）。
//
// 消费序即归属序（身份去重、先到先归）；换序会移动归属但不动总额 —— 依赖消费序的
// 归属都写进「归属待定」一节，不硬塞某一面把账做平。
// ===========================================================================

private const val MIB = 1024L * 1024L

/** 一行账面。counted=false 的行（交叉核对、横切族、边际、装配后再读、小计）**不进**总账求和。 */
internal class LedgerRow(
    val surface: String,
    val item: String,
    val count: String,
    val bytes: Long,
    val source: String,
    val counted: Boolean,
    val note: String = "",
)

/**
 * 逐根账面：`入账合计 + 未归类 = live 总额` 这条恒等式的载体。
 *
 * 判据（[RootLedger.Verdict]）：
 * - `OVERCOUNT` —— 入账合计 > live + 容差：有面把同一批对象重复入账（归因 bug，必红）；
 * - `BIG_GAP` —— 未归类 > live × [RootLedger.gapWarnRatio]：有面没点到名（警告、不判红，
 *   未归类逐 MiB 可见）；
 * - `BALANCED` —— 其余。
 */
internal class RootLedger(val liveBytes: Long, val gapWarnRatio: Double = 0.25) {
    val rows = ArrayList<LedgerRow>()

    fun add(
        surface: String,
        item: String,
        count: String = "",
        bytes: Long,
        source: String = "枚举-精确公式",
        counted: Boolean = true,
        note: String = "",
    ) {
        rows.add(LedgerRow(surface, item, count, bytes, source, counted, note))
    }

    fun countedSum(): Long = rows.filter { it.counted }.sumOf { it.bytes.coerceAtLeast(0L) }

    /** 未归类兜底 = live 总额 - 入账合计（可为负 —— 负即重复计账，由 [verdict] 判 OVERCOUNT）。 */
    fun unclassified(): Long = liveBytes - countedSum()

    fun surfaceTotals(): Map<String, Long> =
        rows.filter { it.counted }.groupBy { it.surface }
            .mapValues { e -> e.value.sumOf { r -> r.bytes.coerceAtLeast(0L) } }

    enum class Verdict { BALANCED, OVERCOUNT, BIG_GAP }

    /** 容差 ±10 MiB = HeapMeter 既有标定（GC 最小值噪声 + SizeModel 建模误差的联合展布）。 */
    fun verdict(tolBytes: Long = 10 * MIB): Verdict {
        val sum = countedSum()
        if (sum > liveBytes + tolBytes) return Verdict.OVERCOUNT
        val gap = liveBytes - sum
        if (gap > (liveBytes * gapWarnRatio).toLong()) return Verdict.BIG_GAP
        return Verdict.BALANCED
    }
}

/** 探针主体（只在测试源集存在；产品面 core-vm main 一个字节都不动）。 */
internal class HeapRootAttributionProbe {

    private val sizer = SizeModel(SizeModel.detectRefWidth())
    private val report = StringBuilder()

    /** 本次运行的账本（writeReport 与 §3 估算读 Form 族横切行用）。 */
    private var ledgerRef: RootLedger? = null

    /** Form 族实例数与字节（§3 组合池估算的假设列用；walkSurfaces 里赋值）。 */
    private var formFamCount: Long = 0L
    private var formFamBytes: Long = 0L

    // ---- 反射与格式小工具（与 HeapCompositionProbeTest 同一套口径） ----

    private fun field(target: Any?, name: String): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                return runCatching { f.isAccessible = true; f.get(target) }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    private fun fieldByType(target: Any?, type: Class<*>): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type == type) {
                    return runCatching { f.isAccessible = true; f.get(target) }.getOrNull()
                }
            }
            c = c.superclass
        }
        return null
    }

    /** 一个对象的全部非静态引用字段值（壳已 markVisited 时用它逐字段作根，账不落进「未归类」）。 */
    private fun refFieldValues(o: Any): List<Any?> {
        val vals = ArrayList<Any?>()
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers) || f.type.isPrimitive) continue
                val v = runCatching { f.isAccessible = true; f.get(o) }.getOrNull()
                if (v != null) vals.add(v)
            }
            c = c.superclass
        }
        return vals
    }

    private fun arrLen(v: Any?): Int =
        if (v == null) 0 else runCatching { java.lang.reflect.Array.getLength(v) }.getOrDefault(0)

    private fun arrBytes(v: Any?): Long = if (v == null) 0L else sizer.arraySize(v)

    private fun nonNullCount(v: Any?): Int = (v as? Array<*>)?.count { it != null } ?: 0

    private fun slotLen(v: Any?): Int = when (v) {
        null -> 0
        is BlockRefs<*> -> v.size
        else -> arrLen(v)
    }

    private fun slotNonNull(v: Any?): Int = when (v) {
        null -> 0
        is BlockRefs<*> -> v.nonNullEntries()
        else -> nonNullCount(v)
    }

    private fun refTableBytes(v: Any?): Long = when (v) {
        null -> 0L
        is BlockRefs<*> -> {
            val blockShell = sizer.objectSize(AtomicReferenceArray<Any?>(1))
            val full = SizeModel.align8(16L + 4L * v.blockLength(0))
            val last = SizeModel.align8(16L + 4L * v.blockLength((v.blockCount - 1).coerceAtLeast(0)))
            val allocated = v.allocatedBlocks()
            val tail = if (allocated == v.blockCount && v.blockCount > 0) full - last else 0L
            sizer.objectSize(v) + SizeModel.align8(16L + 4L * v.blockCount) +
                allocated.toLong() * (blockShell + full) - tail
        }
        else -> arrBytes(v)
    }

    private fun strOffsets(t: Any?): IntArray? = field(t, "offsets") as? IntArray

    private fun strIndexEntries(t: Any?): Int = (field(t, "indexEntries") as Int?) ?: 0

    private fun strDataBytes(t: Any?): Long = (field(t, "dataBytes") as Int?)?.toLong() ?: 0L

    private fun strOffsetResident(t: Any?): Long {
        if (t == null) return 0L
        val offs = strOffsets(t)
        if (offs != null) return arrBytes(offs)
        return blockU32Bytes(field(t, "offsetBlocks"))
    }

    private fun blockU32Bytes(v: Any?): Long {
        val t = v as? BlockU32s ?: return if (v == null) 0L else arrBytes(v)
        val full = SizeModel.align8(16L + 4L * t.blockLength(0))
        val last = SizeModel.align8(16L + 4L * t.blockLength((t.blockCount - 1).coerceAtLeast(0)))
        val filled = t.filledBlocks()
        val tail = if (filled == t.blockCount && t.blockCount > 0) full - last else 0L
        return sizer.objectSize(t) + SizeModel.align8(16L + 4L * t.blockCount) +
            filled.toLong() * full - tail
    }

    private fun nodeOffsetCount(v: Any?): Int = (field(v, "size") as Int?) ?: 0

    private fun nodeOffsetsBytes(v: Any?): Long {
        if (v == null) return 0L
        val bases = field(v, "bases") as IntArray? ?: return 0L
        val deltas = field(v, "deltas") as ShortArray? ?: return 0L
        val overflow = field(v, "overflow") as Map<*, *>?
        val of = overflow?.values?.sumOf { (it as IntArray).size * 4L } ?: 0L
        return arrBytes(bases) + arrBytes(deltas) + of
    }

    private fun bufLimit(bb: Any?): Long =
        ((bb as? ByteBuffer)?.let { (it as java.nio.Buffer).limit().toLong() }) ?: 0L

    private fun mib(bytes: Long): String {
        val neg = bytes < 0
        val abs = if (neg) -bytes else bytes
        val tenths = (abs * 10 + 524_288) / MIB
        return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10}"
    }

    private fun group(n: Number): String {
        val s = n.toLong().toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(s[i])
        }
        return sb.toString()
    }

    private fun pct(part: Long, whole: Long): String =
        if (whole <= 0) "-" else {
            val t = (part * 1000 + whole / 2) / whole
            "${t / 10}.${t % 10}%"
        }

    private fun simpleName(fqcn: String): String =
        fqcn.substringAfterLast('.').replace('$', '.')

    private fun line(s: String = "") {
        println(s)
        report.appendLine(s)
    }

    // ------------------------------------------------------------------
    // 主流程
    // ------------------------------------------------------------------

    fun run(erbcDir: Path, gameRoot: Path) {
        val walkOn = System.getenv("ERATW_PROBE_WALK")?.trim() != "0"
        val flatOn = System.getenv("ERATW_PROBE_FLAT")?.trim() == "1"
        val programFile = erbcDir.resolve("program.erbc")
        val metaFile = erbcDir.resolve("program.meta")
        val sidecarFile = erbcDir.resolve("program.stmt.idx")
        require(Files.isRegularFile(metaFile)) { "缺 program.meta：$metaFile（侧车与产物必须同源）" }

        line("# OBS-HEAPROOT 逐根归因表（真产物装载到 GameSession 装配完成；只测量，产品零改动）")
        line()
        line("- 产物：`$erbcDir`（erbc ${group(Files.size(programFile))} B / meta ${group(Files.size(metaFile))} B / 侧车 " +
            (if (Files.isRegularFile(sidecarFile)) "${group(Files.size(sidecarFile))} B" else "缺失") + "）")
        line("- 游戏根：`$gameRoot`")
        line("- JVM：${HeapMeter.jvmName()} 挡位=${mib(HeapMeter.max())} MiB 收集器=${HeapMeter.gcNames()} GC轮=${HeapMeter.rounds}")
        line("- 装载口径：`compat=false`、`flatStatements=$flatOn`（缺省 = 设备侧关扁平池，与 `GameHost`/`deviceTierProbe` 一致）；" +
            "systemOps=NoSaveSystemOps（不碰盘）；randSeed=20260226；consoleWidth=199；meta 解析为串行档" +
            "（设备侧与装载并行只影响瞬时峰值，不影响装配完的 live 构成——侧车本体在装配后无读者）")
        line("- 遍历档：walk=$walkOn（挡位堆养不起访问集时设 ERATW_PROBE_WALK=0，只出 live 总额）")
        line()

        val s0 = HeapMeter.settled()
        println("[heaproot] S0 基线（装载之前，探针脚手架之外）= ${mib(s0)} MiB")

        val tLoad = System.nanoTime()
        val program = ProgramLoader.loadMapped(programFile, compat = false, flatStatements = flatOn)
        println("[heaproot] program 装载 ${(System.nanoTime() - tLoad) / 1_000_000} ms（惰性池=${program.holdsLazyAnyPool}）")
        val tMeta = System.nanoTime()
        var metadata: BootMetadata? = BootMetadata.read(metaFile)
        println("[heaproot] meta 解析 ${(System.nanoTime() - tMeta) / 1_000_000} ms")
        seamGc("meta→session")

        val tSess = System.nanoTime()
        val session = GameSession(
            gameRoot = gameRoot,
            program = program,
            metadata = metadata!!,
            consoleWidth = 199,
            randSeed = 20260226L,
            systemOps = NoSaveSystemOps,
        )
        println("[heaproot] session 装配 ${(System.nanoTime() - tSess) / 1_000_000} ms")
        metadata = null // GameSession 不持侧车本体；放掉本地引用，装配完即无读者的那部分随 GC 归还
        val live = HeapMeter.settled()
        val liveBase = live - s0
        println(
            "[heaproot] 装载+装配完成：live=${mib(live)} MiB（基线 ${mib(s0)}，净常驻 **${mib(liveBase)} MiB**）；" +
                "对照 uipreview serve -Xmx512m 同相位 280 MiB",
        )

        val ledger = RootLedger(liveBase)
        ledgerRef = ledger
        if (walkOn) {
            walkSurfaces(ledger, program, session, s0)
            materializedSample(ledger, program)
        } else {
            ledger.add("(walk=0)", "只出 live 总额；各面读数未采", "", 0L, "档位声明",
                note = "设备挡位复刻：-Pprobe.xmx=512m + ERATW_PROBE_WALK=0")
        }
        jcmdCrossCheck(ledger)

        val sum = ledger.countedSum()
        val unclassified = ledger.unclassified()
        val verdict = ledger.verdict()

        line()
        line("## 1. 逐根归因表（入账面 + 交叉核对行；单位 MiB）")
        line()
        line("| 面 | 项 | 计数 | MiB | 来源 | 入账 | 备注 |")
        line("|---|---|---|---:|---|---|---|")
        for (r in ledger.rows) {
            line("| ${r.surface} | ${r.item} | ${r.count} | ${if (r.bytes < 0) "(堆外)" else mib(r.bytes)} | ${r.source} | ${if (r.counted) "是" else "否"} | ${r.note} |")
        }
        line("| **入账面合计** | | | **${mib(sum)}** | 枚举(公式+遍历) | |")
        line("| **未归类（兜底行，不抹平）** | live 总额 - 入账合计 | | **${mib(unclassified)}**（${pct(unclassified, liveBase)}） | GC差 | 是 |")
        line("| **live 总额** | 装载+装配完成、GC ${HeapMeter.rounds} 轮取最小（已扣 S0） | | **${mib(liveBase)}** | GC差 | |")
        line()
        line("- 判定：`$verdict`（容差 ±10 MiB = HeapMeter 既有标定；BIG_GAP 只警告，未归类逐 MiB 可见）")
        line("- 来源口径互不混用：`枚举-*` 是 SizeModel 布局/身份去重遍历（模型，非权威）；`jcmd直方图` 是 JVM 权威 live（只做交叉核对与横切族单独行）；`GC差` 是 Runtime 口径稳态差。")
        val surfTotals = ledger.surfaceTotals()
        line()
        line("### 1b. 各面合计（入账行按面聚合，占比对 live 总额）")
        line()
        line("| 面 | MiB | 占比 |")
        line("|---|---:|---:|")
        for ((s, b) in surfTotals.entries.sortedByDescending { it.value }) {
            line("| $s | ${mib(b)} | ${pct(b, liveBase)} |")
        }

        writeReport(erbcDir, gameRoot, program, liveBase, sum, unclassified, verdict)
        runCatching { session.close() }

        if (verdict == RootLedger.Verdict.OVERCOUNT) {
            println("[heaproot] 归因不闭合：入账合计 ${mib(sum)} 越过 live ${mib(liveBase)} + 容差 ⇒ 有面重复计账，exit 1")
            exitProcess(1)
        }
        exitProcess(0)
    }

    /** 设备宿主同款交界回收（`always` 口径：进 session 前恒收；稳态 live 与收的时机无关，留一行日志对照设备屏）。 */
    private fun seamGc(stage: String) {
        val used = HeapMeter.used() / MIB
        val max = HeapMeter.max() / MIB
        System.gc()
        println("  [seamGc] $stage used=${used}MiB / max=${max}MiB ⇒ 收")
    }

    // ------------------------------------------------------------------
    // 逐根遍历（消费序 = 归属序，逐条写死）
    // ------------------------------------------------------------------

    private fun walkSurfaces(ledger: RootLedger, program: Program, session: GameSession, s0: Long) {
        val walker = ResidentWalker(sizer)

        fun bucket(surface: String, item: String, vararg roots: Any?, note: String = ""): Long {
            val t = System.nanoTime()
            var b = 0L
            for (r in roots) b += walker.consume(r)
            val ms = (System.nanoTime() - t) / 1_000_000
            ledger.add(surface, item, "", b, "枚举-遍历模型", note = if (note.isEmpty()) "耗时 ${ms} ms" else "$note；耗时 ${ms} ms")
            return b
        }

        fun shellRow(surface: String, item: String, o: Any?, note: String = "") {
            if (o == null) return
            ledger.add(surface, item, "", sizer.objectSize(o), "枚举-精确公式", note = note)
        }

        val pool = field(program, "pool")!!
        val lazyStmt = field(pool, "lazyStatements")
        val lazyExpr = field(pool, "lazyExprs")
        val stmtArena = lazyStmt?.let { field(it, "arena") }
        val stmtStrings = stmtArena?.let { field(it, "strings") }
        val exprArena = lazyExpr?.let { field(it, "arena") }
        val exprStrings = exprArena?.let { field(it, "strings") }
        val fnTable = program.functions
        val fnStrings = field(fnTable, "strings")
        val fnParam = field(fnTable, "paramNames")

        // ============ 面 1：指令/code 段 ============
        val codeSource = program.codeSource
        val pageScratch = arrLen(field(codeSource, "pageScratch")) / 4
        val pageCache = field(codeSource, "cache")
        ledger.add(
            "指令/code 段", "MappedCodeSource 换页缓冲 + 页缓存表",
            "常驻页 ${nonNullCount(pageCache)} × ${pageScratch} 字",
            arrBytes(field(codeSource, "pageScratch")) + arrBytes(pageCache),
            "枚举-精确公式",
            note = "CODE 段本体 = 只读映射（堆外 ${group(bufLimit(field(codeSource, "read")))} B 地址空间），不进 Java 堆账；" +
                "运行期最坏 +${mib(64L * (16L + 4096L * 4L))} MiB（CodeSource PAGE_CACHE_SLOTS，口径同 HeapCompositionProbe §1）",
        )
        bucket("指令/code 段", "CodeSource 壳与对象图残余", codeSource)

        // ============ 面 2：常量池 ============
        val gLongs = field(pool, "longs") as List<*>
        walker.markVisited(gLongs)
        ledger.add(
            "常量池", "longs（数值常量，装箱 Long）", "${group(gLongs.size)} 条",
            gLongs.size * 24L + SizeModel.align8(16L + 4L * gLongs.size) + 24L,
            "枚举-精确公式", note = "Long 壳 24 B/条 —— 条数小，装箱无痛",
        )
        val poolExprs = field(pool, "exprs") as List<*>
        walker.markVisited(poolExprs)
        var poolExprBytes = 0L
        for (e in poolExprs) poolExprBytes += walker.consume(e)
        ledger.add(
            "常量池", "exprs 表达式树（惰性表达式池接上时这里应为空壳）", "${group(poolExprs.size)} 条",
            poolExprBytes, "枚举-遍历模型",
        )
        shellRow("常量池", "ConstantPool 壳", pool)

        // ============ 面 3：串表 / 局部串表（全局串先归，各面同实例不重复计） ============
        val poolStrings = field(pool, "strings") as List<*>
        var gStrBytes = 0L
        for (s in poolStrings) {
            val str = s as String
            gStrBytes += SizeModel.stringBytes(str)
            walker.markVisited(str)
        }
        walker.markVisited(poolStrings)
        ledger.add(
            "段0 全局串表", "ConstantPool.strings（背衬 + 逐串 compact-string 公式）",
            "${group(poolStrings.size)} 条", gStrBytes + SizeModel.align8(16L + 4L * poolStrings.size) + 24L,
            "枚举-精确公式",
        )
        val paramCounts = fnParam?.let { field(it, "counts") }
        val paramFrom = fnParam?.let { field(it, "dataFrom") }
        val paramStrings = fnParam?.let { field(it, "strings") }
        for ((tag, sp) in listOf(
            "段3 语句局部串表" to stmtStrings,
            "段2 表达式局部串表" to exprStrings,
            "段4 函数名局部串表" to fnStrings,
            "段8 形参名局部串表" to paramStrings,
        )) {
            if (sp == null) continue
            val offs = strOffsets(sp)
            val offItems = offs?.size ?: strIndexEntries(sp)
            ledger.add(
                tag, "偏移表（整张数组或稀疏分块真身）", "项数 ${group(offItems - 1)} × 4 B",
                strOffsetResident(sp), "枚举-精确公式",
                note = "数据块 = 映射（堆外 ${group(strDataBytes(sp))} B）；memoize=${field(sp, "memoize")}",
            )
            walker.markVisited(offs)
            walker.markVisited(field(sp, "offsetBlocks"))
            val cache = field(sp, "cache")
            if (cache != null) {
                var dec = 0L
                for (s in (cache as Array<*>)) {
                    if (s is String) {
                        dec += SizeModel.stringBytes(s)
                        walker.markVisited(s)
                    }
                }
                ledger.add(tag, "已解码备忘（cache 数组 + 钉住的 String 实例）",
                    "已解码 ${group(nonNullCount(cache))} 条", arrBytes(cache) + dec, "枚举-精确公式")
                walker.markVisited(cache)
            }
        }
        ledger.add(
            "段8 形参名局部串表", "counts + dataFrom 索引两张", "${group(arrLen(paramCounts))} 函数",
            arrBytes(paramCounts) + arrBytes(paramFrom), "枚举-精确公式",
        )
        walker.markVisited(paramCounts)
        walker.markVisited(paramFrom)

        // ============ 面 4：语句对象树（惰性语句池 + 扁平池若开 + 段7 实参索引） ============
        if (lazyStmt != null) {
            val recordOffsets = field(lazyStmt, "recordOffsets")!!
            val roHeap = field(recordOffsets, "heap") as IntArray?
            if (roHeap != null) {
                ledger.add("语句对象树", "语句记录偏移表 IntArray（回落扫描档）", "${group(roHeap.size)} × 4 B",
                    arrBytes(roHeap), "枚举-精确公式")
                walker.markVisited(roHeap)
            } else {
                ledger.add("语句对象树", "语句记录偏移表（侧车只读映射，MEM10 刀 E 形态）",
                    "${group(field(recordOffsets, "size") as Int)} 条 u32", 0L, "枚举-精确公式",
                    note = "堆内 0 —— codeBase 偏移基座在记录区这条腿**已经吃到**；取用 = 一次 4 B 映射读")
            }
            walker.markVisited(recordOffsets)
            val cached = field(lazyStmt, "cached")
            val nodesArr = field(lazyStmt, "nodes") ?: field(stmtArena, "nodes")
            val nodeOffsets = field(stmtArena, "nodeOffsets")
            ledger.add(
                "语句对象树", "条目引用表（分块：块位表 + 已分配块）",
                "${group(slotLen(cached))} 槽 / 已物化 ${group(slotNonNull(cached))}",
                refTableBytes(cached), "枚举-精确公式",
                note = "块宽 4,096 槽：没物化到的块一字节不占",
            )
            ledger.add(
                "语句对象树", "节点表 + 节点偏移表（惰性置空后的骨架）",
                "${group(slotLen(nodesArr))} 槽 + ${group(nodeOffsetCount(nodeOffsets))} 条",
                refTableBytes(nodesArr) + nodeOffsetsBytes(nodeOffsets), "枚举-精确公式",
            )
            walker.markVisited(cached)
            walker.markVisited(nodesArr)
            walker.markVisited(nodeOffsets)
            walker.markVisited(field(lazyStmt, "window"))
            val interner = field(lazyStmt, "interner")
            if (interner != null) {
                val hashes = field(interner, "hashes")
                val reps = field(interner, "reps")
                val strPool = field(interner, "strings")
                ledger.add(
                    "语句对象树", "ArgInterner 规范表（装载/装配期去重种下的共享表）",
                    "cap=${group(arrLen(hashes))}", arrBytes(hashes) + arrBytes(reps) +
                        (if (strPool == null) 0L else sizer.objectSize(strPool) + arrBytes(field(strPool, "table"))),
                    "枚举-精确公式",
                )
                walker.markVisited(hashes)
                walker.markVisited(reps)
                walker.markVisited(strPool)
                walker.markVisited(field(strPool, "table"))
                // 代表树逐元素作根（reps 数组本体已入账 ⇒ 数组挡门、元素放行）
                val repRoots = (reps as? Array<*>)?.filterNotNull().orEmpty().toTypedArray()
                bucket("语句对象树", "ArgInterner 代表树（共享实参子树实例；组合池的前身）", *repRoots)
            }
            bucket("语句对象树", "池壳/arena/串表壳/窗口残余（骨架与串已单列）", lazyStmt)
        } else {
            ledger.add("语句对象树", "本产物档无惰性语句池（非映射装载？）", "", 0L, "档位声明")
        }
        if (lazyExpr != null) {
            val roots = field(lazyExpr, "roots")
            val nodesArr = field(lazyExpr, "nodes") ?: field(exprArena, "nodes")
            val nodeOffsets = field(exprArena, "nodeOffsets")
            ledger.add(
                "语句对象树", "段2 惰性表达式池骨架（池根表 + 节点表 + 节点偏移表）",
                "${group((field(lazyExpr, "poolCount") as Int?) ?: 0)} 根 + ${group(slotLen(nodesArr))} 槽 + " +
                    "${group(nodeOffsetCount(nodeOffsets))} 条",
                blockU32Bytes(roots) + refTableBytes(nodesArr) + nodeOffsetsBytes(nodeOffsets),
                "枚举-精确公式", note = "表达式面是语句实参的共享底座，计入「语句对象树」总额",
            )
            walker.markVisited(roots)
            walker.markVisited(nodesArr)
            walker.markVisited(nodeOffsets)
            walker.markVisited(field(exprArena, "lazyWin"))
            bucket("语句对象树", "段2 表达式池壳/arena 残余（骨架与串已单列）", lazyExpr)
        }
        if (program.flatStatementsEnabled) {
            bucket("语句对象树", "扁平语句池 flatStmtSource（分片 LRU，开启档才入账）", program.flatStmtSource)
        } else {
            ledger.add("语句对象树", "扁平语句池", "设备口径 flatStatements=false ⇒ 未构建", 0L, "档位声明",
                note = "开档对照：ERATW_PROBE_FLAT=1 复跑本表")
        }
        val cai = field(program, "callArgIndex") as CallArgIndex?
        if (cai != null) {
            val pcs = field(cai, "pcs")
            val vals = field(cai, "vals")
            ledger.add(
                "语句对象树", "段7 CallArgIndex 本体（pcs + vals 两张定长数组 + 壳）",
                "${group(arrLen(pcs))} 点 / 独立表 ${group(cai.distinctListCount())} 张",
                arrBytes(pcs) + arrBytes(vals) + sizer.objectSize(cai), "枚举-精确公式",
                note = "MEM9 刀 1 的既有战果：LinkedHashMap 壳已删；再瘦 = 组合池的账（见 §3 估算）",
            )
            walker.markVisited(pcs)
            walker.markVisited(vals)
            walker.markVisited(cai)
            val listRoots = ArrayList<Any>()
            (vals as? Array<*>)?.forEach { if (it != null) listRoots.add(it) }
            bucket("语句对象树", "独立实参表 + CallArg 实例 + 名字串（去重后逐张作根）", *listRoots.toTypedArray())
        }

        // ============ 面 4b：指令行号表（段6，零拷贝刀口的现形态） ============
        ledger.add(
            "指令行号表", "LineTable 装载后常驻（分块形态 = 块位表 + 已灌块）",
            "${group(program.instructionCount)} 指令 / ${group(program.lineTableBlockStats().first)} 块位 / " +
                "已灌 ${group(program.lineTableBlockStats().second)} 块",
            program.lineTableResidentBytes, "枚举-精确公式",
            note = if (program.lineTableIsPaged) "MEM9 刀 2 分块形态；全灌满硬上限 = count×2 B" else "整表形态（退回档）",
        )
        walker.markVisited(field(program, "lineTable"))

        // ============ 面 5：函数表 / 标签表（程序侧 + 侧车投影 + 剪枝标签） ============
        val fnRecordOffsets = field(fnTable, "recordOffsets")
        ledger.add(
            "函数表/标签表", "映射函数表骨架（recordOffsets）", "${group(arrLen(fnRecordOffsets))} 函数",
            arrBytes(fnRecordOffsets), "枚举-精确公式",
        )
        walker.markVisited(fnRecordOffsets)
        walker.markVisited(fnTable) // List 形态：整根 consume 会当场物化全部 FunctionInfo，禁止
        walker.markVisited(field(fnTable, "window"))
        bucket("函数表/标签表", "已物化 FunctionInfo（装配期 EngineFunctionPolicy 全量填满）", field(fnTable, "cache"))
        bucket("函数表/标签表", "映射函数表壳 + 名/形参串表壳 + 窗口残余", fnParam, fnStrings)
        val sidecar = field(session, "sidecar")
        if (sidecar != null) {
            walker.markVisited(sidecar)
            shellRow("函数表/标签表", "SidecarTables 壳", sidecar)
            bucket("函数表/标签表", "剪枝标签表（900 函数 / 1,623 条口径；侧车投影）", field(sidecar, "labels"))
            bucket("数据节表", "PRINTDATA/STRDATA 表达式组（侧车按语句池下标直查）", field(sidecar, "sections"))
        }

        // ============ 会话侧消费序的分界：程序段整体挡门（store.aux 反挂边不许把程序再吞一遍） ============
        walker.markVisited(program)
        shellRow("会话壳残余", "Program 对象壳（程序段各面未含的最后一层）", program)

        // ============ 面 6：CSV 表 ============
        val gameData = session.gameData
        if (gameData != null) {
            // registry 被 store 共享引用：CSV 面先归（构造侧），变量池那行只剩壳与声明侧增量（归属待定 §4）。
            bucket("CSV 表", "GameData 总装（29 张 CsvNameTable + templates + itemPrices + registry）", gameData)
        } else {
            ledger.add("CSV 表", "gameData=null（CSV 目录缺失）", "", 0L, "档位声明")
        }

        // ============ 面 7：控制台行表 ============
        val console = session.console
        bucket("控制台行表", "sink 壳 + displayLines/pending/islands（装配完成期）", console)
        val displayLineClass = runCatching {
            console.javaClass.declaredClasses.firstOrNull { it.simpleName == "DisplayLine" }
        }.getOrNull()
        val maxLogLines = (field(console, "maxLines") as Int?) ?: -1
        ledger.add(
            "控制台行表", "游玩期上界（口径行，不入账）", "maxLines=$maxLogLines + 折行副本",
            0L, "枚举-公式(不入账)", counted = false,
            note = "单行 DisplayLine 浅尺寸 = ${displayLineClass?.let { sizer.shallowOf(it) } ?: "取不到"} B；" +
                "每行的 TextSpan/按钮列表是对象图，游玩期涨多少取决于屏上行数 —— " +
                "TODO(verify)：本探针只到装配完，游玩期读数归 deviceTierProbe 的 title 相位",
        )

        // ============ 面 8：变量池（全局 + 私有 + 角色维；运行期图挡在门外自己成行） ============
        val store = session.store
        val runtime = session.runtime
        walker.markVisited(session)
        shellRow("会话壳残余", "GameSession 对象壳", session)
        walker.markVisited(runtime) // aux→runtime 反挂边；运行期面用逐字段根在后面单独成行
        // 先归函数表运行期投影（runtime 壳挡着不影响逐根 consume）
        val runtimeFnTable = fieldByType(runtime, FunctionTable::class.java)
        bucket("函数表/标签表", "运行期 FunctionTable（侧车 FuncMeta 投影：key/name 串 + 查询表）", runtimeFnTable)
        walker.markVisited(store)
        shellRow("变量池", "VariableStore 壳", store)
        val registry = field(store, "registry")
        val userVars = field(store, "userVars") as? Map<*, *>
        val privateVars = field(store, "privateVars") as? Map<*, *>
        val templates = field(store, "templatesByNo") as? Map<*, *>
        val privateTotal = privateVars?.values?.sumOf { (it as? Map<*, *>)?.size ?: 0 } ?: 0
        ledger.add(
            "变量池", "声明量（计数行，不入账）",
            "全局用户 ${group(userVars?.size ?: 0)} / 私有 ${group(privateTotal)}（${group(privateVars?.size ?: 0)} 函数键）" +
                " / 角色模板 ${group(templates?.size ?: 0)}", 0L, "精确计数(不入账)", counted = false,
        )
        val varRoots = listOf(
            "系统全局整数列 gInt1/gInt2/gInt3（角色维数据主体）" to
                arrayOf(field(store, "gInt1"), field(store, "gInt2"), field(store, "gInt3"), field(store, "itemPriceSource")),
            "系统全局字符串列 gStr1/gStr0（存池下标）" to arrayOf(field(store, "gStr1"), field(store, "gStr0")),
            "角色表 CharacterTable（含 CHARADATA 用户列）" to arrayOf(field(store, "characters")),
            "VariableRegistry 壳与系统 VarDef（其 CsvNameTables 已归 CSV 面）" to arrayOf(registry),
            "字符串驻留池 StringTable" to arrayOf(field(store, "pool")),
            "全局用户变量四表（DimSpec+VarDef+UserVar+数据数组）" to arrayOf(
                userVars, field(store, "userVarsRaw"), field(store, "globalUserVars"), field(store, "savedUserVars"),
            ),
            "私有用户变量 privateVars + privateNames" to arrayOf(privateVars, field(store, "privateNames")),
            "局部变量态 localsByFunc/localLengths/frames/framePool" to arrayOf(
                field(store, "localsByFunc"), field(store, "localLengths"), field(store, "frames"), field(store, "framePool"),
            ),
            "角色模板 templatesByNo/templatesByName" to arrayOf(templates, field(store, "templatesByName")),
            "ERD 用户关键字表 userKeywordTables" to arrayOf(field(store, "userKeywordTables")),
            "绑定/名字记忆缓存 + 句柄表" to arrayOf(
                field(store, "bindNames"), field(store, "bindPriv"), field(store, "bindVals"),
                field(store, "upperNames"), field(store, "handleEntries"), field(store, "handleSlots"),
                field(store, "indexScratch"),
            ),
            "store.aux 的 aux 槽本体（skipState/宏表投影等；ProcessRuntime 图已挡门）" to arrayOf(field(store, "aux")),
            "全局/私有 #DIM 装载结果与告警清单（globalDimInstall 常量表等）" to
                arrayOf(session.globalDimInstall, session.privateDimFailures, session.erdWarnings, session.mouseWarnings),
            "store 未点名其余字段（catch-all 逐字段根）" to refFieldValues(store).toTypedArray(),
        )
        for ((name, roots) in varRoots) bucket("变量池", name, *roots)

        // ============ 面 9：运行期与宿主 ============
        // 宿主先归（ProcessRuntime 的字段挂着 builtinHost 引用，后归会把宿主图吞进运行期行）
        bucket("运行期与宿主", "宿主/配置/输入壳图（builtinHost→RootedBuiltinHost 文件IO与配置覆盖链等；整根 consume）",
            session.builtinHost, session.beginHost, session.inputSink, session.configStore)
        shellRow("运行期与宿主", "ProcessRuntime 壳（已挡门，逐字段成根）", runtime)
        bucket("运行期与宿主", "ProcessRuntime 图（stub 表/Vm/ExecContext/BEGIN 态；程序段与变量池已归各面）",
            *refFieldValues(runtime).toTypedArray())

        // ============ 横切族：FormStringTerm / FormPart 家族（归属定不了，单独成行） ============
        val classTally = walker.classTop(100_000)
        val fam = classTally.filter {
            it.first.startsWith("com.eratw.port.core.expr.Form") || it.first == "com.eratw.port.core.lexer.StrForm"
        }
        val famBytes = fam.sumOf { it.third }
        val famCount = fam.sumOf { it.second.toLong() }
        formFamCount = famCount
        formFamBytes = famBytes
        ledger.add(
            "FormStringTerm 族（横切，不重复入账）", "遍历逐类账单（FormStringTerm/FormPart/FormConversion 系）",
            "${group(famCount)} 实例", famBytes, "枚举-遍历模型(不入账)", counted = false,
            note = "实例散布在语句实参子树与数据节两面，身份去重已把它们分给先到者；这里给**族级**读数供 §3 组合池估算用。" +
                "明细：" + fam.joinToString(" / ") { "${simpleName(it.first)} x${group(it.second)}=${mib(it.third)}" },
        )

        // ============ 与 live 对账 + 逐类 top ============
        val withVisitedMap = HeapMeter.settled()
        ledger.add("对账(不入账)", "遍历完成后立即再收（walker 访问集还活着）", "",
            withVisitedMap - s0, "GC差", counted = false,
            note = "比 live_base 高的那截 = 访问集自身（几十 MiB 级），下一条根因即回落")
        walker.releaseVisited()
        val topText = classTally.take(15).joinToString(" / ") {
            "${simpleName(it.first)} x${group(it.second)}=${mib(it.third)}MiB"
        }
        ledger.add("对账(不入账)", "逐类 top15（枚举侧账单，供与 jcmd 直方图并排看）", "", 0L,
            "枚举-遍历模型(不入账)", counted = false, note = topText)

        // sanity（与 HeapCompositionProbe 同判据：形态改了本表口径即不成立）
        check(lazyStmt == null || (field(field(lazyStmt, "recordOffsets"), "size") as Int) == program.statementCount) {
            "recordOffsets 条数 != 语句池条数：形态改了"
        }
        println("[heaproot] 遍历去重对象 ${group(walker.objects)} 个；程序段反挂挡门后 live 读数未受污染（访问集已清）")
    }

    /** 等距抽样物化 —— 游玩期增量的外推尺（不入总账；无随机源，确定性）。 */
    private fun materializedSample(ledger: RootLedger, program: Program) {
        val sampleN = System.getenv("ERATW_PROBE_SAMPLE")?.toIntOrNull() ?: 1000
        if (sampleN <= 0) return
        val before = HeapMeter.settled()
        val stride = maxOf(1, program.statementCount / sampleN)
        var idx = 0
        var sampled = 0
        while (idx < program.statementCount && sampled < sampleN) {
            program.statement(idx)
            sampled++
            idx += stride
        }
        val after = HeapMeter.settled()
        val per = (after - before) / sampled.coerceAtLeast(1)
        ledger.add(
            "边际(不入账)", "等距抽样物化 ${sampled} 条语句", "每条 ${group(per)} B ⇒ 每 10 万条 ${mib(per * 100_000L)} MiB",
            after - before, "GC差", counted = false,
            note = "游玩期「脚本跑出来的那部分」外推尺（materializedStatementCount × 每条 B）",
        )
    }

    // ------------------------------------------------------------------
    // jcmd GC.class_histogram（JVM 权威 live；来源单独标注，只交叉核对不入账）
    // ------------------------------------------------------------------

    private fun jcmdCrossCheck(ledger: RootLedger) {
        if (System.getenv("ERATW_PROBE_JCMD")?.trim() == "0") {
            ledger.add("jcmd 交叉核对(不入账)", "ERATW_PROBE_JCMD=0 跳过", "", 0L, "jcmd直方图(不入账)", counted = false)
            return
        }
        val parsed = runCatching {
            val jcmd = Path.of(
                System.getProperty("java.home"), "bin",
                if (System.getProperty("os.name").lowercase().contains("win")) "jcmd.exe" else "jcmd",
            )
            if (!Files.isRegularFile(jcmd)) return@runCatching null
            val pid = ProcessHandle.current().pid().toString()
            val p = ProcessBuilder(jcmd.toString(), pid, "GC.class_histogram")
                .redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(240, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching null }
            if (p.exitValue() != 0) return@runCatching null
            text
        }.getOrNull()
        if (parsed == null) {
            ledger.add("jcmd 交叉核对(不入账)", "jcmd 不可用/超时（不硬造读数）", "", 0L, "jcmd直方图(不入账)",
                counted = false, note = "TODO(verify)：本机 java.home 下没有 jcmd 或 240 s 超时")
            return
        }
        val entries = ArrayList<Triple<Long, Long, String>>() // instances, bytes, class
        // JDK17 行形态：`   1:      8964       678448  [B (java.base@17.0.20.1)` —— 模块后缀剥掉再比类名。
        // MULTILINE 必带：Java 正则的 `$` 缺省只锚「整段输入末尾」，不带它逐行全灭（首轮实测解析 0 行的根因）。
        Regex(
            """\s*\d+:\s+(\d+)\s+(\d+)\s+(\S.*?)(?:\s+\([^)]*\))?\s*$""",
            RegexOption.MULTILINE,
        ).findAll(parsed).forEach { m ->
            entries.add(Triple(m.groupValues[1].toLong(), m.groupValues[2].toLong(), m.groupValues[3].trim()))
        }
        if (entries.isEmpty()) {
            ledger.add("jcmd 交叉核对(不入账)", "直方图解析 0 行（输出格式变了，如实报）", "", 0L, "jcmd直方图(不入账)", counted = false)
            return
        }
        val totalBytes = entries.sumOf { it.second }
        val fam = entries.filter {
            it.third.startsWith("com.eratw.port.core.expr.Form") || it.third == "com.eratw.port.core.lexer.StrForm"
        }
        val fnMeta = entries.filter { it.third.endsWith(".FunctionInfo") || it.third.endsWith(".FuncMeta") }
        val callArg = entries.filter { it.third.endsWith(".CallArg") }
        val str = entries.firstOrNull { it.third == "java.lang.String" }
        val barr = entries.firstOrNull { it.third == "[B" }
        val iarr = entries.firstOrNull { it.third == "[I" }
        ledger.add(
            "jcmd 交叉核对(不入账)", "GC.class_histogram live 总量", "${group(entries.size)} 类", totalBytes,
            "jcmd直方图", counted = false,
            note = "jcmd 自身先触发一次 full GC；与 HeapMeter live（GC差口径）应同量级，差值 = 对齐/统计口径差",
        )
        ledger.add(
            "jcmd 交叉核对(不入账)", "FormStringTerm/FormPart 族 live", "${group(fam.sumOf { it.first })} 实例",
            fam.sumOf { it.second }, "jcmd直方图", counted = false,
            note = "对照「FormStringTerm 族（横切）」的遍历模型行：两口径之差即模型误差实测",
        )
        ledger.add(
            "jcmd 交叉核对(不入账)", "FunctionInfo+FuncMeta live（函数表面包屑）", "${group(fnMeta.sumOf { it.first })} 实例",
            fnMeta.sumOf { it.second }, "jcmd直方图", counted = false,
        )
        ledger.add(
            "jcmd 交叉核对(不入账)", "CallArg live", "${group(callArg.sumOf { it.first })} 实例",
            callArg.sumOf { it.second }, "jcmd直方图", counted = false,
            note = "String=${str?.let { "${group(it.first)} 实例/${mib(it.second)} MiB" } ?: "-"}；" +
                "byte[]=${barr?.let { "${mib(it.second)} MiB" } ?: "-"}；int[]=${iarr?.let { "${mib(it.second)} MiB" } ?: "-"}" +
                "（串的字节背衬在 byte 数组行里，与「串表」面交叉看）",
        )
        val top10 = entries.sortedByDescending { it.second }.take(10)
            .joinToString(" / ") { "${simpleName(it.third)} ${mib(it.second)}MiB" }
        ledger.add("jcmd 交叉核对(不入账)", "top10 类（JVM 权威）", "", 0L, "jcmd直方图", counted = false, note = top10)
    }

    // ------------------------------------------------------------------
    // 余量与刀口估算 + 报告落盘
    // ------------------------------------------------------------------

    private fun writeReport(
        erbcDir: Path,
        gameRoot: Path,
        program: Program,
        liveBase: Long,
        sum: Long,
        unclassified: Long,
        verdict: RootLedger.Verdict,
    ) {
        val ledger = requireNotNull(ledgerRef) { "writeReport 前必须先建账本" }
        val surf = ledger.surfaceTotals()
        line()
        line("## 2. 对 512 MiB 挡位")
        line()
        val liveMi = liveBase / MIB
        line("- live 总额（本档 HotSpot，GC ${HeapMeter.rounds} 轮取最小、已扣 S0）= **${mib(liveBase)} MiB**；挡位 512 MiB ⇒ 余量 **${512 - liveMi} MiB**（本档挡位实际 = ${mib(HeapMeter.max())} MiB，换算只对账用）")
        line("- 假设 A1：桌面 HotSpot 与真机 ART 的 live 集同量级（压缩引用 4 B 同源、对象头差 ±5% 级）。TODO(verify)：真机没有同相位的 live 装置，唯一旁证 = uipreview serve -Xmx512m 的 280 MiB（用 `-Pprobe.xmx=512m ERATW_PROBE_WALK=0` 复跑本探针对表）。")
        line("- 游玩期增量外推尺见「边际(不入账)」行；G_ID 工作表单张图可达 122 MiB（platform 图面，不在 Java 堆账）——「余量 < 一张图的量级」就是 `used=510/512` GC 风暴的形状。")
        line()
        line("## 3. 刀口估算（R7：codeBase 偏移基座 / 组合池 / 零拷贝行号表）")
        line()
        val stmtBytes = surf["语句对象树"] ?: 0L
        val codeBytes = surf["指令/code 段"] ?: 0L
        val lineResident = program.lineTableResidentBytes
        val lineFull = program.instructionCount.toLong() * 2L
        val formFam = formFamBytes
        val fnBytes = (surf["函数表/标签表"] ?: 0L)
        line("| 刀 | 对应面 | 现读数 MiB | 可省下界 | 可省上界 | 假设与 TODO(verify) |")
        line("|---|---|---:|---:|---:|---|")
        line(
            "| codeBase 偏移基座 | 语句记录偏移 + code 段常驻 | ${mib(codeBytes)}（记录偏移已 =0，侧车映射形态） | 0 | ${mib(codeBytes)} | " +
                "记录区这条腿**已经吃到**（表内「语句记录偏移表」行堆内 0）；剩下只有页缓存/换页缓冲这 ${mib(codeBytes)} MiB 级。" +
                "装载瞬时收益在 churn 面，归 deviceTierProbe 的分配列。TODO(verify)：ART 上 mmap 页的 RSS 记账不同，别把 0 当白名单 |",
        )
        line(
            "| 零拷贝行号表 | 指令行号表（段6） | ${mib(lineResident)}（装载后块位表形态） | 0 | ${mib(lineResident)} | " +
                "现形态已是「装载后零拷贝」（MEM9 刀 2）；上界 = 装配/游玩真读到的块涨回来的全部。" +
                "全灌满硬上限 = count×2 B = ${mib(lineFull)} —— 游玩期涨回多少：TODO(verify)（本探针止于装配完） |",
        )
        line(
            "| 组合池（实参/表达式子树） | 语句对象树 + Form 族 + 函数表 | ${mib(stmtBytes)} + ${mib(formFam)}(横切) + ${mib(fnBytes)} | " +
                "${mib(formFam * 3L / 10L)} | ${mib((stmtBytes + fnBytes) * 5L / 10L)} | " +
                "下界假设：只把 Form 族（x${group(formFamCount)} 实例）的对象头/引用腿折进定长槽 —— 头 + 槽 ≈ 该族字节的 30～50%（SizeModel 头 12～16 B/对象 vs 组合槽 8 B），取保守 30% 折入；" +
                "上界假设：整棵实参/表达式对象图 + FunctionInfo 壳池化，扣掉 M6-PERFINT interning 已吃掉的一半 ⇒ 50%。" +
                "两头都是**模型投影不是落地实测**。TODO(verify)：R7 原型后以本表复跑为准 |",
        )
        line(
            "| 全局串表瘦身（顺带） | 段0 全局串表 | ${mib(surf["段0 全局串表"] ?: 0L)} | ${mib((surf["段0 全局串表"] ?: 0L) / 3L)} | ${mib((surf["段0 全局串表"] ?: 0L) * 2L / 3L)} | " +
                "String 对象 2 层头（壳 32 + 数组头 16）对短串占比大，打包进 byte 段可省 1/3～2/3；" +
                "串被各面共享，省的是**同一批实例**，不能与上行相加。TODO(verify)：装箱/驻留交互未建模 |",
        )
        line()
        line("- 以上四条**互有重叠面**，合计不能相加；每一刀的靶都以 §1 逐根表为准。")
        line()
        line("## 4. 归属待定（不硬塞、不对平账的行）")
        line()
        line("- FormStringTerm/FormPart：同时服务「语句对象树的实参子树」与「数据节表」，身份去重按先到归属 ⇒ 单独横切行（§1），两面都不重复入账；族级字节以 jcmd 行为权威交叉。")
        line("- VariableRegistry ↔ CSV 表：registry 由 GameData 构造、被 store 共享持有；消费序 = **CSV 面先归**（构造侧），变量池只留壳与声明侧增量。换序移动几 MiB 归属、不动总额。")
        line("- store.aux ↔ ProcessRuntime：aux 槽挂在变量池面，运行期图整根挡门后逐字段自成一「运行期与宿主」面；ExecContext 造的帧若同时被两边可达，归 store（先消费）。")
        line("- 全局串表 ↔ 各面同实例引用：逐串 markVisited 先归段0；各面局部串表解出的**不同实例**各归各面 —— 这是口径不是误差（memoize 关时两侧本就不共享）。")
        line("- 「对账(不入账)」与「jcmd 交叉核对(不入账)」行不参与恒等式；未归类是唯一兜底行。")
        line()
        line("## 5. 判定与出口")
        line()
        line("- 恒等式：入账合计 **${mib(sum)}** + 未归类 **${mib(unclassified)}**（${pct(unclassified, liveBase)}）= live **${mib(liveBase)}** MiB")
        line("- verdict = `$verdict`；OVERCOUNT（合计越 live + 10 MiB）⇒ 探针 exit 1。账面算术的自证用例见 `HeapRootLedgerTest`（不依赖产物，`:core-vm:test --tests \"*HeapRootLedger*\"`）。")
        line()
        line("## 6. 复现")
        line()
        line("```")
        line("ERATW_ROOT=<work-root>; source \$ERATW_ROOT/.tooling/env.sh")
        line("export ERATW_GAMEDIR=\"\$ERATW_ROOT/eratw-画蛇添足版eratw4.980\"")
        line("ERATW_ERBC_DIR=<erbc.v1 目录> ./gradlew --configure-on-demand :core-vm:heapRootAttribution -Pprobe.xmx=3g")
        line("旋钮：-Pprobe.xmx=512m + ERATW_PROBE_WALK=0（设备挡位只出 live）；ERATW_PROBE_FLAT=1（开扁平池对照）；")
        line("      ERATW_PROBE_JCMD=0（跳直方图）；ERATW_PROBE_SAMPLE=<n>（物化抽样条数，0=关）；ERATW_PROBE_OUT=<路径>（报告落点）；")
        line("      ERATW_PERF_ROUNDS=<n>（GC 轮数）。报告默认落 core-vm/build/heap-root-attribution.md（build 目录，不入库）")
        line("```")
        line()
        line("- 本次：产物 `$erbcDir`；游戏根 `$gameRoot`；耗时见各行 ms 列。")

        val dst = System.getenv("ERATW_PROBE_OUT")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(it) }
            ?: Path.of("build", "heap-root-attribution.md")
        runCatching {
            dst.parent?.let { Files.createDirectories(it) }
            Files.writeString(dst, report.toString(), java.nio.charset.StandardCharsets.UTF_8)
            println("[heaproot] 报告：$dst")
        }.onFailure { println("[heaproot] 报告写入失败：${it.message}") }
    }
}

/**
 * 正式入口（`:core-vm:heapRootAttribution`）。
 *
 * 与 `deviceTierProbe` 共用同一套产物查找与环境口径：那条量「哪一段把堆吃穿」（区间），
 * 这条量「280 MiB 里各面各占多少」（构成）。
 */
fun main() {
    val erbcDir = System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        ?: System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it).parent }
    if (erbcDir == null || !Files.isRegularFile(erbcDir.resolve("program.erbc"))) {
        println("[heaproot] 缺产物：设 ERATW_ERBC_DIR 指向含 program.erbc 的目录（本探针不回落构建缓存）")
        exitProcess(2)
    }
    val gameRoot = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
    if (gameRoot == null || !Files.isDirectory(gameRoot)) {
        println("[heaproot] 缺游戏根：设 ERATW_GAMEDIR")
        exitProcess(2)
    }
    val probe = HeapRootAttributionProbe()
    try {
        probe.run(erbcDir, gameRoot)
    } catch (oome: OutOfMemoryError) {
        println("[heaproot] OOM：本挡位养不起归因遍历 —— 用 -Pprobe.xmx=3g，或 ERATW_PROBE_WALK=0 只要 live 总额")
        exitProcess(1)
    }
}
