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
 * **表达式位用户方法的 REF 形参绑定**（`ProcessRuntime.evalContext.callFunctionRef`）。
 *
 * ## 现场
 *
 * 语料 `ERB/魔改内容/qol/qol_skipmove.ERB:73,150` 的
 * `FOR i, 0, SPLIT_F(P_AdjList:(…),"/",LOCALS)` —— `SPLIT_F` 是用户 `#FUNCTION`
 * （`ERB/魔改内容/Misc.ERB:454`，第三个形参 `#DIMS REF _LIST`），调用点位于**表达式位**。
 * 旧实现只把实参值搬进类型桶，REF 位永远没有目标 ⇒ 被调 `SPLIT` 首次读 `_LIST` 即抛
 * `引用变量 _LIST 未绑定目标（EmptyRefVar）`（真机/uipreview 实测 `@SPLIT_F#458`）。
 *
 * ## 原版口径
 *
 * * 表达式位的用户方法在**求值期**建帧（spec: Process.cs:514-540），实参由
 *   `UserDefinedFunctionArgument.cs:35-69` 处理：REF 位**不求值**，只取
 *   `variableTerm.Identifier.GetArray()`；`IntoFunction` 再逐位
 *   `((ReferenceToken)Arg[i]).SetRef(...)`（ProcessState.cs:439-441）。
 * * `LOCAL`/`LOCALS`/`ARG`/`ARGS` 是 `LocalVariableToken`，`GetArray()` 返回函数帧数组
 *   （spec: LocalVariableToken.cs:17-37），与用户变量一样可作 REF 目标。
 *
 * 本用例锁三件事：私有/全局用户数组、局部系统数组（LOCALS）、裸多维数组（不求值）。
 */
