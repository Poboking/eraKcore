package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 发布边界「必然产出一致快照」的回归钉子（A2 · 2026-11-08 深化）。
 *
 * 帧闸的发布边界（[LineBufferConsoleSink.publishFrame] / [LineBufferConsoleSink.holdFrame] /
 * 写侧发布记账）维护**一份**当前发布帧：[LineBufferConsoleSink.frameSnapshot] 恒返回它，
 * 闸开直通逐笔同步到活表、闸关冻结段停在发布时刻那一版、解冻整份替换。本类断言的是这份
 * 不变量 —— 任意一个发布边界之后取帧，拿到的都是该边界定下的整帧，而不是循环体中途态 ——
 * 而不是某条「补抓兜底」的行为。原版没有「快照点 ≠ 发布点」的窗口：Await 进入即
 * RefreshStrings(force_Paint: true) 在脚本线程内同步画完（EmueraConsole.cs:840-856），
 * RefreshStrings 的 lastDrawnLineNo 早退与 16ms 节流见 :1758、写回见 :1942-1949。
 *
 * 真机病灶（2026-11-05「打开动态地图后整屏不断交替闪动」、2026-11-06 超时回显每 tick
 * 抖动）都落在「发布边界可不产出快照、读侧回落活表」这一处；本类用开/关帧闸逐帧一致、
 * 冻结段中途态不漏、回显真实路径三种形态钉死修复后的口径。
 */
class FrameGateFrozenSnapshotTest {

    private fun texts(s: LineBufferConsoleSink): List<String> = s.frameSnapshot().map { s.textOf(it) }

    /** 发布边界之后取帧，必然是发布时刻的整帧（哪怕该边界无脏、不推进 revision）。 */
    @Test
    fun `publish boundary always yields the frame captured at publish time`() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.printLine("地图前日志")
        val rev = s.revision

        // 闸开打印之后立刻到让出边界：无脏，发布帧仍应是这一刻的整表。
        s.publishFrame()
        s.holdFrame()

        // 冻结段是地图循环体的 CLEARLINE：只置脏，不得改变已发布的帧。
        s.deleteLine(1)

        assertEquals(rev, s.revision, "无脏的发布边界不重复 bump revision")
        assertEquals(listOf("地图前日志"), texts(s), "发布边界之后取帧恒为该边界定下的整帧")
    }

    /** 中途直接关闸（不经 publishFrame）同样给出一致快照，覆盖 noteInputWait 形态。 */
    @Test
    fun `hold without publish still yields a consistent snapshot`() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.printLine("地图前日志")

        s.holdFrame()
        s.deleteLine(1)

        assertEquals(listOf("地图前日志"), texts(s), "关闸之后取帧仍是发布帧，冻结段中途态不漏")
    }

    /** 冻结段攒出的完整帧仍按原子口径发布，且只 bump 一次 revision。 */
    @Test
    fun `frozen frame still published atomically after boundary capture`() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.printLine("地图前日志")
        s.publishFrame()
        s.holdFrame()
        s.deleteLine(1)
        s.printLine("地图页")
        s.publishFrame()

        assertEquals(listOf("地图页"), texts(s), "解冻发布的是冻结段完整帧")
    }

    /** 无脏的发布边界重复调用不得推进 revision（消费侧不该多画空帧）。 */
    @Test
    fun `repeat publish without writes keeps revision`() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.printLine("A")
        s.holdFrame()
        s.publishFrame()
        val rev = s.revision
        s.publishFrame()
        s.publishFrame()
        assertEquals(rev, s.revision)
    }

    /** 闸开直通写行后，发布帧逐笔跟上活表（流式打印语义不变）。 */
    @Test
    fun `gate open keeps the published frame in sync with live lines`() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.printLine("A")
        s.publishFrame()
        s.printLine("B 直通")
        assertEquals(listOf("A", "B 直通"), texts(s))
    }

    /**
     * 限时等待超时回显（TINPUTS ...,"UPDATE",0,""）不得进入 UI 可见帧。
     *
     * 走**真实上游路径** [LineBufferConsoleSink.printEchoLine]（REPLICA 档的 onEchoInput
     * 装配点），而不是手工 holdFrame + printLine —— 撤销该装配的发布相位契约
     * （回显直通打印）时本用例必红，这是 A2 的防回归钉。
     *
     * 回显由 QueueInputSink.onEchoInput 在 waitInput 返回之前触发，而
     * StatementExecutor.waitInput 先 publishFrame、返回后才 holdFrame ⇒ 交付窗口闸是开的。
     * 原版没有这个窗口：Print(str) 只进打印缓冲，PrintFlush(force:false)
     * （EmueraConsole.cs:1280-1282）之后脚本继续同步执行；超时收尾要等
     * RunEmueraProgram("") 跑完才 forced paint（:1068/:1080），Running/Initializing
     * 期间另有 16ms 节流（:1758），真上屏时 CLEARLINE 早已跑完。
     */
    @Test
    fun `timeout input echo stays out of the published frame`() {
        val s = LineBufferConsoleSink(clientWidth = 40)
        s.printLine("地图页")
        // 上一轮等待的收尾：先整帧发布、再关闸。
        s.publishFrame()
        s.holdFrame()

        // 冻结段：剧本 CLEARLINE 后重印地图。
        s.deleteLine(1)
        s.printLine("地图页2")

        // 本轮等待：发布边界整帧上屏。
        s.publishFrame()

        // 交付窗口（onEchoInput 时机）：经真实回显入口，回显不得改变已发布快照。
        s.printEchoLine("UPDATE")
        assertEquals(listOf("地图页2"), texts(s), "超时回显不得进入 UI 可见帧")

        // 剧本随后 CLEARLINE（地图行 + 回显行一起删）后重印，下一次发布边界只出新整帧。
        s.deleteLine(2)
        s.printLine("地图页3")
        s.publishFrame()
        assertEquals(listOf("地图页3"), texts(s), "重印后的整帧覆盖冻结段")
    }

    /** 逃生阀（frameGateEnabled=false）：发布帧恒等于活表（写侧逐笔同步）。 */
    @Test
    fun `frameGate disabled keeps the published frame equal to live lines`() {
        val s = LineBufferConsoleSink(clientWidth = 40, frameGateEnabled = false)
        s.printLine("A")
        s.publishFrame()
        s.holdFrame()
        s.deleteLine(1)
        s.printLine("B")
        assertEquals(listOf("B"), texts(s), "逃生阀下 frameSnapshot 必须恒等于活表")
    }

    /**
     * 报告 §7-2 原口径：同一输入序列在开/关帧闸下，逐帧（每个发布边界）快照必须一致。
     *
     * 帧闸只决定「中间态要不要上屏」，不改变发布边界产出的内容；两闸在每个边界上
     * 给出同一份整帧。比对点是每个发布边界之后的 [LineBufferConsoleSink.frameSnapshot]。
     */
    @Test
    fun `same input sequence yields identical frames with gate on and off`() {
        fun drive(gated: Boolean): List<List<String>> {
            val s = LineBufferConsoleSink(clientWidth = 40, frameGateEnabled = gated)
            val frames = ArrayList<List<String>>()
            fun boundary() { s.publishFrame(); s.holdFrame() }
            fun capture() { frames.add(texts(s)) }

            s.printLine("甲"); capture()
            boundary()
            s.printLine("乙"); boundary(); capture()
            s.deleteLine(1); boundary(); capture()
            s.printLine("丙"); boundary(); capture()
            return frames
        }
        assertEquals(drive(gated = false), drive(gated = true), "逐帧比对：两闸在每个发布边界给出同一份整帧")
    }
}
