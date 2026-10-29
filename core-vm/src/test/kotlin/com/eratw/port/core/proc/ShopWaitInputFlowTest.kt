package com.eratw.port.core.proc

import com.eratw.port.core.err.CodeEE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 商店输入状态 `Shop_WaitInput` 的 handler（`Process.shopWaitInput`，Process.cs:2609-2640）
 * 对拍。与 [ShopHostModelTest] 的分工：那个走**真变量池**钉 [StoreShopHostModel] 的
 * 读数语义（扣钱/ITEM/BOUGHT/RESULT 落池），本类用**假体 [ShopHostModel]** 钉
 * [BeginFlow] handler 对注入契约的使用方式：
 *
 * - 调用顺序与旗标：ItemSales → BuyItem → EVENTBUY（force=false, isEvent=true，
 *   Process.cs:2613/2615/2618）；越界支 RESULT 先写、USERSHOP 后调（force=true，
 *   :2636-2637）且状态最后切（:2638）；
 * - 输入范围检查 `0 <= input < maxShopItem`（:2611，闭开区间，负数同越界支）；
 * - 失败文案与回环：MONEY不足（Lang.cs:3150 / zhs:742）、售罄（Lang.cs:3154 /
 *   zhs:743），落 endCallShowShop（:2632 → :2603-2607 = 回 Shop_WaitInput 重等输入，
 *   不重跑 SHOW_SHOP）；
 * - 阻塞五元组：shopHostModel 未接 = HOST_MISSING，输入面未接 = HOST_INPUT。
 *
 * 迁移目标同时过 [SystemStateGraph.requireTransition] —— handler 产出与状态图
 * 声明必须一致（Shop_WaitInput 失败边的目标即 Shop_WaitInput 自身）。
 */
class ShopWaitInputFlowTest {

    // ------------------------------------------------------------------
    // 假体
    // ------------------------------------------------------------------

    /** 脚本调用记录（连 force/isEvent 旗标一起钉，原版 :2618/:2637 两处旗标不同）。 */
    private data class CallRecord(val name: String, val force: Boolean, val isEvent: Boolean)

    private class FakeCalls : BeginCallPort {
        val functions = HashSet<String>()
        val events = HashSet<String>()
        val calls = ArrayList<CallRecord>()

        var systemResultValue: Long = 0L
        var pending: BeginType? = null

        override fun callFunction(name: String, force: Boolean, isEvent: Boolean): Boolean {
            val exists = if (isEvent) events.contains(name) else functions.contains(name)
            if (!exists) {
                if (force) throw CodeEE("函数 $name 未定义")
                return false
            }
            calls += CallRecord(name, force, isEvent)
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
        override fun trainNameCount(): Int? = null
        override fun pendingBegin(): BeginType? = pending
        override fun takePendingBegin(): BeginType? = pending.also { pending = null }
        override fun clearPendingBegin() {
            pending = null
        }
    }

    /**
     * 假体商品模型：四个方法的调用各记一份流水，行为由构造参数给定
     * （缺省全成交）。契约冻结为现有 4 方法，这里不增不减。
     */
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

    private class FakeHost(
        override val shopHostModel: ShopHostModel? = null,
    ) : BeginHost {
        var shopInput: Long? = null
        var shopInputCalls = 0
        val written = ArrayList<String>()

        override fun awaitShopInput(): Long? {
            shopInputCalls++
            return shopInput
        }

        override fun writeLine(text: String) {
            written += text
        }
    }

    /**
     * 状态直落 Shop_WaitInput（原版 `endCallShowShop` 的落点，Process.cs:2603-2607），
     * 一步即 shopWaitInput handler。
     */
    private fun shopFlow(
        calls: FakeCalls,
        input: Long?,
        model: ShopHostModel?,
    ): Pair<BeginFlow, FakeHost> {
        val host = FakeHost(model)
        host.shopInput = input
        val flow = BeginFlow(calls, host)
        flow.enterState(SystemStateCode.Shop_WaitInput)
        return flow to host
    }

    // ------------------------------------------------------------------
    // 成交路径（Process.cs:2613-2623）
    // ------------------------------------------------------------------

