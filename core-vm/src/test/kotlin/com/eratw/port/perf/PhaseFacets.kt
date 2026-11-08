package com.eratw.port.perf

import com.eratw.port.core.code.CallArgIndex
import com.eratw.port.core.code.Program
import com.eratw.port.core.host.CBG_SLOT
import com.eratw.port.core.host.GRAPHICS_SLOT
import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.host.SPRITE_SLOT
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.loader.BlockRefs
import com.eratw.port.core.loader.BlockU32s
import com.eratw.port.core.perf.ResidentWalker
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.GameSession
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReferenceArray

// ===========================================================================
// OBS-PHASETITLE 相位分面装置：把 `HeapRootAttributionProbe` 的逐根口径搬到**运行期相位**。
//
// 上一路（已合 main）把「装载 + 装配完成」钉在 live ≈ 69 MiB，并更正了「桌面 280 MiB 是装载
// 常驻」这条写错的归因 —— 280 MiB 属于**标题页之后**的相位。本装置因此只补一段：
// 同一个 root 集、同一个消费序、同一套来源标注，**逐相位**再读一遍，好让相位之间可差分。
//
// 纪律（与 `HeapRootAttributionProbe` 同源，一条都不放宽）：
// - 每相位一个全新 `RootLedger` + 全新 `ResidentWalker`；恒等式「入账合计 + 未归类 = live 总额」
//   逐相位各自闭合，容差 ±10 MiB 沿用 `HeapMeter` 标定（`RootLedger.verdict` 缺省）；
// - 来源四口径不混为「实测」：`枚举-精确公式` / `枚举-遍历模型` / `jcmd直方图` / `GC差`；
// - 消费序即归属序（身份去重、先到先归）。跨相位可比的前提是**每相位跑同一段代码、同一顺序**，
//   所以这里把根顺序写死，不给调用方留调整口；
// - 只读产品对象图，一个字段都不写、不催任何产品行为（物化只发生在脚本自己走过的地方）。
//
// 与 `HeapRootAttributionProbe` 的四处刻意差别（都是本相位才需要的）：
// 0. **控制台行表最先点名**：它挂在宿主链上（`builtinHost`/`beginHost` 都持它），按原序排在面 7 之后
//    时被更早的根整个吃掉（实测行数列 48 行、遍历列恒 0.0 MiB、耗时 0 ms = 早已登记）。
//    本相位要追的就是这一面，所以把它提到遍历最前；其余各面的序照原探针不动。
// 1. **已物化语句逐条作根**（`BlockRefs.forEachActive`）：装配完那张表几乎空着，所以原探针只按
//    公式计了引用表骨架；运行期相位骨架不变、条目在长，不逐条收就会把最大的那块掉进「未归类」；
// 2. **图面成独立面**：`store.aux` 的 gfx / sprite / cbg 三个槽先于「aux 槽本体」消费。
//    像素按 `GraphicsPool.stats()` / `GraphicsBuffer.pixelBytes` 的**精确公式**入账（正号段），
//    Sprite 侧只补「不在 G_ID 池里的独有缓冲」，两池共享的实例数另报一行做交叉（负号段是
//    **去重说明**，不入总账 —— 同一批 IntArray 计两次就是重复计账，`verdict` 会判 OVERCOUNT）；
// 3. **渲染侧缓存单独成面但不入账**：本装置跑在 `:core-vm` 测试源集的 classpath 上，
//    `:render`（字形缓存 / 合成画布）与 `:tools:uipreview`（AWT 栅格）都不在这条 classpath 上
//    （依赖方向是 render 吃 core-vm、uipreview 吃 core-vm，反向不成立）。所以这一面固定给
//    「公式(不入账) + 0」加一句口径声明，跨装置的差额交给 jcmd 行交叉，绝不假装量到了它。
// ===========================================================================

/** 上一路 `HeapRootAttributionProbe` 的等距抽样实测：每条物化语句 7,933 B 的 live 增量（边际尺）。 */
internal const val MARGINAL_STMT_BYTES = 7_933L

/** 逐类账单取前 N 条（只为在本进程里找渲染/栅格类，不参与总账）。 */
private const val WALK_CLASS_TOP = 3_000

/** 分面名（每相位同一张表的行键；差分按这个键对齐，所以名字写死成常量）。 */
internal object PhaseSurface {
    const val CODE = "1 指令/code 段"
    const val CONST_POOL = "2 常量池"
    const val STRINGS = "3 串表(段0/2/3/4/8)"
    const val STATEMENTS = "4 语句对象树(骨架+物化)"
    const val LINE_TABLE = "5 指令行号表"
    const val FN_TABLE = "6 函数表/标签表+数据节"
    const val CSV = "7 CSV 表"
    const val CONSOLE = "8 控制台行表"
    const val GRAPHICS = "9 图面(gfx/sprite/cbg)"
    const val VARS = "10 变量池"
    const val RUNTIME = "11 运行期与宿主"
    const val SHELLS = "12 会话壳残余"
    const val RENDER = "13 渲染侧缓存(:render，classpath 外)"

    /** 表里出现的顺序（差分表按这个顺序出行，不按大小 —— 跨相位对齐靠行键）。 */
    val ORDER = listOf(
        CODE, CONST_POOL, STRINGS, STATEMENTS, LINE_TABLE, FN_TABLE, CSV,
        CONSOLE, GRAPHICS, VARS, RUNTIME, SHELLS, RENDER,
    )
}

