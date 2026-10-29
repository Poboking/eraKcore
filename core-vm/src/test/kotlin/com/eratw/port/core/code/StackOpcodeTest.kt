package com.eratw.port.core.code

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Ternary
import com.eratw.port.core.expr.TestContext
import com.eratw.port.core.expr.Unary
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.loader.ErbcLoadException
import com.eratw.port.core.loader.ProgramLoader
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 栈码组（202..243，T2-21 / T2-22）的验收用例。
 *
 * 四层判据：
 * 1. **往返**：`asm(disasm(p)) == p` 逐字恒等（与 [AsmDisasmRoundTripAuditTest] 同口径，
 *    这里额外给每条指令配了「会真的执行」的形态）。
 * 2. **逐条执行**：42 条指令各至少一个用例，断言值与 `core.expr` 的对应求值一致；
 *    只读宿主的变量写指令断言**显式失败**（写通道的正面验收在 [VmVarBindingTest]）。
 * 3. **栈机本身**：跨帧隔离、尾跳清栈、500 层递归 + 40 深度表达式、溢出/下溢的固定文案。
 * 4. **装载期**：栈码的裸池下标越界必须被两条装载路（整读 / 映射流式）以**同一句**拒绝。
 *
 * 差分（用例 2 的加强版）直接拿 [Expr] 求值当预言机：同一语义两条路径必须逐值相同，
 * 错误路径必须逐字相同（T2-53 文案对拍的前置）。
 */
class StackOpcodeTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** body 求出的**单个 int** 作为 run 的返回值交回（末尾自动补落槽 + `_RET`）。 */
    private fun runInt(body: String, ctx: EvalContext? = null): Long {
        val prog = Assembler.assemble("$body\n  _STK_POP_SLOT_I R0\n  _RET R0")
        return VirtualMachine(prog, StringHost(ctx)).run()
    }

    /** body 求出的**单个字符串**经 `_RETS` 交回（`stringResult`）。 */
    private fun runStr(body: String, ctx: EvalContext? = null): String {
        val prog = Assembler.assemble("$body\n  _STK_POP_SLOT_S R0\n  _RETS R0")
        val vm = VirtualMachine(prog, StringHost(ctx))
        vm.run()
        return vm.stringResult ?: ""
    }

    private fun firstDiff(expected: List<Int>, actual: List<Int>): String {
        val n = minOf(expected.size, actual.size)
        for (i in 0 until n) {
            if (expected[i] != actual[i]) {
                val pc = i / Instruction.WORDS
                val field = when (i % Instruction.WORDS) {
                    0 -> "op"
                    1 -> "a"
                    2 -> "b"
                    else -> "c"
                }
                return "第 $i 字（pc=$pc 的 $field）不一致: 期望 ${expected[i]} 实际 ${actual[i]}"
            }
        }
        if (expected.size != actual.size) {
            return "指令字数不一致: 期望 ${expected.size} 实际 ${actual.size}"
        }
        return ""
    }

    private fun assertRoundTrip(src: String) {
        val p1 = Assembler.assemble(src)
        val text = p1.disassemble()
        val p2 = Assembler.assemble(text, p1.expressions(), p1.statements())
        assertEquals("", firstDiff(p1.code.toList(), p2.code.toList()), "指令字不一致。反汇编文本:\n$text")
        assertEquals(p1.instructionCount, p2.instructionCount, "指令条数不一致。反汇编文本:\n$text")
        assertEquals(p1.constantCount, p2.constantCount, "常量池大小不一致。反汇编文本:\n$text")
        assertEquals(p1.stringCount, p2.stringCount, "字符串池大小不一致。反汇编文本:\n$text")
        assertEquals(p1.globalSlots, p2.globalSlots, "全局槽数不一致。反汇编文本:\n$text")
    }

    // ------------------------------------------------------------------
    // 1 往返
    // ------------------------------------------------------------------

    /** 42 条栈码各出现一次（带操作数的用**会被真的执行**的形态），整段往返恒等。 */
    @Test
    fun `栈码组整段汇编反汇编往返字节级恒等`() {
        assertRoundTrip(
            """
            .func main slots=4
              _STK_PUSH_IMM -9223372036854775808
              _STK_PUSH_IMM 42
              _STK_PUSH_STR "引号\"反斜杠\\换行\n制表\t"
              _STK_DUP_I
              _STK_DROP
              _STK_DROP_S
              _STK_PUSH_VAR_I "A", 0
              _STK_PUSH_VAR_I "B", 2
              _STK_PUSH_VAR_S "S2", 1
              _STK_PLUS_VAR_I "A", 0, 1
              _STK_PLUS_VAR_I "C", 3, -1
              _STK_STORE_VAR_I "A", 1
              _STK_STORE_VAR_S "S", 2
              _STK_ADD
              _STK_SUB
              _STK_MUL
              _STK_DIV
              _STK_MOD
              _STK_NEG
              _STK_BITAND
              _STK_BITOR
              _STK_BITXOR
              _STK_SHL
              _STK_SHR
              _STK_BITNOT
              _STK_EQ
              _STK_NE
              _STK_LT
              _STK_LE
              _STK_GT
              _STK_GE
              _STK_SEQ
              _STK_SNE
              _STK_SLT
              _STK_SLE
              _STK_SGT
              _STK_SGE
              _STK_CONCAT
              _STK_SREPEAT
              _STK_I2S
              _STK_JZ_POP L1
              _STK_JNZ_POP L2
              _STK_JZ_KEEP L1
            L1:
              _STK_POP_SLOT_I R0
              _STK_POP_SLOT_S G0
            L2:
              _HALT
            """,
        )
    }

    /** 反汇编必须打印真名（不是 `<op:202>`）：名字表与编号一一对应是往返的前提。 */
    @Test
    fun `栈码反汇编打印规范名`() {
        val p = Assembler.assemble("_STK_PUSH_IMM 7\n_STK_ADD")
        assertEquals("_STK_PUSH_IMM 7", p.disassemble().lineSequence().first { it.contains("_STK_PUSH_IMM") }.trim())
        assertTrue(p.disassemble().contains("    _STK_ADD"))
        assertEquals(Opcode._STK_PUSH_IMM, p.op(0))
        assertEquals(7L, p.constant(p.a(0)))
    }

    // ------------------------------------------------------------------
    // 2 逐条执行
    // ------------------------------------------------------------------

    private data class IntCase(val name: String, val body: String, val expected: Long)

    @Test
    fun `每条整型栈码一个执行用例`() {
        val ctx = TestContext()
        ctx.variables.ints["A"] = 11
        ctx.variables.ints["B:2"] = 7

        val cases = listOf(
            IntCase("_STK_PUSH_IMM", "_STK_PUSH_IMM 7", 7),
            IntCase("_STK_DUP_I", "_STK_PUSH_IMM 3\n_STK_DUP_I\n_STK_ADD", 6),
            IntCase("_STK_DROP", "_STK_PUSH_IMM 9\n_STK_PUSH_IMM 4\n_STK_DROP", 9),
            IntCase("_STK_DROP_S", "_STK_PUSH_STR \"x\"\n_STK_DROP_S\n_STK_PUSH_IMM 5", 5),
            IntCase("_STK_PUSH_VAR_I(0维)", "_STK_PUSH_VAR_I \"A\", 0", 11),
            IntCase("_STK_PUSH_VAR_I(1维)", "_STK_PUSH_IMM 2\n_STK_PUSH_VAR_I \"B\", 1", 7),
            IntCase("_STK_PLUS_VAR_I", "_STK_PLUS_VAR_I \"A\", 0, 1", 12),
            IntCase("_STK_ADD", "_STK_PUSH_IMM 3\n_STK_PUSH_IMM 4\n_STK_ADD", 7),
            IntCase("_STK_SUB", "_STK_PUSH_IMM 3\n_STK_PUSH_IMM 4\n_STK_SUB", -1),
            IntCase("_STK_MUL", "_STK_PUSH_IMM 3\n_STK_PUSH_IMM 4\n_STK_MUL", 12),
            IntCase("_STK_DIV", "_STK_PUSH_IMM 12\n_STK_PUSH_IMM 4\n_STK_DIV", 3),
            IntCase("_STK_MOD", "_STK_PUSH_IMM 13\n_STK_PUSH_IMM 4\n_STK_MOD", 1),
            IntCase("_STK_NEG", "_STK_PUSH_IMM 5\n_STK_NEG", -5),
            IntCase("_STK_BITAND", "_STK_PUSH_IMM 6\n_STK_PUSH_IMM 3\n_STK_BITAND", 2),
            IntCase("_STK_BITOR", "_STK_PUSH_IMM 6\n_STK_PUSH_IMM 3\n_STK_BITOR", 7),
            IntCase("_STK_BITXOR", "_STK_PUSH_IMM 6\n_STK_PUSH_IMM 3\n_STK_BITXOR", 5),
            IntCase("_STK_SHL", "_STK_PUSH_IMM 1\n_STK_PUSH_IMM 4\n_STK_SHL", 16),
            IntCase("_STK_SHR", "_STK_PUSH_IMM -16\n_STK_PUSH_IMM 2\n_STK_SHR", -4),
            IntCase("_STK_BITNOT", "_STK_PUSH_IMM 0\n_STK_BITNOT", -1),
            IntCase("_STK_EQ", "_STK_PUSH_IMM 3\n_STK_PUSH_IMM 3\n_STK_EQ", 1),
            IntCase("_STK_NE", "_STK_PUSH_IMM 3\n_STK_PUSH_IMM 4\n_STK_NE", 1),
            IntCase("_STK_LT", "_STK_PUSH_IMM 3\n_STK_PUSH_IMM 4\n_STK_LT", 1),
            IntCase("_STK_LE", "_STK_PUSH_IMM 4\n_STK_PUSH_IMM 4\n_STK_LE", 1),
            IntCase("_STK_GT", "_STK_PUSH_IMM 5\n_STK_PUSH_IMM 4\n_STK_GT", 1),
            IntCase("_STK_GE", "_STK_PUSH_IMM 4\n_STK_PUSH_IMM 4\n_STK_GE", 1),
            IntCase("_STK_SEQ", "_STK_PUSH_STR \"a\"\n_STK_PUSH_STR \"a\"\n_STK_SEQ", 1),
            IntCase("_STK_SNE", "_STK_PUSH_STR \"a\"\n_STK_PUSH_STR \"b\"\n_STK_SNE", 1),
            IntCase("_STK_SLT", "_STK_PUSH_STR \"a\"\n_STK_PUSH_STR \"b\"\n_STK_SLT", 1),
            // 官方 bug：`<=` 判 c<0，相等时得 0（详见 specs/verify-notes.md §1）
            IntCase("_STK_SLE", "_STK_PUSH_STR \"b\"\n_STK_PUSH_STR \"b\"\n_STK_SLE", 0),
            IntCase("_STK_SGT", "_STK_PUSH_STR \"b\"\n_STK_PUSH_STR \"a\"\n_STK_SGT", 1),
            // 官方 bug：`>=` 判 c<0，方向整个反了 —— "a">="b" 得 1
            IntCase("_STK_SGE", "_STK_PUSH_STR \"a\"\n_STK_PUSH_STR \"b\"\n_STK_SGE", 1),
            // JZ_POP 取跳转分支：结果为 0；没跳的话栈顶会是 99
            IntCase(
                "_STK_JZ_POP",
                "_STK_PUSH_IMM 0\n_STK_JZ_POP L1\n_STK_PUSH_IMM 99\nL1:",
                0,
            ),
            // JZ_POP 不取分支：压入右侧值
            IntCase(
                "_STK_JZ_POP(不跳)",
                "_STK_PUSH_IMM 1\n_STK_JZ_POP L1\n_STK_PUSH_IMM 99\nL1:",
                99,
            ),
            IntCase(
                "_STK_JNZ_POP",
                "_STK_PUSH_IMM 5\n_STK_JNZ_POP L1\n_STK_PUSH_IMM 99\nL1:",
                1,
            ),
            IntCase(
                "_STK_JNZ_POP(不跳)",
                "_STK_PUSH_IMM 0\n_STK_JNZ_POP L1\n_STK_PUSH_IMM 99\nL1:",
                99,
            ),
            IntCase(
                "_STK_JZ_KEEP(假)",
                "_STK_PUSH_IMM 0\n_STK_JZ_KEEP Lelse\n_STK_PUSH_IMM 7\n_JUMP Lend\nLelse:\n_STK_PUSH_IMM 9\nLend:",
                9,
            ),
            IntCase(
                "_STK_JZ_KEEP(真)",
                "_STK_PUSH_IMM 1\n_STK_JZ_KEEP Lelse\n_STK_PUSH_IMM 7\n_JUMP Lend\nLelse:\n_STK_PUSH_IMM 9\nLend:",
                7,
            ),
        )

        for (c in cases) {
            assertEquals(c.expected, runInt(c.body, ctx), "${c.name} 的执行结果不符")
        }
        // `_STK_PLUS_VAR_I` 是读改写：副作用必须落库
        assertEquals(12L, ctx.variables.ints["A"], "_STK_PLUS_VAR_I 必须写回变量")
    }

    private data class StrCase(val name: String, val body: String, val expected: String)

    @Test
    fun `每条字符串栈码一个执行用例`() {
        val ctx = TestContext()
        ctx.variables.strs["S"] = "hi"

        val cases = listOf(
            StrCase("_STK_PUSH_STR", "_STK_PUSH_STR \"ab\"", "ab"),
            StrCase("_STK_PUSH_VAR_S", "_STK_PUSH_VAR_S \"S\", 0", "hi"),
            StrCase("_STK_CONCAT", "_STK_PUSH_STR \"a\"\n_STK_PUSH_STR \"b\"\n_STK_CONCAT", "ab"),
            StrCase("_STK_SREPEAT", "_STK_PUSH_STR \"ab\"\n_STK_PUSH_IMM 2\n_STK_SREPEAT", "abab"),
            StrCase("_STK_SREPEAT(0次)", "_STK_PUSH_STR \"ab\"\n_STK_PUSH_IMM 0\n_STK_SREPEAT", ""),
            StrCase("_STK_SREPEAT(空串)", "_STK_PUSH_STR \"\"\n_STK_PUSH_IMM 5\n_STK_SREPEAT", ""),
            StrCase("_STK_I2S", "_STK_PUSH_IMM 42\n_STK_I2S", "42"),
            StrCase("_STK_I2S(负)", "_STK_PUSH_IMM -7\n_STK_I2S", "-7"),
        )

        for (c in cases) {
            assertEquals(c.expected, runStr(c.body, ctx), "${c.name} 的执行结果不符")
        }
    }

    /** `_STK_POP_SLOT_I`：LIFO 落槽（先弹的给后写的槽号，顺序错了值就换位）。 */
    @Test
    fun `落槽指令按栈序写入槽`() {
        val prog = Assembler.assemble(
            """
            .func main slots=4
              _STK_PUSH_IMM 1
              _STK_PUSH_IMM 2
              _STK_POP_SLOT_I R1
              _STK_POP_SLOT_I R2
              _ADD R3, R1, R2
              _RET R3
            """.trimIndent(),
        )
        val vm = VirtualMachine(prog)
        assertEquals(3L, vm.run(), "栈顶 2 应进 R1、次顶 1 应进 R2")
        assertEquals(0, vm.intStackDepth, "落槽后栈必须回到空")
    }

    /** `_STK_POP_SLOT_S`：字符串通道，且 `null` 归一为 ""（同 getStr 口径）。 */
    @Test
    fun `字符串落槽写入槽且空值归一为空串`() {
        val prog = Assembler.assemble(
            """
            .func main slots=4
              _STK_PUSH_STR "ok"
              _STK_POP_SLOT_S R1
              _RETS R1
            """.trimIndent(),
        )
        val vm = VirtualMachine(prog)
        vm.run()
        assertEquals("ok", vm.stringResult)
        assertEquals(0, vm.strStackDepth)
    }

    /**
     * 写通道是**独立能力**（`VariableWriter`，T2-23）：只读宿主上必须显式失败，
     * 而不是绕过抽象去 cast 具体实现。
     *
     * 这里的只读宿主就是 [TestContext]（`FakeVariables : VariableReader`）：它有
     * `EvalContext`（否则先撞「EVAL 需要 EvalContext」那条守卫），但变量层没有写口 ——
     * 正是「宿主只接了读能力」的真实形态。
     *
     * 真变量池上的写入（含下标、私有变量跨函数、校验文案）见 [VmVarBindingTest]。
     */
    @Test
    fun `只读变量层上变量写指令显式失败`() {
        for (body in listOf("_STK_STORE_VAR_I \"A\", 0", "_STK_STORE_VAR_S \"S\", 0")) {
            val e = assertFailsWith<CodeEE> { runInt(body, ctx()) }
            assertTrue(
                "未实现 VariableWriter" in e.message!!,
                "$body 应报「未实现 VariableWriter」，实际: ${e.message}",
            )
        }
    }

    /**
     * 维数是**裸操作数**：合法域 0..3（ERB 变量到 3 维为止，`VariableStore` 的
     * `名字@1/2/3` 键空间）。不挡的话畸形值（如 0x7FFFFFFF）会在 `LongArray(b)` 上
     * 直接 OOM —— 必须按「字节码不完整」显式失败，且在**弹出下标之前**就拒
     * （先查后弹，弹了的下标随错误一起作废，不会产生半截状态）。
     */
    @Test
    fun `变量指令维数越界在弹下标之前显式失败`() {
        for (body in listOf(
            "_STK_PUSH_VAR_I \"A\", 4", // 恰好越上界
            "_STK_PUSH_VAR_I \"A\", 2147483647", // 畸形大值（会 OOM 的那一类）
            "_STK_PUSH_VAR_I \"A\", -1", // 负数
            "_STK_PUSH_VAR_S \"S\", 5",
        )) {
            val e = assertFailsWith<CodeEE> { runInt(body) }
            assertTrue(
                "维数非法" in e.message!!,
                "$body 应报「维数非法」，实际: ${e.message}",
            )
        }
    }

    // ------------------------------------------------------------------
    // 3 与 Expr 的差分（预言机 = core.expr）
    // ------------------------------------------------------------------

    private data class DiffCase(val expr: Expr, val body: String, val isStr: Boolean = false)

    private fun ctx(): TestContext = TestContext().apply {
        variables.ints["A"] = 11
        variables.ints["B:2"] = 7
        variables.ints["M:1:2"] = 99 // 2 维：锁 popIndices 的倒序回填（idx0 先压、idx1 在栈顶）
        variables.strs["S"] = "hi"
    }

    @Test
    fun `栈码求值与 Expr 求值逐值一致`() {
        val ctx = ctx()
        val cases = listOf(
            // 算术
            DiffCase(
                Binary(OperatorCode.PLUS, IntLiteral(1), Binary(OperatorCode.MULT, IntLiteral(2), IntLiteral(3), OperandType.INT), OperandType.INT),
                "_STK_PUSH_IMM 1\n_STK_PUSH_IMM 2\n_STK_PUSH_IMM 3\n_STK_MUL\n_STK_ADD",
            ),
            DiffCase(
                Binary(OperatorCode.MULT, Binary(OperatorCode.PLUS, IntLiteral(1), IntLiteral(2), OperandType.INT), IntLiteral(3), OperandType.INT),
                "_STK_PUSH_IMM 1\n_STK_PUSH_IMM 2\n_STK_ADD\n_STK_PUSH_IMM 3\n_STK_MUL",
            ),
            DiffCase(Binary(OperatorCode.DIV, IntLiteral(7), IntLiteral(2), OperandType.INT), "_STK_PUSH_IMM 7\n_STK_PUSH_IMM 2\n_STK_DIV"),
            DiffCase(Binary(OperatorCode.DIV, IntLiteral(-7), IntLiteral(2), OperandType.INT), "_STK_PUSH_IMM -7\n_STK_PUSH_IMM 2\n_STK_DIV"),
            DiffCase(Binary(OperatorCode.MOD, IntLiteral(-7), IntLiteral(2), OperandType.INT), "_STK_PUSH_IMM -7\n_STK_PUSH_IMM 2\n_STK_MOD"),
            DiffCase(Unary(OperatorCode.MINUS, IntLiteral(5)), "_STK_PUSH_IMM 5\n_STK_NEG"),
            DiffCase(Unary(OperatorCode.BIT_NOT, IntLiteral(5)), "_STK_PUSH_IMM 5\n_STK_BITNOT"),
            DiffCase(Unary(OperatorCode.NOT, IntLiteral(0)), "_STK_PUSH_IMM 0\n_STK_PUSH_IMM 0\n_STK_EQ"),
            DiffCase(Binary(OperatorCode.BIT_AND, IntLiteral(6), IntLiteral(3), OperandType.INT), "_STK_PUSH_IMM 6\n_STK_PUSH_IMM 3\n_STK_BITAND"),
            DiffCase(Binary(OperatorCode.BIT_OR, IntLiteral(6), IntLiteral(3), OperandType.INT), "_STK_PUSH_IMM 6\n_STK_PUSH_IMM 3\n_STK_BITOR"),
            DiffCase(Binary(OperatorCode.BIT_XOR, IntLiteral(6), IntLiteral(3), OperandType.INT), "_STK_PUSH_IMM 6\n_STK_PUSH_IMM 3\n_STK_BITXOR"),
            DiffCase(Binary(OperatorCode.LEFT_SHIFT, IntLiteral(1), IntLiteral(4), OperandType.INT), "_STK_PUSH_IMM 1\n_STK_PUSH_IMM 4\n_STK_SHL"),
            DiffCase(Binary(OperatorCode.RIGHT_SHIFT, IntLiteral(-16), IntLiteral(2), OperandType.INT), "_STK_PUSH_IMM -16\n_STK_PUSH_IMM 2\n_STK_SHR"),
            // 整型比较
            DiffCase(Binary(OperatorCode.LESS, IntLiteral(1), IntLiteral(2), OperandType.INT), "_STK_PUSH_IMM 1\n_STK_PUSH_IMM 2\n_STK_LT"),
            DiffCase(Binary(OperatorCode.LESS_EQUAL, IntLiteral(2), IntLiteral(2), OperandType.INT), "_STK_PUSH_IMM 2\n_STK_PUSH_IMM 2\n_STK_LE"),
            DiffCase(Binary(OperatorCode.GREATER_EQUAL, IntLiteral(3), IntLiteral(4), OperandType.INT), "_STK_PUSH_IMM 3\n_STK_PUSH_IMM 4\n_STK_GE"),
            DiffCase(Binary(OperatorCode.EQUAL, IntLiteral(3), IntLiteral(3), OperandType.INT), "_STK_PUSH_IMM 3\n_STK_PUSH_IMM 3\n_STK_EQ"),
            DiffCase(Binary(OperatorCode.NOT_EQUAL, IntLiteral(3), IntLiteral(3), OperandType.INT), "_STK_PUSH_IMM 3\n_STK_PUSH_IMM 3\n_STK_NE"),
            // 字符串比较（含两处官方 bug）
            DiffCase(Binary(OperatorCode.GREATER_EQUAL, StrLiteral("a"), StrLiteral("b"), OperandType.INT), "_STK_PUSH_STR \"a\"\n_STK_PUSH_STR \"b\"\n_STK_SGE"),
            DiffCase(Binary(OperatorCode.LESS_EQUAL, StrLiteral("b"), StrLiteral("b"), OperandType.INT), "_STK_PUSH_STR \"b\"\n_STK_PUSH_STR \"b\"\n_STK_SLE"),
            DiffCase(Binary(OperatorCode.GREATER, StrLiteral("b"), StrLiteral("a"), OperandType.INT), "_STK_PUSH_STR \"b\"\n_STK_PUSH_STR \"a\"\n_STK_SGT"),
            DiffCase(Binary(OperatorCode.LESS, StrLiteral("a"), StrLiteral("b"), OperandType.INT), "_STK_PUSH_STR \"a\"\n_STK_PUSH_STR \"b\"\n_STK_SLT"),
            // 变量
            DiffCase(VarRef("A", emptyList(), operandType = OperandType.INT), "_STK_PUSH_VAR_I \"A\", 0"),
            DiffCase(
                VarRef("B", listOf(IntLiteral(2)), operandType = OperandType.INT),
                "_STK_PUSH_IMM 2\n_STK_PUSH_VAR_I \"B\", 1",
            ),
            // 2 维：栈面 idx0=1 先压、idx1=2 在顶 ⇒ 装出来的 indices 必须是 [1, 2]（键 M:1:2）。
            // 倒序回填写反的话这里会读 M:2:1 = 0，差分当场红。
            DiffCase(
                VarRef("M", listOf(IntLiteral(1), IntLiteral(2)), operandType = OperandType.INT),
                "_STK_PUSH_IMM 1\n_STK_PUSH_IMM 2\n_STK_PUSH_VAR_I \"M\", 2",
            ),
            // 短路（&& / ||）：右侧是否求值由 JZ_POP/JNZ_POP 决定
            DiffCase(Binary(OperatorCode.AND, IntLiteral(0), IntLiteral(1), OperandType.INT), "_STK_PUSH_IMM 0\n_STK_JZ_POP Lend\n_STK_PUSH_IMM 1\n_STK_PUSH_IMM 0\n_STK_NE\nLend:"),
            DiffCase(Binary(OperatorCode.AND, IntLiteral(1), IntLiteral(2), OperandType.INT), "_STK_PUSH_IMM 1\n_STK_JZ_POP Lend\n_STK_PUSH_IMM 2\n_STK_PUSH_IMM 0\n_STK_NE\nLend:"),
            DiffCase(Binary(OperatorCode.OR, IntLiteral(1), IntLiteral(0), OperandType.INT), "_STK_PUSH_IMM 1\n_STK_JNZ_POP Lend\n_STK_PUSH_IMM 0\n_STK_PUSH_IMM 0\n_STK_NE\nLend:"),
            DiffCase(Binary(OperatorCode.OR, IntLiteral(0), IntLiteral(0), OperandType.INT), "_STK_PUSH_IMM 0\n_STK_JNZ_POP Lend\n_STK_PUSH_IMM 0\n_STK_PUSH_IMM 0\n_STK_NE\nLend:"),
            // 三目
            DiffCase(Ternary(IntLiteral(1), IntLiteral(2), IntLiteral(3), OperandType.INT), "_STK_PUSH_IMM 1\n_STK_JZ_KEEP Lelse\n_STK_PUSH_IMM 2\n_JUMP Lend\nLelse:\n_STK_PUSH_IMM 3\nLend:"),
            DiffCase(Ternary(IntLiteral(0), IntLiteral(2), IntLiteral(3), OperandType.INT), "_STK_PUSH_IMM 0\n_STK_JZ_KEEP Lelse\n_STK_PUSH_IMM 2\n_JUMP Lend\nLelse:\n_STK_PUSH_IMM 3\nLend:"),
            // 字符串
            DiffCase(Binary(OperatorCode.PLUS, StrLiteral("a"), StrLiteral("b"), OperandType.STR), "_STK_PUSH_STR \"a\"\n_STK_PUSH_STR \"b\"\n_STK_CONCAT", isStr = true),
            DiffCase(Binary(OperatorCode.MULT, StrLiteral("ab"), IntLiteral(2), OperandType.STR), "_STK_PUSH_STR \"ab\"\n_STK_PUSH_IMM 2\n_STK_SREPEAT", isStr = true),
            DiffCase(Binary(OperatorCode.MULT, IntLiteral(2), StrLiteral("ab"), OperandType.STR), "_STK_PUSH_STR \"ab\"\n_STK_PUSH_IMM 2\n_STK_SREPEAT", isStr = true),
            DiffCase(
                Binary(OperatorCode.PLUS, StrLiteral("x"), Binary(OperatorCode.MULT, StrLiteral("y"), IntLiteral(3), OperandType.STR), OperandType.STR),
                "_STK_PUSH_STR \"x\"\n_STK_PUSH_STR \"y\"\n_STK_PUSH_IMM 3\n_STK_SREPEAT\n_STK_CONCAT",
                isStr = true,
            ),
            DiffCase(VarRef("S", emptyList(), operandType = OperandType.STR), "_STK_PUSH_VAR_S \"S\", 0", isStr = true),
        )

        for (row in cases) {
            val label = row.expr.toString()
            if (row.isStr) {
                assertEquals(row.expr.getStrValue(ctx), runStr(row.body, ctx), "字符串差分失败: $label")
            } else {
                assertEquals(row.expr.getIntValue(ctx), runInt(row.body, ctx), "整型差分失败: $label")
            }
        }
    }

    @Test
    fun `运行期错误文案与 Expr 逐字一致`() {
        val c = ctx()
        val cases = listOf(
            DiffCase(Binary(OperatorCode.DIV, IntLiteral(5), IntLiteral(0), OperandType.INT), "_STK_PUSH_IMM 5\n_STK_PUSH_IMM 0\n_STK_DIV"),
            DiffCase(Binary(OperatorCode.MOD, IntLiteral(5), IntLiteral(0), OperandType.INT), "_STK_PUSH_IMM 5\n_STK_PUSH_IMM 0\n_STK_MOD"),
            DiffCase(
                Binary(OperatorCode.DIV, IntLiteral(Long.MIN_VALUE), IntLiteral(-1), OperandType.INT),
                "_STK_PUSH_IMM -9223372036854775808\n_STK_PUSH_IMM -1\n_STK_DIV",
            ),
            DiffCase(Binary(OperatorCode.MULT, StrLiteral("ab"), IntLiteral(-1), OperandType.STR), "_STK_PUSH_STR \"ab\"\n_STK_PUSH_IMM -1\n_STK_SREPEAT", isStr = true),
            DiffCase(Binary(OperatorCode.MULT, IntLiteral(-2), StrLiteral("ab"), OperandType.STR), "_STK_PUSH_STR \"ab\"\n_STK_PUSH_IMM -2\n_STK_SREPEAT", isStr = true),
            DiffCase(Binary(OperatorCode.MULT, StrLiteral("ab"), IntLiteral(10000), OperandType.STR), "_STK_PUSH_STR \"ab\"\n_STK_PUSH_IMM 10000\n_STK_SREPEAT", isStr = true),
        )

        for (d in cases) {
            val expected = assertFailsWith<ExprEvalException> {
                if (d.isStr) d.expr.getStrValue(c) else d.expr.getIntValue(c)
            }.message
            val actual = assertFailsWith<CodeEE> {
                if (d.isStr) runStr(d.body, c) else runInt(d.body, c)
            }.message
            assertEquals(expected, actual, "错误文案不一致: ${d.expr}")
        }
    }

    // ------------------------------------------------------------------
    // 4 栈机本身（T2-22 验收）
    // ------------------------------------------------------------------

    /**
     * 递归 500 层、每层算一条 40 深度的表达式：值必须正确，栈必须逐帧回卷。
     *
     * 这条用例同时压三件事：帧水位线（每层都从空栈起算）、`leaveFrame` 的回卷、
     * 以及 `_CALL` 不把调用方的中间值漏给被调帧。
     */
    @Test
    fun `递归 500 层每层 40 深度表达式不回绕`() {
        val src = buildString {
            append(".func main arity=0 slots=8\n")
            append("  _SET_INT R0, 500\n")
            append("  _CALL rec, 1, R5\n")
            append("  _RET R5\n")
            append("\n.func rec arity=1 slots=8\n")
            append("  _SET_INT R2, 1\n")
            repeat(40) { append("  _STK_PUSH_IMM 1\n") }
            repeat(39) { append("  _STK_ADD\n") }
            append("  _STK_POP_SLOT_I R1\n")
            append("  _JUMP_IF_FALSE R0, done\n")
            append("  _SUB R0, R0, R2\n")
            append("  _CALL rec, 1, none\n")
            append("done:\n")
            append("  _RET R1\n")
        }
        val vm = VirtualMachine(Assembler.assemble(src))
        assertEquals(40L, vm.run(), "40 个 1 相加应得 40")
        assertEquals(0, vm.intStackDepth, "每帧返回都应把 int 栈回卷到水位线")
        assertEquals(0, vm.strStackDepth, "字符串栈未被触碰")
    }

    /** 被调帧看不见调用方的中间值：调用方压着 7 调过去，回来还得是自己的 7。 */
    @Test
    fun `跨帧隔离被调帧看不到调用方的栈`() {
        val src = """
            .func main slots=4
              _STK_PUSH_IMM 7
              _CALL f, 0, none
              _STK_POP_SLOT_I R1
              _RET R1

            .func f arity=0 slots=4
              _STK_PUSH_IMM 1
              _STK_PUSH_IMM 2
              _STK_ADD
              _RET
        """.trimIndent()
        val vm = VirtualMachine(Assembler.assemble(src))
        assertEquals(7L, vm.run(), "被调帧的 1+2 不得吃掉调用方的 7")
        assertEquals(0, vm.intStackDepth)
    }

    /** 尾跳（JUMP 家族）原地换帧：旧件的中间值必须作废。 */
    @Test
    fun `尾跳清空本帧操作数栈`() {
        val src = """
            .func main slots=4
              _STK_PUSH_IMM 7
              _CALL tail, 1073741824, none
              _HALT

            .func tail arity=0 slots=4
              _STK_PUSH_IMM 1
              _STK_PUSH_IMM 2
              _STK_ADD
              _STK_POP_SLOT_I R0
              _RET R0
        """.trimIndent()
        val vm = VirtualMachine(Assembler.assemble(src))
        assertEquals(3L, vm.run())
        assertEquals(0, vm.intStackDepth, "尾跳必须把旧帧压下的 7 丢掉")
    }

    /**
     * 溢出：深递归每层漏一个槽。1024 槽的上限会在 1024 层附近触发，
     * 抛**固定文案**的 CodeEE，而不是回绕改写别的帧。
     */
    @Test
    fun `操作数栈溢出抛固定文案且不回绕`() {
        val src = """
            .func main slots=2
              _STK_PUSH_IMM 1
              _CALL rec, 0, none
              _HALT

            .func rec arity=0 slots=2
              _STK_PUSH_IMM 1
              _CALL rec, 0, none
              _RET
        """.trimIndent()
        val e = assertFailsWith<CodeEE> { VirtualMachine(Assembler.assemble(src)).run() }
        assertEquals("操作数栈溢出（上限 1024 槽）", e.message)
    }

    /** 下溢：空栈弹值必须显式失败，不能读到水位线以下的残留。 */
    @Test
    fun `空栈弹值抛下溢`() {
        val e = assertFailsWith<CodeEE> {
            VirtualMachine(Assembler.assemble("_STK_ADD\n_HALT")).run()
        }
        assertEquals("操作数栈下溢（本帧没有可弹出的整数）", e.message)

        val e2 = assertFailsWith<CodeEE> {
            VirtualMachine(Assembler.assemble("_STK_CONCAT\n_HALT")).run()
        }
        assertEquals("操作数栈下溢（本帧没有可弹出的字符串）", e2.message)
    }

    /** 两个栈互不干扰：字符串栈深不影响整型运算，反之亦然。 */
    @Test
    fun `int 栈与 String 栈互相独立`() {
        val body = """
            _STK_PUSH_STR "hold"
            _STK_PUSH_IMM 3
            _STK_PUSH_IMM 4
            _STK_ADD
            _STK_PUSH_STR "!"
            _STK_CONCAT
            _STK_I2S
        """.trimIndent()
        // 栈面：["hold" "!" "7"]，取栈顶得 "7"
        assertEquals("7", runStr(body))
    }

    // ------------------------------------------------------------------
    // 5 装载期：栈码的裸池下标
    // ------------------------------------------------------------------

    /**
     * 栈码把表达式池下标换成了**裸操作数**，因此越界不再有 `_EVAL_*` 的装载期保护伞
     * （`validate` 里原有的 `_SET_*`/`_EVAL_*`/marker 分支都不覆盖 202..243）。
     *
     * 判据落在 [ErbcReader.requireStackOpPool] 一处、两条装载路共用：这里同时喂整读
     * 与映射（流式）两档，断言两者**同句**（`pc=…` 起的正文逐字相同；来源名前缀本来就
     * 按装载档不同，整读用逻辑名、映射用绝对路径）—— 否则同一份畸形产物会在「装得上/
     * 装不上」之间取决于宿主走哪条装载路，而漏过去的那条会在执行时以裸
     * `IndexOutOfBoundsException`（无函数/行号）炸出来。
     */
    @Test
    fun `栈码池引用越界在装载期被两条装载路拒绝`(@TempDir dir: Path) {
        // 常量池为空却引用常量 0
        val imm = loadBoth(dir, "imm") { b ->
            b.emit(Opcode._STK_PUSH_IMM, 0, 0, 0, line = 3)
        }
        assertTrue(
            "引用常量池下标 0" in imm.first,
            "整读路径必须指名道姓报常量池越界，实际：${imm.first}",
        )
        assertEquals(coreOf(imm.first, "imm.erbc"), coreOf(imm.second, "imm.erbc"), "_STK_PUSH_IMM：两条装载路必须同句")

        // 字符串池为空却引用变量名 0（变量名走全局串池）
        val v = loadBoth(dir, "var") { b ->
            b.emit(Opcode._STK_PUSH_VAR_I, 0, 0, 0, line = 3)
        }
        assertTrue(
            "引用字符串池下标 0" in v.first,
            "整读路径必须指名道姓报字符串池越界，实际：${v.first}",
        )
        assertEquals(coreOf(v.first, "var.erbc"), coreOf(v.second, "var.erbc"), "_STK_PUSH_VAR_I：两条装载路必须同句")
    }

    /**
     * 剥掉装载层的「来源名 + 偏移」前缀，只留解码正文。
     *
     * 两条装载路的来源名本来就不一样（整读拿调用方给的名字、映射拿文件路径），
     * 逐字比对整条消息等于在比对文件名；这里比的是 `pc=` 起的正文。
     */
    private fun coreOf(message: String, expectedName: String): String {
        val start = message.indexOf("pc=")
        assertTrue(start >= 0, "文案里必须带 pc 定位（实际：$message）")
        assertTrue(expectedName in message, "文案里必须带来源名（实际：$message）")
        return message.substring(start)
    }

    /** 用同一段畸形产物走「整读」「映射（流式）」两档装载，返回两边的异常文案。 */
    private fun loadBoth(dir: Path, tag: String, dirty: (CodeBuilder) -> Unit): Pair<String, String> {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 2)
        dirty(b)
        b.emit(Opcode._HALT, 0, 0, 0, line = 4)
        val bytes = ErbcWriter.encode(b.build(main))

        val viaBytes = assertFailsWith<ErbcLoadException> { ProgramLoader.load(bytes, "$tag.erbc") }
        val f = dir.resolve("$tag.erbc")
        Files.write(f, bytes)
        val viaMapped = assertFailsWith<ErbcLoadException> { ProgramLoader.loadMapped(f) }
        return viaBytes.message!! to viaMapped.message!!
    }
}
