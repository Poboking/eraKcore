package com.eratw.port.core.proc

import com.eratw.port.core.host.InputRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.concurrent.thread

/**
 * [QueueInputSink] 单测：阻塞行为与**按等待形态**分派的取值语义。
 *
 * 断言的是原版的输入循环口径（注释里的行号是 `reference/emuera-cs` 的 C# 行）：
 * - 空行取默认值（`EmueraConsole.cs:1119-1124` / `:1190-1194`）；
 * - 数值解析失败丢弃并继续等（`:1125-1128`）；
 * - `oneInput` 只留首字符（`:1440-1443`）；
 * - 限时输入超时：整数有默认值才交付默认值，**无默认值时不推进**（`:1040-1082` + `:1119-1128`）；
 * - `readAnyKey` 认任何一次回车（`:876-895`）。
 *
 * 形态轴（EnterKey / AnyKey / Void / AnyValue / PrimitiveMouseKey）的逐条断言在
 * [InputKindModelingTest]，本文件只覆盖 VALUE 形态与队列/关闭骨架。
 *
 * 线程相关的用例都带 join 超时：**测试不许挂住**（红要红成断言，不要红成超时）。
 */
class QueueInputSinkTest {

    private fun intRequest(
        oneInput: Boolean = false,
        hasDef: Boolean = false,
        defInt: Long = 0L,
        timeLimit: Long = -1L,
    ): InputRequest = InputRequest(
        type = InputRequest.InputType.INT,
        oneInput = oneInput,
        hasDef = hasDef,
        defInt = defInt,
        timeLimit = timeLimit,
    )

    /** 等等待方进入等待态（`awaiting`/`awaitingAnyKey` 置起），带超时不挂测试。 */
    private fun awaitUntilAwaiting(sink: QueueInputSink) {
        val gate = System.nanoTime() + 2_000_000_000L
        while (!sink.isAwaiting && System.nanoTime() < gate) {
            Thread.sleep(5)
        }
        assertTrue(sink.isAwaiting, "等待方未进入等待态（interrupt 前必须已在 await 里）")
    }

    // ------------------------------------------------------------------
    // 排队（无请求方时提交不丢）
    // ------------------------------------------------------------------

    @Test
    fun `先提交后等待 按提交序逐条取出`() {
        val sink = QueueInputSink()
        assertEquals(0, sink.pendingCount)
        assertTrue(sink.submitInt(3))
        assertTrue(sink.submit("hello"))
        assertEquals(2, sink.pendingCount, "提交应入队")

        assertEquals(3L, sink.waitInput(intRequest()).intValue)
        assertEquals("hello", sink.waitInput(InputRequest(type = InputRequest.InputType.STR)).strValue)
        assertEquals(0, sink.pendingCount, "取完应清空")
    }

    // ------------------------------------------------------------------
    // 取值语义
    // ------------------------------------------------------------------

    @Test
    fun `空行取默认值 非空行不取默认值`() {
        val sink = QueueInputSink()
        sink.submit("")
        assertEquals(77L, sink.waitInput(intRequest(hasDef = true, defInt = 77)).intValue, "空行 → DefIntValue")

        sink.submit("")
        assertEquals("默认串", sink.waitInput(
            InputRequest(type = InputRequest.InputType.STR, hasDef = true, defStr = "默认串"),
        ).strValue, "空行 → DefStrValue")

        sink.submit("12")
        assertEquals(12L, sink.waitInput(intRequest(hasDef = true, defInt = 77)).intValue, "有内容时默认值不生效")
    }

    @Test
    fun `数值解析失败的那次提交被丢弃 下次提交照常生效`() {
        val sink = QueueInputSink()
        sink.submit("abc")
        sink.submit("7")
        // 第一次提交不是整数 → 原版 doInputToEmueraProgram 返回 false（控制台继续等），
        // 这里等价为「丢弃并取队列里的下一条」，绝不返回伪造的 0。
        assertEquals(7L, sink.waitInput(intRequest(hasDef = true, defInt = 99)).intValue)
        assertEquals(0, sink.pendingCount)
    }