    @Test
    fun `合法商品号成交派 EVENTBUY 并切 CallEventBuy`() {
        val calls = FakeCalls().apply { events += "EVENTBUY" }
        val model = FakeShopModel()
        val (flow, host) = shopFlow(calls, input = 3L, model = model)

        val step = flow.step()

        assertEquals(StepOutcome.CALLED, step.outcome)
        assertEquals("EVENTBUY", step.calledFunction)
        // :2618 —— EVENTBUY 是 force=false 的 event；:2617 先切状态后调用。
        assertEquals(
            listOf(CallRecord("EVENTBUY", force = false, isEvent = true)),
            calls.calls,
            "Process.cs:2618",
        )
        assertEquals(SystemStateCode.Shop_CallEventBuy, flow.state, "Process.cs:2617")
        SystemStateGraph.requireTransition(SystemStateCode.Shop_WaitInput, flow.state)
        // :2613 → :2615 —— 先 ItemSales 后 BuyItem，都拿同一商品号。
        assertEquals(listOf(3), model.salesCalls, "Process.cs:2613")
        assertEquals(listOf(3), model.buyCalls, "Process.cs:2615")
        // InputSystemInteger（Process.cs:426-430）—— 提交值写进 systemResult。
        assertEquals(3L, calls.systemResultValue)
        // :2636 的 RESULT 写入只在越界支；成交支不写。
        assertTrue(model.results.isEmpty(), "成交路径不写 RESULT（Process.cs:2636 属越界支）")
        assertTrue(host.written.isEmpty(), "成交路径不出临时行")
        assertEquals("Process.cs:2613-2623", step.evidence)
    }

    // ------------------------------------------------------------------
    // 输入范围检查（Process.cs:2611）
    // ------------------------------------------------------------------

    @Test
    fun `上界边界 maxShopItem 减一在界内而等于 maxShopItem 越界`() {
        // 界内支：3 < maxShopItem=4（:2611 的 < 是开区间上界）。
        val inCalls = FakeCalls().apply { events += "EVENTBUY" }
        val inModel = FakeShopModel(max = 4)
        val (inFlow, _) = shopFlow(inCalls, input = 3L, model = inModel)
        assertEquals(StepOutcome.CALLED, inFlow.step().outcome)
        assertEquals(listOf(3), inModel.salesCalls, "Process.cs:2611 开区间上界内")

        // 越界支：4 == maxShopItem 即越界（同 :2611）。
        val outCalls = FakeCalls().apply { functions += "USERSHOP" }
        val outModel = FakeShopModel(max = 4)
        val (outFlow, _) = shopFlow(outCalls, input = 4L, model = outModel)
        val step = outFlow.step()

        assertEquals(StepOutcome.CALLED, step.outcome)
        assertEquals("USERSHOP", step.calledFunction)
        assertTrue(outModel.salesCalls.isEmpty(), "越界不进 ItemSales（:2613 在界内支里）")
        assertEquals(listOf(4L), outModel.results, "Process.cs:2636")
        assertEquals(SystemStateCode.Shop_CallEventBuy, outFlow.state, "Process.cs:2638")
    }

    @Test
    fun `负数输入与越界同支 先写 RESULT 再 force 调 USERSHOP`() {
        val calls = FakeCalls().apply { functions += "USERSHOP" }
        val model = FakeShopModel()
        val (flow, host) = shopFlow(calls, input = -1L, model = model)

        val step = flow.step()

        assertEquals(StepOutcome.CALLED, step.outcome)
        // :2637 —— USERSHOP 是 force=true 的非 event；:2636 RESULT 先于调用写入。
        assertEquals(
            listOf(CallRecord("USERSHOP", force = true, isEvent = false)),
            calls.calls,
            "Process.cs:2637",
        )
        assertEquals(listOf(-1L), model.results, "Process.cs:2636")
        assertEquals(-1L, calls.systemResultValue)
        assertEquals(SystemStateCode.Shop_CallEventBuy, flow.state, "Process.cs:2638")
        SystemStateGraph.requireTransition(SystemStateCode.Shop_WaitInput, flow.state)
        assertTrue(model.salesCalls.isEmpty(), ":2611 的 >= 0 不成立，不进商品模型")
        assertTrue(host.written.isEmpty(), "越界支不出临时行")
    }

    @Test
    fun `USERSHOP 缺失时 force 调用抛 CodeEE`() {
        // :2637 force=true —— 原版缺失即 CodeEE（由 BeginCallPort.callFunction 抛）。
        val calls = FakeCalls() // functions 不含 USERSHOP
        val (flow, _) = shopFlow(calls, input = 100L, model = FakeShopModel())

        assertFailsWith<CodeEE> { flow.step() }
    }

    // ------------------------------------------------------------------
    // 成交失败（Process.cs:2624-2632）
    // ------------------------------------------------------------------

    @Test
    fun `ItemSales 返 false 打售罄并回 Shop_WaitInput 重等输入`() {
        val calls = FakeCalls()
        val model = FakeShopModel(salesResult = false)
        val (flow, host) = shopFlow(calls, input = 2L, model = model)

        val step = flow.step()

        assertEquals(StepOutcome.CONTINUE, step.outcome)
        // :2632 endCallShowShop() → :2605-2606 = 回 Shop_WaitInput（不重跑 SHOW_SHOP）。
        assertEquals(SystemStateCode.Shop_WaitInput, flow.state, "Process.cs:2632 / :2603-2607")
        SystemStateGraph.requireTransition(SystemStateCode.Shop_WaitInput, flow.state)
        assertEquals(listOf("售罄"), host.written, "Lang.Error.OutOfStock（Lang.cs:3154 / zhs:743）")
        assertTrue(model.buyCalls.isEmpty(), "售罄不碰 BuyItem（:2615 在 ItemSales 真支里）")
        assertTrue(calls.calls.isEmpty(), "不派 EVENTBUY")
        assertEquals("Process.cs:2629-2632", step.evidence)
    }

