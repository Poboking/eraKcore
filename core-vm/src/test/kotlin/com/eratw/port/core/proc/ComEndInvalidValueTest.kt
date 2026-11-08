package com.eratw.port.core.proc

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AUD-b1b2（W7 审计波）定向单测 —— `endCallEventComEnd` 分支①的空行半句与
 * 分支②「CALLTRAINEND 缺失落尾部」的补面（本地 reference 实测行号）。
 *
 * 钉的基准行（逐用例注明）：
 * - `Process.cs:2426` 临时行门（`LastLineIsTemporary && !isCTrain && needCheck`）——
 *   先于空行判定，空行位不单独开门；
 * - `Process.cs:2428-2432` `LastLineIsEmpty ⇒ deleteLine(2) +
 *   PrintTemporaryLine(無効な値です)`（zhs「无效的值」，`Lang.cs:3130` /
 *   lang.emuera-zhs.xml:737）；
 * - `Process.cs:2434` `endCallShowUserCom()`（移植侧嵌套直调拆步：换态
 *   Train_CallShowUserCom，下一步才是等输入，见 BeginFlow 类头说明）；
 * - `Process.cs:2439-2443/:2446/:2449` 分支② —— CALLTRAINEND（force=false）缺失时
 *   不 return：队列已清（`:2439`、`:2441-2442` 先于 `:2443` 的派发），落尾部回训练链。
 */
class ComEndInvalidValueTest {

    // ------------------------------------------------------------------
    // 假体：除点名函数外一律「未定义」，CALLTRAINEND 缺失路径依赖这一点
    // ------------------------------------------------------------------

    private class FakeCalls : BeginCallPort {
        val called = ArrayList<String>()
        var ct: Boolean = false
        var consumed: Boolean = true
        var pending: BeginType? = null

        override fun callFunction(name: String, force: Boolean, isEvent: Boolean): Boolean {
            if (force) throw CodeEE("找不到函数\"@$name\"")
            return false
        }

        override fun result(): Long = 0L
        override fun setResult(value: Long) {}
        override fun systemResult(): Long = 0L
        override fun setSystemResult(value: Long) {}
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

    /** 只覆写本路用到的宿主位；其余走 BeginHost 缺省（缺省纪律「没有该能力不伪造」）。 */
    private class FakeHost(
        var temporaryLine: Boolean = false,
        var lastEmpty: Boolean = false,
    ) : BeginHost {
        val deletedCounts = ArrayList<Int>()
        val temporaryLines = ArrayList<String>()

        override fun lastLineIsTemporary(): Boolean = temporaryLine
        override fun lastLineIsEmpty(): Boolean = lastEmpty
        override fun deleteLine(count: Int) {
            deletedCounts += count
        }

        override fun printTemporaryLine(text: String) {
            temporaryLines += text
        }
    }

    // ------------------------------------------------------------------
    // 分支①：Process.cs:2426-2436
    // ------------------------------------------------------------------

    /** `:2428-2432` —— 空临时行：删 2 逻辑行 + 画「无效的值」临时行，再交重等。 */
    @Test
    fun `末行为空临时行时删两行画无效的值再交重等`() {
        val host = FakeHost(temporaryLine = true, lastEmpty = true)
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        val step = f.step()
        assertEquals(listOf(2), host.deletedCounts, "Process.cs:2430 deleteLine(2)")
        assertEquals(
            listOf("无效的值"),
            host.temporaryLines,
            "Process.cs:2431 PrintTemporaryLine(Lang.Error.InvalidValue)（zhs 文案）",
        )
        assertEquals("endCallEventComEnd", step.handler)
        assertEquals(StepOutcome.CONTINUE, step.outcome)
        assertEquals(
            SystemStateCode.Train_CallShowUserCom,
            f.state,
            "Process.cs:2434 endCallShowUserCom（拆步：下一步 stepTrainShowUserCom 等输入）",
        )
    }

    /** `:2428` 反面 —— 末行是**非空**临时行：不删行、不打提示，照常交重等。 */
    @Test
    fun `末行为非空临时行时不删行不打提示`() {
        val host = FakeHost(temporaryLine = true, lastEmpty = false)
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        val step = f.step()
        assertEquals(emptyList(), host.deletedCounts, "LastLineIsEmpty=false ⇒ :2430-2431 不走")
        assertEquals(emptyList(), host.temporaryLines)
        assertEquals(
            SystemStateCode.Train_CallShowUserCom,
            f.state,
            "门体其余照旧：:2433-2434 复用屏上菜单重等输入",
        )
        assertEquals(StepOutcome.CONTINUE, step.outcome)
    }

