package com.eratw.port.vmdbg

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.OffsetDateTime

/**
 * `explore` 子命令（CONTRACT-DBG6 §4.E1.5）：真实流程遍历器的 CLI 面。
 *
 * ## 参数
 *
 * ```
 * explore [--policy exhaustive|least-visited] [--seed N] [--max-episodes N]
 *         [--wall-min N] [--max-depth N] [--boot <配方文件>] [--seed-snap <快照>]
 *         [--sweep-ledger <文件或目录>] [--out <目录>] [--resume] （+ 全局 --erbc/--game/--work/--steps）
 * ```
 *
 * - `--policy` 缺省 exhaustive；`--seed` 缺省 20260226（策略实现内部的 `java.util.Random`
 *   种子，CONTRACT-DBG6 §0.3：禁 kotlin.random、确定性差分前提）；
 * - 预算三旋钮：`--max-episodes` 缺省 200、`--wall-min` 缺省 30（分钟/局）、`--max-depth`
 *   缺省 24（快照栈深度）；
 * - 种子三选一：`--boot <配方>`（覆盖内置 START 链）→ 引导成功后种子落 `<out>/seed.dbg`；
 *   `--seed-snap <文件>` 直用现成快照（CmdSnap 格式主文件 + sidecar；真机 Plan B 的
 *   adb pull 产物直接喂）；`--resume` 复用上次本命令 `<out>/seed.dbg`（跳过引导链）；
 *   都不给 = 内置 START 链（FLOW.md §2）；
 * - `--out <目录>` 缺省 `build/dbg-explore`：seed / boot-fail 现场 / explore.jsonl /
 *   findings.jsonl / replay / coverage.txt 都落这里（E2 台账全套，见 [ExploreOut]）；
 * - `--sweep-ledger <文件或目录>`（DBG7）：coverage.txt 的对照数据源，目录取其下 sweep.jsonl；
 *   缺省探测 `<out>/sweep.jsonl`。台账存在才做计数级对照（桌面 sweep 产物；真机 report.jsonl
 *   无 covered/stmts 字段对照不了），缺席则 coverage.txt 留 TODO(verify) 注明已探测路径。
 *
 * ## 组长接线缝（本文件的两枚 @Volatile；DBG6 合流已接第 1 条）
 *
 * 1. [policyFactory]：E2 策略工厂（Main.kt 的 explore 分发已接 `createExplorePolicy`）。
 * 2. [findingSink]：**同目录二选一**（E2 台账接线后的取舍，KDoc 即契约）——
 *    缺省 = [ExploreOutFindingSink]（E2 的 ExploreOut：findings.jsonl 四类发现 + 驱动器原生类
 *    透传、replay/ 食谱语法重放链、explore.jsonl 逐步台账、coverage.txt 覆盖计数 —— 后两样
 *    只有 E2 有）；E1 的 [JsonlFindingSink]（findings.jsonl 扁平形状 + replay/ep<局>-<序>.txt）
 *    保留作最小对照面。两套 findings.jsonl 字段形状不同（E2 带 fp/req/stackTop/chain/
 *    chainTimeout 数组，E1 只有扁平 detail + consoleTail），同目录双写 = 一个文件混两种
 *    schema，replay/ 编号体系也会互相踩 —— 外层显式 set [findingSink] 时请先删掉另一套的
 *    旧产物或换 `--out`。
 *
 * ## 退出码
 *
 * `0` = 引导 + 探索正常收工（发现不是工具错误，全部落 `--out`，与 sweep 同口径）；
 * `1` = 用法错误 / 装配失败 / **引导失败**（现场落 boot-fail.json + boot-fail-console.txt）。
 */
object CmdExplore {

    /** 策略工厂缝：输入 = `--policy` 名与 `--seed`；输出 = E2 的策略实现。 */
    fun interface PolicyFactory {
        fun create(policy: String, seed: Long): ExplorePolicy
    }

    /** 合流接线点（见类 KDoc 第 1 条）；null = 未接线（引导成功后报错）。 */
    @Volatile
    var policyFactory: PolicyFactory? = null

    /** 发现投递点（见类 KDoc 第 2 条）；null = 用缺省 [JsonlFindingSink]。 */
    @Volatile
    var findingSink: FindingSink? = null

    /** explore 的带值选项（Main.kt 的 Args.VALUE_OPTS 必须收录，见 [requireValueOptsWired]）。 */
    internal val VALUE_OPTS_NEEDED = listOf(
        "--policy", "--seed", "--max-episodes", "--wall-min", "--max-depth", "--boot", "--seed-snap",
        "--sweep-ledger",
    )

