package com.eratw.port.core.host

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [GraphicsPool] 的 Java 堆侧**主动像素预算**档（fix-pixelbudget 车道，2026-11-06）。
 *
 * 钉的四件事（任务书出口判据 1 逐条对应）：
 * ① 超预算时「重解必得同一张图」那一档确实退回未解码态，`residentBytes()` 跟着降；
 * ② 逐出前后 `isCreated` / 宽 / 高 / 尺寸读数**逐字不变**
 *   （原版 `GraphicsImage.IsCreated` 只看位图在不在，GraphicsImage.cs:44-54；
 *   `ASpriteSingle.IsCreated` 因此逐字不变，ASpriteSingle.cs:24-34 —— 反编译在 ERATW_ROOT 下
 *   reference/emuera-cs，路径与行号沿用 [GraphicsBuffer.evictDeferred] 的既有取证）；
 * ③ **画过的缓冲**（无 deferredSource 的 `GCREATE`/`GCREATEFROMFILE` 已改写、或 `fileSource`
 *   已被 [GraphicsBuffer.markDirty] 清掉的）不被预算档动到；
 * ④ 换代成对记账：反复「逐出 + 重解」后 `residentBytes()` 不漂移、不为负，且与 [GraphicsPool.stats]
 *   的 `pixelBytes` 恒等；清池归零（`removeFromPoolAccounting` 扣不成负是 2026-10-30 审计 #2
 *   的旧病灶，这里在预算档路径上再钉一遍）。
 *
 * 外加：LRU 出队序（accessOrder 表序）、`get()` 里的自动检查点、预算算式的纯 JVM 口径。
 * 全部用**小尺寸**缓冲（4 KiB 像素级）—— 预算档是字节判据，行为与 122 MiB 工作表同形，
 * 单测不拿真尺寸图烧堆。
 */
class GraphicsPoolBudgetTest {

    private val side = 64
    private val sheetPixels = side * side.toLong()          // 4096
    private val sheetBytes = sheetPixels * 4L               // 16384

