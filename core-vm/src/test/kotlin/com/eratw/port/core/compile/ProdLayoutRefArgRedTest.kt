package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarRef
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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **生产布局档的 REF 形参实参回填**（CMP-25，P0）—— `ProdLayoutRefArgRedTest`。
 *
 * ## 现场（真机红、桌面不红）
 *
 * 语料 `BODY_INFO/SHOW_PANTIES.ERB:68` 声明 `#DIM カラーマトリクス, 5, 5`，`:90`
 * `CALL SETCOLORMATRIX(カラーマトリクス, …)`；被调 `:101 #DIM REF カラーマトリクス, 0, 0`
 * 体内 `:126-139` 写 `カラーマトリクス:i:j`。原版 REF 位**不读实参的值**：装载期
 * `ConvertArg` 只要求实参是 `VariableTerm` 且同型同维（spec: CalledFunction.cs:154-159），
 * 求值期直接取 `variableTerm.Identifier.GetArray()`（spec:
 * UserDefinedFunctionArgument.cs:45-58）⇒ 裸多维数组实参合法。
 *
 * 本移植把「REF 位不求值」做成编译期回填：`VariableStore` 读 `VarRef` 时会按声明维度校验下标，
 * 裸 2D 数组实参照常 `_EVAL_INT` 会造 0 长下标 ⇒ 运行期抛
 * 「变量 MAT 需要 2 个下标（实际 0）」（`VariableStore.kt:1601`，同文案 `:1471`），
 * `_CALL` 根本到不了。回填点在 `ErbProgramCompiler.patchRefArgEvals`（`build()` 时执行）。
 *
 * ## 为什么生产 erbcc 路径是死代码
 *
 * `patchRefArgEvals` 只回填**登记过**的站点，而站点登记门是
 * `ErbProgramCompiler.hasRefParams` → `LocalLayout.refParamNamesOf`。生产 `CorpusCompile.kt:411`
 * 传 `GlobalNameLayout()`，它不持声明、`refParamNamesOf` 恒 `null` ⇒ `varArgSites` 恒空、
 * 回填空转。boot/E2E 装置（`RealCorpusCompile.kt:356` / `GoldenPipeline.kt:451`）传的是
 * `DimSpecLayout`，所以桌面 610/610 全绿、真机才炸。
 *
 * ## 本夹具的两档（同源同函数表，只换 layout）
 *
 * * **A 档 = 带 REF 信息的 `DimSpecLayout`**（对照）：`run()` 必须跑通、被调写入对调用方可见。
 * * **B 档 = 生产档布局镜像**（`[productionLayout]`，与 `CorpusCompile.kt:411` 逐字同步）：
 *   `build()` 必须**成功**（缺陷在运行期，所以不能对 `compile()` 用 `assertFailsWith`），
 *   `run()` 必须与 A 档同样跑通。修 CMP-25 前 B 档在这里抛
 *   「变量 MAT 需要 2 个下标（实际 0）」，即本用例的红形。
 *
 * 第三例是根因直读（RECON §2 A3）：`_CALL` 之前那条值指令的 `a` 表达式若仍是
 * `VarRef(name, indices = [])`，运行期必在 ReadInt 的维度校验上炸 —— 这条不依赖异常文案。
 */
class ProdLayoutRefArgRedTest {

    /** 一个待编译函数：名字 + arity + 切片行（含 `@行`/`#行`）+ 头部形参名。 */
    private data class Fn(
        val name: String,
        val arity: Int,
        val lines: Array<String>,
        val params: List<String> = emptyList(),
    )

