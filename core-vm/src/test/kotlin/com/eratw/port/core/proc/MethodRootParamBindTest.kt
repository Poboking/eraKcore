package com.eratw.port.core.proc

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **红 N**：内建函数调用户方法（GETMETHS / GETMETH / GETMETH_STR 族 → [ProcessRuntime.invokeUserMethod]）
 * 走根级绑定，必须按头部**形参名**逐位写，而不是只灌 `ARG:0..`/`ARGS:0..` 类型桶。
 *
 * ## 实机读数（2026-10-25，eratw_api34）
 *
 * | 探针 | 修复前 | 期望 |
 * |---|---|---|
 * | `GETMETHS("F角色列表33",0,"名前")` | `""` | `"TARGET列表"` |
 * | `F角色列表33(0,"名前")`（直调） | `""` | `"TARGET列表"` |
 * | `GETMETH_OBJNAME_TO_ID(0,"角色列表","TARGET列表")` | `0` | `-1` |
 *
 * 语料 `@F角色列表33(ARG, O_DATA, V_NAME)` 的第 2 位是 `#DIMS O_DATA`；旧口径只写
 * `ARGS:0 = "名前"`，`O_DATA` 恒空 ⇒ `SELECTCASE O_DATA` 落空 ⇒ 恒 `""` ⇒ 空名比对
 * 让 `OBJNAME_TO_ID` 在 `LOCAL=0` 处提前命中（返回 0 而不是 -1）。
 *
 * 原版逐位写的是 `call.TopLabel.Arg[i]` 这个**变量**（spec: ProcessState.cs:435-452、
 * CalledFunction.cs:143-200），`ARG`/`ARGS` 只是其中一种形参名。对照组 `DIVIDE(ARG, ARG:1)`
 * 恰好只用系统数组，所以旧口径下正常 —— 这就是「同通道一对一错」的原因。
 */
class MethodRootParamBindTest {

    private fun parse(vararg lines: String): List<Statement> {
        // 形参名要在符号表里先声明，`SELECTCASE O_DATA` 才解析得出来（类型 = #DIMS ⇒ STR）
        val p = LineParser(
            SymbolTable()
                .declareVariable("O_DATA", OperandType.STR, 1)
                .declareVariable("V_NAME", OperandType.STR, 1),
        )
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "meth.erb", i + 1, text))
        }
    }

    /** `#FUNCTIONS @F角色列表33(ARG, O_DATA, V_NAME)` 的最小同形：第 2 位是 `#DIMS` 私有形参。 */
    private fun buildStore(): VariableStore {
        val store = VariableStore()
        store.declareUserVar(
            DimSpec("O_DATA", isStr = true, dims = intArrayOf(100), isPrivate = true),
            functionKey = "METH",
        )
        store.declareUserVar(
            DimSpec("V_NAME", isStr = true, dims = intArrayOf(100), isPrivate = true),
            functionKey = "METH",
        )
        return store
    }

    @Test
    fun `根级方法调用按形参名绑定 #DIMS 私有形参`() {
        val sig = FuncSig(
            "METH", arity = 3, isMethod = true, returnsStr = true,
            paramNames = listOf("ARG", "O_DATA", "V_NAME"),
        )
        val c = ErbProgramCompiler()
        c.compileFunction(
            sig,
            parse(
                "@METH",
                "SELECTCASE O_DATA",
                "CASE \"名前\"",
                "  RETURNF \"TARGET列表\"",
                "ENDSELECT",
                "RETURNF \"\"",
            ),
            setOf("METH"),
        )
        val program = c.build("METH")
        val store = buildStore()
        val idx = c.functions.first { it.sig.name == "METH" }.index
        val meta = FuncMeta(
            index = idx, key = "METH", name = "METH", isMethod = true,
            returnsStr = true, argIntCount = 1, argStrCount = 2,
        )
        val rt = ProcessRuntime(program, store, FunctionTable(listOf(meta)), VmConfig())

        // GETMETHS("F角色列表33", 0, "名前") 同形：实参只有 2 位，第 3 位 V_NAME 省略
        val r = rt.invokeUserMethod("METH", listOf(Value.IntV(0), Value.StrV("名前")))
        assertEquals(Value.StrV("TARGET列表"), r, "O_DATA 未按形参名绑定 ⇒ 恒空串（红 N）")
        assertEquals(0, store.callDepth(), "方法必须走独立帧并配对退出")
    }

    @Test
    fun `只有系统 ARG 形参的方法不受影响（DIVIDE 对照组）`() {
        val sig = FuncSig("DIVIDE", arity = 2, isMethod = true, returnsStr = false, paramNames = listOf("ARG", "ARG:1"))
        val c = ErbProgramCompiler()
        c.compileFunction(sig, parse("@DIVIDE", "RETURNF ARG / ARG:1"), setOf("DIVIDE"))
        val program = c.build("DIVIDE")
        val store = VariableStore()
        val idx = c.functions.first { it.sig.name == "DIVIDE" }.index
        val meta = FuncMeta(index = idx, key = "DIVIDE", name = "DIVIDE", isMethod = true, argIntCount = 2)
        val rt = ProcessRuntime(program, store, FunctionTable(listOf(meta)), VmConfig())
        assertEquals(Value.IntV(25), rt.invokeUserMethod("DIVIDE", listOf(Value.IntV(100), Value.IntV(4))))
    }
}
