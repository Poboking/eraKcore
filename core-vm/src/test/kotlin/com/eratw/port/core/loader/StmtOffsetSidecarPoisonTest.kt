package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcEncoded
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 段 3 语句偏移表侧车的**错表负例**（CONTRACT-CMP §3 L02 判据 / RECON-CMP §1 必红夹具）。
 *
 * ## 钉住什么
 *
 * 侧车与 `program.erbc` 是两份文件，装载器只认「单调 + 条数 + 首条为 0 + 末条收在段尾」时，
 * **中间 171 万条偏移一条不检**：把中段每条各 +1 字节（首条 0、末条原样，表仍严格单调、
 * 末条仍对齐段尾）就会按错位边界解出**一条合法但错位的语句**（`valueOf` 不失败）。
 * 这是 CMP-01 的真身，也是 CMP-03「错表绝不静默生效」此前没有任何负例的原因。
 *
 * ## 四条用例
 *
 * 1. **中段平移 1 字节**：期望装载期打「偏移表不可信（回落扫描）」并回落到逐条扫描，
 *    解出来的语句与整池对照档逐条相同（不是「拒了就坏」）；
 * 2. **A4 · `recordBytes` 比真值多 1**：同样必须被拒（`recordBytes` 是写侧对记录区长度的
 *    自述，与解出来的真实长度不符即「表与产物不是一对」）；
 * 3. **对照组**：真侧车走加速档，不打「不可信」，语句与整池档逐条相同（防止修复把加速档
 *    一起打死 —— 那会静默退化成 9.5 s 的扫描档）；
 * 4. **A5 · 配对指纹通过但偏移表仍错**：用真产物字节写侧车（写侧指纹必然与产物配对），
 *    只把中段偏移 +1 ⇒ 仍必须被拒。这条直读「配对指纹单独不够」的事实：指纹只覆盖
 *    产物字节、不覆盖表本身，错表的边界一致性只能靠逐条/抽查回放发现。
 *
 * 用例 4 与用例 1 用的是同一夹具，区别在断言面：用例 4 显式断言「指纹一致」再断言「被拒」。
 */
class StmtOffsetSidecarPoisonTest {

    // ==================================================================
    // 夹具：8 条语句，记录长度各不相同（错位后必然不是合法边界）
    // ==================================================================

