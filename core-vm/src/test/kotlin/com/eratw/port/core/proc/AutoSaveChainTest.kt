package com.eratw.port.core.proc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 挖虫波 B1/B2 定向单测 —— 自动存档链（`Process.cs:2553-2601`）与
 * `endCallEventComEnd` 三分支（本地版 `Process.cs:2424-2456`；「前」分支出自裁定报告引
 * 新版 `Process.cs:1849-1891`）。
 *
 * 钉的基准行（逐用例注明）：
 * - `:2555` saveTarget 复位、`:2556` Config.AutoSave 门、`:2561-2562` Skipped 直调；
 * - `:2565-2579` beginAutoSave（`SYSTEM_AUTOSAVE` 优先 / `saveTarget=99` `:2572` /
 *   `SAVEDATA_TEXT` 时间戳 `:2573` / `SAVEINFO` `:2575`）；
 * - `:2581-2590` endAutoSaveCallSaveInfo（`saveTarget==99` 才 `SaveTo(99)` `:2583`；
 *   失败 ⇒ 两行报错 `:2585-2586` + `ReadAnyKey` `:2587`）；
 * - `:2592-2601` endAutoSave（isBegun → 挂起 BEGIN；否则 SHOW_SHOP force）；
 * - `:136` `const AutoSaveIndex = 99`；`:2032` UniqueAutosave 挂**裸** endAutoSave；
 * - B2：`:2426-2436` 临时行早退、`:2437-2448` CALLTRAINEND 早退（`:2445` needCheck=false、
 *   `:2446` 原地 return）。
 */
class AutoSaveChainTest {

    // ------------------------------------------------------------------
    // 假体
    // ------------------------------------------------------------------

    private class FakeCalls : BeginCallPort {
        val functions = HashSet<String>()
        val events = HashSet<String>()
        val called = ArrayList<String>()

        var ct: Boolean = false
        var consumed: Boolean = true
        var pending: BeginType? = null
        var savedataWritten: String? = null
        var savedataRead: String = ""

        override fun callFunction(name: String, force: Boolean, isEvent: Boolean): Boolean {
            val key = name.uppercase()
            if (!functions.contains(key) && !events.contains(key)) {
                if (force) throw com.eratw.port.core.err.CodeEE("找不到函数\"@$name\"")
                return false
            }
            called.add(name)
            return true
        }

        override fun result(): Long = 0L
        override fun setResult(value: Long) {}
        override fun systemResult(): Long = 0L
        override fun setSystemResult(value: Long) {}
        override fun nextCom(): Long = -1L
        override fun setNextCom(value: Long) {}
        override fun selectCom(): Long = -1L
        override fun setSelectCom(value: Long) {}
        override fun isCTrain(): Boolean = ct
        override fun clearCommands(): Boolean {
            val was = ct
            ct = false
            return was
        }

        override fun beginTrainReset() {}
        override fun afterShowUserComReset() {}
        override fun afterInputComReset() {}
        override fun afterSourceCheckReset() {}
        override fun trainName(code: Int): String? = null
        override fun trainNameCount(): Int = 0

        override fun savedataText(): String = savedataRead
        override fun setSavedataText(value: String) {
            savedataWritten = value
            savedataRead = value
        }

        override fun commandsConsumed(): Boolean = consumed

        override fun pendingBegin(): BeginType? = pending
        override fun takePendingBegin(): BeginType? = pending.also { pending = null }
        override fun clearPendingBegin() {
            pending = null
        }
    }

    private class FakeHost(
        var autoSaveEnabled: Boolean = false,
        var saveToResult: Boolean? = false,
        var comEndBeforeSave: Boolean = false,
        var temporaryLine: Boolean = false,
    ) : BeginHost {
        val saveToCalls = ArrayList<Pair<Int, String>>()
        val lines = ArrayList<String>()
        var readAnyKeyCount = 0

        override fun autoSave(): Boolean = autoSaveEnabled
        override fun saveTo(slot: Int, text: String): Boolean? {
            saveToCalls += slot to text
            return saveToResult
        }

        override fun readAnyKey() {
            readAnyKeyCount++
        }

        override fun autoSaveAtComEnd(): Boolean = comEndBeforeSave
        override fun lastLineIsTemporary(): Boolean = temporaryLine
        override fun writeLine(text: String) {
            lines += text
        }
    }

