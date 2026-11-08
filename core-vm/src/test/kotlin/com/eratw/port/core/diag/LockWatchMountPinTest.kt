package com.eratw.port.core.diag

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FEEL1-L3 的**源码面钉子**（仓内先例：`ScriptLineTokenIdentityTest` 钉「不许回退 hashCode()」、
 * `code/LabelKeyLocaleTest.kt:62-85` 钉源码面禁 `Locale.getDefault()`）。
 *
 * 钉四条，判据是双向的「摘掉即红」：
 * 1. **八处挂载点的探针调用行仍在源码里**（[LockWatch.handleOrNull] 守卫 + 对应的
 *    `LockWatch.ID_*` 常数 + `noteSegment`/`noteWait` 调用）。把探针短路 ⇒ 本条红。
 * 2. **采样表容量是写死的常数**：`const val` 三行原样在、表恒为定长数组、
 *    [LockWatch] 的字段面里**没有任何集合类型**（§0 第 10 条有界性硬约束，
 *    前科是无界台账涨到 42 MB）。把常数改成动态/加一张 `ArrayList` ⇒ 本条红。
 * 3. **容量算式对得上用量**（OBS2-L2 新增）：`const val ID_*` 的个数 = [LockWatch.LOCK_COUNT]、
 *    容量是 2 的幂且半容量盖不住用量、`LOCK_NAMES` 逐项是真实读数名（不许残留 `unused#`）
 *    —— FEEL1 留的两格余量已被 OBS2 花完，从这一秒起「加挂载点却不扩容量」红在这里，
 *    而不是静默落进 `outOfRangeSamples` 兜底行（读数还在、分层没了）。
 * 4. **锁序表逐条带 文件:行号**，OBS2-L2 新增的两条边（erbc 装载链）也在表里。
 *
 * 行为面（真跑挂载点：关时零样本、开时逐条记账、挂载前后逐字同结果）在同目录的
 * [LockWatchObs2MountTest]；本文件只做源码形状钉子。
 */
class LockWatchMountPinTest {

    private val moduleDir: Path = Path.of("").toAbsolutePath()

    private fun source(relativePath: String): String? {
        val p = moduleDir.resolve(relativePath)
        return if (Files.exists(p)) p.readText() else null
    }

    /** ① 每个挂载点：守卫口 + 该点的 id 常数 + 一次记录调用，三者缺一即红。 */
    @Test
    fun `八处探针挂载点仍在源码里`() {
        val mounts = listOf(
            // 文件 → 该文件必须出现的探针 id（同一文件里多处挂载就并列列全）
            "src/main/kotlin/com/eratw/port/core/host/GraphicsBuffer.kt" to listOf(
                "LockWatch.ID_GFX_PIXELS_DECODE",
                "LockWatch.ID_SPRITE_POOL_EVICT",
            ),
            "src/main/kotlin/com/eratw/port/core/loader/MappedErbc.kt" to listOf(
                "LockWatch.ID_ERBC_MATERIALIZE",
                "LockWatch.ID_ERBC_CLOSE",
            ),
            "src/main/kotlin/com/eratw/port/core/proc/QueueInputSink.kt" to listOf(
                "LockWatch.ID_SINK_SUBMIT",
                "LockWatch.ID_SINK_ANYKEY_AWAIT",
            ),
            // OBS2-L2（2026-11-05）新补的两片黑箱锁面
            "src/main/kotlin/com/eratw/port/core/host/LineBufferConsoleSink.kt" to listOf(
                "LockWatch.ID_SINK_DISPLAY",
            ),
            "src/main/kotlin/com/eratw/port/core/loader/ErbcStream.kt" to listOf(
                "LockWatch.ID_ERBC_MATERIALIZE_LOCK",
            ),
        )
        var scanned = 0
        for ((rel, ids) in mounts) {
            val text = source(rel)
            if (text == null) {
                throw AssertionError("$rel 读不到：钉子失效（工作目录不是模块根？$moduleDir）")
            }
            scanned++
            assertTrue(
                "LockWatch.handleOrNull()" in text,
                "$rel 缺守卫口 LockWatch.handleOrNull() —— 探针被摘掉了（FEEL1-L3：缺省关的零开销守卫）",
            )
            for (id in ids) {
                assertTrue(id in text, "$rel 缺挂载点 $id —— 该处的锁读数就此失明")
            }
            assertTrue(
                "watch.noteSegment(" in text || "watch.noteWait(" in text,
                "$rel 没有任何记录调用（noteSegment/noteWait）",
            )
        }
        assertEquals(5, scanned, "五个被钉文件必须全部读到，否则本条断言是空的")
    }

