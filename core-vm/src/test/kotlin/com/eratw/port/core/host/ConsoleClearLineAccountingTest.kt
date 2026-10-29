package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CLEARLINE 的三件账：`deletedLines` 记账、`lineNo` 回退/回绕、`MaxLog` 淘汰与补行。
 *
 * ## 出处
 *
 * - `EmueraConsole.deleteLine`（EmueraConsole.cs:2999-3051）整段；
 * - `addDisplayLine` 的入列侧（:2969-2996）：`lineNo++`（:2970）、两条回绕（:2979-2987）、
 *   头部淘汰 `deletedLines++`（:2988-2996）；
 * - `ClearDisplay` 的三个计数归零（:2801-2813）—— 移植侧的 [LineBufferConsoleSink] 没有
 *   对应方法（清屏走 render 侧 `ConsoleScreen`/`LogBuffer.clear`），故本文件不覆盖。
 *
 * ## 为什么这三件要复刻
 *
 * `lineNo` 不是日志：`deleteLine` 的**补行判据** `lineNo > displayLineList.Count`（:3026）
 * 直接读它，而这条判据在 `CLEARLINE` 把自己归零（:3034）之后就再也推不出来 —— 必须真维护
 * 一个计数器。`MaxLog` 那一对分支（:3022-3030、:3046-3049）则会**改变可见行**：掉到
 * `MaxLog - 2` 时往**头部**插一条（内容取自打印缓冲），`CLEARLINE 0` 在满容时摘掉最旧一行。
 *
 * ## 复刻到什么程度
 *
 * 原版的账并不自洽，本文件把不自洽的部分也钉住，防止后人「顺手修好」：
 * - `deletedLines -= argNum`（:3050）是**无条件**的，所以非满容的 `CLEARLINE 3` 会让它
 *   变成 -3；负数实参会让它加上去；
 * - 删不够时 `logicalLineCount -= argNum - num`（:3035）会把 LINECOUNT 扣成负数；
 * - `CLEARLINE 0` / 负数实参时循环不跑，但收尾的 `if (Count == MaxLog) RemoveAt(0)` 仍可能摘行。
 */
class ConsoleClearLineAccountingTest {

    private fun sink(maxLines: Int = 100, clientWidth: Int = 80) =
        LineBufferConsoleSink(clientWidth = clientWidth, maxLines = maxLines)

    // ==================================================================
    // 1. lineNo：入列递增、逐排回退、删不够归零
    // ==================================================================

    /** `lineNo++` 每排一次（:2970），`deleteLine` 每摘一排 `lineNo--` 一次（:3013）。 */
    @Test
    fun `lineNo 随入列递增并随 CLEARLINE 逐排回退`() {
        val s = sink()
        s.printLine("a")
        s.printLine("b")
        s.printLine("c")
        assertEquals(3, s.lineNo, "三排各占一号（:2970）")
        assertEquals(0L, s.deletedLines, "没淘汰过")

        s.deleteLine(1)
        assertEquals(2, s.lineNo, "摘一排退一号（:3013）")
        assertEquals(2L, s.lineCount)
        assertEquals(-1L, s.deletedLines, ":3050 无条件 `-= argNum` —— 非满容时这个账会变负")
    }

    /**
     * 删不够：`lineNo` 归零，差额按逻辑行硬扣（:3032-3036）。
     *
     * 原版不看被扣的那些行是不是逻辑行，所以 LINECOUNT 会被扣成负数 —— 复刻。
     */
    @Test
    fun `删不够时 lineNo 归零且 LINECOUNT 按差额硬扣`() {
        val s = sink()
        s.printLine("a")
        s.printLine("b")
        s.printLine("c")

        s.deleteLine(5) // 只有 3 排可删
        assertEquals(0, s.lineNo, ":3034 归零")
        assertEquals(0, s.lines().size)
        assertEquals(-2L, s.lineCount, "3 - 5 = -2（:3035 不夹下界）")
        assertEquals(-5L, s.deletedLines)
    }

    /** 折行的一排各占一个行号，但 `num` 只数逻辑行 ⇒ 一次 `CLEARLINE 1` 摘掉整条（:3014-3021）。 */
    @Test
    fun `折行的每一排各占一个行号`() {
        val s = sink(clientWidth = 4)
        s.printLine("abcdefgh") // 宽 8 ⇒ 两排 abcd / efgh
        assertEquals(2, s.lines().size)
        assertEquals(2, s.lineNo, "折行的续排也各占一号")
        assertEquals(1L, s.lineCount, "逻辑行只算一条")

        s.deleteLine(1)
        assertEquals(0, s.lines().size, "整条逻辑行一起出列")
        assertEquals(0, s.lineNo, "两排各退一号")
        assertEquals(0L, s.lineCount)
    }

    /** 临时行替换：原版走 `deleteLine(1)` + `addDisplayLine`（:2891-2893），行号一减一加抵平。 */
    @Test
    fun `临时行替换不让 lineNo 虚涨`() {
        val s = sink()
        s.printTemporaryLine("t1")
        s.printTemporaryLine("t2")
        assertEquals(1, s.lines().size)
        assertEquals(1, s.lineNo, "替换不是新增，行号不该涨到 2")
        assertEquals("t2", s.lines().single().segments.single().text)
    }

    /** PRINTN 并入同理：原版并入前也 `deleteLine(1)`（:2954）。 */
    @Test
    fun `PRINTN 并入不让 lineNo 虚涨`() {
        val s = sink()
        s.print("a", lineEnd = false)
        s.newLine() // isLineEnd=false 的一排
        s.print("b", lineEnd = true)
        s.newLine()

        assertEquals(1, s.lines().size)
        // 并入**不**做同样式合并：原版 `line.ChangeStr(array)` 只是把旧行的按钮串接到新行前面
        // （EmueraConsole.cs:2956-2966），两条 ConsoleButtonString 各自保留。
        assertEquals(listOf("a", "b"), s.lines().single().segments.map { it.text })
        assertEquals(1, s.lineNo, "并入是「删一排 + 加一排」，净额为零")
    }

