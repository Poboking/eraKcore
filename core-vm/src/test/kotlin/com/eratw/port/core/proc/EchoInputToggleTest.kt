package com.eratw.port.core.proc

import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * 键入回显（ECHO-INPUT）引擎档位的定向钉子（2026-11-06 引擎侧收口版）。
 *
 * 玩家侧病灶：「选择口上剧本」页点一颗剧本按钮后，最新一行冒出按钮的 value 编号，
 * 整页被顶上去抖一下。那行不是剧本 PRINT 的，是 `EmueraConsole.cs:1280-1282` 的
 * `Print(str)+PrintFlush` 回显（`ERB/fromEN/HTML_TALENTS/KOJO_DESC.ERB:19-42` 的
 * `ASK_CHOICES_TITLE` 用 `INPUT` 收 0..N 整数，有效项直接 `RETURN RESULT` 不清行）。
 *
 * 与动态地图同源且更深一层：`b00c9dc0a` 的 `holdFrame` 定的是**回显前**帧，动态地图随后
 * 重印所以干净；口上剧本不清行，下一个发布边界把含数字行的活表整帧上屏 ⇒ 一次帧差。
 * 两处都是同一个解冻窗口，那笔修复只覆盖了会重印的那一半。
 *
 * 本类钉的是**引擎侧**实现，不是显示层过滤：`QueueInputSink` 按 `EchoInputMode` 在交付点
 * 决定这次输入是否产生回显行；抑制档只由控制台行账本记进 LINECOUNT，并在随后的
 * `deleteLine` 折算掉。原版那行是脚本 `CLEARLINE 1` 与引擎级 `deleteLine(1)/(2)`
 * （`Process.cs:2194/:2430/:2498/:2518/:2624/:2629/:2648`）的删除对象，4.981 语料
 * 276 个文件含 `CLEARLINE`，`KOJO_DESC.ERB:40` 的 `CASEELSE` 就是紧跟 `INPUT` 删它；
 * 脚本级 `CLEARLINE` 直调 `ctx.console.deleteLine`、不经 `ConsoleBeginHost`，
 * 所以少打一行必须连带把删除账本折算，否则会误删真菜单行（2026-11-04 ABLUP 案）。
 *
 * 判据分四组：① 两档行表与 LINECOUNT 对拍；② 帧差；③ CLEARLINE 三种语料形态
 * （紧贴 INPUT 的字面量 / `LINECOUNT` 派生的动态地图清屏 / 中间有打印的页面重画环）；
 * ④ 两档输入取值与 WAIT 家族路径。
 */
class EchoInputToggleTest {

    private val MODES = listOf(EchoInputMode.REPLICA, EchoInputMode.SUPPRESSED)

    /** 动态地图的 TINPUTS 收的是字符串，走同一条交付链但不会被整数解析拒掉。 */
    private fun strRequest(): InputRequest = InputRequest(
        type = InputRequest.InputType.STR,
        form = InputRequest.RequestForm.VALUE,
    )

    /** 与 GameSession 同款装配：档位 + 两条互斥回调一一对应控制台的两个引擎入口。 */
    private class Rig(
        mode: EchoInputMode,
        frameGateEnabled: Boolean = true,
    ) {
        val console = LineBufferConsoleSink(clientWidth = 40, frameGateEnabled = frameGateEnabled)
        val sink = QueueInputSink()

        init {
            sink.echoInputMode = mode
            sink.onEchoInput = console::printEchoLine
            sink.onEchoSuppressed = console::noteSuppressedEchoLine
        }

        private val intReq = InputRequest(
            type = InputRequest.InputType.INT,
            form = InputRequest.RequestForm.VALUE,
        )

        fun deliver(text: String, request: InputRequest = intReq): InputOutcome {
            sink.submit(text)
            return sink.waitInput(request)
        }
    }

    private fun live(s: LineBufferConsoleSink): List<String> = s.lines().map { s.textOf(it) }

    private fun frame(s: LineBufferConsoleSink): List<String> = s.frameSnapshot().map { s.textOf(it) }

    private fun menu(s: LineBufferConsoleSink) {
        s.printLine("[0] 剧本甲")
        s.printLine("[999] 返回")
    }

    // ------------------------------------------------------------------
    // ① 两档行表与 LINECOUNT
    // ------------------------------------------------------------------

    @Test
    fun `抑制档交付点不产生显示行 但 LINECOUNT 与原版逐字一致`() {
        val off = Rig(EchoInputMode.SUPPRESSED)
        off.deliver("7")
        assertEquals(emptyList<String>(), live(off.console), "引擎不打印这行，屏上根本不存在回显显示行")
        assertEquals(emptyList<String>(), frame(off.console), "冻结段取帧也没有这行")
        assertEquals(1L, off.console.lineCount, "行账本把它计进 LINECOUNT，与原版同读数")

        val on = Rig(EchoInputMode.REPLICA)
        on.deliver("7")
        assertEquals(listOf("7"), live(on.console), "原版档照常落成普通显示行")
        assertEquals(1L, on.console.lineCount)
    }

    @Test
    fun `原版档先关闸再打印 回显不进已发布帧`() {
        val rig = Rig(EchoInputMode.REPLICA)
        rig.console.printLine("剧本选择页")
        rig.console.publishFrame()
        rig.deliver("7")
        assertEquals(listOf("剧本选择页"), frame(rig.console), "回显不产生独立发布帧：发布边界仍产出剧本整帧")
        assertEquals(listOf("剧本选择页", "7"), live(rig.console))
    }

    // ------------------------------------------------------------------
    // ② 帧差：点剧本不再抖
    // ------------------------------------------------------------------

