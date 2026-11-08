package com.eratw.port.core.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 玩家报障「自宅点显示能力 / 显示角色列表报 ARG_VARNAME未定义为变量名」的引擎侧回归钉。
 *
 * 语料 `TEMP_CHARA_LIST_BY_DT.ERB:296-333` 的收集循环按 10×12 全槽遍历；空槽 ⇒
 * `TRYCCALLFORM ""` 未命中 ⇒ 进 CATCH 体 ⇒
 * `DT_CELL_SET 表, 行, COLUMN_NAME(空串), GET_INT_BY_NAME_ELEM(chr, "", 0), 1`。
 *
 * 官方 `DataTableCellSetMethod`（`FunctionMethodCreator.cs:2568-2619`）拿的是**未求值的
 * AExpression 列表**：先求 args[0]/args[4]/args[1]/args[2]，只有
 * `dataRow != null && Columns.Contains(列名)` 成立才碰 args[3]。列名为空 ⇒
 * `Contains("")` 为假 ⇒ `return -3`，`GET_INT_BY_NAME_ELEM` 根本不会被求值，
 * 原版不会触发 CASEELSE THROW。
 *
 * 本仓 `dtCellSet`（`MapDataTableMethods.kt:1327`）同样是惰性的，但**语句位内建**
 * 经 `lowerStatementForPool` → `lowerPoolInstruction` → `lowerArgument` 把参数层所有
 * 用户方法 `FuncCall` 降级成前导 `_CALL` + 返回哨兵，第 4 实参在进 `dtCellSet` 之前
 * 就被求值 ⇒ THROW。故这是**引擎侧求值序能力缺陷**，不是语料守卫不对称，也不是
 * 需要空名守卫/语料补丁的问题。
 */
class CharaListDtLazyCellSetTest {

    private val fnGetter = listOf(
        "@GET_INT_BY_NAME_ELEM(ARG = -1 , ARGS:1 = \"\" , ARG:2 = -1)",
        "#FUNCTION",
        "SELECTCASE ARGS:1",
        "CASE \"ABL\"",
        "RETURNF 42",
        "CASEELSE",
        "THROW 未定义为变量名",
        "ENDSELECT",
    )

    private val fnVal = listOf(
        "@VAL()",
        "#FUNCTION",
        "RETURNF 42",
    )

    /** 字符串列用例：钉 `#FUNCTIONS` 走 `_RETS` 通道，不经编译期降级也能在运行期重入取值。 */
    private val fnStrVal = listOf(
        "@STRVAL()",
        "#FUNCTIONS",
        "RETURNF \"HELLO\"",
    )

