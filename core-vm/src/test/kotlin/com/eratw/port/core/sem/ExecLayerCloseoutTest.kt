package com.eratw.port.core.sem

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.IntAsignArgument
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.PrintDataArgument
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.StrAsignArgument
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.stmt.VarDeclStmt
import com.eratw.port.core.vars.DimSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M2 A5b 收口测试（CONTRACT-M2-E2E §1 C1/C3/C4/C6 + §5 R3/R5）：
 * 1. PRINT 家族"等待按键"语义：N/W 后缀 → 换行且等待（R5 勘误，spec: FunctionIdentifier.cs:146-151
 *    PRINT_WAITINPUT=512(:3741) + ExpressionMediator.cs:59-65 NewLine()+ReadAnyKey()）；
 *    等待经 ExecContext.input 表达，MesSkip 为真时不发起（spec: EmueraConsole.cs:1451-1455 吞等待）。
 * 2. TRYC/PARTIAL(16) 语境标志：本轮仅在 ExecContext 开接口（判定归 A4/装载层，
 *    spec: EmueraConsole.cs:2216-2219；FunctionIdentifier.cs:3049-3052）。
 * 3. 语句池路径接线自检：用 LineParser 解析真实 ERB 行得到 Statement 实例，喂
 *    StatementExecutor.execute(stmt, ctx)（C3/C4 的分派形态），断言输出文本与 VariableStore 落值——
 *    专抓"池中取回的 AST 与 sem 期望形态不一致"的接线错。
 */
class ExecLayerCloseoutTest {

    private fun parser(vararg vars: Pair<String, OperandType>): LineParser {
        var sym = SymbolTable()
        for ((name, type) in vars) {
            // declareVariable(name, type, dimLen)：与 DimSpecLayoutTest/ErbProgramCompilerTest 同用法
            sym = sym.declareVariable(name, type, 1)
        }
        return LineParser(sym)
    }

    // ==================================================================
    // 1. PRINT 家族：换行 + 等待按键（R5）与 MesSkip 短路
    // ==================================================================

    @Test
    fun `PRINTN 换行且等待按键（R5：N 后缀同置 PRINT_WAITINPUT 512）`() {
        val env = SemTestEnv()
        val stmt = LineParser(SymbolTable()).parseCodeLine("PRINTN 换行等待文本", 3)
        env.run(stmt)
        // 反向证明：若按 isa.yaml 旧勘误"不换行不等待"，events 只有一条 print、anyKeyCalls 为空 → 红
        assertEquals(listOf("print(换行等待文本)", "newLine"), env.console.events)
        assertEquals(listOf(false to false), env.input.anyKeyCalls) // 恒为 stopMesSkip=false 的等键
    }

    @Test
    fun `PRINTW PRINTFORMW 等待，PRINTFORML 只换行不等待`() {
        val env = SemTestEnv()
        env.run(LineParser(SymbolTable()).parseCodeLine("PRINTW 文字等待", 1))
        assertEquals(listOf("print(文字等待)", "newLine"), env.console.events)
        assertEquals(1, env.input.anyKeyCalls.size)

        val env2 = SemTestEnv()
        env2.run(LineParser(SymbolTable()).parseCodeLine("PRINTFORMW 状态&警告", 2))
        assertEquals(1, env2.input.anyKeyCalls.size)

        // L 后缀只置 256（PRINT_NEWLINE）：换行但**不等待**
        val env3 = SemTestEnv()
        env3.run(LineParser(SymbolTable()).parseCodeLine("PRINTFORML 普通换行", 3))
        assertEquals(listOf("print(普通换行)", "newLine"), env3.console.events)
        assertEquals(0, env3.input.anyKeyCalls.size)

        // 裸 PRINT：既不换行也不等待
        val env4 = SemTestEnv()
        env4.run(LineParser(SymbolTable()).parseCodeLine("PRINT 同行文本", 4))
        assertEquals(listOf("print(同行文本)"), env4.console.events)
        assertEquals(0, env4.input.anyKeyCalls.size)
    }

    @Test
    fun `MesSkip 为真跳过等待但换行保留`() {
        val env = SemTestEnv()
        env.ctx.mesSkip = true
        env.run(LineParser(SymbolTable()).parseCodeLine("PRINTN 跳过模式文本", 1))
        assertEquals(listOf("print(跳过模式文本)", "newLine"), env.console.events)
        assertTrue(env.input.anyKeyCalls.isEmpty()) // 原版：控制台循环吞掉等待（EmueraConsole.cs:1451-1455）

        // 对照：MesSkip 关掉后同一语句必须发起等待（证明门控真的接在 mesSkip 上）
        env.ctx.mesSkip = false
        env.run(LineParser(SymbolTable()).parseCodeLine("PRINTN 不跳过", 2))
        assertEquals(1, env.input.anyKeyCalls.size)
    }

    @Test
    fun `PRINTDATAW 换行并等待，PRINTDATA 不等待，MesSkip 吞等待`() {
        val env = SemTestEnv()
        env.ctx.dataSections = dualKeySections { listOf(listOf(sLit("数据组行"))) }
        env.run(InstructionStmt(FunctionCode.PRINTDATA, PrintDataArgument(null), 1, "PRINTDATA"))
        assertEquals(listOf("print(数据组行)"), env.console.events)
        assertEquals(0, env.input.anyKeyCalls.size)

        env.run(InstructionStmt(FunctionCode.PRINTDATAW, PrintDataArgument(null), 2, "PRINTDATAW"))
        // 单行数据组：组内无换行（i<size-1 不成立），仅 W 后缀补一条 newLine
        assertEquals(
            listOf("print(数据组行)", "print(数据组行)", "newLine"),
            env.console.events,
        )
        assertEquals(1, env.input.anyKeyCalls.size)

        env.ctx.mesSkip = true
        env.run(InstructionStmt(FunctionCode.PRINTDATAW, PrintDataArgument(null), 3, "PRINTDATAW"))
        assertEquals(1, env.input.anyKeyCalls.size) // 跳过时不再追加等待
        assertEquals(2, env.console.events.count { it == "newLine" }) // 换行不受 MesSkip 影响
    }

