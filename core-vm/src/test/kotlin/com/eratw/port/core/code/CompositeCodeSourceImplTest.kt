package com.eratw.port.core.code

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [CompositeCodeSource] / [PagedIntArraySource] 的产品实现闸（R7 波 B 的 L2 路）。
 *
 * 波 A 钉子 A3（`CompositeCodeSourceTest`，只读参照）钉的是「页宽必须一致 + 页分派 + 尾页可短」。
 * 本文件钉的是产品类在**真实产物形状**下的行为：
 *
 * * 真实 base 是 19,202,316 字 = 4688 × 4096 + 268（末页只有 268 字），
 *   所以跨界页必须由本类合成 —— 守门用例见「非满页 base 的跨界页由本类合成」；
 * * 整页落在 base 时必须直通（不许无谓重分配）；
 * * `fillPage` 与 `page` 逐元素一致，且合成页不写脏调用方缓冲超出部分；
 * * 空段退化（空 base / 空 tail / 两边都空都不许多出或少掉页）。
 */
class CompositeCodeSourceImplTest {

    private val pageWords = CodeSource.DEFAULT_PAGE_WORDS

    /** 值 = 起始值 + 序号，便于逐字核对是否错位。 */
    private fun words(from: Int, count: Int): IntArray = IntArray(count) { from + it }

    /** 小端字流缓冲：给 [MappedCodeSource] 当只读映射窗口用（与 `.erbc` 的 u32 同序）。 */
    private fun leBuffer(words: IntArray): ByteBuffer {
        val buf = ByteBuffer.allocate(words.size * Instruction.WORDS).order(ByteOrder.LITTLE_ENDIAN)
        for (w in words) buf.putInt(w)
        buf.flip()
        return buf
    }

    /** 真实产物的形状：base 2 满页 + 268 字（末页短），tail 1000 字。 */
    private fun ragged(): Pair<IntArray, IntArray> =
        words(0, pageWords * 2 + 268) to words(1_000_000, 1000)

    @Test
    fun `非满页 base 的跨界页由本类合成`() {
        val (baseWords, tailWords) = ragged()
        val composite = CompositeCodeSource(PagedIntArraySource(baseWords, pageWords), tailWords)
        val flat = baseWords + tailWords

        assertEquals(pageWords * 2 + 268 + 1000, composite.wordCount, "字数 = base + tail")
        assertEquals(pageWords, composite.pageWords, "页宽取 base 的页宽")
        assertEquals(3, composite.pageCount, "9,460 字按 4,096 切 = 3 页")

        // 第 2 页跨界：base 末页剩 268 字 + tail 全部 1,000 字 = 1,268 字。
        val cross = composite.page(2)
        assertEquals(1268, cross.size, "跨界页 = 268 + 1,000")
        assertContentEquals(
            baseWords.copyOfRange(pageWords * 2, baseWords.size),
            cross.copyOfRange(0, 268),
            "跨界页前半必须是 base 末页内容",
        )
        assertContentEquals(tailWords, cross.copyOfRange(268, 1268), "跨界页后半必须是 tail 的全部字")

        // 全段逐字：把「页号 / 页内偏移」的任何错位抓死（总长 9,460 字，跑得动）。
        for (i in 0 until composite.wordCount) {
            assertEquals(flat[i], composite.word(i), "第 $i 字")
        }
        assertContentEquals(flat, composite.toIntArray(), "整段物化 = 两段顺序拼接")
    }

    @Test
    fun `满页 base 拼一页半 tail 时逐页内容正确`() {
        val baseWords = words(0, pageWords * 2)
        val tailWords = words(100_000, pageWords + pageWords / 2)
        val composite = CompositeCodeSource(PagedIntArraySource(baseWords, pageWords), tailWords)
        val flat = baseWords + tailWords

        assertEquals(flat.size, composite.wordCount, "字数 = 两段之和")
        assertEquals(4, composite.pageCount, "2 满页 + 1 满页 + 1 半页")

        for (p in 0 until composite.pageCount) {
            val from = p * pageWords
            val expected = flat.copyOfRange(from, minOf(from + pageWords, flat.size))
            assertContentEquals(expected, composite.page(p), "第 $p 页必须等于手工拼接的同区间")
        }

        val last = composite.page(composite.pageCount - 1)
        assertEquals(pageWords / 2, last.size, "尾段末页只剩半页")
        assertContentEquals(tailWords.copyOfRange(pageWords, tailWords.size), last, "短尾页内容不错位")
    }

