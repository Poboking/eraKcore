package com.eratw.port.core.loader

import com.eratw.port.boot.RealCorpus
import com.eratw.port.boot.RealCorpusCompile
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcEncodeOptions
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcHeader
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `.erbc` 映射直读（[ProgramLoader.loadMapped] / [MappedErbc]）与既有全读路径的**一致性**单测。
 *
 * 判据（CONTRACT-M4-H §2 h-mmap）：
 * 1. 同一份产物经 `loadFile` 与 `loadMapped` 得到的 `Program` **逐字段一致**
 *    —— `code`/`lines` 做**全量**数组比对（不是抽样），池条目计数 + 逐条内容、
 *    函数表 `entryPc` / 标签 pc 序列全部对齐；
 * 2. 畸形输入的**异常类型与消息文本**在两条路径上逐字相同（差分矩阵覆盖
 *    `ProgramLoaderTest` 的全部畸形面：magic / 版本 / 段数 / 标志位 / 标志⇔段表 /
 *    段偏移落头内 / 两段同偏移 / 计数撒谎 / 截断 / 段表逐槽篡改）；
 * 3. 惰性段读数（[MappedErbc.readCode] / [MappedErbc.readLines] /
 *    [MappedErbc.stringTable]）与已装载 `Program` 的对应字段逐字相等；
 * 4. 内存模型：大段字节数 = 20 字节/指令（CODE 16 + LINE_TABLE 4）+ 两个计数位。
 *
 * 真语料用例只在 `ERATW_CORPUS` 存在时运行（缺变量即跳过；禁硬编码共享根路径）。
 */
class MmapLoaderTest {

    // ==================================================================
    // 样题
    // ==================================================================

