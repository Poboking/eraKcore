package com.eratw.port.perf

import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.NoSaveSystemOps
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import java.io.File
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.lang.reflect.Array as JavaArray
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 会话**滞留金丝雀**（fix-staticpin 车道，2026-11-01）。
 *
 * ## 它回答的唯一问题
 *
 * 真机（Java 堆上限 512 MiB）上「玩到地图 → 回桌面 → 再点图标进来」必现「启动失败：上一局的
 * 内存还没释放（已用 215 MiB / 上限 512 MiB）」，点重试仍是 `used=216MiB`，不杀进程进不去；
 * Activity 泄漏修完后装载基线 215→184 MiB，仍过不了 `GameHost.STALE_SESSION_BASELINE_MIB = 150`
 * 那道闸，而**根因未明**（BOARD 2026-10-31）。本机 `adb devices` 为空 ⇒ 拿不到设备堆转储，
 * 所以这一刀在桌面 JVM 上判：**一局会话 `close()` 之后它的大对象到底还不还堆；不还的话，
 * 钉住它的那条静态链叫什么名字**。
 *
 * ## 为什么桌面上判得动
 *
 * 跨会话的静态持有面与平台无关 —— 同一份 `core-vm` 字节码、同一个对象图，ART 不会凭空多出
 * 静态引用（它只会**多**出 Activity/View/Compose、别的线程栈、`ThreadLocal` 值那类平台根）。
 * 于是：
 * - 桌面红 ⇒ 真机那 184 MiB 里有一份是 core 侧静态钉住，且本闸直接点名到字段；
 * - 桌面绿 ⇒ 184 MiB **不是**「会话对象图不还堆」，而是 Android 侧的根，需要设备堆转储才能
 *   结清（这同样是有价值的结论，按这条报 + 列出被排除的候选面）。
 *
 * 组长已排除的错误假设本闸不复述其修法：`used=` 读 `Runtime.totalMemory()-freeMemory()` =
 * **Java 堆**，`MappedErbc` 的映射窗口是直接内存、不进这个读数；ART 上没有可用的 `Cleaner`
 * 反射入口（`MappedErbc.kt` 已写明），所以既不拿它当答案也不做「主动 unmap」。
 *
 * ## 三条判据
 *
 * 1. `真产物 标题页后会话 close 后必须可回收 并扫静态钉住链`：真产物 + 真脚本链装一局、走到
 *    标题页第一个等待点、`close()` + VM 线程退出 + 装置自身引用全部置空后，会话根的
 *    `WeakReference` 必须在回收窗口内**全部被清**；同时给字节读数（基线 / 会话在场 /
 *    close+GC 后 —— 桌面版「184 MiB 形状」就是这三行的差）。
 * 2. [scanStaticPins]：`close()` 之后、置空之前，从本进程 classpath 上 `com.eratw` 前缀类的
 *    **静态字段**做广度优先，凡能走到会话根的链逐字段点名。绿的时候也跑 —— 产出是
 *    「静态面确实一个钉住者都没有」的正面证据，而不是「GC 碰巧收了」。
 * 3. `金丝雀自检 钉在静态槽上时必须变红 断开后必须转绿`：往一枚真静态字段（[CanaryPinboard]）
 *    塞一份会话引用，同一套判据**必须**变红且点名到那条链；断开必须转绿。这一条不依赖产物、
 *    恒跑 ⇒ 保证前两行不是「永远绿的闸」。
 *
 * ## 稳定性口径（为什么不会偶尔红）
 *
 * - 回收窗口用**轮询到清为止**，不是「GC 一次就判」；每轮 `System.gc()` + 8 MiB 短命分配
 *   （后者是 `-XX:+DisableExplicitGC` 环境下唯一还能逼出回收的手段），所以显式 GC 被关掉的
 *   机器上判式仍然成立。
 * - 窗口取 60 s：本装置最大活物是标题页相位的会话对象图（真产物桌面 live 是 300 MiB 量级，
 *   对照 `phaseHeapLadder` 的 P1 读数），3g 挡位一次 stop-the-world 全量远小于 1 s ⇒ 60 倍
 *   以上余量；慢机器上变慢的是**装载**（另给 [AWAIT_TITLE_MS] = 5 min），不吃这个窗口。
 * - 可能的合法「误红」只剩三种，且各自单独点名、不伪装成静态泄漏：① 会话根确实被静态钉住
 *   （这正是要抓的）；② VM 线程没退（线程栈自己就是根 ⇒ 报「VM 线程仍活」）；
 *   ③ 标题页没走到等待点（脚本/产物问题 ⇒ 报 `awaitNote`）。
 * - 判式先读数、**后**把装置侧强引用全部置空：[Booted] 的字段是 `var` + [Booted.abandon]，
 *   测试方法侧的局部变量也显式置 null —— 解释器帧的 local slot 在「最后一次使用」之后仍持引用
 *   （`PhaseHeapLadderProbe` 的 metadata 注释是同一条事实），不清就是装置自己造钉住者、
 *   把绿路误判成红。
 * - 产物/游戏根缺失时按仓内既有口径 `assumeTrue` 跳过（skip 在 XML 里可见，不属于「不生成、
 *   不计 skipped」那类语料动态用例）；组长要「不许静默跳过」时设 `ERATW_CANARY_REQUIRE=1`
 *   ⇒ 缺产物直接红。
 *
 * ## 用法
 *
 * ```
 * ERATW_ERBC_DIR=<erbc.v1 目录> ERATW_GAMEDIR=<游戏根> ERATW_CANARY_REQUIRE=1 \
 * ./gradlew --configure-on-demand :core-vm:test --tests "*SessionRetainmentCanaryTest*" cleanTest
 * ```
 *
 * 报告落点 `core-vm/build/session-retainment-canary.md`。方法名故意用「真产物」而不是
 * 「真实产物」，免得被 `gateRealArtifacts` 的 `includeTestsMatching` 顺路圈进那条闸。
 *
 * ## 首跑读数（2026-11-01，Haruki 机 / Temurin 17 / 3g 挡位 / 真产物 erbc.v1）
 *
 * - 真产物档：**绿** —— 会话在场 live=99 MiB（装配 ≈69 + 标题页 ≈30，与 `heapRootAttribution`
 *   的 69 MiB 基准对得上），`close()` + VM 线程退出 + 装置引用置空后 live=17 MiB、
 *   **回吐 82 MiB、残留 0 MiB**，六个会话根的弱引用 1 轮 GC 内全清，静态扫描 0 条钉住链。
 * - 自检档：**红绿都验过** —— `CanaryPinboard.pinned` 一塞进去，六个根全部收不掉、
 *   扫描点名出 6 条链（`静态 …CanaryPinboard.pinned -> ·program / ·store / ·runtime /
 *   ·console / ·inputSink`）；置空后同一批探针立刻全清。⇒ 判据不是空转的。
 * - **绿不等于没问题**：真机 184 MiB 那条仍然没结清。桌面证明的是「`core-vm` 这一侧
 *   close 之后整局会话可回收、没有跨会话静态钉住」，所以那 184 MiB 的持有面在 **app/ART 侧**
 *   （Activity/View/Compose、进程级 `object` 里装的闭包、别的线程栈、`ThreadLocal` 值）——
 *   这四类根本闸看不见，需要设备堆转储。逐条候选与机制写进本路回执的「需组长接线」。
 */
