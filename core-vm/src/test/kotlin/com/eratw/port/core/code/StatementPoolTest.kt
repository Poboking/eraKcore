package com.eratw.port.core.code

import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.PrintDataStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 语句池（契约 C2）+ marker 编码（契约 C3）+ 汇编/反汇编往返。
 *
 * 池口径：按身份追加、严禁去重 —— 依据 sem 数据节以 `Statement` 实例为键
 * （ExecContext.kt:96-100「键为该 PRINTDATA 语句实例」、ProcessRuntime.kt:488-495、
 * StatementExecutor.kt:1267,1286；原版数据节挂在语句行对象上：FunctionIdentifier.cs:4285
 * IsPrintData、:285 PrintDataArgument 取数）。结构相等的两条语句必须是两个条目。
 *
 * 本测试全部在内存内完成，不涉及 `.erbc` 落盘（Program.kt 头部 TODO(verify) 仍开放）。
 */
class StatementPoolTest {

    private fun stmt(code: FunctionCode, line: Int = 1, raw: String = code.name) =
        InstructionStmt(code, VoidArgument(), line, raw)

    // ------------------------------------------------------------------
    // C2：池语义
    // ------------------------------------------------------------------

    @Test
    fun `结构相等的两条语句是两个条目 严禁去重`() {
        val pool = ConstantPool()
        // 同 code、同 args 类型、同行号、同原文 —— 只有实例身份不同。
        val a = stmt(FunctionCode.PRINTDATA, line = 7, raw = "PRINTDATA CHARA")
        val b = stmt(FunctionCode.PRINTDATA, line = 7, raw = "PRINTDATA CHARA")
        assertNotSame(a, b)

        val ia = pool.addStatement(a)
        val ib = pool.addStatement(b)
        assertNotEquals(ia, ib, "语句池去重了 —— sem 的 PRINTDATA 数据节按实例身份取数，去重即串数据")
        assertEquals(0, ia)
        assertEquals(1, ib)
        assertEquals(2, pool.statementCount)
        assertSame(a, pool.statementAt(ia))
        assertSame(b, pool.statementAt(ib))
    }

    @Test
    fun `同一实例重复追加同样新开条目`() {
        val pool = ConstantPool()
        val s = stmt(FunctionCode.PRINT)
        val i0 = pool.addStatement(s)
        val i1 = pool.addStatement(s)
        assertNotEquals(i0, i1, "追加语义 = 每次恒新开条目；调用方引用两次不等于可以合并")
        assertSame(s, pool.statementAt(i0))
        assertSame(s, pool.statementAt(i1))
        assertEquals(2, pool.statementCount)
    }

    @Test
    fun `下标按追加序稳定且不受其他池影响`() {
        val b = CodeBuilder()
        val s0 = stmt(FunctionCode.SET, line = 2, raw = "SET RESULT, 0")
        val s1 = PrintDataStmt(FunctionCode.PRINTDATA, VoidArgument(), 3, "PRINTDATA")
        val s2 = stmt(FunctionCode.CALLEVENT, line = 4, raw = "CALLEVENT \"ERIMARKER\"")

        assertEquals(0, b.statement(s0))
        val longX = b.constant(123L)
        assertEquals(1, b.statement(s1))
        val strX = b.string("x")
        assertEquals(2, b.statement(s2))
        // 其他池的追加不移动语句下标（稳定下标是 marker `a` 可回填的前提）。
        b.emit(Opcode._NOP, 0, 0, 0, -1)
        b.declareFunction("main", 0, 0, 1)
        val p = b.build(0)

        assertEquals(3, p.statementCount)
        assertSame(s0, p.statement(0))
        assertSame(s1, p.statement(1))
        assertSame(s2, p.statement(2))
        assertEquals(123L, p.constant(longX))
        assertEquals("x", p.string(strX))
        // 快照顺序 == 追加序（汇编往返按此复用）。
        assertEquals(listOf<Statement>(s0, s1, s2), p.statements())
    }

    // ------------------------------------------------------------------
    // C3：marker 编码与 4×u32 定长
    // ------------------------------------------------------------------

    @Test
    fun `marker 编码 a 放 32 位下标且定长四字不受影响`() {
        val b = CodeBuilder()
        // 语句池下标是 u32 全宽立即数模式（Instruction 模式表 STMT 行）：取 Int 正半轴上界。
        val pc = b.emit(Opcode.PRINT, Int.MAX_VALUE, 0, SlotAddr.NONE, line = 1)
        b.emit(Opcode._NOP, 0, 0, 0, -1)
        b.declareFunction("main", 0, 0, 1)
        val p = b.build(0)

        assertEquals(Instruction.WORDS, 4)
        assertEquals(8, p.code.size, "每条指令恒占 4 个 u32 字")
        assertEquals(Opcode.PRINT, p.op(pc))
        assertEquals(Int.MAX_VALUE, p.a(pc))
        assertEquals(0, p.b(pc))
        assertEquals(SlotAddr.NONE, p.c(pc))
        assertEquals(Instruction(Opcode.PRINT, Int.MAX_VALUE, 0, SlotAddr.NONE), p.instructionAt(pc))
    }

    // ------------------------------------------------------------------
    // Assembler：stmt#<k> 形式
    // ------------------------------------------------------------------

