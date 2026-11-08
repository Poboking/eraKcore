package com.eratw.port.core.host

import com.eratw.port.core.image.DemotedPngPixels
import com.eratw.port.core.image.PngDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [GraphicsBuffer.demotePixelsToPng] 无损降级档（2026-11-04 OOM 波）。
 *
 * 背景取证：真机 0.2.39+15 五份 512 MiB 报告（eratw/2026-11-04/1793779*）的公共形状是
 * Java 堆 511/512 MiB 贴顶，而常驻大头是「画过的 G_ID 合成缓冲」（HUAWEI TET-AN00 一份
 * `图形池：332 项 / 331 张已创建 / 像素驻留 279 MiB`，超像素预算 109 MiB）。这批缓冲被
 * SPRITECREATE 引用着 ⇒ 宿主 `releaseUnused` 的 exclude 白名单护住，此前所有回收档
 * 对它们都是「收回 0」。本档给出零脚本可见面的出路：像素压成 PNG 闭包（[toSavePng] 编码、
 * [com.eratw.port.core.image.PngDecoder] 解码 —— 本仓 GSAVE 的同款载体，往返逐字一致），
 * `GCREATED`/宽高不动，首次真读透明晋升。
 *
 * 语义锚点（反编译在 ERATW_ROOT 下 reference/emuera-cs）：
 * - `GraphicsImage.IsCreated` 只看位图在不在（GraphicsImage.cs:44-54）——降级不是 dispose；
 * - `ASpriteSingle.IsCreated` 只看 BaseImage.IsCreated（ASpriteSingle.cs:24-34）——
 *   sprite 引用面在降级前后不感知；
 * - 像素读（GGETCOLOR）从 BaseImage 逐字取（GraphicsImage.cs:154-166 的 GetPixel 形状）
 *   ——无损往返保证读数不变。
 *
 * 全部小尺寸（4 KiB 像素级）：降级是字节判据，行为与 1.44 MiB 合成图同形。
 */
class GraphicsBufferDemoteTest {

    private val side = 64
    private val sheetPixels = side * side.toLong()          // 4096
    private val sheetBytes = sheetPixels * 4L               // 16384

    /** 一张「脚本画过」的缓冲：无重解源、fileSource 为空 —— 降级档的典型候选。 */
    private fun painted(pool: GraphicsPool, id: Int, seed: Int = 0x7F000001): GraphicsBuffer {
        val buffer = pool.get(id)
        assertTrue(buffer.createFromArgb(side, side, IntArray(side * side) { idx -> seed + idx }))
        return buffer
    }

    // ------------------------------------------------------------------
    // 降级 ⇄ 晋升往返
    // ------------------------------------------------------------------

    @Test
    fun `降级后首次真读逐字复原且记账成对`() {
        val pool = GraphicsPool()
        val buffer = painted(pool, 1, seed = 0x7F000001)
        assertEquals(sheetBytes, pool.residentBytes(), "前置：像素驻留")

        val pngBytes = buffer.toSavePng().size.toLong()
        val freed = buffer.demotePixelsToPng()

        assertEquals(sheetPixels, freed, "释放按像素条数计（口径同 evictDeferred）")
        assertEquals(0L, buffer.pixelBytes, "像素数组退场")
        // 新契约：PNG 载荷仍被闭包强引用着、仍占 Java 堆 ⇒ 账本只降「像素−载荷」的差额。
        // 旧契约记 0，让预算闸/水位档/合成图逐出档一起失明，是长时间游玩 OOM 的根因。
        assertEquals(pngBytes, pool.residentBytes(), "池记账按真实驻留降，不许归零")
        assertEquals(pngBytes, buffer.retainedBytes, "分项读数与池级账本同源")
        assertTrue(buffer.isPixelsDemoted, "降级读数")
        assertNull(buffer.pixelsIfResident(), "驻留探测必须拒绝降级态（渲染侧据此复用原生缓存）")
        assertTrue(buffer.isCreated, "GCREATED 不许翻 false（GraphicsImage.cs:44-54）")
        assertEquals(side, buffer.width)
        assertEquals(side, buffer.height)

        val pixels = buffer.pixels
        assertEquals(sheetPixels, pixels.size.toLong())
        for (idx in side * side - 1 downTo 0) {
            assertEquals(0x7F000001 + idx, pixels[idx], "晋升像素第 $idx 格必须逐字相同")
        }
        assertFalse(buffer.isPixelsDemoted, "晋升后不再处于降级态")
        assertEquals(sheetBytes, pool.residentBytes(), "晋升记账复原")
        assertSame(pixels, buffer.pixelsIfResident(), "晋升后驻留探测给到当前代")
    }

