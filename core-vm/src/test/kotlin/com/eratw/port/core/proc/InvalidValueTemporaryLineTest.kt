package com.eratw.port.core.proc

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W10 保真度收口定向单测 —— 三处非法输入提示换道 / 两处空行半句接线
 * （标题 else 支、ABLUP/SHOP 收尾临时行支；形态与既有的 EVENTCOMEND 分支①
 * `ComEndInvalidValueTest` 同族：先删行、再画临时行、后置位）。
 *
 * 钉的基准行（本地 reference 实测，逐用例注明）：
 * - `Process.cs:2192-2198`（endOpenning else 支）：deleteLine(1)（`:2194`）→
 *   PrintTemporaryLine（`:2195`，Lang.Error.InvalidValue 的 zhs 文案）→
 *   置位（`:2196`）→ openingInput 重等（`:2197`）—— 原为 writeLine 普通行
 *   （提示残留屏上），W10 换道后与原版同寿（被下一次正式输出收走）。
 * - `Process.cs:2512-2528`（endCallAblupXX）：临时行门（`:2514`）内层空行半句
 *   `LastLineIsEmpty ⇒ deleteLine(2)`（`:2516-2518`）+ PrintTemporaryLine
 *   （`:2519`，**硬编码日文**「無効な値です」，不经 Lang 资源——同文件
 *   `:2431`/`:2499`/`:2649` 的兄弟无效值点位全走 Lang，唯此处字面量，
 *   zhs 化环境也打日文，ADR-005 照抄）→ 置位（`:2521`，空行判定**之外**）→
 *   endCallShowAblupSelect（`:2522`）；非临时行走 beginAblup（`:2526`）。
 * - `Process.cs:2642-2658`（endCallEventBuy）：临时行门（`:2644`）内层空行半句
 *   `LastLineIsEmpty ⇒ deleteLine(2)`（`:2646-2648`）+ PrintTemporaryLine
 *   （`:2649`，走 Lang.Error.InvalidValue.Text —— 与 ABLUP `:2519` 不同，
 *   **不经**硬编码）→ 置位（`:2651`）→ endCallShowShop（`:2652`）；
 *   非临时行走 endAutoSave（`:2656`）。
 *
 * 用例里的调用序断言（delete → temp → gen）钉的是「同位同形」：
 * 半句在置位之前、置位在空行判定之外临时行门之内（与 EVENTCOMEND 分支①先例一致）。
 */
class InvalidValueTemporaryLineTest {

    // ------------------------------------------------------------------
    // 假体（形制同 ComEndInvalidValueTest / SystemMenuGenerationWiringTest）
    // ------------------------------------------------------------------

    /** 函数表内返回 true；表外 force=false 返回 false、force=true 抛 CodeEE。 */
    private class FakeCalls(
        var functions: Set<String> = emptySet(),
    ) : BeginCallPort {
        val called = ArrayList<String>()
        var ct: Boolean = false
        var consumed: Boolean = true
        var pending: BeginType? = null
        var lastSystemResult: Long? = null
        var sys: Long = 0L

        override fun callFunction(name: String, force: Boolean, isEvent: Boolean): Boolean {
            called += name
            if (name in functions) return true
            if (force) throw CodeEE("找不到函数\"@$name\"")
            return false
        }

        override fun result(): Long = 0L
        override fun setResult(value: Long) {}
        override fun systemResult(): Long = sys
        override fun setSystemResult(value: Long) {
            lastSystemResult = value
        }

        override fun nextCom(): Long = -1L
        override fun setNextCom(value: Long) {}
        override fun selectCom(): Long = -1L
        override fun setSelectCom(value: Long) {}
        override fun isCTrain(): Boolean = ct
        override fun clearCommands(): Boolean {
            val was = ct
            ct = false
            return was
        }

        override fun beginTrainReset() {}
        override fun afterShowUserComReset() {}
        override fun afterInputComReset() {}
        override fun afterSourceCheckReset() {}
        override fun trainName(code: Int): String? = null
        override fun trainNameCount(): Int = 0
        override fun commandsConsumed(): Boolean = consumed
        override fun pendingBegin(): BeginType? = pending
        override fun takePendingBegin(): BeginType? = pending.also { pending = null }
        override fun clearPendingBegin() {
            pending = null
        }
    }

