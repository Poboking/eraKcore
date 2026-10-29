package com.eratw.port.render.adapter

import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ConsoleAdaptCache] 的逐行实例缓存判据（DEV-05a，纯性能面）。
 *
 * 判据：
 * * **命中**：同一行实例重复 adapt，复用同一个 RenderLine 实例（内容未变不重建）；
 * * **失效**：PRINTN 并入重建、CLEARLINE 后重打同文行、MaxLog 淘汰、焦点色变化，
 *   各失效路径都必须重建，且重建结果的值与逐行全量 adapt 逐项相同；
 * * **部分失效**：一次快照推进里只有变化的行重建，其余行保持实例同一。
 *
 * 「实例同一」是本缓存唯一的可观测契约：`ConsoleAdapter.adapt` 是纯函数
 * （ConsoleAdapter.kt 的线程模型节），命中实例与重建实例逐字段相同，
 * 下游消费的只是值 —— 所以值断言之外全部用 assertSame 与 assertNotSame 钉。
 * CLEARLINE 同文行用 assertNotSame 钉的是**行身份**口径：与锚点表
 * （ConsoleFeed.adoptMergedTail）同一纪律，结构相等不等于同一条行。
 */
class ConsoleAdaptCacheTest {

    private fun sink(width: Int = 20, maxLines: Int = LineBufferConsoleSink.DEFAULT_MAX_LINES) =
        LineBufferConsoleSink(clientWidth = width, maxLines = maxLines)

    /** 缓存命中的判据本体：同一快照重复 adapt，逐行返回同一实例。 */
    @Test
    fun `同一快照重复 adapt 逐行复用同一实例`() {
        val s = sink()
        s.printLine("abc")
        s.printLine("あいう")
        val cache = ConsoleAdaptCache()

        val first = cache.adapt(s.snapshot())
        val second = cache.adapt(s.snapshot())

        assertEquals(first.size, second.size)
        for (i in first.indices) {
            assertSame(first[i], second[i], "行 $i 内容未变，必须复用实例")
        }
        // 值面同时钉住：命中产物与逐行全量 adapt 逐字段相同
        assertEquals(ConsoleAdapter.adapt(s.snapshot()), second)
    }

    /** 部分失效：追加一行只有新行重建，旧行继续命中。 */
    @Test
    fun `追加行时旧行命中新行重建`() {
        val s = sink()
        s.printLine("one")
        val cache = ConsoleAdaptCache()
        val first = cache.adapt(s.snapshot())

        s.printLine("two")
        val second = cache.adapt(s.snapshot())

        assertEquals(2, second.size)
        assertSame(first[0], second[0], "旧行实例未变，不该重建")
        assertEquals("two", second[1].runs.joinToString("") { it.text }, "新行照常适配")
    }

    /** PRINTN 并入：末行被 core 用 copy 重建（新实例）→ 该行必须重建，其余行命中。 */
    @Test
    fun `PRINTN 并入改写末行时末行重建前缀行命中`() {
        val s = sink()
        s.printLine("done")
        s.print("abc", false)
        s.newLine()
        val cache = ConsoleAdaptCache()
        val first = cache.adapt(s.snapshot())
        assertEquals(2, first.size)

        s.print("def", true)
        s.newLine()
        val second = cache.adapt(s.snapshot())

        assertEquals(2, second.size, "PRINTN 续行并入末行，显示行数不增")
        assertSame(first[0], second[0], "前缀行未变，继续命中")
        assertNotSame(first[1], second[1], "末行被并入重建（新实例），必须重新适配")
        assertEquals("abcdef", second[1].runs.joinToString("") { it.text })
    }

