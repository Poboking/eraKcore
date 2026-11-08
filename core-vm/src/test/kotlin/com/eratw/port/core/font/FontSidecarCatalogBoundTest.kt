package com.eratw.port.core.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * fix-varcapacity：`FontSidecarCatalog.resolved` 的**有界 LRU + 装配条目钉住**面。
 *
 * 出口判据 ③ 的 FontSidecar 半：逐出只许影响成本，不许影响结果。两条反面都要钉到：
 * 1. 正表条目过上限会被逐出，但逐出后再查按同一路径重建，**读数逐字不变**
 *    （`advancePixels` / `covers` / `hasOwnTable` 对同一码位前后一致）——
 *    这就是「字表对象身份没被别处当键用」的用例面证据：链上消费全部按内容取值。
 * 2. `put` 直挂的装配条目**永不逐出**：读取口缺席（reader=null 的夹具世界）时它们无从重建，
 *    逐出会把「可解析」打成「缺失」，那是语义变化而不是成本。
 * 3. 负缓存（null 条目 + missReasons）不参逐出，`reasonFor` 的诊断读数逐字不变。
 */
class FontSidecarCatalogBoundTest {

    private val sidecarText = buildString {
        appendLine("#family TestFam")
        appendLine("#unitsPerEm 1000")
        appendLine("0041 640 80")
        appendLine("0042 640 80")
        appendLine("3042 1200 0")
    }

    /** 对任何 sidecar 路径都给同一份表的读取口（脚本造变体名的最坏情形）。 */
    private fun anyReader(): FontSidecarReader = FontSidecarReader { _ -> sidecarText }

    @Suppress("UNCHECKED_CAST")
    private fun resolvedSize(catalog: FontSidecarCatalog): Int {
        val f = FontSidecarCatalog::class.java.getDeclaredField("resolved").apply { isAccessible = true }
        return (f.get(catalog) as Map<String, FontAdvanceTable?>).size
    }

    @Test
    fun `正表条目数被上限钳住 且逐出前后读数逐字不变`() {
        val catalog = FontSidecarCatalog(reader = anyReader())
        val variants = (0 until FontSidecarCatalog.MAX_RESOLVED_ENTRIES + 200)
            .map { "Var Fam $it" } // slug 折叠成 var-fam-N，各自独立入缓存、各自解一份整表
        val first = catalog.tableFor(variants[0])!!
        val firstAdvance = first.advancePixels(0x41, 20L)
        assertTrue(firstAdvance != null && firstAdvance > 0f)
        for (name in variants) {
            assertTrue(catalog.hasOwnTable(name), "变体名 $name 解析不出表")
        }
        // 旧实现在这里 = 712 条整表副本无界驻留；现在总量必须不超过声明上限。
        assertTrue(resolvedSize(catalog) <= FontSidecarCatalog.MAX_RESOLVED_ENTRIES,
            "resolved 越上限：${resolvedSize(catalog)}")
        // 被逐出的头部键再查：重建结果**逐字**相同（内容口径，不是实例口径）。
        val revived = catalog.tableFor(variants[0])!!
        assertEquals(first.family, revived.family)
        assertEquals(first.unitsPerEm, revived.unitsPerEm)
        assertEquals(firstAdvance, revived.advancePixels(0x41, 20L))
        assertEquals(first.codePoints, revived.codePoints)
        assertTrue(revived.covers(0x3042) && !revived.covers(0x1234))
    }

    @Test
    fun `put 直挂的装配条目不逐出 夹具世界里可解析性不动`() {
        val table = FontAdvanceTable.parse(sidecarText)
        // 夹具世界：没有读取口，表只有 put 这一条来路。
        val catalog = FontSidecarCatalog(reader = null)
        catalog.put(table, alias = "Alias Fam")
        // 负缓存洪泛（旧路径 256 条 null + 任何数量都会撞上限）。
        for (i in 0 until 1000) catalog.hasOwnTable("ghost $i")
        assertSame(table, catalog.tableFor(table.family), "装配表被逐出/替换：可解析性被动了")
        assertSame(table, catalog.tableFor("Alias Fam"), "别名实例被逐出")
        assertTrue(catalog.hasOwnTable(table.family))
        // 替代链的去重（身份判等）在钉住条目之间照旧成立。
        catalog.substituteFamily = "Alias Fam"
        val chain = catalog.chainFor(table.family)
        assertEquals(1, chain.size, "同一实例挂两名时必须仍去重成 1 跳")
    }

    @Test
    fun `装配表加变体洪泛 逐出只碰可重建条目`() {
        val table = FontAdvanceTable.parse(sidecarText) // family=TestFam
        val catalog = FontSidecarCatalog(reader = anyReader())
        catalog.put(table, alias = null)
        for (i in 0 until FontSidecarCatalog.MAX_RESOLVED_ENTRIES + 50) {
            catalog.hasOwnTable("Flood Fam $i")
        }
        // 装配条目还在（钉住），实例身份未变；可重建的变体条目被压在上限内。
        assertSame(table, catalog.tableFor("TestFam"))
        assertTrue(resolvedSize(catalog) <= FontSidecarCatalog.MAX_RESOLVED_ENTRIES)
    }

    @Test
    fun `负缓存与原因表成对 逐出不动诊断读数`() {
        val catalog = FontSidecarCatalog(reader = FontSidecarReader { null }) // 全部缺失
        val names = (0 until FontSidecarCatalog.MAX_MISS_CACHE_ENTRIES + 64).map { "miss $it" }
        for (n in names) assertEquals(null, catalog.tableFor(n))
        val early = catalog.reasonFor(names[0])
        assertTrue(early != null, "首个缺失名应有在册原因")
        // 再灌正表洪泛把 LRU 逼起来：null 条目不许被逐出（reasonFor 不许翻脸）。
        val posReader = anyReader()
        catalog.reader = posReader
        for (i in 0 until FontSidecarCatalog.MAX_RESOLVED_ENTRIES + 100) {
            catalog.hasOwnTable("Pos Fam $i")
        }
        assertEquals(early, catalog.reasonFor(names[0]), "缺失原因读数被逐出改写")
        assertTrue(catalog.reasonFor(names[10]) != null)
    }
}
