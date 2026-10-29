package com.eratw.port.core.proc

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 帧发布闸的桥接面（红 G / `FRAMEGATE-BRIDGE`）。
 *
 * 两条必须同时成立的转发：
 * 1. sem 层拿到的 `ctx.console` 是 [ProcessRuntime] 内部的 `ConsoleBridge`，不是宿主的
 *    [LineBufferConsoleSink]。桥接器若只转发 `setRedraw` 而漏了 `publishFrame`/`holdFrame`，
 *    剧本一次 `REDRAW 0` 关闸之后 sem 的让出边界就再也打不到真 sink —— `revision` 不再自增，
 *    UI 永远显示旧帧（第十程真机实证：读档后 `revision` 恒 2）。
 * 2. 开局/读档状态机 [ConsoleBeginHost] 的四个 `await*` 绕过 sem 直接等输入队列，
 *    必须自己做「等前发布 → 返回后冻结」，否则闸关着时开局等待同样不冲刷。
 *
 * 两处缺一不可：只修 1 时商店等待仍会冻住（看板第十程补记）。
 */
class FrameGateBridgeTest {

    /** 最小程序：main 只有一条 `_HALT`，本测试不执行它，只用 runtime 的桥接器。 */
    private fun emptyProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 0)
        b.emit(Opcode._HALT, 0, 0, 0, 0)
        return b.build(0)
    }

    private fun runtimeOver(sink: LineBufferConsoleSink): ProcessRuntime {
        val program = emptyProgram()
        val meta = FuncMeta(
            index = 0,
            key = "MAIN",
            name = "main",
            isMethod = false,
            defIndex = 0,
        )
        return ProcessRuntime(
            program,
            VariableStore(),
            FunctionTable(listOf(meta)),
            VmConfig(),
            consoleSink = sink,
        )
    }

    @Test
    fun bridgeForwardsPublishAndHoldToHostSink() {
        val sink = LineBufferConsoleSink(clientWidth = 40, frameGateEnabled = true)
        val rt = runtimeOver(sink)
        val bridge = rt.execContext.console

        bridge.holdFrame()
        bridge.print("abc", lineEnd = true)
        bridge.newLine()
        val frozen = sink.revision
        assertEquals(0L, frozen, "闸关时桥接器写入只记脏，不得让宿主 sink 发布")

        bridge.publishFrame()
        assertEquals(1L, sink.revision, "publishFrame 必须穿过桥接器到达宿主 sink 并发布一帧")
    }

    /**
     * 选项快照的冻结入口也必须穿过桥接器。
     *
     * 2026-10-25 真机标题页实测：`pending=INT` 但 `chips` 恒空 —— sem 的 `ctx.console`
     * 是 ConsoleBridge，`noteInputWait` 走了接口缺省 no-op，真正的
     * [LineBufferConsoleSink.noteInputWait] 从未被调用。
     */
    @Test
    fun bridgeForwardsInputWaitToHostSink() {
        val sink = LineBufferConsoleSink(clientWidth = 40)
        sink.printLine("[0] 开始游戏")
        val rt = runtimeOver(sink)

        rt.execContext.console.noteInputWait(InputRequest(type = InputRequest.InputType.INT))

        assertEquals(
            listOf<Any>(0L),
            sink.currentOptions().map { it.value },
            "桥接器漏转发 noteInputWait ⇒ 真机右侧栏整页漏识别",
        )
    }

    @Test
    fun beginHostPublishesBeforeSystemWait() {
        val sink = LineBufferConsoleSink(clientWidth = 40, frameGateEnabled = true)
        val queue = QueueInputSink()
        val host = ConsoleBeginHost(queue, sink)

        sink.holdFrame()
        sink.print("标题/存档页文字", lineEnd = true)
        sink.newLine()
        assertEquals(0L, sink.revision, "闸关时开局输出的中间态不发布")

        // 先提交后等待：QueueInputSink 无请求方时提交不丢，等待立即消费。
        queue.submitInt(0)
        assertEquals(0L, host.awaitOpeningInput(), "取到排队提交的整数")

        assertEquals(1L, sink.revision, "系统等待前必须发布：此前冻结的标题/存档页整帧上屏")
    }
}
