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
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * TRYC 动态名**空名**回归钉（玩家报障链的最小复现，2026-10-30；ENGFIX-2 重钉，2026-11-05）。
 *
 * 语料 TEMP_CHARA_LIST_BY_DT.ERB:296-333 的收集循环按 10×12 全槽遍历 COLUMN_VAR，
 * 未填槽是空串 ⇒ SPLIT 出空名 ⇒ TRYCCALLFORM "" ⇒（未命中）进 CATCH 体 ⇒
 * GET_INT_BY_NAME_ELEM(chr,"",0) 落 CASEELSE THROW —— 即
 * 「ARG_VARNAME未定义为变量名 ARG_VARNAME=」的致命错误报告。
 *
 * **ENGFIX-2 拆掉了 521c42c3 自造的空名守卫**（名字求值成空串时整块跳过）：
 * oracle 里动态名只经 `FunctionIdentifier.cs:3574-3575` 的字符串解析与
 * `CalledFunction.cs:93-105` 的查表，两处对空串均无特判 ⇒ 空名与普通未命中同形，
 * 未命中跳 CATCH 体首（`FunctionIdentifier.cs:3577-3588`；装载期
 * `ErbLoader.cs:1339-1341` 只按 FunctionCode 判配对，不看名字是否为空）。
 * 本套件改钉四条判据：空名未命中也进 CATCH 体（守卫不得复活）、已填槽未命中
 * 照常取值、命中路径不受影响、非空名未命中进 CATCH 体。
 *
 * **语料链后果（ENGFIX-5 已取证，2026-11-05）**：官方语义下，10×12 全空槽到达本循环时，
 * `GET_INT_BY_NAME_ELEM("")` 的 CASEELSE THROW 会随守卫拆除而复活。4.981 真语料探针
 * [CharaListDtCorpusProbeTest] 实读 `LIST_BY_DT_COLUMN_INIT` 后的 COLUMN_VAR：group 6-9
 * 整组为空（≥48 空槽），收集循环按 `VARSIZE` 全槽遍历且无前置守卫，空槽的
 * `LIST_DT_CELL_DISABLE(",")` 落 CASEELSE 0 ⇒ 空名确实会走到体内 THROW。该路径只在
 * SAVEDATA `高级列表功能` 打开时进入（`能力表示.ERB:926` 的门，该键缺省 0），故它是
 * 语料自身缺陷（初始化/收集两遍守卫不对称），根因在上游表口径，不在引擎加第二层守卫；
 * 登记见 `specs/verify-notes.md` §31。
 *
 * **上述「语料自身缺陷」结论已于 2026-11-06 翻案（见 §31 末尾追记）**：本套件缩样把
 * CATCH 体简写成直接赋值 `L_LASTINT = GET_INT_BY_NAME_ELEM(...)`，漏了真语料外面那层
 * `DT_CELL_SET`。官方 `DataTableCellSetMethod` 只在行/列命中后才求值第 4 实参，
 * 空列名 ⇒ `return -3` ⇒ 根本不调用 `GET_INT_BY_NAME_ELEM`。真缺陷在移植侧语句位内建
 * 的实参被编译期提前降级。**本套件四条「守卫不得复活」判据依然有效**（拆守卫后空名
 * 确实进 CATCH 体）；玩家链的求值时机由 `CharaListDtLazyCellSetTest` 单独钉。
 */
class CharaListDtEmptyGuardTest {

    private val fnGetter = listOf(
        "@GET_INT_BY_NAME_ELEM(ARG = -1 , ARGS:1 = \"\" , ARG:2 = -1)",
        "#FUNCTION",
        "SELECTCASE ARGS:1",
        "CASE \"ABL\"",
        "RETURNF 42",
        "CASE \"BASE\"",
        "RETURNF 43",
        "CASEELSE",
        "THROW 未定义为变量名",
        "ENDSELECT",
    )

    private val fnDisable = listOf(
        "@LIST_DT_CELL_DISABLE(ARGS:0 , ARG:1 = -1)",
        "#FUNCTION",
        "SELECTCASE ARGS:0",
        "CASE \"CFLAG,340\"",
        "RETURNF 0",
        "CASEELSE",
        "RETURNF 0",
        "ENDSELECT",
    )

    /** 直接调用目标（非 #FUNCTION，可含 PRINTL）：验证命中路径不受守卫影响。 */
    private val fnShowCell = listOf(
        "@SHOW_CELL(ARG = 0)",
        "PRINTL HIT-BODY",
    )

