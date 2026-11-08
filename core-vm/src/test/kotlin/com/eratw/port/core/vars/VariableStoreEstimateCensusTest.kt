package com.eratw.port.core.vars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EP3/L10（census-histo）：`VariableStore` 那三枚**观测口**的公式对账。
 *
 * 病（一句话）：OOM 报告的 Java 堆分项账面只解释了堆的 9.6~22.4%，剩下八成说不清是谁 ——
 * 其中最大一枚是 `LiveSetCensus.charaIntBytes` 把 `estimateBytesPerCharacter()`（**单个角色**的
 * 整数格字节，KDoc 原话）当**总量**入账，本臂少报 ≈38.4 MiB
 * （基准：`.local/heap-phase/probe-20261102-161404.census.txt` 的
 * `RAW ... charaIntBytes=208768` + `RUN ... charaCount/Cap=168/193` 与 `HISTO` 的
 * `long[] 59,091,016 B`）。
 *
 * 本类钉的是「修账面所依赖的那两条结构等式」，不是 census 的成文形状（那条在
 * `LiveSetCensusTest`）：
 * 1. **角色整数列的实际驻留 = 每行宽 × 容量行**（`characterIntResidentBytes()` 读逐列
 *    `data.size`，`CharacterTable.kt:75` 的分配形状是它的结构依据）⇒ 这条成立才允许
 *    census 把「容量」当乘数；同时给出反例形状：× `count` 在有空尾时**必然少报**。
 * 2. **全局整数列按需分配**：声明尺寸（`estimateGlobalIntBytes()`，`VariableStore.kt:3170`）
 *    与实际分配（`globalIntResidentBytes()`，本路新增）是两笔，且实占 ≤ 声明恒成立。
 *
 * 纯 JVM、不吃语料：只建默认 [VariableStore]（`VariableSizes` 出厂档）。
 */
class VariableStoreEstimateCensusTest {

    /** 逐列 `data.size` 求和 = 每行宽 × 容量行（census 的新口径依赖这条等式）。 */
    @Test
    fun `角色整数列实际驻留恒等于每行宽乘容量行`() {
        val store = VariableStore()
        for (n in 0 until 12) {
            if (n > 0) store.characters.addBare()
            val perChar = store.estimateBytesPerCharacter()
            val capacity = store.characters.allocatedCapacity.toLong()
            assertEquals(
                perChar * capacity,
                store.characterIntResidentBytes(),
                "count=${store.characters.count} capacity=$capacity 时实际列数组必须正好容量行长",
            )
        }
    }

    /** 反例形状：有空尾时「× count」比实际驻留少 (capacity − count) 行 —— 这就是少乘少报的量。 */
    @Test
    fun `按角色数乘会少报空尾行且少报量恰好是空尾`() {
        val store = VariableStore()
        repeat(5) { store.characters.addBare() }
        val perChar = store.estimateBytesPerCharacter()
        val capacity = store.characters.allocatedCapacity.toLong()
        val count = store.characters.count.toLong()
        assertTrue(capacity > count, "fixture 需要空尾：容量 $capacity vs 角色数 $count")
        assertEquals(
            (capacity - count) * perChar,
            store.characterIntResidentBytes() - perChar * count,
            "少报量 = 空尾行 × 每行宽（L7 那臂是 25 行 ≈5 MiB）",
        )
    }

    /** 用户 `#DIM CHARADATA` 追加列后等式仍成立（新列按当前容量分配，`CharacterTable.kt:288-291`）。 */
    @Test
    fun `追加用户角色整数列后每行宽与实际驻留同步增长`() {
        val store = VariableStore()
        repeat(3) { store.characters.addBare() }
        val before = store.characterIntResidentBytes()
        val beforePerChar = store.estimateBytesPerCharacter()
        store.characters.appendIntColumn(intArrayOf(7))
        val capacity = store.characters.allocatedCapacity.toLong()
        assertEquals(
            before + 7L * capacity * 8L,
            store.characterIntResidentBytes(),
            "新列 = 7 格 × 容量行 × 8 B，一次都不许多",
        )
        assertTrue(store.estimateBytesPerCharacter() > beforePerChar, "每行宽必须随新列增长")
        assertEquals(
            store.estimateBytesPerCharacter() * capacity,
            store.characterIntResidentBytes(),
            "追加列之后主等式依旧成立",
        )
    }

    /** 复位链把列容量还给起步档 ⇒ 实际驻留跟着回落到「一行宽」（`CharacterTable.clear`）。 */
    @Test
    fun `角色表清空归还列容量后实际驻留回到起步档`() {
        val store = VariableStore()
        repeat(9) { store.characters.addBare() }
        assertTrue(store.characters.allocatedCapacity > 1, "fixture 应已扩容，实际 ${store.characters.allocatedCapacity}")
        store.characters.clear()
        assertEquals(1, store.characters.allocatedCapacity, "clear() 归还到起步容量")
        assertEquals(
            store.estimateBytesPerCharacter(),
            store.characterIntResidentBytes(),
            "count=0 不等于零字节：起步容量那 1 行仍然占着堆（占位不是垃圾）",
        )
    }

    /**
     * 全局整数列按需分配的**实测形状**（出厂档 `VariableSizes`）：新建 store 的 `init { resetValues() }`
     * （`VariableStore.kt:366-368`）只把**有初值**的那些列现分配 ⇒ 实占既不是 0（那是「全部没写过」
     * 的假形状，会证伪）也不是声明尺寸（那是「全部写过」的高估）。census 里那对
     * 「声明 / 实占」就是为了把这一段说清：本臂出厂档实占 448,000 B / 声明 17,072,000 B。
     */
    @Test
    fun `全局整数列实占严格小于声明尺寸`() {
        val store = VariableStore()
        val declared = store.estimateGlobalIntBytes()
        val resident = store.globalIntResidentBytes()
        assertTrue(declared > 0L, "声明尺寸应为正：$declared")
        assertTrue(
            resident in 1L until declared,
            "按需分配 ⇒ 实占 $resident 必须严格落在 (0, 声明 $declared) 内，否则这把尺没有意义",
        )
    }

    /**
     * 写没写过的列 ⇒ 实占增长；重复写同一列 ⇒ 一分不涨（读的是 `LongArray.length`，
     * 不是写入次数；`intCol` 的分配只在缺席时发生，`VariableStore.kt:128-134`）。
     */
    @Test
    fun `写全局整数列只增长一次且实占恒不超过声明`() {
        val store = VariableStore()
        val base = store.globalIntResidentBytes()
        for (name in listOf("A", "B", "C", "D", "E", "F")) store.writeInt(name, longArrayOf(0), 1L)
        val after = store.globalIntResidentBytes()
        assertTrue(after > base, "写过 A-F ⇒ 实占必须增长：$base → $after")
        assertTrue(after <= store.estimateGlobalIntBytes(), "实占 $after 不得超过声明")
        store.writeInt("A", longArrayOf(1), 2L)
        store.writeInt("A", longArrayOf(2), 3L)
        assertEquals(after, store.globalIntResidentBytes(), "同一列重复写不追加分配")
    }
}
