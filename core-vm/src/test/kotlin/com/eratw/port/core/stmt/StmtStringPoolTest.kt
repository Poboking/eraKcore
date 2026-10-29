package com.eratw.port.core.stmt

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.loader.StringPool
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 字符串归一（[StringPool] + [ArgInterner] 接缝）。
 *
 * 钉住四件事：同内容不同实例归一到同一实例、内容一字不改、统计快照读数正确、
 * 归一前后 `ErbcWriter` 编码逐字节相同。
 */
class StmtStringPoolTest {

    /** 造一个「内容相等但实例不同」的字符串（模拟解析器逐行 new 出来的重复串）。 */
    private fun dup(s: String): String = StringBuilder(s).toString()

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

    @Test
    fun `同内容不同实例归一为同一实例且内容不变`() {
        val pool = StringPool()
        val a = dup("セリフ")
        val b = dup("セリフ")
        val c = dup("別の台詞")
        assertNotSame(a, b, "前提：造出来的两个实例必须不同")

        val ra = pool.intern(a)
        val rb = pool.intern(b)
        val rc = pool.intern(c)

        assertSame(a, ra, "首个实例自己当代表")
        assertSame(ra, rb, "内容相等必须归一到同一实例")
        assertNotSame(ra, rc, "内容不同不得归一")
        assertEquals("セリフ", rb, "内容一字不改")
        assertEquals("別の台詞", rc, "内容一字不改")

        val snap = pool.snapshot()
        assertEquals(3L, snap.requests)
        assertEquals(1L, snap.hits)
        assertEquals(2, snap.distinct)
        assertEquals(2L, snap.misses)
        assertEquals(1.0 / 3.0, snap.hitRate, 1e-12)
        // 只有第二次 "セリフ" 是重复实例：40 B 头 + 2 B/字符 × 3 字符
        assertEquals(46L, snap.savedBytes, "节省字节口径 = 头 40 B + 2 B/字符")
    }

    @Test
    fun `扩容后仍能命中且不重排代表实例`() {
        val pool = StringPool(initialCapacity = 16)
        val keys = ArrayList<String>()
        val reps = ArrayList<String>()
        for (i in 0 until 5_000) {
            val s = dup("V$i")
            keys += s
            reps += pool.intern(s)
        }
        assertEquals(5_000, pool.distinct)
        for (i in keys.indices) {
            assertSame(reps[i], pool.intern(keys[i]), "扩容后第 $i 项必须仍命中同一代表")
        }
        assertEquals(5_000L, pool.snapshot().hits)
    }

    @Test
    fun `语句实参的常量文本归一到同一实例`() {
        // 两条语句的 args 结构不同（constInt 不同）⇒ 参数实例不合并，
        // 但它们的 constStr 内容相同 ⇒ 必须指向同一 String 实例。
        val a = InstructionStmt(
            FunctionCode.PRINTL,
            ExpressionArgument(IntLiteral(1)).apply {
                isConst = true
                constStr = dup("セリフ")
                constInt = 1
            },
            1, "",
        )
        val b = InstructionStmt(
            FunctionCode.PRINTL,
            ExpressionArgument(IntLiteral(1)).apply {
                isConst = true
                constStr = dup("セリフ")
                constInt = 2
            },
            2, "",
        )
        val stats = ArgInterner().run(listOf(a, b))

        val aa = a.args as ExpressionArgument
        val bb = b.args as ExpressionArgument
        assertNotSame(aa, bb, "constInt 不同 ⇒ 参数实例不得合并")
        assertSame(aa.constStr, bb.constStr, "同内容 constStr 必须归一")
        assertEquals("セリフ", aa.constStr, "内容一字不改")
        assertTrue(stats.strings.hits >= 1, "读数应记到至少一次字符串命中：${stats.strings}")
        assertEquals(1, stats.strings.distinct, "只有一条不同内容：${stats.strings}")
        assertTrue(stats.strings.savedBytes > 0, "重复实例应计入节省字节：${stats.strings}")
    }