    fun run(parsed: Args, out: PrintStream, err: PrintStream): Int {
        requireValueOptsWired(parsed)
        val policyName = parsed.valueOr("--policy", "exhaustive")
        if (policyName != "exhaustive" && policyName != "least-visited") {
            throw CliError("--policy 只认 exhaustive|least-visited: $policyName")
        }
        val policySeed = parsed.longOr("--seed", 20260226L)
        val maxEpisodes = parsed.longOr("--max-episodes", 200L).toInt()
        if (maxEpisodes <= 0) throw CliError("--max-episodes 必须为正: $maxEpisodes")
        val wallMin = parsed.longOr("--wall-min", 30L)
        if (wallMin < 0) throw CliError("--wall-min 不得为负: $wallMin")
        val maxDepth = parsed.longOr("--max-depth", 24L).toInt()
        if (maxDepth <= 0) throw CliError("--max-depth 必须为正: $maxDepth")
        val outDir = Path.of(parsed.valueOr("--out", "build/dbg-explore")).toAbsolutePath().normalize()

        // 种子计划三选一；--seed-snap / --resume 优先于 --boot / 内置链（现成快照即跳过引导）。
        val seedSnap = parsed.value("--seed-snap")
        val plan: ExplorePlan = when {
            seedSnap != null -> ExplorePlan.SeedFile(Path.of(seedSnap).toAbsolutePath().normalize())
            parsed.flag("--resume") -> ExplorePlan.SeedFile(outDir.resolve("seed.dbg"))
            parsed.value("--boot") != null ->
                ExplorePlan.Boot(ExploreBoot.parseFile(Path.of(parsed.value("--boot"))))
            else -> ExplorePlan.Boot(ExploreBoot.BUILTIN_START)
        }
        val cfg = ExploreConfig(
            maxEpisodes = maxEpisodes,
            wallMs = wallMin * 60_000L,
            maxDepth = maxDepth,
            outDir = outDir,
        )
        out.println(
            "explore: policy=" + policyName + " seed=" + policySeed +
                " maxEpisodes=" + maxEpisodes + " wallMin=" + wallMin + " maxDepth=" + maxDepth,
        )
        out.println("out=" + outDir)

        val factory = policyFactory
        val sink = ExploreInputSink(cfg.tailLines)
        val assembled = Assembler.assemble(parsed, sinkOverride = sink)
        // E2 台账（局部名 ledger：不遮蔽 PrintStream 参数 out —— 同名会让 out.println 编译红）
        val ledger = ExploreOut(
            outDir,
            program = assembled.program,
            functions = assembled.metadata.functions,
            resume = parsed.flag("--resume"),
            sweepLedgers = sweepLedgerCandidates(parsed, outDir),
        )
        val driver = ExploreDriver(
            assembled = assembled,
            sink = sink,
            cfg = cfg,
            findings = findingSink ?: ExploreOutFindingSink(ledger),
            plan = plan,
            out = ledger,
            policyProvider = {
                factory?.create(policyName, policySeed)
                    ?: throw CliError(
                        "explore 策略未接线：CmdExplore.policyFactory 为 null —— " +
                            "待 E2 的 ExhaustivePolicy / LeastVisitedPolicy 合流后由组长接工厂（交付报告接线清单）",
                    )
            },
            err = err,
        )
        val result = driver.run()
        if (result.bootFailure != null) {
            out.println("status=BOOT_FAILED")
            out.println("reason=" + result.bootFailure)
            out.println("console=boot-fail-console.txt（同目录）")
            return 1
        }
        out.println("status=PASS")
        result.seedPath?.let { out.println("seed=$it") }
        result.anchor?.let { out.println("anchor=" + it.name) }
        out.println("episodes=" + result.episodes)
        val counts = result.outcomeCounts.entries.sortedByDescending { it.value }
        out.println("outcomes=" + counts.joinToString(",") { it.key.name + ":" + it.value })
        out.println("findings=" + result.findingsCount)
        out.println("ledger=explore.jsonl / findings.jsonl / replay/ / coverage.txt（同目录）")
        return 0
    }

    /**
     * coverage.txt 的 sweep 台账候选（按序探测、首个存在者生效；见 [ExploreOut] 的 sweepLedgers）：
     * 显式 `--sweep-ledger`（文件或目录）优先；缺省只探 `<out>/sweep.jsonl`
     * （把 sweep 产物拷进 explore 输出目录的用法；分片台账即选片文件传入）。
     */
    private fun sweepLedgerCandidates(parsed: Args, outDir: Path): List<Path> {
        val explicit = parsed.value("--sweep-ledger")
        if (explicit != null) return listOf(Path.of(explicit).toAbsolutePath().normalize())
        return listOf(outDir.resolve(ExploreOut.SWEEP_LEDGER_NAME))
    }

