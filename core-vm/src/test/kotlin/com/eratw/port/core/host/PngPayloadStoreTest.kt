package com.eratw.port.core.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 堆外 PNG 载荷通道（OOM-B 波，2026-11-08）。
 *
 * 钉四条：
 * 1. spill → decode 逐字复原（与堆内闭包同语义）；
 * 2. 降级走通道时池级账本记 0（载荷已出 Java 堆），晋升 / dispose 成对不漂移；
 * 3. 换代统一收旧载荷：晋升 / dispose / 清池三条路文件都删掉（已丢弃映射引用；
 *    Linux / Windows 上 JDK 都以 share-delete 打开映射，实测可立即删；删不掉的残留由
 *    sweepStale 兜，见第 4 条）；
 * 4. spill 失败回落堆内闭包（账本照旧按 png.size 记），sweepStale 只收本通道命名形状的文件。
 *
 * 全部小尺寸（64x64）：行为与真机 600x600 合成图同构，判据是字节与文件生命周期。
 */
class PngPayloadStoreTest {

    private val side = 64
    private val sheetPixels = (side * side).toLong()
    private val sheetBytes = sheetPixels * 4L

    private fun painted(pool: GraphicsPool, id: Int, seed: Int = 0x7F000001): GraphicsBuffer {
        val buffer = pool.get(id)
        assertTrue(buffer.createFromArgb(side, side, IntArray(side * side) { idx -> seed + idx }))
        return buffer
    }

    private fun tempDir(): Path = Files.createTempDirectory("png-payload-test")

    private fun payloadFiles(dir: Path): List<Path> =
        Files.newDirectoryStream(dir, "payload-*.png").use { it.toList() }

    @Test
    fun `spill 后 decode 逐字复原且 close 删文件`() {
        val dir = tempDir()
        val store = MappedPngPayloadStore(dir)
        val pixels = IntArray(side * side) { 0x7F000001 + it }
        val pool = GraphicsPool()
        val buffer = painted(pool, 1)
        val png = buffer.toSavePng()

        val payload = assertNotNull(store.spill(png))
        assertEquals(1, payloadFiles(dir).size)
        val decoded = assertNotNull(payload.decode())
        assertEquals(pixels.size, decoded.size)
        for (i in pixels.indices) assertEquals(pixels[i], decoded[i], "第 $i 格逐字相同")
        // 重复 decode 不串位（duplicate 游标各自独立）。
        val again = assertNotNull(payload.decode())
        assertEquals(pixels[0], again[0])

        payload.close()
        assertNull(payload.decode(), "已 close 不读悬空内存")
        payload.close() // 幂等
        assertEquals(0, payloadFiles(dir).size, "close 删文件")
        store.close()
    }

    @Test
    fun `降级走通道时账本记零且晋升逐字复原`() {
        val dir = tempDir()
        val store = MappedPngPayloadStore(dir)
        val pool = GraphicsPool()
        val buffer = painted(pool, 1)
        assertEquals(sheetBytes, pool.residentBytes())

        assertEquals(sheetPixels, buffer.demotePixelsToPng(store))
        assertTrue(buffer.isPixelsDemoted)
        assertTrue(buffer.isCreated, "GCREATED 不翻")
        assertEquals(0L, pool.residentBytes(), "载荷已出 Java 堆，账本（堆字节口径）记 0")
        assertEquals(0L, buffer.retainedBytes)
        assertEquals(1, payloadFiles(dir).size, "载荷确实落在堆外文件里")

        val pixels = buffer.pixels
        assertEquals(sheetPixels, pixels.size.toLong())
        for (i in pixels.indices) assertEquals(0x7F000001 + i, pixels[i], "晋升逐字复原")
        assertFalse(buffer.isPixelsDemoted)
        assertEquals(sheetBytes, pool.residentBytes(), "晋升入账")
        assertEquals(0, payloadFiles(dir).size, "晋升换代收旧载荷（publishSurfaceLocked 统一口）")
        store.close()
    }

