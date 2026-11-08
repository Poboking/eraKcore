package com.eratw.port.core.host

import com.eratw.port.core.loader.ProgramLoader
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [MemoryTier] 的档位判式 + 两道 core 侧内存闸在两个档位下的读数（2026-11-03 分档刀）。
 *
 * ## 这条刀的前提，用例先把它钉住
 *
 * Java 堆硬上限应用侧抬不动：`largeHeap` 在框架里就是
 * `ActivityThread.handleBindApplication` 那句 `VMRuntime.clearGrowthLimit()`
 * （AOSP frameworks/base `main@1cdfff555f4a`
 * `core/java/android/app/ActivityThread.java:7649-7656`），顶到 `dalvik.vm.heapsize` 为止。
 * 真机读数（vivo V2199A / API 34）：heapgrowthlimit=256m、heapsize=512m ⇒ 512 MiB 已是顶格。
 * 所以本刀只动**自家写的闸**，而自家闸只允许在「API >= 26 且堆 >= 512 MiB」时抬 ——
 * 两个条件各钉一头，少任何一个都算回归。
 *
 * ## 为什么性质缺失必须等于保守档
 *
 * 桌面装置、黄金基准、`:tools:vmdbg` 清扫都不落性质 ⇒ 必须逐字维持加刀前的行为，
 * 否则本刀会改动语料闸与基准的口径。「性质缺失 = 保守档」由同名用例钉住。
 *
 * 用例一律走**显式入参**（注入堆读数 / 两入参判式），不把断言挂在测试 JVM 自己的堆挡位上
 * —— 那条挡位能被 `-PtestXmx` 改，挂了就会在别人的挡位实验里假红或假跳过。
 */
class MemoryTierTest {

    private val mib: Long = 1024L * 1024L

    @AfterTest
    fun clearProperty() {
        // 性质是进程级的：不清掉会污染同一 JVM 里后面的用例。
        System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
    }

    // ---- 判式本体（两入参，不碰任何进程读数）----

    @Test
    fun `版本线钉在 API 26`() {
        // 25 = Android 7.1：位图还压在本挡 Java 堆上，端点里那份「连 16 字节都分配不出来」
        // 的黑鲨 SKW-A0 属于这一族 ⇒ 必须留在保守档，在那种机上多放行只是把闪退提前。
        assertFalse(MemoryTier.isGenerous(sdkInt = 25, maxMemoryBytes = 512 * mib))
        assertTrue(MemoryTier.isGenerous(sdkInt = 26, maxMemoryBytes = 512 * mib))
        assertTrue(MemoryTier.isGenerous(sdkInt = 36, maxMemoryBytes = 512 * mib))
        assertEquals(26, MemoryTier.GENEROUS_FROM_SDK_INT)
    }

    @Test
    fun `堆线钉在 512 MiB 而 256 挡的新机型也不许放开`() {
        // 第二条线是**产品护栏**：ProgramLoader 的「2026-11-03 重标」一节写明 256 MiB 挡
        // 恒判不足是有意的（该挡游玩期连图像预算都放不下）。按 API 级别单腿放开会打穿它。
        assertFalse(MemoryTier.isGenerous(sdkInt = 34, maxMemoryBytes = 256 * mib))
        assertTrue(MemoryTier.isGenerous(sdkInt = 34, maxMemoryBytes = 512 * mib))
        // 恰好等于线放行（判据是 >=，不是 >）：512m 挡就是真机的常态读数。
        assertFalse(MemoryTier.isGenerous(sdkInt = 34, maxMemoryBytes = 512 * mib - 1L))
        assertEquals(512L, MemoryTier.GENEROUS_MIN_MAX_MEM_MIB)
    }

    @Test
    fun `性质缺失等于保守档`() {
        System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
        assertEquals(MemoryTier.SDK_INT_UNKNOWN, MemoryTier.sdkInt(), "性质没落就是「不知道」，不许猜一个挡位")
        assertFalse(MemoryTier.isGenerous(), "桌面装置/单测/vmdbg 不许被本刀改变口径")
    }

    @Test
    fun `宿主落的性质就是现取的版本线`() {
        System.setProperty(MemoryTier.SDK_INT_PROPERTY, "34")
        assertEquals(34, MemoryTier.sdkInt())
        assertTrue(MemoryTier.isGenerous(512 * mib))
        System.setProperty(MemoryTier.SDK_INT_PROPERTY, "25")
        assertFalse(MemoryTier.isGenerous(512 * mib))
        // 脏值（空串/非数字）不许抛，一律按「不知道」= 保守档。
        System.setProperty(MemoryTier.SDK_INT_PROPERTY, "  ")
        assertEquals(MemoryTier.SDK_INT_UNKNOWN, MemoryTier.sdkInt())
        System.setProperty(MemoryTier.SDK_INT_PROPERTY, "abc")
        assertFalse(MemoryTier.isGenerous(512 * mib))
    }

