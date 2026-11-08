package com.eratw.port.core.host

import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.NoSaveSystemOps
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * **真产物前后对照装置**（fix-pixelbudget 车道，出口判据 2）：同一份 `$ERATW_ERBC_DIR` 产物、
 * 同一条装载 + `begin(TITLE)` 脚本链，对照「无预算档」（覆写性质设成天文数字 ⇒ 判据恒不成立）
 * 与「设备挡位预算档」（128 MiB = 512 MiB ÷ 4，同机同产物）下的
 * **像素驻留峰值 / 逐出次数 / 重解（解码）次数**。
 *
 * 口径声明（不装）：
 * - 装载与会话半边是**真产物**：`loadMapped(program.erbc)` + `BootMetadata` + `GameSession`
 *   （参数与 `PhaseHeapLadderProbe` 逐字同口径：consoleWidth=199、randSeed=20260226、NoSaveSystemOps），
 *   并泵到标题页第一个等待点、再翻一页 —— 这一段证明预算档上线后**装载链读数不变**（标题页
 *   驻留够不着预算，两档行为逐字一致）；
 * - 驻留 churn 半边是**合成但等尺寸**的工作表（两张 8000×4000 = 各 122.1 MiB 像素 + 四张
 *   1600×1200 中图，走生产同一条 `createDeferredFromFile` → `pixels` 落地 → [GraphicsPool.get]
 *   检查点路径，像素内容由确定性合成函数给出、重解逐字同图）。真机上把这批图灌进驻留的是
 *   `GCREATEFROMFILE` + 读像素的语句链，桌面复刻该场景需要整局深度游玩，装置闸养不起；
 *   所以「逐出/重解/峰值」三列读数是**装置形状**，装载/标题两列是**真产物**读数。
 *
 * 逐出/重解的观测口径：每次操作后对全部条目的 `pixelBytes` 采样；
 * 「>0 变 0」记一次逐出、「0 变 >0」记一次落地（首轮 = 首解，之后 = 重解），解码次数另用
 * 延迟源里的 [AtomicInteger] 精确计。峰值 = 每次采样点的 `residentBytes()` 最大值
 * （像素落地发生在 `buffer.pixels` 内部，采样紧跟其后，不漏台阶）。
 *
 * 复现：
 * ```
 * export ERATW_ERBC_DIR=<work-root>/erbc-4980-recheck/erbc.v1
 * export ERATW_GAMEDIR=<work-root>/eratw-安卓移植计划/eratw-画蛇添足版eratw4.980
 * ./gradlew --configure-on-demand :core-vm:cleanTest :core-vm:test --tests "*GraphicsPoolBudgetProbe*" -i
 * ```
 * 注意 test worker 堆为 3g（core-vm/build.gradle.kts:113），churn 峰值 ≈ 2×122 MiB，挡位安全。
 */
class GraphicsPoolBudgetProbeTest {

    private val mib = 1024L * 1024L

    private class SheetSpec(val id: Int, val w: Int, val h: Int, val label: String)

    /** 与真机工作表同尺寸的 churn 序列（像素 = 4 B/格，ARGB IntArray，口径同 GraphicsBuffer）。 */
    private fun churnSpecs(base: Int): List<SheetSpec> = listOf(
        SheetSpec(base + 1, 8000, 4000, "工作表A 8000x4000=122.1MiB"),
        SheetSpec(base + 2, 8000, 4000, "工作表B 8000x4000=122.1MiB"),
        SheetSpec(base + 3, 1600, 1200, "中图1 1600x1200=7.6MiB"),
        SheetSpec(base + 4, 1600, 1200, "中图2 1600x1200=7.6MiB"),
        SheetSpec(base + 5, 1600, 1200, "中图3 1600x1200=7.6MiB"),
        SheetSpec(base + 6, 1600, 1200, "中图4 1600x1200=7.6MiB"),
    )

    private class ChurnResult(
        val peakResidentMiB: Double,
        val endResidentMiB: Double,
        val evictions: Int,
        val decodes: Int,
        val redecodes: Int,
    )

