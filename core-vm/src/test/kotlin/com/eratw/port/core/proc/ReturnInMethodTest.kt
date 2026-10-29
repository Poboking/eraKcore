package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.Value
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `RETURN <值>` 落在 `#FUNCTION`/`#FUNCTIONS` 内的语义（ADR-005 原样复刻官方怪行为）。
 *
 * ## 原版口径
 *
 * `RETURN_Instruction.DoInstruction` 只做两件事（spec: FunctionIdentifier.cs:3265-3271）：
 * `SetResultX(...)` 写 RESULT 数组，然后 `state.Return(RESULT:0)`。而
 * `ProcessState.Return` 的**第一条**就是
 * `if (IsFunctionMethod) { ReturnF(null); return; }`（spec: ProcessState.cs:289-295）——
 * 方法帧内的 `RETURN` 不走「弹帧 + 交返回值」，而是转 `ReturnF(null)`：
 * `MethodReturnValue = null`（ProcessState.cs:463-473）。消费侧
 * `SuperUserDefinedMethodTerm.GetIntValue` 对 null 返回 **0**（`…Term.cs:24-31`），
 * `GetStrValue` 返回 ""（:33-38）。
 *
 * 于是 `RETURN 5` 写在 `#FUNCTION` 里：**方法返回值是 0**，但 RESULT:0 仍是 5
 * （SetResultX 已经写完才转 ReturnF）。本移植旧实现直接 `ExecResult.Return(RESULT:0)`
 * ⇒ 方法返回值成了 5，比原版「多给」一个值。
 *
 * 语料实测 0 命中（往 `#FUNCTION` 里写 `RETURN x` 本身就是脚本 bug），本组用例锁的是
 * 语义而非可观测收益 —— 与 ADR-005「怪行为照抄」一致。
 */
class ReturnInMethodTest {

    /** 用例的函数体只写 `RETURN`/`RETURNF`，不引用任何变量 ⇒ 符号表留空即可。 */
    private fun parser(): LineParser = LineParser(SymbolTable())

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "retmethod.erb", i + 1, text))
        }
    }

    /** 编译一组函数；[methods] 里的名字按 `#FUNCTION` 方法编（`RETURNF` 的装载期校验需要它）。 */
    private fun compile(vararg fns: Pair<String, List<String>>, methods: Set<String> = emptySet()): Program {
        val compiler = ErbProgramCompiler()
        val known = fns.mapTo(HashSet()) { it.first.uppercase() }
        for ((name, lines) in fns) {
            val parsed = parse(*lines.toTypedArray())
            val label = parsed.filterIsInstance<FuncLabelStmt>().firstOrNull() ?: error("切片缺 @行")
            compiler.compileFunction(
                FuncSig(
                    name = name,
                    arity = label.paramNames.size,
                    isMethod = name.uppercase() in methods,
                    paramNames = label.paramRefs,
                ),
                parsed,
                known,
            )
        }
        return compiler.build(fns.first().first)
    }

    /** 元数据表：全部函数按名登记，[methods] 内的标 `isMethod`。 */
    private fun runtime(program: Program, store: VariableStore, methods: Set<String> = emptySet()): ProcessRuntime {
        val metas = program.functions.indices.map { i ->
            val f = program.functions[i]
            FuncMeta(
                index = i,
                key = f.name,
                isMethod = f.name.uppercase() in methods,
                argIntCount = f.arity,
                argStrCount = f.arity,
                defIndex = i,
            )
        }
        return ProcessRuntime(program, store, FunctionTable(metas), VmConfig())
    }

    @Test
    fun `RETURN 在 FUNCTION 内的方法返回值是 0 而不是 RESULT 0`() {
        val program = compile(
            "MTH" to listOf("@MTH", "#FUNCTION", "RETURN 5"),
            methods = setOf("MTH"),
        )
        val store = VariableStore()
        val ret = runtime(program, store, setOf("MTH")).invokeUserMethod("MTH", emptyList())
        assertEquals(
            0L,
            (ret as Value.IntV).v,
            "spec: ProcessState.cs:289-295 —— 方法帧内 RETURN 先转 ReturnF(null)，消费侧得 0",
        )
        assertEquals(5L, store.readInt("RESULT", longArrayOf(0)), "RESULT 数组仍被 SetResultX 写成 5")
    }

    /** 对照组：`RETURNF 5` 是方法返回值的**正规**通道，必须照旧返回 5。 */
    @Test
    fun `RETURNF 在 FUNCTION 内照旧返回该值`() {
        val program = compile(
            "MTH" to listOf("@MTH", "#FUNCTION", "RETURNF 5"),
            methods = setOf("MTH"),
        )
        val store = VariableStore()
        val ret = runtime(program, store, setOf("MTH")).invokeUserMethod("MTH", emptyList())
        assertEquals(5L, (ret as Value.IntV).v)
    }

    /** 对照组：普通函数（非方法）里的 `RETURN 5` 仍是「交回 5」，不受方法帧规则影响。 */
    @Test
    fun `RETURN 在普通函数内照旧交回该值`() {
        val program = compile("PLAIN" to listOf("@PLAIN", "RETURN 7"))
        val store = VariableStore()
        assertEquals(7L, runtime(program, store).callFunction("PLAIN"))
    }
}
