package com.eratw.port.core.stmt

import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **原版拒绝 / 本移植当前宽松的缺口台账（A10 parser-oracle）**
 *
 * 本文件不是"绿灯即无害"的清单：每条都是原版装载期会拒绝（`InvalidLine`，或 level≥2 标错使该行
 * 不可执行）而我们的语句层照收的真实差异；ADR-001 把错误行为列为逐位契约之一，故均为待收口项。
 * 断言写成「当前解析成功」，因此：
 *  - 收紧实现后本文件立刻变红 → 强制把该条迁往 [PcRejectedSyntaxTest]（可反向证明）；
 *  - `类别全覆盖` 保证 239 条告警去重出的类别（实测 18 类 + 1 个同源对称类）不会有人被静默丢掉。
 * 类别 id 与 `tools/golden_pc_oracle.py` 完全一致。
 *
 * 给组长的收口优先级（本轮 A10 不改 core/compile〔A3〕与 core/expr〔非本所有权〕，只出证据）：
 *  - **P0 SCOPED_VAR_INSTR**：**已收口（A3 语句层门控）** —— 原版对**本作**不存在 VARI/VARS
 *    （`FunctionIdentifier.cs:4123-4127` 受 `JSONConfig.UseScopedVariableInstruction` 门控，
 *    而 `eratw-画蛇添足版eratw4.980/setting.json` 显式为 false；它是金标 57 条「无法解析的行」
 *    与 9 条标识符级联的根因）。LineParser 新增 `StmtParseConfig.useScopedVariableInstruction`
 *    构造参数（默认 true=现状兼容），显式 false 时 VARI/VARS 在标识符解析期抛 CanNotUseVAR
 *    同口径错误（`IdentifierDictionary.cs:624-627`）；拒绝面已锁进 [PcRejectedSyntaxTest]
 *    （SCOPED_VAR_INSTR 段）。生产装载接线（ProcessRuntime/erbcc 传 false）归主代理。
 *  - **P0 METHOD_AS_VARIABLE**（22 条）：**已收口（A4）** —— 裸内建方法名不再当零参函数取值。
 *    原版 `GetFunctionMethod(arguments == null)` 只查 `refmethodDic`（`IdentifierDictionary.cs:538-547`），
 *    内建方法以 `DefinedNameType.SystemMethod` 进 nameDic（:170）→ :607-612 抛
 *    「用变量的方式使用函数名」。移植侧新增
 *    `IdentifierResolver.canBeReferencedBare`（Parser.kt 裸标识符分支 + `BuiltinSymbols.isBuiltinMethod`）。
 *    拒绝面已锁进 [PcRejectedSyntaxTest]（METHOD_AS_VARIABLE 段）与 [BuiltinMethodSymbolTest]。
 *  - **P1 FUNC_LABEL_ARG_FORM**（**已收口（A3）**）：原版标签参数是「引用已声明变量 + 常量默认值」
 *    （`ErbLoader.cs:654-755`），不是形参名声明。`parseLabelLine` 现在按
 *    `ReduceArguments(isDefine:true)` 的口径校验：非 `VariableTerm` / 未声明 / CONST →
 *    `ArgCanOnlyAssignableVar`（`ErbLoader.cs:651-652 / 680-684`）→ `Warn(level 2, isError)`（:769）；
 *    空形参位 → `CannotOmitFuncArg`（`ExpressionParser.cs:269-272`）。拒绝面见 [ParserStrictnessTest]。
 *  - **P1 METHOD_ARG_TYPE**：方法实参类型/个数/重载校验缺失（数据已在 specs/builtin-functions.yaml）。
 *  - **P2 全角空格门控**：1-2 条，成本低。**RESVAR_NAME 已收口（CORE-06）**：
 *    `#DIM` 名撞 Reserved/SystemMethod/SystemVariable/SystemInstrument 时
 *    `LineParser.parseSharpLine` 抛 CodeEE（`IdentifierDictionary.cs:258-297`），
 *    拒绝面迁至 [PcRejectedSyntaxTest]。
 *  - **P3 DUP_FUNCTION / MISSING_FUNCTION / ARG_UNDECLARED_WARN**：本就不属语句层，移交 proc/sem。
 */
class PcOracleGapTest {

    /** 缺口条目：engine=原版拒绝证据（必须是 文件:行号），owner=应收口的位置。 */
    private data class Gap(
        val id: String,
        val engineMessage: String,
        val engineEvidence: String,
        val owner: String,
    )

