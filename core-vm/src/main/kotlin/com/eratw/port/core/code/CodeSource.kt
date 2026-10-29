package com.eratw.port.core.code

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 指令流的**字视图**（M6-CODE-VIEW：把「指令流必须是一整块 `IntArray`」从 VM 的取指面摘掉）。
 *
 * ## 为什么需要这层
 *
 * 全量语料 4,800,579 条指令时，`code`（16 B/条）+ `lines`（4 B/条）= 91.6 MiB 常驻，
 * 是装载后内存里仅次于语句池/表达式池的第三块（`PoolDecomp` 实测口径）。
 * 指令流本身是**只读、顺序取指**的：VM 每步只需要当前指令的 4 个字，
 * 没有"随机回写"也没有"跨指令视图"。因此它可以按页放在堆外只读映射里
 * （`.erbc` 的 `SECTION_CODE` 段本就是 4 字节小端字流，见 `ErbcFormat.ErbcSink.u32`），
 * 由 VM 按页取指 = 堆内不再需要那 76.8 MB。
 *
 * ## 页契约（VM 的取指循环依赖它）
 *
 * * [pageWords] 必须是 [Instruction.WORDS] 的整数倍 ⇒ **一条指令的 4 个字永远不跨页**，
 *   取指循环只在页首做一次边界比较，不需要拼半条指令。
 * * [page] 返回的数组**只读**（调用方不得写），长度 = 本页实际字数（尾页可短）。
 * * 页号从 0 起连续；[pageCount] 至少 1（空流也给一页，VM 不必判 null）。
 *
 * ## 语义等价（为什么换取指方式不改行为）
 *
 * 取指只是 `code[pc shl 2 + k]` 的另一种读法：同一个 [wordCount]、逐下标同一个 u32。
 * [IntArrayCodeSource] 是**零拷贝**的——整段就是唯一一页，返回的正是原数组本体，
 * 改造前后的 VM 取到的是同一块内存（`ProcessRuntimeTest` 的 `assertSame(program.code, ...)`
 * 与全部 golden/L3 轨迹因此逐字不动）。[MappedCodeSource] 逐字读出同一段字节，
 * 字节序与 `ErbcSink.u32` / `ErbcCursor.u32` 一致（小端）。
 */
interface CodeSource {
    /** 指令流总字数（= 指令条数 × [Instruction.WORDS]）。 */
    val wordCount: Int

    /** 每页字数；必须是 [Instruction.WORDS] 的整数倍（见类文档的页契约）。 */
    val pageWords: Int

    /** 页数（至少 1）。 */
    val pageCount: Int

    /**
     * 取第 [pageIndex] 页的字（下标 0 = 该页首字）。
     *
     * 返回数组只读；长度可为页字数，也可更短（尾页）。VM 把最近一页缓存在局部变量里，
     * 只在跨页时再调用本方法。
     */
    fun page(pageIndex: Int): IntArray

    /**
     * 是否支持 [fillPage] 的**原地填充**（当前只有 [MappedCodeSource] 支持）。
     *
     * PERF（2026-10-27 第五批）：VM 的取指循环据此决定页的持有方式 ——
     * 支持时用「VM 自己的常驻页缓冲 + 原地填充」，每换一页**零分配**；
     * 不支持时沿用 [page] 返回的数组。JFR 分配采样实测 `MappedCodeSource.page` 里
     * `IntArray(16 KiB)` 占执行循环内分配的 **29.2%（45.39 MiB）**，而且 16 KiB
     * 超过 ART 的大对象阈值（12 KiB）⇒ 每页都落 LOS，代价远不止字节数。
     *
     * 选「VM 独占缓冲」而不是「页数组回收池」的原因：`page()` 返回的数组会被**调用方
     * 长期持有**（VM 的 `page` 局部变量、嵌套 VM 各自的局部变量），回收池无法证明某个
     * 数组已经没人引用，回收就会把别人正在读的页改掉。缓冲归 VM 所有则不存在别名。
     */
    val supportsFillPage: Boolean get() = false

