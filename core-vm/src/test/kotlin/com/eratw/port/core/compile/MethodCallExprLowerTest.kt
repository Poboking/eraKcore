package com.eratw.port.core.compile

import com.eratw.port.core.code.ErbcReader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * 表达式位调用**语料自定义 METHOD**（`#FUNCTION` / `#FUNCTIONS`）的两条形制（路次 i-methexpr）。
 *
 * bootSmoke 首差原文：`表达式位置不支持运行期函数调用 GETMETH_INT（应由编译期展开为 _CALL）`
 * （函数 `BREWING_GUIDE_CHECK_EXISTING` 行=25，pc=627672；源文件
 * `ERB/コマンド関連/COMF/日常系/SAKE_BREWING_GUIDE.ERB`，语句形如
 * `IF GETMETH_INT(0, "酒データ", LOCAL, "醸造数") > 0 || GETMETH_INT(0, "酒データ", LOCAL, "酒気増加量") > 0`）。
 * `GETMETH_INT` / `GETMETH_STR` 是语料自定义的方法（`ERB/魔改内容/qol/qol_OBJ.ERB:1-2` / `:21-22`），
 * **不是**内建 `GETMETH` / `GETMETHS`（后者注册于
 * `reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs:9302-9303`）。
 *
 * ## 原版口径（逐条回 C# 定死）
 *
 * * **解析期绑成调用节点、求值期才建帧**：表达式位的用户方法解析成
 *   `UserDefinedMethodTerm`（`Runtime.Script.Statements.Function/UserDefinedMethodTerm.cs:17-26`
 *   = `CreateCalledFunctionMethod` + `ConvertArg`），其求值 =
 *   `SuperUserDefinedMethodTerm.cs:15-24` 的 `GetIntValue`/`GetStrValue` → `exm.Process.GetValue(this)`；
 *   真正建帧在 `MinorShift.Emuera.GameProc/Process.cs:514-540`：
 *   `methodStack++`（:516-520，>100 → `Lang.Error.OverflowFuncStack`）→
 *   `state.currentMin` 暂存/还原（:522-523 / :537）→
 *   `state.IntoFunction(udmt.Call, udmt.Argument, exm)`（:527）→ `runScriptProc()` →
 *   `return state.MethodReturnValue`（:529）→ `finally` 私有动态变量 `ScopeOut()`（:533-538）。
 *   ⇒ 调用发生在**求值真正到达的那一刻**，实参也在那一刻升序求值
 *   （`UserDefinedFunctionArgument.cs:35-69`：REF 位记 `TransporterRef` :43-58，其余按
 *   `GetOperandType()` 分别记 `TransporterInt[i]` / `TransporterStr[i]` :60-67，null 位
 *   `continue` 不求值 :39-42），被调帧按形参位置写回（`ProcessState.cs:435-452`，
 *   `Arguments[i] != null` 守卫 :437）。
 * * **`&&` / `||` 真短路**：`OperatorMethodManager.cs:393-409`（本侧 `Expr.kt:234-238` 同口径）
 *   ⇒ 短路右位/三目分支里的调用**可能一次都不执行**；把它们提成无条件前导 `_CALL` =
 *   伪造副作用（ADR-005），故这类调用点保持 `FuncCall` 原节点、运行期重入建帧。
 * * **消费侧类型容错**：`SuperUserDefinedMethodTerm.cs:26-38` —— 返回值与消费语境类型不符时
 *   int 得 `0`、str 得 `""`（不报错）；编译期降级那条已由 `Vm.kt:555-573` 的 `_RET`/`_RETS` 复刻。
 * * **非方法 `@label` 在表达式位**：解析期即 `UsedNonMethodFunc` 抛
 *   （`MinorShift.Emuera/IdentifierDictionary.cs:566-569`），不许悄悄支持。
 * * **用户方法遮蔽同名内建**：`IdentifierDictionary.cs:548-586` ⇒ 运行期判定顺序
 *   哨兵 → 用户方法 → 内建（`ProcessRuntime.evalContext` ②）。
 *
 * ## 本侧两条通道（必须同读数）
 *
 * 1. **严格求值位**（赋值右值、`IF`/`SIF` 条件的**左**操作数、PRINT 词项、指令行参数位…）：
 *    编译期降级 = 「实参升序求值 → `_MOV_*` 进被调形参桶 → `_CALL`(c=返回槽；str 方法另置
 *    bit28) → 哨兵 `#EXPR_RET#<k>` / `#EXPR_RETS#<k>` 读槽」（h-htmlp 既有骨架，本路只补
 *    **实参分桶**与惰性位的运行期通道）。
 * 2. **惰性求值位**（`&&`/`||` 右位、三目分支、返回类型未知的方法）：保持原节点，运行期由
 *    `ProcessRuntime.evalContext` → [ProcessRuntime.invokeUserMethod] 重入被调帧
 *    （GETMETH/GETMETHS 早已在用这条通道），方法栈上限 100 与 `methodDepth` 共用。
 *
 * 实参占位统一为「int 按出现序进 int 单元格 R0..、str 按出现序进 str 单元格 R0..」：
 * 被调侧 `ProcessRuntime.bindArgs`（`_CALL`）与 `rootBindOf`+`bindBuckets`（重入/按名）读的都
 * 是**桶序**。旧实现是"调用方按位置写、被调方按桶读"，只在实参类型不交错时侥幸等价；
 * 语料真实形态 `GETMETH_INT(0, "酒データ", LOCAL, "醸造数")` 恰为 int/str/int/str 交错
 * ⇒ 整体错位读脏槽。
 */
