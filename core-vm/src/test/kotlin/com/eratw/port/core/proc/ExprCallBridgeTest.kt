package com.eratw.port.core.proc

import com.erbcc.bridge.buildExprCallTable
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.EXPR_TEMP_PREFIX
import com.eratw.port.core.compile.ExprCallKind
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.exprTempSlotOf
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.fn.BuiltinRegistry
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.sem.SemConfig
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.erbcc.LabelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A16 · 表达式位调用的运行期接线（`ProcessRuntime.evalContext`）。
 *
 * 覆盖三件事：
 * 1. **内建函数不再撞桩**：查 `core/fn` 的 271 注册表并执行；未命中按原版语义抛
 *    （spec: IdentifierDictionary.cs:538-547, 562-580, 607-628）；宿主注入点
 *    （clockMillis/moneyLabel/moneyLabelFirst/saveDataNos/lineIsEmpty）经 `builtinHost`，
 *    缺省由 [SemConfig] 派生（配置不另立第二真相）。
 * 2. **`EXPR_TEMP_PREFIX` 哨兵桥**：编译期降级的用户 `#FUNCTION` 调用，运行期把返回槽
 *    的值读回表达式（A15 约定，见 ErbProgramCompiler.kt:1479-1498）。
 * 3. **erbcc 桥喂表**：[buildExprCallTable] 从 `LabelDef` 分 int/str/普通 @label 三桶。
 *
 * 优先级复刻（spec: IdentifierDictionary.cs:548-586）：用户 `#FUNCTION` **遮蔽**同名内建，
 * 所以"编译期未降级的用户方法"必须在查内建表**之前**被认领，走
 * [ProcessRuntime.invokeUserMethod] 的求值期重入建帧通道（spec: Process.cs:514-540，
 * 路次 i-methexpr 的口径），既不能被内建静默顶掉、也不再报「应由编译期展开为 _CALL」。
 *
 * 反向可证：把 `evalContext.callFunction` 还原成 A15 之前的恒抛桩 ⇒「内建真执行」「宿主
 * 注入」「哨兵端到端」三组全红；把 ②（用户方法优先）挪到注册表之后 ⇒「遮蔽同名内建」
 * 红（会拿到 20）；删掉 erbcc 桥的 INT 分支 ⇒「桥分桶」红。旧用例（ProcessRuntimeTest /
 * StatementBridgeTest / ExprCallLoweringTest）不受影响。
 */
