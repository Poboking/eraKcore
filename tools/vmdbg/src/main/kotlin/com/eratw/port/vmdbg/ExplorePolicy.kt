package com.eratw.port.vmdbg

import com.eratw.port.core.host.InputRequest
import java.util.Random

/**
 * 引擎按钮候选（收集型 sink 自 printButton 聚积，到下一等待点清空）。
 *
 * 值来源 = ConsoleSink.printButton(text, value: Long)（core/host/ConsoleSink.kt:86）。
 * 字符串值按钮（printButton(text, value: String)，ConsoleSink.kt:88）承载不进来：
 * [value] 按合同 §3 钉死为 Long —— STR 按钮取值因此进不了候选集，STR 题的候选只剩
 * 空串与缺省串（见 [exploreCandidateActions] 的 STR 分支说明）。
 */
data class ButtonOpt(val text: String, val value: Long)

/**
 * 一次等待点全观测（CONTRACT-DBG6 §3 钉死类型；E1 的 ExploreInputSink 组装、E2 的策略消费）。
 *
 * 字段口径：
 * - [request] —— core/host/InputRequest 原样（type/form/hasDef/defInt/defStr/timeLimit 全量可得，
 *   InputSink.kt:48-70），等待点的「题目」；
 * - [anyKey] —— true = readAnyKey 路径（ENTER_KEY/ANY_KEY/PRINTW 系，InputSink.kt:140-146），
 *   此时唯一合法动作是 [ExploreAction.PassAnyKey]；
 * - [buttons] —— 候选（INT 题的合法取值优先来源）；
 * - [consoleTail] —— 控制台尾（缺省 30 行、去空白尾行，按控制台顺序最旧在前，
 *   与 Commands.kt:92-95 的 snapshot().takeLast(...) 同口径）；
 * - [stackTop] —— 「函数名:pc」；E1 拿不到调用栈口时传空串（合同 §3 原注）。
 */
data class WaitPoint(
    val request: InputRequest,
    val anyKey: Boolean,
    val buttons: List<ButtonOpt>,
    val consoleTail: List<String>,
    val stackTop: String,
)

/** 策略决定（CONTRACT-DBG6 §3 钉死类型）。 */
sealed interface ExploreAction {

    /**
     * 喂一个整数。`asTimeout = true` 即 TINPUT 超时语义：交付缺省值且 ISTIMEOUT 为真
     * （「超时模拟 = 返回 defInt/defStr 且 isTimeout=true」，InputOutcome KDoc，
     * core/host/InputSink.kt:120-135；原版超时推进判据 InputSink.kt:13-19）。
     * FLOW.md §5.2：「TINPUT 超时是隐藏分支」（title 菜单 20s 不答 = 默认值 2 = 进 DEMO）。
     */
    data class FeedInt(val value: Long, val asTimeout: Boolean = false) : ExploreAction

    data class FeedStr(val value: String) : ExploreAction

    data object PassAnyKey : ExploreAction

    /** 弃局：结束当前局回退到最近分支点（全排列换下一候选）或种子。 */
    data class Abandon(val reason: String) : ExploreAction
}

/** 一次动作的结果（驱动器回喂给策略与台账；CONTRACT-DBG6 §3 钉死枚举）。 */
enum class OutcomeKind { ADVANCED, CRASH, WALL_LIMIT, STEP_LIMIT, ABANDONED, EPISODE_DONE }

/** 策略接口（CONTRACT-DBG6 §3 钉死；实现必须确定：同 WaitPoint 序列 + 同 seed 即同决定）。 */
interface ExplorePolicy {

    /** 给出动作。实现必须确定性（同 WaitPoint 序列 + 同 seed 即同决定）；约定不许抛。 */
    fun choose(wp: WaitPoint): ExploreAction

    /** 动作结果回喂（用于访问计数/发现记账）。 */
    fun onOutcome(wp: WaitPoint, action: ExploreAction, outcome: OutcomeKind)
}

/** 策略 RNG 缺省种子（CONTRACT-DBG6 §0.3：seed 必须 CLI 可配且缺省钉死；与 Cli.RAND_SEED 同值 20260226）。 */
const val DEFAULT_EXPLORE_SEED = 20260226L

/** 同指纹连续 ABANDON/CRASH 达此值即永久跳过该指纹（CONTRACT-DBG6 §4.E2.1，--loop-cap 缺省）。 */
const val DEFAULT_LOOP_CAP = 8

