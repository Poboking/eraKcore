package com.eratw.port.core.loader

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
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
 * 装载峰值读数（CONTRACT-M6-A §2.1 判据②）：`loadMapped` 装同一份产物要多花多少字节。
 *
 * ## 装置复用（不新造尺子）
 *
 * 常驻 / 段内峰值直接复用 `com.eratw.port.perf.HeapMeter`（`perfBaseline`、`bootSmoke`、
 * `PoolDecomp` 用的是同一个 `used = totalMemory - freeMemory` 口径，读数可跨装置对照）。
 * [HeapMeter] 只回答「活着多少」，回答不了「这一次调用**碰过**多少字节」——
 * 而本任务要打掉的正是**整份产物的一次性拷贝**，它装完就被丢弃，常驻口径看不见。
 * 所以主指标另取 JVM 的**线程分配计数**（`com.sun.management.ThreadMXBean`）：
 * 单调累加、与 GC 时机无关，`readAllBytes` 的 323.7 MB 一定会出现在差值里。
 *
 * ## 三条路径（「前 / 后」的定义）
 *
 * | 标签 | 实现 | 对应 |
 * |---|---|---|
 * | `全读 loadFile` | `Files.readAllBytes` + `ErbcReader` | 装机前的老装载路 |
 * | `旧 mapped（fullBytes）` | `MappedErbc.fullBytes()` + `ProgramLoader.load` | **本任务改造前**的 `toProgram` |
 * | `流式 mapped` | `ProgramLoader.loadMapped`（[ErbcStreamDecoder]） | 本任务改造后 |
 *
 * 判据不是「峰值读数更小」这种会被 GC 时机左右的软指标，而是可复算的硬等式：
 * `分配(旧 mapped) - 分配(流式 mapped) ≥ 0.9 × 产物字节数` —— 省下的就是那一次整文件拷贝。
 *
 * ## 覆盖
 *
 * - 合成产物用例**无条件跑**（约 8 MB，秒级），把「不复制整份产物」钉成常驻护栏；
 * - 真实产物用例在 `ERATW_ERBC`（指向 `program.erbc` 或其目录）存在时跑：全量语料
 *   323,711,756 B / 4,799,524 指令（BOARD「ERBC 全量产物」行），给出前/后 MiB 读数与
 *   残余常驻的 `code + lines` 分解（h-mmap 已证伪「code/lines 是主缺口」，这里只报实测）。
 *
 * 峰值读数本身**不做绝对值断言**（G1 的 `peakUsage` 对短于一个 GC 周期的尖峰会低估，
 * `HeapMeter` 的 KDoc 已写明），只断言分配量差值并打印全部读数。
 *
 * ## 装载期 churn 的回归闸（2026-10-21，CONTRACT-M6-LOADCHURN）
 *
 * 分配量除「解出的 Program 本体」外，还包含装载期一次性临时量（churn）。流式路径实测：
 *
 * | 读数（328,799,098 B 全量产物 / 3g 堆） | 改前 | 改后 |
 * |---|---|---|
 * | 分配总量 | 2733.1 MiB（8.72× 产物） | 1081.5 MiB（3.45× 产物） |
 * | 额外瞬时（分配 − 常驻） | 1986.3 MiB | 465.7 MiB |
 * | 段内峰值 | 1474.0 MiB | 1055.7 MiB |
 * | 装载耗时 | 3683 ms | 2299 ms |
 *
 * 改前的大头是串池解码（`decodeToString(strict)` 每次新建 `CharsetDecoder` + `CharBuffer`，
 * 249 B/次 × 793 万次），改后见 `StrictUtf8` / `StreamStringTable` 的备忘表。
 *
 * 因此断言两条**分配倍数**上界（比绝对字节稳，跟产物大小同尺度）：合成样本 < 4.0×产物、
 * 真实产物 < 7.5×产物。改前分别是 6.2× 与 8.7×，任何一条回归到「每次解码都新建解码器」
 * 都会当场红。
 *
 * ⚠️ 真实产物那条倍数在 M10-MEM 之后从 4.5× 抬到 5.5×，并另加一条**常驻 < 0.5×产物**：
 * 4.5× 标的是「整池顺序解码 + 串池备忘 + 装载后整池规范化去重」那一条链（1081.5 MiB = 3.45×），
 * M6-PERF-INTERN 把整池去重（瞬时 ~590 MiB）挂上之后它就已经对不上了；段 3 惰性档又是
 * **有意关备忘**（备忘会把 131.0 MiB 原文钉住）。两条读数的分工与逐条理由见
 * `真实产物：三条路径的装载峰值读数` 用例里的注释。
 *
 * ⚠️ T2-64 波次 C「压扁即弃」把扁平池构建挪进装载链 ⇒ 装载账多出「整池流式解码 + 规范化 +
 * 编码」这一笔，两条闸按同一套理由重标：**分配 < 7.5×**、**常驻 < 1.55×产物**
 * （2026-10-28 本机实测 2135.4 MiB = 6.80× / post-GC 常驻 +437.5 MiB = 1.393×；同一份 Program
 * 再把语句树整池物化要 +223.4 MiB，即波次 B 稳态 ≈ +660.9 MiB = 2.104×）。
 * 常驻那条的判别力因此从「整池 vs 惰性」收窄成「树有没有被弃」——见用例内注释。
 */