    private val gaps: Map<String, Gap> = LinkedHashMap<String, Gap>().apply {
        put("METHOD_ARG_TYPE", Gap(
            id = "METHOD_ARG_TYPE",
            engineMessage = "函数VARSETEX: 第1个参数不是字符串 / 参数过多 / 参数不符合任何一种形式",
            engineEvidence = "IdentifierDictionary.cs:576-585（methodDic → CheckArgumentType → CodeEE）；" +
                "消息模板 lang zhs.xml:890 ArgIsNotStr / :891 ArgIsNotInt / :901 TooManyFuncArgs / :905 NotValidArgs",
            owner = "core/expr Parser.kt:295-320（源码注释自证「绑定后置到 M2」）+ core/fn 重载表",
        ))
        put("MISSING_FUNCTION", Gap(
            id = "MISSING_FUNCTION",
            engineMessage = "没有找到指定的函数\"@GOLDEN_RNG\"",
            engineEvidence = "SP_CALL_ArgumentBuilder（ArgumentParser.cs:707+）装载期核对标签存在性 → " +
                "IdentifierDictionary.cs:588-628 → CodeEE → LogicalLineParser.cs:558-562 InvalidLine",
            owner = "core/proc resolveFunctionIndex（语义层，非语句层）",
        ))
        put("DUP_FUNCTION", Gap(
            id = "DUP_FUNCTION",
            engineMessage = "函数\"@G12_PLAIN\"已经被定义过(…的48行)",
            engineEvidence = "ErbLoader.cs:443-449（需 Config.WarnNormalFunctionOverloading；level 1，非致命）",
            owner = "core/proc 标签表（事件函数多定义合法；非事件重名属声明层告警面）",
        ))
        put("ARG_UNDECLARED_WARN", Gap(
            id = "ARG_UNDECLARED_WARN",
            engineMessage = "函数没有声明参数\"ARG\"而直接使用了\"ARG\"(不推荐这种用法，请考虑使用#DIM)",
            engineEvidence = "VariableLocal.cs:80（取用 ARG/ARGS 时核对当前 FunctionLabelLine 是否声明过它）；" +
                "消息模板 lang zhs.xml:392 Error.UseArgVarInHasNotArgFunc（level 1，需函数级上下文）",
            owner = "core/vars 变量访问层 + core/proc FuncMeta（语句层没有「当前函数声明了哪些参数」的信息）",
        ))
    }

    /** PcRejectedSyntaxTest / PcFormInterpolationTest 已锁的类别（与 oracle 工具同步）。 */
    private val lockedCategories = setOf(
        "SET_INSTR_FORM", "UNKNOWN_INSTR_NAME", "ILLEGAL_SEP_AFTER_INSTR", "BRACE_OUTSIDE_FORM",
        "FORM_BRACE_NOT_INT", "FORM_PERCENT_NOT_STR", "UNKNOWN_IDENTIFIER", "INSTRUCTION_ARG_TYPE",
        "BLOCK_MISSING_PAIR", "EXPR_SYNTAX", "UNEXPECTED_SYMBOL", "SCOPED_VAR_INSTR",
        "METHOD_AS_VARIABLE",
        // CORE-06：`#DIM` 撞系统保留名由 LineParser.parseSharpLine 拒绝
        // （IdentifierDictionary.cs:258-297；拒绝面见 PcRejectedSyntaxTest）。
        "RESVAR_NAME",
        // A3：形参必须解成 VariableTerm（ErbLoader.cs:651-652 / 658-702 / 769）。
        // 由 [ParserStrictnessTest] 锁「原版拒 / 原版收」两侧。
        "FUNC_LABEL_ARG_FORM",
    )

    /**
     * `tools/golden_pc_oracle.py` 对 239 条告警去重出的类别全集 —— 实测 18 类，
     * 外加 FORM_PERCENT_NOT_STR 这个**对称类**（StrForm.cs:330-335，本轮 0 次触发，
     * 但与 {{}} 整型契约同源同实现，必须一起锁），合计 19。改动需同步两侧。
     */
    private val oracleCategories = setOf(
        "SET_INSTR_FORM", "UNKNOWN_INSTR_NAME", "ILLEGAL_SEP_AFTER_INSTR", "BRACE_OUTSIDE_FORM",
        "FORM_BRACE_NOT_INT", "FORM_PERCENT_NOT_STR", "UNKNOWN_IDENTIFIER", "INSTRUCTION_ARG_TYPE",
        "BLOCK_MISSING_PAIR", "EXPR_SYNTAX", "UNEXPECTED_SYMBOL",
        "RESVAR_NAME", "SCOPED_VAR_INSTR", "METHOD_AS_VARIABLE", "FUNC_LABEL_ARG_FORM",
        "METHOD_ARG_TYPE", "MISSING_FUNCTION", "DUP_FUNCTION", "ARG_UNDECLARED_WARN",
    )

