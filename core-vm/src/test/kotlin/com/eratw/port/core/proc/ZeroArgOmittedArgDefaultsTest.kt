package com.eratw.port.core.proc

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.DimSpecLayout
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.GlobalNameLayout
import com.eratw.port.core.compile.LocalDim
import com.eratw.port.core.err.CodeEE
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
import kotlin.test.assertFailsWith

/**
 * **零实参调用点的省略形参默认值**（`ProcessRuntime.onFunctionEnter` → `bindZeroArgImplicitDefaults`）。
 *
 * ## 现场
 *
 * 语料 `ERB/魔改内容/TRANSLATION/DISH_TASTE.ERB:14-25` 的 `@DISHNAME_TR(ARGS)` 用零参缺省实参
 * 写法取全局料理名：
 *
 * ```
 * SIF ARGS == ""
 *     ARGS '= DISH_NAME
 * SIF MAP_HAS(TASTE_MAP, ARGS)
 *     RETURNF MAP_GET(TASTE_MAP, ARGS)
 * ```
 *
 * 老版本 `onFunctionEnter` 对零实参调用点（`Program.declareCallArgs` 不登记空表 ⇒
 * `callArgsAt(pc) == null`）直接放弃绑定，被调 `ARGS` 保留上一次调用的值 ⇒
 * 第二次起 `SIF ARGS == ""` 恒假，所有菜名都显示成第一次的菜名
 * （真机「做菜时所有食谱都变成一样的」）。
 *
 * ## 原版口径
 *
 * `CalledFunction.ConvertArg`（CalledFunction.cs:133-200）对 `srcArgs.Count == 0` 仍逐位判定：
 * 省略位取装载期 `Def[i]`，而 `ErbLoader.cs:714-722` 把 **ARG/ARGS/私有形参**的无显式默认位
 * 折成 `0L` / `""` ⇒ 每次零参调用都会写回默认值；REF 形参省略是硬错误
 * （:150-153 `CanNotOmitRefArg`，先于 `CompatiFuncArgOptional` 分支）。
 */
class ZeroArgOmittedArgDefaultsTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("DISH_NAME", OperandType.STR, 1)
            .declareVariable("TASTE_MAP", OperandType.STR, 1)
            .declareVariable("OUT", OperandType.STR, 1)
            .declareVariable("OUTI", OperandType.INT, 1)
            .declareVariable("_X", OperandType.INT, 1)
            .declareFunction("NAME_TR", OperandType.STR)
            .declareFunction("ECHOS", OperandType.STR)
            .declareFunction("ECHOI", OperandType.INT)
            .declareFunction("R", OperandType.INT),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "zeroarg.erb", i + 1, text))
        }
    }

    private data class Harness(val rt: ProcessRuntime, val store: VariableStore)

    /**
     * `MAIN` + 若干 `#FUNCTION(S)` 方法（走编译期表达式降级：`ExprCallTable` 已给出返回类型）。
     */
    private fun harness(
        mainLines: List<String>,
        methodName: String,
        methodLines: List<String>,
        params: List<String>,
        returnsStr: Boolean,
        mainLocalsSize: Int = 4,
    ): Harness {
        val c = ErbProgramCompiler(DimSpecLayout(GlobalNameLayout(), emptyMap()))
        val known = setOf("MAIN", methodName)
        val table = if (returnsStr) ExprCallTable(strMethods = setOf(methodName))
        else ExprCallTable(intMethods = setOf(methodName))
        c.compileFunction(FuncSig("MAIN", 0), parse(*mainLines.toTypedArray()), known, table)
        c.compileFunction(
            FuncSig(methodName, params.size, isMethod = true, returnsStr = returnsStr, paramNames = params),
            parse(*methodLines.toTypedArray()), known, table,
        )
        val mainIdx = c.functions.first { it.sig.name == "MAIN" }.index
        val methodIdx = c.functions.first { it.sig.name == methodName }.index
        val metas = listOf(
            FuncMeta(index = mainIdx, key = "MAIN", name = "MAIN", localsSize = mainLocalsSize),
            FuncMeta(
                index = methodIdx, key = methodName.uppercase(), name = methodName, isMethod = true,
                returnsStr = returnsStr,
            ),
        )
        val store = VariableStore()
        store.declareUserVar(DimSpec("DISH_NAME", isStr = true, dims = intArrayOf(1)))
        store.declareUserVar(DimSpec("TASTE_MAP", isStr = true, dims = intArrayOf(1)))
        store.declareUserVar(DimSpec("OUT", isStr = true, dims = intArrayOf(8)))
        store.declareUserVar(DimSpec("OUTI", isStr = false, dims = intArrayOf(8)))
        return Harness(ProcessRuntime(c.build("MAIN"), store, FunctionTable(metas), VmConfig()), store)
    }

    /** 语料 DISHNAME_TR 的最小同形：零参缺省实参 + 全局名查表。 */
    @Test
    fun `零参缺省实参每次取最新全局值`() {
        val h = harness(
            mainLines = listOf(
                "@MAIN",
                "#LOCALSIZE 4",
                "#LOCALSSIZE 4",
                "TASTE_MAP '= \"map:TASTE\"",
                "MAP_CREATE \"map:TASTE\"",
                "MAP_SET \"map:TASTE\", \"A\", \"甲\"",
                "MAP_SET \"map:TASTE\", \"B\", \"乙\"",
                "DISH_NAME = A",
                "LOCALS '= NAME_TR()",
                "OUT:0 '= LOCALS",
                "DISH_NAME = B",
                "LOCALS '= NAME_TR()",
                "OUT:1 '= LOCALS",
                "DISH_NAME = A",
                "LOCALS '= NAME_TR()",
                "OUT:2 '= LOCALS",
                "RETURN",
            ),
            methodName = "NAME_TR",
            methodLines = listOf(
                "@NAME_TR(ARGS)",
                "#FUNCTIONS",
                "SIF ARGS == \"\"",
                "\tARGS '= DISH_NAME",
                "SIF MAP_HAS(TASTE_MAP, ARGS)",
                "\tRETURNF MAP_GET(TASTE_MAP, ARGS)",
                "RETURNF ARGS",
            ),
            params = listOf("ARGS"),
            returnsStr = true,
        )
        h.rt.execute()
        h.store.enterFunction("MAIN")
        val got = (0 until 3).map { h.store.readStr("OUT", longArrayOf(it.toLong())) }
        h.store.leaveFunction()
        assertEquals(listOf("甲", "乙", "甲"), got)
    }

    @Test
    fun `零参调用把被调 ARGS 复位为空串`() {
        val h = harness(
            mainLines = listOf(
                "@MAIN",
                "#LOCALSIZE 4",
                "#LOCALSSIZE 4",
                "LOCALS '= ECHOS(\"x\")",
                "OUT:0 '= LOCALS",
                "LOCALS '= ECHOS()",
                "OUT:1 '= LOCALS",
                "RETURN",
            ),
            methodName = "ECHOS",
            methodLines = listOf("@ECHOS(ARGS)", "#FUNCTIONS", "RETURNF ARGS"),
            params = listOf("ARGS"),
            returnsStr = true,
        )
        h.rt.execute()
        h.store.enterFunction("MAIN")
        assertEquals("x", h.store.readStr("OUT", longArrayOf(0)))
        assertEquals("", h.store.readStr("OUT", longArrayOf(1)))
        h.store.leaveFunction()
    }

    @Test
    fun `零参调用把被调 ARG 复位为 0`() {
        val h = harness(
            mainLines = listOf(
                "@MAIN",
                "#LOCALSIZE 4",
                "OUTI:0 = ECHOI(7)",
                "OUTI:1 = ECHOI()",
                "RETURN",
            ),
            methodName = "ECHOI",
            methodLines = listOf("@ECHOI(ARG)", "#FUNCTION", "RETURNF ARG"),
            params = listOf("ARG"),
            returnsStr = false,
        )
        h.rt.execute()
        h.store.enterFunction("MAIN")
        assertEquals(7L, h.store.readInt("OUTI", longArrayOf(0)))
        assertEquals(0L, h.store.readInt("OUTI", longArrayOf(1)))
        h.store.leaveFunction()
    }

    @Test
    fun `零参调用命中 REF 形参按原版报不能省略`() {
        val mainLines = listOf("@MAIN", "#LOCALSIZE 4", "OUTI:0 = R()", "RETURN")
        val methodLines = listOf("@R(_X)", "#DIM REF _X", "RETURNF 1")
        // REF 位信息来自 DimSpecLayout（#DIM REF），与 erbcc 生产布局同源。
        val layout = DimSpecLayout(
            GlobalNameLayout(),
            mapOf("R" to listOf(LocalDim("_X", isStr = false, ref = true))),
        )
        val c = ErbProgramCompiler(layout)
        val table = ExprCallTable(intMethods = setOf("R"))
        c.compileFunction(FuncSig("MAIN", 0), parse(*mainLines.toTypedArray()), setOf("MAIN", "R"), table)
        c.compileFunction(
            FuncSig("R", 1, isMethod = true, paramNames = listOf("_X")),
            parse(*methodLines.toTypedArray()), setOf("MAIN", "R"), table,
        )
        val mainIdx = c.functions.first { it.sig.name == "MAIN" }.index
        val methodIdx = c.functions.first { it.sig.name == "R" }.index
        val metas = listOf(
            FuncMeta(index = mainIdx, key = "MAIN", name = "MAIN", localsSize = 4),
            FuncMeta(index = methodIdx, key = "R", name = "R", isMethod = true),
        )
        val store = VariableStore()
        store.declareUserVar(
            DimSpec("_X", isStr = false, dims = intArrayOf(0), isRef = true, isPrivate = true), "R",
        )
        val rt = ProcessRuntime(c.build("MAIN"), store, FunctionTable(metas), VmConfig())

        val e = assertFailsWith<CodeEE> { rt.execute() }
        assertEquals("@R的第1个参数是引用形式因此无法省略", e.message)
    }
}
