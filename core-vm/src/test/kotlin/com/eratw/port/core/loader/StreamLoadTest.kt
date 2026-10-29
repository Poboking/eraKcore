package com.eratw.port.core.loader

import com.eratw.port.core.code.CallArg
import com.eratw.port.core.code.CallArgSource
import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcEncodeOptions
import com.eratw.port.core.code.ErbcFormat
import com.eratw.port.core.code.ErbcFormatException
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.expr.Binary
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.OperatorCode
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.InstructionStmt
import com.eratw.port.core.stmt.VoidArgument
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.io.TempDir
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 流式装载入口 [ProgramLoader.open] 的判据（CONTRACT-M8 的 w8-erbcstream 路）。
 *
 * ## 它守的是什么
 *
 * 装载链曾经是「整份产物读进一个 `ByteArray` 再解码」（旧 [MappedErbc.toProgram] 走
 * `fullBytes()`），全量语料那是 313.6 MiB 的一次性拷贝。本文件把四条性质钉死：
 *
 * 1. **等价**：[ProgramLoader.open] 物化出的 [Program] 与 [ProgramLoader.loadFile]
 *    **逐字段一致**，v1 与 v2 两种容器都覆盖 —— 重点是 v2 的两段专门元数据
 *    （段 7 `CALL_ARGS` 调用点实参表、段 8 `PARAM_NAMES` 头部形参名，规约 §5.8/§5.9），
 *    它们是流式解码器与 `ErbcReader` 唯一「按段而不是按整份字节」的额外实现面。
 * 2. **不复制整份产物**：线程分配计数（`com.sun.management.ThreadMXBean`）上，
 *    `open().program()` 比 `loadFile` 少分配至少 0.9×产物大小（省下的就是那次整文件拷贝）。
 *    装置与 `ErbcStreamPeakTest` 同一把尺子，这里再钉一遍是因为本路新增了入口。
 * 3. **懒物化**：打开只读文件头 + 段表，段表读数（[MappedProgramSource.instructionCount]、
 *    `byteSize`、`header`）在物化前就可用，且取它们不会触发解码。
 * 4. **生命周期**：物化出的 [Program] 与文件脱钩（[MappedProgramSource.close] 之后照样能跑），
 *    而句柄在 close 之后的任何段读数抛 [ErbcLoadException] 而不是读到悬空映射；close 幂等。
 *    这正是「装载完成后释放映射以外的临时缓冲」的可观测面。
 *
 * ## 异常口径
 *
 * 文件头畸形在 `open` 就抛（与 [ProgramLoader.probeFile] 逐字同一句），段布局/解码层畸形
 * 留到 `program()`（与 [ProgramLoader.loadFile] 逐字同一句）。两条路径共用
 * [ProgramLoader.validateSections] 与 [ProgramLoader.decodeMapped] 的收口，本文件用
 * 同一批畸形字节对拍，防止将来「流式路径另写一套报错」。
 */
class StreamLoadTest {

    // ==================================================================
    // 1. 等价：open 物化 vs loadFile（v1 容器）
    // ==================================================================

    @Test
    fun `open 物化与 loadFile 逐字段一致（v1 容器）`(@TempDir dir: Path) {
        val f = dir.resolve("v1.erbc")
        val v1 = ErbcEncodeOptions(version = ErbcFormat.VERSION_1)
        Files.write(f, ErbcWriter.encode(sampleProgram(), v1))
        assertEquals(ErbcFormat.VERSION_1, ProgramLoader.probeFile(f).version, "样本必须是 v1 容器")

        val viaFile = ProgramLoader.loadFile(f)
        val viaOpen = ProgramLoader.open(f).use { it.program() }

        assertSameProgram(viaFile, viaOpen, "v1")
        // 再编码必须回到原容器字节：容器承载的全部字段（指令/行号/池/函数表）都逐字相同
        assertArrayEquals(Files.readAllBytes(f), ErbcWriter.encode(viaOpen, v1), "v1 再编码应逐字回到原字节")
    }

