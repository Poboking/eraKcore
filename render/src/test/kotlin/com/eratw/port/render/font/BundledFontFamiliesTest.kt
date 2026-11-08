package com.eratw.port.render.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [BundledFontFamilies] 的选族与回落（GFX-12 换字体波）。
 *
 * 这些断言钉的是**行为契约**，不是实现细节：
 *   * 语料里写的 PC 字体名（`ＭＳ ゴシック` / `黑体`）在安卓上没有对应文件，
 *     必须折到默认族而不是抛/回 null（同 `FontFactory.cs:33-48` 的 GDI 通用替换）；
 *   * `SETFONT "Saitamaar"` 必须真的落到 Saitamaar 族（默认正文 + 语料 14 处字符画靠它）；
 *   * 未知/空名一律默认族 —— 渲染层不许因为配置里写了个不存在的字体名就画不出字。
 *
 * 另有一条**跨表一致性**断言：注册表里的族名必须与 sidecar 的 `#family` 对得上，
 * 否则度量侧按族名查不到表，会静默退化成缺字分档（屏上宽度对、字形错位）。
 */
class BundledFontFamiliesTest {

    @Test
    fun `默认族是 Sarasa Mono SC 且进选择列表`() {
        // 原版 PC 的正文是 `FONT NAME:黑体`（SimHei，微软专有），不可随包分发；
        // Sarasa Mono SC 是它的合法替身（思源黑体同源、OFL-1.1、严格 1:2），
        // 且八族里字数覆盖最高（99.999%，缺 1056 次），因此是默认正文族。
        assertEquals("Sarasa Mono SC", BundledFontFamilies.default.family)
        assertSame(BundledFontFamilies.SARASA, BundledFontFamilies.default)
        assertTrue(BundledFontFamilies.default.selectable, "默认族必须能出现在设置页里")
        // 演示名与机器键分离：机器键进 Typeface/sidecar，演示名只上设置页
        assertTrue(BundledFontFamilies.default.displayName.contains("Sarasa"))
    }

    @Test
    fun `空名与未知名一律回落默认族`() {
        for (name in listOf(null, "", "   ", "　", "NoSuchFont", "ＭＳ ゴシック２")) {
            assertSame(
                BundledFontFamilies.default,
                BundledFontFamilies.resolve(name),
                "字体名 ${name ?: "<null>"} 应回落默认族",
            )
        }
    }

    @Test
    fun `PC 字体名折到默认族`() {
        // ConfigSpec.kt:122 的出厂值 + 语料里 SETFONT 常写的几个名字
        for (name in listOf(
            "ＭＳ ゴシック",
            "MS Gothic",
            "ＭＳ Ｐゴシック",
            "MS P Gothic",
            "黑体",
            "SimHei",
        )) {
            assertEquals(
                BundledFontFamilies.DEFAULT_FAMILY,
                BundledFontFamilies.resolve(name).family,
                "$name 应折到默认族",
            )
        }
    }

    @Test
    fun `Saitamaar 精确命中且仍可作正文选项`() {
        val resolved = BundledFontFamilies.resolve("Saitamaar")
        assertSame(BundledFontFamilies.SAITAMAAR, resolved)
        assertTrue(resolved.selectable, "字符画族仍须进正文选择列表")
        // 大小写与首尾空白不敏感
        assertSame(BundledFontFamilies.SAITAMAAR, BundledFontFamilies.resolve("  saitamaar "))
    }