    /**
     * 把第 [pageIndex] 页的字写进 [dst] 的前 n 个位置，返回 n（= 本页实际字数，尾页可短）。
     *
     * 只在 [supportsFillPage] 为真时有意义；默认实现退化为 [page] + 拷贝。
     */
    fun fillPage(pageIndex: Int, dst: IntArray): Int {
        val p = page(pageIndex)
        p.copyInto(dst, 0, 0, p.size)
        return p.size
    }

    /**
     * 单个字（冷路径：反汇编、装载校验、诊断装置）。等值于 `wordAt` 语义的
     * `code[index]`，越界抛 [IndexOutOfBoundsException]。
     */
    fun word(index: Int): Int {
        require(index >= 0 && index < wordCount) { "码字下标越界($index)，共 $wordCount 字" }
        val p = index / pageWords
        return page(p)[index - p * pageWords]
    }

    /** 整段物化成 `IntArray`（`.erbc` 落盘 / 逐字对拍的兼容视图；运行期不用）。 */
    fun toIntArray(): IntArray {
        val out = IntArray(wordCount)
        var base = 0
        var p = 0
        while (base < wordCount) {
            val words = page(p)
            val n = minOf(words.size, wordCount - base)
            words.copyInto(out, base, 0, n)
            base += n
            p++
        }
        return out
    }

    companion object {
        /** 默认页字数 = 4096 字 = 1024 条指令（16 KiB 页，与映射段天然对齐）。 */
        const val DEFAULT_PAGE_WORDS: Int = 4096

        /** 驻留 `IntArray` 的零拷贝视图（当前编译/装载链的默认来源）。 */
        fun of(words: IntArray): CodeSource = IntArrayCodeSource(words)

        /**
         * 只读映射来源：`bytes` 的 `[position, limit)` = 整段指令流（4 字节小端字流）。
         *
         * 装载侧只需把 `SECTION_CODE` 的映射窗口（或整份产物里该段的切片）连同一个
         * 已校验的 [wordCount] 交进来；Program 之后不再持有堆内指令数组。
         */
        fun ofLittleEndian(
            bytes: ByteBuffer,
            wordCount: Int,
            pageWords: Int = DEFAULT_PAGE_WORDS,
        ): CodeSource = MappedCodeSource(bytes, wordCount, pageWords)
    }
}

/**
 * 堆内 `IntArray` 的**零拷贝**字视图：整段就是唯一一页（[pageWords] = 字数），
 * 因此 VM 的页缓存只会命中，取指开销与改造前 `val code = program.code` 完全相同。
 *
 * 这是编译期产物（`CodeBuilder.build`）与既有 `.erbc` 装载链的默认来源；
 * 非 4 的整数倍长度的数组（只可能出现在手工构造的测试桩里）退化成分页拷贝，
 * 以保证「一条指令不跨页」的页契约成立。
 */
class IntArrayCodeSource(private val words: IntArray) : CodeSource {
    override val wordCount: Int get() = words.size

    override val pageWords: Int =
        if (words.size >= Instruction.WORDS && words.size % Instruction.WORDS == 0) {
            words.size
        } else {
            CodeSource.DEFAULT_PAGE_WORDS
        }

    /** 整段一页 ⇒ 恒 1；只有长度非 4 整数倍的桩才会分页。 */
    override val pageCount: Int get() = if (pageWords >= words.size) 1 else (words.size + pageWords - 1) / pageWords

    override fun page(pageIndex: Int): IntArray {
        if (pageWords >= words.size) {
            require(pageIndex == 0) { "页号越界($pageIndex)，本来源只有 1 页" }
            return words
        }
        require(pageIndex >= 0 && pageIndex < pageCount) { "页号越界($pageIndex)，共 $pageCount 页" }
        val from = pageIndex * pageWords
        return words.copyOfRange(from, minOf(from + pageWords, words.size))
    }

    override fun word(index: Int): Int = words[index]

    override fun toIntArray(): IntArray = words.copyOf()
}