    /**
     * ① 附带：记录点必须在被观察锁的**外侧**（`pixels` / `evict` / `program` / `close` /
     * `lines` / `appendGroup` / `statementAt` / `buildFlatStatementsRange` 都块后记录）。
     */
    @Test
    fun `探针记录点在被观察锁外侧的形状没被改掉`() {
        // 唯一例外是 anykey 的 await（口径见 LockWatch 类 KDoc 锁序表末段的叶锁不变式）。
        val gfx = source("src/main/kotlin/com/eratw/port/core/host/GraphicsBuffer.kt")
            ?: throw AssertionError("读不到 GraphicsBuffer.kt")
        val sink = source("src/main/kotlin/com/eratw/port/core/proc/QueueInputSink.kt")
            ?: throw AssertionError("读不到 QueueInputSink.kt")
        val erbc = source("src/main/kotlin/com/eratw/port/core/loader/MappedErbc.kt")
            ?: throw AssertionError("读不到 MappedErbc.kt")
        val console = source("src/main/kotlin/com/eratw/port/core/host/LineBufferConsoleSink.kt")
            ?: throw AssertionError("读不到 LineBufferConsoleSink.kt")
        val stream = source("src/main/kotlin/com/eratw/port/core/loader/ErbcStream.kt")
            ?: throw AssertionError("读不到 ErbcStream.kt")

        // 形状钉子：各处「块后记录」都以 `if (watch !== null) {` 起头、后跟 noteSegment。
        // `\s+` 而不是 `\n`：检出文件在 Windows 上是 CRLF，换行符写死会漏匹配。
        val afterLock = Regex("if \\(watch !== null\\) \\{\\s+watch\\.noteSegment\\(")
        assertEquals(2, afterLock.findAll(gfx).count(),
            "GraphicsBuffer 的两处挂载（pixels / evictDeferredPixels）必须在监视器外侧记录")
        assertEquals(2, afterLock.findAll(erbc).count(),
            "MappedProgramSource（erbc 句柄）的两处挂载（program 物化段 / close）都必须在监视器外侧记录")
        // OBS2-L2：两个新挂载点各两处，同样必须在监视器**外侧**记录（摘掉任一 noteSegment ⇒ 本条红）。
        assertEquals(2, afterLock.findAll(console).count(),
            "sink.display 的两处挂载（lines() 读侧快照 / appendGroup 写侧入列）必须在监视器外侧记录")
        assertEquals(2, afterLock.findAll(stream).count(),
            "erbc.materialize.lock 的两处挂载（statementAt 逐条物化 / buildFlatStatementsRange 建片趟）" +
                "必须在 materializeLock 外侧记录")
        assertTrue("lock.unlock()" in sink && "watch.noteSegment(" in sink,
            "sink.submit 的记录点必须排在 lock.unlock() 同一段 finally 里")
        assertEquals(1, Regex("watch\\.noteWait\\(").findAll(sink).count(),
            "anykey 等待点是全仓唯一的 noteWait 挂载（多挂就是越界改了本波范围）")
    }

