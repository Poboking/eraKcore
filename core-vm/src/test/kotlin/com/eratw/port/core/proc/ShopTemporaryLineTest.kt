package com.eratw.port.core.proc

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OBS2-L1 商店支换道钉测 —— `shopWaitInput` 买不成的两行提示
 * （`Process.cs:2624-2625` 「MONEY不足」/ `:2629-2630` 「售罄」）从
 * [BeginHost.writeLine] 普通行换到与原版同形的
 * [BeginHost.deleteLine]`(1)` + [BeginHost.printTemporaryLine] 临时行通道。
 *
 * 钉的基准行（reference/emuera-cs 逐字核对）：
 * - `Process.cs:2609-2640`（shopWaitInput）：`:2613` ItemSales 假 ⇒ `:2629-2630`
 *   deleteLine(1) + PrintTemporaryLine(OutOfStock)；`:2613` 真且 `:2615` BuyItem 假 ⇒
 *   `:2624-2625` deleteLine(1) + PrintTemporaryLine(NotEnoughMoney)；两支随后
 *   `:2632` endCallShowShop（`:2603-2607` = setWaitInput + 回 `Shop_WaitInput`）。
 * - **两支都不带 `updatedGeneration` 置位**：`Process.cs:2609-2640` 全文无
 *   `console.updatedGeneration`（该支的删除+临时行两语句后即 `:2632`；对照
 *   `ablupWaitInput` 的 `:2498-2500` 三支带置位半句）。移植侧照抄**不补**
 *   [BeginHost.markGenerationUpdated]（ADR-005）。
 * - deleteLine(1) 的删除对象 = 玩家那次键入的回显行
 *   （`EmueraConsole.cs:1280-1282` 的 Print(str) 落点，回显已随 `f85b3c2d` 落 main，
 *   回归钉 [InputEchoTest]）；11-04 曾因无回显把 ABLUP 菜单按钮行误删而回滚
 *   （可点列表 `[0,100]`→`[]`），本文件的「按钮列表不被吃掉」组用例即钉此前提在商店支不复现。
 * - 临时行同寿：下一次正式输出到达时先被删（`EmueraConsole.cs:2891-2893` 的
 *   addDisplayLine 门，移植侧 `LineBufferConsoleSink` appendGroup 的同形摘除）。
 * - 文案：`Lang.cs:3150`「お金が足りません。」/ `:3154`「売っていません。」，
 *   zhs 译文「MONEY不足」/「售罄」（lang.emuera-zhs.xml:742/:743）。
 *
 * 世代读数口径（如实登记）：原版的等待行身份 = 挂起的那条 ERB `INPUT` 语句
 * （`process.getCurrentLine`，商店重等时恒为同一行 ⇒ `EmueraConsole.cs:789-796`
 * 走 else 支、旧按钮保持可点，这就是原版敢不置位的原因）。本刀（OBS2-L1）落 main 时
 * 移植生产链 `GameSession` 构造 [ConsoleBeginHost] **没传**身份通道 ⇒ 身份退化为行水位
 * （`LineBufferConsoleSink.kt:714`），提示/回显推进水位后重等入口会前移世代——
 * **换道前后该读数一致**（不是本刀引入），收口由 OBS2-L5 接上（现两条通道都传：
 * `scriptLineToken` + `pageScriptLineIdentity`），接线后的生产读数与破坏面对照钉在
 * `WaitInputGenerationTest`。本文件保留的 `stableScriptLine = false` 一支因此是
 * **未接线的降级形状**（假体/无身份通道时控制台仍走行水位），不再是生产同形。
 * 稳定身份一侧由 [稳定脚本行身份下重等后商店旧按钮仍可点] 钉住。
 *
 * 真机手感（触屏上按钮实际亮不亮）：本机无 adb 设备，一律 TODO(verify)。
 */
class ShopTemporaryLineTest {

    // ------------------------------------------------------------------
    // 假体（形制同 SystemMenuGenerationWiringTest）
    // ------------------------------------------------------------------

