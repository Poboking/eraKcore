package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.ReturnFStmt
import com.eratw.port.core.stmt.ReturnStmt
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * RETURN / RETURNFORM / RETURNF 的语句层语义（契约 R10 / API 1-2）。
 *
 * 原版依据：
 * - RETURN_Instruction：空 TermList 直接 RESULT=0 并返回 0；否则逐项求值后 SetResultX
 *   （spec: FunctionIdentifier.cs:3242-3272）。
 * - RETURNFORM_Instruction：FORM 串按逗号逐段求值，空列表补 0，再 SetResultX
 *   （spec: FunctionIdentifier.cs:3213-3239）。
 * - SetResultX 只写给定下标，不清除 RESULT 的更高位（spec: VariableEvaluator.cs:2010-2016）。
 * - RETURNF 已由编译器编成 _RET（`stmt.code == RETURNF`），sem 层为 no-op、不写 RESULT。
 */
class ReturnSemanticsTest {

    private fun returnStmt(raw: String, vararg values: Expr?): ReturnStmt =
        ReturnStmt(values.toList(), VoidArgument(), 1, raw)

    private fun returnFormStmt(raw: String, text: Expr?): ReturnFStmt =
        ReturnFStmt(text, VoidArgument(), 1, raw, FunctionCode.RETURNFORM)

    private fun result(env: SemTestEnv, index: Long): Long =
        env.store.readInt("RESULT", longArrayOf(index))

    @Test
    fun `RETURN 按序写 RESULT 并返回首项`() {
        val env = SemTestEnv()
        val r = env.run(
            returnStmt(
                "RETURN 10, 20, 30",
                IntLiteral(10L), IntLiteral(20L), IntLiteral(30L),
            ),
        )
        assertEquals(ExecResult.Return(10L), r)
        assertEquals(10L, result(env, 0))
        assertEquals(20L, result(env, 1))
        assertEquals(30L, result(env, 2))
    }

    @Test
    fun `RETURN 不清除 RESULT 高位`() {
        val env = SemTestEnv()
        env.store.writeInt("RESULT", longArrayOf(2), 77)
        env.store.writeInt("RESULT", longArrayOf(3), 88)
        val r = env.run(returnStmt("RETURN 1, 2", IntLiteral(1L), IntLiteral(2L)))
        assertEquals(ExecResult.Return(1L), r)
        assertEquals(1L, result(env, 0))
        assertEquals(2L, result(env, 1))
        assertEquals(77L, result(env, 2))
        assertEquals(88L, result(env, 3))
    }

    @Test
    fun `空参 RETURN 将 RESULT 首项写为 0`() {
        val env = SemTestEnv()
        val r = env.run(returnStmt("RETURN"))
        assertEquals(ExecResult.Return(0L), r)
        assertEquals(0L, result(env, 0))
    }

    @Test
    fun `RETURNFORM 逐段写 RESULT 并返回首项`() {
        val env = SemTestEnv()
        env.ctx.runtimeIntList = { text ->
            if (text == "11, 3") listOf(11L, 3L) else error("unexpected: $text")
        }
        val r = env.run(returnFormStmt("RETURNFORM {11, 3}", StrLiteral("11, 3")))
        assertEquals(ExecResult.Return(11L), r)
        assertEquals(11L, result(env, 0))
        assertEquals(3L, result(env, 1))
    }

    @Test
    fun `RETURNFORM 未注入 runtimeIntList 抛 CodeEE`() {
        val env = SemTestEnv()
        assertEquals(null, env.ctx.runtimeIntList)
        val e = assertFailsWith<CodeEE> {
            env.run(returnFormStmt("RETURNFORM {11}", StrLiteral("11")))
        }
        assertTrue(e.message!!.contains("runtimeIntList"), e.message)
    }

    @Test
    fun `RETURNFORM 解析为空列表时补 0`() {
        val env = SemTestEnv()
        env.ctx.runtimeIntList = { _ -> emptyList() }
        val r = env.run(returnFormStmt("RETURNFORM {}", StrLiteral("")))
        assertEquals(ExecResult.Return(0L), r)
        assertEquals(0L, result(env, 0))
    }

    @Test
    fun `RETURNF 不写 RESULT 且返回 Normal`() {
        val env = SemTestEnv()
        env.store.writeInt("RESULT", longArrayOf(0), 123)
        val r = env.run(ReturnFStmt(IntLiteral(42L), VoidArgument(), 1, "RETURNF 42"))
        assertEquals(ExecResult.Normal, r)
        assertEquals(123L, result(env, 0))
    }
}
