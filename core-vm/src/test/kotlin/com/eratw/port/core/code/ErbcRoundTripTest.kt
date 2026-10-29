package com.eratw.port.core.code

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.KeywordIndexArg
import com.eratw.port.core.expr.NullTerm
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.UnaryAfter
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.Argument
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.ArgValueType
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.BitArgument
import com.eratw.port.core.stmt.CaseArgument
import com.eratw.port.core.stmt.CaseElseStmt
import com.eratw.port.core.stmt.CaseExpression
import com.eratw.port.core.stmt.CaseStmt
import com.eratw.port.core.stmt.CatchStmt
import com.eratw.port.core.stmt.CallFStmt
import com.eratw.port.core.stmt.CallSharpStmt
import com.eratw.port.core.stmt.CallStmt
import com.eratw.port.core.stmt.DataListStmt
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.DoStmt
import com.eratw.port.core.stmt.ElseIfStmt
import com.eratw.port.core.stmt.ElseStmt
import com.eratw.port.core.stmt.EndBlockStmt
import com.eratw.port.core.stmt.EndCatchStmt
import com.eratw.port.core.stmt.EndIfStmt
import com.eratw.port.core.stmt.EndSelectStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.ExpressionArrayArgument
import com.eratw.port.core.stmt.ExpressionsArgument
import com.eratw.port.core.stmt.FlowStmt
import com.eratw.port.core.stmt.ForStmt
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.GotoLabelStmt
import com.eratw.port.core.stmt.HtmlPrintArgument
import com.eratw.port.core.stmt.IfStmt
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.IntAsignArgument
import com.eratw.port.core.stmt.JumpStmt
import com.eratw.port.core.stmt.LoopStmt
import com.eratw.port.core.stmt.MethodArgument
import com.eratw.port.core.stmt.MethodStmt
import com.eratw.port.core.stmt.MixedIntegerExprTerm
import com.eratw.port.core.stmt.NextStmt
import com.eratw.port.core.stmt.PrintDataArgument
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.RefArgument
import com.eratw.port.core.stmt.RendStmt
import com.eratw.port.core.stmt.RepeatStmt
import com.eratw.port.core.stmt.ReturnFStmt
import com.eratw.port.core.stmt.ReturnStmt
import com.eratw.port.core.stmt.SelectCaseStmt
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.SortOrder
import com.eratw.port.core.stmt.SpArrayControlArgument
import com.eratw.port.core.stmt.SpArrayShiftArgument
import com.eratw.port.core.stmt.SpArraySortArgument
import com.eratw.port.core.stmt.SpBarArgument
import com.eratw.port.core.stmt.SpButtonArgument
import com.eratw.port.core.stmt.SpCallArgument
import com.eratw.port.core.stmt.SpCallFArgument
import com.eratw.port.core.stmt.SpCallSharpArgument
import com.eratw.port.core.stmt.SpColorArgument
import com.eratw.port.core.stmt.SpCopyArrayArgument
import com.eratw.port.core.stmt.SpCVarSetArgument
import com.eratw.port.core.stmt.SpDtColumnOptions
import com.eratw.port.core.stmt.SpForNextArgument
import com.eratw.port.core.stmt.SpGetIntArgument
import com.eratw.port.core.stmt.SpHtmlPrint
import com.eratw.port.core.stmt.SpHtmlSplitArgument
import com.eratw.port.core.stmt.SpInputsArgument
import com.eratw.port.core.stmt.SpPowerArgument
import com.eratw.port.core.stmt.SpPrintImgArgument
import com.eratw.port.core.stmt.SpPrintShapeArgument
import com.eratw.port.core.stmt.SpPrintVArgument
import com.eratw.port.core.stmt.SpSaveDataArgument
import com.eratw.port.core.stmt.SpSaveVarArgument
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.SpSetArrayArgument
import com.eratw.port.core.stmt.SpSortcharaArgument
import com.eratw.port.core.stmt.SpSplitArgument
import com.eratw.port.core.stmt.SpSwapCharaArgument
import com.eratw.port.core.stmt.SpSwapVarArgument
import com.eratw.port.core.stmt.SpTimesArgument
import com.eratw.port.core.stmt.SpTInputsArgument
import com.eratw.port.core.stmt.SpVarSetArgument
import com.eratw.port.core.stmt.SpVarsizeArgument
import com.eratw.port.core.stmt.StrAsignArgument
import com.eratw.port.core.stmt.StrDataArgument
import com.eratw.port.core.stmt.StrDoubleArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VarDeclStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.stmt.WendStmt
import com.eratw.port.core.loader.BootFailureKind
import com.eratw.port.core.loader.BootFailures
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.stmt.WhileStmt
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `.erbc` v1 往返（契约 CONTRACT-M4-A §2.4 (a)）。
 *
 * 断言的是**内存态 `Program` 的全部构成**，不是「看起来差不多」：
 * 指令流逐字、行号表逐字、四个池（i64 / 字符串 / 表达式 / 语句）逐条、
 * 函数表（名称 / entryPc / arity / slotCount / `$标签` 平行表）、
 * `entryFunction`、`globalSlots`。
 *
 * 判等口径故意**不用** `ErbcWriter`/`ErbcReader` 自己（否则「写读同时丢同一个字段」会假绿）：
 * 语句与参数走 [assertGraphEqual] 的反射逐字段比较，表达式走 data class 的 `==`，
 * 再加一条「规范再编码逐字节相同」作为第二重保险。
 */
class ErbcRoundTripTest {

    // ==================================================================
    // (a) 富样 Program 逐字段一致
    // ==================================================================

    @Test
    fun `往返后指令流与行号表逐字一致`() {
        val before = richProgram()
        val after = ErbcReader.decode(ErbcWriter.encode(before))

        assertEquals(before.instructionCount, after.instructionCount)
        assertContentEquals("code", before.code, after.code)
        assertContentEquals("lines", before.lines, after.lines)
        assertEquals(before.globalSlots, after.globalSlots)
        assertEquals(before.entryFunction, after.entryFunction)
    }

    @Test
    fun `往返后常量池与字符串池逐条一致且下标不漂移`() {
        val before = richProgram()
        val after = ErbcReader.decode(ErbcWriter.encode(before))

        assertEquals(before.constantCount, after.constantCount)
        for (i in 0 until before.constantCount) {
            assertEquals(before.constant(i), after.constant(i), "常量池下标 $i")
        }
        assertEquals(before.stringCount, after.stringCount)
        for (i in 0 until before.stringCount) {
            assertEquals(before.string(i), after.string(i), "字符串池下标 $i")
        }
        // 非 ASCII 与空串都在池里（UTF-8 往返 + 零长项），i64 边界值同样逐字回来
        val afterStrings = (0 until after.stringCount).map { after.string(it) }
        assertTrue(afterStrings.contains("こんにちは，世界"), "字符串池缺非 ASCII 项")
        assertTrue(afterStrings.contains(""), "字符串池缺空串项")
        val afterConstants = (0 until after.constantCount).map { after.constant(it) }
        assertTrue(afterConstants.contains(Long.MIN_VALUE), "常量池缺 Long.MIN_VALUE")
        assertTrue(afterConstants.contains(Long.MAX_VALUE), "常量池缺 Long.MAX_VALUE")
    }

    @Test
    fun `往返后表达式池逐条结构相等 含行列与静态类型`() {
        val before = richProgram()
        val after = ErbcReader.decode(ErbcWriter.encode(before))

        assertEquals(before.exprCount, after.exprCount, "表达式池长度变了 = 池下标会整体漂移")
        for (i in 0 until before.exprCount) {
            // Expr 全是 data class：== 会逐字段比（含 line/col/operandType/isConst/subKey）
            assertEquals(before.expr(i), after.expr(i), "表达式池下标 $i 结构不等")
        }
        // 覆盖到 11 个 Expr 实现里的关键几类（构造顺序见 richExpressions）
        assertTrue(after.expressions().any { it is FormStringTerm }, "FormStringTerm 未落进池")
        assertTrue(after.expressions().any { it is KeywordIndexArg }, "KeywordIndexArg 未落进池")
        assertTrue(after.expressions().any { it is NullTerm }, "NullTerm 未落进池")
        val binary = after.expressions().filterIsInstance<Binary>().first()
        assertEquals(OperandType.INT, binary.operandType)
        assertEquals(OperatorCode.PLUS, binary.op)
    }