    /**
     * ① 附带（OBS2-L2 硬要求 3）：**关时零开销的形状仍然成立**。
     *
     * 三个可钉的点逐个钉死，缺一条就说明有人把探针从「一次 volatile 布尔读」改成了
     * 无条件取时钟 / 无条件进监视器 / 顺手分配：
     * 1. 守卫口 `LockWatch.handleOrNull()` 在被钉文件里出现（上面第一条用例已含）；
     * 2. 每个挂载点恰好三发 `System.nanoTime()`：守卫式 enter、块内 `acquiredNs`、记录点。
     *    **全文件 nanoTime 总数 = 守卫 + 获锁 + 记录**，多出来的那一发就是无条件取时钟
     *    （关时也要付）⇒ 零开销形状破了；
     * 3. 关时不分配：挂载点只交基本类型（`Int` id + 三个 `Long` 时标 + `Long` tag），
     *    源码里不许出现 `LockWatch(` 构造、也不许在 `noteSegment` 实参里拼字符串
     *    （读数行的拼装归 [LockWatch.readout]，只在读侧锁外做）。
     *
     * 「不进监视器」那一半由 2 的守卫式 + 记录点必须裹在 `if (watch !== null)` 里
     * （上一条用例）共同保证：唯一的 [LockWatch.noteSegment] 入口被 null 守卫挡住 ⇒
     * 关时走不到本类的 `synchronized(lock)`。真跑一遍挂载点的行为面在
     * [LockWatchObs2MountTest] 里钉。
     */
    @Test
    fun `关时新挂载点不取时钟不进监视器不分配的形状还在`() {
        for (rel in listOf(
            "src/main/kotlin/com/eratw/port/core/host/LineBufferConsoleSink.kt",
            "src/main/kotlin/com/eratw/port/core/loader/ErbcStream.kt",
        )) {
            val text = source(rel) ?: throw AssertionError("读不到 $rel")
            // 只数代码行：注释里出现的 `System.nanoTime()` 不是取时钟。
            val code = text.lineSequence().filterNot { ln ->
                val t = ln.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }.joinToString("\n")
            val guards =
                Regex("if \\(watch === null\\) 0L else System\\.nanoTime\\(\\)").findAll(code).count()
            val acquired =
                Regex("if \\(watch !== null\\) acquiredNs = System\\.nanoTime\\(\\)").findAll(code).count()
            val records = Regex("watch\\.noteSegment\\(").findAll(code).count()
            assertEquals(2, guards, "$rel 的守卫式取时钟应是 2 处（两个挂载点各一处），实读 $guards")
            assertEquals(2, acquired, "$rel 块内的 acquiredNs 应是 2 处，实读 $acquired")
            assertEquals(2, records, "$rel 的记录调用应是 2 处，实读 $records")
            // 只数两个**锁挂载点代码块**（`val enterNs = ...` 到 `watch.noteSegment(...)`）：
            // 文件里另有 `progressClockMs` 的帧保活默认参数（perf-yield 波 089ac4a7 新增）也含
            // System.nanoTime()，它不在锁挂载点内，不算「关时也要付」的那一发。
            val mountBlocks = Regex(
                "val enterNs = if \\(watch === null\\)[\\s\\S]*?watch\\.noteSegment\\([\\s\\S]*?\\)",
            ).findAll(code).toList()
            assertEquals(2, mountBlocks.size, "$rel 应有两个锁挂载点代码块，实读 ${mountBlocks.size}")
            val nanoPerBlock = mountBlocks.map { b -> Regex("System\\.nanoTime\\(\\)").findAll(b.value).count() }
            assertEquals(
                listOf(3, 3), nanoPerBlock,
                "$rel 每个挂载点块应恰好 3 发 System.nanoTime()（守卫 + 获锁 + 记录），实读 $nanoPerBlock",
            )
            val nano = nanoPerBlock.sum()
            assertEquals(
                guards + acquired + records, nano,
                "$rel 的 System.nanoTime() 总数应 = 守卫 $guards + 获锁 $acquired + 记录 $records = " +
                    "${guards + acquired + records}（每处挂载点三发）；实读 $nano —— " +
                    "多出来的那一发就是**无条件取时钟**，关时也要付，零开销形状破了",
            )
            assertFalse("LockWatch(" in code, "$rel 出现了 LockWatch 构造形态 —— 探针不该自己分配对象")
            assertFalse(
                Regex("noteSegment\\([^)]*\\+").findAll(code).any(),
                "$rel 的 noteSegment 实参里出现了字符串拼接 —— 挂载点必须只交基本类型",
            )
        }
    }