class SessionRetainmentCanaryTest {

    // ------------------------------------------------------------------
    // 判据 3：探测器自检（不依赖产物，恒跑）
    // ------------------------------------------------------------------

    @Test
    fun `金丝雀自检 钉在静态槽上时必须变红 断开后必须转绿`(@TempDir dir: Path) {
        val baseline = HeapMeter.settled()
        var booted: Booted? = bootSyntheticSession(dir)
        // A) 钉住 ⇒ 必须红，而且静态扫描必须点名到钉住它的那枚字段。
        CanaryPinboard.pinned = booted!!.session
        val (prepared, firstScan) = prepare(booted!!, usedWithSession = HeapMeter.settled(), baseline = baseline, label = LABEL_PINNED)
        booted = null
        val red = gcWindow(prepared, GC_WINDOW_MS / 3, firstScan)
        assertTrue(
            red.surviving.isNotEmpty(),
            "金丝雀失效：静态槽 CanaryPinboard.pinned 明明钉着会话，弱引用却被清了 ⇒ 判据是空转的",
        )
        assertTrue(
            red.staticPaths.any { it.contains("CanaryPinboard") && it.contains("pinned") },
            "静态钉住扫描没点到 CanaryPinboard.pinned，实际扫到 " + red.staticPaths.ifEmpty { "（空）" },
        )
        // B) 断开 ⇒ 同一批弱引用必须全清，且**重扫**静态链必须为空（只差那枚静态引用）。
        CanaryPinboard.pinned = null
        val rescan = rescanProbes(prepared.probes)
        val green = gcWindow(prepared.relabel(LABEL_UNPINNED), GC_WINDOW_MS, rescan)
        assertTrue(
            green.surviving.isEmpty(),
            "断开静态槽后会话仍不可回收（幸存 " + green.surviving + "，回收前 " + mib(green.usedBeforeBytes) +
                " MiB → 回收后 " + mib(green.usedAfterBytes) + " MiB）：绿路对照失效",
        )
        assertTrue(green.staticPaths.isEmpty(), "绿路仍扫到静态钉住链：" + green.staticPaths)
    }

    // ------------------------------------------------------------------
    // 判据 1 + 2：真产物链路
    // ------------------------------------------------------------------

