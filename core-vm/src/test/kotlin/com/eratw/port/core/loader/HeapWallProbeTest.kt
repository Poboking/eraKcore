package com.eratw.port.core.loader

import com.eratw.port.core.code.Program
import com.eratw.port.perf.HeapMeter
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 512 MiB 装机挡位的**装载墙**（判据装置，只测量，不改语义）。
 *
 * ## 这个数为什么必须量
 *
 * 真机侧留下的是「启动失败：堆不足」，而桌面闸默认跑在 3g（`core-vm/build.gradle.kts` 的
 * `maxHeapSize`），`Runtime.maxMemory()` 口径的读数天然被上限夹住 —— 于是「内存车道修完了没有」
 * 在桌面上根本没有判据。本装置把**被测进程本身**降到真机 `largeHeap` 挡位，判据只有一条：
 * **同一个 512 MiB 堆里，4.981 的装载峰值不许超过 512 MiB**。
 *
 * 2026-11-03 首测（`main` 的 T2-64 波次 D 之后，同一挡位跑了 5 轮）：4.981 峰值 **55–121 MiB**
 * （0.15–0.33× 367 MiB 产物）、装载后常驻 **+10 MiB**、装载 1.3–3.6 s；4.980 峰值 59–129 MiB、
 * 常驻同样 +10 MiB。摆幅来自「mark 点读 `used` 不催 GC」这一档口径（churn 收不收由 G1 自己决定），
 * 池峰值交叉列同步摆动。
 * 首测当时 `ProgramLoader.estimatedHeapBytes` 用 1.45× 给 4.981 报 **540 MiB**（标定自波次 C 的
 * 「装载期压扁」，那一段在波次 D 已取消）⇒ 512 MiB 机型是被**预检**挡在门外的（`GameHost.kt:2167`
 * 的 release 硬闸），不是被装载压垮的 —— 本装置的读数就是那次重标（2026-11-03，产物项改 0.8×，
 * 4.981 报 ≈301 MiB）的依据。
 * TODO(verify)：ART 上的同一份产物没有真机读数（`adb` 不在场）；本装置给的是 HotSpot 的数，只能证明
 * 「装载链本身不是墙」，不能代替真机取证。运行期那堵墙（`devtools/heap_gate.py` 标定 512m 下
 * 首读 471 MiB）在 `GameSession` 装配与游玩期，不在本夹具的射程内。
 *
 * 为什么不是「fork 一个 -Xmx512m 子 JVM 再回读 stdout」：早先的 Gradle test worker 把应用类挂在
 * 自定义 ClassLoader 上，`java.class.path` 只有 `gradle-worker.jar`，子 JVM 拿不到
 * `Program`/`ProgramLoader` 的类路径（2026-11-06 实测 8.10.2 的 worker 会把全量 runtime
 * classpath 放回 `java.class.path`，`AndroidLoadPeakTest` 的子 JVM 对照正是走这条路）。
 * 本装置仍选「被测进程本身就是设备挡位」—— 读数口径最短，也不押注 worker 类路径形态这种
 * 随版本变的细节。
 *
 * ## 口径（三件都对齐游戏宿主）
 *
 * 1. 夹具：`ProgramLoader.loadMapped(path, compat = false, flatStatements = false)`
 *    —— 与 `GameHost.kt:2492` 同一组实参（设备侧扁平池关、非兼容档）；
 * 2. 逐段读数：`LoadStageMark` 回调里读 `HeapMeter.used()`（= `totalMemory - freeMemory`，
 *    与宿主 `LoadTrace` 同口径）。**刻意不在每个 mark 点催 GC**：ART 也不会在你要下一笔分配
 *    之前替你收，装载期「还没被回收的垃圾」正是 512 MiB 挡位要一起装下的那部分；
 *    每点催 5 轮 GC（`HeapMeter.settled()`）会把这条量成「活物高水位」，40 余个点还要多付
 *    两三分钟 GC，且测的就不是装机场景了。段内真正的高水位另用 `HeapMeter.peak()`
 *    （各堆池 `peakUsage` 之和）交叉核对一列；
 * 3. 常驻：装载返回后 `HeapMeter.settled()`（连打 5 轮 GC 取最小值）**减去装载前同一口径的基线**
 *    —— 基线里必然含 test worker 自身那几十 MiB，所以表头把基线单列出来，增量才是「装载吃掉的」。
 *    读数期间必须对装好的 `Program` 保持强引用，否则 GC 顺手收掉它，常驻读数变成 +0 MiB 的假数。
 *
 * ## 墙在哪
 *
 * 墙 = 本进程的 `Runtime.maxMemory()`：在设备挡位下这两者是同一个数（`-PtestXmx=512m`
 * ⇒ 512 MiB），所以判据就是任务要的那一条「4.981 峰值 ≤ 512 MiB」。挡位只许**往低**换
 * （≤ 512 MiB，往高换等于换台机器量，`峰值 ≤ 墙` 会恒绿）：
 *
 * | 挡位 | 用途 |
 * |---|---|
 * | `-PtestXmx=512m` | 判据档（真机 `largeHeap`） |
 * | `-PtestXmx=96m` 一类 | 可证伪性：装置在装不下的挡位上必须红，且报出超了多少 MiB |
 *
 * ## 读哪里
 *
 * 输出 = stdout 的 `[heap-wall]` 行 + UTF-8 表 `core-vm/build/heap-wall-<版本>.md`（Gradle 对
 * **绿**用例不回显 stdout，Windows 控制台还是 GBK，中文段名会花 ⇒ 文件才是权威出口，惯例同
 * `deviceTierProbe` 的 `build/device-tier-report.md`）。
 *
 * ## 变量（缺一个都显式打印「未测」，不静默）
 *
 * | 变量 | 用途 |
 * |---|---|
 * | `ERATW_ERBC_DIR` | 4.981 产物目录（含 `program.erbc` / `program.meta`） |
 * | `ERATW_ERBC_DIR_BASE` | 4.980 产物目录（对照；缺失只跳过对照） |
 * | `ERATW_GAMEDIR` | 游戏根（装载链不读它，只在表头记一笔，便于提交信息对上语料） |
 *
 * 仓里读真产物的用例如果写成 JUnit 动态用例，变量缺失时**不生成也不计 skipped**（看着全绿）；
 * 这里用普通 `@Test` + `assumeTrue`，缺变量是 `aborted` 且在表里写明原因。
 *
 * ```
 * ./gradlew --configure-on-demand :core-vm:cleanTest :core-vm:test --tests "*HeapWallProbeTest*" -PtestXmx=512m
 * ```
 */
