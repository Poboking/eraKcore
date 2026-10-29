package com.eratw.port.core.compile

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.FlowStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 语句树规范折叠（MEM-OPT）的语义保持测试：
 * 值等价 + 行列归一、可变常量缓存迁移、跨语句实例共享、契约 C2 身份不变。
 */
class ExprCanonicalizerTest {

    private val c = ExprCanonicalizer()

    /** `LV = 7`（term 由调用方给，带各自的行列号）。 */
    private fun mk(term: IntLiteral): AssignStmt {
        val dest = VarRef("LV", listOf(IntLiteral(0, 9, 9)), operandType = OperandType.INT, line = 8, col = 8)
        return AssignStmt(dest, OperatorCode.ASSIGNMENT, SpSetArgument(dest, term), 1, "LV = 7")
    }

    @Test
    fun `折叠后值等价 行列归一为 -1 语句行号与原文保留`() {
        val s = AssignStmt(
            VarRef("LV", listOf(IntLiteral(0, 1, 2)), operandType = OperandType.INT, line = 3, col = 4),
            OperatorCode.ASSIGNMENT,
            SpSetArgument(
                VarRef("LV", emptyList(), operandType = OperandType.INT),
                Binary(
                    OperatorCode.PLUS,
                    IntLiteral(5, 5, 1),
                    VarRef("RESULT", listOf(IntLiteral(0)), operandType = OperandType.INT),
                    OperandType.INT,
                    6,
                    2,
                ),
            ),
            9,
            "LV = 5 + RESULT",
        )
        val r = c.canonicalizeStatement(s) as AssignStmt
        assertEquals(s.line, r.line, "语句行号不归一")
        assertEquals(s.raw, r.raw, "语句原文不归一")
        assertEquals(s.op, r.op)
        assertEquals("LV", r.dest.name)
        assertEquals(-1, r.dest.line, "VarRef 行号应归一")
        assertEquals(-1, r.dest.col, "VarRef 列号应归一")
        assertEquals(IntLiteral(0, -1, -1), r.dest.indices.single(), "下标字面量应归一")
        val t = (r.args as SpSetArgument).term!! as Binary
        assertEquals(IntLiteral(5, -1, -1), t.left)
        assertEquals(OperatorCode.PLUS, t.op)
        assertEquals(-1, t.line)
    }

    @Test
    fun `可变常量缓存随拷贝迁移`() {
        val dest = VarRef("LV", emptyList(), operandType = OperandType.INT)
        val arg = SpSetArgument(dest, IntLiteral(3, 1, 1)).apply {
            addConst = true
            isConst = true
            constInt = 3L
        }
        val s = AssignStmt(dest, OperatorCode.ASSIGNMENT, arg, 1, "LV += 3")
        val r = c.canonicalizeStatement(s) as AssignStmt
        val ra = r.args as SpSetArgument
        assertTrue(ra.addConst, "addConst 必须随拷贝迁移")
        assertTrue(ra.isConst)
        assertEquals(3L, ra.constInt)
    }

    @Test
    fun `重复字面量跨语句共享同一规范实例`() {
        val a = c.canonicalizeStatement(mk(IntLiteral(7, 1, 1))) as AssignStmt
        val b = c.canonicalizeStatement(mk(IntLiteral(7, 2, 2))) as AssignStmt
        val ta = (a.args as SpSetArgument).term!!
        val tb = (b.args as SpSetArgument).term!!
        assertSame(ta, tb, "同值字面量（行列不同）应折叠为同一实例")
    }

    @Test
    fun `结构相等的两条语句仍是两个实例 契约C2`() {
        val r1 = c.canonicalizeStatement(mk(IntLiteral(7, 1, 1))) as AssignStmt
        val r2 = c.canonicalizeStatement(mk(IntLiteral(7, 1, 1))) as AssignStmt
        assertNotSame(r1, r2, "语句池按身份追加：折叠绝不合并语句实例")
        assertNotSame(r1.args, r2.args, "参数记录同样各是各的实例")
        val a1 = r1.args as SpSetArgument
        val a2 = r2.args as SpSetArgument
        assertSame(a1.term, a2.term, "但树内 Expr 节点共享规范实例")
        assertEquals(a1.dest.name, a2.dest.name)
    }

    @Test
    fun `无表达式的语句原实例返回`() {
        val s = FlowStmt(FunctionCode.BREAK, VoidArgument(), 1, "BREAK")
        assertSame(s, c.canonicalizeStatement(s), "没有可折叠内容就不该重建")
    }

    @Test
    fun `release 清规范表 已共享实例仍活着`() {
        val a = c.canonicalizeStatement(mk(IntLiteral(7, 1, 1))) as AssignStmt
        val termA = (a.args as SpSetArgument).term!!
        c.release()
        val b = c.canonicalizeStatement(mk(IntLiteral(7, 3, 3))) as AssignStmt
        val termB = (b.args as SpSetArgument).term!!
        assertEquals(termA, termB, "release 后重新折叠仍产出等价实例")
        assertNotSame(termA, termB, "表清了，新折叠不再共享旧实例（释放语义）")
    }

    @Test
    fun `STRDATA 头语句恒原实例 数据节身份链不折断`() {
        // 数据节以 Statement 实例为键（ExecContext.kt:96-100）：宿主在 AST 上注册
        // （GoldenPipeline/RealCorpusCompile/GameBootSmoke 的 IdentityHashMap），
        // VM 从池内取指回查——折叠若重建此语句（DEST VarRef 归一换新 → args 变），
        // groupsOf 查空 → SDV 不写（E2E 首差 `SDV=` 实证）。
        val dest = VarRef("SDV", emptyList(), operandType = OperandType.STR, line = 3, col = 9)
        val s = com.eratw.port.core.stmt.PrintDataStmt(
            FunctionCode.STRDATA,
            com.eratw.port.core.stmt.StrDataArgument(dest),
            3,
            "STRDATA SDV",
        )
        assertSame(s, c.canonicalizeStatement(s), "PRINTDATA/STRDATA 头语句必须原实例入池")
    }

    @Test
    fun `DATA 数据行恒原实例 单行STRDATA同`() {
        val s = com.eratw.port.core.stmt.DataStmt(FunctionCode.DATA, "SDA", VoidArgument(), 4, "DATA SDA")
        assertSame(s, c.canonicalizeStatement(s), "数据行跳过折叠")
        val single = com.eratw.port.core.stmt.DataStmt(
            FunctionCode.STRDATA,
            "",
            com.eratw.port.core.stmt.StrDataArgument(
                VarRef("SDV", emptyList(), operandType = OperandType.STR),
            ),
            5,
            "STRDATA SDV",
        )
        assertSame(single, c.canonicalizeStatement(single), "单行 STRDATA 本身是数据节键")
    }
}
