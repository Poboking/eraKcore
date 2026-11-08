package com.eratw.port.core.proc

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W9-gen 定向单测 —— updatedGeneration 同源缺口四处接线的点位钉测
 * （`Process.cs:2196`（标题菜单）/ `:2500`（ABLUP 缺函数）/ `:2521`（ABLUP 收尾临时行）/
 * `:2651`（商店收尾临时行）→ [BeginHost.markGenerationUpdated] → `ConsoleBeginHost` →
 * `LineBufferConsoleSink.markGenerationUpdated`；通道语义与「只置脏不前移」的机制依据
 * 见 W8-gen 已收口的 `ComEndGenerationWiringTest` 与 `core/host/GenerationRedisplayTest`）。
 *
 * 钉的基准行（本地 reference 实测，四段形态「非法输入 → 打提示 → 置位 → 重等」逐处核对）：
 * - `Process.cs:2192-2198`（endOpenning else 支）：deleteLine(1)（:2194）→
 *   PrintTemporaryLine（:2195，W10 换道：走 [BeginHost.printTemporaryLine] 临时行通道）
 *   → 置位（:2196）→ openingInput 重等（:2197）；合法输入（0/1）**不**置位。
 * - `Process.cs:2492-2502`（ablupWaitInput）：`systemResult` 在 0..99 且 `ABLUP{N}` 缺失 ⇒
 *   deleteLine(1)（:2498，删的是键入回显行 —— ECHO-INPUT 波次补上回显后本支换道，
 *   与标题菜单支同形）→ PrintTemporaryLine（:2499，走 [BeginHost.printTemporaryLine]
 *   临时行通道）→ 置位（:2500）→
 *   endCallShowAblupSelect 重等（:2501，setWaitInput :2486）；`>=100` 走 USERABLUP 支不置位。
 * - `Process.cs:2512-2528`（endCallAblupXX）：临时行收尾 ⇒ 空行半句（:2516-2520，
 *   W10 接线）→ 置位（:2521）→ endCallShowAblupSelect（:2522）；
 *   非临时行走 beginAblup（:2526）不置位。
 * - `Process.cs:2644-2653`（endCallEventBuy）：临时行收尾 ⇒ 空行半句（:2646-2650，
 *   W10 接线）→ 置位（:2651）→ endCallShowShop（:2652，setWaitInput :2605）；
 *   非临时行走 endAutoSave（:2656）不置位。
 * - `Process.cs:2852`/`:2868`/`:2930`（saveGameWaitInput / saveGameWaitInputOverwrite /
 *   loadGameWaitInput）：包含它们的四段存档 UI handler 移植侧**未接管**（[BeginFlow.step]
 *   落「未接管」BLOCKED）⇒ 无落点可接，判「暂不可接线」登记；本文件末组用例把该现状钉住，
 *   接管存档 UI 时须随行补置位（见 BeginHost.markGenerationUpdated 的 KDoc 登记）。
 */
class SystemMenuGenerationWiringTest {

    // ------------------------------------------------------------------
    // 假体（形制同 ComEndGenerationWiringTest；callFunction 按函数表分派）
    // ------------------------------------------------------------------

    /** 函数表内返回 true；表外 force=false 返回 false（缺函数路径）、force=true 抛 CodeEE。 */
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

    /** 只覆写本路用到的宿主位；[markGenerationUpdated] 计次以钉调用点位。 */
    private class FakeHost(
        var temporaryLine: Boolean = false,
        var lastEmpty: Boolean = false,
        var openingInput: Long? = null,
        var ablupInput: Long? = null,
    ) : BeginHost {
        val writtenLines = ArrayList<String>()
        val deletedCounts = ArrayList<Int>()
        val temporaryLines = ArrayList<String>()
        var generationMarks = 0

        override fun awaitOpeningInput(): Long? = openingInput
        override fun awaitAblupInput(): Long? = ablupInput
        override fun lastLineIsTemporary(): Boolean = temporaryLine
        override fun lastLineIsEmpty(): Boolean = lastEmpty
        override fun writeLine(text: String) {
            writtenLines += text
        }

        override fun deleteLine(count: Int) {
            deletedCounts += count
        }

        override fun printTemporaryLine(text: String) {
            temporaryLines += text
        }

        override fun markGenerationUpdated() {
            generationMarks++
        }
    }