    @Test
    fun `oneInput 只留首字符`() {
        val sink = QueueInputSink()
        sink.submit("42x")
        assertEquals(4L, sink.waitInput(intRequest(oneInput = true)).intValue, "单键输入取第 1 个字符")

        sink.submit("ab")
        assertEquals("a", sink.waitInput(
            InputRequest(type = InputRequest.InputType.STR, oneInput = true),
        ).strValue)

        sink.submit("事")
        assertEquals("事", sink.waitInput(
            InputRequest(type = InputRequest.InputType.STR, oneInput = true),
        ).strValue, "单字符不被截断成空串")
    }

    @Test
    fun `负数与全角空格外的前导空白可解析`() {
        val sink = QueueInputSink()
        sink.submit(" -5 ")
        assertEquals(-5L, sink.waitInput(intRequest()).intValue, "trim 后按 long 解析")
    }

    @Test
    fun `字符串输入不做整数解析`() {
        val sink = QueueInputSink()
        sink.submit("0032")
        assertEquals("0032", sink.waitInput(InputRequest(type = InputRequest.InputType.STR)).strValue)
    }

    // ------------------------------------------------------------------
    // 任意键
    // ------------------------------------------------------------------

    @Test
    fun `readAnyKey 消费任意一次提交 不阻塞在有排队时`() {
        val sink = QueueInputSink()
        sink.submit("随便什么")
        sink.readAnyKey() // 不阻塞即通过
        assertEquals(0, sink.pendingCount, "任意键把排队的那次提交消费掉")
    }

    @Test
    fun `readAnyKey 在无排队时阻塞 直到提交到达`() {
        val sink = QueueInputSink()
        val done = CountDownLatch(1)
        val t = thread(name = "anykey-waiter") {
            sink.readAnyKey(anyKey = true, stopMesSkip = false)
            done.countDown()
        }
        Thread.sleep(30)
        assertEquals(1L, done.count, "没有提交时 readAnyKey 必须仍在等待")
        sink.submit("")
        assertTrue(done.await(2, TimeUnit.SECONDS), "提交后 readAnyKey 必须返回")
        t.join(2_000)
        assertFalse(t.isAlive, "等待线程应已退出")
    }

    // ------------------------------------------------------------------
    // 跳过标志（PressEnterKey 的 keySkip，EmueraConsole.cs:1371-1373）
    // ------------------------------------------------------------------

    @Test
    fun `跳过提交的标志经 consumeMesSkip 一次性取出`() {
        val sink = QueueInputSink()
        sink.submit("", mesSkip = true)
        sink.readAnyKey()
        assertTrue(sink.consumeMesSkip(), "keySkip=true 应可见")
        assertFalse(sink.consumeMesSkip(), "读过即清")
    }

    @Test
    fun `普通提交把跳过标志置 false`() {
        val sink = QueueInputSink()
        sink.submit("", mesSkip = true)
        sink.readAnyKey()
        sink.consumeMesSkip()
        sink.submit("x")
        sink.waitInput(InputRequest(type = InputRequest.InputType.STR))
        assertFalse(sink.consumeMesSkip(), "普通回车不得继承上一次跳过")
    }

    @Test
    fun `数值解析失败被丢弃后 后续提交的标志独立`() {
        val sink = QueueInputSink()
        sink.submit("abc", mesSkip = true)
        sink.submit("5")
        assertEquals(5L, sink.waitInput(intRequest()).intValue, "非法数字丢弃后取到下一条")
        assertFalse(sink.consumeMesSkip(), "最后消费的是普通提交，不继承前一条的跳过标志")
    }

    // ------------------------------------------------------------------
    // 限时输入
    // ------------------------------------------------------------------