    @Test
    fun `抑制档发布边界前后帧逐字相同 点剧本不再抖`() {
        val rig = Rig(EchoInputMode.SUPPRESSED)
        menu(rig.console)
        rig.console.publishFrame()
        val before = frame(rig.console)

        rig.deliver("7")
        // 剧本选中有效项：不 CLEARLINE、直接 RETURN，随后到达下一个发布边界。
        rig.console.publishFrame()

        assertEquals(before, frame(rig.console), "冻结段帧与发布帧逐字相同 ⇒ 没有帧差、没有抖动")
        assertEquals(before, live(rig.console), "活表也没有多出一行")
    }

    @Test
    fun `抑制档在帧闸逃生阀下同样没有回显行`() {
        val rig = Rig(EchoInputMode.SUPPRESSED, frameGateEnabled = false)
        rig.deliver("7")
        assertEquals(emptyList<String>(), frame(rig.console))
        assertEquals(emptyList<String>(), live(rig.console))
        assertEquals(1L, rig.console.lineCount)
    }

    // ------------------------------------------------------------------
    // ③ CLEARLINE 三种语料形态：两档对拍
    // ------------------------------------------------------------------

    @Test
    fun `两档对拍 字面量 CLEARLINE 1 都只删回显 菜单行不误删`() {
        for (mode in MODES) {
            val rig = Rig(mode)
            menu(rig.console)
            rig.deliver("1000") // KOJO_DESC.ERB:40 的 CASEELSE：无效值后紧跟 CLEARLINE 1
            rig.console.deleteLine(1)

            assertEquals(
                listOf("[0] 剧本甲", "[999] 返回"),
                live(rig.console),
                "$mode：少打回显又不折算会把 [999] 返回 删掉（2026-11-04 ABLUP 案）",
            )
            assertEquals(2L, rig.console.lineCount, "$mode：LINECOUNT 与开态收敛到同一读数")
            assertEquals(listOf("[0] 剧本甲", "[999] 返回"), frame(rig.console))
        }
    }

    @Test
    fun `两档对拍 LINECOUNT 派生的清屏删得一样干净 动态地图形态`() {
        // コマンド関連/COMF/日常系/COMF400 移動.ERB:183 的 TINPUTS 超时交付 "UPDATE"，
        // :193 紧接 CLEARLINE LINECOUNT - LINESTART（中间没有打印）。
        for (mode in MODES) {
            val rig = Rig(mode)
            rig.console.printLine("地图行1")
            rig.console.printLine("地图行2")
            rig.deliver("UPDATE", strRequest())
            val lineStart = 0L
            rig.console.deleteLine((rig.console.lineCount - lineStart).toInt())

            assertEquals(emptyList<String>(), live(rig.console), "$mode：不能残留地图行")
            assertEquals(0L, rig.console.lineCount, "$mode：LINECOUNT 也归零")
        }
    }

    @Test
    fun `打印跨过账本后 页面重画环不再被折算`() {
        // 「记 L = LINECOUNT → 打印一屏 → CLEARLINE LINECOUNT - L」：
        // 这一族的删除量只数新打印的行，旧回显账已经沉进历史，不能再扣一次。
        val off = Rig(EchoInputMode.SUPPRESSED)
        off.console.printLine("base")
        off.deliver("7")
        val l = off.console.lineCount
        off.console.printLine("new1")
        off.console.printLine("new2")
        off.console.deleteLine((off.console.lineCount - l).toInt())
        assertEquals(listOf("base"), live(off.console), "折算误伤会残留 new2")

        val on = Rig(EchoInputMode.REPLICA)
        on.console.printLine("base")
        on.deliver("7")
        val lOn = on.console.lineCount
        on.console.printLine("new1")
        on.console.printLine("new2")
        on.console.deleteLine((on.console.lineCount - lOn).toInt())
        assertEquals(listOf("base", "7"), live(on.console), "开态仍是原版逐字行为")
    }

    @Test
    fun `抑制档连续交付只累计账本不累计显示行`() {
        val rig = Rig(EchoInputMode.SUPPRESSED)
        rig.deliver("7")
        rig.deliver("8")
        assertEquals(emptyList<String>(), live(rig.console))
        assertEquals(2L, rig.console.lineCount, "每轮输入各记一笔，与开态两行回显的 LINECOUNT 一致")
        // TODO(verify)：账本只标最近一轮，更早的虚拟行不占显示行；长会话下
        // CLEARLINE LINECOUNT 会按原版行数多扣，需真机长局复核。
    }

    // ------------------------------------------------------------------
    // ④ 输入取值与 WAIT 家族
    // ------------------------------------------------------------------

    @Test
    fun `两档 RESULT 取值与超时判读不受档位影响`() {
        val off = Rig(EchoInputMode.SUPPRESSED).deliver("7")
        val on = Rig(EchoInputMode.REPLICA).deliver("7")
        assertEquals(7L, off.intValue)
        assertEquals(7L, on.intValue)
        assertFalse(off.isTimeout)
        assertFalse(on.isTimeout)
    }

    @Test
    fun `抑制档 WAIT 家族交付也只记账`() {
        val rig = Rig(EchoInputMode.SUPPRESSED)
        rig.sink.submit("继续")
        rig.sink.readAnyKey(false, false)
        assertEquals(emptyList<String>(), live(rig.console))
        assertEquals(1L, rig.console.lineCount)
    }

    @Test
    fun `原版空串早退不回显 抑制档也不凭空记账`() {
        val rig = Rig(EchoInputMode.SUPPRESSED)
        rig.sink.submit("")
        rig.sink.readAnyKey(false, false)
        assertEquals(emptyList<String>(), live(rig.console))
        assertEquals(0L, rig.console.lineCount, "Print(\"\") 早退（EmueraConsole.cs:3167-3172）")
    }
}