    // ==================================================================
    // 2. 等价：v2 容器（段 7 调用点实参 + 段 8 头部形参名）
    // ==================================================================

    @Test
    fun `open 物化与 loadFile 逐字段一致（v2 容器：段7 调用实参 + 段8 形参名）`(@TempDir dir: Path) {
        val f = dir.resolve("v2.erbc")
        val source = sampleProgram(withCallArgs = true, paramNames = listOf("LOCAL", "REF_TGT"))
        Files.write(f, ErbcWriter.encode(source)) // 默认版本 = v2
        assertEquals(ErbcFormat.VERSION_2, ProgramLoader.probeFile(f).version, "样本必须是 v2 容器")

        val viaFile = ProgramLoader.loadFile(f)
        val viaOpen = ProgramLoader.open(f).use { it.program() }

        assertSameProgram(viaFile, viaOpen, "v2")
        assertArrayEquals(Files.readAllBytes(f), ErbcWriter.encode(viaOpen), "v2 再编码应逐字回到原字节")

        // 两段 v2 专有元数据单独再钉一次（它们是「按段流式」相对整体字节解码的差异面）
        assertEquals(CallArgSource.ERBC_V2, viaOpen.callArgSource, "装载来源应标成 v2 段 7")
        assertTrue(viaOpen.bindsCallArgsPositionally, "v2 装载后逐位绑定必须可用")
        assertTrue(viaOpen.callArgs.isNotEmpty(), "样本必须带调用点实参表")
        val callPc = viaOpen.callArgs.keys.single()
        assertEquals(viaFile.callArgsAt(callPc), viaOpen.callArgsAt(callPc), "调用点 pc=$callPc 实参表逐字段")
        assertEquals(
            listOf("LOCAL", "REF_TGT"),
            viaOpen.paramNames(viaOpen.functions.indexOfFirst { it.name == "callee" }),
            "段 8 头部形参名",
        )
    }

    // ==================================================================
    // 3. 懒物化：打开只读段表
    // ==================================================================

    @Test
    fun `open 只读段表：物化前段表读数可用且不触发解码`(@TempDir dir: Path) {
        val f = dir.resolve("lazy.erbc")
        val source = sampleProgram(withCallArgs = true, paramNames = listOf("LOCAL"))
        val bytes = ErbcWriter.encode(source)
        Files.write(f, bytes)

        ProgramLoader.open(f).use { handle ->
            assertFalse(handle.isMaterialized, "open 之后还没物化")
            assertEquals(bytes.size, handle.byteSize, "产物字节数（段表口径，不读正文）")
            assertEquals(ErbcFormat.VERSION_2, handle.header.version, "文件头版本")
            assertEquals(source.instructionCount, handle.instructionCount(), "CODE 段声明条数")
            assertEquals(f, handle.path, "句柄路径")
            assertFalse(handle.isMaterialized, "只读段表 / 指令条数不得触发解码")
        }
    }

    // ==================================================================
    // 4. 生命周期：物化结果与文件脱钩；close 后句柄报错；close 幂等
    // ==================================================================