    @Test
    fun `stmt#k 发射 marker 且池下标等于列表下标`() {
        val sA = stmt(FunctionCode.SET, line = 9, raw = "SET RESULT, 0")
        val sB = stmt(FunctionCode.PRINT, line = 10, raw = "PRINTL")
        val p = Assembler.assemble(
            """
            PRINT stmt#1
            _HALT
            """.trimIndent(),
            statements = listOf(sA, sB),
        )

        assertEquals(2, p.statementCount, "整表按序预注册：未引用的条目也进池，保证 k == 池下标")
        assertSame(sB, p.statement(p.a(0)))
        assertEquals(Opcode.PRINT, p.op(0))
        assertEquals(1, p.a(0))
        assertEquals(0, p.b(0))
        assertEquals(SlotAddr.NONE, p.c(0), "契约 C3：marker 的 c = SlotAddr.NONE")
        assertEquals(Opcode._HALT, p.op(1))
    }

    @Test
    fun `同一语句可被多条 marker 引用`() {
        val s = stmt(FunctionCode.PRINT, line = 1, raw = "PRINT")
        val p = Assembler.assemble(
            """
            PRINT stmt#0
            PRINTL stmt#0
            _HALT
            """.trimIndent(),
            statements = listOf(s),
        )
        // PRINTL 是 PRINT 的别名（StatementAliases）——两条 marker 同 op、同 a、指向同一实例。
        assertEquals(p.op(0), p.op(1))
        assertEquals(0, p.a(0))
        assertEquals(0, p.a(1))
        assertEquals(1, p.statementCount, "引用两次不重复进池：注册发生在 statements 列表，一次一条")
        assertSame(s, p.statement(p.a(1)))
    }

    @Test
    fun `stmt# 越界或与立即数混排报错`() {
        val s = stmt(FunctionCode.PRINT)
        val e1 = assertFailsWith<AssemblyException> {
            Assembler.assemble("PRINT stmt#2", statements = listOf(s))
        }
        assertTrue(e1.message!!.contains("越界"), e1.message)

        val e2 = assertFailsWith<AssemblyException> {
            Assembler.assemble("PRINT stmt#0, 1", statements = listOf(s))
        }
        assertTrue(e2.message!!.contains("唯一操作数"), e2.message)

        val e3 = assertFailsWith<AssemblyException> { Assembler.assemble("PRINT stmt#0") }
        assertTrue(e3.message!!.contains("越界"), e3.message)
    }

    // ------------------------------------------------------------------
    // 反汇编往返（Disassembler 未改动：marker 的 a 打印为整数，按立即数原样回解析）
    // ------------------------------------------------------------------

    @Test
    fun `marker 经反汇编文本往返 指令字与 a 池下标不丢`() {
        val sA = stmt(FunctionCode.SET, line = 1, raw = "SET RESULT, 0")
        val sB = stmt(FunctionCode.PRINT, line = 2, raw = "PRINT A")
        val p1 = Assembler.assemble(
            """
            .func main slots=1
              SET stmt#0
              PRINT stmt#1
              _HALT
            """.trimIndent(),
            statements = listOf(sA, sB),
        )
        // Opcode.SET == 121（语句码区），不是内部指令 _SET_INT；见 isa 分段（OpcodeTable.kt 头注）。
        val text = p1.disassemble()
        // Disassembler 的语句 else 分支把 a,b,c 原样打印为整数 —— marker 即 "SET 0, 0, -1"。
        // TODO(verify): 契约所有权表要求 "a 打印成 stmt#N"；这必须改 Disassembler.kt（我只读该文件），
        //   已报告组长。当前形态不丢信息：a 仍是池下标，按立即数往返是字节级恒等的。
        assertTrue(text.contains("SET 0, 0, -1"), "反汇编文本未出现 marker 整数形态:\n$text")

        val p2 = Assembler.assemble(text, p1.expressions(), p1.statements())
        assertEquals(p1.code.toList(), p2.code.toList(), "往返后指令字（含 marker 的 a 下标）必须逐字一致")
        assertEquals(p1.statementCount, p2.statementCount)
        for (i in 0 until p1.statementCount) assertSame(p1.statement(i), p2.statement(i))
        // 往返后仍可由 a 读回池条目：语义闭环。
        val markerPc = p2.functions[0].entryPc
        assertSame(sA, p2.statement(p2.a(markerPc)))
    }

    // ------------------------------------------------------------------
    // 冻结（MEM-OPT）：Program 构建后释放去重索引
    // ------------------------------------------------------------------

    @Test
    fun `冻结后 addLong addString addExpr 拒绝追加 语句池仍可追加`() {
        val pool = ConstantPool()
        val li = pool.addLong(42L)
        val si = pool.addString("文本")
        val s = stmt(FunctionCode.PRINT, raw = "PRINT 文本")
        val i = pool.addStatement(s)

        pool.seal()

        val e1 = assertFailsWith<IllegalStateException> { pool.addLong(1L) }
        assertTrue(e1.message!!.contains("冻结"), e1.message)
        assertFailsWith<IllegalStateException> { pool.addString("x") }
        assertFailsWith<IllegalStateException> { pool.addExpr(com.eratw.port.core.expr.IntLiteral(1L)) }

        // 条目与只读路径全部原样：Program 构建后的运行期只走这些。
        assertEquals(42L, pool.longAt(li))
        assertEquals("文本", pool.stringAt(si))
        assertSame(s, pool.statementAt(i))
        assertEquals(1, pool.longCount)
        assertEquals(1, pool.stringCount)
        assertEquals(0, pool.exprCount)
        // 语句池本就没有去重索引：ErbcReader 重灌（先 addStatement 再构造 Program）依赖这一点。
        val s2 = stmt(FunctionCode.PRINT, raw = "PRINT 文本")
        assertEquals(i + 1, pool.addStatement(s2))
    }
}