    @Test
    fun `真产物 标题页后会话 close 后必须可回收 并扫静态钉住链`() {
        val erbcDir = findErbcDir()
        val gameRoot = gameRootFromEnv()
        val programFile = erbcDir?.resolve("program.erbc")
        val metaFile = erbcDir?.resolve("program.meta")
        val have = programFile != null && Files.isRegularFile(programFile) &&
            metaFile != null && Files.isRegularFile(metaFile) && gameRoot != null
        if (!have) {
            if (requireArtifacts()) {
                fail("ERATW_CANARY_REQUIRE=1 但产物/游戏根不齐（erbc=${programFile?.toAbsolutePath()}，gameRoot=$gameRoot）")
            }
            assumeTrue(false, "未找到 erbc 产物或游戏根（设 ERATW_ERBC_DIR 与 ERATW_GAMEDIR）：跳过真产物档")
        }
        val baseline = HeapMeter.settled()
        val t0 = System.nanoTime()
        // 设备口径：compat=false、flatStatements=false（与 `GameHost` 装载同一参数面）、
        // systemOps=NoSaveSystemOps（不碰盘）、randSeed 固定 ⇒ 读数与 phaseHeapLadder 可比。
        var program: Program? = ProgramLoader.loadMapped(programFile!!, compat = false, flatStatements = false)
        var metadata: BootMetadata? = BootMetadata.read(metaFile!!)
        var session: GameSession? = GameSession(
            gameRoot = gameRoot!!,
            program = program!!,
            metadata = metadata!!,
            consoleWidth = 199,
            randSeed = 20260226L,
            systemOps = NoSaveSystemOps,
        )
        metadata = null

        // 标题页跑在独立 VM 线程上（真机 `GameHost` 的线程形状：脚本会阻塞在输入等待）。
        var vmError: Throwable? = null
        var vm: Thread? = Thread {
            try {
                session!!.runtime.begin(BeginType.TITLE)
            } catch (t: Throwable) {
                vmError = t
            }
        }.apply { name = "canary-vm"; isDaemon = true; start() }
        val awaitNote = awaitFirstWaitPoint(vm!!, session!!, AWAIT_TITLE_MS)
        // 「确实走到了标题页」的自证：行数与屏上末两行原样打出来（否则 awaitNote 为空可能只是
        // 脚本一进门就抛异常，那是装置的问题不是判据的结论）。
        val titleTail = runCatching {
            session!!.console.loggedLines().takeLast(6).joinToString(" ⏎ ") { "\"" + it + "\"" }
        }.getOrDefault("?")
        println("[canary] 标题页自证：await判据='${awaitNote.ifEmpty { "到等待点" }}' lines=${runCatching { session!!.console.loggedLines().size }.getOrDefault(-1)} vmAlive=${vm!!.isAlive} err=${vmError?.let { it::class.simpleName }}")
        println("[canary] 标题页末六行原文（含空行占位）：$titleTail")
        val usedWithSession = HeapMeter.settled()
        var booted: Booted? = Booted(
            session = session!!,
            roots = listOf(
                "GameSession" to session!!,
                "Program" to program!!,
                "VariableStore" to session!!.store,
                "ProcessRuntime" to session!!.runtime,
                "ConsoleSink" to session!!.console,
                "QueueInputSink" to session!!.inputSink,
            ),
            vmThread = vm,
        )
        // 装置侧局部强引用逐个交出去（含被闭包捕获的 var：Kotlin 按引用捕获，赋 null 真能清槽）。
        session = null
        program = null
        vm = null
        vmError = null

        // close + 等 VM 线程退出都在 prepare 之前：线程没退就没什么可谈的（那条单独点名）。
        val closeNote = runCatching {
            if (booted!!.session!!.close()) "releaseMmap=true" else "releaseMmap=false"
        }.getOrElse { "close-ex=$it" }
        booted!!.vmThread?.interrupt()
        booted!!.vmThread?.join(JOIN_MS)
        val stageMs = (System.nanoTime() - t0) / 1_000_000L

        val (prepared, scan) = prepare(
            booted = booted!!,
            usedWithSession = usedWithSession,
            baseline = baseline,
            label = LABEL_REAL,
            awaitNote = awaitNote.ifEmpty { closeNote },
            stageMs = stageMs,
        )
        booted = null
        val reading = gcWindow(prepared, GC_WINDOW_MS, scan)

        assertTrue(reading.titlePhaseReached, "标题页未到第一个等待点：" + reading.awaitNote)
        assertTrue(
            !reading.vmThreadAlive,
            "close 后 VM 线程仍活着（" + reading.vmThreadState + "）：线程栈自己就是根，这条与静态面无关，" +
                "先坐实它（真机同判据见 SessionHandoff 的登记槽设计）",
        )
        assertTrue(
            reading.staticPaths.isEmpty(),
            "close 后仍有静态字段钉住会话根：\n" + reading.staticPaths.joinToString("\n"),
        )
        assertTrue(
            reading.surviving.isEmpty(),
            "close 后 " + reading.windowMs + " ms 内这些会话根仍不可回收：" + reading.surviving +
                "（回收前 live=" + mib(reading.usedBeforeBytes) + " MiB，回收后 " + mib(reading.usedAfterBytes) +
                " MiB，静态扫到 " + reading.staticPaths.size + " 条钉住链，扫描展开 " + reading.scanVisited +
                " 节点" + (if (reading.scanExhausted) "·预算耗尽（深链可能漏报，直接钉住不会）" else "") + "）",
        )
        // 「这枚闸真的装出一局会话」的自证：真产物 + 标题页相位的 live 在桌面上是 99 MiB 量级
        // （装配完 69 + 标题页 ~30，对照 heapRootAttribution/phaseHeapLadder 的基准），
        // close 后必须真把这一份还掉。差不足 40 MiB 只可能是「根本没装出会话」⇒ 判式空转。
        assertTrue(
            reading.usedBeforeBytes - reading.usedAfterBytes > MIN_RELEASED_BYTES,
            "close+GC 前后只差 " + mib(reading.usedBeforeBytes - reading.usedAfterBytes) + " MiB" +
                "（在场 " + mib(reading.usedBeforeBytes) + " MiB）—— 低于 " + mib(MIN_RELEASED_BYTES) +
                " MiB 的下界说明本局没真装出会话，金丝雀在这一档就是空转",
        )
    }

