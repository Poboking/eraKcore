package com.eratw.port.core.code

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.SpSetArgument
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.VoidArgument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * `.erbc` v1 编码确定性（契约 CONTRACT-M4-A §2.4 (b)）。
 *
 * 锁死两件事：
 * 1. **同一 Program 两次 `encode` 逐字节相同** —— 编码路径里任何 `HashMap`
    （字符串驻留表、节点表记忆化、函数名查重表）都只允许**查表**，
    字节顺序一律来自「追加序」。一旦有人把 `map.entries` 拿去迭代，本条立刻红。
 * 2. **池的追加交错顺序不影响产物字节** —— 各池内部的相对次序相同、指令里引用的
    下标相同 ⇒ 字节必须相同。这条是上一条的强化版：它专门针对
    「JVM 的 HashMap 在不同插入历史下迭代序不同」这一类隐藏不确定性。
 *
 * 另加：装载→再编码的**幂等性**（布局规范化的证明，分片/mmap 侧要按偏移复用字节）。
 */
class ErbcDeterminismTest {

    @Test
    fun `同一 Program 两次编码逐字节相同`() {
        val program = largeProgram(500, 2000)
        val first = ErbcWriter.encode(program)
        val second = ErbcWriter.encode(program)

        assertEquals(first.size, second.size, "两次编码长度都不同，说明有非确定序的容器被迭代了")
        assertContentEquals(first, second)
        assertTrue(first.size > 8000, "样例应足够大（当前 ${first.size} 字节），否则这条断言没力量")
    }

    @Test
    fun `编码之间夹入别的编码与别的 Program 也不串状态`() {
        val a = largeProgram(80, 200)
        val b = largeProgram(120, 400)
        val a1 = ErbcWriter.encode(a)
        val ignored = ErbcWriter.encode(b)
        val a2 = ErbcWriter.encode(a)
        val b2 = ErbcWriter.encode(b)

        assertContentEquals(a1, a2)
        assertContentEquals(ignored, b2)
        assertTrue(!a1.contentEquals(b2), "不同 Program 必须不同字节（否则上面两条是空转）")
    }

    @Test
    fun `各池追加的交错顺序不同 产物逐字节相同`() {
        val grouped = ErbcWriter.encode(poolProgram(interleave = false))
        val interleaved = ErbcWriter.encode(poolProgram(interleave = true))

        assertContentEquals(grouped, interleaved)
    }

    @Test
    fun `装载后再编码仍然逐字节相同（布局规范化）`() {
        val program = poolProgram(interleave = true)
        val bytes = ErbcWriter.encode(program)
        val once = ErbcWriter.encode(ErbcReader.decode(bytes))
        val twice = ErbcWriter.encode(ErbcReader.decode(once))

        assertContentEquals(bytes, once)
        assertContentEquals(once, twice)
    }

    @Test
    fun `结构相等的表达式在节点表里共享 但语句池条目各自保持`() {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        // 表达式池按结构相等去重（Program.kt:70-77）⇒ 同一个池下标
        val shared = IntLiteral(7L, 1, 2)
        val i0 = b.expr(shared)
        val i1 = b.expr(IntLiteral(7L, 1, 2))
        assertEquals(i0, i1)
        // 语句池不去重（Program.kt:84-90）⇒ 结构相等的两条语句是两个下标
        val s0 = b.statement(assign("LV = 3"))
        val s1 = b.statement(assign("LV = 3"))
        assertTrue(s0 != s1)
        b.emit(Opcode._EVAL_INT, i0, SlotAddr.local(0), 0, 1)
        b.emit(Opcode.SET, s0, 0, SlotAddr.NONE, 3)
        b.emit(Opcode.SET, s1, 0, SlotAddr.NONE, 3)
        b.emit(Opcode._HALT, 0, 0, 0, 4)
        val program = b.build(0)

        val bytes = ErbcWriter.encode(program)
        assertContentEquals(bytes, ErbcWriter.encode(ErbcReader.decode(bytes)))

        val back = ErbcReader.decode(bytes)
        assertEquals(1, back.exprCount, "表达式池结构相等去重 ⇒ 只有一个条目")
        assertEquals(2, back.statementCount, "语句池不去重 ⇒ 两个条目")
        assertNotSame(back.statement(0), back.statement(1))
        // 两条记录共用同一份子树节点：VarRef 的下标节点 + 它的 IntLiteral 下标 + 右值 IntLiteral
        assertEquals(3, back.nodeCountOfStatements(), "语句段的表达式子树必须被共享，不随记录重复发射")
    }

    private fun assign(raw: String): Statement {
        val dest = VarRef("LV", listOf(IntLiteral(1L, 9, 9)), operandType = OperandType.INT)
        return AssignStmt(
            dest = dest,
            op = OperatorCode.ASSIGNMENT,
            args = SpSetArgument(dest, IntLiteral(3L, 9, 1)),
            line = 3,
            raw = raw,
        )
    }

    /** 语句池段头的第一个 u32 = 该段节点表条数（规约 §5.4）；直接读字节，不走解析路径。 */
    private fun Program.nodeCountOfStatements(): Int {
        val bytes = ErbcWriter.encode(this)
        val off = ErbcReader.probe(bytes).offsets[ErbcFormat.SECTION_STATEMENTS]
        assertTrue(off != 0, "样例必须带语句池")
        return readLe32(bytes, off)
    }