    // ==================================================================
    // 2. deletedLines：淘汰 +1，CLEARLINE 收尾 -= argNum
    // ==================================================================

    /** 头部淘汰 `deletedLines++`（:2988-2996）；CLEARLINE 不动它以外的账。 */
    @Test
    fun `MaxLog 淘汰时 deletedLines 逐行累加`() {
        val s = sink(maxLines = 3)
        repeat(4) { s.printLine("l$it") }

        assertEquals(3, s.lines().size, "稳态保留数等于 MaxLog")
        assertEquals(1L, s.deletedLines, "淘汰一行记一次（:2995）")
        assertEquals(4, s.lineNo, "lineNo 是「已入列排数」，淘汰不回退")
    }

    /**
     * `CLEARLINE 0` 在满容时摘掉最旧一行（:3046-3049 的收尾夹取）。
     *
     * 原版 `deleteLine` 没有 `argNum <= 0` 的早退，这一步是唯一可能摘行的地方；
     * 注意它**不动** `lineNo` —— 于是 `lineNo > size` 的关系从这里开始就成立了。
     */
    @Test
    fun `CLEARLINE 0 在满容时摘掉最旧一行且不动行号`() {
        val s = sink(maxLines = 3)
        s.printLine("a")
        s.printLine("b")
        s.printLine("c")
        assertEquals(3, s.lineNo)

        s.deleteLine(0)
        assertEquals(listOf("b", "c"), s.lines().map { it.segments.single().text }, "摘的是头部一行（:3048）")
        assertEquals(3, s.lineNo, "循环没跑 ⇒ 行号不变")
        assertEquals(0L, s.deletedLines, "`-= 0` 不改变")
        assertEquals(3L, s.lineCount, "LINECOUNT 也不回退")
    }

    /** 负数实参：循环不跑、列表不动，但 `deletedLines -= argNum` 会把它加上去（:3050）。 */
    @Test
    fun `CLEARLINE 负参数只改 deletedLines`() {
        val s = sink(maxLines = 3)
        s.printLine("a")
        s.printLine("b")

        s.deleteLine(-5)
        assertEquals(2, s.lines().size, "列表不动")
        assertEquals(2, s.lineNo)
        assertEquals(5L, s.deletedLines, "-(-5) ⇒ +5")
    }

    // ==================================================================
    // 3. MaxLog 补行（:3026-3030）
    // ==================================================================

    /**
     * 补行：列表掉到 `MaxLog - 2` 且**已淘汰过头部**（`lineNo > size`）时，把打印缓冲排成的
     * 单行插回**头部**。这一行不经 `addDisplayLine` ⇒ 不涨 `lineNo`、不涨 LINECOUNT。
     */
    @Test
    fun `列表掉到 MaxLog 减二且已淘汰过头部时在头部补一条`() {
        val s = sink(maxLines = 4)
        repeat(6) { s.printLine("l$it") }
        assertEquals(4, s.lines().size)
        assertEquals(2L, s.deletedLines)
        assertEquals(6, s.lineNo)
        assertEquals(6L, s.lineCount)

        s.deleteLine(2)
        // 第 1 轮：摘 l5 ⇒ size 3 == MaxLog-1 ⇒ deletedLines++
        // 第 2 轮：摘 l4 ⇒ size 2 == MaxLog-2 且 lineNo(4) > size(2) ⇒ 头部补一条空白行
        assertEquals(3, s.lines().size, "补行把 size 抬回 3")
        assertEquals(listOf(" ", "l2", "l3"), s.lines().map { it.segments.single().text })
        assertEquals(4, s.lineNo, "补行不涨 lineNo")
        assertEquals(4L, s.lineCount, "补行不涨 LINECOUNT")
        assertEquals(1L, s.deletedLines, "2 + 1（:3024）- 2（:3050）= 1")
    }

    /** 补行的内容是**打印缓冲**，不是恒定空格；缓冲非空时用它，用完即清。 */
    @Test
    fun `补行取打印缓冲的内容并清空缓冲`() {
        val s = sink(maxLines = 4)
        repeat(6) { s.printLine("l$it") }
        s.print("待决", lineEnd = false)
        assertFalse(s.printingBufferEmpty, "缓冲里有待决内容")

        s.deleteLine(2)
        assertEquals(listOf("待决", "l2", "l3"), s.lines().map { it.segments.single().text })
        assertTrue(s.printingBufferEmpty, "FlushSingleLine 会 clearBuffer（PrintStringBuffer.cs:175）")
    }

    /**
     * 反证：没有淘汰过头部（`lineNo == size`）时**不**补行。
     *
     * 这是 :3026 判据的另一半 —— 光看「掉到 MaxLog - 2」不够，必须是「头部已经丢过行」。
     */
    @Test
    fun `未淘汰过头部时不补行`() {
        val s = sink(maxLines = 4)
        repeat(4) { s.printLine("l$it") } // 恰好满，未淘汰
        assertEquals(4, s.lineNo)
        assertEquals(0L, s.deletedLines)

        s.deleteLine(2)
        // 第 1 轮：size 3 == MaxLog-1 ⇒ deletedLines++；第 2 轮：size 2 == MaxLog-2 但
        // lineNo(2) > size(2) 不成立 ⇒ 不补行。
        assertEquals(2, s.lines().size)
        assertEquals(listOf("l0", "l1"), s.lines().map { it.segments.single().text })
        assertEquals(2, s.lineNo)
    }
}
