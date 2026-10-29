package com.eratw.port.core.proc

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.SemTestEnv
import com.eratw.port.core.sem.instr
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SpSwapCharaArgument
import com.eratw.port.core.stmt.VoidArgument
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 输入「等待形态」建模闸。
 *
 * 原版 `InputType`（`MinorShift.Emuera.Runtime/InputType.cs:3-14`，9 项）把两件不同的事写在
 * 同一个枚举里：这次等待**吃什么、什么时候推进**（形态），以及交付**写哪个槽**（值通道）。
 * 本移植拆成 [InputRequest.RequestForm] × [InputRequest.InputType] 两个正交轴，
 * 本文件逐条钉住按形态分派的结果（行号全部指 `reference/emuera-cs` 的 C# 原文）。
 *
 * 关键判据（`MinorShift.Emuera.GameView/EmueraConsole.cs`）：
 * - `PressEnterKey` 的入口早退：Void 直接 return（:1417-1420），EnterKey/AnyKey 顺带 `stopTimer`（:1421-1424）；
 * - `doInputToEmueraProgram` 的 switch（:1112-1288）：只有 IntValue/IntButton/StrValue/StrButton/
 *   AnyValue 五支会调 `process.InputInteger`/`InputString`，EnterKey/AnyKey/Void/PrimitiveMouseKey
 *   **没有 case** ⇒ 落到 :1278 的 `stopTimer` + :1287 的 `return true`（推进但不写值）；
 * - `endTimer`（:1040-1082）：置 `isTimeout` 后调 `RunEmueraProgram("")`，于是
 *   「超时推不推进」= 「空文本能不能被这次等待接受」：整数无默认值 ⇒ `long.TryParse("")` 失败
 *   ⇒ 返回 false ⇒ 不推进（:1119-1128 + :1094）；其余形态一律推进。
 *
 * 自包含：不依赖语料环境变量、不真等墙钟（超时用注入的假单调时钟推进）。
 */
class InputKindModelingTest {

    /**
     * 假单调时钟（纳秒）：每被读一次前进 [stepNanos]。
     * 取 step 大于任何被测时限，就能让「进入等待后的第一次截止判定」必然越过 deadline，
     * 从而零墙钟地驱动超时分支；`stepNanos = 0` 时退化成常量时钟（永不超时）。
     */
    private fun clockOf(stepNanos: Long): () -> Long {
        val now = AtomicLong(0L)
        return { now.addAndGet(stepNanos) }
    }

    private fun request(
        type: InputRequest.InputType = InputRequest.InputType.INT,
        form: InputRequest.RequestForm = InputRequest.RequestForm.VALUE,
        hasDef: Boolean = false,
        defInt: Long = 0L,
        defStr: String = "",
        timeLimit: Long = -1L,
    ) = InputRequest(
        type = type,
        form = form,
        hasDef = hasDef,
        defInt = defInt,
        defStr = defStr,
        timeLimit = timeLimit,
    )

    // ------------------------------------------------------------------
    // 形态轴本身
    // ------------------------------------------------------------------

    /** 原版 `NeedValue`（InputRequest.cs:31-41）：取值形态为真，EnterKey/AnyKey/Void 为假。 */
    @Test
    fun `NeedValue 只由形态决定 不由值通道决定`() {
        assertTrue(request(form = InputRequest.RequestForm.VALUE, type = InputRequest.InputType.INT).needValue)
        assertTrue(request(form = InputRequest.RequestForm.VALUE, type = InputRequest.InputType.STR).needValue)
        assertTrue(request(form = InputRequest.RequestForm.ANY_VALUE).needValue, "AnyValue 在 NeedValue 的枚举名单里（InputRequest.cs:35）")
        assertTrue(request(form = InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY).needValue, "PrimitiveMouseKey 同（InputRequest.cs:35）")
        assertFalse(request(form = InputRequest.RequestForm.ENTER_KEY).needValue)
        assertFalse(request(form = InputRequest.RequestForm.ANY_KEY).needValue)
        assertFalse(request(form = InputRequest.RequestForm.VOID).needValue, "Void 不在 NeedValue 名单里 ⇒ 跳过态被输入循环跑过（EmueraConsole.cs:1451-1455）")
    }

