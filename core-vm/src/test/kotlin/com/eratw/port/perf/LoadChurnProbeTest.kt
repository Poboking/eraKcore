package com.eratw.port.perf

import com.eratw.port.core.code.Program
import com.eratw.port.core.data.csv.GameData
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.lexer.ErbLexer
import com.eratw.port.core.lexer.LexerConfig
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.LoadStageMark
import com.eratw.port.core.loader.LoadStages
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.SystemVariableBootstrap
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.DimVarSizes
import com.eratw.port.core.vars.VarConfig
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.vars.withPureStaticDimFolders
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * **生产装载链**（`ProgramLoader.loadMapped`，含 `program.stmt.idx` 侧车）的逐段分配/常驻读数。
 *
 * ## 为什么不能直接复用 `ErbcStreamAllocProfileTest`
 *
 * 那个装置调的是 `ErbcStreamDecoder.decode(m) { … }` —— **不带侧车**。于是段 3 会回落到
 * 「逐条反序列化 171 万条语句只为拿到记录偏移」的索引趟，读数里出现 **943.9 MiB / 段3 语句记录**
 * 这一坨。真机不走那条路：`ProgramLoader.decodeMapped` 先读 `program.stmt.idx`，命中可信
 * 侧车后索引趟退化成「1/8 条带抽查」。**拿那个读数当生产画像是错的**（会让人去优化一个
 * 生产上根本不发生的分配），本装置走的是宿主真正调用的那个入口。
 *
 * ## 口径
 *
 * - **分配**：`ThreadMXBean.getThreadAllocatedBytes` 增量（单调，与 GC 时机无关）；
 * - **常驻**：`HeapMeter.settled()` 增量（多轮 GC 取最小 used 的差），打开 `retained` 时才读
 *   （每次读数连做 5 轮 GC，会把耗时搅成噪声）；
 * - 段边界由宿主侧 mark 给，段名与 `load.*` 日志同一套。
 *
 * 用法：`ERATW_ERBC=<program.erbc 或所在目录> ./gradlew :core-vm:test --tests "*LoadChurnProbeTest*"`
 * 未设环境变量时跳过（不污染闸门）。
 */
class LoadChurnProbeTest {

    private class Meter private constructor(
        private val bean: com.sun.management.ThreadMXBean,
        private val tid: Long,
    ) {
        fun read(): Long = bean.getThreadAllocatedBytes(tid)

        /** 另一条线程的累计分配字节（线程已结束时为 -1，调用方要在 join 之前读）。 */
        fun readOf(id: Long): Long = bean.getThreadAllocatedBytes(id)

        companion object {
            fun open(): Meter? = runCatching {
                val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
                    ?: return null
                if (!bean.isThreadAllocatedMemorySupported) return null
                bean.isThreadAllocatedMemoryEnabled = true
                val tid = Thread.currentThread().id
                bean.getThreadAllocatedBytes(tid)
                Meter(bean, tid)
            }.getOrNull()
        }
    }

    /**
     * 整堆 `used` 的穷采样器（每 2 ms 一次，取窗口最大值）。
     *
     * 为什么不用 `HeapMeter.peak()`：G1 的 pool peak 记的是「上一次 GC 时该池的占用」，
     * 短于一个 GC 周期的尖峰（侧车解析正好是这种）会被它抹平；装载峰值要的是**窗口内的
     * 实际高水位**，与真机 `load.peak` 同源（`Runtime.totalMemory() - freeMemory()`）。
     */
    private class UsedSampler {
        @Volatile private var stop = false
        @Volatile private var max = 0L
        private val rt = Runtime.getRuntime()
        private val th = Thread {
            while (!stop) {
                val used = rt.totalMemory() - rt.freeMemory()
                if (used > max) max = used
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply { name = "load-churn-sampler"; isDaemon = true }

        fun start(): UsedSampler = apply { th.start() }

        /** 收口并返回窗口内最大 used（字节）。 */
        fun finish(): Long {
            stop = true
            th.join(2_000)
            return max
        }
    }

    /** 侧车文件（与 `program.erbc` 同目录的 `program.meta`）；缺失返回 null。 */
    private fun realMetaArtifact(): Path? {
        val dir = System.getenv("ERATW_ERBC_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: System.getenv("ERATW_ERBC")?.takeIf { it.isNotBlank() }?.let {
                val p = Path.of(it)
                if (Files.isDirectory(p)) p else p.parent
            } ?: return null
        if (!Files.isDirectory(dir)) return null
        return dir.resolve("program.meta").takeIf { Files.isRegularFile(it) }
    }

    private fun realArtifact(): Path? {
        val raw = System.getenv("ERATW_ERBC")?.takeIf { it.isNotBlank() } ?: return null
        val p = Path.of(raw)
        val f = if (Files.isDirectory(p)) p.resolve("program.erbc") else p
        return f.takeIf { Files.isRegularFile(it) }
    }

    private fun mib(bytes: Long): String {
        val neg = bytes < 0
        val abs = if (neg) -bytes else bytes
        val tenths = (abs * 10 + 524_288) / 1_048_576
        return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10}"
    }