    @Test
    fun `往返后语句池逐条同类同字段`() {
        val before = richProgram()
        val after = ErbcReader.decode(ErbcWriter.encode(before))

        assertEquals(before.statementCount, after.statementCount, "语句池条目数必须逐条相等")
        for (i in 0 until before.statementCount) {
            val b = before.statement(i)
            val a = after.statement(i)
            assertEquals(b.javaClass, a.javaClass, "语句池下标 $i 类不同")
            assertEquals(b.code, a.code, "语句池下标 $i 的 FunctionCode 不同")
            assertEquals(b.line, a.line, "语句池下标 $i 的行号不同")
            assertEquals(b.raw, a.raw, "语句池下标 $i 的原文不同")
            assertGraphEqual("stmt[$i]", b, a)
        }
    }

    @Test
    fun `往返后函数表与标签平行表一致`() {
        val before = richProgram()
        val after = ErbcReader.decode(ErbcWriter.encode(before))

        assertEquals(before.functions.size, after.functions.size)
        for (i in before.functions.indices) {
            val f = before.functions[i]
            val g = after.functions[i]
            assertEquals(f.name, g.name, "函数 $i 名字")
            assertEquals(f.entryPc, g.entryPc, "函数 ${f.name} entryPc")
            assertEquals(f.arity, g.arity, "函数 ${f.name} arity")
            assertEquals(f.slotCount, g.slotCount, "函数 ${f.name} slotCount")
            assertEquals(f.labelNames.toList(), g.labelNames.toList(), "函数 ${f.name} 标签名表")
            assertEquals(f.labelPcs.toList(), g.labelPcs.toList(), "函数 ${f.name} 标签 pc 表")
        }
        assertEquals("sub", after.function("sub")?.name, "按名查找（FunctionTable 侧的入口）")
    }

    @Test
    fun `marker 的 a 装载后仍取回对应槽位的语句`() {
        val before = richProgram()
        val after = ErbcReader.decode(ErbcWriter.encode(before))

        val markerPcs = (0 until before.instructionCount).filter {
            before.op(it) in ErbcFormat.MARKER_OPCODE_MIN..ErbcFormat.MARKER_OPCODE_MAX
        }
        assertTrue(markerPcs.isNotEmpty(), "样例里没有 marker 指令")
        for (pc in markerPcs) {
            assertEquals(before.op(pc), after.op(pc), "pc=$pc 的 marker op")
            assertEquals(before.a(pc), after.a(pc), "pc=$pc 的 marker 池下标")
            assertEquals(before.b(pc), after.b(pc))
            assertEquals(before.c(pc), after.c(pc), "契约 C3：marker 的 c = NONE")
            assertEquals(
                before.statement(before.a(pc)).javaClass,
                after.statement(after.a(pc)).javaClass,
                "pc=$pc 的 marker 取回了不同类的语句",
            )
        }
    }

    // ==================================================================
    // 语句池的身份语义（最容易做错、也最难发现的一条）
    // ==================================================================

    @Test
    fun `结构相等的两条语句装载后仍是两个不同实例`() {
        // sem 的数据节以 Statement 实例为键：ExecContext.kt:101-110、ProcessRuntime.kt:848-877、
        // 原版 InstructionLine.cs:30 的 dataList 挂在语句行对象上（ErbLoader.cs:1383,1445）。
        // 装载侧若把两条记录合并成一个实例，PRINTDATA 会静默少一组数据。
        val a = DataStmt(FunctionCode.DATA, "同一行文本", VoidArgument(), 5, "DATA 同一行文本")
        val b = DataStmt(FunctionCode.DATA, "同一行文本", VoidArgument(), 5, "DATA 同一行文本")
        val builder = CodeBuilder()
        builder.declareFunction("main", 0, 0, 1)
        val ia = builder.statement(a)
        val ib = builder.statement(b)
        val pc1 = builder.emit(Opcode.DATA, ia, 0, SlotAddr.NONE, 5)
        builder.emit(Opcode.DATA, ib, 0, SlotAddr.NONE, 5)
        builder.emit(Opcode._HALT, 0, 0, 0, 6)
        val before = builder.build(0)
        assertNotSame(a, b)

        val after = ErbcReader.decode(ErbcWriter.encode(before))

        assertEquals(2, after.statementCount)
        assertNotSame(after.statement(0), after.statement(1), "语句池合并了结构相等的条目 —— 数据节会静默失效")
        assertEquals(pc1, 0)
        assertGraphEqual("stmt[0]", after.statement(0), after.statement(1))
        assertEquals(0, after.a(0))
        assertEquals(1, after.a(1), "marker 的 a 必须各自指向自己的条目")
    }

    @Test
    fun `同一实例被追加两次在产物里是两条记录`() {
        val s = InstructionStmt(FunctionCode.WAIT, VoidArgument(), 3, "WAIT")
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        val i0 = b.statement(s)
        val i1 = b.statement(s)
        b.emit(Opcode.WAIT, i0, 0, SlotAddr.NONE, 3)
        b.emit(Opcode.WAIT, i1, 0, SlotAddr.NONE, 3)
        b.emit(Opcode._HALT, 0, 0, 0, 4)
        val before = b.build(0)

        val after = ErbcReader.decode(ErbcWriter.encode(before))

        assertEquals(2, after.statementCount, "ConstantPool.addStatement 恒新开条目，产物必须保持两条")
        assertNotSame(after.statement(0), after.statement(1))
    }

    // ==================================================================
    // 规范再编码 + 选项 / 错误路径
    // ==================================================================

    @Test
    fun `装载后重新编码与原字节逐字相同`() {
        val before = richProgram()
        val bytes = ErbcWriter.encode(before)
        val again = ErbcWriter.encode(ErbcReader.decode(bytes))
        assertContentEquals("re-encode", bytes, again)
    }

    @Test
    fun `空池的段被省略且标志位清零`() {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        val program = b.build(0)

        val bytes = ErbcWriter.encode(program)
        val header = ErbcReader.probe(bytes)

        assertEquals(0, header.offsets[ErbcFormat.SECTION_STRINGS], "空字符串池不写段")
        assertEquals(0, header.offsets[ErbcFormat.SECTION_CONSTANTS], "空常量池不写段")
        assertEquals(0, header.offsets[ErbcFormat.SECTION_EXPRESSIONS])
        assertEquals(0, header.offsets[ErbcFormat.SECTION_STATEMENTS])
        assertEquals(0, header.flags and ErbcFormat.FLAG_HAS_EXPRESSIONS)
        assertEquals(0, header.flags and ErbcFormat.FLAG_HAS_STATEMENTS)
        assertTrue(header.hasSection(ErbcFormat.SECTION_FUNCTIONS))
        assertTrue(header.hasSection(ErbcFormat.SECTION_CODE))

        val after = ErbcReader.decode(bytes)
        assertEquals(1, after.instructionCount)
        assertEquals(0, after.statementCount)
        assertContentEquals("再编码", bytes, ErbcWriter.encode(after))
    }

    @Test
    fun `关掉 includeLines 只影响行号 不影响其它任何字段`() {
        val before = richProgram()
        val bytes = ErbcWriter.encode(before, ErbcEncodeOptions(includeLines = false))
        val after = ErbcReader.decode(bytes)

        assertEquals(0, ErbcReader.probe(bytes).flags and ErbcFormat.FLAG_HAS_LINES)
        assertEquals(before.instructionCount, after.instructionCount)
        assertContentEquals("code", before.code, after.code)
        for (pc in 0 until after.instructionCount) {
            assertEquals(-1, after.lineOf(pc), "行号段缺席时 lineOf 必须是 -1（Program.kt:117 的既有口径）")
        }
        assertEquals(before.statementCount, after.statementCount)
        assertEquals(before.exprCount, after.exprCount)
    }