    /** 覆盖面优先的小样本：空串 / 多字节 UTF-8 / 负常量 / 表达式池 / 语句池 / 标签表。 */
    private fun sampleProgram(): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 1, 3)
        val c0 = b.constant(42L)
        val c1 = b.constant(Long.MIN_VALUE)
        val s0 = b.string("こんにちは，世界")
        val s1 = b.string("")
        val s2 = b.string("line\r\nbreak")
        b.emit(Opcode._SET_INT, SlotAddr.local(0), c0, 0, 1)
        b.emit(Opcode._SET_INT, SlotAddr.global(1), c1, 0, 1)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s0, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s1, 0, 2)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), s2, 0, 3)
        b.emit(Opcode._EVAL_INT, b.expr(IntLiteral(7L, 1, 2)), SlotAddr.local(1), 0, 4)
        b.emit(
            Opcode._EVAL_STR,
            b.expr(
                Binary(
                    OperatorCode.PLUS,
                    IntLiteral(1L, 3, 1),
                    VarRef("LV", emptyList(), operandType = OperandType.INT),
                    OperandType.INT,
                    3,
                    3,
                ),
            ),
            SlotAddr.local(1),
            0,
            5,
        )
        b.emit(Opcode.WAIT, b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), 10, "WAIT")), 0, SlotAddr.NONE, 10)
        b.emit(Opcode.DATA, b.statement(DataStmt(FunctionCode.DATA, "数据", VoidArgument(), 11, "DATA 数据")), 0, SlotAddr.NONE, 11)
        b.declareLabel(main, "TAIL", b.here())
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        val sub = b.declareFunction("sub", b.here(), 2, 4)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 20)
        b.declareLabel(sub, "INNER", b.here() - 1)
        b.setFunctionSlots(sub, 4)
        b.noteGlobalSlot(1)
        return b.build(0)
    }

    /** 量级样本：让「全量比对」真的有量可pin（指令数可参数化）。 */
    private fun bigProgram(instructions: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val strings = (0 until 300).map { b.string("str-$it-驻留") }
        val consts = (0 until 50).map { b.constant(it.toLong() * 1_000_003L) }
        val stmts = (0 until 120).map {
            b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), it + 1, "WAIT  #${it}"))
        }
        var i = 0
        while (i < instructions) {
            when (i % 5) {
                0 -> b.emit(Opcode._SET_INT, SlotAddr.local(0), consts[i % consts.size], 0, i % 97)
                1 -> b.emit(Opcode._SET_STR, SlotAddr.local(1), strings[i % strings.size], 0, i % 97)
                2 -> b.emit(Opcode._PRINT_LITERAL, strings[(i * 7) % strings.size], 0, SlotAddr.NONE, i % 97)
                3 -> b.emit(Opcode._NOP, 0, 0, 0, -1)
                else -> b.emit(Opcode.WAIT, stmts[i % stmts.size], 0, SlotAddr.NONE, i % 97)
            }
            i++
        }
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    private fun writeArtifact(
        dir: Path,
        bytes: ByteArray,
        name: String = "program.erbc",
    ): Path {
        val f = dir.resolve(name)
        Files.write(f, bytes)
        return f
    }

    // ==================================================================
    // 1. 逐字段一致（全量）
    // ==================================================================

    /**
     * 逐字段比对的**唯一**实现：`code`/`lines` 用全量数组比对，四个池按条目遍历，
     * 函数表按 `entryPc` 与标签 pc 序列比对。
     */
    private fun assertProgramsIdentical(
        expected: Program,
        actual: Program,
        ctx: String,
    ) {
        assertContentEquals(expected.code, actual.code, "$ctx：code 全量 ${expected.code.size} 字")
        assertContentEquals(expected.lines, actual.lines, "$ctx：lines 全量 ${expected.lines.size} 行")
        assertEquals(expected.instructionCount, actual.instructionCount, "$ctx：指令条数")
        assertEquals(expected.globalSlots, actual.globalSlots, "$ctx：全局槽")
        assertEquals(expected.entryFunction, actual.entryFunction, "$ctx：入口函数")
        assertEquals(expected.constantCount, actual.constantCount, "$ctx：常量池条目数")
        for (i in 0 until expected.constantCount) {
            assertEquals(expected.constant(i), actual.constant(i), "$ctx：常量池 $i")
        }
        assertEquals(expected.stringCount, actual.stringCount, "$ctx：字符串池条目数")
        for (i in 0 until expected.stringCount) {
            assertEquals(expected.string(i), actual.string(i), "$ctx：字符串池 $i")
        }
        assertEquals(expected.exprCount, actual.exprCount, "$ctx：表达式池条目数")
        for (i in 0 until expected.exprCount) {
            assertEquals(expected.expr(i), actual.expr(i), "$ctx：表达式池 $i")
        }
        assertEquals(expected.statementCount, actual.statementCount, "$ctx：语句池条目数")
        for (i in 0 until expected.statementCount) {
            val e = expected.statement(i)
            val a = actual.statement(i)
            assertEquals(e.javaClass, a.javaClass, "$ctx：语句池 $i 类")
            assertEquals(e.code, a.code, "$ctx：语句池 $i 的 FunctionCode")
            assertEquals(e.line, a.line, "$ctx：语句池 $i 行号")
            assertEquals(e.raw, a.raw, "$ctx：语句池 $i 原文")
        }
        assertEquals(expected.functions.size, actual.functions.size, "$ctx：函数表长度")
        assertContentEquals(
            IntArray(expected.functions.size) { expected.functions[it].entryPc },
            IntArray(actual.functions.size) { actual.functions[it].entryPc },
            "$ctx：entryPc 序列",
        )
        for (i in expected.functions.indices) {
            val e = expected.functions[i]
            val a = actual.functions[i]
            assertEquals(e.name, a.name, "$ctx：函数 $i 名")
            assertEquals(e.arity, a.arity, "$ctx：函数 ${e.name} arity")
            assertEquals(e.slotCount, a.slotCount, "$ctx：函数 ${e.name} slotCount")
            assertEquals(e.labelNames.toList(), a.labelNames.toList(), "$ctx：函数 ${e.name} 标签名")
            assertEquals(e.labelPcs.toList(), a.labelPcs.toList(), "$ctx：函数 ${e.name} 标签 pc")
            assertEquals(e.paramNames.toList(), a.paramNames.toList(), "$ctx：函数 ${e.name} 头部形参名（v2 段 8）")
        }
        assertEquals(expected.callArgs, actual.callArgs, "$ctx：调用点实参表（v2 段 7）")
        assertEquals(expected.callArgSource, actual.callArgSource, "$ctx：实参元数据来源")
    }

    @Test
    fun `loadMapped 与 loadFile 的 Program 逐字段一致（小样本）`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(sampleProgram())
        val f = writeArtifact(dir, bytes)
        assertProgramsIdentical(ProgramLoader.loadFile(f), ProgramLoader.loadMapped(f), "小样本")
    }

    @Test
    fun `loadMapped 与 loadFile 的 Program 逐字段一致（八千条指令全量）`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(bigProgram(8_000))
        val f = writeArtifact(dir, bytes)
        val a = ProgramLoader.loadFile(f)
        val b = ProgramLoader.loadMapped(f)
        assertEquals(8_001, a.instructionCount, "样本指令数（含收尾 _HALT）")
        assertProgramsIdentical(a, b, "全量 8001 条 / code ${a.code.size} 字")
        assertTrue(a.code.size == 4 * a.instructionCount, "code 数组长度 = 4 字/条")
    }

    @Test
    fun `尾部填充产物两条路径同样一致（规约 §1_5）`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(sampleProgram()) + ByteArray(41) { 0x5A }
        val f = writeArtifact(dir, bytes)
        assertProgramsIdentical(ProgramLoader.loadFile(f), ProgramLoader.loadMapped(f), "尾部填充")
    }

    // ==================================================================
    // 2. 惰性段读数 == 已装载 Program 的对应字段
    // ==================================================================

    @Test
    fun `readCode 与 readLines 逐字等于 Program 的两个大数组`(@TempDir dir: Path) {
        val f = writeArtifact(dir, ErbcWriter.encode(bigProgram(3_000)))
        val p = ProgramLoader.loadFile(f)
        ProgramLoader.mapped(f).use { m ->
            assertEquals(p.instructionCount, m.instructionCount(), "段表读出的指令条数")
            assertContentEquals(p.code, m.readCode(), "readCode 全量 ${p.code.size} 字")
            assertContentEquals(p.lines, m.readLines(), "readLines 全量 ${p.lines.size} 行")
        }
    }

    @Test
    fun `不带行号段的产物 readLines 全 -1 且与装载结果同口径（规约 §3）`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(sampleProgram(), ErbcEncodeOptions(includeLines = false))
        val f = writeArtifact(dir, bytes)
        val p = ProgramLoader.loadFile(f)
        assertTrue(p.lines.all { it == -1 }, "缺 LINE_TABLE 时 lines 应整表 -1")
        ProgramLoader.mapped(f).use { m ->
            assertFalse(m.isPresent(ErbcFormat.SECTION_LINES), "flags 未置 HAS_LINES ⇒ 段表偏移应为 0")
            assertContentEquals(p.lines, m.readLines(), "readLines 与 Program.lines")
        }
        assertProgramsIdentical(p, ProgramLoader.loadMapped(f), "无行号段")
    }

    @Test
    fun `字符串池段的惰性视图逐条等于常驻池`(@TempDir dir: Path) {
        val f = writeArtifact(dir, ErbcWriter.encode(bigProgram(1_200)))
        val p = ProgramLoader.loadFile(f)
        ProgramLoader.mapped(f).use { m ->
            val t = m.stringTable(ErbcFormat.SECTION_STRINGS)
            assertEquals(p.stringCount, t.count, "惰性视图条目数 == 池条目数")
            for (i in 0 until t.count) {
                assertEquals(p.string(i), t[i], "字符串池 $i（惰性解码 vs 常驻池）")
            }
            assertEquals(t.materialize(), (0 until p.stringCount).map { p.string(it) }, "全量物化顺序")
        }
    }

    @Test
    fun `段区间与内存模型：大段字节数 = 20 每指令 + 计数位`(@TempDir dir: Path) {
        val n = 5_000
        val f = writeArtifact(dir, ErbcWriter.encode(bigProgram(n)))
        ProgramLoader.mapped(f).use { m ->
            val instr = m.instructionCount()
            val codeRange = requireNotNull(m.sectionRange(ErbcFormat.SECTION_CODE))
            val linesRange = requireNotNull(m.sectionRange(ErbcFormat.SECTION_LINES))
            val codeBytes = codeRange.last - codeRange.first + 1L
            val lineBytes = linesRange.last - linesRange.first + 1L
            // 规约 §5.6：u32 计数 + 4×u32/条（CODE 是段号 5，其后只有 LINE_TABLE ⇒ 区间精确）
            assertEquals(4L + 16L * instr, codeBytes, "CODE 段区间必须精确（下一段起点即本段上界）")
            // LINE_TABLE 之后只允许 assets 对齐填充（规约 §1.5），因此取下界
            assertTrue(lineBytes >= 8L + 4L * instr, "LINE_TABLE 区间 $lineBytes < 下界 ${8L + 4L * instr}")
            val big = (codeBytes - 4L) + (lineBytes - 8L)
            assertTrue(big >= 20L * instr, "每指令 20 字节（16 code + 4 line）实得 $big")
            assertTrue(m.byteSize.toLong() >= big, "产物总字节必 >= 大段合计：${m.byteSize} vs $big")
        }
    }

    // ==================================================================
    // 3. probe 面：段表读数 + 畸形差分
    // ==================================================================

    @Test
    fun `probeMapped 与 probeFile 段表逐槽相等（正样例）`(@TempDir dir: Path) {
        val f = writeArtifact(dir, ErbcWriter.encode(sampleProgram()))
        val a = ProgramLoader.probeFile(f)
        val b = ProgramLoader.probeMapped(f)
        assertEquals(a.offsets.toList(), b.offsets.toList(), "段表 7 槽")
        assertEquals(a.flags, b.flags, "flags")
        assertEquals(a.version, b.version, "version")
        assertEquals(a.sectionCount, b.sectionCount, "sectionCount")
        ProgramLoader.mapped(f).use { m ->
            for (i in 0 until a.sectionCount) assertEquals(a.offsets[i], m.offsetOf(i), "段 $i 偏移")
        }
    }

    /** 把 `ProgramLoaderTest` 的畸形面在**两条路径**上各跑一遍，要求异常类型与消息逐字相同。 */
    private fun differential(
        dir: Path,
        name: String,
        bytes: ByteArray,
    ) {
        val f = writeArtifact(dir, bytes, name)
        val fullProbe = capture { ProgramLoader.probeFile(f) }
        val mapProbe = capture { ProgramLoader.probeMapped(f) }
        assertEquals(fullProbe, mapProbe, "$name：probe 面两条路径必须逐字同（类型 + 消息）")

        val fullLoad = capture { ProgramLoader.loadFile(f) }
        val mapLoad = capture { ProgramLoader.loadMapped(f) }
        if (fullLoad.startsWith("ok:")) {
            assertTrue(mapLoad.startsWith("ok:"), "$name：全读成功而映射失败 ⇒ $mapLoad")
            assertProgramsIdentical(ProgramLoader.loadFile(f), ProgramLoader.loadMapped(f), name)
        } else {
            assertEquals(fullLoad, mapLoad, "$name：load 面两条路径必须逐字同（类型 + 消息）")
        }
    }

    private inline fun capture(block: () -> Any?): String =
        try {
            "ok:${block()?.let { if (it is Program) "program:${it.instructionCount}" else it.toString() } ?: "null"}"
        } catch (e: Throwable) {
            "err:${e.javaClass.name}:${e.message}"
        }

    @Test
    fun `畸形输入面差分：异常类型与消息在两条路径逐字相同`(@TempDir dir: Path) {
        val good = ErbcWriter.encode(sampleProgram())
        differential(dir, "正样例", good)
        differential(dir, "空文件", ByteArray(0))
        differential(dir, "9 字节", ByteArray(9))
        differential(dir, "20 字节截断", good.copyOf(20))
        differential(dir, "37 字节截断", good.copyOf(37))
        differential(dir, "尾部填充", good + ByteArray(37) { 0x5A })

        val magicBad = good.copyOf().also { it[0] = 'X'.code.toByte() }
        differential(dir, "magic 不符", magicBad)
        differential(dir, "版本 3", good.copyOf().also { writeLe16(it, 4, 3) })
        differential(dir, "段数 8", good.copyOf().also { writeLe16(it, 8, 8) })
        differential(dir, "未定义标志位", good.copyOf().also { writeLe16(it, 6, ErbcFormat.FLAG_HAS_LINES or 0x0100) })
        differential(
            dir,
            "声明有语句池却写 0 偏移",
            good.copyOf().also { writeLe32(it, ErbcFormat.HEADER_BYTES + ErbcFormat.SECTION_STATEMENTS * 4, 0) },
        )
        differential(
            dir,
            "段偏移落进段表",
            good.copyOf().also { writeLe32(it, ErbcFormat.HEADER_BYTES + ErbcFormat.SECTION_FUNCTIONS * 4, 20) },
        )

        // 段表逐槽指向：负 / 极小 / 文件尾 / 尾后 / Int.MIN_VALUE
        var slot = 0
        while (slot < ErbcFormat.SECTION_COUNT) {
            for (v in intArrayOf(-1, 1, 37, good.size, good.size + 1000, Int.MIN_VALUE)) {
                val bad = good.copyOf()
                writeLe32(bad, ErbcFormat.HEADER_BYTES + slot * 4, v)
                differential(dir, "段槽 $slot = $v", bad)
            }
            slot++
        }
        // 两段声明同一偏移
        val sameOff = good.copyOf().also {
            writeLe32(it, ErbcFormat.HEADER_BYTES + ErbcFormat.SECTION_FUNCTIONS * 4, ProgramLoader.probe(good, null).offsets[ErbcFormat.SECTION_CODE])
        }
        differential(dir, "两段同偏移", sameOff)
        // CODE 计数撒谎 / LINE_TABLE 条数不符 / 行号表带文件 id
        val codeOff = ProgramLoader.probe(good, null).offsets[ErbcFormat.SECTION_CODE]
        differential(dir, "CODE 条数 9999", good.copyOf().also { writeLe32(it, codeOff, 9_999) })
        val linesOff = ProgramLoader.probe(good, null).offsets[ErbcFormat.SECTION_LINES]
        differential(dir, "LINE_TABLE 条数 3", good.copyOf().also { writeLe32(it, linesOff + 4, 3) })
        differential(dir, "LINE_TABLE 带文件 id", good.copyOf().also { writeLe32(it, linesOff, 1) })
        // 各段计数位改成极大值
        val header = ProgramLoader.probe(good, null)
        for (sec in 0 until ErbcFormat.SECTION_COUNT) {
            val off = header.offsets[sec]
            if (off == 0) continue
            for (v in intArrayOf(Int.MAX_VALUE, -2, 1 shl 28)) {
                differential(dir, "段 $sec 计数 $v", good.copyOf().also { writeLe32(it, off, v) })
            }
        }
        // 截断
        for (cut in intArrayOf(1, 5, 10, 11, 37, 38, 60, good.size / 2, good.size - 1)) {
            differential(dir, "截断到 $cut", good.copyOf(cut))
        }
    }

    // ==================================================================
    // 4. 生命周期与延迟句柄
    // ==================================================================

    @Test
    fun `close 之后取段数据明确报错而不是读到悬空内存`(@TempDir dir: Path) {
        val f = writeArtifact(dir, ErbcWriter.encode(sampleProgram()))
        val m = ProgramLoader.mapped(f)
        assertTrue(m.readCode().isNotEmpty())
        m.close()
        assertTrue(m.isClosed)
        val e = assertFailsWith<ErbcLoadException> { m.readCode() }
        assertTrue(e.message!!.contains("映射已关闭"), e.message!!)
        assertFailsWith<ErbcLoadException> { m.u32At(0) }
        m.close() // 幂等
    }

    @Test
    fun `deferredMapped 未物化时不解码，物化后与 loadFile 一致`(@TempDir dir: Path) {
        val f = writeArtifact(dir, ErbcWriter.encode(bigProgram(2_000)))
        val expect = ProgramLoader.loadFile(f)
        ProgramLoader.deferredMapped(f).use { s ->
            assertFalse(s.isMaterialized, "刚打开就物化 ⇒ 延迟失效")
            assertEquals(expect.instructionCount, s.instructionCount(), "段表读数不需要物化")
            val first = s.program()
            assertTrue(s.isMaterialized)
            assertSame(first, s.program(), "第二次取必须同一实例（语句池以实例为键）")
            assertProgramsIdentical(expect, first, "延迟物化")
        }
        // 目录清单：只 enumerate + probe，不物化
        val root = dir.resolve("set")
        Files.createDirectories(root)
        val bytes = ErbcWriter.encode(sampleProgram())
        Files.write(root.resolve("a.erbc"), bytes)
        Files.write(root.resolve("b.erbc"), bytes)
        val sources = ProgramLoader.deferredDirectory(root)
        assertEquals(2, sources.size)
        assertTrue(sources.none { it.isMaterialized }, "清单阶段不得物化")
        assertEquals(bytes.size, sources.first().byteSize)
        sources.forEach { it.close() }
    }

    @Test
    fun `映射路径不改变既有 loadFile 的读数（回归护栏）`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(sampleProgram())
        val f = writeArtifact(dir, bytes)
        assertProgramsIdentical(ProgramLoader.load(bytes, f.toString()), ProgramLoader.loadFile(f), "字节入口 vs 文件入口")
        assertNotEquals(0, ProgramLoader.artifactFiles(dir).size, "目录清单仍认 .erbc 后缀")
    }

    // ==================================================================
    // 5. 真语料（ERATW_CORPUS）
    // ==================================================================

    @Test
    fun `真语料产物：映射路径与全读路径逐字段一致`(@TempDir dir: Path) {
        val corpus = System.getenv("ERATW_CORPUS")
        assumeTrue(!corpus.isNullOrBlank() && Files.isDirectory(Path.of(corpus)), "ERATW_CORPUS 未设置或目录不存在：跳过")
        val c = RealCorpus.load()
        assumeTrue(c != null, "语料未定位：跳过")
        val model = RealCorpusCompile.compile(c!!, 40)
        val prog = model.program
        assumeTrue(prog != null, "语料前 40 个文件未产出 Program：跳过")
        val bytes = ErbcWriter.encode(prog!!)
        val f = writeArtifact(dir, bytes, "corpus40.erbc")
        val a = ProgramLoader.loadFile(f)
        val b = ProgramLoader.loadMapped(f)
        assertProgramsIdentical(a, b, "真语料 40 文件产物（${bytes.size} 字节 / ${a.instructionCount} 指令）")
        ProgramLoader.mapped(f).use { m ->
            assertContentEquals(a.code, m.readCode(), "真语料 code 全量 ${a.code.size} 字")
            assertContentEquals(a.lines, m.readLines(), "真语料 lines 全量 ${a.lines.size} 行")
            val t = m.stringTable(ErbcFormat.SECTION_STRINGS)
            assertEquals(a.stringCount, t.count, "真语料字符串池条目数")
            for (i in 0 until t.count step maxOf(1, t.count / 64)) {
                assertEquals(a.string(i), t[i], "真语料字符串池 $i")
            }
        }
        // 内存模型的语料读数（打印给交付报告用，不做绝对值断言）
        val big = 20L * a.instructionCount
        println(
            "[mmap-corpus] 产物 ${bytes.size} B / 指令 ${a.instructionCount} / " +
                "大段模型 ${big} B（CODE ${16L * a.instructionCount} + LINE ${4L * a.instructionCount}）/ " +
                "池：串 ${a.stringCount} 常量 ${a.constantCount} 表达式 ${a.exprCount} 语句 ${a.statementCount}",
        )
    }

    // ==================================================================
    // 小工具（测试自己按小端拼，与 ErbcSink 无关）
    // ==================================================================

    private fun writeLe16(
        dst: ByteArray,
        at: Int,
        value: Int,
    ) {
        dst[at] = (value and 0xFF).toByte()
        dst[at + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeLe32(
        dst: ByteArray,
        at: Int,
        value: Int,
    ) {
        dst[at] = (value and 0xFF).toByte()
        dst[at + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[at + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[at + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
