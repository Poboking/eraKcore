package com.eratw.port.core.loader

import com.eratw.port.core.code.Program
import com.eratw.port.perf.HeapMeter
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Android 生产装载路径**的逐段堆读数（内存车道，2026-11-03；只测量，不改语义）。
 *
 * ## 为什么另起一条（不套既有装置）
 *
 * 既有真产物用例（`LazyStatementDecodeTest` / `ErbcStreamPeakTest` / `LoadTimingProbe`）都按
 * `ProgramLoader.loadMapped(path)` 的**缺省档**跑，而缺省档 `flatStatements = true`；
 * 真机跑的是 `GameHost.loadAndRun` 传的 `flatStatements = false`（`GameHost.kt:2492`），
 * 两条档的区别不只是「建不建扁平池」：`flatStatements = true` 会把分片惰性源装进 Program，
 * 后续任何 `statement(i)` 都要穿过一层压扁/还原。读数混在一起就说不清设备上到底要装多少。
 * 本装置钉死：`compat = false`、`flatStatements = false` —— **与设备逐字同档**。
 *
 * ## 读数口径（三把尺子，缺一不可）
 *
 * | 尺子 | 取法 | 回答 |
 * |---|---|---|
 * | 瞬时 `used` | `Runtime.total - free`，段边界现读 | 该段边界上**堆里躺着**多少（含未回收垃圾） |
 * | 段内峰值 `peak` | `HeapMeter.resetPeaks()` + `peakUsage` 之和 | 这一段内部**碰到的最高点**（G1 对短尖峰会低估） |
 * | 催过 GC 的 `live` | `HeapMeter.settled()` 连打 N 轮 GC 取最小值 | 这一段结束时**真活着**多少（= ART 要装下的那一下） |
 *
 * 第三把尺子是唯一能拿去对 512 MiB 判据的：ART 在分配失败前自己会 GC，它要装下的是 live 高水位，
 * 不是 churn。三段读数同趟打印，分段名用 `LoadStages` 的原始段名。
 *
 * ## 两种跑法
 *
 * - `逐段瞬时与峰值`：**不插 GC**（与宿主 `LoadTrace` 的口径一致：只在三段上 `gcHint`）。
 *   这是「真机上装载期会发生什么」的近似；
 * - `逐段催 GC 的 live 高水位`：每个段边界都 `settled()`。慢得多，但它把「垃圾」从读数里剔掉，
 *   是**削减目标**的依据。
 *
 * 产物来自两个环境变量（缺一个只跳过对应那条）：
 * `ERATW_ERBC_DIR`（4.981 当前语料）/ `ERATW_ERBC_DIR_BASE`（4.980 对照语料）。
 * 注意 `ERATW_ERBC_DIR_BASE` 在本仓 build 脚本里**没有值源**（env.sh 不导、真产物档
 * wireRealArtifacts 只接 `ERATW_ERBC_DIR`/`ERATW_ERBC`）：不显式导出时 4.980 对照两条在任何
 * 口径都停在前置假设上 —— 这是接线缺口（2026-11-06 记，进「需组长接线」），不是闸失灵。
 */
// realArtifacts 家标记（realarttag 路 2026-11-06）：本类 5 条全是产物门控钉，供 gateRealArtifacts 圈定；判定逻辑一字不动。
@Tag("realArtifacts")
class AndroidLoadPeakTest {

    // ------------------------------------------------------------------
    // 产物查找
    // ------------------------------------------------------------------

    private fun artifact(env: String): Path? {
        val raw = System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val p = Path.of(raw)
        val f = if (Files.isDirectory(p)) p.resolve("program.erbc") else p
        return f.takeIf { Files.isRegularFile(it) }
    }

