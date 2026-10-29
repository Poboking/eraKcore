package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.stmt.DataStmt
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimSpec
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 按段流式解码（[ErbcStreamDecoder]，经 [ProgramLoader.loadMapped] / [MappedErbc.toProgram]）
 * 与整文件全读（[ProgramLoader.loadFile]）的**等价性**单测。
 *
 * 判据（CONTRACT-M6-A §2.1）：
 * 1. 自造小产物：两条路径的 `Program` 逐字段一致（`code`/`lines` 全量 + 四个池 + 函数表）；
 * 2. **记录区逐字节变异差分**：把同一批畸形字节分别喂给两条路径，异常类型与消息
 *    （以及「都成功」时的 Program 全量内容）必须逐字相同 —— 这条是「`RecordSource` 是
 *    `ErbcReader` 逐字副本」的运行时证据，`MmapLoaderTest` 的差分矩阵大多死在段布局层，
 *    到不了记录解码；
 * 3. 真实产物（`ERATW_ERBC` 指向 `program.erbc` 时）：指令数 / lines / 函数 / 池计数逐项相等，
 *    `code`/`lines` 全量内容哈希相等、池抽样内容相等 —— 一次只驻留一个 Program（3g 堆），
 *    按项抽样足以在计数相同的前提下钉住「字节读歪」这类分歧；
 * 4. 侧车 `BootMetadata.read`（按行流式）与 `parse(整文)` 对同一份文本逐字段一致，
 *    包括 CRLF / 孤立 `\\r` / 无尾换行 / 空行 / 尾部空白行。
 */
class ErbcStreamEquivalenceTest {