    /** 语料收集循环的机制缩样：2×3 槽位，槽内容由用例自填。 */
    private fun main(slot00: String) = listOf(
        "@SYSTEM_TITLE",
        "#DIMS L_ELEMENT , 5",
        "#DIM CNT_0",
        "#DIM CNT_1",
        "#DIM L_LASTINT",
        "COLUMN_VAR:0:0 '= \"$slot00\"",
        "COLUMN_NAME:0:0 '= \"ＡＢＬ\"",
        "FOR CNT_0 , 0 , 2",
        "FOR CNT_1 , 0 , 3",
        "VARSET L_ELEMENT",
        "SPLIT COLUMN_VAR:CNT_0:CNT_1 , \",\" , L_ELEMENT",
        "IF LIST_DT_CELL_DISABLE(L_ELEMENT:0 + \",\" + L_ELEMENT:1 , 0)",
        "PRINTL DISABLED",
        "ELSE",
        "TRYCCALLFORM %L_ELEMENT:0% , 0",
        "PRINTL TRY-BODY",
        "CATCH",
        "IF TOUPPER(L_ELEMENT:2) == \"STR\"",
        "PRINTL STR-COL",
        "ELSE",
        "L_LASTINT = GET_INT_BY_NAME_ELEM(0 , L_ELEMENT:0 , TOINT(L_ELEMENT:1))",
        "PRINTFORML GOT {L_LASTINT}",
        "ENDIF",
        "ENDCATCH",
        "ENDIF",
        "NEXT",
        "NEXT",
        "PRINTL LOOP-END",
    )

    private fun parseErbLines(vararg lines: String): List<com.eratw.port.core.stmt.Statement> {
        val p = LineParser(
            SymbolTable()
                .declareVariable("L_LASTINT", OperandType.INT, 1)
                .declareVariable("CNT_0", OperandType.INT, 1)
                .declareVariable("CNT_1", OperandType.INT, 1)
                .declareVariable("L_ELEMENT", OperandType.STR, 1)
                .declareVariable("COLUMN_VAR", OperandType.STR, 2)
                .declareVariable("COLUMN_NAME", OperandType.STR, 2)
                .declareFunction("LIST_DT_CELL_DISABLE", OperandType.INT)
                .declareFunction("GET_INT_BY_NAME_ELEM", OperandType.INT),
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
            p.parseLogicalLine(LogicalLine(kind, "guard-test.erb", i + 1, text))
        }
    }

    private fun build(mainLines: List<String>): Program {
        val c = ErbProgramCompiler()
        val tbl = ExprCallTable(
            intMethods = setOf("LIST_DT_CELL_DISABLE", "GET_INT_BY_NAME_ELEM"),
            strMethods = emptySet(),
            nonMethodLabels = emptySet(),
        )
        val known = setOf("LIST_DT_CELL_DISABLE", "GET_INT_BY_NAME_ELEM", "SHOW_CELL")
        c.compileFunction(FuncSig("LIST_DT_CELL_DISABLE", 2, isMethod = true, paramNames = listOf("ARGS:0", "ARG:1")), parseErbLines(*fnDisable.toTypedArray()), known, tbl)
        c.compileFunction(FuncSig("GET_INT_BY_NAME_ELEM", 3, isMethod = true, paramNames = listOf("ARG", "ARGS:1", "ARG:2")), parseErbLines(*fnGetter.toTypedArray()), known, tbl)
        c.compileFunction(FuncSig("SHOW_CELL", 1, isMethod = false, paramNames = listOf("ARG")), parseErbLines(*fnShowCell.toTypedArray()), known, tbl)
        c.compileFunction(FuncSig("SYSTEM_TITLE", 0, isMethod = false, paramNames = emptyList()), parseErbLines(*mainLines.toTypedArray()), known, tbl)
        return c.build("SYSTEM_TITLE")
    }