// realArtifacts 家标记（realarttag 路 2026-11-06）：本类 2 条全是产物门控钉，供 gateRealArtifacts 圈定；判定逻辑一字不动。
@Tag("realArtifacts")
class HeapWallProbeTest {

    /** 一个 mark 点的读数行。 */
    private data class Row(
        val stage: String,
        val usedMiB: Long,
        val headroomMiB: Long,
        val overBaseMiB: Long,
        val ms: Long,
    )

    /** 一次装载的全部读数。 */
    private class Wall(
        val label: String,
        val dir: Path,
        val limitMiB: Long,
    ) {
        val rows = ArrayList<Row>(64)
        var fileMiB = 0L
        var estimateMiB = -1L
        var baseMiB = 0L
        var retainedMiB = 0L
        var poolPeakMiB = 0L
        var statements = 0
        var instructions = 0
        var materialized = 0
        var loadMs = 0L
        var oom: Throwable? = null
        var failure: Throwable? = null

        /** 逐 mark 点已用堆的最大值（= 判据「峰值」）；装载前基线也算一路，避免 0 段产物读成负超。 */
        val peakMiB: Long get() = maxOf(rows.maxOfOrNull { it.usedMiB } ?: 0L, baseMiB)

        val peakStage: String get() = rows.maxByOrNull { it.usedMiB }?.stage ?: "（装载期一个 mark 都没打出来）"

        /** 死点前最后一行读数（OOM 档的「卡在哪」）。 */
        val lastRow: Row? get() = rows.lastOrNull()

        val overflowMiB: Long get() = peakMiB - limitMiB

        val loadOk: Boolean get() = oom == null && failure == null

        val verdict: String
            get() = when {
                // OOM 档的超出量只有**下界**：顶穿的那一笔分配落在两次 mark 之间，采样看不见它，
                // 能确定的只是「上限 − 死前已用」这段余量不够下一次分配。
                oom != null -> "装不下：装载在「${lastRow?.stage ?: "装载开始"}」之后 OOM 被杀" +
                    "（死前已用 ${lastRow?.usedMiB ?: 0} MiB / 上限 $limitMiB MiB，剩下 " +
                    "${limitMiB - (lastRow?.usedMiB ?: 0)} MiB 不够下一笔分配；顶穿的那一笔不在 mark 上，" +
                    "所以超出量只能给下界 ${maxOf(0L, overflowMiB)} MiB）"
                failure != null ->
                    "装载抛异常（不是 OOM）：${failure!!.javaClass.simpleName}: ${failure!!.message?.lineSequence()?.firstOrNull()}"
                overflowMiB > 0 -> "超墙：峰值 $peakMiB MiB 超过 $limitMiB MiB 挡位 **${overflowMiB} MiB**"
                else -> "装得下：峰值 $peakMiB MiB，距 $limitMiB MiB 余 ${-overflowMiB} MiB"
            }
    }

