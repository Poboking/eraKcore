package com.eratw.port.vmdbg

import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.InputOutcome
import java.time.OffsetDateTime
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.SaveLoadHandoff
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.core.proc.StepOutcome
import com.eratw.port.core.proc.SystemStateCode
import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

/**
 * explore 驱动器（CONTRACT-DBG6 E1）：真实游戏流程状态机的「窄而深」遍历器。
 *
 * sweep 是「宽而浅」（伪造实参单函数直调，CONTRACT-DBG1 路2），本文件是它的对偶：
 * 驱动会话真实跑标题 → 开局 → 主菜单流程，在每个等待点问策略要动作、把动作翻译回
 * [InputOutcome] 喂还引擎，用快照栈 + 输入链重放做时间旅行，把全排列逐支走完。
 *
 * ## 线程形状（为什么是双线程）
 *
 * 脚本侧语句输入全部走注入的 [ExploreInputSink]（[GameSession.inputSinkOverride]，
 * GameSession.kt:154-172 的构造期单例注入点），在**泵线程**上同步回调，策略决定内联发生。
 * 但 BEGIN 状态机自己的系统输入（`ConsoleBeginHost.waitSystemInteger`，ConsoleBeginHost.kt:109-114）
 * 读的是会话自带 [com.eratw.port.core.proc.QueueInputSink]——主菜单「第1日」的指令选择正是这一路
 * （BeginFlow.stepTrainShowUserCom → `host.awaitUserComInput()`，BeginFlow.kt:844-873），
 * 队列需要**另一个线程**在等待挂起期间喂入。因此每局两条线程：
 * - **泵线程**：`resumeSystem()` 循环（或直调模式的 `callFunction`），等待点回调发生在其上；
 * - **喂入线程**：轮询 `inputSink.isAwaiting`（QueueInputSink.kt:212-214，volatile 只读快照），
 *   命中即组装队列面 WaitPoint 问策略、`submitInt`/`submit` 解除停车。
 *
 * 引擎严格串行（任一时刻至多一个等待方），两条线程对策略的访问由驱动器锁串行化，
 * 决定序列确定（--seed 由策略实现消费，CONTRACT-DBG6 §0.3 的 `java.util.Random` 纪律）。
 *
 * ## 分支与时间旅行（CONTRACT-DBG6 §4.E1.2）
 *
 * 等待点若可判出多于一个候选（按钮值集 + 缺省值 + 数字 0..9 去重，[candidateCount]），
 * 驱动器在执行动作**前**把会话快照（变量面 + RNG，[SnapShots]，与 CmdSnap.kt:86-110 同口径：
 * 引擎 saveData 全链落沙箱槽 [CmdSnap.DEBUG_SLOT]）连同「从局起点到本等待点的动作链」压栈。
 * 局终态（CRASH/WALL_LIMIT/STEP_LIMIT/Abandon/EPISODE_DONE）弹栈：
 * restore（CmdSnap.kt:116-161 同链：LOADDATA 语义 + handoff 清零 + RNG setState）→
 * 状态机锚点复位（[reAnchor]）→ 重放动作链回到分支等待点 → 策略给出下一候选（策略经
 * `onOutcome` 记账后换发下一支；驱动器另有 `tried` 护栏：同分支点重放后策略再次给出
 * 已试过的同一动作即视为该支耗尽，防打转）→ 全排列逐支。栈空收工。
 *
 * 状态机锚点为什么必须反射复位：restore 只还原变量面与 RNG，`BeginFlow.state` 是
 * 进程内 Kotlin 对象状态（ProcessRuntime.kt 的 `systemState` 只读视图，写入点只有
 * BeginFlow.select/enterState），loadData 的交接面（SaveLoadHandoff）又只接受三个读档态、
 * 不接受任意态（SaveLoadHandoff.kt 的「存什么、谁消费」节）。core-vm 零改动铁律下，
 * 唯一可行缝 = `BeginFlow.resetToBootState()`（公开，清终局粘滞位）+ 反射写
 * `BeginFlow.state` 回锚点态（先例：StepReader / CmdSnap.reflectFuncStack 的工具侧反射）。
 * 需组长接线：core 侧若将来开放「状态机位置还原」通道，换掉本反射。
 *
 * ## 已知保真边界（快照不还原的面，与 CmdSnap 同口径）
 *
 * - **console 不还原**（CmdSnap 只记不还原）：跨局指纹里的 consoleTail 维度不回退；
 * - **BeginFlow 的 comAble 表 / calledWhenNormal 不还原**：菜单循环每轮经
 *   endCallShowStatus 重清重登记（BeginFlow.kt:787-798），常态自愈；
 * - **aux 槽（鼠标 / keytoggle / 图像缓存）不还原**：GETKEY 系读数跨局可能残留。
 * 三者都可能让重放与首局分歧；驱动器以分支签名校验兜底（[signatureOf]，不匹配记
 * REPLAY_DIVERGE 发现并弃局），不静默续跑。
 *
 * ## E2 台账接线（out 参数，DBG7）
 *
 * `out` 非 null 时驱动器在四个点旁路喂 E2 的 [ExploreOut]（决策与执行语义零变化，out=null
 * 全部旁路 —— 既有单测路径不变）：
 * - **逐步台账**：`closeOutPendingLocked`（上一步结局揭晓的唯一时点：下一等待点到达 / 局终态）
 *   调 `logStep`——自由段与重放段都记（重放步也真实喂了输入，链必须含它才可重放；重放步不回喂
 *   策略，outcomeCounts/onOutcome 口径不变）；
 * - **引导链步**：`bootNextActionLocked` 消费食谱步时调 `logBootStep`（步的原文输入 + 期望注记，
 *   与 --boot 配方语法同构，重放文件因此可直接回喂）；引导步在任何探索步之前，链序 = 时间序；
 * - **覆盖计数**：`run()` 开头 `coverage.attach`（探针覆盖引导段 + 全部局，回调只在 VM 执行线程）、
 *   finally `detach`（收工读数 happens-before 经线程 join 成立）；
 * - **收工落盘**：`run()` finally `out.close()`（冲洗 explore.jsonl/findings.jsonl + 落
 *   coverage.txt；引导失败也照落，coverage.txt 此时是失败前已触碰的读数）。
 * 发现投递不经本类改动：FindingSink 由外层接 E2 适配器（ExploreOutFindingSink，见 CmdExplore）。
 */
object ExploreDefaults {

    /** WaitPoint.consoleTail 的缺省行数（CONTRACT-DBG6 §3：缺省 30 行，去空白尾行）。 */
    const val TAIL_LINES = 30

    /** 引导逐步台账的文件名（落 `<out>` 下，引导失败与引导成功各写一份全量）。 */
    const val BOOT_STEPS_LEDGER = "boot-steps.jsonl"

    /** 引导台账里单格文本的截断长度（屏上 ASCII 地图一行就上千字符，不截会灌爆台账）。 */
    const val BOOT_TRACE_TEXT_MAX = 400

    /**
     * 引导期「任意键自动放行」的护栏上限（obs-bootrecipe）：超过即判引导失败并说明卡在纯推进面。
     * 口径依据：[ExploreBoot] 类 KDoc 钉死「任意键等待点不经食谱（自动 PassAnyKey 放行，对应 FLOW.md §2
     * 里的 (Enter×N) 段）」，而开局链的 (Enter×N) 段有几十个这样的点（真机取证原话「几十个等待点」），
     * 无上限则脚本一旦进入纯 anykey 死循环会把浸泡墙钟烧光。
     */
    const val BOOT_ANYKEY_AUTO_MAX = 400

    /**
     * 发现台账**同种重复**的抑制上限（obs-soakwall）：一种 kind 在一轮 run 里最多真实投递这么多条。
     * 立项依据（组长实测，修前装置）：--soak-min 1 一轮写出 findings.jsonl 42 MB / 28,964 行，
     * 全部 kind=WALL_LIMIT、全部 episode=1，相邻两条时间戳只差 0～37 ms —— 喂入侧墙钟命中后
     * 「记一条 = 应答一次输入」的死循环（修法见 feederBody 与 stateMachinePump 的 KDoc）。
     * 定长护栏：即使别处再冒出同类环，台账行数也有上界（本仓的 OOM 账从没人记账的累加开始）。
     */
    const val FINDING_KIND_CAP = 40

    /** 发现台账**总行数**的护栏上限（单轮 run，跨所有 kind；[FINDING_KIND_CAP] 之外的第二道闸）。 */
    const val FINDING_TOTAL_CAP = 400

    /** [FindingGuard] 种类桶的上限：kind 名全部来自本文件常量集合，正常远达不到；防未知 kind 撑爆映射。 */
    const val FINDING_KIND_KEYS_MAX = 64

    /**
     * 加载种子的缺省状态机锚点：第 1 日主菜单的指令选择步（BeginFlow 的 Train_CallShowUserCom，
     * 端上引导链的种子打点态）。
     * TODO(verify)：真机 Plan B 快照（--seed-snap）拖回时的状态机位置只能是假设——
     * sidecar 无 systemState 字段时回落本值；若真机快照停在别的态（例如 SaveGame 界面），
     * 播种后的泵从错误的 handler 起步，需要组长在真机上核对 DevTools snapshot 的落点再定。
     */
    val DEFAULT_ANCHOR: SystemStateCode = SystemStateCode.Train_CallShowUserCom
}

/** 发现投递缝（E1 本地类型）：驱动器产出的每条发现交到这里；explore CLI 缺省接
 *  [ExploreOutFindingSink]（E2 台账，同目录与 JsonlFindingSink 二选一，见 CmdExplore 类 KDoc）。 */
fun interface FindingSink {
    /**
     * @param kind 发现类别（CRASH / WALL_LIMIT / STEP_LIMIT / ILLEGAL_ACTION /
     *   REPLAY_DIVERGE / POLICY_CRASH / BOOT_FAIL；CODEEE_FLOOD 归 E2 的台账面）
     * @param detail 扁平键值（episode / chain / exception / anchor 等，字符串化）
     * @param consoleTail 发现现场的控制台尾（与 WaitPoint 同口径，去空白尾行）
     * @param replayInputs 本发现的可重放输入链（每行一条，可直接喂 --boot 格式）
     */
    fun onFinding(kind: String, detail: Map<String, String>, consoleTail: List<String>, replayInputs: List<String>)
}

/** 弃局信号（非 CodeEE 的 RuntimeException：与 WallLimitExceeded 同款，TRYC 家族捕不到，原样穿栈上抛）。 */
class ExploreAbandon(message: String, val findingKind: String? = null) : RuntimeException(message)

/**
 * 同种发现的重复抑制护栏（obs-soakwall）：驱动器的每条发现投递都先过这里。
 *
 * 口径（三条都要被单测钉住）：
 * 1. **第一次的真现场绝不吞**：每种 kind 前 [kindCap] 条、全局前 [totalCap] 条原样放行；
 * 2. **台账行数有上界**：超出的只累计 [suppressed] 计数、不再投递（死循环再犯也只烧常数行）；
 * 3. **定长存储**：kind → 已放行条数用映射记账，桶数上限 [ExploreDefaults.FINDING_KIND_KEYS_MAX]，
 *    未知 kind 超出后并入 overflow 桶共享额度——不存在无界累加。
 *
 * 首次把某个 kind 压静音时（[Pass.justMuted]），调用方应打一行 stderr 说明护栏生效，
 * 让「台账不再涨」可被读成「被抑制」而不是「现场已清」。线程纪律：只在驱动器锁内调
 * [allow]（三个 report 挂点都持 lock），本类自身不加锁。
 */
internal class FindingGuard(private val kindCap: Int, private val totalCap: Int) {

    /** 投递判定结果。 */
    class Pass(val delivered: Boolean, val justMuted: Boolean)

    /** 单 kind 的护栏状态：已放行条数 + 是否已报过静音（每 kind 一格，定长）。 */
    private class KindState {
        var delivered = 0
        var mutedAnnounced = false
    }

    private val perKind = HashMap<String, KindState>()
    private var total = 0

