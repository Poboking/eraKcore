package com.eratw.port.core.expr

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
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
 * 红 J（NEWGAME-STEP-SPIN）表达式层的**端到端值链**：`JUEL_DEMAND_RESULT = JUEL_DEMAND_x(ARG:1, ARG:2, ARG:3)`
 * 一路走真 `VariableStore` + 真 `ProcessRuntime`，并且**带形参名**（`paramNames`）编译 —— 即语料
 * 真实走的 `bindCallArgs` 按名绑定（`ARG` / `ARG:n` → `ARG[n]`，spec: `ProcessState.cs:435-452`
 * 的 `Arguments[i]` 按**头部形参位**写入），而非只按类型桶序的退化路径。
 *
 * 打转本体（ERB 目录下 `ステータス計算関連/ABL/ABL.ERB`）：
 * - `:79 FOR LOCAL,0,4` + `:80 $AGAIN` … `:130 GOTO AGAIN`：出体唯一途径是 `能力値不足`(:91) /
 *   `経験不足`(:102) 被置位 → `CONTINUE`(:97/:108)。
 * - 置位要求 `JUEL_DEMAND(...) > 0`（`:89`），而 `CALC_ABL_DEMAND` 对 0 需求是**合法返回 0**
 *   （`ABL_UP_DATA.ERB:2775-2776` 的 `SIF DEMAND == 0 → RETURNF 0`）——
 *   于是值链上任何一环塌成 0，`ABL:(角色):(上げるABL) += 1`（`:125`）之后 `GOTO AGAIN` 永远退不出去。
 *
 * 本测试锁的词项（逐条回 C# 定死）：
 * 1. **赋值右值**（`JUEL_DEMAND_RESULT = ...`，`ABL_UP_DATA.ERB:8-34`）：
 *    `UserDefinedMethodTerm.cs:17-26` 解析期绑成调用节点，求值期 `SuperUserDefinedMethodTerm.cs:15-24`
 *    → `Process.GetValue`（`Process.cs:514-540`）→ 返回 `state.MethodReturnValue`。
 * 2. **`RETURNF` 表达式**（`ABL_UP_DATA.ERB:52 RETURNF CALC_ABL_DEMAND(...)`）：
 *    `ProcessState.cs:463-473` 把表达式求值结果写 `MethodReturnValue`。
 * 3. **`SIF` 条件**（`ABL.ERB:83/85`）：单行 IF 只吞紧邻一条语句；条件里的调用必须真出值。
 * 4. **函数实参**（`ABL_UP_DATA.ERB:50-52` 二次转手）：实参升序求值
 *    （`UserDefinedFunctionArgument.cs:35-69`），`ARG:n` 必须按**头部形参位**绑定。
 * 5. **循环内调用**（`ABL.ERB:79-131`）：调用后返回值不被下一轮覆盖、循环计数不被跨调用清零。
 *
 * 覆盖缺口（本用例存在的原因）：`MethodCallExprLowerTest` 的 `FuncMeta` 不传 `paramNames`，
 * 只走 `bindArgs` 桶序；`FuncArgConvertTest` 的 `ARG:n` 只覆盖**省略位**（写 0）。
 * 语料真实形态（`ARG` 与 `ARG:1`/`ARG:2`/`ARG:3` 同在头部）此前**没有任何用例**。
 */
class JuelDemandChainTest {