    /**
     * 产物门的消息文案：**未设置**与**设了但读不到**必须分开说，不许含混成一句。
     *
     * - `ERATW_ERBC_DIR_BASE` 未设置 = 本仓 build 脚本无该变量的值源（零接线，需组长接线）；
     * - `ERATW_ERBC_DIR` 未设置 = §5 裸口径本就不导出（真产物档 `-PwithRealArtifacts=true` 会接上）；
     * - 设了但读不到 = 操作者给错路径，原样回显路径让人对得上。
     */
    private fun gateMessage(env: String): String {
        val raw = System.getenv(env)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return when (env) {
                "ERATW_ERBC_DIR_BASE" -> "$env 未设置（本仓 build 脚本无该变量的值源：env.sh 不导、" +
                    "wireRealArtifacts 也不接，零接线）⇒ 对照档判不了 —— 需组长接线"
                else -> "$env 未设置（§5 裸口径不导出；真产物档 `-PwithRealArtifacts=true` 会接上，" +
                    "或显式导出后重跑）"
            }
        val p = Path.of(raw)
        val f = if (Files.isDirectory(p)) p.resolve("program.erbc") else p
        return "$env=$raw 下没有可读的 program.erbc：跳过（${if (Files.isDirectory(p)) "目录里没有" else "不是目录，"}" +
            "按文件给也只认 program.erbc 本身）"
    }

    // ------------------------------------------------------------------
    // 读数装置
    // ------------------------------------------------------------------

    private class StageRow(
        val name: String,
        val ms: Long,
        val used: Long,
        val peak: Long,
        val live: Long,
    )

    private class LoadReading(
        val label: String,
        val fileBytes: Long,
        val program: Program,
        val stages: List<StageRow>,
        val baseUsed: Long,
        val baseLive: Long,
        val afterUsed: Long,
        val afterLive: Long,
        val totalMs: Long,
    )

    /**
     * 生产档装载一趟并逐段读数。
     *
     * @param settleEveryStage true = 每个段边界催 GC 取 [HeapMeter.settled]（live 口径）；
     *   false = 只读瞬时 `used` 与段内 `peak`（宿主 LoadTrace 口径）。
     * @param compat 兼容装载档（`GameHost` 的 `compatLoad`）：装载侧 `serialPhases = true`
     *   ⇒ 段 3 索引趟与段 4/6/7 预取都**不另起线程**，在与段 2 不重叠的位置串行跑。
     */
    private fun probe(
        path: Path,
        label: String,
        settleEveryStage: Boolean,
        compat: Boolean = false,
    ): LoadReading {
        val baseUsed = HeapMeter.used()
        val baseLive = HeapMeter.settled()
        System.gc()
        val stages = ArrayList<StageRow>()
        var last = System.nanoTime()
        HeapMeter.resetPeaks()
        val t0 = System.nanoTime()
        val program = ProgramLoader.loadMapped(
            path,
            compat = compat,
            flatStatements = false,
        ) { stage ->
            val now = System.nanoTime()
            val used = HeapMeter.used()
            val peak = HeapMeter.peak()
            HeapMeter.resetPeaks()
            val live = if (settleEveryStage) HeapMeter.settled() - baseLive else 0L
            stages += StageRow(stage, (now - last) / 1_000_000L, used, peak, live)
            last = now
        }
        val totalMs = (System.nanoTime() - t0) / 1_000_000L
        val afterUsed = HeapMeter.used()
        val afterLive = HeapMeter.settled()
        return LoadReading(
            label = label,
            fileBytes = Files.size(path),
            program = program,
            stages = stages,
            baseUsed = baseUsed,
            baseLive = baseLive,
            afterUsed = afterUsed,
            afterLive = afterLive,
            totalMs = totalMs,
        )
    }