    // ==================================================================
    // 样例
    // ==================================================================

    /** 大量互不相同的字符串 / 表达式 / 语句 + 一条长指令流。 */
    private fun largeProgram(
        poolSize: Int,
        instructionCount: Int,
    ): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 1, 4)
        val constants = IntArray(poolSize) { i -> b.constant(i.toLong() * 31L - 17L) }
        val strings = IntArray(poolSize) { i -> b.string("文字列 $i \\\\ \"quoted\"") }
        val exprs = IntArray(poolSize) { i -> b.expr(richExpr(i)) }
        val stmts = IntArray(poolSize) { i -> b.statement(richStmt(i)) }
        for (pc in 0 until instructionCount) {
            val k = pc % poolSize
            when (pc % 5) {
                0 -> b.emit(Opcode._SET_INT, SlotAddr.local(0), constants[k], 0, pc)
                1 -> b.emit(Opcode._SET_STR, SlotAddr.local(1), strings[k], 0, pc)
                2 -> b.emit(Opcode._EVAL_INT, exprs[k], SlotAddr.local(0), 0, pc)
                3 -> b.emit(Opcode._ADD, SlotAddr.local(0), SlotAddr.global(1), SlotAddr.local(2), pc)
                // marker（契约 C3）：a=语句池下标、b=0、c=NONE
                else -> b.emit(Opcode.PRINT, stmts[k], 0, SlotAddr.NONE, pc)
            }
        }
        b.emit(Opcode._HALT, 0, 0, 0, instructionCount)
        b.declareFunction("tail", b.here() - 1, 0, 1)
        for (i in 0 until minOf(poolSize, 32)) {
            b.declareLabel(0, "L$i", i)
        }
        b.noteGlobalSlot(1)
        return b.build(0)
    }

    /**
     * 同样的池内容与同样的指令流，只是**取池下标的时机交错**不同：
     * `interleave=true` 时逐轮 `constant/string/expr/statement` 交替追加，
     * `false` 时按池分批追加。两个池各自的相对次序完全一致 ⇒ 产物必须逐字节相同。
     */
    private fun poolProgram(interleave: Boolean): Program {
        val n = 60
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val constants = IntArray(n)
        val strings = IntArray(n)
        val exprs = IntArray(n)
        val stmts = IntArray(n)
        if (interleave) {
            for (i in 0 until n) {
                constants[i] = b.constant(i * 97L)
                strings[i] = b.string("s$i")
                exprs[i] = b.expr(richExpr(i))
                stmts[i] = b.statement(richStmt(i))
            }
        } else {
            for (i in 0 until n) constants[i] = b.constant(i * 97L)
            for (i in 0 until n) strings[i] = b.string("s$i")
            for (i in 0 until n) exprs[i] = b.expr(richExpr(i))
            for (i in 0 until n) stmts[i] = b.statement(richStmt(i))
        }
        for (i in 0 until n) {
            b.emit(Opcode._SET_INT, SlotAddr.local(0), constants[i], 0, i + 1)
            b.emit(Opcode._SET_STR, SlotAddr.local(1), strings[i], 0, i + 1)
            b.emit(Opcode._EVAL_INT, exprs[i], SlotAddr.local(0), 0, i + 1)
            b.emit(Opcode.WAIT, stmts[i], 0, SlotAddr.NONE, i + 1)
        }
        b.emit(Opcode._HALT, 0, 0, 0, n + 1)
        return b.build(0)
    }

    private fun richExpr(i: Int): Expr = Binary(
        OperatorCode.PLUS,
        IntLiteral(i * 3L, i, i + 1),
        VarRef(
            name = "VARIABLE_$i",
            indices = listOf(IntLiteral(1L, i, 0), VarRef("IDX", emptyList(), operandType = OperandType.INT)),
            operandType = OperandType.INT,
            isConst = i % 3 == 0,
            subKey = if (i % 4 == 0) "SUB" else null,
            line = i,
            col = i + 2,
        ),
        OperandType.INT,
        line = i + 3,
        col = i + 4,
    )

    private fun richStmt(i: Int): Statement = InstructionStmt(
        code = if (i % 2 == 0) FunctionCode.WAIT else FunctionCode.PRINT,
        args = VoidArgument(),
        line = i,
        raw = if (i % 2 == 0) "WAIT" else "PRINT 変数 $i",
    )

    // ==================================================================
    // 断言工具
    // ==================================================================

    private fun assertContentEquals(
        expected: ByteArray,
        actual: ByteArray,
    ) {
        if (expected.size != actual.size) {
            val limit = minOf(expected.size, actual.size)
            val firstDiff = (0 until limit).firstOrNull { expected[it] != actual[it] } ?: limit
            throw AssertionError("长度不同：${expected.size} vs ${actual.size}（首个差异字节 $firstDiff）")
        }
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                throw AssertionError("第 $i 字节不同：期望 ${expected[i]} 实为 ${actual[i]}")
            }
        }
    }

    private fun readLe32(
        bytes: ByteArray,
        at: Int,
    ): Int = (bytes[at].toInt() and 0xFF) or
        ((bytes[at + 1].toInt() and 0xFF) shl 8) or
        ((bytes[at + 2].toInt() and 0xFF) shl 16) or
        ((bytes[at + 3].toInt() and 0xFF) shl 24)
}
