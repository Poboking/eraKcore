package com.eratw.port.core.code

import com.eratw.port.core.lexer.LangVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ENGFIX-3 · 死循环告警的**三段官方文案**逐字锁（标题 / 正文 / 玩家选退出那句错）。
 *
 * ## 出处（两侧都复核过，逐字同文）
 * | 文案 | skia `Lang.cs` | 官方 `Lang.cs` | `lang.emuera-zhs.xml` | `lang.emuera-eng.xml` | 消费点 |
 * |---|---|---|---|---|---|
 * | `MessageBox.InfiniteLoop`（框标题） | 3147 | 4102 | 970 | 973 | skia `Process.cs:480` / 官方 `:503` |
 * | `MessageBox.TooLongLoop`（框正文） | 3150 | 4106 | 971-973 | 974-976 | skia `Process.cs:481` / 官方 `:504` |
 * | `Error.SelectExitInfiniteLoopMB`（退出错） | 2270 | 2934 | 688 | 691 | skia `Process.cs:484` / 官方 `:507` |
 *
 * ## 锁什么
 * 1. **占位符语义**：正文四个实参的**顺序**照抄 `Process.cs:481`
 *    —— 第 0 位文件名、第 1 位行号、第 2 位段落内已执行行数、第 3 位墙钟毫秒。
 *    日文模板里 `{3}` 出现在 `{2}` **之前**（「最後の入力から{3}ミリ秒経過し{2}行が実行されました」），
 *    按位置填充与按顺序填充不等价 ⇒ 这一条是唯一能锁住「别把两个数换错位置」的闸。
 * 2. **不修译文、不改引号形态、不 trim**（ADR-005）：模板串与两侧表逐字一致。
 * 3. **玩家选退出那句上没有步数/调用链后缀**：官方那条就是纯 `CodeEE(...Text)`，
 *    现场由 [InfiniteLoopAlert] / [StepLimitExceeded.line] 这些结构化字段承担。
 *
 * 文案目前住在 [InfiniteLoopTexts]（本波文件面只有 `core/code/Vm.kt` 与 `core/code/Errors.kt`，
 * `core/lexer/LangText.kt` 与 `specs/error-texts.yaml` 不在本路权限内）。
 * 组长把它们并进 `LangKey` 后，本文件改成断言 `LangKey.某键.render(...)` 的返回值即可，
 * 断言的期望串一个字都不动。
 */
class InfiniteLoopTextsTest {

    private fun alert(
        sourceName: String?,
        functionName: String? = "EVENTTRAIN",
        lineNo: Long = 1234L,
        linesExecuted: Long = 10_000L,
        elapsedMs: Long = 5_001L,
        variant: LangVariant = LangVariant.ZHS,
    ) = InfiniteLoopAlert(
        sourceName = sourceName,
        functionName = functionName,
        lineNo = lineNo,
        linesExecuted = linesExecuted,
        elapsedMs = elapsedMs,
        alertTimeMs = 5_000L,
        textVariant = variant,
    )

    // ------------------------------------------------------------------
    // 1. 标题：两侧原文逐字
    // ------------------------------------------------------------------

    @Test
    fun `框标题逐字照抄两侧表`() {
        assertEquals("無限ループの可能性があります", InfiniteLoopTexts.TITLE_JA)
        assertEquals("可能发生了无限循环", InfiniteLoopTexts.TITLE_ZHS)
        assertEquals(InfiniteLoopTexts.TITLE_JA, InfiniteLoopTexts.title(LangVariant.JA))
        assertEquals(InfiniteLoopTexts.TITLE_ZHS, InfiniteLoopTexts.title(LangVariant.ZHS))
    }

    @Test
    fun `告警自带的语言档决定标题用哪一档`() {
        val ja = alert("test/loop.erb", variant = LangVariant.JA)
        val zhs = alert("test/loop.erb")
        assertEquals(InfiniteLoopTexts.TITLE_JA, ja.title())
        assertEquals(InfiniteLoopTexts.TITLE_ZHS, zhs.title())
        // 显式改档仍然可用（宿主想按玩家当前语言重画一次时）。
        assertEquals(InfiniteLoopTexts.TITLE_ZHS, ja.title(LangVariant.ZHS))
    }

    // ------------------------------------------------------------------
    // 2. 正文：四个占位符的**位置**是这份文案的命门
    // ------------------------------------------------------------------

