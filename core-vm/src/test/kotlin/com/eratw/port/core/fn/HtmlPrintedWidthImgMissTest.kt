package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.CellWidth
import com.eratw.port.core.host.SPRITE_SLOT
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `HTML_STRINGLEN` 回读侧：`<img>` **图元 miss** 时判 miss 的**相位**必须是尺寸计算之前。
 *
 * ## 钉的是原版哪一步
 *
 * `ConsoleImagePart` 的构造顺序是：`:44-59` 先重建 AltText，`:60-65` **再查图元**，
 * `cImage == null` ⇒ `Text = AltText` 后立刻 `return` —— `:66` 起那整段「height 折算 /
 * `Width = DestBaseSize.Width * num / DestBaseSize.Height` / 显式 `width`」一行都不执行；
 * 节点的宽改由 `SetWidth`（`:104-115`，`StringMeasure.GetDisplayLength(Text)`）按
 * **重建文案的文本度量**给出。所以「显式 `width`」在 miss 时**不生效**。
 *
 * 改动前本仓的 `imgGroupNode` 只在「没有 `width` 属性」时才查表：显式 `width` + miss
 * 直接按属性计宽（`<img width='180px'>` ⇒ 180px），与折行侧
 * [CellWidth.visibleHtmlCells] 的 miss 支（AltText 文本格）分叉。本文件即那条相位的回归锚。
 *
 * ## 三态（与 `CellWidthImgMissTest` 同判据）
 *
 * 1. 宿主**未接线**（`imageSizeOf == null`）⇒ 判不了在不在，**逐值不变**：
 *    显式 `width` 计图形宽、缺 `width` 计 AltText 的文本度量（改动前的读数）；
 * 2. 接线 + miss（含登记的基图高 ≤ 0）⇒ AltText 文本度量，显式 `width` **不生效**；
 * 3. 接线 + 命中 ⇒ 显式 `width`（px 原值 / 百分比 `FontSize*num/100` 整除）或
 *    「高 × 基图纵横比」推宽（`:66-71`），亚像素余数照旧被后续 `<shape>` 吸收（`:70`）。
 *
 * 量具与 `HtmlPrintedWidthTest` 同形：[pwMeasure] 式的 `CellWidth.of × FontSize/2`，
 * 不另建第二份宽度表。读数全部是具体数字。
 */
class HtmlPrintedWidthImgMissTest {

    private val fontPx = 18L

    /** 夹具度量：与生产侧 `HtmlMethods.approxTextWidth` 同一把尺（半角 9px）。 */
    private fun measure(s: String): Long = CellWidth.of(s).toLong() * (fontPx / 2)

    /** 宿主已接线、但表里没有这张图。 */
    private val missing: (String) -> ImagePixelSize? = { null }

    private fun sized(w: Int, h: Int): (String) -> ImagePixelSize? = { ImagePixelSize(w, h) }

    /** 直接驱动解析器（`drawablePx = 0` ⇒ 不折行，读数 = 第一条显示行的整串宽）。 */
    private fun len(html: String, sizes: ((String) -> ImagePixelSize?)?): Long =
        HtmlStrictParser(html, ::measure, { it }, fontPx, 0xFFFF00, 0L, collectRows = false, imageSizeOf = sizes)
            .length().toLong()

    // ------------------------------------------------------------------
    // 1) 接线 + miss：显式 width 也不生效（ConsoleImagePart.cs:60-65）
    // ------------------------------------------------------------------

    @Test
    fun `显式 width 遇图元 miss 时按 AltText 文本度量`() {
        val html = "<img src='c.png' width='180px' height='50'>"
        // 重建文案 = `<img src='c.png' height='9' width='180px'>`（ConsoleImagePart.cs:45-58）：
        // 42 个半角字符 × 9px。
        val alt = CellWidth.imgAltText(html, fontPx)!!
        assertEquals("<img src='c.png' height='9' width='180px'>", alt)
        assertEquals(42L * 9L, len(html, missing), "宽 = 文本度量（:104-115），不是显式 width")
        assertNotEquals(180L, len(html, missing), "显式 width='180px' 在 miss 时不生效")
        assertEquals(CellWidth.displayCells(alt).toLong() * 9L, len(html, missing), "与 AltText 的显示格同值")
    }