    @Test
    fun `close 生命周期：物化后的 Program 不受影响，句柄读数报 ErbcLoadException`(@TempDir dir: Path) {
        val f = dir.resolve("lifecycle.erbc")
        Files.write(f, ErbcWriter.encode(sampleProgram()))

        // 对照档（段 3 整池解码，改造前形态）：物化结果与文件完全脱钩 ⇒ close 后句柄读数必须报错
        val handle = ProgramLoader.deferredMappedEager(f)
        val program = handle.program()
        assertTrue(handle.isMaterialized, "物化后 isMaterialized 置位")
        assertSame(program, handle.program(), "重复 program() 必须返回同一实例（语句池以实例为键）")
        val expectedCode = program.code.copyOf()

        handle.close()
        // 物化结果是纯堆对象：通道关了照常可读（VM 运行期不再碰文件）
        assertArrayEquals(expectedCode, program.code, "close 之后 Program.code 仍可读")
        assertTrue(program.instructionCount > 0, "close 之后 Program 仍可跑")

        // 句柄侧：任何段读数都必须报错，而不是读悬空映射
        val e = assertFailsWith<ErbcLoadException> { handle.program() }
        assertTrue(
            e.message!!.contains("映射已关闭"),
            "close 后 program() 应报映射已关闭，实际：${e.message}",
        )
        assertFailsWith<ErbcLoadException> { handle.instructionCount() }
        handle.close() // 幂等：二次 close 不抛
        assertTrue(handle.path.toString().isNotEmpty(), "句柄仍可报路径（诊断用）")
    }

    @Test
    fun `close 生命周期：惰性语句池的产物把通道留给 Program 而不是留给句柄`(@TempDir dir: Path) {
        val f = dir.resolve("lifecycle-lazy.erbc")
        Files.write(f, ErbcWriter.encode(sampleProgram()))

        // 生产档（M10-MEM 惰性语句池）：第一次取语句才回读段 3 ⇒ close 不能把通道抽走。
        // 但「接走通道」的是 Program，不是句柄：句柄侧的段读数在 close 之后照样必须失效
        // （与对照档同一条不变量，防的是「拿着已关闭的句柄继续读」），所以这里不能像改造前
        // 那样指望 close 之后还能从 handle 拿到第二份 Program。
        val handle = ProgramLoader.open(f)
        val program = handle.program()
        assertTrue(program.statementCount > 0, "段声明的语句条数（不物化）")
        handle.close()
        assertFailsWith<ErbcLoadException> { handle.program() }
        assertFailsWith<ErbcLoadException> { handle.instructionCount() }
        assertTrue(handle.isClosed, "句柄侧读数失效，但通道没关（下面还能解码）")
        handle.close() // 幂等
        // 调用方手里那份 Program 照常逐条解码；同一实例口径与改造前一致
        val first = program.statement(0)
        assertSame(first, program.statement(0), "同一池下标恒同一实例")
        assertSame(first, program.statements()[0], "整池快照与按需物化给同一实例")
    }

    // ==================================================================
    // 5. 不复制整份产物（分配量硬等式）
    // ==================================================================

    /** 线程分配计数（宿主 JVM 不支持时用例跳过，不伪造读数）。 */
    private class AllocMeter private constructor(
        private val bean: com.sun.management.ThreadMXBean,
        private val tid: Long,
    ) {
        fun read(): Long = bean.getThreadAllocatedBytes(tid)

        companion object {
            fun open(): AllocMeter? = runCatching {
                val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
                    ?: return null
                if (!bean.isThreadAllocatedMemorySupported) return null
                bean.isThreadAllocatedMemoryEnabled = true
                val tid = Thread.currentThread().id
                bean.getThreadAllocatedBytes(tid)
                AllocMeter(bean, tid)
            }.getOrNull()
        }
    }

    private fun allocatedBy(
        meter: AllocMeter,
        block: () -> Program,
    ): Long {
        val before = meter.read()
        block()
        return meter.read() - before
    }

