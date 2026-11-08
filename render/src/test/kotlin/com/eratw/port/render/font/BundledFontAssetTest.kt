package com.eratw.port.render.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 登记表 → 随包资产的存在性闸（2026-11-05，八族收口）。
 *
 * 病根：`BundledFontFamilies` 只是**名字表**；[BundledFontFamiliesTest] 也只钉「asset 字段
 * 长得像路径」。若 `render/src/main/resources/fonts/` 里少了一份 ttf 或 sidecar，
 * 单测全绿、装配期 `Typeface.createFromAsset` 才抛 → 该族整屏回落 `Typeface.DEFAULT`
 * （机型相关字形），而设置页仍报着「覆盖率 99.99%」。
 *
 * 这里按 classpath 逐条验：八个 asset 都在、七份 sidecar 都在且 `#family` 与登记族名逐字相同
 * （sidecar 按族名查表，差一个字就静默退化成网格分档）。美咲与 Saitamaar 的口径：
 * 美咲**故意没有** sidecar（它严格 1:2，缺行即按 Grid 走），Saitamaar 有。
 */
class BundledFontAssetTest {

    /** 有 sidecar 的族（键 = 登记 id，值 = `<slug>-advance.txt`）。 */
    private val sidecarsByFamilyId = mapOf(
        "sarasa" to "fonts/sarasa-mono-sc-advance.txt",
        "lxgw" to "fonts/lxgw-wenkai-mono-advance.txt",
        "maple" to "fonts/maple-mono-cn-advance.txt",
        "saitamaar" to "fonts/saitamaar-advance.txt",
        "fusion" to "fonts/fusion-pixel-12px-advance.txt",
        "ark" to "fonts/ark-pixel-12px-advance.txt",
        "unifont" to "fonts/unifont-advance.txt",
    )

    private fun read(path: String): String? =
        javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }

    @Test
    fun `每个登记族都有随包 ttf`() {
        for (family in BundledFontFamilies.ALL) {
            val bytes = javaClass.classLoader.getResourceAsStream(family.asset)?.use { it.readBytes() }
            assertNotNull(bytes, "${family.family} 的 asset 不在 classpath：${family.asset}")
            assertTrue(bytes.size > 10_000, "${family.family} 的 asset 只有 ${bytes.size} 字节，像是坏包")
        }
    }

    @Test
    fun `有 sidecar 的族其表头 family 与登记名逐字相同`() {
        assertEquals(
            sidecarsByFamilyId.size,
            BundledFontFamilies.ALL.count { it.id in sidecarsByFamilyId },
            "新增/删除带 sidecar 的族时本表要同步（它是「谁该有表」的唯一名单）",
        )
        for ((id, path) in sidecarsByFamilyId) {
            val family = assertNotNull(BundledFontFamilies.byId(id), "登记表缺 $id")
            val text = assertNotNull(read(path), "$id 的 sidecar 不在 classpath：$path")
            val declared = text.lineSequence()
                .firstOrNull { it.startsWith("#family ") }
                ?.removePrefix("#family ")
            assertEquals(family.family, declared, "$path 的 #family 与登记族名不一致，度量侧会查空表")
        }
    }

    @Test
    fun `美咲故意没有 sidecar`() {
        assertTrue(BundledFontFamilies.MISAKI.id !in sidecarsByFamilyId)
        assertEquals(null, read("fonts/misaki-advance.txt"), "美咲是严格 1:2，缺行即按 Grid；多一份表会变成第二处口径")
    }
}