    @Test
    fun `声明不带某池而程序实际需要 编码期就拒绝`() {
        val program = richProgram()
        val e1 = assertFailsWith<ErbcEncodeException> {
            ErbcWriter.encode(program, ErbcEncodeOptions(includeStatements = false))
        }
        assertTrue(e1.message!!.contains("语句池"), e1.message)
        val e2 = assertFailsWith<ErbcEncodeException> {
            ErbcWriter.encode(program, ErbcEncodeOptions(includeExpressions = false))
        }
        assertTrue(e2.message!!.contains("表达式池"), e2.message)
    }

    // ==================================================================
    // (c) 手写字节：reader 不得依赖 writer
    // ==================================================================

    @Test
    fun `手写的 200 字节容器能解出预期 Program`() {
        // 完全按 specs/erbc-format.md §2/§5 的表格手搓，不调用 ErbcWriter 的任何代码路径。
        val strings = handStringTable(listOf(""))                                  // 16
        val constants = hand { u32(1); u32(0); i64(42L) }                          // 16
        val expressions = hand {
            u32(1); u32(1)                                                          // nodeCount, poolCount
            put(handStringTable(emptyList()))                                       // 段内空串池
            u8(ErbcFormat.ExprTag.INT_LITERAL); i64(7L); i32(3); i32(4)            // IntLiteral(7,3,4)
            u32(0)                                                                  // 池根
        }
        val functions = hand {
            u32(1); u32(0); u32(5)                                                  // funcCount, entryFunction, globalSlots
            put(handStringTable(listOf("f")))                                       // 局部串池：函数名
            u32(0); i32(0); i32(1); i32(2); u32(0); u32(0)                          // 名字/entryPc/arity/slots/flags/labelCount
        }
        val code = hand {
            u32(2)
            u32(Opcode._EVAL_INT); u32(0); u32(SlotAddr.local(0)); u32(0)
            u32(Opcode._SET_STR); u32(SlotAddr.local(1)); u32(0); u32(0)
        }
        val bytes = handContainer(
            flags = ErbcFormat.FLAG_HAS_EXPRESSIONS,
            bodies = arrayOf(
                strings, // 段 0
                constants, // 段 1
                expressions, // 段 2
                null, // 段 3：语句池不存在
                functions, // 段 4
                code, // 段 5
                null, // 段 6：行号表不存在
            ),
        )
        assertTrue(bytes.size <= 200, "契约 §2.4(c) 要求手写字节 ≤200 字节，实为 ${bytes.size}")

        val program = ErbcReader.decode(bytes)

        assertEquals(2, program.instructionCount)
        assertEquals(Opcode._EVAL_INT, program.op(0))
        assertEquals(0, program.a(0))
        assertEquals(SlotAddr.local(0), program.b(0))
        assertEquals(Opcode._SET_STR, program.op(1))
        assertEquals(SlotAddr.local(1), program.a(1))
        assertEquals(0, program.b(1))
        assertEquals(1, program.stringCount)
        assertEquals("", program.string(0))
        assertEquals(1, program.constantCount)
        assertEquals(42L, program.constant(0))
        assertEquals(1, program.exprCount)
        assertEquals(IntLiteral(7L, 3, 4), program.expr(0))
        assertEquals(0, program.statementCount)
        assertEquals(1, program.functions.size)
        assertEquals("f", program.functions[0].name)
        assertEquals(0, program.functions[0].entryPc)
        assertEquals(1, program.functions[0].arity)
        assertEquals(2, program.functions[0].slotCount)
        assertEquals(emptyList<String>(), program.functions[0].labelNames.toList())
        assertEquals(0, program.entryFunction)
        assertEquals(5, program.globalSlots)
        assertEquals(-1, program.lineOf(0), "行号段缺席 ⇒ 未知")
        assertEquals(-1, program.lineOf(1))
    }

    @Test
    fun `手写的语句池记录能解出 marker 对应的实例`() {
        val statements = hand {
            u32(0); u32(1)                                                          // nodeCount=0, stmtCount=1
            put(handStringTable(listOf("PRINT")))                                   // raw 与 code 名同串
            u8(ErbcFormat.StmtTag.INSTRUCTION)
            i32(9)                                                                   // line
            u32(0)                                                                   // raw
            u32(0)                                                                   // code 名
            u8(ErbcFormat.ArgTag.VOID)                                              // 参数记录
            u8(0)                                                                    // 头：不 isConst、无附加字段
        }
        val functions = hand {
            u32(1); u32(0); u32(0)
            put(handStringTable(listOf("main")))
            u32(0); i32(0); i32(0); i32(1); u32(0); u32(0)
        }
        val code = hand {
            u32(2)
            u32(Opcode.PRINT); u32(0); u32(0); u32(SlotAddr.NONE)                   // marker（契约 C3）
            u32(Opcode._HALT); u32(0); u32(0); u32(0)
        }
        val bytes = handContainer(
            flags = ErbcFormat.FLAG_HAS_STATEMENTS,
            bodies = arrayOf(null, null, null, statements, functions, code, null),
        )
        assertTrue(bytes.size <= 200, "手写字节应 ≤200 字节，实为 ${bytes.size}")

        val program = ErbcReader.decode(bytes)

        assertEquals(1, program.statementCount)
        val stmt = program.statement(program.a(0))
        assertTrue(stmt is InstructionStmt, "期望 InstructionStmt，实际 ${stmt.javaClass.name}")
        assertEquals(FunctionCode.PRINT, stmt.code)
        assertEquals(9, stmt.line)
        assertEquals("PRINT", stmt.raw)
        assertTrue(stmt.args is VoidArgument)
        assertEquals(SlotAddr.NONE, program.c(0))
        assertEquals(Opcode.PRINT, program.op(0))
    }

    // ==================================================================
    // (d) 畸形头部：明确异常，不许静默降级
    // ==================================================================

    @Test
    fun `magic 不符直接报错`() {
        val good = ErbcWriter.encode(minimalProgram())
        val bad = good.copyOf()
        bad[0] = 'X'.code.toByte()
        val e = assertFailsWith<ErbcFormatException> { ErbcReader.decode(bad) }
        assertTrue(e.message!!.contains("magic"), e.message)
    }

    @Test
    fun `版本不是 1 或 2 直接报错 不做降级解释`() {
        val good = ErbcWriter.encode(minimalProgram())
        val bad = good.copyOf()
        bad[4] = 3 // version 的低字节：2 → 3（v2 是当前最新，v1 仍读）
        val e = assertFailsWith<ErbcFormatException> { ErbcReader.decode(bad) }
        assertTrue(e.message!!.contains("版本"), e.message)

        val bad2 = good.copyOf()
        bad2[4] = 0
        bad2[5] = 2 // version = 512
        assertTrue(ErbcReader.decode(good).instructionCount > 0, "同一份好字节必须能解（对照组）")
        assertFailsWith<ErbcFormatException> { ErbcReader.decode(bad2) }
    }

    @Test
    fun `未定义标志位与标志位段表不一致都报错`() {
        val good = ErbcWriter.encode(minimalProgram())
        val badFlags = good.copyOf()
        badFlags[7] = 0x01 // flags |= 0x0100：v1/v2 都未定义的位
        val e1 = assertFailsWith<ErbcFormatException> { ErbcReader.decode(badFlags) }
        assertTrue(e1.message!!.contains("标志位"), e1.message)

        // 声明带语句池，但段表里该段偏移为 0 ⇒ 不一致
        val rich = ErbcWriter.encode(richProgram())
        val header = ErbcReader.probe(rich)
        val lying = rich.copyOf()
        val stmtOffsetIndex = ErbcFormat.HEADER_BYTES + ErbcFormat.SECTION_STATEMENTS * 4
        writeLe32(lying, stmtOffsetIndex, 0)
        assertEquals(
            true,
            (header.flags and ErbcFormat.FLAG_HAS_STATEMENTS) != 0,
            "样例本身必须声明带语句池",
        )
        val e2 = assertFailsWith<ErbcFormatException> { ErbcReader.decode(lying) }
        assertTrue(e2.message!!.contains("不一致"), e2.message)
    }