/**
 * 等待点指纹（CONTRACT-DBG6 §4.E2.1 钉死的公式）：
 * `stackTop + "|" + form/type + buttons 值集哈希 + consoleTail 首行`。
 *
 * - buttons 取**值集**口径：distinct + 升序后再哈希，按钮打印顺序漂移不漂指纹；
 * - consoleTail 只取**首行**（去首尾空白）——合同钉死的最小屏幕上下文；尾中部各行漂移
 *   不改指纹（口径如此，ExplorePolicyTest 有断言锁定）；
 * - 同一 WaitPoint 序列纯函数复算恒等（无 RNG、无时钟），是 loop-cap 与「已试集」的键。
 */
fun waitPointFingerprint(wp: WaitPoint): String {
    val btnHash = wp.buttons.map { it.value }.distinct().sorted().hashCode()
    val firstLine = wp.consoleTail.firstOrNull()?.trim() ?: ""
    return wp.stackTop + "|" + wp.request.form.name + "/" + wp.request.type.name + "|" + btnHash + "|" + firstLine
}

/**
 * 本等待点的候选动作全域（两策略共用；E1 的快照栈判定「候选数 > 1」也用它）。
 *
 * 顺序（CONTRACT-DBG6 §4.E2.1：按钮值升序 → 缺省值 → 数字 0..9 去重 → 弃局）：
 * 1. **按钮值升序去重**（WaitPoint.buttons 的 value 集，FLOW.md §5.1 的「合法取值优先来源」）；
 * 2. **缺省值**（hasDef/defInt；与按钮重复时跳过）；
 * 3. **数字 0..9 去重**（数字题惯例集，FLOW.md §5.1）；
 * 4. **超时候选垫底** —— [ExploreAction.FeedInt] `asTimeout = true`，仅当
 *    `form == VALUE && type == INT && hasDef && timeLimit >= 0`，值 = defInt。
 *    排位说明（任务书要求写清）：超时排在**全部具体取值之后、弃局之前** —— 它是
 *    FLOW.md §5.2 的「隐藏分支」（title→DEMO 就是这么进的），但 DEMO(BADAPPLE) 是
 *    WALL_LIMIT 大户（FLOW.md §4），垫底探测不挤占常规分支。门条件的依据：
 *    超时模拟交付的就是 defInt（InputSink.kt:120-135），无缺省时原版超时不推进
 *    （InputSink.kt:13-19 的 INT 无默认值分支），喂了就是语义谎言，故 hasDef 才发；
 *    STR 题的超时不可表达（FeedStr 无 asTimeout 位，§3 钉死签名）——本波放弃 STR 超时候选。
 * 5. 候选全试完 → 策略回 [ExploreAction.Abandon]`("exhausted")`（不在本函数内，见
 *    [BaseExplorePolicy.choose]）。
 *
 * 特形：
 * - `anyKey = true`：唯一 [ExploreAction.PassAnyKey]（合同 §3：违者驱动器记发现按弃局处理）；
 * - 不取值形态（ENTER_KEY/ANY_KEY/VOID，InputRequest.needValue = false，InputSink.kt:102-106）：
 *   内容必被引擎忽略 —— 单个占位 FeedInt/FeedStr（有缺省取缺省、否则 0/空串），无分支；
 * - STR：候选 = 空串 → 缺省串（合同只钉 INT 序，STR 序为本波自定义：空串是 STR 的
 *   「零值」，与 INT 的 0 同位）。
 */
