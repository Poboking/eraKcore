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
 * **用户函数缺省实参默认值**（`@F(A, B = 2, C = "x")`）—— FIRST 素质屏「素质名全空」的回归锁。
 *
 * ## 现场（本测试要锁的产品缺陷）
 *
 * `ERB/ステータス表示関連/PRINT_STATE.ERB:474` 的
 * `LOCALS '= GET_TALENTNAME(LOCAL, TALENT:C_ID:LOCAL)` 只传 2 个实参，而定义是
 * `@GET_TALENTNAME(ARG , ARG:1 , ARG_ALL_GET = 0 , ARG_SW = ",")`
 * （`ERB/ステータス表示関連/PRINT_TALENTNAME.ERB:18`）。省略位拿不到默认值时，
 * `ARG_SW` 停在 `#DIMS ARG_SW` 的 `""`，函数体内的
 * `SPLIT L_TNAME , ARG_SW , L_SPLITSTR`（同文件 :369-375）整串切成 1 段，
 * `FINDELEMENT(...)` 找不到下标 ⇒ `RETURNF ""` ⇒ 素质名全空。
 *
 * ## 原版口径（逐条 `文件:行号`）
 *
 * * 装载期：每个形参项 = `[VariableTerm, SingleTerm 默认值]` 对
 *   （`ErbLoader.cs:646-760`；`label.Def = array2` 在 :752），默认值必须是常量
 *   （`Restructure` 后不是 `SingleTerm` ⇒ `ArgCanOnlyConst`，:764-767），
 *   且类型与形参一致（`:770-773`）。`= ` 后无表达式 = `NullTerm` ⇒ 按形参类型折成
 *   `0` / `""`（`:717-726`）。`FunctionLabelLine.Def` 字段声明在 `FunctionLabelLine.cs:49`。
 * * 调用期：`CalledFunction.ConvertArg` 先把实参表补到 `TopLabel.Arg.Length`（`:137`），
 *   逐位「缺失即取 `Def[i]`」（`:167`），无默认值且 `CompatiFuncArgOptional` 关才报
 *   `CanNotOmitArg`（`:168-172`）；随后 `UserDefinedFunctionArgument.cs:35-69` 在**调用方帧**
 *   按序求值全部实参，`ProcessState.cs:435-452` 逐位写回被调 `ARG`/`ARGS`。
 *
 * ## 本侧的落地形态（为什么不改 `.erbc`）
 *
 * 默认值在**编译期**补进实参表（[ErbProgramCompiler.callDefaults]），字节码里看到的
 * 就是完整实参 —— 容器无需新增段/版本位，运行期 `bindCallArgs` 的
 * `present` 位也因此为真，写回路径与「显式传了该实参」逐字相同。
 *
 * 本文件锁三件事：
 * 1. `@行` 解析出的逐位默认值（[FuncLabelStmt.paramDefaults]）：字面量收下、
 *    非字面量登记为不可注入；
 * 2. 语句位 `CALL` 家族的补位（1/2/3 个实参与显式空位 `,,`）；
 * 3. 表达式位用户方法调用（`#FUNCTIONS` 字符串通道）的补位 —— 即 FIRST 素质屏那条路。
 */
class DefaultArgTest {

