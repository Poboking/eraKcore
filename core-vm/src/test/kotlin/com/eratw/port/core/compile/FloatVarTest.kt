package com.eratw.port.core.compile

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.VmConfig
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.BuiltinSymbols
import com.eratw.port.core.stmt.DefinitionScopedResolver
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.StmtParseException
import com.eratw.port.core.stmt.StrictSymbolResolver
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.vars.VarType
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 4.981 fork 浮点变量族的**解析/编译面**用例（`#DIMF` + `LOCALF`/`ARGF`）。
 *
 * 语料实证（本路唯一两条触发点）：
 * - `DLC/魔法DLC/魔法相关函数.ERB:3190` `#DIMF DYNAMIC 倍率`（全语料唯一一条 `#DIMF`）；
 *   `:3213` `倍率 = 1.0`、`:3215` `倍率 *= 1.2`、`:3218` `IF 倍率 > 1.0`、`:3219` `TOINT(倍率)`。
 * - `DLC/魔法DLC/使用魔法.ERB:1248-1256` `LOCALF = 0.0` / `LOCALF += YOGORE:(LOCAL) * 0.01` / `TOINT(LOCALF)`。
 *
 * 语义出处（spec: variables.yaml 的 float_vars 一段）：`#DIMF` 只允许 1 维，2D/3D 一律
 * ExeEE(AbnormalVarDeclaration)（fork-src VariableData.cs:469-474/553-561/586-593）；
 * `#DIMF CHARADATA` 在 fork 里掉进**整数**分支（VariableData.cs:410-429，ADR-005 原样复刻）。
 *
 * 口径边界：解析/编译面与**名字可见性**已收编完成 —— `LOCALF`/`ARGF`/`RESULTF` 由
 * [com.eratw.port.core.stmt.BuiltinSymbols] 正式登记（1 维 / 0 维 FLOAT），原先挂在
 * `LineParser` 前面的 FloatLocalResolver 补丁层已删除；`#DIMF` 的预扫描与侧车见
 * `tools/erbcc` 的 `DimfSidecarTest`，侧车 v2 起携带 `isFloat`。运行期浮点存储与执行链
 * 已接入（`VariableStore` 的 floatScalars / LOCALF·ARGF 实例、`ArgBuilders` 的 FLOAT
 * 左值支、`StatementExecutor` 的 execAssign 与 execSetFlat 两条 SET 路径），完整的
 * 运行期断言面见 `vars/FloatVarRuntimeTest`。仍待收口：`#DIMF` 初值
 * （`DimSpec.defaultsFloat`）、`#LOCALFSIZE` 生效长度（spec 的 `float_vars.dimf_default_value`）。
 */
class FloatVarTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /**
     * 带「当前 `@函数` 私有表」的解析器替身：语义与 `tools/erbcc` 的 `CompileResolver` 同口径
     * （进入定义即清表、`declarePrivateVariable` 即时登记），并记录登记明细供断言。
     */
    private class ScopeResolver(private val inner: SymbolTable = SymbolTable()) :
        DefinitionScopedResolver {

        private var cur = LinkedHashMap<String, VarInfo>()

        /** 登记序（名字、类型、维度）。 */
        val declarations = ArrayList<Triple<String, OperandType, IntArray>>()

        override fun resolveVariable(name: String, subKey: String?): VarInfo? =
            cur[name.uppercase()] ?: inner.resolveVariable(name, subKey)

        override fun resolveFunction(name: String): OperandType? = inner.resolveFunction(name)

        override fun canBeReferencedBare(name: String): Boolean = inner.canBeReferencedBare(name)

        override fun isUserMethod(name: String): Boolean = inner.isUserMethod(name)

        override fun enterFunction(functionName: String?) {
            cur = LinkedHashMap()
        }

        override fun enterFunctionDefinition(functionName: String?, filename: String, line: Int) {
            cur = LinkedHashMap()
        }

        override fun declarePrivateVariable(name: String, type: OperandType, dimensions: IntArray) {
            cur[name.uppercase()] = VarInfo(
                name = name,
                operandType = type,
                dimension = dimensions.size.coerceIn(1, 3),
                isCharacterData = false,
                isConst = false,
            )
            declarations.add(Triple(name, type, dimensions))
        }
    }

    private fun kindOf(text: String): LineKind = when {
        text.startsWith("@") -> LineKind.FUNCTION_LABEL
        text.startsWith("$") -> LineKind.GOTO_LABEL
        text.startsWith("#") -> LineKind.SHARP
        else -> LineKind.CODE
    }

    private fun parseWith(
        resolver: ScopeResolver,
        vararg lines: String,
    ): List<com.eratw.port.core.stmt.Statement> {
        val p = LineParser(resolver)
        return lines.mapIndexedNotNull { i, text ->
            p.parseLogicalLine(LogicalLine(kindOf(text), "float.erb", i + 1, text))
        }
    }

    /** 解析 + 编译成 [Program]（不执行，见类 KDoc 的口径边界）。 */
    private fun compileProgram(vararg lines: String): Pair<Program, ScopeResolver> =
        compileProgramWith(SymbolTable(), *lines)

    private fun compileProgramWith(
        inner: SymbolTable,
        vararg lines: String,
    ): Pair<Program, ScopeResolver> {
        val r = ScopeResolver(inner)
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("T"), parseWith(r, *lines), setOf("T"))
        return c.build("T") to r
    }

    private fun assignStmt(stmts: List<com.eratw.port.core.stmt.Statement>, line: Int): AssignStmt? =
        stmts.filterIsInstance<AssignStmt>().firstOrNull { it.line == line }

    // ------------------------------------------------------------------
    // #DIMF 声明
    // ------------------------------------------------------------------

    /**
     * `#DIMF DYNAMIC 倍率`：名字 = `倍率`（不是修饰符 DYNAMIC），且登记成 **1 维 FLOAT**。
     *
     * 「无尺寸段补 1 维」的出处是 `UserDefinedVariableData.cs:292-299`（`wc.EOL` 分支
     * `list.Add(1)`），与本仓 `DimParser` 末尾 `if (dims.isEmpty()) dims.add(1)` 同口径。
     */
    @Test
    fun `DIMF声明解析出浮点名`() {
        val r = ScopeResolver()
        val stmt = parseWith(r, "@永恒回归_倍率更新", "#DIMF DYNAMIC 倍率")
            .filterIsInstance<SharpStmt>().single()
        assertEquals("DIMF", stmt.keyword)
        assertEquals("倍率", stmt.name, "名字必须是 倍率，不能被 DYNAMIC 吃掉（UserDefinedVariableData.cs:44-70）")
        assertFalse(stmt.isString, "#DIMF 是数值型")
        val decl = r.declarations.single()
        assertEquals("倍率", decl.first)
        assertEquals(OperandType.FLOAT, decl.second, "4.981 fork：`#DIMF` 建浮点 token")
        assertEquals(listOf(1), decl.third.toList(), "无尺寸段 ⇒ 原版补 [1]（UserDefinedVariableData.cs:292-299）")

        // 后续行可达该名字（同一 @定义 内）：
        val assign = assertIs<AssignStmt>(parseWith(r, "@永恒回归_倍率更新", "#DIMF DYNAMIC 倍率", "倍率 = 1.0")[2])
        assertEquals(OperandType.FLOAT, assign.dest.operandType, "私有浮点名的解析类型必须是 FLOAT")
    }

    /** 2D/3D `#DIMF` → ExeEE(AbnormalVarDeclaration) 口径（声明层 CodeEE → 语句层带行号异常）。 */
    @Test
    fun `DIMF多维声明被拒`() {
        val r = ScopeResolver()
        val e = assertFailsWith<StmtParseException> {
            parseWith(r, "@F", "#DIMF DYNAMIC X, 3, 4")
        }
        assertTrue(
            e.message.orEmpty().contains("1 维"),
            "文案应指向 1 维限制（fork-src VariableData.cs:553-561）；实得 ${e.message}",
        )
    }

    /**
     * `#DIMF CHARADATA` 的 fork quirk：`CreateUserDefCharaVariable`（fork-src
     * VariableData.cs:410-429）只有 `TypeIsStr` / else(整数) 两支 ⇒ 浮点被折成**整数**。
     * ADR-005 要求原样复刻，这里锁住「不减配成浮点」（`DimSpec.isFloat == false`）。
     */
    @Test
    fun `DIMF_CHARADATA掉进整数分支`() {
        val spec = DimParser.parse("#DIMF", "CHARADATA X", isPrivate = false)
        assertTrue(spec.isCharaData, "CHARADATA 修饰照旧生效")
        assertFalse(spec.isFloat, "quirk：fork 只有 TypeIsStr/整数两支 ⇒ 浮点声明被当整数（原样复刻）")
        assertEquals(VarType.INT, spec.toVarDef(index = 0, chara = true, code = 0).type)
    }

    // ------------------------------------------------------------------
    // LOCALF / ARGF 名字可见性
    // ------------------------------------------------------------------

    /**
     * `LOCALF`/`ARGF` 是 1 维浮点局部名（spec: variables.yaml:99-100；fork-src
     * `VariableData.cs:376-379` / `ConstantData.cs:211-217` 定长 10）。
     * 名字可见性现在**只**来自 [BuiltinSymbols] 的正式登记（补丁层已删）。
     */
    @Test
    fun `LOCALF_ARGF按浮点名字解析`() {
        val r = ScopeResolver()
        val stmts = parseWith(r, "@T", "LOCALF = 0.0", "ARGF = 1.0")
        for (i in 1..2) {
            val a = assertIs<AssignStmt>(stmts[i])
            assertEquals(OperandType.FLOAT, a.dest.operandType, "第 $i 行 `LOCALF`/`ARGF` 必须按 FLOAT 解析")
            assertIs<VarRef>(a.dest)
        }
    }

    /** 正式登记面：两名都在 BuiltinSymbols，1 维 FLOAT（解析期最小集；容量归 VariableStore）。 */
    @Test
    fun `LOCALF_ARGF在正式符号表登记`() {
        for (name in listOf("LOCALF", "ARGF")) {
            val spec = assertNotNull(BuiltinSymbols.variable(name), "$name 必须登记在 BuiltinSymbols")
            assertEquals(OperandType.FLOAT, spec.type, "$name 是浮点型（spec: variables.yaml:99-100）")
            assertEquals(1, spec.dimension, "$name 是 1 维（getFloatValue 取 1 下标）")
            assertFalse(spec.isCharacterData)
        }
    }

    /**
     * 补丁层已删除：`LineParser.resolver` 就是构造入参本身（不再套 FloatLocalResolver），
     * 但 `LOCALF` 仍按 FLOAT 解析 —— 证明可见性走的是正式符号表而非包装器。
     */
    @Test
    fun `LOCALF名字解析不依赖补丁层`() {
        val p = LineParser(StrictSymbolResolver)
        assertSame(StrictSymbolResolver, p.resolver, "LineParser 不得再套 FloatLocalResolver 包装层")
        val a = assertIs<AssignStmt>(
            p.parseLogicalLine(LogicalLine(LineKind.CODE, "float.erb", 1, "LOCALF = 0.0")),
        )
        assertEquals(OperandType.FLOAT, a.dest.operandType, "无包装层时 LOCALF 仍必须是 FLOAT")
    }

    /**
     * `#LOCALFSIZE n`（4.981 fork；spec: variables.yaml:99 的 float_vars 备注，
     * fork-src LogicalLineParser.cs:257-266）与 `#LOCALSIZE` 同形：语句层切出数值。
     * 写进函数元数据（FuncMeta localFSize / 侧车 / VariableStore）属跨路接线，见报告。
     */
    @Test
    fun `LOCALFSIZE声明切出数值`() {
        val stmts = parseWith(ScopeResolver(), "@F", "#LOCALFSIZE 16")
        val sharp = assertIs<SharpStmt>(stmts[1])
        assertEquals("LOCALFSIZE", sharp.keyword.uppercase())
        assertEquals(listOf(16L), sharp.values, "#LOCALFSIZE 的值必须进 SharpStmt.values")
    }

    // ------------------------------------------------------------------
    // 端到端：解析 + 编译
    // ------------------------------------------------------------------

    /** `使用魔法.ERB:1248-1256` 的最小复刻：`LOCALF = 0.0` → `+= x * 0.01` → `TOINT(LOCALF)`。 */
    @Test
    fun `LOCALF累加与TOINT可编译`() {
        val (program, r) = compileProgram(
            "@MAGIC_USE",
            "LOCALF = 0.0",
            "LOCALF += A * 0.01",
            "A = TOINT(LOCALF)",
        )
        assertTrue(program.statementCount >= 3, "三条赋值都应入语句池；实得 ${program.statementCount}")
        val destTypes = program.statements().filterIsInstance<AssignStmt>().map { it.dest.name }
            .filter { it != "RESULT" } // 编译器在函数尾部补的 `RESULT = …`（与浮点无关）
        assertEquals(listOf("LOCALF", "LOCALF", "A"), destTypes)
        // 解析层保住了浮点：`LOCALF += A * 0.01` 的右值是 FLOAT 结点（dest 也是 FLOAT）。
        val plus = assertNotNull(
            assignStmt(parseWith(r, "@MAGIC_USE", "LOCALF += A * 0.01"), 2),
            "`LOCALF += A * 0.01` 应解析成一条赋值",
        )
        val term = assertIs<Binary>((plus.args as SpSetArgument).term)
        assertEquals(OperandType.FLOAT, term.operandType, "A * 0.01 的类型提升结果是 FLOAT")
    }

    /** `魔法相关函数.ERB:3190-3219` 的最小复刻：`#DIMF` 声明 + `倍率 *= 1.2` + `IF 倍率 > 1.0`。 */
    @Test
    fun `DIMF倍率循环可编译`() {
        val inner = SymbolTable().declareVariable("乘数", OperandType.INT, 1)
        val (program, r) = compileProgramWith(
            inner,
            "@永恒回归_倍率更新",
            "#DIM DYNAMIC 乘数",
            "#DIMF DYNAMIC 倍率",
            "乘数 = 3",
            "倍率 = 1.0",
            "WHILE 乘数 > 0",
            "倍率 *= 1.2",
            "乘数 --",
            "WEND",
            "IF 倍率 > 1.0",
            "A = TOINT(倍率) + 5",
            "ELSE",
            "A = 0",
            "ENDIF",
        )
        val names = program.statements().filterIsInstance<AssignStmt>().map { it.dest.name }
        assertTrue("倍率" in names, "`倍率 = 1.0` 必须入语句池；实得 $names")
        assertEquals(
            OperandType.FLOAT, r.declarations.single { it.first == "倍率" }.second,
            "`#DIMF` 登记的类型是 FLOAT",
        )
    }

    // ------------------------------------------------------------------
    // 端到端：解析 + 编译 + **执行**（f1 波接上浮点存储面与执行链之后才有的一条）
    // ------------------------------------------------------------------

    /**
     * 同一组形态跑起来：`LOCALF = 0.0` → `LOCALF += A * 0.01`（A=125）→ `B = TOINT(LOCALF)`。
     *
     * 与 [LOCALF累加与TOINT可编译] 的区别就是这一条**执行**：修复前浮点左值掉进字符串写通道，
     * 编译全绿而执行语义错；现在断言落进 [VariableStore] 的是 `1.25` 这个 double，
     * 且 `TOINT` 从表达式链拿到的是 `FloatV(1.25)` 而截成 1。
     *
     * 出程序后再读 `LOCALF` 必须重新进一次那个函数（局部变量在函数外不可访问，
     * 与 `LOCAL` 同口径）；实例本身不随弹帧销毁。
     */
    @Test
    fun `浮点左值可执行`() {
        val (program, _) = compileProgram(
            "@T",
            "LOCALF = 0.0",
            "A = 125",
            "LOCALF += A * 0.01",
            "B = TOINT(LOCALF)",
        )
        val store = VariableStore()
        ProcessRuntime(
            program,
            store,
            FunctionTable(listOf(FuncMeta(index = 0, key = "T", defIndex = 0))),
            VmConfig(maxStepsPerRun = 50_000L),
        ).execute()

        store.enterFunction("T")
        val localF = store.readFloat("LOCALF", longArrayOf(0))
        store.leaveFunction()
        assertEquals(1.25, localF, "0.0 + 125*0.01 必须精确落进浮点列（不是 1.0、不是 1）")
        assertEquals(1L, store.readInt("B", longArrayOf(0)), "TOINT 经表达式链读到的是 FloatV(1.25) ⇒ 截断为 1")
    }
}