    /**
     * 两档共用的**同一份源码**（同源）：`MAIN2D` 把 2D 数组 `MAT` 裸传给 REF 形参
     * `MAT_REF`，被调写 `MAT_REF:3:3`（行主序落回调用方 `MAT:3:3`）。
     */
    private fun source(): List<Fn> = listOf(
        Fn(
            "MAIN2D", 1,
            arrayOf("@MAIN2D(MAT)", "CALL MAT_WRITER(MAT)", "RETURN"),
            params = listOf("MAT"),
        ),
        Fn(
            "MAT_WRITER", 1,
            arrayOf("@MAT_WRITER(MAT_REF)", "#DIM REF MAT_REF, 0, 0", "MAT_REF:3:3 = 4242", "RETURN"),
            params = listOf("MAT_REF"),
        ),
    )

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("MAT", OperandType.INT, 2)
            .declareVariable("MAT_REF", OperandType.INT, 2),
    )

    private fun parse(f: Fn): List<Statement> {
        val p = parser()
        return f.lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "prodref.erb", i + 1, text))
        }
    }

    /**
     * 从测试源行里扫 `#DIM REF NAME, …`，折成 [DimSpecLayout] 的函数局部表 ——
     * 与生产侧 `erbcc` 的 `LabelDef.dimSpecs → com.erbcc.bridge.buildFuncDims` 同口径
     * （`LocalDim.ref = true`）。
     */
    private fun refDimsOf(fns: List<Fn>): Map<String, List<LocalDim>> {
        val decl = Regex("""^#\s*DIMS?\s+.*\bREF\b\s+([^\s,;=]+)""", RegexOption.IGNORE_CASE)
        val out = HashMap<String, List<LocalDim>>()
        for (f in fns) {
            val refs = f.lines.mapNotNull { text ->
                val m = decl.find(text.trim()) ?: return@mapNotNull null
                LocalDim(m.groupValues[1], isStr = false, ref = true)
            }
            if (refs.isNotEmpty()) out[f.name.uppercase()] = refs
        }
        return out
    }

    /** A 档：显式喂 REF 声明（对照档，证明编译器侧回填路径本身可用）。 */
    private fun deviceLayout(fns: List<Fn>): LocalLayout =
        DimSpecLayout(GlobalNameLayout(), refDimsOf(fns))

    /**
     * **生产档布局镜像**：必须与 `tools/erbcc/…/compile/CorpusCompile.kt:411` 的实参逐字同步。
     *
     * 修 CMP-25 前那一行是 `ErbProgramCompiler(GlobalNameLayout(), …)`（编译器拿不到 REF
     * 形参信息）⇒ 本函数当时返回 [GlobalNameLayout]；现行生产形态是
     * `DimSpecLayout(GlobalNameLayout(), buildFuncDims(allDefs, consts))`，本函数同步成
     * [DimSpecLayout]。两处不同步就是本用例要抓的回归（编译器只读 `refParamNamesOf`，
     * 故这里只给 REF 项即可与生产等价）。
     */
    private fun productionLayout(fns: List<Fn>): LocalLayout =
        DimSpecLayout(GlobalNameLayout(), refDimsOf(fns))

    /** 编译产物 + 函数下标查询（`ErbProgramCompiler.functions` 顺序 == `Program.functions`）。 */
    private class Pb(val compiler: ErbProgramCompiler, val program: Program, val names: List<String>) {
        fun index(name: String): Int = compiler.functions.first { it.sig.name == name }.index
    }

    private fun compileWith(layout: LocalLayout, fns: List<Fn> = source()): Pb {
        val c = ErbProgramCompiler(layout)
        val names = fns.map { it.name }.toSet()
        for (f in fns) {
            c.compileFunction(FuncSig(f.name, f.arity, paramNames = f.params), parse(f), names)
        }
        return Pb(c, c.build(fns.first().name), fns.map { it.name })
    }

    private fun newStore(): VariableStore {
        val store = VariableStore()
        // 调用方：5×5 整型数组（行主序压平存储）
        store.declareUserVar(DimSpec("MAT", false, intArrayOf(5, 5), isPrivate = true), "MAIN2D")
        // 被调：REF 形参（绑定槽，不占帧槽；目标形状随实参传入）
        store.declareUserVar(
            DimSpec("MAT_REF", false, intArrayOf(0, 0), isRef = true, isPrivate = true),
            "MAT_WRITER",
        )
        return store
    }

    private fun run(pb: Pb, store: VariableStore) {
        val metas = pb.names.map { name ->
            val arity = pb.program.functions[pb.index(name)].arity
            FuncMeta(index = pb.index(name), key = name, argIntCount = arity, defIndex = pb.index(name))
        }
        ProcessRuntime(pb.program, store, FunctionTable(metas), VmConfig()).execute()
    }

    /** `_CALL` 之前最近的一条值指令（回填点）；找不到返回 -1。 */
    private fun evalPcBeforeCall(pb: Pb): Pair<Int, Int> {
        val callPc = (0 until pb.program.instructionCount).first { pb.program.op(it) == Opcode._CALL }
        var pc = callPc - 1
        while (pc >= 0) {
            val op = pb.program.op(pc)
            if (op == Opcode._EVAL_INT || op == Opcode._SET_INT) return callPc to pc
            pc--
        }
        return callPc to -1
    }

    // ------------------------------------------------------------------
    // 1. 对照档：带 REF 信息的布局 ⇒ 2D 裸数组实参跑通
    // ------------------------------------------------------------------

    @Test
    fun `A档_DimSpecLayout_2D_REF_裸数组实参跑通`() {
        val pb = compileWith(deviceLayout(source()))
        val store = newStore()
        run(pb, store)
        assertEquals(0, store.callDepth(), "帧必须全部退出（enter/leave 配对）")
        store.enterFunction("MAIN2D")
        // 行主序：:3:3 → 3*5+3 = 18；别的格子必须不受影响
        assertEquals(4242L, store.readInt("MAT", longArrayOf(3, 3)), "REF 是数组本体别名（ReferenceToken.cs:125-128）")
        assertEquals(0L, store.readInt("MAT", longArrayOf(0, 3)))
        assertEquals(5, store.getLength("MAT", 1), "形状随调用方数组走")
        store.leaveFunction()
    }

    // ------------------------------------------------------------------
    // 2. 生产档（镜像 CorpusCompile.kt:411）：build() 成功、run() 必须跑通
    // ------------------------------------------------------------------

    @Test
    fun `B档_生产档布局_2D_REF_裸数组实参跑通`() {
        val pb = compileWith(productionLayout(source()))
        // 缺陷在运行期：编译**必须**先成功（不能对 compile 用 assertFailsWith）
        assertTrue(pb.program.instructionCount > 0, "build() 必须先成功（抛点在运行期）")
        val callPc = (0 until pb.program.instructionCount).first { pb.program.op(it) == Opcode._CALL }
        assertEquals("MAT", pb.program.callArgsAt(callPc)?.single()?.name, "裸变量实参必须登记进 callArgs")
        val store = newStore()
        try {
            run(pb, store)
        } catch (e: CodeEE) {
            fail(
                "生产档布局（镜像 CorpusCompile.kt:411）下 2D REF 调用在运行期抛错：" +
                    "${e.message}；期望与 A 档一样跑通（build() 已成功，抛点在 VariableStore 的下标维度校验）",
            )
        }
        store.enterFunction("MAIN2D")
        assertEquals(4242L, store.readInt("MAT", longArrayOf(3, 3)))
        store.leaveFunction()
    }

    // ------------------------------------------------------------------
    // 3. 根因直读：REF 位的值指令不得是空下标 VarRef（不依赖异常文案）
    // ------------------------------------------------------------------

    @Test
    fun `B档_根因_REF位的值指令不得是空下标VarRef`() {
        val pb = compileWith(productionLayout(source()))
        val (callPc, evalPc) = evalPcBeforeCall(pb)
        assertTrue(evalPc >= 0, "_CALL(pc=$callPc) 之前找不到值指令")
        val e = pb.program.expr(pb.program.a(evalPc))
        val bad = e as? VarRef
        assertTrue(
            bad == null || bad.indices.isNotEmpty(),
            "REF 位的实参值指令仍是 VarRef(${bad?.name}, indices=[]) —— build() 成功但运行期会在 " +
                "VariableStore 抛「变量 ${bad?.name} 需要 N 个下标（实际 0）」；" +
                "回填（patchRefArgEvals）没有生效",
        )
    }
}