    // ------------------------------------------------------------------
    // 装置：装载 → 读数
    // ------------------------------------------------------------------

    /** 一次装载的产物；字段全是 `var`，[abandon] 把装置侧强引用清零。 */
    private class Booted(
        var session: GameSession?,
        var roots: List<Pair<String, Any>>?,
        var vmThread: Thread?,
    ) {
        fun abandon() {
            session = null
            roots = null
            vmThread = null
        }
    }

    /** 弱引用探针：只装名字与弱引用，装置自身不再是根。 */
    private class Probe(val name: String, val ref: WeakReference<Any>)

    /** `prepare` 的产出：只允许装字符串/数字/弱引用（`Prepared` 活着也不能钉住会话）。 */
    private class Prepared(
        val probes: List<Probe>,
        val usedBeforeBytes: Long,
        val baselineBytes: Long,
        val vmThreadAlive: Boolean,
        val vmThreadState: String,
        val eratwThreads: String,
        val label: String,
        val awaitNote: String,
        val stageMs: Long,
    ) {
        /** 换标签复用同一批弱引用（自检的「断开后再跑一次」用；静态链由调用方**重扫**后显式传入）。 */
        fun relabel(label: String): Prepared = Prepared(
            probes = probes,
            usedBeforeBytes = usedBeforeBytes,
            baselineBytes = baselineBytes,
            vmThreadAlive = vmThreadAlive,
            vmThreadState = vmThreadState,
            eratwThreads = eratwThreads,
            label = label,
            awaitNote = awaitNote,
            stageMs = stageMs,
        )
    }

    /** 一次金丝雀读数 = 报告的一行。 */
    private class Reading(
        val label: String,
        val windowMs: Long,
        val usedBeforeBytes: Long,
        val usedAfterBytes: Long,
        val baselineBytes: Long,
        val gcRounds: Int,
        val surviving: List<String>,
        val staticPaths: List<String>,
        val scanVisited: Int,
        val scanExhausted: Boolean,
        val scanClasses: Int,
        val scanClassFail: Int,
        val scanMs: Long,
        val vmThreadAlive: Boolean,
        val vmThreadState: String,
        val awaitNote: String,
        val titlePhaseReached: Boolean,
        val stageMs: Long,
    )

    /**
     * 扫静态链 + 造弱引用探针，然后立刻把装置侧强引用全部交出去。
     * 返回的 [Prepared] 里没有任何会话对象的强引用。
     */
    private fun prepare(
        booted: Booted,
        usedWithSession: Long,
        baseline: Long,
        label: String,
        awaitNote: String = "",
        stageMs: Long = 0L,
    ): Pair<Prepared, ScanResult> {
        val roots = booted.roots ?: emptyList()
        val scan = scanStaticPins(roots)
        val probes = roots.map { Probe(it.first, WeakReference(it.second)) }
        val thread = booted.vmThread
        val alive = thread?.isAlive == true
        val state = thread?.state?.toString() ?: "gone"
        val names = Thread.getAllStackTraces().keys
            .mapNotNull { it.name.takeIf { n -> n.startsWith("canary") || n.contains("eratw") } }
            .joinToString(",")
            .ifEmpty { "-" }
        booted.abandon()
        return Prepared(
            probes = probes,
            usedBeforeBytes = usedWithSession,
            baselineBytes = baseline,
            vmThreadAlive = alive,
            vmThreadState = state,
            eratwThreads = names,
            label = label,
            awaitNote = awaitNote,
            stageMs = stageMs,
        ) to scan
    }

    /**
     * 用同一批弱引用**重扫**一次静态链（自检 B 段用：断开静态槽之后必须为空）。
     *
     * 强引用只在本次调用栈里存在 —— `roots` 是局部变量，函数返回即出栈，不会把会话钉在
     * 随后的回收窗口里（GC 根只认**存活帧**，弹出的帧不算）。
     */
    private fun rescanProbes(probes: List<Probe>): ScanResult {
        val roots = probes.mapNotNull { p -> p.ref.get()?.let { p.name to it } }
        return scanStaticPins(roots)
    }