    /** 「EVENTSHOP 已返回、下一步进 endCallEventShop」的最小装置（档名注入固定串）。 */
    private fun atEventShopReturn(
        autoSave: Boolean,
        saveToResult: Boolean?,
        functionSet: Set<String> = setOf("SAVEINFO", "SHOW_SHOP"),
        saveText: String = "TS ",
    ): Triple<FakeCalls, FakeHost, BeginFlow> {
        val calls = FakeCalls().apply { functions.addAll(functionSet) }
        val host = FakeHost(autoSaveEnabled = autoSave, saveToResult = saveToResult)
        val f = BeginFlow(calls, host) { saveText }
        // 脚本入口 BEGIN SHOP（Normal 出发 ⇒ calledWhenNormal=true，ProcessState.cs:227-236）。
        f.select(BeginType.SHOP, viaBeginType = false)
        val begin = f.step() // beginShop（EVENTSHOP 缺失 ⇒ NOT_CALLED，:2546-2549）
        assertEquals(StepOutcome.NOT_CALLED, begin.outcome)
        assertEquals(SystemStateCode.Shop_CallEventShop, f.state)
        return Triple(calls, host, f)
    }

    // ------------------------------------------------------------------
    // B1：AUTO_SAVE 关闭（缺省档）—— 既有读数不变
    // ------------------------------------------------------------------

    /**
     * `MAKE AUTOSAVES:NO`（`Process.cs:2556` 门，玩家 `emuera.config:8` 正是 NO）：
     * 链落 `AutoSave_Skipped`（`:2561`），`:2583` 判据 `saveTarget==99` 不成立 ⇒
     * **一次都不实存**，收口仍是 SHOW_SHOP（`:2599-2600`）。钉 `:2555/:2556/:2561-2562/:2583`。
     */
    @Test
    fun `AUTO_SAVE 关闭时 Skipped 链不触 SaveTo 且照常 SHOW_SHOP`() {
        val (calls, host, f) = atEventShopReturn(autoSave = false, saveToResult = true)

        val step = f.step()
        assertEquals(StepOutcome.CALLED, step.outcome)
        assertEquals("SHOW_SHOP", step.calledFunction)
        assertEquals(SystemStateCode.Shop_CallShowShop, f.state)
        assertEquals(0, host.saveToCalls.size, "saveTarget==-1 ⇒ :2583 不成立，禁止实存")
        assertEquals(-1, f.saveTarget)
        assertEquals(listOf("SHOW_SHOP"), calls.called, "NO 档只走收口，不派发 SAVEINFO/SYSTEM_AUTOSAVE")
    }

    // ------------------------------------------------------------------
    // B1：AUTO_SAVE=YES 全链
    // ------------------------------------------------------------------

    /**
     * YES + `SAVEINFO` 存在：`beginAutoSave`（`Process.cs:2565-2578`）——
     * `SYSTEM_AUTOSAVE` 缺失（`:2567`）⇒ `saveTarget=99`（`:2572`，`AutoSaveIndex` `:136`）、
     * `SAVEDATA_TEXT` 写缺省文案（`:2573`，此处注入固定串）、置 `AutoSave_CallSaveInfo`
     * （`:2574`）、派发 `SAVEINFO`（`:2575`）。本步**不**实存（存档在 handler 收口点）。
     */
    @Test
    fun `AUTO_SAVE YES 时 beginAutoSave 置 99 写文案并派发 SAVEINFO`() {
        val (calls, host, f) = atEventShopReturn(autoSave = true, saveToResult = true)

        val step = f.step()
        assertEquals(StepOutcome.CALLED, step.outcome, "SAVEINFO 在位")
        assertEquals("SAVEINFO", step.calledFunction)
        assertEquals(SystemStateCode.AutoSave_CallSaveInfo, f.state)
        assertEquals(99, f.saveTarget, "Process.cs:2572 saveTarget = 99")
        assertEquals("TS ", calls.savedataWritten, "Process.cs:2573 SAVEDATA_TEXT 缺省文案")
        assertEquals(0, host.saveToCalls.size, "SAVEINFO 返回前不得抢跑实存")
    }

