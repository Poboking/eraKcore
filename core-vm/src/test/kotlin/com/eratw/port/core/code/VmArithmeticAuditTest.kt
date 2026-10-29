package com.eratw.port.core.code

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.sem.ExecContext
import com.eratw.port.core.sem.StatementExecutor
import com.eratw.port.core.sem.TestEvalContext
import com.eratw.port.core.sem.instr
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SpPowerArgument
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * BH3-VMCORE 判据 ④：算术溢出与截断语义。
 *
 * 原版口径（C#）：表达式层二元/一元运算全在 `OperatorMethodManager`：
 * * 加减乘 = 普通 `long` 运算，**unchecked 回绕**（spec: OperatorMethodManager.cs:24/:57/:71）；
 * * 整除 `DivIntInt`（:129-137）：先查 0（:132-135 抛 DivideByZero），再
 *   `arguments[0] / intValue`（:136）—— .NET long 整除**向零截断**；
 *   `long.MinValue / -1` 是 .NET 唯一溢出的整除，抛 OverflowException；
 * * 取余 `ModIntInt`（:148-156）：`%`（:155）—— 符号随被除数，`MinValue % -1` 得 0 不抛；
 * * 取负 `MinusInt`（:559-566）：操作数是 MinValue 时**打一行系统提示**后仍返回
 *   `-MinValue`（回绕成 MinValue，:565）；
 * * `POWER` **语句**（spec: Process.cs:1350-1370）与 `POWER` **方法**
 *   （spec: FunctionMethodCreator.cs:4172-4190）同构：`Math.Pow` 双精度幂 →
 *   NaN/Infinity/`|d| >= 9.223372036854776E+18` 三闸抛错 → `(long)d` 向零截断。
 *
 * JVM 侧 `ladd/lsub/lmul/ldiv/lrem/lneg` 与上述逐条同语义，唯一差别是
 * `MinValue / -1`：JVM ldiv 静默回绕成 MinValue，.NET 抛 OverflowException ——
 * 按原版抛 [CodeEE]（Vm.kt `_DIV` 分支注释，既有用例已锁；本文件复钉）。
 */
class VmArithmeticAuditTest {

    private fun arith(op: String, l: Long, r: Long): Long {
        val p = Assembler.assemble(
            """
            .func main slots=3
              _SET_INT R0, $l
              _SET_INT R1, $r
              $op R2, R0, R1
              _RET R2
            """.trimIndent(),
        )
        return VirtualMachine(p, StringHost()).run()
    }

    private fun neg(v: Long): Long {
        val p = Assembler.assemble(
            """
            .func main slots=2
              _SET_INT R0, $v
              _NEG R1, R0
              _RET R1
            """.trimIndent(),
        )
        return VirtualMachine(p, StringHost()).run()
    }

    // ------------------------------------------------------------------
    // Long 回绕（C# unchecked：OperatorMethodManager.cs:24/:57/:71）
    // ------------------------------------------------------------------

    @Test
    fun `加减乘按 C# unchecked 回绕`() {
        // ADD
        assertEquals(Long.MIN_VALUE, arith("_ADD", Long.MAX_VALUE, 1), "MAX+1 应回绕成 MIN")
        assertEquals(Long.MAX_VALUE, arith("_ADD", Long.MIN_VALUE, -1), "MIN+(-1) 应回绕成 MAX")
        assertEquals(-2L, arith("_ADD", Long.MAX_VALUE, Long.MAX_VALUE), "MAX+MAX 应回绕成 -2")
        // SUB
        assertEquals(Long.MAX_VALUE, arith("_SUB", Long.MIN_VALUE, 1), "MIN-1 应回绕成 MAX")
        assertEquals(0L, arith("_SUB", Long.MIN_VALUE, Long.MIN_VALUE), "MIN-MIN = 0")
        // MUL
        assertEquals(-2L, arith("_MUL", Long.MAX_VALUE, 2), "MAX*2 应回绕成 -2")
        assertEquals(0L, arith("_MUL", Long.MIN_VALUE, 2), "MIN*2 = 2^64 回绕成 0")
        assertEquals(0L, arith("_MUL", 4611686018427387904L, 4), "2^62*4 = 2^64 回绕成 0")
        assertEquals(Long.MIN_VALUE, arith("_MUL", Long.MIN_VALUE, -1), "MIN*(-1) 回绕成 MIN（不抛）")
    }

    @Test
    fun `取负对最小整数回绕而不是抛错`() {
        // C# MinusInt（OperatorMethodManager.cs:561-565）：MinValue 只多打一行系统提示，
        // 返回值仍是 -MinValue = MinValue（回绕）。
        assertEquals(Long.MIN_VALUE, neg(Long.MIN_VALUE), "NEG(MIN) 应回绕成 MIN")
        assertEquals(1L, neg(-1))
        assertEquals(-5L, neg(5))
    }

    // ------------------------------------------------------------------
    // 整除 / 取余：向零截断 + 余数符号随被除数（C# `/` 与 `%`）
    // ------------------------------------------------------------------

