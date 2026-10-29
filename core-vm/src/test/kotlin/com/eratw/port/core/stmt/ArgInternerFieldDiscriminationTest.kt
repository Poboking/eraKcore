package com.eratw.port.core.stmt

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.FormConversion
import com.eratw.port.core.expr.FormPart
import com.eratw.port.core.expr.FormStringTerm
import com.eratw.port.core.expr.FuncCall
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.loader.ProgramLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * 形状表**判别力**矩阵：同类结点只差一个字段时，规范化去重以后必须仍然读得出那个字段。
 *
 * ## 为什么单独立一个文件（不塞进 `StmtArgInterningTest`）
 *
 * 2026-10-21 真机（ART）P0：`ArgInterner.shapeOf` 用 JDK 9 的 `trySetAccessible()` 判定字段
 * 可访问性，`runCatching` 把「运行时里没有这个方法」连同别的失败一起咽掉，然后 `continue`
 * —— 于是**一个字段都没进形状表**，同类不同形的结点全部塌成「第一个见到的代表」。现场是
 * `TITLE.ERB` 里 `FOR I, 0, 35` 的 INIT（`I = 0`）与 STEP（`I += 1`）两条语句拿到了同一份
 * `dest`/`args`（都指向此前某条 `RESULT = GLOBAL` 的实例），计数器恒 0，终值判定永真，
 * 每轮执行的都是别人的赋值，最后撞死循环闸。
 *
 * JVM 侧急切档/惰性档/`.erbc` 往返/真语料体/被 CALL 帧五种形态全绿：反射在 HotSpot 上是好的，
 * 而**内部自检用的就是那张坏掉的表**（`hashOf`/`sameStructure`/`canonicalize` 都走 `shapeOf`），
 * 从里面看不出任何异常。所以这里断言的全是「从外面读回来」的值：公共 getter 与 data class 的
 * `equals`，一条都不依赖反射。
 *
 * 与生产自带的形状自检（`ArgInterner.probeShapes`，建表即自证）互为旁证：那一趟让塌陷在装载期
 * 就抛错，本文件把同一批判据钉成闸上的用例。
 */
class ArgInternerFieldDiscriminationTest {

    private fun varI() = VarRef("I", listOf(IntLiteral(0L)), operandType = OperandType.INT)

    private fun varOf(name: String, indices: List<Expr> = emptyList()) =
        VarRef(name, indices, operandType = OperandType.INT)