    @Test
    fun `声明不含语句池但指令流有 marker 时报指名道姓的错误`() {
        // 这条只能手写字节（编码侧拒绝产生自相矛盾的产物），正是「不许静默降级」的验收路径。
        val statements = hand {
            u32(0); u32(1)
            put(handStringTable(listOf("PRINT")))
            u8(ErbcFormat.StmtTag.INSTRUCTION); i32(1); u32(0); u32(0)
            u8(ErbcFormat.ArgTag.VOID); u8(0)
        }
        val functions = hand {
            u32(1); u32(0); u32(0)
            put(handStringTable(listOf("main")))
            u32(0); i32(0); i32(0); i32(1); u32(0); u32(0)
        }
        val code = hand {
            u32(1)
            u32(Opcode.PRINT); u32(0); u32(0); u32(SlotAddr.NONE)
        }
        val bytes = handContainer(
            flags = 0, // 声明：不带表达式池、不带语句池、不带行号表
            bodies = arrayOf(null, null, null, null, functions, code, null),
        )
        val e = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(bytes) }
        assertTrue(e.message!!.contains("不含语句池"), e.message)
        assertTrue(e.message!!.contains("marker"), e.message)

        // 同一份段内容不动，只把语句段真正带上 ⇒ 必须可解（证明错误来自声明而不是字节读不懂）
        val ok = handContainer(
            flags = ErbcFormat.FLAG_HAS_STATEMENTS,
            bodies = arrayOf(null, null, null, statements, functions, code, null),
        )
        assertEquals(1, ErbcReader.decode(ok).statementCount)
    }

    @Test
    fun `池下标越界的指令在装载期就被拒绝`() {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        val k = b.constant(42L)
        val s = b.string("文本")
        b.emit(Opcode._SET_INT, SlotAddr.local(0), k, 0, 1)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s, 0, 2)
        b.emit(Opcode._HALT, 0, 0, 0, 3)
        val bytes = ErbcWriter.encode(b.build(0))
        val header = ErbcReader.probe(bytes)
        // 指令流正文 = u32 条数 + (op,a,b,c)*n；pc0 的 b（常量池下标）在 +4+8 处
        val pc0b = header.offsets[ErbcFormat.SECTION_CODE] + 4 + 8
        val corrupted = bytes.copyOf()
        writeLe32(corrupted, pc0b, 9999)
        val e = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(corrupted) }
        assertTrue(e.message!!.contains("常量池"), e.message)
        assertTrue(e.message!!.contains("越界"), e.message)

        // 对照组：同一个 pc 位置放合法下标必须可解（证明错误来自判界而不是字节读不动）
        assertEquals(1, ErbcReader.decode(bytes).constantCount)
    }

    @Test
    fun `f64 常量池与函数标志位都还没定稿 必须拒绝而不是忽略`() {
        val constants = hand { u32(0); u32(3) } // longCount=0, doubleCount=3
        val functions = hand {
            u32(1); u32(0); u32(0)
            put(handStringTable(listOf("main")))
            u32(0); i32(0); i32(0); i32(1); u32(1); u32(0) // flags=1（EVENT）
        }
        val code = hand {
            u32(1); u32(Opcode._HALT); u32(0); u32(0); u32(0)
        }
        val bytes = handContainer(
            flags = 0,
            bodies = arrayOf(null, constants, null, null, functions, code, null),
        )
        val e = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(bytes) }
        assertTrue(e.message!!.contains("f64"), e.message)

        val bytes2 = handContainer(
            flags = 0,
            bodies = arrayOf(null, null, null, null, functions, code, null),
        )
        val e2 = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(bytes2) }
        assertTrue(e2.message!!.contains("标志位"), e2.message)
    }

    @Test
    fun `SharpStmt 携带词法 Token 时编码期拒绝`() {
        val tokens = com.eratw.port.core.lexer.Token.Integer(123L, 1, 1)
        val sharp = SharpStmt(
            keyword = "#DIM",
            values = listOf(3L),
            name = "LV",
            isString = false,
            tokens = listOf(tokens),
            line = 1,
            raw = "#DIM LV,3",
        )
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        val i = b.statement(sharp)
        b.emit(Opcode.WAIT, i, 0, SlotAddr.NONE, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        val e = assertFailsWith<ErbcEncodeException> { ErbcWriter.encode(b.build(0)) }
        assertTrue(e.message!!.contains("Token"), e.message)
    }

    // ==================================================================
    // (e) 装载健壮性：解码异常类型、计数上界、映射生命周期、写侧判界
    // ==================================================================

    /** 段 0 只含一个 0xFF 字节的非法 UTF-8 序列，其余段齐全（v1）。 */
    private fun badUtf8Container(): ByteArray {
        val strings = hand {
            u32(1); u32(1) // count, dataLen
            u32(0); u32(1) // offset[0], offset[1]
            u8(0xFF) // 非法 UTF-8（孤立字节）
        }
        val functions = hand {
            u32(1); u32(0); u32(0)
            put(handStringTable(listOf("main")))
            u32(0); i32(0); i32(0); i32(1); u32(0); u32(0)
        }
        val code = hand {
            u32(1); u32(Opcode._HALT); u32(0); u32(0); u32(0)
        }
        return handContainer(
            flags = 0,
            bodies = arrayOf(strings, null, null, null, functions, code, null),
        )
    }

    @Test
    fun `非法 UTF-8 字符串抛 ErbcDecodeException 而不是 CharacterCodingException`() {
        // 旧实现的 catch (IllegalArgumentException) 是死码：decodeToString(strict) 实际抛的是
        // CharacterCodingException（IOException 支），它会原样越过装载层的异常收口。
        val e = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(badUtf8Container()) }
        assertTrue(e.message!!.contains("UTF-8"), e.message)
        assertTrue(
            e.cause is CharacterCodingException,
            "cause 应保留 JDK 的 CharacterCodingException，实为 ${e.cause}",
        )
    }

    @Test
    fun `映射与流式装载同样把非法 UTF-8 包成 ErbcDecodeException`(@TempDir dir: Path) {
        val file = dir.resolve("bad-utf8.erbc")
        Files.write(file, badUtf8Container())

        // 第三条路径：MappedStringTable（惰性视图，不经 ErbcReader/StreamDecoder）
        ProgramLoader.mapped(file).use { mapped ->
            val e = assertFailsWith<ErbcDecodeException> {
                mapped.stringTable(ErbcFormat.SECTION_STRINGS)[0]
            }
            assertTrue(e.message!!.contains("UTF-8"), e.message)
            assertTrue(e.cause is CharacterCodingException, "实为 ${e.cause}")
        }

        // 第二条路径：ErbcStreamDecoder 的全局串池（生产装载链）。顶层是带路径前缀的
        // ErbcLoadException，但 cause 链里必须是 ErbcDecodeException。
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.loadMapped(file) }
        assertTrue(e.message!!.contains("UTF-8"), e.message)
        assertTrue(
            generateSequence(e as Throwable?) { it.cause }.any { it is ErbcDecodeException },
            "流式装载的 cause 链里应有 ErbcDecodeException，实为 ${e.cause}",
        )
        // 最终判据：畸形产物必须落到「段损坏」分支，而不是 OTHER（旧实现漏出 IOException 支）。
        assertEquals(BootFailureKind.CORRUPT_ARTIFACT, BootFailures.classify(e).kind)
    }

    @Test
    fun `畸形计数按元素最小字节宽度在分配前拒绝`(@TempDir dir: Path) {
        fun container(body: ByteArray, flags: Int = 0): ByteArray {
            val functions = hand {
                u32(1); u32(0); u32(0)
                put(handStringTable(listOf("main")))
                u32(0); i32(0); i32(0); i32(1); u32(0); u32(0)
            }
            val code = hand { u32(1); u32(Opcode._HALT); u32(0); u32(0); u32(0) }
            return handContainer(
                flags = flags,
                bodies = arrayOf(null, body, null, null, functions, code, null),
            )
        }

        // 常量池声明 4096 个 i64，段里只剩 4104 字节：旧上界（count <= 剩余字节数）放行，
        // 新上界（4096 × 8 > 4104）在 LongArray 分配前就拒绝。
        val constants = hand {
            u32(4096); u32(0)
            repeat(4100) { u8(0) }
        }
        val e1 = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(container(constants)) }
        assertTrue(e1.message!!.contains("常量池长度"), e1.message)
        assertTrue(e1.message!!.contains("至少需要"), e1.message)

        // 字符串表声明 1000 项，段里只剩 1008 字节：1000 ≤ 1008 骗过旧上界，
        // 但 1000 × 4 > 1008，必须在 IntArray(count + 1) 之前拒绝。
        val strings = hand {
            u32(1000); u32(0)
            repeat(1004) { u8(0) }
        }
        val badStrings = handContainer(
            flags = 0,
            bodies = arrayOf(
                strings,
                null,
                null,
                null,
                hand {
                    u32(1); u32(0); u32(0)
                    put(handStringTable(listOf("main")))
                    u32(0); i32(0); i32(0); i32(1); u32(0); u32(0)
                },
                hand { u32(1); u32(Opcode._HALT); u32(0); u32(0); u32(0) },
                null,
            ),
        )
        val e2 = assertFailsWith<ErbcDecodeException> { ErbcReader.decode(badStrings) }
        assertTrue(e2.message!!.contains("字符串表项数"), e2.message)
        assertTrue(e2.message!!.contains("至少需要"), e2.message)

        // 流式路径同一条判据、同一句文案（差分口径：Reader 与 StreamDecoder 逐字相同）
        val file = dir.resolve("bad-count.erbc")
        Files.write(file, container(constants))
        val e3 = assertFailsWith<ErbcFormatException> { ProgramLoader.loadMapped(file) }
        assertTrue(e3.message!!.contains("至少需要"), e3.message)
    }

    @Test
    fun `loadMapped 在记录区畸形时抛错并关闭映射`(@TempDir dir: Path) {
        // 段表本身合法（偏移不动），只把函数表的 funcCount 改成撒谎值：解码期必抛。
        val bytes = ErbcWriter.encode(richProgram())
        val header = ErbcReader.probe(bytes)
        val funcOff = header.offsets[ErbcFormat.SECTION_FUNCTIONS]
        assertTrue(funcOff != 0, "富样必须带函数表")
        writeLe32(bytes, funcOff, 0x7FFFFF00)
        val file = dir.resolve("bad-record.erbc")
        Files.write(file, bytes)

        val mapped = ProgramLoader.mapped(file)
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.loadMappedHandle(mapped, null) }
        assertTrue(e.message!!.contains("函数个数"), e.message)
        assertTrue(mapped.isClosed, "解码失败路径必须关闭映射，否则文件句柄泄漏到 GC")
    }

    /** 直构 Program（internal 构造器）只为造出写侧必须拒绝的形态；`_HALT` 足够跑编码。 */
    private fun writerGuardProgram(
        instructionCount: Int = 1,
        lines: IntArray = IntArray(instructionCount) { 1 },
        functions: List<FunctionInfo> = listOf(FunctionInfo("main", 0, 0, 1)),
        globalSlots: Int = 0,
    ): Program {
        val code = IntArray(instructionCount * Instruction.WORDS)
        code[0] = Opcode._HALT
        return Program(
            code = code,
            lines = lines,
            pool = ConstantPool(),
            functions = functions,
            entryFunction = 0,
            globalSlots = globalSlots,
        )
    }

    @Test
    fun `写侧拒绝 entryPc arity globalSlots 与行表长度越界`() {
        val entryPc = assertFailsWith<ErbcEncodeException> {
            ErbcWriter.encode(writerGuardProgram(functions = listOf(FunctionInfo("main", 5, 0, 1))))
        }
        assertTrue(entryPc.message!!.contains("entryPc"), entryPc.message)

        val arity = assertFailsWith<ErbcEncodeException> {
            ErbcWriter.encode(writerGuardProgram(functions = listOf(FunctionInfo("main", 0, -1, 1))))
        }
        assertTrue(arity.message!!.contains("arity"), arity.message)

        val slots = assertFailsWith<ErbcEncodeException> {
            ErbcWriter.encode(writerGuardProgram(globalSlots = -1))
        }
        assertTrue(slots.message!!.contains("全局槽数"), slots.message)

        val lines = assertFailsWith<ErbcEncodeException> {
            ErbcWriter.encode(writerGuardProgram(instructionCount = 1, lines = IntArray(2)))
        }
        assertTrue(lines.message!!.contains("行号表"), lines.message)

        // 对照组：同一构造方式下合法形态必须照常编码（证明拒绝来自越界而不是构造器）
        assertTrue(ErbcWriter.encode(writerGuardProgram()).isNotEmpty())
    }

    // ==================================================================
    // 样题构造
    // ==================================================================

    private fun minimalProgram(): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        b.emit(Opcode._NOP, 0, 0, 0, 1)
        b.emit(Opcode._HALT, 0, 0, 0, 2)
        return b.build(0)
    }

    /** 富样：四个池 + 两个函数 + 标签表 + 全部语句/参数类各至少一条。 */
    private fun richProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 1, 3)
        // ---- 常量池（含边界值）----
        val cMin = b.constant(Long.MIN_VALUE)
        val cZero = b.constant(0L)
        val cMax = b.constant(Long.MAX_VALUE)
        val cNeg = b.constant(-1L)
        // ---- 字符串池（含空串与非 ASCII）----
        val sJa = b.string("こんにちは，世界")
        val sEmpty = b.string("")
        val sQuote = b.string("含 \" 引号 与 \\ 反斜杠\n换行")
        // ---- 表达式池（11 个实现全覆盖）----
        val exprs = richExpressions()
        val exprIdx = exprs.map { b.expr(it) }
        // ---- 语句池（37 个类 + 47 个参数类）----
        val stmts = richStatements()
        val stmtIdx = stmts.map { b.statement(it) }

        b.emit(Opcode._SET_INT, SlotAddr.local(0), cMin, 0, 1)
        b.emit(Opcode._SET_INT, SlotAddr.local(0), cZero, 0, 1)
        b.emit(Opcode._SET_INT, SlotAddr.global(1), cMax, 0, 2)
        b.emit(Opcode._ADD, SlotAddr.local(2), SlotAddr.local(0), SlotAddr.global(1), 3)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), sJa, 0, 4)
        b.emit(Opcode._PRINT_LITERAL, sEmpty, 1, 0, 5)
        b.emit(Opcode._PRINT_LITERAL, sQuote, 0, 0, 5)
        for ((k, i) in exprIdx.withIndex()) {
            val op = if (k % 2 == 0) Opcode._EVAL_INT else Opcode._EVAL_STR
            b.emit(op, i, SlotAddr.local(k % 3), 0, 6 + k)
        }
        // 未被任何 marker 引用的语句也必须往返（池下标稳定是 marker 可回填的前提）
        b.emit(Opcode._JUMP, 2, 0, 0, 30)
        val jumpTarget = b.emit(Opcode._CALL, 1, 0, SlotAddr.NONE, 31)
        b.emit(Opcode.DATA, stmtIdx[0], 0, SlotAddr.NONE, 32)
        b.emit(Opcode.PRINT, stmtIdx[1], 0, SlotAddr.NONE, 33)
        b.emit(Opcode.DATALIST, stmtIdx[2], 0, SlotAddr.NONE, 34)
        b.emit(Opcode.SET, stmtIdx[3], 0, SlotAddr.NONE, 35)
        b.emit(Opcode.IF, stmtIdx[4], 0, SlotAddr.NONE, 36)
        b.emit(Opcode.DATA, stmtIdx[5], 0, SlotAddr.NONE, 37)
        b.declareLabel(main, "TAIL", jumpTarget)
        b.declareLabel(main, "NEXT LABEL", b.here())
        b.noteGlobalSlot(1)
        b.setFunctionSlots(main, 3)
        val sub = b.declareFunction("sub", b.here(), 2, 4)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 40)
        b.declareLabel(sub, "INNER", b.here() - 1)
        b.setFunctionSlots(sub, 4)
        return b.build(0)
    }

    private fun richExpressions(): List<Expr> {
        val name0 = VarRef("NAME", listOf(IntLiteral(0L, 1, 2)), operandType = OperandType.INT, line = 1, col = 3)
        return listOf(
            IntLiteral(Long.MIN_VALUE, 1, 1),
            StrLiteral("文字列", 2, 3),
            NullTerm(isInt = true, 3, 4),
            NullTerm(isInt = false, 3, 5),
            name0,
            VarRef(
                name = "CSTR",
                indices = listOf(name0, IntLiteral(7L, 4, 1)),
                isNoArg = true,
                operandType = OperandType.STR,
                isConst = true,
                subKey = "1",
                line = 4,
                col = 2,
            ),
            KeywordIndexArg(StrLiteral("薬", 5, 1), "ITEM", 0, 5, 2),
            FuncCall("ABS", listOf(IntLiteral(1L, 6, 1), null), OperandType.INT, 6, 2),
            FuncCall("GET_CALL_NAME", emptyList(), OperandType.STR, 6, 3),
            Unary(OperatorCode.MINUS, IntLiteral(9L, 7, 1), 7, 2),
            Unary(OperatorCode.BIT_NOT, VarRef("A", emptyList(), operandType = OperandType.INT), 7, 3),
            UnaryAfter(OperatorCode.INCREMENT, VarRef("LV", emptyList(), operandType = OperandType.INT), 7, 4),
            Binary(OperatorCode.PLUS, IntLiteral(1L, 8, 1), IntLiteral(2L, 8, 2), OperandType.INT, 8, 3),
            Binary(OperatorCode.MULT, StrLiteral("x", 9, 1), IntLiteral(3L, 9, 2), OperandType.STR, 9, 3),
            Ternary(
                IntLiteral(1L, 10, 1),
                StrLiteral("a", 10, 2),
                StrLiteral("b", 10, 3),
                OperandType.STR,
                10,
                4,
            ),
            FormStringTerm(
                parts = listOf(
                    FormPart.Text("前"),
                    FormPart.Embedded(name0, FormConversion.Curly(IntLiteral(8L, 11, 1), alignLeft = true)),
                    FormPart.Text(""),
                    FormPart.Embedded(StrLiteral("s", 11, 2), FormConversion.Percent()),
                    FormPart.Embedded(
                        IntLiteral(0L, 11, 3),
                        FormConversion.YenAt(StrLiteral("T", 11, 4), StrLiteral("F", 11, 5)),
                    ),
                ),
                line = 11,
                col = 0,
            ),
            Binary(
                OperatorCode.AND,
                Binary(OperatorCode.EQUAL, name0, IntLiteral(2L, 12, 1), OperandType.INT, 12, 2),
                Unary(OperatorCode.NOT, name0, 12, 3),
                OperandType.INT,
                12,
                4,
            ),
        )
    }

    private fun intRef(name: String = "LV"): VarRef =
        VarRef(name, listOf(IntLiteral(0L, 0, 0)), operandType = OperandType.INT)

    private fun strRef(name: String = "CSTR"): VarRef =
        VarRef(name, emptyList(), operandType = OperandType.STR)

    /** 覆盖 37 个 Statement 类与 47 个 Argument 类（每类至少一条）。 */
    private fun richStatements(): List<Statement> {
        val printL = requireNotNull(PrintSpec.parse(FunctionCode.PRINTL))
        val printFormW = requireNotNull(PrintSpec.parse(FunctionCode.PRINTFORMW))
        val printV = requireNotNull(PrintSpec.parse(FunctionCode.PRINTV))
        return listOf(
            // 1 InstructionStmt + VoidArgument
            InstructionStmt(FunctionCode.WAIT, VoidArgument(), 100, "WAIT"),
            // 2 PrintStmt + ExpressionArgument（含 PrintSpec 标志位）
            PrintStmt(printL, ExpressionArgument(StrLiteral("行", 101, 1)), 101, "PRINTL 行"),
            PrintStmt(printFormW, ExpressionArgument(null), 102, "PRINTFORMW"),
            PrintStmt(printV, SpPrintVArgument(listOf(IntLiteral(1L, 103, 1))), 103, "PRINTV 1"),
            // 3 PrintDataStmt + PrintDataArgument
            PrintDataStmt(FunctionCode.PRINTDATA, PrintDataArgument(intRef("RESULT")), 104, "PRINTDATA A"),
            // 4 AssignStmt + SpSetArgument（可变字段 addConst）
            AssignStmt(intRef(), OperatorCode.ASSIGNMENT, SpSetArgument(intRef(), IntLiteral(5L, 105, 1)).apply { addConst = true }, 105, "LV += 5"),
            AssignStmt(strRef(), OperatorCode.ASSIGNMENT_STR, SpSetArgument(strRef(), null), 106, "CSTR$ = "),
            // 5 JumpStmt + SpCallArgument
            JumpStmt(FunctionCode.JUMP, "TAIL", SpCallArgument(StrLiteral("TAIL", 107, 1), listOf(null), listOf(IntLiteral(1L, 107, 2))), 107, "JUMP TAIL"),
            JumpStmt(FunctionCode.GOTOFORM, null, SpCallArgument(null, emptyList(), emptyList()), 108, "GOTOFORM"),
            // 6 CallStmt
            CallStmt(FunctionCode.CALL, "SUB", SpCallArgument(StrLiteral("SUB", 109, 1), listOf(StrLiteral("x", 109, 2)), listOf(IntLiteral(2L, 109, 3))), 109, "CALL SUB"),
            // 7 CallFStmt + SpCallFArgument
            CallFStmt("FUNC_ABS", SpCallFArgument(StrLiteral("FUNC_ABS", 110, 1), emptyList(), listOf(IntLiteral(3L, 110, 2), null)), 110, "CALLF FUNC_ABS", FunctionCode.CALLF),
            // 8 CallSharpStmt + SpCallSharpArgument
            CallSharpStmt(SpCallSharpArgument(StrLiteral("Lib", 111, 1), listOf(IntLiteral(1L, 111, 2)), listOf(null)), 111, "CALLSHARP Lib"),
            // 9 MethodStmt + MethodArgument
            MethodStmt("MY_METHOD", MethodArgument(FuncCall("MY_METHOD", listOf(IntLiteral(1L)), OperandType.INT, 112, 1)), 112, "MY_METHOD 1"),
            // 10 VarDeclStmt + IntAsignArgument / StrAsignArgument
            VarDeclStmt(FunctionCode.VARI, IntAsignArgument("SLV", intArrayOf(1, 2, 3), IntLiteral(7L, 113, 1)), 113, "VARI SLV(7)"),
            VarDeclStmt(FunctionCode.VARS, StrAsignArgument("SSV", intArrayOf(0), null), 114, "VARS SSV"),
            // 11 IfStmt（condition + single）
            IfStmt(Binary(OperatorCode.GREATER, intRef(), IntLiteral(1L, 115, 1), OperandType.INT, 115, 2), ExpressionArgument(intRef()), 115, "IF LV > 1"),
            IfStmt(IntLiteral(0L, 116, 1), VoidArgument(), 116, "SIF 0", single = true),
            // 12/13/14 ElseIf / Else / EndIf
            ElseIfStmt(StrLiteral("c", 117, 1), ExpressionArgument(StrLiteral("c", 117, 2)), 117, "ELSEIF \"c\""),
            ElseStmt(VoidArgument(), 118, "ELSE"),
            EndIfStmt(VoidArgument(), 119, "ENDIF"),
            // 15/16/17/18 SELECTCASE 家族（CaseArgument 三种 CaseType）
            SelectCaseStmt(intRef(), ExpressionArgument(intRef()), 120, "SELECTCASE LV"),
            CaseStmt(
                cases = listOf(
                    CaseExpression(CaseExpression.CaseType.NORMAL, IntLiteral(1L, 121, 1), null, OperatorCode.NULL),
                    CaseExpression(CaseExpression.CaseType.TO, IntLiteral(2L, 121, 2), IntLiteral(9L, 121, 3), OperatorCode.NULL),
                    CaseExpression(CaseExpression.CaseType.IS, StrLiteral("s", 121, 4), null, OperatorCode.EQUAL),
                ),
                args = CaseArgument(emptyList()),
                line = 121,
                raw = "CASE 1, 2 TO 9, IS s",
            ),
            CaseElseStmt(VoidArgument(), 122, "CASEELSE"),
            EndSelectStmt(VoidArgument(), 123, "ENDSELECT"),
            // 19/20/21/22 FOR 家族
            ForStmt(FunctionCode.FOR, SpForNextArgument(intRef("I"), IntLiteral(0L, 124, 1), IntLiteral(9L, 124, 2), null), 124, "FOR I, 0, 9"),
            NextStmt(VoidArgument(), 125, "NEXT"),
            RepeatStmt(ExpressionArrayArgument(listOf(IntLiteral(3L, 126, 1), null)), 126, "REPEAT 3"),
            RendStmt(VoidArgument(), 127, "REND"),
            // 23/24/25/26 WHILE / WEND / DO / LOOP
            WhileStmt(Unary(OperatorCode.NOT, intRef("F"), 128, 1), ExpressionArgument(intRef("F")), 128, "WHILE !F"),
            WendStmt(VoidArgument(), 129, "WEND"),
            DoStmt(VoidArgument(), 130, "DO"),
            LoopStmt(null, VoidArgument(), 131, "LOOP"),
            // 27/28 CATCH / ENDCATCH
            CatchStmt(VoidArgument(), 132, "CATCH"),
            EndCatchStmt(VoidArgument(), 133, "ENDCATCH"),
            // 29/30 DATALIST / ENDBLOCK
            DataListStmt(FunctionCode.DATALIST, VoidArgument(), 134, "DATALIST"),
            EndBlockStmt(FunctionCode.ENDLIST, VoidArgument(), 135, "ENDLIST"),
            // 31 FlowStmt + ExpressionsArgument（types 含 null）
            FlowStmt(FunctionCode.BREAK, ExpressionsArgument(listOf(null, ArgValueType.INT), listOf(IntLiteral(1L, 136, 1), null)), 136, "BREAK"),
            ReturnStmt(listOf(IntLiteral(1L, 137, 1), null, StrLiteral("x", 137, 2)), ExpressionArrayArgument(listOf(null)), 137, "RETURN 1, , \"x\""),
            // 33 ReturnFStmt（RETURNFORM 变体）
            ReturnFStmt(FormStringTerm(listOf(FormPart.Text("a")), 138, 1), VoidArgument(), 138, "RETURNFORM a", FunctionCode.RETURNFORM),
            // 34/35/36 args 由类推导的三类
            FuncLabelStmt("EVENTTRAIN", listOf("A", "B", "C"), 139, "@EVENTTRAIN(A, B, C)"),
            GotoLabelStmt("TAIL", 140, "\$TAIL"),
            SharpStmt(keyword = "#DIM", values = listOf(3L, 5L), name = "LV", isString = false, tokens = emptyList(), line = 141, raw = "#DIM LV,3,5"),
            // 37 DataStmt（数据节成员：语句身份就是它的键）
            DataStmt(FunctionCode.DATA, "第一组文本", VoidArgument(), 142, "DATA 第一组文本"),
            DataStmt(FunctionCode.DATAFORM, "第二组文本", VoidArgument(), 142, "DATAFORM 第二组文本"),
            DataStmt(FunctionCode.DATA, "第一组文本", VoidArgument(), 142, "DATA 第一组文本"),
            // ---- 其余参数类（挂到 InstructionStmt 上；codec 覆盖用，不追求真实配对）----
            InstructionStmt(FunctionCode.SETBIT, BitArgument(intRef(), listOf(IntLiteral(1L, 143, 1))), 143, "SETBIT LV,1"),
            InstructionStmt(FunctionCode.REF, RefArgument("REFNAME", "SRC", IntLiteral(1L, 144, 1), refByName = true), 144, "REFBYNAME"),
            InstructionStmt(FunctionCode.STRDATA, StrDataArgument(strRef()), 145, "STRDATA RES$"),
            InstructionStmt(FunctionCode.ARRAYREMOVE, SpArrayControlArgument(intRef(), IntLiteral(1L, 146, 1), null), 146, "ARRAYREMOVE"),
            InstructionStmt(FunctionCode.ARRAYSHIFT, SpArrayShiftArgument(intRef(), IntLiteral(1L), IntLiteral(0L), IntLiteral(0L), null), 147, "ARRAYSHIFT"),
            InstructionStmt(FunctionCode.ARRAYSORT, SpArraySortArgument(intRef(), SortOrder.DESENDING, IntLiteral(0L), null), 148, "ARRAYSORT DESC"),
            InstructionStmt(FunctionCode.BAR, SpBarArgument(IntLiteral(1L), IntLiteral(2L), IntLiteral(3L)), 149, "BAR 1,2,3"),
            InstructionStmt(FunctionCode.PRINTBUTTON, SpButtonArgument(StrLiteral("钮", 150, 1), IntLiteral(4L, 150, 2)), 150, "PRINTBUTTON 钮, 4"),
            InstructionStmt(FunctionCode.CVARSET, SpCVarSetArgument(strRef(), IntLiteral(0L), StrLiteral("v", 151, 1), IntLiteral(0L), IntLiteral(1L)), 151, "CVARSET"),
            InstructionStmt(FunctionCode.SETCOLOR, SpColorArgument(IntLiteral(1L), IntLiteral(2L), IntLiteral(3L)), 152, "SETCOLOR 1,2,3"),
            InstructionStmt(FunctionCode.SETBGCOLOR, SpColorArgument(IntLiteral(0xFF0000L)), 153, "SETBGCOLOR 0xFF0000"),
            InstructionStmt(FunctionCode.ARRAYCOPY, SpCopyArrayArgument(StrLiteral("A", 154, 1), StrLiteral("B", 154, 2)), 154, "ARRAYCOPY A,B"),
            InstructionStmt(
                FunctionCode.DT_COLUMN_OPTIONS,
                SpDtColumnOptions(IntLiteral(0L), StrLiteral("Default", 155, 1), listOf(SpDtColumnOptions.DTOption.DEFAULT), listOf(IntLiteral(1L, 155, 2), null)),
                155,
                "DT_COLUMN_OPTIONS",
            ),
            InstructionStmt(FunctionCode.PRINTCPERLINE, SpGetIntArgument(intRef("RESULT")), 156, "PRINTCPERLINE RESULT"),
            InstructionStmt(FunctionCode.HTML_PRINT, SpHtmlPrint(StrLiteral("<b>", 157, 1), IntLiteral(1L, 157, 2)), 157, "HTML_PRINT \"<b>\", 1"),
            InstructionStmt(FunctionCode.HTML_TAGSPLIT, SpHtmlSplitArgument(StrLiteral("<i>x</i>", 158, 1), "SPLITED", intRef("RESULT")), 158, "HTML_TAGSPLIT"),
            InstructionStmt(FunctionCode.INPUT, SpInputsArgument(StrLiteral("消息", 159, 1), IntLiteral(0L), IntLiteral(1L)), 159, "INPUT 消息"),
            InstructionStmt(FunctionCode.POWER, SpPowerArgument(intRef(), IntLiteral(2L), IntLiteral(3L)), 160, "POWER LV,2,3"),
            InstructionStmt(
                FunctionCode.PRINT_IMG,
                SpPrintImgArgument(StrLiteral("a.webp", 161, 1), null, StrLiteral("m.webp", 161, 2), listOf(MixedIntegerExprTerm(IntLiteral(10L, 161, 3), true), MixedIntegerExprTerm(null, false))),
                161,
                "PRINT_IMG",
            ),
            InstructionStmt(
                FunctionCode.PRINT_RECT,
                SpPrintShapeArgument(listOf(MixedIntegerExprTerm(IntLiteral(20L, 162, 1), false))),
                162,
                "PRINT_RECT",
            ),
            InstructionStmt(FunctionCode.SAVEDATA, SpSaveDataArgument(IntLiteral(1L), StrLiteral("mes", 163, 1)), 163, "SAVEDATA 1, mes"),
            InstructionStmt(FunctionCode.SAVEVAR, SpSaveVarArgument(StrLiteral("NO", 164, 1), StrLiteral("mes", 164, 2), listOf("A", "B")), 164, "SAVEVAR"),
            InstructionStmt(
                FunctionCode.VARSET,
                SpSetArrayArgument(intRef(), listOf(IntLiteral(1L, 165, 1), null), longArrayOf(1L, 2L), arrayOf("x", "y")),
                165,
                "VARSET",
            ),
            InstructionStmt(
                FunctionCode.VARSET,
                SpSetArrayArgument(intRef("OTHER"), emptyList(), null, null),
                166,
                "VARSET OTHER",
            ),
            InstructionStmt(FunctionCode.SORTCHARA, SpSortcharaArgument(intRef("EXP"), SortOrder.ASCENDING), 167, "SORTCHARA EXP"),
            InstructionStmt(FunctionCode.SPLIT, SpSplitArgument(StrLiteral("a,b", 168, 1), StrLiteral(",", 168, 2), "SPLITED", intRef("RESULT")), 168, "SPLIT"),
            InstructionStmt(FunctionCode.SWAPCHARA, SpSwapCharaArgument(IntLiteral(1L), IntLiteral(2L)), 169, "SWAPCHARA 1,2"),
            InstructionStmt(FunctionCode.SWAP, SpSwapVarArgument(intRef(), intRef("OTHER")), 170, "SWAP LV, OTHER"),
            InstructionStmt(
                FunctionCode.TINPUT,
                SpTInputsArgument(IntLiteral(100L), StrLiteral("d", 171, 1), IntLiteral(0L), StrLiteral("t", 171, 2), IntLiteral(1L), IntLiteral(0L)),
                171,
                "TINPUT",
            ),
            InstructionStmt(FunctionCode.TIMES, SpTimesArgument(intRef(), 1.25), 172, "TIMES LV, 1.25"),
            InstructionStmt(FunctionCode.VARSET, SpVarSetArgument(intRef(), IntLiteral(0L), IntLiteral(1L), null), 173, "VARSET LV, 0, 1"),
            InstructionStmt(FunctionCode.VARSIZE, SpVarsizeArgument("ITEM"), 174, "VARSIZE ITEM"),
            InstructionStmt(FunctionCode.TOOLTIP_SETDELAY, StrDoubleArgument(IntLiteral(1L, 175, 1), -0.0), 175, "TOOLTIP_SETDELAY"),
            InstructionStmt(FunctionCode.HTML_PRINT_ISLAND, HtmlPrintArgument(StrLiteral("i", 176, 1), null), 176, "HTML_PRINT_ISLAND"),
        )
    }

    // ==================================================================
    // 断言工具
    // ==================================================================

    private fun assertContentEquals(
        what: String,
        expected: IntArray,
        actual: IntArray,
    ) {
        assertEquals(expected.size, actual.size, "$what 长度")
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], "$what[$i]")
        }
    }

    /**
     * 反射逐字段比较两棵对象图（不依赖 data class 的 `equals`，也不依赖 erbc 编解码本身）。
     *
     * 必要性：语句/参数类**都不是** data class（身份语义，见 `Statement.kt:58`），
     * 而 `assertEquals(expected, actual)` 对它们等价于 `assertSame`，抓不到「字段少写一个」。
     * 反射只看实例字段（`declaredFields` 去掉 static / synthetic），沿继承链上溯到
     * [Statement] / [Argument] 基类；数组与列表按元素递归，Double 按 raw bits 比较
     * （-0.0 与 0.0 必须区分，否则 8 字节 raw bits 的往返就白测了）。
     */
    private fun assertGraphEqual(
        path: String,
        expected: Any?,
        actual: Any?,
    ) {
        if (expected === actual) return
        if (expected == null || actual == null) {
            assertEquals(expected, actual, "$path（一侧为 null）")
            return
        }
        // 列表容器实现类（SingletonList / ArrayList / …）不是语义：只有元素序列才是。
        val listPair = expected is List<*> && actual is List<*>
        if (expected.javaClass != actual.javaClass && !listPair) {
            fail("$path 类不同: ${expected.javaClass.simpleName} vs ${actual.javaClass.simpleName}")
        }
        when (expected) {
            is Double -> assertEquals(expected.toRawBits(), (actual as Double).toRawBits(), "$path（Double raw bits）")
            is String, is Boolean, is Char, is Number, is Enum<*> -> assertEquals(expected, actual, path)
            is IntArray -> assertEquals(expected.toList(), (actual as IntArray).toList(), "$path（IntArray）")
            is LongArray -> assertEquals(expected.toList(), (actual as LongArray).toList(), "$path（LongArray）")
            is DoubleArray -> assertEquals(
                expected.map { it.toRawBits() },
                (actual as DoubleArray).map { it.toRawBits() },
                "$path（DoubleArray）",
            )

            is ByteArray -> assertContentEquals(path, expected, actual as ByteArray)
            is Array<*> -> {
                val other = actual as Array<*>
                assertEquals(expected.size, other.size, "$path（数组长度）")
                for (i in expected.indices) assertGraphEqual("$path[$i]", expected[i], other[i])
            }

            is List<*> -> {
                val other = actual as List<*>
                assertEquals(expected.size, other.size, "$path（列表长度）")
                for (i in expected.indices) assertGraphEqual("$path[$i]", expected[i], other[i])
            }

            else -> {
                val fields = instanceFields(expected.javaClass)
                assertTrue(fields.isNotEmpty(), "$path 没有可比对的实例字段：${expected.javaClass}")
                for (f in fields) {
                    f.isAccessible = true
                    assertGraphEqual("$path.${f.name}", f.get(expected), f.get(actual))
                }
            }
        }
    }

    private fun assertContentEquals(
        path: String,
        expected: ByteArray,
        actual: ByteArray,
    ) {
        assertEquals(expected.size, actual.size, "$path（字节数组长度）")
        for (i in expected.indices) assertEquals(expected[i], actual[i], "$path[$i]")
    }

    private fun instanceFields(clazz: Class<*>): List<java.lang.reflect.Field> {
        val out = ArrayList<java.lang.reflect.Field>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.isSynthetic) continue
                if (f.name.startsWith("\$")) continue
                out.add(f)
            }
            c = c.superclass
        }
        return out
    }

    // ==================================================================
    // 手写字节工具（与 ErbcSink 无关；测试自己按规约拼字节）
    // ==================================================================

    private class Hand {
        private val out = ArrayList<Int>()

        fun u8(v: Int): Hand {
            out.add(v and 0xFF)
            return this
        }

        fun u16(v: Int): Hand = apply {
            u8(v)
            u8(v ushr 8)
        }

        fun u32(v: Int): Hand = apply {
            u8(v)
            u8(v ushr 8)
            u8(v ushr 16)
            u8(v ushr 24)
        }

        fun i32(v: Int): Hand = u32(v)

        fun i64(v: Long): Hand = apply {
            for (i in 0 until 8) u8((v ushr (8 * i)).toInt())
        }

        fun put(bytes: ByteArray): Hand = apply { for (b in bytes) u8(b.toInt()) }

        fun build(): ByteArray = ByteArray(out.size) { out[it].toByte() }
    }

    private fun hand(block: Hand.() -> Unit): ByteArray = Hand().apply(block).build()

    /** `STRING_TABLE ::= u32 count | u32 dataLen | u32 offset[count+1] | data`（规约 §5.1）。 */
    private fun handStringTable(items: List<String>): ByteArray = hand {
        u32(items.size)
        val encoded = items.map { it.toByteArray(Charsets.UTF_8) }
        u32(encoded.sumOf { it.size })
        var acc = 0
        for (e in encoded) {
            u32(acc)
            acc += e.size
        }
        u32(acc)
        for (e in encoded) put(e)
    }

    /** 文件头 + 段表 + 各段正文（规约 §2）；null 段写偏移 0。手搓的一律是 **v1** 容器（7 槽）。 */
    private fun handContainer(
        flags: Int,
        bodies: Array<ByteArray?>,
    ): ByteArray {
        assertEquals(ErbcFormat.SECTION_COUNT_V1, bodies.size, "v1 段表槽数固定为 7")
        val offsets = IntArray(bodies.size)
        var next = ErbcFormat.HEADER_BYTES + bodies.size * 4
        for (i in bodies.indices) {
            if (bodies[i] != null) {
                offsets[i] = next
                next += bodies[i]!!.size
            }
        }
        return hand {
            u8(ErbcFormat.MAGIC_0)
            u8(ErbcFormat.MAGIC_1)
            u8(ErbcFormat.MAGIC_2)
            u8(ErbcFormat.MAGIC_3)
            u16(ErbcFormat.VERSION_1)
            u16(flags)
            u16(bodies.size)
            for (o in offsets) u32(o)
            for (b in bodies) if (b != null) put(b)
        }
    }

    private fun writeLe32(
        dst: ByteArray,
        at: Int,
        value: Int,
    ) {
        dst[at] = (value and 0xFF).toByte()
        dst[at + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[at + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