    private fun report(r: LoadReading, settleEveryStage: Boolean) {
        val p = r.program
        println(
            "[android-peak] ${r.label}：产物 ${mib(r.fileBytes)} / 指令 ${p.instructionCount} / " +
                "语句 ${p.statementCount}（物化 ${p.materializedStatementCount}）/ " +
                "串 ${p.stringCount} / 表达式 ${p.exprCount} / flatSeg=${p.flatResidentSegmentCount} / " +
                "装载 ${r.totalMs} ms",
        )
        println(
            "[android-peak]   JVM ${HeapMeter.jvmName()} / 堆上限 ${mib(HeapMeter.max())} / " +
                "GC ${HeapMeter.gcNames()} / 起读 used=${mib(r.baseUsed)} live=${mib(r.baseLive)}",
        )
        println("[android-peak]   段名 | 耗时 ms | used | 段内峰值 | live(催 GC 增量)")
        for (s in r.stages) {
            println(
                "[android-peak]   ${s.name} | ${s.ms} | ${mib(s.used)} | ${mib(s.peak)}" +
                    if (settleEveryStage) " | +${mib(s.live)}" else "",
            )
        }
        val topUsed = r.stages.maxByOrNull { it.used }
        val topPeak = r.stages.maxByOrNull { it.peak }
        val topLive = r.stages.maxByOrNull { it.live }
        println(
            "[android-peak]   ▶ 最高 used = ${mib(topUsed!!.used)} @ ${topUsed.name}；" +
                "最高段内峰值 = ${mib(topPeak!!.peak)} @ ${topPeak.name}",
        )
        if (settleEveryStage) {
            println("[android-peak]   ▶ 最高 live 高水位 = +${mib(topLive!!.live)} @ ${topLive.name}")
        }
        println(
            "[android-peak]   ▶ 装载后：used ${mib(r.afterUsed)}（+${mib(r.afterUsed - r.baseUsed)}）/ " +
                "常驻 live +${mib(r.afterLive - r.baseLive)}（= 装载后 ${mib(r.afterLive)}）/" +
                "距 512 MiB 余量 ${mib(512L * 1024 * 1024 - r.afterLive)}",
        )
    }

    /**
     * `jcmd <pid> GC.class_histogram`：常驻到底堆在哪些类上。
     *
     * 只做「取读数」：attach 失败（容器/hprof 不支持/受限）时整段跳过，不伪造读数。
     */
    private fun classHistogram(top: Int): List<String> {
        val jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd")
        if (!Files.isRegularFile(jcmd)) return emptyList()
        val pid = ProcessHandle.current().pid().toString()
        val text = runCatching {
            val proc = ProcessBuilder(jcmd.toString(), pid, "GC.class_histogram").redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out
        }.getOrNull() ?: return emptyList()
        // 行形如 `   1:          1234       567890  [B  (java.base@17...)`
        val rows = ArrayList<Triple<Long, Long, String>>()
        val re = Regex("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+.*)$")
        for (line in text.lineSequence()) {
            val m = re.find(line) ?: continue
            rows += Triple(m.groupValues[2].toLong(), m.groupValues[1].toLong(), m.groupValues[3].trim())
        }
        return rows.sortedByDescending { it.first }.take(top).map { (b, n, name) ->
            "${mib(b)} × $n  $name"
        }
    }

    // ------------------------------------------------------------------
    // 4.981（当前语料）
    // ------------------------------------------------------------------

    @Test
    fun `生产档 4_981 逐段瞬时与峰值`() {
        val f = artifact("ERATW_ERBC_DIR")
        assumeTrue(f != null, gateMessage("ERATW_ERBC_DIR"))
        val r = probe(f!!, "4.981 生产档（不插 GC）", settleEveryStage = false)
        report(r, settleEveryStage = false)
        assertEquals(0, r.program.materializedStatementCount, "生产档装载返回后不得物化语句树")
        assertTrue(r.program.instructionCount > 5_000_000, "指令数 ${r.program.instructionCount} 不像 4.981 全量产物")
    }

    @Test
    fun `生产档 4_981 逐段催 GC 的 live 高水位`() {
        val f = artifact("ERATW_ERBC_DIR")
        assumeTrue(f != null, gateMessage("ERATW_ERBC_DIR"))
        val r = probe(f!!, "4.981 生产档（逐段催 GC）", settleEveryStage = true)
        report(r, settleEveryStage = true)
        println("[android-peak]   常驻逐类（jcmd GC.class_histogram，前 20）：")
        for (line in classHistogram(20)) println("[android-peak]     $line")
        assertEquals(0, r.program.materializedStatementCount, "生产档装载返回后不得物化语句树")
    }

    // ------------------------------------------------------------------
    // 4.980（对照语料）
    // ------------------------------------------------------------------

