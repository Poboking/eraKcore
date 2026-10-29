package com.eratw.port.core.loader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.ArgType
import com.eratw.port.core.stmt.AssignStmt
import com.eratw.port.core.stmt.ExpressionArgument
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.PrintSpec
import com.eratw.port.core.stmt.PrintStmt
import com.eratw.port.core.stmt.VoidArgument
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 段 3 语句记录偏移表侧车（`program.stmt.idx`）的等价性测试（2026-10-23 装载提速）。
 *
 * ## 钉住什么
 *
 * 侧车档只影响「怎么拿偏移」，不许影响**解出来的语句本身**。所以同一份产物用两条
 * 路径各装一次：
 * - **扫描档**：把侧车临时移开 ⇒ 走原来的 `buildRecordOffsets`（逐条反序列化，天然是 oracle）；
 * - **侧车档**：把侧车放回 ⇒ 走 `StmtOffsetSidecar.read` + 校验 + 直读偏移。
 *
 * 逐条比对（抽样）语句类型与原文；同时断言侧车档没有打「不可信，回落扫描」标记 ——
 * 否则这个用例会在表悄悄失效时假绿（那正是最危险的退化）。
 *
 * ## 为什么在临时目录里重写侧车（CMP-01/CMP-02 后的口径）
 *
 * 侧车格式已升到 v2：头里带 `recordBytes` + 段 3 记录区 CRC32 的**配对指纹**
 * （`StmtOffsetSidecar.VERSION_PAIRED`）。v1（20 字节头、无指纹）按设计**一律按不可信
 * 处理并回落扫描** —— 它没有与产物配对的任何凭证，正是 CMP-01 静默解错语句的成因。
 * 旧产物的侧车或旧用例按 v1 写出来的侧车因此不能再直接喂给「侧车档不回落」这条断言。
 *
 * 修法是**用 v2 写法重写一份**，而不是放宽断言：把产物复制到 `@TempDir`，用
 * `StmtOffsetSidecar.write`（它自己从同目录 `program.erbc` 的段 3 记录区算 CRC32，
 * 所以写出来的侧车必然与产物配对）按读到的偏移表重写，再跑两条路径。这样：
 * - 语料/共享构建目录里的原件一个字节不动；
 * - 用例仍然钉住「v2 侧车 + 配对产物 ⇒ 加速档生效」这条生产路径；
 * - 表本身被改坏时由 `validateRecordOffsets` 的第二层（边界回放）打回，见
 *   `StmtOffsetSidecarPoisonTest`。
 *
 * 缺产物 / 缺侧车（旧构建）时跳过，不污染闸门。
 *
 * ## 流式读的判据不变性（2026-10-28 真机 512 MiB 堆顶 OOM 修复）
 *
 * 读侧从「`Files.readAllBytes` 整份读成字节再包裹解析」改成「定长小块解进最终的
 * `IntArray`」：真机装载到段 3 之前峰值已贴顶，那 6,866,568 B 的文件副本与
 * 6,866,560 B 的偏移表是同一份数据的两次同尺寸分配，崩溃报告里失败的那笔正数就是后者。
 * 改成小块后这一段只剩一块 64 KiB 的额外驻留，而**判据必须逐输入不变**（侧车是可选
 * 加速件，读坏了只许回落扫描，不许改语句本身）。下面 `写侧落盘的侧车…`、`v1 旧格式…`、
 * `空表侧车…`、`结构负例…`、`20 万条偏移…` 五条用例钉住往返与全部 null 面；
 * 唯一有意改变的是 v2 头残件（整个文件只有 20 到 23 字节）从抛
 * `java.nio.BufferUnderflowException` 变成返回 null，见 `v2 头残件…` 那条。
 */
class StmtOffsetSidecarTest {

