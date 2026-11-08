package com.eratw.port.render.font

import kotlin.test.Test
import kotlin.test.assertSame

/**
 * [ResolvedFontFamilyCache] 的缓存语义钉子。
 *
 * 断言的是**与 [BundledFontFamilies.resolve] 的可观察等价**（族实例逐调用一致）加上三条
 * 缓存自身的不变量：空白直返默认族、同一请求名两次返回同一实例、表满作废后仍回答正确。
 * 这里不测性能，只测「缓存不会把族搞错」—— 热路径上的错误复用会直接表现为整屏换错字体。
 */
class ResolvedFontFamilyCacheTest {

    @Test
    fun `null 与空白返回默认族的同一实例`() {
        val cache = ResolvedFontFamilyCache()
        for (name in listOf(null, "", "   ", "　")) {
            assertSame(
                BundledFontFamilies.default,
                cache.resolve(name),
                "字体名 ${name ?: "<null>"} 应直返默认族实例",
            )
        }
        // 反复取值仍是同一实例（空白名不入表，但不改变结果）
        assertSame(BundledFontFamilies.default, cache.resolve(null))
        assertSame(BundledFontFamilies.default, cache.resolve("  "))
    }

    @Test
    fun `同一非空族名两次返回同一登记实例`() {
        val cache = ResolvedFontFamilyCache()
        val first = cache.resolve("Fusion Pixel 12px")
        val second = cache.resolve("Fusion Pixel 12px")
        assertSame(BundledFontFamilies.FUSION_PIXEL, first, "应命中登记表里的 Fusion 实例")
        assertSame(first, second, "第二次命中缓存必须返回同一实例")
        // 大小写不同是**不同键**，但值仍折到同一登记实例（与 resolve 语义一致）
        assertSame(BundledFontFamilies.FUSION_PIXEL, cache.resolve("fusion pixel 12PX"))
    }

    @Test
    fun `超过 maxEntries 后仍逐名正确`() {
        val cache = ResolvedFontFamilyCache(maxEntries = 2)
        // 前两个各占一格；第三个触发整表 clear 再登记
        assertSame(BundledFontFamilies.SAITAMAAR, cache.resolve("Saitamaar"))
        assertSame(BundledFontFamilies.UNIFONT, cache.resolve("Unifont"))
        assertSame(BundledFontFamilies.FUSION_PIXEL, cache.resolve("Fusion Pixel 12px"))
        // 作废后重新登记，仍然按名回答（顺序无关、不串族）
        assertSame(BundledFontFamilies.SAITAMAAR, cache.resolve("Saitamaar"))
        assertSame(BundledFontFamilies.FUSION_PIXEL, cache.resolve("Fusion Pixel 12px"))
        // 再塞一个不同的名字触发第二次 clear，之后逐名取值依旧正确
        assertSame(BundledFontFamilies.MISAKI, cache.resolve("MisakiGothic"))
        assertSame(BundledFontFamilies.UNIFONT, cache.resolve("Unifont"))
        assertSame(BundledFontFamilies.SAITAMAAR, cache.resolve("Saitamaar"))
    }

    @Test
    fun `未知名与 PC 别名都回默认族`() {
        val cache = ResolvedFontFamilyCache()
        for (name in listOf("NoSuchFont", "ＭＳ ゴシック", "黑体", "MS P Gothic")) {
            assertSame(
                BundledFontFamilies.default,
                cache.resolve(name),
                "$name 应回落默认族",
            )
        }
    }

    @Test
    fun `不同请求名不会互相串族`() {
        val cache = ResolvedFontFamilyCache()
        // 交替访问不同族，检的是「键没被折叠/覆盖」
        repeat(3) {
            assertSame(BundledFontFamilies.MISAKI, cache.resolve("MisakiGothic"))
            assertSame(BundledFontFamilies.ARK_PIXEL, cache.resolve("Ark Pixel 12px"))
            assertSame(BundledFontFamilies.SAITAMAAR, cache.resolve("Saitamaar"))
        }
    }
}
