package com.eratw.port.vmdbg

import com.eratw.port.core.host.InputRequest
import java.util.Random

/**
 * 浸泡档（obs-soak 车道）：把「随机操作 + 随机等待玩几十分钟」变成可复跑读数。
 *
 * ## 为什么要它
 *
 * 既有装置回答的是「能不能启动」（explore 引导链 / bootSmoke）与「能不能走通开局链」
 * （explore 逐支全排列 / hawkeye play），但「随机操作 + 随机等待长时间游玩、把偶发冻结
 * 变成可复跑读数」没有——玩家报的「玩着玩着卡死、点击没反应」正是这类：`turn Nms`
 * （口径 = 提交输入 → 脚本到达下一个等待点）出现 `2ms → 2342ms → 15192ms → 58515ms`
 * 的尾部劣化。平均值会被大量快页稀释，要看的是 n/p50/p95/max 与「单步超过阈值的慢步」。
 *
 * ## 与 explore 的关系
 *
 * 复用 [ExploreDriver] 的双线程形状（泵线程 + 喂入线程）、快照/还原、墙钟兜底与发现台账，
 * 只把三件事换掉（全部经 [ExploreConfig.soak] 门控，`soak=false` 时驱动器行为零变化）：
 * 1. **策略**：[SoakPolicy] 随机取候选（含重复），像真人连点，不做全排列枚举；
 * 2. **终止**：跑满给定墙钟时长（总 soak 墙钟）而不是 episode 数——局终后重新播种续跑；
 * 3. **计量**：[SoakMeter] 逐条记每一步「提交 → 到点」的墙钟毫秒，末尾聚合分位 + 慢步成节。
 * 浸泡档关掉快照栈分支（[ExploreConfig.soak] 时 [ExploreDriver] 不再 push 分支任务），
 * 保证是「一条随机操作流一直玩下去」，不是「逐支穷举」。
 *
 * ## 分位口径（钉死，与产品侧同源）
 *
 * 与 app 侧 `TurnPercentiles.kt`（只读参考，不改它）同款 **最近秩（nearest-rank）**：
 * 把 n 条读数升序排，q 分位取第 `ceil(q × n / 100)` 小（1 起算；整数算式
 * `(q100 × n + 99) / 100`，不用浮点乘法）。偶数不插值（p50 取下中位数）；n≤19 时
 * p95 恒等于 max（最近秩定义使然）；n=0 时 [p50]/[p95]/[max] 返回 0、[summaryLine] 出显式
 * 「无读数」文案。窗口 = 本次浸泡的全部样本（浸泡有总时长上限，样本量有界，不做定长截断）。
 */
class SoakMeter(private val slowStepMs: Long) {

    /**
     * 单步读数：提交→到点的墙钟毫秒 + 现场（函数名:pc、步序、动作、结局）
     * + **结算时刻的 live 堆水位**（obs-soak512）。
     *
     * [heapUsedMi] = 上一步到点、本条被结算那一刻的 `(totalMemory-freeMemory)` 折 MiB
     * （live 近似：自上次 GC 起算，单调性受 GC 时机影响，读趋势不读绝对值）；
     * -1 = 未采样（单测手造样本 / 非浸泡路径）。
     */
    data class Sample(
        val elapsedMs: Long,
        val episode: Long,
        val stackTop: String,
        val action: String,
        val stepIndex: Int,
        val outcome: String,
        val heapUsedMi: Long = -1L,
    )

    private val samples = ArrayList<Sample>()
    private val hangs = ArrayList<HangScene>()
    private val ooms = ArrayList<HangScene>()

    /** 记一步（驱动器在下一步到达 / 局终态揭晓时调用）。 */
    fun record(sample: Sample) {
        synchronized(samples) { samples.add(sample) }
    }

    /**
     * 记一次「单步墙钟闸命中」（obs-midhang `--step-wall-ms`）：抛点现场原样搬运，
     * 按段耗时降序成节（见 [renderReport] 的挂起专节）。与 [record] 分表：
     * 慢步是「跑到点了但很久」，挂起是「没跑到点就被闸中断」，两种判据不能混。
     */
    fun hang(scene: HangScene) {
        synchronized(hangs) { hangs.add(scene) }
    }