    /** 被抑制的条数（供 run 结果汇总，不落台账）。 */
    var suppressed: Int = 0
        private set

    /** 共享桶：未知 kind 超出桶数上限后的归并位（kind 名来自本文件常量集合，正常走不到）。 */
    private fun bucketOf(kind: String): String =
        if (kind in perKind || perKind.size < ExploreDefaults.FINDING_KIND_KEYS_MAX) kind else "*"

    fun allow(kind: String): Pass {
        val st = perKind.getOrPut(bucketOf(kind)) { KindState() }
        if (total < totalCap && st.delivered < kindCap) {
            st.delivered++
            total++
            return Pass(delivered = true, justMuted = false)
        }
        suppressed++
        val justMuted = !st.mutedAnnounced
        st.mutedAnnounced = true
        return Pass(delivered = false, justMuted = justMuted)
    }
}

/**
 * 等待点观测环境：组装 [WaitPoint] 所需的三项活读数。
 * 驱动器用会话实现（[ExploreDriver.sessionEnv]）；单测用假体直接喂。
 */
interface WaitEnv {
    /** 控制台尾（最新在后，去空白尾行）。 */
    fun consoleTail(max: Int): List<String>

    /** 取走自上一等待点以来积聚的按钮候选（到下一等待点清空，CONTRACT-DBG6 §3）。 */
    fun drainButtons(): List<ButtonOpt>

    /** 「函数名:pc」栈顶；拿不到时 ""。 */
    fun stackTop(): String
}

/**
 * 按钮候选收集器：从会话控制台的显示行按钮区间（[LineBufferConsoleSink.DisplayLine.buttons]，
 * PRINTBUTTON / PRINTBUTTONC / 自动按钮的全部落点，ConsoleSink.kt:85-94 的值最终都记在这里）
 * 按行摊平出 [ButtonOpt]。
 *
 * 「到下一等待点清空」的落地方式：记「已消费行数」水位，每次 [drain] 只收水位之后的行，
 * 收完把水位推到当前行数。CLEARLINE（deleteLine）会在两次等待之间摘行使下标前移，
 * 水位因此只做钳制不做精确回退（TODO(verify)：若语料出现「CLEARLINE 后重打同位按钮」
 * 的菜单形态导致候选漏收，再改为按 revision 差分）。
 *
 * 文本提取是**近似**：按钮列号是半角格坐标，这里按「1 字符 = 1 格」对拼接文本切片，
 * 纯 ASCII 行精确、含全角前缀的行可能偏移——候选判定只依赖 value（Long），text 仅诊断用。
 * 字符串值按钮（PRINTBUTTON 的 String 形态）不进候选：ButtonOpt.value 是 Long
 * （CONTRACT-DBG6 §3 钉死形状），跳过并计数，见 [skippedStrButtons]。
 */
class ConsoleButtonCollector(private val console: LineBufferConsoleSink) {

    private var consumedLines = 0

    /** 已跳过的字符串值按钮累计（诊断）。 */
    var skippedStrButtons: Int = 0
        private set

    /** 局起点调用：把水位推到当前行数（跨局按钮基线重置，见 ExploreDriver 已知保真边界）。 */
    fun resetBaseline() {
        consumedLines = console.lines().size
    }

    /** 收走水位之后的按钮候选；返回顺序 = 行号升序、行内列号升序。 */
    fun drain(): List<ButtonOpt> {
        val lines = console.lines()
        val from = consumedLines.coerceIn(0, lines.size)
        val out = ArrayList<ButtonOpt>()
        for (i in from until lines.size) {
            val line = lines[i]
            for (b in line.buttons) {
                val v = b.value
                when (v) {
                    is Long -> out.add(ButtonOpt(buttonTextOf(line, b), v))
                    is String -> skippedStrButtons++
                }
            }
        }
        consumedLines = lines.size
        return out
    }

    /** 按钮可见文本：行内拼接文本按按钮列区间切片（近似口径见类 KDoc）。 */
    private fun buttonTextOf(line: LineBufferConsoleSink.DisplayLine, b: LineBufferConsoleSink.Button): String {
        val text = line.segments.joinToString("") { it.text }
        val from = b.startCol.coerceIn(0, text.length)
        val to = b.endCol.coerceIn(from, text.length)
        return text.substring(from, to)
    }
}

/**
 * explore 的输入宿主（CONTRACT-DBG6 §4.E1.1）：实现 core/host/InputSink.kt:145 的接口，
 * 把每个等待点组装成完整的 [WaitPoint]（type/form/hasDef/defInt/defStr/timeLimit 全量可见，
 * InputSink.kt:48）交策略决定，再把 [ExploreAction] 翻译回 [InputOutcome]。
 *
 * 翻译矩阵：
 * - `FeedInt(v, asTimeout)` → `InputOutcome(intValue = v, isTimeout = asTimeout)`。
 *   asTimeout=true 且 v=请求缺省值 = 原版「超时自动推进」（InputSink.kt:111-135 的 TINPUT 语义：
 *   ISTIMEOUT 置真 + 默认值交付）；asTimeout=true 且 v 与缺省不同 = 「截止后才提交」的粘滞
 *   超时形态（isTimeout 在一次限时等待内粘滞，InputSink.kt:126-131）。
 * - `FeedStr(s)` → `InputOutcome(strValue = s)`（仅 STR 请求合法）。
 * - `PassAnyKey` → 仅 anyKey=true 的等待点（readAnyKey 路径，含 WAIT / WAITANYKEY / PRINTW 系，
 *   CONTRACT-DBG6 §3 的 anyKey 注记）合法，直接放行。
 * - `Abandon(reason)` → 抛 [ExploreAbandon] 弃局（穿栈上抛，泵线程捕获折成 ABANDONED）。
 * - 非法组合（值等待面收 PassAnyKey / INT 题收 FeedStr / STR 题收 FeedInt）→ 记
 *   ILLEGAL_ACTION 发现（经 [ExploreAbandon.findingKind] 随信号上抛）并按 Abandon 处理
 *   （CONTRACT-DBG6 §3 约定）。
 *
 * 驱动器经 [attach] 迟绑（装配循环：会话构造需要 sink，sink 的决定需要会话——与
 * Assembler.DeferredSandboxSystemOps 的 sessionRef 同款解法）。
 *
 * @param budget 每局换装的墙钟预算（与 ImmediateInputSink.budget 同一挂点：两个等待入口各查一次）。
 */
class ExploreInputSink(
    private val tailLines: Int = ExploreDefaults.TAIL_LINES,
) : InputSink {

    /** 驱动器钩子；null = 尚未 attach（引擎不可能在装配期等待，命中即编程错误）。 */
    @Volatile
    var hooks: ExploreHooks? = null

    /** 当前墙钟预算；null = 不检查。 */
    @Volatile
    var budget: WallBudget? = null

    /**
     * 浸泡档「交付前」缝（obs-soak）：null = 非浸泡（既有探索路径零行为变化）。
     * 驱动器在 [ExploreConfig.soak] 时接线；交付输入前经它做随机停顿 + 记录提交时刻（见 [SoakTuner]）。
     */
    @Volatile
    var soak: SoakTuner? = null

    /** 迟绑驱动器（驱动器构造完成后立即调用）。 */
    fun attach(h: ExploreHooks) {
        hooks = h
    }

    /** 浸泡档：交付前停顿（锁外 sleep）+ 记录提交时刻；[soak] 未接线时纯透传（异常动作不交付）。 */
    private fun soakBeforeDeliver(wp: WaitPoint, action: ExploreAction) {
        val s = soak ?: return
        val ms = s.idleMs()
        if (ms > 0L) Thread.sleep(ms)
        s.markSubmit(wp, action)
    }

    override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) {
        budget?.check()
        val d = hooks ?: throw IllegalStateException("explore 驱动器未接线（attach 未调用）")
        val env = d.env()
        // anyKey 路径没有引擎侧 InputRequest：按形态合成（INT + ANY_KEY/ENTER_KEY 形态位），
        // type 位在 anyKey 等待点不参与交付（needValue=false，InputSink.kt:102-106）。
        val request = InputRequest(
            type = InputRequest.InputType.INT,
            form = if (anyKey) InputRequest.RequestForm.ANY_KEY else InputRequest.RequestForm.ENTER_KEY,
        )
        val wp = WaitPoint(request, anyKey = true, buttons = env.drainButtons(), consoleTail = env.consoleTail(tailLines), stackTop = env.stackTop())
        val action = d.decide(wp)
        d.onObserved(wp, action)
        if (action is ExploreAction.PassAnyKey) {
            soakBeforeDeliver(wp, action)
            return
        }
        when (action) {
            is ExploreAction.Abandon -> throw ExploreAbandon(action.reason)
            else -> throw ExploreAbandon("anyKey 等待点收到取值类动作（非法）", findingKind = "ILLEGAL_ACTION")
        }
    }

    override fun waitInput(request: InputRequest): InputOutcome {
        budget?.check()
        val d = hooks ?: throw IllegalStateException("explore 驱动器未接线（attach 未调用）")
        val env = d.env()
        val wp = WaitPoint(request, anyKey = false, buttons = env.drainButtons(), consoleTail = env.consoleTail(tailLines), stackTop = env.stackTop())
        val action = d.decide(wp)
        d.onObserved(wp, action)
        val outcome = translate(wp, action)
        // 浸泡档：交付前停顿 + 记录提交时刻（translate 若抛非法/弃局，不交付、不停顿，正确跳过）。
        soakBeforeDeliver(wp, action)
        return outcome
    }

    /** 动作 → 交付的唯一定义点（翻译矩阵见类 KDoc）；非法组合与 Abandon 一律以异常表达。 */
    private fun translate(wp: WaitPoint, action: ExploreAction): InputOutcome = when (action) {
        is ExploreAction.FeedInt ->
            if (wp.anyKey || wp.request.type != InputRequest.InputType.INT) {
                throw ExploreAbandon(
                    "非法动作：INT/anyKey 等待点收到 FeedInt 之外的交付形态不合（type=${wp.request.type} anyKey=${wp.anyKey}）",
                    findingKind = "ILLEGAL_ACTION",
                )
            } else {
                InputOutcome(intValue = action.value, isTimeout = action.asTimeout)
            }
        is ExploreAction.FeedStr ->
            if (wp.anyKey || wp.request.type != InputRequest.InputType.STR) {
                throw ExploreAbandon(
                    "非法动作：STR 交付落在 type=${wp.request.type} anyKey=${wp.anyKey} 的等待点上",
                    findingKind = "ILLEGAL_ACTION",
                )
            } else {
                InputOutcome(strValue = action.value)
            }
        is ExploreAction.PassAnyKey ->
            throw ExploreAbandon(
                "非法动作：值等待面（type=${wp.request.type} anyKey=${wp.anyKey}）收到 PassAnyKey",
                findingKind = "ILLEGAL_ACTION",
            )
        is ExploreAction.Abandon -> throw ExploreAbandon(action.reason)
    }
}

/** [ExploreInputSink] 与驱动器之间的接线面（驱动器实现；单测用假体）。 */
interface ExploreHooks {
    fun env(): WaitEnv
    fun decide(wp: WaitPoint): ExploreAction
    fun onObserved(wp: WaitPoint, action: ExploreAction)
}

/**
 * 「函数名:pc」栈顶读取（工具侧反射先例：StepReader / CmdSnap.reflectFuncStack，
 * CmdSnap.kt:214-242）。funcStack 和 depth 都是 [VirtualMachine] 私有字段；
 * pc 用公开的 [VirtualMachine.lastPc]（Vm.kt:331-333，最近一条已执行指令）。
 * 反射不可得 / 无活帧时返回 ""（CONTRACT-DBG6 §3 允许）。
 */
object VmStackReader {

    private val vmField = runCatching {
        ProcessRuntime::class.java.getDeclaredField("vm").apply { isAccessible = true }
    }.getOrNull()
    private val funcStackField = runCatching {
        VirtualMachine::class.java.getDeclaredField("funcStack").apply { isAccessible = true }
    }.getOrNull()
    private val depthField = runCatching {
        VirtualMachine::class.java.getDeclaredField("depth").apply { isAccessible = true }
    }.getOrNull()