    /**
     * SAVEINFO 返回后的收口步 = `endAutoSaveCallSaveInfo`（`Process.cs:2581-2590`）：
     * `SaveTo(99, SAVEDATA_TEXT)`（`:2583`）成功 ⇒ 不报错、直接 endAutoSave 收口
     * （`SHOW_SHOP`，`:2599-2600`）。
     */
    @Test
    fun `SAVEINFO 返回后 SaveTo 99 成功并照常收口`() {
        val (calls, host, f) = atEventShopReturn(autoSave = true, saveToResult = true)
        f.step() // beginAutoSave → SAVEINFO
        assertEquals(SystemStateCode.AutoSave_CallSaveInfo, f.state)
        // SAVEINFO 脚本追加档名（PUTFORM 语义，Process.cs:1295-1306 同一格变量）：
        calls.savedataRead = "TS 自定义档名"

        val step = f.step()
        assertEquals(
            listOf(99 to "TS 自定义档名"),
            host.saveToCalls,
            "Process.cs:2583 SaveTo(saveTarget, SAVEDATA_TEXT) —— 读的是当前值（SAVEINFO 可改写）",
        )
        assertEquals(StepOutcome.CALLED, step.outcome)
        assertEquals("SHOW_SHOP", step.calledFunction)
        assertEquals(SystemStateCode.Shop_CallShowShop, f.state)
        assertEquals("endAutoSaveCallSaveInfo", step.handler)
        assertEquals(-1, f.saveTarget, "链收口后复位（否则入口闸永久挡住下一轮 AUTO_SAVE）")
        assertEquals(0, host.lines.size, "成功不报错")
    }

    /**
     * `SaveTo` 失败（`Process.cs:2584-2588`）：两行报错（zhs `Error.AutoSaveError1/2`，
     * `lang.emuera-zhs.xml:740-741`；日原文 `Lang.cs:3142/:3146`；报告称 eraTW 资源键名
     * AUTO_SAVE_4/5 —— 内容同）+ `ReadAnyKey`；**不抛不断**，随后照常收口（`:2589`）。
     */
    @Test
    fun `SaveTo 失败时双行报错加 ReadAnyKey 且链不中断`() {
        val (_, host, f) = atEventShopReturn(autoSave = true, saveToResult = false)
        f.step() // beginAutoSave → SAVEINFO

        val step = f.step()
        assertEquals(
            listOf("自动存档期间发生预期以外的的错误", "自动存档将被跳过"),
            host.lines,
            "Process.cs:2585-2586（双「的」为官方 zhs 原文，ADR-005 照抄）",
        )
        assertEquals(1, host.readAnyKeyCount, "Process.cs:2587 ReadAnyKey")
        assertEquals(StepOutcome.CALLED, step.outcome, "失败照样 endAutoSave（:2589）")
        assertEquals("SHOW_SHOP", step.calledFunction)
    }

    /**
     * 宿主没接实档（`saveTo` 缺省 null）：BLOCKED，不伪造成败、不转态；
     * `saveTarget` 保持 99，接线补齐后原地续跑。
     */
    @Test
    fun `宿主缺 saveTo 时落 BLOCKED 不伪造结果`() {
        val (calls, _, f) = atEventShopReturn(autoSave = true, saveToResult = null)
        f.step() // beginAutoSave → SAVEINFO

        val step = f.step()
        assertEquals(StepOutcome.BLOCKED, step.outcome)
        assertEquals(SystemStateCode.AutoSave_CallSaveInfo, f.state, "BLOCKED 不转态")
        assertEquals(99, f.saveTarget, "留挂账等接线，不伪造已存")
        assertEquals(BlockerKind.HOST_MISSING, step.blocker!!.kind)
        assertTrue(step.blocker.message.contains("saveTo"))
        assertFalse("SHOW_SHOP" in calls.called, "收口未发生")
    }

