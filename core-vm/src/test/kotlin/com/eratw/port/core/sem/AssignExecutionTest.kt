package com.eratw.port.core.sem

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SET 赋值执行口径（`SET-ADDCONST` / 原 `DRAW_MAP-LOOP`）。
 *
 * 原版 SET_Instruction（`FunctionIdentifier.cs:640-657`）：
 * `value = IsConst ? ConstInt : Term.GetIntValue(exm)`，`AddConst ? ChangeValue(value) : SetValue(value)`。
 * 解析期（`ArgumentParser.cs:1037-1096`）把 `X += 2` 折成 `Term=null, IsConst, AddConst,
 * ConstInt=±2`，把非常量复合赋值折成 `Term = (X op rhs)` **整式**。
 *
 * 移植侧旧执行层只读 `Term`（常量为 null ⇒ rhs=0）并按 `stmt.op` 再套一次运算：
 * - `VAR += 常量` / `VAR -= 常量` → no-op（DRAW_MAP 的 `X_POINT += 2` 永不前进 ⇒ 死循环）；
 * - `VAR *= 2`（非常量语义的整式）→ `X*(X*2)`，双重应用。
 */
class AssignExecutionTest {

    private fun parseLine(text: String): Statement = LineParser().parseCodeLine(text, 1)!!

    private fun run(vararg lines: String): VariableStore {
        val env = SemTestEnv()
        lines.forEach { env.run(parseLine(it)) }
        return env.store
    }

    @Test
    fun `常量增量 += 生效`() {
        val store = run("DAY = 10", "DAY += 2")
        assertEquals(12L, store.readInt("DAY", longArrayOf(0)), "X += 2 必须是加 2，不是 no-op")
    }

    @Test
    fun `常量减量 -= 生效`() {
        val store = run("DAY = 10", "DAY -= 2")
        assertEquals(8L, store.readInt("DAY", longArrayOf(0)), "X -= 2 必须是减 2（解析期 ConstInt 已带符号）")
    }

    @Test
    fun `自增自减生效`() {
        val store = run("DAY = 10", "DAY++", "DAY++", "DAY--")
        assertEquals(11L, store.readInt("DAY", longArrayOf(0)))
    }

    @Test
    fun `非常量 += 不双重应用`() {
        // `DAY += DAY` 的 Term 是整式 (DAY + DAY)；DAY=10 ⇒ 20。
        // 旧实现 rhs=20 再加 20 ⇒ 30（错）。
        val store = run("DAY = 10", "DAY += DAY")
        assertEquals(20L, store.readInt("DAY", longArrayOf(0)))
    }

    @Test
    fun `非常量乘等按整式落库`() {
        // `DAY *= 2` ⇒ Term=(DAY*2)=20，SetValue(20)；旧实现 10*20=200。
        val store = run("DAY = 10", "DAY *= 2")
        assertEquals(20L, store.readInt("DAY", longArrayOf(0)))
    }

    @Test
    fun `除零走表达式口径报错`() {
        val env = SemTestEnv()
        env.run(parseLine("DAY = 10"))
        val e = kotlin.runCatching { env.run(parseLine("DAY /= 0")) }.exceptionOrNull()
        assertEquals("除数为 0", e?.message, "整式求值与普通表达式同源（OperatorMethodManager.cs:121-157）")
    }

    @Test
    fun `非常量步长 FOR 循环按步进收口`() {
        // FOR 步进是编译器合成的 AssignStmt：非常量步长必须与源码复合赋值同形（整式），
        // 否则执行层会把整式当右值直接写回，循环变量被写成步长本身。
        val parser = LineParser(
            SymbolTable()
                .declareVariable("CNT", OperandType.INT, 1)
                .declareVariable("STEPV", OperandType.INT, 1),
        )
        val lines = listOf("@T", "CNT = 0", "STEPV = 2", "FOR CNT, 0, 10, STEPV", "NEXT")
        val stmts = lines.mapIndexedNotNull { i, text ->
            val kind = if (text.startsWith("@")) LineKind.FUNCTION_LABEL else LineKind.CODE
            parser.parseLogicalLine(LogicalLine(kind, "test.erb", i + 1, text))
        }
        val compiler = ErbProgramCompiler()
        val info = compiler.compileFunction(FuncSig("T", 0), stmts, setOf("T"))
        val program = compiler.build("T")

        val store = VariableStore()
        store.declareUserVar(DimSpec("CNT", isStr = false, dims = intArrayOf(1), isPrivate = false))
        store.declareUserVar(DimSpec("STEPV", isStr = false, dims = intArrayOf(1), isPrivate = false))
        ProcessRuntime(
            program,
            store,
            FunctionTable(listOf(FuncMeta(index = info.index, key = "T", defIndex = 0))),
            VmConfig(maxStepsPerRun = 100_000L),
        ).execute()

        assertEquals(10L, store.readInt("CNT", longArrayOf(0)), "FOR CNT, 0, 10, STEPV（STEPV=2）排他界收口在 10")
        assertEquals(2L, store.readInt("STEPV", longArrayOf(0)), "步长变量本身不得被改写")
    }
}