/** 一相位的读数包：live（GC差口径，已扣 S0）+ 各面入账字节 + 恒等式判定 + 廉价计数器。 */
internal class PhaseReading(
    val label: String,
    val liveBytes: Long,
    val surfaces: Map<String, Long>,
    val counted: Long,
    val unclassified: Long,
    val verdict: RootLedger.Verdict,
    val ledger: RootLedger,
    val counters: Map<String, String>,
    val walkMs: Long,
) {
    /** 同相位、同一次的 `jcmd GC.class_histogram` live 总量（-1 = 未采）；只作交叉，不改 GC差口径。 */
    var jcmdLiveBytes: Long = -1L

    /**
     * 判定override：两个口径（GC差 / jcmd）在「入账 > live」这件事上 disagreement 时用它，
     * 空 = 按 `verdict` 原样出行。口径分歧不许靠改判据抹平，只许并排报（见探针主体的四口径纪律）。
     */
    var verdictOverride: String = ""
}

/**
 * 相位分面器：一次 [attribute] = 一相位的完整逐根遍历（新建 walker，遍历后释放访问集）。
 *
 * @param marginalStmtBytes 上一路标定的**边际尺**（每条物化语句的 live 增量，7,933 B），
 *        只用于「按字节反推物化条数」的交叉列（不入账），不参与总账。
 */
