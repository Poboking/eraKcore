package com.eratw.port.core.code

import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [CodeSource] 与行号表紧凑表示（M6-CODE-VIEW）的语义等价测试。
 *
 * 换取指方式属于性能改动，判据只有两条：
 *
 * 1. **逐字等价**：`IntArray` 来源与只读映射来源对同一份字节给出逐下标相同的字，
 *    因此 `op/a/b/c`、`instructionAt`、`lineOf` 对每个 pc 都相同；
 * 2. **行为等价**：同一个 `CodeBuilder` 产物换成映射来源（含把页缩到 2 条指令的一页，
 *    逼出跨页取指）后，`VirtualMachine.run` 的返回值与输出逐字相同。
 *
 * 行号表同样只换表示：u16 主表 + 溢出表必须对每个下标返回入参 `IntArray` 的**原值**
 * （含 -1 哨兵与越界回落 -1 的旧口径）。
 */
class CodeSourceViewTest {

    // ------------------------------------------------------------------
    // CodeSource：IntArray 零拷贝视图
    // ------------------------------------------------------------------

    @Test
    fun `IntArray 来源整段就是一页且返回原数组本体`() {
        val words = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val src = CodeSource.of(words)

        assertEquals(8, src.wordCount, "字数 = 数组长度")
        assertEquals(8, src.pageWords, "整段一页：页字数 = 全段字数")
        assertEquals(1, src.pageCount, "整段一页")
        assertSame(words, src.page(0), "页就是原数组本体（零拷贝，VM 取指与改造前同一块内存）")
        for (i in words.indices) assertEquals(words[i], src.word(i), "第 $i 字逐字相同")
        assertContentEquals(words, src.toIntArray(), "物化视图逐字相同")
    }

    @Test
    fun `长度非四的整数倍时退化分页且一条指令不跨页`() {
        // 只可能出现在手工桩里：6 字不是 4 的倍数 ⇒ 不能用「整段一页」，按默认页长切片。
        val words = intArrayOf(1, 2, 3, 4, 5, 6)
        val src = CodeSource.of(words)

        assertEquals(CodeSource.DEFAULT_PAGE_WORDS, src.pageWords, "非 4 整数倍 → 默认页长（4 的倍数）")
        assertEquals(0, src.pageWords % Instruction.WORDS, "页字数必须是 4 的倍数")
        assertContentEquals(words, src.page(0), "首（也是唯一）页逐字相同")
        assertContentEquals(words, src.toIntArray(), "物化视图逐字相同")
        for (i in words.indices) assertEquals(words[i], src.word(i), "第 $i 字逐字相同")
    }

    // ------------------------------------------------------------------
    // CodeSource：只读映射页
    // ------------------------------------------------------------------

    @Test
    fun `只读映射来源逐字读出同一段字节且尾页可短`(@TempDir dir: Path) {
        val words = IntArray(16) { 0x01020304 * (it + 1) } // 含负值与高位，验证小端读回
        val file = writeWords(dir, words)
        FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, Files.size(file))
            // 页 3 条指令 = 12 字 ⇒ 16 字分两页，第二页只有 4 个字。
            val src = MappedCodeSource(mapped, words.size, pageWords = 3 * Instruction.WORDS)