    // ==================================================================
    // 2. TRYC/PARTIAL(16) 语境标志（R3：接口位，由 A4 ProcessRuntime 写入）
    // ==================================================================

    @Test
    fun `ExecContext 暴露 PARTIAL 语境标志位（默认关闭）`() {
        val env = SemTestEnv()
        assertEquals(false, env.ctx.inFunctionMethod) // EmueraConsole.cs:2216-2219 语境
        assertEquals(false, env.ctx.afterSifLine)     // FunctionIdentifier.cs:3049-3052 语境
        env.ctx.inFunctionMethod = true
        env.ctx.afterSifLine = true
        // TODO(verify)：sem 本轮无消费点（TRYC 已在编译期降级，PARTIAL 位在运行期不可见）；
        // 门控与装载报错复现由 A4 ProcessRuntime 落地，见 ExecContext 属性注释。
    }

    // ==================================================================
    // 3. 语句池路径接线自检（LineParser 真 AST → execute(Statement, ExecContext)）
    // ==================================================================

    @Test
    fun `池路径 PRINTS 变量取文与赋值落点正确`() {
        // 注：PRINTFORM 表单串内 `&AA` 在我们的 AST 里原样透传（未做 FORM 记号展开），
        // 与原版 FORM 语义的差异属 expr 层 FormStringTerm 接线（非本轮 sem 范围，已回报组长）。
        // 此处用 PRINTS（STR 表达式参数）验证 池→sem→VariableStore 同一条求值链。
        val env = SemTestEnv()
        env.store.declareUserVar(DimSpec("BS", true, intArrayOf(1)))
        val lp = parser("BS" to OperandType.STR)
        env.run(lp.parseCodeLine("BS = 池值", 1))
        env.run(lp.parseCodeLine("PRINTS BS", 2))
        assertEquals(listOf("print(池值)"), env.console.events)
    }

    @Test
    fun `池路径 赋值语句 AST 形态与 VariableStore 落值`() {
        val lp = parser("AA" to OperandType.INT, "BS" to OperandType.STR)
        val intStmt = lp.parseCodeLine("AA = 42", 5)
        assertIs<AssignStmt>(intStmt) // 池里取回的必须是 sem 期望的 AssignStmt 形态
        val env = SemTestEnv()
        env.store.declareUserVar(DimSpec("AA", false, intArrayOf(1)))
        env.store.declareUserVar(DimSpec("BS", true, intArrayOf(1)))
        env.run(intStmt)
        assertEquals(42L, env.store.readInt("AA", longArrayOf(0)))

        val strStmt = lp.parseCodeLine("BS = 文字值", 6)
        assertIs<AssignStmt>(strStmt)
        env.run(strStmt)
        assertEquals("文字值", env.store.readStr("BS", longArrayOf(0)))

        // 右端含变量引用的赋值：经同一 AssignStmt 路径求值落值（+= 复合格式的解析口径由 A3 决定，本处不测）
        env.run(lp.parseCodeLine("AA = AA + 8", 7))
        assertEquals(50L, env.store.readInt("AA", longArrayOf(0)))
    }

    @Test
    fun `池路径 PRINT 语句形态为 PrintStmt 且 flag 位驱动输出`() {
        val stmt = LineParser(SymbolTable()).parseCodeLine("PRINTFORMN 池行等待", 9)
        assertIs<PrintStmt>(stmt)
        assertEquals(512, stmt.print.flag and 512) // N → PRINT_WAITINPUT 位必须在 AST 上可见
        val env = SemTestEnv()
        env.run(stmt)
        assertEquals(listOf("print(池行等待)", "newLine"), env.console.events)
        assertEquals(1, env.input.anyKeyCalls.size)
    }

    @Test
    fun `VARI VARS 按禁用语义报错（UseScopedVariableInstruction false）`() {
        // spec: IdentifierDictionary.cs:624-627（原版解析期即 CodeEE CanNotUseVAR；本游戏 setting.json=false）
        val env = SemTestEnv()
        val e1 = assertFailsWith<CodeEE> {
            env.run(VarDeclStmt(FunctionCode.VARI, IntAsignArgument("GA", IntArray(0), null), 1, "VARI GA"))
        }
        assertTrue(e1.message!!.contains("VARI"))
        assertFailsWith<CodeEE> {
            env.run(VarDeclStmt(FunctionCode.VARS, StrAsignArgument("GSB", IntArray(0), "初值"), 2, "VARS GSB"))
        }
        // 反向证明：若误开 config，则不走报错分支（初值语义另有 TODO(verify)）
        val env2 = SemTestEnv(
        ).also {
            it.ctx = ExecContext(
                variables = it.store,
                eval = TestEvalContext(it.store),
                console = it.console,
                input = it.input,
                config = SemConfig(useScopedVariableInstruction = true),
            )
        }
        env2.run(VarDeclStmt(FunctionCode.VARI, IntAsignArgument("GA", IntArray(0), IntLiteral(3)), 1, "VARI GA, 3"))
        // store 无该变量声明时 VARI 运行期为 no-op（execVarDecl 的 userVar ?: return），不抛错即为期望
    }
}
