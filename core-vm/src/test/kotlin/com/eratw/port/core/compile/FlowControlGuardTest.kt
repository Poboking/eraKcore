package com.eratw.port.core.compile

import com.eratw.port.core.code.Opcode
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LineKind
import com.eratw.port.core.lexer.LogicalLine
import com.eratw.port.core.stmt.BlockChecker
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 流程控制「块边界/修饰边界」的装载期报错锁，逐条钉原版 C# 装载器行号：
 *
 * - SIF 修饰边界：SIF_Instruction.SetJumpTo（FunctionIdentifier.cs:3038-3070）
 *   —— NothingAfterSif（:3041-3046，目标为函数标签行/越过函数末尾）、
 *   FuncCanNotAfterSif（:3049-3052，IsPartial() = flag & 0x10，:4265-4268）、
 *   LabelCanNotAfterSif（:3058-3061，GotoLabelLine）。
 * - CATCH 配对：ErbLoader.cs:1338-1348 —— 栈顶不是 TRY* 系时报 MissingTryc
 *   （isError）且**不把 CATCH 压栈**，于是 ENDCATCH 在 :1351-1356 再报一次。
 * - TRY*LIST 体内只允许 FUNC/ENDFUNC：ErbLoader.cs:1090-1096
 *   InvalidInstructionInSyntax（isError）。
 */
class FlowControlGuardTest {

    // ------------------------------------------------------------------
    // 基础设施（同 JumpCompileTest 口径）
    // ------------------------------------------------------------------

    private fun parser(): LineParser = LineParser(
        SymbolTable()
            .declareVariable("A", OperandType.INT, 1)
            .declareVariable("RESULT", OperandType.INT, 0)
            .declareFunction("MYFUNC", OperandType.INT),
    )

    private fun parse(vararg lines: String): List<com.eratw.port.core.stmt.Statement> {
        val p = parser()
        return lines.mapIndexed { i, text ->
            val kind = when {
                text.startsWith("@") -> LineKind.FUNCTION_LABEL
                text.startsWith("$") -> LineKind.GOTO_LABEL
                text.startsWith("#") -> LineKind.SHARP
                else -> LineKind.CODE
            }
            p.parseLogicalLine(LogicalLine(kind, "test.erb", i + 1, text))
        }.filterNotNull()
    }

    private fun compileOne(vararg lines: String) {
        val c = ErbProgramCompiler()
        c.compileFunction(FuncSig("TEST"), parse(*lines))
        c.build()
    }

    // ------------------------------------------------------------------
    // F1：SIF 修饰边界（FunctionIdentifier.cs:3038-3070）
    // ------------------------------------------------------------------

    @Test
    fun `SIF 之后是 GOTO 标签行报错`() {
        // spec: FunctionIdentifier.cs:3058-3061 LabelCanNotAfterSif
        val e = assertFailsWith<CompileException> {
            compileOne("SIF A", "\$TAIL", "PRINTL \"X\"")
        }
        assertTrue("标签" in (e.message ?: ""), "应报 LabelCanNotAfterSif，实际=${e.message}")
    }

    @Test
    fun `SIF 之后紧跟函数标签行报错`() {
        // spec: FunctionIdentifier.cs:3041-3046 —— nextLine is FunctionLabelLine
        val e = assertFailsWith<CompileException> {
            compileOne("SIF A", "@NEXTFUNC")
        }
        assertTrue("标签" in (e.message ?: ""), "应报 NothingAfterSif，实际=${e.message}")
    }

    @Test
    fun `SIF 是函数最后一条语句报错`() {
        // spec: FunctionIdentifier.cs:3041-3046 —— nextLine 越过函数末尾
        // （下一条 FunctionLabelLine / 文件尾 NullLine 均命中判空分支）。
        val e = assertFailsWith<CompileException> {
            compileOne("PRINTL \"X\"", "SIF A")
        }
        assertTrue("NothingAfterSif" in (e.message ?: ""), "应报 NothingAfterSif，实际=${e.message}")
    }

    @Test
    fun `SIF 之后是 DATA 报错`() {
        // spec: FunctionIdentifier.cs:3049-3052 FuncCanNotAfterSif；
        // DATA/DATAFORM 的 flag=22 含 0x10（:4018-4019），且 role=NONE 逃过块族判据。
        for (src in listOf("DATA 文本", "DATAFORM 文本")) {
            val e = assertFailsWith<CompileException> {
                compileOne("SIF A", src)
            }
            assertTrue("FuncCanNotAfterSif" in (e.message ?: ""), "$src 应报 FuncCanNotAfterSif，实际=${e.message}")
        }
    }