    /** 缺省形态 = VALUE：既有 INPUT/TINPUT/BINPUT 构造点与宿主桩的口径不变。 */
    @Test
    fun `缺省形态是 VALUE 且交付双通道都开`() {
        val req = request()
        assertEquals(InputRequest.RequestForm.VALUE, req.form)
        val out = InputOutcome()
        assertTrue(out.writesInt && out.writesStr, "缺省双开，使老宿主实现不必跟着改（InputSink.kt 的 writes* 说明）")
    }

    // ------------------------------------------------------------------
    // EnterKey / AnyKey：等一次按键，文本不取值
    // ------------------------------------------------------------------

    /**
     * EnterKey 形态认**任何**一条提交：空串、非数字都推进。
     * 旧实现塌缩成 INT 后，空串/非数字会被 `long.TryParse` 失败分支丢弃继续等
     * （EmueraConsole.cs:1125-1128），把「按一次键就走」错成「按了也不算」。
     */
    @Test
    fun `ENTER_KEY 空提交与非数字提交都推进 且不写任何槽`() {
        val sink = QueueInputSink()
        sink.submit("")
        val empty = sink.waitInput(request(form = InputRequest.RequestForm.ENTER_KEY))
        assertFalse(empty.isTimeout, "期限内推进 ⇒ isTimeout 保持假（setTimer :996 之后 endTimer 未走）")
        assertFalse(empty.writesInt, "switch 没有 EnterKey 的 case ⇒ 不调 InputInteger（EmueraConsole.cs:1276-1287）")
        assertFalse(empty.writesStr, "同理不调 InputString")
        assertEquals(0, sink.pendingCount, "这条提交被消费")

        sink.submit("随便写的字")
        val text = sink.waitInput(request(form = InputRequest.RequestForm.ENTER_KEY))
        assertFalse(text.writesInt && text.writesStr)
        assertEquals(0, sink.pendingCount)
    }

    /** AnyKey 与 EnterKey 在同一条队列模型里推进条件相同（差别只在物理按键映射，属绑定层）。 */
    @Test
    fun `ANY_KEY 与 ENTER_KEY 同路推进`() {
        val sink = QueueInputSink()
        sink.submit("12")
        val out = sink.waitInput(request(form = InputRequest.RequestForm.ANY_KEY))
        assertFalse(out.writesInt && out.writesStr)
        assertEquals(0, sink.pendingCount)
    }

    /**
     * EnterKey/AnyKey 超时**照常推进**（这正是 TWAIT 被塌缩成 INT 时丢掉的一条）：
     * `endTimer` → `RunEmueraProgram("")`（EmueraConsole.cs:1043/1068）⇒ 空文本对非取值形态
     * 也不是值 ⇒ 只推进。
     */
    @Test
    fun `ENTER_KEY 无提交时按超时推进 不写值`() {
        val sink = QueueInputSink(timeSource = clockOf(1_000_000_000L))
        val out = sink.waitInput(request(form = InputRequest.RequestForm.ENTER_KEY, timeLimit = 100))
        assertTrue(out.isTimeout, "endTimer 置 isTimeout（EmueraConsole.cs:1043）")
        assertFalse(out.writesInt && out.writesStr)
    }

    /** 跳过标志与交付槽位互不相干：EnterKey 推进时照样带出本次提交的 keySkip（:1373）。 */
    @Test
    fun `ENTER_KEY 推进后能取到本次提交的跳过标志`() {
        val sink = QueueInputSink()
        sink.submit("", mesSkip = true)
        sink.waitInput(request(form = InputRequest.RequestForm.ENTER_KEY))
        assertTrue(sink.consumeMesSkip(), "MesSkip = keySkip 在 PressEnterKey 入口就置（EmueraConsole.cs:1373）")
    }

