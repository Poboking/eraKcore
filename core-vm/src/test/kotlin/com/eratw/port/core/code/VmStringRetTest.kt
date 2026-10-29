package com.eratw.port.core.code

import com.eratw.port.core.expr.EvalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 合同 M3-A §3 A7：`_RETS` 字符串返回通道（`#FUNCTIONS`）在 VM 层的锁。
 *
 * 全部用 [CodeBuilder] 手工构造：STR_RET 是 `_CALL`/`_CALL_BY_NAME` 的 b 操作数 bit28
 * （0x10000000），[Assembler] 的文本格式表达不了位组合，只能直接 `emit` + 本地字面常量
 * （同 [VmJumpTest] 对 JUMP/CALLF/TRY 位的处理）。
 *
 * 语义依据（reference/emuera-cs，逐行）：
 * - `#FUNCTIONS` 返回值走 `MethodReturnValueS`（`FunctionMethodCreator.cs` 的
 *   `UserDefinedMethodTerm` / `Process.cs:514-540`），消费侧 `GetStrValue` 只认
 *   `SingleStrTerm`：方法帧内走了 `Return(long)` → `ReturnF(null)` 得 `""`
 *   （`ProcessState.cs:289-295` + `SuperUserDefinedMethodTerm.cs:33-38`）；
 * - 反向（int 调用位收到字符串返回）：`GetIntValue` 对非 long 得 `0L`
 *   （`SuperUserDefinedMethodTerm.cs:26-31`）；
 * - 掉尾（无 RETURN/RETURNF）方法帧：`Process.cs:1058-1065` 先清 RESULT 再
 *   `Return(0L)` → `ReturnF(null)` → 消费侧得 `""`。
 * 以上三条容错本测试逐条复刻（不抛、静默按原版口径），ADR-005"官方行为原样复刻"。
 *
 * ## 反向可证（改坏实现必红）
 * * STR_RET 位若被忽略：例 1 的 `_CALL` 走 long 通道，`setInt` 写调用方 R1，
 *   `_PRINT_STR` 读字符串槽得 `""`（输出空行而非 "hello"）。
 * * `_RETS` 若退化成 `_RET`：例 1 字符串丢失；例 3 的 int 容错（得 0）反成正确路径。
 * * `leaveFrameStr` 若写 null 而非 `""`：例 2 输出 `null` 字面量。
 * * 顶层 `_RETS` 若不直接结束 run：例 6 会继续执行后续打印，输出非空。
 * * `stringResult` 若不在 run 开始时重置：例 7 会读到上一次执行的残留。
 * * 尾跳 + 字符串位若各走一套帧逻辑：例 10 的 maxDepth 变 3 或返回值不达 main。
 */
class VmStringRetTest {

    private companion object {
        /** `_CALL` / `_CALL_BY_NAME` 的 b bit28：字符串返回标志（合同 M3-A §3 A7）。 */
        const val STR_RET_FLAG = 0x10000000

        /** `_CALL` / `_CALL_BY_NAME` 的 b bit30：尾跳标志（契约 R10/API 3）。 */
        const val JUMP_FLAG = 0x40000000

        /** `_CALL_BY_NAME` 的 b bit31：try 标志（契约 C5）。 */
        const val TRY_FLAG = Int.MIN_VALUE
    }

    /**
     * 记录型假宿主：输出、帧生命周期事件、按名解析留痕。
     * [resolveMethodIndex] 一旦被调到就抛 [AssertionError] —— 本文件只测函数家族。
     */
    private class StrRetHost : VmHost {
        override val evalContext: EvalContext? = null

        val output = StringBuilder()
        val events = ArrayList<String>()
        val functionNames = ArrayList<String>()

        /** 函数名 → 函数下标；查不到返回 null（"未命中"路径）。 */
        var functions: Map<String, Int> = emptyMap()

        var depth = 0
            private set
        var maxDepth = 0
            private set

        override fun print(text: String) {
            output.append(text)
        }

        override fun onFunctionEnter(funcIndex: Int) {
            events += "enter:$funcIndex"
            depth++
            if (depth > maxDepth) maxDepth = depth
        }

        override fun onFunctionExit(funcIndex: Int) {
            depth--
            events += "exit:$funcIndex"
        }

        override fun resolveMethodIndex(name: String): Int? =
            throw AssertionError("本文件不走 CALLF 家族（收到 $name）")

        override fun resolveFunctionIndex(name: String): Int? {
            functionNames += name
            return functions[name]
        }
    }

    private fun vmRun(program: Program, host: VmHost): Long =
        VirtualMachine(program, host).run()