    @Test
    fun `生产装载链：逐段分配与常驻`() {
        val path = realArtifact()
        assumeTrue(path != null, "ERATW_ERBC 未指向可读的 program.erbc：跳过")
        val meter = Meter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数：跳过")
        val m = meter!!
        val retained = System.getenv("ERATW_ALLOC_RETAINED") != null

        // 预热一次（JIT + 类加载的分配不该算进被测段），随后 GC 归零。
        ProgramLoader.loadMapped(path!!).releaseMmap()
        System.gc()

        val rows = ArrayList<Triple<String, Long, Long>>() // name, churn, retained
        var prevA = m.read()
        var prevR = if (retained) HeapMeter.settled() else 0L
        var prevT = System.nanoTime()
        val a0 = prevA
        val t0 = prevT

        val program: Program = ProgramLoader.loadMapped(
            path,
            LoadStageMark { stage ->
                val a = m.read()
                val t = System.nanoTime()
                val r = if (retained) HeapMeter.settled() else 0L
                rows.add(Triple(stage, a - prevA, r - prevR))
                println(
                    "[load-churn]   ${stage.padEnd(34)} ${mib(a - prevA).padStart(9)} MiB" +
                        " ${(if (retained) mib(r - prevR) else "-").padStart(9)} MiB" +
                        " ${((t - prevT) / 1_000_000).toString().padStart(6)} ms",
                )
                prevA = a
                prevR = r
                prevT = t
            },
        )
        val totalChurn = m.read() - a0
        val totalMs = (System.nanoTime() - t0) / 1_000_000

        println("[load-churn] 合计：分配 ${mib(totalChurn)} MiB / ${totalMs} ms / 产物 ${mib(Files.size(path))} MiB")
        val sum = rows.sumOf { it.second }
        println("[load-churn] 逐段之和 ${mib(sum)} MiB（差 ${mib(totalChurn - sum)} MiB）")
        println(
            "[load-churn] 前三名：" + rows.sortedByDescending { it.second }.take(3)
                .joinToString(" > ") { "${it.first} ${mib(it.second)}" },
        )

        // 装载完成后 Program 的形状（对照用）
        println(
            "[load-churn] 装载后：指令 ${program.instructionCount} / 语句 ${program.statementCount}" +
                " / 表达式 ${program.exprCount} / 函数 ${program.functions.size}" +
                " / 串 ${program.stringCount} / 常量 ${program.constantCount}" +
                " / code 常驻 ${mib(program.codeResidentBytes)} MiB / 惰性池 ${program.holdsLazyAnyPool}",
        )
        assertTrue(totalChurn > 0, "分配量读数不为 0")
        assertTrue(rows.isNotEmpty(), "至少要有一个段边界")

        program.releaseMmap()
    }

    /**
     * `program.meta` 单独一段的分配 / 常驻 / 峰值（真 49 MB 侧车）。
     *
     * 这一段在真机上是**并行线程**（`eratw-meta`，GameHost.kt:934）跑的，它的常驻正落在
     * `program.erbc` 解码的高水位之上，所以它的峰值就是装载峰值的一部分。
     * 读数口径与本文件上方 `生产装载链` 用例一致：分配 = 线程累计分配增量；
     * 常驻 = 多轮 GC 取最小 used 的差；峰值 = 2 ms 穷采样的窗口最大值。
     *
     * 用法：`ERATW_ERBC_DIR=<含 program.meta 的目录> ./gradlew --configure-on-demand :core-vm:test --tests "*LoadChurnProbeTest*"`
     */
    @Test
    fun `侧车解析：meta 段的分配与常驻与峰值`() {
        val meta = realMetaArtifact()
        assumeTrue(meta != null, "ERATW_ERBC / ERATW_ERBC_DIR 未指向含 program.meta 的目录：跳过")
        val meter = Meter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数：跳过")
        val m = meter!!
        val metaPath = meta!!
        val fileBytes = Files.size(metaPath)

        // 预热一次（JIT + 类加载的分配不计入被测段），随后 GC 归零。
        BootMetadata.read(metaPath)
        System.gc()

        val sampler = UsedSampler().start()
        val before = HeapMeter.settled()
        val a0 = m.read()
        val t0 = System.nanoTime()
        val model = BootMetadata.read(metaPath)
        val ms = (System.nanoTime() - t0) / 1_000_000L
        val allocated = m.read() - a0
        val retained = HeapMeter.settled() - before   // 模型仍在局部引用上 ⇒ 含模型常驻
        val peak = sampler.finish()

        println("[meta-churn] 侧车=${metaPath.fileName} ${mib(fileBytes)} MiB / 解析 ${ms} ms")
        println("[meta-churn]   分配 ${mib(allocated)} MiB / 常驻(含模型) ${mib(retained)} MiB / 窗口峰值 ${mib(peak)} MiB")
        println("[meta-churn]   模型：${model}")
        // 丢开模型再读一次常驻：解析期的临时量（记录表）释放后应当塌下去
        val keepAlive = model.functions.size
        val dropped = HeapMeter.settled()
        println("[meta-churn]   丢开模型后常驻 ${mib(dropped - before)} MiB（相对解析结束的回落 = 临时量）")

        assertTrue(allocated > 0, "侧车解析的分配读数不为 0")
        assertTrue(keepAlive > 0, "侧车必须解出函数表")
    }

