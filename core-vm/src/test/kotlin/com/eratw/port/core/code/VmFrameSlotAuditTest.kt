package com.eratw.port.core.code

import com.eratw.port.core.expr.EvalContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BH3-VMCORE 判据 ③：帧与槽位落点、深递归边界。
 *
 * 原版口径（C#）：帧 = `ProcessState.functionList` 里的 CalledFunction
 * （spec: ProcessState.cs:458 `functionList.Add(call)`，动态 List 无容量上限）；
 * 进入时先 `ScopeIn` 私有变量再逐**头部形参位**绑定 ARG
 * （spec: ProcessState.cs:428-452：:435 循环上界 `call.TopLabel.Arg.Length`、
 * :437 `Arguments[i] == null` 的位跳过不写、:445/:449 按 int/str 分通道 SetValue）；
 * 函数原型的局部/形参计数在 `FunctionLabelLine.LocalLength/LocalsLength/ArgLength/ArgsLength`
 * （spec: FunctionLabelLine.cs:35-41）；返回时 IsJump 帧移除自身并**递归** Return
 * （spec: ProcessState.cs:289-311）。原版唯一显式深度闸是表达式内嵌套**方法**求值的
 * `methodStack > 100`（spec: Process.cs:514-519 `OverflowFuncStack`）——普通调用栈
 * 没有上限（爆了是 .NET StackOverflow，不可捕获）。
 *
 * 本移植的帧模型（Vm.kt KDoc）：经典栈帧，帧基址 = 调用者基址 + 调用者帧大小；
 * 实参按位置复制到被调帧 R0..，`[argc, slotCount)` 清零；尾跳原地换帧不增深度。
 * `maxCallDepth`（默认 10000）是防爆栈的硬闸，**不是原版语义**（Vm.kt
 * ensureFrameSlots KDoc 已声明）。本文件锁这些移植侧契约的精确边界。
 */
class VmFrameSlotAuditTest {

    private fun runText(src: String, config: VmConfig = VmConfig()): String {
        val host = StringHost()
        VirtualMachine(Assembler.assemble(src.trimIndent()), host, config).run()
        return host.output.toString()
    }

    private class RecordingHost : VmHost {
        override val evalContext: EvalContext? = null
        var enters = 0
            private set
        var exits = 0
            private set
        override fun print(text: String) {}
        override fun onFunctionEnter(funcIndex: Int) { enters++ }
        override fun onFunctionExit(funcIndex: Int) { exits++ }
    }

    // ------------------------------------------------------------------
    // 帧基址与帧隔离
    // ------------------------------------------------------------------

    @Test
    fun `被调帧写满全部槽也不影响调用方槽`() {
        // 调用者 R0/R1/R2 = 1/2/3；被调帧 4 个槽全写。返回后调用方槽必须原值
        // （帧基址 = 调用者基址 + 调用者帧大小，两帧不重叠）。
        assertEquals(
            "7723\n",
            runText(
                """
                .func main slots=3
                  _SET_INT R0, 1
                  _SET_INT R1, 2
                  _SET_INT R2, 3
                  _CALL stomp, 0, R0
                  _PRINT_INT R0, 0
                  _PRINT_INT R1, 0
                  _PRINT_INT R2, 1
                  _HALT

                .func stomp arity=0 slots=4
                  _SET_INT R0, 77
                  _SET_INT R1, 88
                  _SET_INT R2, 99
                  _SET_INT R3, 111
                  _RET R0
                """,
            ),
        )
    }