    // ==================================================================
    // 1. 4.981（判据）
    // ==================================================================

    @Test
    fun `4_981 在 512 MiB 挡位的装载峰值不超墙`() {
        val dir = gate("ERATW_ERBC_DIR")
        val wall = measure("4.981", dir)
        publish(wall)

        assertTrue(
            wall.oom == null,
            "4.981 在 ${wall.limitMiB} MiB 挡位装载 OOM（死前最后一段「${wall.lastRow?.stage}」已用 " +
                "${wall.lastRow?.usedMiB} MiB / 上限 ${wall.limitMiB} MiB，顶穿的是它之后那笔分配）：" +
                "真机「启动失败：堆不足」的桌面挡位复现。读数见 build/heap-wall-4.981.md",
        )
        assertTrue(
            wall.peakMiB <= wall.limitMiB,
            "4.981 装载峰值 ${wall.peakMiB} MiB > ${wall.limitMiB} MiB 挡位：**超 ${wall.overflowMiB} MiB**" +
                "（峰值落在「${wall.peakStage}」；逐段读数见 build/heap-wall-4.981.md）",
        )
    }

    // ==================================================================
    // 2. 4.980（对照：同一装置在旧产物上必须量得出一条不超墙的读数）
    // ==================================================================

    @Test
    fun `4_980 对照装载在 512 MiB 挡位装得下`() {
        val dir = gate("ERATW_ERBC_DIR_BASE")
        val wall = measure("4.980", dir)
        publish(wall)

        // 对照档只证明本装置的口径能产出一条不超墙的读数 —— 若连 4.980（预检估算 462 MiB、
        // 桌面实测峰值 59–129 MiB）都在设备挡位里死掉，说明装置把 worker 自身的开销算进了产物，
        // 4.981 那条读数就不足以为凭。
        assertTrue(
            wall.loadOk,
            "4.980 对照在 ${wall.limitMiB} MiB 挡位也装不下（${wall.verdict}）：装置口径可疑，" +
                "4.981 的读数不可采信",
        )
    }

    // ==================================================================
    // 装置
    // ==================================================================

    /**
     * 变量与挡位都到齐才测；缺一都**显式打印 + 落盘**（缺变量不许静默变绿）。
     *
     * 挡位不齐也算「未测」而不是红：`:core-vm:test` 在 §5 全闸里跑在 3g，本闸没被 `-PtestXmx=512m`
     * 降到设备挡位时读数毫无意义，红在那里只会误导别人；但也不能静默，所以横幅 + aborted 两条一起给。
     * 只许**往低**换挡（≤ 512 MiB）：往高换就是在量另一台机器，`peak ≤ 墙` 那条会恒绿。
     */
    private fun gate(envName: String): Path {
        val dir = System.getenv(envName)?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        val maxMiB = HeapMeter.max() / MIB
        val problems = ArrayList<String>(2)
        if (dir == null) {
            problems += when (envName) {
                // 孤儿 env（2026-11-06 记）：全仓 build 脚本里没有它的值源 —— env.sh 不导、
                // 真产物档 wireRealArtifacts 只接 ERATW_ERBC_DIR/ERATW_ERBC 不接它 ⇒ 不显式
                // 导出时 4.980 对照在任何口径都到不了。判不了就明说，不许含混成「未设置」一句。
                "ERATW_ERBC_DIR_BASE" -> "$envName 未设置（本仓 build 脚本无该变量的值源：env.sh 不导、" +
                    "wireRealArtifacts 也不接，零接线）⇒ 4.980 对照档判不了 —— 需组长接线"
                "ERATW_ERBC_DIR" -> "$envName 未设置（§5 裸口径不导出；真产物档 `-PwithRealArtifacts=true` " +
                    "会经 wireRealArtifacts 接上，或显式导出后重跑）"
                else -> "$envName 未设置"
            }
        } else if (!Files.isRegularFile(dir.resolve("program.erbc"))) {
            problems += "$envName=$dir 下没有 program.erbc"
        }
        if (maxMiB > DEVICE_WALL_MIB) {
            problems += "堆挡位 $maxMiB MiB 高于真机挡位 $DEVICE_WALL_MIB MiB ⇒ 读数被上限夹住，量不出墙" +
                "（缺 -PtestXmx=512m）"
        }
        if (problems.isEmpty()) return dir!!

        val banner = buildString {
            appendLine("## 装载墙：未测（这不是绿，也不是内存结论）")
            appendLine()
            appendLine("- 变量：$envName，挡位 $maxMiB MiB")
            appendLine("- 游戏根：${System.getenv("ERATW_GAMEDIR") ?: "（未设置）"}")
            problems.forEach { appendLine("- 卡点：$it") }
            appendLine()
            appendLine("复现：")
            appendLine("```")
            appendLine(COMMAND)
            appendLine("```")
        }
        println(banner)
        writeReport("skipped", banner)
        assumptions(problems.joinToString("；"))
        return dir!!
    }