    @Test
    fun `tail 为空时与纯 base 完全一致且走直通分支`() {
        val baseWords = words(0, pageWords * 2)
        val base = PagedIntArraySource(baseWords, pageWords)
        val composite = CompositeCodeSource(base, IntArray(0))

        assertEquals(base.wordCount, composite.wordCount)
        assertEquals(base.pageWords, composite.pageWords)
        assertEquals(base.pageCount, composite.pageCount, "空 tail 不多出一页")
        for (p in 0 until base.pageCount) {
            assertContentEquals(base.page(p), composite.page(p), "第 $p 页与纯 base 相同")
        }
        for (i in 0 until base.wordCount) {
            assertEquals(baseWords[i], composite.word(i), "第 $i 字")
        }

        // 直通：整段一页的 IntArrayCodeSource.page 返回本体，拼接来源不许重新分配。
        val rawBase = CodeSource.of(words(0, 100))
        val passthrough = CompositeCodeSource(rawBase, IntArray(0))
        assertSame(rawBase.page(0), passthrough.page(0), "整页在 base 时必须直通")
    }

    @Test
    fun `base 为空时只由 tail 组成`() {
        val tailWords = words(2000, pageWords + 7)
        val composite = CompositeCodeSource(PagedIntArraySource(IntArray(0), pageWords), tailWords)

        assertEquals(tailWords.size, composite.wordCount)
        assertEquals(2, composite.pageCount, "4,103 字按 4,096 切 = 2 页")
        assertEquals(tailWords[0], composite.word(0), "首字就是 tail 首字（不许落到空 base 的页上越界）")
        for (i in 0 until tailWords.size) {
            assertEquals(tailWords[i], composite.word(i), "第 $i 字")
        }
        assertContentEquals(tailWords, composite.toIntArray())
    }

    @Test
    fun `两边都空时只有一页空页`() {
        val composite = CompositeCodeSource(PagedIntArraySource(IntArray(0), pageWords), IntArray(0))

        assertEquals(0, composite.wordCount)
        assertEquals(1, composite.pageCount, "页契约：至少 1 页")
        assertEquals(0, composite.page(0).size, "唯一一页是空页")
        assertEquals(0, composite.fillPage(0, IntArray(0)), "空页原地填充返回 0")
    }

    @Test
    fun `fillPage 与 page 逐元素一致`() {
        val (baseWords, tailWords) = ragged()
        val flat = baseWords + tailWords
        // 一条走 base 的原地填充（映射来源），一条走 page() + 拷（不支持原地填充的 base）。
        val mapped = CompositeCodeSource(
            MappedCodeSource(leBuffer(baseWords), baseWords.size, pageWords),
            tailWords,
        )
        val copied = CompositeCodeSource(NoFillSource(PagedIntArraySource(baseWords, pageWords)), tailWords)

        for (composite in listOf(mapped, copied)) {
            assertTrue(composite.supportsFillPage, "拼接来源恒支持原地填充")
            val dst = IntArray(pageWords)
            for (p in 0 until composite.pageCount) {
                val expected = composite.page(p)
                dst.fill(-1)
                val n = composite.fillPage(p, dst)
                assertEquals(expected.size, n, "第 $p 页返回字数")
                assertContentEquals(expected, dst.copyOfRange(0, n), "第 $p 页内容")
                if (n < dst.size) assertEquals(-1, dst[n], "缓冲超出本页的部分不许被写")
            }
        }

        // 再跟手工拼接对一遍：确认 fillPage 不是「自洽地错」。
        val dst = IntArray(pageWords)
        for (p in 0 until mapped.pageCount) {
            val n = mapped.fillPage(p, dst)
            assertContentEquals(
                flat.copyOfRange(p * pageWords, p * pageWords + n),
                dst.copyOfRange(0, n),
                "第 $p 页与手工拼接一致",
            )
        }
    }

    @Test
    fun `页号越界与缓冲不足都抛错`() {
        val (baseWords, tailWords) = ragged()
        val composite = CompositeCodeSource(PagedIntArraySource(baseWords, pageWords), tailWords)
        val full = IntArray(pageWords)

        assertFailsWith<IllegalArgumentException> { composite.page(-1) }
        assertFailsWith<IllegalArgumentException> { composite.page(composite.pageCount) }
        assertFailsWith<IllegalArgumentException> { composite.fillPage(-1, full) }
        assertFailsWith<IllegalArgumentException> { composite.fillPage(composite.pageCount, full) }

        val e = assertFailsWith<IllegalArgumentException> { composite.fillPage(2, IntArray(1267)) }
        val msg = e.message!!
        assertTrue(msg.contains("1268"), "消息必须点名本页实际字数：$msg")
        assertTrue(msg.contains("1267"), "消息必须点名缓冲大小：$msg")
    }

    /** 只把 [CodeSource.supportsFillPage] 摁成 false 的包装：其余成员原样转发。 */
    private class NoFillSource(inner: CodeSource) : CodeSource by inner {
        override val supportsFillPage: Boolean get() = false
    }
}
