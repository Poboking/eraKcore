package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.sem.SemTestEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 语句形式的 `++` / `--`：前置与后置两种写法都要解析成同一条 INCREMENT/DECREMENT 赋值，
 * 并在执行层真的改变量。
 *
 * 出处：
 * - 前置：`LogicalLineParser.cs:394-405`（行首 `++`/`--` 走自增减语句）；
 * - 后置：`LogicalLineParser.cs:536-556`（赋值行读出 `Increment`/`Decrement` 运算符）；
 * - 参数折叠：`ArgumentParser.cs:1073-1090`（`AddConst` 常量 ±1，`++` 不许带参数）；
 * - 执行：与表达式层同口径 `plusInt`（`Expr.kt:206-207`）。
 *
 * 收口前后置形 `LOCAL++` 会抛「不支持的赋值运算符 INCREMENT」（`LexOperatorCode` 的枚举名
 * 是大写，`ExprOperatorCode.fromCSharpName` 只认驼峰 "Increment"），bootSmoke 的
 * `PRINT_STATE.ERB:429` 正是这条路径；即便解析出来，执行层也没接 INCREMENT/DECREMENT。
 */
class IncrementLineTest {

    private fun parser() = LineParser(SymbolTable())

    @Test
    fun `后置自增解析为 INCREMENT 赋值`() {
        val st = parser().parseCodeLine("DAY++", 1) as AssignStmt
        assertEquals(OperatorCode.INCREMENT, st.op)
        assertEquals("DAY", st.dest.name)
        assertTrue(st.args is SpSetArgument)
    }

    @Test
    fun `前置自增解析为 INCREMENT 赋值`() {
        val st = parser().parseCodeLine("++DAY", 1) as AssignStmt
        assertEquals(OperatorCode.INCREMENT, st.op)
        assertEquals("DAY", st.dest.name)
    }

    @Test
    fun `自减两种写法`() {
        assertEquals(OperatorCode.DECREMENT, (parser().parseCodeLine("DAY--", 1) as AssignStmt).op)
        assertEquals(OperatorCode.DECREMENT, (parser().parseCodeLine("--DAY", 1) as AssignStmt).op)
    }

    @Test
    fun `自增不许带参数`() {
        assertFailsWith<StmtParseException> { parser().parseCodeLine("DAY++ 1", 1) }
        assertFailsWith<StmtParseException> { parser().parseCodeLine("++DAY 1", 1) }
    }

    @Test
    fun `执行层后置自增改变量`() {
        val env = SemTestEnv()
        env.store.writeInt("DAY", longArrayOf(0), 5L)
        env.run(parser().parseCodeLine("DAY++", 1))
        assertEquals(6L, env.store.readInt("DAY", longArrayOf(0)))
    }

    @Test
    fun `执行层前置自减改变量`() {
        val env = SemTestEnv()
        env.store.writeInt("DAY", longArrayOf(0), 5L)
        env.run(parser().parseCodeLine("--DAY", 1))
        assertEquals(4L, env.store.readInt("DAY", longArrayOf(0)))
    }

    @Test
    fun `执行层数组元素自增`() {
        val env = SemTestEnv()
        env.store.writeInt("DAY", longArrayOf(1), 5L)
        env.run(parser().parseCodeLine("DAY:1++", 1))
        assertEquals(6L, env.store.readInt("DAY", longArrayOf(1)))
        assertEquals(0L, env.store.readInt("DAY", longArrayOf(0)))
    }
}