    @Test
    fun `新增的开源族按族名与别名命中`() {
        assertSame(BundledFontFamilies.SARASA, BundledFontFamilies.resolve("Sarasa Mono SC"))
        assertSame(BundledFontFamilies.SARASA, BundledFontFamilies.resolve("sarasa-mono-sc"))
        assertSame(BundledFontFamilies.LXGW_WENKAI, BundledFontFamilies.resolve("Eratw WenKai Mono"))
        assertSame(BundledFontFamilies.LXGW_WENKAI, BundledFontFamilies.resolve("LXGW WenKai Mono"))
        assertSame(BundledFontFamilies.MAPLE_MONO, BundledFontFamilies.resolve("Maple Mono CN"))
        assertSame(BundledFontFamilies.MAPLE_MONO, BundledFontFamilies.resolve("maple-mono"))
        // 霞鹜文楷的机器键是改名后的 `Eratw WenKai Mono`（OFL RFN），原名仍可达
        assertEquals("Eratw WenKai Mono", BundledFontFamilies.LXGW_WENKAI.family)
        assertTrue(BundledFontFamilies.LXGW_WENKAI.displayName.contains("LXGW"))
    }

    @Test
    fun `备选族按族名精确命中`() {
        assertSame(BundledFontFamilies.FUSION_PIXEL, BundledFontFamilies.resolve("Fusion Pixel 12px"))
        assertSame(BundledFontFamilies.UNIFONT, BundledFontFamilies.resolve("Unifont"))
        assertSame(BundledFontFamilies.UNIFONT, BundledFontFamilies.resolve("unifont"))
        // 内部空白差异不影响命中（normalize 会删掉空白）
        assertSame(BundledFontFamilies.FUSION_PIXEL, BundledFontFamilies.resolve("FusionPixel12px"))
    }

    @Test
    fun `缺字回落链确定性且只含随包族`() {
        // 默认族只兜 Unifont；其它族先兜 Sarasa 再兜 Unifont
        assertEquals(listOf(BundledFontFamilies.UNIFONT), BundledFontFamilies.fallbacksFor(BundledFontFamilies.SARASA))
        assertEquals(listOf(BundledFontFamilies.SARASA), BundledFontFamilies.fallbacksFor(BundledFontFamilies.UNIFONT))
        assertEquals(
            listOf(BundledFontFamilies.SARASA, BundledFontFamilies.UNIFONT),
            BundledFontFamilies.fallbacksFor(BundledFontFamilies.MISAKI),
        )
        for (family in BundledFontFamilies.ALL) {
            val chain = BundledFontFamilies.fallbacksFor(family)
            assertTrue(chain.none { it.id == family.id }, "${family.family} 的回落链不应包含自己")
            assertTrue(chain.all { it in BundledFontFamilies.ALL }, "${family.family} 的回落链只能含随包族")
            assertEquals(chain.size, chain.map { it.id }.toSet().size, "${family.family} 的回落链有重复")
        }
    }

    @Test
    fun `登记表自洽：id 唯一、assets 前缀正确、覆盖率落在 0 到 1`() {
        assertEquals(
            BundledFontFamilies.ALL.size,
            BundledFontFamilies.ALL.map { it.id }.toSet().size,
            "id 必须唯一",
        )
        assertEquals(
            BundledFontFamilies.ALL.size,
            BundledFontFamilies.ALL.map { it.family }.toSet().size,
            "族名必须唯一（它是 sidecar 与 Typeface 的共同键）",
        )
        for (family in BundledFontFamilies.ALL) {
            assertTrue(
                family.asset.startsWith("fonts/") && family.asset.endsWith(".ttf"),
                "${family.family} 的 asset 必须在 assets/fonts/ 下且是 ttf：${family.asset}",
            )
            assertTrue(family.coverage in 0.0..1.0, "${family.family} 覆盖率越界：${family.coverage}")
            assertTrue(family.coverageHit <= family.coverageTotal, "${family.family} 命中数超过总数")
        }
    }

    @Test
    fun `byId 与 byFamily 两条查询同源`() {
        for (family in BundledFontFamilies.ALL) {
            assertSame(family, BundledFontFamilies.byId(family.id), "byId(${family.id})")
            assertSame(family, BundledFontFamilies.byFamily(family.family), "byFamily(${family.family})")
        }
        assertEquals(null, BundledFontFamilies.byId("nope"))
        assertEquals(null, BundledFontFamilies.byFamily("nope"))
    }