    private fun sampleProgram(statements: Int = 8): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 4)
        val shared = Binary(
            OperatorCode.PLUS,
            IntLiteral(7L, 3, 1),
            IntLiteral(3L, 2, 1),
            OperandType.INT,
            3,
            1,
        )
        val poolRoot = b.expr(shared)
        val stmts = ArrayList<Int>(statements)
        for (i in 0 until statements) {
            val line = i + 1
            val s = when (i % 4) {
                0 -> PrintStmt(
                    PrintSpec(
                        code = FunctionCode.PRINTL,
                        name = "PRINTL",
                        argType = ArgType.STR_EXPRESSION,
                        isLineEnd = true,
                        flag = 0,
                    ),
                    ExpressionArgument(StrLiteral("第 $i 行 —— 记录边界", line, 0)),
                    line,
                    "PRINTL \"第 $i 行 —— 记录边界\"",
                )

                1 -> AssignStmt(
                    dest = com.eratw.port.core.expr.VarRef(
                        "B$",
                        emptyList(),
                        isNoArg = false,
                        operandType = OperandType.STR,
                    ),
                    op = OperatorCode.ASSIGNMENT_STR,
                    args = VoidArgument(),
                    line = line,
                    raw = "B$ = \"\"  #$i",
                )

                2 -> InstructionStmt(FunctionCode.WAIT, VoidArgument(), line, "WAIT  #$i")

                else -> InstructionStmt(
                    FunctionCode.PRINT,
                    ExpressionArgument(shared),
                    line,
                    "PRINT 7+3  #$i",
                )
            }
            stmts.add(b.statement(s))
        }
        b.emit(Opcode._EVAL_INT, poolRoot, 0, SlotAddr.NONE, 3)
        b.emit(Opcode.WAIT, stmts[0], 0, SlotAddr.NONE, 4)
        for (k in 1 until stmts.size) b.emit(Opcode.WAIT, stmts[k], 0, SlotAddr.NONE, k + 1)
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    /** 落一份真产物（固定叫 `program.erbc`：侧车的配对指纹按这个文件名找同目录产物）。 */
    private fun writeArtifact(
        dir: Path,
        program: Program,
    ): Pair<Path, ErbcEncoded> {
        val encoded = ErbcWriter.encodeIndexed(program)
        val erbc = dir.resolve("program.erbc")
        Files.write(erbc, encoded.bytes)
        return erbc to encoded
    }

    private fun load(
        erbc: Path,
    ): Pair<Program, List<String>> {
        val stages = ArrayList<String>()
        val program = ProgramLoader.loadMapped(erbc) { stage -> stages += stage }
        return program to stages
    }

    /** 装载期是否打出「不可信（回落扫描）」。 */
    private fun untrusted(stages: List<String>): Boolean = stages.any { it.contains("不可信") }

    /**
     * 两条装载档解出来的语句逐条相同（回落扫描档必须仍是对的，不是「拒了就坏」）。
     *
     * 错表被信时这一条也会失败：错位边界解出来的语句类/原文都对不上。
     */
    private fun assertSameStatements(
        expected: Program,
        actual: Program,
    ) {
        assertEquals(expected.statementCount, actual.statementCount, "语句池条数")
        for (i in 0 until expected.statementCount) {
            val a = expected.statement(i)
            val b = actual.statement(i)
            assertEquals(a.javaClass, b.javaClass, "第 $i 条语句具体类")
            assertEquals(a.raw, b.raw, "第 $i 条语句原文")
            assertEquals(a.line, b.line, "第 $i 条语句行号")
        }
    }

    // ==================================================================
    // 1. 中段平移 1 字节（RECON 必红夹具）
    // ==================================================================

    @Test
    fun `中段偏移各加一：装载期必须判不可信并回落扫描`(@TempDir dir: Path) {
        val program = sampleProgram(8)
        val (erbc, encoded) = writeArtifact(dir, program)
        val real = encoded.statementRecordOffsets
        assertTrue(real != null && real.size == 8, "夹具必须带 8 条记录偏移（实际 ${real?.size}）")
        val poison = real!!.copyOf()
        for (i in 1..poison.size - 2) poison[i] += 1
        assertEquals(0, poison[0], "首条保持 0")
        assertEquals(real[real.size - 1], poison[poison.size - 1], "末条原样")
        for (i in 1 until poison.size) {
            assertTrue(poison[i] > poison[i - 1], "夹具自身必须仍严格单调（第 $i 条）")
        }
        StmtOffsetSidecar.write(StmtOffsetSidecar.pathFor(erbc), poison, encoded.statementRecordBytes)

        val (loaded, stages) = load(erbc)
        assertTrue(
            untrusted(stages),
            "中段平移 1 字节的错表必须在装载期被打回（marks=${stages.joinToString(" | ")}）",
        )
        assertSameStatements(ProgramLoader.loadMappedEager(erbc), loaded)
    }

    // ==================================================================
    // 2. A4：recordBytes 比真值多 1（防「只把 recordBytes 接回去」的假修）
    // ==================================================================

    @Test
    fun `recordBytes 比真值多一：装载期必须判不可信`(@TempDir dir: Path) {
        val program = sampleProgram(8)
        val (erbc, encoded) = writeArtifact(dir, program)
        val real = encoded.statementRecordOffsets!!
        StmtOffsetSidecar.write(
            StmtOffsetSidecar.pathFor(erbc),
            real,
            encoded.statementRecordBytes + 1,
        )

        val (loaded, stages) = load(erbc)
        assertTrue(
            untrusted(stages),
            "recordBytes 与真实记录区长度不符必须被打回（marks=${stages.joinToString(" | ")}）",
        )
        assertSameStatements(ProgramLoader.loadMappedEager(erbc), loaded)
    }

    // ==================================================================
    // 3. 对照组：真侧车必须走加速档（防止修复把加速档一起打死）
    // ==================================================================

    @Test
    fun `真侧车走加速档不回落且语句与整池档逐条相同`(@TempDir dir: Path) {
        val program = sampleProgram(8)
        val (erbc, encoded) = writeArtifact(dir, program)
        StmtOffsetSidecar.write(
            StmtOffsetSidecar.pathFor(erbc),
            encoded.statementRecordOffsets!!,
            encoded.statementRecordBytes,
        )

        val (loaded, stages) = load(erbc)
        assertFalse(
            untrusted(stages),
            "真侧车不得回落扫描（marks=${stages.joinToString(" | ")}）；" +
                "否则加速档被修复打死，真机静默退化 9.5 s",
        )
        assertSameStatements(ProgramLoader.loadMappedEager(erbc), loaded)
    }

    // ==================================================================
    // 4. A5：配对指纹一致 + 中段平移 ⇒ 仍必须被拒（直读「指纹单独不够」）
    // ==================================================================

    @Test
    fun `配对指纹通过但中段平移仍被拒：指纹只覆盖产物不覆盖表`(@TempDir dir: Path) {
        val program = sampleProgram(8)
        val (erbc, encoded) = writeArtifact(dir, program)
        val real = encoded.statementRecordOffsets!!
        val poison = real.copyOf()
        for (i in 1..poison.size - 2) poison[i] += 1
        // 写侧车用的产物是真的 ⇒ 写侧算出的记录区指纹一定是「配对」的
        StmtOffsetSidecar.write(StmtOffsetSidecar.pathFor(erbc), poison, encoded.statementRecordBytes)

        val header = StmtOffsetSidecar.readHeader(erbc)
        assertTrue(header != null, "v2 侧车必须带可读头")
        assertEquals(encoded.statementRecordBytes, header!!.recordBytes, "侧车自述的记录区长度是真值")
        val paired = MappedErbc.open(erbc).use { mapped ->
            val end = mapped.endOf(ErbcFormat.SECTION_STATEMENTS)
            StmtOffsetSidecar.regionCrc32(mapped, end - header.recordBytes, end)
        }
        assertEquals(header.recordCrc32, paired, "产物侧的记录区指纹必须配对（A5 的前提）")

        val (loaded, stages) = load(erbc)
        assertTrue(
            untrusted(stages),
            "指纹配对成功也不能让错表生效（marks=${stages.joinToString(" | ")}）",
        )
        assertSameStatements(ProgramLoader.loadMappedEager(erbc), loaded)
    }

    /** 夹具自检：8 条语句的记录区非空、偏移严格递增（写侧事实，避免夹具本身不成立）。 */
    @Test
    fun `夹具自检：偏移严格递增且首条为 0`(@TempDir dir: Path) {
        val (_, encoded) = writeArtifact(dir, sampleProgram(8))
        val offsets = encoded.statementRecordOffsets!!
        assertEquals(0, offsets[0], "首条偏移必须为 0")
        for (i in 1 until offsets.size) {
            assertTrue(offsets[i] > offsets[i - 1], "偏移必须严格递增（第 $i 条）")
        }
        assertTrue(encoded.statementRecordBytes > offsets.last(), "记录区总长必须大于末条偏移")
    }
}
