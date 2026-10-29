package com.eratw.port.core.code

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * VM 执行骨架：fetch-decode-execute、帧模型、错误传播、死循环保护。
 *
 * 两个验收程序（派单要求）：fib(10) 与 100 次循环。
 */
class VmExecutionTest {

    private fun runText(src: String, config: VmConfig = VmConfig(), host: StringHost? = null): String {
        val program = Assembler.assemble(src.trimIndent())
        val h = host ?: StringHost()
        VirtualMachine(program, h, config).run()
        return h.output.toString()
    }

    private fun vmOf(src: String, config: VmConfig = VmConfig(), host: VmHost = StringHost()): VirtualMachine =
        VirtualMachine(Assembler.assemble(src.trimIndent()), host, config)

    // ------------------------------------------------------------------
    // 两个验收程序
    // ------------------------------------------------------------------

    @Test
    fun `递归 fib(10) 得到 55`() {
        val out = runText(
            """
            .func main slots=4
              _SET_INT R0, 10
              _CALL fib, 1, R1
              _PRINT_INT R1, 1
              _HALT

            .func fib arity=1 slots=7
              _SET_INT R1, 2
              _SET_INT R2, 1
              _LT R3, R0, R1
              _JUMP_IF_TRUE R3, base
              _MOV_INT R4, R0
              _SUB R0, R4, R2
              _CALL fib, 1, R5
              _SUB R0, R4, R1
              _CALL fib, 1, R6
              _ADD R0, R5, R6
              _RET R0
            base:
              _RET R0
            """,
        )
        assertEquals("55\n", out)
    }

    @Test
    fun `循环 100 次累加到 100`() {
        val out = runText(
            """
            .func main slots=4
              _SET_INT R0, 0
              _SET_INT R1, 1
              _SET_INT R2, 100
            loop:
              _ADD R0, R0, R1
              _LT R3, R0, R2
              _JUMP_IF_TRUE R3, loop
              _PRINT_INT R0, 1
              _HALT
            """,
        )
        assertEquals("100\n", out)
    }

    // ------------------------------------------------------------------
    // 跳转
    // ------------------------------------------------------------------

    @Test
    fun `无条件跳转按指令条数偏移跳过中间指令`() {
        val out = runText(
            """
            _SET_INT R0, 1
            _JUMP end
            _SET_INT R0, 99
            end:
            _PRINT_INT R0, 1
            _HALT
            """,
        )
        assertEquals("1\n", out)
    }

    @Test
    fun `条件为假时跳转生效`() {
        val out = runText(
            """
            _SET_INT R0, 0
            _JUMP_IF_FALSE R0, skip
            _SET_INT R1, 1
            skip:
            _PRINT_INT R1, 1
            _HALT
            """,
        )
        assertEquals("0\n", out)
    }

    @Test
    fun `条件为真时不跳转`() {
        val out = runText(
            """
            _SET_INT R0, 1
            _JUMP_IF_FALSE R0, skip
            _SET_INT R1, 1
            skip:
            _PRINT_INT R1, 1
            _HALT
            """,
        )
        assertEquals("1\n", out)
    }

    @Test
    fun `条件跳转的真值判定是非零为真`() {
        val out = runText(
            """
            _SET_INT R0, -5
            _JUMP_IF_TRUE R0, yes
            _PRINT_LITERAL "no"
            _HALT
            yes:
            _PRINT_LITERAL "yes"
            _HALT
            """,
        )
        assertEquals("yes", out)
    }

    // ------------------------------------------------------------------
    // 调用栈 / 返回
    // ------------------------------------------------------------------

    @Test
    fun `按位置传参并接收返回值`() {
        val out = runText(
            """
            .func main slots=3
              _SET_INT R0, 7
              _SET_INT R1, 35
              _CALL add2, 2, R2
              _PRINT_INT R2, 1
              _HALT

            .func add2 arity=2 slots=3
              _ADD R2, R0, R1
              _RET R2
            """,
        )
        assertEquals("42\n", out)
    }