    // ------------------------------------------------------------------
    // :2196 —— 标题菜单非法输入重等
    // ------------------------------------------------------------------

    /** 非法输入（非 0/1）：打提示 + 置位 + 重新取菜单输入（:2194-2197 四段）。 */
    @Test
    fun `标题菜单非法输入重等前置位`() {
        val calls = FakeCalls()
        calls.sys = 7L
        val host = FakeHost(openingInput = 0L)
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Openning)

        val step = f.step()
        assertEquals(listOf(1), host.deletedCounts, "Process.cs:2194 deleteLine(1)（W10 换道）")
        assertEquals(
            listOf("无效的值"),
            host.temporaryLines,
            "Process.cs:2195 PrintTemporaryLine（zhs 文案，W10 换道走临时行通道）",
        )
        assertTrue(host.writtenLines.isEmpty(), "W10 换道后提示不再经 writeLine 落普通行")
        assertEquals(1, host.generationMarks, "Process.cs:2196 置位（W9 通道）")
        assertEquals(0L, calls.lastSystemResult, ":2197 openingInput 重等取到的新值写回 systemResult")
        assertEquals(SystemStateCode.Openning, f.state, "openingInput 的状态半边：留在 Openning")
        assertEquals(StepOutcome.AWAIT_INPUT, step.outcome)
    }

    /** 合法输入（0）走新游戏支，不置位（:2196 只属 else 支）。 */
    @Test
    fun `标题菜单合法输入不置位`() {
        val calls = FakeCalls()
        calls.sys = 0L
        val host = FakeHost()
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Openning)

        f.step()
        assertEquals(0, host.generationMarks, "Process.cs:2169-2180 无置位点")
        assertEquals(SystemStateCode.First_Begin, f.state)
    }

    // ------------------------------------------------------------------
    // :2500 —— ABLUP 缺函数重等
    // ------------------------------------------------------------------

    /** 0..99 且 ABLUP{N} 缺失：删回显行 + 画临时行提示 + 置位 + 回等待（:2498-2501）。 */
    @Test
    fun `ABLUP缺函数重等前置位`() {
        val calls = FakeCalls()
        val host = FakeHost(ablupInput = 50L)
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Ablup_WaitInput)

        val step = f.step()
        assertEquals(listOf("ABLUP50"), calls.called, "Process.cs:2495 按输入拼函数名")
        assertEquals(listOf(1), host.deletedCounts, "Process.cs:2498 删键入回显行（ECHO-INPUT 换道）")
        assertEquals(listOf("无效的值"), host.temporaryLines, "Process.cs:2499 临时行通道（zhs 文案）")
        assertTrue(host.writtenLines.isEmpty(), "换道后提示不再经 writeLine 落普通行")
        assertEquals(1, host.generationMarks, "Process.cs:2500 置位（W9 通道）")
        assertEquals(SystemStateCode.Ablup_WaitInput, f.state, ":2501 endCallShowAblupSelect 重等")
        assertEquals(StepOutcome.AWAIT_INPUT, step.outcome)
    }

    /** `>=100` 走 USERABLUP 支（:2506-2508），不置位。 */
    @Test
    fun `ABLUP编号越界走USERABLUP不置位`() {
        val calls = FakeCalls(functions = setOf("USERABLUP"))
        val host = FakeHost(ablupInput = 150L)
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Ablup_WaitInput)

        f.step()
        assertEquals(0, host.generationMarks, "Process.cs:2500 只属缺函数支")
        assertEquals(SystemStateCode.Ablup_CallAblupXX, f.state)
    }

    // ------------------------------------------------------------------
    // :2521 —— ABLUP 收尾临时行
    // ------------------------------------------------------------------

    /** 临时行收尾（末行非空）：置位 + 回菜单重等（:2521-2522）；空行半句不触发（:2516 门）。 */
    @Test
    fun `ABLUP收尾临时行置位`() {
        val calls = FakeCalls()
        val host = FakeHost(temporaryLine = true)
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Ablup_CallAblupXX)

        f.step()
        assertEquals(1, host.generationMarks, "Process.cs:2521 置位（W9 通道）")
        assertEquals(
            emptyList(),
            host.deletedCounts,
            "Process.cs:2516 LastLineIsEmpty=false ⇒ :2518-2519 空行半句不触发（W10 门位）",
        )
        assertEquals(emptyList(), host.temporaryLines, "非空临时行不画提示（:2516 门）")
        assertEquals(SystemStateCode.Ablup_CallShowAblupSelect, f.state, ":2522 重等（拆步经 stepAblupShowSelect）")
    }

    /** 非临时行收尾走 beginAblup（:2526），不置位。 */
    @Test
    fun `ABLUP收尾非临时行不置位`() {
        val calls = FakeCalls()
        val host = FakeHost(temporaryLine = false)
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Ablup_CallAblupXX)

        f.step()
        assertEquals(0, host.generationMarks, "Process.cs:2521 只属临时行支")
        assertEquals(SystemStateCode.Ablup_Begin, f.state, ":2526 beginAblup")
    }

    // ------------------------------------------------------------------
    // :2651 —— 商店购买收尾临时行
    // ------------------------------------------------------------------

    /** 临时行收尾（末行非空）：置位 + 回商店重等（:2651-2652）；空行半句不触发（:2646 门）。 */
    @Test
    fun `商店收尾临时行置位`() {
        val calls = FakeCalls()
        val host = FakeHost(temporaryLine = true)
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Shop_CallEventBuy)

        f.step()
        assertEquals(1, host.generationMarks, "Process.cs:2651 置位（W9 通道）")
        assertEquals(
            emptyList(),
            host.deletedCounts,
            "Process.cs:2646 LastLineIsEmpty=false ⇒ :2648-2649 空行半句不触发（W10 门位）",
        )
        assertEquals(emptyList(), host.temporaryLines, "非空临时行不画提示（:2646 门）")
        assertEquals(SystemStateCode.Shop_CallShowShop, f.state, ":2652 endCallShowShop 重等（拆步经 stepShopShow）")
    }

    /** 非临时行收尾走 endAutoSave（:2656 → SHOW_SHOP），不置位。 */
    @Test
    fun `商店收尾非临时行不置位`() {
        val calls = FakeCalls(functions = setOf("SHOW_SHOP"))
        val host = FakeHost(temporaryLine = false)
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Shop_CallEventBuy)

        f.step()
        assertEquals(0, host.generationMarks, "Process.cs:2651 只属临时行支")
        assertEquals(SystemStateCode.Shop_CallShowShop, f.state, ":2656 endAutoSave → SHOW_SHOP")
    }

    // ------------------------------------------------------------------
    // :2852 / :2868 / :2930 —— 存读档 UI handler 未接管（判「暂不可接线」的现状钉测）
    // ------------------------------------------------------------------

    /**
     * 三处置位的包含 handler（saveGameWaitInput / saveGameWaitInputOverwrite /
     * loadGameWaitInput，`Process.cs:2817-2957`）在移植侧无 step 落点：状态落在
     * [BeginFlow.step] 的「未接管」BLOCKED 支。接管存档 UI 的波次须随行补置位。
     */
    @Test
    fun `存读档UI状态未接管且不置位`() {
        val unported = listOf(
            SystemStateCode.SaveGame_WaitInput,
            SystemStateCode.SaveGame_WaitInputOverwrite,
            SystemStateCode.LoadGame_WaitInput,
            SystemStateCode.LoadGameOpenning_WaitInput,
        )
        for (code in unported) {
            val calls = FakeCalls()
            val host = FakeHost(temporaryLine = true)
            val f = BeginFlow(calls, host)
            f.enterState(code)

            val step = f.step()
            assertEquals(StepOutcome.BLOCKED, step.outcome, "$code 应落未接管 BLOCKED")
            assertEquals(BlockerKind.NOT_IMPLEMENTED, step.blocker?.kind, "$code 的 blocker 形态")
            assertEquals(0, host.generationMarks, "$code 无置位落点（登记见 BeginHost KDoc）")
        }
    }

    // ------------------------------------------------------------------
    // 真宿主全链：ABLUP 缺函数重等 → ConsoleBeginHost → 真 sink → 旧按钮仍可点
    // ------------------------------------------------------------------

    /**
     * 玩家视角全链（:2500 形态，eraTW 可达：能力提升菜单输 5..9/19..29/36..99 即缺函数）：
     * 菜单页按钮可点 → 非法输入置位重等 → **重等后旧页按钮仍在右侧栏可点**。
     * 置位通道走真链（[ConsoleBeginHost.markGenerationUpdated] →
     * `LineBufferConsoleSink.markGenerationUpdated`），重等走真等待路径
     * （[ConsoleBeginHost.awaitAblupInput] → `waitSystemInteger` → `noteInputWait`）；
     * 脚本行身份走宿主缺省（`scriptLine = -1` → 行水位回退）——提示行推进过行水位，
     * 无置位时该路径同样失配（反面机制见 GenerationRedisplayTest）。
     */
    @Test
    fun `ABLUP缺函数重等后屏上旧按钮保持可点`() {
        val console = LineBufferConsoleSink(clientWidth = 80)
        val input = QueueInputSink()
        val host = ConsoleBeginHost(input, console)
        // 生产装配同形线（GameSession / BootPlayChain 各有一行同形装配）：取值交付先回显
        // 一行（EmueraConsole.cs:1280-1282），:2498 的 deleteLine(1) 删的才是这行回显，
        // 不是按钮行——不接线时 deleteLine(1) 会误删按钮行（本波次要修的缺陷形态本身）。
        input.onEchoInput = { console.printLine(it) }
        console.printButton("Ｃ感覚", 0L)
        console.printButton("確認", 100L)
        // 首等与后面的宿主重等必须走**同一条身份通道**（scriptLine = -1 → 行水位回退，
        // 同 ConsoleBeginHost 缺省）：若首等给显式 101、重等回退行水位，身份位在
        // 置位之前的中间等待就翻转一次，按钮会先于被测机制死掉——那是测试假象，
        // 不是 :2500 路径的形态（真流所有等待都经同一个 ConsoleBeginHost 通道）。
        console.noteInputWait(
            InputRequest(type = InputRequest.InputType.INT, scriptLine = -1L),
        )
        assertEquals(listOf(0L, 100L), console.currentOptions().map { it.value as Long })

        // 非法输入 50（FakeCalls 无 ABLUP50 → force=false 缺函数 → :2498-2501 路径）。
        val calls = FakeCalls()
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Ablup_WaitInput)
        assertTrue(input.submitInt(50L), "预喂的非法值入队")
        f.step()
        assertEquals(SystemStateCode.Ablup_WaitInput, f.state, ":2501 重等")
        assertEquals(
            listOf("无效的值"),
            console.lines().map { console.textOf(it) }.filter { it == "无效的值" },
            "Process.cs:2499 提示行上屏（printTemporaryLine 通道，ECHO-INPUT 换道）",
        )

        // 重等：预喂下一个输入，走真等待路径消费；置位已保住旧页按钮世代。
        assertTrue(input.submitInt(0L), "预喂的重等输入入队")
        f.step()
        assertEquals(
            listOf(0L, 100L),
            console.currentOptions().map { it.value as Long },
            "重等后旧页按钮仍可点（Process.cs:2500 的可见效果；失配形态见 GenerationRedisplayTest）",
        )
    }
}