    /** 行身份口径：CLEARLINE 之后重打的同文行结构相等但实例不同，必须重建为新实例。 */
    @Test
    fun `CLEARLINE 后重打同文行不命中旧实例`() {
        val s = sink()
        s.printLine("x")
        val cache = ConsoleAdaptCache()
        val first = cache.adapt(s.snapshot())

        s.deleteLine(1)
        s.printLine("x")
        val second = cache.adapt(s.snapshot())

        assertEquals(1, second.size)
        assertEquals(first[0], second[0], "同文行结构相等")
        assertNotSame(first[0], second[0], "但行实例不同（CLEARLINE 摘旧打新），不许错配旧行产物")
    }

    /** MaxLog 淘汰：滚出头的行随重建表出缓存，之后快照的适配结果与全量 adapt 一致。 */
    @Test
    fun `MaxLog 淘汰头部行后缓存跟随当前快照`() {
        val s = sink(maxLines = 3)
        s.printLine("l0")
        s.printLine("l1")
        val cache = ConsoleAdaptCache()
        val first = cache.adapt(s.snapshot())

        s.printLine("l2")
        s.printLine("l3")
        val second = cache.adapt(s.snapshot())

        assertEquals(3, second.size, "maxLines=3，最旧的 l0 被淘汰")
        assertEquals(listOf("l1", "l2", "l3"), second.map { it.runs.joinToString("") { r -> r.text } })
        assertSame(first[1], second[0], "幸存的 l1 继续命中")
        assertNotSame(first[0], second[0], "被淘汰的 l0 与幸存行不是同一实例")
        assertEquals(ConsoleAdapter.adapt(s.snapshot()), second, "与逐行全量 adapt 逐字段相同")
    }

    /** 焦点色变化：按钮 run 的 back 槽随 focusColor 走，换色整表作废。 */
    @Test
    fun `焦点色变化后按钮行重建且 back 更新`() {
        val s = sink(width = 80)
        s.print("按[123]确认")
        s.newLine()
        val cache = ConsoleAdaptCache()
        val def = cache.adapt(s.snapshot()).single()
        assertEquals(TextRun.DEFAULT_FOCUS_COLOR, def.runs.single().back)

        val green = cache.adapt(s.snapshot(), focusColor = 0x00FF00).single()
        assertNotSame(def, green, "换焦点色等于整表作废，该行必须重建")
        assertEquals(0x00FF00, green.runs.single().back)
        assertEquals(RunKind.BUTTON, green.runs.single().kind)

        val back = cache.adapt(s.snapshot()).single()
        assertNotSame(green, back, "换回默认色同样整表作废")
        assertEquals(TextRun.DEFAULT_FOCUS_COLOR, back.runs.single().back)
    }

    /** 空快照往返：清空后缓存表随之清空，重打行照常重建；空表 adapt 返回空表。 */
    @Test
    fun `清空后重打行照常重建`() {
        val s = sink()
        s.printLine("a")
        val cache = ConsoleAdaptCache()
        val first = cache.adapt(s.snapshot())

        assertTrue(cache.adapt(emptyList()).isEmpty(), "空快照 adapt 返回空表")

        s.deleteLine(1)
        s.printLine("a")
        val second = cache.adapt(s.snapshot())
        assertEquals(1, second.size)
        assertNotSame(first[0], second[0], "清空与重打之间实例换新，不许复用旧产物")
        assertEquals(first[0], second[0])
    }

    /** 与全量入口的等价性（改前基准）：任意快照序列上，缓存 adapt 的值序列与逐行 adapt 相同。 */
    @Test
    fun `任意快照序列上与逐行全量 adapt 逐项等价`() {
        val s = sink()
        val cache = ConsoleAdaptCache()
        val steps = listOf(
            { s.printLine("one") },
            { s.print("t", false); s.newLine() },
            { s.print("wo", true); s.newLine() },
            { s.printTemporaryLine("tmp") },
            { s.printLine("next") },
            { s.deleteLine(1) },
            { s.printLine("末") },
        )
        for (step in steps) {
            step()
            val cached = cache.adapt(s.snapshot())
            val fresh = ConsoleAdapter.adapt(s.snapshot())
            assertEquals(fresh, cached, "快照推进一步后与全量 adapt 不等价")
        }
    }
}
