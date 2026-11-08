package com.eratw.port.core.code

import com.eratw.port.core.expr.TestContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 稳定性波 L1（VM 执行核心）：**同一类「字节码操作数越界」必须走同一条 CodeEE 出口**。
 *
 * 三类现状缺陷（改前各自以不同方式绕过宿主收口）：
 * 1. `_JUMP_BY_NAME` 取到标签 pc 后**不校验**归属（[Opcode._JUMP_DYN] 走
 *    `checkTransferTarget`，同名表同一语义却漏了一条）⇒ 标签表里一条越出本函数的 pc
 *    会让当前帧「跑进别的函数体」，返回点仍指向原调用者 —— 帧模型静默损坏，
 *    且脚本侧看到的是「值莫名其妙变了」而不是任何报错。
 * 2. 栈码组的池引用（`_STK_PUSH_IMM` 的常量池、`_STK_PUSH_STR` / `_STK_PUSH_VAR_*` /
 *    `_STK_PLUS_VAR_I` / `_STK_STORE_VAR_*` 的字符串池）是**裸操作数**，VM 内没有守卫
 *    （[VirtualMachine] 的 `_SET_INT`/`_SET_STR`/`_PRINT_LITERAL`/`_EVAL_*` 都有
 *    `checkPoolIndex`）⇒ 越界时冒出去的是 `IndexOutOfBoundsException`，不是 [CodeEE]，
 *    失败屏因此没有「函数 行N」现场（宿主 `GameHost.scriptLocation` 只认 CodeEE）。
 *    装载链的两条路都校验过栈码池引用（`ErbcStream.validate` → `ErbcReader.requireStackOpPool`），
 *    但内存直编（`CodeBuilder` / `Assembler`）不走那趟 —— 与 `checkPoolIndex` 的 KDoc
 *    写明的存在理由逐字相同。
 * 3. [VirtualMachine.run] **可重入**：内层 run 会把 `depth` 复位成 0、退出时再收成 -1，
 *    外层循环随后读 `funcStack[-1]`（`ArrayIndexOutOfBoundsException`）或干脆静默返回
 *    错值；入口帧的 `onFunctionExit` 也再不会配对调用。嵌套执行有专用通道
 *    （`deriveNestedVm` 造**另一个实例**），同一实例的重入只会摧毁帧状态。
 *
 * 反向可证（防误伤）：同函数内的合法标签跳转照常生效；合法栈码照常求值；
 * 重入被拒之后同一实例仍能正常跑下一次 `run`（状态复位）。
 */
class StabilityL1VmGuardTest {

    // ------------------------------------------------------------------
    // 1. _JUMP_BY_NAME 目标归属
    // ------------------------------------------------------------------