    /**
     * SAVEINFO 缺失：原版**当场**直调 `endAutoSaveCallSaveInfo()`（`Process.cs:2576-2577`）
     * ⇒ 同一步内完成 `SaveTo(99)` + 收口（嵌套直调拆步口径见 BeginFlow 类头）。
     * 钉 `:2572-2578` + `:2581-2601`。
     */
    @Test
    fun `SAVEINFO 缺失时 beginAutoSave 当场走 SaveTo 收口`() {
        val calls = FakeCalls().apply { functions += "SHOW_SHOP" }
        val host = FakeHost(autoSaveEnabled = true, saveToResult = true)
        val f = BeginFlow(calls, host) { "TS " }
        f.select(BeginType.SHOP, viaBeginType = false)
        assertEquals(StepOutcome.NOT_CALLED, f.step().outcome) // beginShop：EVENTSHOP 缺失

        val step = f.step()
        assertEquals(listOf(99 to "TS "), host.saveToCalls)
        assertEquals(StepOutcome.CALLED, step.outcome)
        assertEquals("SHOW_SHOP", step.calledFunction)
        assertEquals(SystemStateCode.Shop_CallShowShop, f.state)
        assertEquals(-1, f.saveTarget, "收口后复位")
    }

    /**
     * `SYSTEM_AUTOSAVE` 存在（`Process.cs:2567-2571`）：走 `AutoSave_CallUniqueAutosave`，
     * 它的注册 handler 是**裸 endAutoSave**（`Process.cs:2032`）—— 移植侧同样**不**触
     * 引擎 `SaveTo`（该函数自管实存），且 `saveTarget` 不置位（早退在 `:2572` 之前）。
     */
    @Test
    fun `SYSTEM_AUTOSAVE 在位时走 UniqueAutosave 且不触引擎 SaveTo`() {
        val calls = FakeCalls().apply { functions += setOf("SYSTEM_AUTOSAVE", "SAVEINFO", "SHOW_SHOP") }
        val host = FakeHost(autoSaveEnabled = true, saveToResult = true)
        val f = BeginFlow(calls, host) { "TS " }
        f.select(BeginType.SHOP, viaBeginType = false)
        f.step() // beginShop

        val step = f.step()
        assertEquals("SYSTEM_AUTOSAVE", step.calledFunction)
        assertEquals(SystemStateCode.AutoSave_CallUniqueAutosave, f.state)
        assertEquals(-1, f.saveTarget, "Process.cs:2567-2571 早退在 :2572 置位之前")

        val back = f.step()
        assertEquals("SHOW_SHOP", back.calledFunction)
        assertEquals(0, host.saveToCalls.size, "字典 :2032 —— UniqueAutosave 的 handler 是裸 endAutoSave")
        assertNullSavedata(calls)
    }

    private fun assertNullSavedata(calls: FakeCalls) {
        // SYSTEM_AUTOSAVE 分支不写 SAVEDATA_TEXT（:2567-2571 早退在 :2573 之前）。
        assertEquals(null, calls.savedataWritten)
    }

    /**
     * 入口闸「saveTarget>=0 不重复进 AUTO_SAVE」（裁定报告引新版 `Process.cs:1656-1659`；
     * 本地 1.824 无此段，TODO(verify) 见 `BeginFlow.saveTarget` KDoc）：本轮已带 99 时，
     * 商店返回**既不重跑 beginAutoSave 也不二次实存**，直进 endAutoSave 收口。
     */
    @Test
    fun `saveTarget 大于等于 0 时商店返回不重复进 AUTO_SAVE`() {
        val (calls, host, f) = atEventShopReturn(autoSave = true, saveToResult = true)
        f.step() // beginAutoSave：saveTarget=99，SAVEINFO 在跑（尚未收口）
        assertEquals(99, f.saveTarget)

        // 模拟本轮链未收口时又回到商店返回点（移植侧 enterState 直给回点状态）。
        f.enterState(SystemStateCode.Shop_CallEventShop)
        val step = f.step()
        assertEquals(
            listOf("SAVEINFO"),
            calls.called.filter { it == "SAVEINFO" || it == "SYSTEM_AUTOSAVE" },
            "入口闸命中：不得重跑 beginAutoSave",
        )
        assertEquals(0, host.saveToCalls.size, "不得二次 SaveTo")
        assertEquals(StepOutcome.CALLED, step.outcome)
        assertEquals("SHOW_SHOP", step.calledFunction, "直进 endAutoSave 收口（:2592-2601）")
        assertEquals(99, f.saveTarget, "闸命中不清 saveTarget（留给本轮链自己的收口点）")
    }

