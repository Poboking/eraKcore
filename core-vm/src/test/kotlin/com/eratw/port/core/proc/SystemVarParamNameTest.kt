package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 形参名是**内建（系统/局部）变量**时的实参写回。
 *
 * ## 现场（本测试要锁的产品缺陷，2026-10-29 玩家反馈）
 *
 * `ERB/DLC/OPTIONPRINT.ERB:94` 的补丁清单打印器写成
 * `@Pt01(LOCAL,ARG,ARGS,ARG:1,ARGS:1,…)`，逐条由 `:110-129` 判 `IF LOCAL` 决定印
 * 「已启动 / 未启动」。旧实现只把 `ARG`/`ARGS` 单独接出来，其余走
 * `store.userVar(...) ?: continue` —— `LOCAL` 不是用户变量 ⇒ 该位**静默不写** ⇒
 * `LOCAL` 恒 0 ⇒ 所有补丁无论开没开都印「未启动」。
 *
 * 同一道缝在语料里还有 24 个形参位：`PLAYER` 11（`@CAN_COM702(PLAYER)` 一族）、
 * `TARGET` 7、`LOCALS` 5、`RESULT` 1。
 *
 * ## 原版口径
 *
 * * `ProcessState.cs:435-452`：进帧后逐位 `call.TopLabel.Arg[i].SetValue(...)`，
 *   `Arg[i]` 是 `ErbLoader.parseLabel` 按**形参项原文**解析出的 `VariableTerm`
 *   （`ErbLoader.cs:646-760`）—— 名字是什么就写什么。
 * * `IdentifierDictionary.GetVariableToken`（`:429-495`）查表顺序：
 *   私有用户变量 → 局部变量（LOCAL/LOCALS/ARG/ARGS）→ 全局系统变量 → 全局用户变量。
 * * 1 维变量不带下标 ⇒ 补 `ZeroTerm`（`VariableParser.cs:156-168`，`LOCAL` = `LOCAL:0`）；
 *   0 维变量（`RESULT`）不带下标。
 * * 省略位：`Def[i]` 只给 `ARG`/`ARGS`/`IsPrivate` 补隐式 0/""（`ErbLoader.cs:714-718`），
 *   而 `LocalVariableToken` 的 `IsPrivate` 恒 false（`VariableToken.cs:72`）⇒
 *   **省略 LOCAL 形参位是硬错误**（`CanNotOmitArg`，`:168-172`），本侧必须同样报。
 */
class SystemVarParamNameTest {

    // ------------------------------------------------------------------
    // 装置（与 FuncArgSubscriptTest / FuncArgConvertTest 同款）
    // ------------------------------------------------------------------

    private data class Fn(val name: String, val lines: Array<String>)