    // ------------------------------------------------------------------
    // Void：只等时限，键盘提交既不推进也不消费
    // ------------------------------------------------------------------

    /**
     * Void（TWAIT 第 2 参非 0）：键盘提交在出队之前就早退（EmueraConsole.cs:1417-1420）
     * ⇒ 既不推进也**不进程序**；那次回车被丢掉（不是留给下一次等待），推进只可能来自超时。
     * 下一次等待要重新按一次回车 —— 原版文本留在输入框里，但事件本身已经用掉了。
     */
    @Test
    fun `VOID 丢弃提交 只由超时推进`() {
        val sink = QueueInputSink(timeSource = clockOf(1_000_000_000L))
        sink.submit("5")
        val out = sink.waitInput(request(form = InputRequest.RequestForm.VOID, timeLimit = 100))
        assertTrue(out.isTimeout)
        assertFalse(out.writesInt && out.writesStr)
        assertEquals(0, sink.pendingCount, "Void 下这次回车被丢弃，不留给下一次取值等待（:1417-1420 的早退）")
    }

    /** VOID 且没有时限 = 原版没有任何键盘路径能推进；只能被 close 打断（ADR-005 照抄）。 */
    @Test
    fun `VOID 无时限时键盘提交永不推进`() {
        val sink = QueueInputSink()
        val ref = AtomicReference<InputOutcome?>()
        val t = thread(name = "void-forever") {
            runCatching { sink.waitInput(request(form = InputRequest.RequestForm.VOID, timeLimit = -1L)) }
                .onSuccess { ref.set(it) }
        }
        Thread.sleep(30)
        sink.submit("7")
        sink.submit("", mesSkip = true)
        Thread.sleep(30)
        assertEquals(null, ref.get(), "键盘提交对 Void 无效（EmueraConsole.cs:1417-1420）")
        sink.close()
        t.join(2_000)
        assertFalse(t.isAlive, "close 必须能打断这种死等")
        assertEquals(0, sink.pendingCount, "两条提交都被丢弃（不进程序、也不排队）")
    }

    // ------------------------------------------------------------------
    // AnyValue（INPUTANY）：二选一交付
    // ------------------------------------------------------------------

    /** 数字提交 ⇒ 只写 RESULT；文本（含空串）⇒ 只写 RESULTS（EmueraConsole.cs:1256-1274）。 */
    @Test
    fun `ANY_VALUE 按可否解析二选一交付`() {
        val sink = QueueInputSink()
        sink.submit("42")
        val asInt = sink.waitInput(request(form = InputRequest.RequestForm.ANY_VALUE))
        assertEquals(42L, asInt.intValue)
        assertTrue(asInt.writesInt)
        assertFalse(asInt.writesStr, "InputInteger 那一路不碰 RESULTS（EmueraConsole.cs:1264-1267）")

        sink.submit("あ")
        val asStr = sink.waitInput(request(form = InputRequest.RequestForm.ANY_VALUE))
        assertEquals("あ", asStr.strValue)
        assertTrue(asStr.writesStr)
        assertFalse(asStr.writesInt, "InputString 那一路不碰 RESULT（EmueraConsole.cs:1271）")

        sink.submit("")
        val asEmpty = sink.waitInput(request(form = InputRequest.RequestForm.ANY_VALUE, hasDef = true, defStr = "默认串"))
        assertEquals("", asEmpty.strValue, "AnyValue 分支没有 HasDefValue 判断（EmueraConsole.cs:1256-1274）")
    }

    /** AnyValue 的超时 = 空文本交付 ⇒ 走字符串通道、写空串（:1068 + :1271）。 */
    @Test
    fun `ANY_VALUE 超时交付空串到 RESULTS`() {
        val sink = QueueInputSink(timeSource = clockOf(1_000_000_000L))
        val out = sink.waitInput(request(form = InputRequest.RequestForm.ANY_VALUE, timeLimit = 100))
        assertTrue(out.isTimeout)
        assertEquals("", out.strValue)
        assertFalse(out.writesInt)
    }