    @Test
    fun `数组实参里的常量文本归一到同一实例`() {
        val a = InstructionStmt(
            FunctionCode.SET,
            SpSetArrayArgument(
                VarRef("A", emptyList(), false, OperandType.INT),
                listOf(StrLiteral(dup("x"))),
                null,
                arrayOf(dup("セリフ"), dup("台詞")),
            ),
            1, "",
        )
        val b = InstructionStmt(
            FunctionCode.SET,
            SpSetArrayArgument(
                VarRef("B", emptyList(), false, OperandType.INT),
                listOf(StrLiteral(dup("x"))),
                null,
                arrayOf(dup("セリフ"), dup("台詞")),
            ),
            2, "",
        )
        ArgInterner().run(listOf(a, b))

        val la = (a.args as SpSetArrayArgument).constStrList!!
        val lb = (b.args as SpSetArrayArgument).constStrList!!
        assertNotSame(a.args, b.args, "dest 不同 ⇒ 参数实例不得合并")
        assertSame(la[0], lb[0], "同内容数组元素必须归一")
        assertSame(la[1], lb[1], "同内容数组元素必须归一")
        assertEquals("セリフ", la[0])
        assertEquals("台詞", la[1])
    }

    @Test
    fun `归一前后 ErbcWriter 编码字节相等`() {
        val s0 = InstructionStmt(
            FunctionCode.PRINTL, ExpressionArgument(StrLiteral(dup("セリフ"))), 1, "PRINTL セリフ",
        )
        val s1 = InstructionStmt(
            FunctionCode.PRINTL, ExpressionArgument(StrLiteral(dup("セリフ"))), 2, "PRINTL セリフ",
        )
        val s2 = InstructionStmt(
            FunctionCode.SET,
            SpSetArrayArgument(
                VarRef("A", emptyList(), false, OperandType.INT),
                listOf(StrLiteral(dup("x"))),
                null,
                arrayOf(dup("セリフ")),
            ),
            3, "A 'x' = セリフ",
        )
        val v0 = ((s0.args as ExpressionArgument).term as StrLiteral).value
        val v1 = ((s1.args as ExpressionArgument).term as StrLiteral).value
        assertNotSame(v0, v1, "前提：两个同内容字面量必须是不同实例")

        val program = programOf(s0, s1, s2)
        val before = ErbcWriter.encode(program)
        program.internStatementArgs()
        val after = ErbcWriter.encode(program)

        assertContentEquals(before, after, "归一不得改变 .erbc 落盘字节（内容不变 ⇒ 串池下标不变）")
        val w0 = ((program.statement(0).args as ExpressionArgument).term as StrLiteral).value
        val w1 = ((program.statement(1).args as ExpressionArgument).term as StrLiteral).value
        assertSame(w0, w1, "归后同内容字面量共享同一 String 实例")
        assertEquals("セリフ", w0)
    }

    @Test
    fun `调用方注入的池跨段共享且读数累计`() {
        // 模拟两段装载（种子段 + 语句段）各持一个 ArgInterner，但注入**同一张调用方池**：
        // 第二段的重复串必须命中第一段登记的代表实例，且第二段读数是累计值。
        val pool = StringPool()
        val seg1 = listOf(IfStmt(StrLiteral(dup("セリフ")), VoidArgument(), 1, "IF セリフ"))
        val seg2 = listOf(IfStmt(StrLiteral(dup("セリフ")), VoidArgument(), 2, "IF セリフ"))

        val st1 = ArgInterner(pool).run(seg1)
        val st2 = ArgInterner(pool).run(seg2)

        // IfStmt.condition 是根级可写槽：第二段的条件被写回成持有第一段代表串的新实例
        val v1 = (seg1[0].condition as StrLiteral).value
        val v2 = (seg2[0].condition as StrLiteral).value
        assertSame(v1, v2, "跨段装载必须归一到同一代表实例")
        assertEquals("セリフ", v2, "内容一字不改")

        assertEquals(1L, st1.strings.requests, "第一段只见过一次")
        assertEquals(0L, st1.strings.hits)
        assertEquals(2L, st2.strings.requests, "同一张池 ⇒ 第二段读数是累计值")
        assertEquals(1L, st2.strings.hits)
        assertEquals(1, st2.strings.distinct)
        assertEquals(46L, st2.strings.savedBytes, "口径 = 头 40 B + 2 B/字符 × 3 字符")
    }