    /**
     * 整数等待**无默认值**时超时不推进（旧口径「超时返回 0 + isTimeout」是 TWAIT 被塌缩成
     * INT 时留下的，形态拆开后这条组合只剩「谁真的构造了限时无默认值整数请求」才可达）。
     *
     * 原版：`endTimer` 置 isTimeout 后调 `RunEmueraProgram("")`（EmueraConsole.cs:1043/1068），
     * 空文本在 `doInputToEmueraProgram` 里既没有默认值可吃（`HasDefValue=false`，:1119）
     * 又过不了 `long.TryParse("")`（:1125-1128）⇒ 返回 false ⇒ `RunEmueraProgram` 于 :1094
     * 不收尾 ⇒ 等待原地继续。后来靠真提交推进时 `isTimeout` 仍为真
     * （`stopTimer` :1035-1038 不清这一位，读点 VariableData.cs:1085）。
     *
     * 计时用注入的假时钟：截止点在开等之前就越过，全程不占墙钟。
     */
    @Test
    fun `限时整数无默认值 超时死等 后续提交仍带超时标记`() {
        // 假时钟：每读一次推进 1 秒 ⇒ 进入等待后的第一次截止判定就必然越过 deadline，
        // 全程不占墙钟（等待侧只在 await/awaitNanos 上真挂起，由 submit 唤醒）。
        val clock = AtomicLong(0L)
        val sink = QueueInputSink(timeSource = { clock.addAndGet(1_000_000_000L) })
        val ref = AtomicReference<Pair<Long, Boolean>?>()
        val t = thread(name = "int-no-def-deadwait") {
            ref.set(
                sink.waitInput(intRequest(timeLimit = 100))
                    .let { it.intValue to it.isTimeout },
            )
        }
        Thread.sleep(50)
        assertEquals(null, ref.get(), "截止点已过但无默认值 ⇒ 不推进，等待继续（EmueraConsole.cs:1119-1128）")
        sink.submitInt(6)
        t.join(2_000)
        assertFalse(t.isAlive, "真提交应当推进等待")
        assertEquals(6L to true, ref.get(), "推进时 isTimeout 仍是真（stopTimer 不清 :1035-1038）")
    }

    /**
     * 原版超时收尾是一笔空文本交付（`endTimer` → `RunEmueraProgram("")`，
     * EmueraConsole.cs:1068），而空文本在 `!IsRunningTimer`（计时已过，:374-381）下
     * 走默认值分支：整数取 `DefIntValue`（:1119-1124）、字符串取 `DefStrValue`（:1190-1193）。
     * TINPUT 系恒 `HasDefValue = true`（FunctionIdentifier.cs:1022），所以「TINPUT 超时
     * RESULT = 默认值」是原版读数，不是 0/""。
     */
    @Test
    fun `限时输入超时按默认值交付`() {
        val sink = QueueInputSink()
        val outInt = sink.waitInput(intRequest(hasDef = true, defInt = 77, timeLimit = 40))
        assertTrue(outInt.isTimeout)
        assertEquals(77L, outInt.intValue, "超时交付走默认值分支（EmueraConsole.cs:1068 → :1119-1124）")
        val outStr = sink.waitInput(
            InputRequest(type = InputRequest.InputType.STR, hasDef = true, defStr = "默认串", timeLimit = 40),
        )
        assertTrue(outStr.isTimeout)
        assertEquals("默认串", outStr.strValue, "字符串超时同理（EmueraConsole.cs:1190-1193）")
    }