fun exploreCandidateActions(wp: WaitPoint): List<ExploreAction> {
    if (wp.anyKey) return listOf(ExploreAction.PassAnyKey)
    val req = wp.request
    if (!req.needValue) {
        return listOf(
            if (req.type == InputRequest.InputType.INT) {
                ExploreAction.FeedInt(if (req.hasDef) req.defInt else 0L)
            } else {
                ExploreAction.FeedStr(if (req.hasDef) req.defStr else "")
            },
        )
    }
    return when (req.type) {
        InputRequest.InputType.INT -> {
            val out = ArrayList<ExploreAction>()
            val seen = LinkedHashSet<Long>()
            wp.buttons.map { it.value }.sorted().forEach { v -> if (seen.add(v)) out.add(ExploreAction.FeedInt(v)) }
            if (req.hasDef && seen.add(req.defInt)) out.add(ExploreAction.FeedInt(req.defInt))
            for (d in 0L..9L) if (seen.add(d)) out.add(ExploreAction.FeedInt(d))
            if (req.form == InputRequest.RequestForm.VALUE && req.hasDef && req.timeLimit >= 0L) {
                out.add(ExploreAction.FeedInt(req.defInt, asTimeout = true))
            }
            if (out.isEmpty()) out.add(ExploreAction.FeedInt(0L)) // 理论不可达（0..9 兜底），防御性占位
            out
        }
        InputRequest.InputType.STR -> {
            val out = ArrayList<ExploreAction>()
            val seen = LinkedHashSet<String>()
            if (seen.add("")) out.add(ExploreAction.FeedStr(""))
            if (req.hasDef && seen.add(req.defStr)) out.add(ExploreAction.FeedStr(req.defStr))
            out
        }
    }
}

/** 动作的单行标签（台账 action 字段、喂食计数键、测试断言共用；不含引号换行）。 */
fun exploreActionLabel(a: ExploreAction): String = when (a) {
    is ExploreAction.FeedInt -> if (a.asTimeout) "FeedInt(${a.value},timeout)" else "FeedInt(${a.value})"
    is ExploreAction.FeedStr -> "FeedStr(${a.value})"
    ExploreAction.PassAnyKey -> "PassAnyKey"
    is ExploreAction.Abandon -> "Abandon(${a.reason})"
}

/**
 * 两策略共用的骨架（实现细节，勿直接依赖；确定性结构在此锁定）。
 *
 * - 状态表按 [waitPointFingerprint] 分桶：已试取值集 + 超时位 + 连续坏局计数 + blocked +
 *   访问/喂食计数（FLOW.md 语料规模下指纹数有限，表不设上限，随进程退出即弃）；
 * - **offer 即消费**：[choose] 返回候选前先标记已试 —— 即使驱动器漏调 [onOutcome]，
 *   枚举也必然终止（ExplorePolicyTest 有「不喂 onOutcome 也会弃局」的断言）；
 * - [onOutcome]：ABANDONED/CRASH 延长该指纹的「连续坏局」计数，其余结局清零；
 *   计数达 [loopCap] → 该指纹永久 blocked（合同 §4.E2.1「防输入死循环」），
 *   之后 [choose] 对它一律 [ExploreAction.Abandon]`("loop-capped")`；
 * - 全部决策走 [Random]（seed 由子类构造传入，合同 §0.3 禁 kotlin.random）；
 *   ExhaustivePolicy 不消耗 draw（纯序），LeastVisitedPolicy 每 select 恰一次 draw
 *   —— 同 WaitPoint 序列 + 同 seed ⇒ 同决定序列。
 * - 单线程口径（ADR-003）：策略只被 VM 线程经 sink 调用，状态无同步。
 */
abstract class BaseExplorePolicy(seed: Long, protected val loopCap: Int) : ExplorePolicy {

    /** 策略 RNG 序（确定性来源；Exhaustive 不消耗，LeastVisited 每 select 一次）。 */
    protected val rng = Random(seed)

    /** 单指纹状态（protected：子类 select 签名可见；字段只在本文件写）。 */
    protected class FpState {
        /** 已 offer 过的取值候选（offer 即消费，见类 KDoc）。 */
        val triedInts = HashSet<Long>()
        val triedStrs = HashSet<String>()

        /** 超时候选已 offer 位（与取值集分开 —— 同值超时是另一条分支）。 */
        var timeoutTried = false

        /** 连续 ABANDONED/CRASH 计数（其余结局清零）。 */
        var consecutiveBad = 0

        /** loop-cap 命中 → 永久跳过。 */
        var blocked = false

        /** 访问计数（LeastVisitedPolicy 的「访问计数」骨架数据）。 */
        var visits = 0L

        /** 喂食计数（动作标签 → 次数；台账与二期调优的数据面）。 */
        val feedCounts = HashMap<String, Int>()
    }

    private val states = HashMap<String, FpState>()

    private fun stateOf(fp: String): FpState = states.getOrPut(fp) { FpState() }

    /** 子类唯一分叉点：在未试候选里挑一个（Exhaustive=序首；LeastVisited=RNG）。 */
    protected abstract fun selectUntried(untried: List<ExploreAction>, st: FpState): ExploreAction