    /**
     * 源码读出来、但金标语料**未触发**的额外宽松面（不进 18 类覆盖断言）。
     * 全角空格：原版 LogicalLineParser.cs:517-523 只在 Config.SystemAllowFullSpace=true 时把 '　'
     * 当合法分隔符；本作 setting.json 没有该项（默认 false）→ `PRINTL　ABC` 在原版是
     * InvalidLine(InvalidCharacterAfterInstruction1)。我们的 splitArgs（LineParser.kt:196-202）
     * 无条件接受 '　'。
     */
    private val extraLeniency = mapOf(
        "FULLWIDTH_SEPARATOR_GATE" to "LineParser.kt:200 无条件放过 '　'（原版需 SystemAllowFullSpace）",
    )

    private fun bare(): LineParser {
        val sym = SymbolTable()
            .declareVariable("ARR", OperandType.INT, 1)
            .declareVariable("ARR2", OperandType.INT, 1)
            .declareVariable("CNT", OperandType.INT, 1)
            .declareVariable("STRARR", OperandType.STR, 1)
            .declareFunction("MYFUNC", OperandType.INT)
        return LineParser(sym)
    }

    /** 解析成功 = 本移植当前接受（= 缺口）。失败即说明实现已收紧，须把该条迁到 PcRejectedSyntaxTest。 */
    private fun accept(id: String, src: String, p: LineParser = bare()): Statement {
        val meta = gaps.getValue(id)
        val outcome = runCatching {
            when {
                src.startsWith("#") -> p.parseSharpLine(src, 1)
                src.startsWith("@") || src.startsWith("$") -> p.parseLabelLine(src, 1, "GOLDEN_ORACLE.ERB")
                else -> p.parseLogicalLine(LogicalLine(LineKind.CODE, "GOLDEN_ORACLE.ERB", 1, src))
            }
        }
        val s = outcome.getOrElse {
            throw AssertionError(
                "缺口台账前提被破坏：'$src' 目前应能解析（原版拒绝证据 ${meta.engineEvidence}），" +
                    "实际抛 ${it::class.simpleName}: ${it.message} —— 说明已收紧，请把本条迁到 PcRejectedSyntaxTest",
            )
        } ?: throw AssertionError("'$src' 解析为空语句")
        return s
    }

    // -----------------------------------------------------------------------
    // P1 —— 函数标签参数的语义模型
    // -----------------------------------------------------------------------

    @Test
    fun `P1 函数标签形参必须是可赋值变量（A3 收口）`() {
        // 原版：ReduceArguments(isDefine:true) 把每个形参解成 VariableTerm，
        // 未声明/字面量 → ArgCanOnlyAssignableVar 或标识符解析异常
        // （spec: ErbLoader.cs:651-652 / 658-702 / 769）。
        // 本条已从缺口台账迁入 lockedCategories，验收用例见 ParserStrictnessTest。
        val p = bare()
        for (src in listOf(
            "@GOLDEN_SUB, ARG0, ARG1",   // 未声明标识符（金标原样形态）
            "@MYFUNC(\"ABC\")",           // 字符串字面量
            "@MYFUNC(123)",               // 整数字面量
        )) {
            assertFailsWith<StmtParseException>("原版拒绝，我们必须同样拒绝：$src") {
                p.parseLabelLine(src, 1, "GOLDEN_ORACLE.ERB")
            }
        }
        // 原版合法：ARG/ARGS 内建变量；逗号式与括号式两种写法
        assertEquals(
            listOf("ARG", "ARG"),
            (p.parseLabelLine("@MYFUNC(ARG, ARG:1)", 1, "GOLDEN_ORACLE.ERB") as FuncLabelStmt).paramNames,
        )
        assertEquals(
            listOf("ARGS", "ARGS"),
            (p.parseLabelLine("@PRINTLINE, ARGS, ARGS:1 = \"-\"", 1, "GOLDEN_ORACLE.ERB") as FuncLabelStmt).paramNames,
        )
    }

    @Test
    fun `P1 方法实参类型与个数当前不检查`() {
        for (src in listOf(
            "VARSETEX FLAG, 9, 100, 102",
            "PRINTFORML V={HOTKEY_STATE_INIT(1, 0)}",
            "PRINTFORML V={GFILLRECTANGLE(10, 255, 10, 10, 20, 20)}",
            "PRINTFORML V={GDASHSTYLE(10, 0)}",
            "PRINTFORML V={XML_SET(\"GDOC\", \"/root/a[1]\", \"B1\")}",
            "PRINTFORML V={XML_ADDNODE(\"GDOC\", \"/root\", \"c\", \"CC\")}",
            "PRINTFORML V={XML_REPLACE(\"GDOC\", \"/root/a[2]\", \"<b>9</b>\")}",
            "PRINTFORML V={STRLENSU(RESULT)}",
            "PRINTFORML V={MYFUNC(1, 2, 3, 4, 5)}",
        )) {
            accept("METHOD_ARG_TYPE", src)
        }
    }