    @Test
    fun `整除向零截断且余数符号随被除数`() {
        // C# long `/` 与 `%`（OperatorMethodManager.cs:136/:155）与 JVM ldiv/lrem 同语义。
        assertEquals(-3L, arith("_DIV", -7, 2), "-7/2 应向零截断成 -3")
        assertEquals(-1L, arith("_MOD", -7, 2), "-7%2 余数符号随被除数 = -1")
        assertEquals(-3L, arith("_DIV", 7, -2), "7/-2 应向零截断成 -3")
        assertEquals(1L, arith("_MOD", 7, -2), "7%-2 余数符号随被除数 = 1")
        assertEquals(3L, arith("_DIV", -7, -2), "-7/-2 = 3")
        assertEquals(-1L, arith("_MOD", -7, -2), "-7%-2 余数符号随被除数 = -1")
        assertEquals(-2L, arith("_DIV", -8, 3), "-8/3 向零截断成 -2（不是 -3）")
        assertEquals(-2L, arith("_MOD", -8, 3), "-8%3 = -2")
        assertEquals(3L, arith("_DIV", 7, 2))
        assertEquals(1L, arith("_MOD", 7, 2))
    }

    @Test
    fun `最小整数除以负一抛 CodeEE 而取余得零`() {
        // C#：MinValue / -1 是 .NET 唯一溢出整除（OverflowException，
        // spec: OperatorMethodManager.cs:136）；取余两边都得 0 不抛（:155）。
        val min = "-9223372036854775808"
        val p = Assembler.assemble(
            """
            .func main slots=3
              _SET_INT R0, $min
              _SET_INT R1, -1
              _DIV R2, R0, R1
              _RET R2
            """.trimIndent(),
        )
        val e = assertFailsWith<CodeEE> { VirtualMachine(p, StringHost()).run() }
        assertTrue(e.message!!.contains("除算溢出"), "实际文案: ${'$'}{e.message}")

        val pm = Assembler.assemble(
            """
            .func main slots=3
              _SET_INT R0, $min
              _SET_INT R1, -1
              _MOD R2, R0, R1
              _RET R2
            """.trimIndent(),
        )
        assertEquals(0L, VirtualMachine(pm, StringHost()).run(), "MinValue % -1 = 0")
    }

    @Test
    fun `除零与取余零都抛 CodeEE`() {
        // C# DivIntInt/ModIntInt 的零检查（OperatorMethodManager.cs:132-135 / :151-154）。
        val div = assertFailsWith<CodeEE> { arith("_DIV", 1, 0) }
        assertTrue(div.message!!.contains("除数为 0"), "实际文案: ${'$'}{div.message}")
        val mod = assertFailsWith<CodeEE> { arith("_MOD", 1, 0) }
        assertTrue(mod.message!!.contains("除数为 0"), "实际文案: ${'$'}{mod.message}")
    }

    // ------------------------------------------------------------------
    // POWER：语句（Process.cs:1350-1370）+ 方法（FunctionMethodCreator.cs:4172-4190）
    // ------------------------------------------------------------------

    /** 走真实语句层执行 `POWER dest, x, y`，返回 dest 读数。 */
    private fun powerStmt(x: Long, y: Long): Long {
        val store = VariableStore()
        val ctx = ExecContext(variables = store, eval = TestEvalContext(store))
        StatementExecutor().execute(
            instr(
                FunctionCode.POWER,
                SpPowerArgument(
                    VarRef("RESULT", listOf(IntLiteral(0L)), operandType = OperandType.INT),
                    IntLiteral(x),
                    IntLiteral(y),
                ),
            ),
            ctx,
        )
        return store.readInt("RESULT", longArrayOf(0))
    }

    private fun powerMethod(x: Long, y: Long): Long {
        val store = VariableStore()
        val ctx = BuiltinEvalContext(TestEvalContext(store), store, object : BuiltinHost {})
        return (ctx.callFunction("POWER", listOf(Value.IntV(x), Value.IntV(y))) as Value.IntV).v
    }

    @Test
    fun `POWER 语句按原版双精度幂与三闸越界抛错`() {
        assertEquals(1024L, powerStmt(2, 10))
        assertEquals(4611686018427387904L, powerStmt(2, 62), "2^62 在 Int64 内")
        assertEquals(-8L, powerStmt(-2, 3), "负底整数幂")
        assertEquals(9L, powerStmt(3, 2))
        // 2^63 恰为越界闸值（>= 9.223372036854776E+18）—— spec: Process.cs:1364-1367
        assertFailsWith<CodeEE> { powerStmt(2, 63) }
        // 无穷大闸 —— spec: Process.cs:1360-1363
        assertFailsWith<CodeEE> { powerStmt(2, 100) }
    }

    @Test
    fun `POWER 方法与语句同口径`() {
        assertEquals(1024L, powerMethod(2, 10))
        assertEquals(4611686018427387904L, powerMethod(2, 62))
        assertEquals(1L, powerMethod(0, 0))
        assertFailsWith<CodeEE> { powerMethod(2, 63) }
        assertFailsWith<CodeEE> { powerMethod(2, 100) }
    }
}
