package com.eratw.port.core.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M2-1 链接序钉：硬依赖缺一拒绝、软顺序提示可缺、无约束包保持输入序、依赖环整组拒绝。
 * 链接产物缓存指纹（ABI-8）要求同一集合两次定序得到同一条序。
 */
class ModuleLinkOrderTest {

    private fun pkg(
        id: String,
        requires: List<String> = emptyList(),
        loadAfter: List<String> = emptyList(),
    ): ModuleDescriptor = ModuleDescriptor(
        packageId = id,
        requires = requires,
        loadAfter = loadAfter,
    )

    private fun ids(list: List<ModuleDescriptor>): List<String> = list.map { it.packageId }

    @Test
    fun hardDependenciesComeFirstRegardlessOfInputOrder() {
        val a = pkg("a")
        val b = pkg("b", requires = listOf("a"))
        val c = pkg("c", requires = listOf("b", "a"))
        // 反序输入也必须得到 a → b → c。
        assertEquals(listOf("a", "b", "c"), ids(ModuleLinkOrder.plan(listOf(c, b, a))))
        assertEquals(listOf("a", "b", "c"), ids(ModuleLinkOrder.plan(listOf(a, b, c))))
    }

    @Test
    fun loadAfterIsSoftOrdering() {
        // b 在 a 后；c 的 loadAfter 指向不存在的包 ⇒ 忽略，不拒绝。
        val a = pkg("a")
        val b = pkg("b", loadAfter = listOf("a"))
        val c = pkg("c", loadAfter = listOf("missing"))
        assertEquals(listOf("a", "b", "c"), ids(ModuleLinkOrder.plan(listOf(b, a, c))))
        assertEquals(listOf("c", "a", "b"), ids(ModuleLinkOrder.plan(listOf(c, b, a))))
    }

    @Test
    fun independentPackagesKeepInputOrder() {
        val list = listOf(pkg("z"), pkg("a"), pkg("m"))
        assertEquals(listOf("z", "a", "m"), ids(ModuleLinkOrder.plan(list)))
        // 重复调用结果逐项相同（缓存指纹前提）。
        assertEquals(ids(ModuleLinkOrder.plan(list)), ids(ModuleLinkOrder.plan(list)))
    }

    @Test
    fun missingHardDependencyRejectsWholeGroup() {
        val e = assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(listOf(pkg("b", requires = listOf("a"))))
        }
        assertTrue(e.message!!.contains("缺少硬依赖"), e.message)
        assertTrue(e.message!!.contains("[a]"), e.message)
    }

    @Test
    fun cyclesAndDuplicatesAreRejected() {
        val cycle = assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(
                listOf(
                    pkg("a", requires = listOf("b")),
                    pkg("b", requires = listOf("a")),
                ),
            )
        }
        assertTrue(cycle.message!!.contains("依赖环"), cycle.message)
        assertTrue(cycle.message!!.contains("a") && cycle.message!!.contains("b"))

        assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(listOf(pkg("a", requires = listOf("a"))))
        }
        assertFailsWith<LinkOrderException> {
            ModuleLinkOrder.plan(listOf(pkg("a"), pkg("a")))
        }
    }

    @Test
    fun mixedHardAndSoftEdgesAreDeduplicated() {
        // 同一条边同时出现在 requires 与 loadAfter 里：只算一次，不会让 b 永远不就绪。
        val a = pkg("a")
        val b = pkg("b", requires = listOf("a"), loadAfter = listOf("a"))
        assertEquals(listOf("a", "b"), ids(ModuleLinkOrder.plan(listOf(b, a))))
        assertTrue(ModuleLinkOrder.plan(emptyList()).isEmpty())
    }
}