    // -----------------------------------------------------------------------
    // P2 —— config 门控的空白面（RESVAR_NAME 已收口，见 PcRejectedSyntaxTest）
    // -----------------------------------------------------------------------

    @Test
    fun `P2 全角空格分隔当前无条件接受`() {
        // 原版：'　' 仅当 Config.SystemAllowFullSpace=true 才合法（LogicalLineParser.cs:517-523），
        // 本作未开启 → `PRINTL　ABC` 在原版是 InvalidLine(InvalidCharacterAfterInstruction1)。
        // 该宽松面金标未触发，故列在 extraLeniency（不进 18 类覆盖断言）。
        assertTrue(extraLeniency.containsKey("FULLWIDTH_SEPARATOR_GATE"))
        for (src in listOf("PRINTL　ABC", "INPUT　100", "RESETCOLOR　")) {
            val e = runCatching {
                bare().parseLogicalLine(LogicalLine(LineKind.CODE, "GOLDEN_ORACLE.ERB", 1, src))
            }
            assertTrue(e.isSuccess, "当前实现接受全角空格分隔（收紧后本断言要翻转为失败）：$src → ${e.exceptionOrNull()}")
        }
        // 半角空白原版也接受（对照组，防止误收）
        assertIs<PrintStmt>(
            bare().parseLogicalLine(LogicalLine(LineKind.CODE, "GOLDEN_ORACLE.ERB", 1, "PRINTL ABC"))!!,
        )
    }

    // -----------------------------------------------------------------------
    // P3 —— 不属语句层的三类（记录归属，防止被误当成解析器缺陷）
    // -----------------------------------------------------------------------

    @Test
    fun `P3 存在性与函数级告警不在语句层`() {
        accept("MISSING_FUNCTION", "CALL GOLDEN_RNG")
        accept("MISSING_FUNCTION", "TRYCALL GOLDEN_RNG")
        accept("MISSING_FUNCTION", "CALLFORM GOLDEN_S{1+1}, 3")
        accept("DUP_FUNCTION", "@G12_PLAIN")
        accept("DUP_FUNCTION", "@G12_PLAIN")
        accept("ARG_UNDECLARED_WARN", "RESULT:0 = ARG:0 * ARG:0")
        accept("ARG_UNDECLARED_WARN", "RETURNF ARG:0 + 100")
    }

    // -----------------------------------------------------------------------
    // 台账完整性 + 交给组长的建模结论
    // -----------------------------------------------------------------------

    @Test
    fun `类别全覆盖`() {
        // oracle 的 19 类必须被「已锁」∪「缺口」精确覆盖：新增类别不建测试、或把已锁类别偷偷挪进
        // 缺口台账，都会让下面第一条断言红。
        assertEquals(oracleCategories, lockedCategories + gaps.keys, "已锁 ∪ 缺口 必须等于 oracle 类别全集")
        assertEquals(19, oracleCategories.size, "oracle 类别数 = 实测 18 + 对称类 FORM_PERCENT_NOT_STR")
        assertTrue(gaps.keys.all { it !in lockedCategories }, "同一类别不得既锁又缺口")
        assertEquals(gaps.size, gaps.values.map { it.engineEvidence }.distinct().size)
        assertTrue(gaps.values.all { it.engineEvidence.contains(Regex("\\.cs:\\d")) }, "证据必须是 文件:行号")
        assertTrue(gaps.values.all { it.owner.isNotEmpty() }, "每条缺口要标明收口位置")
        assertTrue(extraLeniency.keys.all { it !in oracleCategories }, "额外宽松面不得混入 oracle 类别")
    }

    @Test
    fun `SET 指令面永不产出语句`() {
        // 交给组长的结论性断言：FunctionCode.SET 只能由赋值两段式产出 AssignStmt；
        // 任何 `SET 目标, 值` 写法都在解析期失败 ⇒ core/compile/ErbProgramCompiler.kt:887-904
        // 把 SET 当通用赋值指令建模的分支，在原版语义下不可达（死代码）。
        val p = bare()
        val assign = p.parseLogicalLine(LogicalLine(LineKind.CODE, "GOLDEN_ORACLE.ERB", 1, "CNT = 1"))
        assertIs<AssignStmt>(assign!!)
        assertEquals(FunctionCode.SET, assign.code, "SET 只是赋值的伪码，不是语句名")
        for (bad in listOf("SET CNT, 1", "SET ARR, 1, 2", "SET GA, GA + 5", "SET WINDOW_TITLE, \"T\"")) {
            runCatching { p.parseLogicalLine(LogicalLine(LineKind.CODE, "GOLDEN_ORACLE.ERB", 1, bad)) }
                .onSuccess { throw AssertionError("'$bad' 在原版是「无法解析的行」，不得产出语句：$it") }
        }
    }
}