    private class FakeCalls : BeginCallPort {
        val functions = HashSet<String>()
        val events = HashSet<String>()
        val called = ArrayList<String>()
        var systemResultValue: Long = 0L
        var pending: BeginType? = null

        override fun callFunction(name: String, force: Boolean, isEvent: Boolean): Boolean {
            val exists = if (isEvent) events.contains(name) else functions.contains(name)
            if (!exists) {
                if (force) throw CodeEE("找不到函数\"@$name\"")
                return false
            }
            called += name
            return true
        }

        override fun result(): Long = 0L
        override fun setResult(value: Long) {}
        override fun systemResult(): Long = systemResultValue
        override fun setSystemResult(value: Long) {
            systemResultValue = value
        }

        override fun nextCom(): Long = -1L
        override fun setNextCom(value: Long) {}
        override fun selectCom(): Long = -1L
        override fun setSelectCom(value: Long) {}
        override fun isCTrain(): Boolean = false
        override fun clearCommands(): Boolean = false
        override fun beginTrainReset() {}
        override fun afterShowUserComReset() {}
        override fun afterInputComReset() {}
        override fun afterSourceCheckReset() {}
        override fun trainName(code: Int): String? = null
        override fun trainNameCount(): Int = 0
        override fun commandsConsumed(): Boolean = true
        override fun pendingBegin(): BeginType? = pending
        override fun takePendingBegin(): BeginType? = pending.also { pending = null }
        override fun clearPendingBegin() {
            pending = null
        }
    }

    /** 假体商品模型：`itemSales`/`buyItem` 返回值可控，调用各记流水。 */
    private class FakeShopModel(
        val max: Int = 100,
        var salesResult: Boolean = true,
        var buyResult: Boolean = true,
    ) : ShopHostModel {
        val salesCalls = ArrayList<Int>()
        val buyCalls = ArrayList<Int>()
        val results = ArrayList<Long>()

        override fun maxShopItem(): Int = max
        override fun itemSales(itemNo: Int): Boolean {
            salesCalls += itemNo
            return salesResult
        }

        override fun buyItem(itemNo: Int): Boolean {
            buyCalls += itemNo
            return buyResult
        }

        override fun writeResult(value: Long) {
            results += value
        }
    }

    /** 商店步假体宿主：四通道各记流水，[markGenerationUpdated] 计次钉「原版不置位」。 */
    private class FakeShopHost(
        override val shopHostModel: ShopHostModel? = null,
        var shopInput: Long? = null,
    ) : BeginHost {
        val written = ArrayList<String>()
        val errorLines = ArrayList<String>()
        val deletedCounts = ArrayList<Int>()
        val temporaryLines = ArrayList<String>()
        var generationMarks = 0

        override fun awaitShopInput(): Long? = shopInput
        override fun writeLine(text: String) {
            written += text
        }

        override fun printError(text: String) {
            errorLines += text
        }

        override fun deleteLine(count: Int) {
            deletedCounts += count
        }

        override fun printTemporaryLine(text: String) {
            temporaryLines += text
        }

        override fun markGenerationUpdated() {
            generationMarks++
        }
    }

    // ------------------------------------------------------------------
    // 通道级：删行 + 临时行 + 不置位（Process.cs:2624-2630 逐字口径）
    // ------------------------------------------------------------------

    /** 钱不够（`:2615` BuyItem 假）：deleteLine(1) + PrintTemporaryLine，**不**置位。 */
    @Test
    fun `钱不够删回显行画临时行且不置世代位`() {
        val calls = FakeCalls()
        val model = FakeShopModel(buyResult = false)
        val host = FakeShopHost(model, shopInput = 1L)
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Shop_WaitInput)

        val step = f.step()

