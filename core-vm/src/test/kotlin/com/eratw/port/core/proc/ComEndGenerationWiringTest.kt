package com.eratw.port.core.proc

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W8-gen 定向单测 —— `endCallEventComEnd` 分支①的 `Process.cs:2433
 * console.updatedGeneration = true` 接线（[BeginHost.markGenerationUpdated] →
 * `ConsoleBeginHost` → `LineBufferConsoleSink.markGenerationUpdated`）。
 *
 * 钉的基准行（本地 reference 实测）：
 * - `Process.cs:2426` 临时行门、`:2428-2432` 空行半句（deleteLine(2) +
 *   PrintTemporaryLine(無効な値です)）——既有覆盖在 `ComEndInvalidValueTest`；
 * - `Process.cs:2433` 置位在**空行判定的 if 之外**、`:2426` 门之内 ⇒ 两种临时行形态
 *   都要置位；非临时行走尾部（`:2449-2455`）**不**置位；
 * - `Process.cs:2434 endCallShowUserCom()`（`:2321-2339`）重等**不重印**菜单
 *   （`setWaitInput` `:2330`）⇒ 置位是屏上旧按钮保持可点的唯一手段
 *   （机制面用例见 `core/host/GenerationRedisplayTest`）。
 */
class ComEndGenerationWiringTest {

    // ------------------------------------------------------------------
    // 假体（形制同 ComEndInvalidValueTest；置位通道记录调用次数）
    // ------------------------------------------------------------------

    private class FakeCalls : BeginCallPort {
        val called = ArrayList<String>()
        var ct: Boolean = false
        var consumed: Boolean = true
        var pending: BeginType? = null
        var lastSystemResult: Long? = null

        override fun callFunction(name: String, force: Boolean, isEvent: Boolean): Boolean {
            if (force) throw CodeEE("找不到函数\"@$name\"")
            return false
        }

        override fun result(): Long = 0L
        override fun setResult(value: Long) {}
        override fun systemResult(): Long = 0L
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
    ) : BeginHost {
        val deletedCounts = ArrayList<Int>()
        val temporaryLines = ArrayList<String>()
        var generationMarks = 0

        override fun lastLineIsTemporary(): Boolean = temporaryLine
        override fun lastLineIsEmpty(): Boolean = lastEmpty
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
    // 点位钉测：:2433 在空行判定的 if 之外、:2426 门之内
    // ------------------------------------------------------------------

    /** 非空临时行形态：不删行不打提示，但照样置位（`:2433` 无条件于 `:2428` 的 if）。 */
    @Test
    fun `非空临时行形态也置位`() {
        val host = FakeHost(temporaryLine = true, lastEmpty = false)
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        f.step()
        assertEquals(0, host.deletedCounts.size, "LastLineIsEmpty=false ⇒ :2430-2431 不走")
        assertEquals(1, host.generationMarks, "Process.cs:2433 无条件置位（W8-gen 通道）")
        assertEquals(SystemStateCode.Train_CallShowUserCom, f.state, ":2434 重等复用屏上菜单")
    }

    /** 空临时行形态：删行 + 提示 + 置位三者同轮发生（`:2428-2433`）。 */
    @Test
    fun `空临时行形态删行提示并置位`() {
        val host = FakeHost(temporaryLine = true, lastEmpty = true)
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        f.step()
        assertEquals(listOf(2), host.deletedCounts, "Process.cs:2430")
        assertEquals(listOf("无效的值"), host.temporaryLines, "Process.cs:2431（zhs 文案）")
        assertEquals(1, host.generationMarks, "Process.cs:2433")
        assertEquals(SystemStateCode.Train_CallShowUserCom, f.state)
    }

    /** 末行非临时行：走尾部回训练链，不置位（`:2433` 只属分支①）。 */
    @Test
    fun `非临时行走尾部不置位`() {
        val host = FakeHost(temporaryLine = false)
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        f.step()
        assertEquals(0, host.generationMarks, "Process.cs:2433 只在 :2426 门内")
        assertEquals(
            SystemStateCode.Train_CallEventTrain,
            f.state,
            "Process.cs:2449-2455 尾部（needWaitToEventComEnd 初值 false）",
        )
    }

    // ------------------------------------------------------------------
    // 真宿主全链：分支① → ConsoleBeginHost → 真 sink → 重等后旧菜单仍可点
    // ------------------------------------------------------------------

    /**
     * 玩家视角全链（非空临时行形态，菜单不被删行收走）：
     * 第一页菜单可点 → 指令链收尾置位重等 → **重等后旧菜单按钮仍在右侧栏可点**，
     * 预喂的指令号被正常消费（不因世代失配「点了没反应」）。
     * 重等走真等待路径（`stepTrainShowUserCom` → `awaitUserComInput` →
     * `ConsoleBeginHost.waitSystemInteger` → `noteInputWait`），
     * 脚本行身份走宿主缺省（`scriptLine = -1` → 行水位回退）——指令链推进过脚本行
     * ⇒ 无置位时该路径同样失配（`GenerationRedisplayTest` 的反面用例）。
     */
    @Test
    fun `分支①重等后屏上旧菜单按钮保持可点`() {
        val console = LineBufferConsoleSink(clientWidth = 80)
        val input = QueueInputSink()
        val host = ConsoleBeginHost(input, console)
        console.printButton("清掃", 410L)
        console.printButton("休息", 411L)
        // 第一页菜单等待（指令链前；sem 层 INPUT 等待与系统等待共用 noteInputWait 入口）。
        console.noteInputWait(
            InputRequest(type = InputRequest.InputType.INT, scriptLine = 101L),
        )
        assertEquals(listOf(410L, 411L), console.currentOptions().map { it.value as Long })

        // 非空临时行收尾的指令链（LastLineIsTemporary=true 且非空 ⇒ 不删行）。
        console.printTemporaryLine("ステータス")
        val calls = FakeCalls()
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Train_CallEventComEnd)
        val step = f.step()
        assertEquals(SystemStateCode.Train_CallShowUserCom, f.state)
        assertEquals(StepOutcome.CONTINUE, step.outcome)

        // 重等：预喂指令号，走真等待路径消费。
        assertTrue(input.submitInt(410L), "预喂的指令号入队")
        f.step()
        assertEquals(
            410L,
            calls.lastSystemResult,
            "Process.cs:426-430 —— InputSystemInteger 写 systemResult",
        )
        assertEquals(
            listOf(410L, 411L),
            console.currentOptions().map { it.value as Long },
            "重等后旧菜单仍可点（Process.cs:2433 的可见效果；失配形态见 GenerationRedisplayTest）",
        )
        assertFalse(console.lines().isEmpty())
    }
}