    /**
     * `侧车解析：meta 段的分配与常驻与峰值` 的 **ASCII 名多轮版**（mem4）。
     *
     * 口径与上面那条既有用例**逐字相同**（线程累计分配增量 / `HeapMeter.settled()` 差 /
     * 2 ms 穷采样窗口峰值 / 预热一次后 GC 归零），只是把「一次」换成「[ERATW_REPS] 轮取中位」，
     * 并且方法名不带中文 —— `--tests` 过滤器在 MSYS 下传中文会坏，窄测时需要能点名这一条。
     *
     * 用法：`ERATW_ERBC_DIR=<含 program.meta 的目录> ERATW_REPS=3 ./gradlew --configure-on-demand
     * :core-vm:test --tests "*LoadChurnProbeTest.metaSidecarReps*" -i`
     */
    @Test
    fun metaSidecarReps() {
        val meta = realMetaArtifact()
        assumeTrue(meta != null, "ERATW_ERBC / ERATW_ERBC_DIR 未指向含 program.meta 的目录：跳过")
        val meter = Meter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数：跳过")
        val m = meter!!
        val metaPath = meta!!
        val fileBytes = Files.size(metaPath)
        val reps = System.getenv("ERATW_REPS")?.toIntOrNull()?.coerceIn(1, 9) ?: 3

        BootMetadata.read(metaPath)
        System.gc()

        val allocs = ArrayList<Long>()
        val peaks = ArrayList<Long>()
        val times = ArrayList<Long>()
        var retained = 0L
        var shape = ""
        for (r in 1..reps) {
            val sampler = UsedSampler().start()
            val before = HeapMeter.settled()
            val a0 = m.read()
            val t0 = System.nanoTime()
            val model = BootMetadata.read(metaPath)
            val ms = (System.nanoTime() - t0) / 1_000_000L
            val allocated = m.read() - a0
            retained = HeapMeter.settled() - before
            val peak = sampler.finish()
            allocs.add(allocated)
            peaks.add(peak)
            times.add(ms)
            shape = model.toString()
            println("[meta-churn] 第 $r 轮：分配 ${mib(allocated)} MiB / 常驻(含模型) ${mib(retained)} MiB / 窗口峰值 ${mib(peak)} MiB / ${ms} ms")
        }
        println(
            "[meta-churn] 中位（$reps 轮）：侧车=${metaPath.fileName} ${mib(fileBytes)} MiB " +
                "分配 ${mib(median(allocs))} MiB / 常驻 ${mib(retained)} MiB / 峰值 ${mib(median(peaks))} MiB / " +
                "${median(times)} ms",
        )
        println("[meta-churn]   模型：$shape")
        assertTrue(allocs.all { it > 0 }, "侧车解析的分配读数不为 0")
    }

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    /**
     * 侧车解析的**并发**是不是元凶：三种起跑次序的装载窗口峰值对照。
     *
     * 三臂做的活完全相同（`program.erbc` 惰性解码 + `program.meta` 解析，两份结果最后都要
     * 同时驻留 —— 装配期本来就要同时握住它们），只有**次序**不同：
     * - `并行` = 真机现状（`GameHost.kt:934` 的 `eratw-meta` 与解码同时在跑）；
     * - `meta先` = 侧车先解完（解完的临时量在解码起跑前已被催一次 GC 清掉），再解 program；
     * - `meta后` = meta 等解码跑完再起。
     *
     * 读数：窗口峰值（2 ms 穷采样的整堆 used 最大值）、侧车线程自己的累计分配与耗时。
     * 各臂之间把两份结果彻底丢开 + GC，避免上一臂残留抬高下一臂。
     */
    @Test
    fun `侧车与程序解码：并行与串行次序的窗口峰值对照`() {
        val erbc = realArtifact()
        val meta = realMetaArtifact()
        assumeTrue(erbc != null && meta != null, "缺 program.erbc 或 program.meta：跳过")
        val meter = Meter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数：跳过")
        val m = meter!!
        val programPath = erbc!!
        val metaPath = meta!!
        val fileBytes = Files.size(metaPath)
        // 结果钉在字段上：采样窗口内两份结果都必须「可达」，否则 GC 会提前把 program 收走、
        // 峰值读数变成假低（本装置测的就是两份同时驻留的高水位）。
        val sink = ArrayList<Any>()

        /** 一臂（[mode] = `parallel` / `meta-first` / `meta-last`）：返回 (标签, 窗口峰值字节)。 */
        fun arm(mode: String): Pair<String, Long> {
            val label = when (mode) {
                "parallel" -> "并行"
                "meta-first" -> "meta先"
                else -> "meta后"
            }
            var metaAlloc = -1L
            var metaMs = -1L
            var programMs = -1L
            val metaThread = Thread {
                val own = Meter.open()
                val t = System.nanoTime()
                val start = own?.read() ?: 0L
                val parsed = BootMetadata.read(metaPath)
                metaMs = (System.nanoTime() - t) / 1_000_000L
                metaAlloc = (own?.read() ?: 0L) - start
                synchronized(sink) { sink.add(parsed) }
            }.apply { name = if (mode == "parallel") "eratw-meta" else "eratw-meta-$mode" }

            val sampler = UsedSampler().start()
            val a0 = m.read()
            when (mode) {
                "parallel" -> {
                    metaThread.start()
                    val t0 = System.nanoTime()
                    val program = ProgramLoader.loadMapped(programPath)
                    programMs = (System.nanoTime() - t0) / 1_000_000L
                    synchronized(sink) { sink.add(program) }
                    metaThread.join()
                }
                "meta-first" -> {
                    metaThread.start()
                    metaThread.join()
                    // 侧车的临时量先清掉，再起解码（这就是「串行化能不能把临时量挪出峰值」的判据）
                    System.gc()
                    val t0 = System.nanoTime()
                    val program = ProgramLoader.loadMapped(programPath)
                    programMs = (System.nanoTime() - t0) / 1_000_000L
                    synchronized(sink) { sink.add(program) }
                }
                else -> {
                    val t0 = System.nanoTime()
                    val program = ProgramLoader.loadMapped(programPath)
                    programMs = (System.nanoTime() - t0) / 1_000_000L
                    synchronized(sink) { sink.add(program) }
                    metaThread.start()
                    metaThread.join()
                }
            }
            val peak = sampler.finish()
            println(
                "[meta-churn] ${label}臂：解码 ${programMs} ms / 侧车 ${metaMs} ms / 侧车线程分配 ${mib(metaAlloc)} MiB" +
                    " / 窗口峰值 ${mib(peak)} MiB（主线程分配 ${mib(m.read() - a0)} MiB，驻留 ${sink.size} 份）",
            )
            return label to peak
        }

        // 预热（三臂共用同一段 JIT 路径；第一臂的类加载分配不该单边抬高）
        BootMetadata.read(metaPath)
        ProgramLoader.loadMapped(programPath).releaseMmap()
        System.gc()

        val rows = ArrayList<Pair<String, Long>>()
        for (mode in listOf("parallel", "meta-first", "meta-last")) {
            val row = arm(mode)
            rows.add(row)
            // 臂间彻底归零：丢掉两份结果 + GC（否则下一臂的基线被上一臂的常驻抬高）
            sink.clear()
            System.gc()
        }
        val best = rows.minByOrNull { it.second }!!
        val parallel = rows.first { it.first == "并行" }.second
        println("[meta-churn] 侧车文件 ${mib(fileBytes)} MiB；三臂窗口峰值：" + rows.joinToString(" / ") { "${it.first} ${mib(it.second)} MiB" })
        println(
            "[meta-churn] 次序判据：最省的是 ${best.first}（${mib(best.second)} MiB），" +
                "比并行档挪走 ${mib(parallel - best.second)} MiB（正数=串行化有意义，负数=并行本来就更省）",
        )
        assertTrue(rows.size == 3 && rows.all { it.second > 0 }, "三臂都要有峰值读数")
    }