    @Test
    fun `SIF 与目标之间的 # 元数据行透明`() {
        // # 行在原版不进逻辑行链（ErbLoader.cs:413-422 解析后直接 continue，不 addLine），
        // 故 SIF 的目标仍是其后第一条可执行语句：`SIF A / #DIM … / PRINTL Y / PRINTL Z`
        // 的补跳必须落在 PRINTL Z 上，而不是把 #DIM 当目标消费掉。
        val c = ErbProgramCompiler()
        val fb = c.compileFunction(
            FuncSig("TEST"),
            parse("SIF A", "#DIM TMPVAL", "PRINTL \"Y\"", "PRINTL \"Z\""),
        )
        val p = c.build()
        val jumps = (fb.entryPc until fb.endPc)
            .map { it to p.instructionAt(it) }
            .filter { it.second.op == Opcode._JUMP_IF_FALSE }
        assertEquals(1, jumps.size, "SIF 只发射一条 _JUMP_IF_FALSE，实际 ${jumps.size}")
        val (pc, ins) = jumps.single()
        val target = pc + 1 + ins.b
        val t = p.instructionAt(target)
        assertEquals(Opcode.PRINT, t.op, "补跳目标应是语句 marker（PRINTL 归一为 PRINT，OpcodeTable.kt:710）")
        assertEquals("PRINTL \"Z\"", p.statement(t.a).raw, "SIF 的目标是 # 行之后那条可执行语句")
    }

    @Test
    fun `SIF 后恰有一条语句（函数末条）合法`() {
        // 原版不报错（nextLine.NextLine 落到下一条 FunctionLabelLine，非空）；
        // 运行期 JumpTo 落到 FunctionLabelLine 即 Return(0)（Process.cs runScriptProc），
        // 等价于跳过该条后收尾 —— 与本侧补跳口径一致。
        compileOne("SIF A", "PRINTL \"ONLY\"")
    }

    // ------------------------------------------------------------------
    // F2：CATCH 缺少 TRY* 系栈顶（ErbLoader.cs:1338-1348 MissingTryc）
    // ------------------------------------------------------------------

    @Test
    fun `CATCH 之前没有 TRYC 报错且不压栈`() {
        val p = parser()
        val stmts = listOf("CATCH", "ENDCATCH").mapIndexed { i, t ->
            p.parseLogicalLine(LogicalLine(LineKind.CODE, "test.erb", 10 + i, t))!!
        }
        val errors = BlockChecker().check(stmts)
        // 原样复刻：CATCH 行报 MissingTryc 后不压栈，ENDCATCH 因栈顶不是 CATCH
        // 再报一次（ErbLoader.cs:1351-1356 UnexpectedEndcatch）。
        assertEquals(2, errors.size, "CATCH 与 ENDCATCH 各报一次，实际=$errors")
        assertTrue(errors[0].message.contains("TRYC"), "应报 MissingTryc，实际=${errors[0].message}")
        assertTrue(errors[1].message.contains("ENDCATCH"), "ENDCATCH 应报不匹配，实际=${errors[1].message}")
    }

    @Test
    fun `合法 TRYC CATCH ENDCATCH 无块错误`() {
        val p = parser()
        val stmts = listOf("TRYCGOTO LAB", "CATCH", "ENDCATCH", "\$LAB").mapIndexed { i, t ->
            val kind = if (t.startsWith("$")) LineKind.GOTO_LABEL else LineKind.CODE
            p.parseLogicalLine(LogicalLine(kind, "test.erb", 10 + i, t))!!
        }
        assertTrue(BlockChecker().check(stmts).isEmpty())
    }

    // ------------------------------------------------------------------
    // F3：TRY*LIST 体内只允许 FUNC/ENDFUNC（ErbLoader.cs:1090-1096）
    // ------------------------------------------------------------------

    @Test
    fun `TRYCALLLIST 体内普通语句报错`() {
        val known = setOf("GOLDEN_LISTCALLED")
        val e = assertFailsWith<CompileException> {
            val c = ErbProgramCompiler()
            c.compileFunction(
                FuncSig("TEST"),
                parse(
                    "TRYCALLLIST",
                    "FUNC GOLDEN_LISTCALLED",
                    "PRINTL \"BAD\"",
                    "ENDFUNC",
                ),
                known,
            )
            c.build()
        }
        assertTrue("只允许 FUNC" in (e.message ?: ""), "应报 InvalidInstructionInSyntax，实际=${e.message}")
    }

    @Test
    fun `TRYGOTOLIST 体内 IF 块报错`() {
        // 块结构语句同样命中 :1090-1096（原版 nestCheck 对指令行一律检查）。
        val e = assertFailsWith<CompileException> {
            val c = ErbProgramCompiler()
            c.compileFunction(
                FuncSig("TEST"),
                parse(
                    "TRYGOTOLIST",
                    "FUNC \$TAIL",
                    "IF A == 1",
                    "ENDIF",
                    "ENDFUNC",
                    "\$TAIL",
                ),
            )
            c.build()
        }
        assertTrue("只允许 FUNC" in (e.message ?: ""), "应报 InvalidInstructionInSyntax，实际=${e.message}")
    }
}