    /**
     * 回收窗口：轮询到弱引用全清为止，上限 [windowMs]。
     * 每轮 `System.gc()` + 8 MiB 短命分配（后者保证显式 GC 被禁用的 JVM 上判式仍能推进）。
     */
    private fun gcWindow(p: Prepared, windowMs: Long, scan: ScanResult): Reading {
        val start = System.nanoTime()
        var rounds = 0
        val pressure = ArrayList<Any>(PRESSURE_ROUNDS)
        var surviving = p.probes.filter { it.ref.get() != null }.map { it.name }
        while (surviving.isNotEmpty() && (System.nanoTime() - start) / 1_000_000L < windowMs) {
            rounds++
            System.gc()
            pressure.add(ByteArray(PRESSURE_BYTES))
            if (pressure.size >= PRESSURE_ROUNDS) pressure.clear()
            Thread.sleep(POLL_MS)
            surviving = p.probes.filter { it.ref.get() != null }.map { it.name }
        }
        pressure.clear()
        val usedAfter = HeapMeter.settled()
        val r = Reading(
            label = p.label,
            windowMs = windowMs,
            usedBeforeBytes = p.usedBeforeBytes,
            usedAfterBytes = usedAfter,
            baselineBytes = p.baselineBytes,
            gcRounds = rounds,
            surviving = surviving,
            staticPaths = scan.paths,
            scanVisited = scan.visited,
            scanExhausted = scan.exhausted,
            scanClasses = scan.classes,
            scanClassFail = scan.classFail,
            scanMs = scan.elapsedMs,
            vmThreadAlive = p.vmThreadAlive,
            vmThreadState = p.vmThreadState,
            awaitNote = p.awaitNote,
            titlePhaseReached = p.awaitNote.isEmpty() || p.awaitNote.startsWith("releaseMmap"),
            stageMs = p.stageMs,
        )
        report(r, p.eratwThreads)
        return r
    }

    // ------------------------------------------------------------------
    // 静态钉住扫描
    // ------------------------------------------------------------------

    /** 扫描产出：链的**文字**与计数器（强引用图在返回前就地清空，装置自己不能变成新的根）。 */
    private class ScanResult(
        val paths: List<String>,
        val visited: Int,
        val exhausted: Boolean,
        val classes: Int,
        val classFail: Int,
        val elapsedMs: Long,
    )

    /** BFS 的一帧：对象 + 经由谁 + 最初的静态字段是谁 + 这条边的名字。 */
    private class Frame(val obj: Any, val parent: Frame?, val origin: String, val edge: String)

    /**
     * 从「本进程 classpath 上所有 `com.eratw` 前缀类的静态字段」广度优先，找出**能走到会话根**的链。
     *
     * 口径：
     * - 硬边 = 实例字段 / 数组元素 / `Iterable` 与 `Map` 的元素 / `Map.Entry` / lambda 捕获；
     *   `WeakReference`、`SoftReference` 也走 `get()`，但边标成「软/弱（非硬钉）」，报告不冒充硬钉；
     * - JDK 与 kotlin 标准库的类**只用公开 API**（`iterator` / `entries` / `Array.get`）展开，
     *   不反射私有字段 —— JDK 17 的 `java.base` 没开模块，`setAccessible` 会直接抛；
     * - 编译器类（`com.erbcc`）不入静态根：设备 classpath 上没有它们，拿它们当肇事者是假案。
     *
     * 已知盲区（照实写，不当「没问题」）：**别的线程栈**与 **`ThreadLocal` 的值**不是静态字段，
     * 本扫描看不见。前者由 [Reading.vmThreadAlive] 兜住（本装置的 VM 线程必须退），后者要
     * `--add-opens java.base/java.lang` 才能反射 `Thread.threadLocals`，本闸不开这个开关。
     * TODO(verify)：设备侧若真有 ThreadLocal 那一份，需要组长在 app 侧带 add-opens 复跑
     * （本车道没建 `app/src/test/.../SessionRetainmentCanaryTest.kt`，理由见回执）。
     */
    private fun scanStaticPins(roots: List<Pair<String, Any>>): ScanResult {
        val t0 = System.nanoTime()
        val rootIndex = IdentityHashMap<Any, String>()
        roots.forEach { (name, value) -> rootIndex.putIfAbsent(value, name) }
        val seen = IdentityHashMap<Any, Frame>()
        val queue = ArrayDeque<Frame>()
        var classFail = 0
        var classTried = 0
        val paths = ArrayList<String>()
        var visited = 0
        var exhausted = false
        var found = 0
        // 入队即比对：预算耗尽时「静态字段直接钉住」这一层仍然查得出（深链才可能漏，
        // 报告与断言文案都会带上「预算耗尽」这条限定）。
        fun recordRoot(frame: Frame) {
            if (found >= PATH_BUDGET) return
            rootIndex[frame.obj]?.let { rootName ->
                found++
                paths.add(rootName + " <- " + describeChain(frame))
            }
        }
        val classNames = candidateClassNames()
        for (name in classNames) {
            if (classTried >= CLASS_BUDGET) break
            classTried++
            val clazz = runCatching { Class.forName(name, false, javaClass.classLoader) }.getOrNull()
            if (clazz == null) {
                classFail++
                continue
            }
            val fields = runCatching { clazz.declaredFields }.getOrNull()
            if (fields == null) {
                classFail++
                continue
            }
            for (f in fields) {
                if (!Modifier.isStatic(f.modifiers)) continue
                val value = runCatching { isAccessible(f); f.get(null) }.getOrNull() ?: continue
                if (seen.containsKey(value)) continue
                val origin = "静态 ${clazz.name}.${f.name}"
                val frame = Frame(value, null, origin, origin)
                seen[value] = frame
                recordRoot(frame)
                queue.add(frame)
            }
        }

        while (queue.isNotEmpty()) {
            if (visited >= NODE_BUDGET) { exhausted = true; break }
            val frame = queue.removeFirst()
            visited++
            forEachChild(frame.obj) { child, edge ->
                if (seen.containsKey(child) || isLeaf(child)) return@forEachChild
                val next = Frame(child, frame, frame.origin, edge)
                seen[child] = next
                recordRoot(next)
                queue.add(next)
            }
        }
        val result = ScanResult(
            paths = ArrayList(paths),
            visited = visited,
            exhausted = exhausted,
            classes = classNames.size,
            classFail = classFail,
            elapsedMs = (System.nanoTime() - t0) / 1_000_000L,
        )
        // 装置的强引用图就地清空：留着就等于自己造一个钉住者。
        seen.clear()
        queue.clear()
        rootIndex.clear()
        return result
    }

