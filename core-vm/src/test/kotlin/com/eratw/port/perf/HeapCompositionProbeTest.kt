package com.eratw.port.perf

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.CallArgIndex
import com.eratw.port.core.loader.BlockRefs
import com.eratw.port.core.loader.BlockU32s
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.LoadStageMark
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.perf.ResidentWalker
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.NoSaveSystemOps
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.Array as JavaArray
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **mem3 堆构成探针**：真产物装载后「常驻到底是哪些字节」的第一份逐子系统分解
 * （只测量，产品零改动；`:core-vm` 测试源装置）。
 *
 * ## 它回答的问题
 *
 * 真机（vivo / Android 14 / largeHeap 512 MiB 挡位）装载峰值已砍到 410 MiB，但标题页
 * `Runtime.used` 稳态 182 MiB 从来没人拆过组成。本探针在同一份全量产物上
 * （`program.erbc` 329,311,670 B / `program.meta` 49,119,511 B / `program.stmt.idx` 6,866,568 B；
 * 3,971 文件 / 1,716,636 语句 / 898,111 + 827,992 节点 / 328,509 池根）给出三张表：
 *
 * 1. **稳态构成表**（headline）：装载后逐子系统常驻。索引骨架（惰性池留下的
 *    `IntArray` / null 填满的引用数组）用**精确**条数 × SizeModel 数组公式；
 *    对象图部分用 [ResidentWalker] 身份去重遍历（互斥可加，先到者归属）。
 * 2. **逐窗分配表**：与真机 `LoadTrace` 完全同一组分段 mark（`program.语句偏移侧车` …），
 *    每个窗口两个口径 —— 主线程分配（`getThreadAllocatedBytes(main)`，逐字节精确）与
 *    **全进程分配**（所有线程分配计数之和，含已退线程的最后读数 —— 段 3 / 预取 / 去重
 *    三个装载工兵线程的分配因此不会丢，误差只在「工兵线程末次采样到死亡」的间隙里，
 *    窗口 ≤ 相邻 mark 间隔）。真机 `dalloc` 就是进程口径，对照看这一列。
 * 3. **边际物化成本**：等距抽样物化 N 条语句，测「每千条物化语句付多少 MiB」——
 *    标题页 182 MiB 里「脚本跑出来的那部分」的唯一外推依据。
 * 4. **vars 面拆分**（报告 §5）：§1 的「VariableStore + 运行期图」是单根先到先归的粗桶，
 *    本表用**另一只独立的 ResidentWalker** 把 `session.store` 按根再拆一遍
 *    （系统列 / 角色表 / 注册表 / 用户变量壳 / ERD 关键字表 / 驻留池 / 运行期图…）。
 *    主表每一行的归属与读数逐字不变（两只遍历不共享访问集），mem6 路次据此定靶。
 *
 * ## 误差条（先说清楚，不装精确）
 *
 * - 精确行（数组条数 × 布局公式）：与 HotSpot 实际占用差 = 对齐噪声（≤ 8 B/数组）。
 *   **ART 侧同一公式成立**（压缩引用 4 B），但对象头 12 B vs ART 的类似量级 —— 跨机对账按 ±5% 看。
 * - [ResidentWalker] 建模行：`String` 每条少算约 8 B、HashMap 表容量按 0.75 负载反推、
 *   `ArrayList` 背衬按 `size` 下界估 —— 整体 ±10～20%（`PoolDecompAuditTest` §5 同口径）。
 * - HeapMeter 常驻：多轮 `System.gc()` 取最小值，复跑展布 ±10 MiB（既有标定）。
 * - 本探针**不**执行 `begin(TITLE)` 脚本（输入泵 + 不确定步数，破 60 s 预算），
 *   标题页脚本侧的增量用「边际物化成本 × 真机 materializedStatementCount」外推，
 *   渲染/位图缓存（`ROW_CACHE_MAX_BYTES` 24 MiB、`SpriteBitmapSource` 32+16 MiB 等）
 *   只引用常量不落数（它们在 `:platform`/`:render-canvas`，且 Android 8+ 的 Bitmap 像素
 *   在 native 堆，根本不进 `used`）。
 *
 * ## 复现
 *
 * ```
 * ERATW_ERBC_DIR=<含 program.erbc 的 erbc.v1 目录>   # 或 ERATW_ERBC 指到 program.erbc
 * ERATW_GAMEDIR=<游戏根>                              # 缺省时跳过 session 段（程序段照出）
 * ERATW_PERF_ROUNDS=3                                 # 常驻读数的 GC 轮数（默认 5，3 就够稳）
 * ./gradlew --configure-on-demand :core-vm:test --tests "*HeapCompositionProbe*" -i
 * ```
 *
 * 报告落 `core-vm/build/mem3-heap-composition.md`（build 目录，不入库）。
 * 确定性：无随机源（抽样全用等距下标）；session 段固定 `randSeed = 20260226`。
 */
class HeapCompositionProbeTest {

    private val sizer = SizeModel(SizeModel.detectRefWidth())

    // ------------------------------------------------------------------
    // 读数容器
    // ------------------------------------------------------------------

    /** 稳态构成 / 引用常量行。bytes<0 = 纯常量行（不落堆读数，只列账）。 */
    private class Row(
        val group: String,
        val item: String,
        val count: String,
        val bytes: Long,
        val method: String,
        val note: String = "",
    )

    /** 一个分段窗口：进程口径与主线程口径的分配增量 + 毫秒。 */
    private class WinRow(
        val stage: String,
        val procBytes: Long,
        val mainBytes: Long,
        val ms: Long,
    )

    private class Meter(
        val bean: com.sun.management.ThreadMXBean,
        val mainId: Long,
    ) {
        /** 已见过的线程 → 最后一次读到的分配数（线程死后冻结，不再增长）。 */
        private val lastByThread = HashMap<Long, Long>()

        fun mainAlloc(): Long = runCatching { bean.getThreadAllocatedBytes(mainId) }.getOrDefault(0L)

        /**
         * 进程口径：当前活线程读实际值并记账；已消失的线程用其最后读数计入总和。
         * 误差：活线程采样点之间刚死亡的线程，会少算「末次采样→死亡」一段（上界 = 一个窗口）。
         */
        fun procAlloc(): Long {
            val ids = bean.allThreadIds
            val live = HashSet<Long>(ids.size * 2)
            var sum = 0L
            for (id in ids) {
                live.add(id)
                val v = runCatching { bean.getThreadAllocatedBytes(id) }.getOrDefault(-1L)
                if (v >= 0) {
                    lastByThread[id] = v
                    sum += v
                }
            }
            for ((id, v) in lastByThread) {
                if (id !in live) sum += v
            }
            return sum
        }

        companion object {
            fun open(): Meter? {
                val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
                    ?: return null
                if (!bean.isThreadAllocatedMemorySupported) return null
                bean.isThreadAllocatedMemoryEnabled = true
                @Suppress("DEPRECATION")
                return Meter(bean, Thread.currentThread().id)
            }
        }
    }

    // ------------------------------------------------------------------
    // 反射与格式化小工具
    // ------------------------------------------------------------------

    /** 读私有字段（含父类链）；取不到返回 null（装置对结构变化宁可少一行也不炸）。 */
    private fun field(target: Any?, name: String): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
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