            assertEquals(words.size, src.wordCount, "字数 = 段字数")
            assertEquals(2, src.pageCount, "16 字 / 12 字一页 = 2 页")
            assertEquals(12, src.page(0).size, "首页满页")
            assertEquals(4, src.page(1).size, "尾页短：只剩末 4 字")
            for (i in words.indices) assertEquals(words[i], src.word(i), "第 $i 字小端读回逐字相同")
            assertContentEquals(words, src.toIntArray(), "物化视图逐字相同")
            assertSame(src.page(1), src.page(1), "同一页重复取命中页缓存（不重复复制）")
            assertTrue(src.page(0) !== src.page(1), "不同页各自一页（缓存不串页）")
        }
    }

    @Test
    fun `映射窗口不足时构造即失败而不是读到段外`(@TempDir dir: Path) {
        val words = intArrayOf(1, 2, 3, 4)
        val file = writeWords(dir, words)
        FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, Files.size(file))
            val e = assertFailsWith<IllegalArgumentException>("窗口 16 B 装不下 8 字") {
                MappedCodeSource(mapped, wordCount = 8, pageWords = 8)
            }
            assertTrue(e.message!!.contains("映射窗口不足"), "屏上原样文案：${e.message}")
        }
    }

    @Test
    fun `页字数不是四的倍数时拒绝构造`(@TempDir dir: Path) {
        val file = writeWords(dir, intArrayOf(1, 2, 3, 4))
        FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, Files.size(file))
            val e = assertFailsWith<IllegalArgumentException> {
                MappedCodeSource(mapped, wordCount = 4, pageWords = 3)
            }
            assertTrue(e.message!!.contains("整数倍"), "一条指令不许跨页：${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Program：兼容视图的身份与行号表
    // ------------------------------------------------------------------

    @Test
    fun `数组来源下 code 身份不变且 lines 身份稳定`() {
        val words = intArrayOf(Opcode._HALT, 0, 0, 0)
        val lines = intArrayOf(7)
        val p = arrayProgram(words, lines)

        assertSame(words, p.code, "code 仍是入参数组本体（不复制第二份指令流）")
        assertSame(p.lines, p.lines, "lines 兼容视图身份稳定（getOrElse 口径的实例不变）")
        assertContentEquals(lines, p.lines, "lines 逐字相同")
        assertEquals(1, p.instructionCount, "指令条数 = 字数 / 4")
        assertTrue(p.codeResidentBytes > 0, "数组来源常驻字节非零：${p.codeResidentBytes}")
    }

    @Test
    fun `行号表对全部哨兵与越界返回原值`() {
        val lines = intArrayOf(
            -1, 0, 1, 12, 65533, 65534, 65535, 1_000_000, -2, Int.MAX_VALUE, Int.MIN_VALUE, -1,
        )
        val words = IntArray(lines.size * Instruction.WORDS)
        val p = arrayProgram(words, lines)

        assertContentEquals(lines, p.lines, "物化视图逐字回放（含 -1 / 65534 / 超 u16 / 负数哨兵）")
        for (i in lines.indices) assertEquals(lines[i], p.lineOf(i), "第 $i 条行号原值")
        assertEquals(-1, p.lineOf(-1), "负下标回落 -1（旧 getOrElse 口径）")
        assertEquals(-1, p.lineOf(lines.size), "上界外回落 -1（旧 getOrElse 口径）")
    }

    @Test
    fun `常规行号表的常驻是 2 B 每条而不是 4 B`() {
        // 全量语料的常态：行号都在 u16 直接段内（无溢出项），因此常驻 = 3 个数组头 + 2 B/条。
        val n = 20_000
        val lines = IntArray(n) { it % 5000 }
        val words = IntArray(n * Instruction.WORDS)
        val p = arrayProgram(words, lines)

        assertEquals(3 * 16L + n * 2L, p.lineTableResidentBytes, "无溢出项时 = 数组头 + 2 B/条")
        val saving = n * 4L - p.lineTableResidentBytes
        assertTrue(saving > n * 2L - 64L, "相对 IntArray 总省约 2 B/条（总省 $saving B，只差数组头）")
        assertEquals(n, p.lines.size, "条目数不变")
        assertEquals(4999, p.lineOf(4999), "行号原值（回绕点）")
        assertEquals(0, p.lineOf(5000), "行号原值（回绕点后一条）")
    }

    // ------------------------------------------------------------------
    // VM：映射来源 + 跨页取指的行为等价
    // ------------------------------------------------------------------

    @Test
    fun `映射来源与数组来源的取指解码逐 pc 相同`(@TempDir dir: Path) {
        val words = countingLoop()
        val lines = IntArray(words.size / Instruction.WORDS) { 100 + it }
        val arrayP = arrayProgram(words, lines)

        withMappedProgram(dir, words, lines, pageWords = 2 * Instruction.WORDS) { mappedP ->
            assertEquals(arrayP.instructionCount, mappedP.instructionCount, "指令条数相同")
            for (pc in 0 until arrayP.instructionCount) {
                assertEquals(arrayP.op(pc), mappedP.op(pc), "op@pc$pc")
                assertEquals(arrayP.a(pc), mappedP.a(pc), "a@pc$pc")
                assertEquals(arrayP.b(pc), mappedP.b(pc), "b@pc$pc")
                assertEquals(arrayP.c(pc), mappedP.c(pc), "c@pc$pc")
                assertEquals(arrayP.instructionAt(pc), mappedP.instructionAt(pc), "解码@pc$pc")
                assertEquals(arrayP.lineOf(pc), mappedP.lineOf(pc), "行号@pc$pc（marker 热读同源）")
            }
            assertContentEquals(arrayP.code, mappedP.code, "物化视图逐字相同")
        }
    }

    @Test
    fun `映射来源下 VM 跨页取指的执行结果与输出逐字相同`(@TempDir dir: Path) {
        val words = countingLoop()
        val lines = IntArray(words.size / Instruction.WORDS) { 1 + it }
        val arrayP = arrayProgram(words, lines)

        val arrayHost = StringHost()
        val arrayResult = VirtualMachine(arrayP, arrayHost).run(0)

        // 页 = 2 条指令：每一轮循环都跨页（回边 pc4 → pc2），逼出真正的「按页取指」路径。
        withMappedProgram(dir, words, lines, pageWords = 2 * Instruction.WORDS) { mappedP ->
            val mappedHost = StringHost()
            val mappedResult = VirtualMachine(mappedP, mappedHost).run(0)
            assertEquals(arrayResult, mappedResult, "返回值相同")
            assertEquals(arrayHost.output.toString(), mappedHost.output.toString(), "输出逐字相同")
            assertEquals("0\n", mappedHost.output.toString(), "屏上原样文案")
            assertEquals(0L, mappedP.codeResidentBytes, "映射来源的指令流常驻堆字节 = 0")
        }
    }

    // ------------------------------------------------------------------
    // 桩
    // ------------------------------------------------------------------

    /**
     * 计数循环（7 条指令，含一条跨页回边）：
     *
     * ```
     * pc0 _SET_INT R0, #0(=4)
     * pc1 _SET_INT R1, #1(=1)
     * pc2 _SUB     R0, R0, R1
     * pc3 _LT      R3, R4, R0      ; R4 未写过 = 0
     * pc4 _JUMP_IF_TRUE R3, -3     ; R0 > 0 时回到 pc2
     * pc5 _PRINT_INT R0, 1         ; 结束时 R0 = 0
     * pc6 _HALT
     * ```
     */
    private fun countingLoop(): IntArray = intArrayOf(
        Opcode._SET_INT, 0, 0, 0,
        Opcode._SET_INT, 1, 1, 0,
        Opcode._SUB, 0, 0, 1,
        Opcode._LT, 3, 4, 0,
        Opcode._JUMP_IF_TRUE, 3, -3, 0,
        Opcode._PRINT_INT, 0, 1, 0,
        Opcode._HALT, 0, 0, 0,
    )

    /** 数组来源的 Program；常量池顺序与 [countingLoop] 的 `#0/#1` 一致。 */
    private fun arrayProgram(words: IntArray, lines: IntArray): Program = Program(
        code = words,
        lines = lines,
        pool = constantPoolFor(words, lines),
        functions = listOf(FunctionInfo("MAIN", entryPc = 0, arity = 0, slotCount = 8)),
        entryFunction = 0,
        globalSlots = 0,
    )

    /** 常量池：`#0 = 4`、`#1 = 1`（[countingLoop] 只引用这两个；其余桩不读池）。 */
    private fun constantPoolFor(words: IntArray, lines: IntArray): ConstantPool {
        val pool = ConstantPool()
        val instructions = minOf(words.size, lines.size * Instruction.WORDS) / Instruction.WORDS
        var maxConst = -1
        for (pc in 0 until instructions) {
            if (words[pc * Instruction.WORDS] == Opcode._SET_INT) {
                maxConst = maxOf(maxConst, words[pc * Instruction.WORDS + 2])
            }
        }
        val values = intArrayOf(4, 1)
        for (i in 0..maxConst) pool.addLong(values.getOrElse(i) { 0 }.toLong())
        return pool
    }

    /** 把字流按 `ErbcSink.u32` 的口径（小端）落盘并映射成 Program。 */
    private fun withMappedProgram(
        dir: Path,
        words: IntArray,
        lines: IntArray,
        pageWords: Int,
        body: (Program) -> Unit,
    ) {
        val file = writeWords(dir, words)
        FileChannel.open(file, StandardOpenOption.READ).use { ch ->
            val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0, Files.size(file))
            val source = MappedCodeSource(mapped, words.size, pageWords)
            val program = Program(
                codeSource = source,
                lines = lines,
                pool = constantPoolFor(words, lines),
                functions = listOf(FunctionInfo("MAIN", entryPc = 0, arity = 0, slotCount = 8)),
                entryFunction = 0,
                globalSlots = 0,
            )
            body(program)
        }
    }

    private fun writeWords(dir: Path, words: IntArray): Path {
        val bytes = ByteArray(words.size * 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(words)
        val file = dir.resolve("code-${words.size}-${words.contentHashCode()}.bin")
        Files.write(file, bytes)
        return file
    }
}