class ExprCallBridgeTest {

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("ARG", OperandType.INT, 1)
            .declareVariable("ARGS", OperandType.STR, 1)
            .declareFunction("M", OperandType.INT)          // #FUNCTION（int 方法）
            .declareFunction("SM", OperandType.STR),        // #FUNCTIONS（str 方法，本遍不降级）
    )

    /** 与 ErbProgramCompiler 测试同源：@ 行由调用方切片，这里只喂函数体。 */
    private fun parse(vararg lines: String): List<Statement> {
        val p = parser()
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "a16.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun index(c: ErbProgramCompiler, name: String): Int =
        c.functions.first { it.sig.name.equals(name, ignoreCase = true) }.index

    private fun meta(c: ErbProgramCompiler, name: String, returnsStr: Boolean = false): FuncMeta {
        val fb = c.functions.first { it.sig.name.equals(name, ignoreCase = true) }
        return FuncMeta(
            index = fb.index, key = fb.sig.name.uppercase(), name = fb.sig.name,
            isMethod = fb.sig.isMethod, argIntCount = fb.sig.arity, returnsStr = returnsStr,
            position = "a16.erb",
        )
    }

    /** 最小运行时：一个空 main 入口 + 追加元数据（内建直测、未降级路径报红测用）。 */
    private fun bareRuntime(
        semConfig: SemConfig = SemConfig(),
        builtinHost: BuiltinHost? = null,
        extraMetas: List<FuncMeta> = emptyList(),
    ): ProcessRuntime {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("MAIN", 0), emptyList())
        val metas = listOf(
            FuncMeta(index = index(c, "MAIN"), key = "MAIN", name = "MAIN"),
        ) + extraMetas
        return ProcessRuntime(
            c.build("MAIN"), VariableStore(), FunctionTable(metas), VmConfig(),
            semConfig = semConfig, builtinHost = builtinHost,
        )
    }

    // ------------------------------------------------------------------
    // 1) 内建函数：命中执行 / 未命中按原版抛
    // ------------------------------------------------------------------

    @Test
    fun `内建 int 与 str 函数经注册表真执行 不再撞桩`() {
        val rt = bareRuntime()
        // GETBIT(0b1010, 1) = 1
        assertEquals(
            Value.IntV(1L),
            rt.evalContext.callFunction("GETBIT", listOf(Value.IntV(0b1010), Value.IntV(1))),
        )
        // BARSTR(3, 4, 20)：str 返回内建（PureMethods.kt:264-272）
        assertEquals(
            Value.StrV("[" + "*".repeat(15) + ".".repeat(5) + "]"),
            rt.evalContext.callFunction("BARSTR", listOf(Value.IntV(3), Value.IntV(4), Value.IntV(20))),
        )
        // STRLENSU（E2E GOLDEN_MISC 的首个卡点）
        assertEquals(Value.IntV(3L), rt.evalContext.callFunction("STRLENSU", listOf(Value.StrV("abc"))))
    }

    @Test
    fun `内建未命中 个数不符 M3 占位 引用参数 各自按既有文案抛`() {
        val rt = bareRuntime()
        // 未命中 → 基类 BuiltinEvalContext.kt:165（原版：IdentifierNotFoundCodeEE，
        // spec: IdentifierDictionary.cs:562-580, 628）
        val miss = assertFailsWith<CodeEE> { rt.evalContext.callFunction("NO_SUCH_FN", emptyList()) }
        assertTrue("未定义的函数 NO_SUCH_FN" in (miss.message ?: ""), miss.message ?: "")
        // 个数检查（spec: FunctionMethod.cs:302-335）
        val arity = assertFailsWith<CodeEE> { rt.evalContext.callFunction("GETBIT", listOf(Value.IntV(1))) }
        assertTrue("参数个数不匹配" in (arity.message ?: ""), arity.message ?: "")
        // 占位机制回归（占位已归零，271 项全真实现）：用本地注册表测占位抛错语义。
        val reg = BuiltinRegistry()
        reg.registerUnimplemented("SCRATCH_UNIMPL_FN", com.eratw.port.core.expr.OperandType.INT, 1, 1)
        val evalCtx = rt.evalContext as com.eratw.port.core.fn.BuiltinEvalContext
        val todo = assertFailsWith<CodeEE> {
            reg["SCRATCH_UNIMPL_FN"]!!.eval!!.invoke(evalCtx, List(1) { Value.IntV(0) })
        }
        assertTrue("尚未实现" in (todo.message ?: ""), todo.message ?: "")
        // 引用参数类内建（GETVAR）：expr 层 RefAware 通道未落地 ⇒ 明确报红，不静默取 0
        val ref = assertFailsWith<CodeEE> { rt.evalContext.callFunction("GETVAR", listOf(Value.IntV(0))) }
        assertTrue("必须是变量引用" in (ref.message ?: ""), ref.message ?: "")
    }

    @Test
    fun `宿主注入点缺省由 SemConfig 派生 且可整体替换`() {
        // SAVENOS 缺省宿主 = 语句层同一份配置（sem/ExecContext.kt:28 saveDataNos）
        assertEquals(
            Value.IntV(7L),
            bareRuntime(semConfig = SemConfig(saveDataNos = 7))
                .evalContext.callFunction("SAVENOS", emptyList()),
        )
        // 显式注入优先（时钟/货币标签/存档数/行状态）
        val host = object : BuiltinHost {
            override val saveDataNos = 3
            override val moneyLabel = "円"
            override val moneyLabelFirst = false
            override fun clockMillis() = 0L
            override val lineIsEmpty = true
        }
        val rt = bareRuntime(builtinHost = host)
        assertEquals(Value.IntV(3L), rt.evalContext.callFunction("SAVENOS", emptyList()))
        assertEquals(Value.IntV(1L), rt.evalContext.callFunction("LINEISEMPTY", emptyList()))
    }

    // ------------------------------------------------------------------
    // 2) 哨兵桥：编译期降级的返回值读回调用方帧槽
    // ------------------------------------------------------------------

    @Test
    fun `表达式位 int 方法降级后哨兵读回返回值`() {
        val c = ErbProgramCompiler()
        val known = setOf("MAIN", "M")
        val table = ExprCallTable(intMethods = setOf("M"))
        c.compileFunction(FuncSig("MAIN", 0), parse("GLOBAL:0 = M(3)"), known, table)
        c.compileFunction(FuncSig("M", 1, isMethod = true), parse("RETURNF ARG * 2"), known, table)
        val metas = listOf(
            FuncMeta(index = index(c, "MAIN"), key = "MAIN", name = "MAIN"),
            meta(c, "M"),
        )
        val store = VariableStore()
        val rt = ProcessRuntime(c.build("MAIN"), store, FunctionTable(metas), VmConfig())
        rt.execute()
        // 3 → 实参升序暂存 R0 → _CALL(c=返回槽) → RETURNF 6 → _RET 写回 → 哨兵读槽 → GLOBAL:0
        assertEquals(6L, store.readInt("GLOBAL", longArrayOf(0)), "哨兵桥必须把 _CALL 的返回槽值读回表达式")
    }

    @Test
    fun `哨兵名解析出槽号且不与 ERB 标识符碰撞`() {
        assertEquals(12, exprTempSlotOf(EXPR_TEMP_PREFIX + "12"))
        assertEquals(null, exprTempSlotOf("SAVENOS"))
        // '#' 不是 ERB 标识符字符 ⇒ 用户/内建名永远不会撞进哨兵分支
        assertTrue('#' !in "SAVENOS")
    }

    // ------------------------------------------------------------------
    // 2b) 未降级的用户方法：求值点重入建帧（原 Process.cs:514-540 的运行期语义）
    // ------------------------------------------------------------------

    /**
     * MAIN + **真实方法体**的运行时。
     *
     * 表达式位的用户方法在原版是「解析期绑节点、求值期建帧」
     * （spec: `Runtime.Script.Statements.Function/UserDefinedMethodTerm.cs:17-26` →
     * `SuperUserDefinedMethodTerm.cs:15-24` → `MinorShift.Emuera.GameProc/Process.cs:514-540`），
     * 本侧同形（[ProcessRuntime.invokeUserMethod]）⇒ 目标函数的指令**必须真的在指令流里**，
     * 不能像 [bareRuntime] 那样只登记 [FuncMeta] 而不编函数体（那会撞 `函数下标越界`）。
     */
    private fun reentryRuntime(
        name: String,
        returnsStr: Boolean,
        body: List<String>,
    ): Pair<ProcessRuntime, VariableStore> {
        val c = ErbProgramCompiler()
        val known = setOf("MAIN", name)
        // 空表 = 调用方没喂 ExprCallTable ⇒ 全部留在惰性位，走重入通道
        val tbl = ExprCallTable()
        c.compileFunction(FuncSig("MAIN", 0), emptyList(), known, tbl)
        c.compileFunction(
            FuncSig(name, 0, isMethod = true, returnsStr = returnsStr),
            parse(*body.toTypedArray()), known, tbl,
        )
        val metas = listOf(
            FuncMeta(index = index(c, "MAIN"), key = "MAIN", name = "MAIN"),
            meta(c, name, returnsStr),
        )
        val store = VariableStore()
        return ProcessRuntime(c.build("MAIN"), store, FunctionTable(metas), VmConfig()) to store
    }

    @Test
    fun `未降级的用户方法在求值点重入执行 不被同名内建顶掉`() {
        // 原版建帧点全部在求值期（spec: Process.cs:514-540）：
        //   :516-520 methodStack++（>100 → Lang.Error.OverflowFuncStack）
        //   :522-523 state.currentMin 暂存 / :537 还原
        //   :527 state.IntoFunction(udmt.Call, udmt.Argument, exm)
        //   :528 runScriptProc() / :529 return state.MethodReturnValue
        //   :533-538 finally 私有动态变量 ScopeOut()
        // ⇒ 本侧不再抛「应由编译期展开为 _CALL」，而是真的进被调帧执行并取返回值。
        //  SAVENOS 同时是内建（会返回存档数 20），原版用户 #FUNCTION 遮蔽同名内建
        // （spec: IdentifierDictionary.cs:548-586）⇒ 读数必须是方法自己的 999。
        assertEquals(
            Value.IntV(20L),
            bareRuntime().evalContext.callFunction("SAVENOS", emptyList()),
            "对照：无同名用户方法时才落到内建 SAVENOS",
        )
        val (rt, store) = reentryRuntime(
            "SAVENOS",
            returnsStr = false,
            body = listOf("GLOBAL:0 = GLOBAL:0 + 1", "RETURNF 999"),
        )
        assertEquals(Value.IntV(999L), rt.evalContext.callFunction("SAVENOS", emptyList()))
        assertEquals(Value.IntV(999L), rt.evalContext.callFunction("SAVENOS", emptyList()))
        assertEquals(
            2L, store.readInt("GLOBAL", longArrayOf(0)),
            "每次求值各建一帧（methodStack 进出配对），帧内语句真的执行了",
        )
    }

    @Test
    fun `字符串方法重入经 _RETS 通道取回自身返回值`() {
        // `_RETS` 字符串返回通道（A7 起接通）+ 重入执行：spec: Process.cs:527-529
        // （IntoFunction → runScriptProc → MethodReturnValue），本侧
        // ProcessRuntime.invokeUserMethod 的 `meta.returnsStr` 分支读
        // `VirtualMachine.stringResult`。返回值类型不符的消费侧容错
        // （int 得 0 / str 得 ""，spec: SuperUserDefinedMethodTerm.cs:26-38）不在本用例范围。
        val (rt, store) = reentryRuntime(
            "SM",
            returnsStr = true,
            body = listOf("GLOBAL:0 = GLOBAL:0 + 1", "RETURNF \"user-str\""),
        )
        assertEquals(Value.StrV("user-str"), rt.evalContext.callFunction("SM", emptyList()))
        assertEquals(1L, store.readInt("GLOBAL", longArrayOf(0)), "方法体执行了一次")
        assertEquals(Value.StrV("user-str"), rt.evalContext.callFunction("SM", emptyList()))
        assertEquals(2L, store.readInt("GLOBAL", longArrayOf(0)), "第二次调用再次建帧执行")
    }

    // ------------------------------------------------------------------
    // 2c) 字符串 SELECTCASE：目标表达式只求值一次（spec: FunctionIdentifier.cs:3156-3165）
    // ------------------------------------------------------------------

    /**
     * 原版 SELECTCASE_Instruction 在进入 ifCase 循环**之前**就把目标求值定死
     * （:3156-3165：`AExpression term = …; string text = null; … text = term.GetStrValue(exm)`，
     * 循环内 :3195-3204 只用现成的 `is2 = text` 逐 caseExp 比较）。
     * 本侧 compileSelect 把字符串目标求值进 SelectScope.targetSlot，CASE 比较经
     * `#EXPR_RETS#` 哨兵读槽 —— 目标里的副作用方法（本用例每帧调用给 GLOBAL:0 加一）
     * 只允许触发一次；旧实现把 targetExpr 内嵌进比较式，每个 caseExp 重复建帧调用。
     */
    @Test
    fun `字符串 SELECTCASE 的目标方法只求值一次`() {
        val c = ErbProgramCompiler()
        val known = setOf("MAIN", "SM")
        val table = ExprCallTable(strMethods = setOf("SM"))
        c.compileFunction(
            FuncSig("MAIN", 0),
            parse(
                "SELECTCASE SM()",
                "CASE \"a\"",
                "GLOBAL:1 = 1",
                "CASE \"b\"",
                "GLOBAL:1 = 2",
                "CASEELSE",
                "GLOBAL:1 = 3",
                "ENDSELECT",
            ),
            known, table,
        )
        c.compileFunction(
            FuncSig("SM", 0, isMethod = true, returnsStr = true),
            parse("GLOBAL:0 = GLOBAL:0 + 1", "RETURNF \"b\""),
            known, table,
        )
        val metas = listOf(meta(c, "MAIN"), meta(c, "SM", returnsStr = true))
        val store = VariableStore()
        val rt = ProcessRuntime(c.build("MAIN"), store, FunctionTable(metas), VmConfig())
        rt.execute()
        assertEquals(2L, store.readInt("GLOBAL", longArrayOf(1)), "SM 返回 \"b\" ⇒ 命中第二个 CASE 体")
        assertEquals(
            1L, store.readInt("GLOBAL", longArrayOf(0)),
            "目标（带副作用的 #FUNCTIONS）只求值一次（spec: FunctionIdentifier.cs:3156-3165）",
        )
    }

    // ------------------------------------------------------------------
    // 3) erbcc 桥：从符号表构造 ExprCallTable
    // ------------------------------------------------------------------

    private fun def(key: String, isMethod: Boolean = false, isEvent: Boolean = false) = LabelDef(
        name = key, key = key, file = "a16.erb", lineNo = 1, fileIndex = 1, defIndex = 1,
        isEvent = isEvent, isSystem = false, isMethod = isMethod, isOnly = false, isPri = false,
        isLater = false, isSingle = false,
    )

    @Test
    fun `桥按 LabelDef 分四桶 未知类型保守不降级`() {
        val t = buildExprCallTable(
            listOf(
                def("G12_FUNC", isMethod = true),
                def("STR_M", isMethod = true),
                def("MYREF", isMethod = true),            // 未给返回类型 ⇒ 类型未知桶
                def("PLAINLBL"),
                def("EVENTTURNEND", isEvent = true),
            ),
            methodTypes = mapOf("G12_FUNC" to OperandType.INT, "STR_M" to OperandType.STR),
        )
        assertEquals(setOf("G12_FUNC"), t.intMethods, "#FUNCTION 才进可降级桶")
        assertEquals(setOf("STR_M"), t.strMethods, "#FUNCTIONS 走 _RETS 字符串通道（A7 起可降级）")
        assertEquals(setOf("MYREF"), t.unknownMethods, "类型未知 ⇒ METHOD_UNKNOWN（误判通道会静默读错值）")
        assertEquals(setOf("PLAINLBL"), t.nonMethodLabels, "普通 @label（表达式位 = UsedNonMethodFunc）")
        assertTrue("EVENTTURNEND" !in t.nonMethodLabels, "纯事件名不可解析，不是 UsedNonMethodFunc")
    }

    @Test
    fun `桥的分类与原版一致 同名内建静默走内建`() {
        val t = buildExprCallTable(
            listOf(
                def("SAVENOS", isMethod = true),
                def("BARSTR"), def("PLAINLBL"),
                def("MYREF", isMethod = true),
            ),
            methodTypes = mapOf("SAVENOS" to OperandType.INT),
        )
        assertEquals(ExprCallKind.METHOD_INT, t.classify("savenos"), "大小写折叠（spec: Config.cs:33）")
        assertEquals(ExprCallKind.BUILTIN, t.classify("BARSTR"), "普通 @label 与内建同名 → 静默走内建（:566）")
        assertEquals(ExprCallKind.METHOD_UNKNOWN, t.classify("MYREF"), "类型未知 = 不降级（等类型声明/REF 支持）")
        assertEquals(ExprCallKind.NON_METHOD_LABEL, t.classify("PLAINLBL"), "无同名内建的普通 @label → 编译期抛")
        assertEquals(ExprCallKind.UNKNOWN, t.classify("GHOST_NAME"), "谁都不认识 → 交给解析/运行期报未定义")
    }
}