    // ------------------------------------------------------------------
    // PrimitiveMouseKey（INPUTMOUSEKEY）
    // ------------------------------------------------------------------

    /** 一条文本提交对 PrimitiveMouseKey 也不写值（switch 无 case，:1276-1287），只是推进。 */
    @Test
    fun `PRIMITIVE_MOUSE_KEY 提交不写值 超时交付事件类型 4`() {
        val sink = QueueInputSink()
        sink.submit("9")
        val byKey = sink.waitInput(request(form = InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY))
        assertFalse(byKey.writesInt, "真值交付属鼠标事件路径，本层不产（EmueraConsole.cs:1290-1346）")

        val timed = QueueInputSink(timeSource = clockOf(1_000_000_000L))
        val byTimeout = timed.waitInput(request(form = InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY, timeLimit = 100))
        assertTrue(byTimeout.isTimeout)
        assertEquals(4L, byTimeout.intValue, "endTimer → InputMouseKey(4,0,0,0,0,0) 的类型位（:1046 → Process.cs:393-401）")
    }

    // ------------------------------------------------------------------
    // 取值形态的提交侧规则：空串合法性（STR）与空提交的默认值门禁
    // ------------------------------------------------------------------

    /**
     * STR 的空串是**合法交付**：原版 `StrValue` 分支没有解析环节，空且无默认值时
     * `str` 保持 "" 直接 `InputString("")`（EmueraConsole.cs:1190-1198）—— 与 INT 的
     * 「空行 `long.TryParse("")` 失败 ⇒ 丢弃继续等」（:1125-1128）形成对照。
     * 空行取 `DefStrValue` 只在「有默认值且非计时中」时发生（:1191-1194）。
     */
    @Test
    fun `STR 空串合法交付 有默认值且非计时中才取默认串`() {
        val sink = QueueInputSink()
        sink.submit("")
        val plain = sink.waitInput(request(type = InputRequest.InputType.STR))
        assertEquals("", plain.strValue, "空串合法，不丢弃、不伪造（EmueraConsole.cs:1195-1203）")
        assertTrue(plain.writesStr)
        assertFalse(plain.writesInt, "StrValue 只写 RESULTS（Process.cs:432-436）")

        sink.submit("")
        val withDef = sink.waitInput(
            request(type = InputRequest.InputType.STR, hasDef = true, defStr = "兜底"),
        )
        assertEquals("兜底", withDef.strValue, "空行 + 有默认值 + 非计时中 ⇒ DefStrValue（:1191-1194）")
    }

    // ------------------------------------------------------------------
    // 取值形态的超时对照（整数死等 vs 字符串必推进）
    // ------------------------------------------------------------------

    /**
     * 同为「无默认值 + 限时 + 无提交」：字符串必然推进（空串交付，EmueraConsole.cs:1190-1204），
     * 整数则死等（:1119-1128）。这条不对称是形态轴的判据，也是原版官方行为（不修）。
     */
    @Test
    fun `VALUE 形态超时 字符串交付空串 整数无默认值不交付`() {
        val strSink = QueueInputSink(timeSource = clockOf(1_000_000_000L))
        val s = strSink.waitInput(
            request(type = InputRequest.InputType.STR, timeLimit = 100),
        )
        assertTrue(s.isTimeout)
        assertEquals("", s.strValue)
        assertFalse(s.writesInt)

        val intSink = QueueInputSink(timeSource = clockOf(1_000_000_000L))
        val ref = AtomicReference<InputOutcome?>()
        val t = thread(name = "value-int-timeout") { ref.set(intSink.waitInput(request(timeLimit = 100))) }
        Thread.sleep(30)
        assertEquals(null, ref.get(), "整数无默认值的限时等待超时不推进（EmueraConsole.cs:1094/1119-1128）")
        intSink.close()
        t.join(2_000)
        assertFalse(t.isAlive)
    }