    @Test
    fun `dispose 与清池都收掉堆外载荷`() {
        val dir = tempDir()
        val store = MappedPngPayloadStore(dir)
        val pool = GraphicsPool()
        val a = painted(pool, 1)
        val b = painted(pool, 2, seed = 0x01000001)
        assertTrue(a.demotePixelsToPng(store) > 0L)
        assertTrue(b.demotePixelsToPng(store) > 0L)
        assertEquals(2, payloadFiles(dir).size)
        assertEquals(0L, pool.residentBytes())

        a.dispose()
        assertEquals(1, payloadFiles(dir).size, "dispose 收自己那份")
        pool.clear()
        assertEquals(0, payloadFiles(dir).size, "清池收剩下的（回标题 UnloadGraphicList 路径）")
        assertEquals(0L, pool.residentBytes(), "清池不扣成负数")
        store.close()
    }

    @Test
    fun `spill 失败回落堆内闭包且账本照旧`() {
        // 目录不存在且父路径是个文件 ⇒ createDirectories 必败 ⇒ spill 返回 null。
        val notDir = Files.createTempFile("png-payload-not-dir", ".tmp")
        val store = MappedPngPayloadStore(notDir.resolve("child"))
        val pool = GraphicsPool()
        val buffer = painted(pool, 1)
        val pngBytes = buffer.toSavePng().size.toLong()

        assertTrue(buffer.demotePixelsToPng(store) > 0L)
        assertTrue(buffer.isPixelsDemoted)
        assertEquals(pngBytes, pool.residentBytes(), "回落堆内闭包：账本按 png.size 记（老路逐字不变）")
        val pixels = buffer.pixels
        assertEquals(0x7F000001, pixels[0], "回落闭包照常解回")
        store.close()
        Files.deleteIfExists(notDir)
    }

    @Test
    fun `sweepStale 只收本通道命名形状的残留`() {
        val dir = tempDir()
        val stale = dir.resolve("payload-42.png")
        val other = dir.resolve("keep.png")
        val lookalike = dir.resolve("payload-abc.png")
        Files.write(stale, byteArrayOf(1))
        Files.write(other, byteArrayOf(2))
        Files.write(lookalike, byteArrayOf(3))

        val store = MappedPngPayloadStore(dir)
        store.sweepStale()
        assertFalse(Files.exists(stale), "本通道残留被收")
        assertTrue(Files.exists(other), "别的文件一个字节不动")
        assertTrue(Files.exists(lookalike), "序号非数字的不收")
        store.close()
        Files.deleteIfExists(other)
        Files.deleteIfExists(lookalike)
    }

    @Test
    fun `堆外载荷的只读字节口给瞬时拷贝 且凭据是闭包本体`() {
        val dir = tempDir()
        val store = MappedPngPayloadStore(dir)
        val pool = GraphicsPool()
        val buffer = painted(pool, 1)
        val png = buffer.toSavePng()
        assertEquals(sheetPixels, buffer.demotePixelsToPng(store))

        val cred = assertNotNull(buffer.demotedCredential(), "降级代必须给零分配凭据")
        assertTrue(cred is PngPayloadStore.DecodeClosure, "堆外载荷的凭据是闭包本体")
        val first = assertNotNull(buffer.demotedPngBytes())
        assertTrue(png.contentEquals(first), "只读口交付的就是降级编码的那份 PNG")
        val second = assertNotNull(buffer.demotedPngBytes())
        assertTrue(first !== second, "映射没有堆内正本：每次读都是新拷贝（平台按凭据缓存结果）")
        assertSame(cred, buffer.demotedCredential(), "凭据本身零分配、同代恒等")
        assertEquals(0L, pool.residentBytes(), "只读不换代：堆外载荷的账本仍记 0")

        buffer.pixels // 晋升 ⇒ 换代口 close 旧载荷
        assertNull(buffer.demotedCredential())
        assertNull(buffer.demotedPngBytes(), "载荷已被换代口 close，读不出字节")
        assertEquals(0, payloadFiles(dir).size, "close 删文件")
        store.close()
    }

    @Test
    fun `store 关闭后 spill 拒绝且存活载荷被收尾`() {
        val dir = tempDir()
        val store = MappedPngPayloadStore(dir)
        val pool = GraphicsPool()
        val buffer = painted(pool, 1)
        val payload = assertNotNull(store.spill(buffer.toSavePng()))
        assertEquals(1, payloadFiles(dir).size)

        store.close()
        assertNull(payload.decode(), "store 关闭收掉存活载荷")
        assertNull(store.spill(byteArrayOf(1, 2, 3)), "关闭后不再接新载荷")
        assertEquals(0, payloadFiles(dir).size)
        store.close() // 幂等
    }
}