    @Test
    fun `降级后写路径透明晋升且旧内容保真`() {
        val pool = GraphicsPool()
        val buffer = painted(pool, 2, seed = 0x01000000)
        assertTrue(buffer.demotePixelsToPng() > 0L, "前置：降级成功")

        buffer.setPixel(0, 0, 0xFF010203.toInt())

        assertEquals(0xFF010203.toInt(), buffer.pixelAt(0, 0), "写进去的新像素")
        // pixelAt(x=1, y=1) ⇒ 像素序号 65：种子 0x01000000 + 65。
        assertEquals(0x01000041, buffer.pixelAt(1, 1), "没写到的格子必须还是降级前的旧内容")
        assertFalse(buffer.isPixelsDemoted, "写路径经 pixels 取数组 ⇒ 已透明晋升")
        assertEquals(sheetBytes, pool.residentBytes(), "晋升记账复原")
    }

    // ------------------------------------------------------------------
    // 候选判据：与 evictDeferred 互补，不重叠
    // ------------------------------------------------------------------

    @Test
    fun `可重解与未创建的缓冲不在降级候选集`() {
        val pool = GraphicsPool()
        // 未创建：get 惰性建出来的空条目
        assertEquals(0L, pool.get(31).demotePixelsToPng(), "无像素可压")
        // 可重解（fileSource 还在）：这一档能整退得更干净，降级不许抢班
        val sheet = pool.get(32)
        assertTrue(
            sheet.createDeferredFromFile(side, side, "resources/demote-32.webp") {
                IntArray(side * side) { idx -> idx }
            },
        )
        sheet.pixels
        assertEquals(sheetBytes, sheet.pixelBytes, "前置：工作表驻留")
        assertEquals(0L, sheet.demotePixelsToPng(), "fileSource 在 ⇒ 留给 evictDeferred")
        assertEquals(sheetBytes, sheet.pixelBytes, "降级对它必须是零动作")
    }

    @Test
    fun `重复降级幂等`() {
        val pool = GraphicsPool()
        val buffer = painted(pool, 41)
        val pngBytes = buffer.toSavePng().size.toLong()
        assertEquals(sheetPixels, buffer.demotePixelsToPng())
        assertEquals(0L, buffer.demotePixelsToPng(), "已在降级态 ⇒ 第二发必须收 0")
        assertEquals(pngBytes, pool.residentBytes(), "幂等：载荷只入账一次，不二次扣")

        // 真读晋升后再降：往返内容一致、每次晋升都换出新代数组。
        val first = buffer.pixels
        assertEquals(sheetPixels, buffer.demotePixelsToPng(), "晋升后再降：第二次正常收走")
        assertEquals(first.size, buffer.pixels.size, "两次往返尺寸一致")
        assertNotEquals(first, buffer.pixels, "晋升会换出新代（同一内容新数组）")
    }

    // ------------------------------------------------------------------
    // 池级第二档：预算闸里的降级序、护持者、与第一档的先后
    // ------------------------------------------------------------------