    /** 待编译函数：行 = 含 `@行` 的完整切片；名字/arity/形参一律从 `@行` 解析结果取。 */
    private data class Fn(
        val name: String,
        val lines: Array<String>,
        /** `#FUNCTIONS`（str 返回方法）⇒ `returnsStr = true`。 */
        val returnsStr: Boolean = false,
        /** 进表达式位降级表（`METHOD_STR`）—— 表达式里 `NAME(...)` 才会降成 `_CALL`。 */
        val exprMethod: Boolean = false,
    )

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("PA", OperandType.INT, 1)
            .declareVariable("PB", OperandType.INT, 1)
            .declareVariable("PC", OperandType.INT, 1)
            .declareVariable("PY", OperandType.STR, 1)
            .declareVariable("OUT", OperandType.INT, 1)
            .declareVariable("OUTS", OperandType.STR, 1)
            .declareVariable("ARG_ALL_GET", OperandType.INT, 1)
            .declareVariable("ARG_SW", OperandType.STR, 1)
            .declareFunction("GET_TALENTNAME", OperandType.STR),
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
            p.parseLogicalLine(LogicalLine(kind, "defaultarg.erb", i + 1, text))
        }
    }

    /** 编译产物（entry = 第一个函数）+ 函数下标查询。 */
    private class Pb(
        val names: List<String>,
        val program: Program,
        /** 函数名 → 是否 `#FUNCTIONS`（FuncMeta.returnsStr / isMethod 口径）。 */
        val returnsStr: Map<String, Boolean>,
    ) {
        fun index(name: String): Int =
            program.functions.indices.first { program.functions[it].name.equals(name, ignoreCase = true) }
    }

    /**
     * 解析 → 采集默认值 → 编译。
     *
     * 采集口径与装载装置（`RealCorpusCompile` / `GoldenPipeline` / `CorpusCompile`）逐字一致：
     * 默认值来自 `@行` 的 [FuncLabelStmt.paramDefaults]，键 = 函数名大写，
     * 形参名 = `paramRefs`（含 `:n` 下标，逐位绑定用）。
     */
    private fun compile(vararg fns: Fn): Pb {
        val parsed = fns.map { parse(*it.lines) }
        val labels = parsed.map { stmts ->
            stmts.filterIsInstance<FuncLabelStmt>().firstOrNull()
                ?: error("切片缺 @行")
        }
        val collector = CallDefaultsCollector()
        for ((i, label) in labels.withIndex()) {
            collector.add(fns[i].name, label.paramDefaults)
        }
        val strMethods = fns.filter { it.exprMethod }.mapTo(HashSet()) { it.name.uppercase() }
        val exprCalls = if (strMethods.isEmpty()) null else ExprCallTable(strMethods = strMethods)
        val compiler = ErbProgramCompiler(callDefaults = collector.build())
        val known = fns.mapTo(HashSet()) { it.name.uppercase() }
        for ((i, f) in fns.withIndex()) {
            val label = labels[i]
            compiler.compileFunction(
                FuncSig(
                    name = f.name,
                    arity = label.paramNames.size,
                    isMethod = f.returnsStr,
                    returnsStr = f.returnsStr,
                    paramNames = label.paramRefs,
                ),
                parsed[i],
                known,
                exprCalls,
            )
        }
        return Pb(
            fns.map { it.name },
            compiler.build(fns.first().name),
            fns.associate { it.name to it.returnsStr },
        )
    }

    private fun run(pb: Pb, store: VariableStore): ProcessRuntime {
        val metas = pb.names.map { name ->
            val arity = pb.program.functions[pb.index(name)].arity
            val isMethod = pb.returnsStr[name] == true
            FuncMeta(
                index = pb.index(name), key = name,
                isMethod = isMethod,
                argIntCount = arity, argStrCount = if (isMethod) 1 else 0,
                returnsStr = isMethod,
                defIndex = pb.index(name),
            )
        }
        val rt = ProcessRuntime(pb.program, store, FunctionTable(metas), VmConfig())
        rt.execute()
        return rt
    }

    private fun intVar(name: String) = DimSpec(name, false, intArrayOf(1), isPrivate = true)

    private fun strVar(name: String) = DimSpec(name, true, intArrayOf(1), isPrivate = true)

    // ------------------------------------------------------------------
    // 1. 解析面：`@F(A, B = 2, C = "x")` 的逐位默认值
    // ------------------------------------------------------------------

    @Test
    fun `解析形参默认值 - 字面量收下非字面量登记不注入`() {
        val stmt = parse(
            "@F(PA , PB = 2 , PC = C_YELLOW)",
            "RETURN",
        ).filterIsInstance<FuncLabelStmt>().first()
        assertEquals(listOf("PA", "PB", "PC"), stmt.paramNames, "形参名（下标与默认值都要剥掉）")
        assertEquals(3, stmt.paramDefaults.size, "逐位默认值与形参等长")
        assertEquals(null, stmt.paramDefaults[0], "无 `=` 的位 = 无默认值")
        assertEquals(2L, (stmt.paramDefaults[1]!!.expr as? com.eratw.port.core.expr.IntLiteral)?.value)
        assertEquals(null, stmt.paramDefaults[2]!!.expr, "常量名不是字面量 → 不注入（登记 raw）")
        assertEquals("C_YELLOW", stmt.paramDefaults[2]!!.raw.uppercase(), "原文供报告")

        val collector = CallDefaultsCollector()
        collector.add("F", stmt.paramDefaults)
        assertEquals(1, collector.unsupported.size, "非字面量默认值必须登记")
        val table = collector.build()["F"]!!
        assertEquals(3, table.size, "默认值表与形参等长")
        assertEquals(null, table[0], "无默认值的位不补")
        assertEquals(2L, (table[1] as? com.eratw.port.core.expr.IntLiteral)?.value, "可注入位 = 字面量常量")
        assertEquals(null, table[2], "非字面量位不注入（宁可不补也不猜）")
    }

    @Test
    fun `解析形参默认值 - 负数与十六进制`() {
        val stmt = parse(
            "@H(PA = -1 , PB = 0x10 , PY = \"x\")",
            "RETURN",
        ).filterIsInstance<FuncLabelStmt>().first()
        val values = stmt.paramDefaults.map { (it?.expr as? com.eratw.port.core.expr.IntLiteral)?.value }
        assertEquals(listOf(-1L, 16L, null), values, "一元负号/十六进制折叠成整数字面量；字符串走另一支")
        assertEquals("x", (stmt.paramDefaults[2]!!.expr as? com.eratw.port.core.expr.StrLiteral)?.value)
    }

    @Test
    fun `解析形参默认值 - 与形参类型不符时不注入`() {
        // PC 是整型形参、默认值给字符串：原版装载期即 NotMatchTypeArgAndInitialValue
        // （ErbLoader.cs:770-773）；本侧只登记不注入（补进去运行期也会被按位绑定跳过）。
        val stmt = parse("@T(PC = \"x\")", "RETURN").filterIsInstance<FuncLabelStmt>().first()
        assertEquals(null, stmt.paramDefaults.single()!!.expr)
    }

    // ------------------------------------------------------------------
    // 2. 语句位 `CALL`：1 / 2 / 3 个实参 + 显式空位
    // ------------------------------------------------------------------

    /** `@F(PA, PB = 2, PC = 3)` 把 `PA*100+PB*10+PC` 写进私有 `OUT`。 */
    private val fInt = Fn(
        "F",
        arrayOf(
            "@F(PA , PB = 2 , PC = 3)",
            "#DIM OUT",
            "OUT = PA * 100 + PB * 10 + PC",
            "RETURN",
        ),
    )

    private fun runCall(callLine: String): Long {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", callLine, "RETURN")),
            fInt,
        )
        val store = VariableStore()
        for (n in listOf("PA", "PB", "PC", "OUT")) store.declareUserVar(intVar(n), "F")
        run(pb, store)
        assertEquals(0, store.callDepth(), "帧必须全部退出（enter/leave 配对）")
        return store.userVar("OUT", "F")!!.readInt(0, 0)
    }

    @Test
    fun `CALL 省略两个实参 - 被省略位取默认值`() {
        assertEquals(123L, runCall("CALL F(1)"), "PA=1、PB=2、PC=3 全部到位（CalledFunction.cs:167）")
    }

    @Test
    fun `CALL 省略一个实参 - 只有末位取默认值`() {
        assertEquals(153L, runCall("CALL F(1 , 5)"), "PA=1、PB=5、PC=默认 3")
    }

    @Test
    fun `CALL 全给实参 - 默认值不覆盖显式值`() {
        assertEquals(157L, runCall("CALL F(1 , 5 , 7)"), "三位都是显式值")
    }

    @Test
    fun `CALL 显式空位 - 空位取默认值其余位不受影响`() {
        assertEquals(123L, runCall("CALL F(1 , , 3)"), "PB 空位取默认 2（原版 Def[i] 顶替后再按位写回）")
    }

    // ------------------------------------------------------------------
    // 3. 表达式位用户方法调用（`#FUNCTIONS` 字符串通道）= FIRST 素质屏那条路
    // ------------------------------------------------------------------

    /** `@GET_TALENTNAME` 的合成形态：默认 `ARG_SW = ","`，实参不足两位时返回它。 */
    private val fTalentName = Fn(
        "GET_TALENTNAME",
        arrayOf(
            "@GET_TALENTNAME(ARG , ARG:1 , ARG_ALL_GET = 0 , ARG_SW = \",\")",
            "#FUNCTIONS",
            "#DIM ARG_ALL_GET",
            "#DIMS ARG_SW",
            "IF ARG_ALL_GET != 0",
            "\tRETURNF \"BAD\"",
            "ENDIF",
            "RETURNF ARG_SW",
        ),
        returnsStr = true,
        exprMethod = true,
    )

    private fun runTalentName(expr: String): String {
        val pb = compile(
            Fn("MAIN", arrayOf("@MAIN", "OUTS '= $expr", "RETURN")),
            fTalentName,
        )
        val store = VariableStore()
        store.declareUserVar(strVar("OUTS"), "MAIN")
        store.declareUserVar(intVar("ARG_ALL_GET"), "GET_TALENTNAME")
        store.declareUserVar(strVar("ARG_SW"), "GET_TALENTNAME")
        run(pb, store)
        return store.userVar("OUTS", "MAIN")!!.readStr(0, 0)
    }

    @Test
    fun `表达式位方法调用省略实参 - ARG_SW 取默认逗号`() {
        assertEquals(
            ",", runTalentName("GET_TALENTNAME(1 , 2)"),
            "FIRST 素质屏现场：SPLIT 依赖 ARG_SW 的默认值（PRINT_TALENTNAME.ERB:18）",
        )
    }

    @Test
    fun `表达式位方法调用省略末位 - 中间的默认值同样生效`() {
        assertEquals(",", runTalentName("GET_TALENTNAME(1 , 2 , 0)"), "ARG_ALL_GET 显式 0、ARG_SW 省略")
    }

    @Test
    fun `表达式位方法调用全给实参 - 默认值不覆盖显式值`() {
        assertEquals(";", runTalentName("GET_TALENTNAME(1 , 2 , 0 , \";\")"), "第 4 位显式分号")
        assertEquals("BAD", runTalentName("GET_TALENTNAME(1 , 2 , 2)"), "第 3 位显式 2 → 走非 0 分支")
    }
}