    // ------------------------------------------------------------------
    // 1. 自造小产物：逐字段一致
    // ------------------------------------------------------------------

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
        for (i in expected.functions.indices) {
            val e = expected.functions[i]
            val a = actual.functions[i]
            assertEquals(e.name, a.name, "$ctx：函数 $i 名")
            assertEquals(e.entryPc, a.entryPc, "$ctx：函数 $i 入口")
            assertEquals(e.arity, a.arity, "$ctx：函数 ${e.name} arity")
            assertEquals(e.slotCount, a.slotCount, "$ctx：函数 ${e.name} slotCount")
            assertEquals(e.labelNames.toList(), a.labelNames.toList(), "$ctx：函数 ${e.name} 标签名")
            assertEquals(e.labelPcs.toList(), a.labelPcs.toList(), "$ctx：函数 ${e.name} 标签 pc")
            assertEquals(e.paramNames.toList(), a.paramNames.toList(), "$ctx：函数 ${e.name} 头部形参名（v2 段 8）")
        }
        assertEquals(expected.callArgs, actual.callArgs, "$ctx：调用点实参表（v2 段 7）")
    }

    @Test
    fun `自造小产物：loadFile 与流式 mapped 逐字段一致`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(sampleProgram())
        val f = dir.resolve("program.erbc")
        Files.write(f, bytes)
        val expected = ProgramLoader.loadFile(f)
        assertProgramsIdentical(expected, ProgramLoader.loadMapped(f), "流式 mapped")
        ProgramLoader.deferredMapped(f).use { s ->
            assertProgramsIdentical(expected, s.program(), "流式 deferredMapped")
        }
        // 尾部填充（规约 §1.5）同样不得影响流式读
        val padded = dir.resolve("padded.erbc")
        Files.write(padded, bytes + ByteArray(41) { 0x5A })
        assertProgramsIdentical(ProgramLoader.loadFile(padded), ProgramLoader.loadMapped(padded), "尾部填充")
    }

    // ------------------------------------------------------------------
    // 1b. 跨窗口重映射（分段窗口的读序回归）
    // ------------------------------------------------------------------

    /**
     * 跨窗口样本：`statements` 条**身份不同**的语句（`raw` 各不相同 ⇒ 语句段局部字符串表
     * 真的装数据），每条再跟 3 条 `_NOP` 把 CODE 段顶过窗口。
     *
     * 为什么要这个样本：`SectionWindow` 按 [WINDOW_BYTES] 分块，块外读取会重映射。
     * 2026-10-20 的实测缺陷是「`buf.get(offsetIn(...), ...)` 在 Kotlin 里先取接收者、再求参数」，
     * 于是重映射之后的那一次读用的是**旧块**；小样本装得进第一个块，测不出来，
     * 真实产物（表达式段 25 MB / 语句段 196 MB）第一次跨块就解出乱字节。
     */
    private fun crossingProgram(statements: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val const = b.constant(7L)
        val shared = b.string("跨界串")
        repeat(statements) { i ->
            val raw = "WAIT  #$i" + "·padding".repeat(6)
            val st = b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), i + 1, raw))
            b.emit(Opcode.WAIT, st, 0, SlotAddr.NONE, i % 97)
            repeat(3) { b.emit(Opcode._NOP, 0, 0, 0, -1) }
            if (i % 8 == 0) b.emit(Opcode._SET_STR, SlotAddr.local(0), shared, 0, i % 97)
            if (i % 16 == 0) b.emit(Opcode._SET_INT, SlotAddr.local(1), const, 0, i % 97)
        }
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    @Test
    fun `跨窗口重映射：CODE 段与语句段都大过窗口时两条路径逐字一致`(@TempDir dir: Path) {
        val bytes = ErbcWriter.encode(crossingProgram(300_000))
        val hdr = ProgramLoader.probe(bytes, null)
        val ends = ProgramLoader.sectionEnds(hdr, bytes.size)
        val codeLen = ends[ErbcFormat.SECTION_CODE] - hdr.offsets[ErbcFormat.SECTION_CODE]
        val stmtOff = hdr.offsets[ErbcFormat.SECTION_STATEMENTS]
        val stmtLen = ends[ErbcFormat.SECTION_STATEMENTS] - stmtOff
        assertTrue(
            codeLen > WINDOW_BYTES,
            "样本 CODE 段只有 $codeLen 字节（窗口 $WINDOW_BYTES）：没跨块就测不出读序缺陷",
        )
        assertTrue(
            stmtLen > WINDOW_BYTES,
            "样本语句段只有 $stmtLen 字节（窗口 $WINDOW_BYTES）：没跨块就测不出字符串表的跳回读",
        )
        val f = dir.resolve("cross.erbc")
        Files.write(f, bytes)
        val full = ProgramLoader.loadFile(f)
        val streamed = ProgramLoader.loadMapped(f)
        assertProgramsIdentical(full, streamed, "跨窗口 ${mib(bytes.size.toLong())}（CODE $codeLen B / 语句 $stmtLen B）")
        println(
            "[erbc-stream] 跨窗口样本 ${mib(bytes.size.toLong())}：指令 ${full.instructionCount} / " +
                "CODE $codeLen B / 语句段 $stmtLen B（窗口 $WINDOW_BYTES B），两条路径逐字段一致",
        )
    }

    private fun mib(bytes: Long): String {
        val tenths = (bytes * 10 + 524_288) / 1_048_576
        return "${tenths / 10}.${tenths % 10} MiB"
    }

    // ------------------------------------------------------------------
    // 2. 记录区逐字节变异差分（流式解码器 vs 全读解码器）
    // ------------------------------------------------------------------

    /** 一次装载的读数：成功给 Program 摘要，失败给「类型 + 消息」（与 `MmapLoaderTest` 同口径）。 */
    private fun capture(block: () -> Program?): String {
        return try {
            val p = block() ?: return "ok:null"
            // M12-EXPRLAZY（2026-10-24）：生产路径（`ErbcStreamDecoder`）不再在装载期建表达式池，
            // 池里损坏的结点记录要到**首次 `expr(k)`** 才爆；而全读路径（`ErbcReader`）仍是整池解码。
            // 两条路径的差别只在「什么时候发现」，不在「发现什么」——所以这里统一把整池摸一遍，
            // 让对拍落在同一深度上（物化结果用完即弃）。
            for (k in 0 until p.exprCount) p.expr(k)
            "ok:${p.instructionCount}/${p.code.size}/${p.lines.contentHashCode()}/" +
                "${p.constantCount}/${p.stringCount}/${p.exprCount}/${p.statementCount}/" +
                "${p.functions.size}/${p.entryFunction}/${p.globalSlots}"
        } catch (e: Throwable) {
            "err:${e.javaClass.name}:${e.message}"
        }
    }

    @Test
    fun `记录区逐字节变异：两条路径的异常类型与消息逐字相同`(@TempDir dir: Path) {
        val good = ErbcWriter.encode(sampleProgram())
        val hdr = ProgramLoader.probe(good, null)
        val ends = ProgramLoader.sectionEnds(hdr, good.size)

        // 候选变异点：全部段的正文（段表本身由 MmapLoaderTest 覆盖），按预算抽样
        val points = ArrayList<Int>()
        for (sec in 0 until ErbcFormat.SECTION_COUNT) {
            val off = hdr.offsets[sec]
            if (off == 0) continue
            for (at in off until ends[sec]) points.add(at)
        }
        val budget = 1_200
        val step = maxOf(1, points.size / budget)
        val mutationPoints = points.filterIndexed { i, _ -> i % step == 0 }
        assertTrue(mutationPoints.size >= 100, "变异点太少（${mutationPoints.size}）：样本不覆盖记录区")

        var exact = 0
        var failures = 0
        var n = 0
        for (at in mutationPoints) {
            val bad = good.copyOf()
            bad[at] = (bad[at].toInt() xor 0xFF).toByte()
            // 每个变异点写**独立文件**：映射路径解完就把产物映射到地址空间，而 Windows 在映射存活期间
            // 拒绝截断/重写同一文件（实测 `FileSystemException: 另一个程序正在使用此文件`）。
            // 映射要在 GC 时才 munmap（`MappedErbc` 类末 TODO(verify) 同一条），所以复用同一个文件名
            // 会变成「测试装置被平台约束卡住」，而不是被测语义的一部分。
            val f = dir.resolve("mut-${n++}.erbc")
            Files.write(f, bad)
            val full = capture { ProgramLoader.loadFile(f) }
            val stream = capture { ProgramLoader.loadMapped(f) }
            assertEquals(full, stream, "变异点 $at（段内偏移 ${at}）：两条路径读数必须逐字相同")
            if (full.startsWith("err:")) failures++ else exact++
        }
        println("[erbc-stream] 逐字节变异差分：${mutationPoints.size} 点（成功 $exact / 报错 $failures），两条路径逐字相同")
    }

    // ------------------------------------------------------------------
    // 3. 真实产物：计数 + 全量内容哈希（一次只驻留一个 Program）
    // ------------------------------------------------------------------

    private data class Readings(
        val bytes: Long,
        val instructions: Int,
        val lines: Int,
        val functions: Int,
        val constants: Int,
        val strings: Int,
        val exprs: Int,
        val statements: Int,
        val globalSlots: Int,
        val entry: Int,
        val codeHash: Int,
        val linesHash: Int,
        val poolHash: Int,
    )

    private fun readings(
        path: Path,
        mapped: Boolean,
    ): Readings {
        val prog = if (mapped) ProgramLoader.loadMapped(path) else ProgramLoader.loadFile(path)
        val constants = LongArray(prog.constantCount) { prog.constant(it) }
        val stringStep = maxOf(1, prog.stringCount / 512)
        val sampledStrings = ArrayList<String>()
        var i = 0
        while (i < prog.stringCount) {
            sampledStrings.add(prog.string(i))
            i += stringStep
        }
        val stmtStep = maxOf(1, prog.statementCount / 512)
        val sampledStmts = ArrayList<String>()
        i = 0
        while (i < prog.statementCount) {
            val s = prog.statement(i)
            sampledStmts.add(s.javaClass.simpleName + "|" + s.code.name + "|" + s.line + "|" + s.raw)
            i += stmtStep
        }
        return Readings(
            bytes = Files.size(path),
            instructions = prog.instructionCount,
            lines = prog.lines.size,
            functions = prog.functions.size,
            constants = prog.constantCount,
            strings = prog.stringCount,
            exprs = prog.exprCount,
            statements = prog.statementCount,
            globalSlots = prog.globalSlots,
            entry = prog.entryFunction,
            codeHash = prog.code.contentHashCode(),
            linesHash = prog.lines.contentHashCode(),
            poolHash = listOf(
                constants.contentHashCode(),
                sampledStrings.hashCode(),
                sampledStmts.hashCode(),
                prog.functions.sumOf { it.entryPc },
                prog.functions.sumOf { it.labelPcs.size },
            ).hashCode(),
        )
    }

    @Test
    fun `真实产物：loadFile 与流式 mapped 计数与内容哈希一致`(@TempDir dir: Path) {
        val path = realArtifact()
        assumeTrue(path != null, "ERATW_ERBC 未指向可读的 program.erbc：跳过真实产物用例")
        val full = readings(path!!, mapped = false)
        System.gc() // 两次装载之间只驻留一个 Program（全量产物 Program 常驻可达 GB 级，测试堆 3g）
        val streamed = readings(path, mapped = true)
        assertEquals(full, streamed, "真实产物（${full.bytes} B / ${full.instructions} 指令）两条路径读数")
        println(
            "[erbc-stream] 真实产物 ${full.bytes} B：指令 ${full.instructions} / lines ${full.lines} / " +
                "函数 ${full.functions} / 池 常量 ${full.constants} 串 ${full.strings} 表达式 ${full.exprs} 语句 ${full.statements}",
        )
    }

    private fun realArtifact(): Path? {
        val raw = System.getenv("ERATW_ERBC")?.takeIf { it.isNotBlank() } ?: return null
        val p = Path.of(raw)
        val file = if (Files.isDirectory(p)) p.resolve("program.erbc") else p
        return file.takeIf { Files.isRegularFile(it) }
    }

    // ------------------------------------------------------------------
    // 4. 侧车：按行流式读 == 整文 parse
    // ------------------------------------------------------------------

    private fun sampleMeta(): BootMetadata = BootMetadata(
        labels = mapOf("SYSTEM_TITLE" to mapOf("TOP" to 0, "MAIN" to 3)),
        dataSections = mapOf(
            2 to listOf(listOf(IntLiteral(1L, 1, 1), VarRef("DAY", emptyList(), operandType = OperandType.INT))),
        ),
        globalDims = listOf(BootMetadata.RawDim("#DIM GLOBAL, 10", "ERB/main.ERB", 3)),
        privateDims = listOf(
            BootMetadata.PrivateDim(
                "SYSTEM_TITLE",
                DimSpec(name = "LOCAL", isStr = false, dims = intArrayOf(4), defaultsInt = longArrayOf(1L, 2L)),
            ),
        ),
        functions = listOf(FuncMeta(index = 0, key = "MAIN", name = "main", defIndex = 1, position = "ERB/main.ERB:1")),
        fileByKey = mapOf("MAIN" to "ERB/main.ERB"),
        dimParseConfig = DimParseConfig(
            systemSaveInBinary = true,
            macros = mapOf("FOO" to "1"),
            intConstants = mapOf("BAR" to 7L),
        ),
        engineTag = "test 1",
        programSha256 = "00ff",
    )

    private fun metaCapture(block: () -> BootMetadata): String = try {
        "ok:" + block().encodeText()
    } catch (e: Throwable) {
        "err:${e.javaClass.name}:${e.message}"
    }

    @Test
    fun `BootMetadata read 按行流式解析与整文 parse 逐字一致`(@TempDir dir: Path) {
        val text = sampleMeta().encodeText()
        val cases = linkedMapOf(
            "LF" to text,
            "尾部空白行" to (text + "\n\n"),
            "无尾换行" to text.trimEnd('\n'),
            "CRLF" to text.replace("\n", "\r\n"),
            "CRLF 尾" to (text.replace("\n", "\r\n") + "\r\n\r\n"),
            "行首空行" to ("\n\n" + text),
            "尾部空格" to text.replace("\n", "  \n"),
            "孤立 CR" to text.replace("\n", "\r"),
            "空文件" to "",
            "只有换行" to "\n\n\n",
        )
        var n = 0
        for ((name, payload) in cases) {
            val f = dir.resolve("m$n.meta")
            n++
            Files.writeString(f, payload)
            val whole = metaCapture { BootMetadata.parse(payload) }
            val streamed = metaCapture { BootMetadata.read(f) }
            assertEquals(whole, streamed, "$name：read(按行流式) 必须与 parse(整文) 逐字相同")
        }
        val f = dir.resolve("round.meta")
        Files.writeString(f, text)
        assertEquals(text, BootMetadata.read(f).encodeText(), "LF 侧车往返")
        assertTrue(BootMetadata.read(f).functions.single().key == "MAIN")
    }
}