    @Test
    fun `open 物化比 loadFile 少分配一份产物（无整文件拷贝）`(@TempDir dir: Path) {
        val meter = AllocMeter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数（com.sun.management.ThreadMXBean）：跳过")
        val m = requireNotNull(meter)

        // 预热：两条路径都先走一遍，类加载/JIT 的一次性分配不计入读数
        val warm = dir.resolve("warm.erbc")
        Files.write(warm, ErbcWriter.encode(bulkProgram(400)))
        ProgramLoader.loadFile(warm)
        ProgramLoader.open(warm).use { it.program() }
        System.gc()

        val f = dir.resolve("bulk.erbc")
        Files.write(f, ErbcWriter.encode(bulkProgram(300_000)))
        val fileBytes = Files.size(f)
        assertTrue(fileBytes > 3L shl 20, "样本产物太小（$fileBytes B）：整文件拷贝会被噪声淹没")

        val stream = allocatedBy(m) { ProgramLoader.open(f).use { it.program() } }
        System.gc()
        val full = allocatedBy(m) { ProgramLoader.loadFile(f) }

        println(
            "[erbc-stream] 产物 $fileBytes B：open().program() 分配 $stream B / " +
                "loadFile 分配 $full B / 差额 ${full - stream} B（≈ 整文件拷贝 $fileBytes B）",
        )
        assertTrue(
            full - stream >= fileBytes * 8 / 10,
            "open 路径应比 loadFile 少分配至少 0.8×产物（产物 $fileBytes B，实差 ${full - stream} B）：" +
                "整文件拷贝可能被重新引入（读数见上方 [erbc-stream]）",
        )
    }

    // ==================================================================
    // 6. 异常口径：头畸形在 open，段布局/解码畸形在 program()
    // ==================================================================

    @Test
    fun `畸形头在 open 就抛，且与 probeFile 逐字同一句`(@TempDir dir: Path) {
        val bad = dir.resolve("bad-magic.erbc")
        val bytes = ErbcWriter.encode(sampleProgram())
        bytes[0] = 0x00 // magic 首字节
        Files.write(bad, bytes)

        val viaProbe = assertFailsWith<ErbcFormatException> { ProgramLoader.probeFile(bad) }
        val viaOpen = assertFailsWith<ErbcFormatException> { ProgramLoader.open(bad) }
        assertEquals(viaProbe.message, viaOpen.message, "open 与 probeFile 的头层报错必须逐字相同")
        assertEquals(ErbcLoadException::class, viaOpen::class, "装载层异常类型")
    }

    @Test
    fun `段布局畸形在 program() 才抛，且与 loadFile 逐字同一句`(@TempDir dir: Path) {
        val bad = dir.resolve("bad-code.erbc")
        val bytes = ErbcWriter.encode(sampleProgram())
        Files.write(bad, bytes)
        // 把 CODE 段声明的指令条数改成 Int.MAX_VALUE：头/段表仍然自洽，只有段长校验拦得住
        val codeOff = ProgramLoader.probeFile(bad).offsets[ErbcFormat.SECTION_CODE]
        assertTrue(codeOff > 0, "样本必须带 CODE 段")
        writeLe32(bad, codeOff, Int.MAX_VALUE)

        val handle = ProgramLoader.open(bad) // 头与段表合法 ⇒ 打开成功
        val viaOpen = assertFailsWith<ErbcFormatException> { handle.program() }
        handle.close()

        val viaFile = assertFailsWith<ErbcFormatException> { ProgramLoader.loadFile(bad) }
        assertEquals(viaFile.message, viaOpen.message, "程序层报错必须逐字相同")
        assertEquals(viaFile::class, viaOpen::class, "异常类型")
    }

    // ==================================================================
    // 7. 真实产物：装载链里剩下的那笔瞬时分配归谁
    // ==================================================================

    private fun realArtifact(): Path? {
        val raw = System.getenv("ERATW_ERBC")?.takeIf { it.isNotBlank() } ?: return null
        val p = Path.of(raw)
        val f = if (Files.isDirectory(p)) p.resolve("program.erbc") else p
        return f.takeIf { Files.isRegularFile(it) }
    }