    /**
     * 计时进行中 `IsRunningTimer` 为 true ⇒ 空提交**不**走默认值：整数侧 `long.TryParse("")`
     * 失败 → 丢弃本次提交继续等（EmueraConsole.cs:1125-1128）。
     */
    @Test
    fun `限时输入计时中的空整数提交被丢弃 继续等待`() {
        val sink = QueueInputSink()
        val ref = AtomicReference<Pair<Long, Boolean>?>()
        val t = thread(name = "timed-empty-int-waiter") {
            val out = sink.waitInput(intRequest(hasDef = true, defInt = 77, timeLimit = 2_000))
            ref.set(out.intValue to out.isTimeout)
        }
        Thread.sleep(30)
        sink.submit("")
        Thread.sleep(30)
        assertEquals(null, ref.get(), "计时中的空提交不得按默认值提前结束等待")
        sink.submitInt(5)
        t.join(2_000)
        assertFalse(t.isAlive, "等待线程应已退出")
        assertEquals(5L to false, ref.get(), "后续有效提交按正常值交付")
    }

    /**
     * 字符串输入没有解析环节：计时中的空提交直接按空串交付、等待结束
     * （EmueraConsole.cs:1190-1204 —— 默认值分支被 `IsRunningTimer` 挡住后 `str` 保持 ""）。
     */
    @Test
    fun `限时输入计时中的空字符串提交按空串交付`() {
        val sink = QueueInputSink()
        val ref = AtomicReference<Pair<String, Boolean>?>()
        val t = thread(name = "timed-empty-str-waiter") {
            val out = sink.waitInput(
                InputRequest(type = InputRequest.InputType.STR, hasDef = true, defStr = "默认串", timeLimit = 2_000),
            )
            ref.set(out.strValue to out.isTimeout)
        }
        Thread.sleep(30)
        sink.submit("")
        t.join(2_000)
        assertFalse(t.isAlive, "等待线程应已退出")
        assertEquals("" to false, ref.get(), "计时中的空串不走默认值（EmueraConsole.cs:1190-1204）")
    }

    @Test
    fun `限时输入在期限内到达时不报超时`() {
        val sink = QueueInputSink()
        val ref = AtomicReference<Pair<Long, Boolean>>()
        val t = thread(name = "timed-waiter") {
            val out = sink.waitInput(intRequest(timeLimit = 2_000))
            ref.set(out.intValue to out.isTimeout)
        }
        Thread.sleep(30)
        sink.submitInt(5)
        t.join(2_000)
        assertFalse(t.isAlive, "等待线程应已退出")
        assertEquals(5L to false, ref.get(), "期限内到达的提交按正常值返回")
    }

    @Test
    fun `限时输入超时带回默认值`() {
        val sink = QueueInputSink()
        val out = sink.waitInput(intRequest(hasDef = true, defInt = 77, timeLimit = 50))
        assertTrue(out.isTimeout, "无提交必须报超时")
        assertEquals(77L, out.intValue, "超时走 RunEmueraProgram(\"\") 的默认值分支（EmueraConsole.cs:1120）")

        val s = sink.waitInput(
            InputRequest(type = InputRequest.InputType.STR, hasDef = true, defStr = "默认串", timeLimit = 50),
        )
        assertTrue(s.isTimeout)
        assertEquals("默认串", s.strValue, "字符串超时同样取 DefStrValue（EmueraConsole.cs:1191）")
    }

    @Test
    fun `计时中空回车不取整数默认值 丢弃后继续等待`() {
        val sink = QueueInputSink()
        val ref = AtomicReference<Pair<Long, Boolean>>()
        val t = thread(name = "timed-empty-int") {
            val out = sink.waitInput(intRequest(hasDef = true, defInt = 77, timeLimit = 2_000))
            ref.set(out.intValue to out.isTimeout)
        }
        Thread.sleep(30)
        sink.submit("")
        Thread.sleep(50)
        assertNull(ref.get(), "计时中空行不走默认值；整数解析失败丢弃该次提交（EmueraConsole.cs:1111-1124）")
        sink.submitInt(9)
        t.join(2_000)
        assertFalse(t.isAlive, "等待线程应已退出")
        assertEquals(9L to false, ref.get(), "丢弃空行后取到下一条有效提交")
    }