    /** 沿 [Frame.parent] 回溯，拼出「静态 类.字段 -> 字段 -> …」一条链（正向读，末端是根）。 */
    private fun describeChain(frame: Frame): String {
        val parts = ArrayList<String>()
        var cur: Frame? = frame
        while (cur != null && parts.size < CHAIN_GUARD) {
            parts.add(cur.edge)
            cur = cur.parent
        }
        parts.reverse()
        return parts.joinToString(" -> ")
    }

    private fun isAccessible(f: java.lang.reflect.Field) {
        runCatching { f.isAccessible = true }
    }

    /** 一个对象的孩子：字段 / 数组元素 / 集合与 Map 元素 / 软弱的 `get()`。 */
    private fun forEachChild(o: Any, emit: (Any, String) -> Unit) {
        if (o is Class<*> || o is String || o is Thread) return
        if (o is Reference<*>) {
            val v = runCatching { o.get() }.getOrNull()
            if (v != null) emit(v, "软/弱引用（非硬钉）")
            return
        }
        if (o.javaClass.isArray) {
            if (o.javaClass.componentType.isPrimitive) return
            val n = runCatching { JavaArray.getLength(o) }.getOrDefault(0)
            var i = 0
            while (i < n && i < ARRAY_EDGE_BUDGET) {
                val v = runCatching { JavaArray.get(o, i) }.getOrNull()
                if (v != null) emit(v, "[$i]")
                i++
            }
            return
        }
        if (o is Map.Entry<*, *>) {
            val k = runCatching { o.key }.getOrNull()
            val v = runCatching { o.value }.getOrNull()
            if (k != null) emit(k, "Entry 键")
            if (v != null) emit(v, "Entry 值")
            return
        }
        if (o is Map<*, *>) {
            runCatching {
                var n = 0
                for ((k, v) in o) {
                    if (k != null) emit(k, "Map 键")
                    if (v != null) emit(v, "Map 值")
                    if (++n >= ARRAY_EDGE_BUDGET) break
                }
            }
            return
        }
        if (o is Iterable<*>) {
            runCatching {
                var n = 0
                for (e in o) {
                    if (e != null) emit(e, "元素")
                    if (++n >= ARRAY_EDGE_BUDGET) break
                }
            }
            return
        }
        var c: Class<*>? = o.javaClass
        while (c != null && c != Any::class.java && !isJdkPackage(c.name)) {
            val fields = runCatching { c.declaredFields }.getOrNull()
            fields?.forEach { f ->
                if (Modifier.isStatic(f.modifiers)) return@forEach
                val v = runCatching { isAccessible(f); f.get(o) }.getOrNull() ?: return@forEach
                emit(v, "·${f.name}")
            }
            c = c.superclass
        }
    }

    private fun isJdkPackage(name: String): Boolean =
        name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") ||
            name.startsWith("sun.") || name.startsWith("kotlin.") || name.startsWith("org.jetbrains.")

    /** 不可再往下走的叶子（字符串/装箱数/布尔/原始数组等：不可能指向会话）。 */
    private fun isLeaf(o: Any): Boolean =
        o is String || o is Number || o is Boolean || o is Character || o is Class<*> ||
            o is Thread || (o.javaClass.isArray && o.javaClass.componentType.isPrimitive)

    /** classpath 上所有 com/eratw 子树里的 class 文件对应的类名（目录与 jar 两种入口都覆盖）。 */
    private fun candidateClassNames(): List<String> {
        val out = LinkedHashSet<String>()
        for (entry in System.getProperty("java.class.path").split(File.pathSeparatorChar)) {
            if (entry.isBlank()) continue
            val f = File(entry)
            if (f.isDirectory) {
                val base = f.toPath()
                val root = base.resolve("com").resolve("eratw")
                if (Files.isDirectory(root)) {
                    root.toFile().walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { file ->
                        val rel = base.relativize(file.toPath()).toString().replace(File.separatorChar, '.')
                        out.add(rel.removeSuffix(".class"))
                    }
                }
            } else if (f.isFile && entry.endsWith(".jar")) {
                runCatching {
                    ZipFile(f).use { zip ->
                        java.util.Collections.list(zip.entries()).forEach { e ->
                            val n = e.name
                            if (!e.isDirectory && n.startsWith("com/eratw/") && n.endsWith(".class")) {
                                out.add(n.removeSuffix(".class").replace('/', '.'))
                            }
                        }
                    }
                }
            }
        }
        return ArrayList(out)
    }

    // ------------------------------------------------------------------
    // 装载装置
    // ------------------------------------------------------------------

