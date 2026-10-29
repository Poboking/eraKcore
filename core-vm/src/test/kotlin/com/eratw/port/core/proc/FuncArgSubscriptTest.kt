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

/**
 * CMP-85A：形参项**冒号下标**非编译期常量时，`ProcessRuntime` 原来是**静默跳过**整位写回。
 *
 * ## 原版口径（reference/emuera-cs 只读）
 *
 * `ErbLoader.parseLabel` 逐位取形参项，并**在装载期**判死下标形态：
 *
 * * `variableTerm is VariableNoArgTerm` ⇒ `ArgHasNotSubscript`（`ErbLoader.cs:686-688` → :776-778）；
 * * `!variableTerm.isAllConst` ⇒ `ArgSubscriptOnlyConst`（`:690-693` → :773-775）；
 * * 外层模板 `FuncArgError`（`ErbLoader.cs:768`；`Lang.cs:2446`「関数\"@{0}\"の引数のエラー:{1}」）。
 *
 * 文案（本作 zhs 语言表 `reference/emuera-cs/MinorShift.Emuera.Properties.lang.emuera-zhs.xml`）：
 * `:566` = `函数"@{0}"的参数错误:{1}`、`:576` = `函数定义的参数下标必须指定为一个常量`。
 *
 * ## 本侧形态
 *
 * 形参项原文（`FuncLabelStmt.paramRefs`，带下标）经容器段 8 到运行期，
 * `ProcessRuntime.bindCallArgs`（**改前** `:857-860`）与 `bindImplicitArgDefault`
 * （**改前** `:932-939`）各自把下标 `toLongOrNull()`，`null` 就 `continue`/`return true` ——
 * 该形参位**整位不写**，被调读到的是旧值，调用方无从察觉（原版是装载期硬错误，
 * 不是运行期静默降级）。改后两条通道都走 `foldParamSubscripts`（`ProcessRuntime.kt:936`），
 * 非十进制整数下标一律按原版文案报 [CodeEE]。
 *
 * 可达形态是**冒号下标**：方括号形参 `@F[X+1]` 被 `LineParser.kt:560-561` 直接拒绝
 * （语料 0 条），所以只有 `@F(OUT:X+1)` 这一条路能进到运行期。
 *
 * **CMP-85A 生产者侧落定后**（`LineParser.constSubscripts`）：`@F(OUT:X+1)` 在解析期即抛
 * `StmtParseException`，本文件改由 `compile(..., paramRefsOverride=…)` 直构 `FuncSig.paramNames`
 * 送达运行期 —— 覆盖对象变成「容器段 8 的旧数据 / 直构路径」这一条兜底通道，
 * 与生产者侧的新用例（`LineParserParamSubscriptTest`）互补。
 *
 * 本文件三条用例：
 * 1. 非 const 下标（实参位）⇒ 必须报错；
 * 2. 非 const 下标（省略位）⇒ 同样必须报错（两条通道口径一致）；
 * 3. 数字常量下标 ⇒ 照原版写回**该元素**（回归护栏：修复不得把合法形态一起拦掉）。
 */
class FuncArgSubscriptTest {

    // ------------------------------------------------------------------
    // 装置（与 FuncArgConvertTest / RefArgBindingTest 同款）
    // ------------------------------------------------------------------

    private data class Fn(val name: String, val lines: Array<String>)

