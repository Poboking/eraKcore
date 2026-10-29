package com.eratw.port.core.code

import com.eratw.port.core.expr.EvalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 契约 R10（JUMP 尾跳）在 VM 层的锁。
 *
 * 全部用 [CodeBuilder] 手工构造：JUMP / CALLF / TRY 都是 b 操作数的高位标志，
 * [Assembler] 表达不了位组合，只能直接 `emit` + 本地字面常量。
 *
 * ## 反向可证（改坏实现必红）
 * * 尾跳若退化成普通调用：例 1 会多执行 mid 的后续打印、返回值不再写回 main；
 *   例 2 的深度峰值会变成 3（target 叠加在 mid 之上）；例 3 的顶层 `_RET` 会返回 0。
 * * 尾跳若另开帧：例 3 的实参复制不到目标帧 R0/R1，结果为 0 或错位。
 * * CALLF 位若被忽略：例 4 的 `resolveFunctionIndex` 一被调用就抛 [AssertionError]；
 *   例 5 的报错文案会退化成 CALL 族那条（没有找到指定的函数"@…"，两族文案不同）。
 * * try 位若不认：例 5b 直接抛 CodeEE；c=NONE 若被当作槽写，`setInt(-1)` 会越界。
 */
class VmJumpTest {

    private companion object {
        /** `_CALL` / `_CALL_BY_NAME` 的 b bit30：尾跳标志。 */
        const val JUMP_FLAG = 0x40000000

        /** `_CALL_BY_NAME` 的 b bit29：CALLF 家族标志。 */
        const val CALLF_FLAG = 0x20000000

        /** `_CALL_BY_NAME` 的 b bit31：try 标志（与 Vm.kt 的 TRY_FLAG 同值）。 */
        const val TRY_FLAG = Int.MIN_VALUE
    }

    /**
     * 记录型假宿主：输出、帧生命周期事件、两种按名解析分别留痕。
     *
     * [resolveFunctionIndex] 一旦被调到就抛 [AssertionError] —— CALLF 位必须只走
     * [resolveMethodIndex]（契约 R11）。
     */
    private class RecordingHost : VmHost {
        override val evalContext: EvalContext? = null

        val output = StringBuilder()
        val events = ArrayList<String>()
        val methodNames = ArrayList<String>()
        val functionNames = ArrayList<String>()

        /** 方法名 → 函数下标；查不到返回 null（“未命中”路径）。 */
        var methods: Map<String, Int> = emptyMap()

        /** 当前帧生命周期深度与峰值。 */
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

        override fun resolveMethodIndex(name: String): Int? {
            methodNames += name
            return methods[name]
        }

        override fun resolveFunctionIndex(name: String): Int? {
            functionNames += name
            throw AssertionError("CALLF 位不得调用 resolveFunctionIndex（收到 $name）")
        }
    }

    private fun vmRun(program: Program, host: VmHost): Long =
        VirtualMachine(program, host).run()

    // ------------------------------------------------------------------
    // R10：JUMP 尾跳
    // ------------------------------------------------------------------

