package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ECHO-INPUT 抑制档行账本的「多轮」钉子（B3）。
 *
 * ## 病灶
 *
 * 抑制档（[com.eratw.port.core.proc.EchoInputMode.SUPPRESSED]）不回显显示行，但每轮输入
 * 都要在原版行账里留一笔：原版交付点就是一句普通 [EmueraConsole.Print] —— 输入处理末尾
 * `Print(str); PrintFlush(force: false);`（`EmueraConsole.cs:1280-1282`），
 * 经 `addDisplayLine` 落成一行（`displayLineList.Add` :2969、`lineNo++` :2970、
 * 逻辑行计数 :2971-2977）⇒ 每轮输入各占**一个** LINECOUNT 与**一个**显示行。
 *
 * 旧实现只有 `suppressedEchoActive: Boolean`，只标**最近一轮**：连续 N 轮输入后
 * 账本深度仍是 1，而 LINECOUNT 已经 +N。于是原版「删 N 行就删掉这 N 轮回显」的
 * `CLEARLINE N` 在移植侧先折算 1 笔虚拟行、余下 N-1 笔转去摘**真显示行**
 * （[LineBufferConsoleSink.deleteLine] → `deleteRowsLocked`，`EmueraConsole.cs:3007-3031`），
 * 屏上少掉 N-1 行老内容；`CLEARLINE LINECOUNT - L` 这类派生清屏（语料 124 文件）
 * 因此「按原版行数多扣」。
 *
 * ## 判据
 *
 * 每轮回显各留一笔虚拟行账，且**只有表尾那几笔**可被 `deleteLine` 折算：
 * 一轮真实打印入列（`appendGroup`）就把旧虚拟行埋进历史（原版它们此刻已不在表尾，
 * 尾部删除碰不到），这与 `EchoInputToggleTest` 的页面重画环用例同一口径。
 */
class SuppressedEchoLedgerRoundsTest {

    private fun sink(width: Int = 40, maxLines: Int = 1000) =
        LineBufferConsoleSink(clientWidth = width, maxLines = maxLines)

    private fun live(s: LineBufferConsoleSink): List<String> = s.lines().map { s.textOf(it) }

    /**
     * 三行老内容 + 连续三轮输入（中间无打印无删除）+ `CLEARLINE 3`。
     *
     * 原版：三行老内容之上是三行回显，`CLEARLINE 3` 正好删掉三行回显 ⇒ 老内容原样保留、
     * LINECOUNT 回到 3（`EmueraConsole.cs:3007-3031` 逐行摘排、:3014-3021 逐逻辑行扣数）。
     * 旧实现：账本深度 1 ⇒ 折算一笔后还要摘两行真内容（末行与倒数第二行），屏上少两行。
     */
    @Test
    fun `连续三轮回显各留一笔账 紧随的 CLEARLINE 3 一行真内容都不许摘`() {
        val s = sink()
        s.printLine("甲")
        s.printLine("乙")
        s.printLine("丙")
        assertEquals(3L, s.lineCount)

        repeat(3) { s.noteSuppressedEchoLine() }
        assertEquals(6L, s.lineCount, "每轮输入各记一笔（原版三行回显，:2970-2977）")

        s.deleteLine(3)

        assertEquals(listOf("甲", "乙", "丙"), live(s), "三笔虚拟行账吃满 3，真行一行都不该动")
        assertEquals(3L, s.lineCount, "LINECOUNT 回到输入前的读数")
    }

    /**
     * 删除量超过账本深度：先折算全部虚拟行，余下的才从真行表尾摘
     * （原版 `CLEARLINE 4` = 三行回显 + 一行老内容，`EmueraConsole.cs:3007-3031`）。
     */
    @Test
    fun `CLEARLINE 超过账本时虚拟行先扣满 余量再摘真行`() {
        val s = sink()
        s.printLine("甲")
        s.printLine("乙")
        repeat(3) { s.noteSuppressedEchoLine() }
        assertEquals(5L, s.lineCount)

        s.deleteLine(4)

        assertEquals(listOf("甲"), live(s), "只该摘掉一行真内容（乙）")
        assertEquals(1L, s.lineCount)
    }

    /**
     * 账本只保护**表尾**那几笔：一轮真实打印入列后，旧虚拟行已不在表尾，
     * 尾部删除不该再拿它们抵扣。
     */
    @Test
    fun `真实打印跨过账本后 旧虚拟行不再抵删除量`() {
        val s = sink()
        s.printLine("甲")
        repeat(2) { s.noteSuppressedEchoLine() }
        s.printLine("乙") // appendGroup：两笔旧虚拟行沉进历史
        assertEquals(4L, s.lineCount)

        s.deleteLine(1)

        assertEquals(listOf("甲"), live(s), "删的是表尾真行「乙」")
        assertEquals(3L, s.lineCount, "旧虚拟行仍在 LINECOUNT 里，但不再参与折算")
    }

    /**
     * 部分折算后剩余账本仍有效：两笔虚拟行，`CLEARLINE 1` 只吃一笔，
     * 紧接着再 `CLEARLINE 1` 仍只吃虚拟行、不碰真行。
     */
    @Test
    fun `部分折算后剩余虚拟行账继续生效`() {
        val s = sink()
        s.printLine("甲")
        repeat(2) { s.noteSuppressedEchoLine() }
        assertEquals(3L, s.lineCount)

        s.deleteLine(1)
        assertEquals(2L, s.lineCount)
        assertEquals(listOf("甲"), live(s), "第一笔删除完全花在虚拟行上")

        s.deleteLine(1)
        assertEquals(1L, s.lineCount)
        assertEquals(listOf("甲"), live(s), "第二笔仍花在虚拟行上")
    }

    /**
     * 零/负删除量不折算（原版 `CLEARLINE 0` 与负数有独立收尾，
     * `EmueraConsole.cs:3046-3050`，按 ADR-005 原样复刻）：账本必须原样留着。
     */
    @Test
    fun `CLEARLINE 0 不消耗虚拟行账`() {
        val s = sink()
        s.printLine("甲")
        repeat(2) { s.noteSuppressedEchoLine() }
        s.deleteLine(0)
        assertEquals(3L, s.lineCount)

        s.deleteLine(2)
        assertEquals(1L, s.lineCount)
        assertEquals(listOf("甲"), live(s), "账本没被 0 那一刀吃掉")
    }
}
