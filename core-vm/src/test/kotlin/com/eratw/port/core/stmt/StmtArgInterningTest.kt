package com.eratw.port.core.stmt

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.loader.ProgramLoader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 语句参数子树规范化去重（[ArgInterner]）的语义边界。
 *
 * 钉住三件事：
 * 1. 结构相等的参数**合并**成同一实例；结构不同（含 `isConst/constInt` 这类可变缓存位）
 *    **不合并**；
 * 2. 合并后读出的字段与合并前逐项相同（可变缓存位、条件、列表都原值）；
 * 3. 幂等：第二次调用不再改动任何语句。
 */
class StmtArgInterningTest {

    private fun programOf(vararg stmts: Statement): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        for (s in stmts) {
            val idx = b.statement(s)
            b.emit(Opcode.PRINT, idx, 0, SlotAddr.NONE, 0)
        }
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        return b.build(0)
    }

    private fun printLine(term: com.eratw.port.core.expr.Expr, line: Int, raw: String): InstructionStmt =
        InstructionStmt(FunctionCode.PRINTL, ExpressionArgument(term), line, raw)

    @Test
    fun `同形参数合并为单实例且异形不合并`() {
        val a = printLine(StrLiteral("セリフ"), 1, "PRINTL セリフ")
        val b = printLine(StrLiteral("セリフ"), 2, "PRINTL セリフ")
        val c = printLine(StrLiteral("別の台詞"), 3, "PRINTL 別の台詞")
        // 同 Term、但编译期常量缓存位不同 ⇒ 结构不同，绝不能合并
        val constArgs = ExpressionArgument(IntLiteral(5)).apply {
            isConst = true
            constInt = 5
        }
        val plainArgs = ExpressionArgument(IntLiteral(5))
        val d = InstructionStmt(FunctionCode.PRINTL, constArgs, 4, "PRINTL 5")
        val e = InstructionStmt(FunctionCode.PRINTL, plainArgs, 5, "PRINTL 5")
        val program = programOf(a, b, c, d, e)

        val stats = program.internStatementArgs()
        assertSame(program.statement(0).args, program.statement(1).args, "结构相等的参数应合并")
        assertNotSame(program.statement(0).args, program.statement(2).args, "结构不同不得合并")
        assertNotSame(program.statement(3).args, program.statement(4).args, "isConst 不同的参数不得合并")

        // 读出值原样（合并会换实例，但字段一个都不能变）
        val kept = program.statement(3).args as ExpressionArgument
        assertTrue(kept.isConst, "isConst 必须随实例保留")
        assertEquals(5L, kept.constInt, "constInt 必须随实例保留")
        assertEquals("セリフ", (program.statement(1).args as ExpressionArgument).literalStr)
        assertTrue(stats.merged >= 1, "至少应合并一个结点（实测 ${stats.merged}）")
    }

    @Test
    fun `条件与列表字段一并规范化`() {
        val cond1 = VarRef("RESULT", listOf(IntLiteral(0)), false, OperandType.INT)
        val cond2 = VarRef("RESULT", listOf(IntLiteral(0)), false, OperandType.INT)
        val s1 = IfStmt(cond1, ExpressionArgument(cond1), 1, "IF RESULT:0")
        val s2 = IfStmt(cond2, ExpressionArgument(cond2), 2, "IF RESULT:0")
        val program = programOf(s1, s2)
        program.internStatementArgs()
        assertSame(s1.condition, s2.condition, "条件表达式应合并")
        assertEquals((s1.args as ExpressionArgument).term, s1.condition, "args 里的同一表达式也应指向合并后的实例")
    }

    @Test
    fun `去重幂等`() {
        val a = printLine(StrLiteral("x"), 1, "PRINTL x")
        val b = printLine(StrLiteral("x"), 2, "PRINTL x")
        val program = programOf(a, b)
        val first = program.internStatementArgs()
        val args0 = program.statement(0).args
        val args1 = program.statement(1).args
        val second = program.internStatementArgs()
        assertSame(first, second, "第二次调用应直接返回首次读数")
        assertSame(args0, program.statement(0).args)
        assertSame(args1, program.statement(1).args)
    }

    @Test
    fun `erbc 装载路径同样去重且再编码逐字节不变`() {
        val a = printLine(StrLiteral("セリフ"), 1, "PRINTL セリフ")
        val b = printLine(StrLiteral("セリフ"), 2, "PRINTL セリフ")
        val program = programOf(a, b)
        val bytes = ErbcWriter.encode(program)
        val loaded = ProgramLoader.load(bytes)
        assertNotNull(loaded.argsInterned, "Loader 装载后应已跑过规范化去重")
        // 串池读数必须随 Stats 一起交出去：`perfBaseline` 的「串池命中率」只有这一个来源，
        // 漏传就是 `Stats.strings` 的缺省值 EMPTY（0 请求 / 0% 命中），报告里看着像串池没生效。
        // 两条装载路径（整池聚合 / 惰性池种子）都在这条断言后面。
        assertTrue(
            loaded.argsInterned!!.strings.requests > 0,
            "装载路径的 Stats 必须带上串池读数，实际 ${loaded.argsInterned}",
        )
        assertSame(loaded.statement(0).args, loaded.statement(1).args, "装载路径上的同形参数应合并")
        assertContentEquals(
            bytes,
            ErbcWriter.encode(loaded),
            "去重不得改变 .erbc 落盘字节：节点表按结构去重（不看实例身份），" +
                "本例各结点的 line/col 相同，故往返逐字节一致",
        )
    }

    /**
     * 防回归（2026-10-22 真机死循环根因）：判等的字段分类若不完整（真机 ART 上
     * `trySetAccessible` 缺失曾被 runCatching 吞成 false ⇒ 全字段被静默跳过 ⇒ Shape 空），
     * 同类节点会全部判「同构」互相折叠 —— `I[0]=0` 被换成先物化的 `_i[0]=0`，
     * FOR 计数器写丢失、循环永不退出。本测试把**后果**钉死在桌面语义上：
     * 仅 name 不同 / 仅值不同的结点必须保持区分，且去重后 dest 读出原变量名。
     * 配套约束（ArgInterner.shapeOf）：字段可访问性失败必须 fail-fast，禁止静默 continue。
     */
    @Test
    fun `仅变量名或值不同的节点绝不互相折叠`() {
        val sI = InstructionStmt(
            FunctionCode.PRINTL,
            SpSetArgument(VarRef("I", listOf(IntLiteral(0)), false, OperandType.INT), IntLiteral(0)),
            7,
            "I = 0",
        ) as Statement
        val s_i = InstructionStmt(
            FunctionCode.PRINTL,
            SpSetArgument(VarRef("_i", listOf(IntLiteral(0)), false, OperandType.INT), IntLiteral(0)),
            59,
            "_i = 0",
        ) as Statement
        val sOne = InstructionStmt(
            FunctionCode.PRINTL,
            SpSetArgument(VarRef("I", listOf(IntLiteral(0)), false, OperandType.INT), IntLiteral(1)),
            7,
            "I = 1",
        ) as Statement
        val program = programOf(sI, s_i, sOne)
        program.internStatementArgs()
        val d0 = (program.statement(0).args as SpSetArgument).dest
        val d1 = (program.statement(1).args as SpSetArgument).dest
        val t0 = (program.statement(0).args as SpSetArgument).term
        val t2 = (program.statement(2).args as SpSetArgument).term
        assertEquals("I", d0.name, "#0 dest 变量名必须保持 I")
        assertEquals("_i", d1.name, "仅 name 不同的 VarRef 不得折叠（真机死循环根因）")
        assertNotSame(d0, d1, "VarRef(I,[0]) 与 VarRef(_i,[0]) 结构不同")
        assertEquals(0L, (t0 as IntLiteral).value, "#0 term 值保持 0")
        assertEquals(1L, (t2 as IntLiteral).value, "仅值不同的 IntLiteral 不得折叠成 Int(0)")
        assertNotSame(t0, t2, "IntLiteral(0) 与 IntLiteral(1) 结构不同")
    }

    /**
     * 折叠审计（2026-10-22 复盘方案 #2）：审计开启**不得改变折叠语义**；健康折叠
     * （结构相等 ⇒ 关键摘要必相同）必须零 RED —— RED 判定的误报 = 审计自身失格。
     */
    @Test
    fun `折叠审计对正常折叠零误报且不改语义`() {
        val prev = StmtInternAudit.enabled
        StmtInternAudit.enabled = true
        try {
            val a = printLine(StrLiteral("セリフ"), 1, "PRINTL セリフ")
            val b = printLine(StrLiteral("セリフ"), 2, "PRINTL セリフ")
            val program = programOf(a, b)
            program.internStatementArgs()
            assertSame(program.statement(0).args, program.statement(1).args, "审计开启时同形参数仍应合并（语义不变）")
            val lines = StmtInternAudit.drain()
            assertTrue(lines.isNotEmpty(), "drain 必须返回摘要行")
            assertTrue(
                lines.first().contains("可疑（关键摘要不同）0 次"),
                "健康折叠必须零 RED（有误报 = 审计失格）: ${lines.first()}",
            )
        } finally {
            StmtInternAudit.drain() // 清残留，防污染后续测试
            StmtInternAudit.enabled = prev
        }
    }
}