    @Test
    fun `RET 无操作数返回 0`() {
        val out = runText(
            """
            .func main slots=2
              _SET_INT R1, 7
              _CALL zero, 0, R1
              _PRINT_INT R1, 1
              _HALT

            .func zero arity=0 slots=1
              _SET_INT R0, 123
              _RET
            """,
        )
        assertEquals("0\n", out)
    }

    @Test
    fun `CALL 省略返回槽时丢弃返回值`() {
        val out = runText(
            """
            .func main slots=2
              _CALL side, 0
              _PRINT_LITERAL "ok", 1
              _HALT

            .func side arity=0 slots=1
              _RET R0
            """,
        )
        assertEquals("ok\n", out)
    }

    @Test
    fun `被调帧的槽不与调用者共享`() {
        // 调用者 R1=9；被调函数未初始化 R1，若帧隔离正确则返回 0
        val out = runText(
            """
            .func main slots=2
              _SET_INT R0, 1
              _SET_INT R1, 9
              _CALL leaky, 0, R1
              _PRINT_INT R1, 1
              _HALT

            .func leaky arity=0 slots=2
              _RET R1
            """,
        )
        assertEquals("0\n", out)
    }

    @Test
    fun `顶层 RET 结束执行并返回结果`() {
        val p = Assembler.assemble(
            """
            _SET_INT R0, 7
            _RET R0
            _SET_INT R1, 1
            """.trimIndent(),
        )
        assertEquals(7L, VirtualMachine(p, StringHost()).run())
    }

    // ------------------------------------------------------------------
    // 输出
    // ------------------------------------------------------------------

    @Test
    fun `PRINT 家族的换行标志位`() {
        val out = runText(
            """
            _SET_STR R0, "abc"
            _PRINT_STR R0, 0
            _PRINT_LITERAL "def"
            _PRINT_INT R0, 1
            _PRINT_LITERAL "x", 1
            _HALT
            """,
        )
        // R0 是字符串槽，按整数读为 0
        assertEquals("abcdef0\nx\n", out)
    }

    // ------------------------------------------------------------------
    // 算术
    // ------------------------------------------------------------------

    @Test
    fun `比较与取反的真值表`() {
        val out = runText(
            """
            _SET_INT R0, 5
            _SET_INT R1, 3
            _LT R2, R0, R1
            _GT R3, R0, R1
            _EQ R4, R0, R1
            _NE R5, R0, R1
            _LE R6, R0, R1
            _GE R7, R0, R1
            _NOT R8, R0
            _NOT R9, R1
            _PRINT_INT R2, 0
            _PRINT_INT R3, 0
            _PRINT_INT R4, 0
            _PRINT_INT R5, 0
            _PRINT_INT R6, 0
            _PRINT_INT R7, 0
            _PRINT_INT R8, 0
            _PRINT_INT R9, 1
            _HALT
            """,
        )
        // 5<3=0, 5>3=1, 5==3=0, 5!=3=1, 5<=3=0, 5>=3=1, !5=0, !3=0
        assertEquals("01010100\n", out)
    }

    @Test
    fun `取负与整除取余按 C 语义`() {
        val out = runText(
            """
            _SET_INT R0, -7
            _SET_INT R1, 3
            _DIV R2, R0, R1
            _MOD R3, R0, R1
            _NEG R4, R1
            _PRINT_INT R2, 0
            _PRINT_LITERAL ","
            _PRINT_INT R3, 0
            _PRINT_LITERAL ","
            _PRINT_INT R4, 1
            _HALT
            """,
        )
        assertEquals("-2,-1,-3\n", out)
    }

    // ------------------------------------------------------------------
    // 错误路径
    // ------------------------------------------------------------------