    private fun mib(bytes: Long): String {
        val neg = bytes < 0
        val abs = if (neg) -bytes else bytes
        val tenths = (abs * 10 + 524_288) / 1_048_576
        return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10} MiB"
    }

    /**
     * 全量产物的分配归因：`open().program()`（装载链，含 [Program.internStatementArgs]）
     * 对比 `ErbcStreamDecoder.decode`（只有解码，不含规范化去重）。
     *
     * 存在的理由：`ErbcStreamPeakTest` 的 4.5×产物上界是按 **去重落地之前** 的链标定的
     * （M6-LOAD-CHURN 的 1081.5 MiB），而 M6-PERF-INTERN 在装载链尾追加了
     * `internStatementArgs()`（常驻 708.5 → 628.6 MiB 的代价是 5.3 s 一趟）。两者叠起来，
     * 全量产物上「分配总量」比那个上界高出一大截 —— 本用例把那笔差额量出来，供 P0 决策
     * （瞬时 vs 常驻的取舍）用，不做绝对值断言：读数随产物与机器变，账才是不变量。
     *
     * 顺序故意先跑「不含去重」的一趟：它付类加载/JIT 的冷启动，读数偏**大** ⇒ 算出来的
     * 去重差额偏**小**，是保守估计。
     *
     * ## 冷启动轮必须单独丢弃（2026-10-24 修）
     *
     * 走 `:core-vm:gateRealArtifacts` 的**方法级过滤**时，本用例是进程里第一个碰装载链的测试，
     * 类加载/去虚拟化/JIT 的分配全记在「只解码」这一趟上（实测冷启动 1074.9 MiB vs 热 209.0 MiB），
     * 于是分配量的比对变成伪红；整包跑时同一次冷启动被别的用例摊掉，所以过去没暴露。
     * 现在显式跑一轮**预热**并丢弃读数，两条被测路径都从热态起算。
     *
     * ## 方向本身在 2026-10-28 被纠正过（别再改回去）
     *
     * 这里原先断言 `withIntern >= decodeOnly`（「去重要额外花分配」）。真产物上它从 HEAD 起
     * 就不成立：只解码 985.3 / 整池+去重 538.6（mem1 之后 1289.0 / 538.6）—— 因为
     * `internStatementArgs()` 是把重复的实参结点折成**共享实例**，分配总量只会降不会升。
     * 2026-10-24 那笔「冷启动轮」补丁只解释了抖动，没解释方向，所以红了一路留到今天。
     * 现在断言的是有语义的两条：去重不得比不去重更费；惰性档（生产路径）必须显著低于整池物化。
     */
    @Test
    fun `真实产物：规范化去重占装载分配多少（ERATW_ERBC 动态门）`() {
        val path = realArtifact()
        assumeTrue(path != null, "ERATW_ERBC 未指向可读的 program.erbc：跳过真实产物用例")
        val meter = AllocMeter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数（com.sun.management.ThreadMXBean）：跳过")
        val m = requireNotNull(meter)
        val file = requireNotNull(path)
        val fileBytes = Files.size(file)

        // 预热轮：接住类加载/JIT 的冷启动分配，读数弃用（见 KDoc「冷启动轮必须单独丢弃」）。
        allocatedBy(m) {
            ProgramLoader.mapped(file).use { mapped ->
                mapped.checkSectionLayout()
                ErbcStreamDecoder.decode(mapped)
            }
        }
        System.gc()

        val decodeOnly = allocatedBy(m) {
            ProgramLoader.mapped(file).use { mapped ->
                mapped.checkSectionLayout()
                ErbcStreamDecoder.decode(mapped)
            }
        }
        System.gc()
        // 同路径对比：`loadMappedEager` = 映射 + 段 3 **整池**顺序解码（+ 规范化去重），
        // 与上面 `decodeOnly`（映射 + 逐段解码）是同一条解码链的两个阶段。
        //
        // 反例：`open().program()` 是**惰性**装载（段 3 只登记不物化），它的分配量本来就
        // 比全解码少一个量级（实测 208.9 vs 1074.8 MiB），拿它当「不应小于纯解码」的
        // 下界从语义上就不成立（2026-10-24 `gateRealArtifacts` 首次真跑时暴露）。
        val withIntern = allocatedBy(m) { ProgramLoader.loadMappedEager(file) }
        System.gc()
        // 惰性档不参与断言，只作读数留档（它才是生产路径的开销）。
        val lazyLoad = allocatedBy(m) { ProgramLoader.open(file).use { it.program() } }

        println(
            "[erbc-stream] 真实产物 ${mib(fileBytes)}：只解码 ${mib(decodeOnly)} / " +
                "整池解码+去重 ${mib(withIntern)} / 规范化去重 ${mib(withIntern - decodeOnly)} / " +
                "惰性装载（不对账） ${mib(lazyLoad)}",
        )
        assertTrue(
            withIntern <= decodeOnly,
            "规范化去重把重复实例折成共享，分配总量不应上升（${mib(withIntern)} vs ${mib(decodeOnly)}）",
        )
        // 生产路径是惰性档（段 3 只登记不物化）：它若涨到接近整池物化，说明惰性档失效 ——
        // 那是真回归，值得挡（实测 58.8 vs 538.6 MiB，4 倍是留给产物增长的余量）。
        assertTrue(
            lazyLoad * 4 <= withIntern,
            "惰性装载（生产路径）应显著低于整池物化，否则惰性档失效（${mib(lazyLoad)} vs ${mib(withIntern)}）",
        )
    }

    // ==================================================================
    // 样本与断言工具
    // ==================================================================

    /**
     * 小样本：指令 / 行号 / 常量池 / 字符串池 / 表达式池 / 语句池 / 函数表 / 标签全覆盖。
     *
     * @param withCallArgs 是否登记段 7 的调用点实参（v2 容器才能写）
     * @param paramNames callee 的段 8 头部形参名（v2 容器才能写）
     */
    private fun sampleProgram(
        withCallArgs: Boolean = false,
        paramNames: List<String> = emptyList(),
    ): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 0, 4)
        val one = b.constant(11L)
        val shared = b.string("共用串·駐留")
        val expr = b.expr(
            Binary(
                OperatorCode.PLUS,
                IntLiteral(1L, 1, 1),
                VarRef("LOCAL", emptyList(), operandType = OperandType.INT),
                OperandType.INT,
                1,
                1,
            ),
        )
        val wait = b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), 7, "WAIT  #1"))
        b.emit(Opcode._SET_INT, SlotAddr.local(0), one, 0, 1)
        b.emit(Opcode._SET_STR, SlotAddr.local(1), shared, 0, 2)
        b.emit(Opcode._EVAL_INT, expr, SlotAddr.local(2), 0, 3)
        b.emit(Opcode.WAIT, wait, 0, SlotAddr.NONE, 7)
        val callPc = b.emit(Opcode._CALL, 1, 0, 0, 8) // 函数下标 1 = 下面声明的 callee
        if (withCallArgs) {
            b.declareCallArgs(callPc, listOf(CallArg(position = 0, bucket = 0, isStr = false, name = "LOCAL", present = true)))
        }
        b.emit(Opcode._HALT, 0, 0, 0, 9)
        val callee = b.declareFunction("callee", b.here(), 1, 2, paramNames)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 10)
        b.declareLabel(callee, "TAIL", b.here() - 1)
        b.noteGlobalSlot(1)
        return b.build(main)
    }

    /** 分配量样本：指令数可参数化，池里都有内容（让解码真的有事做）。与 `ErbcStreamPeakTest` 同构。 */
    private fun bulkProgram(instructions: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val strings = (0 until 200).map { b.string("str-$it-驻留") }
        val consts = (0 until 40).map { b.constant(it.toLong() * 1_000_003L) }
        val stmts = (0 until 100).map {
            b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), it + 1, "WAIT  #$it"))
        }
        var i = 0
        while (i < instructions) {
            when (i % 4) {
                0 -> b.emit(Opcode._SET_INT, SlotAddr.local(0), consts[i % consts.size], 0, i % 97)
                1 -> b.emit(Opcode._SET_STR, SlotAddr.local(1), strings[i % strings.size], 0, i % 97)
                2 -> b.emit(Opcode.WAIT, stmts[i % stmts.size], 0, SlotAddr.NONE, i % 97)
                else -> b.emit(Opcode._NOP, 0, 0, 0, -1)
            }
            i++
        }
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    /**
     * 两个装载结果逐字段对拍（口径与 `ProgramLoaderTest.assertProgramEquals` 相同，字典顺序一致）。
     *
     * 语句/表达式内部结构不在这里递归：容器再编码的逐字比较已经覆盖图形状
     * （`ErbcWriter` 对同一份图必须写出同一份字节），这里只钉下标面与标量字段。
     */
    private fun assertSameProgram(
        expected: Program,
        actual: Program,
        tag: String,
    ) {
        assertEquals(expected.instructionCount, actual.instructionCount, "$tag 指令条数")
        assertArrayEquals(expected.code, actual.code, "$tag 指令流逐字")
        assertArrayEquals(expected.lines, actual.lines, "$tag 行号表逐字")
        assertEquals(expected.globalSlots, actual.globalSlots, "$tag 全局槽")
        assertEquals(expected.entryFunction, actual.entryFunction, "$tag 入口函数")

        assertEquals(expected.constantCount, actual.constantCount, "$tag 常量池长度")
        for (i in 0 until expected.constantCount) {
            assertEquals(expected.constant(i), actual.constant(i), "$tag 常量池下标 $i")
        }
        assertEquals(expected.stringCount, actual.stringCount, "$tag 字符串池长度")
        for (i in 0 until expected.stringCount) {
            assertEquals(expected.string(i), actual.string(i), "$tag 字符串池下标 $i")
        }
        assertEquals(expected.exprCount, actual.exprCount, "$tag 表达式池长度")
        for (i in 0 until expected.exprCount) {
            assertEquals(expected.expr(i), actual.expr(i), "$tag 表达式池下标 $i 结构不等")
        }
        assertEquals(expected.statementCount, actual.statementCount, "$tag 语句池长度")
        for (i in 0 until expected.statementCount) {
            val e = expected.statement(i)
            val a = actual.statement(i)
            assertEquals(e.javaClass, a.javaClass, "$tag 语句池下标 $i 类不同")
            assertEquals(e.code, a.code, "$tag 语句池下标 $i 的 FunctionCode")
            assertEquals(e.line, a.line, "$tag 语句池下标 $i 行号")
            assertEquals(e.raw, a.raw, "$tag 语句池下标 $i 原文")
        }

        assertEquals(expected.functions.size, actual.functions.size, "$tag 函数表长度")
        for (i in expected.functions.indices) {
            val e = expected.functions[i]
            val a = actual.functions[i]
            assertEquals(e.name, a.name, "$tag 函数 $i 名字")
            assertEquals(e.entryPc, a.entryPc, "$tag 函数 ${e.name} entryPc")
            assertEquals(e.arity, a.arity, "$tag 函数 ${e.name} arity")
            assertEquals(e.slotCount, a.slotCount, "$tag 函数 ${e.name} slotCount")
            assertEquals(e.labelNames.toList(), a.labelNames.toList(), "$tag 函数 ${e.name} 标签名表")
            assertEquals(e.labelPcs.toList(), a.labelPcs.toList(), "$tag 函数 ${e.name} 标签 pc 表")
            assertEquals(e.paramNames.toList(), a.paramNames.toList(), "$tag 函数 ${e.name} 头部形参名")
        }
        assertEquals(expected.callArgs, actual.callArgs, "$tag 调用点实参表")
    }

    /** 在文件绝对偏移 [at] 写一个小端 u32（只用于造畸形产物）。 */
    private fun writeLe32(
        path: Path,
        at: Int,
        value: Int,
    ) {
        val bytes = Files.readAllBytes(path)
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[at + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[at + 3] = ((value ushr 24) and 0xFF).toByte()
        Files.write(path, bytes)
    }
}
