package com.eratw.port.core.host

import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * [LineBufferConsoleSink] 的并发回归测试（A2：UI 线程 ConcurrentModificationException）。
 *
 * 背景：displayLines 是普通 ArrayList，VM 线程在 flush / PRINTN 并入 / REUSELASTLINE
 * 临时行先删 / 超 maxLines 头部淘汰 / CLEARLINE 处原地增删；UI 线程每帧经 render 的
 * ConsoleFeed 调 [LineBufferConsoleSink.lines] 做快照。未加锁时 UI 线程的 toList
 * 迭代器（modCount 校验）会撞上写侧并发修改，直接崩 UI 线程。
 *
 * 修复后所有 displayLines 读写都持 `synchronized(displayLines)`、revision 加 @Volatile，
 * 本测试用 1 个写线程 + 3 个读线程高频竞争约 2 秒，验证：
 * - 快照路径（lines / displayLineCount / buttons / loggedLines）不抛任何异常；
 * - 快照行数恒非负；
 * - revision 跨线程读单调不减（@Volatile 可见性）。
 *
 * 为什么是 2 秒：这是回归钉子，未加锁版本在高频竞争下第一秒内就会崩在 modCount
 * 校验上；跑太久只会拖慢单测反馈，不增加覆盖。
 */
class LineBufferSinkConcurrentTest {

    @Test
    fun concurrentWriterAndReaderThreadsDoNotCorruptSnapshots() {
        val sink = LineBufferConsoleSink(clientWidth = 80, maxLines = 500)
        val running = AtomicBoolean(true)
        // 线程内不做断言：断言异常只会杀死线程本身、静默变成「少跑了竞争」；
        // 统一收集失败原因，主线程 join 后一次性断言。
        val failures = Collections.synchronizedList(ArrayList<String>())
        val deadline = System.nanoTime() + 2_000_000_000L

        // 写线程：覆盖 appendGroup 的全部汇合路径（普通追加 / PRINTN 并入 / 临时行先删 /
        // 折行 / 超 maxLines 头部淘汰）与 deleteLine（CLEARLINE 摘尾）。单写多读，
        // 与生产「VM 单线程写、UI 单线程读」的形状一致（ADR-003）。
        val writer = Thread {
            var i = 0
            while (running.get()) {
                try {
                    when (i % 6) {
                        0 -> sink.print("普通输出 $i ")
                        1 -> sink.print("带换行的输出 $i\n第二段 $i")
                        2 -> sink.printLine("整行 $i")
                        3 -> sink.printSingleLine("独立行 $i")
                        4 -> sink.printTemporaryLine("临时行 $i")
                        else -> sink.print("待删的行 $i")
                    }
                    if (i % 3 == 0) sink.newLine()
                    if (i % 5 == 0) sink.deleteLine(1)
                } catch (t: Throwable) {
                    failures.add("写线程第 $i 轮：${t::class.java.name}: $t")
                    return@Thread
                }
                i++
            }
        }

        // 读线程：与 UI 线程每帧的动作一致——先看 revision 水位，再取快照；
        // 每轮核对快照行数非负、revision 相对上一次读单调不减。
        val readers = (0 until 3).map { id ->
            Thread {
                var lastRevision = -1L
                while (running.get()) {
                    try {
                        val snapshot = sink.lines()
                        if (snapshot.size < 0) failures.add("读线程 $id：快照行数为负")
                        val revision = sink.revision
                        if (revision < lastRevision) {
                            failures.add("读线程 $id：revision 回退 $lastRevision -> $revision")
                        }
                        lastRevision = revision
                        sink.displayLineCount
                        sink.buttons()
                        sink.loggedLines()
                    } catch (t: Throwable) {
                        failures.add("读线程 $id：${t::class.java.name}: $t")
                        return@Thread
                    }
                }
            }
        }

        writer.start()
        readers.forEach { it.start() }
        // 有界等待：固定跑 ~2 秒后置停跑标志，保证测试独立、快速、确定地结束。
        while (System.nanoTime() < deadline) Thread.sleep(50)
        running.set(false)
        writer.join(10_000)
        readers.forEach { it.join(10_000) }
        assertTrue(!writer.isAlive, "写线程应在停跑标志置位后退出")
        assertTrue(readers.all { !it.isAlive }, "读线程应在停跑标志置位后退出")
        // 竞争确实发生过的证据：写线程至少产生过一次可见变化，否则本测试是空转。
        assertTrue(sink.revision > 0, "写线程应已产生可见变化，否则测试没有覆盖到竞争")
        assertTrue(failures.isEmpty(), "并发读写不应有任何异常/失败：\n${failures.joinToString("\n")}")
    }