    /** 当前栈顶（「KEY:lastPc」）；函数下标不在侧车元数据里时以 "#下标" 兜底。 */
    fun read(rt: ProcessRuntime, metadata: BootMetadata): String {
        val vmField = vmField ?: return ""
        val stackField = funcStackField ?: return ""
        val depthField = depthField ?: return ""
        val vm = runCatching { vmField.get(rt) as? VirtualMachine }.getOrNull() ?: return ""
        val stack = runCatching { stackField.get(vm) as? IntArray }.getOrNull() ?: return ""
        val depth = runCatching { depthField.getInt(vm) }.getOrNull() ?: return ""
        if (depth < 0 || depth >= stack.size) return ""
        val idx = stack[depth]
        val key = metadata.functions.firstOrNull { it.index == idx }?.key ?: ("#" + idx)
        return key + ":" + vm.lastPc
    }
}

/**
 * 一份 explore 快照：变量面字节（引擎 save 格式）+ RNG 625 元状态 + 快照时的状态机态名。
 * 与 CmdSnap 的 snapshot/restore 同链（CmdSnap.kt:86-161），见 [SnapShots]。
 */
class ExploreSnap(val bytes: ByteArray, val rng: LongArray, val systemStateName: String)

/** 快照/还原/种子文件读写（CmdSnap.kt 全链口径：槽 99 沙箱，游戏根零接触）。 */
object SnapShots {

    /**
     * 抓当前会话快照。**调用点约束**：只能在「变量面静止」的时刻调——等待点回调里
     * （VM 停在输入等待，语句边界）或两局之间（无脚本帧）。这与原版 SAVEDATA 语句的
     * 执行形态同形（语句执行期抓取），引擎链本身支持。
     */
    fun take(session: GameSession, sandboxDir: Path, systemStateName: String): ExploreSnap {
        val ops = FileSystemOps(session.store, RootedSaveFileIo(sandboxDir), session.saveConfig)
        val ok = try {
            ops.saveData(CmdSnap.DEBUG_SLOT, "vmdbg explore " + OffsetDateTime.now())
        } catch (e: Exception) {
            throw CliError("explore 快照抓取异常: ${e.message}", e)
        }
        if (!ok) throw CliError("explore 快照抓取失败（saveData 返回 false，见 audit/盘空间）")
        val bytes = Files.readAllBytes(sandboxDir.resolve(session.saveConfig.normalPath(CmdSnap.DEBUG_SLOT)))
        val rng = try {
            session.store.randProvider?.state() ?: LongArray(0)
        } catch (_: CodeEE) {
            LongArray(0) // 非 SFMT 提供者：与 CmdSnap.buildSidecar 同款降级
        }
        return ExploreSnap(bytes, rng, systemStateName)
    }

    /**
     * 还原快照：字节拷进本进程沙箱槽 → 引擎 loadData 全链（LOADDATA 语义，整栈丢弃）→
     * handoff 清零（CmdSnap.kt:135-137 同款：装配/局间还原没有脚本帧在飞）→ RNG setState。
     * **不还原**的面见 Explore.kt 头 KDoc「已知保真边界」。
     */
    fun restore(session: GameSession, sandboxDir: Path, snap: ExploreSnap) {
        val slotRel = session.saveConfig.normalPath(CmdSnap.DEBUG_SLOT)
        val slotPath = sandboxDir.resolve(slotRel)
        // 槽相对路径可能带 sav/ 前缀（USE SAV FOLDER:YES），落盘前必须建父目录（CmdSnap.kt:122-128 同口径）。
        slotPath.parent?.let { Files.createDirectories(it) }
        Files.write(slotPath, snap.bytes)
        val ops = FileSystemOps(session.store, RootedSaveFileIo(sandboxDir), session.saveConfig)
        try {
            ops.loadData(CmdSnap.DEBUG_SLOT)
        } catch (e: CodeEE) {
            throw CliError("explore 快照还原被引擎拒收（LOADDATA 语义）: ${e.message}", e)
        }
        SaveLoadHandoff.of(session.store)?.reset()
        if (snap.rng.size == Sfmt19937.RANDDATA_LEN) {
            session.store.randProvider?.loadState(snap.rng)
        }
    }

    /** 种子落盘：主文件 `<dir>/seed.dbg` + sidecar（CmdSnap.sidecarPath 口径 + systemState 字段）。 */
    fun saveSeed(dir: Path, snap: ExploreSnap, randSeed: Long) {
        Files.createDirectories(dir)
        val main = dir.resolve("seed.dbg")
        Files.write(main, snap.bytes)
        val sidecar = CmdSnap.sidecarPath(main)
        Files.writeString(
            sidecar,
            buildString {
                append("{\"format\":1")
                append(",\"createdAt\":\"").append(Jsonl.escape(OffsetDateTime.now().toString())).append('"')
                append(",\"snapshotText\":\"").append(Jsonl.escape("vmdbg explore seed")).append('"')
                append(",\"randSeed\":").append(randSeed)
                append(",\"systemState\":\"").append(Jsonl.escape(snap.systemStateName)).append('"')
                append(",\"rngState\":[").append(snap.rng.joinToString(",")).append(']')
                append("}")
            },
            Charsets.UTF_8,
        )
    }

    /**
     * 读现成种子（--seed-snap / --resume）：主文件字节 + sidecar 的 rngState / systemState。
     * sidecar 缺席 = 只还变量面（RNG 不还原，确定性降级——与 CmdSnap.restore 的缺席容忍同口径）。
     * systemState 字段缺席（例如真机 DevTools 拉回的 CmdSnap 快照）= ""，由调用方回落缺省锚点。
     *
     * 解析不走 [Jsonl.parseLine]：sidecar 的 rngState 是数字数组，平面解析器会在数组首个逗号处
     * 截断、把后续数组元素当键名而整行返回 null（CmdSnap.kt:139-140 的同款坑）——
     * systemState 用 [scalarStringField] 专扫，rngState 照旧走 [jsonArrayField]。
     */
    fun loadSeed(main: Path): ExploreSnap {
        if (!Files.isRegularFile(main)) throw CliError("种子快照不存在: $main")
        val bytes = Files.readAllBytes(main)
        var rng: LongArray = LongArray(0)
        var stateName = ""
        val sidecar = CmdSnap.sidecarPath(main)
        if (Files.isRegularFile(sidecar)) {
            val line = Files.readString(sidecar, Charsets.UTF_8).lineSequence().firstOrNull() ?: ""
            val rngRaw = jsonArrayField(line, "rngState")
            if (!rngRaw.isNullOrEmpty() && rngRaw != "[]") rng = parseLongArray(rngRaw)
            stateName = scalarStringField(line, "systemState") ?: ""
        }
        return ExploreSnap(bytes, rng, stateName)
    }

