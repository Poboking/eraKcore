package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `LineBufferConsoleSink.print(text, lineEnd)` 的多行语义（嵌入 `\n` 的分段）+ MaxLog 淘汰。
 *
 * 期望值按原实现（EmueraConsole.cs:3167-3189 的递归 `Print(str3)`）逐段推导：
 * - 每个 `\n` 之前的前缀以 lineEnd=true 入缓冲并立即 flush；
 * - 空前缀只把 pendingLineEnd 置真，flush 补一个空格行；
 * - 最后一个 `\n` 之后的末段仍以 lineEnd=true 进缓冲（等下一次 newLine 上屏）；
 * - 文本以 `\n` 结尾时不再产生额外行。
 *
 * 用 `print` 改成循环后的实现验证这些边界，MaxLog 淘汰后顺序仍是最旧在前。
 */
class MultiLinePrintTest {

    private fun texts(s: LineBufferConsoleSink): List<String> = s.snapshot().map { s.textOf(it) }

    /** 多个换行：中间各段各自成行，末段留缓冲。 */
    @Test
    fun `多个换行逐段 flush`() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.print("a\nb\nc")

        assertEquals(listOf("a", "b"), texts(s), "两个换行 ⇒ 两行已上屏，末段仍在缓冲")
        assertFalse(s.printingBufferEmpty)

        s.newLine()
        assertEquals(listOf("a", "b", "c"), texts(s))
        assertTrue(s.printingBufferEmpty)
    }

    /** 连续空行：空前缀只置 pendingLineEnd，flush 补一个空格行（原版 NewLine 口径）。 */
    @Test
    fun `连续空行各补一个空格行`() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.print("a\n\nb")

        assertEquals(listOf("a", " "), texts(s), "空段 ⇒ 一个空格行")
        s.newLine()
        assertEquals(listOf("a", " ", "b"), texts(s))
    }

    /** 尾部换行：最后一段为空，不额外产生行，也不残留缓冲。 */
    @Test
    fun `尾部换行不产生额外行`() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.print("a\n")

        assertEquals(listOf("a"), texts(s))
        assertTrue(s.printingBufferEmpty, "末段为空 ⇒ 缓冲已空")

        s.newLine()
        assertEquals(listOf("a", " "), texts(s), "再 flush 只补一个空格行（与旧递归同语义）")
    }

    /** 无换行：整段留在缓冲，newLine 才上屏；lineEnd=false 与 true 的缓冲标记不变。 */
    @Test
    fun `无换行保持单段缓冲语义`() {
        val s = LineBufferConsoleSink(clientWidth = 80)

        s.print("abc", false)
        assertEquals(emptyList<String>(), texts(s))
        assertFalse(s.printingBufferEmpty)

        s.newLine()
        assertEquals(listOf("abc"), texts(s))
        assertEquals(0L, s.lineCount, "PRINTN 的逻辑行未结束，不应计数")
    }

    /** MaxLog 淘汰：超上限丢最旧行，留下的顺序仍是旧→新。 */
    @Test
    fun `MaxLog 淘汰后顺序正确`() {
        val s = LineBufferConsoleSink(clientWidth = 80, maxLines = 3)

        s.print("l1\nl2\nl3\nl4")
        assertEquals(listOf("l1", "l2", "l3"), texts(s))

        s.newLine() // flush 末段 l4，淘汰最旧的 l1
        assertEquals(listOf("l2", "l3", "l4"), texts(s))

        s.print("l5")
        s.newLine()
        assertEquals(listOf("l3", "l4", "l5"), texts(s))
    }
}