class ExprCallRefBindingTest {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("SRC", OperandType.INT, 1)
            .declareVariable("SRC2D", OperandType.INT, 2)
            .declareVariable("_RM", OperandType.INT, 2)
            .declareVariable("_R", OperandType.INT, 1)
            .declareVariable("_RS", OperandType.STR, 1)
            .declareVariable("PA", OperandType.INT, 1)
            .declareVariable("PB", OperandType.STR, 1)
            .declareVariable("PC", OperandType.INT, 1)
            .declareFunction("W", OperandType.INT)
            .declareFunction("W2", OperandType.INT)
            .declareFunction("WS", OperandType.INT),
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
            p.parseLogicalLine(LogicalLine(kind, "exprref.erb", i + 1, text))
        }
    }

    private val refDecl = Regex("""^#\s*DIMS?\s+.*\bREF\b\s+([^\s,;=]+)""", RegexOption.IGNORE_CASE)

    /** 从被调源码扫 `#DIM(S) REF NAME`，喂给编译器的函数局部表（与 erbcc 装载侧同形）。 */
    private fun layoutOf(methodKey: String, lines: List<String>): DimSpecLayout {
        val refs = lines.mapNotNull { text ->
            val m = refDecl.find(text.trim()) ?: return@mapNotNull null
            LocalDim(m.groupValues[1], isStr = text.trim().startsWith("#DIMS", ignoreCase = true), ref = true)
        }
        return DimSpecLayout(GlobalNameLayout(), mapOf(methodKey.uppercase() to refs))
    }

    private data class Harness(val rt: ProcessRuntime, val store: VariableStore)

    /**
     * 编译 `MAIN`（[mainLines] 含 `@MAIN` 行）+ 一个 `#FUNCTION` 方法（[methodLines] 含
     * `@NAME(...)` 与 `#DIM REF` 行）。表达式位的调用**不喂** [ExprCallTable] ⇒ 留在
     * 运行期重入通道（本用例要测的那条）。
     */
    private fun harness(
        mainLines: List<String>,
        methodName: String,
        methodLines: List<String>,
        params: List<String>,
        mainLocalsSize: Int = 0,
    ): Harness {
        val c = ErbProgramCompiler(layoutOf(methodName, methodLines))
        val known = setOf("MAIN", methodName)
        c.compileFunction(
            FuncSig("MAIN", 0), parse(*mainLines.toTypedArray()), known, ExprCallTable(),
        )
        c.compileFunction(
            FuncSig(methodName, params.size, isMethod = true, paramNames = params),
            parse(*methodLines.toTypedArray()), known, ExprCallTable(),
        )
        val mainIdx = c.functions.first { it.sig.name.equals("MAIN", ignoreCase = true) }.index
        val methodIdx = c.functions.first { it.sig.name.equals(methodName, ignoreCase = true) }.index
        val metas = listOf(
            FuncMeta(index = mainIdx, key = "MAIN", name = "MAIN", localsSize = mainLocalsSize),
            FuncMeta(
                index = methodIdx, key = methodName.uppercase(), name = methodName,
                isMethod = true, argIntCount = params.size, argStrCount = params.size,
            ),
        )
        val store = VariableStore()
        return Harness(
            ProcessRuntime(c.build("MAIN"), store, FunctionTable(metas), VmConfig()),
            store,
        )
    }

    // ------------------------------------------------------------------
    // 1) 用户变量作 REF 实参：别名写回调用方
    // ------------------------------------------------------------------

    @Test
    fun `表达式位用户方法的 REF 形参绑调用方数组`() {
        val h = harness(
            mainLines = listOf("@MAIN", "GLOBAL:0 = W(SRC)"),
            methodName = "W",
            methodLines = listOf("@W(_R)", "#DIM REF _R", "_R:0 = 4242", "RETURNF 1"),
            params = listOf("_R"),
        )
        h.store.declareUserVar(DimSpec("SRC", false, intArrayOf(10)))
        h.store.declareUserVar(
            DimSpec("_R", false, intArrayOf(0), isRef = true, isPrivate = true), "W",
        )

        h.rt.execute()

        assertEquals(0, h.store.callDepth(), "帧必须全部退出")
        assertEquals(
            4242L, h.store.readInt("SRC", longArrayOf(0)),
            "REF 是别名（ReferenceToken.cs:125-128）；旧实现按值传参 ⇒ EmptyRefVar",
        )
        assertEquals(1L, h.store.readInt("GLOBAL", longArrayOf(0)), "方法返回值经 _RET 通道读回")
    }

    // ------------------------------------------------------------------
    // 2) 局部系统数组作 REF 实参（LOCALS）：qol_skipmove 的真实形态
    // ------------------------------------------------------------------

    @Test
    fun `表达式位 REF 实参可以是调用方的 LOCALS`() {
        val h = harness(
            mainLines = listOf("@MAIN", "#LOCALSSIZE 8", "GLOBAL:0 = WS(LOCALS)"),
            methodName = "WS",
            methodLines = listOf("@WS(_RS)", "#DIMS REF _RS", "_RS:0 '= \"abc\"", "RETURNF 1"),
            params = listOf("_RS"),
            mainLocalsSize = 8,
        )
        h.store.declareUserVar(
            DimSpec("_RS", true, intArrayOf(0), isRef = true, isPrivate = true), "WS",
        )

        h.rt.execute()

        h.store.enterFunction("MAIN")
        assertEquals(
            "abc", h.store.readStr("LOCALS", longArrayOf(0)),
            "LocalVariableToken.GetArray() 的数组本体被写回（spec: LocalVariableToken.cs:17-37）",
        )
        h.store.leaveFunction()
    }

    // ------------------------------------------------------------------
    // 3) REF 位不求值：裸多维数组实参不得触发「需要 N 个下标」
    // ------------------------------------------------------------------

    @Test
    fun `表达式位 REF 位不读值 - 裸 2 维数组实参可绑定`() {
        val h = harness(
            mainLines = listOf("@MAIN", "GLOBAL:0 = W2(SRC2D)"),
            methodName = "W2",
            methodLines = listOf("@W2(_RM)", "#DIM REF _RM, 0, 0", "_RM:1:1 = 55", "RETURNF 1"),
            params = listOf("_RM"),
        )
        h.store.declareUserVar(DimSpec("SRC2D", false, intArrayOf(5, 5)))
        h.store.declareUserVar(
            DimSpec("_RM", false, intArrayOf(0, 0), isRef = true, isPrivate = true), "W2",
        )

        h.rt.execute()

        assertEquals(
            55L, h.store.readInt("SRC2D", longArrayOf(1, 1)),
            "原版 REF 位只取 Identifier.GetArray()，不读值（UserDefinedFunctionArgument.cs:35-69）",
        )
    }

    // ------------------------------------------------------------------
    // 5) 系统数组作 REF 实参（TARGET 等会话级数组）
    // ------------------------------------------------------------------

    /**
     * `COMMON.ERB:554` 的 `CALL MAKE_CHARA_LIST("TARGET列表", L_ID, TARGET, TARGET_CNT)`
     * （以及 :2365 `CHARA_LIST`）—— `TARGET` 是系统 `G_INT1` 数组，原版 `VariableToken.GetArray()`
     * 直接交全局列数组（spec: VariableData.cs:2389-2417），REF 绑定正是靠它成立。
     * 旧实现只查用户变量/局部数组，漏掉这一类 ⇒ `@MAKE_CHARA_LIST#11` 的 `VARSET CHARA_LIST`
     * 抛 EmptyRefVar（2026-10-25 睁开眼睛链实测）。
     */
    @Test
    fun `表达式位 REF 实参可以是系统数组`() {
        val h = harness(
            mainLines = listOf("@MAIN", "GLOBAL:0 = W(TARGET)"),
            methodName = "W",
            methodLines = listOf("@W(_R)", "#DIM REF _R", "_R:0 = 7", "RETURNF 1"),
            params = listOf("_R"),
        )
        h.store.declareUserVar(
            DimSpec("_R", false, intArrayOf(0), isRef = true, isPrivate = true), "W",
        )

        h.rt.execute()

        assertEquals(
            7L, h.store.readInt("TARGET", longArrayOf(0)),
            "TARGET 的全局列数组必须被 REF 别名写入（VariableData.cs:2389-2417）",
        )
    }

    // ------------------------------------------------------------------
    // 5b) 同一方法自递归：REF 形参的目标必须随调用链存活（2026-10-29 真机红）
    // ------------------------------------------------------------------

    /**
     * 真机红：`PLAY_GOMOKU.ERB` 的 `GOMOKU_ALPHABETA`（`#FUNCTION` + `#DIM REF BOARD`）体内
     * `VAL = GOMOKU_ALPHABETA(BOARD, DEPTH - 1, …)` 自递归 ⇒「引用类型变量"BOARD"缺少引用对象」。
     *
     * 本用例走**运行期重入**通道（不喂 `ExprCallTable` 的降级表 ⇒ 调用留在
     * `invokeUserMethod`）：同一方法递归时 caller 与 callee 共用一个 `RefUserVar` 实例，
     * 被调帧 `scopeIn()` 先把 target 清空；旧实现到 `applyPendingRefBindings`（进帧之后）
     * 才回读 holder ⇒ 空引用。修法：解析时（`resolveRefSource`）留目标数组与形状快照。
     *
     * 语句位 `_CALL` 的同形红在 `RefArgBindingTest` 的「同一函数递归时 REF 形参仍绑调用方数组」。
     */
    @Test
    fun `自递归方法的 REF 形参不因被调帧 scopeIn 丢目标`() {
        val h = harness(
            mainLines = listOf("@MAIN", "GLOBAL:0 = W(SRC)"),
            methodName = "W",
            methodLines = listOf(
                "@W(_R)",
                "#FUNCTION",
                "#DIM REF _R",
                "_R:0 = _R:0 + 1",
                "SIF _R:0 < 3",
                "\tGLOBAL:0 = W(_R)",
                "RETURNF 1",
            ),
            params = listOf("_R"),
        )
        h.store.declareUserVar(DimSpec("SRC", false, intArrayOf(10)))
        h.store.declareUserVar(
            DimSpec("_R", false, intArrayOf(0), isRef = true, isPrivate = true), "W",
        )

        h.rt.execute()

        assertEquals(0, h.store.callDepth(), "帧必须全部退出")
        assertEquals(
            3L, h.store.readInt("SRC", longArrayOf(0)),
            "三层递归都必须写回调用方那一个数组（SetRef 别名跨递归存活）",
        )
    }

    // ------------------------------------------------------------------
    // 4) 调用点校验：REF 位给非变量实参 = RequireArrayBecauseRefArg
    // ------------------------------------------------------------------

    @Test
    fun `表达式位 REF 位给字面量在调用点报错`() {
        val h = harness(
            mainLines = listOf("@MAIN", "GLOBAL:0 = W(5)"),
            methodName = "W",
            methodLines = listOf("@W(_R)", "#DIM REF _R", "_R:0 = 1", "RETURNF 1"),
            params = listOf("_R"),
        )
        h.store.declareUserVar(
            DimSpec("_R", false, intArrayOf(0), isRef = true, isPrivate = true), "W",
        )

        val e = assertFailsWith<CodeEE> { h.rt.execute() }
        assertEquals(
            "@W的第1个参数必须是是引用形式的数组变量", e.message,
            "文案同语句位 bindCallArgs / CalledFunction.cs:154-158",
        )
    }

    // ------------------------------------------------------------------
    // 6) 重入方法通道的**具名形参**：GETMETH/GETMETHS 的基础
    // ------------------------------------------------------------------

    /**
     * 旧 [ProcessRuntime.invokeUserMethod] 只把实参写进 `ARG`/`ARGS` 类型桶，具名私有形参
     * （`@F角色列表33(ARG, O_DATA, V_NAME)` 的 `O_DATA`）永远为空 ⇒ `GETMETHS` 恒返回 ""
     * ⇒ qol 的 `OOP_NAME2ID` 名图全空 ⇒ `OBJNAME_TO_ID` 返回 -1 ⇒ `CHECK_CHARA`
     * 不明なTYPE（2026-10-26 新游戏链实测）。本用例锁头部形参名的逐位写回。
     */
    @Test
    fun `重入方法按头部形参名绑定具名参数`() {
        val h = harness(
            mainLines = listOf("@MAIN", "GLOBAL:0 = 0"),
            methodName = "W",
            methodLines = listOf(
                "@W(PA, PB, PC)",
                "#DIMS PB",
                "GLOBAL:1 = PA + PC",
                "GLOBAL:2 = STRLENS(PB)",
                "RETURNF 0",
            ),
            params = listOf("PA", "PB", "PC"),
        )
        h.store.declareUserVar(DimSpec("PA", false, intArrayOf(1), isPrivate = true), "W")
        h.store.declareUserVar(DimSpec("PB", true, intArrayOf(1), isPrivate = true), "W")
        h.store.declareUserVar(DimSpec("PC", false, intArrayOf(1), isPrivate = true), "W")

        h.rt.execute()
        h.rt.evalContext.callFunction(
            "W", listOf(com.eratw.port.core.expr.Value.IntV(2), com.eratw.port.core.expr.Value.StrV("xyz"), com.eratw.port.core.expr.Value.IntV(3)),
        )

        assertEquals(5L, h.store.readInt("GLOBAL", longArrayOf(1)), "PA + PC 必须来自具名形参（ProcessState.cs:443-450）")
        assertEquals(3L, h.store.readInt("GLOBAL", longArrayOf(2)), "PB$ 形参必须拿到字符串实参")
    }
}