    /** 合成小语料会话（判据 3 用：不碰产物、不碰盘、毫秒级装配）。 */
    private fun bootSyntheticSession(dir: Path): Booted {
        val compiler = ErbProgramCompiler()
        val parser = LineParser(SymbolTable())
        val lines = listOf(
            LogicalLine(LineKind.FUNCTION_LABEL, "CANARY.ERB", 1, "@SYSTEM_TITLE"),
            LogicalLine(LineKind.CODE, "CANARY.ERB", 2, "PRINTL CANARY"),
        ).mapNotNull { parser.parseLogicalLine(it) }
        val fb = compiler.compileFunction(FuncSig("SYSTEM_TITLE"), lines, setOf("SYSTEM_TITLE"))
        var program: Program? = compiler.build("SYSTEM_TITLE")
        val metadata = BootMetadata(
            labels = mapOf("SYSTEM_TITLE" to fb.labels),
            functions = listOf(
                FuncMeta(
                    index = fb.index, key = "SYSTEM_TITLE", name = "SYSTEM_TITLE",
                    defIndex = 0, position = "CANARY.ERB:1",
                ),
            ),
            fileByKey = mapOf("SYSTEM_TITLE" to "CANARY.ERB"),
        )
        var session: GameSession? = GameSession(
            gameRoot = dir,
            program = program!!,
            metadata = metadata,
            consoleWidth = 199,
            randSeed = 20260226L,
            systemOps = NoSaveSystemOps,
        )
        // 小语料的 SYSTEM_TITLE 里没有输入等待，begin 直接跑完 ⇒ 收口形状与真产物档一致。
        session!!.begin(BeginType.TITLE)
        val s = session
        s!!.close()
        val gone = Thread { }.apply { name = "canary-vm-synthetic"; start() }
        gone.join(2_000)
        val booted = Booted(
            session = s,
            roots = listOf(
                "GameSession" to s,
                "Program" to program!!,
                "VariableStore" to s.store,
                "ProcessRuntime" to s.runtime,
                "ConsoleSink" to s.console,
                "QueueInputSink" to s.inputSink,
            ),
            vmThread = gone,
        )
        program = null
        session = null
        return booted
    }

    /** 「标题页走到输入等待」的判据：控制台停增 + VM 线程 park（`QueueInputSink` 的阻塞形状）。 */
    private fun awaitFirstWaitPoint(vm: Thread, session: GameSession, timeoutMs: Long): String {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var lastLines = -1
        var stableSince = System.nanoTime()
        while (System.nanoTime() < deadline) {
            if (!vm.isAlive) return ""
            val lines = runCatching { session.console.snapshot().size }.getOrDefault(0)
            if (lines != lastLines) {
                lastLines = lines
                stableSince = System.nanoTime()
            } else if (lines > 0) {
                val state = vm.state
                if ((state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) &&
                    System.nanoTime() - stableSince > STABLE_WINDOW_NS
                ) {
                    return ""
                }
            }
            Thread.sleep(POLL_MS)
        }
        return "TIMEOUT ${timeoutMs}ms lines=$lastLines state=${vm.state}"
    }

    // ------------------------------------------------------------------
    // 读数与报告
    // ------------------------------------------------------------------

    private fun report(r: Reading, eratwThreads: String) {
        val residual = (r.usedAfterBytes - r.baselineBytes) / MIB
        val released = (r.usedBeforeBytes - r.usedAfterBytes) / MIB
        println(
            "[canary] ${r.label}: 基线=${mib(r.baselineBytes)}MiB 会话在场=${mib(r.usedBeforeBytes)}MiB " +
                "close+GC 后=${mib(r.usedAfterBytes)}MiB 回吐=${released}MiB 残留(对基线)=${residual}MiB " +
                "轮=${r.gcRounds}/${r.windowMs}ms 静态钉住链=${r.staticPaths.size}条 " +
                "扫描=${r.scanVisited}节点/${r.scanClasses}类/${r.scanMs}ms${if (r.scanExhausted) "(预算耗尽)" else ""} " +
                "幸存根=${r.surviving} 装载=${r.stageMs}ms eratw线程=${eratwThreads}",
        )
        r.staticPaths.forEach { println("[canary]   钉住链: $it") }
        appendReport(
            "| ${r.label} | ${mib(r.baselineBytes)} | ${mib(r.usedBeforeBytes)} | ${mib(r.usedAfterBytes)} | " +
                "$released | $residual | ${r.gcRounds} | ${r.windowMs} | " +
                (if (r.vmThreadAlive) "仍活(${r.vmThreadState})" else "已退") + " | " +
                "${r.scanVisited} / ${r.scanClasses}类 / ${r.scanMs}ms${if (r.scanExhausted) " 预算耗尽" else ""} | " +
                "${r.staticPaths.size} | ${r.surviving.ifEmpty { "-" }} | ${r.awaitNote.ifEmpty { "到等待点" }} | " +
                "${r.stageMs} |\n",
        )
    }

