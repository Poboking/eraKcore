package com.eratw.port.core.stmt

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarRef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * CMP-14 必红夹具 + 防回归：**数组字段必须按原子（内容）参与结构比较**。
 *
 * ## 缺陷机理
 *
 * `ArgInterner.isAtomFieldType` 原先只列了 `IntArray`/`LongArray`/`DoubleArray`/`BooleanArray`/
 * `Array<String>`。其余数组类型（`CharArray`/`ByteArray`/`ShortArray`/`FloatArray`/`Array<Expr>`…）
 * 被当成**子结点**，而数组类没有任何可反射字段 ⇒ `shapeOf` 给出空 Shape ⇒ `hashOf` 只剩类名 ⇒
 * 该数组的持有者（`SpSetArrayArgument`、`IntAsignArgument.lengths` 一类）所有实例互相判「同构」。
 *
 * 目前唯一活着的对象数组字段是 `VarRef.indexArray`（派生视图，按字段名跳过），所以这本是一条
 * **潜伏**缺陷：真正会踩的是「新增/改名一个数组字段」，那正是 2026-10-22 真机死循环的同型病。
 * 必红因此钉在分类判据本身（[ArgInterner.isAtomFieldType]）与内容判等行为上：
 *
 * - 红：`CharArray`/`ByteArray`/`ShortArray`/`FloatArray`/`Array<Expr>`/`Array<IntArray>` 被判成子结点
 *   （`assertTrue(isAtomFieldType)` 失败）；
 * - 绿：全部数组类型是原子；`probeShapes()` 零违规；`constStrList` 内容不同绝不合并、内容相同合并。
 */
class StmtArgInternArrayShapeTest {

    // ==================================================================
    // 1. 分类判据（红形落点）
    // ==================================================================

    @Test
    fun `全部数组类型都按原子分类`() {
        val arrays = listOf(
            IntArray::class.java,
            LongArray::class.java,
            DoubleArray::class.java,
            BooleanArray::class.java,
            CharArray::class.java,
            ByteArray::class.java,
            ShortArray::class.java,
            FloatArray::class.java,
            Array<String>::class.java,
            Array<Expr>::class.java,
            Array<Any>::class.java,
            Array<IntArray>::class.java,
        )
        for (t in arrays) {
            assertTrue(
                ArgInterner.isAtomFieldType(t),
                "${t.name} 不是原子类型：它会被当成子结点 ⇒ 空 Shape ⇒ 同类结点互相塌陷（CMP-14）",
            )
        }
        // 反向对照：真正的子结点类型不许被误判成原子
        assertTrue(!ArgInterner.isAtomFieldType(VarRef::class.java), "VarRef 是子结点")
        assertTrue(!ArgInterner.isAtomFieldType(Argument::class.java), "Argument 是子结点")
    }

    // ==================================================================
    // 2. 建表自检（probeShapes）
    // ==================================================================

    @Test
    fun `probeShapes 对全部带数组字段的模型类零违规`() {
        val violations = ArgInterner.probeShapes()
        assertTrue(
            violations.isEmpty(),
            "形状表自检有 ${violations.size} 条违规：\n" + violations.joinToString("\n"),
        )
    }

    // ==================================================================
    // 3. 行为：Array<String> 字段按内容判等
    // ==================================================================

    private fun programOf(vararg stmts: Statement): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        for (s in stmts) b.emit(Opcode.PRINT, b.statement(s), 0, SlotAddr.NONE, 0)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        return b.build(0)
    }

    private fun setArray(constStrList: Array<String>): SpSetArrayArgument = SpSetArrayArgument(
        dest = VarRef("A$", emptyList(), isNoArg = false, operandType = OperandType.STR),
        terms = listOf(IntLiteral(1L)),
        constStrList = constStrList,
    )

    private fun stmt(args: Argument) = InstructionStmt(FunctionCode.SET, args, 1, "SET A$")

    @Test
    fun `constStrList 内容不同不合并 内容相同才合并`() {
        val xy = setArray(arrayOf("x", "y"))
        val xz = setArray(arrayOf("x", "z"))
        val xyAgain = setArray(arrayOf("x", "y"))
        val program = programOf(stmt(xy), stmt(xz), stmt(xyAgain))

        program.internStatementArgs()

        val read = (0 until 3).map { program.statement(it).args as SpSetArrayArgument }
        assertSame(read[0], read[2], "constStrList 内容相同的两个 SpSetArrayArgument 应合并")
        assertNotSame(read[0], read[1], "constStrList 内容不同（y vs z）绝不许合并 —— 数组按身份判等的塌陷")
        assertContentEquals(arrayOf("x", "y"), read[0].constStrList, "合并后 constStrList 内容必须原样")
        assertContentEquals(arrayOf("x", "z"), read[1].constStrList, "未合并的一方 constStrList 内容必须原样")
        assertEquals(0, ArgInterner.probeShapes().size, "去重跑完后形状表自检仍须零违规")
    }
}