    /** ② 容量三常数原样在，且乘积关系与上限都钉死。 */
    @Test
    fun `采样表容量是写死的常数`() {
        val text = source("src/main/kotlin/com/eratw/port/core/diag/LockWatch.kt")
            ?: throw AssertionError("读不到 LockWatch.kt")
        for (line in listOf(
            "const val LOCK_COUNT = 8",
            "const val THREAD_SLOTS = 4",
            "const val SLOT_COUNT = LOCK_COUNT * THREAD_SLOTS",
        )) {
            assertTrue(line in text, "容量常数行被改动：缺 $line（有界性硬约束 §0 第 10 条）")
        }
        assertEquals(LockWatch.LOCK_COUNT * LockWatch.THREAD_SLOTS, LockWatch.SLOT_COUNT)
        assertTrue(
            LockWatch.SLOT_COUNT in 1..64,
            "槽总数必须留在常数级（现 ${LockWatch.SLOT_COUNT}）；要扩容量先回类 KDoc 补算式并改这条钉子",
        )
    }

    /**
     * ③ 容量与用量对齐（OBS2-L2 花完 FEEL1 的两格余量之后新增的钉子）。
     *
     * `const val ID_*` 的个数正好等于 [LockWatch.LOCK_COUNT]、容量是 2 的幂且
     * 「半容量盖不住用量」= 类 KDoc 那句「刚够盖住用量的 2 的幂」；`LOCK_NAMES`
     * 逐项都是真实读数名，不许残留 `unused#`。
     *
     * 判据方向：**有人加第 9 个挂载点却不把容量抬到 16 ⇒ 本条红**，而不是让新点
     * 静默落进 [LockWatch] 的越界兜底计数（读数还在、分层没了）。
     */
    @Test
    fun `挂载点个数与容量算式对齐`() {
        val text = source("src/main/kotlin/com/eratw/port/core/diag/LockWatch.kt")
            ?: throw AssertionError("读不到 LockWatch.kt")
        val ids = Regex("const val ID_[A-Z_]+ = (\\d+)").findAll(text)
            .map { it.groupValues[1].toInt() }
            .toList()
            .sorted()
        assertEquals(
            LockWatch.LOCK_COUNT, ids.size,
            "挂载点 id 个数（${ids.size}）必须等于 LOCK_COUNT（${LockWatch.LOCK_COUNT}）；" +
                "要加第 9 个点先把 LOCK_COUNT 抬到 16 并改类 KDoc 的算式",
        )
        assertEquals((0 until LockWatch.LOCK_COUNT).toList(), ids, "id 必须是 0 起连续编号")
        var pow = 1
        while (pow < LockWatch.LOCK_COUNT) pow *= 2
        assertEquals(LockWatch.LOCK_COUNT, pow, "LOCK_COUNT 必须是 2 的幂（现 ${LockWatch.LOCK_COUNT}）")
        assertTrue(
            LockWatch.LOCK_COUNT / 2 < ids.size,
            "容量不许虚占：半容量（${LockWatch.LOCK_COUNT / 2}）应盖不住现有用量 ${ids.size}",
        )
        val names = LockWatch::class.java.getDeclaredField("LOCK_NAMES").apply { isAccessible = true }
            .get(LockWatch) as Array<*>
        val stale = names.filter { it.toString().startsWith("unused#") }
        assertTrue(
            stale.isEmpty() && names.size == LockWatch.LOCK_COUNT,
            "LOCK_NAMES 必须逐项是真实读数名（长度 ${names.size} / 容量 ${LockWatch.LOCK_COUNT}），" +
                "残留空格 $stale ⇒ 有人改了 id 却没同步名表",
        )
        // 名表与 id 的对应关系：读数行的挂载点名就来自这一格，写歪了报告就在骗人。
        assertEquals("sink.display", LockWatch.nameOf(6))
        assertEquals("erbc.materialize.lock", LockWatch.nameOf(7))
        assertEquals("unknown#99", LockWatch.nameOf(99), "越界 id 必须给 unknown# 而不是抛")
    }

    /** ② 附带：读侧字段面**没有任何集合**（定长数组 + 基本类型，无按样本增长的结构）。 */
    @Test
    fun `采样器没有任何会增长的容器`() {
        val collections = LockWatch::class.java.declaredFields
            .filter { f ->
                java.util.Collection::class.java.isAssignableFrom(f.type) ||
                    java.util.Map::class.java.isAssignableFrom(f.type)
            }
            .map { it.name }
        assertTrue(
            collections.isEmpty(),
            "LockWatch 出现集合类型字段 $collections —— 无界台账前科（42 MB）不许在这里复活；" +
                "采样表必须全是构造期定长数组",
        )
    }

