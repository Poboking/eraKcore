package com.eratw.port.core.code

/**
 * 显式声明页宽的堆内来源：`words` 整段按 [pageWords] 切页，**页宽由调用方给定，不由数组长度推断**。
 *
 * ## 为什么需要它（踩坑史，别再用 `CodeSource.of` 拼尾段）
 *
 * [CodeSource.of] 造出的是 [IntArrayCodeSource]，它的 [IntArrayCodeSource.pageWords]
 * 判据是「字数 ≥ [Instruction.WORDS] 且字数是 [Instruction.WORDS] 的整数倍」（`CodeSource.kt:146-151`），
 * 命中就把页宽**退化成自身长度**。对编译产物这是零拷贝优化（整段一页），但对补丁尾段是灾难：
 * 1,000 条指令 = 4,000 字，正好是 4 的整数倍 ⇒ 页宽 4,000，而 base 的页宽是 4,096
 * ⇒ 尾段被按错误的页宽读出、再按 base 的页宽拼进去，每个字都错位。
 *
 * 波 A 钉子 A3 实测钉住了这个退化（`CompositeCodeSourceTest`）：10 字落回
 * [CodeSource.DEFAULT_PAGE_WORDS]，4,000 字退化成 4,000。所以**尾段必须显式携带页宽**，
 * 本类就是尾段在**物化之前**应有的形态。
 *
 * ## 与 [IntArrayCodeSource] 的区别
 *
 * * 页宽恒定 = 构造入参（不随数组长度漂移），因此可以按 base 的页宽切；
 * * 页数组一律是**拷贝**（尾页可短），不像 [IntArrayCodeSource] 那样在整段一页时返回原数组本体
 *   —— 本类物化出的整段 `IntArray` 随后要交给 [CompositeCodeSource]，
 *   返回本体等于把内部缓冲暴露给调用方，省下的那点拷贝换不来别名风险；
 * * [fillPage] 走同一套下标算术，只是把 `copyInto` 直接写进调用方缓冲（堆内拷贝，无 I/O）。
 */
class PagedIntArraySource(
    private val words: IntArray,
    override val pageWords: Int,
) : CodeSource {
    init {
        require(pageWords > 0 && pageWords % Instruction.WORDS == 0) {
            "页字数必须是 ${Instruction.WORDS} 的整数倍($pageWords)：否则一条指令会跨页"
        }
    }

    override val wordCount: Int get() = words.size

    /** 空流也给一页（页契约：`pageCount` 至少 1，VM 不必判 null）。 */
    override val pageCount: Int
        get() = maxOf(1, ((words.size.toLong() + pageWords - 1) / pageWords).toInt())

    override fun page(pageIndex: Int): IntArray {
        require(pageIndex >= 0 && pageIndex < pageCount) { "页号越界($pageIndex)，共 $pageCount 页" }
        val from = pageStart(pageIndex)
        val n = pageLength(pageIndex)
        return words.copyOfRange(from, from + n)
    }

    override val supportsFillPage: Boolean get() = true

    override fun fillPage(pageIndex: Int, dst: IntArray): Int {
        require(pageIndex >= 0 && pageIndex < pageCount) { "页号越界($pageIndex)，共 $pageCount 页" }
        val n = pageLength(pageIndex)
        require(dst.size >= n) { "页缓冲不足：本页 $n 字，缓冲只有 ${dst.size}" }
        words.copyInto(dst, 0, pageStart(pageIndex), pageStart(pageIndex) + n)
        return n
    }

    /** 页首字下标；用 Long 乘，避免 `pageIndex * pageWords` 在 Int 上回绕。 */
    private fun pageStart(pageIndex: Int): Int = (pageIndex.toLong() * pageWords).toInt()

    /** 本页实际字数（尾页可短，空流为 0）。 */
    private fun pageLength(pageIndex: Int): Int =
        minOf(pageWords.toLong(), wordCount.toLong() - pageIndex.toLong() * pageWords)
            .coerceAtLeast(0L)
            .toInt()
}