    /**
     * 门序 —— `:2426` 先查临时行位：末行**不是**临时行时，空行位即便为真也不开门
     * （空行判定在临时行门的内层，`Process.cs:2428`），落尾部回训练链。
     */
    @Test
    fun `末行非临时行时空行位不参与判定`() {
        val host = FakeHost(temporaryLine = false, lastEmpty = true)
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        val step = f.step()
        assertEquals(emptyList(), host.deletedCounts, ":2426 门不成立 ⇒ 内层空行半句不达")
        assertEquals(emptyList(), host.temporaryLines)
        assertEquals(StepOutcome.CONTINUE, step.outcome)
        assertEquals(
            SystemStateCode.Train_CallEventTrain,
            f.state,
            "Process.cs:2449-2455 尾部（needWaitToEventComEnd 初值 false，不等按键）",
        )
    }

    // ------------------------------------------------------------------
    // 分支②补面：Process.cs:2437-2448 的 CALLTRAINEND 缺失路径
    // ------------------------------------------------------------------

    /**
     * `:2439-2443/:2446/:2449` —— `CALLTRAINEND` 缺失（force=false）：队列**先清**
     * （`:2439`、`:2441-2442` 在 `:2443` 派发之前），派发不成立则不 return、落尾部；
     * 队列已清 ⇒ 下一次再进本 handler 也不会再派发。
     */
    @Test
    fun `CALLTRAINEND 缺失时队列仍清空并落尾部`() {
        val calls = FakeCalls().apply { ct = true }
        val f = BeginFlow(calls, FakeHost())
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        val step = f.step()
        assertFalse(calls.ct, "Process.cs:2439-2442 队列清空先于 :2443 的派发")
        assertEquals(
            emptyList(),
            calls.called,
            "Process.cs:2443 force=false —— CALLTRAINEND 缺失不是错误、不得抛",
        )
        assertEquals(StepOutcome.CONTINUE, step.outcome)
        assertEquals(
            SystemStateCode.Train_CallEventTrain,
            f.state,
            ":2446 不 return ⇒ 落 :2449-2455 尾部",
        )
    }

    // ------------------------------------------------------------------
    // 生产宿主接线：ConsoleBeginHost 三通道落在同一张真 sink 上
    // ------------------------------------------------------------------

    /** 空行判定与原版属性同形（`EmueraConsole.cs:526-537`）：行表空 = false、空白临时行 = true。 */
    @Test
    fun `ConsoleBeginHost 空行判定与原版属性同形`() {
        val console = LineBufferConsoleSink(clientWidth = 80)
        val host = ConsoleBeginHost(QueueInputSink(), console)

        assertFalse(host.lastLineIsEmpty(), "EmueraConsole.cs:530-533 行表为空 ⇒ false")
        assertFalse(host.lastLineIsTemporary())

        console.printLine("内容行")
        assertFalse(host.lastLineIsEmpty(), "普通行有内容 ⇒ false")
        assertFalse(host.lastLineIsTemporary())

        console.printTemporaryLine("　")
        assertTrue(host.lastLineIsTemporary(), "REUSELASTLINE 落临时行")
        assertTrue(host.lastLineIsEmpty(), "EmueraConsole.cs:535 整行 Trim 后为空（全角空格）⇒ true")
    }

    /** `deleteLine` / `printTemporaryLine` 通道逐字转交真 sink（按逻辑行删 + 临时行追加）。 */
    @Test
    fun `deleteLine 与 printTemporaryLine 通道转交真 sink`() {
        val console = LineBufferConsoleSink(clientWidth = 80)
        val host = ConsoleBeginHost(QueueInputSink(), console)
        console.printLine("一")
        console.printLine("二")
        console.printLine("三")

        host.deleteLine(2)
        assertEquals(
            listOf("一"),
            console.lines().map { console.textOf(it) },
            "EmueraConsole.cs:2999-3051 —— 尾部删 2 逻辑行",
        )

        host.printTemporaryLine("无效的值")
        val last = console.lines().last()
        assertTrue(last.isTemporary, "PrintTemporaryLine 落临时行（EmueraConsole.cs:3053-3056）")
        assertEquals("无效的值", console.textOf(last))
        assertFalse(host.lastLineIsEmpty(), "提示行本身非空")
        assertTrue(host.lastLineIsTemporary())
    }

    /** 真宿主全链：分支①经 ConsoleBeginHost 落到真 sink 的可见效果。 */
    @Test
    fun `真宿主全链 空临时行时删两行并画无效的值`() {
        val console = LineBufferConsoleSink(clientWidth = 80)
        val host = ConsoleBeginHost(QueueInputSink(), console)
        console.printLine("指令菜单")
        console.printTemporaryLine("　")
        val f = BeginFlow(FakeCalls(), host)
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        val step = f.step()
        assertEquals(SystemStateCode.Train_CallShowUserCom, f.state)
        assertEquals(StepOutcome.CONTINUE, step.outcome)
        val rows = console.lines()
        // deleteLine(2) 收走「指令菜单」与空临时行（C# :3007 的 while 弹到 2 条逻辑行或表空），
        // 再画「无效的值」临时行（:2431）——屏上净效果 = 原内容消失、一行提示待下一次输出收走。
        assertEquals(listOf("无效的值"), rows.map { console.textOf(it) })
        assertTrue(rows[0].isTemporary)
    }
}
