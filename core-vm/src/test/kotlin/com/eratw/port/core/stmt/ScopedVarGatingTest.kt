package com.eratw.port.core.stmt

import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **VARI/VARS 的 UseScopedVariableInstruction 门控（合同 §7 #11 P0-1，A3）**
 *
 * 原版语义（唯一定语义依据，reference/emuera-cs）：
 * - 注册面：`FunctionIdentifier.cs:4123-4127` —— 仅 `JSONConfig.Data.UseScopedVariableInstruction`
 *   为 true 时才 `addFunction(FunctionCode.VARI/VARS, …)`；关闭时这两个名字**根本不进 funcDic**。
 *   门控名单恰为 **VARI、VARS** 两条（FunctionIdentifier.cs:4125-4126）；
 *   任务描述中的 "variByRef" 在 C# 参考与 Kotlin 代码库中均无对应（grep 0 命中），不在门控面内。
 * - 拒绝点：`IdentifierDictionary.cs:624-627` `ThrowException` —— 开关关闭且标识符为
 *   "VARI"/"VARS" 时抛 `CodeEE(Lang.Error.CanNotUseVAR.Text)`；
 *   消息模板 `Lang.cs:4010`「{0}命令は現在の設定では使用できません」。
 * - 解析路径：`LogicalLineParser.cs:406-411`（GetFunctionIdentifier 未命中 funcDic）→
 *   赋值两段式（:530-556）→ 目的表达式的标识符解析期抛错
 *   （ExpressionParser.cs:444 → ThrowException → CanNotUseVAR）。
 *
 * 本移植的门控点：`LineParser.parseInstructionLine` 顶部 —— 语句名命中 VARI/VARS 且开关关闭时，
 * **先于参数切分**抛 [StmtParseException]（对应原版 InvalidLine 级致命拒绝；
 * 「解析期立即抛出」纪律见 ParseSupport.kt:22-28）。
 *
 * 开关传入：`LineParser(resolver, stmtConfig = StmtParseConfig(useScopedVariableInstruction = …))`；
 * 默认 true（门开）= 与现状兼容（老构造 `LineParser(resolver)` 行为不变），
 * 本作生产口径（setting.json false）显式传 false。
 *
 * 覆盖矩阵：开关开/关两态 × VARI/VARS 各语句名形态 × 错误口径（CanNotUseVAR 文案 +
 * 解析期致命异常类型），入口用真实 [LineParser.parseLogicalLine]（语句级解析）。
 */
class ScopedVarGatingTest {

    private fun logical(text: String, lineNo: Int = 10): LogicalLine =
        LogicalLine(LineKind.CODE, "scoped-var.erb", lineNo, text)

    private fun parse(p: LineParser, text: String, lineNo: Int = 10): Statement? =
        p.parseLogicalLine(logical(text, lineNo))

    /** 本作口径：setting.json `UseScopedVariableInstruction:false`。 */
    private fun closed(): LineParser = LineParser(
        SymbolTable().declareVariable("CNT", OperandType.INT, 1),
        stmtConfig = StmtParseConfig(useScopedVariableInstruction = false),
    )

    /** 显式开门。 */
    private fun open(): LineParser = LineParser(
        SymbolTable().declareVariable("CNT", OperandType.INT, 1),
        stmtConfig = StmtParseConfig(useScopedVariableInstruction = true),
    )

    /** 老构造（不传 stmtConfig）：必须与现状兼容 = 门开。 */
    private fun legacy(): LineParser = LineParser(SymbolTable())

    // -----------------------------------------------------------------------
    // 门控关闭：VARI / VARS 各形态一律 CanNotUseVAR 同口径拒绝
    // -----------------------------------------------------------------------

    @Test
    fun `门控关闭时 VARI 各形态被拒`() {
        val p = closed()
        for (src in listOf(
            "VARI GA, 10",           // 长度式（金标形态）
            "VARI ARR2, 10, 20",     // 多维长度式
            "VARI CNT = 5",          // 初值式
            "VARI CNT",              // 裸名
            "VARI",                  // 裸语句名
            "vari ga, 10",           // 小写：语句名分派大小写不敏感，同样命中门控
        )) {
            val e = assertFailsWith<StmtParseException>("门控关闭必须拒绝：$src") { parse(p, src) }
            assertContains(e.message ?: "", "当前设置", message = "应体现 CanNotUseVAR 口径：${e.message}")
            assertEquals(10, e.line, "行号必须透传：$src")
        }
    }