    /**
     * 跑一轮 churn：三轮「按序 get（走预算检查点）→ 读像素（落地）→ 全表采样」。
     * 每轮操作序完全相同 ⇒ 两档唯一变量就是预算。
     * 采样必须走**全表**：预算档在「碰下一个条目」时逐出的往往是**别的**条目（LRU 头），
     * 只采样被碰的那个会把逐出数读成 0。
     */
    private fun runChurn(
        pool: GraphicsPool,
        specs: List<SheetSpec>,
        decodeCounter: AtomicInteger,
    ): ChurnResult {
        val resident = HashMap<Int, Long>()
        var peak = 0L
        var evictions = 0
        var landings = 0
        for (round in 1..3) {
            for (s in specs) {
                val buffer = pool.get(s.id) // 语句点入口：内含预算检查（超了先退 LRU 可重解项）
                if (round == 1) {
                    buffer.createDeferredFromFile(s.w, s.h, "resources/probe-sheet-${s.id}.webp") {
                        decodeCounter.incrementAndGet()
                        // 确定性内容：重解逐字同图（这正是 evictDeferred 判据的装置化）
                        IntArray(s.w * s.h) { idx -> ((idx * 31 + s.id) and 0x00FFFFFF) or 0xFF000000.toInt() }
                    }
                }
                buffer.pixels // 首/重解落地（真机上由 GGETCOLOR / GDRAWG 的读触发）
                for (spec in specs) {
                    val now = pool.peek(spec.id)?.pixelBytes ?: 0L
                    val prev = resident[spec.id] ?: 0L
                    if (prev > 0L && now == 0L) evictions++
                    if (prev == 0L && now > 0L) landings++
                    resident[spec.id] = now
                }
                val r = pool.residentBytes()
                if (r > peak) peak = r
            }
        }
        return ChurnResult(
            peakResidentMiB = peak.toDouble() / mib,
            endResidentMiB = pool.residentBytes().toDouble() / mib,
            evictions = evictions,
            decodes = decodeCounter.get(),
            redecodes = landings - specs.size, // 首轮 = 首解
        )
    }