    @Test
    fun `尾跳让返回值直达调用者的调用者并跳过跳转点之后的指令`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)

        // main: mid 的返回值写回 main 的 R0（尾跳后由 target 的 _RET 写入）
        b.emit(Opcode._CALL, 1, 0, SlotAddr.local(0)) // 1 = mid（声明顺序 main=0）
        b.emit(Opcode._PRINT_INT, SlotAddr.local(0), 1)
        b.emit(Opcode._HALT)

        val mid = b.declareFunction("mid", b.here(), 0)
        // mid: 0 个实参 + JUMP 位 → 替换本帧，target 的返回直达 main
        b.emit(Opcode._CALL, 2, 0 or JUMP_FLAG, SlotAddr.NONE) // 2 = target
        b.emit(Opcode._PRINT_LITERAL, b.string("MID-AFTER"), 1)
        b.emit(Opcode._RET, SlotAddr.NONE)

        val target = b.declareFunction("target", b.here(), 0)
        b.emit(Opcode._SET_INT, SlotAddr.local(1), b.constant(42L))
        b.emit(Opcode._RET, SlotAddr.local(1))

        b.setFunctionSlots(main, 2)
        b.setFunctionSlots(mid, 2)
        b.setFunctionSlots(target, 2)

        val host = RecordingHost()
        vmRun(b.build(0), host)

        // 恰好 42 + 换行：返回值写回 main 的 retDst R0，且 mid 的后续指令一条都没跑。
        assertEquals("42\n", host.output.toString())
        assertTrue("MID-AFTER" !in host.output.toString(), "尾跳点之后的 mid 指令不得执行")
    }

    @Test
    fun `尾跳不增加调用深度且 enter exit 严格配对`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._CALL, 1, 0, SlotAddr.NONE) // mid
        b.emit(Opcode._HALT)

        val mid = b.declareFunction("mid", b.here(), 0)
        b.emit(Opcode._CALL, 2, JUMP_FLAG, SlotAddr.NONE) // target，尾跳
        b.emit(Opcode._PRINT_LITERAL, b.string("MID-AFTER"), 1)
        b.emit(Opcode._RET, SlotAddr.NONE)

        val target = b.declareFunction("target", b.here(), 0)
        b.emit(Opcode._PRINT_LITERAL, b.string("TARGET"), 1)
        b.emit(Opcode._RET, SlotAddr.NONE)

        b.setFunctionSlots(main, 1)
        b.setFunctionSlots(mid, 1)
        b.setFunctionSlots(target, 1)

        val host = RecordingHost()
        assertEquals(0L, vmRun(b.build(0), host))

        assertEquals("TARGET\n", host.output.toString(), "目标必须执行；mid 尾跳点之后的打印不得执行")
        assertEquals(2, host.maxDepth, "入口 main=1、mid=2；尾跳替换 mid 而不是把 target 叠成第 3 层")
        assertEquals(
            listOf("enter:0", "enter:1", "exit:1", "enter:2", "exit:2", "exit:0"),
            host.events,
            "尾跳必须先 exit 旧帧再 enter 新帧，且每次 enter 恰好对应一次 exit",
        )
        assertEquals(0, host.depth)
    }

    /**
     * 连续尾跳（main →(尾跳) mid →(尾跳) target）：每个函数下标 enter 与 exit **各一次**。
     *
     * 锁的是 [VirtualMachine.jumpInto] 的帧替换口径：`exit:旧` 与 `enter:新` 在同一个
     * 帧槽上成对出现，且 run() 的 finally 只兜底最后活着的那一帧（target）。
     * 现状若不改 `funcStack[depth]` 之前的失败路径可见于下一个用例。
     */
    @Test
    fun `连续尾跳链上每个函数 enter 与 exit 各一次`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._CALL, 1, JUMP_FLAG, SlotAddr.NONE) // 尾跳 mid
        b.emit(Opcode._HALT)

        val mid = b.declareFunction("mid", b.here(), 0)
        b.emit(Opcode._CALL, 2, JUMP_FLAG, SlotAddr.NONE) // 尾跳 target
        b.emit(Opcode._RET, SlotAddr.NONE)

        val target = b.declareFunction("target", b.here(), 0)
        b.emit(Opcode._RET, SlotAddr.NONE)

        b.setFunctionSlots(main, 1)
        b.setFunctionSlots(mid, 1)
        b.setFunctionSlots(target, 1)

        val host = RecordingHost()
        vmRun(b.build(0), host)

        assertEquals(
            listOf("enter:0", "exit:0", "enter:1", "exit:1", "enter:2", "exit:2"),
            host.events,
            "尾跳链上 exit 必须紧跟在它所退出的旧帧之后",
        )
        assertEquals(0, host.depth)
    }

    /**
     * 尾跳建帧失败（目标 `onFunctionEnter` 抛错）时，旧帧**不得**被 run() 的 finally
     * 二次 exit：旧帧在 jumpInto 里已经 exit 过，而宿主契约是"enter 抛错的帧不出现于
     * exit 序列"（`VariableStore.enterFunction` 回滚，见 callInto 的同款注释）。
     *
     * 反向可证：若 `unwindScopes` 不跳过这一帧，事件表末尾会多一条 `exit:0`
     * （同一函数 exit 两次、enter 一次 ⇒ 变量层作用域栈欠账）。
     */
    @Test
    fun `尾跳 enter 抛错时 finally 不再二次 exit 旧帧`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._CALL, 1, JUMP_FLAG, SlotAddr.NONE) // 尾跳 target
        b.emit(Opcode._HALT)

        val target = b.declareFunction("target", b.here(), 0)
        b.emit(Opcode._RET, SlotAddr.NONE)

        b.setFunctionSlots(main, 1)
        b.setFunctionSlots(target, 1)

        val host = object : VmHost {
            override val evalContext: EvalContext? = null
            val events = ArrayList<String>()

            override fun print(text: String) {}

            override fun onFunctionEnter(funcIndex: Int) {
                events += "enter:$funcIndex"
                if (funcIndex == 1) throw IllegalStateException("尾跳 enter 失败")
            }

            override fun onFunctionExit(funcIndex: Int) {
                events += "exit:$funcIndex"
            }
        }

        val vm = VirtualMachine(b.build(0), host)
        assertFailsWith<IllegalStateException> { vm.run() }
        assertEquals(
            listOf("enter:0", "exit:0", "enter:1"),
            host.events,
            "旧帧只 leave 一次；失败的新帧不进 exit 序列",
        )
    }

    @Test
    fun `尾跳复用当前帧实参且顶层 RET 直接返回目标值`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(3L))
        b.emit(Opcode._SET_INT, SlotAddr.local(1), b.constant(4L))
        b.emit(Opcode._CALL, 1, 2 or JUMP_FLAG, SlotAddr.NONE) // add2，argc=2 + 尾跳
        b.emit(Opcode._HALT)

        val add2 = b.declareFunction("add2", b.here(), 2)
        b.emit(Opcode._ADD, SlotAddr.local(2), SlotAddr.local(0), SlotAddr.local(1))
        b.emit(Opcode._RET, SlotAddr.local(2))

        b.setFunctionSlots(main, 2)
        b.setFunctionSlots(add2, 3)

        val host = RecordingHost()
        // main 是入口帧（depth 0）。尾跳替换它之后，add2 的顶层 _RET 值就是 run 的返回值；
        // 若尾跳退化成普通调用，main 会继续执行 _HALT 并返回 0。
        assertEquals(7L, vmRun(b.build(0), host))
        assertEquals("", host.output.toString())
    }

    // ------------------------------------------------------------------
    // R11：_CALL_BY_NAME 的 CALLF 位
    // ------------------------------------------------------------------

    @Test
    fun `CALLF 位走 resolveMethodIndex 而不是 resolveFunctionIndex`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), b.string("MYMETHOD"))
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(1), CALLF_FLAG, SlotAddr.NONE)
        b.emit(Opcode._PRINT_INT, SlotAddr.global(0), 1)
        b.emit(Opcode._HALT)

        val target = b.declareFunction("target", b.here(), 0)
        b.emit(Opcode._SET_INT, SlotAddr.global(0), b.constant(77L))
        b.emit(Opcode._RET, SlotAddr.NONE)

        b.setFunctionSlots(main, 2)
        b.setFunctionSlots(target, 1)
        b.noteGlobalSlot(0)

        val host = RecordingHost().apply { methods = mapOf("MYMETHOD" to target) }
        vmRun(b.build(0), host)

        assertEquals("77\n", host.output.toString())
        assertEquals(listOf("MYMETHOD"), host.methodNames)
        assertTrue(host.functionNames.isEmpty(), "CALLF 命中不得查函数表：${host.functionNames}")
    }

    @Test
    fun `CALLF 非 try 未命中抛没有找到行内函数原文`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("NO-SUCH-METHOD"))
        b.emit(Opcode._CALL_BY_NAME, SlotAddr.local(0), CALLF_FLAG, SlotAddr.NONE)
        b.emit(Opcode._PRINT_LITERAL, b.string("MUST-NOT-RUN"), 1)
        b.emit(Opcode._HALT)
        b.setFunctionSlots(main, 1)

        val host = RecordingHost() // methods 为空 → resolveMethodIndex 返回 null
        val e = assertFailsWith<CodeEE> { vmRun(b.build(0), host) }

        // 上屏整串原文 = Lang.Error.NotDefinedUserFunc 的 zhs 表形（zhs:507 / Lang.cs:2210，
        // 抛点 FunctionIdentifier.cs:1161-1163）；**不带** `@` 前缀。
        assertEquals("没有找到行内函数\"NO-SUCH-METHOD\"", e.message)
        assertEquals(listOf("NO-SUCH-METHOD"), host.methodNames)
        assertTrue(host.functionNames.isEmpty(), "CALLF 未命中也不得回退查函数表：${host.functionNames}")
        assertEquals("", host.output.toString(), "抛错前不得执行后续打印")
    }

    @Test
    fun `CALLF try 未命中跳过且 c 为 NONE 时不写标志`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), b.string("NO-SUCH-METHOD"))
        b.emit(
            Opcode._CALL_BY_NAME,
            SlotAddr.local(1),
            CALLF_FLAG or TRY_FLAG,
            SlotAddr.NONE,
        )
        b.emit(Opcode._PRINT_LITERAL, b.string("AFTER"), 1)
        b.emit(Opcode._HALT)
        b.setFunctionSlots(main, 2)

        val host = RecordingHost()
        assertEquals(0L, vmRun(b.build(0), host))

        assertEquals("AFTER\n", host.output.toString(), "try 未命中只跳过调用本身，后续指令照常执行")
        assertEquals(listOf("NO-SUCH-METHOD"), host.methodNames)
        assertTrue(host.functionNames.isEmpty(), "CALLF 不得回退 resolveFunctionIndex：${host.functionNames}")
        assertEquals(0, host.depth)
        // c=NONE 的“不写标志”由不抛 ArrayIndexOutOfBounds 反证：
        // 若实现把 NONE 当槽号写，setInt(-1) 会在入口帧（frameBase=0）越界。
    }
}