    @Test
    fun `选择列表只含可选择的族且默认族排第一`() {
        assertTrue(BundledFontFamilies.SELECTABLE.all { it.selectable })
        assertEquals(BundledFontFamilies.default, BundledFontFamilies.SELECTABLE.first())
        assertTrue(
            BundledFontFamilies.SELECTABLE.any { it.id == "saitamaar" },
            "默认族必须出现在正文选择里",
        )
        assertEquals(
            BundledFontFamilies.ALL.size,
            BundledFontFamilies.SELECTABLE.size,
            "当前八族全部可选（Saitamaar 也提供选项）",
        )
    }

    @Test
    fun `覆盖率读数与随包 manifest 一致`() {
        // 与 render/src/main/resources/fonts/font-pack-manifest.txt 的 coverage /
        // occurrence-coverage 行同值。改字体子集后三处（manifest / 本表 / 这里）一起改，
        // 否则设置页显示的覆盖率会骗人。
        // 2026-11-05，4.981 语料重扫（闭包 7956 码点，分母 108,464,789 次）
        val expected = mapOf(
            "sarasa" to (7880 to 1056),
            "lxgw" to (7651 to 42354),
            "maple" to (7213 to 887528),
            "fusion" to (7136 to 89992),
            "ark" to (7146 to 526038),
            "unifont" to (7909 to 495),
            "saitamaar" to (5169 to 5098238),
            "misaki" to (4977 to 5389921),
        )
        for ((id, pair) in expected) {
            val family = assertNotNull(BundledFontFamilies.byId(id), "登记表缺 $id")
            assertEquals(pair.first, family.coverageHit, "$id 的码点命中数与 manifest 不一致")
            assertEquals(pair.second, family.occurrenceMissing, "$id 的字数缺字次数与 manifest 不一致")
            assertEquals(BundledFontFamilies.REQUIRED_CODEPOINTS, family.coverageTotal)
        }
    }

    @Test
    fun `覆盖率排序：Fusion 仍是 12px 档最优，Unifont 是零方框档`() {
        // 默认族（Sarasa）也是字数覆盖最高的族；这条钉住其余排序（改字体子集后仍要成立），
        // 设置页的覆盖率提示才有意义。
        val bestNonFallback = BundledFontFamilies.SELECTABLE
            .filter { it.id != BundledFontFamilies.UNIFONT.id }
            .maxBy { it.occurrenceCoverage }
        assertSame(
            BundledFontFamilies.SARASA,
            bestNonFallback,
            "除兜底族外，默认族 Sarasa 的字数覆盖应最高",
        )
        assertTrue(
            BundledFontFamilies.FUSION_PIXEL.occurrenceCoverage >
                BundledFontFamilies.ARK_PIXEL.occurrenceCoverage,
            "Fusion 的字数覆盖必须高于 Ark",
        )
        assertTrue(
            BundledFontFamilies.UNIFONT.occurrenceCoverage >=
                BundledFontFamilies.FUSION_PIXEL.occurrenceCoverage,
            "Unifont 是零方框档，字数覆盖不该低于 Fusion",
        )
        val best12px = BundledFontFamilies.SELECTABLE
            .filter { it.designPx == 12 }
            .maxBy { it.occurrenceCoverage }
        assertSame(BundledFontFamilies.FUSION_PIXEL, best12px, "12px 档里字数覆盖最高的仍是 Fusion")
        // 美咲是观感档：覆盖率明显低于像素字体，这点必须在表里写实
        assertTrue(
            BundledFontFamilies.MISAKI.occurrenceCoverage < BundledFontFamilies.FUSION_PIXEL.occurrenceCoverage,
            "美咲的覆盖率低于像素字体，不能虚报",
        )
        // 字符画族 Saitamaar 同理：覆盖率不是最优，设置页提示必须写实
        assertTrue(
            BundledFontFamilies.SAITAMAAR.occurrenceCoverage < BundledFontFamilies.FUSION_PIXEL.occurrenceCoverage,
            "Saitamaar 的覆盖率低于像素字体，不能虚报",
        )
    }
}