    private class Pb(val program: Program, val names: List<String>) {
        fun index(name: String): Int =
            program.functions.indices.first { program.functions[it].name.equals(name, ignoreCase = true) }
    }

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("OUT", OperandType.INT, 1)
            // 运行期变量：`@F(OUT:X+1)` 的 X 是普通变量 ⇒ 下标不是编译期常量
            .declareVariable("X", OperandType.INT, 1),
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
            p.parseLogicalLine(LogicalLine(kind, "funcargsub.erb", i + 1, text))
        }
    }

    private fun compile(
        vararg fns: Fn,
        /**
         * 直构形参项原文（不经 `@行` 解析）。
         *
         * CMP-85A 生产者侧落定后（`LineParser.constSubscripts`），`@PSUB(OUT:X+1)`
         * 这类非 const 下标会在**解析期**抛 [com.eratw.port.core.stmt.StmtParseException]，
         * 本文件原先经 `@行` 构造的输入到不了运行期。本参数保住第二条通道的覆盖：
         * 容器段 8 的旧数据/直构 `FuncSig.paramNames` 仍可能带非 const 原文，
         * `ProcessRuntime.foldParamSubscripts` 必须继续报 CodeEE 而不是静默跳过。
         */
        paramRefsOverride: Map<String, List<String>> = emptyMap(),
    ): Pb {
        val parsed = fns.map { parse(*it.lines) }
        val labels = parsed.map { stmts ->
            stmts.filterIsInstance<FuncLabelStmt>().firstOrNull() ?: error("切片缺 @行")
        }
        val compiler = ErbProgramCompiler()
        val known = fns.mapTo(HashSet()) { it.name.uppercase() }
        for ((i, f) in fns.withIndex()) {
            val refs = paramRefsOverride[f.name] ?: labels[i].paramRefs
            compiler.compileFunction(
                FuncSig(
                    name = f.name,
                    arity = refs.size,
                    paramNames = refs,
                ),
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

    /** 被调私有整型数组，两维口径与语料 `#DIM OUT, 2` 同形（下标 0/1 都可写）。 */
    private fun outStore(fn: String): VariableStore {
        val store = VariableStore()
        store.declareUserVar(DimSpec("OUT", false, intArrayOf(2), isPrivate = true), fn)
        return store
    }

    private companion object {
        /** zhs:566 模板 + zhs:576 文案（`FuncArgError` = 函数"@{0}"的参数错误:{1}）。 */
        fun argSubscriptOnlyConst(fn: String): String =
            "函数\"@$fn\"的参数错误:函数定义的参数下标必须指定为一个常量"
    }

    // ------------------------------------------------------------------
    // 1. 实参位：`@PSUB(OUT:X+1)` —— 下标不是常量 ⇒ 报错（旧形态：静默跳过、OUT 不动）
    // ------------------------------------------------------------------

    @Test
    fun `非 const 形参下标必须报错而不是静默跳过`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL PSUB(7)", "RETURN")),
            // 生产者侧已在解析期拒绝 `@PSUB(OUT:X+1)`；这里直构原文，专测运行期兜底。
            Fn("PSUB", arrayOf("@PSUB(OUT)", "RETURN")),
            paramRefsOverride = mapOf("PSUB" to listOf("OUT:X+1")),
        )
        val store = outStore("PSUB")
        val e = assertFailsWith<CodeEE>("非 const 下标必须报错（旧形态静默 continue）") {
            runtime(pb, store).execute()
        }
        assertEquals(argSubscriptOnlyConst("PSUB"), e.message, "实际=${e.message}")
        // 报错前的写回不得残留
        assertEquals(0L, store.userVar("OUT", "PSUB")!!.readInt(0, 0), "非 const 位不得写回")
        assertEquals(0L, store.userVar("OUT", "PSUB")!!.readInt(0, 1))
    }

    // ------------------------------------------------------------------
    // 2. 省略位：`@PSUB2(OUT:1, OUT:X+1)` + `CALL PSUB2(7)` —— 省略通道同样不得静默
    // ------------------------------------------------------------------

    @Test
    fun `省略位的非 const 形参下标同样必须报错`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL PSUB2(7)", "RETURN")),
            // 同上：`@PSUB2(OUT:1, OUT:X+1)` 的非法位在解析期即拒，运行期用直构原文覆盖。
            Fn("PSUB2", arrayOf("@PSUB2(OUT:1, OUT)", "RETURN")),
            paramRefsOverride = mapOf("PSUB2" to listOf("OUT:1", "OUT:X+1")),
        )
        val store = outStore("PSUB2")
        val e = assertFailsWith<CodeEE>("省略位的非 const 下标必须报错（旧形态 return true 静默放行）") {
            runtime(pb, store).execute()
        }
        assertEquals(argSubscriptOnlyConst("PSUB2"), e.message, "实际=${e.message}")
    }

    // ------------------------------------------------------------------
    // 3. 回归护栏：数字常量下标照原版写回**该元素**
    // ------------------------------------------------------------------

    @Test
    fun `数字常量的形参下标写回对应元素`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL PSUB(7)", "RETURN")),
            Fn("PSUB", arrayOf("@PSUB(OUT:1)", "RETURN")),
        )
        val store = outStore("PSUB")
        runtime(pb, store).execute()
        val out = store.userVar("OUT", "PSUB")!!
        assertEquals(7L, out.readInt(0, 1), "`@PSUB(OUT:1)` 的第 1 个实参写 OUT[1]（ProcessState.cs:435-452）")
        assertEquals(0L, out.readInt(0, 0), "OUT[0] 不得被写")
        assertEquals(0, store.callDepth(), "帧必须全部退出")
    }

    /** 无下标的形参项（`@F(OUT)`）不受影响：写 OUT[0]。 */
    @Test
    fun `无下标的形参项照旧写元素 0`() {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "CALL PSUB0(5)", "RETURN")),
            Fn("PSUB0", arrayOf("@PSUB0(OUT)", "RETURN")),
        )
        val store = outStore("PSUB0")
        runtime(pb, store).execute()
        assertEquals(5L, store.userVar("OUT", "PSUB0")!!.readInt(0, 0))
        assertEquals(0, store.callDepth())
    }
}