    @Test
    fun `BuyItem 返 false 打 MONEY不足并回 Shop_WaitInput 重等输入`() {
        val calls = FakeCalls()
        val model = FakeShopModel(buyResult = false)
        val (flow, host) = shopFlow(calls, input = 1L, model = model)

        val step = flow.step()

        assertEquals(StepOutcome.CONTINUE, step.outcome)
        assertEquals(SystemStateCode.Shop_WaitInput, flow.state, "Process.cs:2632 / :2603-2607")
        SystemStateGraph.requireTransition(SystemStateCode.Shop_WaitInput, flow.state)
        assertEquals(listOf("MONEY不足"), host.written, "Lang.Error.NotEnoughMoney（Lang.cs:3150 / zhs:742）")
        assertEquals(listOf(1), model.salesCalls, "Process.cs:2613 在售")
        assertEquals(listOf(1), model.buyCalls, "Process.cs:2615 成交失败")
        assertTrue(model.results.isEmpty(), "失败路径不写 RESULT")
        assertTrue(calls.calls.isEmpty(), "不派 EVENTBUY")
        assertEquals("Process.cs:2624-2632", step.evidence)
    }

    @Test
    fun `失败回环后下一步重新取商店输入`() {
        // 原版 endCallShowShop = setWaitInput + 回 Shop_WaitInput：下一次输入是新提交。
        val calls = FakeCalls().apply { events += "EVENTBUY" }
        val model = FakeShopModel(salesResult = false)
        val host = FakeHost(model).apply { shopInput = 2L }
        val flow = BeginFlow(calls, host)
        flow.enterState(SystemStateCode.Shop_WaitInput)

        val fail = flow.step()
        assertEquals(StepOutcome.CONTINUE, fail.outcome)
        assertEquals(1, host.shopInputCalls, "失败步取了一次输入")

        host.shopInput = 0L
        model.salesResult = true
        val retry = flow.step()
        assertEquals(StepOutcome.CALLED, retry.outcome, "第二次提交成交")
        assertEquals(2, host.shopInputCalls, "回环后重新取输入（:2605 setWaitInput）")
        assertEquals(listOf(2, 0), model.salesCalls, "两次提交各过一次 ItemSales")
    }

    // ------------------------------------------------------------------
    // 阻塞五元组（真机探针原文的回归钉）
    // ------------------------------------------------------------------

    @Test
    fun `shopHostModel 未接时 BLOCKED 且五元组齐全`() {
        val calls = FakeCalls()
        val (flow, host) = shopFlow(calls, input = 0L, model = null)

        val step = flow.step()

        assertEquals(StepOutcome.BLOCKED, step.outcome)
        assertNull(step.calledFunction)
        assertEquals("shopWaitInput", step.handler)
        assertEquals(SystemStateCode.Shop_WaitInput, flow.state, "阻塞不迁移状态")
        assertTrue(calls.calls.isEmpty(), "不伪造成交")
        val blocker = assertNotNull(step.blocker)
        assertEquals(BlockerKind.HOST_MISSING, blocker.kind, "宿主面缺失，不是 BOOT-2 未接管")
        assertEquals(
            "ItemSales/BuyItem 属宿主商品模型（Process.cs:2611-2639）；" +
                "BeginHost.shopHostModel 未接",
            blocker.message,
        )
        assertEquals("Process.cs:2609-2640", blocker.evidence)
        assertEquals(-1, blocker.line, "状态级阻塞无 ERB 位置")
        assertEquals(-1, blocker.pc)
        assertTrue(blocker.tuple().contains("error=ItemSales/BuyItem"), "tuple 五元组可读")
        // 交付值仍写进 systemResult（与 C# 一致：InputSystemInteger 先于 handler）。
        assertEquals(0L, calls.systemResultValue)
    }

    @Test
    fun `输入面未接是 AWAIT_INPUT 而非 BLOCKED`() {
        // 与上一条对照：没有输入后端 = HOST_INPUT 等输入；没有商品模型 = HOST_MISSING。
        val calls = FakeCalls()
        val (flow, _) = shopFlow(calls, input = null, model = FakeShopModel())

        val step = flow.step()

        assertEquals(StepOutcome.AWAIT_INPUT, step.outcome)
        val blocker = assertNotNull(step.blocker)
        assertEquals(BlockerKind.HOST_INPUT, blocker.kind)
        assertEquals(SystemStateCode.Shop_WaitInput, flow.state)
        assertTrue(blocker.message.contains("BeginHost.awaitShopInput"))
        assertEquals("Process.cs:2609-2640", blocker.evidence)
    }
}