    private fun isUntried(st: FpState, a: ExploreAction): Boolean = when (a) {
        is ExploreAction.FeedInt -> if (a.asTimeout) !st.timeoutTried else a.value !in st.triedInts
        is ExploreAction.FeedStr -> a.value !in st.triedStrs
        else -> true
    }

    private fun markTried(st: FpState, a: ExploreAction) {
        when (a) {
            is ExploreAction.FeedInt -> if (a.asTimeout) st.timeoutTried = true else st.triedInts.add(a.value)
            is ExploreAction.FeedStr -> st.triedStrs.add(a.value)
            else -> {}
        }
    }

    final override fun choose(wp: WaitPoint): ExploreAction {
        val st = stateOf(waitPointFingerprint(wp))
        st.visits++
        if (st.blocked) return ExploreAction.Abandon("loop-capped")
        val plan = exploreCandidateActions(wp)
        if (plan.size <= 1) return plan.firstOrNull() ?: ExploreAction.Abandon("exhausted")
        val untried = plan.filter { isUntried(st, it) }
        if (untried.isEmpty()) return ExploreAction.Abandon("exhausted")
        val pick = selectUntried(untried, st)
        markTried(st, pick)
        return pick
    }

    final override fun onOutcome(wp: WaitPoint, action: ExploreAction, outcome: OutcomeKind) {
        val st = stateOf(waitPointFingerprint(wp))
        when (outcome) {
            OutcomeKind.ABANDONED, OutcomeKind.CRASH -> st.consecutiveBad++
            else -> st.consecutiveBad = 0
        }
        if (st.consecutiveBad >= loopCap) st.blocked = true
        val label = exploreActionLabel(action)
        st.feedCounts[label] = (st.feedCounts[label] ?: 0) + 1
    }
}

/**
 * 全排列主力（CONTRACT-DBG6 §4.E2.1）：同指纹候选按 [exploreCandidateActions] 的钉死顺序
 * 逐个全试（配合 E1 快照栈回退），试尽弃局。RNG 零消耗 —— 决策是「指纹状态 + WaitPoint」
 * 的纯函数，seed 只进入实例构造（两实例同序列必同决定，与 seed 无关也成立）。
 */
class ExhaustivePolicy(
    seed: Long = DEFAULT_EXPLORE_SEED,
    loopCap: Int = DEFAULT_LOOP_CAP,
) : BaseExplorePolicy(seed, loopCap) {

    override fun selectUntried(untried: List<ExploreAction>, st: FpState): ExploreAction = untried.first()
}

/**
 * 「最少访问优先」策略骨架（CONTRACT-DBG6 §4.E2.1：骨架 + 访问计数即可，**二期再调优**）。
 *
 * 本波实现 = 未试候选里按 [rng] 均匀抽取（随机化枚举），并记好 [BaseExplorePolicy.FpState.visits]
 * 与 feedCounts 两套计数；loop-cap 与「offer 即消费」与 ExhaustivePolicy 完全同构。
 * 二期方向（占位，不在本波做）：按「候选后继的访问计数」加权选支，避免反复钻热屏。
 */
class LeastVisitedPolicy(
    seed: Long = DEFAULT_EXPLORE_SEED,
    loopCap: Int = DEFAULT_LOOP_CAP,
) : BaseExplorePolicy(seed, loopCap) {

    override fun selectUntried(untried: List<ExploreAction>, st: FpState): ExploreAction {
        if (untried.size == 1) return untried[0]
        return untried[rng.nextInt(untried.size)]
    }
}

/**
 * CLI 策略名 → 实例（E1 的 CmdExplore 唯一构造入口；名字即 --policy 的取值空间）。
 * 未知名字抛 [CliError]（退出码 1 口径）。E1 接线形如：
 * `createExplorePolicy(policyArg, seedArg, loopCapArg)`。
 */
fun createExplorePolicy(name: String, seed: Long = DEFAULT_EXPLORE_SEED, loopCap: Int = DEFAULT_LOOP_CAP): ExplorePolicy =
    when (name) {
        "exhaustive" -> ExhaustivePolicy(seed, loopCap)
        "least-visited" -> LeastVisitedPolicy(seed, loopCap)
        else -> throw CliError("--policy 只认 exhaustive|least-visited: $name")
    }
