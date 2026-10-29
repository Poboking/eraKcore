package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
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
import com.eratw.port.perf.HeapMeter
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 装载期分配 churn 的**按段归因**装置（CONTRACT-M6-LOADCHURN §1）。
 *
 * ## 为什么需要它
 *
 * `ErbcStreamPeakTest` 只能回答「整次装载分配了多少」。要动优化就必须知道**哪一段**吃掉
 * 了那一坨：段 5 指令流的 19.2M 个 u32 是纯算术（`codeCur.u32()` 不分配），
 * 而段 3 语句池的 1.7M 条记录里每个 `raw` / `codeName` / 变量名都要解码成 `String`
 * —— 两者的优化手段完全不同。本类把 [LoadStageSink] 的段边界换算成
 * 「线程分配计数（`com.sun.management.ThreadMXBean`）增量 + 耗时增量」，逐段列表。
 *
 * ## 读数口径（与 `ErbcStreamPeakTest` 同一个尺子）
 *
 * - **分配**：`getThreadAllocatedBytes`（单调、与 GC 时机无关），差值 = 该段一次性 churn；
 * - **常驻**：`HeapMeter.settled()`（`used = total - free`，多轮 GC 取最小值），
 *   差值 = 该段**留在** Program 里的字节（`code`/`lines` 两个大数组 + 池对象 + 字符串）；
 * - **对象数**：JVM 不提供精确的「对象分配计数」（JFR 的 `ObjectAllocationSample` 是抽样），
 *   所以这一列是**结构计数**：该段解码出的条目数（串池项 / 节点 / 语句 / 指令 / 函数 /
 *   调用点），乘上该类的已知对象构成才是对象数。归因用「分配字节」这一列，不拿结构计数
 *   当分配数用。
 *
 * ## 判据
 *
 * 逐段分配之和必须等于整次装载的分配量（同一计数器、同一线程，恒等式不是估计）；
 * 分工被断言住，读数本身不做绝对值断言（分配量随 GC/JIT/堆大小波动）。
 */
class ErbcStreamAllocProfileTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 一段的读数。`bytes` = 该段分配的字节；`decodes`/`decodedBytes` = 该段的字符串解码次数/字节。 */
    private class Stage(
        val name: String,
        val bytes: Long,
        val retained: Long,
        val ms: Long,
        val decodes: Long,
        val decodedBytes: Long,
        val hits: Long,
        val objects: String,
    )

    private class Profile(
        val fileBytes: Long,
        val stages: List<Stage>,
        val total: Long,
        val totalMs: Long,
        val program: Program,
    )

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

    /** 结构计数（每段解码出的条目数）：给「对象数」列提供可复核的分子。 */
    private class Counts(val program: Program) {
        fun of(stage: String): String = when (stage) {
            LoadStages.STRINGS -> "${program.stringCount} 串池项"
            LoadStages.CONSTANTS -> "${program.constantCount} 常量"
            LoadStages.EXPR_NODES -> "节点表（表达式池 ${program.exprCount} 根）"
            LoadStages.EXPR_ROOTS -> "${program.exprCount} 根"
            LoadStages.STMT_STRINGS -> "局部串池（引用见下段）"
            LoadStages.STMT_NODES -> "节点表"
            LoadStages.STMT_RECORDS -> "${program.statementCount} 语句实例"
            LoadStages.FUNCTIONS -> "${program.functions.size} 函数 / ${program.functions.sumOf { it.labelPcs.size }} 标签"
            LoadStages.CODE -> "${program.instructionCount} 指令（4 字/条）"
            LoadStages.LINES -> "${program.lines.size} 行"
            LoadStages.CALL_ARGS -> "${program.callArgs.size} 调用点 / ${program.callArgs.values.sumOf { it.size }} 实参"
            else -> "-"
        }
    }

    /**
     * 逐段装载一次（`MappedErbc.checkSectionLayout` + [ErbcStreamDecoder] + [LoadStageSink]）。
     *
     * 钩子回调里读线程分配计数与 `nanoTime`；[retained] 打开时**额外**读 `HeapMeter.settled()`，
     * 那会连做几轮 GC，只在本段常驻也要看时开（默认关，避免把装载耗时搅成噪声）。
     */
    private fun profile(
        path: Path,
        retained: Boolean = false,
    ): Profile {
        val meter = requireNotNull(AllocMeter.open()) { "宿主 JVM 不支持线程分配计数" }
        val fileBytes = Files.size(path)
        val marks = ArrayList<StageReading>()
        var prevA = 0L
        var prevT = 0L
        var prevR = 0L
        var prevD = 0L
        var prevDB = 0L
        var prevH = 0L
        val aOuter = meter.read()
        var aEnd = aOuter
        val program = ProgramLoader.mapped(path).use { m ->
            m.checkSectionLayout()
            prevA = meter.read()
            prevT = System.nanoTime()
            prevR = if (retained) HeapMeter.settled() else 0L
            marks.add(StageReading("装载头（open + 段布局校验）", prevA - aOuter, 0L, 0L, 0L, 0L, 0L))
            val decoded = ErbcStreamDecoder.decode(m) { stage ->
                val a = meter.read()
                val t = System.nanoTime()
                val r = if (retained) HeapMeter.settled() else 0L
                marks.add(
                    StageReading(
                        stage.name,
                        a - prevA,
                        (t - prevT) / 1_000_000,
                        r - prevR,
                        stage.stringDecodes - prevD,
                        stage.stringBytes - prevDB,
                        stage.stringHits - prevH,
                    ),
                )
                prevA = a
                prevT = t
                prevR = r
                prevD = stage.stringDecodes
                prevDB = stage.stringBytes
                prevH = stage.stringHits
            }
            aEnd = meter.read()
            decoded
        }
        val counts = Counts(program)
        val stages = marks.map { m ->
            Stage(
                m.name,
                m.bytes,
                m.retained,
                m.ms,
                m.decodes,
                m.decodedBytes,
                m.hits,
                if (m.name.startsWith("装载头")) "-" else counts.of(m.name),
            )
        }
        return Profile(fileBytes, stages, aEnd - aOuter, stages.sumOf { it.ms }, program)
    }

    /** 段边界的原始读数（一个对象一段，只出现在计量路径）。 */
    private class StageReading(
        val name: String,
        val bytes: Long,
        val ms: Long,
        val retained: Long,
        val decodes: Long,
        val decodedBytes: Long,
        val hits: Long,
    )

    private fun mib(bytes: Long): String {
        val neg = bytes < 0
        val abs = if (neg) -bytes else bytes
        val tenths = (abs * 10 + 524_288) / 1_048_576
        return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10}"
    }

    private fun report(p: Profile, title: String) {
        val sum = p.stages.sumOf { it.bytes }
        val decodes = p.stages.sumOf { it.decodes }
        val hits = p.stages.sumOf { it.hits }
        println(
            "[erbc-alloc] $title：产物 ${mib(p.fileBytes)} MiB / 分配合计 ${mib(p.total)} MiB / ${p.totalMs} ms" +
                " / 串解码 $decodes 次（备忘命中 $hits）",
        )
        println(
            "[erbc-alloc]   ${"段".padEnd(32)} ${"分配(MiB)".padStart(10)} ${"占比".padStart(6)} ${"ms".padStart(6)} " +
                "${"串解码".padStart(10)} ${"命中".padStart(10)} ${"解码字节".padStart(10)}  结构计数",
        )
        for (s in p.stages) {
            val share = if (sum == 0L) 0.0 else s.bytes * 100.0 / sum
            println(
                "[erbc-alloc]   ${s.name.padEnd(32)} ${mib(s.bytes).padStart(10)} " +
                    "${String.format(java.util.Locale.ROOT, "%5.1f%%", share).padStart(6)} " +
                    "${s.ms.toString().padStart(6)} " +
                    "${s.decodes.toString().padStart(10)} ${s.hits.toString().padStart(10)} " +
                    "${mib(s.decodedBytes).padStart(10)}  ${s.objects}",
            )
        }
        val top = p.stages.sortedByDescending { it.bytes }.take(3)
        println("[erbc-alloc] 前三名：" + top.joinToString(" > ") { "${it.name} ${mib(it.bytes)} MiB" })
    }

    // ------------------------------------------------------------------
    // 合成产物：无条件跑（秒级）
    // ------------------------------------------------------------------

    /**
     * 让每一段都真的有活干：3 万条语句（raw 各不同 ⇒ 局部串池有真数据）、
     * 表达式池、常量池、函数标签表、v2 段 7/8。
     */
    private fun mixedProgram(statements: Int): Program {
        val b = CodeBuilder()
        val main = b.declareFunction("main", 0, 1, 4, listOf("ARG", "LOCAL\$"))
        val const = b.constant(7L)
        val shared = b.string("共用串")
        val bin = b.expr(
            Binary(
                OperatorCode.PLUS,
                IntLiteral(1L, 1, 1),
                VarRef("LOCAL", emptyList(), operandType = OperandType.INT),
                OperandType.INT,
                1,
                1,
            ),
        )
        repeat(statements) { i ->
            val st = b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), i + 1, "WAIT  #$i·padding"))
            b.emit(Opcode.WAIT, st, 0, SlotAddr.NONE, i % 97)
            b.emit(Opcode._SET_STR, SlotAddr.local(1), shared, 0, i % 97)
            b.emit(Opcode._SET_INT, SlotAddr.local(2), const, 0, i % 97)
            if (i % 4 == 0) b.emit(Opcode._EVAL_INT, bin, SlotAddr.local(3), 0, i % 97)
        }
        b.declareLabel(main, "TAIL", b.here())
        val callPc = b.emit(Opcode._CALL, main, 0, 0, -1)
        b.declareCallArgs(callPc, listOf(com.eratw.port.core.code.CallArg(0, 0, isStr = false, name = "LOCAL", present = true)))
        b.emit(Opcode._HALT, 0, 0, 0, -1)
        return b.build(0)
    }

    @Test
    fun `合成产物：逐段分配读数可加总，且每段都被真正走到`(@TempDir dir: Path) {
        assumeTrue(AllocMeter.open() != null, "宿主 JVM 不支持线程分配计数：跳过")
        val warm = dir.resolve("warm.erbc")
        Files.write(warm, ErbcWriter.encode(mixedProgram(200)))
        ProgramLoader.loadMapped(warm)
        System.gc()

        val f = dir.resolve("mixed.erbc")
        Files.write(f, ErbcWriter.encode(mixedProgram(30_000)))
        val p = profile(f)

        report(p, "合成产物")
        val names = p.stages.map { it.name }
        assertEquals(
            listOf(
                "装载头（open + 段布局校验）",
                LoadStages.STRINGS,
                LoadStages.CONSTANTS,
                LoadStages.EXPR_STRINGS,
                LoadStages.EXPR_NODES,
                LoadStages.EXPR_ROOTS,
                LoadStages.STMT_STRINGS,
                LoadStages.STMT_NODES,
                LoadStages.STMT_RECORDS,
                LoadStages.FUNCTIONS,
                LoadStages.CODE,
                LoadStages.LINES,
                LoadStages.CALL_ARGS,
                LoadStages.POOL_REFILL,
                LoadStages.VALIDATE,
                // 2026-10-24：语句实参规范化去重（种子段）挪进 decodeProgram 与引用范围校验重叠，
                // 由解码器自己发 INTERN mark（ProgramLoader 只给字节档兜底），故这里是第 16 段。
                LoadStages.INTERN,
            ),
            names,
            "段边界钩子的发射顺序（漏一段就说明 decodeProgram 与计量装置脱钩了）",
        )
        // 段 5 自 M8-COD-EMAP 起只映射、不装配 IntArray：整段 code 不再出现在堆分配里
        // （7.68 MB 的 IntArray 若重新出现，本断言当场红）；常驻口径由 codeResidentBytes 钉死。
        val codeStage = p.stages.single { it.name == LoadStages.CODE }
        assertEquals(0L, p.program.codeResidentBytes, "映射装载后 code 常驻堆字节应 = 0")
        assertTrue(
            codeStage.bytes < 1L shl 20,
            "段 5 只应建立映射窗口（堆分配 < 1 MiB），实为 ${codeStage.bytes} B：指令流被重新装配成 IntArray 了",
        )
        // 逐段之和必须等于整次装载：同一个单调计数器、同一个线程，账对不上就是漏了边界。
        // 残差 = 计量自身的开销（一次一个段快照对象，落在下一段/尾账上），上限 4 KiB。
        val sum = p.stages.sumOf { it.bytes }
        assertTrue(p.total >= sum, "整次装载的分配量(${p.total}) 不应小于逐段之和($sum)")
        assertTrue(p.total - sum <= 4096, "逐段分配之和与整次装载差 ${p.total - sum} B：段边界漏计")
        assertTrue(p.total > 0, "分配量读数不为 0")
    }

    // ------------------------------------------------------------------
    // 真实产物：走 ERATW_ERBC（与 `ErbcStreamPeakTest` 同一个门）
    // ------------------------------------------------------------------

    private fun realArtifact(): Path? {
        val raw = System.getenv("ERATW_ERBC")?.takeIf { it.isNotBlank() } ?: return null
        val p = Path.of(raw)
        val f = if (Files.isDirectory(p)) p.resolve("program.erbc") else p
        return f.takeIf { Files.isRegularFile(it) }
    }

    @Test
    fun `真实产物：逐段分配读数（前 三个大头）`() {
        val path = realArtifact()
        assumeTrue(path != null, "ERATW_ERBC 未指向可读的 program.erbc：跳过真实产物用例")
        assumeTrue(AllocMeter.open() != null, "宿主 JVM 不支持线程分配计数：跳过")
        val p = profile(path!!)
        report(p, "真实产物")
        assertEquals(p.program.instructionCount * 4, p.program.code.size, "code 数组形状")
        assertEquals(p.program.instructionCount, p.program.lines.size, "lines 数组形状")
        assertTrue(p.stages.size >= 3, "段边界至少要有 3 段")
    }
}
