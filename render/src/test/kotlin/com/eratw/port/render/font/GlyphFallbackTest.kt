package com.eratw.port.render.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * [GlyphFallback] 的链序 / 去重 / 短路 / 全落空四条纪律（契约 §3.1）。
 *
 * 期望值只来自 [BundledFontFamilies.fallbacksFor] 的规则（选中族 → Sarasa → Unifont，
 * 去掉自身），**不从 [GlyphFallback] 的实现回读**：链序断言写成字面清单，
 * 改链序必须同时改本用例。
 *
 * 为什么两处消费者（`FontWidthRuler.compute` 与 `CanvasConsoleView.effectiveFamily`）
 * 必须同源：链序分家意味着同一码位在度量侧与绘制侧落在不同族上 ⇒ 屏上「按 A 族推进、
 * 用 B 族画」的错格。本类钉的就是那条链本身，两处消费者的源码形状由
 * `glyph/FontFallbackSingleSourceTest` 与 `canvas/FontFallbackChainSingleSourceTest` 钉。
 */
class GlyphFallbackTest {

    @Test
    fun `八个登记族的全链成员与顺序（按 id 去重、请求族在首位）`() {
        // 登记表基数变了就必须同批重定标本用例（设置页展示顺序也在这张表里）。
        assertEquals(8, BundledFontFamilies.ALL.size, "登记族数量与用例假设不符")
        // 两族互为兜底的那一对：链长 2。
        val pairChains = mapOf(
            BundledFontFamilies.SARASA to
                listOf(BundledFontFamilies.SARASA, BundledFontFamilies.UNIFONT),
            BundledFontFamilies.UNIFONT to
                listOf(BundledFontFamilies.UNIFONT, BundledFontFamilies.SARASA),
        )
        for (family in BundledFontFamilies.ALL) {
            val chain = GlyphFallback.chainFor(family)
            val want = pairChains[family]
                ?: listOf(family, BundledFontFamilies.SARASA, BundledFontFamilies.UNIFONT)
            assertEquals(want, chain, "族 ${family.id} 的回落链成员或顺序不对")
            // 去重：同一族不许在链里出现两次（按 id 判，族名是机器键的唯一性口径）。
            assertEquals(
                chain.size,
                chain.map { it.id }.distinct().size,
                "族 ${family.id} 的链里有重复成员：${chain.map { it.id }}",
            )
            // 请求族在首位（且是本体，不是等值副本）。
            assertSame(family, chain.first(), "链首必须是请求族本体")
            // 无缓存：两次调用必须是两个不同的列表实例。
            assertNotSame(chain, GlyphFallback.chainFor(family), "chainFor 不许缓存列表实例")
        }
    }

    @Test
    fun `resolve 首命中即短路`() {
        val requested = BundledFontFamilies.MISAKI
        val target = BundledFontFamilies.SARASA
        val chain = GlyphFallback.chainFor(requested)
        val calls = ArrayList<BundledFontFamily>(4)
        val hit = GlyphFallback.resolve(requested) { candidate ->
            calls += candidate
            candidate === target
        }
        assertSame(target, hit, "命中兜底族时必须返回它本体")
        // 契约 §3.1.3：首命中的调用次数 = 命中位置 + 1（命中之后不再问谓词）。
        assertEquals(
            chain.indexOfFirst { it === target } + 1,
            calls.size,
            "首命中之后还在调谓词：调用序列 ${calls.map { it.id }}",
        )
        // 而且调用序必须是链的前缀（不跳、不重排）。
        assertEquals(chain.take(calls.size), calls, "谓词调用序必须沿链序")
    }

    @Test
    fun `resolve 全落空返回请求族本体而不抛`() {
        val requested = BundledFontFamilies.MISAKI
        val visited = ArrayList<BundledFontFamily>(4)
        val hit = GlyphFallback.resolve(requested) { candidate ->
            visited += candidate
            false
        }
        assertSame(requested, hit, "全落空必须返回请求族本体（===），不是 null 也不是默认族")
        assertEquals(GlyphFallback.chainFor(requested), visited, "全落空要把整条链问完")
    }
}
