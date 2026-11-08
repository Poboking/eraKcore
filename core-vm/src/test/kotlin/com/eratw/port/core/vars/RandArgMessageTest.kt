package com.eratw.port.core.vars

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RAND 伪变量的上界错误文案（解析期 + 运行期两条路都对齐官方 zhs）。
 *
 * 官方出处：
 * - 运行期 `VariableData.cs` RandToken :846-851（`num <= 0` 抛）→
 *   `emuera-zhs.xml:376` Error.RandArgIsNegative =「RAND不能指定0以下的值({0})」。
 * - 解析期 `VariableParser.cs:168-170`（常量字面 0 折叠期报错）→
 *   `emuera-zhs.xml:345` Error.RandArgIsZero =「RAND有一个参数为0」。
 *
 * 背景：2026-10-31 玩家报告 M_KOJO_EVENT_K70_31 行6087（语料侧 `RAND:RESULT` 撞 0，
 * ADR-005 判定语料侧不修），当时移植侧文案与官方措辞不同——屏面同构但字面不一致，
 * 对账时多一步翻译，故逐字对齐。
 */
class RandArgMessageTest {

    private fun parse(vararg lines: String): List<Statement> {
        val p = LineParser(
            SymbolTable()
                .declareVariable("G0", OperandType.INT, 1)
                .declareFunction("MAIN", OperandType.INT),
        )
        return lines.mapIndexedNotNull { i, text ->
            p.parseLogicalLine(
                when {
                    text.startsWith("@") -> LogicalLine(LineKind.FUNCTION_LABEL, "rand.erb", i + 1, text)
                    else -> LogicalLine(LineKind.CODE, "rand.erb", i + 1, text)
                },
            )
        }
    }

    /** 装一个只含 MAIN 的程序：`G0:0 = RAND:<expr>`，bound 表达式由 [boundExpr] 给出。 */
    private fun compileRand(boundExpr: String): Pair<ProcessRuntime, VariableStore> {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("MAIN", 0), parse("@MAIN", "G0:0 = RAND:$boundExpr"), setOf("MAIN"))
        val metas = listOf(
            FuncMeta(
                index = c.functions.first { it.sig.name.uppercase() == "MAIN" }.index,
                key = "MAIN", name = "MAIN", position = "rand.erb",
            ),
        )
        val store = VariableStore().apply {
            declareUserVar(DimSpec("G0", isStr = false, dims = intArrayOf(1)))
            randProvider = RandProvider { bound -> bound - 1 }
        }
        return ProcessRuntime(c.build("MAIN"), store, FunctionTable(metas), VmConfig()) to store
    }

    @Test
    fun `字面 RAND 0 走解析期闸文案等于官方 zhs`() {
        val e = runCatching { compileRand("0") }.exceptionOrNull()
        assertTrue(e is com.eratw.port.core.stmt.StmtParseException, "RAND:0 字面量必须在解析期报错，实际 ${e?.javaClass?.name}")
        // StmtParseException 的 message 带「语句解析错误(行…)」包装前缀，按既有测试口径 contains 断言。
        assertTrue(e.message!!.contains("RAND有一个参数为0"), e.message)
    }

    @Test
    fun `运行期 bound 0 的报错文案逐字等于官方 zhs`() {
        // RESULT 是变量引用、解析期常量闸看不见它；掉尾保留口径下初值 0，正好走运行期抛点。
        val (rt, _) = compileRand("RESULT")
        val t = try {
            rt.execute(); null
        } catch (thrown: Throwable) {
            thrown
        }
        assertTrue(t is CodeEE, "RAND:RESULT（初值 0）必须运行期抛 CodeEE，实际 ${t?.javaClass?.name}")
        assertEquals("RAND不能指定0以下的值(0)", t.message)
    }

    @Test
    fun `运行期负上界报错同样对齐且带原始值`() {
        val (rt, store) = compileRand("G0")
        store.writeInt("G0", longArrayOf(0), -3)
        val t = try {
            rt.execute(); null
        } catch (thrown: Throwable) {
            thrown
        }
        assertTrue(t is CodeEE, "RAND:-3 必须运行期抛 CodeEE，实际 ${t?.javaClass?.name}")
        assertEquals("RAND不能指定0以下的值(-3)", t.message)
    }

    @Test
    fun `RAND 正常上界不受文案改动影响`() {
        val (rt, _) = compileRand("5")
        val t = try {
            rt.execute(); null
        } catch (thrown: Throwable) {
            thrown
        }
        assertEquals(null, t, "RAND:5 在桩（返回 bound-1）下应正常落 4，不抛")
    }
}