    @Test
    fun `登记的基图高为零按 miss 处理`() {
        val html = "<img src='c.png' width='180px'>"
        // 原版 :69 在这里整数除零（ADR-005 登记为 miss）；宽 = `<img src='c.png' width='180px'>`
        // 的 31 个半角 × 9px。
        assertEquals(31L * 9L, len(html, sized(100, 0)))
    }

    @Test
    fun `缺 width 且 miss 的旧读数不变`() {
        // 与改动前逐值一致（原先这条也走 AltText 回落）。
        assertEquals(17L * 9L, len("<img src='c.png'>", missing), "`<img src='c.png'>` 17 个半角")
    }

    // ------------------------------------------------------------------
    // 2) 未接线：判不了在不在 ⇒ 逐值不变（显式宽仍计图形宽）
    // ------------------------------------------------------------------

    @Test
    fun `未接线时显式 width 与缺 width 保持旧读数`() {
        assertEquals(180L, len("<img src='c.png' width='180px'>", null), "未接线判不了 miss ⇒ 显式宽照旧")
        assertEquals(17L * 9L, len("<img src='c.png'>", null), "未接线缺 width 仍回落文本度量")
    }

    // ------------------------------------------------------------------
    // 3) 接线 + 命中：显式宽 / 纵横比 / 负宽取绝对值 / 亚像素余数
    // ------------------------------------------------------------------

    @Test
    fun `命中时显式宽照旧计图形宽`() {
        assertEquals(180L, len("<img src='d.png' width='180px' height='50'>", sized(64, 32)))
        assertEquals(180L, len("<img src='d.png' width='-180px'>", sized(64, 32)), "负宽取绝对值（:83-87）")
    }

    @Test
    fun `命中且缺宽时按基图纵横比推宽`() {
        // :66 num = height = 30px；:69 100 * 30 / 50 = 60
        assertEquals(60L, len("<img src='d.png' height='30px'>", sized(100, 50)))
    }

    @Test
    fun `命中时亚像素余数仍被后续 shape 吸收`() {
        // :77-79 18*111/100 = 19 整除、余数 0.98 进 XsubPixel；:70 同一条通道。
        // ConsoleSpacePart.cs:18-23：(int)(0.98 + 5) = 5 ⇒ 合计 24（与 HTML_PRINTEDWIDTH 系同值）。
        assertEquals(24L, len("<img src='d.png' width='111'><shape type='space' param='5px'>", sized(64, 32)))
    }

    // ------------------------------------------------------------------
    // 4) 函数面：生产装配（aux 的 SpritePool）同样按 miss 相位判
    // ------------------------------------------------------------------

    private class PwMissBaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("PwMissBaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    /** `HTML_STRINGLEN(html, 1)` = 第一条显示行的像素宽（HtmlManager.cs:168-182）。 */
    private fun poolPx(html: String): Long {
        val store = VariableStore()
        val base = PwMissBaseCtx()
        base.variables = store
        store.aux.slot(SPRITE_SLOT) { SpritePool() }
            .put(SpritePool.Sprite("d.png", 200, 100)) // 只登记 d.png
        val ctx = BuiltinEvalContext(base, store, object : BuiltinHost {})
        return (ctx.callFunction("HTML_STRINGLEN", listOf(Value.StrV(html), Value.IntV(1))) as Value.IntV).v
    }

    @Test
    fun `函数面显式 width 遇未登记图元按 AltText 度量`() {
        // e.png 未登记 ⇒ miss ⇒ 宽 = `<img src='e.png' width='180px'>`（31 个半角 × 9）
        assertEquals(31L * 9L, poolPx("<img src='e.png' width='180px'>"))
        // d.png 已登记 ⇒ 命中，显式 width 照旧
        assertEquals(180L, poolPx("<img src='d.png' width='180px'>"))
    }
}