    // ==================================================================
    // vars 面（mem6）：装配期分配归因 + 常驻拆分 + 热路径窄基准
    // ==================================================================

    private fun gameRootDir(): Path? {
        val raw = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() } ?: return null
        val p = Path.of(raw)
        return p.takeIf { Files.isDirectory(it) }
    }

    /**
     * 标定用的「必须逃逸」收口：把新建对象挂进一个满 64 就清的列表。
     * 不加这一层，JIT 的标量替换会把 `repeat { ErbLexer() }` 整个消掉，读数变 0。
     */
    private val keepSink = ArrayList<Any>(64)

    private fun keepAlloc(o: Any) {
        keepSink.add(o)
        if (keepSink.size == 64) keepSink.clear()
    }

    /**
     * **vars 面装配归因**（mem6 第一阶段装置）：`session.store` / `session.dims` /
     * `session.privatedims` / `session.keywords` 四窗的**线程内分配拆分**与**常驻拆分**。
     *
     * ## 为什么要新加一趟而不看 §2 的逐窗表
     *
     * `HeapCompositionProbeTest` 的逐窗列是**墙钟进程口径**（所有线程分配计数之和）：
     * 单窗能把别的装载工兵线程的分配归进来，展布 ±40%，只能拿合计对账。四窗里
     * `session.dims` 32.1 MiB、`session.keywords` 9.4 MiB 这两坨**全是主线程单线程**的活，
     * 本装置把每一步（剥注释 / 解析 / 注册 / 折叠表 / ERD 挂表）用
     * `getThreadAllocatedBytes(main)` 逐字节量开，再对每个阶段补一次
     * `HeapMeter.settled()` 常驻读数 —— 装完就 GC，所以「分配」与「驻留」是两笔独立账。
     *
     * ## 与生产装配的关系（口径差异逐条写明）
     *
     * 走的是 `GameSession` 的**同一串调用**（CSV 总装 → 建池 → 折叠面 → `install` →
     * 私有 `declareUserVar` → `UserKeywordTables.install`），只有三处有意省略，
     * 都不落在被量的四窗里：
     * - `installLexerMacros`（宏表投影 ≈22 条，`session.store` 尾部）；
     * - `mouseStateOf(...).warnRecorder` 赋值与 `EmSaveSets.parse` / `SaveWiring`（`session.save`）；
     * - `Config.UseERD` / `CheckDuplicateIdentifier` 直接按语料实值 true 传（不读 emuera.config）。
     *
     * 另有一条**自证**：`install` 的不动点重试环在 `proc/SystemVariableBootstrap.kt`（非本路面），
     * 本装置带一份逐行等价的副本 [replicaInstall] 用来分步计量，并在末尾断言
     * 「副本的 declared / failures 与真实 [SystemVariableBootstrap.install] 逐项相同」——
     * 副本一旦与产品分叉就当场报红，归因数字不会静默失真。
     *
     * 用法：`ERATW_ERBC_DIR=<含 program.meta 的目录> ERATW_GAMEDIR=<游戏根> ERATW_PERF_ROUNDS=3 \`
     * `./gradlew --configure-on-demand :core-vm:test --tests "*LoadChurnProbe*" -i`
     */
    @Test
    fun `会话装配 vars 面：四窗分配与常驻拆分`() {
        val metaPath = realMetaArtifact()
        val root = gameRootDir()
        assumeTrue(metaPath != null && root != null, "缺 program.meta 或 ERATW_GAMEDIR：跳过")
        val meter = Meter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数：跳过")
        val m = meter!!
        val gameRoot = root!!

        /** 一步的分配读数（主线程口径）+ 打印。 */
        fun step(name: String, body: () -> Unit): Long {
            val a = m.read()
            val t = System.nanoTime()
            body()
            val d = m.read() - a
            println("[vars-churn] ${name.padEnd(52)} ${mib(d).padStart(8)} MiB ${((System.nanoTime() - t) / 1_000_000L).toString().padStart(6)} ms")
            return d
        }

        // ---- 侧车（前置，不属于四窗）----
        val t0 = HeapMeter.settled()
        var metadata: BootMetadata? = null
        step("前置 meta.read（侧车解析，给四窗喂声明）") { metadata = BootMetadata.read(metaPath!!) }
        val md = metadata!!
        val afterMeta = HeapMeter.settled()
        println("[vars-churn]   常驻=侧车驻留 ${mib(afterMeta - t0)} MiB（全局声明 ${md.globalDims.size} 条 / 私有 ${md.privateDims.size} 条）")

        // ---- CSV 总装（= session.csv 窗，前置，不计入四窗合计）----
        var loadedData: GameData? = null
        step("前置 GameDataLoader.load（= session.csv 窗）") {
            loadedData = GameDataLoader.load(gameRoot.resolve("CSV"))
        }
        val data = loadedData!!
        val registry = data.registry

        // ---- 1) session.store：建池 + 模板 + 角色 + STR 名字回填 ----
        val r0 = HeapMeter.settled()
        var storeRef: VariableStore? = null
        val churnStore = step("窗1 session.store：VariableStore 构造 + 模板 + 角色") {
            val s = VariableStore(registry, VarConfig(systemSaveInBinary = true))
            for (t in data.templates) s.registerTemplate(t)
            s.loadItemPrice(data.itemPrices)
            s.reloadStrNames()
            if (s.template(0L) != null) s.addCharacter(0L) else repeat(4) { s.characters.addBare() }
            storeRef = s
        }
        val store = storeRef!!
        val r1 = HeapMeter.settled()
        println("[vars-churn]   常驻=池建完 +${mib(r1 - r0)} MiB；声明口径 estimateGlobalIntBytes=${mib(store.estimateGlobalIntBytes())} MiB" +
            "（2 维 ${store.registry.sizes.int2D.contentDeepToString()} / 3 维 ${store.registry.sizes.int3D.contentDeepToString()}）")

        // ---- 2) session.tables：折叠面（ERD 快照 + 内置关键字表 + 尺寸表）----
        val systemNames = registry.all().mapTo(HashSet()) { it.name.uppercase() }
        var erdFold: Map<String, Map<String, Int>> = emptyMap()
        step("tables.UserKeywordTables.foldSnapshot（ERB 树全扫 + 表文件读）") {
            erdFold = UserKeywordTables.foldSnapshot(gameRoot, systemNames)
        }
        var varSizes: Map<String, LongArray> = emptyMap()
        var kwTables: Map<String, Map<String, Int>> = emptyMap()
        step("tables.DimVarSizes.ofRegistry + systemKeywordTables") {
            varSizes = DimVarSizes.ofRegistry(registry)
            kwTables = erdFold + registry.systemKeywordTables()
        }
        var cfg: DimParseConfig = md.dimParseConfig
        step("tables.withPureStaticDimFolders（挂折叠回调）") {
            cfg = md.dimParseConfig.copy(systemSaveInBinary = true)
                .withPureStaticDimFolders(varSizes, kwTables, consoleWidth = 199)
        }

        // ---- 3) session.dims：真实 install（与 GameSession 同一入口）----
        val declarations = md.globalDims.map { SystemVariableBootstrap.RawDeclaration(it.raw, it.file, it.line) }
        var real: SystemVariableBootstrap.Result? = null
        val churnDims = step("dims.SystemVariableBootstrap.install（真实入口）") {
            real = SystemVariableBootstrap.install(store, declarations, cfg)
        }
        val rr = real!!
        val r2 = HeapMeter.settled()
        println("[vars-churn]   declared=${rr.declared} failures=${rr.failures.size} " +
            "constants=${rr.constants.size} strConstants=${rr.strConstants.size}" +
            "；常驻=全局 #DIM 之后 +${mib(r2 - r1)} MiB（累计 +${mib(r2 - r0)}）")

        // ---- 3b) 副本分步挪到全部常驻读数之后（r564 实测教训：副本那份额外的池会把
        //      privatedims 的常驻读数抬高 ~20 MiB —— 它必须在 r3/r4 之后才建）。----

        // ---- 4) session.privatedims ----
        var privOk = 0
        val churnPriv = step("privatedims.declareUserVar × ${md.privateDims.size}") {
            for (p in md.privateDims) {
                runCatching { store.declareUserVar(p.spec, p.functionKey) }.onSuccess { privOk++ }
            }
        }
        val r3 = HeapMeter.settled()
        println("[vars-churn]   成功 $privOk 条；常驻=私有声明之后 +${mib(r3 - r2)} MiB")

        // ---- 5) session.keywords ----
        var discoverFiles: Map<String, List<Path>> = emptyMap()
        step("keywords.discover（ERB 递归 + CSV 顶层，第二趟）") {
            discoverFiles = UserKeywordTables.discover(gameRoot)
        }
        val warnings = ArrayList<String>()
        val churnKeys = step("keywords.install（逐变量挂 ERD 表）") {
            UserKeywordTables.install(store, gameRoot, warnings, checkDuplicateIdentifier = true)
        }
        val r4 = HeapMeter.settled()
        println("[vars-churn]   ERD 组 ${discoverFiles.size} / 告警 ${warnings.size}；常驻=挂表之后 +${mib(r4 - r3)} MiB")

        // ---- 3b) 同一条声明序列的**分步**副本（只有副本能把 dims 那坨拆到每一步）。
        //      放在所有常驻读数之后：它自己会再建一份池（含系统列），必须不污染上面的账。----
        var store2 = VariableStore(registry, VarConfig(systemSaveInBinary = true))
        for (t in data.templates) store2.registerTemplate(t)
        store2.loadItemPrice(data.itemPrices)
        store2.reloadStrNames()
        val rep = replicaInstall(store2, declarations, cfg, m)
        println("[vars-churn] dims.副本分步：尝试 ${rep.attempts} 次 / 成功 ${rep.declared} / 失败 ${rep.failures}")
        println("[vars-churn]   ├ 剥注释+拆关键字 ${mib(rep.strip)} MiB")
        println("[vars-churn]   ├ DimParser.parse    ${mib(rep.parse)} MiB")
        println("[vars-churn]   ├ declareUserVar     ${mib(rep.declare)} MiB")
        println("[vars-churn]   └ 其余（常量表/迭代器/循环） ${mib(rep.other)} MiB")
        println("[vars-churn]   副本合计 ${mib(rep.total)} MiB vs 真实 ${mib(churnDims)} MiB（差 ${mib(rep.total - churnDims)}）")

        // ---- 诊断：`ErbLexer` 一次构造到底多少字节（dims 窗 parse 那一坨的单价标定）----
        val nLex = declarations.size
        val lexChurn = step("诊断：新建 ErbLexer × $nLex（构造成本标定，非产品路径）") {
            repeat(nLex) { keepAlloc(ErbLexer()) }
        }
        val cfgChurn = step("诊断：新建 LexerConfig × $nLex（同上）") {
            repeat(nLex) { keepAlloc(LexerConfig()) }
        }
        keepSink.clear()
        println("[vars-churn]   ErbLexer 单次构造 = ${(lexChurn / nLex)} B；LexerConfig 单次 = ${(cfgChurn / nLex)} B" +
            "（parse 那一步 ${mib(rep.parse)} MiB / ${nLex} 条 = ${rep.parse / nLex} B 每条）")

        println("[vars-churn] 四窗分配合计（store+dims+privatedims+keywords，主线程口径）= " +
            "${mib(churnStore + churnDims + churnPriv + churnKeys)} MiB" +
            "（store ${mib(churnStore)} / dims ${mib(churnDims)} / privatedims ${mib(churnPriv)} / keywords ${mib(churnKeys)}）")
        println("[vars-churn] 四窗常驻合计（相对建池前）= ${mib(r4 - r0)} MiB" +
            "（建池 ${mib(r1 - r0)} / 全局 #DIM ${mib(r2 - r1)} / 私有 ${mib(r3 - r2)} / 挂表 ${mib(r4 - r3)}）")
        println("[vars-churn]   形态计数：字符串池 ${store.pool.distinctCount} 条 / 用户变量 ${store.allUserVars().size} 实例 /" +
            " 私有 ${store.allPrivateUserVars().size} 实例 / 全局整数列声明 ${mib(store.estimateGlobalIntBytes())} MiB")

        // 自证：副本与产品必须做同一份活，否则上面的分步归因不成立。
        assertTrue(rep.declared == rr.declared && rep.failures == rr.failures.size,
            "install 副本与产品分叉：副本 declared=${rep.declared}/failures=${rep.failures} vs " +
                "产品 declared=${rr.declared}/failures=${rr.failures}")
        assertTrue(churnStore > 0 && churnDims > 0 && churnPriv > 0 && churnKeys > 0, "四窗读数必须为正")

        // 读数到手：把两份池丢开，别把常驻留给同进程的下一个用例。
        store2.declareUserVar(DimSpec("MEM6_TAIL", false, intArrayOf(1)))
        System.gc()
    }