    @Test
    fun `预算闸把画过的按 LRU 降级到预算内且护持者幸免`() {
        val pool = GraphicsPool()
        val first = painted(pool, 61)
        val third = painted(pool, 62)
        val second = painted(pool, 63)
        // 脚本此刻正在用 second：表序 [first, third, second]（头部 = 最久没碰过）
        assertSame(second, pool.get(63))

        val freed = pool.evictDeferredPixelsWithinBudget(sheetBytes, protect = second)

        assertEquals(sheetPixels * 2, freed, "降两张到预算内（像素条数）")
        assertEquals(0L, first.pixelBytes, "最久未访问的先降")
        assertEquals(0L, third.pixelBytes)
        assertEquals(sheetBytes, second.pixelBytes, "预算达标即停，不碰剩下一张")
        assertTrue(first.isPixelsDemoted)
        assertTrue(second.isPixelsDemoted.not())
        assertEquals(
            sheetBytes + first.retainedBytes + third.retainedBytes, pool.residentBytes(),
            "护持者那份像素 + 两张降级载荷，账本一字节都不许漏",
        )

        // 对照：预算再压一档（差一字节）⇒ 护持者也保不住——证明上一段的幸免是护持者给的。
        val freed2 = pool.evictDeferredPixelsWithinBudget(sheetBytes - 1L, protect = second)
        assertEquals(0L, freed2, "全池只剩护持者一张可降 ⇒ 什么都不动（护栏语义）")
        assertEquals(sheetBytes, second.pixelBytes)
    }

    @Test
    fun `第一档优先于第二档可重解的先整退`() {
        val pool = GraphicsPool()
        val sheet = pool.get(71)
        assertTrue(
            sheet.createDeferredFromFile(side, side, "resources/demote-71.webp") {
                IntArray(side * side) { idx -> idx }
            },
        )
        sheet.pixels
        val paintedBuffer = painted(pool, 72)
        assertEquals(sheetBytes * 2, pool.residentBytes())

        val freed = pool.evictDeferredPixelsWithinBudget(sheetBytes)

        assertEquals(sheetPixels, freed, "只退了第一档那张")
        assertEquals(0L, sheet.pixelBytes, "可重解的整退（连编码成本都不用付）")
        assertEquals(sheetBytes, paintedBuffer.pixelBytes, "画过的在预算达标后不许动")
        assertFalse(paintedBuffer.isPixelsDemoted)
        assertEquals(sheetBytes, pool.residentBytes())
    }

    // ------------------------------------------------------------------
    // 读数：stats.demoted 与报告口径
    // ------------------------------------------------------------------

    @Test
    fun `stats 报出降级张数且与像素记账互洽`() {
        val pool = GraphicsPool()
        painted(pool, 81)
        painted(pool, 82)
        val sheet = pool.get(83)
        assertTrue(
            sheet.createDeferredFromFile(side, side, "resources/demote-83.webp") {
                IntArray(side * side) { idx -> idx }
            },
        )
        sheet.pixels

        var stats = pool.stats()
        assertEquals(3, stats.created)
        assertEquals(0, stats.demoted, "降级前无人处于降级态")

        pool.evictDeferredPixelsWithinBudget(0L)

        stats = pool.stats()
        assertEquals(2, stats.demoted, "两张画过的降级；可重解那张走第一档不算降级")
        assertEquals(3, stats.created, "降级不动 GCREATED（GraphicsImage.cs:44-54）")
        // 像素阵列退场 ≠ 内存释放：两份 PNG 载荷仍占 Java 堆，遍历读数与原子计数都要认它。
        val payloads = pool.peek(81)!!.retainedBytes + pool.peek(82)!!.retainedBytes
        assertTrue(payloads > 0L, "降级载荷不许从账本里消失")
        assertEquals(payloads, stats.pixelBytes, "stats 记的是真实驻留")
        assertEquals(payloads, pool.residentBytes(), "原子计数与遍历读数恒等")
    }

    // ------------------------------------------------------------------
    // 只读凭据/字节口：渲染侧取图不许晋升
    // （2026-11-08 玩家报 0.2.43「查看其他人的位置」OOM 的根因面）
    // ------------------------------------------------------------------