    /**
     * 缺省档名格式（`Process.cs:2573` `DateTime.Now.ToString("yyyy/MM/dd HH:mm:ss") + " "`）。
     */
    @Test
    fun `缺省 SAVEDATA_TEXT 是时间戳加一个尾随空格`() {
        val calls = FakeCalls().apply { functions += setOf("SAVEINFO", "SHOW_SHOP") }
        val real = BeginFlow(calls, FakeHost(autoSaveEnabled = true, saveToResult = true))
        real.select(BeginType.SHOP, viaBeginType = false)
        real.step() // beginShop
        real.step() // beginAutoSave（SAVEINFO 在位 → CALLED）
        assertTrue(
            Regex("""\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2} """).matches(calls.savedataWritten!!),
            "实得：${calls.savedataWritten}",
        )
    }

    /** `Initialize` 复位项（裁定报告引 `Process.cs:983-984`；见 saveTarget KDoc 的 TODO(verify)）。 */
    @Test
    fun `resetToBootState 复位 saveTarget`() {
        val (_, _, f) = atEventShopReturn(autoSave = true, saveToResult = true)
        f.step() // beginAutoSave → SAVEINFO
        assertEquals(99, f.saveTarget)
        f.resetToBootState()
        assertEquals(-1, f.saveTarget)
    }

    // ------------------------------------------------------------------
    // B2：endCallEventComEnd 三分支
    // ------------------------------------------------------------------

    /**
     * 缺省「後」（报告引 `ConfigData.cs:66` 的缺省口径；本地 1.824 无该配置项 ⇒
     * `autoSaveAtComEnd()` 缺省 false）：ComEnd **不**内联实存。非批量训练
     * （`isCTrain=false`）时三条早退全不命中，落尾部回训练链 —— 与旧移植读数一致（回归闸）。
     */
    @Test
    fun `endCallEventComEnd 缺省后档不触内联存档并回训练链`() {
        val calls = FakeCalls().apply { functions += "CALLTRAINEND" }
        val host = FakeHost(autoSaveEnabled = true, saveToResult = true)
        val f = BeginFlow(calls, host) { "TS " }
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        val step = f.step()
        assertEquals(SystemStateCode.Train_CallEventTrain, f.state)
        assertEquals(StepOutcome.CONTINUE, step.outcome)
        assertEquals(0, host.saveToCalls.size, "「後」档不在 ComEnd 内联走 B1 链")
        assertEquals(emptyList(), calls.called, "isCTrain=false ⇒ :2437 门不成立，CALLTRAINEND 不得派发")
    }

    /**
     * ③ CALLTRAINEND 早退（`Process.cs:2437-2448`）：`isCTrain && count==coms.Count`
     * ⇒ 清队列 + `CALLTRAINEND(force=false)`；调得动则**原地返回**（状态不动，`:2446`），
     * 下一次 step 再进本 handler 时 `isCTrain` 已 false，落尾部。
     */
    @Test
    fun `CALLTRAIN 队列耗尽时 ComEnd 先派发 CALLTRAINEND 再落尾部`() {
        val calls = FakeCalls().apply { functions += "CALLTRAINEND"; ct = true }
        val f = BeginFlow(calls, FakeHost()) { "TS " }
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        val step = f.step()
        assertEquals(StepOutcome.CALLED, step.outcome)
        assertEquals("CALLTRAINEND", step.calledFunction)
        assertEquals(SystemStateCode.Train_CallEventComEnd, f.state, "Process.cs:2446 —— return 前状态不动")
        assertFalse(calls.ct, ":2439 isCTrain=false（clearCommands）")

        val tail = f.step()
        assertEquals(StepOutcome.CONTINUE, tail.outcome)
        assertEquals(SystemStateCode.Train_CallEventTrain, f.state)
    }