    // ------------------------------------------------------------------
    // 帧返回：_RETS 把被调帧字符串写进调用方返回槽
    // ------------------------------------------------------------------

    @Test
    fun `_RETS 帧返回把字符串写进调用方返回槽`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._CALL, 1, STR_RET_FLAG, SlotAddr.local(1)) // strm，c=R1（字符串槽）
        b.emit(Opcode._PRINT_STR, SlotAddr.local(1), 1)
        b.emit(Opcode._HALT)

        val strm = b.declareFunction("strm", b.here(), 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("hello"))
        b.emit(Opcode._RETS, SlotAddr.local(0))

        b.setFunctionSlots(main, 2)
        b.setFunctionSlots(strm, 1)

        val host = StrRetHost()
        vmRun(b.build(0), host)
        assertEquals("hello\n", host.output.toString())
    }

    @Test
    fun `_RETS 无操作数按原版 ReturnF(null) 写空串`() {
        // spec: FunctionIdentifier.cs:3444-3478 RETURNF 无参 → ReturnF(null)；
        // 消费侧 SuperUserDefinedMethodTerm.cs:33-38 对非 SingleStrTerm 得 ""。
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._CALL, 1, STR_RET_FLAG, SlotAddr.local(1))
        b.emit(Opcode._PRINT_STR, SlotAddr.local(1), 1)
        b.emit(Opcode._HALT)

        val strm = b.declareFunction("strm", b.here(), 0)
        b.emit(Opcode._RETS, SlotAddr.NONE)

        b.setFunctionSlots(main, 2)
        b.setFunctionSlots(strm, 1)

        val host = StrRetHost()
        vmRun(b.build(0), host)
        assertEquals("\n", host.output.toString(), "无返回值必须落空串而不是 null 字面量")
    }

    // ------------------------------------------------------------------
    // 原版消费侧容错（ADR-005：不抛，按原版口径静默降级）
    // ------------------------------------------------------------------

    @Test
    fun `int 调用位收到 _RETS 按原版消费侧得 0`() {
        // spec: SuperUserDefinedMethodTerm.cs:26-31 —— GetIntValue 对 SingleStrTerm 得 0L。
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._CALL, 1, 0, SlotAddr.local(0)) // 不置 STR_RET → long 通道
        b.emit(Opcode._PRINT_INT, SlotAddr.local(0), 1)
        b.emit(Opcode._HALT)

        val strm = b.declareFunction("strm", b.here(), 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("x"))
        b.emit(Opcode._RETS, SlotAddr.local(0))

        b.setFunctionSlots(main, 2)
        b.setFunctionSlots(strm, 1)

        val host = StrRetHost()
        vmRun(b.build(0), host)
        assertEquals("0\n", host.output.toString())
    }

    @Test
    fun `str 调用位收到 _RET 按原版 ReturnF(null) 得空串`() {
        // spec: ProcessState.cs:289-295 —— 方法帧内 Return(long) → ReturnF(null)，
        // 消费侧得 ""（SuperUserDefinedMethodTerm.cs:33-38）。
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._CALL, 1, STR_RET_FLAG, SlotAddr.local(1)) // 要字符串
        b.emit(Opcode._PRINT_STR, SlotAddr.local(1), 1)
        b.emit(Opcode._HALT)

        val im = b.declareFunction("im", b.here(), 0)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(42L))
        b.emit(Opcode._RET, SlotAddr.local(0)) // 被调帧走了 long 通道

        b.setFunctionSlots(main, 2)
        b.setFunctionSlots(im, 1)

        val host = StrRetHost()
        vmRun(b.build(0), host)
        assertEquals("\n", host.output.toString())
    }

    @Test
    fun `掉尾的字符串方法经兜底 _RET 得空串`() {
        // spec: Process.cs:1058-1065 —— 掉尾方法帧 Return(0L) → ReturnF(null) → ""。
        // 编译器函数尾声发 `_RET NONE`（ErbProgramCompiler.kt:190），这里手工复刻。
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._CALL, 1, STR_RET_FLAG, SlotAddr.local(1))
        b.emit(Opcode._PRINT_STR, SlotAddr.local(1), 1)
        b.emit(Opcode._HALT)

        val faller = b.declareFunction("faller", b.here(), 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("ignored"))
        b.emit(Opcode._RET, SlotAddr.NONE) // = 编译器尾声兜底返回

        b.setFunctionSlots(main, 2)
        b.setFunctionSlots(faller, 1)

        val host = StrRetHost()
        vmRun(b.build(0), host)
        assertEquals("\n", host.output.toString())
    }

    // ------------------------------------------------------------------
    // 顶层 _RETS 与 stringResult
    // ------------------------------------------------------------------

    @Test
    fun `顶层 _RETS 结束 run 且字符串经 stringResult 取`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("top"))
        b.emit(Opcode._RETS, SlotAddr.local(0))
        b.emit(Opcode._PRINT_LITERAL, b.string("MUST-NOT-RUN"), 1)
        b.emit(Opcode._HALT)
        b.setFunctionSlots(main, 2)

        val host = StrRetHost()
        val vm = VirtualMachine(b.build(0), host)
        assertEquals(0L, vm.run(), "顶层 _RETS 的 long 结果保持 0（字符串走 stringResult）")
        assertEquals("top", vm.stringResult)
        assertEquals("", host.output.toString(), "顶层 _RETS 之后的指令不得执行")
    }

    @Test
    fun `无 _RETS 的 run 之后 stringResult 保持 null`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("x"))
        b.emit(Opcode._PRINT_STR, SlotAddr.local(0), 1)
        b.emit(Opcode._HALT)
        b.setFunctionSlots(main, 2)

        val vm = VirtualMachine(b.build(0), StrRetHost())
        vm.run()
        assertEquals(null, vm.stringResult, "每次 run 开始重置（Vm.kt run 初始化），不得读到残留")
    }

    // ------------------------------------------------------------------
    // 帧生命周期：enter/exit 配对、深度、尾跳
    // ------------------------------------------------------------------

    @Test
    fun `字符串返回的 enter exit 配对与深度不变`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._CALL, 1, STR_RET_FLAG, SlotAddr.NONE)
        b.emit(Opcode._HALT)

        val strm = b.declareFunction("strm", b.here(), 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("s"))
        b.emit(Opcode._RETS, SlotAddr.local(0))

        b.setFunctionSlots(main, 1)
        b.setFunctionSlots(strm, 1)

        val host = StrRetHost()
        vmRun(b.build(0), host)
        assertEquals(listOf("enter:0", "enter:1", "exit:1", "exit:0"), host.events)
        assertEquals(2, host.maxDepth)
        assertEquals(0, host.depth)
    }

    @Test
    fun `嵌套字符串方法经帧传参`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("x"))
        b.emit(Opcode._CALL, 1, 1 or STR_RET_FLAG, SlotAddr.local(2)) // outer(argc=1)
        b.emit(Opcode._PRINT_STR, SlotAddr.local(2), 1)
        b.emit(Opcode._HALT)

        val outer = b.declareFunction("outer", b.here(), 1)
        b.emit(Opcode._CALL, 2, 1 or STR_RET_FLAG, SlotAddr.local(1)) // inner(argc=1)
        b.emit(Opcode._RETS, SlotAddr.local(1))

        val inner = b.declareFunction("inner", b.here(), 1)
        b.emit(Opcode._RETS, SlotAddr.local(0)) // 原样返回自己的实参槽

        b.setFunctionSlots(main, 3)
        b.setFunctionSlots(outer, 2)
        b.setFunctionSlots(inner, 1)

        val host = StrRetHost()
        vmRun(b.build(0), host)
        assertEquals("x\n", host.output.toString(), "str 实参须经帧槽复制穿透两层字符串方法帧")
    }

    @Test
    fun `尾跳加字符串返回直达调用者的调用者`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._CALL, 1, STR_RET_FLAG, SlotAddr.local(0)) // mid，c=R0
        b.emit(Opcode._PRINT_STR, SlotAddr.local(0), 1)
        b.emit(Opcode._HALT)

        val mid = b.declareFunction("mid", b.here(), 0)
        b.emit(Opcode._CALL, 2, JUMP_FLAG or STR_RET_FLAG, SlotAddr.NONE) // target 尾跳
        b.emit(Opcode._PRINT_LITERAL, b.string("MID-AFTER"), 1)
        b.emit(Opcode._RET, SlotAddr.NONE)

        val target = b.declareFunction("target", b.here(), 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), b.string("tail"))
        b.emit(Opcode._RETS, SlotAddr.local(1))

        b.setFunctionSlots(main, 2)
        b.setFunctionSlots(mid, 2)
        b.setFunctionSlots(target, 2)

        val host = StrRetHost()
        vmRun(b.build(0), host)
        assertEquals("tail\n", host.output.toString(), "target 的 _RETS 字符串经 mid 帧的 retDst 直达 main")
        assertTrue("MID-AFTER" !in host.output.toString(), "尾跳点之后的 mid 指令不得执行")
        assertEquals(2, host.maxDepth, "尾跳替换 mid 而不是把 target 叠成第 3 层")
        assertEquals(0, host.depth)
    }

    // ------------------------------------------------------------------
    // _CALL_BY_NAME：字符串返回位与静态 _CALL 同一条建帧路径
    // ------------------------------------------------------------------

    @Test
    fun `_CALL_BY_NAME 加字符串返回位走同一通道`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("STRM"))
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(0), STR_RET_FLAG, SlotAddr.local(1))
        b.emit(Opcode._PRINT_STR, SlotAddr.local(1), 1)
        b.emit(Opcode._HALT)

        val strm = b.declareFunction("strm", b.here(), 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("byname"))
        b.emit(Opcode._RETS, SlotAddr.local(0))

        b.setFunctionSlots(main, 3)
        b.setFunctionSlots(strm, 1)

        val host = StrRetHost().apply { functions = mapOf("STRM" to 1) }
        vmRun(b.build(0), host)
        assertEquals("byname\n", host.output.toString())
        assertEquals(listOf("STRM"), host.functionNames)
    }

    // ------------------------------------------------------------------
    // 运行期守卫：返回槽（c）越界必须在调用点抛 CodeEE，不得静默写坏相邻帧
    //（Vm.kt checkRetDst；retDst=NONE 丢弃是合法路径，由上方 c=NONE 用例锁死不报错）
    // ------------------------------------------------------------------

    /** 单函数裸骨架：main 发一条 `_CALL`，sub 空返回。 */
    private fun guardProgram(cOperand: Int, bFlags: Int): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._CALL, 1, bFlags, cOperand)
        b.emit(Opcode._HALT)
        val sub = b.declareFunction("sub", b.here(), 0)
        b.emit(Opcode._RET, SlotAddr.NONE)
        b.setFunctionSlots(main, 1)
        b.setFunctionSlots(sub, 1)
        return b.build(0)
    }

    @Test
    fun `_CALL 返回槽越出调用方帧在调用点抛 CodeEE`() {
        val e = assertFailsWith<CodeEE> { VirtualMachine(guardProgram(SlotAddr.local(5), 0), StrRetHost()).run() }
        assertTrue("返回槽越界" in (e.message ?: ""), "文案须含槽诊断：${e.message}")
        assertTrue("R5" in (e.message ?: ""), "文案须含槽形式：${e.message}")
    }

    @Test
    fun `_CALL 返回槽指向未登记的全局槽在调用点抛 CodeEE`() {
        // CodeBuilder 未 noteGlobalSlot ⇒ globalSlots=0 ⇒ 全局槽数组长度 1，G1 越界。
        val e = assertFailsWith<CodeEE> { VirtualMachine(guardProgram(SlotAddr.global(1), 0), StrRetHost()).run() }
        assertTrue("全局槽" in (e.message ?: ""), "文案须指明全局槽越界：${e.message}")
    }

    @Test
    fun `_CALL_BY_NAME try 命中标志槽越界在调用点抛 CodeEE`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("SUB"))
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(0), TRY_FLAG, SlotAddr.local(3)) // main 只有 1 槽
        b.emit(Opcode._HALT)
        val sub = b.declareFunction("sub", b.here(), 0)
        b.emit(Opcode._RET, SlotAddr.NONE)
        b.setFunctionSlots(main, 1)
        b.setFunctionSlots(sub, 1)

        val host = StrRetHost().apply { functions = mapOf("SUB" to 1) }
        val e = assertFailsWith<CodeEE> { VirtualMachine(b.build(0), host).run() }
        assertTrue("返回槽越界" in (e.message ?: ""), "命中分支写标志 1 前必须守卫：${e.message}")
    }

    @Test
    fun `_CALL_BY_NAME try 未命中标志槽越界同样抛 CodeEE`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("NOPE"))
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(0), TRY_FLAG, SlotAddr.local(3)) // main 只有 1 槽
        b.emit(Opcode._HALT)
        val sub = b.declareFunction("sub", b.here(), 0)
        b.emit(Opcode._RET, SlotAddr.NONE)
        b.setFunctionSlots(main, 1)
        b.setFunctionSlots(sub, 1)

        val host = StrRetHost() // functions 空 ⇒ 未命中，try 走"未调用"标志 0
        val e = assertFailsWith<CodeEE> { VirtualMachine(b.build(0), host).run() }
        assertTrue("返回槽越界" in (e.message ?: ""), "未命中分支写标志 0 前必须守卫：${e.message}")
    }
}
