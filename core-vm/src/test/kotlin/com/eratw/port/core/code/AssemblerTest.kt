package com.eratw.port.core.code

import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 汇编器/反汇编器：文本 ↔ 字节码往返与错误路径。 */
class AssemblerTest {

    private fun assertRoundTrip(src: String, exprs: List<Expr> = emptyList()) {
        val p1 = Assembler.assemble(src.trimIndent(), exprs)
        val text = p1.disassemble()
        val p2 = Assembler.assemble(text, p1.expressions())

        assertEquals(
            p1.code.toList(),
            p2.code.toList(),
            "指令字不一致。反汇编文本:\n$text",
        )
        assertEquals(p1.instructionCount, p2.instructionCount)
        assertEquals(p1.constantCount, p2.constantCount, "常量池大小不一致")
        assertEquals(p1.stringCount, p2.stringCount, "字符串池大小不一致")
        assertEquals(p1.globalSlots, p2.globalSlots)
        assertEquals(
            p1.functions.map { listOf(it.name, it.arity, it.slotCount, it.entryPc) },
            p2.functions.map { listOf(it.name, it.arity, it.slotCount, it.entryPc) },
        )
    }

    @Test
    fun `标签与跳转往返`() {
        assertRoundTrip(
            """
            .func main slots=3
              _SET_INT R0, 0
              _SET_INT R1, 1
            loop:
              _ADD R0, R0, R1
              _LT R2, R0, R1
              _JUMP_IF_FALSE R2, done
              _JUMP loop
            done:
              _HALT
            """,
        )
    }

    @Test
    fun `字符串转义往返`() {
        assertRoundTrip(
            """
            _SET_STR R0, "引号\"与反斜杠\\与换行\n制表\t"
            _PRINT_LITERAL "日本語テキスト"
            _HALT
            """,
        )
    }

    @Test
    fun `常量与全局槽往返`() {
        assertRoundTrip(
            """
            _SET_INT R0, -9223372036854775808
            _SET_INT R1, 9223372036854775807
            _SET_INT G2, 42
            _ADD G2, G2, R0
            _HALT
            """,
        )
    }

    @Test
    fun `函数与调用往返`() {
        assertRoundTrip(
            """
            .func main slots=2
              _CALL helper, 1, R0
              _HALT

            .func helper arity=1 slots=4
              _RET R0
            """,
        )
    }

    @Test
    fun `表达式操作数往返`() {
        val exprs = listOf<Expr>(
            Binary(
                OperatorCode.PLUS,
                VarRef("X", emptyList(), operandType = OperandType.INT),
                IntLiteral(2),
                operandType = OperandType.INT,
            ),
            IntLiteral(7),
        )
        assertRoundTrip(
            """
            _EVAL_INT ${'$'}0, R0
            _EVAL_INT ${'$'}1, R1
            _HALT
            """,
            exprs,
        )
    }

    @Test
    fun `ERB 语句操作码原样发射并可反汇编`() {
        val p = Assembler.assemble("PRINTL 1, 2, 3")
        assertEquals(Opcode.PRINT, p.op(0))
        assertEquals(1, p.a(0))
        assertEquals(2, p.b(0))
        assertEquals(3, p.c(0))
        assertTrue(p.disassemble().contains("PRINT"))
    }

    @Test
    fun `编译器扩展操作码 198 到 201 可汇编且反汇编往返`() {
        // 审计 finding 3：NAMES 只到 197 时反汇编打印 `<op:198>`、汇编器报"未知助记符"，
        // 编译器产物（CALLFORM/GOTOFORM/#FUNCTIONS）无法 asm(disasm(p)) 往返。
        assertRoundTrip(
            """
            .func main slots=3
              _SET_STR R0, "FOO"
              _CALL_BY_NAME R0, 1, R1
              _JUMP_BY_NAME R0, 0, none
              _JUMP_DYN 2
              _SET_STR R2, "RET"
              _RETS R2
              _HALT
            """,
        )
        val p = Assembler.assemble("_RETS none")
        assertEquals("_RETS", p.instructionAt(0).name, "反汇编必须打印真名而不是 <op:201>")
        assertTrue(Opcode.isExtension(p.op(0)))
    }

    @Test
    fun `隐式 main 函数在没有 func 指示符时创建`() {
        val p = Assembler.assemble("_HALT")
        assertEquals(1, p.functions.size)
        assertEquals("main", p.functions[0].name)
        assertEquals(0, p.functions[0].entryPc)
    }

    @Test
    fun `声明帧槽数大于实际使用时保留声明值`() {
        val p = Assembler.assemble(
            """
            .func main slots=10
              _HALT
            """.trimIndent(),
        )
        assertEquals(10, p.functions[0].slotCount)
    }

    @Test
    fun `未知助记符报错`() {
        val e = assertFailsWith<AssemblyException> { Assembler.assemble("_FOO R0") }
        assertTrue(e.message!!.contains("未知助记符"), e.message)
    }

    @Test
    fun `未定义标签报错`() {
        val e = assertFailsWith<AssemblyException> { Assembler.assemble("_JUMP nowhere") }
        assertTrue(e.message!!.contains("未定义的标签"), e.message)
    }

    @Test
    fun `未定义函数报错`() {
        val e = assertFailsWith<AssemblyException> { Assembler.assemble("_CALL ghost, 0, R0") }
        assertTrue(e.message!!.contains("未定义的函数"), e.message)
    }

    @Test
    fun `字符串未闭合报错`() {
        val e = assertFailsWith<AssemblyException> { Assembler.assemble("_PRINT_LITERAL \"abc") }
        assertTrue(e.message!!.contains("未闭合"), e.message)
    }

    @Test
    fun `SET_STR 要求字符串字面量`() {
        val e = assertFailsWith<AssemblyException> { Assembler.assemble("_SET_STR R0, 12") }
        assertTrue(e.message!!.contains("字符串字面量"), e.message)
    }

    @Test
    fun `操作数个数不符报错`() {
        val e = assertFailsWith<AssemblyException> { Assembler.assemble("_ADD R0, R1") }
        assertTrue(e.message!!.contains("需要 3 个操作数"), e.message)
    }

    @Test
    fun `槽操作数格式错误报错`() {
        val e = assertFailsWith<AssemblyException> { Assembler.assemble("_SET_INT 5, 1") }
        assertTrue(e.message!!.contains("槽操作数"), e.message)
    }

    @Test
    fun `重复标签报错`() {
        val e = assertFailsWith<AssemblyException> {
            Assembler.assemble(
                """
                a:
                _NOP
                a:
                _HALT
                """.trimIndent(),
            )
        }
        assertTrue(e.message!!.contains("标签重复"), e.message)
    }

    @Test
    fun `重复函数名报错`() {
        val e = assertFailsWith<AssemblyException> {
            Assembler.assemble(
                """
                .func f slots=1
                  _HALT
                .func f slots=1
                  _HALT
                """.trimIndent(),
            )
        }
        assertTrue(e.message!!.contains("重复声明"), e.message)
    }
}
