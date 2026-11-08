package com.eratw.port.core.host

import java.lang.management.ManagementFactory
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [GraphicsBuffer.blitFrom] 的**取样通道**口径（6493950e「非同源贴图不再整窗拷快照」的可证伪钉）。
 *
 * 两条通道、两个判据：
 * - **自贴（`src === this`）**：先把采样窗口拷成快照再逐像素写 —— 写 dest 会覆盖尚未读到的
 *   源像素，不冻结读到的是「贴了一半」的新内容。判据摘掉（自贴也直读）⇒
 *   `自贴重叠区按冻结快照` / `自贴缩放取样也按冻结快照` 红。
 * - **非同源**：src 与 dest 是两份独立像素数组，写入 dest 碰不到 src，直接按源下标取样。
 *   语义与「先拷快照」逐比特同值（快照取的就是取样瞬间的源内容），但每次 GDRAWG 白烧一次
 *   整窗分配 —— 真机 512 MiB 挡两次 blitFrom OOM 都死在这（0.2.39 LOOK_AT 0.5 MiB +
 *   EVENTTRAIN 1.5 MiB，端点报告 2026-11-03 233215）。判据摘掉（非同源也整窗拷）⇒
 *   `非同源路径不物化快照` 的分配读数红。
 *
 * 原版口径（C#）：GDRAWG 的 dst/src 都按编号取 `GraphicsImage`
 * （FunctionMethodCreator.cs:7252-7261，同号即同一实例）；`g = Graphics.FromImage(RealBitmap)`
 * （GraphicsImage.cs:104-117）而 `GetBitmap()` 返回同一份 `RealBitmap`（GraphicsImage.cs:23-34）
 * ⇒ 自贴是「同一张 Bitmap 画回自己」，GDI+ 实测（2026-11-04 Windows System.Drawing 探针）
 * 对重叠区仍读**原值**（8 像素行右移 1 像素得 01,01,02,03,04,05,06,07，不是边读边写的 01,01,01,...）
 * —— 冻结通道与原版可见行为一致；非同源则是两张独立 Bitmap，本无读改交叠。
 * 黄金基准对自贴的钉法是 pc-golden-root.txt:652 `GDRAWG=1`（GDRAWG(10,10,30,0,...)，
 * src/dest 矩形不相交、只钉返回值），重叠区的像素语义由本文件补钉。
 *
 * sprite 层的同口径既有钉：GdrawSpriteCompositeTest `自贴图按冻结快照而不是边读边写`
 * （经 GDRAWSPRITE → blitFrom，src === this 形态）；本文件直接打 [GraphicsBuffer.blitFrom]。
 */
class GraphicsBufferBlitFromTest {

    /** 不透明的红色阶样本：`0xFFRR0000`，RR = [1..255]。alpha=255 走 blendPixel 的直写支，结果确定。 */
    private fun red(r: Int): Int = 0xFF000000.toInt() or (r shl 16)

    private fun row(buf: GraphicsBuffer, w: Int): IntArray = IntArray(w) { buf.pixelAt(it, 0) }

    private fun column(buf: GraphicsBuffer, h: Int): IntArray = IntArray(h) { buf.pixelAt(0, it) }

    /**
     * 自贴 1:1 右移 1 像素：dest(i+1) = 原始 src(i)。
     *
     * 形态与 GDI+ 探针 S1 一致：8 像素行 [01..08]，`blitFrom(this, 1,0,7,1, 0,0,7,1)`。
     * 冻结口径期望 `01,01,02,03,04,05,06,07`；若判据被摘掉（自贴也边读边写），
     * dest(2) 读到的是刚被写过的 dest(1)（= 01），整行退化成 01,01,01,... ⇒ 红。
     */
    @Test
    fun `自贴重叠区按冻结快照`() {
        val buf = GraphicsBuffer(80)
        buf.create(8, 1)
        for (i in 0 until 8) buf.setPixel(i, 0, red(i + 1))

        buf.blitFrom(buf, 1, 0, 7, 1, 0, 0, 7, 1)

        val expected = intArrayOf(1, 1, 2, 3, 4, 5, 6, 7)
        row(buf, 8).forEachIndexed { x, r ->
            assertEquals(expected[x], r shr 16 and 0xFF, "自贴右移后 x=$x 应读冻结快照（边读边写会得到 1）")
        }
    }

    /**
     * 自贴 + 缩放（dw=3 从 sw=2 取样，右移 1）：快照通道的行基址按**缩放采样窗口**算。
     * 4 像素行 [A,B,C,D]，`blitFrom(this, 1,0,3,1, 0,0,2,1)` ⇒ 采样下标 0,0,1：
     * 冻结期望 `A,A,A,B`；边读边写时 dest(3) 读到刚写过的 dest(1)=A ⇒ `A,A,A,A` ⇒ 红。
     */
    @Test
    fun `自贴缩放取样也按冻结快照`() {
        val buf = GraphicsBuffer(81)
        buf.create(4, 1)
        for (i in 0 until 4) buf.setPixel(i, 0, red(0x30 * (i + 1)))

        buf.blitFrom(buf, 1, 0, 3, 1, 0, 0, 2, 1)

        assertEquals(0x30, row(buf, 4)[1] shr 16 and 0xFF, "dest(1)=src(0)")
        assertEquals(0x30, row(buf, 4)[2] shr 16 and 0xFF, "dest(2)=src(0)（缩放重复取样）")
        assertEquals(0x60, row(buf, 4)[3] shr 16 and 0xFF, "dest(3)=原始 src(1)；边读边写会得到 0x30")
    }

