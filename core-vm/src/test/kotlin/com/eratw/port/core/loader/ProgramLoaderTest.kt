package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcFormatException
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.FunctionInfo
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

/**
 * `.erbc` 装载器（core/loader）单测。
 *
 * 覆盖 ERBC-B 任务 2 的验收面：
 * 1. `load(bytes)` 走 `ErbcReader` 解出的 Program 与内存编译的 Program 逐字段一致；
 * 2. 单文件 / 目录入口（目录按相对路径排序，续片 `.erbc.p1` 不参与）；
 * 3. 畸形输入（magic/版本/段数/标志位/段长/截断）抛**明确异常**，
 *    且任何非 `ErbcFormatException` 的运行期异常（IndexOutOfBounds 等）都被收口；
 * 4. 文件末尾多余字节合法（规约 §1.5：assets 对齐填充）。
 */
class ProgramLoaderTest {

    // ==================================================================
    // 样题：四池 + 两函数 + 标签表（全走公开 API；语义构造不依赖 erbc 编解码）
    // ==================================================================

    private fun sampleProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 1, 3)
        val c0 = b.constant(42L)
        val c1 = b.constant(Long.MIN_VALUE)
        val s0 = b.string("こんにちは，世界")
        val s1 = b.string("")
        val e0: Expr = IntLiteral(7L, 1, 2)
        val e1: Expr = Binary(
            OperatorCode.PLUS,
            IntLiteral(1L, 3, 1),
            VarRef("LV", emptyList(), operandType = OperandType.INT),
            OperandType.INT,
            3,
            3,
        )
        val st0 = InstructionStmt(FunctionCode.WAIT, VoidArgument(), 10, "WAIT")
        val st1 = DataStmt(FunctionCode.DATA, "数据", VoidArgument(), 11, "DATA 数据")
        b.emit(Opcode._SET_INT, SlotAddr.local(0), c0, 0, 1)
        b.emit(Opcode._SET_INT, SlotAddr.global(1), c1, 0, 1)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s0, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s1, 0, 2)
        b.emit(Opcode._EVAL_INT, b.expr(e0), SlotAddr.local(1), 0, 3)
        b.emit(Opcode._EVAL_INT, b.expr(e1), SlotAddr.local(1), 0, 4)
        b.emit(Opcode.WAIT, b.statement(st0), 0, SlotAddr.NONE, 10)
        b.emit(Opcode.DATA, b.statement(st1), 0, SlotAddr.NONE, 11)
        b.declareLabel(main, "TAIL", b.here())
        b.emit(Opcode._HALT, 0, 0, 0, 12)
        val sub = b.declareFunction("sub", b.here(), 2, 4)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 20)
        b.declareLabel(sub, "INNER", b.here() - 1)
        b.setFunctionSlots(sub, 4)
        b.noteGlobalSlot(1)
        return b.build(0)
    }

    private fun assertProgramEquals(expected: Program, actual: Program) {
        assertEquals(expected.instructionCount, actual.instructionCount, "指令条数")
        assertArrayEquals(expected.code, actual.code, "指令流逐字")
        assertArrayEquals(expected.lines, actual.lines, "行号表逐字")
        assertEquals(expected.globalSlots, actual.globalSlots, "全局槽")
        assertEquals(expected.entryFunction, actual.entryFunction, "入口函数")
        assertEquals(expected.constantCount, actual.constantCount, "常量池长度")
        for (i in 0 until expected.constantCount) {
            assertEquals(expected.constant(i), actual.constant(i), "常量池下标 $i")
        }
        assertEquals(expected.stringCount, actual.stringCount, "字符串池长度")
        for (i in 0 until expected.stringCount) {
            assertEquals(expected.string(i), actual.string(i), "字符串池下标 $i")
        }
        assertEquals(expected.exprCount, actual.exprCount, "表达式池长度")
        for (i in 0 until expected.exprCount) {
            assertEquals(expected.expr(i), actual.expr(i), "表达式池下标 $i 结构不等")
        }
        assertEquals(expected.statementCount, actual.statementCount, "语句池长度")
        for (i in 0 until expected.statementCount) {
            val e = expected.statement(i)
            val a = actual.statement(i)
            assertEquals(e.javaClass, a.javaClass, "语句池下标 $i 类不同")
            assertEquals(e.code, a.code, "语句池下标 $i 的 FunctionCode")
            assertEquals(e.line, a.line, "语句池下标 $i 行号")
            assertEquals(e.raw, a.raw, "语句池下标 $i 原文")
            assertGraphEquals("stmt[$i]", e, a)
        }
        assertEquals(expected.functions.size, actual.functions.size, "函数表长度")
        for (i in expected.functions.indices) {
            val e: FunctionInfo = expected.functions[i]
            val a: FunctionInfo = actual.functions[i]
            assertEquals(e.name, a.name, "函数 $i 名字")
            assertEquals(e.entryPc, a.entryPc, "函数 ${e.name} entryPc")
            assertEquals(e.arity, a.arity, "函数 ${e.name} arity")
            assertEquals(e.slotCount, a.slotCount, "函数 ${e.name} slotCount")
            assertEquals(e.labelNames.toList(), a.labelNames.toList(), "函数 ${e.name} 标签名表")
            assertEquals(e.labelPcs.toList(), a.labelPcs.toList(), "函数 ${e.name} 标签 pc 表")
            assertEquals(e.paramNames.toList(), a.paramNames.toList(), "函数 ${e.name} 头部形参名（v2 段 8）")
        }
        assertEquals(expected.callArgs, actual.callArgs, "调用点实参表（v2 段 7）")
    }

    // ==================================================================
    // 往返：装载出的 Program 与内存编译的 Program 逐字段一致
    // ==================================================================

    @Test
    fun `load 字节装载与内存编译的 Program 逐字段一致`() {
        val before = sampleProgram()
        val bytes = ErbcWriter.encode(before)

        val after = ProgramLoader.load(bytes)

        assertProgramEquals(before, after)
        assertTrue(after.instructionCount > 0)
    }

    @Test
    fun `装载后再编码与原始字节逐字相同`() {
        val before = sampleProgram()
        val bytes = ErbcWriter.encode(before)
        val again = ErbcWriter.encode(ProgramLoader.load(bytes))
        assertArrayEquals(bytes, again, "装载 → 再编码必须规范化回同一份字节")
    }

    @Test
    fun `文件末尾多余字节合法（规约 §1_5 对齐填充）`() {
        val bytes = ErbcWriter.encode(sampleProgram())
        val padded = bytes + ByteArray(37) { 0x5A }
        val after = ProgramLoader.load(padded)
        assertTrue(after.instructionCount > 0, "尾部填充不得影响装载")
        assertProgramEquals(sampleProgram(), after)
    }

    // ==================================================================
    // 单文件 / 目录入口
    // ==================================================================

    @Test
    fun `单文件装载与字节装载一致`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(sampleProgram())
        val f = dir.resolve("program.erbc")
        Files.write(f, bytes)
        assertProgramEquals(sampleProgram(), ProgramLoader.loadFile(f))
    }

    @Test
    fun `目录装载按相对路径排序且跳过续片`(@TempDir dir: Path) {
        val root = dir.resolve("out")
        Files.createDirectories(root.resolve("nested"))
        val bytes = ErbcWriter.encode(sampleProgram())
        // 故意用乱序创建：a.z / nested/b / c 三个产物 + 一个续片
        Files.write(root.resolve("a.z.erbc"), bytes)
        Files.write(root.resolve("nested/b.erbc"), bytes)
        Files.write(root.resolve("c.erbc"), bytes)
        Files.write(root.resolve("program.erbc.p1"), bytes.copyOf(64))   // 续片：不得参与

        val names = ProgramLoader.artifactFiles(root).map { root.relativize(it).toString().replace('\\', '/') }
        assertEquals(listOf("a.z.erbc", "c.erbc", "nested/b.erbc"), names, "清单必须按相对路径排序")

        val loaded = ProgramLoader.loadDirectory(root)
        assertEquals(3, loaded.size, "续片不参与装载")
        assertEquals(listOf("a.z.erbc", "c.erbc", "nested/b.erbc"), loaded.map { it.relativePath })
        for (lp in loaded) {
            assertEquals(bytes.size, lp.byteSize)
            assertProgramEquals(sampleProgram(), lp.program)
        }
    }

    @Test
    fun `probe 只读文件头不触碰段正文`() {
        val bytes = ErbcWriter.encode(sampleProgram())
        val header = ProgramLoader.probe(bytes, "x.erbc")
        assertEquals(ErbcFormat.LATEST_VERSION, header.version)
        assertEquals(ErbcFormat.SECTION_COUNT, header.sectionCount)
        assertTrue(header.hasSection(ErbcFormat.SECTION_FUNCTIONS))
        assertTrue(header.hasSection(ErbcFormat.SECTION_CODE))
        assertTrue(header.hasSection(ErbcFormat.SECTION_LINES))
        assertTrue(header.hasSection(ErbcFormat.SECTION_STRINGS))
        assertTrue(header.hasSection(ErbcFormat.SECTION_STATEMENTS))
        assertTrue(header.hasSection(ErbcFormat.SECTION_EXPRESSIONS))
    }

    // ==================================================================
    // 畸形输入：明确异常，不许 IndexOutOfBounds 冒泡
    // ==================================================================

    @Test
    fun `空文件与过短文件头报明确错误`() {
        assertFailsWith<ErbcFormatException> { ProgramLoader.load(ByteArray(0)) }
        assertFailsWith<ErbcFormatException> { ProgramLoader.load(ByteArray(9)) }
    }

    @Test
    fun `magic 不符报实际四字节的十六进制`() {
        val bad = ErbcWriter.encode(sampleProgram()).copyOf()
        bad[0] = 'X'.code.toByte()
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.load(bad, "broken.erbc") }
        assertTrue(e.message!!.contains("magic"), e.message!!)
        assertTrue(e.message!!.contains("broken.erbc"), "异常应带来源：${e.message}")
    }

    @Test
    fun `版本不是 1 或 2 拒绝且不做降级解释`() {
        val bad = ErbcWriter.encode(sampleProgram()).copyOf()
        writeLe16(bad, 4, 3)
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.load(bad) }
        assertTrue(e.message!!.contains("版本"), e.message!!)
    }

    @Test
    fun `段表槽数不是 7 拒绝`() {
        val bad = ErbcWriter.encode(sampleProgram()).copyOf()
        writeLe16(bad, 8, 8)
        assertFailsWith<ErbcFormatException> { ProgramLoader.load(bad) }
    }

    @Test
    fun `未定义标志位拒绝`() {
        val bad = ErbcWriter.encode(sampleProgram()).copyOf()
        writeLe16(bad, 6, ErbcFormat.FLAG_HAS_LINES or 0x0100)
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.load(bad) }
        assertTrue(e.message!!.contains("标志位"), e.message!!)
    }

    @Test
    fun `标志位与段表不一致（声明有段却写 0 偏移）拒绝`() {
        val bad = ErbcWriter.encode(sampleProgram()).copyOf()
        // 声明带语句池（flags 里有 HAS_STATEMENTS），但段表里把语句段偏移改成 0
        val stmtSlot = ErbcFormat.HEADER_BYTES + ErbcFormat.SECTION_STATEMENTS * 4
        writeLe32(bad, stmtSlot, 0)
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.load(bad) }
        assertTrue(e.message!!.contains("不一致"), e.message!!)
    }

    @Test
    fun `段偏移落在文件头加段表内拒绝`() {
        val bad = ErbcWriter.encode(sampleProgram()).copyOf()
        val funcSlot = ErbcFormat.HEADER_BYTES + ErbcFormat.SECTION_FUNCTIONS * 4
        writeLe32(bad, funcSlot, 20)   // < 38：读进去会把段表字节当段正文
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.load(bad) }
        assertTrue(e.message!!.contains("文件头"), e.message!!)
    }

    @Test
    fun `两个段声明同一偏移拒绝`() {
        val bad = ErbcWriter.encode(sampleProgram()).copyOf()
        val header = ProgramLoader.probe(bad, null)
        val funcSlot = ErbcFormat.HEADER_BYTES + ErbcFormat.SECTION_FUNCTIONS * 4
        writeLe32(bad, funcSlot, header.offsets[ErbcFormat.SECTION_CODE])
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.load(bad) }
        assertTrue(e.message!!.contains("同一偏移"), e.message!!)
    }

    @Test
    fun `CODE 段指令条数撒谎（装不下）拒绝`() {
        val bad = ErbcWriter.encode(sampleProgram()).copyOf()
        val codeOff = ProgramLoader.probe(bad, null).offsets[ErbcFormat.SECTION_CODE]
        writeLe32(bad, codeOff, 9_999)   // 声明 9999 条指令，段区间装不下
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.load(bad) }
        assertTrue(e.message!!.contains("指令"), e.message!!)
    }

    @Test
    fun `LINE_TABLE 条数与指令条数不符拒绝`() {
        val bad = ErbcWriter.encode(sampleProgram()).copyOf()
        val linesOff = ProgramLoader.probe(bad, null).offsets[ErbcFormat.SECTION_LINES]
        writeLe32(bad, linesOff + 4, 3)   // 实际指令条数不是 3
        val e = assertFailsWith<ErbcFormatException> { ProgramLoader.load(bad) }
        assertTrue(e.message!!.contains("行号表"), e.message!!)
    }

    @Test
    fun `文件被截断时报段长不足而不是越界异常`() {
        val bytes = ErbcWriter.encode(sampleProgram())
        for (cut in intArrayOf(1, 5, 20, 38, 60, bytes.size / 2, bytes.size - 1)) {
            // kotlin.test.assertFailsWith 没有 message 重载：循环上下文写进后续断言的 message
            val e = assertFailsWith<ErbcFormatException> {
                ProgramLoader.load(bytes.copyOf(cut), "truncated.erbc")
            }
            assertFalse(
                e.message!!.contains("IndexOutOfBounds"),
                "截断到 $cut 字节时抛了裸越界：${e.message}",
            )
        }
    }

    @Test
    fun `畸形输入一律收口为 ErbcFormatException（不许 IndexOutOfBounds 冒泡）`() {
        val good = ErbcWriter.encode(sampleProgram())
        val header = ProgramLoader.probe(good, null)
        // 段表每个槽位逐一指向文件尾之后 / 负值 / 极小值，外加随机篡改计数位
        for (slot in 0 until ErbcFormat.SECTION_COUNT) {
            for (v in intArrayOf(-1, 1, 37, good.size, good.size + 1000, Int.MIN_VALUE)) {
                val bad = good.copyOf()
                writeLe32(bad, ErbcFormat.HEADER_BYTES + slot * 4, v)
                val e = assertFailsWith<ErbcFormatException> {
                    ProgramLoader.load(bad)
                }
                assertTrue(
                    e is ErbcLoadException || e.javaClass.name.startsWith("com.eratw.port.core.code."),
                    "段 $slot 偏移 $v 抛了意外异常类型: ${e.javaClass.name}",
                )
            }
        }
        // 段正文首四个字节（计数位）逐一改成极大值
        for (sec in 0 until ErbcFormat.SECTION_COUNT) {
            val off = header.offsets[sec]
            if (off == 0) continue
            for (v in intArrayOf(Int.MAX_VALUE, -2, 1 shl 28)) {
                val bad = good.copyOf()
                writeLe32(bad, off, v)
                val e = assertFailsWith<ErbcFormatException> {
                    ProgramLoader.load(bad)
                }
                assertSame(
                    ErbcFormatException::class.java,
                    e.javaClass.superclass,
                    "段 $sec 计数 $v 抛的必须是 Erbc 格式族异常，实际 ${e.javaClass.name}",
                )
            }
        }
    }

    // ==================================================================
    // 反射逐字段比较（Statement/Argument 不是 data class，== 等价于 assertSame）
    // ==================================================================

    private fun assertGraphEquals(path: String, expected: Any?, actual: Any?) {
        if (expected === actual) return
        // 注意：JUnit 5 的 Assertions.fail 返回 void（不是 kotlin.test 的 Nothing），
        // 不产生 smart-cast —— 用局部 val 显式绑定非空，别依赖 fail 后的类型收窄。
        if (expected == null || actual == null) {
            fail<Unit>("$path 一侧为 null")
            return
        }
        val e = expected
        val a = actual
        // 容器类非语义：listOf(x)（SingletonList）与 ArrayList 值相等即可 ——
        // 与 main 上 ErbcRoundTripTest.assertGraphEqual 的 listPair 豁免同口径
        // （VarRef.indices 编译侧单元素走 SingletonList，ErbcReader 重建走 ArrayList）。
        val listPair = e is List<*> && a is List<*>
        if (e.javaClass != a.javaClass && !listPair) {
            fail<Unit>("$path 类不同: ${e.javaClass.simpleName} vs ${a.javaClass.simpleName}")
            return
        }
        when (e) {
            is Double -> assertEquals(e.toRawBits(), (a as Double).toRawBits(), "$path（Double raw bits）")
            is String, is Boolean, is Char, is Number, is Enum<*> -> assertEquals(e, a, path)
            is IntArray -> assertArrayEquals(e.toTypedArray(), (a as IntArray).toTypedArray(), path)
            is LongArray -> assertArrayEquals(e.toTypedArray(), (a as LongArray).toTypedArray(), path)
            is DoubleArray -> assertEquals(
                e.map { it.toRawBits() },
                (a as DoubleArray).map { it.toRawBits() },
                "$path（Double raw bits）",
            )
            is Array<*> -> {
                val other = a as Array<*>
                assertEquals(e.size, other.size, "$path（数组长度）")
                for (i in e.indices) assertGraphEquals("$path[$i]", e[i], other[i])
            }
            is List<*> -> {
                val other = a as List<*>
                assertEquals(e.size, other.size, "$path（列表长度）")
                for (i in e.indices) assertGraphEquals("$path[$i]", e[i], other[i])
            }
            else -> {
                val fields = instanceFields(e.javaClass)
                assertTrue(fields.isNotEmpty(), "$path 没有可比对的实例字段：${e.javaClass}")
                for (f in fields) {
                    f.isAccessible = true
                    assertGraphEquals("$path.${f.name}", f.get(e), f.get(a))
                }
            }
        }
    }

    private fun instanceFields(clazz: Class<*>): List<java.lang.reflect.Field> {
        val out = ArrayList<java.lang.reflect.Field>()
        var c: Class<*>? = clazz
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                if (f.isSynthetic) continue
                if (f.name.startsWith("$")) continue
                out.add(f)
            }
            c = c.superclass
        }
        return out
    }

    // ==================================================================
    // 字节小工具（与 ErbcSink 无关；测试自己按小端拼）
    // ==================================================================

    private fun writeLe16(dst: ByteArray, at: Int, value: Int) {
        dst[at] = (value and 0xFF).toByte()
        dst[at + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeLe32(dst: ByteArray, at: Int, value: Int) {
        dst[at] = (value and 0xFF).toByte()
        dst[at + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[at + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
