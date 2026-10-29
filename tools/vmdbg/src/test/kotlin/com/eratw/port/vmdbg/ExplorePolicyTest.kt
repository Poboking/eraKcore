package com.eratw.port.vmdbg

import com.eratw.port.core.host.InputRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CONTRACT-DBG6 §4.E2.3 ①②：指纹稳定性 + ExhaustivePolicy 候选序与 loop-cap。
 * 全部合成 WaitPoint（不碰语料、不装配会话）；合成样本不依赖 ERATW_* 环境变量。
 */
class ExplorePolicyTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 合成 INT/STR 等待点（字段缺省即「无按钮、无缺省、无限时、栈顶 FN:1、两行尾」）。 */
    private fun wp(
        type: InputRequest.InputType = InputRequest.InputType.INT,
        form: InputRequest.RequestForm = InputRequest.RequestForm.VALUE,
        anyKey: Boolean = false,
        buttons: List<ButtonOpt> = emptyList(),
        hasDef: Boolean = false,
        defInt: Long = 0L,
        defStr: String = "",
        timeLimit: Long = -1L,
        stackTop: String = "FN:1",
        tail: List<String> = listOf("标题菜单", "[0]开始 [1]继续"),
    ): WaitPoint = WaitPoint(
        request = InputRequest(
            type = type, form = form,
            hasDef = hasDef, defInt = defInt, defStr = defStr, timeLimit = timeLimit,
        ),
        anyKey = anyKey,
        buttons = buttons,
        consoleTail = tail,
        stackTop = stackTop,
    )

    private fun btn(value: Long, text: String = "b" + value) = ButtonOpt(text, value)

    private val feedIntLabels: (ExploreAction) -> String = { exploreActionLabel(it) }

    // ------------------------------------------------------------------
    // ① 指纹稳定性
    // ------------------------------------------------------------------

    @Test
    fun fingerprintStableForIdenticalWaitPointSequences() {
        val a = wp(buttons = listOf(btn(1), btn(2)), hasDef = true, defInt = 5)
        val b = wp(buttons = listOf(btn(1), btn(2)), hasDef = true, defInt = 5)
        assertEquals(waitPointFingerprint(a), waitPointFingerprint(b), "同构 WaitPoint 必同指纹")
        assertEquals(waitPointFingerprint(a), waitPointFingerprint(a), "纯函数：复算恒等")
        assertTrue(waitPointFingerprint(a).isNotEmpty(), "指纹非空")
    }

    @Test
    fun fingerprintChangesWithConsoleTailFirstLine() {
        val base = wp()
        val changedFirst = wp(tail = listOf("另一屏", "[0]开始 [1]继续"))
        assertTrue(
            waitPointFingerprint(base) != waitPointFingerprint(changedFirst),
            "console 尾首行变则指纹变",
        )
        // 合同钉死「首行」口径：尾中部漂移不改指纹（锁定语义，防止实现悄悄扩成全尾哈希）
        val changedSecond = wp(tail = listOf("标题菜单", "漂移行"))
        assertEquals(waitPointFingerprint(base), waitPointFingerprint(changedSecond), "尾中部漂移不改指纹（首行口径）")
        assertTrue(
            waitPointFingerprint(base.copy(consoleTail = emptyList())).endsWith("|"),
            "空尾 → 首行空串，指纹仍良构",
        )
    }

    @Test
    fun fingerprintChangesWithStackTopTypeFormAndButtonSet() {
        val base = wp(buttons = listOf(btn(1), btn(2)))
        assertTrue(
            waitPointFingerprint(base) != waitPointFingerprint(base.copy(stackTop = "FN:2")),
            "栈顶变则指纹变",
        )
        assertTrue(
            waitPointFingerprint(base) != waitPointFingerprint(base.copy(request = base.request.copy(type = InputRequest.InputType.STR))),
            "type 变则指纹变",
        )
        assertTrue(
            waitPointFingerprint(base) != waitPointFingerprint(base.copy(request = base.request.copy(form = InputRequest.RequestForm.ANY_VALUE))),
            "form 变则指纹变",
        )
        // 值集口径：按钮同值集不同顺序/重复项 → 同指纹
        val reordered = wp(buttons = listOf(btn(2), btn(1), btn(1, "dup")))
        assertEquals(waitPointFingerprint(base), waitPointFingerprint(reordered), "按钮值集相同（乱序+重复）应同指纹")
        assertTrue(
            waitPointFingerprint(base) != waitPointFingerprint(wp(buttons = listOf(btn(1), btn(3)))),
            "按钮值集不同应异指纹",
        )
    }

    // ------------------------------------------------------------------
    // ② 候选序（按钮升序 → 缺省 → 0..9 去重 → 超时垫底）
    // ------------------------------------------------------------------

    @Test
    fun candidateOrderButtonsThenDefaultThenDigits() {
        val w = wp(buttons = listOf(btn(3), btn(1), btn(3, "dup")), hasDef = true, defInt = 5)
        val plan = exploreCandidateActions(w).map(feedIntLabels)
        assertEquals(
            listOf(
                "FeedInt(1)", "FeedInt(3)", // 按钮值升序去重
                "FeedInt(5)", // 缺省值
                "FeedInt(0)", "FeedInt(2)", "FeedInt(4)", "FeedInt(6)", "FeedInt(7)", "FeedInt(8)", "FeedInt(9)", // 0..9 去重
            ),
            plan,
            "候选序 = 按钮升序 → 缺省 → 0..9 去重",
        )
    }

    @Test
    fun exhaustiveEnumeratesAllThenAbandonsExhausted() {
        val w = wp(buttons = listOf(btn(1)))
        val p = ExhaustivePolicy()
        val fed = ArrayList<ExploreAction>()
        // 0..9 全域 = {1} ∪ {0..9} = 10 个取值候选（无限时 → 无超时候选）
        repeat(10) {
            val a = p.choose(w)
            assertTrue(a is ExploreAction.FeedInt && !a.asTimeout, "第 ${it + 1} 步应为普通取值: $a")
            fed.add(a)
            p.onOutcome(w, a, OutcomeKind.ADVANCED)
        }
        // 全域 = 按钮 {1} ∪ 0..9 = 10 个取值候选；序 = [1] 后接 0..9 去重（1 已试跳过）
        val expectedOrder = listOf(1L) + (0L..9L).filter { it != 1L }
        assertEquals(expectedOrder, fed.map { (it as ExploreAction.FeedInt).value }, "枚举序 = 候选序")
        val end = p.choose(w)
        assertEquals(ExploreAction.Abandon("exhausted"), end, "试尽 → Abandon(exhausted)（合同钉死 reason）")
    }

    @Test
    fun timeoutCandidateIsLastAndGated() {
        // 有按钮有缺省有超时：取值候选不含 asTimeout，超时候选垫底且值 = defInt
        val timed = wp(buttons = listOf(btn(2), btn(0)), hasDef = true, defInt = 2, timeLimit = 20_000L)
        val plan = exploreCandidateActions(timed)
        assertEquals(11, plan.size, "10 个取值 + 1 个超时候选")
        assertEquals(ExploreAction.FeedInt(2, asTimeout = true), plan.last(), "超时候选垫底、值 = defInt")
        assertTrue(plan.dropLast(1).none { it is ExploreAction.FeedInt && it.asTimeout }, "取值段不得混入超时位")
        // 无限时 → 无超时候选
        assertEquals(10, exploreCandidateActions(timed.copy(request = timed.request.copy(timeLimit = -1L))).size)
        // 无缺省 → 无超时候选（原版 INT 无默认值超时不推进，InputSink.kt:13-19）
        assertEquals(
            10,
            exploreCandidateActions(timed.copy(request = timed.request.copy(hasDef = false))).size,
            "无 defInt 不得发超时候选",
        )
        // 超时候选排在全排列末尾：取值全部试尽后才轮到它，然后才弃局
        val p = ExhaustivePolicy()
        repeat(10) { p.onOutcome(timed, p.choose(timed), OutcomeKind.ADVANCED) }
        assertEquals(ExploreAction.FeedInt(2, asTimeout = true), p.choose(timed), "取值试尽 → 超时候选")
        assertEquals(ExploreAction.Abandon("exhausted"), p.choose(timed), "超时也试尽 → 弃局")
    }

    @Test
    fun strCandidatesEmptyStringThenDefault() {
        val withDef = wp(type = InputRequest.InputType.STR, hasDef = true, defStr = "默认")
        assertEquals(
            listOf<ExploreAction>(ExploreAction.FeedStr(""), ExploreAction.FeedStr("默认")),
            exploreCandidateActions(withDef),
            "STR 候选 = 空串 → 缺省串",
        )
        val p = ExhaustivePolicy()
        assertEquals(ExploreAction.FeedStr(""), p.choose(withDef))
        assertEquals(ExploreAction.FeedStr("默认"), p.choose(withDef))
        assertEquals(ExploreAction.Abandon("exhausted"), p.choose(withDef), "STR 候选试尽 → 弃局")
        val noDef = wp(type = InputRequest.InputType.STR)
        assertEquals(listOf<ExploreAction>(ExploreAction.FeedStr("")), exploreCandidateActions(noDef), "无缺省 STR 只有空串")
    }

    @Test
    fun anyKeyWaitPointYieldsPassAnyKeyOnly() {
        val w = wp(anyKey = true, buttons = listOf(btn(1), btn(2)))
        val p = ExhaustivePolicy()
        repeat(3) {
            assertEquals(ExploreAction.PassAnyKey, p.choose(w), "anyKey 等待点唯一合法动作")
        }
    }

    @Test
    fun noValueFormYieldsSinglePlaceholder() {
        // VOID：内容被引擎忽略（needValue=false），单占位候选、无分支
        val voidWp = wp(form = InputRequest.RequestForm.VOID, hasDef = true, defInt = 7)
        assertEquals(
            listOf<ExploreAction>(ExploreAction.FeedInt(7)),
            exploreCandidateActions(voidWp),
            "不取值形态 = 单占位（有缺省取缺省）",
        )
        val voidNoDef = wp(form = InputRequest.RequestForm.VOID)
        assertEquals(listOf<ExploreAction>(ExploreAction.FeedInt(0)), exploreCandidateActions(voidNoDef))
    }

    // ------------------------------------------------------------------
    // ② loop-cap（连续 ABANDON/CRASH 达 8 → 永久跳过该指纹）
    // ------------------------------------------------------------------

    private fun exhaustStrPolicy(p: BaseExplorePolicy, w: WaitPoint) {
        p.choose(w) // FeedStr("")
        p.choose(w) // FeedStr("X") —— 之后恒 Abandon(exhausted)
    }

    @Test
    fun loopCapBlocksFingerprintAfterEightConsecutiveAbandons() {
        val w = wp(type = InputRequest.InputType.STR, hasDef = true, defStr = "X")
        val p = ExhaustivePolicy()
        exhaustStrPolicy(p, w)
        repeat(8) {
            val a = p.choose(w)
            assertEquals(ExploreAction.Abandon("exhausted"), a, "第 $it 次弃局仍应为 exhausted")
            p.onOutcome(w, a, OutcomeKind.ABANDONED)
        }
        assertEquals(ExploreAction.Abandon("loop-capped"), p.choose(w), "连续 8 次 ABANDON 后永久跳过")
        assertEquals(ExploreAction.Abandon("loop-capped"), p.choose(w), "blocked 之后恒 loop-capped")
    }

    @Test
    fun nonAbandonOutcomeResetsStreak() {
        val w = wp(type = InputRequest.InputType.STR, hasDef = true, defStr = "X")
        val p = ExhaustivePolicy()
        exhaustStrPolicy(p, w)
        repeat(7) { p.onOutcome(w, p.choose(w), OutcomeKind.ABANDONED) } // 连续 7
        p.onOutcome(w, ExploreAction.PassAnyKey, OutcomeKind.ADVANCED) // 清零
        repeat(7) { p.onOutcome(w, p.choose(w), OutcomeKind.ABANDONED) } // 又 7（不连续）
        assertEquals(ExploreAction.Abandon("exhausted"), p.choose(w), "未连续达 8 不得 blocked")
        p.onOutcome(w, p.choose(w), OutcomeKind.ABANDONED) // 第 8 次
        assertEquals(ExploreAction.Abandon("loop-capped"), p.choose(w), "补足连续 8 → blocked")
    }

    @Test
    fun crashOutcomeAlsoCountsTowardLoopCap() {
        val w = wp(type = InputRequest.InputType.STR, hasDef = true, defStr = "X")
        val p = ExhaustivePolicy()
        exhaustStrPolicy(p, w)
        repeat(7) { p.onOutcome(w, p.choose(w), OutcomeKind.ABANDONED) }
        p.onOutcome(w, ExploreAction.FeedStr(""), OutcomeKind.CRASH) // CRASH 同样计数（合同：ABANDON/CRASH）
        assertEquals(ExploreAction.Abandon("loop-capped"), p.choose(w), "ABANDON 与 CRASH 混算达 8 → blocked")
    }

    @Test
    fun blockIsPerFingerprint() {
        val blocked = wp(type = InputRequest.InputType.STR, hasDef = true, defStr = "X")
        val other = wp(type = InputRequest.InputType.STR, hasDef = true, defStr = "Y", stackTop = "FN:2")
        val p = ExhaustivePolicy()
        exhaustStrPolicy(p, blocked)
        repeat(8) { p.onOutcome(blocked, p.choose(blocked), OutcomeKind.ABANDONED) }
        assertEquals(ExploreAction.Abandon("loop-capped"), p.choose(blocked))
        assertEquals(ExploreAction.FeedStr(""), p.choose(other), "blocked 是指纹级：别的指纹照常探索")
    }

    @Test
    fun offerConsumesCandidateEvenWithoutOnOutcome() {
        // 驱动器漏调 onOutcome 也不得死循环：offer 即消费 → 必然弃局
        val w = wp(buttons = listOf(btn(1)))
        val p = ExhaustivePolicy()
        val first = p.choose(w)
        val second = p.choose(w)
        assertTrue(first != second, "无 onOutcome 也应推进到下一候选")
        var abandoned = false
        for (i in 0 until 30) {
            if (p.choose(w) is ExploreAction.Abandon) {
                abandoned = true
                break
            }
        }
        assertTrue(abandoned, "30 步内必须弃局（终止保证）")
    }

    // ------------------------------------------------------------------
    // 确定性与工厂
    // ------------------------------------------------------------------

    @Test
    fun sameSeedSameSequenceForBothPolicies() {
        val script: (ExplorePolicy) -> List<ExploreAction> = { p ->
            val seq = ArrayList<ExploreAction>()
            val ws = listOf(
                wp(buttons = listOf(btn(1), btn(2))),
                wp(anyKey = true),
                wp(type = InputRequest.InputType.STR, hasDef = true, defStr = "X"),
                wp(hasDef = true, defInt = 2, timeLimit = 20_000L),
            )
            for (round in 0 until 12) {
                val w = ws[round % ws.size]
                val a = p.choose(w)
                seq.add(a)
                p.onOutcome(w, a, if (a is ExploreAction.Abandon) OutcomeKind.ABANDONED else OutcomeKind.ADVANCED)
            }
            seq
        }
        val e1 = script(ExhaustivePolicy(seed = 7))
        val e2 = script(ExhaustivePolicy(seed = 7))
        assertEquals(e1, e2, "Exhaustive：同 seed 同序列 ⇒ 同决定")
        val l1 = script(LeastVisitedPolicy(seed = 7))
        val l2 = script(LeastVisitedPolicy(seed = 7))
        assertEquals(l1, l2, "LeastVisited：同 seed 同序列 ⇒ 同决定（RNG 序确定）")
    }

    @Test
    fun leastVisitedStillCoversEveryCandidateBeforeAbandon() {
        val w = wp() // 无按钮无缺省 → 候选 = 0..9
        val p = LeastVisitedPolicy(seed = 42)
        val fed = HashSet<Long>()
        repeat(10) {
            val a = p.choose(w)
            assertTrue(a is ExploreAction.FeedInt && !a.asTimeout, "应为取值候选: $a")
            fed.add((a as ExploreAction.FeedInt).value)
            p.onOutcome(w, a, OutcomeKind.ADVANCED)
        }
        assertEquals((0L..9L).toSet(), fed, "随机化枚举也必须全覆盖")
        assertEquals(ExploreAction.Abandon("exhausted"), p.choose(w), "试尽 → 弃局")
    }

    @Test
    fun createExplorePolicyFactory() {
        assertTrue(createExplorePolicy("exhaustive") is ExhaustivePolicy)
        assertTrue(createExplorePolicy("least-visited") is LeastVisitedPolicy)
        assertFailsWith<CliError> { createExplorePolicy("nope") }
        // loop-cap 透传：loopCap=1 时一次 ABANDON 即 blocked
        val w = wp(type = InputRequest.InputType.STR, hasDef = true, defStr = "X")
        val p = createExplorePolicy("exhaustive", seed = 1, loopCap = 1) as ExhaustivePolicy
        exhaustStrPolicy(p, w)
        val a = p.choose(w)
        p.onOutcome(w, a, OutcomeKind.ABANDONED)
        assertEquals(ExploreAction.Abandon("loop-capped"), p.choose(w), "loopCap=1 → 一次弃局即跳过")
    }
}