    @Test
    fun `生产档 4_980 逐段瞬时与峰值`() {
        val f = artifact("ERATW_ERBC_DIR_BASE")
        assumeTrue(f != null, gateMessage("ERATW_ERBC_DIR_BASE"))
        val r = probe(f!!, "4.980 生产档（不插 GC）", settleEveryStage = false)
        report(r, settleEveryStage = false)
        assertEquals(0, r.program.materializedStatementCount, "生产档装载返回后不得物化语句树")
    }

    @Test
    fun `生产档 4_980 逐段催 GC 的 live 高水位`() {
        val f = artifact("ERATW_ERBC_DIR_BASE")
        assumeTrue(f != null, gateMessage("ERATW_ERBC_DIR_BASE"))
        val r = probe(f!!, "4.980 生产档（逐段催 GC）", settleEveryStage = true)
        report(r, settleEveryStage = true)
        println("[android-peak]   常驻逐类（jcmd GC.class_histogram，前 20）：")
        for (line in classHistogram(20)) println("[android-peak]     $line")
        // 2026-11-06 补判定：本用例原来是零断言（读了数就 PASS，读数本身没有牙齿）。
        // 两条都对照生产档不变量：物化口径与 mark 回调接线，缺一读数就是废表。
        assertEquals(0, r.program.materializedStatementCount, "生产档装载返回后不得物化语句树")
        assertTrue(
            r.stages.isNotEmpty(),
            "装载期一个 mark 都没打出来：LoadStageMark 回调没接上或装载在首个 mark 前就死了，live 读数作废",
        )
    }

    private fun mib(bytes: Long): String {
        val neg = bytes < 0
        val abs = if (neg) -bytes else bytes
        val tenths = (abs * 10 + 524_288) / 1_048_576
        return "${if (neg) "-" else ""}${tenths / 10}.${tenths % 10} MiB"
    }

    // ------------------------------------------------------------------
    // 512 MiB 挡位 A/B（子 JVM，`-Xmx512m`；`compat` 改前→改后）
    // ------------------------------------------------------------------

    /**
     * 本用例是**两段式**：父进程起两条子 JVM（各 `-Xmx512m`，一条生产档一条兼容档），
     * 子 JVM 里只跑 [childBody]。分两条进程而不是同进程连跑两趟，是因为惰性池把映射通道
     * 留给 Program，同一进程里第二趟的基线一定被上一趟的产物钉着（读数会虚高）。
     *
     * 目的：`ErbcStreamDecoder` 的 `serialPhases`（兼容档）在 2026-11-03 那一刀之后
     * 连「段 3 索引趟 + 段 4/6/7 预取」也不再与段 2 重叠 —— 这条用例量它省下多少峰值、
     * 付掉多少墙钟。缺 `java.class.path` 可用时整条跳过（不伪造读数）。
     */
    @Test
    fun `512m 挡位 生产档与兼容档装载峰值对照（子 JVM）`() {
        val f = artifact("ERATW_ERBC_DIR")
        assumeTrue(f != null, gateMessage("ERATW_ERBC_DIR"))
        val cp = System.getProperty("java.class.path")?.takeIf { it.isNotBlank() }
        assumeTrue(cp != null, "取不到 java.class.path：跳过子 JVM 对照")
        // Windows 的 `java.home/bin/java` 没有扩展名 ⇒ 两种写法都试（`Files.isRegularFile` 不认 PATHEXT）。
        val javaBin = Path.of(System.getProperty("java.home"), "bin")
        val java = listOf(javaBin.resolve("java"), javaBin.resolve("java.exe"))
            .firstOrNull { Files.isRegularFile(it) }
        assumeTrue(java != null, "java 可执行不在 $javaBin：跳过")
        // 挡位可换（默认 512m = 设备上限）；额外 JVM 参数从 `ERATW_PEAK_JVM_ARGS` 空格分隔取
        // （例如 `-XX:ActiveProcessorCount=2` 把并发线程的推进速度压回低端机水平）。
        val xmx = System.getenv("ERATW_PEAK_XMX")?.trim()?.takeIf { it.isNotEmpty() } ?: "512m"
        val extra = System.getenv("ERATW_PEAK_JVM_ARGS")
            ?.trim()?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList()
        val readings = ArrayList<Pair<String, Int>>()
        for (mode in listOf("prod", "compat")) {
            val cmd = ArrayList<String>()
            cmd += java!!.toString()
            cmd += "-Xmx$xmx"
            cmd += "-Xms16m"
            // 子进程的标准输出按 UTF-8 编（JDK 17 缺省跟平台编码，中文读数会被父进程的
            // UTF-8 reader 解成替换字符；2026-11-03 踩过）。
            cmd += "-Dfile.encoding=UTF-8"
            cmd += "-Dstdout.encoding=UTF-8"
            cmd += "-Dstderr.encoding=UTF-8"
            cmd += extra
            cmd += listOf("-cp", cp!!, CHILD_MAIN, mode)
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            println("==== 子 JVM（-Xmx512m，mode=$mode）exit=$code ====")
            // 子进程整段回显：装载失败/异常也在这里看得见（不筛行，免得把病因筛掉）。
            for (line in out.lineSequence()) println("  $line")
            readings += mode to code
        }
        for ((mode, code) in readings) {
            assertEquals(0, code, "子 JVM mode=$mode 未正常结束（exit=$code）：看上面子进程输出")
        }
    }