    private fun appendReport(text: String) {
        runCatching {
            val f = File(REPORT_REL)
            f.parentFile?.mkdirs()
            if (!f.exists()) {
                f.writeText(
                    "# 会话滞留金丝雀（fix-staticpin）\n\n" +
                        "- 口径：`Runtime.totalMemory()-freeMemory()` 多轮 GC 取最小值 = 常驻（`HeapMeter` 同源）\n" +
                        "- 装载口径：设备侧 `compat=false`、`flatStatements=false`、`systemOps=NoSaveSystemOps`、" +
                        "`consoleWidth=199`、`randSeed=20260226`\n" +
                        "- 判据：`close()` + VM 线程退出 + 装置引用全部置空后，会话根的 `WeakReference` 必须在窗口内全清，" +
                        "且静态字段可达图里不得有到会话根的链\n" +
                        "- 残留 = `close+GC 后` - `基线`（桌面版「184 MiB 仍不还」的同形状读数，绿时应只有几 MiB；" +
                        "静态扫描会顺带初始化若干类，这部分算进残留，读数偏保守）\n" +
                        "- JVM ${System.getProperty("java.version")} / 挡位 ${mib(HeapMeter.max())} MiB / GC ${HeapMeter.gcNames()}\n\n" +
                        "| 档 | 基线 MiB | 会话在场 MiB | close+GC 后 MiB | 回吐 MiB | 残留 MiB | GC 轮 | 窗口 ms | VM 线程 | 扫描 | 钉住链 | 幸存根 | 标题页/备注 | 装载 ms |\n" +
                        "|---|---:|---:|---:|---:|---:|---:|---:|---|---|---:|---|---|---:|\n",
                )
            }
            f.appendText(text)
        }
    }

    private fun mib(bytes: Long): String = (bytes / MIB).toString()

    private fun requireArtifacts(): Boolean = System.getenv("ERATW_CANARY_REQUIRE")?.trim() == "1"

    /** 与 `LoadTimingProbe`/`HeapCompositionProbeTest` 同一套查找顺序。 */
    private fun findErbcDir(): Path? {
        System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it).parent }
        val candidates = listOf(
            Path.of("build", "erbc-out", "erbc.v1"),
            Path.of("build", "erbc.v1"),
            Path.of("..", "tools", "erbcc", "build", "erbc-out", "erbc.v1"),
        )
        return candidates.firstOrNull { Files.isRegularFile(it.resolve("program.erbc")) }
    }

    private fun gameRootFromEnv(): Path? =
        System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    /**
     * 破坏性自检用的**真静态槽**：Kotlin `object` 的属性编译成静态字段，正是本闸要能看见的那类
     * 跨会话根。用例判式跑完立即置空 —— 它自己要是留着，就成第二个泄漏。
     */
    private object CanaryPinboard {
        var pinned: GameSession? = null
    }

    private companion object {
        const val MIB = 1024L * 1024L

        /** 回收窗口：判式见类文档「稳定性口径」。 */
        const val GC_WINDOW_MS = 60_000L

        /** 轮询步长（`SessionHandoff.awaitOthersGone` 同一手感：对玩家不可见，对窗口足够细）。 */
        const val POLL_MS = 25L

        /** 每轮短命分配的字节数。 */
        const val PRESSURE_BYTES = 8 shl 20

        /** 分配压力攒够几条就丢一次（造垃圾逼回收，同时不吃挡位）。 */
        const val PRESSURE_ROUNDS = 24

        /** `close()` 后等 VM 线程退出的上限：队列已关，脚本只会以 InputSinkClosedException 收口。 */
        const val JOIN_MS = 15_000L

        /** 走到标题页第一个等待点的上限（真产物 TITLE 段在桌面是分钟级以内）。 */
        const val AWAIT_TITLE_MS = 300_000L

        /** 「控制台停增 + 线程 park」多久算到位。 */
        const val STABLE_WINDOW_NS = 800_000_000L

        /**
         * BFS 节点预算：静态可达图本身就吃到注册表量级（真产物测试 classpath 上 2,700 余个
         * `com.eratw` 类的静态注册表合计百万级对象），100 万节点在桌面上实测约 15 s。
         * 预算耗尽时**浅层仍然完整**（静态字段本体在入队那一层就被比对，所以「直接钉住」
         * 永远查得出，深链可能查不出），报告与断言文案都会带上「预算耗尽」这一条限定。
         */
        const val NODE_BUDGET = 1_000_000

        /** 参与静态扫描的类数上限。 */
        const val CLASS_BUDGET = 4_000

        /** 单个集合/数组展开的边数上限（一张百万级池不该一次吃光预算）。 */
        const val ARRAY_EDGE_BUDGET = 20_000

        /** 最多点名几条钉住链。 */
        const val PATH_BUDGET = 24

        /** 链回溯的层数上限。 */
        const val CHAIN_GUARD = 64

        /**
         * 「这一局确实装出来了并且确实还掉了」的下界（40 MiB）。
         * 真产物 + 标题页相位的桌面 live 实测 99 MiB（装配完 ≈69 + 标题页 ≈30，对照
         * `heapRootAttribution` / `phaseHeapLadder` 的基准），一半都不到就是装置空转。
         */
        const val MIN_RELEASED_BYTES = 40L * 1024 * 1024

        const val REPORT_REL = "build/session-retainment-canary.md"

        const val LABEL_REAL = "真产物·标题页 close 后"
        const val LABEL_PINNED = "自检·钉在静态槽"
        const val LABEL_UNPINNED = "自检·断开静态槽"
    }
}