    @Test
    fun `计时中空回车按空串提交字符串输入`() {
        val sink = QueueInputSink()
        val ref = AtomicReference<String>()
        val t = thread(name = "timed-empty-str") {
            val out = sink.waitInput(
                InputRequest(type = InputRequest.InputType.STR, hasDef = true, defStr = "默认串", timeLimit = 2_000),
            )
            ref.set(out.strValue)
        }
        Thread.sleep(30)
        sink.submit("")
        t.join(2_000)
        assertFalse(t.isAlive, "计时中的空串直接 InputString(\"\")，不取默认值（EmueraConsole.cs:1190-1194）")
        assertEquals("", ref.get())
    }

    // ------------------------------------------------------------------
    // interrupt（宿主销毁收敛：恢复中断位后按关闭异常离开等待，禁止持锁热转）
    // ------------------------------------------------------------------

    @Test
    fun `无限等待被打断时恢复中断位并按关闭异常收敛`() {
        val sink = QueueInputSink()
        val caught = AtomicReference<Throwable?>()
        val flagKept = AtomicReference(false)
        val done = CountDownLatch(1)
        val t = thread(name = "interrupt-waiter") {
            try {
                sink.waitInput(intRequest())
            } catch (e: Throwable) {
                flagKept.set(Thread.currentThread().isInterrupted)
                Thread.interrupted() // 清位：不带进测试线程的后续逻辑
                caught.set(e)
            }
            done.countDown()
        }
        awaitUntilAwaiting(sink)
        t.interrupt()
        // 旧实现（恢复中断位后 continue）：AQS 的 await 首行在持锁状态下即再抛，
        // 等待方持锁热转永不离开 —— 这里以「限时内收敛」钉死修复口径。
        assertTrue(done.await(2, TimeUnit.SECONDS), "interrupt 后等待方必须立即收敛，不得重进等待热转")
        assertTrue(caught.get() is InputSinkClosedException, "应抛 InputSinkClosedException，实为 ${caught.get()}")
        assertTrue(flagKept.get(), "中断位必须恢复（不吞中断，java 惯例）")
        t.join(2_000)
        assertFalse(t.isAlive)
    }

    @Test
    fun `限时等待被打断时立即收敛而非热转到截止`() {
        val sink = QueueInputSink()
        val caught = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val t = thread(name = "timed-interrupt-waiter") {
            try {
                // 600 秒截止：旧实现会持锁热转整段截止，close/submit 全被挡在锁外。
                sink.waitInput(intRequest(timeLimit = 600_000))
            } catch (e: Throwable) {
                Thread.interrupted()
                caught.set(e)
            }
            done.countDown()
        }
        awaitUntilAwaiting(sink)
        t.interrupt()
        assertTrue(done.await(2, TimeUnit.SECONDS), "限时等待被 interrupt 后必须有限收敛，不热转到截止")
        assertTrue(caught.get() is InputSinkClosedException, "实为 ${caught.get()}")
        t.join(2_000)
        assertFalse(t.isAlive)
    }

    @Test
    fun `GameHost 销毁序（先 interrupt 后 close）下 close 拿得到锁`() {
        val sink = QueueInputSink()
        val caught = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val t = thread(name = "destroy-order-waiter") {
            try {
                sink.readAnyKey(anyKey = true)
            } catch (e: Throwable) {
                Thread.interrupted()
                caught.set(e)
            }
            done.countDown()
        }
        awaitUntilAwaiting(sink)
        // 复刻 GameHost.close（GameHost.kt:363-368）：先打断 VM 线程，再走 shutdownForExit
        // → GameSession.close → inputSink.close。旧实现下 close 永远拿不到锁（热转方不撒锁），
        // readAnyKey 的等待方也必须按同一口径收敛。
        t.interrupt()
        val closeDone = CountDownLatch(1)
        thread(name = "closer", isDaemon = true) {
            sink.close()
            closeDone.countDown()
        }
        assertTrue(closeDone.await(2, TimeUnit.SECONDS), "interrupt 后的等待方必须放开锁，close 才能置 closed")
        assertTrue(done.await(2, TimeUnit.SECONDS), "readAnyKey 被打断后必须收敛")
        assertTrue(caught.get() is InputSinkClosedException, "实为 ${caught.get()}")
        t.join(2_000)
        assertFalse(t.isAlive)
        assertTrue(sink.isClosed)
    }