    /**
     * 非同源三形态（平移 / 缩放 / 源矩形越界采透明）+ 纵向越界：钉住直读路径的
     * 行基址与下标算术（`srcRow = py*srcW`、`srcPixels[srcRow + px]`）。
     * 写坏取样算术、或错拿 dest 侧数组当源 ⇒ 这里红。
     */
    @Test
    fun `非同源按源直读平移缩放与越界透明`() {
        // 源：8x1 行 [S0..S7]
        val src = GraphicsBuffer(82)
        src.create(8, 1)
        for (i in 0 until 8) src.setPixel(i, 0, red(0x10 * (i + 1)))

        // (a) 平移：dest(2..7) = S0..S5，前两格不动
        val dstA = GraphicsBuffer(83)
        dstA.create(8, 1)
        dstA.blitFrom(src, 2, 0, 6, 1, 0, 0, 6, 1)
        row(dstA, 8).forEachIndexed { x, r ->
            val want = if (x >= 2) 0x10 * (x - 1) else 0
            assertEquals(want, r shr 16 and 0xFF, "平移形态 x=$x")
        }

        // (b) 缩 2:1：px(i)=2i ⇒ [S0,S2,S4,S6]
        val dstB = GraphicsBuffer(84)
        dstB.create(4, 1)
        dstB.blitFrom(src, 0, 0, 4, 1, 0, 0, 8, 1)
        row(dstB, 4).forEachIndexed { x, r ->
            assertEquals(0x10 * (2 * x + 1), r shr 16 and 0xFF, "缩放形态 x=$x")
        }

        // (c) 源矩形越界：sx=-2, sw=8 ⇒ 采样 px=-2,0,2,4，px<0 采透明 ⇒ [0,S0,S2,S4]
        val dstC = GraphicsBuffer(85)
        dstC.create(4, 1)
        dstC.blitFrom(src, 0, 0, 4, 1, -2, 0, 8, 1)
        row(dstC, 4).forEachIndexed { x, r ->
            val want = if (x == 0) 0 else 0x10 * (2 * x - 1)
            assertEquals(want, r shr 16 and 0xFF, "源越界形态 x=$x（源外采透明）")
        }

        // (d) 纵向：源 1x4 [T0..T3]，sy=-1, sh=4 ⇒ 采样 py=-1,0,1,2 ⇒ [0,T0,T1,T2]
        val vsrc = GraphicsBuffer(86)
        vsrc.create(1, 4)
        for (j in 0 until 4) vsrc.setPixel(0, j, red(0x20 * (j + 1)))
        val dstD = GraphicsBuffer(87)
        dstD.create(1, 4)
        dstD.blitFrom(vsrc, 0, 0, 1, 4, 0, -1, 1, 4)
        column(dstD, 4).forEachIndexed { y, r ->
            val want = if (y == 0) 0 else 0x20 * y
            assertEquals(want, r shr 16 and 0xFF, "纵向越界形态 y=$y（源外采透明）")
        }
    }

    /**
     * 分配读数钉：非同源路径**不物化快照**，自贴路径**必须物化**。
     *
     * 用 HotSpot 的 com.sun.management.ThreadMXBean 线程分配计数（累计分配量，不受 GC 影响）：
     * 512x512 窗口的快照是 1,048,576 字节 ——
     * - 非同源 blit 的分配增量必须 < 0.5 MiB（直读路径近零分配）；
     * - 自贴 blit 的分配增量必须 ≥ 快照整窗（快照确实在物化）。
     * 「非同源也整窗拷」的旧实现（6493950e 之前）一进来分配就多 1 MiB ⇒ 第一条红。
     * 「自贴也直读」的退化 ⇒ 第二条红（同时 `自贴重叠区按冻结快照` 也红）。
     *
     * 不支持的 JVM（非 HotSpot 或关掉了分配计数）按 assumeTrue 跳过——本仓工具链
     * Temurin 17 恒支持，跳过只发生在异种 JVM 上。
     */
    @Test
    fun `非同源路径不物化快照自贴路径物化`() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported, "本 JVM 不支持线程分配计数，跳过分配读数钉")
        val tid = Thread.currentThread().id
        assumeTrue(bean.getThreadAllocatedBytes(tid) != -1L, "线程分配计数未开启，跳过分配读数钉")

        val src = GraphicsBuffer(88)
        src.create(512, 512)
        val dst = GraphicsBuffer(89)
        dst.create(512, 512)
        for (i in 0 until 512) {
            src.setPixel(i, i, red(0x80))
            dst.setPixel(i, i, red(0x40))
        }
        // 预热：把 blitFrom 相关类路径全部加载掉，读数窗口里只剩本次调用的真实分配。
        dst.blitFrom(src, 0, 0, 2, 1, 0, 0, 2, 1)
        dst.blitFrom(dst, 0, 1, 2, 1, 0, 0, 2, 1)

        val beforeNonSelf = bean.getThreadAllocatedBytes(tid)
        dst.blitFrom(src, 0, 0, 512, 512, 0, 0, 512, 512)
        val nonSelfDelta = bean.getThreadAllocatedBytes(tid) - beforeNonSelf
        assertTrue(
            nonSelfDelta < 512L * 1024L,
            "非同源 blit 不得物化快照（512x512 窗口的快照是 1 MiB）：实际分配 $nonSelfDelta 字节",
        )

        val beforeSelf = bean.getThreadAllocatedBytes(tid)
        dst.blitFrom(dst, 0, 0, 512, 512, 0, 0, 512, 512)
        val selfDelta = bean.getThreadAllocatedBytes(tid) - beforeSelf
        assertTrue(
            selfDelta >= 512L * 512L * 4L,
            "自贴 blit 必须物化快照（512x512 窗口 ≥ 1 MiB）：实际分配 $selfDelta 字节",
        )
    }
}