    /** 闸命中的挂起现场（耗时降序）。 */
    fun hangs(): List<HangScene> = synchronized(hangs) { hangs.sortedByDescending { it.elapsedMs } }

    /**
     * 记一次「堆 OOM」（obs-soak512）：泵线程被 [OutOfMemoryError] 打断的现场
     * （scene 键：message/heapUsedMi/heapCeilingMi/stackTop）。与 [hang] 分表——
     * 闸命中是「段落跑太久被中断」，堆 OOM 是「堆贴顶被 JVM 拒绝分配」，两种判据不混。
     */
    fun oom(scene: HangScene) {
        synchronized(ooms) { ooms.add(scene) }
    }

    /** 堆 OOM 现场（发生序）。 */
    fun ooms(): List<HangScene> = synchronized(ooms) { ooms.toList() }

    /**
     * 已结算样本里的 live 堆峰值（MiB）；无采样返回 -1。
     * 「离挡位还剩多少」= [heapCeilingMi] - 本值——贴顶判据的直接读数。
     */
    fun heapPeakMi(): Long = synchronized(samples) {
        samples.maxOfOrNull { it.heapUsedMi }?.takeIf { it >= 0L } ?: -1L
    }

    /** 本进程 JVM 的堆挡位（-Xmx 折 MiB；堆压浸泡据此核对「512m 挡真的生效了吗」）。 */
    fun heapCeilingMi(): Long = heapCeilingMiNow()

    /** 已记录的总步数（=「跑了多少步」）。 */
    fun count(): Int = synchronized(samples) { samples.size }

    /** 全部耗时的升序快照（分位计算用）。 */
    private fun sortedElapsed(): LongArray =
        synchronized(samples) { LongArray(samples.size) { i -> samples[i].elapsedMs }.also { java.util.Arrays.sort(it) } }

    /**
     * q 分位（最近秩）。[times100] = 百分位 × 100：p50 传 50、p95 传 95、max 传 100。
     * 空样本返回 0L（不抛、不 NaN）；口径与产品侧 TurnPercentiles.percentile 逐字一致。
     */
    fun percentile(times100: Int): Long {
        require(times100 in 1..100) { "times100 必须在 1..100，实际 $times100" }
        val sorted = sortedElapsed()
        val n = sorted.size
        if (n == 0) return 0L
        val rank = (times100.toLong() * n + 99L) / 100L
        return sorted[(rank - 1L).toInt()]
    }

    fun p50(): Long = percentile(50)

    fun p95(): Long = percentile(95)

    /** 最大值 = percentile(100)（最近秩下第 n 小即最大）。 */
    fun max(): Long = percentile(100)

    /** 单步耗时 ≥ [slowStepMs] 的慢步，按耗时降序（含函数名:pc / 步序 / 动作 / 结局）。 */
    fun slowSteps(): List<Sample> =
        synchronized(samples) { samples.filter { it.elapsedMs >= slowStepMs }.sortedByDescending { it.elapsedMs } }

    /**
     * 一行分位摘要（聚合读数）。
     * 非空：`n=812 p50=3ms p95=1844ms max=48213ms slow(>=5000ms)=6`；
     * 空：`n=0 无耗时读数`。
     */
    fun summaryLine(): String {
        val n = count()
        if (n == 0) return "n=0 无耗时读数"
        return "n=$n p50=${p50()}ms p95=${p95()}ms max=${max()}ms slow(>=${slowStepMs}ms)=${slowSteps().size}"
    }