    @AfterTest
    fun clearOverride() {
        System.clearProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)
    }

    /** 建一张「文件源还在」的 G_ID 工作表并落地像素（口径同 `GCREATEFROMFILE` 的延迟形态）。 */
    private fun materializedSheet(pool: GraphicsPool, id: Int): GraphicsBuffer {
        val buffer = pool.get(id)
        assertTrue(
            buffer.createDeferredFromFile(side, side, "resources/budget-sheet-$id.webp") {
                IntArray(side * side) { idx -> 0x01000000 or idx }
            },
        )
        buffer.pixels // 首访问落地（真实场景里由 GGETCOLOR / GDRAWG 的读触发）
        return buffer
    }

    // ------------------------------------------------------------------
    // ① 超预算 ⇒ 退回未解码态、residentBytes 跟着降
    // ------------------------------------------------------------------

    @Test
    fun `超预算时可重解缓冲退回未解码态且驻留读数跟着降`() {
        val pool = GraphicsPool()
        val a = materializedSheet(pool, 1)
        val b = materializedSheet(pool, 2)
        assertEquals(sheetBytes * 2, pool.residentBytes(), "两张工作表都驻留")

        val freed = pool.evictDeferredPixelsWithinBudget(sheetBytes)

        assertEquals(sheetPixels, freed, "退回一张： freed 按像素条数计（口径同 evictDeferredPixels）")
        assertEquals(sheetBytes, pool.residentBytes(), "驻留读数必须跟着降（换代全走 publishSurfaceLocked）")
        assertEquals(0L, a.pixelBytes, "被退的那张回到未解码态")
        assertEquals(sheetBytes, b.pixelBytes, "预算达标即停，不多退")
        // 重解逐字得同一张图（这是「谁引用都不影响」的全部依据）
        val aPixels = a.pixels
        assertEquals(sheetPixels, aPixels.size.toLong())
        for (idx in 0 until side * side) {
            assertEquals(0x01000000 or idx, aPixels[idx], "重解像素第 $idx 格必须逐字相同")
        }
    }

    // ------------------------------------------------------------------
    // ② isCreated / 宽高 / stats 读数逐字不变
    // ------------------------------------------------------------------

    @Test
    fun `逐出前后 isCreated 宽高与 created 计数逐字不变`() {
        val pool = GraphicsPool()
        val sheet = materializedSheet(pool, 7)
        val beforeCreated = pool.stats().created
        val beforeWidth = sheet.width
        val beforeHeight = sheet.height
        val beforeIsCreated = sheet.isCreated

        pool.evictDeferredPixelsWithinBudget(0L)

        assertEquals(beforeIsCreated, sheet.isCreated, "IsCreated 只看尺寸在不在（GraphicsImage.cs:44-54）")
        assertEquals(beforeWidth, sheet.width, "宽度不得被回收动")
        assertEquals(beforeHeight, sheet.height, "高度不得被回收动")
        assertEquals(beforeCreated, pool.stats().created, "created 计数在回收前后逐字不变")
        assertEquals(0L, pool.residentBytes(), "预算 0 = 尽量退（退化成全表 sweep 的自洽读法）")
    }

    // ------------------------------------------------------------------
    // ③ 画过 / 不可重解的缓冲：第一档（整退）碰不到，第二档（无损降级）收走
    // ------------------------------------------------------------------

    /**
     * 2026-11-04 OOM 波改约：本用例原名「画过的缓冲与无重解源的缓冲不被预算档动到」，
     * 钉的是旧契约「预算档对画过的收 0」。真机 0.2.39+15 五份 512 MiB 报告证明那个契约
     * 留着一条致命缝：常驻的恰恰全是画过的合成图（SPRITECREATE 引用面又被宿主 dispose 档
     * 的 exclude 护住），Java 堆 511/512 后任何 1.44 MiB 分配都是 OutOfMemoryError。
     * 新契约：第一档（evictDeferred，可重解整退）仍然碰不到它们；第二档（无损降级，
     * [GraphicsBuffer.demotePixelsToPng]）把它们压成 PNG 闭包 —— 像素逐字保留、
     * GCREATED/宽高不动、首次真读透明晋升。
     */
    @Test
    fun `画过的缓冲在预算档里被无损降级而不是丢弃`() {
        val pool = GraphicsPool()
        // GCREATE / GCREATEFROMFILE 立即解码的：没有 deferredSource，第一档永远不是候选。
        val painted = pool.get(11)
        assertTrue(painted.createFromArgb(side, side, IntArray(side * side) { 0x7F000001 }))
        // 有文件源但真写过像素的：markDirty 把 fileSource 清掉 ⇒ 第一档判据 2 失效，第二档收走。
        val dirty = materializedSheet(pool, 12)
        dirty.setPixel(0, 0, 0xFF010203.toInt())
        assertNull(dirty.fileSource, "前置：写过像素后文件源必须已作废")

        val freed = pool.evictDeferredPixelsWithinBudget(0L)

        assertEquals(sheetPixels * 2, freed, "两张画过的都走降级档：按像素条数计（口径同 evictDeferred）")
        // 像素阵列确实退场，可两份 PNG 载荷仍占着 Java 堆 ⇒ 账本记的是「差额」不是 0。
        val payloads = painted.retainedBytes + dirty.retainedBytes
        assertTrue(payloads > 0L, "降级载荷必须还在账本里")
        assertEquals(payloads, pool.residentBytes(), "驻留记账按真实字节降（换代全走 publishSurfaceLocked）")
        // 脚本可见面逐字不动：GCREATED 仍真、宽高原样 —— 这些只有 dispose 才会动
        // （GraphicsImage.cs:44-54 的 IsCreated 只看位图在不在）。
        assertTrue(painted.isCreated, "降级不等于 dispose：GCREATED 不许翻 false")
        assertTrue(dirty.isCreated)
        assertEquals(side, painted.width)
        assertEquals(side, dirty.height)
        // 内容无损：首次真读透明晋升，逐字一致。
        assertEquals(0x7F000001, painted.pixelAt(0, 0))
        assertEquals(0xFF010203.toInt(), dirty.pixelAt(0, 0), "脚本画上去的像素必须还在")
        assertEquals(sheetBytes * 2, pool.residentBytes(), "晋升后记账复原")
    }

    @Test
    fun `releaseUnused 的触发面不因预算档放宽`() {
        // 预算档只走 evictDeferred（第一档）与 demote（第二档）；真 dispose 那一档
        // （releaseUnused）看 exclude/minPixels，两条预算档都不得把缓冲推进 dispose 面。
        val pool = GraphicsPool()
        val sheet = materializedSheet(pool, 21)
        // 预算档先把可重解的退回未解码态
        pool.evictDeferredPixelsWithinBudget(0L)
        assertEquals(0L, pool.residentBytes())
        // 画过的一张：第二档把它无损降级（像素退场），但 isCreated 必须仍真 ——
        // dispose 是 releaseUnused 的职权（要看 exclude/minPixels），预算档永远代不了班。
        val painted = pool.get(22)
        assertTrue(painted.createFromArgb(side, side, IntArray(side * side)))
        pool.evictDeferredPixelsWithinBudget(0L)
        assertTrue(painted.retainedBytes > 0L, "降级载荷仍在 Java 堆里")
        assertEquals(painted.retainedBytes, pool.residentBytes(), "画过的那张像素阵列退场，载荷仍计入账本")
        assertTrue(painted.isCreated, "降级不等于 dispose：GCREATED 不许翻 false")
        assertEquals(0x00000000, painted.pixelAt(0, 0), "createFromArgb 全零图：晋升回来还是全零")
        assertEquals(sheetBytes, pool.residentBytes(), "晋升后记账复原")
        assertTrue(sheet.isCreated)
    }

    // ------------------------------------------------------------------
    // ④ 成对记账：反复逐出 + 重解不漂移、不为负、清池归零
    // ------------------------------------------------------------------

    @Test
    fun `反复逐出与重解后驻留记账不漂移不为负`() {
        val pool = GraphicsPool()
        val a = materializedSheet(pool, 31)
        val b = materializedSheet(pool, 32)
        for (round in 1..8) {
            a.pixels; b.pixels // 重解再驻留
            assertEquals(sheetBytes * 2, pool.residentBytes(), "第 $round 轮：落地后记账")
            assertEquals(pool.stats().pixelBytes, pool.residentBytes(), "第 $round 轮：原子计数与遍历读数同值")
            pool.evictDeferredPixelsWithinBudget(0L) // 全退
            assertEquals(0L, pool.residentBytes(), "第 $round 轮：退完后必须恰好归零（不为负、不虚高）")
            assertTrue(pool.residentBytes() >= 0L)
        }
        // 回收态（accountedBytes 已归零）的条目被移出池：不得扣成负数（审计 #2 的病灶在预算路径复验）
        pool.clear()
        assertEquals(0L, pool.residentBytes(), "清池后恰好归零")
    }

    // ------------------------------------------------------------------
    // LRU：最久未访问先出
    // ------------------------------------------------------------------

    @Test
    fun `预算只够一张时最久未访问的先出`() {
        val pool = GraphicsPool()
        val firstBuilt = materializedSheet(pool, 41)
        val secondBuilt = materializedSheet(pool, 42)
        // 脚本此刻正在用 41 号（建表更早，但刚被语句链碰过）：accessOrder 把它挪到 MRU 端，
        // 表序变成 [42, 41] ⇒ 出队的是「最久没被碰」的 42 号，而不是「建得最早」的那张。
        assertSame(firstBuilt, pool.get(41))

        val freed = pool.evictDeferredPixelsWithinBudget(sheetBytes)

        assertEquals(sheetPixels, freed)
        assertEquals(sheetBytes, firstBuilt.pixelBytes, "刚被访问过的必须留在预算内")
        assertEquals(0L, secondBuilt.pixelBytes, "最久未访问的先出（哪怕它建得更晚）")
    }

    // ------------------------------------------------------------------
    // get() 的自动检查点（不再只靠进程级水位）
    // ------------------------------------------------------------------

    @Test
    fun `超预算后任意一条语句点的 get 就触发自动退回`() {
        System.setProperty(
            GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY,
            sheetBytes.toString(), // 复刻设备挡位的形状：预算 = 恰好一张表
        )
        val pool = GraphicsPool()
        val a = materializedSheet(pool, 51)
        materializedSheet(pool, 52)
        assertEquals(sheetBytes * 2, pool.residentBytes(), "落地瞬间超预算（像素落地不经池，检查点在下一个语句点）")

        pool.get(53) // 下一条 G 语句的 readGraphics 就是这个入口

        assertEquals(sheetBytes, pool.residentBytes(), "自动档把驻留拉回预算内")
        assertEquals(0L, a.pixelBytes, "LRU 头（51 号）先退")
    }

    // ------------------------------------------------------------------
    // 预算算式（纯 JVM 口径，禁 Android API）
    // ------------------------------------------------------------------

    @Test
    fun `预算算式为堆上限除以四且纯 JVM 可核`() {
        System.clearProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY)
        assertEquals(4L, GraphicsPool.PIXEL_BUDGET_DIVISOR)
        assertEquals(
            Runtime.getRuntime().maxMemory() / GraphicsPool.PIXEL_BUDGET_DIVISOR,
            GraphicsPool.defaultPixelBudgetBytes(),
            "算式：预算 = Runtime.maxMemory() ÷ 4（像素驻留 KDoc 的论据 1/2）",
        )
        // 512 MiB 真机挡 ⇒ 128 MiB；一张 8000×4000 工作表 = 122.1 MiB 必须容得下
        val deviceBudget = 512L * 1024 * 1024 / GraphicsPool.PIXEL_BUDGET_DIVISOR
        assertEquals(128L * 1024 * 1024, deviceBudget)
        val worksheetBytes = 8000L * 4000L * 4L
        assertTrue(worksheetBytes < deviceBudget, "单张工作表 $worksheetBytes 必须小于一挡预算")
        assertTrue(worksheetBytes * 2 > deviceBudget, "两张工作表叠驻留必须超预算（否则预算档形同虚设）")
    }

    @Test
    fun `覆写性质只认正数且每读现取不缓存`() {
        System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, "12345")
        val pool = GraphicsPool()
        assertEquals(12345L, pool.pixelBudgetBytes())
        System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, "not-a-number")
        // 坏值 = 回落默认算式（宁按 maxMemory÷4 也不拿 0 / 负数当预算乱退）
        assertEquals(GraphicsPool.defaultPixelBudgetBytes(), pool.pixelBudgetBytes())
    }

    // ------------------------------------------------------------------
    // 护持者（组长验收补）：池里唯一一张可退项自己超预算时，不许退它
    // ------------------------------------------------------------------

    /**
     * 少这道护栏的形状是真会出事的：小挡位机型 + 一张整工作表 ⇒ 预算比那一张还小 ⇒
     * 「退到预算内」永远做不到。届时 [GraphicsPool.get] 的自动检查点每次都会退掉调用方
     * 正要用的那一张，下一次 `pixels` 整图重解 —— 每条 G 语句解码一次，比不加预算更糟。
     */
    @Test
    fun `池里唯一一张且超预算时护持者不被逐出`() {
        val pool = GraphicsPool()
        val only = materializedSheet(pool, 7)
        assertEquals(sheetBytes, pool.residentBytes())

        assertEquals(
            0L,
            pool.evictDeferredPixelsWithinBudget(sheetBytes - 1L, protect = only),
            "护持者就是全池唯一可退项时，必须什么都不退",
        )
        assertEquals(sheetBytes, pool.residentBytes(), "没退成功就不该动驻留记账")
        assertEquals(sheetBytes, only.pixelBytes, "刚交出去的那一张必须还在驻留态")

        // 对照：同一判据去掉护持者 ⇒ 它被退掉。这一条证明上面的 0 是护栏给的，
        // 不是判据压根没触发（否则本用例改成「永远返回 0」也会绿）。
        assertEquals(
            sheetPixels,
            pool.evictDeferredPixelsWithinBudget(sheetBytes - 1L),
            "无护持者时同一张必须可退",
        )
    }

    /** 走 [GraphicsPool.get] 的自动检查点复现同一形状（预算覆写成 1 字节，池里只有一张图）。 */
    @Test
    fun `get 的自动检查点不会退掉刚交出去的那一张`() {
        System.setProperty(GraphicsPool.PIXEL_BUDGET_OVERRIDE_PROPERTY, "1")
        val pool = GraphicsPool()
        val buffer = pool.get(9)
        assertTrue(
            buffer.createDeferredFromFile(side, side, "resources/budget-sheet-9.webp") {
                IntArray(side * side) { idx -> 0x01000000 or idx }
            },
        )
        buffer.pixels
        assertEquals(sheetBytes, pool.residentBytes(), "像素已落地")

        val again = pool.get(9)
        assertSame(buffer, again)
        assertEquals(sheetBytes, pool.residentBytes(), "预算 1 字节也退不掉正在被取用的这一张")
        assertEquals(sheetBytes, again.pixelBytes)
    }

    // ------------------------------------------------------------------
    // 画过的缓冲的预算档（releaseUnused 的 budgetBytes，2026-11-01 收件箱设备实证）
    // ------------------------------------------------------------------

    /** 一张「脚本画过」的缓冲（无重解源、fileSource 已作废）：只有预算档能动它。 */
    private fun painted(pool: GraphicsPool, id: Int): GraphicsBuffer {
        val buffer = pool.get(id)
        assertTrue(buffer.createFromArgb(side, side, IntArray(side * side) { 0x7F000001 }))
        return buffer
    }

    @Test
    fun `预算档只收到预算内并按 LRU 出队`() {
        val pool = GraphicsPool()
        val first = painted(pool, 61)
        val third = painted(pool, 62)
        val second = painted(pool, 63)
        assertEquals(sheetBytes * 3, pool.residentBytes(), "三张画过的都驻留")

        // 脚本此刻正在用 second：表序变成 [first, third, second]（头部 = 最久没碰过）
        assertSame(second, pool.get(63))

        val result = pool.releaseUnused(exclude = emptySet(), minPixels = 1, budgetBytes = sheetBytes)

        assertEquals(2, result.buffers, "够预算就停：三张里只退两张")
        assertEquals(sheetBytes * 2, result.bytes, "读数按实际退掉的像素字节算")
        assertEquals(sheetBytes, pool.residentBytes(), "驻留跟着降（换代全走 publishSurfaceLocked）")
        assertFalse(first.isCreated, "最久未访问的最先出")
        assertFalse(third.isCreated)
        assertTrue(second.isCreated, "刚被访问过的那张留下")
        assertEquals(side, second.width, "留下的那张几何原样")
    }

    @Test
    fun `预算档尊重 exclude 文件源与最小像素门槛`() {
        val pool = GraphicsPool()
        val protectedBuffer = painted(pool, 71)
        val reusable = materializedSheet(pool, 72)
        val tiny = pool.get(73)
        assertTrue(tiny.createFromArgb(4, 4, IntArray(16)))

        val result = pool.releaseUnused(
            exclude = setOf(protectedBuffer),
            minPixels = 64 * 64,
            budgetBytes = 0L,
        )

        assertEquals(0, result.buffers, "够格的被 exclude 挡住；文件源条目与过小的都不该动")
        assertEquals(sheetBytes * 2 + 64L, pool.residentBytes(), "一分都没退（64 B = 4×4×4）")
        assertTrue(protectedBuffer.isCreated)
        assertTrue(tiny.isCreated)
        assertEquals(sheetBytes, reusable.pixelBytes, "文件源条目留给 evictDeferred 那一档")

        // 对照：同一个判据去掉 exclude ⇒ 被护住的那张立刻可退。这一条证明上面的 0 是排除集给的，
        // 不是判据压根没触发（否则本用例改成「永远返回 0」也会绿）。
        assertEquals(
            1,
            pool.releaseUnused(emptySet(), 64 * 64, budgetBytes = 0L).buffers,
            "去掉 exclude 后同一张必须可退",
        )
    }

    @Test
    fun `预算档在预算内零动作而缺省口径维持原样`() {
        val pool = GraphicsPool()
        val kept = painted(pool, 81)
        assertEquals(sheetBytes, pool.residentBytes())

        assertEquals(
            0,
            pool.releaseUnused(emptySet(), 1, budgetBytes = sheetBytes).buffers,
            "已在预算内 ⇒ 什么都不退（这一发只付一次原子读）",
        )
        assertEquals(sheetBytes, pool.residentBytes())

        // 缺省 null = 92% 水位那一档与 onTrimMemory 强档请求的旧口径：不看预算，够大的一律 dispose。
        val all = pool.releaseUnused(emptySet(), 1)
        assertEquals(1, all.buffers, "缺省口径不受预算约束（与加参数之前逐字相同）")
        assertEquals(0L, pool.residentBytes())
        assertFalse(kept.isCreated)
    }
}