    @Test
    fun `日文正文模板的占位符顺序照抄 Process 的实参序`() {
        assertEquals(
            "現在、{0}の{1}行目を実行中です。\n" +
                "最後の入力から{3}ミリ秒経過し{2}行が実行されました。\n" +
                "処理を中断し強制終了しますか？",
            InfiniteLoopTexts.MESSAGE_TEMPLATE_JA,
            "三个换行、四个占位、{3} 在 {2} 之前 —— 与 skia Lang.cs:3150 / 官方 Lang.cs:4106 逐字",
        )
    }

    @Test
    fun `简中正文模板与日文逐行对齐`() {
        assertEquals(
            "目前正在执行{0}的第{1}行。\n" +
                "距离最后的输入经过了{3}毫秒并执行了{2}行。\n" +
                "中断处理并强制结束吗？",
            InfiniteLoopTexts.MESSAGE_TEMPLATE_ZHS,
            "zhs 表（lang.emuera-zhs.xml:971-973）三段合并，换行位置与日文原文的两个换行对齐",
        )
    }

    @Test
    fun `日文正文按位填充四个实参`() {
        val a = alert(sourceName = "ERB/CHAT/ERB10_CAMP.erb", lineNo = 88L, linesExecuted = 10_000L, elapsedMs = 6_240L)
        assertEquals(
            "現在、ERB/CHAT/ERB10_CAMP.erbの88行目を実行中です。\n" +
                "最後の入力から6240ミリ秒経過し10000行が実行されました。\n" +
                "処理を中断し強制終了しますか？",
            a.message(LangVariant.JA),
        )
    }

    @Test
    fun `简中正文按位填充四个实参`() {
        val a = alert(sourceName = "ERB/CHAT/ERB10_CAMP.erb", lineNo = 88L, linesExecuted = 10_000L, elapsedMs = 6_240L)
        assertEquals(
            "目前正在执行ERB/CHAT/ERB10_CAMP.erb的第88行。\n" +
                "距离最后的输入经过了6240毫秒并执行了10000行。\n" +
                "中断处理并强制结束吗？",
            a.message(LangVariant.ZHS),
        )
    }

    /**
     * 文件名取不到（`.erbc` v1 / 内存编译的裸 Program）⇒ 回落**函数书写名**，
     * 且回落的是 `displaySource` 这一个口径，正文与现场说的必须是同一个名字。
     */
    @Test
    fun `没有源文件名时正文第零位回落函数书写名`() {
        val a = alert(sourceName = null, functionName = "EVENTTRAIN")
        assertEquals("EVENTTRAIN", a.displaySource)
        assertTrue(a.message(LangVariant.ZHS).startsWith("目前正在执行EVENTTRAIN的第1234行。"), a.message())
        assertTrue(a.message(LangVariant.JA).startsWith("現在、EVENTTRAINの1234行目を実行中です。"), a.message())
    }

    /** 两个名字都没有（连函数名都取不到）⇒ 空串，与 C# 把 null 实参上屏为空一致，不打 `null` 上去。 */
    @Test
    fun `文件名与函数名都缺时第零位是空串而不是 null 字样`() {
        val a = alert(sourceName = null, functionName = null)
        assertEquals("", a.displaySource)
        assertTrue(a.message(LangVariant.ZHS).startsWith("目前正在执行的第1234行。"), a.message())
        assertTrue(!a.message().contains("null"), "不许把 Kotlin 的 null 字面量画上屏：${a.message()}")
    }

    /** 默认档 = 使用者面简中（口径同 `core/lexer/LangText.kt` 类头）。 */
    @Test
    fun `缺省语言档是简中`() {
        assertEquals(LangVariant.ZHS, alert("x.erb").textVariant)
        assertEquals(LangVariant.ZHS, InfiniteLoopAlert("x.erb", "f", 1L, 1L, 1L, 1L).textVariant)
    }

    // ------------------------------------------------------------------
    // 3. 「玩家选退出」那句：错文本身，与带错对象的形状
    // ------------------------------------------------------------------