    /**
     * 完整 soak.txt 文本：聚合分位 + 慢步专节（逐条 函数名:pc / episode / 步序 / 动作 / 结局 / 耗时）。
     * 慢步按耗时降序，最多列 200 条（尾部劣化的取证面，超出注明省略条数）。
     */
    fun renderReport(seed: Long, wallMin: Long): String {
        val sb = StringBuilder()
        sb.append("# vmdbg soak 浸泡读数（obs-soak；提交→到点耗时，最近秩分位口径同 app/TurnPercentiles.kt）\n")
        sb.append("policySeed=").append(seed).append('\n')
        sb.append("soakWallMin=").append(wallMin).append('\n')
        sb.append("slowStepThresholdMs=").append(slowStepMs).append('\n')
        sb.append("summary=").append(summaryLine()).append('\n')
        // 堆水位（obs-soak512）：挡位 = 本进程 -Xmx 折 MiB；峰值 = 已结算样本的 live 上界。
        sb.append("heapCeilingMi=").append(heapCeilingMi()).append('\n')
        val peakMi = heapPeakMi()
        if (peakMi < 0L) {
            sb.append("heapPeakMi=-1（未采样）\n")
        } else {
            sb.append("heapPeakMi=").append(peakMi)
                .append("（离挡位余量 ").append(heapCeilingMi() - peakMi).append(" MiB）\n")
        }
        val slow = slowSteps()
        sb.append('\n')
        sb.append("[慢步 elapsedMs>=").append(slowStepMs).append("] 共 ").append(slow.size).append(" 条\n")
        slow.take(200).forEach { s ->
            sb.append("  ").append(s.elapsedMs).append("ms fn:pc=").append(if (s.stackTop.isEmpty()) "-" else s.stackTop)
                .append(" ep=").append(s.episode).append(" step=").append(s.stepIndex)
                .append(" action=").append(s.action).append(" outcome=").append(s.outcome).append('\n')
        }
        if (slow.size > 200) sb.append("  …其余 ").append(slow.size - 200).append(" 条略\n")
        val hs = hangs()
        sb.append('\n')
        sb.append("[单步墙钟闸命中 --step-wall-ms=").append(sceneGateMs()).append("] 共 ").append(hs.size).append(" 条\n")
        if (hs.isEmpty()) {
            sb.append("  （无：本轮没有段落被墙钟闸中断）\n")
        }
        hs.take(200).forEach { h ->
            sb.append("  ").append(h.elapsedMs).append("ms 最内层=").append(h.scene["funcName"] ?: "-")
                .append(" 行=").append(h.scene["line"] ?: "-").append(" pc=").append(h.scene["pc"] ?: "-")
                .append(" 段指令=").append(h.scene["steps"] ?: "-")
                .append(" ep=").append(h.episode).append(" step=").append(h.stepIndex)
                .append(" action=").append(h.action).append('\n')
            sb.append("    调用链(外→内)=").append(h.scene["callChain"] ?: "-").append('\n')
        }
        if (hs.size > 200) sb.append("  …其余 ").append(hs.size - 200).append(" 条略\n")
        val ooms = ooms()
        sb.append('\n')
        sb.append("[堆 OOM（OutOfMemoryError，obs-soak512）] 共 ").append(ooms.size).append(" 条\n")
        if (ooms.isEmpty()) {
            sb.append("  （无：本轮没有步被堆 OOM 打断）\n")
        }
        ooms.take(200).forEach { o ->
            sb.append("  ").append(if (o.elapsedMs >= 0L) o.elapsedMs.toString() + "ms" else "-ms")
                .append(" ep=").append(o.episode).append(" step=").append(o.stepIndex)
                .append(" action=").append(o.action)
                .append(" 已用=").append(o.scene["heapUsedMi"] ?: "-").append("Mi/挡位=")
                .append(o.scene["heapCeilingMi"] ?: "-").append("Mi")
                .append(" 提交面=").append(o.scene["stackTop"] ?: "-").append('\n')
            sb.append("    message=").append(o.scene["message"] ?: "-")
                .append("（stepIndex 为该步在局内的提交序；-ms = OOM 时无在飞步）").append('\n')
        }
        if (ooms.size > 200) sb.append("  …其余 ").append(ooms.size - 200).append(" 条略\n")
        return sb.toString()
    }

    /** 挂起专节表头里的闸阈值（取任一条现场自带的 `stepWallMs`；无命中则报「未启用/未知」）。 */
    private fun sceneGateMs(): String = hangs().firstOrNull()?.scene?.get("stepWallMs") ?: "-"