class ErbcStreamPeakTest {

    // ------------------------------------------------------------------
    // 读数装置
    // ------------------------------------------------------------------

    /** 一次装载的读数。`extra` = 分配总量 - 常驻增量，即「装完就扔」的那部分（整文件拷贝在这里）。 */
    private class Sample(
        val label: String,
        val fileBytes: Long,
        val allocated: Long,
        val retainedBefore: Long,
        val retainedAfter: Long,
        val peak: Long,
        val ms: Long,
        val instructions: Int,
        val codeBytes: Long,
        val linesBytes: Long,
        val statements: Int,
        val strings: Int,
    ) {
        val retained: Long get() = retainedAfter - retainedBefore
        val extra: Long get() = allocated - retained
    }

    /**
     * 线程分配计数（`com.sun.management.ThreadMXBean`）。宿主 JVM 不支持时返回 null，
     * 调用方 [assumeTrue] 跳过而不是伪造读数。
     */
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
                // 探一次：代理未实现该方法时会抛 UnsupportedOperationException
                bean.getThreadAllocatedBytes(tid)
                AllocMeter(bean, tid)
            }.getOrNull()
        }
    }

    private fun sample(
        label: String,
        fileBytes: Long,
        meter: AllocMeter,
        block: () -> Program,
    ): Sample {
        HeapMeter.resetPeaks()
        val before = HeapMeter.settled()
        val a0 = meter.read()
        val t0 = System.nanoTime()
        val p = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val a1 = meter.read()
        val peak = HeapMeter.peak()
        val after = HeapMeter.settled()
        return Sample(
            label = label,
            fileBytes = fileBytes,
            allocated = a1 - a0,
            retainedBefore = before,
            retainedAfter = after,
            peak = peak,
            ms = ms,
            instructions = p.instructionCount,
            codeBytes = p.code.size.toLong() * 4L,
            linesBytes = p.lines.size.toLong() * 4L,
            statements = p.statementCount,
            strings = p.stringCount,
        )
    }

    /** 旧 mapped 路径（改造前的 `toProgram`）：整份产物顺序拷进堆再解码。 */
    private fun loadViaFullBytes(path: Path): Program = ProgramLoader.mapped(path).use { m ->
        ProgramLoader.load(m.fullBytes(), path.toString())
    }

    private fun report(s: Sample) {
        println(
            "[erbc-peak] ${s.label}：" +
                "产物 ${mib(s.fileBytes)} / 指令 ${s.instructions} / " +
                "分配 ${mib(s.allocated)} / 常驻前 ${mib(s.retainedBefore)} → 后 ${mib(s.retainedAfter)}" +
                "（+${mib(s.retained)}）/ 额外瞬时 ${mib(s.extra)} / 段内峰值 ${mib(s.peak)} / ${s.ms} ms",
        )
    }

    private fun mib(bytes: Long): String {
        val neg = bytes < 0
        val abs = if (neg) -bytes else bytes
        val tenths = (abs * 10 + 524_288) / 1_048_576
        return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10} MiB"
    }

    // ------------------------------------------------------------------
    // 合成产物：无条件跑，把「不复制整份产物」钉成护栏
    // ------------------------------------------------------------------

    /**
     * 量级样本：指令数可参数化，字符串/常量/语句池各有内容（让解码真的有事做）。
     * 与 `MmapLoaderTest.bigProgram` 同构，文件大小 ≈ `16 × 指令数 + 4 × 指令数 + 池`。
     */
    private fun bigProgram(instructions: Int): Program {
        val b = CodeBuilder()
        b.declareFunction("main", 0, 0, 2)
        val strings = (0 until 300).map { b.string("str-$it-驻留") }
        val consts = (0 until 50).map { b.constant(it.toLong() * 1_000_003L) }
        val stmts = (0 until 120).map {
            b.statement(InstructionStmt(FunctionCode.WAIT, VoidArgument(), it + 1, "WAIT  #$it"))
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

    /** 预热：类加载、JIT、`ErbcWriter`/两个解码器的常量池都先碰一遍，再开始计数。 */
    private fun warmUp(dir: Path) {
        val f = dir.resolve("warm.erbc")
        Files.write(f, ErbcWriter.encode(bigProgram(400)))
        ProgramLoader.loadFile(f)
        ProgramLoader.loadMapped(f)
        loadViaFullBytes(f)
        System.gc()
    }

    @Test
    fun `合成产物：流式 mapped 少分配整整一份产物，且 Program 计数不变`(@TempDir dir: Path) {
        val meter = AllocMeter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数（com.sun.management.ThreadMXBean）：跳过")
        val m = requireNotNull(meter)
        warmUp(dir)

        val file = dir.resolve("big.erbc")
        Files.write(file, ErbcWriter.encode(bigProgram(400_000)))
        val fileBytes = Files.size(file)
        assertTrue(fileBytes > 6L shl 20, "样本产物太小（$fileBytes B）：整文件拷贝会被噪声淹没")

        val full = sample("全读 loadFile", fileBytes, m) { ProgramLoader.loadFile(file) }
        System.gc()
        val mapped = sample("流式 mapped", fileBytes, m) { ProgramLoader.loadMapped(file) }
        System.gc()
        val legacy = sample("旧 mapped（fullBytes）", fileBytes, m) { loadViaFullBytes(file) }

        report(full)
        report(legacy)
        report(mapped)

        assertEquals(full.instructions, mapped.instructions, "两条路径指令数")
        assertEquals(full.codeBytes, mapped.codeBytes, "code 字节数（4 字/条）")
        assertEquals(full.linesBytes, mapped.linesBytes, "lines 字节数（1 项/条）")
        assertEquals(full.statements, mapped.statements, "语句池条目数")
        assertEquals(full.strings, mapped.strings, "字符串池条目数")

        val savedVsFull = full.allocated - mapped.allocated
        val savedVsLegacy = legacy.allocated - mapped.allocated
        assertTrue(
            savedVsFull >= fileBytes * 9 / 10,
            "loadFile 比流式 mapped 应多分配至少 0.9×产物（产物 $fileBytes B，" +
                "实差 $savedVsFull B；读数见上方 [erbc-peak]）",
        )
        assertTrue(
            savedVsLegacy >= fileBytes * 9 / 10,
            "旧 mapped（fullBytes）比流式 mapped 应多分配至少 0.9×产物（产物 $fileBytes B，" +
                "实差 $savedVsLegacy B；读数见上方 [erbc-peak]）",
        )
        // 流式路径的「额外瞬时」不应再包含整份产物：分配总量里除常驻 Program 外只剩解码临时量
        assertTrue(
            mapped.extra < fileBytes / 2,
            "流式 mapped 的额外瞬时量 ${mib(mapped.extra)} 已接近产物大小（${mib(fileBytes)}）：" +
                "整文件拷贝可能被重新引入",
        )
        // 装载期 churn 闸（合成样本：改前 6.2×产物、改后 2.5×产物，见类 KDoc 的表）
        assertTrue(
            mapped.allocated < fileBytes * 4,
            "流式 mapped 分配 ${mib(mapped.allocated)} 超过 4×产物（${mib(fileBytes * 4)}）：" +
                "串池解码快路径或备忘表被回退（读数见上方 [erbc-peak]）",
        )
    }

    // ------------------------------------------------------------------
    // 真实产物：前 / 后 MiB（ERATW_ERBC 动态门）
    // ------------------------------------------------------------------

    private fun realArtifact(): Path? {
        val raw = System.getenv("ERATW_ERBC")?.takeIf { it.isNotBlank() } ?: return null
        val p = Path.of(raw)
        val f = if (Files.isDirectory(p)) p.resolve("program.erbc") else p
        return f.takeIf { Files.isRegularFile(it) }
    }

    @Test
    fun `真实产物：三条路径的装载峰值读数（前 fullBytes vs 后 流式）`() {
        val path = realArtifact()
        assumeTrue(path != null, "ERATW_ERBC 未指向可读的 program.erbc：跳过真实产物用例")
        val meter = AllocMeter.open()
        assumeTrue(meter != null, "宿主 JVM 不支持线程分配计数：跳过")
        val m = requireNotNull(meter)
        val fileBytes = Files.size(path!!)

        val legacy = sample("旧 mapped（fullBytes）", fileBytes, m) { loadViaFullBytes(path) }
        System.gc()
        val mapped = sample("流式 mapped", fileBytes, m) { ProgramLoader.loadMapped(path) }
        System.gc()
        val full = sample("全读 loadFile", fileBytes, m) { ProgramLoader.loadFile(path) }
        System.gc()

        report(legacy)
        report(mapped)
        report(full)
        println(
            "[erbc-peak] 差额：流式比旧 mapped 少分配 ${mib(legacy.allocated - mapped.allocated)}" +
                "（产物 ${mib(fileBytes)}）；比 loadFile 少分配 ${mib(full.allocated - mapped.allocated)}",
        )
        println(
            "[erbc-peak] 残余常驻分解（流式路径）：code ${mib(mapped.codeBytes)} + " +
                "lines ${mib(mapped.linesBytes)} = ${mib(mapped.codeBytes + mapped.linesBytes)}" +
                "（占常驻增量 ${mapped.retained.let { if (it <= 0) "-" else "${1000 * (mapped.codeBytes + mapped.linesBytes) / it / 10}%" }}）" +
                "；语句池 ${mapped.statements} 条 / 字符串池 ${mapped.strings} 条，其余是池对象与去重索引",
        )

        assertEquals(legacy.instructions, mapped.instructions, "旧 mapped 与流式 mapped 的指令数")
        assertEquals(full.instructions, mapped.instructions, "loadFile 与流式 mapped 的指令数")
        assertEquals(full.codeBytes, mapped.codeBytes, "code 字节数")
        assertEquals(full.linesBytes, mapped.linesBytes, "lines 字节数")
        assertEquals(full.statements, mapped.statements, "语句池条目数")
        assertEquals(full.strings, mapped.strings, "字符串池条目数")

        val saved = legacy.allocated - mapped.allocated
        assertTrue(
            saved >= fileBytes * 9 / 10,
            "流式 mapped 应比旧 mapped 少分配至少 0.9×产物（产物 $fileBytes B，实差 $saved B）",
        )
        // ---- 装载期 churn 与常驻的回归闸（真实产物）------------------------------
        //
        // 口径换了（2026-10-21 M10-MEM），别照旧读数对：旧 4.5× 闸标的是**整池顺序解码 + 串池备忘**
        // 那一档（1081.5 MiB = 3.45×），M6-PERF-INTERN 把装载后整池规范化去重（瞬时 ~590 MiB）
        // 挂到同一条链上之后就再没过（`ProgramLoader` 的表：流式 mapped 1637.3 MiB = 5.18×）。
        // 段 3 惰性档则**有意关掉串池备忘**（备忘会把 131.0 MiB 原文钉住，惰性就白做），
        // 索引趟「解完即弃」的 churn 因此是 6,140,654 次字符串解码 + 1,716,637 条语句对象，
        // 实测 1594.5 MiB = 5.04×，比同一把尺子下的整池对照档（1638.9 MiB = 5.18×）还低。
        //
        // 波次 C（2026-10-28）**改了闸的口径**：装载链的 FLAT 段把整池逐条「解码 → 规范化 →
        // 写入扁平流 → 弃树」也算了进来（波次 B 把这笔放在 proc 桥构建期，不在装载账上）。
        // 本机实测 流式 mapped 分配 2135.4 MiB = 6.80×、post-GC 常驻 +437.5 MiB = 1.393×。
        // 两道闸按新形状重标，各自仍抓原来那类回退：
        //  - **churn 上界 7.5×**：仍能抓住「每次解码新建 CharsetDecoder/CharBuffer」
        //    （2026-10-21 实测 1594.5 MiB ⇒ 按 249 B/次 × 614 万次回退后 ≈ 9.6×）；
        //  - **常驻上界 1.55×产物**：扁平池本体（int 流 + 表达式结点 + 规范表 + 串表）必须驻留，
        //    实测 1.393×；再叠上语句树就是波次 B 的稳态 2.104×（同池实测 +223.4 MiB）⇒
        //    这条仍能抓住「树又整池驻留」那一类回退。
        assertTrue(
            mapped.allocated < fileBytes * 75 / 10,
            "流式 mapped 分配 ${mib(mapped.allocated)} 超过 7.5×产物（${mib(fileBytes * 75 / 10)}）：" +
                "串池解码快路径被回退（读数见上方 [erbc-peak]）",
        )
        assertTrue(
            mapped.retained < fileBytes * 155 / 100,
            "流式 mapped 常驻增量 ${mib(mapped.retained)} 超过 1.55×产物（${mib(fileBytes * 155 / 100)}）：" +
                "压扁即弃之后还多留住了一份语句树（同池实测 +223.4 MiB）⇒ 真机 512 MiB 挡位必 OOM",
        )
    }
}