    // ------------------------------------------------------------------
    // 夹具（解析与编译骨架同 MethodCallExprLowerTest 同源）
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("G0", OperandType.INT, 1)
            .declareVariable("G1", OperandType.INT, 1)
            .declareVariable("CNT", OperandType.INT, 1)
            .declareVariable("LOCAL", OperandType.INT, 1)
            .declareVariable("ARG", OperandType.INT, 1)
            .declareFunction("F", OperandType.INT)
            .declareFunction("G", OperandType.INT)
            .declareFunction("H", OperandType.INT)
            .declareFunction("J17", OperandType.INT)
            .declareFunction("J17CALC", OperandType.INT)
            .declareFunction("JDEM", OperandType.INT),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "jueldemand.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun callTable() = ExprCallTable(
        setOf("F", "G", "H", "J17", "J17CALC", "JDEM"),
        emptySet(),
        emptySet(),
    )

    /**
     * 一个被调假体。
     *
     * [paramNames] 是**头部形参原文**（含 `:n` 下标），生产路径由
     * `CorpusCompile.compileOne(u.sigKey, u.def, s.stmts, s.params, s.paramRefs)` 传入 `paramRefs`
     * —— `bindCallArgs` 靠它把 `ARG:n` 写到 `ARG[n]`。空 = 该函数走桶序 `bindArgs`。
     *
     * [localSize] 是 `#LOCALSIZE`（`LOCAL` 一维长度）；本用例需要 `LOCAL:0`/`LOCAL:7` 同时可读。
     */
    private data class Callee(
        val name: String,
        val lines: List<String>,
        val arity: Int = 0,
        val argInt: Int = 0,
        val argStr: Int = 0,
        val returnsStr: Boolean = false,
        val paramNames: List<String> = emptyList(),
        val localSize: Int = 0,
    )

    private class Model(val program: Program, val metas: List<FuncMeta>, val store: VariableStore) {
        fun runtime(): ProcessRuntime = ProcessRuntime(program, store, FunctionTable(metas), VmConfig())
    }

    private fun observedStore(): VariableStore = VariableStore().apply {
        declareUserVar(DimSpec("CNT", isStr = false, dims = intArrayOf(1)))
        declareUserVar(DimSpec("G0", isStr = false, dims = intArrayOf(1)))
        declareUserVar(DimSpec("G1", isStr = false, dims = intArrayOf(1)))
    }

    private fun model(main: List<Statement>, vararg callees: Callee): Model {
        val c = ErbProgramCompiler()
        val tbl = callTable()
        val known = buildSet {
            add("MAIN")
            callees.forEach { add(it.name) }
        }
        c.compileFunction(FuncSig("MAIN", 0), main, known, tbl)
        val metas = ArrayList<FuncMeta>()
        // MAIN 也要 LOCAL 一维（循环计数用 LOCAL:1，见 `循环内调用` 用例）
        metas.add(
            FuncMeta(
                index = c.indexOf("MAIN"), key = "MAIN", name = "MAIN",
                localSize = 20, position = "jueldemand.erb",
            ),
        )
        for (f in callees) {
            c.compileFunction(
                FuncSig(
                    f.name, f.arity, isMethod = true, returnsStr = f.returnsStr,
                    paramNames = f.paramNames,
                ),
                parse(*f.lines.toTypedArray()),
                known,
                tbl,
            )
            metas.add(
                FuncMeta(
                    index = c.indexOf(f.name), key = f.name, name = f.name, isMethod = true,
                    argIntCount = f.argInt, argStrCount = f.argStr, returnsStr = f.returnsStr,
                    localSize = f.localSize, position = "jueldemand.erb",
                ),
            )
        }
        return Model(c.build("MAIN"), metas, observedStore())
    }

    private fun ErbProgramCompiler.indexOf(name: String): Int =
        functions.first { it.sig.name.equals(name, ignoreCase = true) }.index

    // ------------------------------------------------------------------
    // 被调假体（形态取自语料，逐行对应）
    // ------------------------------------------------------------------

    /** `F`：计调用次数，返回 `ARG * 2`（`MethodCallExprLowerTest.miBody` 的极简版）。 */
    private val fBody = listOf(
        "CNT:0 = CNT:0 + 1",
        "RETURNF ARG * 2",
    )

    private val f = Callee("F", fBody, arity = 1, argInt = 1, paramNames = listOf("ARG"))
    private val g = Callee(
        "G", listOf("RETURNF ARG + 1"),
        arity = 1, argInt = 1, paramNames = listOf("ARG"),
    )

    /** `H`：`RETURNF` 表达式里嵌套调用（`ABL_UP_DATA.ERB:52` 形态）。 */
    private val h = Callee("H", listOf("RETURNF G(F(3))"))

    /**
     * `ABL_UP_DATA.ERB:113-157 @JUEL_DEMAND_0` 的骨架：`SIF ARG > 0 → RETURNF -1`、
     * `LOCAL:(ARG:1)` 作下标读、末尾 `RETURNF`。
     *
     * 这里刻意做成**只有 ARG:1 正确绑定才读得到 305**：
     * - 正确：`LOCAL:7 = ARG:2 + 300 = 305`，返回 `LOCAL[7] + LOCAL[0] = 305 + 100 = 405`；
     * - `ARG:1` 塌成 0：写 `LOCAL[0]`，返回 `305 + 305 = 610`；
     * - `ARG:2` 塌成 0：写 `LOCAL[7] = 300`，返回 `300 + 100 = 400`。
     *
     * 两种塌陷都让外层 `CALC` 的读数与期望不同，能立刻区分。
     */
    private val j17 = Callee(
        "J17",
        listOf(
            "SIF ARG > 0",
            "RETURNF -1",
            "LOCAL:0 = 100",
            "LOCAL:7 = 200",
            "LOCAL:(ARG:1) = ARG:2 + 300",
            "RETURNF LOCAL:(ARG:1) + LOCAL:0",
        ),
        arity = 3, argInt = 3,
        paramNames = listOf("ARG", "ARG:1", "ARG:2"),
        localSize = 20,
    )

    /** `ABL_UP_DATA.ERB:2768-2777 @CALC_ABL_DEMAND`：0 需求原样返回 0，否则按比率折算（此处 ×2）。 */
    private val calc = Callee(
        "J17CALC",
        listOf(
            "SIF ARG:1 == 0",
            "RETURNF 0",
            "RETURNF ARG:1 * 2",
        ),
        arity = 2, argInt = 2,
        paramNames = listOf("ARG", "ARG:1"),
    )

    /**
     * `ABL_UP_DATA.ERB:2-52 @JUEL_DEMAND` 派发器：分支里
     * `JUEL_DEMAND_RESULT = JUEL_DEMAND_17(ARG:1, ARG:2, ARG:3)`（:8-34 的形态），
     * 随后 `SIF JUEL_DEMAND_RESULT < 0 → RETURNF`（:50）、`RETURNF CALC_ABL_DEMAND(...)`（:52）。
     */
    private val dem = Callee(
        "JDEM",
        listOf(
            "G0:0 = J17(ARG:1, ARG:2, ARG:3)",
            "IF G0:0 < 0",
            "RETURNF G0:0",
            "ENDIF",
            "RETURNF J17CALC(ARG, G0:0)",
        ),
        arity = 4, argInt = 4,
        paramNames = listOf("ARG", "ARG:1", "ARG:2", "ARG:3"),
    )

    // ------------------------------------------------------------------
    // 1) `Y = F(3)`：赋值右值直接消费返回值
    // ------------------------------------------------------------------

    @Test
    fun `赋值右值的调用返回真值且只执行一次`() {
        val m = model(parse("G0:0 = F(3)"), f)
        m.runtime().execute()
        assertEquals(6L, m.store.readInt("G0", longArrayOf(0)), "`X = F(3)` 必须取 F 的返回值（不是 0/旧值）")
        assertEquals(1L, m.store.readInt("CNT", longArrayOf(0)), "实参升序求值只调用一次")
    }

    // ------------------------------------------------------------------
    // 2) `RETURNF G(F(3))`：嵌套调用不得互相覆盖返回槽
    // ------------------------------------------------------------------

    @Test
    fun `RETURNF 里的嵌套调用不被外层覆盖`() {
        val m = model(parse("G0:0 = H()"), h, f, g)
        m.runtime().execute()
        assertEquals(7L, m.store.readInt("G0", longArrayOf(0)), "G(F(3)) = 3*2 + 1")
        assertEquals(1L, m.store.readInt("CNT", longArrayOf(0)), "内层 F 只调用一次")
    }

    // ------------------------------------------------------------------
    // 3) `SIF F(1) == 2`：条件里的调用出值 + 单行 IF 只吞紧邻一条
    // ------------------------------------------------------------------

    @Test
    fun `SIF 条件里的调用命中则执行紧邻一条`() {
        val m = model(parse("G0:0 = 0", "SIF F(1) == 2", "G0:0 = 10"), f)
        m.runtime().execute()
        assertEquals(10L, m.store.readInt("G0", longArrayOf(0)), "F(1) = 2 ⇒ SIF 不跳过下一条（ABL.ERB:83-84）")
        assertEquals(1L, m.store.readInt("CNT", longArrayOf(0)), "SIF 条件里的调用必须真执行")
    }

    @Test
    fun `SIF 条件里的调用不命中则跳过紧邻一条`() {
        val m = model(parse("G0:0 = 0", "SIF F(1) == 3", "G0:0 = 10"), f)
        m.runtime().execute()
        assertEquals(0L, m.store.readInt("G0", longArrayOf(0)), "F(1) = 2 ≠ 3 ⇒ 只跳过紧邻一条")
        assertEquals(1L, m.store.readInt("CNT", longArrayOf(0)), "条件仍求值一次")
    }

    // ------------------------------------------------------------------
    // 4) `ARG > 0 → RETURNF -1` 早退原样穿过派发器
    // ------------------------------------------------------------------

    @Test
    fun `负值早退原样穿过派发器`() {
        val m = model(parse("G1:0 = JDEM(17, 2, 7, 5)"), dem, j17, calc)
        m.runtime().execute()
        assertEquals(-1L, m.store.readInt("G1", longArrayOf(0)), "ARG > 0 ⇒ -1，派发器 `G0:0 < 0 → RETURNF` 原样带回")
    }

    // ------------------------------------------------------------------
    // 5) 派发器按名绑定的 ARG:1/ARG:2 生效（语料真实形态）
    // ------------------------------------------------------------------

    @Test
    fun `派发器链按名绑定 ARG 1 ARG 2 后返 810`() {
        val m = model(parse("G1:0 = JDEM(17, 0, 7, 5)"), dem, j17, calc)
        m.runtime().execute()
        // J17(0, 7, 5) = LOCAL[7](=5+300) + LOCAL[0](=100) = 405；J17CALC(17, 405) = 810
        assertEquals(
            810L, m.store.readInt("G1", longArrayOf(0)),
            "ARG:1 作下标、ARG:2 作值必须按头部形参位绑定（塌成 0 会得 610×2 或 400×2）",
        )
    }

    // ------------------------------------------------------------------
    // 6) 循环内调用后返回值不变、循环计数不被跨调用清零（ABL.ERB:79-131 骨架）
    // ------------------------------------------------------------------

    @Test
    fun `循环内调用返回值不变且计数器不被打断`() {
        val m = model(
            parse(
                "CNT:0 = 0",
                "FOR LOCAL:1, 0, 3",
                "G0:0 = F(3)",
                "NEXT",
            ),
            f,
        )
        m.runtime().execute()
        assertEquals(6L, m.store.readInt("G0", longArrayOf(0)), "末轮返回值必须仍是 F(3) 的值")
        assertEquals(3L, m.store.readInt("CNT", longArrayOf(0)), "FOR 计数必须走到 3（内层调用不得重置外层循环状态）")
    }
}