    /**
     * main 的标签 `L` 被登记成**另一个函数体**的首地址：改前跳过去照跑
     * （`_RET R0` 在 depth 0 处直接结束 run，返回值 42），改后必须是 CodeEE。
     */
    @Test
    fun `按名跳转的目标落在别的函数体时抛 CodeEE 而不是静默跑进别人的代码`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        val strL = b.string("L")
        b.emit(Opcode._SET_STR, SlotAddr.local(0), strL, 0, 1)
        b.emit(Opcode._JUMP_BY_NAME, SlotAddr.local(0), 0, 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)
        val otherEntry = b.here()
        val other = b.declareFunction("other", otherEntry, 0, 2)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(42L), 0, 4)
        b.emit(Opcode._RET, SlotAddr.local(0), 0, 0, 5)
        b.emit(Opcode._HALT, 0, 0, 0, 6)
        // 坏表：main 的 $L 指向 other 的第一条指令。
        b.declareLabel(main, "L", otherEntry)
        b.declareLabel(other, "OWN", otherEntry)

        val e = assertFailsWith<CodeEE> {
            VirtualMachine(b.build(main), StringHost()).run()
        }
        val message = e.message.orEmpty()
        assertTrue("跳转目标 pc 越界" in message, "文案须点名跳转目标越界，实际：$message")
        assertTrue("main" in message, "文案须点出当前函数，实际：$message")
        assertEquals(2, e.line, "位置应落在 _JUMP_BY_NAME 所在的源码行")
    }

    /** 反向：同函数内的合法 `$标签` 跳转不受影响（跳过去打印 7）。 */
    @Test
    fun `同函数内的按名跳转照常生效`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("L"), 0, 1)
        b.emit(Opcode._JUMP_BY_NAME, SlotAddr.local(0), 0, 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3) // 跳转没命中的话就到这里
        val target = b.here()
        b.emit(Opcode._SET_INT, SlotAddr.local(1), b.constant(7L), 0, 4)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(1), 0, 0, 5)
        b.emit(Opcode._HALT, 0, 0, 0, 6)
        b.declareLabel(main, "L", target)

        val host = StringHost()
        VirtualMachine(b.build(main), host).run()
        assertEquals("7", host.output.toString(), "合法标签跳转必须照旧跳到目标")
    }

    /** 反向：`$标签` 缺失且带 TRY 位时照旧跳过本行（不因新守卫变成硬错）。 */
    @Test
    fun `TRY 位下缺失标签照旧跳过`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(0), b.string("NOPE"), 0, 1)
        b.emit(Opcode._JUMP_BY_NAME, SlotAddr.local(0), Opcode.TRY_FLAG, 0, 2)
        b.emit(Opcode._PRINT_LITERAL, b.string("ok"), 0, 0, 3)
        b.emit(Opcode._HALT, 0, 0, 0, 4)

        val host = StringHost()
        VirtualMachine(b.build(main), host).run()
        assertEquals("ok", host.output.toString())
    }

    // ------------------------------------------------------------------
    // 2. 栈码组的池引用越界
    // ------------------------------------------------------------------

    /** `_STK_PUSH_IMM` 的 `a` 是常量池绝对下标；伪下标必须 CodeEE。 */
    @Test
    fun `栈码常量池伪下标抛 CodeEE 而不是 IndexOutOfBoundsException`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        val c = b.constant(5L) // 池里只有 1 项
        b.emit(Opcode._STK_PUSH_IMM, c, 0, 0, 1)
        b.emit(Opcode._STK_POP_SLOT_I, SlotAddr.local(0), 0, 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)
        b.patch(0, 1, 99)

        val e = assertFailsWith<CodeEE> {
            VirtualMachine(b.build(main), StringHost()).run()
        }
        val message = e.message.orEmpty()
        assertTrue("常量池下标越界" in message, "文案口径须同 checkPoolIndex，实际：$message")
        assertTrue("99" in message, "文案须带伪下标，实际：$message")
    }

    /** `_STK_PUSH_STR` 的 `a` 是字符串池绝对下标。 */
    @Test
    fun `栈码字符串池伪下标抛 CodeEE`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        val s = b.string("x")
        b.emit(Opcode._STK_PUSH_STR, s, 0, 0, 1)
        b.emit(Opcode._STK_DROP_S, 0, 0, 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)
        b.patch(0, 1, 77)

        val e = assertFailsWith<CodeEE> {
            VirtualMachine(b.build(main), StringHost(TestContext())).run()
        }
        val message = e.message.orEmpty()
        assertTrue("字符串池下标越界" in message, "文案口径须同 checkPoolIndex，实际：$message")
        assertTrue("77" in message, "文案须带伪下标，实际：$message")
    }

    /** 变量名同样是字符串池下标（`_STK_PUSH_VAR_I` 的 `a`）。 */
    @Test
    fun `栈码变量名的池伪下标抛 CodeEE`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        val name = b.string("A")
        b.emit(Opcode._STK_PUSH_VAR_I, name, 0, 0, 1)
        b.emit(Opcode._STK_POP_SLOT_I, SlotAddr.local(0), 0, 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)
        b.patch(0, 1, 500)

        val e = assertFailsWith<CodeEE> {
            VirtualMachine(b.build(main), StringHost(TestContext())).run()
        }
        val message = e.message.orEmpty()
        assertTrue("字符串池下标越界" in message, "文案口径须同 checkPoolIndex，实际：$message")
        assertTrue("500" in message, "文案须带伪下标，实际：$message")
    }

    /** 反向：合法栈码常量/字符串照常求值（守卫不得误报）。 */
    @Test
    fun `合法栈码常量与字符串照常求值`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._STK_PUSH_IMM, b.constant(3L), 0, 0, 1)
        b.emit(Opcode._STK_PUSH_IMM, b.constant(4L), 0, 0, 2)
        b.emit(Opcode._STK_ADD, 0, 0, 0, 3)
        b.emit(Opcode._STK_POP_SLOT_I, SlotAddr.local(0), 0, 0, 4)
        b.emit(Opcode._STK_PUSH_STR, b.string("ab"), 0, 0, 5)
        b.emit(Opcode._STK_POP_SLOT_S, SlotAddr.local(1), 0, 0, 6)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(0), 0, 0, 7)
        b.emit(Opcode._PRINT_STR, SlotAddr.local(1), 0, 0, 8)
        b.emit(Opcode._HALT, 0, 0, 0, 9)

        val host = StringHost()
        VirtualMachine(b.build(main), host).run()
        assertEquals("7ab", host.output.toString())
    }

    // ------------------------------------------------------------------
    // 3. VM 实例不可重入
    // ------------------------------------------------------------------

    /** 在 `print` 回调里对**同一实例**再 `run()`：改前静默返回错值 / AIOOBE。 */
    @Test
    fun `同一实例重入 run 抛 CodeEE 且作用域仍配对`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(1L), 0, 1)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(0), 0, 0, 2)
        b.emit(Opcode._RET, SlotAddr.local(0), 0, 0, 3)

        var enters = 0
        var exits = 0
        var vm: VirtualMachine? = null
        val host = object : VmHost {
            override fun print(text: String) {
                vm!!.run() // 重入：期望被拒
            }

            override val evalContext: com.eratw.port.core.expr.EvalContext? = null
            override fun onFunctionEnter(funcIndex: Int) { enters++ }
            override fun onFunctionExit(funcIndex: Int) { exits++ }
        }
        vm = VirtualMachine(b.build(main), host)

        val e = assertFailsWith<CodeEE> { vm!!.run() }
        assertTrue("不可重入" in e.message.orEmpty(), "文案须点名不可重入，实际：${e.message}")
        assertEquals(1, enters, "入口帧只进一次")
        assertEquals(1, exits, "重入被拒后入口帧仍须成对退出")
    }

    /** 反向：重入被拒之后，同一实例的下一次 `run` 照常工作。 */
    @Test
    fun `重入被拒后同一实例仍可正常再跑`() {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), b.constant(9L), 0, 1)
        b.emit(Opcode._PRINT_INT, SlotAddr.local(0), 0, 0, 2)
        b.emit(Opcode._RET, SlotAddr.local(0), 0, 0, 3)
        val program = b.build(main)

        var reenter = true
        var vm: VirtualMachine? = null
        val host = object : VmHost {
            override fun print(text: String) {
                if (reenter) {
                    reenter = false
                    runCatching { vm!!.run() } // 第一次被拒（外层仍在跑），之后不再重入
                }
            }

            override val evalContext: com.eratw.port.core.expr.EvalContext? = null
        }
        vm = VirtualMachine(program, host)
        assertEquals(9L, vm!!.run(), "重入被拒后帧状态必须复位，下一次 run 结果正确")
        assertEquals(9L, vm!!.run(), "再跑一次同样正确")
    }

    // ------------------------------------------------------------------
    // 4. 主循环三闸的边界值与中断现场
    // ------------------------------------------------------------------

    /** 自环程序（纯计算长链，语句之间没有让出边界）。 */
    private fun selfLoop(line: Int = 1): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._JUMP, -1, 0, 0, line) // pc' = pc + 1 + (-1) = pc
        return b.build(main)
    }

    /** 步数闸的边界：恰好 `maxStepsPerRun` 条指令走完不抛，第 `max+1` 条才抛。 */
    @Test
    fun `步数闸的边界恰为 maxStepsPerRun 条指令`() {
        val e = assertFailsWith<StepLimitExceeded> {
            VirtualMachine(selfLoop(), StringHost(), VmConfig(maxStepsPerRun = 100L)).run()
        }
        assertEquals(101L, e.steps, "第 101 条指令才触发闸（前 100 条是允许量）")

        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 1)
        repeat(99) { b.emit(Opcode._NOP, 0, 0, 0, 1) }
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        // 99 × _NOP + 1 × _HALT = 100 条：正好等于上限，必须正常结束。
        VirtualMachine(b.build(main), StringHost(), VmConfig(maxStepsPerRun = 100L)).run()
    }

    /**
     * 纯计算长链在 interrupt 后必须退出，且抛点**带现场**（函数/行/pc）——两条中断出口
     * （65536 步检查点 / 暂停分支）共用 [VirtualMachine] 的同一句文案与同一份位置章，
     * 宿主日志与失败屏据此定位。
     */
    @Test
    fun `纯计算长链 interrupt 后抛带位置的 CodeEE`() {
        Thread.currentThread().interrupt()
        val e = try {
            assertFailsWith<CodeEE> { VirtualMachine(selfLoop(line = 7), StringHost()).run() }
        } finally {
            Thread.interrupted() // 清中断位，避免污染同线程的后续用例
        }
        assertTrue("执行被中断" in e.message.orEmpty(), "文案须为中断出口，实际：${e.message}")
        assertEquals("main", e.funcName, "中断出口须带函数名")
        assertEquals(7, e.line, "中断出口须带源码行")
        assertTrue(e.pc >= 0, "中断出口须带 pc，实际：${e.pc}")
    }
}
