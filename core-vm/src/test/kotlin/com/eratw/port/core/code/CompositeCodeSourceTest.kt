package com.eratw.port.core.code

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * R7 钉子 A3（CONTRACT-R7 §2 波 A）：pc 空间按页拼接的算术能不能成立。
 *
 * ## 这个钉子存在的理由
 *
 * R7 波 C 的取指方案是 `CompositeCodeSource`：`p < base.pageCount ? base.page(p)
 * : tail.page(p - base.pageCount)`。VM 每跨一页只多一次比较（页缓存命中 99.97%，
 * `CodeSource.kt:68`），热路径代价可忽略。但「可忽略」的前提是**两段来源的页宽必须相同**，
 * 否则页索引换算全部错位。
 *
 * 这里钉住一个真实陷阱：[IntArrayCodeSource.pageWords] 在长度是 `Instruction.WORDS`（4）的
 * 整数倍时会退化成 `words.size`（`CodeSource.kt:146-151`）—— 也就是说
 * `CodeSource.of(补丁数组)` 得到的页宽可能是「补丁自己的长度」，而不是 base 的 4096。
 * 1000 条指令的补丁正好踩中。**补丁尾段绝不能直接用 `CodeSource.of`**，必须用一个显式
 * 声明页宽的包装（本测试里的 [PagedIntArraySource] 就是它应有的样子）。
 *
 * ## 结论（写进波 C 契约用）
 *
 * 1. 尾段来源必须显式携带 `pageWords = base.pageWords`，不许走 `CodeSource.of`；
 * 2. `pageCount = base.pageCount + ceil(tailWordCount / pageWords)`，尾页可短；
 * 3. `word(i)` / `toIntArray()` 的跨页行为由接口的默认实现给出，只需页分派正确即可。
 */
class CompositeCodeSourceTest {

    /** 显式页宽的堆内来源 —— 波 C 尾段应有的形态（对照 `CodeSource.of` 的退化行为）。 */
    private class PagedIntArraySource(private val words: IntArray, override val pageWords: Int) : CodeSource {
        override val wordCount: Int get() = words.size
        override val pageCount: Int get() = (words.size + pageWords - 1) / pageWords
        override fun page(pageIndex: Int): IntArray {
            require(pageIndex in 0 until pageCount) { "页号越界($pageIndex)，共 $pageCount 页" }
            val from = pageIndex * pageWords
            return words.copyOfRange(from, minOf(from + pageWords, words.size))
        }
    }

    /** 拟定实现：分界只在页索引上做一次比较，两段页宽必须相等。 */
    private class CompositeCodeSource(
        private val base: CodeSource,
        private val tail: CodeSource,
    ) : CodeSource {
        init {
            require(base.pageWords == tail.pageWords) {
                "页宽不一致：base=${base.pageWords} tail=${tail.pageWords}（页索引换算会错位）"
            }
        }

        override val wordCount: Int get() = base.wordCount + tail.wordCount
        override val pageWords: Int get() = base.pageWords
        override val pageCount: Int get() = base.pageCount + tail.pageCount

        override fun page(pageIndex: Int): IntArray =
            if (pageIndex < base.pageCount) base.page(pageIndex) else tail.page(pageIndex - base.pageCount)
    }

    private val pageWords = CodeSource.DEFAULT_PAGE_WORDS

    /** 造 n 个「值 = 起始值 + 序号」的字，便于逐字核对跨页是否错位。 */
    private fun words(from: Int, count: Int): IntArray = IntArray(count) { from + it }

    @Test
    fun `CodeSource of 只在字数为 4 的整数倍时才把页宽退化成数组长度`() {
        // 首跑假设「小数组一定退化」被证伪：退化判据是 `size % Instruction.WORDS == 0`
        // （CodeSource.kt:146-151），非 4 整数倍反而落回 DEFAULT_PAGE_WORDS。
        val notMultipleOf4 = CodeSource.of(words(0, 10))
        assertEquals(
            pageWords,
            notMultipleOf4.pageWords,
            "10 字不是 4 的整数倍 ⇒ 落回 DEFAULT_PAGE_WORDS，与 base 页宽恰好一致",
        )

        val multipleOf4 = CodeSource.of(words(0, 4000))
        assertEquals(4000, multipleOf4.pageWords, "退化行为：页宽 = 数组长度")
        assertNotEquals(
            pageWords,
            multipleOf4.pageWords,
            "陷阱确认：补丁字数一旦是 4 的整数倍（1,000 条指令就够），of() 的页宽就与 base 的 4096 不一致",
        )
    }

    @Test
    fun `两段页宽不一致时构造必须拒绝`() {
        val base = PagedIntArraySource(words(0, pageWords * 2), pageWords)
        // 4,000 字 = 1,000 条指令：正好踩中 of() 的退化分支，页宽 4000 ≠ 4096。
        val badTail = CodeSource.of(words(0, 4000))

        val e = runCatching { CompositeCodeSource(base, badTail) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException, "页宽不一致必须当场拒绝，不许静默错位：$e")
    }

    @Test
    fun `页分派在分界处正确，尾页可短`() {
        val baseWords = words(1000, pageWords * 2)          // 2 满页
        val tailWords = words(9000, 10)                      // 10 字 = 1 个短尾页
        val composite = CompositeCodeSource(
            PagedIntArraySource(baseWords, pageWords),
            PagedIntArraySource(tailWords, pageWords),
        )

        assertEquals(pageWords * 2 + 10, composite.wordCount)
        assertEquals(pageWords, composite.pageWords)
        assertEquals(3, composite.pageCount, "2 满页 + 1 短尾页")

        assertContentEquals(baseWords.copyOfRange(0, pageWords), composite.page(0))
        assertContentEquals(baseWords.copyOfRange(pageWords, pageWords * 2), composite.page(1))
        assertEquals(10, composite.page(2).size, "尾页可短")
        assertContentEquals(tailWords, composite.page(2))
    }

    @Test
    fun `逐字读取跨分界不错位`() {
        val baseWords = words(0, pageWords * 2)
        val tailWords = words(500_000, 10)
        val composite = CompositeCodeSource(
            PagedIntArraySource(baseWords, pageWords),
            PagedIntArraySource(tailWords, pageWords),
        )

        assertEquals(0, composite.word(0), "base 首字")
        assertEquals(pageWords * 2 - 1, composite.word(pageWords * 2 - 1), "base 末字")
        assertEquals(500_000, composite.word(pageWords * 2), "分界后首字必须接上尾段，不许回绕到 base")
        assertEquals(500_009, composite.word(pageWords * 2 + 9), "尾段末字")

        val flat = composite.toIntArray()
        assertEquals(composite.wordCount, flat.size)
        assertContentEquals(baseWords + tailWords, flat, "整段物化必须等于两段顺序拼接")
    }
}
