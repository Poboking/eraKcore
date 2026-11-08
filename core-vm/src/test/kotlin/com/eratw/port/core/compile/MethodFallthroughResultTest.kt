package com.eratw.port.core.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
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
import com.eratw.port.core.vars.RandProvider
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 掉尾（无 RETURN/RETURNF 落到底）时 **`#FUNCTION`/`#FUNCTIONS` 方法不清 RESULT**。
 *
 * 原版判据在 `Process.cs:1059-1065`：走到文件末的 NullLine / 下一个函数标签时
 * `if (!state.IsFunctionMethod) vEvaluator.RESULT = 0L;` 然后 `state.Return(0L)`。
 * 即清零只对**普通 `@label` 函数**生效；方法帧掉尾时 RESULT 保留函数体内的最后一次写入
 * （方法自己的返回值走 `ReturnF(null)` → RESULTS 空串 / 消费侧 0，与 RESULT 无关）。
 *
 * 语料依赖这条：`M_KOJO_K70_01_イベント.ERB:6087` 的
 * `CALLF M_KOJO_K70_KOSUZUBOOM()` 之后紧接 `RAND:RESULT`（:6088），而被调方法是
 * `#FUNCTIONS`（:247-250）—— 它 `RESULT = 5` 后对 `CFLAG:1080 % 10 == 0` 不命中任何 CASE、
 * 直接落尾。移植侧旧实现无条件投 `RESULT = 0` ⇒ 调用方读到 0 ⇒
 * 「RAND 的参数必须为正数（实际 0）」开局中止（2026-10-31 玩家屏）。
 */
class MethodFallthroughResultTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("G0", OperandType.INT, 1)
            .declareFunction("F", OperandType.STR)
            .declareFunction("G", OperandType.INT),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "fallthrough.erb", i + 1, text))
        }
    }

    private data class Model(val program: Program, val store: VariableStore, val metas: List<FuncMeta>)

    private fun model(
        callee: String,
        isMethod: Boolean,
        returnsStr: Boolean,
        main: List<String>,
        body: List<String>,
    ): Model {
        val c = ErbProgramCompiler()
        val known = setOf("MAIN", callee)
        c.compileFunction(FuncSig("MAIN", 0), parse(*main.toTypedArray()), known)
        c.compileFunction(FuncSig(callee, 0, isMethod = isMethod, returnsStr = returnsStr), parse(*body.toTypedArray()), known)
        val metas = listOf(
            FuncMeta(index = c.indexes.getValue("MAIN"), key = "MAIN", name = "MAIN", position = "fallthrough.erb"),
            FuncMeta(
                index = c.indexes.getValue(callee),
                key = callee,
                name = callee,
                isMethod = isMethod,
                returnsStr = returnsStr,
                position = "fallthrough.erb",
            ),
        )
        val store = VariableStore().apply {
            declareUserVar(DimSpec("G0", isStr = false, dims = intArrayOf(1)))
            // 上界探查桩：返回 bound-1，把「RAND 收到的上界」原样透到结果里。
            randProvider = RandProvider { bound -> bound - 1 }
        }
        return Model(c.build("MAIN"), store, metas)
    }

    private fun Model.run(): Long =
        ProcessRuntime(program, store, FunctionTable(metas), VmConfig()).execute()

    @Test
    fun `FUNCTIONS 方法掉尾保留 RESULT 调用方 RAND 读到 5`() {
        val m = model(
            callee = "F",
            isMethod = true,
            returnsStr = true,
            main = listOf("CALLF F()", "G0:0 = RAND:RESULT"),
            body = listOf("RESULT = 5"),
        )
        m.run()
        assertEquals(
            4L, m.store.readInt("G0", longArrayOf(0)),
            "RAND:RESULT 的上界必须是方法体留下的 5（桩返回 bound-1）；读到 0 即掉尾被误清零",
        )
    }

    @Test
    fun `普通函数掉尾仍清 RESULT`() {
        val m = model(
            callee = "G",
            isMethod = false,
            returnsStr = false,
            main = listOf("CALL G()", "G0:0 = RESULT"),
            body = listOf("RESULT = 5"),
        )
        m.run()
        assertEquals(
            0L, m.store.readInt("G0", longArrayOf(0)),
            "普通 @label 函数掉尾清零（spec: Process.cs:1061-1063）",
        )
    }

    private val ErbProgramCompiler.indexes: Map<String, Int>
        get() = functions.associate { it.sig.name.uppercase() to it.index }
}