    /**
     * `SystemVariableBootstrap.install` 的**逐行等价副本**（只多插了分配采样）。
     * 与产品的等价性由调用点的 `declared` / `failures` 断言兜住。
     */
    private class ReplicaReads(
        val attempts: Int,
        val declared: Int,
        val failures: Int,
        val strip: Long,
        val parse: Long,
        val declare: Long,
        val other: Long,
        val total: Long,
    )

    private fun replicaInstall(
        store: VariableStore,
        declarations: List<SystemVariableBootstrap.RawDeclaration>,
        config: DimParseConfig,
        m: Meter,
    ): ReplicaReads {
        val constants = LinkedHashMap<String, Long>()
        constants.putAll(config.intConstants)
        val strConstants = LinkedHashMap<String, String>()
        strConstants.putAll(config.strConstants)
        val pending = ArrayList<SystemVariableBootstrap.RawDeclaration>()
        declarations.forEach { pending.add(it) }
        val t0 = m.read()
        var declared = 0
        var failures = 0
        var attempts = 0
        var strip = 0L
        var parse = 0L
        var declare = 0L
        var progress = true
        while (pending.isNotEmpty() && progress) {
            progress = false
            val roundConfig = config.copy(intConstants = constants, strConstants = strConstants)
            val it = pending.iterator()
            while (it.hasNext()) {
                val d = it.next()
                var a = m.read()
                val split = SystemVariableBootstrap.splitDeclaration(SystemVariableBootstrap.stripComment(d.raw))
                strip += m.read() - a
                if (split == null) {
                    it.remove()
                    failures++
                    continue
                }
                attempts++
                a = m.read()
                val spec = try {
                    DimParser.parse(split.first, split.second, false, roundConfig)
                } catch (e: CodeEE) {
                    parse += m.read() - a
                    val message = e.message ?: "解析失败"
                    if (SystemVariableBootstrap.isUnresolvedConstant(message)) continue
                    it.remove()
                    failures++
                    continue
                }
                parse += m.read() - a
                if (spec.isRef) {
                    it.remove()
                    failures++
                    continue
                }
                a = m.read()
                try {
                    store.declareUserVar(spec)
                } catch (e: CodeEE) {
                    declare += m.read() - a
                    it.remove()
                    failures++
                    continue
                }
                declare += m.read() - a
                it.remove()
                declared++
                progress = true
                // rememberConstant 的等价实现（产品侧 private，本副本只测分配、不测清单）。
                if (spec.isConst && !spec.isRef && spec.dims.size == 1 && spec.totalSize == 1) {
                    val key = spec.name.uppercase()
                    if (spec.isStr) spec.defaultsStr?.firstOrNull()?.let { strConstants[key] = it }
                        else spec.defaultsInt?.firstOrNull()?.let { constants[key] = it }
                }
            }
        }
        failures += pending.size
        val totalBytes = m.read() - t0
        val other = totalBytes - strip - parse - declare
        return ReplicaReads(attempts, declared, failures, strip, parse, declare, other, totalBytes)
    }