    @Test
    fun `侧车加速档与逐条扫描档解出的语句逐条等价`(@TempDir dir: Path) {
        val src = findErbcDir()
        assumeTrue(src != null, "无 erbc 产物：跳过（先跑 :tools:erbcc:compileCorpus）")
        val erbc = src!!.resolve("program.erbc")
        assumeTrue(Files.isRegularFile(erbc), "缺 program.erbc：跳过")
        val sidecar = StmtOffsetSidecar.pathFor(erbc)
        assumeTrue(Files.isRegularFile(sidecar), "无 program.stmt.idx 侧车（旧产物）：跳过")

        // 侧车自身读数（v1/v2 都能读）：条数/首条/记录区长度。
        val offsets = StmtOffsetSidecar.read(erbc)
        assumeTrue(offsets != null, "侧车不可读：跳过")
        assertTrue(offsets!!.isNotEmpty(), "侧车偏移表为空")
        assertEquals(0, offsets[0], "首条记录偏移必须为 0（相对记录区起点）")
        val header = StmtOffsetSidecar.readHeader(erbc)
        assumeTrue(header != null, "侧车头不可读：跳过")
        assertEquals(offsets.size, header!!.stmtCount, "侧车条数与偏移表长度不同")

        // ---- 在临时目录重建一对「产物 + v2 侧车」 ----
        // 复制产物是因为侧车的配对指纹按**同目录** program.erbc 取数，不能在共享/构建
        // 目录里改原件；v2 写法由 write 自己补 recordBytes 对应的 CRC32。
        val work = dir.resolve("erbc")
        Files.createDirectories(work)
        val localErbc = work.resolve(StmtOffsetSidecar.PROGRAM_FILE_NAME)
        Files.copy(erbc, localErbc, StandardCopyOption.REPLACE_EXISTING)
        val localSidecar = StmtOffsetSidecar.pathFor(localErbc)
        StmtOffsetSidecar.write(localSidecar, offsets, header.recordBytes)
        assertEquals(
            StmtOffsetSidecar.VERSION_PAIRED,
            StmtOffsetSidecar.readHeader(localErbc)!!.version,
            "重写后的侧车必须是 v2（带配对指纹）",
        )

        // ---- 扫描档（oracle）：临时把侧车藏起来 ----
        val hidden = localSidecar.resolveSibling("__hidden__" + StmtOffsetSidecar.FILE_NAME)
        Files.move(localSidecar, hidden, StandardCopyOption.REPLACE_EXISTING)
        val scanStages = ArrayList<String>()
        val scan = try {
            ProgramLoader.loadMapped(localErbc) { stage -> scanStages += stage }
        } finally {
            Files.move(hidden, localSidecar, StandardCopyOption.REPLACE_EXISTING)
        }
        assertTrue(
            scanStages.any { it.contains("语句记录") },
            "扫描档应当打完语句记录分段标记（实际 ${scanStages.size} 条）",
        )

        // ---- 侧车档 ----
        val fastStages = ArrayList<String>()
        val fast = ProgramLoader.loadMapped(localErbc) { stage -> fastStages += stage }
        assertFalse(
            fastStages.any { it.contains("不可信") },
            "侧车档不应回落扫描（打到不可信标记 ⇒ 侧车与产物不配对）",
        )

        assertEquals(scan.instructionCount, fast.instructionCount, "指令数不同")
        assertEquals(scan.statementCount, fast.statementCount, "语句池长度不同")
        assertEquals(fast.statementCount, offsets.size, "侧车条数与语句池长度不同")

        // 抽样逐条比对（约 500 条，覆盖全池；语句原文 + 具体类都钉住）
        val step = maxOf(1, scan.statementCount / 500)
        var i = 0
        var checked = 0
        while (i < scan.statementCount) {
            val a = scan.statement(i)
            val b = fast.statement(i)
            assertEquals(a.javaClass, b.javaClass, "第 $i 条语句具体类不同")
            assertEquals(a.raw, b.raw, "第 $i 条语句原文不同")
            i += step
            checked++
        }
        assertTrue(checked >= 100, "抽样条数过少（$checked）")
    }

    // ==================================================================
    // 流式读（2026-10-28 真机 512 MiB 堆顶 OOM 修复）
    //
    // 读侧从「整份 readAllBytes + 包裹解析」改成「定长小块解进最终的 IntArray」，
    // 判据必须逐输入不变：这一组用例把往返、v1/v2、空表、尺寸闸、条数/长度负值、
    // 魔数/版本负例、跨分块边界的 20 万条大表全部钉住。
    // 只有一条是**有意改动**：v2 头残件（文件只有 20~23 字节）改造前抛
    // java.nio.BufferUnderflowException（读不到完整的 recordCrc32 字段），
    // 现在按「侧车不可用 ⇒ 回落扫描」返回 null —— 见下面那条用例的名字。
    // ==================================================================

    @Test
    fun `写侧落盘的侧车与流式读逐条往返相同（含头字段）`(@TempDir dir: Path) {
        val encoded = ErbcWriter.encodeIndexed(sampleProgram(8))
        val erbc = dir.resolve(StmtOffsetSidecar.PROGRAM_FILE_NAME)
        Files.write(erbc, encoded.bytes)
        val real = encoded.statementRecordOffsets
        assertTrue(real != null && real.size == 8, "夹具必须带 8 条记录偏移（实际 ${real?.size}）")
        StmtOffsetSidecar.write(StmtOffsetSidecar.pathFor(erbc), real!!, encoded.statementRecordBytes)

        val offsets = StmtOffsetSidecar.read(erbc)
        assertTrue(offsets != null, "v2 侧车必须可读")
        assertEquals(real.size, offsets!!.size, "条数")
        assertTrue(real.contentEquals(offsets), "偏移表逐条不同")
        assertEquals(0, offsets[0], "首条偏移必须为 0")

        val header = StmtOffsetSidecar.readHeader(erbc)
        assertTrue(header != null, "头必须可读")
        assertEquals(StmtOffsetSidecar.VERSION_PAIRED, header!!.version, "版本")
        assertEquals(real.size, header.stmtCount, "头里记的条数")
        assertEquals(encoded.statementRecordBytes, header.recordBytes, "头里记的记录区长度")
        val paired = MappedErbc.open(erbc).use { mapped ->
            val end = mapped.endOf(ErbcFormat.SECTION_STATEMENTS)
            StmtOffsetSidecar.regionCrc32(mapped, end - header.recordBytes, end)
        }
        assertEquals(header.recordCrc32, paired, "头里的记录区 CRC32 必须与产物配对")
    }