    private fun parseErbLines(vararg lines: String): List<Statement> {
        val p = LineParser(
            SymbolTable()
                .declareVariable("L_DTNAME", OperandType.STR, 1)
                .declareVariable("L_TEMP", OperandType.INT, 1)
                .declareVariable("L_ELEMENT", OperandType.STR, 1)
                .declareFunction("GET_INT_BY_NAME_ELEM", OperandType.INT)
                .declareFunction("VAL", OperandType.INT)
                .declareFunction("STRVAL", OperandType.STR),
        )
        return lines.mapIndexedNotNull { i, raw ->
            val text = raw.trimStart()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "lazy-cellset-test.erb", i + 1, text))
        }
    }

    private fun build(mainLines: List<String>): Program {
        val c = ErbProgramCompiler()
        val tbl = ExprCallTable(
            intMethods = setOf("GET_INT_BY_NAME_ELEM", "VAL"),
            strMethods = setOf("STRVAL"),
            nonMethodLabels = emptySet(),
        )
        val known = setOf("GET_INT_BY_NAME_ELEM", "VAL", "STRVAL")
        c.compileFunction(
            FuncSig("GET_INT_BY_NAME_ELEM", 3, isMethod = true, paramNames = listOf("ARG", "ARGS:1", "ARG:2")),
            parseErbLines(*fnGetter.toTypedArray()), known, tbl,
        )
        c.compileFunction(
            FuncSig("VAL", 0, isMethod = true, paramNames = emptyList()),
            parseErbLines(*fnVal.toTypedArray()), known, tbl,
        )
        c.compileFunction(
            FuncSig("STRVAL", 0, isMethod = true, returnsStr = true, paramNames = emptyList()),
            parseErbLines(*fnStrVal.toTypedArray()), known, tbl,
        )
        c.compileFunction(
            FuncSig("SYSTEM_TITLE", 0, isMethod = false, paramNames = emptyList()),
            parseErbLines(*mainLines.toTypedArray()), known, tbl,
        )
        return c.build("SYSTEM_TITLE")
    }

    private fun run(mainLines: List<String>): List<String> {
        val program = build(mainLines)
        val store = VariableStore().also {
            it.declareUserVar(DimParser.parse("#DIMS", "L_DTNAME", isPrivate = false))
            it.declareUserVar(DimParser.parse("#DIM", "L_TEMP", isPrivate = false))
            it.declareUserVar(DimParser.parse("#DIMS", "L_ELEMENT, 5", isPrivate = false))
        }
        // 惰性实参在运行期经 isUnloweredUserMethod → exprCallUserMethodRef 重入；
        // 正式产物由 program.meta 的 method-type I/S 带出 isMethod / returnsStr，
        // 手写夹具须自己置位（漏 returnsStr ⇒ invokeUserMethod 走 int 通道，STR 用例报「非字符串结果」）。
        val methodNames = setOf("GET_INT_BY_NAME_ELEM", "VAL", "STRVAL")
        val strMethodNames = setOf("STRVAL")
        val table = FunctionTable(
            program.functions.mapIndexed { i, f ->
                FuncMeta(
                    index = i,
                    key = f.name.uppercase(),
                    isMethod = f.name.uppercase() in methodNames,
                    returnsStr = f.name.uppercase() in strMethodNames,
                    argIntCount = f.arity,
                    argStrCount = f.arity,
                )
            },
        )
        val out = StringBuilder()
        ProcessRuntime(
            program, store, table, VmConfig(maxStepsPerRun = 200_000L),
            printSink = { out.append(it).append('\n') },
        ).execute()
        return out.lines().filter { it.isNotBlank() }
    }

    private fun dtSetup() = listOf(
        "@SYSTEM_TITLE",
        "#DIMS L_DTNAME",
        "#DIM L_TEMP",
        "#DIMS L_ELEMENT , 5",
        "L_DTNAME = \"T\"",
        "DT_CREATE L_DTNAME",
        "DT_COLUMN_ADD L_DTNAME , \"COL\" , \"int64\"",
        "DT_ROW_ADD L_DTNAME , \"COL\" , 0",
        "L_TEMP = RESULT",
    )

    /** 空列名 ⇒ 官方 return -3，第 4 实参（会 THROW 的用户方法）不得被求值。 */
    @Test
    fun 空列名短路_副作用实参不求值() {
        val lines = run(
            dtSetup() + listOf(
                "DT_CELL_SET L_DTNAME , L_TEMP , \"\" , GET_INT_BY_NAME_ELEM(L_TEMP , \"\" , 0) , 1",
                "PRINTFORML R={RESULT}",
                "PRINTL DONE",
            ),
        )
        assertEquals(listOf("R=-3", "DONE"), lines)
    }

    /** 列命中 ⇒ 第 4 实参照常求值（不能把「惰性」做成「永不求值」）。 */
    @Test
    fun 列命中_副作用实参照常求值() {
        val e = assertFailsWith<CodeEE> {
            run(
                dtSetup() + listOf(
                    "DT_CELL_SET L_DTNAME , L_TEMP , \"COL\" , GET_INT_BY_NAME_ELEM(L_TEMP , \"\" , 0) , 1",
                ),
            )
        }
        assertTrue(e.message!!.contains("未定义为变量名"), "实际信息：${e.message}")
    }

    /** 列命中 + 正常用户方法实参 ⇒ 运行期重入通道仍能取值并写入单元格。 */
    @Test
    fun 列命中_用户方法实参照常写入() {
        val lines = run(
            dtSetup() + listOf(
                "DT_CELL_SET L_DTNAME , L_TEMP , \"COL\" , VAL() , 1",
                "PRINTFORML R={RESULT}",
                "PRINTFORML V={DT_CELL_GET(L_DTNAME , 0 , \"COL\")}",
            ),
        )
        assertEquals(listOf("R=1", "V=42"), lines)
    }

    /**
     * 列命中 + **带参**用户方法实参正常返回 ⇒ 钉住运行期重入的参数绑定。
     * 只靠 THROW 路径不够：`ARGS:1` 若没绑上也会保持默认空串、同样落 CASEELSE，
     * 假绿。这里传 "ABL" 要求 RETURNF 42 并写进单元格。
     * `needsExprArgs` 对所有声明 evalRef 的语句位内建生效，绑定通道必须一并成立。
     */
    @Test
    fun 列命中_带参用户方法实参正常写入() {
        val lines = run(
            dtSetup() + listOf(
                "DT_CELL_SET L_DTNAME , L_TEMP , \"COL\" , GET_INT_BY_NAME_ELEM(L_TEMP , \"ABL\" , 0) , 1",
                "PRINTFORML R={RESULT}",
                "PRINTFORML V={DT_CELL_GET(L_DTNAME , 0 , \"COL\")}",
            ),
        )
        assertEquals(listOf("R=1", "V=42"), lines)
    }

    /**
     * 字符串列 + `#FUNCTIONS` 用户方法实参 ⇒ 钉运行期重入的**字符串通道**。
     * 修复前的降级路径由 `lowerFuncCall` 发 `_CALL(STR_RET bit)` + 字符串哨兵；
     * 改成保持 Expr 后必须靠 `exprCallUserMethodRef → invokeUserMethod` 按被调帧声明
     * 取 `_RETS`，且 `dtCellSet` 的 `operandType == STR` 检查要过（否则返回 -2）。
     */
    @Test
    fun 列命中_字符串用户方法实参正常写入() {
        val lines = run(
            dtSetup() + listOf(
                "DT_COLUMN_ADD L_DTNAME , \"SCOL\"",
                "DT_CELL_SET L_DTNAME , L_TEMP , \"SCOL\" , STRVAL() , 1",
                "PRINTFORML R={RESULT}",
                "PRINTFORML L={STRLENS(DT_CELL_GETS(L_DTNAME , 0 , \"SCOL\"))}",
            ),
        )
        assertEquals(listOf("R=1", "L=5"), lines)
    }

    /** 玩家链整体：TRYCCALLFORM 空名 → CATCH → DT_CELL_SET 空列名，不得弹脚本错误。 */
    @Test
    fun 玩家链_空名进CATCH后DT_CELL_SET不炸() {
        val lines = run(
            dtSetup() + listOf(
                "VARSET L_ELEMENT",
                "SPLIT \"\" , \",\" , L_ELEMENT",
                "TRYCCALLFORM %L_ELEMENT:0% , 0",
                "PRINTL TRY-BODY",
                "CATCH",
                "DT_CELL_SET L_DTNAME , L_TEMP , \"\" , GET_INT_BY_NAME_ELEM(0 , L_ELEMENT:0 , TOINT(L_ELEMENT:1)) , 1",
                "PRINTFORML R={RESULT}",
                "ENDCATCH",
                "PRINTL DONE",
            ),
        )
        assertEquals(listOf("R=-3", "DONE"), lines)
    }
}