    @Test
    fun `降级载荷只读且逐字可还原 - 绝不触发晋升`() {
        val pool = GraphicsPool()
        val buffer = painted(pool, 91, seed = 0x7F00005B)
        val expected = buffer.pixels.copyOf()
        assertEquals(sheetPixels, buffer.demotePixelsToPng())

        // 渲染侧拿字节去解 native 位图，全程不碰 GraphicsBuffer.pixels。
        val png = assertNotNull(buffer.demotedPngBytes(), "降级代必须把 PNG 字节交给渲染侧")
        val decoded = PngDecoder.decode(png).pixels
        assertEquals(side, buffer.width)
        assertEquals(side, buffer.height)
        assertTrue(expected.contentEquals(decoded), "载荷解回来与降级前逐字一致")

        assertEquals(png.size.toLong(), pool.residentBytes(), "只读不换代 ⇒ 池级账本一分钱不许动")
        assertEquals(png.size.toLong(), buffer.retainedBytes)
        assertTrue(buffer.isPixelsDemoted, "读载荷不许把缓冲晋升回来")
        assertNull(buffer.pixelsIfResident(), "读载荷不许落地像素数组")
        assertTrue(buffer.isCreated, "读载荷不许翻 GCREATED")
        assertSame(png, buffer.demotedPngBytes(), "同一代反复读恒等：缓存有效性凭据是载荷身份")
    }

    @Test
    fun `未降级 可重解 已晋升 已释放的缓冲都不给凭据与载荷`() {
        val pool = GraphicsPool()
        val plain = painted(pool, 92)
        assertNull(plain.demotedCredential(), "像素还驻留着 ⇒ 没有降级凭据")
        assertNull(plain.demotedPngBytes(), "像素还驻留着 ⇒ 没有 PNG 载荷")

        val sheet = pool.get(93)
        assertTrue(
            sheet.createDeferredFromFile(side, side, "resources/demote-93.webp") {
                IntArray(side * side) { idx -> idx }
            },
        )
        sheet.pixels
        assertNull(sheet.demotedCredential(), "CSV 父图的未落地态不是降级态")
        assertNull(sheet.demotedPngBytes(), "CSV 父图的未落地态不是降级态")
        assertTrue(sheet.evictDeferred() > 0L, "退回可重解态")
        assertNull(sheet.demotedCredential(), "deferredSource 非空一律不给凭据")
        assertNull(sheet.demotedPngBytes(), "deferredSource 非空一律不给载荷")

        assertTrue(plain.demotePixelsToPng() > 0L)
        assertNotNull(plain.demotedCredential())
        assertNotNull(plain.demotedPngBytes())
        plain.pixels // 真读晋升
        assertNull(plain.demotedCredential(), "晋升后凭据归零")
        assertNull(plain.demotedPngBytes(), "晋升后载荷读数归零")

        assertTrue(plain.demotePixelsToPng() > 0L, "晋升后可再降")
        assertNotNull(plain.demotedPngBytes())
        plain.dispose()
        assertNull(plain.demotedCredential(), "dispose 后凭据归零")
        assertNull(plain.demotedPngBytes(), "dispose 后载荷读数归零")
    }

    @Test
    fun `写后再降级换出新凭据与新载荷 - 旧字节不再等同于当代`() {
        val pool = GraphicsPool()
        val buffer = painted(pool, 94, seed = 0x01000000)
        assertTrue(buffer.demotePixelsToPng() > 0L)
        val firstCred = assertNotNull(buffer.demotedCredential())
        val first = assertNotNull(buffer.demotedPngBytes())
        assertTrue(firstCred is DemotedPngPixels, "堆内载荷的凭据就是载体本体")

        buffer.setPixel(0, 0, 0xFF112233.toInt()) // 写路径 ⇒ 透明晋升
        assertFalse(buffer.isPixelsDemoted)
        assertNull(buffer.demotedCredential(), "晋升期间凭据不在当代")
        assertNull(buffer.demotedPngBytes(), "晋升期间载荷不在当代")

        assertTrue(buffer.demotePixelsToPng() > 0L)
        val secondCred = assertNotNull(buffer.demotedCredential())
        val second = assertNotNull(buffer.demotedPngBytes())
        assertTrue(firstCred !== secondCred, "再降级必换凭据 ⇒ 渲染侧按身份判缓存失效")
        assertTrue(first !== second, "再降级换出新载荷")
        val restored = PngDecoder.decode(second).pixels
        assertEquals(0xFF112233.toInt(), restored[0], "新载荷带着写入后的像素")
        assertEquals(0x01000041, restored[side + 1], "没写到的格子仍是降级前旧内容")
    }
}