    /**
     * **vars 面热路径窄基准**（mem6 出口判据 #3）：改前/改后各跑一遍对拍。
     *
     * 只量「一次变量访问」的那几条路径，整局墙钟在本机已被证伪（`LocalSlotBenchTest`
     * 类 KDoc：跨 213~420 ns/instr），口径同它：同一进程内**交替**跑各腿、取多轮最小值。
     *
     * 六条腿各自钉住一处会被「表示形态/生命周期」改动碰到的热路径：
     * - `G_INT1`：系统 1 维整数列（`FLAG`）——列数组若改成惰性，取列这一步就是唯一新增成本；
     * - `G_INT3`：系统 3 维整数列（`TA`，100×100×100）——**预触后再计**，量的是稳态读写；
     * - `USER`：全局 `#DIM` 整数（`StaticIntUserVar`）；
     * - `PRIV`：函数内私有 `#DIM`（走 `privateVars[funcKey]` + 帧表）；
     * - `KEYWORD`：内置 CSV 关键字表查下标（`keywordToInteger`）；
     * - `STRPOOL`：字符串驻留池 `intern`+`lookup`（`StringTable`）。
     *
     * 不进常规闸：未设 `ERATW_PERF_BENCH` 时直接 return（恒绿、零读数）。
     */
    @Test
    fun `vars 面热路径窄基准`() {
        val rounds = System.getenv("ERATW_PERF_BENCH")?.toIntOrNull() ?: 0
        if (rounds <= 0) return
        val legs = listOf("G_INT1", "G_INT3", "USER", "PRIV", "KEYWORD", "STRPOOL")
        val best = HashMap<String, Long>()
        for (r in 1..rounds) {
            for (leg in legs) {
                val ns = benchLeg(leg)
                best[leg] = minOf(best[leg] ?: Long.MAX_VALUE, ns)
            }
        }
        val sb = StringBuilder("[vars-bench] rounds=$rounds ops=" + OPS)
        for (leg in legs) sb.append(" | $leg=${"%.2f".format(best.getValue(leg).toDouble() / OPS)}ns")
        println(sb.toString())
        assertTrue(legs.all { best.getValue(it) > 0 }, "六条腿都要有读数")
    }