    /** 缺变量/挡位不齐时走 abort（`assumeTrue`），不把「未测」伪装成红。 */
    private fun assumptions(reason: String): Nothing {
        assumeTrue(false, "装载墙未测：$reason")
        throw IllegalStateException("unreachable")
    }

    /** 一次冷装载（生产夹具）+ 逐 mark 点读数 + post-GC 常驻。 */
    private fun measure(label: String, dir: Path): Wall {
        // 墙 = 本进程的堆上限：真机挡位下这两者同义（`-PtestXmx=512m` ⇒ maxMemory 恰好 512 MiB），
        // 而往低换挡做可证伪性时，墙跟着降 —— 装置在「装不下」的挡位上必须红，不许恒绿。
        val wall = Wall(label, dir, HeapMeter.max() / MIB)
        val erbc = dir.resolve("program.erbc")
        val meta = dir.resolve("program.meta")
        wall.fileMiB = Files.size(erbc) / MIB
        // 预检估算只作参照（`estimateHeap` 读文件头，不解码）：它给的是「该有多大堆」，
        // 本装置的判据是「实际峰值多大」——两者差多少就是估算的松紧。
        wall.estimateMiB = runCatching { ProgramLoader.estimateHeap(erbc, meta).requiredMiB }.getOrDefault(-1L)

        // 基线：与终值同一口径（GC 5 轮取最小），差值才是「装载吃掉的」
        val base = HeapMeter.settled()
        wall.baseMiB = base / MIB
        HeapMeter.resetPeaks()
        // 强引用必须留在方法作用域里：装载返回的 Program 一旦不可达，`settled()` 的那几轮 GC
        // 会把它连同整个语句/串池一起收掉 ⇒ 常驻读数变成 +0 MiB（2026-11-03 首版就是这样假读数）。
        var alive: Program? = null
        val t0 = System.nanoTime()
        try {
            val program = ProgramLoader.loadMapped(
                erbc,
                compat = false,
                flatStatements = false,
                mark = LoadStageMark { stage ->
                    val used = HeapMeter.used()
                    wall.rows += Row(
                        stage = stage,
                        usedMiB = used / MIB,
                        headroomMiB = wall.limitMiB - used / MIB,
                        overBaseMiB = (used - base) / MIB,
                        ms = (System.nanoTime() - t0) / 1_000_000,
                    )
                },
            )
            wall.statements = program.statementCount
            wall.instructions = program.instructionCount
            wall.materialized = program.materializedStatementCount
            // 惰性池把产物通道留给了 Program（CMP-19）：放掉映射，下一个产物才拿到干净的 fd/映射面
            program.releaseMmap()
            alive = program
        } catch (t: OutOfMemoryError) {
            wall.oom = t
        } catch (t: Throwable) {
            wall.failure = t
        } finally {
            wall.loadMs = (System.nanoTime() - t0) / 1_000_000
        }
        wall.poolPeakMiB = HeapMeter.peak() / MIB
        // 常驻：装载后 GC 5 轮取最小（OOM 档也照样读 —— 装载期垃圾被收掉之后的活物同样是读数）。
        // `alive` 在读数期间一直可达，收掉的是垃圾而不是被量的那一套池。
        wall.retainedMiB = (HeapMeter.settled() - base) / MIB
        check(alive == null || alive!!.materializedStatementCount == wall.materialized) {
            "读数期间装载结果被人动过：已物化条数从 ${wall.materialized} 变成了 ${alive!!.materializedStatementCount}"
        }
        return wall
    }