    @Test
    fun `除零抛 CodeEE 并带源码行号`() {
        val vm = vmOf(
            """
            _SET_INT R0, 1
            _SET_INT R1, 0
            _DIV R2, R0, R1
            """,
        )
        val e = assertFailsWith<CodeEE> { vm.run() }
        assertEquals(3, e.line)
        assertEquals("main", e.funcName)
        assertTrue(e.message!!.contains("除数为 0"), e.message)
    }

    @Test
    fun `取余零同样抛 CodeEE`() {
        val vm = vmOf("_MOD R2, R1, R0")
        assertFailsWith<CodeEE> { vm.run() }
    }

    /**
     * `long.MinValue / -1`：C# 抛 OverflowException（.NET 唯一会溢出的整除，
     * spec: OperatorMethodManager.cs:136），JVM 的 ldiv 静默回绕成 MIN_VALUE —— 按原版抛 CodeEE。
     * 取余同样不回绕：C# 与 JVM 都得 0。
     */
    @Test
    fun `最小整数除以负一抛 CodeEE 而不是回绕`() {
        val vm = vmOf(
            """
            _SET_INT R0, -9223372036854775807
            _SET_INT R1, 1
            _SUB R0, R0, R1
            _NEG R1, R1
            _DIV R2, R0, R1
            _HALT
            """,
        )
        val e = assertFailsWith<CodeEE> { vm.run() }
        assertEquals(5, e.line)
        assertEquals("main", e.funcName)
        assertTrue(e.message!!.contains("除算溢出"), e.message)
    }

    @Test
    fun `最小整数对负一取余仍得 0`() {
        val out = runText(
            """
            _SET_INT R0, -9223372036854775807
            _SET_INT R1, 1
            _SUB R0, R0, R1
            _NEG R1, R1
            _MOD R2, R0, R1
            _PRINT_INT R2, 1
            _HALT
            """,
        )
        assertEquals("0\n", out)
    }

    @Test
    fun `PC 越界抛 CodeEE`() {
        val vm = vmOf("_JUMP 100")
        val e = assertFailsWith<CodeEE> { vm.run() }
        assertTrue(e.message!!.contains("指令指针越界"), e.message)
    }

    @Test
    fun `调用不存在的函数下标抛 CodeEE`() {
        val vm = vmOf("_CALL #7, 0, R0")
        val e = assertFailsWith<CodeEE> { vm.run() }
        assertTrue(e.message!!.contains("函数下标越界"), e.message)
    }

    @Test
    fun `死循环被步数上限截断`() {
        val vm = vmOf(
            """
            loop:
              _JUMP loop
            """,
            VmConfig(maxStepsPerRun = 500),
        )
        val e = assertFailsWith<StepLimitExceeded> { vm.run() }
        assertTrue(e.steps > 500, "步数应超过上限")
        assertTrue(e.message!!.contains("疑似死循环"), e.message)
    }

    @Test
    fun `撞闸异常带脚本调用链（失败屏只透传 message）`() {
        val vm = vmOf(
            """
            .func main slots=0
              _CALL looper, 0, R0
              _HALT

            .func looper slots=0
            loop:
              _JUMP loop
            """,
            VmConfig(maxStepsPerRun = 500),
        )
        val e = assertFailsWith<StepLimitExceeded> { vm.run() }
        assertEquals("main → looper", e.callChain, "抛点就地活链：入口帧 → 当前帧（unwindScopes 之后就拿不到了）")
        assertTrue(e.message!!.endsWith("@ main → looper"), e.message)
    }

    @Test
    fun `无限递归被调用栈上限截断`() {
        val vm = vmOf(
            """
            .func main slots=2
              _CALL rec, 0, R0
              _HALT

            .func rec arity=0 slots=2
              _CALL rec, 0, R0
              _RET
            """,
            VmConfig(maxCallDepth = 16),
        )
        val e = assertFailsWith<CallStackOverflow> { vm.run() }
        assertEquals(16, e.depth)
    }

