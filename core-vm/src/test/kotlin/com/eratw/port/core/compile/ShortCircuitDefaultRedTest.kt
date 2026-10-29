package com.eratw.port.core.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **短路位上的缺省实参补位**（CMP-27）与 **NAND/NOR 右位短路**（CMP-30）。
 *
 * ## CMP-27 现场
 *
 * 缺省补位此前只在编译期降级成功的调用点做（`stageArgs` → `padOmittedArgs`）。落在
 * **惰性求值位**的调用（`&&`/`||` 右侧、三目分支、`\@…?\@` 分支、返回类型未知的方法）
 * 保持 `FuncCall` 原节点、运行期由 `ProcessRuntime.invokeUserMethod` 重入被调帧 ——
 * 省略位不补 ⇒ 被调形参停在既有值/装载期隐式 0。`SIF 1 && F()` 是最小形态。
 *
 * 原版在这里补：默认值顶替发生在**解析期**，与调用是否真被执行无关 ——
 * `CalledFunction.ConvertArg` 先把实参表补到 `TopLabel.Arg.Length`（spec:
 * CalledFunction.cs:137），逐位「缺失即取 `Def[i]`」（`:165-173`）；表达式位由
 * `UserDefinedMethodTerm.Create` 走同一个 `ConvertArg`（spec: UserDefinedMethodTerm.cs:17-20）。
 * 被调侧只对 `Arguments[i] == null` 跳过写回（spec: ProcessState.cs:437），而原版的 null
 * 在解析期就已经被 `Def[i]` 顶替。
 *
 * ## CMP-30 现场
 *
 * 求值器里 `!&`（NAND）/`!|`（NOR）也短路（`Expr.kt:283-290`；C# 出处
 * `OperatorMethodManager.cs:439-446` / `:457-464`），但编译器的短路位集合此前只有
 * AND/OR ⇒ `0 !& F()` 右位的用户调用被前移成无条件 `_CALL`（副作用/RAND 多跑一次）。
 *
 * 本文件锁四条：
 * 1. `SIF 1 && F(7)` 的 F 省略位取显式默认值（红形）；
 * 2. 严格求值位 `SIF F(7)` 本来就走编译期补位（对照，不许回退）；
 * 3. 三目分支 `1 ? F(7) # 0` 同样补位；
 * 4. `0 !& F(7)` 右位**一次都不执行**（CMP-30）。
 */
class ShortCircuitDefaultRedTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("G0", OperandType.INT, 1)
            .declareVariable("G1", OperandType.INT, 1)
            .declareVariable("OUT", OperandType.INT, 1)
            .declareFunction("F", OperandType.INT),
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
            p.parseLogicalLine(LogicalLine(kind, "sigdefault.erb", i + 1, text))
        }
    }

    private class Model(val program: Program, val metas: List<FuncMeta>, val store: VariableStore) {
        fun runtime(): ProcessRuntime = ProcessRuntime(program, store, FunctionTable(metas), VmConfig())
    }

    /**
     * `@F(ARG, B = 5)`：把**第 2 个形参位**（桶位 `ARG:1`）抄进 `G0:0`、调用计数写 `G1:0`，
     * 返回 1（供 SIF/三目条件使用）。用 `ARG:1` 而不是具名 `B` 是为了不依赖
     * `bindCallArgs` 的具名形参路径（桶位绑定对所有通道都生效）。
     */
    private fun fBody(): List<Statement> = parse(
        // 形参 B 直接复用系统变量 B（原版 nameDic 的 SystemVariable）：`#DIM B` 会被
        // CORE-06 拒绝，且本用例只用 ARG:1 的桶位，不需要私有声明。
        "@F(ARG , B = 5)",
        "G1:0 = G1:0 + 1",
        "G0:0 = ARG:1",
        "RETURNF 1",
    )

    /**
     * 编译 `MAIN`（入口）+ 被调 `F`。
     *
     * 缺省值来源与装载装置同口径：`@F(...)` 的 [FuncLabelStmt.paramDefaults] →
     * [CallDefaultsCollector]；`exprCalls` 把 F 标成 `#FUNCTION` 方法（运行期重入通道
     * 的 `isUnloweredUserMethod` 判据 = `FunctionTable` 的 `isMethod`）。
     */
    private fun model(mainLines: List<String>): Model {
        val main = parse(*mainLines.toTypedArray())
        val fStmts = fBody()
        val label = fStmts.filterIsInstance<FuncLabelStmt>().first()
        val defaults = CallDefaultsCollector().apply { add("F", label.paramDefaults) }
        val compiler = ErbProgramCompiler(callDefaults = defaults.build())
        val table = ExprCallTable(intMethods = setOf("F"))
        val known = setOf("MAIN", "F")
        compiler.compileFunction(FuncSig("MAIN", 0), main, known, table)
        // 不喂 paramNames：被调侧走类型桶绑定（ARG:0/ARG:1）—— 编译期 `_CALL` 与运行期重入
        // 两条通道都写这两个桶，本用例才能用同一个 `ARG:1` 读两档的补位结果。
        compiler.compileFunction(
            FuncSig("F", 2, isMethod = true),
            fStmts, known, table,
        )
        val indexOf = { name: String ->
            compiler.functions.first { it.sig.name.equals(name, ignoreCase = true) }.index
        }
        val metas = listOf(
            FuncMeta(index = indexOf("MAIN"), key = "MAIN", name = "MAIN", position = "sigdefault.erb"),
            FuncMeta(
                index = indexOf("F"), key = "F", name = "F", isMethod = true,
                argIntCount = 2, position = "sigdefault.erb",
            ),
        )
        val store = VariableStore().apply {
            declareUserVar(DimSpec("G0", isStr = false, dims = intArrayOf(1)))
            declareUserVar(DimSpec("G1", isStr = false, dims = intArrayOf(1)))
            declareUserVar(DimSpec("OUT", isStr = false, dims = intArrayOf(1)))
        }
        return Model(compiler.build("MAIN"), metas, store)
    }

    private fun Model.run() {
        runtime().execute()
    }

    private fun Model.readInt(name: String): Long = store.readInt(name, longArrayOf(0))

    // ------------------------------------------------------------------
    // 1. 红形：SIF 的 `&&` 右位调用省略位必须取显式默认值
    // ------------------------------------------------------------------

    @Test
    fun `SIF_AND右位的调用省略位取默认值`() {
        val m = model(listOf("SIF 1 && F(7) > 0", "OUT:0 = 1"))
        m.run()
        assertEquals(
            1L, m.readInt("G1"),
            "条件为真 ⇒ 右位调用必须执行一次（前置：本用例先确认调用真的发生）",
        )
        assertEquals(
            5L, m.readInt("G0"),
            "F 的第 2 个形参位省略 ⇒ 必须取 @F 头部的显式默认值 5" +
                "（spec: CalledFunction.cs:137/:165-173，表达式位同路 UserDefinedMethodTerm.cs:17-20）",
        )
        assertEquals(1L, m.readInt("OUT"), "条件为真 ⇒ SIF 之后那条语句执行")
    }

    // ------------------------------------------------------------------
    // 2. 对照：严格求值位（SIF 条件左位）走编译期补位，不许回退
    // ------------------------------------------------------------------

    @Test
    fun `严格位_SIF条件左位的调用省略位取默认值`() {
        val m = model(listOf("SIF F(7) > 0", "OUT:0 = 1"))
        m.run()
        assertEquals(5L, m.readInt("G0"), "严格位由 stageArgs 补位（既有行为）")
        assertEquals(1L, m.readInt("OUT"))
    }

    // ------------------------------------------------------------------
    // 3. 三目分支同样补位
    // ------------------------------------------------------------------

    @Test
    fun `三目分支的调用省略位取默认值`() {
        val m = model(listOf("OUT:0 = 1 ? F(7) # 0"))
        m.run()
        assertEquals(5L, m.readInt("G0"), "三目选中分支上的调用同样要补缺省实参")
        assertEquals(1L, m.readInt("OUT"), "F 返回 1 ⇒ 分支值为 1")
    }

    // ------------------------------------------------------------------
    // 4. CMP-30：`!&` 右位短路（左位 0 时右侧一次都不执行）
    // ------------------------------------------------------------------

    @Test
    fun `NAND左位为0时右位调用不执行`() {
        val m = model(listOf("OUT:0 = 0 !& F(7)"))
        m.run()
        assertEquals(
            0L, m.readInt("G1"),
            "`0 !& x` 恒为 1、右侧不求值（spec: OperatorMethodManager.cs:439-446；Expr.kt:283-290）" +
                "—— 右位调用被前移成无条件 _CALL 会多跑一次",
        )
        assertEquals(1L, m.readInt("OUT"), "NAND(0, 任意) = 1")
    }

    // ------------------------------------------------------------------
    // 5. CMP-30 的另一半：NOR 左位非 0 时右位同样不执行
    // ------------------------------------------------------------------

    @Test
    fun `NOR左位非0时右位调用不执行`() {
        val m = model(listOf("OUT:0 = 1 !| F(7)"))
        m.run()
        assertEquals(
            0L, m.readInt("G1"),
            "`1 !| x` 恒为 0、右侧不求值（spec: OperatorMethodManager.cs:457-464；Expr.kt:289-290）" +
                "—— 旧短路位集合只有 AND/OR 时右位 _CALL 会被前移",
        )
        assertEquals(0L, m.readInt("OUT"), "NOR(1, 任意) = 0")
    }

    @Test
    fun `NAND左位非0时右位调用恰好执行一次`() {
        val m = model(listOf("OUT:0 = 1 !& F(7)"))
        m.run()
        assertEquals(1L, m.readInt("G1"), "`1 !& x` 右侧必须求值一次（不能为了短路而吞掉真实调用）")
        assertEquals(0L, m.readInt("OUT"), "F 返回 1 ⇒ NAND(1, 1) = 0")
    }
}