    @Test
    fun `退出错逐字照抄两侧表`() {
        assertEquals("無限ループの疑いにより強制終了が選択されました", InfiniteLoopTexts.EXIT_JA)
        assertEquals("可能进入了无限循环因此选择强制终止", InfiniteLoopTexts.EXIT_ZHS)
        assertEquals(InfiniteLoopTexts.EXIT_JA, InfiniteLoopTexts.exitError(LangVariant.JA))
        assertEquals(InfiniteLoopTexts.EXIT_ZHS, InfiniteLoopTexts.exitError(LangVariant.ZHS))
        assertEquals(InfiniteLoopTexts.EXIT_ZHS, InfiniteLoopTexts.exitError(), "缺省走使用者面简中")
    }

    @Test
    fun `玩家退出的异常只有官方那句没有步数与调用链后缀`() {
        val e = StepLimitExceeded(
            steps = 123_456_789L,
            line = 87,
            pc = 4096,
            funcName = "EVENTTRAIN",
            callChain = "ERBSTART → EVENTTRAIN",
            elapsedMs = 6_240L,
            guardCause = LoopGuardCause.PlayerChoseExit,
            alert = alert(sourceName = "ERB/CHAT/ERB10_CAMP.erb", elapsedMs = 6_240L),
        )
        assertEquals(InfiniteLoopTexts.EXIT_ZHS, e.message, "官方那句原样上屏：一个后缀都不加")
        // 现场仍然结构化可取（宿主的取证/报告靠这三件，不是靠 message）。
        assertEquals(123_456_789L, e.steps)
        assertEquals(6_240L, e.elapsedMs)
        assertEquals(87, e.line)
        assertEquals(4096, e.pc)
        assertEquals("EVENTTRAIN", e.funcName)
        assertEquals("ERB/CHAT/ERB10_CAMP.erb", e.alert?.sourceName)
        assertEquals(5_000L, e.alert?.alertTimeMs)
    }

    /** 语言档跟框走：框是日文 ⇒ 屏上那句也是日文。 */
    @Test
    fun `退出异常的文案语言档取告警现场那一档`() {
        val ja = StepLimitExceeded(
            steps = 1L, line = 0, pc = 0, funcName = null,
            guardCause = LoopGuardCause.PlayerChoseExit,
            alert = alert(sourceName = "x.erb", variant = LangVariant.JA),
        )
        assertEquals(InfiniteLoopTexts.EXIT_JA, ja.message)
    }

    // ------------------------------------------------------------------
    // 4. 装置预算那条的旧文案不动（既有台账/失败屏断言依赖它）
    // ------------------------------------------------------------------

    @Test
    fun `装置预算的两条旧文案逐字不变`() {
        val stepsOnly = StepLimitExceeded(steps = 501L, line = 3, pc = 9, funcName = "main")
        assertEquals("执行步数超过上限(501)，疑似死循环", stepsOnly.message)
        assertEquals(LoopGuardCause.DeviceBudget, stepsOnly.guardCause, "缺省成因 = 移植层预算")
        assertEquals(-1L, stepsOnly.elapsedMs, "纯步数闸不谎报墙钟")

        val withChain = StepLimitExceeded(
            steps = 501L, line = 3, pc = 9, funcName = "main", callChain = "A → B",
        )
        assertEquals("执行步数超过上限(501)，疑似死循环 @ A → B", withChain.message)

        val timed = StepLimitExceeded(
            steps = 9_999L, line = 3, pc = 9, funcName = "main", elapsedMs = 60_001L,
        )
        assertEquals("执行时间超过上限(60001ms，已执行 9999 步)，疑似死循环", timed.message)
    }

    /**
     * 三支答复的形状：`Exit` 带语言档，`Continue` / `NoUi` 是无字段单例
     * （VM 的 `when` 按穷举分派，漏一支就编译不过）。缺省切口必须答 `NoUi`
     * —— 没接线的宿主因此与改造前逐条一致。
     */
    @Test
    fun `告警答复的三支形状与缺省切口`() {
        assertEquals(LangVariant.ZHS, InfiniteLoopAnswer.Exit().textVariant)
        assertEquals(LangVariant.JA, InfiniteLoopAnswer.Exit(textVariant = LangVariant.JA).textVariant)
        assertEquals(
            InfiniteLoopAnswer.NoUi,
            object : com.eratw.port.core.code.VmHost {
                override val evalContext: com.eratw.port.core.expr.EvalContext? = null
                override fun print(text: String) {}
            }.askInfiniteLoop(alert(sourceName = "x.erb")),
            "VmHost.askInfiniteLoop 的缺省实现必须 ≙ skia Process.cs:478 的 view.Enabled == false",
        )
    }
}