    /**
     * 事件序日志假体：deleteLine / printTemporaryLine / markGenerationUpdated 逐笔
     * 记进 [log]（`delete:<n>` / `temp:<text>` / `gen`），钉「半句先于置位」的原文次序。
     */
    private class FakeHost(
        var temporaryLine: Boolean = false,
        var lastEmpty: Boolean = false,
        var openingInput: Long? = null,
    ) : BeginHost {
        val log = ArrayList<String>()

        override fun awaitOpeningInput(): Long? = openingInput
        override fun lastLineIsTemporary(): Boolean = temporaryLine
        override fun lastLineIsEmpty(): Boolean = lastEmpty
        override fun deleteLine(count: Int) {
            log += "delete:$count"
        }

        override fun printTemporaryLine(text: String) {
            log += "temp:$text"
        }

        override fun markGenerationUpdated() {
            log += "gen"
        }
    }

    // ------------------------------------------------------------------
    // 标题菜单 else 支：Process.cs:2192-2198
    // ------------------------------------------------------------------

    /** `:2194-2196` —— 删 1 行 + 画临时行 + 置位，三笔按原文次序；随后 `:2197` 重等。 */
    @Test
    fun `标题菜单非法输入删一行画临时行再置位`() {
        val calls = FakeCalls().apply { sys = 5L }
        val host = FakeHost(openingInput = 0L)
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Openning)