    @Test
    fun `v1 旧格式（20 字节头）偏移表位置仍从第 20 字节起`(@TempDir dir: Path) {
        val offsets = intArrayOf(0, 17, -1, 65536, Int.MAX_VALUE, 4096)
        putSidecar(dir, sidecarBytes(StmtOffsetSidecar.VERSION_LEGACY, offsets))

        val read = StmtOffsetSidecar.read(dir.resolve(StmtOffsetSidecar.PROGRAM_FILE_NAME))
        assertTrue(read != null, "v1 侧车结构合法，read 只判结构（可信与否由解码器判）")
        assertTrue(offsets.contentEquals(read!!), "v1 偏移表逐条不同（读侧必须按 20 字节头定位）")
        val header = StmtOffsetSidecar.readHeader(dir.resolve(StmtOffsetSidecar.PROGRAM_FILE_NAME))
        assertEquals(StmtOffsetSidecar.VERSION_LEGACY, header!!.version, "v1 版本号")
        assertEquals(0, header.recordCrc32, "v1 没有 recordCrc32 字段，读侧恒报 0")
        assertEquals(4096, header.recordBytes, "v1 的 recordBytes")
    }

    @Test
    fun `空表侧车读出零长数组（v1 与 v2 同口径）`(@TempDir dir: Path) {
        for (version in listOf(StmtOffsetSidecar.VERSION_LEGACY, StmtOffsetSidecar.VERSION_PAIRED)) {
            putSidecar(dir, sidecarBytes(version, IntArray(0)))
            val read = StmtOffsetSidecar.read(dir.resolve(StmtOffsetSidecar.PROGRAM_FILE_NAME))
            assertTrue(read != null, "v$version 空表结构合法")
            assertEquals(0, read!!.size, "v$version 空表长度")
        }
    }

    @Test
    fun `结构负例一律返回 null（与整份读法同判据）`(@TempDir dir: Path) {
        val good = sidecarBytes(StmtOffsetSidecar.VERSION_PAIRED, IntArray(64) { it * 8 })
        val programPath = dir.resolve(StmtOffsetSidecar.PROGRAM_FILE_NAME)

        // 侧车文件不存在
        assertNull(StmtOffsetSidecar.read(programPath), "缺文件必须 null")
        assertNull(StmtOffsetSidecar.readHeader(programPath), "缺文件头必须 null")

        // 短于最小头
        putSidecar(dir, good.copyOf(19))
        assertNull(StmtOffsetSidecar.read(programPath), "19 字节不足 v1 头")

        // 魔数被改坏
        val badMagic = good.copyOf()
        "ERSTMT99".toByteArray(Charsets.US_ASCII).copyInto(badMagic, 0)
        putSidecar(dir, badMagic)
        assertNull(StmtOffsetSidecar.read(programPath), "魔数不符必须 null")

        // 版本 0 / 3 / -1（只有 1 与 2 是合法版本）
        for (v in intArrayOf(0, 3, -1)) {
            val bytes = good.copyOf()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, v)
            putSidecar(dir, bytes)
            assertNull(StmtOffsetSidecar.read(programPath), "版本 $v 不在 {1,2} 必须 null")
        }

        // 条数负值 / 记录区长度负值
        val negCount = good.copyOf()
        ByteBuffer.wrap(negCount).order(ByteOrder.LITTLE_ENDIAN).putInt(12, -1)
        putSidecar(dir, negCount)
        assertNull(StmtOffsetSidecar.read(programPath), "条数为负必须 null")

        val negRecordBytes = good.copyOf()
        ByteBuffer.wrap(negRecordBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(16, -2)
        putSidecar(dir, negRecordBytes)
        assertNull(StmtOffsetSidecar.read(programPath), "recordBytes 为负必须 null")

        // 尺寸与「头 + 4×条数」不符：多一字节、少一字节、少掉最后一条、整份翻倍
        for (delta in intArrayOf(1, -1, -4, good.size)) {
            putSidecar(dir, good.copyOf((good.size + delta).coerceAtLeast(0)))
            assertNull(StmtOffsetSidecar.read(programPath), "尺寸差 $delta 字节必须 null")
        }
        // 头合法但偏移区整体缺失
        putSidecar(dir, good.copyOf(StmtOffsetSidecar.HEADER_BYTES))
        assertNull(StmtOffsetSidecar.read(programPath), "声明 64 条却没有偏移区必须 null")
    }

