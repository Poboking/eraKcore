package com.eratw.port.core.proc

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 商店输入处理（`Process.shopWaitInput`，Process.cs:2609-2640）+ 宿主商品模型
 * （VariableEvaluator.cs:1952-1988 的 ItemSales / BuyItem，Process.cs:2611 的
 * MaxShopItem 上界，:2636 的 RESULT 写入）。
 *
 * 用例走**真变量池**（[StoreShopHostModel] + 裸 [VariableStore]），只对
 * BeginCallPort / BeginHost 用假体 —— 变量断言（MONEY 扣减、ITEM 自增、BOUGHT
 * 记账、RESULT 写入）因此直接对着池子读，不经过任何转述层。
 */
class ShopHostModelTest {

    // ------------------------------------------------------------------
    // 假体
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
                // 假体复刻生产侧文案（Lang.Error.FuncIsNotFound，zhs:736 / Lang.cs:3126；
                // 真抛点 Process.cs:2090-2099 的 force 分支），不得留旧自造串。
                if (force) throw CodeEE("找不到函数\"@$name\"")
                return false
            }
            called.add(name)
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

    private class FakeHost(
        override val shopHostModel: ShopHostModel? = null,
    ) : BeginHost {
        var shopInput: Long? = null
        var temporaryLine = false
        val written = ArrayList<String>()

        override fun awaitShopInput(): Long? = shopInput
        override fun writeLine(text: String) {
            written.add(text)
        }
        override fun lastLineIsTemporary(): Boolean = temporaryLine
    }

    // ------------------------------------------------------------------
    // 变量池基座
    // ------------------------------------------------------------------

    /**
     * 商店基座（读数口径见各用例）：
     * MONEY=1000；ITEMSALES[0..1]=1、[3]=1；ITEMPRICE[0]=300（买得起）、
     * [1]=999999（买不起）；ITEM 名字表 0/1 有名、3 留空（VariableEvaluator.cs:1963
     * 的 null 名字分支）；2 等未登记的格子 ITEMSALES 恒 0（缺货）。
     */
    private fun shopStore(): VariableStore = VariableStore().apply {
        writeInt("MONEY", longArrayOf(0L), 1000L)
        writeInt("ITEMSALES", longArrayOf(0L), 1L)
        writeInt("ITEMSALES", longArrayOf(1L), 1L)
        writeInt("ITEMSALES", longArrayOf(3L), 1L)
        writeInt("BOUGHT", longArrayOf(0L), -1L)
        loadItemPrice(longArrayOf(300L, 999999L))
        registry.csvTable("ITEM").set(0, "药")
        registry.csvTable("ITEM").set(1, "神器")
    }

    /**
     * 状态直落 Shop_WaitInput（原版 `endCallShowShop` 的落点，Process.cs:2603-2607），
     * 模型用真变量池版 [StoreShopHostModel] —— 与生产装配层该传给 ConsoleBeginHost 的同款。
     */
    private fun shopFlow(
        calls: FakeCalls,
        shopInput: Long?,
        withModel: Boolean = true,
        store: VariableStore = shopStore(),
        config: ConfigStore = ConfigStore(),
    ): Pair<BeginFlow, FakeHost> {
        val host = FakeHost(if (withModel) StoreShopHostModel(store, config) else null)
        host.shopInput = shopInput
        val flow = BeginFlow(calls, host)
        flow.enterState(SystemStateCode.Shop_WaitInput)
        return flow to host
    }

    // ------------------------------------------------------------------
    // 买入成功（Process.cs:2613-2623）
    // ------------------------------------------------------------------

    @Test
    fun `买入成功扣钱加货记 BOUGHT 并调 EVENTBUY`() {
        val calls = FakeCalls().apply { events += "EVENTBUY" }
        val store = shopStore()
        val (flow, host) = shopFlow(calls, shopInput = 0L, store = store)

        val step = flow.step()

        assertEquals(StepOutcome.CALLED, step.outcome, "Process.cs:2618 callFunction(EVENTBUY)")
        assertEquals("EVENTBUY", step.calledFunction)
        assertEquals(listOf("EVENTBUY"), calls.called)
        assertEquals(SystemStateCode.Shop_CallEventBuy, flow.state, "Process.cs:2617")
        // VariableEvaluator.cs:1984-1986 —— 扣钱、ITEM 自增、BOUGHT 记账。
        assertEquals(700L, store.readInt("MONEY", longArrayOf(0L)), "1000 买 300 的货")
        assertEquals(1L, store.readInt("ITEM", longArrayOf(0L)), "VariableEvaluator.cs:1985")
        assertEquals(0L, store.readInt("BOUGHT", longArrayOf(0L)), "VariableEvaluator.cs:1986")
        assertEquals(0L, calls.systemResultValue, "交付值写进 systemResult（Process.cs:426-430）")
        assertTrue(host.written.isEmpty(), "成功路径不出临时行")
    }

    @Test
    fun `买入成功但 EVENTBUY 缺失时切 CallEventBuy 报 NOT_CALLED 由下一步承接`() {
        val calls = FakeCalls() // events 不含 EVENTBUY
        val store = shopStore()
        val (flow, host) = shopFlow(calls, shopInput = 0L, store = store)

        val step = flow.step()

        assertEquals(StepOutcome.NOT_CALLED, step.outcome, "force=false 的 event 缺失")
        assertEquals(SystemStateCode.Shop_CallEventBuy, flow.state, "Process.cs:2617 先切状态")
        assertEquals(700L, store.readInt("MONEY", longArrayOf(0L)), "成交已生效（:1984）")
        // 原版 :2620 当场 endCallEventBuy()；移植侧拆步 —— 下一步就是 endCallEventBuy。
        host.temporaryLine = true
        val next = flow.step()
        assertEquals("endCallEventBuy", next.handler, "Process.cs:2642-2653")
        assertEquals(SystemStateCode.Shop_CallShowShop, flow.state, "临时行 → 重显商店（:2644-2653）")
        assertEquals(StepOutcome.CONTINUE, next.outcome)
    }

    // ------------------------------------------------------------------
    // 钱不够 / 缺货（Process.cs:2624-2632）
    // ------------------------------------------------------------------

    @Test
    fun `钱不够打 MONEY不足 回 Shop_WaitInput 且 MONEY 不动`() {
        val calls = FakeCalls()
        val store = shopStore()
        val (flow, host) = shopFlow(calls, shopInput = 1L, store = store) // 价 999999 > 钱 1000

        val step = flow.step()

        assertEquals(StepOutcome.CONTINUE, step.outcome)
        assertEquals(SystemStateCode.Shop_WaitInput, flow.state, "endCallShowShop（:2632→:2603-2607）")
        assertEquals(1000L, store.readInt("MONEY", longArrayOf(0L)), ":1980-1983 不成交不扣钱")
        assertEquals(0L, store.readInt("ITEM", longArrayOf(1L)))
        assertEquals(-1L, store.readInt("BOUGHT", longArrayOf(0L)), "BOUGHT 不记账")
        assertEquals(listOf("MONEY不足"), host.written, "Lang.Error.NotEnoughMoney（zhs xml:742）")
        assertTrue(calls.called.isEmpty(), "不触发 EVENTBUY")
    }

    @Test
    fun `未登记商品打 售罄 回 Shop_WaitInput`() {
        val calls = FakeCalls()
        val store = shopStore()
        val (flow, host) = shopFlow(calls, shopInput = 2L, store = store) // ITEMSALES[2] = 0

        val step = flow.step()

        assertEquals(StepOutcome.CONTINUE, step.outcome)
        assertEquals(SystemStateCode.Shop_WaitInput, flow.state)
        assertEquals(listOf("售罄"), host.written, "Lang.Error.OutOfStock（zhs xml:743）")
        assertTrue(calls.called.isEmpty())
    }

    @Test
    fun `ITEMSALES 在售但名字表缺格也算售罄`() {
        // VariableEvaluator.cs:1961-1964 —— ITEMSALES 非 0 时看 csvNameList 是否为 null。
        val calls = FakeCalls()
        val store = shopStore()
        val (flow, host) = shopFlow(calls, shopInput = 3L, store = store) // 在售但无名字格

        val step = flow.step()

        assertEquals(StepOutcome.CONTINUE, step.outcome)
        assertEquals(listOf("售罄"), host.written)
        assertEquals(1000L, store.readInt("MONEY", longArrayOf(0L)))
    }

    // ------------------------------------------------------------------
    // USERSHOP 分支（Process.cs:2634-2639）
    // ------------------------------------------------------------------

    @Test
    fun `越界输入写 RESULT 调 USERSHOP 切 CallEventBuy`() {
        val calls = FakeCalls().apply { functions += "USERSHOP" }
        val store = shopStore()
        val (flow, _) = shopFlow(calls, shopInput = 100L, store = store) // == MaxShopItem 缺省

        val step = flow.step()

        assertEquals(StepOutcome.CALLED, step.outcome, "USERSHOP 是 force 调用（:2637）")
        assertEquals("USERSHOP", step.calledFunction)
        assertEquals(listOf("USERSHOP"), calls.called)
        assertEquals(SystemStateCode.Shop_CallEventBuy, flow.state, "Process.cs:2638")
        // Process.cs:2636 —— vEvaluator.RESULT = systemResult（RESULT:0，
        // VariableEvaluator.cs:40-48）。
        assertEquals(100L, store.readInt("RESULT", longArrayOf(0L)))
        assertEquals(100L, calls.systemResultValue)
    }

    @Test
    fun `负数输入也走 USERSHOP`() {
        val calls = FakeCalls().apply { functions += "USERSHOP" }
        val store = shopStore()
        val (flow, _) = shopFlow(calls, shopInput = -1L, store = store) // :2611 的 >= 0 不成立

        val step = flow.step()

        assertEquals(StepOutcome.CALLED, step.outcome)
        assertEquals(listOf("USERSHOP"), calls.called)
        assertEquals(SystemStateCode.Shop_CallEventBuy, flow.state)
        assertEquals(-1L, store.readInt("RESULT", longArrayOf(0L)))
    }

    @Test
    fun `USERSHOP 缺失时 force 调用抛 CodeEE`() {
        val calls = FakeCalls() // functions 不含 USERSHOP
        val (flow, _) = shopFlow(calls, shopInput = 100L)

        assertFailsWith<CodeEE> { flow.step() }
    }

    // ------------------------------------------------------------------
    // 模型直检（VariableEvaluator.cs:1952-1966 的边界）
    // ------------------------------------------------------------------

    @Test
    fun `ItemSales 的负数与越界都是 false`() {
        val model = StoreShopHostModel(shopStore(), ConfigStore())
        assertFalse(model.itemSales(-1), "VariableEvaluator.cs:1956 的 itemNo < 0")
        // ITEMSALES / ITEMNAME 表长由系统布局给出（远大于 4），取一个必然越界的大号。
        assertFalse(model.itemSales(1 shl 20), "VariableEvaluator.cs:1956 的长度越界")
        assertTrue(model.itemSales(0), "在售 + 已登记名字（:1961-1964）")
    }

    @Test
    fun `ItemSales 的 ITEMSALES 为 0 即缺货`() {
        // VariableEvaluator.cs:1961-1965 —— ITEMSALES[num] == 0 直接 false，
        // 名字格登记与否不再参与（2 号格无名且 ITEMSALES[2] = 0）。
        val model = StoreShopHostModel(shopStore(), ConfigStore())
        assertFalse(model.itemSales(2), "基座 ITEMSALES[2] = 0")
    }

    @Test
    fun `ItemSales 在售但名字格未登记也算缺货`() {
        // VariableEvaluator.cs:1963 —— ITEMSALES 非 0 时判 csvNameList[num] != null；
        // 基座 3 号格 ITEMSALES[3] = 1 而名字表只有 0/1 两格。
        val model = StoreShopHostModel(shopStore(), ConfigStore())
        assertFalse(model.itemSales(3), "未登记名字格 → 缺货")
        assertTrue(model.itemSales(0), "已登记名字格 → 在售")
    }

    @Test
    fun `BuyItem 成交三连 扣钱自增记账`() {
        val store = shopStore().apply { writeInt("ITEM", longArrayOf(0L), 7L) }
        val model = StoreShopHostModel(store, ConfigStore())

        assertTrue(model.buyItem(0))

        // VariableEvaluator.cs:1984-1986 —— MONEY 扣价、ITEM 自增、BOUGHT 记账。
        assertEquals(700L, store.readInt("MONEY", longArrayOf(0L)), "1000 - ITEMPRICE[0]=300")
        assertEquals(8L, store.readInt("ITEM", longArrayOf(0L)), "7 + 1")
        assertEquals(0L, store.readInt("BOUGHT", longArrayOf(0L)))
    }

    @Test
    fun `BuyItem 钱刚刚好也成交 扣到 0`() {
        // VariableEvaluator.cs:1980 的判定是 `MONEY < itemPrice[num]` —— 相等不成立，成交。
        val store = shopStore().apply { writeInt("MONEY", longArrayOf(0L), 300L) }
        val model = StoreShopHostModel(store, ConfigStore())

        assertTrue(model.buyItem(0))

        assertEquals(0L, store.readInt("MONEY", longArrayOf(0L)))
        assertEquals(1L, store.readInt("ITEM", longArrayOf(0L)))
        assertEquals(0L, store.readInt("BOUGHT", longArrayOf(0L)))
    }

    @Test
    fun `BuyItem 钱不够分文不动`() {
        val store = shopStore().apply { writeInt("ITEM", longArrayOf(1L), 5L) }
        val model = StoreShopHostModel(store, ConfigStore())

        assertFalse(model.buyItem(1), "ITEMPRICE[1]=999999 > MONEY=1000")

        // VariableEvaluator.cs:1980-1983 —— 不成交即三连都不发生。
        assertEquals(1000L, store.readInt("MONEY", longArrayOf(0L)))
        assertEquals(5L, store.readInt("ITEM", longArrayOf(1L)))
        assertEquals(-1L, store.readInt("BOUGHT", longArrayOf(0L)))
    }

    @Test
    fun `BuyItem 缺货商品不成交`() {
        // VariableEvaluator.cs:1970-1973 —— 先过 ItemSales，2 号格 ITEMSALES = 0。
        val store = shopStore()
        val model = StoreShopHostModel(store, ConfigStore())

        assertFalse(model.buyItem(2))
        assertFalse(model.buyItem(3), "3 号格在售但名字格未登记，同样过不了 ItemSales")

        // VariableEvaluator.cs:1970-1973 —— ItemSales 不过即后续三步都不发生。
        assertEquals(1000L, store.readInt("MONEY", longArrayOf(0L)))
        assertEquals(0L, store.readInt("ITEM", longArrayOf(0L)))
        assertEquals(0L, store.readInt("ITEM", longArrayOf(3L)))
        assertEquals(-1L, store.readInt("BOUGHT", longArrayOf(0L)))
    }

    @Test
    fun `writeResult 只写 RESULT 的 0 号元素`() {
        val store = shopStore()
        val model = StoreShopHostModel(store, ConfigStore())

        model.writeResult(42L)

        // VariableEvaluator.cs:46-49 —— RESULT setter 即 DataIntegerArray[10][0]。
        assertEquals(42L, store.readInt("RESULT", longArrayOf(0L)))
        assertEquals(0L, store.readInt("RESULT", longArrayOf(1L)), "不动 1 号元素")
    }

    @Test
    fun `MaxShopItem 读生效配置`() {
        // ConfigStore() 缺省 = ConfigSpec 的缺省 100（ConfigData.cs:162）。
        assertEquals(100, StoreShopHostModel(shopStore(), ConfigStore()).maxShopItem())
    }

    @Test
    fun `MaxShopItem 取配置原值 边界不含等号`() {
        // Process.cs:2611 —— `systemResult >= 0 && systemResult < Config.MaxShopItem`
        // 才进成交判定；Config.cs:724 读条目原值（无夹取），这里把上界改小到 1。
        val config = ConfigStore().apply {
            itemOf(ConfigKey.MaxShopItem).assign(ConfigValue.IntValue(1))
        }
        assertEquals(1, StoreShopHostModel(shopStore(), config).maxShopItem())

        // == max（1）：不进成交分支，落 USERSHOP + RESULT（Process.cs:2634-2639）。
        val blockedCalls = FakeCalls().apply { functions += "USERSHOP" }
        val blockedStore = shopStore()
        val (blockedFlow, _) = shopFlow(blockedCalls, shopInput = 1L, store = blockedStore, config = config)
        val blocked = blockedFlow.step()
        assertEquals(StepOutcome.CALLED, blocked.outcome)
        assertEquals("USERSHOP", blocked.calledFunction)
        assertEquals(1L, blockedStore.readInt("RESULT", longArrayOf(0L)), "Process.cs:2636")
        assertEquals(1000L, blockedStore.readInt("MONEY", longArrayOf(0L)), "未碰成交三连")

        // < max（0）：进成交分支，事件买入（Process.cs:2613-2623）。
        val tradeCalls = FakeCalls().apply { events += "EVENTBUY" }
        val tradeStore = shopStore()
        val (tradeFlow, _) = shopFlow(tradeCalls, shopInput = 0L, store = tradeStore, config = config)
        val trade = tradeFlow.step()
        assertEquals(StepOutcome.CALLED, trade.outcome)
        assertEquals("EVENTBUY", trade.calledFunction)
        assertEquals(700L, tradeStore.readInt("MONEY", longArrayOf(0L)))
    }

    // ------------------------------------------------------------------
    // 未接模型（既有真机堵点的回归钉）
    // ------------------------------------------------------------------

    @Test
    fun `shopHostModel 未接时商店输入落 BLOCKED`() {
        val calls = FakeCalls()
        val (flow, _) = shopFlow(calls, shopInput = 0L, withModel = false)

        val step = flow.step()

        assertEquals(StepOutcome.BLOCKED, step.outcome)
        assertNull(step.calledFunction)
        assertEquals(SystemStateCode.Shop_WaitInput, flow.state)
        assertTrue(calls.called.isEmpty())
        assertEquals(
            "ItemSales/BuyItem 属宿主商品模型（Process.cs:2611-2639）；" +
                "BeginHost.shopHostModel 未接",
            step.blocker?.message,
        )
    }
}