    // ------------------------------------------------------------------
    // 语句层接线：TWAIT 的交付不写 RESULT，ISTIMEOUT 跨语句生命周期
    // ------------------------------------------------------------------

    /** TWAIT 的等待是 EnterKey/Void 形态 ⇒ 原版丢弃 `WaitInput` 返回值，RESULT 一个字节都不动。 */
    @Test
    fun `TWAIT 不写 RESULT`() {
        val env = SemTestEnv(InputOutcome(intValue = 99L, strValue = "不该出现"))
        env.store.writeInt("RESULT", longArrayOf(0), 7L)
        env.run(instr(FunctionCode.TWAIT, SpSwapCharaArgument(IntLiteral(10), IntLiteral(0))))
        assertEquals(7L, env.store.readInt("RESULT", longArrayOf(0)), "EnterKey 形态不交付值（FunctionIdentifier.cs:823 丢弃返回值）")
        assertEquals(0L, env.store.readStr("RESULTS", longArrayOf(0)).length.toLong(), "RESULTS 同样不动")
    }

    /**
     * `isTimeout` 只有两个写点：`setTimer()` 清零（EmueraConsole.cs:994-996，仅 `Timelimit > 0`
     * 的请求会经 `presetTimer` 挂上，:866-873）、`endTimer()` 置真（:1043）；
     * 读点 = 伪变量 `ISTIMEOUT` → `Console.IsTimeOut`（VariableData.cs:1075-1087）。
     * ⇒ WAIT / 非限时 INPUT 都**保留**旧值，只有下一次限时等待才改写。
     */
    @Test
    fun `ISTIMEOUT 在非限时等待期间保留 在下一次限时等待被改写`() {
        val env = SemTestEnv(InputOutcome(isTimeout = true))
        env.run(instr(FunctionCode.TWAIT, SpSwapCharaArgument(IntLiteral(10), IntLiteral(1))))
        assertTrue(env.ctx.variables.isTimeout, "限时等待超时 ⇒ 置真（EmueraConsole.cs:1043）")

        // WAIT 走 ReadAnyKey：原版给新建请求的 Timelimit 是默认 -1 ⇒ 不挂计时器（:876-895）
        env.run(instr(FunctionCode.WAIT, VoidArgument()))
        assertTrue(env.ctx.variables.isTimeout, "WAIT 不改写 isTimeout")

        // INPUT 无限时 ⇒ 同上
        env.run(instr(FunctionCode.INPUT, com.eratw.port.core.stmt.SpInputsArgument(null, null, null)))
        assertTrue(env.ctx.variables.isTimeout, "非限时取值等待不改写 isTimeout（:866-873）")

        // 下一次限时等待：武装即清零，靠提交推进 ⇒ 保持假
        env.input.outcome = InputOutcome(intValue = 3L)
        env.run(instr(FunctionCode.TWAIT, SpSwapCharaArgument(IntLiteral(10), IntLiteral(0))))
        assertFalse(env.ctx.variables.isTimeout, "限时等待开始即 setTimer 清零（EmueraConsole.cs:996）")
    }

    /** AWAIT 只走控制台 `Await`（:840-856），完全不碰输入层 ⇒ ISTIMEOUT 旧值原样留着。 */
    @Test
    fun `AWAIT 期间保留 ISTIMEOUT`() {
        val delays = mutableListOf<Long>()
        val env = SemTestEnv()
        env.ctx = ExecContext(
            variables = env.store,
            eval = com.eratw.port.core.sem.TestEvalContext(env.store),
            console = env.console,
            input = env.input,
            timer = object : com.eratw.port.core.host.TimerSink {
                override fun delay(millis: Long) { delays.add(millis) }
            },
        )
        env.ctx.variables.isTimeout = true
        env.run(instr(FunctionCode.AWAIT, com.eratw.port.core.sem.intArg(0)))
        assertEquals(listOf(0L), delays)
        assertTrue(env.ctx.variables.isTimeout, "Await 里没有任何 isTimeout 写点（EmueraConsole.cs:840-856）")
    }
}