    @Test
    fun `门控关闭时 VARS 各形态被拒`() {
        val p = closed()
        for (src in listOf(
            "VARS GSB = \"hello\"",
            "VARS GSB, 10",
            "VARS GSB",
            "VARS",
            "Vars gsb = \"x\"",
        )) {
            val e = assertFailsWith<StmtParseException>("门控关闭必须拒绝：$src") { parse(p, src) }
            assertContains(e.message ?: "", "当前设置", message = "应体现 CanNotUseVAR 口径：${e.message}")
        }
    }

    @Test
    fun `门控关闭时的错误口径是解析期致命异常`() {
        // 原版：CodeEE → InvalidLine（装载期致命；ErbLoader.cs:469-490 触发
        // 「由于ERB脚本出现无法解释的行，Emuera停止运行」，Process.cs:2124-2128）。
        // 本移植：StmtParseException（解析期立即抛出，ParseSupport.kt:22-28）。
        val e = assertFailsWith<StmtParseException> { parse(closed(), "VARI GA, 10") }
        assertTrue(e is RuntimeException, "StmtParseException 必须是 RuntimeException 子类")
        assertEquals("VARI", e.statementName, "statementName 必须是语句名原文")
        assertContains(e.message ?: "", "VARI")
        assertContains(e.message ?: "", "UseScopedVariableInstruction", message = "文案要点名开关：${e.message}")
    }

    @Test
    fun `门控关闭先于参数形态校验`() {
        // 原版开关关闭时语句名解析即失败，永远走不到参数校验：`VARI,GA`（指令名后非法分隔）
        // 报的也是 CanNotUseVAR，而不是 splitArgs 的「指令后跟随非法字符」。
        val e = assertFailsWith<StmtParseException> { parse(closed(), "VARI,GA") }
        assertContains(e.message ?: "", "当前设置", message = "非法分隔形态也必须先过门控：${e.message}")
    }

    // -----------------------------------------------------------------------
    // 门控打开：VARI / VARS 正常声明（可反向证明：拒绝只来自门控，不是语句面坏了）
    // -----------------------------------------------------------------------

    @Test
    fun `门控打开时 VARI 正常声明`() {
        val vari = assertIs<VarDeclStmt>(parse(open(), "VARI X, 10, 20"))
        assertEquals(FunctionCode.VARI, vari.code)
        val ia = assertIs<IntAsignArgument>(vari.args)
        assertEquals("X", ia.name)
        assertEquals(listOf(10, 20), ia.lengths.toList())

        val init = assertIs<VarDeclStmt>(parse(open(), "VARI Y = 5"))
        assertEquals(5L, (assertIs<IntAsignArgument>(init.args).exp as IntLiteral).value)
    }

    @Test
    fun `门控打开时 VARS 正常声明`() {
        // 带维度形态不吃初值（spec: LogicalLineParser.cs:485-493，初值只在 array.Length == 1
        // 分支读取——2d4d8da 对齐原版后口径；本用例由旧「带维度也吃初值」改钉）。
        val vars = assertIs<VarDeclStmt>(parse(open(), "VARS S$, 10 = \"hi\""))
        assertEquals(FunctionCode.VARS, vars.code)
        val sa = assertIs<StrAsignArgument>(vars.args)
        assertEquals("S$", sa.name)
        assertEquals(listOf(10), sa.lengths.toList())
        assertNull(sa.value, "带维度形态不吃初值（spec: LogicalLineParser.cs:485-493）")
        // 无维度形态照常吃初值（同提交 2d4d8da 的正向口径）。
        val varsInit = assertIs<VarDeclStmt>(parse(open(), "VARS T$ = \"hi\""))
        assertEquals("hi", assertIs<StrAsignArgument>(varsInit.args).value)
    }

    @Test
    fun `老构造不传 stmtConfig 时行为不变（现状兼容锁）`() {
        // LineParserTest.kt:328-341 等既有测试依赖老构造可解析 VARI/VARS；
        // 本断言锁住「StmtParseConfig 默认值 = 现状兼容（门开）」：
        // 若有人把默认值翻成 false，必须先同步更新 LineParserTest.kt:328-341
        // （该文件不归 A3 所有权），否则本断言与那条既有测试会一起红。
        assertIs<VarDeclStmt>(parse(legacy(), "VARI GA, 10"))
        assertIs<VarDeclStmt>(parse(legacy(), "VARS GSB = \"hello\""))
    }

    @Test
    fun `门控关闭不影响其他语句`() {
        val p = closed()
        assertIs<PrintStmt>(parse(p, "PRINTL hello"))
        assertIs<InstructionStmt>(parse(p, "RESETCOLOR"))
        assertIs<AssignStmt>(parse(p, "CNT = 1"))
    }
}