        val step = f.step()
        assertEquals(
            listOf("delete:1", "temp:无效的值", "gen"),
            host.log,
            "Process.cs:2194 deleteLine(1) → :2195 PrintTemporaryLine（zhs 文案）→ :2196 置位",
        )
        assertEquals(0L, calls.lastSystemResult, ":2197 openingInput 重等取到的新值写回 systemResult")
        assertEquals(SystemStateCode.Openning, f.state, "openingInput 的状态半边：留在 Openning")
        assertEquals(StepOutcome.AWAIT_INPUT, step.outcome)
    }

    // ------------------------------------------------------------------
    // ABLUP 收尾：Process.cs:2512-2528
    // ------------------------------------------------------------------

    /**
     * `:2516-2521` —— 空临时行收尾：删 2 条逻辑行 + 画**日文硬编码**提示 + 置位。
     * 文案断言的是 `:2519` 的字面量「無効な値です」（不经 Lang，zhs 化环境同样如此），
     * 不是 zhs 译文「无效的值」。
     */
    @Test
    fun `ABLUP收尾空临时行删两行画日文提示再置位`() {
        val host = FakeHost(temporaryLine = true, lastEmpty = true)
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Ablup_CallAblupXX)

        val step = f.step()
        assertEquals(
            listOf("delete:2", "temp:無効な値です", "gen"),
            host.log,
            "Process.cs:2518 deleteLine(2) → :2519 PrintTemporaryLine(硬编码日文) → :2521 置位",
        )
        assertEquals(SystemStateCode.Ablup_CallShowAblupSelect, f.state, ":2522 重等（拆步经 stepAblupShowSelect）")
        assertEquals(StepOutcome.CONTINUE, step.outcome)
    }

    /** `:2516` 反面 —— 临时行收尾但末行**非空**：不删行、不打提示，置位照旧。 */
    @Test
    fun `ABLUP收尾非空临时行不走空行半句`() {
        val host = FakeHost(temporaryLine = true, lastEmpty = false)
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Ablup_CallAblupXX)

        f.step()
        assertEquals(listOf("gen"), host.log, "Process.cs:2516 LastLineIsEmpty=false ⇒ :2518-2519 不走；:2521 照旧")
        assertEquals(SystemStateCode.Ablup_CallShowAblupSelect, f.state)
    }

    /** `:2514` 反面 —— 非临时行收尾走 beginAblup（`:2526`）：半句与置位都不参与。 */
    @Test
    fun `ABLUP收尾非临时行不走半句不置位`() {
        val host = FakeHost(temporaryLine = false, lastEmpty = true)
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Ablup_CallAblupXX)

        f.step()
        assertEquals(emptyList(), host.log, "Process.cs:2514 门不成立 ⇒ 空行位即便为真也不达（:2521 也不置位）")
        assertEquals(SystemStateCode.Ablup_Begin, f.state, ":2526 beginAblup")
    }

    // ------------------------------------------------------------------
    // 商店收尾：Process.cs:2642-2658
    // ------------------------------------------------------------------

    /**
     * `:2646-2651` —— 空临时行收尾：删 2 条逻辑行 + 画提示 + 置位。文案断言的是
     * `:2649` 走 Lang.Error.InvalidValue.Text 的 zhs 译文「无效的值」——
     * 与 ABLUP `:2519` 的日文硬编码**不同源**，两处各照各的原文。
     */
    @Test
    fun `商店收尾空临时行删两行画无效的值再置位`() {
        val host = FakeHost(temporaryLine = true, lastEmpty = true)
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Shop_CallEventBuy)

        val step = f.step()
        assertEquals(
            listOf("delete:2", "temp:无效的值", "gen"),
            host.log,
            "Process.cs:2648 deleteLine(2) → :2649 PrintTemporaryLine(InvalidValue，zhs 文案) → :2651 置位",
        )
        assertEquals(SystemStateCode.Shop_CallShowShop, f.state, ":2652 重等（拆步经 stepShopShow）")
        assertEquals(StepOutcome.CONTINUE, step.outcome)
    }

    /** `:2646` 反面 —— 临时行收尾但末行**非空**：不删行、不打提示，置位照旧。 */
    @Test
    fun `商店收尾非空临时行不走空行半句`() {
        val host = FakeHost(temporaryLine = true, lastEmpty = false)
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Shop_CallEventBuy)

        f.step()
        assertEquals(listOf("gen"), host.log, "Process.cs:2646 LastLineIsEmpty=false ⇒ :2648-2649 不走；:2651 照旧")
        assertEquals(SystemStateCode.Shop_CallShowShop, f.state)
    }

    /** `:2644` 反面 —— 非临时行收尾走 endAutoSave（`:2656`）：半句与置位都不参与。 */
    @Test
    fun `商店收尾非临时行走endAutoSave不走半句不置位`() {
        val calls = FakeCalls(functions = setOf("SHOW_SHOP"))
        val host = FakeHost(temporaryLine = false, lastEmpty = true)
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Shop_CallEventBuy)

        f.step()
        assertEquals(emptyList(), host.log, "Process.cs:2644 门不成立 ⇒ 空行位即便为真也不达（:2651 也不置位）")
        assertEquals(listOf("SHOW_SHOP"), calls.called, ":2656 endAutoSave → SHOW_SHOP（force=true）")
        assertEquals(SystemStateCode.Shop_CallShowShop, f.state)
    }

    // ------------------------------------------------------------------
    // 真宿主全链：ConsoleBeginHost 三通道落在同一张真 sink 上（屏上净效果）
    // ------------------------------------------------------------------

    /**
     * ABLUP 收尾全链（`:2516-2522` 形态）：空临时行 + 菜单行被 `deleteLine(2)` 收走，
     * 屏上只剩一条日文提示临时行 —— 待下一次正式输出收走（保真度收口的屏上目的）。
     */
    @Test
    fun `真宿主全链 ABLUP空临时行收尾屏上只剩日文提示`() {
        val console = LineBufferConsoleSink(clientWidth = 80)
        val host = ConsoleBeginHost(QueueInputSink(), console)
        console.printLine("能力提升菜单")
        console.printTemporaryLine("　")
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Ablup_CallAblupXX)

        f.step()
        val rows = console.lines()
        assertEquals(listOf("無効な値です"), rows.map { console.textOf(it) })
        assertTrue(rows[0].isTemporary, "PrintTemporaryLine 落临时行（EmueraConsole.cs:3053-3056）")
    }

    /**
     * 标题 else 支全链（`:2194-2197` 形态）：菜单两行被 `deleteLine(1)` 收走末行，
     * 画「无效的值」临时行，重等输入（预喂 0）写回 systemResult —— 屏上净效果 =
     * 菜单剩首行 + 一条待收走的提示临时行。
     */
    @Test
    fun `真宿主全链 标题非法输入删一行画临时行`() {
        val console = LineBufferConsoleSink(clientWidth = 80)
        val input = QueueInputSink()
        val host = ConsoleBeginHost(input, console)
        console.printLine("［0］初次开始")
        console.printLine("［1］读取存档")
        val calls = FakeCalls().apply { sys = 5L }
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Openning)
        assertTrue(input.submitInt(0L), "预喂的重等输入入队")

        f.step()
        val rows = console.lines()
        assertEquals(
            listOf("［0］初次开始", "无效的值"),
            rows.map { console.textOf(it) },
            "Process.cs:2194 deleteLine(1) 收走末行 → :2195 画提示临时行",
        )
        assertTrue(rows[1].isTemporary)
        assertEquals(0L, calls.lastSystemResult, ":2197 openingInput 重等取到 0 写回 systemResult")
        assertEquals(SystemStateCode.Openning, f.state)
    }
}