    /** 打印 + 落盘一张表。 */
    private fun publish(wall: Wall) {
        val text = render(wall)
        text.lineSequence().forEach { println("[heap-wall] $it") }
        writeReport(wall.label, text)
    }

    private fun render(wall: Wall): String {
        val maxMiB = HeapMeter.max() / MIB
        val sb = StringBuilder(8 * 1024)
        sb.appendLine("## 装载墙读数：${wall.label}（挡位 $maxMiB MiB，墙 ${wall.limitMiB} MiB）")
        sb.appendLine()
        sb.appendLine("- 产物：`${wall.dir}`（`program.erbc` ${wall.fileMiB} MiB）")
        sb.appendLine("- 夹具：`ProgramLoader.loadMapped(compat = false, flatStatements = false)`（= `GameHost.kt:2492`）")
        sb.appendLine("- 游戏根：${System.getenv("ERATW_GAMEDIR") ?: "（未设置，装载链不读）"}")
        sb.appendLine(
            "- JVM：${HeapMeter.jvmName()}；收集器 ${HeapMeter.gcNames()}；inputArgs=${HeapMeter.inputArgs()}",
        )
        sb.appendLine(
            "- 装载前基线 ${wall.baseMiB} MiB（含 test worker 自身）；预检估算 ${wall.estimateMiB} MiB；" +
                "语句池 ${wall.statements} 条（装载后已物化 ${wall.materialized} 条）/ 指令 ${wall.instructions} / " +
                "装载 ${wall.loadMs} ms",
        )
        sb.appendLine()
        sb.appendLine("| # | 段名 | 已用 MiB | 距 ${wall.limitMiB} MiB 余量 | 较基线 MiB | ms |")
        sb.appendLine("|---:|---|---:|---:|---:|---:|")
        wall.rows.forEachIndexed { i, r ->
            sb.appendLine("| ${i + 1} | `${r.stage}` | ${r.usedMiB} | ${r.headroomMiB} | ${r.overBaseMiB} | ${r.ms} |")
        }
        sb.appendLine()
        sb.appendLine("### 表尾")
        sb.appendLine()
        sb.appendLine("- **峰值 ${wall.peakMiB} MiB** @ `${wall.peakStage}`（逐 mark 点 `HeapMeter.used()` 的最大值）")
        sb.appendLine(
            "- **常驻 +${wall.retainedMiB} MiB**（装载后 `HeapMeter.settled()` 5 轮取最小 − 基线）",
        )
        sb.appendLine("- 交叉核对：JVM 各堆池峰值之和 ${wall.poolPeakMiB} MiB（`HeapMeter.peak()`；G1 会低估短尖峰）")
        sb.appendLine("- **结论：${wall.verdict}**")
        if (wall.rows.isEmpty()) {
            sb.appendLine("- 注意：装载期一个 mark 都没打出来 —— 不是 0 段，是回调没接上或装载在第一个 mark 前就死了")
        }
        sb.appendLine()
        sb.appendLine("复现：`$COMMAND`")
        return sb.toString()
    }

    private fun writeReport(tag: String, body: String) {
        runCatching {
            val out = Path.of("build", "heap-wall-$tag.md")
            out.parent?.let { Files.createDirectories(it) }
            Files.write(out, body.toByteArray(StandardCharsets.UTF_8))
            println("[heap-wall] 表写入 $out")
        }.onFailure { println("[heap-wall] 表写入失败：${it.message}") }
    }

    private companion object {
        const val MIB = 1024L * 1024L

        /**
         * 真机 `largeHeap` 挡位：判据的天花板，**不许为了让红灯变绿而改**。
         *
         * 墙本身取 `Runtime.maxMemory()`（换挡跑到更低的挡位时墙跟着降，装置才不会恒绿）；
         * 这个常量只用来拒绝「比真机还大的挡位」，以及写进表头给人对账。
         */
        const val DEVICE_WALL_MIB = 512L

        const val COMMAND =
            "./gradlew --configure-on-demand :core-vm:cleanTest :core-vm:test " +
                "--tests \"*HeapWallProbeTest*\" -PtestXmx=512m"
    }
}