    /**
     * 单行 JSON 里取字符串字段的值（含转义还原）；服务于含数组字段的行——
     * 那种行 [Jsonl.parseLine] 解不动（见 [loadSeed] 的说明）。
     * 值内出现未转义闭引号即止；转义序列按 Jsonl.escape 的逆口径手工还原
     * （systemState 是枚举名，实践中纯标识符，此处只兜常见四种防身）。
     */
    private fun scalarStringField(line: String, key: String): String? {
        val needle = "\"" + key + "\":\""
        val start = line.indexOf(needle)
        if (start < 0) return null
        val from = start + needle.length
        val sb = StringBuilder()
        var i = from
        while (i < line.length) {
            when (val c = line[i]) {
                '\\' -> {
                    if (i + 1 >= line.length) return null
                    when (val d = line[i + 1]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        else -> sb.append(d)
                    }
                    i += 2
                }
                '"' -> return sb.toString()
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    /** 单行 JSON 里取数组字段的原文（含括号）；CmdSnap.jsonArrayField 的私有实现在此复刻（同形状自产自读）。 */
    private fun jsonArrayField(line: String, key: String): String? {
        val needle = "\"" + key + "\":["
        val start = line.indexOf(needle)
        if (start < 0) return null
        val from = start + needle.length
        val end = line.indexOf(']', from)
        if (end < 0) return null
        return line.substring(from - 1, end + 1)
    }

    /** "[1,2,3]" → LongArray（CmdSnap.parseLongArray 同款）。 */
    private fun parseLongArray(raw: String): LongArray {
        val body = raw.trim().removePrefix("[").removeSuffix("]").trim()
        if (body.isEmpty()) return LongArray(0)
        return body.split(',').mapNotNull { it.trim().toLongOrNull() }.toLongArray()
    }
}

/** explore 计划：种子从哪来、每局怎么启动。 */
sealed interface ExplorePlan {

    /** 引导链播种：跑 [recipe] 到终点子串现身，打种子快照。 */
    class Boot(val recipe: BootRecipe) : ExplorePlan

    /** 现成快照播种（--seed-snap / --resume）：跳过引导链，[defaultAnchor] 供 sidecar 无状态字段时回落。 */
    class SeedFile(val main: Path, val defaultAnchor: SystemStateCode = ExploreDefaults.DEFAULT_ANCHOR) : ExplorePlan

    /**
     * 直调模式（单测 / 单函数遍历）：装配态即种子，每局 `callFunction(key, args)`。
     * 不涉及 BEGIN 状态机与队列喂入（直调不触碰 BeginHost），无引导链。
     */
    class DirectCall(val key: String, val intArgs: LongArray, val strArgs: Array<String>) : ExplorePlan
}

/** 一局任务：快照 + 锚点 + 重放链 + 分支护栏。 */
internal class EpisodeTask(
    val snap: ExploreSnap,
    /** 局起点的状态机锚点；null = 直调模式（不重锚）。 */
    val anchorState: SystemStateCode?,
    /** 从锚点重放到分支等待点的动作链（根任务为空）。 */
    val chain: List<ExploreAction>,
    /** 该分支点上一轮已执行的动作（重放到达后策略再给出同一动作 = 该支耗尽）。 */
    val tried: ExploreAction?,
    /** 分支点签名（重放到达校验，校验一次后清空；根任务 null）。 */
    var branchSignature: String?,
    /** 分支嵌套深度（根 0；快照栈的大小 = 当前根到叶路径上的分支点数，max-depth 卡它）。 */
    val depth: Int,
)

/** 一局的结果记录（测试断言面 + err 进度行的数据源）。 */
class EpisodeLog(
    val no: Int,
    val outcome: OutcomeKind,
    val note: String,
    /** 本局完整输入链（种子以来：重放段 + 自由段），字符串化（见 [ExploreDriver.actionLabel]）。 */
    val chain: List<String>,
    /** 局终态时的控制台尾。 */
    val tail: List<String>,
)

/** explore 配置（CLI 旋钮的直载体；缺省值 = CONTRACT-DBG6 §4.E1.2/:5 钉死值）。 */
class ExploreConfig(
    val maxEpisodes: Int = 200,
    /** 每局墙钟预算毫秒（CLI 的 --wall-min 分钟数 × 60000；0 = 关闭）。 */
    val wallMs: Long = 30L * 60_000L,
    val maxDepth: Int = 24,
    val outDir: Path,
    val tailLines: Int = ExploreDefaults.TAIL_LINES,
    /** 浸泡档总开关（obs-soak）：true = 随机操作 + 随机停顿 + 跑满 [soakWallMs] 墙钟，关掉快照栈分支。 */
    val soak: Boolean = false,
    /** 浸泡总墙钟毫秒（[soak] 为真时的主终止条件；局终后重新播种续跑至此）。 */
    val soakWallMs: Long = 0L,
    /** 每步「玩家停顿」的随机下限毫秒。 */
    val idleMinMs: Long = 0L,
    /** 每步「玩家停顿」的随机上限毫秒（含长间隔；与下限同值 = 固定停顿）。 */
    val idleMaxMs: Long = 0L,
    /** 单步「提交 → 到点」慢步阈值毫秒（≥ 此值单独成节，缺省 5 s）。 */
    val slowStepMs: Long = 5_000L,
    /**
     * 单段落墙钟闸毫秒（CLI 的 `--step-wall-ms`，直载体为 `VmConfig.maxRunMillis`）；
     * **缺省 0 = 关闭**，与既有行为逐字一致。与 [wallMs]（每局墙钟，只喂输入循环）和
     * `--steps`（全局/引导共用的步数闸）三闸彼此独立：本闸纯计算也吃得到，
     * 命中即中断这一步并把抛点现场写进台账（见 [reportStepSceneLocked]）。
     */
    val stepWallMs: Long = 0L,
    /** 停顿 RNG 种子（确定性复跑；与策略 seed 可同值，单独持流避免相互消耗）。 */
    val soakSeed: Long = DEFAULT_EXPLORE_SEED,
    /** 同种发现每轮的放行上限（护栏口径见 [FindingGuard] 与 [ExploreDefaults.FINDING_KIND_CAP]）。 */
    val findingKindCap: Int = ExploreDefaults.FINDING_KIND_CAP,
    /** 发现台账每轮总放行上限（[findingKindCap] 之外的第二道闸）。 */
    val findingTotalCap: Int = ExploreDefaults.FINDING_TOTAL_CAP,
    /**
     * 只引导不跑局（obs-compat512 的 `--boot-only`）：引导到落 seed.dbg 即收工。
     * 缺省 false = 既有行为逐字不变；true 时策略供给与逐局循环整段跳过（引导失败照旧报红）。
     */
    val bootOnly: Boolean = false,
)

/**
 * 单步墙钟闸与步数闸的**判据**（obs-midhang）：两者共用
 * [com.eratw.port.core.code.StepLimitExceeded]（Vm.kt:616 的 `timeLimitAt` 与 `stepLimitAt`
 * 两个抛点），唯一区别是 `elapsedMs` —— 时间闸带真实段耗时（≥0），步数闸恒为 -1。
 */
internal fun isSegmentTimeGate(t: com.eratw.port.core.code.StepLimitExceeded): Boolean = t.elapsedMs >= 0L

/**
 * 墙钟闸现场的纯搬运（键名固定：`funcName/line/pc/steps/elapsedMs/callChain/stepWallMs`）。
 * 六项全取自异常自带字段（抛点就地构造，Vm.kt 的 `timeLimitAt` 注释即口径），本函数不读时钟、
 * 不反射猜现场；[stepWallMs] 是启用中的阈值，用于台账里复核「阈值 vs 实际」。
 */
internal fun stepLimitScene(
    t: com.eratw.port.core.code.StepLimitExceeded,
    stepWallMs: Long,
): LinkedHashMap<String, String> = linkedMapOf(
    "funcName" to (t.funcName ?: ""),
    "line" to t.line.toString(),
    "pc" to t.pc.toString(),
    "steps" to t.steps.toString(),
    "elapsedMs" to t.elapsedMs.toString(),
    "callChain" to (t.callChain ?: ""),
    "stepWallMs" to stepWallMs.toString(),
)

/** explore 运行结果（CLI 汇总行的数据源）。 */
class ExploreRunResult(
    val bootFailure: String?,
    val seedPath: Path?,
    val anchor: SystemStateCode?,
    val episodes: Int,
    val outcomeCounts: Map<OutcomeKind, Int>,
    val findingsCount: Int,
    /** 被 [FindingGuard] 抑制的同种重复条数（未落台账；0 = 护栏未触发）。 */
    val findingsSuppressed: Int = 0,
)

/**
 * explore 驱动器主体。线程形状、分支与时间旅行、保真边界见本文件头 KDoc。
 *
 * 锁纪律：全部可变态（阶段 / 任务栈 / 重放游标 / 动作链 / 喂入旗标）收在 [lock] 内，
 * 方法名以 Locked 结尾的辅助函数要求持锁进入。泵线程（等待点回调）与喂入线程（队列面）
 * 都经锁串行化；引擎任一时刻至多一个等待方，决定序列因此确定。
 *
 * @param sink 已接进会话的 explore 输入宿主（Assembler.assemble 的 sinkOverride 注入，
 *   本驱动器构造后立即 [ExploreInputSink.attach]）
 * @param policyProvider 策略供给：引导成功后才调用（CLI 的策略工厂缝允许引导先行失败，
 *   不必先有策略实现）
 */
class ExploreDriver(
    private val assembled: Assembler.Assembled,
    private val sink: ExploreInputSink,
    private val cfg: ExploreConfig,
    private val findings: FindingSink,
    private val plan: ExplorePlan,
    private val policyProvider: () -> ExplorePolicy,
    private val err: PrintStream,
    /**
     * E2 台账（explore.jsonl / findings.jsonl / replay / coverage.txt 的写手，DBG7 接线）；
     * null = 不记（既有单测与最小用法）。挂点见本文件头 KDoc「E2 台账接线」。
     */
    private val out: ExploreOut? = null,
    /**
     * 浸泡档计量器（obs-soak）：[ExploreConfig.soak] 为真时非 null，逐条记「提交 → 到点」耗时。
     * null = 非浸泡（既有探索路径零行为变化）。
     */
    private val meter: SoakMeter? = null,
) : ExploreHooks, SoakTuner {

    private val session: GameSession = assembled.session
    private val collector = ConsoleButtonCollector(session.console)
    private val lock = Any()

    // ---- 以下字段均在 lock 内读写 ----
    private var phase = Phase.BOOT
    private var policy: ExplorePolicy? = null
    private val taskStack = ArrayDeque<EpisodeTask>()
    private var current: EpisodeTask? = null
    private var replayIdx = 0
    private var episodeActions = ArrayList<ExploreAction>()
    private var prevWp: WaitPoint? = null
    private var prevAction: ExploreAction? = null
    private var prevWasFree = false
    private var episodeOver = false
    private var forcedAbandon = false
    private var episodeNo = 0
    private var bootStepIdx = 0
    private var bootFailure: String? = null
    // ---- 引导逐步台账（obs-bootrecipe；均在 lock 内读写）----
    /**
     * 引导期每个等待点一行 JSONL：步序 / 输入 / 期望子串 / 实际动作 / 等待点形态 / 栈顶函数 /
     * **本次新增的屏上行**（上一条记账以来的增量）。
     * 落盘时机：引导失败（[bootFailLocked]，全部失败原因都经它）与引导到种子（[takeSeedLocked]）
     * 各写一次 `<out>/boot-steps.jsonl` —— 有了它，「配方第 k 步到底落在哪一面」是可读的，
     * 不必从 boot-fail-console.txt 的全量转写里反推等待点边界（反推正是 obs-midhang 卡住的地方）。
     */
    private val bootTrace = ArrayList<String>()
    /** 上一次记账时的控制台行序号水位（`Line.serial` 单调递增，行缓冲裁头不影响增量判定）。 */
    private var bootLastSerial = 0L

    /** 引导期「任意键自动放行」计数（护栏见 [ExploreDefaults.BOOT_ANYKEY_AUTO_MAX]；lock 内读写）。 */
    private var bootAnyKeyAuto = 0
    private var seed: ExploreSnap? = null
    private var anchor: SystemStateCode? = null
    private var budget: WallBudget? = null
    private var findingsCount = 0
    /**
     * 同种发现重复抑制护栏（obs-soakwall 任务一）：驱动器全部台账投递点（两个
     * [reportFindingLocked] 重载 + [reportStepSceneLocked]）都先过 [FindingGuard.allow]，
     * 超出定长上限的只累计 [FindingGuard.suppressed]、不再写台账。lock 内使用，本身不加锁。
     */
    private val findingGuard = FindingGuard(cfg.findingKindCap, cfg.findingTotalCap)
    /**
     * 喂入线程侧的墙钟现场挂起位（obs-soakwall 死循环修复）：旧实现命中后只记台账就解除停车，
     * 预算已耗尽 ⇒ 泵回到同一等待点再抛再记，「记一条 = 一次输入」无限循环（组长实测
     * 28,964 行 WALL_LIMIT / episode=1）。现在首条现场经 [reportFindingLocked] 落账一次、
     * 本字段挂起异常，泵在下一步界（[stateMachinePump]）据此把本局折成 WALL_LIMIT 终态收口。
     */
    private var feedWallPending: WallLimitExceeded? = null
    private val outcomeCounts = HashMap<OutcomeKind, Int>()
    val episodeLogs = ArrayList<EpisodeLog>()

    // ---- 浸泡档状态（obs-soak；均在 lock 内读写，idleRng 的抽取亦经锁串行，确定可复跑） ----
    private val idleRng = Random(cfg.soakSeed)
    private var soakLastSubmitNs = 0L
    private var soakPendingStackTop = ""
    private var soakPendingAction = ""
    private var soakStepIdx = 0

    private enum class Phase { BOOT, EXPLORE, DONE }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 主流程：播种（引导链 / 现成快照 / 直调装配态）→ 逐局探索。阻塞至收工。 */
    fun run(): ExploreRunResult {
        sink.attach(this)
        if (cfg.soak) sink.soak = this
        Files.createDirectories(cfg.outDir)
        // 覆盖探针先于播种挂上：引导段的 VM 执行也计入（挂卸口径见头 KDoc「E2 台账接线」）。
        out?.coverage?.attach(session.runtime)
        try {
            when (plan) {
                is ExplorePlan.Boot -> boot(plan.recipe)
                is ExplorePlan.SeedFile -> {
                    val loaded = SnapShots.loadSeed(plan.main)
                    seed = loaded
                    anchor = plan.defaultAnchor.let { def ->
                        if (loaded.systemStateName.isEmpty()) def
                        else runCatching { SystemStateCode.valueOf(loaded.systemStateName) }.getOrDefault(def)
                    }
                    phase = Phase.EXPLORE
                }
                is ExplorePlan.DirectCall -> {
                    seed = SnapShots.take(session, assembled.sandboxDir, "")
                    anchor = null
                    phase = Phase.EXPLORE
                }
            }
            if (bootFailure == null && !cfg.bootOnly) {
                // 引擎 save/load 子系统的首次走通（JIT + 类装载冷启动）不计入任何一局的墙钟预算：
                // 播种后立即做一次 seed 自往返——take 已暖 save 半边，这里暖 load 半边。
                // 没有这一步，第一局的 restore 冷启动成本直接吃穿 --wall-min（wallBudgetTripsOnInputLoop
                // 首跑实锤：预算 200ms 时第二局在首个预算检查点就超时、零 push、栈空收工）。
                SnapShots.restore(session, assembled.sandboxDir, seed ?: throw IllegalStateException("种子缺失"))
                policy = policyProvider()
                val rootTask = EpisodeTask(
                    snap = seed ?: throw IllegalStateException("种子缺失"),
                    anchorState = anchor,
                    chain = emptyList(),
                    tried = null,
                    branchSignature = null,
                    depth = 0,
                )
                taskStack.addLast(rootTask)
                if (cfg.soak) {
                    // 浸泡主终止条件 = 总墙钟（[ExploreConfig.soakWallMs]）；局终后重新播种续跑。
                    // 关掉分支入栈（onObservedLocked 经 cfg.soak 门控）→ taskStack 恒空 → 反复走 root，
                    // 即「一条随机操作流一直玩下去」。maxEpisodes 作二次安全闸（真跑给到极大）。
                    val deadlineNs = System.nanoTime() + cfg.soakWallMs * 1_000_000L
                    while (episodeNo < cfg.maxEpisodes && System.nanoTime() < deadlineNs) {
                        val task = if (taskStack.isEmpty()) rootTask else taskStack.removeLast()
                        runEpisode(task)
                    }
                } else {
                    while (episodeNo < cfg.maxEpisodes && taskStack.isNotEmpty()) {
                        val task = taskStack.removeLast()
                        runEpisode(task)
                    }
                }
            }
        } finally {
            // 探针卸下（生产零行为路径）+ 台账收口（冲洗逐步/发现台账 + 落 coverage.txt）。
            // 引导失败 / 装配后异常同样走这里：coverage.txt 落失败前已触碰的读数。
            sink.soak = null
            out?.coverage?.detach(session.runtime)
            out?.close()
        }
        return ExploreRunResult(
            bootFailure = bootFailure,
            seedPath = when (plan) {
                is ExplorePlan.Boot -> if (bootFailure == null) cfg.outDir.resolve("seed.dbg") else null
                is ExplorePlan.SeedFile -> plan.main
                is ExplorePlan.DirectCall -> null
            },
            anchor = anchor,
            episodes = episodeNo,
            outcomeCounts = outcomeCounts.toMap(),
            findingsCount = findingsCount,
            findingsSuppressed = findingGuard.suppressed,
        )
    }

    // ------------------------------------------------------------------
    // 引导（CONTRACT-DBG6 §4.E1.4；步骤表内容在 ExploreBoot）
    // ------------------------------------------------------------------

    /** 引导阶段：泵线程跑状态机、喂入线程兜底队列面等待点；到终点态界打种子。 */
    private fun boot(recipe: BootRecipe) {
        val pump = Thread({ bootPump(recipe) }, "vmdbg-explore-pump")
        val feeder = Thread({ feederBody() }, "vmdbg-explore-feeder")
        pump.isDaemon = true
        feeder.isDaemon = true
        pump.start()
        feeder.start()
        pump.join()
        synchronized(lock) { episodeOver = true }
        feeder.join()
    }

    private fun bootPump(recipe: BootRecipe) {
        try {
            session.begin(BeginType.TITLE)
            while (true) {
                val done = synchronized(lock) {
                    if (bootFailure != null) return
                    val terminalNow = tailContainsLocked(recipe.terminal)
                    // 浸泡档：终点子串一现身（停在带「第1日」尾屏的等待点）即播种，不等食谱耗尽——
                    // 随机游玩的浸泡种子只需一个「进到游戏里」的落点，避免盲喂尾部步冲过头（overshoot）。
                    // 探索档（exhaustive）沿用原口径：食谱耗尽 + 终点现身才播种（逐支全排列要跑完整链）。
                    (bootStepIdx >= recipe.steps.size && terminalNow) || (cfg.soak && terminalNow)
                }
                if (done) {
                    synchronized(lock) { takeSeedLocked() }
                    return
                }
                val step = session.resumeSystem()
                synchronized(lock) {
                    if (bootFailure != null) return
                    if (step.outcome == StepOutcome.BLOCKED || step.outcome == StepOutcome.FAILED) {
                        bootFailLocked(
                            "引导中途状态机终态 ${step.outcome.name} 于 ${step.from.name}：" +
                                (step.blocker?.message ?: "未知阻塞"),
                        )
                        return
                    }
                    if (session.runtime.quitRequested) {
                        bootFailLocked("引导中途脚本 QUIT（终点「" + recipe.terminal + "」未现身）")
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            synchronized(lock) {
                if (bootFailure == null) {
                    bootFailLocked("引导泵异常：" + t.javaClass.simpleName + " " + (t.message ?: ""))
                }
            }
        }
    }

    /** 种子打点（泵线程、两步之间的界面上：无脚本帧在飞）。 */
    private fun takeSeedLocked() {
        val s = SnapShots.take(session, assembled.sandboxDir, session.runtime.systemState.name)
        seed = s
        anchor = session.runtime.systemState
        SnapShots.saveSeed(cfg.outDir, s, Cli.RAND_SEED)
        phase = Phase.EXPLORE
        episodeOver = true
        bootTraceRow(
            listOf(
                "event" to "SEED",
                "step" to bootStepIdx,
                "systemState" to session.runtime.systemState.name,
                "newLines" to bootNewConsoleLinesLocked().size,
                "seed" to cfg.outDir.resolve("seed.dbg").fileName.toString(),
            ),
        )
        dumpBootTraceLocked()
        err.println("explore: 引导完成，种子落盘（anchor=" + anchor?.name + "）")
    }

    /** 自上一条记账以来新落到控制台行的文本（引导台账的「本次新增屏上行」）。 */
    private fun bootNewConsoleLinesLocked(): List<String> {
        val lines = session.console.lines()
        if (lines.isEmpty()) return emptyList()
        val fresh = lines.filter { it.serial > bootLastSerial }.map { session.console.textOf(it) }
        bootLastSerial = lines.last().serial
        return fresh
    }

    /** 记一行引导台账（字段名固定，值截断到 [ExploreDefaults.BOOT_TRACE_TEXT_MAX]，防整屏 ASCII 图灌爆台账）。 */
    private fun bootTraceRow(fields: List<Pair<String, Any?>>) {
        bootTrace.add(Jsonl.obj(fields.map { (k, v) -> k to (v?.toString()?.take(ExploreDefaults.BOOT_TRACE_TEXT_MAX) ?: "") }))
    }

    /**
     * 引导台账落盘（`<out>/boot-steps.jsonl`，全量覆盖写：一次引导一份）。
     * 失败不掩盖主因——与现场落盘同一口径，写不进就继续报红。
     */
    private fun dumpBootTraceLocked() {
        runCatching {
            Files.createDirectories(cfg.outDir)
            Files.writeString(
                cfg.outDir.resolve(ExploreDefaults.BOOT_STEPS_LEDGER),
                bootTrace.joinToString("\n", postfix = "\n"),
                Charsets.UTF_8,
            )
        }
    }

    private fun bootFailLocked(reason: String) {
        bootFailure = reason
        episodeOver = true
        findingsCount++
        try {
            val step = currentRecipe()?.steps?.getOrNull(bootStepIdx)
            val tail = session.console.loggedLines()
            Files.writeString(
                cfg.outDir.resolve("boot-fail.json"),
                Jsonl.obj(
                    listOf(
                        "ts" to OffsetDateTime.now().toString(),
                        "kind" to "BOOT_FAIL",
                        "reason" to reason,
                        "stepIndex" to bootStepIdx,
                        "stepInput" to (step?.input ?: ""),
                        "stepExpect" to (step?.expect ?: ""),
                        "systemState" to session.runtime.systemState.name,
                    ),
                ) + "\n",
                Charsets.UTF_8,
            )
            Files.writeString(cfg.outDir.resolve("boot-fail-console.txt"), tail.joinToString("\n"), Charsets.UTF_8)
            dumpBootTraceLocked()
            findings.onFinding(
                "BOOT_FAIL",
                mapOf("reason" to reason, "stepIndex" to bootStepIdx.toString()),
                emptyList(),
                emptyList(),
            )
        } catch (_: Exception) {
            // 现场落盘失败不掩盖主因
        }
        err.println("explore: 引导失败 —— " + reason)
    }

    /** 当前引导配方（仅 Boot 计划有；其他计划在引导阶段不会走到这里）。 */
    private fun currentRecipe(): BootRecipe? = (plan as? ExplorePlan.Boot)?.recipe

    /**
     * 引导阶段在等待点上要的下一个动作（期望检查 → 按步骤喂入；耗尽 → 终点已现 = 最小推进）。
     *
     * ## 任意键面不经食谱（obs-bootrecipe 修正的口径）
     *
     * [ExploreBoot] 头 KDoc 自 DBG6 就钉着「任意键等待点不经食谱（自动 PassAnyKey 放行，对应
     * FLOW.md §2 里的 (Enter×N) 段）」，但本函数此前在**每一个**等待点都消费一个食谱步，并在消费前
     * 硬性判定期望子串（[bootExpectHit]）。两条口径叠起来的后果，2026-11-01 的 boot-steps.jsonl
     * 逐条可读：第 15 步（`1 ;; 全部覆盖`）到达的不是口上技能变更面，而是 (Enter×N) 段里
     * `NEWGAME_VERUP` 打「游戏开始处理完成」的那个 PRINTW（anyKey=true，量到的新增屏上行逐字是
     * 「正在匹配经验与能力等级…」+「游戏开始处理完成」）——期望门抢在 [bootActionFor] 的 anykey
     * 宽容（「步值舍弃、后续步自会落在它该在的等待点上」）之前开火，宽容路径对任何带期望的步
     * **永不可达**。真机取证原文（FLOW.md §2）在该步前明明写着「(Enter×N) ← 开局链：经验/能力换算、
     * 初始处理、各角色口上UPDATE（几十个等待点）」，内置链却没有对应的填充步，于是每局必死于第 15 步。
     *
     * 现口径（与类 KDoc 一致，期望门只在它真正交付输入的那一面开火）：
     * - 任意键面 + 队列面（`queueFace`）：不变，走下面的消费路径（队列面没有真 anykey 语义，
     *   按食谱喂 0 是既有交付路径）；
     * - 任意键面 + 当前步是 Enter 步：仍消费该步（配方作者显式写的推进步，进链、进重放、可判期望）；
     * - 任意键面 + 当前步是取值步 / 配方已耗尽：**自动放行且不消费步、不判期望**，记一条
     *   `ANYKEY_AUTO` 台账；累计超 [ExploreDefaults.BOOT_ANYKEY_AUTO_MAX] 判引导失败
     *   （纯 anykey 死循环不吃步数闸，必须有闸）。
     */
    private fun bootNextActionLocked(wp: WaitPoint, queueFace: Boolean): ExploreAction {
        val recipe = currentRecipe() ?: throw BootAbortException()
        // 本等待点自上一条记账以来的新增屏上行：配方错位时它就是「这一面究竟是什么」的证据。
        val fresh = bootNewConsoleLinesLocked()
        if (wp.anyKey && !queueFace) {
            val pending = recipe.steps.getOrNull(bootStepIdx)
            if (pending == null || !isEnterInput(pending.input)) {
                bootAnyKeyAuto++
                bootTraceRow(
                    listOf(
                        "event" to "ANYKEY_AUTO",
                        "step" to bootStepIdx,
                        "pendingInput" to (pending?.input ?: ""),
                        "pendingExpect" to (pending?.expect ?: ""),
                        "anyKey" to true,
                        "autoCount" to bootAnyKeyAuto,
                        "stackTop" to wp.stackTop,
                        "newLines" to fresh.size,
                        "newText" to fresh.joinToString(" | "),
                    ),
                )
                if (bootAnyKeyAuto > ExploreDefaults.BOOT_ANYKEY_AUTO_MAX) {
                    bootFailLocked(
                        "引导在任意键面自动放行超过 " + ExploreDefaults.BOOT_ANYKEY_AUTO_MAX +
                            " 个仍未到终点「" + recipe.terminal + "」（栈顶 " + wp.stackTop + "；见 boot-steps.jsonl）",
                    )
                    throw BootAbortException()
                }
                return ExploreAction.PassAnyKey
            }
        }
        if (bootStepIdx < recipe.steps.size) {
            val step = recipe.steps[bootStepIdx]
            if (!bootExpectHit(step.expect, consoleTailLocked(cfg.tailLines))) {
                bootTraceRow(
                    listOf(
                        "event" to "EXPECT_MISS",
                        "step" to (bootStepIdx + 1),
                        "input" to step.input,
                        "expect" to step.expect,
                        "anyKey" to wp.anyKey,
                        "type" to wp.request.type.name,
                        "stackTop" to wp.stackTop,
                        "newLines" to fresh.size,
                        "newText" to fresh.joinToString(" | "),
                        "tail" to consoleTailLocked(cfg.tailLines).joinToString(" | "),
                    ),
                )
                bootFailLocked(
                    "引导第 " + (bootStepIdx + 1) + " 步期望子串未命中：「" + step.expect +
                        "」（输入 " + step.input + "；屏尾无此串，见 boot-fail-console.txt / boot-steps.jsonl）",
                )
                throw BootAbortException()
            }
            val action = bootActionFor(step, wp, queueFace)
            bootStepIdx++
            // 食谱步入 E2 链（原文输入 + 期望注记，与 --boot 配方语法同构；anyKey 宽容放行的
            // 步值舍弃也照记 —— 重放走同一翻译矩阵，行为一致）。
            out?.logBootStep(step.input, step.expect)
            bootTraceRow(
                listOf(
                    "event" to "STEP",
                    "step" to bootStepIdx,
                    "input" to step.input,
                    "expect" to step.expect,
                    "action" to actionLabel(action),
                    "anyKey" to wp.anyKey,
                    "type" to wp.request.type.name,
                    "queueFace" to queueFace,
                    "stackTop" to wp.stackTop,
                    "newLines" to fresh.size,
                    "newText" to fresh.joinToString(" | "),
                ),
            )
            return action
        }
        // 食谱耗尽：终点已在尾 → 最小推进（anykey 放行 / INT 喂 0 / STR 喂空串），种子在下一步界打点。
        if (consoleTailLocked(cfg.tailLines).any { it.contains(recipe.terminal) }) {
            return defaultAdvance(wp)
        }
        bootTraceRow(
            listOf(
                "event" to "EXHAUSTED_OFF_TERMINAL",
                "step" to bootStepIdx,
                "anyKey" to wp.anyKey,
                "type" to wp.request.type.name,
                "stackTop" to wp.stackTop,
                "newLines" to fresh.size,
                "newText" to fresh.joinToString(" | "),
                "tail" to consoleTailLocked(cfg.tailLines).joinToString(" | "),
            ),
        )
        bootFailLocked("食谱耗尽于非终点等待点（终点「" + recipe.terminal + "」未现身；type=" + wp.request.type.name + "）")
        throw BootAbortException()
    }

    /** 食谱步骤 → 动作：Enter = anykey 放行（队列面以 0 应答，见 [feederSubmit]）；数字 = FeedInt；其余 = FeedStr。 */
    private fun bootActionFor(step: BootStep, wp: WaitPoint, queueFace: Boolean): ExploreAction {
        val raw = step.input.trim()
        // 取值步落在 anyKey 等待点 = 排版漂移（PRINTW 位置与真机不一致）：宽容放行 anykey，
        // 步值舍弃（比硬死好——后续步自会落在它该在的等待点上，错位由期望子串兜底）。
        if (wp.anyKey && !queueFace) {
            return ExploreAction.PassAnyKey
        }
        if (isEnterInput(raw)) {
            if (queueFace || !wp.anyKey) {
                // 引导食谱的 Enter 步落在值等待面上（含队列面）：喂 0 = ASK_YN 的「是」惯例（FLOW.md §0.1）。
                return ExploreAction.FeedInt(0L)
            }
            return ExploreAction.PassAnyKey
        }
        val n = raw.toLongOrNull()
        // 数字配方落在 STR 型等待点上按字符串交付（原版 INPUTS 亦收数字文本；INT 型不受影响）。
        return when {
            n == null -> ExploreAction.FeedStr(raw)
            wp.request.type == InputRequest.InputType.STR -> ExploreAction.FeedStr(raw)
            else -> ExploreAction.FeedInt(n)
        }
    }

    /** 食谱耗尽后的最小推进（终点已现身的前提由调用方保证）。 */
    private fun defaultAdvance(wp: WaitPoint): ExploreAction = when {
        wp.anyKey -> ExploreAction.PassAnyKey
        wp.request.type == InputRequest.InputType.INT -> ExploreAction.FeedInt(0L)
        wp.request.type == InputRequest.InputType.STR -> ExploreAction.FeedStr("")
        else -> ExploreAction.FeedInt(0L)
    }

    private class BootAbortException : RuntimeException("explore 引导中止")

    // ------------------------------------------------------------------
    // 逐局（CONTRACT-DBG6 §4.E1.2/:3）
    // ------------------------------------------------------------------

    private fun runEpisode(task: EpisodeTask) {
        synchronized(lock) {
            episodeNo++
            current = task
            replayIdx = 0
            episodeActions = ArrayList(task.chain)
            prevWp = null
            prevAction = null
            prevWasFree = false
            episodeOver = false
            forcedAbandon = false
            feedWallPending = null
            if (cfg.soak) {
                // 新局：提交计时清零（首步无「上一步」可结算），步序重计。
                soakLastSubmitNs = 0L
                soakPendingStackTop = ""
                soakPendingAction = ""
                soakStepIdx = 0
            }
        }
        err.println(
            "explore: episode " + episodeNo + " depth=" + task.depth +
                " chain=" + task.chain.size + (if (task.tried != null) " retry" else " fresh"),
        )
        SnapShots.restore(session, assembled.sandboxDir, task.snap)
        if (task.anchorState != null) reAnchor(task.anchorState)
        collector.resetBaseline()
        val wall = WallBudget(cfg.wallMs)
        sink.budget = wall
        // AWAIT 族的墙钟兜底仍挂在装配时的缺省 sink 上（Assembler 的 WallTimerSink 接线）：
        // 注入 sink 覆盖了语句等待，AWAIT 不经它——把同一预算实例也挂到缺省 sink 的检查点。
        assembled.inputSink.budget = wall
        synchronized(lock) { budget = wall } // 喂入线程的停车侧代查用
        try {
            val pump = Thread({ pumpBody(task) }, "vmdbg-explore-pump")
            val feeder =
                if (plan is ExplorePlan.DirectCall) null
                else Thread({ feederBody() }, "vmdbg-explore-feeder")
            pump.isDaemon = true
            feeder?.isDaemon = true
            pump.start()
            feeder?.start()
            pump.join()
            synchronized(lock) { episodeOver = true }
            feeder?.join()
        } finally {
            sink.budget = null
            assembled.inputSink.budget = null
            synchronized(lock) { budget = null }
        }
    }

    private fun pumpBody(task: EpisodeTask) {
        try {
            when (plan) {
                is ExplorePlan.DirectCall -> {
                    session.runtime.callFunction(plan.key, plan.intArgs, plan.strArgs)
                    recordTerminalLocked(OutcomeKind.EPISODE_DONE, null, "直调返回")
                }
                else -> stateMachinePump(task)
            }
        } catch (t: ExploreAbandon) {
            t.findingKind?.let { kind -> reportFindingLocked(kind, t) }
            recordTerminalLocked(OutcomeKind.ABANDONED, t, "弃局：" + t.message)
        } catch (t: BootAbortException) {
            // 引导失败现场已由 bootFailLocked 落盘，这里只收口，不再报 CRASH。
            recordTerminalLocked(OutcomeKind.ABANDONED, t, "引导中止")
        } catch (t: WallLimitExceeded) {
            reportFindingLocked("WALL_LIMIT", t)
            recordTerminalLocked(OutcomeKind.WALL_LIMIT, t, "墙钟超限")
        } catch (t: com.eratw.port.core.code.StepLimitExceeded) {
            // 同异常两种闸：elapsedMs>=0 = 单段落墙钟闸（--step-wall-ms）命中，-1 = 步数闸。
            // 前者是 obs-midhang 的定标入口：这一段落从未跨过让出点（没有输入等待、没有 AWAIT），
            // 纯计算跑到超时点被中断，抛点自带函数名/行号/pc/调用链/已执行步数 = 肇事现场。
            // 局终态类别沿用 STEP_LIMIT（既有 OutcomeKind 枚举与 findingKindOf 映射不动）；
            // 台账里靠发现类别字符串与现场字段区分两种闸。
            if (isSegmentTimeGate(t)) {
                reportStepSceneLocked("STEP_WALL_LIMIT", t)
                recordTerminalLocked(OutcomeKind.STEP_LIMIT, t, "单步墙钟超限 " + t.elapsedMs + "ms")
            } else {
                reportFindingLocked("STEP_LIMIT", t)
                recordTerminalLocked(OutcomeKind.STEP_LIMIT, t, "步数超限")
            }
        } catch (t: OutOfMemoryError) {
            // 堆贴顶 OOM（obs-soak512）：真机上报同相位（used=510/512MiB 时平时 2ms 的页面跑到
            // 一分钟），桌面 3g 挡从来没进过这一相位——512m 挡浸泡里 OOM 就是**被观察对象本身**，
            // 不许吞、不许顺手调大挡位蒙混。现场三份：soak.txt 的堆 OOM 专节（meter.oom）、
            // findings.jsonl（HEAP_OOM）、本局终态 CRASH（在飞步仍进耗时台账，outcome=CRASH）。
            // 后两步各自 runCatching：OOM 之后连「记 OOM」的分配都可能再被拒——最后一步
            // 无条件 episodeOver=true，保证喂入线程能收口、浸泡循环能续跑（挡位真贴顶时
            // 下一局的 restore 可能再 OOM，那也照样有现场，不静默挂死）。
            val scene = synchronized(lock) { soakOomSceneLocked(t) }
            meter?.oom(scene)
            runCatching { reportFindingLocked("HEAP_OOM", t) }
            runCatching { recordTerminalLocked(OutcomeKind.CRASH, t, "堆 OOM：" + (t.message ?: t.javaClass.name)) }
            synchronized(lock) { episodeOver = true }
        } catch (t: Throwable) {
            reportFindingLocked("CRASH", t)
            recordTerminalLocked(OutcomeKind.CRASH, t, "异常：" + t.javaClass.simpleName)
        }
    }

    /** 状态机泵：一步一查（终态 / 锚点回归 / QUIT / 强制弃局）。 */
    private fun stateMachinePump(task: EpisodeTask) {
        val anchorState = task.anchorState
        while (true) {
            val step = session.resumeSystem()
            synchronized(lock) {
                // 喂入侧墙钟收口（obs-soakwall）：喂入线程已记现场并挂起异常，本步界即终态——
                // 不再重进等待点、不再重复记同一类 finding。
                feedWallPending?.let { t ->
                    recordTerminalLocked(OutcomeKind.WALL_LIMIT, t, "喂入侧墙钟超限（本局收口）")
                    feedWallPending = null
                    return
                }
                if (session.runtime.quitRequested) {
                    recordTerminalLocked(OutcomeKind.EPISODE_DONE, null, "脚本 QUIT")
                    return
                }
                if (step.outcome == StepOutcome.BLOCKED || step.outcome == StepOutcome.FAILED) {
                    recordTerminalLocked(
                        OutcomeKind.EPISODE_DONE, null,
                        "状态机终态 " + step.outcome.name + "@" + step.from.name +
                            (step.blocker?.let { "：" + it.message } ?: ""),
                    )
                    return
                }
                if (forcedAbandon) {
                    recordTerminalLocked(OutcomeKind.ABANDONED, null, "强制弃局（喂入线程决定）")
                    return
                }
                if (anchorState != null && session.runtime.systemState == anchorState) {
                    recordTerminalLocked(OutcomeKind.EPISODE_DONE, null, "回到锚点态 " + anchorState.name)
                    return
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 喂入线程（BEGIN 面队列等待；直调模式不存在）
    // ------------------------------------------------------------------

    private fun feederBody() {
        val q = session.inputSink
        while (true) {
            synchronized(lock) { if (episodeOver) return }
            if (!q.isAwaiting) {
                Thread.sleep(FEEDER_POLL_MS)
                continue
            }
            // 本次决策针对的那条等待（对象同一性 = 同一次等待的判据；每次系统等待都是新建
            // InputRequest）。块外声明：submit 后的「等消费」自旋要读它。
            var decidedAwait: InputRequest? = null
            var skipTick = false
            var armedDrain = false
            var soakFeeder: Pair<WaitPoint, ExploreAction>? = null
            try {
                synchronized(lock) {
                    if (episodeOver) return
                    val awaitingNow = q.awaiting
                    if (awaitingNow == null) skipTick = true else {
                    val request = awaitingNow
                    decidedAwait = request
                    if (feedWallPending != null) {
                        // 墙钟现场已挂起、等泵在下一步界收口：本等待点不再代查预算、不再记台账，
                        // 只解除停车（obs-soakwall 死循环修复的第二层——连「再抛再记」的入口都拆掉）。
                        armedDrain = true
                    } else {
                    // 停车侧的墙钟兜底：队列等待不经 sink 预算，由喂入线程代查（超限抛
                    // WallLimitExceeded → 记发现 → 解除停车后由泵折成 WALL_LIMIT 终态）。
                    budget?.check()
                    closeOutPendingLocked(OutcomeKind.ADVANCED)
                    val wp = WaitPoint(
                        request, anyKey = false, buttons = collector.drain(),
                        consoleTail = consoleTailLocked(cfg.tailLines), stackTop = queueFaceStackTopLocked(),
                    )
                    val action = decideLocked(wp, queueFace = true)
                    onObservedLocked(wp, action)
                    if (cfg.soak) {
                        // 浸泡档：交付前停顿要放到锁外做（不阻塞泵线程），此刻先暂存决定，
                        // 出锁后再 idle + markSubmit + feederSubmit（非浸泡沿用原「锁内直发」）。
                        soakFeeder = wp to action
                    } else {
                        feederSubmit(q, action)
                    }
                    }
                    }
                }
                if (skipTick) continue
                if (armedDrain) {
                    // 收口等待期的停车解除（值 0 与既有解除面同形）；不再进决策/预算/台账路径。
                    runCatching { q.submitInt(0L) }
                }
                soakFeeder?.let { (wp, action) ->
                    val ms = idleMs()
                    if (ms > 0L) Thread.sleep(ms)
                    markSubmit(wp, action)
                    feederSubmit(q, action)
                }
                // 同一次停车只决策一次：submit 后等「同一个 request」的等待被消费
                // （QueueInputSink.waitInput 的 finally 把 awaiting 清零，QueueInputSink.kt:336-340）——
                // 否则喂入线程会在消费完成前的短暂窗口里把同一等待点再决策一遍（二次喂入 /
                // 误报食谱耗尽）。紧跟着的新等待是另一个对象，自然落回普通轮询。
                var spins = 0
                while (q.awaiting === decidedAwait && spins < FEEDER_DRAIN_SPINS) {
                    Thread.sleep(1)
                    spins++
                }
            } catch (t: BootAbortException) {
                // 引导失败（bootFailLocked 已落盘现场）：解除停车，泵在下一步界看到 bootFailure 收口。
                runCatching { q.submitInt(0L) }
            } catch (t: ExploreAbandon) {
                // 弃局 / 非法动作 / 重放分歧发生在队列面：无法穿栈（停车点在状态机里），
                // 只能解除停车并令泵在下一步界折成 ABANDONED。
                synchronized(lock) { forcedAbandon = true }
                runCatching { q.submitInt(0L) }
            } catch (t: WallLimitExceeded) {
                // 死循环修复（obs-soakwall 任务一）：旧路径只记一条发现就解除停车、不收口本局——
                // 预算已耗尽 ⇒ 泵每回到该等待点都再抛再记（组长实测 --soak-min 1：findings.jsonl
                // 8 分钟 42 MB / 28,964 行全 WALL_LIMIT 全 episode=1，相邻 0～37 ms）。
                // 现口径：单局墙钟耗尽 = 这一局终态——现场（仍过 FindingGuard 闸）只挂一条进
                // feedWallPending，泵在下一步界折成 WALL_LIMIT 终态；后续同局再停车只放行不记录。
                synchronized(lock) {
                    if (feedWallPending == null && !episodeOver) {
                        reportFindingLocked("WALL_LIMIT", t)
                        feedWallPending = t
                    }
                }
                runCatching { q.submitInt(0L) }
            } catch (t: Throwable) {
                synchronized(lock) { reportFindingLocked("FEEDER_ERROR", t) }
                runCatching { q.submitInt(0L) }
                synchronized(lock) { forcedAbandon = true }
            }
        }
    }

    /** 队列面动作投递（FeedInt→submitInt；FeedStr→submit；PassAnyKey/Abandon→0 解除停车）。 */
    private fun feederSubmit(q: QueueInputSink, action: ExploreAction) {
        when (action) {
            is ExploreAction.FeedInt -> q.submitInt(action.value)
            is ExploreAction.FeedStr -> q.submit(action.value)
            is ExploreAction.PassAnyKey -> q.submitInt(0L)
            is ExploreAction.Abandon -> q.submitInt(0L)
        }
    }

    /**
     * 队列面（喂入线程）的栈顶读数（fix-longsoak 补「feeder 构造的 WaitPoint 恒空栈顶」缺口）。
     *
     * 口径分两层：
     * 1. **活帧优先**：与 sink 面同源 [VmStackReader]（funcStack/depth/lastPc 三件套，工具侧反射
     *    的理由见本文件头 KDoc）——若某个队列等待真的挂在中途 ERB 帧上，这里直接给「函数名:pc」。
     * 2. **帧已退卷 ⇒ 页面级定名**：现状的系统队列等待全部发生在 BeginFlow 的 Kotlin 步里、
     *    相邻两次 ERB 调用**之间**（ConsoleBeginHost.kt:86-92 的 await* 全系经
     *    waitSystemInteger（ConsoleBeginHost.kt:130），典型如 stepTrainShowUserCom
     *    （BeginFlow.kt:957-985）：显示函数已在**上一个步界**执行完毕返回，vm 的 run 收尾时
     *    unwindScopes 把 depth 收成 -1——Vm.kt:2291-2296 的「无帧 = null」承诺与
     *    Vm.kt:2307-2311 的 KDoc 就是这条），此刻**不存在**「当前最内层 ERB 帧」可读，
     *    硬报函数名会是脑补。退回「state:状态机态名」：态名本身就是页面名
     *    （Train_CallShowUserCom / Shop_CallShowShop 即此），慢页至少定名到页；
     *    core 侧若要开放「本页由哪些 ERB 函数渲染过」的读数（状态机最近执行函数序列），
     *    属产品面改动，写进交付报告的需组长接线，本车道不动 core-vm。
     *
     * 只读取数，不改任何执行语义。窗口说明：submit 后的「等消费」自旋期间存在读到**陈旧**
     * awaiting 的竞态（泵已复跑），[VmStackReader.read] 的 runCatching 保证读数尽力而为、
     * 撕裂不抛；持锁调用（feederBody 的决策块内）。
     */
    private fun queueFaceStackTopLocked(): String {
        val live = VmStackReader.read(session.runtime, assembled.metadata)
        return if (live.isNotEmpty()) live else "state:" + session.runtime.systemState.name
    }

    // ------------------------------------------------------------------
    // 等待点决策（sink 回调 + 喂入线程共用；调用方持锁）
    // ------------------------------------------------------------------

    override fun env(): WaitEnv = sessionEnv

    private val sessionEnv = object : WaitEnv {
        override fun consoleTail(max: Int): List<String> = synchronized(lock) { consoleTailLocked(max) }
        override fun drainButtons(): List<ButtonOpt> = synchronized(lock) { collector.drain() }
        override fun stackTop(): String = VmStackReader.read(session.runtime, assembled.metadata)
    }

    override fun decide(wp: WaitPoint): ExploreAction = synchronized(lock) { decideLocked(wp, queueFace = false) }

    override fun onObserved(wp: WaitPoint, action: ExploreAction) = synchronized(lock) { onObservedLocked(wp, action) }

    // ---- 浸泡档 SoakTuner 实现（obs-soak）：交付前停顿 + 记录提交时刻；非浸泡纯透传 ----

    /** 本步「玩家停顿」毫秒：[idleMinMs, idleMaxMs] 均匀随机（含长间隔）；非浸泡或未接线返 0。 */
    override fun idleMs(): Long = synchronized(lock) {
        if (!cfg.soak || meter == null) return 0L
        val lo = cfg.idleMinMs.coerceAtLeast(0L)
        val hi = cfg.idleMaxMs.coerceAtLeast(lo)
        val span = hi - lo
        if (span <= 0L) lo else lo + idleRng.nextLong(span + 1)
    }

    /** 停顿结束、交付前：钉本步现场（函数名:pc / 动作）+ 打提交时刻，等下一步到达时结算耗时。 */
    override fun markSubmit(wp: WaitPoint, action: ExploreAction) {
        synchronized(lock) {
            if (!cfg.soak || meter == null) return
            soakPendingStackTop = wp.stackTop
            soakPendingAction = actionLabel(action)
            soakLastSubmitNs = System.nanoTime()
        }
    }

    private fun decideLocked(wp: WaitPoint, queueFace: Boolean): ExploreAction {
        if (phase == Phase.BOOT) return bootNextActionLocked(wp, queueFace)
        closeOutPendingLocked(OutcomeKind.ADVANCED)
        if (episodeOver || forcedAbandon) return ExploreAction.Abandon("局已终态（收尾推进）")
        val task = current ?: throw IllegalStateException("无当前局任务")
        // 重放段：逐条照发，不询问策略（重放不是决定，也不回喂 onOutcome）。
        if (replayIdx < task.chain.size) {
            val a = task.chain[replayIdx]
            replayIdx++
            prevWasFree = false
            prevWp = wp
            prevAction = a
            return a
        }
        // 重放到达校验：分支签名不匹配 = 重放与首局分歧（console/aux 不还原的后果），弃局不静默。
        if (task.branchSignature != null) {
            if (signatureOf(wp) != task.branchSignature) {
                reportFindingLocked(
                    "REPLAY_DIVERGE",
                    "重放到达的等待点与分支签名不符：期望 " + task.branchSignature + " 实得 " + signatureOf(wp),
                )
                forcedAbandon = true
                return ExploreAction.Abandon("重放分歧（replay diverge）")
            }
            task.branchSignature = null // 只校验一次（校验点即分支点本体）
        }
        // 自由段：问策略（choose 抛任何异常 = POLICY_CRASH 发现 + 强制弃局，CONTRACT-DBG6 §3 约定）。
        val p = policy ?: throw IllegalStateException("策略未供给")
        val action = try {
            p.choose(wp)
        } catch (t: Throwable) {
            reportFindingLocked("POLICY_CRASH", t)
            forcedAbandon = true
            return ExploreAction.Abandon("策略抛异常：" + t.javaClass.simpleName + " " + (t.message ?: ""))
        }
        if (action is ExploreAction.Abandon) return action
        // 打转护栏：重放回分支点后策略再次给出已试过的同一动作 = 该支耗尽。
        if (task.tried != null && action == task.tried) {
            forcedAbandon = true
            return ExploreAction.Abandon("该分支点已试过同一动作（tried 护栏）")
        }
        // 动作种类与等待面的匹配校验（与 sink 翻译层同一套规则；在这里拦下可以让重放链
        // 保持干净——非法动作不进链，只记发现 + 弃局，见 onObservedLocked 的链语义）。
        val kindMismatch = when (action) {
            is ExploreAction.FeedInt -> wp.anyKey || wp.request.type != InputRequest.InputType.INT
            is ExploreAction.FeedStr -> wp.anyKey || wp.request.type != InputRequest.InputType.STR
            is ExploreAction.PassAnyKey -> !wp.anyKey
            is ExploreAction.Abandon -> false
        }
        if (kindMismatch) {
            reportFindingLocked(
                "ILLEGAL_ACTION",
                "动作 " + actionLabel(action) + " 与等待面不符（type=" + wp.request.type.name +
                    " anyKey=" + wp.anyKey + "）",
            )
            forcedAbandon = true
            return ExploreAction.Abandon("非法动作：交付形态与等待面不符")
        }
        prevWasFree = true
        prevWp = wp
        prevAction = action
        return action
    }

    /**
     * 动作执行前记账（CONTRACT-DBG6 §4.E1.2：候选多于一个的等待点在执行动作前快照入栈）。
     * 调用点在 sink 回调 / 喂入线程内、动作尚未交给引擎——此刻变量面静止（语句边界 /
     * 队列停车），saveData 的执行形态与原版 SAVEDATA 语句同形。
     */
    private fun onObservedLocked(wp: WaitPoint, action: ExploreAction) {
        if (phase != Phase.EXPLORE) return // 引导段的动作来自食谱，不入链不入栈
        val task = current ?: return
        val free = prevWasFree
        if (free && action !is ExploreAction.Abandon) {
            // 分支入栈：候选数多于一个、深度未满、且动作即将真实执行（Abandon 不执行、不入链）。
            // 浸泡档关掉分支枚举（一条随机操作流一直玩，不回退换支），故 cfg.soak 时不 push。
            if (
                !cfg.soak &&
                candidateCount(wp) > 1 &&
                task.depth + 1 <= cfg.maxDepth
            ) {
                val snap = SnapShots.take(session, assembled.sandboxDir, session.runtime.systemState.name)
                taskStack.addLast(
                    EpisodeTask(
                        snap = snap,
                        anchorState = task.anchorState,
                        chain = episodeActions.toList(),
                        tried = action,
                        branchSignature = signatureOf(wp),
                        depth = task.depth + 1,
                    ),
                )
            }
            episodeActions.add(action)
        }
    }

    /** 上一动作的结局回喂（onOutcome 只回喂策略的自由决定，重放段不喂）。 */
    private fun closeOutPendingLocked(kind: OutcomeKind) {
        if (cfg.soak) recordSoakStepLocked(kind)
        val wp = prevWp
        val act = prevAction
        prevWp = null
        prevAction = null
        val wasFree = prevWasFree
        prevWasFree = false
        if (wp == null || act == null) return
        // E2 逐步台账：重放段与自由段都记（重放步也真实喂了输入，E2 的链必须含它才可重放）；
        // 重放段的策略回喂照旧跳过（outcomeCounts/onOutcome 口径不变，见头 KDoc「E2 台账接线」）。
        out?.logStep(episodeNo.toLong(), wp, act, kind)
        if (!wasFree) return
        outcomeCounts[kind] = (outcomeCounts[kind] ?: 0) + 1
        runCatching { policy?.onOutcome(wp, act, kind) }
    }

    /**
     * 浸泡档：结算「上一提交步」的「提交 → 到点」耗时（此刻正是下一步到达 / 局终态揭晓）。
     * 只认已 markSubmit 过的步（soakLastSubmitNs!=0），首步/无在飞步跳过；耗时不含停顿
     * （停顿在 markSubmit 之前完成，markSubmit 打的是停顿后、交付前的时刻）。
     */
    private fun recordSoakStepLocked(kind: OutcomeKind) {
        val m = meter ?: return
        if (soakLastSubmitNs == 0L) return
        val elapsedMs = (System.nanoTime() - soakLastSubmitNs) / 1_000_000L
        m.record(
            SoakMeter.Sample(
                elapsedMs = elapsedMs,
                episode = episodeNo.toLong(),
                stackTop = soakPendingStackTop,
                action = soakPendingAction,
                stepIndex = soakStepIdx,
                outcome = kind.name,
                // obs-soak512：结算时刻（= 本步刚跑完、下一步到点/局终态揭晓）的 live 堆水位。
                heapUsedMi = heapUsedMiNow(),
            ),
        )
        soakStepIdx++
        soakLastSubmitNs = 0L
    }

    /**
     * 堆 OOM 现场（obs-soak512）：泵线程被 OutOfMemoryError 打断时、在飞步还挂在
     * soak 槽位上——此刻取「提交→OOM」的段耗时与当时的 live 堆，落在 [SoakMeter.oom]
     * 与 findings.jsonl（类别 HEAP_OOM）两侧；随后的 [recordTerminalLocked] 收口仍会把
     * 这一步作为样本记进耗时台账（outcome=CRASH），两表互不混。持锁调用。
     */
    private fun soakOomSceneLocked(t: OutOfMemoryError): HangScene = HangScene(
        elapsedMs = if (soakLastSubmitNs != 0L) (System.nanoTime() - soakLastSubmitNs) / 1_000_000L else -1L,
        episode = episodeNo.toLong(),
        stepIndex = soakStepIdx,
        action = soakPendingAction,
        scene = linkedMapOf(
            "message" to (t.message ?: t.javaClass.name),
            "heapUsedMi" to heapUsedMiNow().toString(),
            "heapCeilingMi" to heapCeilingMiNow().toString(),
            "stackTop" to soakPendingStackTop,
        ),
    )

    /** 局终态：最后一动作的结局 = 局终态类别；记账、日志、旗标一次收口（异常细节由 reportFindingLocked 落）。 */
    private fun recordTerminalLocked(kind: OutcomeKind, t: Throwable?, note: String) {
        closeOutPendingLocked(kind)
        episodeOver = true
        val tail = consoleTailLocked(cfg.tailLines)
        val chain = episodeActions.map { actionLabel(it) }
        episodeLogs.add(EpisodeLog(episodeNo, kind, note, chain, tail))
        err.println(
            "explore: episode " + episodeNo + " -> " + kind.name + "（" + note + "；链长 " + chain.size + "）",
        )
    }

    /**
     * 单步墙钟闸（`--step-wall-ms`）命中时的**函数级现场**投递（obs-midhang）。
     *
     * [com.eratw.port.core.code.StepLimitExceeded] 由 Vm 在抛点就地构造（Vm.kt:616 的
     * `timeLimitAt`：`funcName`/`line`/`pc`/`callChain`/`steps`/`elapsedMs` 全带上），
     * 本函数只做搬运：findings.jsonl 一条 `STEP_WALL_LIMIT`（外→内调用链 + 最内层函数 +
     * `文件:行号` + 该段实际指令数 + 段墙钟毫秒）+ 浸泡台账的挂起专节。
     * 局终态类别仍走 [OutcomeKind.STEP_LIMIT]（枚举与 findingKindOf 映射不动，见 pumpBody 注释）。
     */
    private fun reportStepSceneLocked(kind: String, t: com.eratw.port.core.code.StepLimitExceeded) {
        if (!findingAllowedLocked(kind)) return
        findingsCount++
        val tail = consoleTailLocked(cfg.tailLines)
        val chain = episodeActions.map { actionLabel(it) }
        val scene = stepScene(t)
        val detail = linkedMapOf(
            "episode" to episodeNo.toString(),
            "exception" to (t.javaClass.name + ": " + (t.message ?: "")),
            "anchor" to (anchor?.name ?: ""),
            "systemState" to session.runtime.systemState.name,
            "chain" to chain.joinToString(" -> "),
        )
        detail.putAll(scene)
        runCatching { findings.onFinding(kind, detail, tail, chain) }
        meter?.hang(HangScene(elapsedMs = t.elapsedMs, episode = episodeNo.toLong(),
            stepIndex = soakStepIdx, action = soakPendingAction, scene = scene))
        err.println("explore: 发现 " + kind + " —— " + (t.message ?: ""))
    }

    /** 现场字段（键名固定，findings.jsonl 与 soak.txt 共用；值取异常自带、不事后反射猜）。 */
    private fun stepScene(t: com.eratw.port.core.code.StepLimitExceeded): LinkedHashMap<String, String> =
        stepLimitScene(t, cfg.stepWallMs)

    /**
     * 护栏闸（obs-soakwall）：台账投递前统一过 [FindingGuard.allow]；被抑制时不写台账、
     * 不占 [findingsCount]，只在某个 kind **首次**被压静音时打一行 stderr（让「台账不涨」
     * 可被读成「被抑制」而不是「现场已清」）。持锁调用。
     */
    private fun findingAllowedLocked(kind: String): Boolean {
        val pass = findingGuard.allow(kind)
        if (!pass.delivered && pass.justMuted) {
            err.println(
                "explore: 发现类别 " + kind + " 达单种放行上限（kindCap=" + cfg.findingKindCap +
                    " / totalCap=" + cfg.findingTotalCap + "），后续同类抑制不入台账" +
                    "（累计抑制 " + findingGuard.suppressed + " 条）",
            )
        }
        return pass.delivered
    }

    /** 发现落盘（细节 + 完整输入链 + 现场控制台尾； FindingSink 由外层接 E2 适配器或最小 sink）。 */
    private fun reportFindingLocked(kind: String, t: Throwable) {
        if (!findingAllowedLocked(kind)) return
        findingsCount++
        val tail = consoleTailLocked(cfg.tailLines)
        val chain = episodeActions.map { actionLabel(it) }
        val detail = linkedMapOf(
            "episode" to episodeNo.toString(),
            "exception" to (t.javaClass.name + ": " + (t.message ?: "")),
            "anchor" to (anchor?.name ?: ""),
            "systemState" to session.runtime.systemState.name,
            "chain" to chain.joinToString(" -> "),
        )
        runCatching { findings.onFinding(kind, detail, tail, chain) }
        err.println("explore: 发现 " + kind + " —— " + (t.message ?: t.javaClass.simpleName))
    }

    private fun reportFindingLocked(kind: String, message: String) {
        if (!findingAllowedLocked(kind)) return
        findingsCount++
        val detail = linkedMapOf(
            "episode" to episodeNo.toString(),
            "message" to message,
            "anchor" to (anchor?.name ?: ""),
            "systemState" to session.runtime.systemState.name,
            "chain" to episodeActions.joinToString(" -> ") { actionLabel(it) },
        )
        runCatching { findings.onFinding(kind, detail, consoleTailLocked(cfg.tailLines), episodeActions.map { actionLabel(it) }) }
        err.println("explore: 发现 " + kind + " —— " + message)
    }

    // ------------------------------------------------------------------
    // 小件
    // ------------------------------------------------------------------

    private fun consoleTailLocked(max: Int): List<String> =
        session.console.lines().takeLast(max).map { session.console.textOf(it) }.dropWhile { it.isBlank() }

    private fun tailContainsLocked(sub: String): Boolean = consoleTailLocked(cfg.tailLines).any { it.contains(sub) }

    /** 分支点签名：请求形状 + 按钮值序（console 不还原，故签名不能带控制台内容——见头 KDoc）。 */
    private fun signatureOf(wp: WaitPoint): String =
        wp.request.type.name + "|" + wp.request.form.name + "|" + wp.anyKey + "|" +
            wp.buttons.joinToString(",") { it.value.toString() }

    /**
     * 驱动器侧候选可判集（与 E2 ExhaustivePolicy 的候选源同族，CONTRACT-DBG6 §4.E1.2
     * 「策略经 WaitPoint 可判」）：按钮值 ∪ 缺省值（hasDef/defInt）∪ 数字 0..9 去重；
     * anyKey 等待点恒 1；STR 自由题无界，按 1 处理（不做分支）。
     */
    internal fun candidateCount(wp: WaitPoint): Int {
        if (wp.anyKey) return 1
        if (wp.request.type == InputRequest.InputType.STR && wp.buttons.isEmpty() && !wp.request.hasDef) return 1
        val vals = LinkedHashSet<Long>()
        wp.buttons.forEach { vals.add(it.value) }
        if (wp.request.hasDef) vals.add(wp.request.defInt)
        if (wp.request.type == InputRequest.InputType.INT) for (d in 0..9) vals.add(d.toLong())
        return vals.size
    }

    /** 动作的字符串化（发现链 / 重放文件共用）：数字原样、字符串原样、anykey 记 Enter。 */
    internal fun actionLabel(a: ExploreAction): String = when (a) {
        is ExploreAction.FeedInt -> a.value.toString() + (if (a.asTimeout) "*timeout" else "")
        is ExploreAction.FeedStr -> a.value
        is ExploreAction.PassAnyKey -> "Enter"
        is ExploreAction.Abandon -> "abandon(" + a.reason + ")"
    }

    /** 状态机锚点复位：终局粘滞位清零（公开 API）+ 反射写 state（缝的理由见本文件头 KDoc）。 */
    private fun reAnchor(code: SystemStateCode) {
        val flow = runCatching {
            val f = ProcessRuntime::class.java.getDeclaredField("beginFlow")
            f.isAccessible = true
            f.get(session.runtime)
        }.getOrNull() ?: throw CliError("状态机锚点复位失败：ProcessRuntime.beginFlow 反射不可得")
        runCatching { flow.javaClass.getMethod("resetToBootState").invoke(flow) }
            .onFailure { throw CliError("状态机锚点复位失败：resetToBootState 调用失败（${it.message}）", it) }
        runCatching {
            val f = flow.javaClass.getDeclaredField("state")
            f.isAccessible = true
            f.set(flow, code)
        }.onFailure { throw CliError("状态机锚点复位失败：BeginFlow.state 反射写失败（${it.message}）", it) }
    }

    private companion object {
        const val FEEDER_POLL_MS = 20L

        /** 喂入后等消费的上限轮次（1ms/轮；超出按普通轮询继续，等待点新起时自然重判）。 */
        const val FEEDER_DRAIN_SPINS = 60_000
    }
}
