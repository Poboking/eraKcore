package com.eratw.port.render.adapter

import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `HtmlRunFlatten` 的 `<img srcm='…'>` 取色遮罩面（真缺口回归）。
 *
 * ## 缺口
 *
 * `InlineGraphic.Image.mappingResource` 原先只有 `GraphicsSinkBridge` 的 `PRINT_IMG` 路会填
 * （`view/GraphicsSinkBridge.kt`，第 3 实参 → `ConsoleImagePart.cs:44,98-101` 的 `cImageM`），
 * 而 HTML 写法（`<img src='…' srcm='…'>`，原版 `HtmlManager.cs:1191-1198` 解析该属性、
 * `:1221` 传进 `ConsoleImagePart`）在展平层被**静默丢弃**：`emitImage` 原先只读
 * `src` / `srcb` / `height` / `width` / `ypos`，`mappingResource` 恒为缺省 null。
 * 后果是 HTML 版取色遮罩「画得出来、点不取色」——命中路径
 * （`CanvasConsoleView.mappingColorOrNull` → `MappingColorScan`）看到的段没有遮罩图。
 *
 * ## 判据
 *
 * - 非空 `srcm` 原样落到 [InlineGraphic.Image.mappingResource]（`ConsoleImagePart.cs:44` 的
 *   `MappingGraphName = resNamem`）；
 * - 空串按「没有遮罩」置 null：`:51-54` 只在 `!string.IsNullOrEmpty` 时重建 `srcm=`，
 *   `:98` 的 `if (MappingGraphName != null)` 对空串会去 `GetSprite("")`（查不到）——
 *   两条都指向「空串 = 无遮罩」，端口统一归成 null 而不是空串，免得多一次必然落空的查表；
 * - 没写 `srcm` 的属性照样是 null（回归：语料 0 处命中，不许因新增分支改变既有读数）。
 *
 * 属性名大小写：原版 `HtmlManager.cs:1191` 用 `StringComparison.OrdinalIgnoreCase` 比属性名，
 * 本层的 `parseAttrs` 统一 `lowercase()`（`HtmlRunFlatten.kt:489`），故 `SRCM` 同效。
 */
class HtmlRunFlattenSrcmTest {

    private val metrics = CellMetrics(18)

    private fun flatten(html: String): List<TextRun> = HtmlRunFlatten(metrics).flatten(htmlRun(html))

    private fun htmlRun(html: String) =
        TextRun(html, fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.HTML)

    private fun imageOf(html: String): InlineGraphic.Image =
        flatten(html).single().graphic as InlineGraphic.Image

    @Test
    fun `srcm 落到 mappingResource`() {
        val g = imageOf("<img src='a' srcm='m'>")
        assertEquals("a", g.resource)
        assertEquals("m", g.mappingResource, "srcm 必须原样交出（ConsoleImagePart.cs:44）")
        assertNull(g.buttonResource, "没写 srcb 就是 null")
    }

    @Test
    fun `srcm 空串按 null`() {
        val g = imageOf("<img src='a' srcm=''>")
        assertEquals("a", g.resource)
        assertNull(g.mappingResource, "空串 = 没有遮罩（ConsoleImagePart.cs:51,98）")
    }

    @Test
    fun `无 srcm 时 mappingResource 为 null（回归）`() {
        assertNull(imageOf("<img src='a'>").mappingResource)
        // 同段的其它属性齐活时也不能被顺手填上
        assertNull(imageOf("<img src='a' srcb='b' width='10px' height='10px'>").mappingResource)
    }

    /** 原版属性名大小写不敏感（`HtmlManager.cs:1191` 的 `OrdinalIgnoreCase`）。 */
    @Test
    fun `属性名大小写不敏感`() {
        val g = imageOf("<img SRC='a' SrCm='m'>")
        assertEquals("a", g.resource)
        assertEquals("m", g.mappingResource)
    }

    /**
     * `srcm` 与 `srcb` 各归各位（`FunctionIdentifier.cs:414-426`：第 2 实参按钮图、第 3 实参取色图，
     * 两条通道不能串）。
     */
    @Test
    fun `srcm 与 srcb 不串`() {
        val g = imageOf("<img src='a' srcb='b' srcm='m'>")
        assertEquals("a", g.resource)
        assertEquals("b", g.buttonResource)
        assertEquals("m", g.mappingResource)
    }
}
