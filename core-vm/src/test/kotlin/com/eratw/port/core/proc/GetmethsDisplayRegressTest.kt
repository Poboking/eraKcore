package com.eratw.port.core.proc

import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
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
 * 玩家屏「山道·通往沼泽之道的设下陷阱，选项名变成地名」的引擎侧取证夹具。
 *
 * 语料链：`ERB/魔改内容/qol/QOL_USERCOM.ERB:62`
 * `TEMP_COM_DISPLAY_NAME '= GETMETHS(@"COM{LOCAL}_DISPLAY", "Null")`，
 * `COM448_DISPLAY`（`COMF448 罠を仕掛ける.ERB:4-18`）是 #FUNCTIONS，条件全不命中时**掉尾**、无 RETURNF。
 * 原版 `GetMethsMethod.GetStrValue`（FunctionMethodCreator.cs:8575-8614）此时应得空串，
 * 菜单才会回落 `L_OPTION_NAME` / `TRAINNAME_TR(448)`。本类把这条链上每个可能的串值残留点拆开钉。
 */
class GetmethsDisplayRegressTest {

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("C0", OperandType.STR, 1)
            .declareVariable("C1", OperandType.STR, 1)
            .declareVariable("TEMP_COM_DISPLAY_NAME", OperandType.STR, 1)
            .declareFunction("PLACE_DISPLAY", OperandType.STR)
            .declareFunction("COM448_DISPLAY", OperandType.STR)
            .declareFunction("INNER_OUTER", OperandType.STR)
            .declareFunction("EXPLICIT_RET", OperandType.STR)
            .declareFunction("EMPTY_RET", OperandType.STR),
    )

    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexedNotNull { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "display.erb", i + 1, text))
        }
    }

    private fun run(main: List<String>): VariableStore {
        val c = ErbProgramCompiler()
        val known = setOf("MAIN", "PLACE_DISPLAY", "COM448_DISPLAY", "INNER_OUTER", "EXPLICIT_RET", "EMPTY_RET")
        // 生产链（erbcc 桥 buildExprCallTable）会把表达式位的 #FUNCTIONS 降级成 _CALL；
        // 不带这张表的夹具会走运行期重入通道，照不到 _RETS 的深度语义。
        val exprCalls = ExprCallTable(
            strMethods = setOf("PLACE_DISPLAY", "COM448_DISPLAY", "INNER_OUTER", "EXPLICIT_RET", "EMPTY_RET"),
        )
        c.compileFunction(FuncSig("MAIN", 0), parse(*main.toTypedArray()), known, exprCalls)
        c.compileFunction(
            FuncSig("PLACE_DISPLAY", 0, isMethod = true, returnsStr = true),
            parse("RETURNF \"山道·通往沼泽之道\""), known, exprCalls,
        )
        // 与语料 COM448_DISPLAY 同形：IF 链全不命中 → 掉尾、无任何 RETURNF。
        c.compileFunction(
            FuncSig("COM448_DISPLAY", 0, isMethod = true, returnsStr = true),
            parse("IF 0", "RETURNF \"不应命中\"", "ENDIF"), known, exprCalls,
        )
        // 与语料 TRAINNAME_TR:160-161 / NAME_TR:191-192 同形：裸 RETURNF。
        c.compileFunction(
            FuncSig("EMPTY_RET", 0, isMethod = true, returnsStr = true),
            parse("SIF 1", "RETURNF"), known, exprCalls,
        )
        // 掉尾方法体内**调用过另一个 #FUNCTIONS**（语料里 COM464_DISPLAY 调 GET_PLACENAME、
        // NAME_TR 调 SQL/REPLACE 都是这个形状）：内层 _RETS 落在 depth>0 的帧里。
        c.compileFunction(
            FuncSig("INNER_OUTER", 0, isMethod = true, returnsStr = true),
            parse("C0:0 '= PLACE_DISPLAY()", "IF 0", "RETURNF \"不应命中\"", "ENDIF"), known, exprCalls,
        )
        // 反向锁：内层调用过别的 #FUNCTIONS，但外层**有**自己的 RETURNF 时，
        // 顶层 _RETS 必须盖过内层串（修 depth 语义不得把显式返回值也一起丢掉）。
        c.compileFunction(
            FuncSig("EXPLICIT_RET", 0, isMethod = true, returnsStr = true),
            parse("C0:0 '= PLACE_DISPLAY()", "RETURNF \"设下陷阱\""), known, exprCalls,
        )
        val idx = c.functions.associate { it.sig.name.uppercase() to it.index }
        val metas = listOf(
            FuncMeta(index = idx.getValue("MAIN"), key = "MAIN", name = "MAIN"),
            FuncMeta(index = idx.getValue("PLACE_DISPLAY"), key = "PLACE_DISPLAY", name = "PLACE_DISPLAY", isMethod = true, returnsStr = true),
            FuncMeta(index = idx.getValue("COM448_DISPLAY"), key = "COM448_DISPLAY", name = "COM448_DISPLAY", isMethod = true, returnsStr = true),
            FuncMeta(index = idx.getValue("EMPTY_RET"), key = "EMPTY_RET", name = "EMPTY_RET", isMethod = true, returnsStr = true),
            FuncMeta(index = idx.getValue("INNER_OUTER"), key = "INNER_OUTER", name = "INNER_OUTER", isMethod = true, returnsStr = true),
            FuncMeta(index = idx.getValue("EXPLICIT_RET"), key = "EXPLICIT_RET", name = "EXPLICIT_RET", isMethod = true, returnsStr = true),
        )
        val store = VariableStore().apply {
            declareUserVar(DimSpec("C0", isStr = true, dims = intArrayOf(1)))
            declareUserVar(DimSpec("C1", isStr = true, dims = intArrayOf(1)))
            declareUserVar(DimSpec("TEMP_COM_DISPLAY_NAME", isStr = true, dims = intArrayOf(1)))
        }
        ProcessRuntime(c.build("MAIN"), store, FunctionTable(metas), VmConfig()).execute()
        return store
    }

    private fun VariableStore.s(name: String): String = readStr(name, longArrayOf(0))

    @Test
    fun `RESULTS 空赋值必须清空字符串返回槽`() {
        val store = run(
            listOf(
                "RESULTS '= \"山道·通往沼泽之道\"",
                "RESULTS = ",
                "C0:0 '= RESULTS",
            ),
        )
        assertEquals("", store.s("C0"), "QOL_USERCOM.ERB:66/:73 的 `RESULTS = ` 必须把 RESULTS 清空")
    }

    @Test
    fun `VARSET 字符串变量必须清空`() {
        val store = run(
            listOf(
                "TEMP_COM_DISPLAY_NAME '= \"山道·通往沼泽之道\"",
                "VARSET TEMP_COM_DISPLAY_NAME",
                "C0:0 '= TEMP_COM_DISPLAY_NAME",
            ),
        )
        assertEquals("", store.s("C0"), "QOL_USERCOM.ERB:61 的 VARSET 必须把上一轮动态名清空")
    }

    @Test
    fun `GETMETHS 掉尾方法在前一次返回地名后仍得空串`() {
        val store = run(
            listOf(
                "C0:0 '= GETMETHS(\"PLACE_DISPLAY\", \"Null\")",
                "C1:0 '= GETMETHS(\"COM448_DISPLAY\", \"Null\")",
            ),
        )
        assertEquals("山道·通往沼泽之道", store.s("C0"))
        assertEquals("", store.s("C1"), "同一个嵌套 VM 被复用时，掉尾方法的字符串返回不得读到上一次的残留")
    }

    @Test
    fun `GETMETHS 掉尾方法不读 RESULTS 残留`() {
        val store = run(
            listOf(
                "RESULTS '= \"山道·通往沼泽之道\"",
                "C1:0 '= GETMETHS(\"COM448_DISPLAY\", \"Null\")",
            ),
        )
        assertEquals("", store.s("C1"), "掉尾 #FUNCTIONS 的返回值必须走自己的字符串通道，不是全局 RESULTS")
    }

    @Test
    fun `裸 RETURNF 的 #FUNCTIONS 得空串`() {
        val store = run(
            listOf(
                "C0:0 '= GETMETHS(\"PLACE_DISPLAY\", \"Null\")",
                "C1:0 '= GETMETHS(\"EMPTY_RET\", \"Null\")",
            ),
        )
        assertEquals("", store.s("C1"), "TRAINNAME_TR/NAME_TR 的裸 RETURNF 必须得空串")
    }

    @Test
    fun `掉尾方法体内调用过内层 #FUNCTIONS 时不得把内层返回值当成自己的`() {
        val store = run(
            listOf(
                "C1:0 '= GETMETHS(\"INNER_OUTER\", \"Null\")",
            ),
        )
        assertEquals("", store.s("C1"), "内层 _RETS 在 depth>0 不得污染顶层 stringResult，否则掉尾方法会返回内层的地名串")
    }

    @Test
    fun `外层显式 RETURNF 必须盖过内层字符串返回值`() {
        val store = run(
            listOf(
                "C1:0 '= GETMETHS(\"EXPLICIT_RET\", \"Null\")",
            ),
        )
        assertEquals("设下陷阱", store.s("C1"), "顶层 _RETS 仍须写 stringResult；内层串只该进返回槽")
    }
}
