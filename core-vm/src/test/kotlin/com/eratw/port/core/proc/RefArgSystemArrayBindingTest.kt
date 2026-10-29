package com.eratw.port.core.proc

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.DimSpecLayout
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.GlobalNameLayout
import com.eratw.port.core.compile.LocalDim
import com.eratw.port.core.expr.OperandType
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
 * **REF 形参 ← 系统数组 / 局部数组** 的回归锁（真机红 L①）。
 *
 * ## 现场
 *
 * `ERB/魔改内容/Misc.ERB:453-459` 的 `@SPLIT_F(ARGS, ARGS:1 = "/", _LIST)` 里
 * `_LIST` 是 `#DIMS REF` 形参；调用点 `ERB/魔改内容/qol/qol_skipmove.ERB` 传的是
 * **`LOCALS`（调用方局部字符串数组）**。旧 [ProcessRuntime] 的 REF 实参解析只看
 * `store.userVar(...)`（用户变量），局部/系统数组解析不到就 `continue` 静默跳过绑定，
 * 被调首次读 `_LIST` 抛 `引用变量 _LIST 未绑定目标（EmptyRefVar）`。
 *
 * ## 原版口径（逐条 `文件:行号`）
 *
 * * 实参变量解析 = `IdentifierDictionary.GetVariableToken`：调用方私有变量 → 局部变量
 *   （`localvarTokenDic`，subKey = 调用方函数名，`IdentifierDictionary.cs:448-473`）→
 *   系统变量（`varTokenDic`，`:474-489`）。
 * * REF 位取的是 token 交出的**数组对象本身**：`VariableTerm.Identifier.GetArray()`
 *   （`UserDefinedFunctionArgument.cs:45-58`），局部 token 在 null 时惰性分配
 *   （`VariableData.cs:1202-1209` / `:1279-1285`）；绑定点 `array = refArray`
 *   （`ReferenceToken.cs:125-128`）⇒ 被调写入对调用方立即可见。
 * * 角色变量仍拒绝：`ConvertArg` 恒传 `allowChara: false`（`CalledFunction.cs:159` →
 *   `ReferenceToken.cs:153-157`），本用例不覆盖（角色分支用例见 `RefArgBindingTest`）。
 *
 * 本测试的夹具与 `RefArgBindingTest`（`com.eratw.port.core.compile`）同源（最小装配：
 * `ErbProgramCompiler` + `FunctionTable` + `ProcessRuntime`）。
 */
class RefArgSystemArrayBindingTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            // 被调 `#DIMS REF REF_STRS` 的形参（解析期先认识名字，REF 信息由 DimSpecLayout 给）
            .declareVariable("REF_STRS", OperandType.STR, 1)
            // 调用方 MAIN 的头部形参名（无实参绑定，仅保证头部可解析）
            .declareVariable("ANCHOR", OperandType.INT, 1),
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
            p.parseLogicalLine(LogicalLine(kind, "refargsys.erb", i + 1, text))
        }
    }

    private data class Fn(
        val name: String,
        val arity: Int,
        val lines: Array<String>,
        val params: List<String> = emptyList(),
    )

    /** `#DIMS REF NAME` → [LocalDim]（REF 位的编译期判定源，与 RefArgBindingTest 同口径）。 */
    private fun layoutOf(fns: Array<out Fn>): DimSpecLayout {
        val dims = HashMap<String, List<LocalDim>>()
        val decl = Regex("""^#\s*DIMS?\s+.*\bREF\b\s+([^\s,;=]+)""", RegexOption.IGNORE_CASE)
        for (f in fns) {
            val refs = f.lines.mapNotNull { text ->
                val m = decl.find(text.trim()) ?: return@mapNotNull null
                LocalDim(m.groupValues[1], isStr = text.trim().startsWith("#DIMS", ignoreCase = true), ref = true)
            }
            if (refs.isNotEmpty()) dims[f.name.uppercase()] = refs
        }
        return DimSpecLayout(GlobalNameLayout(), dims)
    }

    private fun compile(vararg fns: Fn): Pb {
        val c = ErbProgramCompiler(layoutOf(fns))
        val names = fns.map { it.name }.toSet()
        for (f in fns) {
            c.compileFunction(FuncSig(f.name, f.arity, paramNames = f.params), parse(*f.lines), names)
        }
        return Pb(c, c.build(fns.first().name), fns.map { it.name })
    }

    private class Pb(val compiler: ErbProgramCompiler, val program: Program, val names: List<String>) {
        fun index(name: String): Int = compiler.functions.first { it.sig.name == name }.index
        fun meta(name: String, argInt: Int = 0): FuncMeta =
            FuncMeta(
                index = index(name), key = name,
                argIntCount = argInt, argStrCount = 0,
                defIndex = index(name),
            )
    }

    private fun run(pb: Pb, store: VariableStore): ProcessRuntime {
        val metas = pb.names.map { name -> pb.meta(name, argInt = pb.program.functions[pb.index(name)].arity) }
        val rt = ProcessRuntime(pb.program, store, FunctionTable(metas), VmConfig())
        rt.execute()
        return rt
    }

    private fun refVar(name: String) = DimSpec(name, true, intArrayOf(0), isRef = true, isPrivate = true)

    // ------------------------------------------------------------------
    // 1. REF ← 系统数组（TSTR：全局 1D 字符串）
    // ------------------------------------------------------------------

    @Test
    fun `REF 形参绑到系统字符串数组 TSTR - 写入穿透`() {
        val pb = compile(
            Fn("MAIN_SYS", 1, arrayOf("@MAIN_SYS(ANCHOR)", "CALL SYS_WRITER(TSTR)", "RETURN")),
            Fn(
                "SYS_WRITER", 1,
                arrayOf("@SYS_WRITER(REF_STRS)", "#DIMS REF REF_STRS", "REF_STRS:1 = \"zz\"", "RETURN"),
                params = listOf("REF_STRS"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(refVar("REF_STRS"), "SYS_WRITER")
        run(pb, store)
        assertEquals(0, store.callDepth(), "帧必须全部退出（enter/leave 配对）")
        // 绑的是系统数组本体（ReferenceToken.cs:125-128）：被调写入直接落到 TSTR[1]。
        // 夹具的语句层字符串字面量保留外层引号（同 RefArgBindingTest），比对前剥引号。
        assertEquals(
            "zz", store.readStr("TSTR", longArrayOf(1)).trim('"'),
            "REF 形参必须绑到系统数组 TSTR（旧实现解析不到就跳过，被调首读抛 EmptyRefVar）",
        )
    }

    // ------------------------------------------------------------------
    // 2. REF ← 调用方局部数组（LOCALS：subKey = 调用方函数名）
    // ------------------------------------------------------------------

    @Test
    fun `REF 形参绑到调用方局部字符串数组 LOCALS - 写入穿透`() {
        val pb = compile(
            Fn("MAIN_LOC", 1, arrayOf("@MAIN_LOC(ANCHOR)", "CALL LOC_WRITER(LOCALS)", "RETURN")),
            Fn(
                "LOC_WRITER", 1,
                arrayOf("@LOC_WRITER(REF_STRS)", "#DIMS REF REF_STRS", "REF_STRS:2 = \"yy\"", "RETURN"),
                params = listOf("REF_STRS"),
            ),
        )
        val store = VariableStore()
        store.declareUserVar(refVar("REF_STRS"), "LOC_WRITER")
        // 预置调用方 MAIN_LOC 的 LOCALS 实例（绑定点必须复用同一份，不能取被调帧的 LOCALS）
        store.enterFunction("MAIN_LOC")
        store.writeStr("LOCALS", longArrayOf(2), "old")
        store.leaveFunction()
        run(pb, store)
        assertEquals(0, store.callDepth(), "帧必须全部退出（enter/leave 配对）")
        store.enterFunction("MAIN_LOC")
        assertEquals(
            "yy", store.readStr("LOCALS", longArrayOf(2)).trim('"'),
            "REF 形参必须绑到调用方 MAIN_LOC 的 LOCALS（真机红 L① 的 SPLIT_F ← LOCALS）",
        )
        store.leaveFunction()
    }
}