internal class PhaseFacets(
    private val sizer: SizeModel,
    private val marginalStmtBytes: Long = MARGINAL_STMT_BYTES,
) {

    private val logBuf = StringBuilder()

    /**
     * 最近一次 [attribute] 的**变量池子块**快照（OBS-VARPOOL，公式口径）。
     *
     * 放成属性而不是塞进 `PhaseReading`：`walk=0` 的挡位压根不跑遍历，但相位/逐页的子块读数
     * 照样要出（它比遍历便宜三个数量级），所以调用方在每次读相位后顺手取这一份即可。
     */
    var lastVarPool: VarPoolCounters? = null
        private set

    fun log(s: String) = logBuf.appendLine(s)

    fun logText(): String = logBuf.toString()

    // ---- 反射与布局小工具（与 HeapRootAttributionProbe 同一套，逐字同口径） ----

    private fun field(target: Any?, name: String): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            val f = runCatching { c.getDeclaredField(name) }.getOrNull()
            if (f != null) return runCatching { f.isAccessible = true; f.get(target) }.getOrNull()
            c = c.superclass
        }
        return null
    }

    private fun fieldByType(target: Any?, type: Class<*>): Any? {
        if (target == null) return null
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type == type) return runCatching { f.isAccessible = true; f.get(target) }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

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
        return sizer.objectSize(t) + SizeModel.align8(16L + 4L * t.blockCount) + filled.toLong() * full - tail
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

    // ------------------------------------------------------------------
    // 一相位的遍历（根顺序写死，见文件头纪律第 3 条）
    // ------------------------------------------------------------------

    fun attribute(
        label: String,
        liveBase: Long,
        program: Program,
        session: GameSession,
        counters: Map<String, String> = emptyMap(),
    ): PhaseReading {
        val ledger = RootLedger(liveBase)
        val walker = ResidentWalker(sizer)
        val t0 = System.nanoTime()

        fun bucket(surface: String, item: String, count: String = "", vararg roots: Any?, note: String = ""): Long {
            val t = System.nanoTime()
            var b = 0L
            for (r in roots) b += walker.consume(r)
            val ms = (System.nanoTime() - t) / 1_000_000
            ledger.add(surface, item, count, b, "枚举-遍历模型",
                note = if (note.isEmpty()) "耗时 ${ms} ms" else "$note；耗时 ${ms} ms")
            return b
        }

        fun shellRow(surface: String, item: String, o: Any?, note: String = "") {
            if (o == null) return
            ledger.add(surface, item, "", sizer.objectSize(o), "枚举-精确公式", note = note)
        }

        val pool = field(program, "pool")
        // ================= 面 8 先点名（消费序的唯一位置特例）=================
        // 控制台行表是本相位要追的头号对象，而它挂在宿主链上（`builtinHost`/`beginHost` 都持它），
        // 按「面 8 排面 7 之后」的原序实测被更早的根吃掉过：行数列给 48 行、遍历列恒 0.0 MiB、
        // 耗时 0 ms（= 整棵子树早在别的面就登记了）。所以这里把它提到**最前**：
        // 先按自己的根点名，再让别的面去捡剩下的。其余各面的序照 `HeapRootAttributionProbe` 不动。
        run {
            val console = session.console
            bucket(PhaseSurface.CONSOLE, "sink 壳 + displayLines/pending/islands（当前相位真值，最先点名）",
                roots = arrayOf(console))
            val displayLineClass = runCatching {
                console.javaClass.declaredClasses.firstOrNull { it.simpleName == "DisplayLine" }
            }.getOrNull()
            val dlShallow = displayLineClass?.let { sizer.shallowOf(it) } ?: 0
            val lineCount = runCatching { console.displayLineCount }.getOrDefault(-1)
            ledger.add(
                PhaseSurface.CONSOLE, "行数与单行浅尺寸（公式列，不入账）",
                "$lineCount 行 / maxLines=${console.maxLines} / 单行浅 $dlShallow B", 0L,
                "枚举-公式(不入账)", counted = false,
                note = "行**对象**只是下界：每行的 TextSpan 列表 + 串 + 按钮项全在上面的遍历列里",
            )
        }
        val lazyStmt = field(pool, "lazyStatements")
        val lazyExpr = field(pool, "lazyExprs")
        val stmtArena = lazyStmt?.let { field(it, "arena") }
        val stmtStrings = stmtArena?.let { field(it, "strings") }
        val exprArena = lazyExpr?.let { field(it, "arena") }
        val exprStrings = exprArena?.let { field(it, "strings") }
        val fnTable = program.functions
        val fnStrings = field(fnTable, "strings")
        val fnParam = field(fnTable, "paramNames")

        // ================= 面 1：指令 / code 段 =================
        val codeSource = program.codeSource
        val pageScratch = arrLen(field(codeSource, "pageScratch")) / 4
        val pageCache = field(codeSource, "cache")
        ledger.add(
            PhaseSurface.CODE, "MappedCodeSource 换页缓冲 + 页缓存表",
            "常驻页 ${nonNullCount(pageCache)} × ${pageScratch} 字",
            arrBytes(field(codeSource, "pageScratch")) + arrBytes(pageCache),
            "枚举-精确公式",
            note = "CODE 段本体 = 只读映射（堆外 ${bufLimit(field(codeSource, "read"))} B 地址空间），不进 Java 堆账；" +
                "本列的跨相位增量 = 页缓存真的灌回来了多少",
        )
        bucket(PhaseSurface.CODE, "CodeSource 壳与对象图残余", roots = arrayOf(codeSource))

        // ================= 面 2：常量池 =================
        val gLongs = field(pool, "longs") as List<*>
        walker.markVisited(gLongs)
        ledger.add(
            PhaseSurface.CONST_POOL, "longs（数值常量，装箱 Long）", "${gLongs.size} 条",
            gLongs.size * 24L + SizeModel.align8(16L + 4L * gLongs.size) + 24L, "枚举-精确公式",
        )
        val poolExprs = field(pool, "exprs") as List<*>
        walker.markVisited(poolExprs)
        var poolExprBytes = 0L
        for (e in poolExprs) poolExprBytes += walker.consume(e)
        ledger.add(PhaseSurface.CONST_POOL, "exprs 表达式树（惰性表达式池接上时为空壳）",
            "${poolExprs.size} 条", poolExprBytes, "枚举-遍历模型")
        shellRow(PhaseSurface.CONST_POOL, "ConstantPool 壳", pool)

        // ================= 面 3：串表 / 局部串表 =================
        val poolStrings = field(pool, "strings") as List<*>
        var gStrBytes = 0L
        for (s in poolStrings) {
            val str = s as String
            gStrBytes += SizeModel.stringBytes(str)
            walker.markVisited(str)
        }
        walker.markVisited(poolStrings)
        ledger.add(
            PhaseSurface.STRINGS, "段0 全局串表（背衬 + 逐串 compact-string 公式）",
            "${poolStrings.size} 条", gStrBytes + SizeModel.align8(16L + 4L * poolStrings.size) + 24L,
            "枚举-精确公式",
        )
        val paramCounts = fnParam?.let { field(it, "counts") }
        val paramFrom = fnParam?.let { field(it, "dataFrom") }
        val paramStrings = fnParam?.let { field(it, "strings") }
        for ((segName, sp) in listOf(
            "段3 语句局部串表" to stmtStrings,
            "段2 表达式局部串表" to exprStrings,
            "段4 函数名局部串表" to fnStrings,
            "段8 形参名局部串表" to paramStrings,
        )) {
            if (sp == null) continue
            val offs = strOffsets(sp)
            val offItems = offs?.size ?: strIndexEntries(sp)
            ledger.add(
                PhaseSurface.STRINGS, "$segName 偏移表（整张数组或稀疏分块真身）",
                "项数 ${offItems - 1} × 4 B", strOffsetResident(sp), "枚举-精确公式",
                note = "数据块 = 映射（堆外 ${strDataBytes(sp)} B）；memoize=${field(sp, "memoize")}",
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
                ledger.add(
                    PhaseSurface.STRINGS, "$segName 已解码备忘（cache 数组 + 钉住的 String 实例）",
                    "已解码 ${nonNullCount(cache)} 条", arrBytes(cache) + dec, "枚举-精确公式",
                    note = "这一列随相位变长：脚本走到哪，哪一段的串才被解出来钉进堆",
                )
                walker.markVisited(cache)
            }
        }
        ledger.add(
            PhaseSurface.STRINGS, "段8 形参名 counts + dataFrom 索引两张", "${arrLen(paramCounts)} 函数",
            arrBytes(paramCounts) + arrBytes(paramFrom), "枚举-精确公式",
        )
        walker.markVisited(paramCounts)
        walker.markVisited(paramFrom)

        // ================= 面 4：语句对象树（骨架 + **已物化逐条**） =================
        var skeletonCachedRef: Any? = null
        var internerRepRoots: List<Any> = emptyList()
        if (lazyStmt != null) {
            val recordOffsets = field(lazyStmt, "recordOffsets")
            walker.markVisited(recordOffsets)
            val roHeap = field(recordOffsets, "heap") as IntArray?
            if (roHeap != null) {
                ledger.add(PhaseSurface.STATEMENTS, "语句记录偏移表 IntArray（回落扫描档）",
                    "${roHeap.size} × 4 B", arrBytes(roHeap), "枚举-精确公式")
                walker.markVisited(roHeap)
            } else {
                ledger.add(
                    PhaseSurface.STATEMENTS, "语句记录偏移表（侧车只读映射形态）",
                    "${field(recordOffsets, "size") as Int? ?: 0} 条 u32", 0L, "枚举-精确公式",
                    note = "堆内 0：codeBase 偏移基座这条腿已吃到",
                )
            }
            skeletonCachedRef = field(lazyStmt, "cached")
            val nodesArr = field(lazyStmt, "nodes") ?: field(stmtArena, "nodes")
            val nodeOffsets = field(stmtArena, "nodeOffsets")
            ledger.add(
                PhaseSurface.STATEMENTS, "条目引用表骨架（块位表 + 已分配块）",
                "${slotLen(skeletonCachedRef)} 槽 / 表上已物化 ${slotNonNull(skeletonCachedRef)}",
                refTableBytes(skeletonCachedRef), "枚举-精确公式",
                note = "骨架只随**块数**长（块宽 4,096 槽），逐条对象在下面的物化行",
            )
            ledger.add(
                PhaseSurface.STATEMENTS, "节点表 + 节点偏移表（惰性置空后的骨架）",
                "${slotLen(nodesArr)} 槽 + ${nodeOffsetCount(nodeOffsets)} 条",
                refTableBytes(nodesArr) + nodeOffsetsBytes(nodeOffsets), "枚举-精确公式",
            )
            walker.markVisited(skeletonCachedRef)
            walker.markVisited(nodesArr)
            walker.markVisited(nodeOffsets)
            walker.markVisited(field(lazyStmt, "window"))
            val interner = field(lazyStmt, "interner")
            if (interner != null) {
                val hashes = field(interner, "hashes")
                val reps = field(interner, "reps")
                val strPool = field(interner, "strings")
                ledger.add(
                    PhaseSurface.STATEMENTS, "ArgInterner 规范表骨架",
                    "cap=${arrLen(hashes)}", arrBytes(hashes) + arrBytes(reps) +
                        (if (strPool == null) 0L else sizer.objectSize(strPool) + arrBytes(field(strPool, "table"))),
                    "枚举-精确公式",
                )
                walker.markVisited(hashes)
                walker.markVisited(reps)
                walker.markVisited(strPool)
                walker.markVisited(field(strPool, "table"))
                // 代表树是「运行期物化」的反挂边总入口（一条实参树能把 FunctionInfo / registry /
                // 变量池都拽住），所以**不在这里 consume**，收进末尾的兜底组（见文件头第 1 条）。
                internerRepRoots = (reps as? Array<*>)?.toList().orEmpty().filterNotNull()
            }
            bucket(PhaseSurface.STATEMENTS, "池壳/arena/串表壳/窗口残余", roots = arrayOf(lazyStmt))
        } else {
            ledger.add(PhaseSurface.STATEMENTS, "本产物档无惰性语句池", "", 0L, "档位声明")
        }
        if (lazyExpr != null) {
            val roots = field(lazyExpr, "roots")
            val nodesArr = field(lazyExpr, "nodes") ?: field(exprArena, "nodes")
            val nodeOffsets = field(exprArena, "nodeOffsets")
            ledger.add(
                PhaseSurface.STATEMENTS, "段2 惰性表达式池骨架（池根表 + 节点表 + 偏移表）",
                "${field(lazyExpr, "poolCount") as Int? ?: 0} 根 + ${slotLen(nodesArr)} 槽 + " +
                    "${nodeOffsetCount(nodeOffsets)} 条",
                blockU32Bytes(roots) + refTableBytes(nodesArr) + nodeOffsetsBytes(nodeOffsets),
                "枚举-精确公式", note = "表达式树本体随语句物化被带进来，归面 4",
            )
            walker.markVisited(roots)
            walker.markVisited(nodesArr)
            walker.markVisited(nodeOffsets)
            walker.markVisited(field(lazyExpr, "lazyWin"))
            val exprCache = field(lazyExpr, "cached") ?: field(lazyExpr, "cache")
            if (exprCache is BlockRefs<*>) {
                walker.markVisited(exprCache)
            }
            bucket(PhaseSurface.STATEMENTS, "段2 表达式池壳/arena 残余", roots = arrayOf(lazyExpr))
        }
        if (program.flatStatementsEnabled) {
            bucket(PhaseSurface.STATEMENTS, "扁平语句池 flatStmtSource（开启档才入账）", roots = arrayOf(program.flatStmtSource))
        } else {
            ledger.add(PhaseSurface.STATEMENTS, "扁平语句池", "设备口径 flatStatements=false ⇒ 未构建", 0L, "档位声明")
        }
        val cai = field(program, "callArgIndex") as? CallArgIndex
        if (cai != null) {
            val pcs = field(cai, "pcs")
            val vals = field(cai, "vals")
            ledger.add(
                PhaseSurface.STATEMENTS, "段7 CallArgIndex 本体（pcs + vals + 壳）",
                "${arrLen(pcs)} 点 / 独立表 ${cai.distinctListCount()} 张",
                arrBytes(pcs) + arrBytes(vals) + sizer.objectSize(cai), "枚举-精确公式",
            )
            walker.markVisited(pcs)
            walker.markVisited(vals)
            walker.markVisited(cai)
            val listRoots = ArrayList<Any>()
            (vals as? Array<*>)?.forEach { if (it != null) listRoots.add(it) }
            bucket(PhaseSurface.STATEMENTS, "独立实参表 + CallArg 实例 + 名字串", roots = listRoots.toTypedArray())
        }
        // **本装置的关键面**：已物化语句逐条作根。装配完那张表几乎空着，所以原探针没有这一腿。
        // **消费序的唯一定 deviation**（写在文件头第 1 条）：这里只**收集**条目实例，真正的 consume
        // 放到整趟遍历的最后（面 12 之后）。放在前面会把函数表 / CSV / 变量池 / 运行期那批
        // 「被运行期反挂边指到的对象」整个吞进语句面 —— 实测形态是语句面 +161 MiB、
        // 函数表 -24.1 / 变量池 -11.7 / CSV -2.4 / 运行期 -1.4 的假象差分（2026-11-01 首跑）。
        // 身份去重下总额不变，变的只是归属；归到语句面还是归到各自的面，取「先按面点名、
        // 物化条目兜底」这一序，差分表才读得出「谁在长」。
        val stmtRoots = ArrayList<Any>(slotNonNull(skeletonCachedRef).coerceAtLeast(16))
        @Suppress("UNCHECKED_CAST")
        (skeletonCachedRef as? BlockRefs<Any>)?.forEachActive { s -> stmtRoots.add(s) }
        val matCount = stmtRoots.size
        ledger.add(
            PhaseSurface.STATEMENTS, "已物化条目引用表上的条数（字节见末尾的兜底行）",
            "$matCount 条（`program.materializedStatementCount`=${program.materializedStatementCount}）",
            0L, "精确计数(不入账)", counted = false,
        )
        val exprCachedRef = field(lazyExpr, "cached") ?: field(lazyExpr, "cache")
        val exprRoots = ArrayList<Any>()
        @Suppress("UNCHECKED_CAST")
        (exprCachedRef as? BlockRefs<Any>)?.forEachActive { e -> exprRoots.add(e) }

        // ================= 面 5：指令行号表 =================
        ledger.add(
            PhaseSurface.LINE_TABLE, "LineTable 常驻（分块 = 块位表 + 已灌块）",
            "${program.instructionCount} 指令 / ${program.lineTableBlockStats().first} 块位 / " +
                "已灌 ${program.lineTableBlockStats().second} 块",
            program.lineTableResidentBytes, "枚举-精确公式",
            note = "全灌满硬上限 = count×2 B = ${program.instructionCount * 2L} B；" +
                "本列跨相位的增量就是「游玩期行号表涨回来多少」的答案",
        )
        walker.markVisited(field(program, "lineTable"))

        // ================= 面 6：函数表 / 标签表 + 数据节 =================
        val fnRecordOffsets = field(fnTable, "recordOffsets")
        ledger.add(PhaseSurface.FN_TABLE, "映射函数表骨架 recordOffsets", "${arrLen(fnRecordOffsets)} 函数",
            arrBytes(fnRecordOffsets), "枚举-精确公式")
        walker.markVisited(fnRecordOffsets)
        walker.markVisited(fnTable) // 整根 consume 会当场物化全部 FunctionInfo，禁止
        walker.markVisited(field(fnTable, "window"))
        bucket(PhaseSurface.FN_TABLE, "已物化 FunctionInfo（cache 表逐条）", roots = arrayOf(field(fnTable, "cache")))
        bucket(PhaseSurface.FN_TABLE, "映射函数表壳 + 名/形参串表壳 + 窗口残余", roots = arrayOf(fnParam, fnStrings))
        val sidecar = field(session, "sidecar")
        if (sidecar != null) {
            walker.markVisited(sidecar)
            shellRow(PhaseSurface.FN_TABLE, "SidecarTables 壳", sidecar)
            bucket(PhaseSurface.FN_TABLE, "剪枝标签表（侧车投影）", roots = arrayOf(field(sidecar, "labels")))
            bucket(PhaseSurface.FN_TABLE, "PRINTDATA/STRDATA 表达式组（数据节，按语句池下标直查）",
                roots = arrayOf(field(sidecar, "sections")))
        }

        // 程序段挡门：store.aux 反挂边不许把程序再吞一遍（与上一路同一道门）
        walker.markVisited(program)
        shellRow(PhaseSurface.SHELLS, "Program 对象壳", program)

        // ================= 面 7：CSV 表 =================
        val gameData = session.gameData
        if (gameData != null) {
            bucket(PhaseSurface.CSV, "GameData 总装（CsvNameTable + templates + itemPrices + registry）",
                roots = arrayOf(gameData))
        } else {
            ledger.add(PhaseSurface.CSV, "gameData=null（CSV 目录缺失）", "", 0L, "档位声明")
        }

        // ================= 面 8：控制台行表 =================
        // 已在最前面点完名（见 attribute 开头那段的位置特例说明），这里不再重复 consume。

        // ================= 面 9：图面（gfx 正号段 / sprite 独有段 / cbg） =================
        val aux: RuntimeAux? = session.store.aux
        val gfxPool = aux?.peek<GraphicsPool>(GRAPHICS_SLOT)
        val spritePool = aux?.peek<SpritePool>(SPRITE_SLOT)
        if (gfxPool != null) {
            val st = gfxPool.stats()
            ledger.add(
                PhaseSurface.GRAPHICS, "G_ID 池像素驻留（正号段）",
                "${st.entries} 条目 / 已创建 ${st.created}", st.pixelBytes, "枚举-精确公式",
                note = "stats() 逐条目 pixelBytes 求和（4 B/像素）；未创建条目计 0，延迟解码未消费前也计 0",
            )
        } else {
            ledger.add(PhaseSurface.GRAPHICS, "G_ID 池", "本相位还没建（aux 无 gfx 槽）", 0L, "档位声明")
        }
        val gfxBuffers: List<GraphicsBuffer> = runCatching {
            (field(gfxPool, "buffers") as? Map<*, *>)?.values?.filterIsInstance<GraphicsBuffer>().orEmpty()
        }.getOrDefault(emptyList())
        for (b in gfxBuffers) {
            (field(b, "pixelData") as IntArray?)?.let { walker.markVisited(it) }
        }
        val spriteBuffers: List<GraphicsBuffer> = collectSpriteBuffers(spritePool)
        val sharedBuffers = spriteBuffers.count { it in gfxBuffers }
        val spriteOnlyPixels = spriteBuffers.filter { it !in gfxBuffers }
            .sumOf { (field(it, "pixelData") as IntArray?)?.size?.toLong()?.times(4L) ?: 0L }
        ledger.add(
            PhaseSurface.GRAPHICS, "Sprite/资源表独有像素（负号段扣掉共享后的净增）",
            "${spriteBuffers.size} 张引用 / 与 G_ID 共享 $sharedBuffers 张", spriteOnlyPixels, "枚举-精确公式",
            note = "共享那批的像素已在正号段入账，这里按**缓冲实例**去重只补独有部分；" +
                "负号段（共享部分）的字节 = 不重复入账的量，见下一行",
        )
        val sharedPixels = spriteBuffers.filter { it in gfxBuffers }
            .sumOf { (field(it, "pixelData") as IntArray?)?.size?.toLong()?.times(4L) ?: 0L }
        ledger.add(
            PhaseSurface.GRAPHICS, "两池共享缓冲（负号段，不入账）", "$sharedBuffers 张", sharedPixels,
            "枚举-精确公式", counted = false,
            note = "这一行**不**进总账：同一批 IntArray 计两次就是重复计账（`verdict` 会判 OVERCOUNT）",
        )
        for (b in spriteBuffers) {
            if (b in gfxBuffers) continue
            (field(b, "pixelData") as IntArray?)?.let { walker.markVisited(it) }
        }
        bucket(PhaseSurface.GRAPHICS, "两池条目壳 + GraphicsBuffer 对象 + 名字串（像素已按上面入账）",
            roots = arrayOf(gfxPool, spritePool))
        val cbgPool = aux?.peek<Any>(CBG_SLOT)
        bucket(PhaseSurface.GRAPHICS, "CBG 图层表（cbg 槽；层只存 gid/名字，像素归上面几行）", roots = arrayOf(cbgPool))

        // ================= 面 10：变量池 =================
        val store = session.store
        val runtime = session.runtime
        walker.markVisited(session)
        shellRow(PhaseSurface.SHELLS, "GameSession 对象壳", session)
        walker.markVisited(runtime)
        val runtimeFnTable = fieldByType(runtime, FunctionTable::class.java)
        bucket(PhaseSurface.FN_TABLE, "运行期 FunctionTable（侧车 FuncMeta 投影）", roots = arrayOf(runtimeFnTable))
        walker.markVisited(store)
        shellRow(PhaseSurface.VARS, "VariableStore 壳", store)
        val userVars = field(store, "userVars") as? Map<*, *>
        val privateVars = field(store, "privateVars") as? Map<*, *>
        val templates = field(store, "templatesByNo") as? Map<*, *>
        val privateTotal = privateVars?.values?.sumOf { (it as? Map<*, *>)?.size ?: 0 } ?: 0
        ledger.add(
            PhaseSurface.VARS, "声明量（计数行，不入账）",
            "全局用户 ${userVars?.size ?: 0} / 私有 $privateTotal（${privateVars?.size ?: 0} 函数键）" +
                " / 角色模板 ${templates?.size ?: 0}", 0L, "精确计数(不入账)", counted = false,
        )
        val varRoots = listOf(
            "系统全局整数列 gInt1/gInt2/gInt3 + itemPriceSource" to
                arrayOf(field(store, "gInt1"), field(store, "gInt2"), field(store, "gInt3"), field(store, "itemPriceSource")),
            "系统全局字符串列 gStr1/gStr0（存池下标）" to arrayOf<Any?>(field(store, "gStr1"), field(store, "gStr0")),
            "角色表 CharacterTable（含 CHARADATA 用户列）" to arrayOf<Any?>(field(store, "characters")),
            "VariableRegistry 壳与系统 VarDef" to arrayOf<Any?>(field(store, "registry")),
            "字符串驻留池 StringTable" to arrayOf<Any?>(field(store, "pool")),
            "全局用户变量四表（DimSpec+VarDef+UserVar+数据数组）" to arrayOf(
                userVars, field(store, "userVarsRaw"), field(store, "globalUserVars"), field(store, "savedUserVars"),
            ),
            "私有用户变量 privateVars + privateNames" to arrayOf(privateVars, field(store, "privateNames")),
            "局部变量态 localsByFunc/localLengths/frames/framePool" to arrayOf(
                field(store, "localsByFunc"), field(store, "localLengths"), field(store, "frames"), field(store, "framePool"),
            ),
            "角色模板 templatesByNo/templatesByName" to arrayOf(templates, field(store, "templatesByName")),
            "ERD 用户关键字表 userKeywordTables" to arrayOf<Any?>(field(store, "userKeywordTables")),
            "绑定/名字记忆缓存 + 句柄表" to arrayOf(
                field(store, "bindNames"), field(store, "bindPriv"), field(store, "bindVals"),
                field(store, "upperNames"), field(store, "handleEntries"), field(store, "handleSlots"),
                field(store, "indexScratch"),
            ),
            // 图面已在面 9 先消费：这里再吃 aux 只剩「非图形槽」，像素不会重复入账
            "store.aux 其余槽本体（图形三槽已归面 9；ProcessRuntime 图已挡门）" to arrayOf<Any?>(field(store, "aux")),
            "全局/私有 #DIM 装载结果与告警清单" to
                arrayOf(session.globalDimInstall, session.privateDimFailures, session.erdWarnings, session.mouseWarnings),
            "store 未点名其余字段（catch-all 逐字段根）" to refFieldValues(store).toTypedArray(),
        )
        for ((name, roots) in varRoots) bucket(PhaseSurface.VARS, name, roots = roots)

        // ================= 面 10 的子块拆分（OBS-VARPOOL）=================
        // 上面那 14 行是**遍历口径**的深账（一个 `bucket` 一整块，读不出「角色数在长」还是「容量在预留」）。
        // 这里补的是**公式口径**的互斥子块：逐列数组长度 × 元素宽，全部 `counted = false`，
        // 不参与恒等式、不改动 `RootLedger` 的账面算术；跨相位/逐页差分只认这张表。
        // 与遍历行的关系：子块合计是变量池数据面的**下界**（对象图里的壳、串、缓存元素由遍历行负责），
        // 两列并排看，不许相加，也不许说成同一个口径。
        lastVarPool = runCatching { VarPoolFacets(sizer).snapshot(store, store.characters) }.getOrNull()
        lastVarPool?.let { vp ->
            for (k in VarPoolBlock.ORDER) {
                if (!vp.bytes.containsKey(k)) continue
                ledger.add(
                    PhaseSurface.VARS, "$k —— 子块(公式口径)", "", vp.block(k),
                    "枚举-精确公式(不入账)", counted = false,
                    note = "互斥子块：与上面的遍历行不重复计账，两侧求和各自成口径；键跨相位对齐",
                )
            }
            ledger.add(
                PhaseSurface.VARS, "子块互斥合计（面 10 数据面的**下界**，不入账）", "", vp.sumBytes(),
                "枚举-精确公式(不入账)", counted = false,
                note = "角色表 ${vp.counts["角色表.count/容量"]} 格 / 行字节 ${vp.counts["角色表.行字节"]} B / " +
                    "已用 ${vp.counts["角色表.已用字节"]} B / **容量冗余 ${vp.counts["角色表.容量冗余字节"]} B** / " +
                    "预留倍率 ${vp.counts["角色表.预留倍率‰"]}‰ —— 冗余列是判性(乙)的靶子",
            )
            ledger.add(
                PhaseSurface.VARS, "子块计数列（只作判读，不参与求和）",
                vp.counts.entries.joinToString(" / ") { "${it.key}=${it.value}" }, 0L,
                "精确计数(不入账)", counted = false,
                note = "「系统整数列.已分配/总列」这一列是移植侧与原版的方向差：原版 65 列构造时全灌满" +
                    "（spec: VariableData.cs:2963-2967），移植侧 arrayOfNulls 懒分配（spec: VariableStore.kt:113）",
            )
        }

        // ================= 面 11：运行期与宿主 =================
        // 宿主先归（ProcessRuntime 的字段挂着 builtinHost 引用，后归会把宿主图吞进运行期行）
        bucket(
            PhaseSurface.RUNTIME, "宿主/配置/输入壳图（builtinHost→文件IO与配置覆盖链等，整根 consume）",
            roots = arrayOf(session.builtinHost, session.beginHost, session.inputSink, session.configStore),
        )
        shellRow(PhaseSurface.RUNTIME, "ProcessRuntime 壳（已挡门，逐字段成根）", runtime)
        bucket(PhaseSurface.RUNTIME, "ProcessRuntime 图（stub 表/Vm/ExecContext/BEGIN 态/调用栈）",
            roots = refFieldValues(runtime).toTypedArray())

        // ================= 面 4 的末尾兜底：已物化语句 / 已解码表达式条目 =================
        // 三行都放最后（消费序唯一 deviation，理由见上面收集处那段）：各面先按自己的根点名，
        // 这三行只收「别的面够不到、由运行期物化带出来」的对象。
        val repBytes = bucket(
            PhaseSurface.STATEMENTS, "ArgInterner 代表树（末尾逐条 consume：共享实参子树实例）",
            "${internerRepRoots.size} 棵", roots = internerRepRoots.toTypedArray(),
            note = "代表树是运行期物化的反挂边总入口；放末尾后这里只剩「语句实参树自己独有的对象」，" +
                "FunctionInfo/registry/变量池那些已经被各自的面点走",
        )
        val matBytes = bucket(
            PhaseSurface.STATEMENTS,
            "已物化语句条目（末尾逐条 consume：Statement 壳 + 实参子树 + 带出的表达式树 + 原文驻留）",
            "$matCount 条",
            roots = stmtRoots.toTypedArray(),
            note = "归属口径：各面先按自己的根点名，剩下这些只被语句带出来的对象才落这里",
        )
        val exprBytes = bucket(
            PhaseSurface.STATEMENTS, "段2 已解码表达式条目（末尾逐条 consume）", "${exprRoots.size} 条",
            roots = exprRoots.toTypedArray(),
        )
        val tailClasses = runCatching {
            walker.classTop(WALK_CLASS_TOP).filter {
                it.first.endsWith(".FunctionInfo") || it.first.endsWith(".FuncMeta") ||
                    it.first.endsWith(".FormStringTerm") || it.first.endsWith(".FormPart") ||
                    it.first.endsWith(".CallArg") || it.first.endsWith(".VarDef")
            }.joinToString(" / ") { "${it.first.substringAfterLast('.')} x${it.second}=${it.third}B" }
        }.getOrDefault("逐类账单不可读")
        ledger.add(
            PhaseSurface.STATEMENTS, "整趟遍历里几个产品类的累计账单（只作构成说明，不重复入账）", "", 0L,
            "枚举-遍历模型(不入账)", counted = false,
            note = "累计账单：$tailClasses。这些类的字节是**整趟遍历**的累计（身份去重后按归属面分散在各面里），" +
                "不是末尾三行独占的量；总额仍以「live 总额 - 入账合计」的未归类行兜底",
        )
        ledger.add(
            PhaseSurface.STATEMENTS, "物化条数交叉列（边际尺反推，不入账）",
            "真数 $matCount 条 / 尺算 ${PhaseLadderMath.stmtCountFromBytes(matBytes + exprBytes, marginalStmtBytes)} 条",
            0L, "枚举-公式(不入账)", counted = false,
            note = "边际尺 = 每条 $marginalStmtBytes B（上一路 `HeapRootAttributionProbe` 等距抽样实测）；" +
                "两列背离只说明本相位物化的语句与抽样总体分布不同（例如集中在重负载函数里），不是尺坏了",
        )

        // ================= 面 13：渲染侧缓存（classpath 外，声明不入账） =================
        val renderClasses = runCatching {
            walker.classTop(WALK_CLASS_TOP).filter {
                it.first.startsWith("com.eratw.port.render") || it.first.contains("glyph", ignoreCase = true) ||
                    it.first.startsWith("java.awt.image") || it.first.startsWith("sun.java2d")
            }
        }.getOrDefault(emptyList())
        ledger.add(
            PhaseSurface.RENDER, ":render 字形缓存 / 合成画布（**本装置量不到**）",
            if (renderClasses.isEmpty()) "遍历内 0 个渲染/栅格类"
            else renderClasses.joinToString(" / ") { "${it.first.substringAfterLast('.')} x${it.second}" },
            0L, "枚举-公式(不入账)", counted = false,
            note = "依赖方向是 render 吃 core-vm、uipreview 吃 core-vm，两者都不在 :core-vm 测试源集的 classpath 上 ⇒ " +
                "桌面 280 MiB 里的字形/合成那部分是**另一个装置**的账；本相位的 live 总额不含它，" +
                "跨装置的差额只按 jcmd 行与桌面既有取证并排看，不许说成实测",
        )

        // ================= 判定与账单尾巴 =================
        val counted = ledger.countedSum()
        val unclassified = ledger.unclassified()
        val verdict = ledger.verdict()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val topText = runCatching {
            walker.classTop(8).joinToString(" / ") { "${it.first.substringAfterLast('.')} x${it.second}=${it.third}B" }
        }.getOrDefault("逐类账单不可读")
        walker.releaseVisited()
        ledger.add(
            "对账(不入账)", "遍历去重对象数 / 逐类 top8（枚举侧账单，访问集已释放）",
            "${walker.objects} 个", 0L, "枚举-遍历模型(不入账)", counted = false, note = topText,
        )
        return PhaseReading(
            label = label, liveBytes = liveBase, surfaces = ledger.surfaceTotals(),
            counted = counted, unclassified = unclassified, verdict = verdict, ledger = ledger,
            counters = counters, walkMs = ms,
        )
    }

    /** Sprite 池（运行表 + CSV 资源表）引用到的全部缓冲实例，按实例去重。 */
    private fun collectSpriteBuffers(spritePool: SpritePool?): List<GraphicsBuffer> {
        if (spritePool == null) return emptyList()
        val out = ArrayList<GraphicsBuffer>()
        for (table in listOf("sprites", "resourceSprites")) {
            val m = field(spritePool, table) as? Map<*, *> ?: continue
            for (s in m.values) {
                (field(s, "buffer") as? GraphicsBuffer)?.let { if (!out.contains(it)) out.add(it) }
                val frames = field(s, "frames") as? List<*>
                for (fr in frames.orEmpty()) {
                    (field(fr, "buffer") as? GraphicsBuffer)?.let { if (!out.contains(it)) out.add(it) }
                }
            }
        }
        return out
    }
}