    /**
     * 子 JVM 的实体（入口见文件末的 `main`）：一趟装载 + 逐段读数，**不做断言**
     * （断言留在父进程）。档位由 `args[0]` 决定：`prod` / `compat`。
     */
    internal fun childRun(args: Array<String>) {
        val compat = args.firstOrNull() == "compat"
        val f = artifact("ERATW_ERBC_DIR")
        if (f == null) {
            println("[android-peak] ERATW_ERBC_DIR 未指向可读的 program.erbc：子 JVM 无事可做")
            return
        }
        val label = if (compat) {
            "4.981 兼容档 compat=true（-Xmx512m）"
        } else {
            "4.981 生产档 compat=false（-Xmx512m）"
        }
        val r = probe(f, label, settleEveryStage = false, compat = compat)
        report(r, settleEveryStage = false)
        // 段 3 语句记录 + 段 4/6/7 单独拎出来：设备上就是这一段顶到天花板的，
        // 而兼容档这一刀动的正是「段 3 与段 4/6/7 是否重叠」。
        for (s in r.stages) {
            if (s.name.contains("语句记录") || s.name.contains("段4") || s.name.contains("段6") ||
                s.name.contains("段7") || s.name.contains("规范化去重")
            ) {
                println("[android-peak]   ↳ ${s.name}：used ${mib(s.used)} / 段内峰值 ${mib(s.peak)} / ${s.ms} ms")
            }
        }
        val topUsed = r.stages.maxByOrNull { it.used }!!
        val topPeak = r.stages.maxByOrNull { it.peak }!!
        println(
            "[android-peak] ▶▶ 摘要 mode=${if (compat) "compat" else "prod"}：" +
                "最高 used ${mib(topUsed.used)} @ ${topUsed.name} / " +
                "最高段内峰值 ${mib(topPeak.peak)} @ ${topPeak.name} / " +
                "装载 ${r.totalMs} ms / 语句物化 ${r.program.materializedStatementCount} / " +
                "指令 ${r.program.instructionCount}",
        )
    }

    private companion object {
        /** 子 JVM 的入口类（本文件顶层 `main` 的 JVM 类名）。 */
        private const val CHILD_MAIN = "com.eratw.port.core.loader.AndroidLoadPeakTestKt"
    }
}

/**
 * 子 JVM 入口：`java -Xmx512m -cp <test runtime classpath> com.eratw.port.core.loader.AndroidLoadPeakTestKt prod|compat`
 *
 * 之所以不用 JUnit 跑子进程：本模块只依赖 JUnit5，没有 `org.junit.runner.JUnitCore` 这个 JUnit4 入口
 * （2026-11-03 踩过：子进程 `ClassNotFoundException` ⇒ 父进程只看到 exit=1）。
 */
fun main(args: Array<String>) {
    AndroidLoadPeakTest().childRun(args)
}
