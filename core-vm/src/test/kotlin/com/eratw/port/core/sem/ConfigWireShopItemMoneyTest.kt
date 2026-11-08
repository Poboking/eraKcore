package com.eratw.port.core.sem

import com.eratw.port.core.host.NoopInputSink
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CONFIG-WIRE U4：PRINT_SHOPITEM 的金额格式取数点（MoneyLabel / MoneyFirst）。
 *
 * C# 语义：Process.cs:1236-1243 —— `MoneyFirst`（出厂 true，ConfigData.cs:160）时
 * `[{k}] {名}({MoneyLabel}{价})`，false 时金额在后；`MoneyLabel`（出厂 "$"，
 * ConfigData.cs:159）是 `Config.SetReplace` 读进来的 REPLACE 组条目（Config.cs:724/726）。
 *
 * 移植侧原状：两处执行路径都硬编码 `"$"` 且恒价在前（TODO(M4) 遗留），本波改为
 * 读 `ctx.config.moneyLabel/moneyFirst`（GameSession 装配喂入）。同 `%MONEYLABEL%`
 * 伪变量、MONEYSTR 函数同源（configChain.store 条目），不留第二把尺。
 *
 * 基准零漂移依据：语料 `CSV/_Replace.csv:7-9` 是 `お金の単位,￥` + `単位の位置,前`
 * （生效值 = ￥/前），但黄金基准运行的 CHARA-SHOP 节无在售项，PRINT_SHOPITEM 本身
 * 不触发 ⇒ 语句面读数不变；会触发的金额显示面（MONEYSTR / `%MONEYLABEL%`）在
 * 前一波已接 `store.moneyLabel`（同条目）且黄金绿。
 */
@Suppress("DEPRECATION")
class ConfigWireShopItemMoneyTest {

    private class ShopEnv(moneyLabel: String, moneyFirst: Boolean) {
        val store = VariableStore().apply {
            writeInt("ITEMSALES", longArrayOf(0L), 1L)
            writeInt("ITEMSALES", longArrayOf(1L), 0L)
            writeInt("ITEMSALES", longArrayOf(2L), 1L)
            // ITEMPRICE 是常量列（ITEM.CSV 第 3 列，ConstantData.cs:1803-1813），不能 writeInt；
            // 走与 ShopHostModelTest 同款的装载口（VariableStore.loadItemPrice）。
            loadItemPrice(longArrayOf(300L, 0L, 7777L))
            registry.csvTable("ITEM").set(0, "药")
            registry.csvTable("ITEM").set(2, "神器")
        }
        val console = FakeConsoleSink()
        val ctx = ExecContext(
            variables = store,
            eval = TestEvalContext(store),
            console = console,
            input = NoopInputSink,
            config = SemConfig(moneyLabel = moneyLabel, moneyFirst = moneyFirst),
        )
        val executor = StatementExecutor()

        fun printShopItem() {
            executor.execute(instr(FunctionCode.PRINT_SHOPITEM), ctx)
        }
    }

    /** printC 事件的文本列（剥掉事件壳与 alignRight 布尔）。 */
    private fun ShopEnv.cTexts(): List<String> =
        console.events.filter { it.startsWith("printC(") }
            .map { it.removePrefix("printC(").removeSuffix(")").substringBeforeLast(",") }

    @Test
    fun `MoneyFirst 为真时货币符在前`() {
        val env = ShopEnv("￥", moneyFirst = true)
        env.printShopItem()
        assertEquals(
            listOf("[0] 药(￥300)", "[2] 神器(￥7777)"),
            env.cTexts(),
            "Process.cs:1238（MoneyFirst=true 分支）",
        )
    }

    @Test
    fun `MoneyFirst 为假时金额在前`() {
        val env = ShopEnv("G", moneyFirst = false)
        env.printShopItem()
        assertEquals(
            listOf("[0] 药(300G)", "[2] 神器(7777G)"),
            env.cTexts(),
            "Process.cs:1242（MoneyFirst=false 分支）",
        )
    }

    @Test
    fun `出厂缺省是美元符且价在前`() {
        val env = ShopEnv("$", moneyFirst = true)
        env.printShopItem()
        assertEquals(
            listOf("[0] 药($300)", "[2] 神器($7777)"),
            env.cTexts(),
            "ConfigData.cs:159/:160 出厂 \"$\" + true（注册默认，非语料生效值）",
        )
    }
}