    /** 一腿：新建 store（形态改动会换 store）→ 预触 → 预热 → 计时；返回总纳秒。 */
    private fun benchLeg(leg: String): Long {
        val store = VariableStore()
        store.declareUserVar(DimSpec("MEM6_U", false, intArrayOf(4096)))
        store.declareUserVar(DimSpec("MEM6_P", false, intArrayOf(64), isPrivate = true), "MEM6_FN")
        val idx3 = longArrayOf(1, 2, 3)
        // 预触：让惰性列在这一腿的计时窗外先把数组建出来（量的是稳态读写，不是首触）
        store.writeInt("TA", idx3, 1L)
        store.writeInt("MEM6_U", longArrayOf(7), 1L)
        store.defineLocalLengths("MEM6_FN", VariableStore.LocalLengths(local = 4))
        val idx1 = LongArray(1)
        var sink = 0L
        fun loop(n: Int): Long {
            var acc = 0L
            when (leg) {
                "G_INT1" -> for (i in 0 until n) {
                    idx1[0] = (i and 1023).toLong()
                    acc += store.readInt("FLAG", idx1)
                    store.writeInt("FLAG", idx1, acc)
                }
                "G_INT3" -> for (i in 0 until n) {
                    idx3[2] = (i and 63).toLong()
                    acc += store.readInt("TA", idx3)
                    store.writeInt("TA", idx3, acc)
                }
                "USER" -> for (i in 0 until n) {
                    idx1[0] = (i and 4095).toLong()
                    acc += store.readInt("MEM6_U", idx1)
                    store.writeInt("MEM6_U", idx1, acc)
                }
                "PRIV" -> {
                    store.enterFunction("MEM6_FN")
                    for (i in 0 until n) {
                        idx1[0] = (i and 63).toLong()
                        acc += store.readInt("MEM6_P", idx1)
                        store.writeInt("MEM6_P", idx1, acc)
                    }
                    store.leaveFunction()
                }
                "KEYWORD" -> {
                    val kw = registryKeywordSample(store)
                    for (i in 0 until n) {
                        acc += if (i and 1 == 0) store.keywordToInteger("BASE", 1, kw) else 0L
                    }
                }
                else -> {
                    val a = "MEM6-STR-$leg"
                    val b = "MEM6-STR-OTHER"
                    for (i in 0 until n) {
                        acc += store.pool.intern(if (i and 1 == 0) a else b).toLong()
                        acc += store.pool.lookup((acc and 7).toInt()).length.toLong()
                    }
                }
            }
            return acc
        }
        sink += loop(WARMUP)
        val t = System.nanoTime()
        sink += loop(OPS)
        val ns = System.nanoTime() - t
        assertTrue(sink != Long.MIN_VALUE, "$leg：读数必须被使用")
        return ns
    }

    /** `BASE` 的第 2 个关键字（出厂名字表第 1 项）；取不到就退回第 0 项，保证腿不为空跑。 */
    private fun registryKeywordSample(store: VariableStore): String {
        val kw = "MEM6KW"
        // 基准档不读 CSV ⇒ 关键字现挂一张：keywordIndex 的表名与消费点同源（SystemLayout.keywords）。
        store.registry.csvTable("BASE").setKeyword(kw, 7)
        return kw
    }

    private companion object {
        const val WARMUP = 200_000
        const val OPS = 1_000_000
    }
}