    /** 按类型找字段（ProcessRuntime 的函数表字段名不写死，防邻路改名将装置拖红）。 */
    private fun fieldByType(target: Any?, type: Class<*>): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type == type) {
                    return runCatching {
                        f.isAccessible = true
                        f.get(target)
                    }.getOrNull()
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun arr(v: Any?): Array<Any?>? = v as? Array<Any?>

    private fun nonNullCount(v: Any?): Int = arr(v)?.count { it != null } ?: 0

    /** 数组精确占用（SizeModel 的 16 B 头 + 元素宽 × 长度，8 对齐）。 */
    private fun arrBytes(v: Any?): Long = if (v == null) 0L else sizer.arraySize(v)

    private fun arrLen(v: Any?): Int = if (v == null) 0 else JavaArray.getLength(v)

    // ---- MEM10 刀 G：节点偏移表（块基 u32 + 块内 u16 相对偏移）----
    private fun nodeOffsetCount(v: Any?): Int = (field(v, "size") as Int?) ?: 0

    private fun nodeOffsetChunks(v: Any?): Int = (field(v, "bases") as IntArray?)?.size ?: 0

    private fun nodeOffsetsBytes(v: Any?): Long {
        if (v == null) return 0L
        val bases = field(v, "bases") as IntArray
        val deltas = field(v, "deltas") as ShortArray
        val overflow = field(v, "overflow") as Map<*, *>?
        val of = overflow?.values?.sumOf { (it as IntArray).size * 4L } ?: 0L
        return arrBytes(bases) + arrBytes(deltas) + of
    }

    // ---- MEM7：分块引用表 / 映射页偏移表的读数口径 --------------------------------
    // 「槽数」与「常驻字节」两条面都可能出现两种形态：旧的一整张数组、
    // 新的一级块表（未分配的块不占堆）。装置按真身算，不按形态猜。

    /** 引用表的槽数（分块表 = 逻辑槽数，普通数组 = 数组长度）。 */
    private fun slotLen(v: Any?): Int = when (v) {
        null -> 0
        is BlockRefs<*> -> v.size
        else -> arrLen(v)
    }

    /** 引用表的已物化条数。 */
    private fun slotNonNull(v: Any?): Int = when (v) {
        null -> 0
        is BlockRefs<*> -> v.nonNullEntries()
        else -> nonNullCount(v)
    }

    /**
     * 分块引用表（loader 的 BlockRefs）的精确常驻：表壳 + 块位表 + **已分配**的块
     * （每块一只 `AtomicReferenceArray` 壳 + 8 对齐的 4 B/槽背衬）。
     *
     * 块是按需分配的，位置任意 ⇒ 「已分配 k 块」按整块宽计；只有全部分配时才扣末块缺口
     * （装载后 0 块、全池物化后满块，这两端都是精确值）。
     */
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

    /** 串池的堆上偏移表；null = 已改从整段 pin 的映射页直取（MEM7 刀 C）。 */
    private fun strOffsets(t: Any?): IntArray? = field(t, "offsets") as? IntArray

    /** 串池偏移表的项数（映射形态下堆上没有数组，读构造期留下的坐标）。 */
    private fun strIndexEntries(t: Any?): Int = (field(t, "indexEntries") as Int?) ?: 0

    /** 串池数据块字节数（原形态由偏移表末项推出，映射形态读显式字段）。 */
    private fun strDataBytes(t: Any?): Long = (field(t, "dataBytes") as Int?)?.toLong() ?: 0L

    /**
     * 串池偏移表的**堆上常驻**：整张数组形态按数组算；稀疏分块形态（MEM7 刀 C）按
     * 「块位表 + 已灌块」算 —— 装载后 0 块 ⇒ 只剩块位表那几 KiB。
     */
    private fun strOffsetResidentBytes(t: Any?): Long {
        val arr = strOffsets(t)
        if (arr != null) return arrBytes(arr)
        return blockU32Bytes(field(t, "offsetBlocks"))
    }

    /** 稀疏分块 u32 表（loader 的 BlockU32s；刀 C 的偏移表、刀 D 的池根表）的精确常驻。 */
    private fun blockU32Bytes(v: Any?): Long {
        val t = v as? BlockU32s ?: return 0L
        val full = SizeModel.align8(16L + 4L * t.blockLength(0))
        val last = SizeModel.align8(16L + 4L * t.blockLength((t.blockCount - 1).coerceAtLeast(0)))
        val filled = t.filledBlocks()
        val tail = if (filled == t.blockCount && t.blockCount > 0) full - last else 0L
        return sizer.objectSize(t) + SizeModel.align8(16L + 4L * t.blockCount) +
            filled.toLong() * full - tail
    }

    /** 分块表的读数文本「已灌 x/y 块」；不是分块表返回空串。 */
    private fun blockU32Note(v: Any?): String {
        val t = v as? BlockU32s ?: return ""
        return "已灌 ${group(t.filledBlocks())}/${group(t.blockCount)} 块"
    }

    /** `ByteBuffer` 的 limit 走父类签名（ART 编译面口径同 SectionWindow.readInto）。 */
    private fun bufLimit(bb: Any?): Long =
        ((bb as? ByteBuffer)?.let { (it as java.nio.Buffer).limit().toLong() }) ?: 0L

    /** SectionWindow.pinned 的映射字节数（null = 换块窗口，堆内 0、地址空间按窗口计）。 */
    private fun pinnedBytes(sectionWindow: Any?): Long =
        bufLimit(field(sectionWindow, "pinned"))

    private fun mib(bytes: Long): String {
        val neg = bytes < 0
        val abs = if (neg) -bytes else bytes
        val tenths = (abs * 10 + 524_288) / 1_048_576
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
        if (whole <= 0) "-" else "${(part * 1000 + whole / 2) / whole / 10}.${(part * 1000 + whole / 2) / whole % 10}%"

    // ------------------------------------------------------------------
    // 装置侧输入
    // ------------------------------------------------------------------

    private fun findErbcDir(): Path? {
        System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return if (Files.isRegularFile(Path.of(it).resolve("program.erbc"))) Path.of(it) else null }
        System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return Path.of(it).parent }
        val candidates = listOf(
            Path.of("build", "erbc-out", "erbc.v1"),
            Path.of("build", "erbc.v1"),
            Path.of("..", "tools", "erbcc", "build", "erbc-out", "erbc.v1"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it.resolve("program.erbc")) }
    }

    private fun gameRoot(): Path? {
        val env = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Path.of(env).toAbsolutePath().normalize().takeIf { Files.isDirectory(it) }
    }

    // ------------------------------------------------------------------
    // 主探针
    // ------------------------------------------------------------------

    @Test
    fun `真产物：标题页常驻构成与逐窗分配账单`() {
        val tProbe = System.nanoTime()
        val dir = findErbcDir()
        assumeTrue(dir != null && Files.isRegularFile(dir.resolve("program.erbc")),
            "未找到 erbc.v1 产物（设 ERATW_ERBC_DIR 或先跑 :tools:erbcc:compileCorpus）：跳过")
        val programFile = dir!!.resolve("program.erbc")
        val metaFile = dir.resolve("program.meta")
        val sidecarFile = dir.resolve("program.stmt.idx")
        val meter = Meter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数：跳过")
        val m = meter!!

        val rows = ArrayList<Row>()
        val wins = ArrayList<WinRow>()
        // T2-64 波次 C：扁平语句池的图遍历字节（runProgramBuckets 里填；残差要扣掉它）
        var flatPoolBytes = 0L

        println("[mem3] 产物=${programFile.parent} erbc=${Files.size(programFile)} B " +
            "meta=${if (Files.isRegularFile(metaFile)) Files.size(metaFile) else "缺失"} B " +
            "侧车=${if (Files.isRegularFile(sidecarFile)) Files.size(sidecarFile) else "缺失"} B")
        println("[mem3] jvm=${HeapMeter.jvmName()} maxHeap=${mib(HeapMeter.max())} MiB GC=${HeapMeter.gcNames()} rounds=${HeapMeter.rounds}")

        // ---- S0：读数前基线 ----
        val s0 = HeapMeter.settled()
        println("[mem3] S0 test-worker 基线 ${mib(s0)} MiB")

        // ---- P1：生产装载链（loadMapped + 侧车），逐 mark 双口径采样 ----
        var lastProc = m.procAlloc()
        var lastMain = m.mainAlloc()
        var lastT = System.nanoTime()
        val mark = LoadStageMark { stage ->
            val now = System.nanoTime()
            val p = m.procAlloc()
            val mm = m.mainAlloc()
            wins.add(WinRow(stage, p - lastProc, mm - lastMain, (now - lastT) / 1_000_000))
            lastProc = p; lastMain = mm; lastT = now
        }
        val program: Program = ProgramLoader.loadMapped(programFile, mark)
        // T2-64 A/B：`ERATW_FLAT_OFF=1` 关掉扁平语句池（Android 生产口径），用来量「开/关」的常驻差。
        val flatOff = System.getenv("ERATW_FLAT_OFF") == "1"
        if (flatOff) program.flatStatementsEnabled = false
        wins.add(WinRow("program.(装载返回)", m.procAlloc() - lastProc, m.mainAlloc() - lastMain,
            (System.nanoTime() - lastT) / 1_000_000))
        val rProg = HeapMeter.settled()
        val progSteady = rProg - s0
        println("[mem3] P1 装载后常驻 ${mib(rProg)}（+${mib(progSteady)} MiB）/ 惰性池=${program.holdsLazyAnyPool}")

        // ---- P2：结构句柄（全反射，不 import 私有类）----
        val pool = field(program, "pool")!!
        val lazyStmt = field(pool, "lazyStatements")!!
        val lazyExpr = field(pool, "lazyExprs")
        val recordOffsets = field(lazyStmt, "recordOffsets")!!
        // MEM7 刀 2：条目槽与两张节点表都换成了分块引用表（loader 的 BlockRefs）——
        // 装置按「块位数 / 已分配块 / 已物化条数」读它的真身，常驻只算**已分配**的块。
        val cached = field(lazyStmt, "cached")
        val cachedLen = slotLen(cached)
        val cachedNonNull = slotNonNull(cached)
        val cachedBackBytes = refTableBytes(cached)
        val stmtArena = field(lazyStmt, "arena")!!
        val interner = field(lazyStmt, "interner")
        val stmtStrings = field(stmtArena, "strings")!!
        val stmtNodesArr = field(stmtArena, "nodes")
        val stmtNodeOffsets = field(stmtArena, "nodeOffsets")
        val stmtWin = field(lazyStmt, "window")

        val exprArena = lazyExpr?.let { field(it, "arena") }
        // MEM7 刀 D：池根表改成稀疏分块（`MappedExprSource.roots` = BlockU32s，装载后 0 块）
        val exprRootCount = lazyExpr?.let { field(it, "poolCount") } as Int?
        val exprRootBlocks = lazyExpr?.let { field(it, "roots") }
        val exprRootsBytes = blockU32Bytes(exprRootBlocks)
        val exprStrings = exprArena?.let { field(it, "strings") }
        val exprNodesArr = exprArena?.let { field(it, "nodes") }
        val exprNodeOffsets = exprArena?.let { field(it, "nodeOffsets") }
        val exprWin = exprArena?.let { field(it, "lazyWin") }

        val fnTable = program.functions
        val fnRecordOffsets = field(fnTable, "recordOffsets")
        val fnCache = field(fnTable, "cache")
        val fnStrings = field(fnTable, "strings")
        val fnParam = field(fnTable, "paramNames")
        val paramCounts = fnParam?.let { field(it, "counts") }
        val paramFrom = fnParam?.let { field(it, "dataFrom") }
        val paramStrings = fnParam?.let { field(it, "strings") }

        val materializedStmts = cachedNonNull
        val exprCacheFilled = nonNullCount(field(exprStrings, "cache"))
        val exprCacheArr = field(exprStrings, "cache")
        val exprMemoize = field(exprStrings, "memoize")
        val stmtMemoize = field(stmtStrings, "memoize")

        // ---- P3：稳态构成 —— 精确行（惰性骨架）----
        // MEM10 刀 E：偏移表改侧车只读映射（heap 字段为 null）⇒ 堆内 0；
        // 若走回落扫描档（heap 非空）仍按整张 IntArray 记账。
        val roHeap = field(recordOffsets, "heap") as IntArray?
        if (roHeap != null) {
            rows.add(Row("段3 惰性语句池", "语句记录偏移表 IntArray（回落扫描档）", "${group(roHeap.size)} × 4 B",
                arrBytes(roHeap), "精确"))
        } else {
            rows.add(Row("段3 惰性语句池", "语句记录偏移表（MEM10 刀 E：侧车只读映射）",
                "${group(field(recordOffsets, "size") as Int)} 条 u32", 0L, "堆内 0（文件页缓存另计）",
                "取用 = 一次 4 B 映射读；旧形态是 171 万条 IntArray 6.5 MiB"))
        }
        rows.add(Row("段3 惰性语句池", "条目缓存（分块引用表；MEM7 刀 2）",
            "${group(cachedLen)} 槽 / 已分配 ${cached.let { if (it is BlockRefs<*>) it.allocatedBlocks() else 0 }} 块" +
                "（已物化 ${group(materializedStmts)}）",
            cachedBackBytes, "精确", "块宽 4,096 槽：没物化到的块一个字节不占；旧形态是整张 6.5 MiB"))
        rows.add(Row("段3 惰性语句池", "节点表 Expr[]（enableLazyNodes 置空后）",
            "${group(slotLen(stmtNodesArr))} 槽（非空 ${slotNonNull(stmtNodesArr)}）",
            refTableBytes(stmtNodesArr), "精确：分块表，只算已分配块"))
        rows.add(Row("段3 惰性语句池", "节点偏移表（MEM10 刀 G：块基 u32 + 块内 u16）",
            "${group(nodeOffsetCount(stmtNodeOffsets))} 条 / ${group(nodeOffsetChunks(stmtNodeOffsets))} 块基",
            nodeOffsetsBytes(stmtNodeOffsets), "精确",
            "旧形态是整张 IntArray（同条数 × 4 B）；块跨度越 65535 的块退化成绝对偏移表（语料 0 块）"))
        val stmtOffsets = strOffsets(stmtStrings)
        val stmtOffsetItems = stmtOffsets?.size ?: strIndexEntries(stmtStrings)
        rows.add(Row("段3 局部串池",
            if (stmtOffsets == null) "偏移表（稀疏分块直取；MEM7 刀 C）" else "偏移表 IntArray",
            "项数 ${group(stmtOffsetItems - 1)} × 4 B（${blockU32Note(field(stmtStrings, "offsetBlocks"))}）",
            strOffsetResidentBytes(stmtStrings), "精确",
            if (stmtOffsets == null) "块第一次被取到才从段 3 那张整段 pin 灌满：不新增映射/fd、" +
                "取用面回到数组直读（逐次读映射页实测把 functions[i].name 从 292.8 拖到 464.9 ns/op）" else ""))
        rows.add(Row("段3 局部串池", "数据块（pinWhole=mmap，非 Java 堆）",
            "${group(strDataBytes(stmtStrings))} B",
            -1L, "地址空间", "memoize=${stmtMemoize}（生产惰性档 = 关：钉住即白占整池原文）"))
        rows.add(Row("段2 惰性表达式池", "节点表 Expr[]（置空后）", "${group(slotLen(exprNodesArr))} 槽（非空 ${slotNonNull(exprNodesArr)}）",
            refTableBytes(exprNodesArr), "精确：分块表，只算已分配块"))
        rows.add(Row("段2 惰性表达式池", "节点偏移表（MEM10 刀 G：块基 u32 + 块内 u16）",
            "${group(nodeOffsetCount(exprNodeOffsets))} 条 / ${group(nodeOffsetChunks(exprNodeOffsets))} 块基",
            nodeOffsetsBytes(exprNodeOffsets), "精确",
            "旧形态是整张 IntArray（同条数 × 4 B）；块跨度越 65535 的块退化成绝对偏移表（语料 0 块）"))
        rows.add(Row("段2 惰性表达式池", "池根表（稀疏分块直取；MEM7 刀 D）",
            "${group(exprRootCount ?: 0)} 根 × 4 B（${blockU32Note(exprRootBlocks)}）",
            exprRootsBytes, "精确", "roots 只留在产物偏移上（段 2 整段 pin 覆盖得到），装载后堆上只剩块位表"))
        val exprOffsets = strOffsets(exprStrings)
        val exprOffsetItems = exprOffsets?.size ?: strIndexEntries(exprStrings)
        rows.add(Row("段2 局部串池",
            if (exprOffsets == null) "偏移表（稀疏分块直取；MEM7 刀 C）" else "偏移表 IntArray",
            "项数 ${group(exprOffsetItems - 1)} × 4 B（${blockU32Note(field(exprStrings, "offsetBlocks"))}）",
            strOffsetResidentBytes(exprStrings), "精确"))
        val exprCacheBytes = if (exprCacheArr == null) 0L else arrBytes(exprCacheArr) +
            (exprCacheArr as Array<*>).filterNotNull().sumOf { SizeModel.stringBytes(it as String) }
        rows.add(Row("段2 局部串池", "备忘表 Array<String?>（首取才分配）",
            "已分配=${exprCacheArr != null} 已解码 ${group(exprCacheFilled)} 条",
            exprCacheBytes, "精确+模型", "memoize=${exprMemoize}（惰性表达式档保持开：真机运行期会把解过的原文钉在这张表上）"))
        val hashes = field(interner, "hashes")
        val reps = field(interner, "reps")
        val strPool = field(interner, "strings")
        rows.add(Row("段3 惰性语句池", "ArgInterner 规范表（装载后为空表）",
            "cap=${group(arrLen(hashes))}", arrBytes(hashes) + arrBytes(reps) +
                (if (strPool == null) 0L else sizer.objectSize(strPool) + arrBytes(field(strPool, "table"))),
            "精确"))
        rows.add(Row("段4 函数表", "记录偏移 + 段8 形参索引（条目缓存另列）", "${group(arrLen(fnRecordOffsets))} 函数",
            arrBytes(fnRecordOffsets) + arrBytes(paramCounts) + arrBytes(paramFrom),
            "精确（装载后缓存非空 ${nonNullCount(fnCache)}）"))
        val fnOffsets = strOffsets(fnStrings)
        val paramOffsets = strOffsets(paramStrings)
        rows.add(Row("段4/8 局部串池", "函数名 / 形参名偏移表",
            "${group(fnOffsets?.size ?: strIndexEntries(fnStrings))} + " +
                "${group(paramOffsets?.size ?: strIndexEntries(paramStrings))} 槽" +
                "（${blockU32Note(field(fnStrings, "offsetBlocks"))} + ${blockU32Note(field(paramStrings, "offsetBlocks"))}）",
            strOffsetResidentBytes(fnStrings) + strOffsetResidentBytes(paramStrings),
            "精确",
            "两侧 memoize 全关，装完 cache=null；" +
                if (fnOffsets == null && paramOffsets == null) "偏移表已改稀疏分块直取（MEM7 刀 C），装载后堆上只剩两张块位表" else ""))
        // MEM9 刀 2：段 6 有两种形态 —— 整表（`count × 2 B` 一张 ShortArray）与稀疏分块
        // （装载后只剩块位表，取用面每碰一块付 8 KiB）。`lineTableResidentBytes` 自己按形态算，
        // 装置只把「是什么形态、灌了几块」写进计数列，不再假设它恒等于 count×2。
        val lineStats = program.lineTableBlockStats()
        rows.add(Row(
            "段6 行号表",
            if (program.lineTableIsPaged) "LineTable（稀疏分块：块位表 + 已灌块）" else "LineTable（u16 主表 + 溢出）",
            "${group(program.instructionCount)} 指令" +
                if (program.lineTableIsPaged) "（${group(lineStats.first)} 块位 / 已灌 ${group(lineStats.second)} 块）" else "",
            program.lineTableResidentBytes, "精确",
            if (program.lineTableIsPaged) "MEM9 刀 2：装载趟仍逐条读一遍（报错位置与文案不变），" +
                "但 u16 整表不留在堆上；第 i 块首次取用时从段 6 整段 pin 回读原始 i32 压实。" +
                "全灌满 = count×2 B，最坏与旧整表形态同量级" else "",
        ))
        // MEM9 刀 1：段 7 的紧凑本体（两张平行数组）。实参表/[CallArg] 实例在 P4 的桶里按对象图算。
        val callArgIndex = field(program, "callArgIndex") as CallArgIndex?
        rows.add(Row(
            "段7 调用点实参表", "CallArgIndex 本体（pcs + vals 两张平行数组）",
            "${group(callArgIndex?.size ?: 0)} 点（独立实参表 ${group(callArgIndex?.distinctListCount() ?: 0)} 张）",
            if (callArgIndex == null) 0L else sizer.objectSize(callArgIndex) +
                arrBytes(field(callArgIndex, "pcs")) + arrBytes(field(callArgIndex, "vals")),
            "精确",
            "MEM9 刀 1：旧形态在这里是一张 LinkedHashMap<Int, List<CallArg>>（节点 40 B + 装箱键 16 B" +
                " + 桶数组）外加 pcs/vals 两份侧车副本 = 10.7 MiB；Map 语义由 CallArgIndex 自己承担",
        ))

        rows.add(Row("段5 指令流", "MappedCodeSource（换页缓冲 16 KiB + 64 槽页缓存表）",
            "常驻页 ${nonNullCount(field(program.codeSource, "cache"))} × ${arrLen(field(program.codeSource, "pageScratch")) / 4} 字",
            arrBytes(field(program.codeSource, "pageScratch")) +
                arrBytes(field(program.codeSource, "cache")) + sizer.objectSize(program.codeSource),
            "精确", "运行期最坏 +${mib(64L * (16 + 4096 * 4))} MiB（CodeSource.kt:304 PAGE_CACHE_SLOTS=64）"))
        // 段0/1：全局串池 + 常量池（精确逐条）
        val gStrings = field(pool, "strings") as List<*>
        var gStrBytes = 0L
        for (s in gStrings) gStrBytes += SizeModel.stringBytes(s as String)
        val gLongs = field(pool, "longs") as List<*>
        rows.add(Row("段0 全局串池", "strings ArrayList<String>", "${group(gStrings.size)} 条",
            gStrBytes + SizeModel.align8(16L + 4L * gStrings.size) + 24L, "模型（串逐条精确）"))
        rows.add(Row("段1 常量池", "longs ArrayList<Long>（装箱）", "${group(gLongs.size)} 条",
            gLongs.size * 24L + 16L + gLongs.size * 4L + 24L, "模型", "Long 壳 24 B/条 —— 条数小，装箱无痛"))

        // ---- P4：对象图分解（ResidentWalker，互斥可加、先到者归属）----
        val walker = ResidentWalker(sizer)
        // 已单列精确行的骨架先 markVisited：图遍历只计「壳 + 真实对象」，两账不重叠。
        walker.markVisited(recordOffsets)
        walker.markVisited(cached)
        walker.markVisited(stmtNodesArr)
        walker.markVisited(stmtNodeOffsets)
        walker.markVisited(stmtOffsets)
        walker.markVisited(field(stmtStrings, "offsetBlocks"))
        walker.markVisited(exprNodesArr)
        walker.markVisited(exprNodeOffsets)
        walker.markVisited(exprRootBlocks)
        walker.markVisited(exprOffsets)
        walker.markVisited(field(exprStrings, "offsetBlocks"))
        walker.markVisited(fnRecordOffsets)
        walker.markVisited(fnOffsets)
        walker.markVisited(field(fnStrings, "offsetBlocks"))
        walker.markVisited(paramOffsets)
        walker.markVisited(field(paramStrings, "offsetBlocks"))
        walker.markVisited(paramCounts)
        walker.markVisited(paramFrom)
        walker.markVisited(hashes)
        walker.markVisited(reps)
        walker.markVisited(field(strPool, "table"))
        walker.markVisited(field(program, "lineTable"))
        // MEM9 刀 1：紧凑本体的两张数组已在 §1 精确行单列，壳本身 markVisited ——
        // 否则 `consume(program)` 走到 `callArgs` 字段时，ResidentWalker 会把这张自定义 Map
        // 按 HashMap 布局建模（40 B/节点 + 桶数组），**虚报 ≈9 MiB** 的已删壳。
        walker.markVisited(field(program, "callArgIndex"))
        walker.markVisited(field(callArgIndex, "pcs"))

        walker.markVisited(gStrings)
        walker.markVisited(gLongs)
        // MappedFunctionTable 是 List：直接 consume 会迭代 get(i) 把 110,072 条 FunctionInfo
        // 当场物化（那是 EngineFunctionPolicy 装配期的事，不该记在装载后头上）。
        walker.markVisited(fnTable)

        fun bucket(name: String, vararg roots: Any?): Long {
            val t = System.nanoTime()
            var b = 0L
            for (r in roots) b += walker.consume(r)
            rows.add(Row("图遍历桶", name, "", b, "遍历（身份去重）",
                "耗时 ${(System.nanoTime() - t) / 1_000_000} ms"))
            return b
        }
        // 图遍历推迟到所有 HeapMeter 读数之后再做：walker 的 visited 表本身是几十 MiB 的活物，
        // 先遍历会污染 meta/装配/释放三处稳态读数（bucket 内容不受影响，桶归属顺序不变）。
        fun runProgramBuckets() {
            // T2-64 波次 C：装载期多出的「扁平语句池」是一等公民（stream ints 67 MB + 字符串旁表 +
            // 表达式旁挂），过去不在本表的任何桶里 ⇒ 单列一桶，并在残差里扣掉它（见下方 residual）。
            flatPoolBytes = bucket("T2-64 扁平语句池（stream ints + 字符串旁表 + 表达式旁挂）", program.flatStmtSource)
            bucket("段3 语句池壳与串表/窗口对象（骨架已单列）", lazyStmt)
            bucket("段2 表达式池壳（骨架已单列）", lazyExpr)
            bucket("段4/8 串表壳与映射窗口", field(fnTable, "strings"), field(fnTable, "window"), fnParam)
            // MEM9 刀 1 之后这段只剩「独立实参表 + CallArg + 名字串」三样真数据：
            // 根 = vals 数组（`Array<List<CallArg>?>`，walker 逐槽下降、身份去重 ⇒ 共享实例只算一次）。
            // 本体两张数组与壳在 §1 精确行单列，这里不再 consume（pcs 已 markVisited）。
            bucket("段7 调用点实参表 独立实参表+CallArg+名字串（本体两张数组已单列）",
                field(program, "callArgIndex")?.let { field(it, "vals") })
            val cai = field(program, "callArgIndex")
            val siteCount = arrLen(field(cai, "pcs"))
            val listCount = nonNullCount(field(cai, "vals"))
            rows.add(Row("段7 调用点实参表", "pcs 键表 / vals 引用表",
                "${group(siteCount)} 点 × 4 B + ${group(siteCount)} 槽 × 4 B",
                arrBytes(field(cai, "pcs")) + arrBytes(field(cai, "vals")), "精确",
                "紧凑本体 = 段 7 唯一常驻的索引；vals 里 ${group(listCount)} 个槽指向的表去重后独立形态见上一行"))

            bucket("段5 CodeSource 对象图", program.codeSource)
            bucket("ConstantPool + Program 外壳残余", pool, program)
            val callArgCount = program.callArgs.values.sumOf { it.size }
            rows.add(Row("段7 调用点实参表", "调用点 / CallArg 条数", "${group(program.callArgs.size)} 点 / ${group(callArgCount)} 个",
                0L, "精确计数"))
        }

        // ---- P5：映射地址空间（非 Java 堆；真机体现在 RSS/native 列）----
        val seenBufs = java.util.IdentityHashMap<Any, Boolean>()
        fun regionRow(name: String, sw: Any?) {
            val pinned = field(sw, "pinned")
            if (pinned != null && seenBufs.put(pinned, true) == null) {
                rows.add(Row("映射窗口（堆外）", name, "${group(bufLimit(pinned))} B", -1L, "地址空间"))
            }
        }
        regionRow("段3 记录区整段 pin", stmtWin)
        regionRow("段3 局部串池数据块", field(stmtStrings, "win"))
        regionRow("段2 记录区整段 pin", exprWin)
        regionRow("段2 局部串池数据块", field(exprStrings, "win"))
        regionRow("段4 函数区 pin", field(fnTable, "window"))
        regionRow("段8 形参名 pin", field(fnParam, "window"))
        // MEM9 刀 2：段 6 整段 pin（分块形态的取用面靠它回读原始 i32；堆内 0，地址空间 19.5 MiB）
        regionRow(
            "段6 行号区整段 pin（MEM9 刀 2）",
            field(program, "lineTable")?.let { lt -> field(lt, "source")?.let { field(it, "win") } },
        )

        rows.add(Row("映射窗口（堆外）", "CODE 段 pin（MappedCodeSource.read）",
            "${group(bufLimit(field(program.codeSource, "read")))} B", -1L, "地址空间"))

        // ---- P6：meta + session（标题页稳态的「会话半边」；缺游戏根则跳过）----
        var rMeta = -1L
        var rSession = -1L
        var metaReleased = -1L
        var metadataRef: BootMetadata? = null
        var session: GameSession? = null
        val winsS = ArrayList<WinRow>()
        val gameRoot = gameRoot()
        val wantSession = System.getenv("ERATW_MEM3_SESSION") != "0" && Files.isRegularFile(metaFile)
        if (gameRoot != null && wantSession) {
            val tMeta = System.nanoTime()
            val p0 = m.procAlloc()
            val mm0 = m.mainAlloc()
            metadataRef = BootMetadata.read(metaFile)
            winsS.add(WinRow("meta.侧车解析", m.procAlloc() - p0, m.mainAlloc() - mm0,
                (System.nanoTime() - tMeta) / 1_000_000))
            rMeta = HeapMeter.settled()
            println("[mem3] P6 meta ${winsS.last().ms} ms：常驻 ${mib(rMeta)}（+${mib(rMeta - rProg)} MiB，瞬态）")
            rows.add(Row("会话相", "program.meta 解析驻留（装配期消费后释放）",
                "${metadataRef!!.functions.size} 函数 / ${metadataRef!!.labels.size} 标签键 / " +
                    "${metadataRef!!.dataSections.size} 数据节 / ${metadataRef!!.globalDims.size} 全局 #DIM + ${metadataRef!!.privateDims.size} 私有",
                rMeta - rProg, "GC 差"))
            val tSess = System.nanoTime()
            var sp = m.procAlloc()
            var sm = m.mainAlloc()
            var sT = tSess
            session = GameSession(
                gameRoot = gameRoot,
                program = program,
                metadata = metadataRef!!,
                consoleWidth = 199,
                randSeed = 20260226L,
                systemOps = NoSaveSystemOps,
                stageMark = { s ->
                    val now = System.nanoTime()
                    val p = m.procAlloc()
                    val mm = m.mainAlloc()
                    winsS.add(WinRow(s, p - sp, mm - sm, (now - sT) / 1_000_000))
                    sp = p; sm = mm; sT = now
                },
            )
            rSession = HeapMeter.settled()
            println("[mem3] P6 session 装配 ${mib(rSession)}（装配时 metadata 仍持有）")
            // 侧车生命周期：GameSession 不持 metadata —— 放掉本地引用再收一次，
            // 差值 = 侧车里「装配完即无读者」那部分（fileByKey / 外层标签表 / #DIM 原文…）。
            metadataRef = null
            metaReleased = HeapMeter.settled()
            println("[mem3] P6 metadata 释放后 ${mib(metaReleased)}（回收 ${mib(rSession - metaReleased)} MiB）")
            rows.add(Row("会话相", "metadata 装配期释放（fileByKey/外层标签/DimParse 原文）", "",
                rSession - metaReleased, "GC 差"))
        }
        // MEM9：两坨靶的**第二个时间点** —— 装载后（§1 那两行）与标题页装配后各读一次。
        // 分块形态的收益只看装载后不够：装配期若真读了行号 / 取了实参表，涨回来的那部分是
        // 运行期应付的账（`method` 用「装配后(不入总账)」标开，不进 `indexed` 求和，
        // 免得与 §1 那两行重复计账把残差判据打歪）。
        run {
            val s2 = program.lineTableBlockStats()
            rows.add(Row("段6 行号表", "同上，**标题页装配后**再读一次",
                "${group(program.instructionCount)} 指令（${group(s2.first)} 块位 / 已灌 ${group(s2.second)} 块）",
                program.lineTableResidentBytes, "装配后(不入总账)",
                "与上一行的差 = 装配期真读到的行号所付的块"))
            val c2 = field(program, "callArgIndex")
            rows.add(Row("段7 调用点实参表", "同上，**标题页装配后**再读一次",
                "${group(nonNullCount(field(c2, "vals")))} 张独立实参表",
                arrBytes(field(c2, "pcs")) + arrBytes(field(c2, "vals")), "装配后(不入总账)",
                "紧凑本体是两张定长数组 ⇒ 这一行装载前后恒等（涨落全在对象图那一桶里）"))
        }
        // 全部稳态读数到手，才开始图遍历。
        runProgramBuckets()

        val progWalked = walker.bytes
        if (session != null) {
            bucket("FunctionTable（侧车函数元数据投影）", fieldByType(session.runtime, FunctionTable::class.java))
            // 装配期 EngineFunctionPolicy/StubTable 遍历 program.functions 取名字，惰性函数表的
            // 缓存因此被**全量填满**（ProcessRuntime.kt:254 → EngineFunctionPolicy.kt:90 的
            // for (i in functions.indices)）—— 真机标题页同样付这笔，归会话相。
            bucket("FunctionInfo 物化（StubTable 装配所致；缓存表本体 + 函数名 + 形参名壳）", fnCache)
            bucket("SidecarTables（剪枝标签 + 数据节）", field(session, "sidecar"))
            bucket("VariableStore + 运行期图（store.aux 挂着 ProcessRuntime/ExecContext 造的对象，整张图先到先归）", session.store)
            println("[mem3] store 桶后 runtime-visited=${walker.isVisited(session.runtime)} objects=${walker.objects}")
            bucket("GameData（CSV 总装）", session.gameData)
            bucket("控制台 sink", session.console)
            bucket("配置/存档接线/其余", session.configStore)
            bucket("会话壳残余（宿主/BEGIN 状态机/其余）", session)
        }

        val steadyRef = if (metaReleased >= 0) metaReleased else rProg
        val steadyBase = steadyRef - s0
        // 惰性骨架小计：enableLazyNodes/侧车留下的「条数正比」数组（精确行求和）。
        val skeletonExact = rows.filter {
            it.method.startsWith("精确") && it.bytes > 0 &&
                (it.group.startsWith("段3") || it.group.startsWith("段2") ||
                    it.group == "段4 函数表" || it.group == "段4/8 局部串池")
        }.sumOf { it.bytes }
        rows.add(0, Row("惰性骨架小计", "条数正比的 IntArray/引用表（段2+段3+段4+段4/8 串表偏移）",
            "装载即付、与真跑到的语句数无关", skeletonExact, "精确合计(小计,不入总账)",
            "这就是惰性池「留在堆上的索引」全部家当"))
        rows.add(Row("稳态合计", if (metaReleased >= 0) "装载 + 会话装配（metadata 已释放；≈ 标题页减脚本）" else "装载后（仅 Program）",
            "", steadyBase, "HeapMeter"))

        // ---- P7：边际物化成本（等距抽样，无随机源）----
        val sampleN = 2000
        val before = HeapMeter.settled()
        val stride = maxOf(1, program.statementCount / sampleN)
        var sampled = 0
        var idx = 0
        while (idx < program.statementCount && sampled < sampleN) {
            program.statement(idx)
            sampled++
            idx += stride
        }
        val after = HeapMeter.settled()
        val perStmt = (after - before) / sampled.coerceAtLeast(1)
        rows.add(Row("边际", "物化 ${sampled} 条语句（等距抽样）", "每条 ${group(perStmt)} B ⇒ 每 10 万条 ${mib(perStmt * 100_000L)} MiB",
            after - before, "边际(GC 差)", "标题页脚本增量的外推尺：materializedStatementCount × 每条 B"))
        val cacheAfter = field(exprStrings, "cache")
        rows.add(Row("边际", "抽样后 段2 备忘表已解码条数", "${group(nonNullCount(cacheAfter))}",
            if (cacheAfter == null) 0L else (cacheAfter as Array<*>).filterNotNull().sumOf { SizeModel.stringBytes(it as String) },
            "边际模型", "备忘表把「解过的原文」钉住的增速（memoize=关的那张不涨）"))

        // ---- P8：vars 面拆分（只加表，不动上面任何桶的归属）----
        val varsSection = varsSplitSection(session, program)
        // ---- P8b：本路两坨的分根拆分（同样只加表）----
        val mem9Section = mem9SplitSection(program)

        runCatching { session?.close() }

        // ---- 断言（不变量，不是某个 MiB）----
        assertTrue(program.holdsLazyAnyPool, "生产档必须走惰性池：拿不到就构成表没有意义")
        assertTrue(field(recordOffsets, "size") == program.statementCount, "recordOffsets 必须 = 语句池条数")
        val flatBuilt = program.flatStmtSource != null
        assertTrue(
            program.materializedStatementCount <= 64 + sampleN || flatBuilt,
            "装载/装配期不应大批物化语句（读数：${program.materializedStatementCount}；" +
                "扁平池已建=$flatBuilt —— 桥构建期整池物化是其固有代价，见 CONTRACT-T2-64 §10.6/§10.7）",
        )
        val stmtRecWindows = wins.filter { it.stage.startsWith("program.段3 语句记录") }.sumOf { it.procBytes }
        assertTrue(stmtRecWindows < 100L * 1_048_576L,
            "段3 语句记录窗口分配 ${mib(stmtRecWindows)} MiB ≥ 100 MiB：侧车没生效（回落了扫描档），本表口径不成立")
        val indexed = rows.filter {
            (it.method.startsWith("精确") || it.method.startsWith("模型") || it.method.startsWith("遍历")) &&
                it.group != "惰性骨架小计"
        }.sumOf { it.bytes.coerceAtLeast(0L) }
        val residual = steadyBase - indexed
        // T2-64：残差里「扁平池那一份」与表达式面的桶按身份去重后**不可加**（同一批 Expr 两边都算）。
        // 实测两档：装载期压扁 残差 93.6 / 桶 258.0；桥构建期压扁 残差 120.1 / 桶 357.9。
        // 判据只保「不出现整量级漏项」：残差不得强负（建模多算），也不得超过已知的扁平池桶。
        assertTrue(residual > -80L * 1_048_576L && residual <= flatPoolBytes + 80L * 1_048_576L,
            "残差 ${mib(residual)} MiB（扁平池桶 ${mib(flatPoolBytes)} MiB）超界：建模漏项")

        // ---- 报告 ----
        val classTopText = walker.classTop(12).joinToString(", ") { e ->
            "${e.first} x${e.second} = ${mib(e.third)} MiB"
        }
        val totalMs = (System.nanoTime() - tProbe) / 1_000_000
        val sb = StringBuilder()
        sb.appendLine("# mem3 堆构成探针（真产物；只测量，产品零改动）")
        sb.appendLine()
        sb.appendLine("- 产物：`${programFile.parent}`（erbc ${Files.size(programFile)} B）")
        sb.appendLine("- JVM：${HeapMeter.jvmName()}，maxHeap ${mib(HeapMeter.max())} MiB，GC ${HeapMeter.gcNames()}（${HeapMeter.rounds} 轮取最小）")
        sb.appendLine("- 探针总耗时 ${totalMs / 1000}s；S0 ${mib(s0)}；装载后 ${mib(rProg)}" +
            if (rMeta >= 0) "；meta 后 ${mib(rMeta)}；装配后 ${mib(rSession)}；metadata 释放后 ${mib(metaReleased)}" else "")
        sb.appendLine()
        sb.appendLine("## 1. 稳态构成")
        sb.appendLine()
        sb.appendLine("| 组 | 项 | 计数 | MiB | 口径 | 备注 |")
        sb.appendLine("|---|---|---|---:|---|---|")
        for (r in rows) {
            sb.appendLine("| ${r.group} | ${r.item} | ${r.count} | ${if (r.bytes < 0) "(堆外)" else mib(r.bytes)} | ${r.method} | ${r.note} |")
        }
        sb.appendLine()
        sb.appendLine("- 已列账合计 ${mib(indexed)} MiB；对稳态 ${mib(steadyBase)} MiB 的残差 **${mib(residual)} MiB**" +
            "（建模误差 + JUnit/装置自身驻留 + GC 时机噪声；±10～20% 见类 KDoc）。")
        sb.appendLine("- 程序段图遍历小计（会话桶另列上表；身份去重）：${mib(progWalked)} MiB；全程去重对象 ${walker.objects} 个。")
        sb.appendLine("- 逐类 top：$classTopText")
        sb.appendLine()
        sb.appendLine("## 2. 逐窗分配（左列 = 与真机 LoadTrace 同名窗口）")
        sb.appendLine()
        sb.appendLine("| 窗口 | 进程分配 MiB | 主线程分配 MiB | ms |")
        sb.appendLine("|---|---:|---:|---:|")
        for (w in wins) sb.appendLine("| ${w.stage} | ${mib(w.procBytes)} | ${mib(w.mainBytes)} | ${w.ms} |")
        if (winsS.isNotEmpty()) {
            sb.appendLine("| ── session ── | | | |")
            for (w in winsS) sb.appendLine("| ${w.stage} | ${mib(w.procBytes)} | ${mib(w.mainBytes)} | ${w.ms} |")
        }
        sb.appendLine()
        sb.appendLine("### 2b. 与真机 dalloc 对照（device 读数 = vivo V2199A 装载轨迹样本，进程口径）")
        sb.appendLine()
        sb.appendLine("| 窗口 | device dalloc MiB | JVM 进程口径 MiB | 说明 |")
        sb.appendLine("|---|---:|---:|---|")
        appendDeviceCompare(sb, wins)
        sb.appendLine()
        sb.appendLine("## 3. 设备侧引用行（不在 Java 堆读数内）")
        sb.appendLine()
        sb.appendLine("| 缓存 | 上限 | 出处 |")
        sb.appendLine("|---|---:|---|")
        sb.appendLine("| 行栅格位图缓存 ROW_CACHE_MAX_BYTES | 24 MiB | render-canvas CanvasConsoleView.kt:2567 |")
        sb.appendLine("| 行点阵池 ROW_POOL_MAX_BYTES | 6 MiB | CanvasConsoleView.kt:2577 |")
        sb.appendLine("| SpriteBitmapSource bufferCache | 32 MiB（DEFAULT_CACHE_BYTES） | platform image/SpriteBitmapSource.kt:345 |")
        sb.appendLine("| SpriteBitmapSource fileCache | 16 MiB（maxBitmapBytes/2） | SpriteBitmapSource.kt:136 |")
        sb.appendLine("| VM 取指页缓存 PAGE_CACHE_SLOTS | 64 × 16 KiB = 1 MiB | core-vm code/CodeSource.kt:304 |")
        sb.appendLine("| RowRasterCache DEFAULT_MAX_BYTES | 24 MiB | render view/RowRasterCache.kt:146 |")
        sb.appendLine()
        sb.appendLine("> Android 8+ 的 Bitmap 像素在 native 堆：`used=`（Java 堆）看不到上面这几行；真机对账时看 `native=` 列。")
        sb.appendLine()
        sb.append(varsSection)
        sb.append(mem9Section)

        sb.appendLine("## 4. 复现")
        sb.appendLine()
        sb.appendLine("```")
        sb.appendLine("ERATW_ERBC_DIR=<erbc.v1 目录> ERATW_GAMEDIR=<游戏根> ERATW_PERF_ROUNDS=3 \\")
        sb.appendLine("./gradlew --configure-on-demand :core-vm:test --tests \"*HeapCompositionProbe*\" -i")
        sb.appendLine("ERATW_MEM3_SESSION=0 只出程序段（标题页的会话半边不测时）")
        sb.appendLine("```")

        val report = Path.of("build", "mem3-heap-composition.md").toAbsolutePath().normalize()
        Files.createDirectories(report.parent)
        Files.writeString(report, sb.toString())
        println(sb.toString())
        println("[mem3] 报告：$report")
        assertTrue(totalMs < 240_000, "探针超预算太多：${totalMs / 1000}s")
    }

    /**
     * **本路两坨的分根拆分**（§6；MEM9 出口判据 2）：段 7 实参表与段 6 行号表各按**根**拆一遍，
     * 每根带「逐类新增 top5」。
     *
     * 为什么必须单独一趟，而不是只看 §1 那两行：
     * - §1 的段 7 桶是**一个根**吃一整张对象图（pcs / vals / 独立实参表 / CallArg / 名字串），
     *   看不出「壳」与「数据」的比 —— 而本路的判断恰恰是「10.7 MiB 里 9.1 MiB 是壳」；
     * - §1 的段 6 行读的是 `lineTableResidentBytes`（本类自算的精确公式），看不到
     *   「已灌几块 / 块位表 / 溢出表」各自多少，也看不到 `source` 那条边挂了什么；
     * - 旧形态下 `ResidentWalker.mapOwnBytes` 对认不出的 Map 类按 `HashMap` 布局建模
     *   （节点 40 B/条 + 桶数组），紧凑本体 [com.eratw.port.core.code.CallArgIndex] 会被**虚报**
     *   —— 所以本表把它的两张数组按根点名，Map 壳直接 markVisited 挡在门外（只计对象本体）。
     *
     * 口径与 §5 同：另开一只 [ResidentWalker]，**不与主遍历共享访问集** ⇒ §1 每一行的归属与
     * 读数逐字不变；与 §1 的两行**不要求逐 MiB 相等**（归属序不同 + GC 噪声）。
     */
    private fun mem9SplitSection(program: Program): String {
        val sb = StringBuilder()
        sb.appendLine("## 6. 段7 / 段6 分根拆分（独立遍历；只做归因，不改 §1 任何一行）")
        sb.appendLine()
        val w = ResidentWalker(sizer)
        val lt = field(program, "lineTable")
        val cai = field(program, "callArgIndex")
        val pcs = field(cai, "pcs")
        val vals = field(cai, "vals")
        val lineBlocks = field(lt, "blocks")
        val overflowPcs = field(lt, "overflowPcs")
        val overflowLines = field(lt, "overflowLines")
        val lineSource = field(lt, "source")

        fun snap(): Map<String, Pair<Int, Long>> = w.classTop(800).associate { it.first to (it.second to it.third) }

        fun row(name: String, vararg roots: Any?): Pair<Long, String> {
            val before = snap()
            val t = System.nanoTime()
            var b = 0L
            for (r in roots) b += w.consume(r)
            val after = snap()
            val detail = after.entries
                .map { e ->
                    val (c0, b0) = before[e.key] ?: (0 to 0L)
                    Triple(e.key, e.value.second - b0, e.value.first - c0)
                }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(5)
                .joinToString(" / ") { "${it.first} +${group(it.third)}个=${mib(it.second)}" }
            return b to "耗时 ${(System.nanoTime() - t) / 1_000_000} ms；逐类：$detail"
        }

        val out = ArrayList<Pair<String, Pair<Long, String>>>()
        // ---- 段 7：三枚根 ----
        // Map 壳单独记（`objectSize` 直算，不走遍历）：它的两个引用字段指向下面两条根，
        // 若先 consume(壳) 会把整张对象图全归到壳上，三枚根就拆不开了。
        val shellBytes = sizer.objectSize(requireNotNull(cai) { "Program 没有 callArgIndex 字段（形态改了？）" })
        w.markVisited(cai)
        out.add("段7 pcs 键表 IntArray" to row("", pcs))
        out.add("段7 vals 引用表 + 其可达的独立实参表/CallArg/名字串" to row("", vals))
        // ---- 段 6：分块形态的可达面走的是 AtomicReferenceArray，元素在 java.base 里判不可见 ----
        // （`fieldsOf` 的 `trySetAccessible` 对具名模块的私有字段返回 false ⇒ 块会被当成叶子漏掉），
        // 所以这一坨**不靠遍历**，直接用 LineTable 自己的精确公式 + 块数读数分行列。
        val stats = program.lineTableBlockStats()
        val filled = stats.second
        val slots = stats.first
        val fullBlock = SizeModel.align8(16L + 2L * com.eratw.port.core.code.LineTable.BLOCK)
        val lineTotal = program.lineTableResidentBytes
        sb.appendLine("- 段 6 形态：${if (program.lineTableIsPaged) "稀疏分块（MEM9 刀 2 已接上）" else "整表 ShortArray（**退回形态**：整段 pin 覆盖不到声明的行号区）"}")
        sb.appendLine("- 段 6 分项（`LineTable.bytes` 的精确公式拆开列；单位 MiB）：")
        sb.appendLine("  - 块位表 ${group(slots)} 槽 = ${mib(32L + slots * 4L)}（表壳 16 + Object[] 头 16 + 4 B/槽）")
        sb.appendLine("  - 已灌块 ${group(filled)} × 满块宽 ${fullBlock / 1024} KiB ≈ ${mib(filled.toLong() * fullBlock)}")
        sb.appendLine("  - 溢出表 2 张 = ${mib(arrBytes(overflowPcs) + arrBytes(overflowLines))}" +
            "（行号 > 65533 或 < -1 的条目；语料实测 0 条）")
        sb.appendLine("  - 本体合计 = **${mib(lineTotal)}**；全灌满的上界 = ${mib(arrBytes(ShortArray(0)) + program.instructionCount * 2L)}" +
            "（⇒ 分块形态**不会比旧整表形态贵**）")
        sb.appendLine("- 段 7 形态：CallArgIndex（紧凑本体，无 LinkedHashMap 壳）；站点 ${group(arrLen(pcs))}" +
            "；vals 非空槽 ${group(nonNullCount(vals))} 张（= 去重后的独立实参表）" +
            "；两张数组本体 = ${mib(arrBytes(pcs) + arrBytes(vals))}；Map 壳 = ${shellBytes} B")
        sb.appendLine()
        sb.appendLine("| 根 | MiB | 逐类新增 top5 |")
        sb.appendLine("|---|---:|---|")
        sb.appendLine("| 段7 CallArgIndex 壳（Map 视图对象本体，不走遍历） | ${mib(shellBytes)} | 两张数组另列下面两行 |")
        for ((name, r) in out) sb.appendLine("| $name | ${mib(r.first)} | ${r.second} |")
        sb.appendLine()
        sb.appendLine(
            "> 「pcs / vals」两行就是本路的靶：旧形态在段 7 上付的是 **一张 151,120 条目的 " +
                "LinkedHashMap**（节点 40 B + 装箱 `Integer` 键 16 B + 桶数组 4 B/槽 ≈ 9.1 MiB）" +
                "**再加两份同数据副本**（`callArgsPcs`/`callArgsVals` ≈ 1.2 MiB）——" +
                "同一批 pc 存了三份索引，而真正唯一的数据只有去重后的那些实参表与 CallArg。",
        )
        sb.appendLine()
        return sb.toString()
    }

    /**
     * **vars 面拆分**（§5）：把 §1 里「VariableStore + 运行期图」那枚粗桶按**根**再拆一遍。
     *
     * 为什么要独立一趟：§1 的桶是「先到先归」的单根遍历（根 = `session.store`），
     * store 本体 / 系统变量表 / 用户变量壳 / ERD 关键字表 / `store.aux` 挂着的运行期全算进同一个数。
     * 这里另开一只 [ResidentWalker]（**不与主遍历共享访问集** ⇒ 主表每一行的归属与读数逐字不变），
     * 按上面那几类分别作根，互斥可加，并对每个根记「本桶新增的逐类账单」。
     *
     * 两处刻意的口径收口：
     * - `markVisited(program)`：`store.aux` 经 ProcessRuntime 反挂回 Program，不挡的话
     *   「运行期图」这一桶会把整张程序段（130 MiB 级）再吞一遍、还把别的桶的边吃掉。
     * - `markVisited(store)`：先点名各字段（列 / 表 / 池），最后 `store` 本体作根时
     *   只剩对象壳 + 没点到的字段；对象壳本身单独用 SizeModel 精确列一行。
     *
     * 与 §1 的那枚桶**不要求逐 MiB 相等**：归属序不同（共享实例先到先归）、程序段被挡在门外、
     * 且 §1 读数含 GC 时机噪声。本表只回答「vars 面上到底是哪几张表在吃」。
     */
    private fun varsSplitSection(session: GameSession?, program: Program): String {
        val sb = StringBuilder()
        sb.appendLine("## 5. vars 面拆分（独立遍历；只做归因，不改 §1 任何一行）")
        sb.appendLine()
        if (session == null) {
            sb.appendLine("> 未装配会话（缺 ERATW_GAMEDIR 或 ERATW_MEM3_SESSION=0）：本节无读数。")
            sb.appendLine()
            return sb.toString()
        }
        val store = session.store
        val w = ResidentWalker(sizer)
        // 挡在门外的三类反挂边：程序段（130 MiB 级，§1 已单列）、会话壳（含 gameData/console/sidecar，
        // §1 各自成桶）。不挡 runtime —— 「运行期图」这一桶要的就是它。
        w.markVisited(program)
        w.markVisited(store)
        w.markVisited(session)
        w.markVisited(session.gameData)
        w.markVisited(session.console)

        fun snap(): Map<String, Pair<Int, Long>> =
            w.classTop(800).associate { it.first to (it.second to it.third) }

        fun bucket(name: String, vararg roots: Any?): Pair<Long, String> {
            val before = snap()
            val t = System.nanoTime()
            var b = 0L
            for (r in roots) b += w.consume(r)
            val after = snap()
            val detail = after.entries
                .map { e ->
                    val (c0, b0) = before[e.key] ?: (0 to 0L)
                    Triple(e.key, e.value.second - b0, e.value.first - c0)
                }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(5)
                .joinToString(" / ") { "${it.first} +${group(it.third)}个=${mib(it.second)}" }
            return b to "耗时 ${(System.nanoTime() - t) / 1_000_000} ms；逐类：$detail"
        }

        val registry = field(store, "registry")
        val userVars = field(store, "userVars") as? Map<*, *>
        val privateVars = field(store, "privateVars") as? Map<*, *>
        val templates = field(store, "templatesByNo") as? Map<*, *>
        val csvTables = field(registry, "csvTables") as? Array<*>
        var keywordEntries = 0
        var keywordCapacity = 0
        if (csvTables != null) {
            for (t in csvTables) {
                (field(t, "keywords") as? Map<*, *>)?.let { keywordEntries += it.size }
                (field(t, "names") as? Array<*>)?.let { keywordCapacity += it.size }
            }
        }
        val erdTables = field(store, "userKeywordTables") as? Map<*, *>
        var erdEntries = 0
        erdTables?.values?.forEach { (it as? Map<*, *>)?.let { m -> erdEntries += m.size } }
        val pool = field(store, "pool")
        val privateTotal = privateVars?.values?.sumOf { (it as? Map<*, *>)?.size ?: 0 } ?: 0

        sb.appendLine("- 声明量：全局用户变量 ${group(userVars?.size ?: 0)} 条 / 私有 ${group(privateTotal)} 条" +
                "（分布在 ${group(privateVars?.size ?: 0)} 个函数键）/ 角色模板 ${group(templates?.size ?: 0)} 条")
        sb.appendLine("- 内置关键字表：${group(keywordEntries)} 个键（29 张表的名字槽合计 ${group(keywordCapacity)}）" +
                "；ERD 用户表：${group(erdTables?.size ?: 0)} 张 / ${group(erdEntries)} 个键")
        sb.appendLine("- 字符串驻留池：${group((field(pool, "values") as? List<*>)?.size ?: 0)} 条")
        sb.appendLine()
        sb.appendLine("| 根 | MiB | 逐类新增 top5 |")
        sb.appendLine("|---|---:|---|")
        val parts = listOf(
            "系统全局整数列 gInt1/gInt2/gInt3 + ITEMPRICE 常量" to arrayOf(
                field(store, "gInt1"), field(store, "gInt2"), field(store, "gInt3"),
                field(store, "itemPriceSource"),
            ),
            "系统全局字符串列 gStr1/gStr0（存池下标）" to arrayOf(field(store, "gStr1"), field(store, "gStr0")),
            "角色表 CharacterTable（含 CHARADATA 用户列）" to arrayOf(field(store, "characters")),
            "VariableRegistry（系统 VarDef + 29 张 CsvNameTable 关键字表）" to arrayOf(registry),
            "字符串驻留池 StringTable" to arrayOf(pool),
            "全局用户变量四表 userVars/userVarsRaw/global/saved（DimSpec+VarDef+UserVar+数据数组）" to arrayOf(
                field(store, "userVars"), field(store, "userVarsRaw"),
                field(store, "globalUserVars"), field(store, "savedUserVars"),
            ),
            "私有用户变量 privateVars + privateNames" to arrayOf(field(store, "privateVars"), field(store, "privateNames")),
            "局部变量态 localsByFunc/localLengths/frames/framePool" to arrayOf(
                field(store, "localsByFunc"), field(store, "localLengths"),
                field(store, "frames"), field(store, "framePool"),
            ),
            "角色模板 templatesByNo/templatesByName" to arrayOf(
                field(store, "templatesByNo"), field(store, "templatesByName"),
            ),
            "ERD 用户变量关键字表 userKeywordTables" to arrayOf(field(store, "userKeywordTables")),
            "绑定/名字记忆缓存 bind* + upperNames + 句柄表" to arrayOf(
                field(store, "bindNames"), field(store, "bindPriv"), field(store, "bindVals"),
                field(store, "upperNames"), field(store, "handleEntries"), field(store, "handleSlots"),
                field(store, "indexScratch"),
            ),
            "运行期图（store.aux 起：ProcessRuntime/ExecContext/宿主造的对象；程序段与 CSV 已挡）" to
                arrayOf(field(store, "aux")),
            "store 其余字段（config/sizes/writeProbe…）" to arrayOf(store),
        )
        var sum = 0L
        for ((name, roots) in parts) {
            val (b, note) = bucket(name, *roots)
            sum += b
            sb.appendLine("| $name | ${mib(b)} | $note |")
        }
        sb.appendLine("| 合计（独立遍历） | ${mib(sum)} | VariableStore 对象壳另计 ${mib(sizer.objectSize(store))} MiB |")
        sb.appendLine()
        sb.appendLine("> 本节合计与 §1 的「VariableStore + 运行期图」桶同根不同序，**不要求逐 MiB 相等**")
        sb.appendLine("> （程序段/CSV/控制台在 §5 被 markVisited 挡在门外，共享实例的归属序也不同）。")
        sb.appendLine()
        return sb.toString()
    }

    /**
     * 真机 dalloc 固定读数（任务书给的六条，`load.*` 行的 allocMiB 字段）。
     * JVM 侧取同名窗口的**进程口径**和；「段3 语句记录」把 1/8..8/8 合起来对。
     */
    private fun appendDeviceCompare(sb: StringBuilder, wins: List<WinRow>) {
        val device = listOf(
            "program.语句偏移侧车" to 21L,
            "program.段2 局部串池" to 7L,
            "program.段2 节点表" to 130L,
            "program.段2 池根表" to 8L,
            "program.段3 局部串池" to 262L,
            "program.段3 语句记录" to 5L,
        )
        fun jvmSum(prefix: String): Long = wins.filter { it.stage.startsWith(prefix) }.sumOf { it.procBytes }
        var jAll = 0L
        var devAll = 0L
        for ((stage, dev) in device) {
            val j = jvmSum(stage)
            jAll += j; devAll += dev
            val why = when {
                stage == "program.段3 局部串池" ->
                    "JVM 侧本窗只建 mmap 映射（pinWhole，0 堆分配；窗口耗时是建映射不是分配）；device 262 主要是并行的 " +
                        "BootMetadata.read 线程分配落进了这个墙钟窗（GameHost.kt:934；JVM 串行实测见 §2 `meta.侧车解析`）"
                stage == "program.段2 局部串池" ->
                    "数据块走 mmap 不进堆；偏移 IntArray 仅 0.3 MiB。JVM 窗口 16.5 里主线程只占 0.7，" +
                        "其余是 erbc-stmt3/预取工兵线程的分配被墙钟归因进来——单窗是归因口径，不是保留量"
                stage.endsWith("节点表") ->
                    "主力是段3 索引趟 readNodesIndexed「全量建 Expr 再置空」的瞬时分配 + 主线程边界扫描 + 预取线程；" +
                        "device 130 与 JVM 同窗+邻窗合计同量级，这是装载 churn 的最大头"
                stage.endsWith("池根表") ->
                    "roots IntArray + arena 空表 + u32Array 临时 ByteArray；工兵线程边界不同，单窗可差 2 倍，合计口径一致"
                stage.contains("侧车") ->
                    "可信侧车档真实成本 = IntArray(171 万) 6.5 MiB + 分块读缓冲；device 21 还含 MappedErbc 打开与" +
                        "并行 meta 线程起跑的分配被归进来"
                else ->
                    "可信侧车档只建 `IntArray(stmtCount)`（记录偏移合并表）+ cached 背衬；device 窗切分粗，" +
                        "这笔落在 8/8 与下一 mark 之间；扫描档这里会是 ~900 MiB"
            }
            sb.appendLine("| ${stage.removePrefix("program.")} | $dev | ${mib(j)} | $why |")
        }
        sb.appendLine()
        sb.appendLine("> 逐窗 dalloc 是**墙钟进程口径**：真机装载期 `BootMetadata.read` 与 program 解码**并行**（GameHost.kt:934），")
        sb.appendLine("> 它的分配会洒进当时正在走的 program.* 窗口；JVM 探针里 meta 解析是独立串行窗口（§2 `meta.侧车解析`）。")
        sb.appendLine("> 所以别拿单窗对账，拿合计：device 六窗合计 $devAll MiB，JVM 同窗合计 ${mib(jAll)} MiB——")
        sb.appendLine("> 差值≈被归因到 program 窗内的 meta churn（JVM 实测见 §2 `meta.侧车解析`），再叠加 ART/HotSpot 对象宽度差（±5% 级）。")
        sb.appendLine("> JVM 单窗读数复跑展布可到 ±40%（工兵线程墙钟归因抖动）；§1 常驻构成复跑展布 ±10 MiB——后者才是可比量。")
        sb.appendLine("> 采信口径：**绝对峰值与 GC 行为信真机**（读 `Dalvik Heap` 行的 committed 峰 343/稳定 209），")
        sb.appendLine("> **逐窗归因与构成信 JVM**（工兵线程归属可查、字段级精确行可与本报告 §1 对账）。")
    }
}