    private class Pb(val program: Program, val names: List<String>) {
        fun index(name: String): Int =
            program.functions.indices.first { program.functions[it].name.equals(name, ignoreCase = true) }
    }

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("OUT", OperandType.INT, 1)
            .declareVariable("OUTS", OperandType.STR, 1)
            // 函数体内读形参位：LOCAL 族是内建局部变量，解析期仍需在符号表里登记，
            // 否则字符串语境下的裸 `LOCALS` 会被当成字面量。
            .declareVariable("LOCAL", OperandType.INT, 1)
            .declareVariable("LOCALS", OperandType.STR, 1),
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
            p.parseLogicalLine(LogicalLine(kind, "sysvarparam.erb", i + 1, text))
        }
    }

    private fun compile(vararg fns: Fn): Pb {
        val parsed = fns.map { parse(*it.lines) }
        val labels = parsed.map { stmts ->
            stmts.filterIsInstance<FuncLabelStmt>().firstOrNull() ?: error("切片缺 @行")
        }
        val compiler = ErbProgramCompiler()
        val known = fns.mapTo(HashSet()) { it.name.uppercase() }
        for ((i, f) in fns.withIndex()) {
            val refs = labels[i].paramRefs
            compiler.compileFunction(
                FuncSig(name = f.name, arity = refs.size, paramNames = refs),
                parsed[i],
                known,
            )
        }
        return Pb(compiler.build(fns.first().name), fns.map { it.name })
    }

    private fun runtime(pb: Pb, store: VariableStore): ProcessRuntime {
        val metas = pb.names.map { name ->
            val index = pb.index(name)
            val arity = pb.program.functions[index].arity
            FuncMeta(index = index, key = name, argIntCount = arity, argStrCount = arity, defIndex = index)
        }
        val host = object : BuiltinHost {}
        return ProcessRuntime(pb.program, store, FunctionTable(metas), VmConfig(), builtinHost = host)
    }

    /** 被调 `P` 的私有输出位（`OUT` 整型 / `OUTS` 字符串）。 */
    private fun store(): VariableStore {
        val store = VariableStore()
        store.declareUserVar(DimSpec("OUT", false, intArrayOf(1), isPrivate = true), "P")
        store.declareUserVar(DimSpec("OUTS", true, intArrayOf(1), isPrivate = true), "P")
        return store
    }

    // ------------------------------------------------------------------
    // 1. `@P(LOCAL)`：实参必须写进**被调方**的 LOCAL:0
    // ------------------------------------------------------------------

    @Test
    fun `形参名为 LOCAL 时实参写进被调方的 LOCAL`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL P(7)", "RETURN")),
            Fn("P", arrayOf("@P(LOCAL)", "OUT = LOCAL", "RETURN")),
        )
        val store = store()
        runtime(pb, store).execute()
        assertEquals(7L, store.userVar("OUT", "P")!!.readInt(0, 0), "LOCAL 形参位被静默丢弃（旧实现）")
    }

    // ------------------------------------------------------------------
    // 2. `@P(LOCAL:1)`：冒号下标必须落到对应元素
    // ------------------------------------------------------------------

    @Test
    fun `形参名为 LOCAL 带下标时写对应元素`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL P(9)", "RETURN")),
            Fn("P", arrayOf("@P(LOCAL:1)", "OUT = LOCAL:1", "RETURN")),
        )
        val store = store()
        runtime(pb, store).execute()
        assertEquals(9L, store.userVar("OUT", "P")!!.readInt(0, 0), "LOCAL:1 形参位")
    }

    // ------------------------------------------------------------------
    // 3. `@P(LOCALS)`：字符串通道
    // ------------------------------------------------------------------

    @Test
    fun `形参名为 LOCALS 时走字符串通道`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL P(\"hi\")", "RETURN")),
            Fn("P", arrayOf("@P(LOCALS)", "OUTS = %LOCALS%", "RETURN")),
        )
        val store = store()
        runtime(pb, store).execute()
        assertEquals("hi", store.userVar("OUTS", "P")!!.readStr(0, 0), "LOCALS 形参位")
    }

    // ------------------------------------------------------------------
    // 4. 省略位：`@P(LOCAL, LOCAL:1)` 只传 1 个 ⇒ 原版硬错误（LOCAL 不是 IsPrivate，
    //    装载期 Def 为 null，CompatiFuncArgOptional 关 ⇒ CanNotOmitArg）
    // ------------------------------------------------------------------

    @Test
    fun `省略 LOCAL 形参位报不能省略而不是静默留零`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL P(5)", "RETURN")),
            Fn("P", arrayOf("@P(LOCAL, LOCAL:1)", "OUT = LOCAL", "RETURN")),
        )
        val e = assertFailsWith<CodeEE>("省略的 LOCAL 形参位必须报 CanNotOmitArg") {
            runtime(pb, store()).execute()
        }
        assertTrue(
            e.message.orEmpty().contains("第2个参数不能省略"),
            "文案应为 CanNotOmitArg（ErbLoader.cs:714-718 只给 ARG/ARGS/私有形参隐式默认）；实际=${e.message}",
        )
    }
}