/**
 * 只读映射页来源（M6-CODE-VIEW 的落点）：字从 [buffer] 的绝对位置读，堆内只保留
 * 「当前这一页」（页由 VM 的取指局部变量持有），因此指令流的常驻堆占用是 0。
 *
 * 字节序固定小端：`.erbc` 的 u32/i32 是 `ErbcSink.u32` 逐字节小端写出的（行号 -1、
 * `SlotAddr.NONE` 都靠补码原样落 4 字节），所以映射读必须同样小端，否则每个操作数
 * 都是字节反序的脏值。
 *
 * 生命周期：映射的有效性由 [buffer] 的持有者（`MappedErbc`/`FileChannel`）决定；
 * 本类不关闭任何通道，也不复制整段 —— 只读引用 + 逐页复制。调用方关掉通道后
 * 映射块仍可读（JVM 映射语义），但 `buffer` 的对象本身不能提前被回收。
 */
class MappedCodeSource(
    buffer: ByteBuffer,
    override val wordCount: Int,
    override val pageWords: Int = CodeSource.DEFAULT_PAGE_WORDS,
) : CodeSource {
    init {
        require(wordCount >= 0) { "字数不能为负($wordCount)" }
        require(pageWords > 0 && pageWords % Instruction.WORDS == 0) {
            "页字数必须是 ${Instruction.WORDS} 的整数倍($pageWords)：否则一条指令会跨页"
        }
    }

    /** 只读副本：绝对位置读不移动 position，与调用方的切片游标互不干扰。 */
    private val read: ByteBuffer = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)

    /** 字流起点（= 入参 buffer 的 position，允许外层把整份产物切到该段）。 */
    private val base: Int = read.position()

    init {
        // 字节数用 Long 比：wordCount 接近 Int.MAX/4 时 `wordCount * Instruction.WORDS`
        // 会回绕成负数/小数，`remaining() >= 负数` 恒真，映射窗口校验直接失效，
        // 越界读会在 page()/word() 里变成 ByteBuffer 的越界异常（丢掉"窗口不足"这个信息）。
        // `wordCount` 来自产物 CODE 段的 u32 计数（spec: erbc-format.md §5.6 段 5：指令流），
        // 回绕还会顺带放大 [pageCount]；按 Long 比较后即得 `wordCount ≤ remaining/4 < 2^29`
        // ⇒ [page]/[word] 里的 `* 4` 同样不会溢出。
        val needBytes = wordCount.toLong() * Instruction.WORDS
        require(buffer.remaining().toLong() >= needBytes) {
            "映射窗口不足：需要 $needBytes 字节，只有 ${buffer.remaining()}"
        }
    }

    override val pageCount: Int get() = maxOf(1, ((wordCount.toLong() + pageWords - 1) / pageWords).toInt())

    override fun page(pageIndex: Int): IntArray {
        require(pageIndex >= 0 && pageIndex < pageCount) { "页号越界($pageIndex)，共 $pageCount 页" }
        val slot = pageIndex % PAGE_CACHE_SLOTS
        if (cacheKeys[slot] == pageIndex) return cache[slot]!!
        // 页首字下标与页内偏移都用 Long：pageIndex * pageWords 在 Int 上会回绕成负数，
        // 回绕后 wordCount - from 又变正，页长算错、读指针直接跑出映射窗口。
        val from = pageIndex.toLong() * pageWords
        val n = minOf(pageWords.toLong(), wordCount.toLong() - from).coerceAtLeast(0L).toInt()
        val out = IntArray(n)
        readPageInto(pageIndex, out, n)
        cacheKeys[slot] = pageIndex
        cache[slot] = out
        return out
    }

    /**
     * 原地填充（PERF 2026-10-27 第五批）：与 [page] 同一段读取逻辑，只是不建数组。
     * 供 VM 用它自己的常驻页缓冲调用，见 [CodeSource.supportsFillPage]。
     */
    override fun fillPage(pageIndex: Int, dst: IntArray): Int {
        require(pageIndex >= 0 && pageIndex < pageCount) { "页号越界($pageIndex)，共 $pageCount 页" }
        val from = pageIndex.toLong() * pageWords
        val n = minOf(pageWords.toLong(), wordCount.toLong() - from).coerceAtLeast(0L).toInt()
        require(dst.size >= n) { "页缓冲不足：本页 $n 字，缓冲只有 ${dst.size}" }
        readPageInto(pageIndex, dst, n)
        return n
    }

    override val supportsFillPage: Boolean get() = true

    /**
     * 第 [pageIndex] 页的 [n] 个字读进 [out] 的前 [n] 位（[page] / [fillPage] 的共同实现）。
     *
     * 批量搬字节再按小端解释（2026-10-23 真机提速）：旧实现逐字 `read.getInt(...)`，
     * 4096 字/页时是 4096 次绝对读（ART 走 `Memory.peekIntNative`）——世界初始化
     * 那一页实测占 VM 线程采样 39%。一次 `get(byte[],int,int)` 拷贝 + 一轮堆内解释
     * 把取指面从 JNI 级降到 memcpy 级；读数不变（同一段字节、同一小端序）。
     */
    private fun readPageInto(pageIndex: Int, out: IntArray, n: Int) {
        val from = pageIndex.toLong() * pageWords
        val bytes = n * Instruction.WORDS
        synchronized(pageScratchLock) {
            // 复用同一个 duplicate 游标（每次换页只改 position）；不再每次 duplicate()。
            val dup = scratchDup
            (dup as java.nio.Buffer).position(base + ((from * Instruction.WORDS).toInt()))
            dup.get(pageScratch, 0, bytes)
            // 字节 → int 手写循环：比 `ByteBuffer.wrap(...).asIntBuffer()` 少两次临时对象，
            // 且是纯堆内数组运算（不触发任何 JNI/checkIndex 包装）。
            var j = 0
            var i = 0
            while (j < bytes) {
                out[i] = (pageScratch[j].toInt() and 0xFF) or
                    ((pageScratch[j + 1].toInt() and 0xFF) shl 8) or
                    ((pageScratch[j + 2].toInt() and 0xFF) shl 16) or
                    ((pageScratch[j + 3].toInt() and 0xFF) shl 24)
                j += Instruction.WORDS
                i++
            }
        }
    }

    /**
     * 页批量拷贝的复用缓冲（[pageWords] × 4 B，缺省 16 KiB）。
     *
     * 只在上面的缓存未命中路径用；加一把锁是因为 [page] 属于公开的只读接口 —— VM 单线程
     * （ADR-003）不会并发，但这把锁把宿主侧误用挡成定义行为，代价是每次换页一次无争用加锁。
     */
    private val pageScratch = ByteArray(pageWords * Instruction.WORDS)
    private val pageScratchLock = Any()

    /** 批量读用的复用游标（与 [pageScratch] 同锁；每次换页只改 position）。 */
    private val scratchDup: ByteBuffer = read.duplicate()

    override fun word(index: Int): Int {
        require(index >= 0 && index < wordCount) { "码字下标越界($index)，共 $wordCount 字" }
        return read.getInt(base + index * 4)
    }

    /**
     * 页缓存（直接映射，[PAGE_CACHE_SLOTS] 槽）：`_CALL`/`_RET` 在「调用者页 ↔ 被调页」之间
     * 来回切，单页缓存会让每次调用都重新复制一页；4 槽把这条常见模式钉在堆内常驻
     * 64 KiB 以内，且不往 VM 热循环里加任何指令。
     *
     * 返回的数组按 [CodeSource.page] 契约**只读**且可被共享：VM 只读不写，
     * 嵌套 VM 之间共享同一来源也安全（ADR-003 单线程语义）。
     */
    private val cache = arrayOfNulls<IntArray>(PAGE_CACHE_SLOTS)

    private val cacheKeys = IntArray(PAGE_CACHE_SLOTS) { -1 }

    private companion object {
        /**
         * 页缓存槽数（64 × 16 KiB = 1 MiB 常驻）。
         *
         * 原为 8 槽（128 KiB）。JFR（`perf3.jfr`）实测 `page()` 占应用采样 **8.9%**
         * （其中 miss 路径 `page` line 207 占 6.4%）：`CHARA_MOVEMENT` 这类「按角色逐个
         * 调函数」的循环在几百个函数的首页之间来回跳，8 槽直接映射被冲干净，每次 miss
         * 都要重搬 16 KiB 再逐字小端解释。槽数抬到 64 后热函数的首页能整批常驻，
         * 代价是 1 MiB 堆（装载峰值 393 MiB / 576 MiB，余量充足）。
         */
        const val PAGE_CACHE_SLOTS = 64
    }
}