    // ---- 闸①：图形像素预算恒 ÷4（不随档位放开） ----

    /**
     * 接线与读数一起钉：注入 512 MiB 的堆（不依赖测试 JVM 自己的挡位），性质落下也仍是 ÷4。
     * 这一档量的是 Dalvik 堆里的 `IntArray` 像素，`SpriteBitmapSource` 那套「API >= 26 位图
     * 在 native 堆」的前提对它不成立；抬到 ÷3（512 挡 170 MiB）等于从「3/4 留给装载基线与
     * 瞬时拷贝」的余量里划走 42 MiB。2026-11-04 真机报告（LOOK_AT 差分立绘合成）就是这份
     * 余量见底的形状：Java 堆 511/512 MiB、正号段驻留 248 MiB、`blitFrom` 0.5 MiB 分配 OOM。
     */
    @Test
    fun `像素预算恒读 ÷4 不随档位放开`() {
        val max512 = 512L * mib
        try {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
            assertEquals(max512 / 4L, GraphicsPool.defaultPixelBudgetBytes(max512), "没落性质 ⇒ ÷4")
            System.setProperty(MemoryTier.SDK_INT_PROPERTY, "34")
            assertEquals(max512 / 4L, GraphicsPool.defaultPixelBudgetBytes(max512), "性质落下也仍旧 ÷4（本档不许放开）")
            // 256 MiB 挡同样 ÷4（判式第二条线在这里不再是放大条件）。
            val max256 = 256L * mib
            assertEquals(max256 / 4L, GraphicsPool.defaultPixelBudgetBytes(max256))
            // 缺省参 = 本进程现取的那一发堆（与显式入参必须同值，否则报告与引擎两张皮）。
            assertEquals(
                GraphicsPool.defaultPixelBudgetBytes(Runtime.getRuntime().maxMemory()),
                GraphicsPool.defaultPixelBudgetBytes(),
            )
        } finally {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
        }
        assertEquals(4L, GraphicsPool.PIXEL_BUDGET_DIVISOR)
        // 512 挡的 128 MiB 预算要容得下一张整工作表（8000x4000x4B = 122.1 MiB），
        // 但**装不下两张**（244 MiB）：÷4 既保住一张常驻，也把两张那一步挡在预检余量之外。
        val oneSheet = 8000L * 4000L * 4L
        val budgetOn512 = max512 / GraphicsPool.PIXEL_BUDGET_DIVISOR
        assertTrue(budgetOn512 >= oneSheet, "512 挡预算要容得下一张整工作表")
        assertTrue(oneSheet * 2L > budgetOn512, "但两张（244 MiB）必须仍然越线、下一个语句点照常逐出")
    }

    // ---- 闸④：装载预检的产物系数 0.8 / 0.7 ----

    @Test
    fun `预检系数放开档只降一档且护栏挡位不变`() {
        // 4.981 真产物读数（ProgramLoader KDoc 那张表）：385,025,304 B / 5,462,998 指令。
        val artifact = 385_025_304L
        val conservative = ProgramLoader.estimatedHeapBytes(artifact, 0L, 5_462_998, generous = false)
        val generous = ProgramLoader.estimatedHeapBytes(artifact, 0L, 5_462_998, generous = true)
        // 整数除法逐值钉：保守档 ×4/5、放开档 ×7/10。
        assertEquals(artifact * 4L / 5L, conservative)
        assertEquals(artifact * 7L / 10L, generous)
        assertTrue(generous < conservative, "放开档必须比保守档少报（这一刀的方向就是把需求报低一档）")
        // 缺省参 = 保守档 ⇒ 既有调用侧（桌面装置、vmdbg、黄金基准）逐字不变。
        assertEquals(conservative, ProgramLoader.estimatedHeapBytes(artifact, 0L, 5_462_998))
        // 护栏复算（不依赖档位）：4.980 全量语料按保守档报出的需求（产物 + 侧车那笔 ≈ 258.3 MiB，
        // 与 ProgramLoader KDoc「两者取大」一行同读数）仍然高出 256 MiB 挡 ⇒ 恒判不足这条护栏
        // 没被本刀动到。**侧车项不能省**：只算产物是 251.2 MiB，反而显得护栏不存在。
        val meta4980 = 49_178_605L // 46.9 MiB（KDoc 标定表用的侧车读数）
        val required4980 = ProgramLoader.estimatedHeapBytes(329_311_670L, meta4980, 4_888_249)
        assertTrue(
            required4980 > 256L * mib,
            "4.980 在 256 MiB 挡恒判不足这条护栏不许被本刀动到：实际 $required4980 B",
        )
        // 而同一份产物在放开档（>= 512 MiB 的机子）少报的只有产物项那 10%：
        val generous4980 = ProgramLoader.estimatedHeapBytes(329_311_670L, meta4980, 4_888_249, generous = true)
        assertTrue(generous4980 < required4980)
        assertEquals(329_311_670L / 10L, required4980 - generous4980)
    }
}