    /**
     * HTML 浮岛（islands）的并发回归钉子（A2 的同类缺陷，另一条状态）。
     *
     * 背景：`islands` 与 displayLines 一样是普通 ArrayList，写点 [LineBufferConsoleSink.addHtmlIsland]
     * / [LineBufferConsoleSink.clearHtmlIslands] 在 VM 线程（语句层 `HTML_PRINT_ISLAND[_CLEAR]`，
     * `StatementExecutor.kt:405-412`），读点 [LineBufferConsoleSink.htmlIslands] 是与
     * [LineBufferConsoleSink.lines] 同一族的宿主快照方法（宿主/UI 侧调）。
     *
     * 未收进锁时的半途状态（本用例钉的就是它）：`islands.toList()` 对 ArrayList 走的是
     * `toArray()` → `Arrays.copyOf(elementData, size)`，**不经过迭代器、所以不抛 CME**，
     * 但它会先读 `size` 再读 `elementData` 这两个互不同步的字段：
     * - 撞上写侧 `clear()` 的摘除循环 → 拷回一串 null；
     * - 撞上 `add()` 触发的扩容 → 拿到旧数组 + 新 size → 尾部 null。
     * 这就是 [LineBufferConsoleSink.lines] 那一条 KDoc 里写的「锁外拷贝可能看到扩容/摘除半途的
     * 尾部 null」——同一个教训，只是换了一条状态。
     *
     * 压力形状：写侧每代灌 [ISLAND_BATCH] 条（够大到让数组反复扩容、让摘除循环足够长，
     * 读侧的拷贝窗口才撞得上），读侧高频取快照。
     *
     * 断言的是浮岛快照的**结构不变量**（半途状态一定违反它）：
     * - 不抛任何异常；
     * - 元素恒为形如 `island-<n>` 的字符串（钉摘除/扩容半途留下的 null 与脏槽）；
     * - 同一快照内 n 严格递增且逐条 +1（写侧只在尾部按全局递增序号连续追加，
     *   交错读不可能出现乱序、重复或空洞）；
     * - 同一快照内条数不超过写侧一批的上限（钉「凭空多出一段」）。
     *
     * 修法口径是「并进已有的 displayLines 单一监视器」，所以读线程在同一次竞争里也读显示行
     * （[LineBufferConsoleSink.lines]/[LineBufferConsoleSink.loggedLines]）：一把锁管两种状态
     * 必须互不锁死，跑满 2 秒不退出即视为死锁。时长口径同
     * [concurrentWriterAndReaderThreadsDoNotCorruptSnapshots]。
     */
    @Test
    fun concurrentIslandWritesAndReadsNeverExposePartialSnapshot() {
        val sink = LineBufferConsoleSink(clientWidth = 80, maxLines = 500)
        val running = AtomicBoolean(true)
        val failures = Collections.synchronizedList(ArrayList<String>())
        val deadline = System.nanoTime() + 2_000_000_000L

        // 写线程：一轮「按全局递增序号连续追加 ISLAND_BATCH 条 + 清空」，中间穿插显示行输出，
        // 让两种状态在同一次竞争里交替被读写（同一把监视器）。
        val writer = Thread {
            var seq = 0L
            var gen = 0
            while (running.get()) {
                try {
                    repeat(ISLAND_BATCH) { sink.addHtmlIsland("island-${++seq}") }
                    if (gen % 3 == 0) sink.printLine("浮岛第 $gen 代")
                    if (gen % 4 == 0) sink.newLine()
                    sink.clearHtmlIslands()
                } catch (t: Throwable) {
                    failures.add("写线程第 $gen 代：${t::class.java.name}: $t")
                    return@Thread
                }
                gen++
            }
        }

        // 读线程：与 UI 线程每帧的读法一致——取浮岛快照并逐条校验结构。
        val readers = (0 until 3).map { id ->
            Thread {
                var checked = 0L
                var sawItems = 0L
                while (running.get()) {
                    try {
                        val snapshot = sink.htmlIslands()
                        checked++
                        sawItems += snapshot.size.toLong()
                        if (snapshot.size > ISLAND_BATCH) {
                            failures.add("读线程 $id：快照凭空超长 ${snapshot.size} > $ISLAND_BATCH（第 $checked 轮）")
                        }
                        // 以 List<Any?> 视角逐条看：锁外拷贝能看到 ArrayList 未写完的 null/脏槽。
                        val asAny: List<Any?> = snapshot
                        var prev = -1L
                        for ((index, value) in asAny.withIndex()) {
                            val text = value as? String
                            if (text == null) {
                                failures.add(
                                    "读线程 $id：快照第 $index/${snapshot.size} 条不是字符串" +
                                        "（写侧摘除或扩容半途被拷回来了）",
                                )
                                break
                            }
                            val n = text.removePrefix("island-").toLongOrNull()
                            if (n == null) {
                                failures.add("读线程 $id：快照第 $index 条形状非法「$text」")
                                break
                            }
                            // 写侧只按全局递增序号连续追加 ⇒ 任意一致快照都是这段连续序号的一个前缀，
                            // 逐条 +1；空洞/重复/乱序只可能是半途状态。
                            if (index > 0 && n != prev + 1) {
                                failures.add("读线程 $id：快照序号不连续 $prev -> $n（第 $checked 轮）")
                                break
                            }
                            prev = n
                        }
                        // 同一次竞争里把显示行侧也读一遍：一把锁管两种状态，取锁顺序必须不互锁。
                        sink.lines()
                        sink.loggedLines()
                    } catch (t: Throwable) {
                        failures.add("读线程 $id：${t::class.java.name}: $t")
                        return@Thread
                    }
                }
                // 竞争确实发生过的证据：读线程必须真的读到过非空浮岛表，否则本用例是空转。
                if (sawItems == 0L) failures.add("读线程 $id：整轮没读到过任何浮岛，测试没有覆盖到竞争")
            }
        }

        writer.start()
        readers.forEach { it.start() }
        while (System.nanoTime() < deadline) Thread.sleep(50)
        running.set(false)
        writer.join(10_000)
        readers.forEach { it.join(10_000) }
        assertTrue(!writer.isAlive, "写线程应在停跑标志置位后退出")
        assertTrue(readers.all { !it.isAlive }, "读线程应在停跑标志置位后退出（没退出即死锁）")
        // 竞争确实发生过的证据：写线程至少跑过一代（穿插过显示行输出）。
        assertTrue(sink.revision > 0, "写线程应已同时产生显示行变化，否则测试没有覆盖到两种状态同锁的竞争")
        assertTrue(failures.isEmpty(), "并发读写浮岛不应有任何异常/半途状态：\n${failures.joinToString("\n")}")
        // 停跑后主线程再读一次：写侧最终停在 clear()，浮岛必须是空的（安全发布后的确定终态）。
        assertTrue(sink.htmlIslands().isEmpty(), "写线程退出后浮岛应为空表")
    }

    private companion object {
        /**
         * 写侧每代灌进 `islands` 的条数。够大才让 ArrayList 一路扩容、`clear()` 的摘除循环
         * 也够长，读侧那次 `toArray` 拷贝才有可被撞上的窗口（几十条时窗口太窄，
         * 未加锁版本能一路绿过去 —— 2026-10-22 实测）。
         */
        const val ISLAND_BATCH = 2048
    }
}