    /** ③ 的反面：队列未耗尽（`count != coms.Count`，`:2437`）⇒ 不派发 CALLTRAINEND，落尾部。 */
    @Test
    fun `CALLTRAIN 队列未耗尽时 ComEnd 不派发 CALLTRAINEND`() {
        val calls = FakeCalls().apply { functions += "CALLTRAINEND"; ct = true; consumed = false }
        val f = BeginFlow(calls, FakeHost()) { "TS " }
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        val step = f.step()
        assertEquals(StepOutcome.CONTINUE, step.outcome)
        assertEquals(SystemStateCode.Train_CallEventTrain, f.state)
        assertEquals(emptyList(), calls.called)
    }

    /**
     * ① 临时行早退（`Process.cs:2426-2436`）：`LastLineIsTemporary && !isCTrain && needCheck`
     * ⇒ 回 `Train_CallShowUserCom` 重显指令菜单（`:2434`）。needCheck 初值 true（`:188`）。
     * `:2428-2432` 的 LastLineIsEmpty 半句未接（TODO(verify)）。
     */
    @Test
    fun `ComEnd 临时行校验命中时重显指令菜单`() {
        val calls = FakeCalls()
        val host = FakeHost(temporaryLine = true)
        val f = BeginFlow(calls, host) { "TS " }
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        val step = f.step()
        assertEquals(SystemStateCode.Train_CallShowUserCom, f.state, "Process.cs:2434 endCallShowUserCom")
        assertEquals(StepOutcome.CONTINUE, step.outcome)
        assertEquals(emptyList(), calls.called)
    }

    /**
     * ②「前」档（`AUTO_SAVE=="前"`，报告引新版 `Process.cs:1849-1891` 分支②）：
     * ComEnd **内联**走 B1 链 —— `saveTarget=99` + `SAVEDATA_TEXT` + `SaveTo(99)` +
     * 队列清法照 `:2437-2442`（clearCommands；报告 :1869-1874），随后落尾部回训练链。
     */
    @Test
    fun `前档时 ComEnd 内联走存档链并清训练队列`() {
        val calls = FakeCalls().apply { functions += "CALLTRAINEND"; ct = true }
        val host = FakeHost(autoSaveEnabled = true, saveToResult = true, comEndBeforeSave = true)
        val f = BeginFlow(calls, host) { "TS " }
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        val step = f.step()
        assertEquals(listOf(99 to "TS "), host.saveToCalls, "内联 SaveTo(99, SAVEDATA_TEXT)")
        assertEquals("TS ", calls.savedataWritten)
        assertFalse(calls.ct, "队列清法照 :2437-2442（报告 :1869-1874）")
        assertEquals(-1, f.saveTarget, "内联链自收口，不留挂账挡住商店档")
        assertEquals(StepOutcome.CONTINUE, step.outcome)
        assertEquals(SystemStateCode.Train_CallEventTrain, f.state)
    }

    /** ②「前」档 + 存档失败：双行报错 + ReadAnyKey（与 B1 的 `:2584-2588` 同一对文案）。 */
    @Test
    fun `前档存档失败时 ComEnd 打双行报错加 ReadAnyKey`() {
        val calls = FakeCalls()
        val host = FakeHost(autoSaveEnabled = true, saveToResult = false, comEndBeforeSave = true)
        val f = BeginFlow(calls, host) { "TS " }
        f.enterState(SystemStateCode.Train_CallEventComEnd)

        f.step()
        assertEquals(
            listOf("自动存档期间发生预期以外的的错误", "自动存档将被跳过"),
            host.lines,
        )
        assertEquals(1, host.readAnyKeyCount)
    }
}