    @Test
    fun `兄弟调用的帧槽各自清零`() {
        // dirty 把自己的 R0..R3 全写 99 后返回；clean 的帧基址与 dirty 相同
        // （同调用者、同帧大小），[argc, slotCount) 必须已清零。
        assertEquals(
            "0\n",
            runText(
                """
                .func main slots=2
                  _CALL dirty, 0, R0
                  _CALL clean, 0, R1
                  _PRINT_INT R1, 1
                  _HALT

                .func dirty arity=0 slots=4
                  _SET_INT R0, 99
                  _SET_INT R1, 99
                  _SET_INT R2, 99
                  _SET_INT R3, 99
                  _RET R0

                .func clean arity=0 slots=4
                  _RET R3
                """,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 实参落位（对照 ProcessState.cs:435-452 的逐位绑定）
    // ------------------------------------------------------------------

    @Test
    fun `实参不足时尾部形参槽为零`() {
        // arity=3 只传 1 个：R1/R2 未传位在 VM 层清零（帧契约 [argc, slotCount) = 0）。
        // 变量层 ARG 的最终值由宿主 bindCallArgs 决定（缺省位按 C# Arguments[i]==null
        // 跳过不写，spec: ProcessState.cs:437），与本 VM 契约不冲突。
        assertEquals(
            "5\n",
            runText(
                """
                .func main slots=3
                  _SET_INT R0, 5
                  _CALL f, 1, R1
                  _PRINT_INT R1, 1
                  _HALT

                .func f arity=3 slots=3
                  _ADD R2, R0, R1
                  _RET R2
                """,
            ),
        )
    }

    @Test
    fun `实参多于形参时多余部分被裁剪且不写坏调用方槽`() {
        // arity=2 传 3 个：只取前两个（minOf(argcRaw, arity)），调用方 R2 保持 7。
        assertEquals(
            "30\n7\n",
            runText(
                """
                .func main slots=4
                  _SET_INT R0, 10
                  _SET_INT R1, 20
                  _SET_INT R2, 7
                  _CALL g, 3, R3
                  _PRINT_INT R3, 1
                  _PRINT_INT R2, 1
                  _HALT

                .func g arity=2 slots=2
                  _ADD R1, R0, R1
                  _RET R1
                """,
            ),
        )
    }

    @Test
    fun `尾跳目标帧接管旧槽时超出实参的槽被清零`() {
        // jumper 帧 R2=99；尾跳 g（arity=1, slots=3）原地换帧，[1,3) 必须清零。
        // 返回经本帧原有 retDst（main 的 R1）直达 —— 期望 0。
        assertEquals(
            "0\n",
            runText(
                """
                .func main slots=3
                  _CALL jumper, 0, R1
                  _PRINT_INT R1, 1
                  _HALT

                .func jumper slots=4
                  _SET_INT R0, 11
                  _SET_INT R2, 99
                  _CALL g, 1073741825, none
                  _HALT

                .func g arity=1 slots=3
                  _RET R2
                """,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 深递归：帧隔离 + 帧槽数组增长 + 深度闸边界
    // ------------------------------------------------------------------

    @Test
    fun `深递归每层局部互不污染且帧槽数组可增长`() {
        // sum(50)：51 帧 × 4 槽 = 204 槽 > INITIAL_SLOTS(64)，走 ensureFrameSlots 增长路。
        // 每层用本帧 R3 保存 n 跨递归调用 —— 帧若重叠结果必错。
        assertEquals(
            "1275\n",
            runText(
                """
                .func main slots=2
                  _SET_INT R0, 50
                  _CALL sum, 1, R1
                  _PRINT_INT R1, 1
                  _HALT

                .func sum arity=1 slots=4
                  _SET_INT R1, 1
                  _LT R2, R0, R1
                  _JUMP_IF_TRUE R2, base
                  _MOV_INT R3, R0
                  _SUB R0, R0, R1
                  _CALL sum, 1, R2
                  _ADD R0, R3, R2
                  _RET R0
                base:
                  _SET_INT R0, 0
                  _RET R0
                """,
            ),
        )
    }

    @Test
    fun `递归深度恰达到上限时成功而超过一阶即抛`() {
        // main(0) → rec(15)(1) → … → rec(1)(15)：最大深度 15。
        val src = """
            .func main slots=2
              _SET_INT R0, 15
              _CALL rec, 1, R1
              _PRINT_INT R1, 1
              _HALT

            .func rec arity=1 slots=3
              _SET_INT R1, 1
              _EQ R2, R0, R1
              _JUMP_IF_TRUE R2, base
              _SUB R0, R0, R1
              _CALL rec, 1, R0
              _RET R0
            base:
              _SET_INT R0, 42
              _RET R0
        """.trimIndent()

        assertEquals("42\n", runText(src, VmConfig(maxCallDepth = 15)), "深度 15 = 上限，应成功")

        val vm = VirtualMachine(Assembler.assemble(src), StringHost(), VmConfig(maxCallDepth = 14))
        val e = assertFailsWith<CallStackOverflow> { vm.run() }
        assertEquals(14, e.depth, "第 15 帧被闸拦下：期望 14 实际 ${'$'}{e.depth}")
    }

    @Test
    fun `调用栈溢出后同一 VM 实例可续跑且作用域栈已回滚`() {
        val program = Assembler.assemble(
            """
            .func main slots=2
              _CALL rec, 0, R0
              _HALT

            .func rec arity=0 slots=2
              _CALL rec, 0, R0
              _RET

            .func quick slots=2
              _SET_INT R0, 7
              _RET R0
            """.trimIndent(),
        )
        val host = RecordingHost()
        val vm = VirtualMachine(program, host, VmConfig(maxCallDepth = 8))
        assertFailsWith<CallStackOverflow> { vm.run(1) }
        assertTrue(host.enters > 0 && host.enters == host.exits, "溢出后 enter/exit 必须配对")

        assertEquals(7L, vm.run(2), "溢出后续跑应成功")
        assertEquals(2L, vm.executedSteps)
        assertEquals(host.enters, host.exits, "两次 run 之后 enter/exit 仍配对")
    }

    @Test
    fun `步数闸异常退出也落 executedSteps`() {
        // 失败记录曾恒为 0（只在 HALT/_RET/Stop/Unwind 正常出口写数），
        // 异常穿透 execute 时也必须把已执行步数交回。
        val vm = VirtualMachine(
            Assembler.assemble(
                """
                .func main slots=0
                  _NOP
                  _NOP
                  _NOP
                  _HALT
                """.trimIndent(),
            ),
            StringHost(),
            VmConfig(maxStepsPerRun = 2),
        )
        assertFailsWith<StepLimitExceeded> { vm.run() }
        assertEquals(3L, vm.executedSteps, "步数闸在第 cap+1 条抛出，读数必须是 3 而不是 0")
    }

    @Test
    fun `尾跳链不增加深度因此不受调用栈闸限制`() {
        // 30 次尾跳全在 depth 1 的同一帧上完成（maxCallDepth=2 也够）——
        // 与 C# IsJump 帧不增加 functionList 深度同口径（ProcessState.cs:298-311）。
        val out = runText(
            """
            .func main slots=2
              _CALL j0, 0, R1
              _PRINT_INT R1, 1
              _HALT

            .func j0 slots=2
              _CALL j1, 1073741824, none

            .func j1 slots=2
              _CALL j2, 1073741824, none

            .func j2 slots=2
              _CALL j3, 1073741824, none

            .func j3 slots=2
              _RET R0
            """,
            VmConfig(maxCallDepth = 2),
        )
        assertEquals("0\n", out)
    }
    @Test
    fun `构建期拒绝 arity 大于 slotCount 的函数`() {
        // 手写 `.func bad arity=3 slots=1`（body 只用到 R0 ⇒ 终态 slotCount=1 < arity=3）：
        // CodeBuilder.build 必须按跨字段不变量当场拒绝，否则 VM 建帧时实参复制越界。
        val e = assertFailsWith<IllegalArgumentException> {
            Assembler.assemble(
                """
                .func main slots=1
                  _HALT

                .func bad arity=3 slots=1
                  _RET R0
                """.trimIndent(),
            )
        }
        assertTrue(e.message!!.contains("arity"), "报错文案应点名 arity/slotCount：${e.message}")
    }

}