        assertEquals(listOf(1), host.deletedCounts, "Process.cs:2624 deleteLine(1)（删的是键入回显行）")
        assertEquals(
            listOf("MONEY不足"),
            host.temporaryLines,
            "Process.cs:2625 PrintTemporaryLine（Lang.cs:3150 / zhs:742）",
        )
        assertTrue(host.written.isEmpty(), "换道后提示不再经 writeLine 落普通行")
        assertEquals(0, host.generationMarks, "Process.cs:2609-2640 全文无 updatedGeneration（对照 :2500），照抄不补")
        assertEquals(SystemStateCode.Shop_WaitInput, f.state, "Process.cs:2632 → :2603-2607 重等")
        assertEquals("Process.cs:2624-2632", step.evidence)
    }

    /** 售罄（`:2613` ItemSales 假）：同款两语句 + 不置位。 */
    @Test
    fun `售罄删回显行画临时行且不置世代位`() {
        val calls = FakeCalls()
        val model = FakeShopModel(salesResult = false)
        val host = FakeShopHost(model, shopInput = 2L)
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Shop_WaitInput)

        val step = f.step()

        assertEquals(listOf(1), host.deletedCounts, "Process.cs:2629 deleteLine(1)")
        assertEquals(
            listOf("售罄"),
            host.temporaryLines,
            "Process.cs:2630 PrintTemporaryLine（Lang.cs:3154 / zhs:743）",
        )
        assertTrue(host.written.isEmpty(), "换道后提示不再经 writeLine 落普通行")
        assertEquals(0, host.generationMarks, "同上：原版商店支不置位（ADR-005）")
        assertTrue(model.buyCalls.isEmpty(), "售罄不碰 BuyItem（:2615 在 ItemSales 真支里）")
        assertEquals(SystemStateCode.Shop_WaitInput, f.state)
        assertEquals("Process.cs:2629-2632", step.evidence)
    }

    /** 成交支（`:2615-2622`）三通道全零：换道不外溢。 */
    @Test
    fun `成交路径不删行不画临时行也不写普通行`() {
        val calls = FakeCalls().apply { events += "EVENTBUY" }
        val model = FakeShopModel()
        val host = FakeShopHost(model, shopInput = 3L)
        val f = BeginFlow(calls, host)
        f.enterState(SystemStateCode.Shop_WaitInput)

        f.step()

        assertTrue(host.deletedCounts.isEmpty(), "成交支无 deleteLine（:2624/:2629 都在失败支）")
        assertTrue(host.temporaryLines.isEmpty())
        assertTrue(host.written.isEmpty())
        assertEquals(0, host.generationMarks)
        assertEquals(listOf("EVENTBUY"), calls.called, "Process.cs:2618")
    }

    // ------------------------------------------------------------------
    // 真宿主全链（ConsoleBeginHost + 真 sink + 真回显装配，GameSession 同形线）
    // ------------------------------------------------------------------

    /**
     * 全链装置：装配线逐条对 `GameSession` 的装配第 7 步（ConsoleBeginHost +
     * `inputSink.onEchoInput = { console.printLine(it) }`）。
     * [stableScriptLine] = 真机 PC 的脚本行身份（商店重等恒在同一条 INPUT 语句上，
     * 经 [ConsoleBeginHost] 的 `scriptLineToken` 构造参数注入）；false = **身份通道未接**
     * 的降级形状（生产自 OBS2-L5 起两条通道都传，未接时控制台回退行水位，口径见类头 KDoc）。
     */
    private class FullRig(
        stableScriptLine: Boolean,
        wireEcho: Boolean = true,
        model: FakeShopModel = FakeShopModel(buyResult = false),
    ) {
        val console = LineBufferConsoleSink(clientWidth = 80)
        val input = QueueInputSink()
        val shopModel = model
        val host = ConsoleBeginHost(
            input,
            console,
            shopHostModel = model,
            scriptLineToken = if (stableScriptLine) {
                { 777L } // 商店等待语句的固定行身份（同一 INPUT 语句重等）
            } else {
                null // 未接身份通道 ⇒ 行水位回退（生产已不再这一形状，见类头 KDoc）
            },
        )

        init {
            if (wireEcho) {
                input.onEchoInput = { console.printLine(it) } // GameSession.kt:924 同形
            }
        }

        /** 商店菜单页：一行两颗按钮（[0] 买 / [100] 退出，值形制同 MAPMOVE/ABLUP 侧读数）。 */
        fun printShopMenu() {
            console.printButton("買う", 0L)
            console.printButton("出る", 100L)
        }

        fun lineTexts(): List<String> = console.lines().map { console.textOf(it) }

        fun optionValues(): List<Long> = console.currentOptions().map { it.value as Long }

        fun buttonValues(): List<Long> = console.buttons().map { it.value as Long }
    }

    /** ① 买不成：键入回显行消失、屏上末行 = 提示临时行、按钮行本体健在。 */
    @Test
    fun `买不成时回显行被删屏上末行是提示临时行`() {
        val rig = FullRig(stableScriptLine = true)
        rig.printShopMenu()
        val calls = FakeCalls()
        val f = BeginFlow(calls, rig.host)
        f.enterState(SystemStateCode.Shop_WaitInput)

        assertTrue(rig.input.submitInt(42L), "预喂商品号 42")
        f.step()

        assertEquals(
            emptyList(),
            rig.lineTexts().filter { it == "42" },
            "deleteLine(1)（Process.cs:2624）删的正是回显行 —— 换道前它是残留的普通行",
        )
        val last = rig.console.lines().last()
        assertTrue(last.isTemporary, "提示走临时行通道（:2625），不是普通行")
        assertEquals("MONEY不足", rig.console.textOf(last))
        assertEquals(listOf(0L, 100L), rig.buttonValues(), "菜单按钮行没被吃掉（11-04 回滚形态的反面）")
    }

    /** ③ 同寿：下一次正式输出把提示行收走（`EmueraConsole.cs:2891-2893` 的门）。 */
    @Test
    fun `下一次正式输出把提示行收走`() {
        val rig = FullRig(stableScriptLine = true)
        rig.printShopMenu()
        val calls = FakeCalls()
        val f = BeginFlow(calls, rig.host)
        f.enterState(SystemStateCode.Shop_WaitInput)

        assertTrue(rig.input.submitInt(42L))
        f.step()
        assertEquals(1, rig.lineTexts().count { it == "MONEY不足" }, "失败当场提示上屏")

        rig.console.printLine("下一屏正式输出") // 例如语料重印 SHOW_SHOP 的第一行
        assertEquals(
            emptyList(),
            rig.lineTexts().filter { it == "MONEY不足" },
            "临时行与回显同寿：被下一次正式输出收走（换道前它是永久残留的普通行）",
        )
        assertEquals("下一屏正式输出", rig.lineTexts().last())
    }

    /**
     * ② 需求硬条：可点列表不被换道吃掉 —— **PC 稳定脚本行身份**一侧。
     * 重等入口 `newGeneration` 走 else 支（`EmueraConsole.cs:789-796`），
     * 旧菜单按钮保持可点；这正是原版商店支不写 `updatedGeneration` 也成立的机制。
     * 断言值即 11-04 回滚时的对照读数：换道前 `[0,100]`，换道后仍 `[0,100]`。
     */
    @Test
    fun `稳定脚本行身份下重等后商店旧按钮仍可点`() {
        val rig = FullRig(stableScriptLine = true)
        rig.printShopMenu()
        val calls = FakeCalls()
        val f = BeginFlow(calls, rig.host)
        f.enterState(SystemStateCode.Shop_WaitInput)

        assertTrue(rig.input.submitInt(42L))
        f.step()
        assertEquals(
            listOf(0L, 100L),
            rig.optionValues(),
            "第一次等待入口冻结的可点集合（换道前读数 [0,100]，换道后必须仍是这一组）",
        )

        assertTrue(rig.input.submitInt(9L))
        f.step() // 第二次等待入口：稳定行身份 ⇒ 世代不前移，且删除对象是回显不是按钮行
        assertEquals(
            listOf(0L, 100L),
            rig.optionValues(),
            "重等后旧按钮仍可点（换道前后同值）——按钮行没被 deleteLine(1) 误删",
        )
    }

    /**
     * ② 对照：**身份通道未接**（[FullRig] 的 `stableScriptLine = false`，即 OBS2-L1 落 main
     * 当时的生产形状）下的读数钉 —— 行水位回退让第二次重等入口前移世代、旧按钮失配；
     * **换道前后该读数逐字一致**（写线支同样推水位、同样失配），本刀没有把新病带来，
     * 也没有治好它。治好 = OBS2-L5 的接线（`GameSession` 现传 `scriptLineToken` +
     * `pageScriptLineIdentity` 两条通道），接线后的生产读数与破坏面钉在
     * `WaitInputGenerationTest`；本用例保留作**降级形状**的对照（宿主没接身份通道时
     * 控制台的读数仍然如此）。
     */
    @Test
    fun `未接身份通道的行水位回退读数换道前后同值`() {
        val rig = FullRig(stableScriptLine = false)
        rig.printShopMenu()
        val calls = FakeCalls()
        val f = BeginFlow(calls, rig.host)
        f.enterState(SystemStateCode.Shop_WaitInput)

        assertTrue(rig.input.submitInt(42L))
        f.step()
        assertEquals(listOf(0L, 100L), rig.optionValues(), "首等冻结读数（换道前后同值）")
        assertEquals(listOf(0L, 100L), rig.buttonValues(), "按钮行本体在两条通道下都不被删（首等快照不含删除后的重收集）")

        assertTrue(rig.input.submitInt(9L))
        f.step()
        assertEquals(
            emptyList(),
            rig.optionValues(),
            "行水位回退的第二次重等前移世代 —— 该读数换道前实测同为 []（不是本刀引入的退化，见类头 KDoc 与 TODO(verify)）",
        )
        assertEquals(listOf(0L, 100L), rig.buttonValues(), "按钮行本体仍在屏上（失配是世代级的，不是被删行）")
    }

    /**
     * 前提钉（11-04 回滚形态的复现装置）：**不接回显**时换道的删除对象就是按钮行本体，
     * 可点列表从 `[0,100]` 掉到 `[]` —— 这条之所以是病，正因为回显已在 main
     * （`f85b3c2d`）；生产装配漏接 `onEchoInput` 会立刻撞上本用例钉住的形态。
     */
    @Test
    fun `无回显装配下换道的删除误吃按钮行`() {
        val rig = FullRig(stableScriptLine = true, wireEcho = false)
        rig.printShopMenu()
        val calls = FakeCalls()
        val f = BeginFlow(calls, rig.host)
        f.enterState(SystemStateCode.Shop_WaitInput)

        assertTrue(rig.input.submitInt(42L))
        f.step()

        assertEquals(emptyList(), rig.buttonValues(), "无回显 ⇒ deleteLine(1) 删掉的是菜单按钮行（2026-11-04 真机实证形态）")
        val last = rig.console.lines().last()
        assertTrue(last.isTemporary, "提示仍走临时行通道（与回显无关）")
        assertEquals("MONEY不足", rig.console.textOf(last))
    }

    // ------------------------------------------------------------------
    // 第二项：自动保存失败两行的通道裁定（PrintError vs writeLine）
    // ------------------------------------------------------------------

    /** 自动存档失败假体 A：printError / writeLine 分道记录（钉「调用点已换通道」）。 */
    private class FakePrintErrorHost(
        var saveToResult: Boolean? = false,
    ) : BeginHost {
        val errorLines = ArrayList<String>()
        val written = ArrayList<String>()
        var readAnyKeyCount = 0

        override fun autoSave(): Boolean = true
        override fun saveTo(slot: Int, text: String): Boolean? {
            check(slot == BeginFlow.AUTO_SAVE_SLOT) { "固定槽 99（Process.cs:136/:2572/:2583）" }
            return saveToResult
        }

        override fun readAnyKey() {
            readAnyKeyCount++
        }

        override fun printError(text: String) {
            errorLines += text
        }

        override fun writeLine(text: String) {
            written += text
        }
    }

    /** 自动存档失败假体 B：**只**覆写 writeLine（AutoSaveChainTest/FakeHost 同形）——
     * [BeginHost.printError] 走接口缺省降级，钉「未覆写宿主的行为与换道前逐字一致」。 */
    private class FakeWriteLineOnlyHost(
        var saveToResult: Boolean? = false,
    ) : BeginHost {
        val written = ArrayList<String>()
        var readAnyKeyCount = 0

        override fun autoSave(): Boolean = true
        override fun saveTo(slot: Int, text: String): Boolean? = saveToResult
        override fun readAnyKey() {
            readAnyKeyCount++
        }

        override fun writeLine(text: String) {
            written += text
        }
    }

    private fun autoSaveFailFlow(host: BeginHost): BeginFlow {
        val calls = FakeCalls().apply {
            functions += "SAVEINFO"
            functions += "SHOW_SHOP"
        }
        val f = BeginFlow(calls, host) { "TS " }
        f.select(BeginType.SHOP, viaBeginType = false)
        assertEquals(StepOutcome.NOT_CALLED, f.step().outcome) // beginShop：EVENTSHOP 缺失（:2546-2549）
        f.step() // beginAutoSave → SAVEINFO（:2563-2577）
        return f
    }

    /**
     * 裁定钉 A：自动存档失败位（[BeginFlow] 的 `endAutoSaveCallSaveInfo` 支）现在打的
     * 是 [BeginHost.printError] 通道（原版 `console.PrintError`，`Process.cs:2585-2586`），
     * 不再打 writeLine；同款形状的 `endCallEventComEnd` AUTO_SAVE「前」支换道见源码
     * 行钉（交付证据表）。与 `PrintError` 的逐句差集与各格的补齐位置见
     * [BeginHost.printError] 的 KDoc；sink 侧对位 `LineBufferConsoleSink.printError`
     * （`ConsoleSink.kt:376` 声明）。
     */
    @Test
    fun `自动存档失败两行走 printError 通道`() {
        val host = FakePrintErrorHost(saveToResult = false)
        val f = autoSaveFailFlow(host)

        val step = f.step() // endAutoSaveCallSaveInfo：SaveTo 假 → 两行报错 + ReadAnyKey（:2584-2588）

        assertEquals(
            listOf("自动存档期间发生预期以外的的错误", "自动存档将被跳过"),
            host.errorLines,
            "Process.cs:2585-2586 PrintError（zhs:740-741，双「的」官方原文 ADR-005 照抄）",
        )
        assertTrue(host.written.isEmpty(), "换道后不再经 writeLine 落普通行")
        assertEquals(1, host.readAnyKeyCount, "Process.cs:2587 ReadAnyKey")
        assertEquals(StepOutcome.CALLED, step.outcome, "失败照样 endAutoSave（:2589）")
    }

    /**
     * 裁定钉 B：`BeginHost.printError` 的缺省实现降级转 [BeginHost.writeLine] ——
     * 未覆写该方法的宿主（含现状 [ConsoleBeginHost]，`ConsoleBeginHost.kt:140-142` 只覆写
     * writeLine）拿到的两行与普通行**逐字同前**，接线前生产行为零变化；
     * 接线（`ConsoleBeginHost.printError → console.printError`）后样式复位 / 强制左对齐 /
     * 不折行 / 不吃按钮解析 / 帧必上屏五格同时到位。
     */
    @Test
    fun `printError 缺省降级为 writeLine 未接线宿主行为不变`() {
        val host = FakeWriteLineOnlyHost(saveToResult = false)
        val f = autoSaveFailFlow(host)

        f.step()

        assertEquals(
            listOf("自动存档期间发生预期以外的的错误", "自动存档将被跳过"),
            host.written,
            "缺省降级 = writeLine：与换道前（BeginFlow 直调 writeLine）读数一致",
        )
        assertEquals(1, host.readAnyKeyCount)
    }
}