class MethodCallExprLowerTest {

    // ------------------------------------------------------------------
    // 夹具（解析与编译骨架同 ExprCallLoweringTest / ExprCallBridgeTest 同源）
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("G0", OperandType.INT, 1)
            .declareVariable("G1", OperandType.INT, 1)
            .declareVariable("CNT", OperandType.INT, 1)
            .declareVariable("C0", OperandType.STR, 1)
            .declareVariable("C1", OperandType.STR, 1)
            .declareVariable("LOCAL", OperandType.INT, 1)
            .declareVariable("ARG", OperandType.INT, 1)
            .declareVariable("ARGS", OperandType.STR, 1)
            .declareFunction("MI", OperandType.INT)          // #FUNCTION，交错四实参
            .declareFunction("MCNT", OperandType.INT)        // #FUNCTION，自调（栈溢出测用）
            .declareFunction("INNER", OperandType.INT)       // #FUNCTION，方法体内被再调
            .declareFunction("SM1", OperandType.STR)         // #FUNCTIONS
            .declareFunction("SM2", OperandType.STR)         // #FUNCTIONS
            // qol_OBJ.ERB 的真实方法（语料形态用例直接解析该行）：解析期严格化后
            // 夹具必须与语料符号表同口径声明，否则 functionCall 会按未定义标识符报错。
            .declareFunction("GETMETH_INT", OperandType.INT)
            .declareFunction("GETMETH_STR", OperandType.STR)
            .declareFunction("PLAINLBL", OperandType.INT)   // 普通 @label（非方法）
            // 行首方法调用形态（`MI(…)` / `SM1(…)` 独占一行 = METHOD 语句，不是赋值）
            .declareMethod("MI")
            .declareMethod("SM1"),
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
            p.parseLogicalLine(LogicalLine(kind, "methexpr.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun callTable(
        int: Set<String> = setOf("MI", "MCNT", "INNER", "GETMETH_INT"),
        str: Set<String> = setOf("SM1", "SM2", "GETMETH_STR"),
        labels: Set<String> = setOf("PLAINLBL"),
    ) = ExprCallTable(
        int.map { it.uppercase() }.toSet(),
        str.map { it.uppercase() }.toSet(),
        labels.map { it.uppercase() }.toSet(),
    )

    private data class Callee(
        val name: String,
        val lines: List<String>,
        val arity: Int = 4,
        val argInt: Int = 2,
        val argStr: Int = 2,
        val returnsStr: Boolean = false,
    )

    private class Model(val program: Program, val metas: List<FuncMeta>, val store: VariableStore) {
        fun runtime(): ProcessRuntime = ProcessRuntime(program, store, FunctionTable(metas), VmConfig())
    }

    /**
     * 运行期变量表。[parser] 的 `SymbolTable` 只负责**解析期**认名字，读写要变量真的
     * 注册进 [VariableStore]（否则 `VariableStore.readInt` 在 `registry.def(name)` 未命中时
     * 立刻抛「未定义的变量 …」，见 VariableStore.kt:107）——
     * 注册口径同本仓既有装置：`declareUserVar(DimSpec(...))`
     * （`core/vars/UserVariables.kt:38`；用法见 `ExecLayerCloseoutTest.kt:144`、
     * `RealCorpusXmlTest.kt:225`、`PrintStatementsTest.kt:257`）。
     *
     * `LOCAL` / `ARG` / `ARGS` 不用注册：它们本身就是系统局部变量
     * （`Storage.L_INT` / `L_STR`，`VariableRegistry.kt:273/290`），尺寸由 [FuncMeta]
     * 的形参桶数经 `ProcessRuntime.init` 的 `defineLocalLengths` 决定，帧由
     * `Vm.run(entryFunctionIndex)` 的 `host.onFunctionEnter`（Vm.kt:306）压入。
     *
     * `A` 同理不能注册（`SystemLayout.kt:41` 已把 `A` 登记为角色能力值 ⇒ `declareUserVar`
     * 会抛「变量名 A 与系统变量重名」），故按既有装置用 `addPseudoCharacter()`
     * 让位置 0 有效（`PrintCharaDataTest.kt:49` / `UserVarTest.kt:192` 同形）。
     */
    private fun observedStore(): VariableStore = VariableStore().apply {
        declareUserVar(DimSpec("CNT", isStr = false, dims = intArrayOf(1)))
        declareUserVar(DimSpec("G0", isStr = false, dims = intArrayOf(1)))
        declareUserVar(DimSpec("G1", isStr = false, dims = intArrayOf(1)))
        declareUserVar(DimSpec("C0", isStr = true, dims = intArrayOf(1)))
        declareUserVar(DimSpec("C1", isStr = true, dims = intArrayOf(1)))
        addPseudoCharacter()
    }

    /** 编译 MAIN + 全部被调方法，并给出 `FuncMeta`（isMethod / returnsStr / 形参桶数）。 */
    private fun model(main: List<Statement>, vararg callees: Callee): Model {
        val c = ErbProgramCompiler()
        val tbl = callTable()
        val known = buildSet {
            add("MAIN")
            callees.forEach { add(it.name) }
        }
        c.compileFunction(FuncSig("MAIN", 0), main, known, tbl)
        val metas = ArrayList<FuncMeta>()
        metas.add(FuncMeta(index = c.indexOf("MAIN"), key = "MAIN", name = "MAIN", position = "methexpr.erb"))
        for (f in callees) {
            c.compileFunction(
                FuncSig(f.name, f.arity, isMethod = true, returnsStr = f.returnsStr),
                parse(*f.lines.toTypedArray()),
                known,
                tbl,
            )
            metas.add(
                FuncMeta(
                    index = c.indexOf(f.name), key = f.name, name = f.name, isMethod = true,
                    argIntCount = f.argInt, argStrCount = f.argStr, returnsStr = f.returnsStr,
                    position = "methexpr.erb",
                ),
            )
        }
        return Model(c.build("MAIN"), metas, observedStore())
    }

    private fun ErbProgramCompiler.indexOf(name: String): Int =
        functions.first { it.sig.name.equals(name, ignoreCase = true) }.index

    /**
     * 交错四实参假体：把四个桶抄进观测变量，返回 ARG:0 + ARG:1；CNT 计调用次数。
     *
     * 字符串桶必须用 **`'=`** 而不是 `=`：原版对**字符串变量**的 `=` 走
     * `AnalyseFormattedString(FormStrEndWith.EoL)` = **form-string 原文**
     * （spec: `ArgumentParser.cs:1024-1041`），`C0:0 = ARGS` 赋的是字面量文本 `ARGS`
     * 本身（本侧 `ArgBuilders.kt:1341-1357` 同形，语料 `GMF_METHOD_STACK '= ARGS` 等
     * 正是用 `'=` 取表达式值）；`'=` 才是 `OperatorCode.AssignmentStr`
     * （spec: `LexicalAnalyzer.cs:566-571`），走 `ReduceArguments` 真表达式通道
     * （spec: `ArgumentParser.cs:1042-1070`）。
     */
    private val miBody = listOf(
        "CNT:0 = CNT:0 + 1",
        "G0:0 = ARG",
        "G1:0 = ARG:1",
        "C0:0 '= ARGS",
        "C1:0 '= ARGS:1",
        "RETURNF ARG + ARG:1",
    )

    /** str 方法假体：抄 ARGS:0（`'=` 口径同 [miBody]），返回常量串；CNT 计调用次数。 */
    private fun smBody(ret: String) = listOf(
        "CNT:0 = CNT:0 + 1",
        "C0:0 '= ARGS",
        "RETURNF \"$ret\"",
    )

    private fun ins(p: Program, pc: Int) = p.instructionAt(pc)

    private fun pcs(p: Program, op: Int): List<Int> =
        (0 until p.instructionCount).filter { ins(p, it).op == op }

    /** 表达式池里第一个「`||` 顶层节点」（= 本路的 IF/赋值条件降级产物）。 */
    private fun orExprOf(p: Program): Binary {
        for (pc in pcs(p, Opcode._EVAL_INT)) {
            val e = p.expr(ins(p, pc).a)
            if (e is Binary && e.op == OperatorCode.OR) return e
        }
        throw AssertionError("表达式池里没有顶层 `||` 节点（共 ${p.exprCount} 条）")
    }

    private fun funCallNames(e: Expr?): List<String> {
        val out = ArrayList<String>()
        fun walk(x: Expr?) {
            when (x) {
                null -> {}
                is FuncCall -> { out.add(x.name); x.args.forEach(::walk) }
                is Binary -> { walk(x.left); walk(x.right) }
                else -> {}
            }
        }
        walk(e)
        return out
    }

    // ------------------------------------------------------------------
    // 1) 编译形状：严格位降级、惰性位保持原节点
    // ------------------------------------------------------------------

    @Test
    fun `短路右位不前移 只有左位发前导_CALL`() {
        val m = model(
            parse(
                "A:0 = 0",
                "IF MI(7, \"酒データ\", 5, \"醸造数\") > 0 || MI(1, \"x\", 2, \"y\") > 0",
                "    A:0 = 1",
                "ENDIF",
            ),
            Callee("MI", miBody),
        )
        val p = m.program
        // 前导 `_CALL` 恰好一条：左位（严格求值）降级，右位（短路位）不前移
        val calls = pcs(p, Opcode._CALL)
        assertEquals(1, calls.size, "短路右位的调用不得提为前导 _CALL（伪造副作用）")
        val cond = orExprOf(p)
        val leftNames = funCallNames(cond.left)
        assertEquals(1, leftNames.size)
        assertNotNull(exprTempSlotOf(leftNames[0]), "左位应已换成 `#EXPR_RET#<k>` 哨兵，实际=${leftNames[0]}")
        assertEquals(listOf("MI"), funCallNames(cond.right), "右位必须保持 FuncCall 原节点")
        assertEquals(
            SlotAddr.local(exprTempSlotOf(leftNames[0])!!), ins(p, calls.single()).c,
            "哨兵槽号必须等于降级 `_CALL` 的 c（调用方帧返回槽）",
        )
    }

    @Test
    fun `交错实参按类型分桶入槽 而不是按位置占槽`() {
        val m = model(
            parse("A:0 = MI(7, \"酒データ\", 5, \"醸造数\") > 0 || 1 == 1"),
            Callee("MI", miBody),
        )
        val p = m.program
        val movs = (0 until p.instructionCount)
            .filter { ins(p, it).op == Opcode._MOV_INT || ins(p, it).op == Opcode._MOV_STR }
            .map { (if (ins(p, it).op == Opcode._MOV_STR) "S" else "I") to SlotAddr.index(ins(p, it).a) }
        assertEquals(
            listOf("I" to 0, "S" to 0, "I" to 1, "S" to 1), movs,
            "int 桶占 R0/R1 的整数单元格、str 桶占 R0/R1 的字符串单元格（被调侧 bindArgs 读的就是桶序）",
        )
        // `_CALL` 的 b 仍是实参总数（超出被调 arity 的部分由 VM 裁剪；两套同号共用）
        assertEquals(4, ins(p, pcs(p, Opcode._CALL).single()).b)
    }

    // ------------------------------------------------------------------
    // 2) 执行读数：两条通道同值 + 短路真不执行
    // ------------------------------------------------------------------

    @Test
    fun `严格位与惰性位读数逐字相同 交错实参绑定一致`() {
        val strict = model(
            parse("A:0 = MI(7, \"酒データ\", 5, \"醸造数\") > 0 || 1 == 1"),
            Callee("MI", miBody),
        )
        strict.runtime().execute()
        val lazySide = model(
            parse("A:0 = 0 == 1 || MI(7, \"酒データ\", 5, \"醸造数\") > 0"),
            Callee("MI", miBody),
        )
        lazySide.runtime().execute()
        for ((tag, x) in listOf("严格位(_CALL)" to strict, "惰性位(重入)" to lazySide)) {
            val s = x.store
            assertEquals(7L, s.readInt("G0", longArrayOf(0)), "$tag：ARG:0 = 第 1 个整型实参")
            assertEquals(5L, s.readInt("G1", longArrayOf(0)), "$tag：ARG:1 = 第 2 个整型实参")
            assertEquals("酒データ", s.readStr("C0", longArrayOf(0)), "$tag：ARGS:0 = 第 1 个字符串实参")
            assertEquals("醸造数", s.readStr("C1", longArrayOf(0)), "$tag：ARGS:1 = 第 2 个字符串实参")
            assertEquals(1L, s.readInt("A", longArrayOf(0)), "$tag：12 > 0 ⇒ 左位即真")
        }
    }

    @Test
    fun `短路挡住的调用一次都不建帧`() {
        val m = model(
            parse(
                "A:0 = 1 || MI(1, \"x\", 2, \"y\") > 0",
                "A:0 = 0 == 1 || MI(3, \"p\", 4, \"q\") > 0",
            ),
            Callee("MI", miBody),
        )
        m.runtime().execute()
        assertEquals(1L, m.store.readInt("CNT", longArrayOf(0)), "第 1 条被短路挡掉 ⇒ 只有第 2 条的调用发生")
        assertEquals(3L, m.store.readInt("G0", longArrayOf(0)))
        assertEquals(4L, m.store.readInt("G1", longArrayOf(0)))
        assertEquals("p", m.store.readStr("C0", longArrayOf(0)))
        assertEquals("q", m.store.readStr("C1", longArrayOf(0)))
        assertEquals(1L, m.store.readInt("A", longArrayOf(0)))
    }

    @Test
    fun `行首方法调用带实参 写 RESULT 且实参按桶绑定`() {
        // spec: FunctionIdentifier.cs:592-603 —— METHOD_Instruction 求值**整个 methodTerm**
        // （实参升序求值 → 建帧 → 取返回值）后写 RESULT。旧实现只发 `_CALL 名, 0`：
        // 实参全丢、返回值丢弃，本用例会读到 ARG=0 且 A=0。
        // MAIN 掉尾有 `RESULT = 0`（spec: Process.cs:1060-1063），故用 `A:0 = RESULT` 取读数。
        val m = model(
            parse(
                "MI 7, \"酒データ\", 5, \"醸造数\"",
                "A:0 = RESULT",
            ),
            Callee("MI", miBody),
        )
        m.runtime().execute()
        assertEquals(7L, m.store.readInt("G0", longArrayOf(0)), "ARG:0 = 第 1 个整型实参")
        assertEquals(5L, m.store.readInt("G1", longArrayOf(0)), "ARG:1 = 第 2 个整型实参")
        assertEquals("酒データ", m.store.readStr("C0", longArrayOf(0)), "ARGS:0 = 第 1 个字符串实参")
        assertEquals("醸造数", m.store.readStr("C1", longArrayOf(0)), "ARGS:1 = 第 2 个字符串实参")
        assertEquals(1L, m.store.readInt("CNT", longArrayOf(0)), "行首调用只执行一次")
        assertEquals(12L, m.store.readInt("A", longArrayOf(0)), "返回值 12 经 RESULT 被后续语句读到")
    }

    @Test
    fun `行首字符串方法调用写 RESULTS 并绑定实参`() {
        // spec: FunctionIdentifier.cs:597-601 —— 非整型 methodTerm 写 RESULTS；
        // `_CALL` 置 bit28（STR_RET_FLAG）走 `_RETS`，离开被调帧时写回调用方返回槽。
        val m = model(
            parse("SM1 3, \"abc\""),
            Callee("SM1", smBody("yes"), arity = 2, argInt = 1, argStr = 1, returnsStr = true),
        )
        m.runtime().execute()
        assertEquals(
            "yes", m.store.readStr("RESULTS", longArrayOf(0)),
            "字符串方法的返回值必须落 RESULTS（旧实现丢掉返回槽，这里读到空串）",
        )
        assertEquals("abc", m.store.readStr("C0", longArrayOf(0)), "ARGS:0 = 字符串实参")
        assertEquals(1L, m.store.readInt("CNT", longArrayOf(0)), "行首调用只执行一次")
    }

    @Test
    fun `字符串方法惰性位重入取回字符串通道读数`() {
        fun prog(left: String) = model(
            parse(
                "A:0 = 0",
                "IF SM1() == \"$left\" || SM2() == \"yes\"",
                "    A:0 = 1",
                "ENDIF",
            ),
            Callee("SM1", smBody("no"), arity = 0, argInt = 0, argStr = 0, returnsStr = true),
            Callee("SM2", smBody("yes"), arity = 0, argInt = 0, argStr = 0, returnsStr = true),
        )
        val hitLeft = prog("no")
        hitLeft.runtime().execute()
        assertEquals(1L, hitLeft.store.readInt("A", longArrayOf(0)), "SM1 经哨兵读回 \"no\" ⇒ 左位即真")
        assertEquals(1L, hitLeft.store.readInt("CNT", longArrayOf(0)), "SM2 被短路挡住，不得执行")

        val hitRight = prog("zzz")
        hitRight.runtime().execute()
        assertEquals(1L, hitRight.store.readInt("A", longArrayOf(0)), "SM2 重入返回 \"yes\" ⇒ 右位为真")
        assertEquals(2L, hitRight.store.readInt("CNT", longArrayOf(0)), "两次调用各执行一次")
    }

    @Test
    fun `方法体内再调方法 两层各执行一次`() {
        val m = model(
            parse("A:0 = 0 == 1 || MCNT(1) > 0"),
            Callee(
                "MCNT",
                listOf("CNT:0 = CNT:0 + 1", "G0:0 = ARG", "G1:0 = INNER(ARG) * 10", "RETURNF G1:0"),
                arity = 1, argInt = 1, argStr = 0,
            ),
            Callee("INNER", listOf("RETURNF ARG * 2"), arity = 1, argInt = 1, argStr = 0),
        )
        m.runtime().execute()
        // MCNT(1) 在惰性位（重入）；INNER(1) 在 MCNT 的赋值右值（严格位，降级 `_CALL`）
        assertEquals(20L, m.store.readInt("G1", longArrayOf(0)), "G1 = INNER(1) * 10 = 2*10")
        assertEquals(1L, m.store.readInt("A", longArrayOf(0)), "MCNT(1) = 20 > 0 ⇒ 条件为真")
        assertEquals(1L, m.store.readInt("CNT", longArrayOf(0)), "MCNT 自身只执行一次")
    }

    @Test
    fun `递归方法在惰性位到达一百层报溢出原文`() {
        val m = model(
            parse("A:0 = 0 == 1 || MCNT(0) > 0"),
            Callee(
                "MCNT",
                listOf("CNT:0 = ARG > 999999 || MCNT(ARG + 1) > 0", "RETURNF ARG"),
                arity = 1, argInt = 1, argStr = 0,
            ),
        )
        val e = assertFailsWith<CodeEE> { m.runtime().execute() }
        // zhs 表 `lang.emuera-zhs.xml:689` = `函数调用栈溢出(是否发生了无限递归调用？)`，
        // 抛点 `Process.cs:516-520`（`methodStack++ > 100` ⇒ 第 101 层）。
        assertEquals("函数调用栈溢出(是否发生了无限递归调用？)", e.message)
    }

    // ------------------------------------------------------------------
    // 3) 报错文案（原版口径，不许悄悄支持）
    // ------------------------------------------------------------------

    @Test
    fun `非方法label表达式位调用 编译期按UsedNonMethodFunc原文抛`() {
        val c = ErbProgramCompiler()
        val e = assertFailsWith<CompileException> {
            c.compileFunction(
                FuncSig("MAIN", 0),
                parse("A:0 = PLAINLBL(1) > 0 || 1 == 1"),
                setOf("MAIN", "PLAINLBL"),
                callTable(),
            )
        }
        assertTrue(
            "对为被标记为#FUNCTION的函数(PLAINLBL)进行了行内函数形式的调用" in (e.message ?: ""),
            e.message ?: "",
        )
        assertTrue("IdentifierDictionary.cs:566-569" in (e.message ?: ""), e.message ?: "")
    }

    @Test
    fun `既非用户方法也非内建的名字 运行期按未定义函数原文抛`() {
        val m = model(
            parse("A:0 = 0"),
            Callee("MI", miBody),
        )
        val e = assertFailsWith<CodeEE> {
            m.runtime().evalContext.callFunction("NO_SUCH_METH_EXPR", emptyList())
        }
        assertEquals("未定义的函数 NO_SUCH_METH_EXPR", e.message, "回落内建桥的 IdentifierNotFoundCodeEE 口径")
    }

    // ------------------------------------------------------------------
    // 4) 差分：源码直建 vs `.erbc` 往返
    // ------------------------------------------------------------------

    private fun readsOf(x: Model): List<String> = listOf(
        "A:0=${x.store.readInt("A", longArrayOf(0))}",
        "G0=${x.store.readInt("G0", longArrayOf(0))}",
        "G1=${x.store.readInt("G1", longArrayOf(0))}",
        "CNT=${x.store.readInt("CNT", longArrayOf(0))}",
        "C0=${x.store.readStr("C0", longArrayOf(0))}",
        "C1=${x.store.readStr("C1", longArrayOf(0))}",
    )

    @Test
    fun `源码直建与erbc往返 指令序列与执行读数逐字相同`() {
        val direct = model(
            parse(
                "A:0 = 0",
                "A:0 = MI(7, \"酒データ\", 5, \"醸造数\") > 999 || MI(9, \"a\", 8, \"b\") > 0",
            ),
            Callee("MI", miBody),
        )
        direct.runtime().execute()
        val want = readsOf(direct)
        assertEquals(
            listOf("A:0=1", "G0=9", "G1=8", "CNT=2", "C0=a", "C1=b"), want,
            "左位降级（返回 12，`12 > 999` 为假）+ 右位重入各执行一次；桶绑定按交错实参",
        )

        val after = ErbcReader.decode(ErbcWriter.encode(direct.program))
        assertEquals(direct.program.instructionCount, after.instructionCount, "往返后指令数变了")
        assertEquals(direct.program.exprCount, after.exprCount, "往返后表达式池条目数变了")
        val roundTrip = Model(after, direct.metas, observedStore())
        roundTrip.runtime().execute()
        assertEquals(want, readsOf(roundTrip), "同一小程序两条路径读数不同 = `.erbc` 丢了调用信息")
    }

    // ------------------------------------------------------------------
    // 5) 语料真实形态（吃 ERATW_CORPUS；变量缺失时不生成用例，不计 skipped）
    // ------------------------------------------------------------------

    private fun corpusRoot(): File {
        val corpus = System.getenv("ERATW_CORPUS")
        Assumptions.assumeTrue(corpus != null && File(corpus).isDirectory, "未设 ERATW_CORPUS：跳过语料形态用例")
        return File(corpus)
    }

    @Test
    fun `语料 SAKE_BREWING_GUIDE 的 GETMETH_INT 短路行 编译不前移 运行按重入取读数`() {
        val src = assertNotNull(
            corpusRoot().walkTopDown().firstOrNull { it.name.equals("SAKE_BREWING_GUIDE.ERB", ignoreCase = true) },
            "语料内未找到 SAKE_BREWING_GUIDE.ERB",
        )
        val stmt = assertNotNull(
            src.readText(Charsets.UTF_8).lines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("IF GETMETH_INT(") && it.contains("||") },
            "语料里没有 GETMETH_INT 的 `||` 短路行：本用例前提已变，需复核",
        )

        // 假体 = qol_OBJ.ERB 的 GETMETH_INT 形制（四实参 int/str 交错、#FUNCTION）
        val m = model(
            parse("LOCAL:0 = 3", "A:0 = 0", stmt, "A:0 = 1", "ENDIF"),
            Callee("GETMETH_INT", miBody),
        )
        val p = m.program
        val calls = pcs(p, Opcode._CALL)
        assertEquals(1, calls.size, "只允许左位的调用被降级；短路右位必须留到运行期")
        assertEquals(listOf("GETMETH_INT"), funCallNames(orExprOf(p).right))

        m.runtime().execute()
        // 假体返回 ARG:0 + ARG:1 = 0 + LOCAL(3) = 3 ⇒ `3 > 0` 左位即真 ⇒ 右位不执行
        assertEquals(1L, m.store.readInt("A", longArrayOf(0)), "左位为真 ⇒ IF 体执行")
        assertEquals(1L, m.store.readInt("CNT", longArrayOf(0)), "短路右位的 GETMETH_INT 不得执行")
        assertEquals(0L, m.store.readInt("G0", longArrayOf(0)), "ARG:0 = 第 1 个整型实参 0")
        assertEquals(3L, m.store.readInt("G1", longArrayOf(0)), "ARG:1 = LOCAL(3)，不是位置 1 上的字符串单元格")
        assertEquals("酒データ", m.store.readStr("C0", longArrayOf(0)))
        assertEquals("醸造数", m.store.readStr("C1", longArrayOf(0)), "ARGS:1 = 第 2 个字符串实参")
    }

    @Test
    fun `语料 qol_OBJ 定义的 GETMETH_INT 与 GETMETH_STR 是方法声明`() {
        val def = assertNotNull(
            corpusRoot().walkTopDown().firstOrNull { it.name.equals("qol_OBJ.ERB", ignoreCase = true) },
            "语料内未找到 qol_OBJ.ERB",
        )
        val lines = def.readText(Charsets.UTF_8).lines().map { it.trim() }
        assertEquals("@GETMETH_INT(ARG, CLASS_NAME, O_ID, O_DATA)", lines.getOrNull(0))
        assertEquals("#FUNCTION", lines.getOrNull(1))
        assertEquals("@GETMETH_STR(ARG, CLASS_NAME, O_ID, O_DATA)", lines.getOrNull(20))
        assertEquals("#FUNCTIONS", lines.getOrNull(21))
    }
}