    /**
     * 带值选项接线自检：Main.kt 的 [Args] 把不在 VALUE_OPTS 里的 `--xxx` 记成布尔旗标、
     * 其值落进位置参数——那种形态下本命令拿到的参数是错位的（且无法复原归属）。
     * 与其静默错跑，不如当场报错并指到接线清单（Main.kt 不在 E1 文件所有权内）。
     */
    private fun requireValueOptsWired(parsed: Args) {
        val broken = VALUE_OPTS_NEEDED.filter { parsed.flag(it) }
        if (broken.isNotEmpty()) {
            throw CliError(
                "explore 的带值选项 $broken 被当成了旗标 —— 组长需把这 " + VALUE_OPTS_NEEDED.size +
                    " 个名字加入 Main.kt 的 Args.VALUE_OPTS（CONTRACT-DBG6 交付报告接线清单第 1 条）",
            )
        }
    }
}

/**
 * E2 台账适配器（DBG7 接线，[CmdExplore] 的缺省 findingSink）：把驱动器的发现投递转写到
 * [ExploreOut.finding]。与 [JsonlFindingSink] 的取舍见 [CmdExplore] 类 KDoc 第 2 条（同目录二选一）。
 *
 * - kind 原样透传（String 版 finding）：FindingKind 四类之外，驱动器原生类
 *   （BOOT_FAIL / ILLEGAL_ACTION / REPLAY_DIVERGE / POLICY_CRASH / FEEDER_ERROR）逐字进
 *   findings.jsonl，不做映射改写；
 * - detail 扁平键值折成 `k=v;k=v` 单串（ExploreOut 的 detail 字段是单串口径）；
 * - **replayInputs 忽略**：重放链的权威 = ExploreOut 自维护的食谱语法链（logBootStep/logStep
 *   逐次累积，带期望注记 + chainTimeout 超时位）；驱动器给的 actionLabel 串是它的降维投影，
 *   丢了注记与超时位，弃用 —— 连带好处：链完整性与 explore.jsonl 由同一实例保证，不漂。
 */
class ExploreOutFindingSink(private val ledger: ExploreOut) : FindingSink {

    override fun onFinding(kind: String, detail: Map<String, String>, consoleTail: List<String>, replayInputs: List<String>) {
        ledger.finding(
            kind,
            detail.entries.joinToString(";") { (k, v) -> k + "=" + v },
            episode = detail["episode"]?.toLongOrNull() ?: -1L,
            consoleTail = consoleTail,
        )
    }
}

/**
 * E1 缺省发现落盘（DBG6 的最小台账；DBG7 起 explore 缺省改走 [ExploreOutFindingSink]，见
 * [CmdExplore] 类 KDoc 第 2 条的同目录二选一契约）：
 * - `findings.jsonl`：每条发现一行（ts / kind / episode / detail 扁平键值 / consoleTail）；
 * - `replay/ep<局>-<序>.txt`：可重放输入链，每行一条输入（`输入` 裸格式，`;;` 后无期望 =
 *   不检查，与 `--boot` 配方格式直接兼容）。
 */
class JsonlFindingSink(private val outDir: Path) : FindingSink {

    private val lock = Any()
    private var seq = 0

    override fun onFinding(kind: String, detail: Map<String, String>, consoleTail: List<String>, replayInputs: List<String>) {
        synchronized(lock) {
            seq++
            val fields = ArrayList<Pair<String, Any?>>()
            fields.add("ts" to OffsetDateTime.now().toString())
            fields.add("kind" to kind)
            for ((k, v) in detail) fields.add(k to v)
            fields.add(
                "consoleTail" to consoleTail.joinToString("\\n"),
            )
            val line = Jsonl.obj(fields)
            Files.writeString(
                outDir.resolve("findings.jsonl"),
                line + "\n",
                Charsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
            if (replayInputs.isNotEmpty()) {
                val replayDir = outDir.resolve("replay")
                Files.createDirectories(replayDir)
                val ep = detail["episode"] ?: "?"
                Files.writeString(
                    replayDir.resolve("ep" + ep + "-" + seq + ".txt"),
                    replayInputs.joinToString("\n") + "\n",
                    Charsets.UTF_8,
                )
            }
        }
    }
}