    private fun programOf(vararg stmts: Statement): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 1)
        for (s in stmts) b.emit(Opcode.PRINT, b.statement(s), 0, SlotAddr.NONE, 0)
        b.emit(Opcode._HALT, 0, 0, 0, 1)
        return b.build(0)
    }

    private fun printl(args: Argument) = InstructionStmt(FunctionCode.PRINTL, args, 1, "PRINTL")

    private fun term(stmt: Statement): Expr? = (stmt.args as ExpressionArgument).term

    private fun assign(dest: VarRef, op: OperatorCode, term: Expr?) = AssignStmt(
        dest,
        op,
        SpSetArgument(dest, term),
        1,
        "SET",
    )

    /** 塌成第一个代表时读回来的就是**它**的字段 —— 每条用例都以它作对照锚点（照 TITLE.ERB 的顺序摆在最前）。 */
    private fun anchorResultEqualsGlobal() = assign(
        varOf("RESULT", listOf(IntLiteral(0L))),
        OperatorCode.ASSIGNMENT,
        varOf("GLOBAL", listOf(IntLiteral(0L))),
    )

    private fun sp(stmt: Statement): SpSetArgument = assertIs<SpSetArgument>((stmt as AssignStmt).args)

    // ==================================================================
    // 1. 真机现场：FOR 的 INIT 与 STEP
    // ==================================================================

    @Test
    fun `真机现场 SET 计数器的 INIT 与 STEP 各自保留左值与增量`() {
        val init = assign(varI(), OperatorCode.ASSIGNMENT, IntLiteral(0L))
        val step = assign(varI(), OperatorCode.PLUS, IntLiteral(1L)).apply { sp(this).addConst = true }
        val program = programOf(anchorResultEqualsGlobal(), init, step)

        program.internStatementArgs()

        val a = program.statement(1) as AssignStmt
        val b = program.statement(2) as AssignStmt
        assertEquals("I", a.dest.name, "FOR INIT 的左值被换掉了（形状表少认 VarRef.name）")
        assertEquals("I", b.dest.name, "FOR STEP 的左值被换掉了（形状表少认 VarRef.name）")
        assertEquals(varI(), a.dest, "FOR INIT 的左值整体被换掉了")
        assertEquals(varI(), b.dest, "FOR STEP 的左值整体被换掉了")
        assertEquals(OperatorCode.ASSIGNMENT, a.op)
        assertEquals(OperatorCode.PLUS, b.op)
        assertEquals(0L, assertIs<IntLiteral>(sp(a).term).value, "I = 0 的初值被换掉了")
        assertEquals(1L, assertIs<IntLiteral>(sp(b).term).value, "I += 1 的增量被换掉了（少认 SpSetArgument.term）")
        assertEquals(varI(), sp(a).dest, "FOR INIT 的参数左值")
        assertEquals(varI(), sp(b).dest, "FOR STEP 的参数左值")
        assertTrue(!sp(a).addConst, "FOR INIT 不该带 addConst")
        assertTrue(sp(b).addConst, "FOR STEP 的 addConst 必须保留")
        assertNotSame(sp(a), sp(b), "只差 term/addConst 的两个 SpSetArgument 被并成了同一实例")
    }

    // ==================================================================
    // 2. 逐字段判别力
    // ==================================================================

    @Test
    fun `VarRef 六个字段各自都能把同类结点区分开`() {
        val groups = listOf(
            "name" to listOf(
                VarRef("A", listOf(IntLiteral(0L)), operandType = OperandType.INT),
                VarRef("B", listOf(IntLiteral(0L)), operandType = OperandType.INT),
            ),
            "indices" to listOf(
                VarRef("A", listOf(IntLiteral(0L)), operandType = OperandType.INT),
                VarRef("A", listOf(IntLiteral(1L)), operandType = OperandType.INT),
            ),
            "isNoArg" to listOf(
                VarRef("A", emptyList(), isNoArg = false, operandType = OperandType.INT),
                VarRef("A", emptyList(), isNoArg = true, operandType = OperandType.INT),
            ),
            "operandType" to listOf(
                VarRef("A", emptyList(), operandType = OperandType.INT),
                VarRef("A", emptyList(), operandType = OperandType.STR),
            ),
            "isConst" to listOf(
                VarRef("A", emptyList(), operandType = OperandType.INT, isConst = false),
                VarRef("A", emptyList(), operandType = OperandType.INT, isConst = true),
            ),
            "subKey" to listOf(
                VarRef("A", emptyList(), operandType = OperandType.INT, subKey = null),
                VarRef("A", emptyList(), operandType = OperandType.INT, subKey = "k"),
            ),
        )
        for ((field, group) in groups) {
            val program = programOf(
                printl(ExpressionArgument(group[0])),
                printl(ExpressionArgument(group[1])),
            )
            program.internStatementArgs()
            val read0 = term(program.statement(0))
            val read1 = term(program.statement(1))
            assertEquals(group[0], read0, "去重后 VarRef.$field 组的第 0 个结点读回了别的内容")
            assertEquals(group[1], read1, "去重后 VarRef.$field 组的第 1 个结点读回了别的内容")
            assertNotSame(read0, read1, "只差 VarRef.$field 的两个结点被并成了同一实例")
        }
    }

    @Test
    fun `字面量与运算符字段都能区分同类结点`() {
        val nodes: List<Expr> = listOf(
            StrLiteral("a"),
            StrLiteral("b"),
            IntLiteral(0L),
            IntLiteral(1L),
            Binary(OperatorCode.PLUS, IntLiteral(1L), IntLiteral(2L), OperandType.INT),
            Binary(OperatorCode.MULT, IntLiteral(1L), IntLiteral(2L), OperandType.INT),
            Binary(OperatorCode.PLUS, IntLiteral(2L), IntLiteral(1L), OperandType.INT),
            Binary(OperatorCode.PLUS, IntLiteral(1L), IntLiteral(2L), OperandType.STR),
            Unary(OperatorCode.MINUS, IntLiteral(1L)),
            Unary(OperatorCode.NOT, IntLiteral(1L)),
            Ternary(IntLiteral(0L), IntLiteral(1L), IntLiteral(2L), OperandType.INT),
            Ternary(IntLiteral(0L), IntLiteral(1L), IntLiteral(3L), OperandType.INT),
            FuncCall("A", listOf(IntLiteral(1L)), OperandType.INT),
            FuncCall("B", listOf(IntLiteral(1L)), OperandType.INT),
            FuncCall("A", listOf(IntLiteral(1L), IntLiteral(2L)), OperandType.INT),
        )
        val program = programOf(*nodes.map { printl(ExpressionArgument(it)) }.toTypedArray())
        program.internStatementArgs()

        val read = (0 until program.statementCount).map { term(program.statement(it)) }
        for (i in read.indices) {
            assertEquals(nodes[i], read[i], "第 $i 个结点去重后读回了别的内容")
            for (j in i + 1 until read.size) {
                assertNotSame(read[i], read[j], "第 $i/$j 个结点只差一个字段仍被并成同一实例")
            }
        }
    }

    @Test
    fun `参数基类的三个常量缓存位各自参与比较`() {
        fun arg(isConst: Boolean, constStr: String, constInt: Long) =
            ExpressionArgument(IntLiteral(7L)).apply {
                this.isConst = isConst
                this.constStr = constStr
                this.constInt = constInt
            }

        val program = programOf(
            printl(arg(false, "", 0L)),
            printl(arg(true, "", 0L)),
            printl(arg(false, "x", 0L)),
            printl(arg(false, "", 5L)),
        )
        program.internStatementArgs()

        val read = (0 until 4).map { program.statement(it).args as ExpressionArgument }
        assertEquals(listOf(false, true, false, false), read.map { it.isConst }, "Argument.isConst 没参与比较")
        assertEquals(listOf("", "", "x", ""), read.map { it.constStr }, "Argument.constStr 没参与比较")
        assertEquals(listOf(0L, 0L, 0L, 5L), read.map { it.constInt }, "Argument.constInt 没参与比较")
    }

    @Test
    fun `SpSetArgument 的 dest 与 term 与 addConst 各自参与比较`() {
        val nodes: List<Argument> = listOf(
            SpSetArgument(varOf("I"), IntLiteral(0L)),
            SpSetArgument(varOf("J"), IntLiteral(0L)),
            SpSetArgument(varOf("I"), IntLiteral(1L)),
            SpSetArgument(varOf("I"), null),
            SpSetArgument(varOf("I"), IntLiteral(0L)).apply { addConst = true },
        )
        val program = programOf(*nodes.map { printl(it) }.toTypedArray())
        program.internStatementArgs()

        val read = (0 until program.statementCount).map { program.statement(it).args as SpSetArgument }
        assertEquals(listOf("I", "J", "I", "I", "I"), read.map { it.dest.name })
        assertEquals(
            listOf<Any?>(0L, 0L, 1L, null, 0L),
            read.map { (it.term as? IntLiteral)?.value },
            "SpSetArgument.term 没参与比较",
        )
        assertEquals(listOf(false, false, false, false, true), read.map { it.addConst })
        for (i in read.indices) {
            for (j in i + 1 until read.size) assertNotSame(read[i], read[j], "第 $i/$j 个参数被并成同一实例")
        }
    }

    @Test
    fun `FORM 串的文本段与转换宽度也参与比较`() {
        fun form(vararg parts: FormPart) = FormStringTerm(parts.toList())
        val nodes: List<Expr> = listOf(
            form(FormPart.Text("a")),
            form(FormPart.Text("b")),
            form(FormPart.Embedded(IntLiteral(1L), FormConversion.Percent(null, false))),
            form(FormPart.Embedded(IntLiteral(1L), FormConversion.Percent(IntLiteral(3L), false))),
            form(FormPart.Embedded(IntLiteral(1L), FormConversion.Percent(IntLiteral(3L), true))),
            form(FormPart.Embedded(IntLiteral(1L), FormConversion.Curly(IntLiteral(3L), false))),
        )
        val program = programOf(*nodes.map { printl(ExpressionArgument(it)) }.toTypedArray())
        program.internStatementArgs()

        val read = (0 until program.statementCount).map { term(program.statement(it)) as FormStringTerm }
        assertEquals("a", assertIs<FormPart.Text>(read[0].parts[0]).str)
        assertEquals("b", assertIs<FormPart.Text>(read[1].parts[0]).str)
        for (i in read.indices) {
            assertEquals(nodes[i], read[i], "第 $i 个 FORM 串去重后读回了别的内容")
            for (j in i + 1 until read.size) assertNotSame(read[i], read[j], "FORM 串第 $i/$j 个被并成同一实例")
        }
    }

    @Test
    fun `列表元素是枚举常量时逐个保留`() {
        val types = listOf(
            listOf(ArgValueType.INT),
            listOf(ArgValueType.STR),
            listOf(ArgValueType.VOID),
            listOf(ArgValueType.INT, ArgValueType.STR),
        )
        val program = programOf(
            *types.map { printl(ExpressionsArgument(it, listOf(IntLiteral(1L)))) }.toTypedArray(),
        )
        program.internStatementArgs()
        val read = (0 until types.size).map { (program.statement(it).args as ExpressionsArgument).types }
        assertEquals(types, read, "列表里的枚举常量被并成第一个常量（形状表爬到了 java.lang.Enum 的私有字段）")
    }

    // ==================================================================
    // 3. 装载链：判别力必须穿过 .erbc 读写仍然成立
    // ==================================================================

    @Test
    fun `erbc 急切档与惰性档装载后逐字段原值`(@TempDir dir: Path) {
        val init = assign(varI(), OperatorCode.ASSIGNMENT, IntLiteral(0L))
        val step = assign(varI(), OperatorCode.PLUS, IntLiteral(1L)).apply { sp(this).addConst = true }
        val bytes = ErbcWriter.encode(programOf(anchorResultEqualsGlobal(), init, step))
        val file = dir.resolve("discrimination.erbc")
        Files.write(file, bytes)
        val lazyProgram = ProgramLoader.loadMapped(file)
        // 取用之前必须是惰性档：本用例要覆盖的是「按条物化时去重」那条路径
        assertEquals(0, lazyProgram.materializedStatementCount, "loadMapped 返回后已物化条数")

        val loaded = listOf(
            "字节档" to ProgramLoader.load(bytes),
            "映射整池档" to ProgramLoader.loadMappedEager(file),
            "惰性档" to lazyProgram,
        )
        for ((label, program) in loaded) {
            val a = program.statement(1) as AssignStmt
            val b = program.statement(2) as AssignStmt
            assertEquals(varI(), a.dest, "$label：FOR INIT 的左值")
            assertEquals(varI(), b.dest, "$label：FOR STEP 的左值")
            assertEquals(0L, assertIs<IntLiteral>(sp(a).term).value, "$label：FOR INIT 的初值")
            assertEquals(1L, assertIs<IntLiteral>(sp(b).term).value, "$label：FOR STEP 的增量")
            assertTrue(sp(b).addConst, "$label：FOR STEP 的 addConst")
        }
    }
}