/**
 * 两段指令流的**字拼接**来源（R7 增量编译）：base 是已装载的产物，tail 是补丁指令流
 * 物化后的字数组。取指在 pc 空间上表现为「先 base 的全部字，再 tail 的全部字」。
 *
 * ## 分派
 *
 * 页号一律由**总字数**算：第 p 页 = `[p * pageWords, min((p + 1) * pageWords, wordCount))`。
 *
 * * 整页落在 base 里 ⇒ 直通 `base.page(p)`，零拷贝（保住映射段的页缓存与取指性能）；
 * * 跨界页、或整页落在 tail 里 ⇒ 本类**合成**一页（一次分配 + 两次 `copyInto`）。
 *
 * 因为不再有「tail 自己的页」这回事，也就没有 `p - basePages` 这种按段相加的页号换算。
 *
 * ## 一条构造期不变式（fail-fast，不许静默纠错）
 *
 * **尾段不许走 [CodeSource.of]**：[IntArrayCodeSource.pageWords] 在字数是
 * [Instruction.WORDS] 的整数倍时会退化成自身长度（`CodeSource.kt:146-151`），
 * 1,000 条指令的补丁正好踩中。本类的 tail 入参是 `IntArray`、不带页宽，所以这条陷阱
 * 只能由接线方在**物化之前**避开：按 `base.pageWords` 从补丁的 `CodeSource` 里把字读出来
 * （[PagedIntArraySource] 就是那个正确的中间形态）。波 A 钉子 A3 的实证仍然成立。
 *
 * ## 为什么不再要求 base 末页满页
 *
 * 全量语料的 base 是 19,202,316 字 = 4688 × 4096 + 268：末页只有 268 字。
 * 上一版要求 `base.wordCount % pageWords == 0`，会把真实产物当场拒掉；
 * 让 base 对齐要么给 `.erbc` 加填充、要么改 `Program.instructionCount` 的推导口径，
 * 都是更大的风险。所以改成：边界落在页中间时，**那一页由本类合成**。
 * 代价是跨界页要多分配一次（每份产物至多一页、一次性），换来的是真实产物能直接拼。
 *
 * 跨界页的 base 侧必然落在 base 的**最后一页**内（`to > base.wordCount` ⇒
 * `from > base.wordCount - pageWords` ⇒ `from >= (base.pageCount - 1) * pageWords`），
 * 所以合成时只需 `base.page(base.pageCount - 1)` **一次**调用。绝不能改成逐字 `base.word(i)`：
 * [MappedCodeSource.page] 每次调用都新分配 16 KiB（`CodeSource.kt:51-74`：JFR 采样里
 * 占执行循环内分配的 29.2%），逐字读会把一页合成变成 4,095 次页分配、64 MiB 垃圾。
 *
 * ## fillPage（VM 独占缓冲 + 原地填充）
 *
 * [supportsFillPage] **恒为真**：拼接来源要么把整页转给 base 原地填，要么自己写进调用方
 * 缓冲，两条路都零分配。整页落在 base 且 base 支持原地填充时直接转给它（省一次拷贝），
 * base 不支持时 `page()` 出来再拷；合成页直接写进 `dst`，不新分配。
 *
 * 注意这条路**缺省不该用**（同进程 A/B 实测 2,789.6 → 742.1 ns/instr，见
 * `CodeSource.kt:64-72`），开关在 VM 侧 `eratw.perf.noCachedFetch`；本类只保证语义正确。
 *
 * ## 与装载接线的关系
 *
 * 本类只做取指面拼接，不碰池、函数表、行号表（那些由 `Program` 的访问器加尾段分支）。
 * 接线方需要保证：tail 物化时按 `base.pageWords` 切（不是 `DEFAULT_PAGE_WORDS` 常量）。
 */
class CompositeCodeSource(
    private val base: CodeSource,
    private val tailWords: IntArray,
) : CodeSource {

    override val wordCount: Int get() = base.wordCount + tailWords.size

    override val pageWords: Int get() = base.pageWords

    override val pageCount: Int
        get() = maxOf(1, ((wordCount.toLong() + pageWords - 1) / pageWords).toInt())

    override fun page(pageIndex: Int): IntArray {
        require(pageIndex >= 0 && pageIndex < pageCount) { "页号越界($pageIndex)，共 $pageCount 页" }
        val from = (pageIndex.toLong() * pageWords).toInt()
        val to = minOf(from + pageWords, wordCount)          // 末页可短
        // 分支 1：整页都在 base 里 ⇒ 直通，零拷贝（保住 mmap 映射段的取指性能）
        if (to <= base.wordCount) return base.page(pageIndex)

        val out = IntArray(to - from)
        // 分支 2/3：跨界页 或 整页在 tail 里 —— 一律合成
        if (from < base.wordCount) {
            // base 贡献 [from, base.wordCount)：它必然落在 base 的**最后一页**里
            val lastPageIdx = base.pageCount - 1
            val bp = base.page(lastPageIdx)                   // 只调一次，别在循环里调（MappedCodeSource.page 每次都新分配 16 KiB）
            val bpStart = (lastPageIdx.toLong() * pageWords).toInt()
            val n = base.wordCount - from
            bp.copyInto(out, 0, from - bpStart, from - bpStart + n)
        }
        val tFrom = maxOf(0, base.wordCount - from)           // out 里从第几个位置开始是 tail
        val tSrc = from + tFrom - base.wordCount              // tailWords 的源下标
        val tLen = to - from - tFrom
        if (tLen > 0) tailWords.copyInto(out, tFrom, tSrc, tSrc + tLen)
        return out
    }

    override val supportsFillPage: Boolean get() = true

    override fun fillPage(pageIndex: Int, dst: IntArray): Int {
        require(pageIndex >= 0 && pageIndex < pageCount) { "页号越界($pageIndex)，共 $pageCount 页" }
        val from = (pageIndex.toLong() * pageWords).toInt()
        val to = minOf(from + pageWords, wordCount)
        val n = to - from
        require(dst.size >= n) { "页缓冲不足：本页 $n 字，缓冲只有 ${dst.size}" }
        if (to <= base.wordCount) {
            // 整页在 base：优先让 base 原地填（省一次拷贝），不支持时才 page() + 拷
            if (base.supportsFillPage) return base.fillPage(pageIndex, dst)
            val p = base.page(pageIndex)
            p.copyInto(dst, 0, 0, minOf(p.size, n))
            return minOf(p.size, n)
        }
        if (from < base.wordCount) {
            val lastPageIdx = base.pageCount - 1
            val bp = base.page(lastPageIdx)
            val bpStart = (lastPageIdx.toLong() * pageWords).toInt()
            val bn = base.wordCount - from
            bp.copyInto(dst, 0, from - bpStart, from - bpStart + bn)
        }
        val tFrom = maxOf(0, base.wordCount - from)
        val tSrc = from + tFrom - base.wordCount
        val tLen = n - tFrom
        if (tLen > 0) tailWords.copyInto(dst, tFrom, tSrc, tSrc + tLen)
        return n
    }
}