    @Test
    fun `语句自带文本字段归一到同一实例`() {
        // 语句层可写文本槽全覆盖：方法名 / 数据行 / 函数名 / 形参名表 / 跳转标签名 /
        // 预处理键与声明名 / 跳转目标标签。
        val m0 = MethodStmt(dup("MYFUNC"), VoidArgument(), 1, "MYFUNC")
        val m1 = MethodStmt(dup("MYFUNC"), VoidArgument(), 2, "MYFUNC")
        val d0 = DataStmt(FunctionCode.DATA, dup("データ行"), VoidArgument(), 1, "DATA データ行")
        val d1 = DataStmt(FunctionCode.DATA, dup("データ行"), VoidArgument(), 2, "DATA データ行")
        val f0 = FuncLabelStmt(dup("FUNCLABEL"), arrayListOf(dup("ARG"), dup("BRG")), 1, "@FUNCLABEL(ARG, BRG)")
        val f1 = FuncLabelStmt(dup("FUNCLABEL"), arrayListOf(dup("ARG"), dup("BRG")), 2, "@FUNCLABEL(ARG, BRG)")
        val g0 = GotoLabelStmt(dup("GOTO_LABEL"), 1, "\$GOTO_LABEL")
        val g1 = GotoLabelStmt(dup("GOTO_LABEL"), 2, "\$GOTO_LABEL")
        val h0 = SharpStmt(dup("#DIM"), emptyList(), dup("DIMNAME"), false, emptyList(), 1, "#DIM DIMNAME")
        val h1 = SharpStmt(dup("#DIM"), emptyList(), dup("DIMNAME"), false, emptyList(), 2, "#DIM DIMNAME")
        val j0 = JumpStmt(FunctionCode.JUMP, dup("JUMP_TARGET"), VoidArgument(), 1, "JUMP JUMP_TARGET")
        val j1 = JumpStmt(FunctionCode.JUMP, dup("JUMP_TARGET"), VoidArgument(), 2, "JUMP JUMP_TARGET")

        val stats = ArgInterner().run(listOf(m0, m1, d0, d1, f0, f1, g0, g1, h0, h1, j0, j1))

        assertSame(m0.methodName, m1.methodName, "同方法名必须归一")
        assertSame(d0.text, d1.text, "同数据行必须归一")
        assertSame(f0.name, f1.name, "同函数名必须归一")
        assertSame(f0.paramNames[0], f1.paramNames[0], "形参名表必须就地写回规范实例")
        assertSame(f0.paramNames[1], f1.paramNames[1])
        assertSame(g0.name, g1.name, "同跳转标签名必须归一")
        assertSame(h0.keyword, h1.keyword, "同预处理键必须归一")
        assertSame(h0.name, h1.name, "同声明名必须归一")
        assertSame(j0.label, j1.label, "同跳转目标必须归一")

        // 内容一字不改
        assertEquals("MYFUNC", m1.methodName)
        assertEquals("データ行", d1.text)
        assertEquals("FUNCLABEL", f1.name)
        assertEquals("ARG", f1.paramNames[0])
        assertEquals("GOTO_LABEL", g1.name)
        assertEquals("#DIM", h1.keyword)
        assertEquals("DIMNAME", h1.name)
        assertEquals("JUMP_TARGET", j1.label)

        // 快照：9 种不同内容。paramRefs 默认与 paramNames 同一张表 ⇒ 每条形参串被走两遍：
        // 请求 = 2+2+5×2+2+2×2+2 = 22，命中 = 13（ARG/BRG 各 3 次，其余各 1 次）。
        assertEquals(9, stats.strings.distinct)
        assertEquals(22L, stats.strings.requests)
        assertEquals(13L, stats.strings.hits)
        assertEquals(9L, stats.strings.misses)
        // savedBytes 只算**真被丢弃的重复实例**：第二遍走同一张表时传进去的就是代表实例本身
        // （`cur === s`），没有对象被丢弃 ⇒ 不计。所以 13 次命中里只有 9 次计入，
        // 其中 ARG/BRG 各只计一遍（另一遍是同一实例的第二遍）：
        // 52+48+58+60+48+54+62+46+46 = 474。
        assertEquals(474L, stats.strings.savedBytes, "只计被丢弃的实例；同一实例的第二遍不计")
    }
}