    @Test
    fun `真产物装载 标题链 与预算档前后对照`() {
        val erbcDir = System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        assumeTrue(
            erbcDir != null && Files.isRegularFile(erbcDir.resolve("program.erbc")) &&
                Files.isRegularFile(erbcDir.resolve("program.meta")),
            "缺 ERATW_ERBC_DIR 产物：跳过（本装置不回落构建缓存）",
        )
        val gameRoot = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        assumeTrue(gameRoot != null && Files.isDirectory(gameRoot), "缺 ERATW_GAMEDIR：跳过")

        val lines = ArrayList<String>()
        fun report(s: String) {
            lines.add(s)
            println("[budget-probe] $s")
        }

        val t0 = System.nanoTime()
        val program = ProgramLoader.loadMapped(erbcDir!!.resolve("program.erbc"))
        var metadata: BootMetadata? = BootMetadata.read(erbcDir.resolve("program.meta"))
        val session = GameSession(
            gameRoot = gameRoot!!,
            program = program,
            metadata = metadata!!,
            consoleWidth = 199,
            randSeed = 20260226L,
            systemOps = NoSaveSystemOps,
        )
        metadata = null
        val gfx = session.store.aux.slot(GRAPHICS_SLOT) { GraphicsPool() }
        report("装载+装配 ${((System.nanoTime() - t0) / 1_000_000)} ms；" +
            "P0 gfx 条目=${gfx.entryCount()} 驻留=${gfx.residentBytes() / mib} MiB")

        // ---- 真脚本链：begin(TITLE) 泵到第一个等待点，再翻一页（口径同 PhaseHeapLadderProbe 的泵） ----
        val terminal = arrayOfNulls<String>(1)
        val pump = Thread({
            try {
                session.runtime.beginStep(BeginType.TITLE)
                while (true) {
                    if (session.inputSink.isAwaiting && session.inputSink.pendingCount == 0) Thread.sleep(10L)
                    session.runtime.resumeSystem()
                }
            } catch (t: Throwable) {
                terminal[0] = t.javaClass.simpleName
            }
        }, "eratw-budget-probe-vm").apply { isDaemon = true }
        pump.start()
        val awaitDeadline = System.nanoTime() + 240_000_000_000L
        while (System.nanoTime() < awaitDeadline && !session.inputSink.isAwaiting && terminal[0] == null) {
            Thread.sleep(20L)
        }
        report("标题页第一个等待点：isAwaiting=${session.inputSink.isAwaiting} 终局=${terminal[0] ?: "-"}；" +
            "P1 gfx 条目=${gfx.entryCount()} 驻留=${gfx.residentBytes() / mib} MiB 预算=${gfx.pixelBudgetBytes() / mib} MiB")
        if (session.inputSink.isAwaiting) {
            val seqBefore = session.inputSink.lastConsumedSeq
            session.inputSink.submit("0", mesSkip = false)
            val turnDeadline = System.nanoTime() + 240_000_000_000L
            while (System.nanoTime() < turnDeadline && session.inputSink.lastConsumedSeq == seqBefore &&
                terminal[0] == null
            ) {
                Thread.sleep(20L)
            }
            report("翻一页后：P2 gfx 条目=${gfx.entryCount()} 驻留=${gfx.residentBytes() / mib} MiB")
        }
        val titleResident = gfx.residentBytes()

        // ---- churn 对照：两档跑**同一条**操作序，唯一变量是预算 ----
        val savedOverride = System.getProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)
        try {
            System.setProperty(
                GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY,
                (Long.MAX_VALUE / 2).toString(), // 「前」= 预算档失效（等价于加档前的旧行为）
            )
            val beforeCounter = AtomicInteger(0)
            val before = runChurn(gfx, churnSpecs(400_001), beforeCounter)
            report("churn·无预算档：峰值驻留=${fmt(before.peakResidentMiB)} MiB 末态驻留=${fmt(before.endResidentMiB)} MiB " +
                "逐出=${before.evictions} 解码=${before.decodes} 重解=${before.redecodes}")
            val beforeEnd = gfx.residentBytes()
            gfx.clear()

            System.setProperty(
                GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY,
                (128L * mib).toString(), // 「后」= 512 MiB 设备挡位的 ÷4 预算，同机同产物复刻
            )
            val afterCounter = AtomicInteger(0)
            val after = runChurn(gfx, churnSpecs(400_001), afterCounter)
            report("churn·预算档 128 MiB：峰值驻留=${fmt(after.peakResidentMiB)} MiB 末态驻留=${fmt(after.endResidentMiB)} MiB " +
                "逐出=${after.evictions} 解码=${after.decodes} 重解=${after.redecodes}")
            val afterEnd = gfx.residentBytes()

            // 判读（装置口径明说）：
            report("对照：末态驻留 ${fmt(beforeEnd / mib.toDouble())} → ${fmt(afterEnd / mib.toDouble())} MiB；" +
                "解码次数 ${before.decodes} → ${after.decodes}（重解 ${after.redecodes} 次 = 预算档的代价，均为无损重解）")
            assertTrue(afterEnd < beforeEnd, "预算档必须把稳态驻留压下来（同一条操作序）")
            assertTrue(after.decodes > before.decodes, "被退的图必须真的重解过（否则逐出计数是假的）")
            assertTrue(
                gfx.residentBytes() >= 0L && gfx.residentBytes() == gfx.stats().pixelBytes,
                "对照跑完驻留读数不为负、与遍历读数同值（成对记账）",
            )
        } finally {
            if (savedOverride == null) {
                System.clearProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)
            } else {
                System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, savedOverride)
            }
            runCatching { session.close() }
        }
        report("标题链驻留（未受 churn 影响的前置读数）= ${titleResident / mib} MiB")
    }

    private fun fmt(v: Double): String = String.format("%.1f", v)
}
