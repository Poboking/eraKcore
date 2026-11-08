package com.eratw.port.core.vars

import java.util.LinkedHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * fix-varcapacity：`VariableStore.upperNames` 名字归一记忆的**有界化**面。
 *
 * 出口判据 ③ 的 VariableStore 半：加界只许影响成本（逐出后重算一次 `uppercase()`），
 * 不许影响结果。论据（`upperNames` 属性 KDoc）：`upperOf` 的缓存值是 `name.uppercase()`
 * 的纯函数 —— 无宿主态、无计数器、与访问历史无关，所以逐出-重建逐字等价。
 * 用例刻意**超上限灌键**把逐出真跑起来，再对「已被逐出的键」复核归一结果与变量读写。
 */
class UpperNamesBoundTest {

    private fun idx(vararg v: Long) = v

    private class Access(store: VariableStore) {
        val field = VariableStore::class.java.getDeclaredField("upperNames").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val map: MutableMap<String, String> = field.get(store) as MutableMap<String, String>
        val upperOf = VariableStore::class.java.getDeclaredMethod("upperOf", String::class.java)
            .apply { isAccessible = true }

        fun call(store: VariableStore, name: String): String = upperOf.invoke(store, name) as String
    }

    @Test
    fun `upperNames 是 accessOrder 的有界表`() {
        val store = VariableStore()
        val a = Access(store)
        assertTrue(a.map is LinkedHashMap, "记忆表应为 LinkedHashMap（LRU 载体），实际 ${a.map.javaClass.name}")
        // accessOrder 内部字段反射会被 JDK 强封装拒（InaccessibleObjectException），
        // 改用「逐出受害者」判据区分 LRU 与插入序逐出。
        val keys = (0 until MAX_UPPER_NAMES).map { "lru_key_$it" }
        for (k in keys) a.call(store, k)
        assertEquals(MAX_UPPER_NAMES, a.map.size, "灌满到上限时逐出应恰好未触发")
        a.call(store, keys[0]) // 触碰头键：accessOrder 表把它挪到尾
        a.call(store, "extra_key_one_more") // 溢出触发逐出
        assertTrue(a.map.containsKey(keys[0]), "刚被访问的头键被逐 ⇒ 不是按访问序逐出（LRU 失效）")
        assertTrue(!a.map.containsKey(keys[1]), "keys[1] 必须是最久未碰的受害者")
    }

    @Test
    fun `超上限逐出后 upperOf 结果逐字等于现算 且热点结果不变`() {
        val store = VariableStore()
        val a = Access(store)
        // 灌过上限：MAX + 2048 个互异名字，逼真实逐出（不是「上限远够不着」的口头保证）。
        val firstNames = ArrayList<String>()
        for (i in 0 until MAX_UPPER_NAMES + 2048) {
            val n = "evict_me_$i"
            if (i % 512 == 0) firstNames += n
            assertEquals(n.uppercase(), a.call(store, n), "upperOf 第 $i 个键结果错")
        }
        assertTrue(a.map.size <= MAX_UPPER_NAMES, "逐出未生效：size=${a.map.size}")
        // 最久未碰的头部键此刻**必已被逐出**（灌入量超上限两千余条）：
        // 重读它们走「未命中 → 现算 → 重新入表」，结果必须与现算逐字相等。
        for (n in firstNames) {
            assertEquals(n.uppercase(), a.call(store, n), "被逐出的键重算结果漂了")
        }
        // 行为面：混大小写访问用户变量在逐出前后读数不变。
        store.declareUserVar(DimParser.parse("#DIM", "GVBOUND, 3", false))
        store.writeInt("GVBOUND", idx(0), 41L)
        assertEquals(41L, store.readInt("gvbound", idx(0)))
        for (i in 0 until MAX_UPPER_NAMES + 3000) a.call(store, "flood_$i")
        assertEquals(41L, store.readInt("GvBound", idx(0)), "逐出洪泛后大小写归一路径读数变了")
    }

    @Test
    fun `单槽身份备忘与有界表共存 结果不变`() {
        // lastUpperIn/Out 单槽只按引用短路（===），与表逐出无关；这里钉「关掉单槽」的
        // 等效路径：每次换一个新实例串（内容同）走表命中/重算，结果恒等。
        val store = VariableStore()
        val a = Access(store)
        val base = "hotvar"
        val viaFresh = ArrayList<String>()
        for (i in 0 until 4096) {
            // StringBuilder.toString() 每次给新实例，内容相同 ⇒ 单槽 === 不命中、走表。
            val s = StringBuilder("hot").append("var").toString()
            assertEquals(base.uppercase(), s.uppercase())
            viaFresh += a.call(store, s)
        }
        assertTrue(viaFresh.all { it == "HOTVAR" }, "新实例路径的结果必须恒等")
    }
}