    /** 逐条台账（soak-steps.jsonl 的一行）：一行一步（含结算时刻的 live 堆水位）。 */
    fun stepJsonl(): String = synchronized(samples) {
        buildString {
            for (s in samples) {
                append(
                    Jsonl.obj(
                        listOf(
                            "elapsedMs" to s.elapsedMs,
                            "episode" to s.episode,
                            "step" to s.stepIndex,
                            "stackTop" to s.stackTop,
                            "action" to s.action,
                            "outcome" to s.outcome,
                            "heapUsedMi" to s.heapUsedMi,
                        ),
                    ),
                )
                append('\n')
            }
        }
    }
}

/** 1 MiB 的字节数（堆水位折算用）。 */
internal const val MI_B = 1024L * 1024L

/** 此刻 live 堆近似（MiB）：`(totalMemory-freeMemory)`；GC 时机影响绝对值，读趋势与峰值。 */
internal fun heapUsedMiNow(): Long = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / MI_B }

/** 本进程 -Xmx 挡位（MiB）。 */
internal fun heapCeilingMiNow(): Long = Runtime.getRuntime().maxMemory() / MI_B

/**
 * 单步墙钟闸命中现场（obs-midhang）：一步提交后 VM 在**同一段落**（相邻两次让出点之间，
 * 让出点 = 输入等待返回 / `AWAIT`）连续算到超时，被 `VmConfig.maxRunMillis` 中断。
 *
 * [scene] 的键与 `ExploreDriver.stepScene` 逐字同源（funcName/line/pc/steps/elapsedMs/
 * callChain/stepWallMs），全部取自抛点自带的异常字段 —— 不做事后反射猜。
 * 同一份现场也进 findings.jsonl（类别 `STEP_WALL_LIMIT`）。
 */
data class HangScene(
    val elapsedMs: Long,
    val episode: Long,
    val stepIndex: Int,
    val action: String,
    val scene: Map<String, String>,
)

/**
 * 随机操作策略（浸泡档）：每个等待点在 [exploreCandidateActions] 的候选里按 [Random] 均匀抽一个，
 * **允许重复**（真人会连点同一按钮），不像 [ExhaustivePolicy]/[LeastVisitedPolicy] 那样按指纹去重、
 * 耗尽即弃局。永不返回 [ExploreAction.Abandon]（除候选为空的病态，兜底喂 0），保证「一直玩下去」。
 *
 * 确定性（AGENTS §2 / CONTRACT-DBG6 §0.3）：`java.util.Random(seed)`，禁 kotlin.random；
 * 同 WaitPoint 序列 + 同 seed ⇒ 同决定序列（浸泡可复跑的前提）。
 * 不维护「已试集」，因此 [onOutcome] 只累计计数、不参与决策。
 */
class SoakPolicy(
    seed: Long = DEFAULT_EXPLORE_SEED,
) : ExplorePolicy {

    private val rng = Random(seed)
    private var decisions = 0L

    override fun choose(wp: WaitPoint): ExploreAction {
        decisions++
        val plan = exploreCandidateActions(wp)
        if (plan.isEmpty()) return if (wp.request.type == InputRequest.InputType.STR) ExploreAction.FeedStr("") else ExploreAction.FeedInt(0L)
        if (plan.size == 1) return plan[0]
        return plan[rng.nextInt(plan.size)]
    }

    override fun onOutcome(wp: WaitPoint, action: ExploreAction, outcome: OutcomeKind) {
        // 浸泡决策与结局无关（随机连点），不做回喂记账；保留接口以满足契约。
    }

    /** 已决策次数（诊断）。 */
    fun decisionCount(): Long = decisions
}

/**
 * 驱动器提供给输入宿主 / 喂入线程的「交付前」缝（浸泡档专用；`soak=false` 时驱动器不接线，
 * 输入宿主与既有探索路径零行为变化）。
 *
 * 两步动作：
 * - [idleMs]：给出本步「玩家停顿」的毫秒数（随机，含长间隔）——在锁外 sleep，模拟真人犹豫，
 *   不计进「提交 → 到点」耗时；
 * - [markSubmit]：停顿结束后、把输入交给引擎之前调用——记录「此刻」为提交时刻，并把现场
 *   （函数名:pc / 动作 / 步序）钉给 [SoakMeter]，等下一步到达 / 局终态揭晓时结算本步耗时。
 */
interface SoakTuner {
    fun idleMs(): Long
    fun markSubmit(wp: WaitPoint, action: ExploreAction)
}
