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
    fun `默认族是 Saitamaar 且进选择列表`() {
        // 2026-10-25 主人指定：默认族切回 Saitamaar 子集（eratw 早期安卓版观感）。
        // 代价是比例 advance（布局仍按 Grid 1:2 兜底）与 65.23% 的种类覆盖；
        // 覆盖率最优的 Fusion 与零方框的 Unifont 仍留在设置页可选。
        assertEquals("Saitamaar", BundledFontFamilies.default.family)
        assertSame(BundledFontFamilies.SAITAMAAR, BundledFontFamilies.default)
        assertTrue(BundledFontFamilies.default.selectable, "默认族必须能出现在设置页里")
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
            "Eratw Mono SC",
        )) {
            assertEquals(
                BundledFontFamilies.DEFAULT_FAMILY,
                BundledFontFamilies.resolve(name).family,
                "$name 应折到默认族",
            )
        }
    }

    @Test
    fun `Saitamaar 精确命中且可作正文选项`() {
        val resolved = BundledFontFamilies.resolve("Saitamaar")
        assertSame(BundledFontFamilies.SAITAMAAR, resolved)
        assertTrue(resolved.selectable, "默认族必须进正文选择列表")
        // 大小写与首尾空白不敏感
        assertSame(BundledFontFamilies.SAITAMAAR, BundledFontFamilies.resolve("  saitamaar "))
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
            "当前五族全部可选（Saitamaar 也提供选项）",
        )
    }

    @Test
    fun `覆盖率读数与随包 manifest 一致`() {
        // 与 render/src/main/resources/fonts/font-pack-manifest.txt 的 coverage /
        // occurrence-coverage 行同值。改字体子集后三处（manifest / 本表 / 这里）一起改，
        // 否则设置页显示的覆盖率会骗人。
        val expected = mapOf(
            "fusion" to (7262 to 80516),
            "ark" to (7252 to 457668),
            "unifont" to (8034 to 84),
            "saitamaar" to (5268 to 3908779),
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
        // 默认族切回 Saitamaar 后，「覆盖率最好」与「默认」不是同一个族 —— 这条钉住
        // 覆盖率本身的排序不变（改字体子集后仍要成立），设置页的覆盖率提示才有意义。
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
        // 默认族 Saitamaar 同理：它是观感选择，覆盖率不是最优，设置页提示必须写实
        assertTrue(
            BundledFontFamilies.SAITAMAAR.occurrenceCoverage < BundledFontFamilies.FUSION_PIXEL.occurrenceCoverage,
            "Saitamaar 的覆盖率低于像素字体，不能虚报",
        )
    }
}
