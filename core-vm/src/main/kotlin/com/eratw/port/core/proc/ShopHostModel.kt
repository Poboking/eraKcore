package com.eratw.port.core.proc

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.vars.VariableStore

/**
 * 商店输入处理（`Process.shopWaitInput`，Process.cs:2609-2640）依赖的**宿主侧模型**。
 *
 * 原版这组语义全长在 `VariableEvaluator`（变量池）上；移植侧 BEGIN 状态机
 * （[BeginFlow]）没有变量池访问通道，故经 [BeginHost.shopHostModel] 注入。
 * 逐条证据链：
 * - [maxShopItem]：`Config.MaxShopItem`（Process.cs:2611 的比较上界；
 *   Config.cs:205 / :724，条目缺省 100，ConfigData.cs:162）。
 * - [itemSales]：VariableEvaluator.cs:1952-1966。
 * - [buyItem]：VariableEvaluator.cs:1968-1988。
 * - [writeResult]：`vEvaluator.RESULT = systemResult`（Process.cs:2636；
 *   RESULT 属性即 DataIntegerArray[10] 的 0 号元素，VariableEvaluator.cs:40-48）。
 */
interface ShopHostModel {
    /** `Config.MaxShopItem`（Process.cs:2611 的比较上界）。 */
    fun maxShopItem(): Int

    /** `VariableEvaluator.ItemSales`（VariableEvaluator.cs:1952-1966）。 */
    fun itemSales(itemNo: Int): Boolean

    /** `VariableEvaluator.BuyItem`（VariableEvaluator.cs:1968-1988）。 */
    fun buyItem(itemNo: Int): Boolean

    /** `vEvaluator.RESULT = systemResult`（Process.cs:2636）。 */
    fun writeResult(value: Long)
}

/**
 * 变量池版 [ShopHostModel]：直接读写 [VariableStore] 的系统变量与 CSV 名字表，
 * 语义逐行对照 VariableEvaluator.cs:1952-1988。宿主装配层（GameSession）用它把
 * 商店输入接进真机；单测也用它对着真变量池钉语义。
 *
 * @param store 变量池（ITEMSALES / ITEMPRICE / MONEY / ITEM / BOUGHT / RESULT）
 * @param config 生效配置（只读 [ConfigKey.MaxShopItem]）
 */
class StoreShopHostModel(
    private val store: VariableStore,
    private val config: ConfigStore,
) : ShopHostModel {

    override fun maxShopItem(): Int = config.itemOf(ConfigKey.MaxShopItem).int()

    override fun itemSales(itemNo: Int): Boolean {
        // VariableEvaluator.cs:1954-1959 —— 负数、越过 ITEMSALES 长度或越过
        // ITEMNAME 名单长度，一律 false。
        val salesLen = store.getLength("ITEMSALES", -1)
        val nameTable = store.registry.csvTable("ITEM")
        if (itemNo < 0 || itemNo >= salesLen || itemNo >= nameTable.size) return false
        // :1961-1965 —— ITEMSALES 为 0 即缺货；非 0 还要看名字格是否登记。
        if (store.readInt("ITEMSALES", longArrayOf(itemNo.toLong())) == 0L) return false
        // :1963 的 `csvNameList[num] != null`：移植侧 CsvNameTable.get 把 null 读成
        // 空串（VariableRegistry.kt:16），故以「非空」判定「已登记名字」。
        // TODO(verify)：若某格被显式写成空串名字，原版算在售而这里算缺货。已核
        // **不可达**：写 ITEM 名字格的唯一路径是 CsvLoader.loadNameTable 原样落
        // Item.csv 第二列（CsvLoader.kt:290 取 cols[1]、:303-306 回落表，对应
        // ConstantData.cs:1802 的 array[num2] = 第二列），别名 .als 只进关键字
        // 反查不写名字格（CsvLoader.kt:345-352）；本语料 CSV/Item.csv 347 行
        // 第二列无一为空（grep '^[0-9]*,\s*,' 零命中）。原版的 null/空串二分在
        // 本语料不会产生可观察差异，故保留本口径；若将来换语料需复跑该 grep。
        return nameTable[itemNo].isNotEmpty()
    }

    override fun buyItem(itemNo: Int): Boolean {
        // VariableEvaluator.cs:1970-1973 —— 先过 ItemSales。
        if (!itemSales(itemNo)) return false
        // :1974-1978 —— 原版读 constant.ItemPrice；移植侧 ITEMPRICE 变量就是该
        // 常量表的复制（VariableStore.resetValues 的 ITEMPRICE 拷贝段，
        // spec: VariableData.cs:3395-3397），同一份读数。
        if (itemNo >= store.getLength("ITEMPRICE", -1)) return false
        val price = store.readInt("ITEMPRICE", longArrayOf(itemNo.toLong()))
        // :1980-1983 —— 钱不够不成交，MONEY 不动。
        val money = store.readInt("MONEY", longArrayOf(0L))
        if (money < price) return false
        // :1984-1986 —— 成交三连：扣钱、ITEM 自增、BOUGHT 记账。
        store.writeInt("MONEY", longArrayOf(0L), money - price)
        val slot = longArrayOf(itemNo.toLong())
        store.writeInt("ITEM", slot, store.readInt("ITEM", slot) + 1)
        store.writeInt("BOUGHT", longArrayOf(0L), itemNo.toLong())
        return true
    }

    override fun writeResult(value: Long) {
        // Process.cs:2636 —— RESULT:0 写入（原版 RESULT 属性的 setter，
        // VariableEvaluator.cs:46-48）。
        store.writeInt("RESULT", longArrayOf(0L), value)
    }
}