    private fun runProgram(mainLines: List<String>): Pair<List<String>, CodeEE?> {
        val program = build(mainLines)
        val store = VariableStore().also {
            it.declareUserVar(DimParser.parse("#DIM", "CNT_0", isPrivate = false))
            it.declareUserVar(DimParser.parse("#DIM", "CNT_1", isPrivate = false))
            it.declareUserVar(DimParser.parse("#DIM", "L_LASTINT", isPrivate = false))
            it.declareUserVar(DimParser.parse("#DIMS", "L_ELEMENT, 5", isPrivate = false))
            it.declareUserVar(DimParser.parse("#DIMS", "COLUMN_VAR, 2, 3", isPrivate = false))
            it.declareUserVar(DimParser.parse("#DIMS", "COLUMN_NAME, 2, 3", isPrivate = false))
        }
        // ARG:i / ARGS:i 都按**位置 i** 从帧槽绑定（ProcessRuntime.bindArgs:1412-1417），
        // 两通道计数都给 arity 才能让混合签名（ARG/ARGS 混排）的形参各归其位——
        // 只给 argIntCount 会让 ARGS:i 落声明默认值（getter 的 ARGS:1 恒 "" → CASEELSE THROW），
        // 与正式管线从装载器带出的通道计数同效（GoldenPipeline.kt:553 口径）。
        val table = FunctionTable(
            program.functions.mapIndexed { i, f ->
                FuncMeta(index = i, key = f.name.uppercase(), argIntCount = f.arity, argStrCount = f.arity)
            },
        )
        val out = StringBuilder()
        try {
            ProcessRuntime(program, store, table, VmConfig(maxStepsPerRun = 200_000L), printSink = { out.append(it).append('\n') }).execute()
        } catch (e: CodeEE) {
            // 官方语义下空名/未命中进 CATCH 体，CATCH 体里的 THROW 会终止整轮 run。
            // 回收中止前的部分输出供断言，异常本身交给调用方处置。
            return out.lines().filter { it.isNotBlank() } to e
        }
        return out.lines().filter { it.isNotBlank() } to null
    }


    /** 与旧 run 同形：CodeEE 照常向上抛（非空名未命中钉 THROW 用）。 */
    private fun run(mainLines: List<String>): List<String> {
        val (lines, err) = runProgram(mainLines)
        err?.let { throw it }
        return lines
    }

    /** 空名未命中也进 CATCH 体（旧空名守卫已拆）：`GET_INT_BY_NAME_ELEM("")` 落 CASEELSE THROW。 */
    @Test
    fun 空名未命中也进CATCH体_守卫不得复活() {
        val e = assertFailsWith<CodeEE> { run(main("")) }
        // 与非空名未命中（ZZZ → CASEELSE THROW）同文案：空名在 oracle 里就是普通未命中。
        // 旧守卫若复活，这里不会抛（整块跳过、LOOP-END 收尾）。
        assertTrue(e.message!!.contains("未定义为变量名"), "实际信息：${e.message}")
    }


    /** 已填槽未命中照常进 CATCH 体取值：@GET_INT_BY_NAME_ELEM 的 CASE "ABL" → 42。 */
    @Test
    fun 已填槽未命中进CATCH体照常取值() {
        val (lines, err) = runProgram(main("ABL,0"))
        // (0,0)="ABL,0" → 未命中进 CATCH 体 → GOT 42；其余 5 槽全空 → 同样进 CATCH 体，
        // 首个 THROW 终止整轮 run（官方语义 ⇒ err 必为 CodeEE，LOOP-END 不可达）。
        // 断言 GOT 42 首位出现 ⇒ 已填槽路径未被空名支路牵连。
        assertTrue(err is CodeEE, "空槽进 CATCH 体应 THROW（实际: $err）")
        assertEquals("GOT 42", lines.first())
    }

    /** 守卫拆除不误伤命中路径：非空名命中真函数时，被调体与 try 体都照常执行。 */
    @Test
    fun 命中路径不受空名守卫拆除影响() {
        val (lines, err) = runProgram(main("SHOW_CELL,7"))
        // 命中：@SHOW_CELL 体先打印 HIT-BODY，返回后 try 体（TRYCCALLFORM 与 CATCH 之间的
        // 行）照常执行打印 TRY-BODY（spec: FunctionIdentifier.cs:3577-3588 未命中才跳 CATCH）。
        // 其余 5 槽全空 → 官方语义进 CATCH 体 → THROW 终止整轮 run ⇒ LOOP-END 不可达，
        // 断言终止事实而非 LOOP-END（旧守卫口径才出 LOOP-END）。
        assertTrue(err is CodeEE, "空槽进 CATCH 体应 THROW（实际: $err）")
        assertEquals(listOf("HIT-BODY", "TRY-BODY"), lines)
    }

    /** 既有语义锚：非空名未命中仍进 CATCH 体（GET_INT_BY_NAME_ELEM("ZZZ") → CASEELSE THROW）。 */
    @Test
    fun 非空名未命中仍进CATCH体() {
        val e = assertFailsWith<CodeEE> { run(main("ZZZ,0")) }
        assertTrue(e.message!!.contains("未定义为变量名"), "实际信息：${e.message}")
    }
}