    @Test
    fun `v2 头残件（20 到 23 字节）按侧车不可用处理`(@TempDir dir: Path) {
        // 改造前：整份字节数组按实际长度包裹，读 recordCrc32 时剩不到 4 字节
        // ⇒ java.nio.BufferUnderflowException 直接从 read() 冒到装载链。
        // 现在：按「可选加速件不可用」返回 null，装载器回落逐条扫描。
        val v2 = sidecarBytes(StmtOffsetSidecar.VERSION_PAIRED, IntArray(0))
        val programPath = dir.resolve(StmtOffsetSidecar.PROGRAM_FILE_NAME)
        for (size in StmtOffsetSidecar.HEADER_BYTES_LEGACY until StmtOffsetSidecar.HEADER_BYTES) {
            putSidecar(dir, v2.copyOf(size))
            assertNull(StmtOffsetSidecar.read(programPath), "$size 字节的 v2 残件必须 null")
        }
    }

    @Test
    fun `20 万条偏移跨分块边界逐条相同`(@TempDir dir: Path) {
        val n = 200_000
        val offsets = IntArray(n) { i ->
            when {
                i % 997 == 0 -> -1
                i % 1013 == 0 -> Int.MAX_VALUE
                else -> i * 4
            }
        }
        putSidecar(dir, sidecarBytes(StmtOffsetSidecar.VERSION_PAIRED, offsets))

        val read = StmtOffsetSidecar.read(dir.resolve(StmtOffsetSidecar.PROGRAM_FILE_NAME))
        assertTrue(read != null, "20 万条侧车必须可读")
        assertEquals(n, read!!.size, "条数")
        assertTrue(offsets.contentEquals(read), "跨分块边界的偏移表必须逐条相同")
    }

    /**
     * 夹具：语句长度各不相同的最小产物（记录边界错位后必然不是合法边界，
     * 与 `StmtOffsetSidecarPoisonTest` 同一套形状）。
     */
    private fun sampleProgram(statements: Int): Program {
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
                    ExpressionArgument(StrLiteral("侧车流式读夹具第 $i 行", line, 0)),
                    line,
                    "PRINTL 侧车流式读夹具第 $i 行",
                )

                1 -> AssignStmt(
                    dest = VarRef(
                        "B\$",
                        emptyList(),
                        isNoArg = false,
                        operandType = OperandType.STR,
                    ),
                    op = OperatorCode.ASSIGNMENT_STR,
                    args = VoidArgument(),
                    line = line,
                    raw = "B\$ = \"\"  #$i",
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

    /** 按侧车字节布局手工落一份文件（v1/v2、任意条数、任意字段值都能造）。 */
    private fun sidecarBytes(
        version: Int,
        offsets: IntArray,
        recordBytes: Int = 4096,
        recordCrc32: Int = 0x5EED,
    ): ByteArray {
        val headerBytes =
            if (version >= StmtOffsetSidecar.VERSION_PAIRED) {
                StmtOffsetSidecar.HEADER_BYTES
            } else {
                StmtOffsetSidecar.HEADER_BYTES_LEGACY
            }
        val buf = ByteBuffer.allocate(headerBytes + offsets.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("ERSTMT01".toByteArray(Charsets.US_ASCII))
        buf.putInt(version)
        buf.putInt(offsets.size)
        buf.putInt(recordBytes)
        if (headerBytes >= StmtOffsetSidecar.HEADER_BYTES) buf.putInt(recordCrc32)
        for (o in offsets) buf.putInt(o)
        return buf.array()
    }

    private fun putSidecar(
        dir: Path,
        bytes: ByteArray,
    ): Path {
        val path = StmtOffsetSidecar.pathFor(dir.resolve(StmtOffsetSidecar.PROGRAM_FILE_NAME))
        Files.write(path, bytes)
        return path
    }

    /** 与 `LoadTimingProbe` 同一套产物查找顺序。 */
    private fun findErbcDir(): Path? {
        System.getenv("ERATW_ERBC_DIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it) }
        System.getenv("ERATW_ERBC")?.trim()?.takeIf { it.isNotEmpty() }?.let { return Path.of(it).parent }
        return listOf(
            Path.of("build", "erbc-out", "erbc.v1"),
            Path.of("build", "erbc.v1"),
            Path.of("..", "tools", "erbcc", "build", "erbc-out", "erbc.v1"),
        ).firstOrNull { Files.isRegularFile(it.resolve("program.erbc")) }
    }
}