    // ------------------------------------------------------------------
    // 关闭
    // ------------------------------------------------------------------

    @Test
    fun `close 唤醒等待方并抛关闭异常`() {
        val sink = QueueInputSink()
        val caught = AtomicReference<Throwable?>()
        val t = thread(name = "closed-waiter") {
            try {
                sink.waitInput(intRequest())
            } catch (e: Throwable) {
                caught.set(e)
            }
        }
        Thread.sleep(30)
        sink.close()
        t.join(2_000)
        assertFalse(t.isAlive, "close 之后等待方必须被唤醒")
        assertTrue(caught.get() is InputSinkClosedException, "应抛 InputSinkClosedException，实为 ${caught.get()}")
        assertTrue(sink.isClosed)
    }

    @Test
    fun `关闭后的提交被丢弃且等待立即抛错`() {
        val sink = QueueInputSink()
        sink.close()
        assertFalse(sink.submit("late"), "关闭后提交返回 false（不抛：来自正在销毁的 UI）")
        assertFailsWith<InputSinkClosedException> { sink.waitInput(intRequest()) }
        assertFailsWith<InputSinkClosedException> { sink.readAnyKey() }
        sink.close() // 幂等
    }

    // ------------------------------------------------------------------
    // 等待快照（UI 选按钮可点性 / 输入框类型）
    // ------------------------------------------------------------------

    @Test
    fun `waitInput 期间 awaiting 可见 取值后清空`() {
        val sink = QueueInputSink()
        val request = intRequest(oneInput = true)
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val t = thread(name = "snapshot-waiter") {
            started.countDown()
            sink.waitInput(request)
            done.countDown()
        }
        assertTrue(started.await(2, TimeUnit.SECONDS), "等待线程应已启动")
        val deadline = System.nanoTime() + 2_000_000_000L
        while (sink.awaiting == null && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertEquals(request, sink.awaiting, "等待期间必须能读到当前请求")
        assertTrue(sink.isAwaiting)
        sink.submitInt(7)
        assertTrue(done.await(2, TimeUnit.SECONDS), "提交后等待方必须返回")
        t.join(2_000)
        assertFalse(t.isAlive)
        assertEquals(null, sink.awaiting, "取值后快照必须清空")
        assertFalse(sink.isAwaiting)
    }

    @Test
    fun `readAnyKey 期间 awaitingAnyKey 可见 返回后清空`() {
        val sink = QueueInputSink()
        val started = CountDownLatch(1)
        val done = CountDownLatch(1)
        val t = thread(name = "anykey-snapshot") {
            started.countDown()
            sink.readAnyKey(anyKey = true, stopMesSkip = false)
            done.countDown()
        }
        assertTrue(started.await(2, TimeUnit.SECONDS), "等待线程应已启动")
        val deadline = System.nanoTime() + 2_000_000_000L
        while (!sink.awaitingAnyKey && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        assertTrue(sink.awaitingAnyKey, "readAnyKey 等待期间标志必须可见")
        assertTrue(sink.isAwaiting)
        sink.submit("")
        assertTrue(done.await(2, TimeUnit.SECONDS), "提交后 readAnyKey 必须返回")
        t.join(2_000)
        assertFalse(sink.awaitingAnyKey, "返回后标志必须清空")
        assertFalse(sink.isAwaiting)
    }
}