    /** ② 附带：真实表长 = SLOT_COUNT（反射读后备数组，防止「常数写了、表却另开一张」）。 */
    @Test
    fun `每张采样表实长都等于写死的槽总数`() {
        var slotArrays = 0
        var nameTableChecked = false
        for (f in LockWatch::class.java.declaredFields) {
            f.isAccessible = true
            val receiver = if (java.lang.reflect.Modifier.isStatic(f.modifiers)) null else LockWatch
            val value = f.get(receiver) ?: continue
            val len = when (value) {
                is LongArray -> value.size
                is IntArray -> value.size
                is Array<*> -> value.size
                else -> continue
            }
            if (f.name == "LOCK_NAMES") {
                assertEquals(LockWatch.LOCK_COUNT, len, "挂载点名表长度 ≠ LOCK_COUNT")
                nameTableChecked = true
            } else {
                assertEquals(LockWatch.SLOT_COUNT, len, "采样表字段 ${f.name} 的表长不是 SLOT_COUNT")
                slotArrays++
            }
        }
        assertTrue(nameTableChecked, "没读到挂载点名表 LOCK_NAMES（改名要先回类 KDoc 改挂载点表）")
        assertTrue(
            slotArrays >= 9,
            "采样表应有 ≥9 张定长数组（1 张线程名 + 7 张时长/tag + 1 张合并计数），实读 $slotArrays 张",
        )
    }

    /** ④ 锁序表只此一处：表里每条边都带 文件:行号 证据（不许退回散落注释）。 */
    @Test
    fun `锁序表在 LockWatch 类文档里且逐条带行号`() {
        val text = source("src/main/kotlin/com/eratw/port/core/diag/LockWatch.kt")
            ?: throw AssertionError("读不到 LockWatch.kt")
        val table = text.substringAfter("## 锁序表").substringBefore("## 容量与算术")
        for (edge in listOf(
            "pendingLock → GameHost.pauseLock",
            "pendingLock → QueueInputSink",
            "pauseLock → GameSession.backgroundPauseLock",
            "SpritePool 监视器 → GraphicsBuffer 监视器",
            "LineBufferConsoleSink",
            "GameHost.pendingLock → KeyEventInputState.intentLock",
            // OBS2-L2 新增的两条边（erbc 装载链）
            "MappedProgramSource 监视器（erbc 句柄）→ LazyStatementRecords.materializeLock",
            "LazyStatementRecords.materializeLock → FoldAuditState.lock",
        )) {
            assertTrue(edge in table, "锁序表缺边：$edge")
        }
        // 行号是「合并 main 后的盘面」快照：app 侧文件一改就会红，逼着回表里核一遍。
        // OBS2-L2 在 main@ec3e4585 上把全表逐条重核过一次：原表的 :493 / :1703 已被
        // 别的波次插行顶掉（GameHost 的成文约定现在在 :612、pauseLock 取锁在 :1899），
        // 这一行随表同步 ⇒ 有人再插行就把本条点红，而不是让表里的行号悄悄过期。
        assertTrue("GameHost.kt:612" in table && "GameHost.kt:1899" in table, "锁序表必须带 文件:行号")
        assertTrue("叶锁" in table, "锁序表必须写明 LockWatch 的 monitor 是叶锁这条不变式")
        // 两条新边各带自己的取锁点行号（第 8 条在装载链上、第 9 条在 ArgInterner 的审计锁上）。
        assertTrue(
            "ErbcStream.kt:1944" in table && "ArgInterner.kt:1086" in table,
            "OBS2 的两条新边必须各带取锁点行号（seedInterning 的 materializeLock / FoldAuditState.fold）",
        )
        // QueueInputSink 类头要有指路注释（GameHost.kt 不归本路，由组长接线）。
        val sink = source("src/main/kotlin/com/eratw/port/core/proc/QueueInputSink.kt")
            ?: throw AssertionError("读不到 QueueInputSink.kt")
        assertTrue("core/diag/LockWatch.kt" in sink, "QueueInputSink 类头缺「锁序表在 LockWatch」的指路注释")
    }
}