    @Test
    fun `真实 ERB 语句交语句钩子而不是静默跳过`() {
        // 契约 C4：操作码 29..197 不再由 VM 抛"尚未实现"，改为按 marker 的 a 从语句池取
        // Statement 交宿主。两条失败路径都必须显式 CodeEE（绝不静默当 NOP）：
        // 1) marker 的 a 不在语句池里（这里用 Assembler 的"操作数原样发射"分支造出空池）；
        val noPool = assertFailsWith<CodeEE> { vmOf("PRINTL 0, 0, 0").run() }
        assertTrue(noPool.message!!.contains("语句池下标越界(0)"), noPool.message)
        // 2) 池里有语句、但宿主没接线（StringHost 不覆盖 executeStatement）。
        val hooked = VirtualMachine(
            Assembler.assemble(
                "PRINTL stmt#0",
                statements = listOf(InstructionStmt(FunctionCode.PRINT, VoidArgument(), 1, "PRINTL 0")),
            ),
            StringHost(),
        )
        val unhosted = assertFailsWith<CodeEE> { hooked.run() }
        assertTrue(unhosted.message!!.contains("宿主未提供语句执行器"), unhosted.message)
    }

    // ------------------------------------------------------------------
    // 表达式复用（core.expr）
    // ------------------------------------------------------------------

    private class MapVariables : VariableReader {
        val ints = mapOf("X" to 41L)

        override fun isConst(name: String) = false
        override fun readInt(name: String, indices: LongArray) = ints[name] ?: 0L
        override fun readStr(name: String, indices: LongArray) = ""
        override fun plusInt(name: String, indices: LongArray, delta: Long) = (ints[name] ?: 0L) + delta
        override fun keywordToInteger(varName: String, dim: Int, keyword: String): Long = 0L
    }

    private class MapContext : EvalContext {
        override val variables = MapVariables()
        override fun callFunction(name: String, args: List<Value?>): Value = Value.IntV(0)
    }

    @Test
    fun `EVAL_INT 直接走 core_expr 求值`() {
        val exprs = listOf<Expr>(
            Binary(
                OperatorCode.PLUS,
                VarRef("X", emptyList(), operandType = OperandType.INT),
                IntLiteral(1),
                operandType = OperandType.INT,
            ),
        )
        val host = StringHost(MapContext())
        val program = Assembler.assemble(
            """
            _EVAL_INT ${'$'}0, R0
            _PRINT_INT R0, 1
            _HALT
            """.trimIndent(),
            exprs,
        )
        VirtualMachine(program, host).run()
        assertEquals("42\n", host.output.toString())
    }

    @Test
    fun `EVAL_STR 求值字符串表达式`() {
        val exprs = listOf<Expr>(StrLiteral("ハロー"))
        val host = StringHost(MapContext())
        val program = Assembler.assemble(
            """
            _EVAL_STR ${'$'}0, R0
            _PRINT_STR R0, 1
            _HALT
            """.trimIndent(),
            exprs,
        )
        VirtualMachine(program, host).run()
        assertEquals("ハロー\n", host.output.toString())
    }

    @Test
    fun `缺少 EvalContext 时 EVAL 抛 CodeEE`() {
        val exprs = listOf<Expr>(IntLiteral(1))
        val program = Assembler.assemble("_EVAL_INT ${'$'}0, R0", exprs)
        val e = assertFailsWith<CodeEE> { VirtualMachine(program, StringHost(null)).run() }
        assertTrue(e.message!!.contains("EvalContext"), e.message)
    }

    // ------------------------------------------------------------------
    // 全局槽
    // ------------------------------------------------------------------

    @Test
    fun `全局槽跨 run 保留而帧槽每次重置`() {
        val program = Assembler.assemble(
            """
            .func main slots=2
              _SET_INT R0, 1
              _ADD G0, G0, R0
              _RET G0
            """.trimIndent(),
        )
        val vm = VirtualMachine(program, StringHost())
        assertEquals(1L, vm.run())
        assertEquals(2L, vm.run(), "全局槽应跨 run 保留")
        assertEquals(1, program.globalSlots)
    }
}
