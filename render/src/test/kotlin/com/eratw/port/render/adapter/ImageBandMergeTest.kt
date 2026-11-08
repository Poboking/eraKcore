package com.eratw.port.render.adapter

import com.eratw.port.core.host.Alignment
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [ImageBandMerge]：首页标题图（36 条 1041×16 横带逐行铺）合成一张等比缩小的图。
 *
 * 判据：把 N 行等高纯图片行折成一行图（等比、居中靠 align）+ 其余行清空；
 * 放得下时**原样返回**（同一个 List 实例，避免无谓抖动）；不足 [ImageBandMerge.merge] 的
 * `minBands` 或尺寸不一致时不合并。
 */
class ImageBandMergeTest {

    private fun band(w: Int, h: Int, resource: String = "TW_title000", y: Int = 0): RenderLine =
        RenderLine(
            runs = listOf(
                TextRun(
                    text = "",
                    fore = 0xC0C0C0,
                    back = 0,
                    styleMask = 0,
                    fontName = "黑体",
                    kind = RunKind.IMAGE,
                    graphic = InlineGraphic.Image(
                        resource = resource,
                        widthPx = w,
                        heightPx = h,
                        yOffsetPx = y,
                        widthCells = maxOf(1, w / 20),
                    ),
                ),
            ),
            isLogicalLine = true,
            isLineEnd = true,
            align = Alignment.CENTER,
        )

    @Test
    fun `竖带块合成一张等比缩小的图并收窄行数`() {
        val lines = (0 until 12).map { band(1000, 40, "TW_title%03d".format(it)) }
        val out = ImageBandMerge.merge(
            lines = lines,
            lineHeightPx = 40,
            cellPixelWidth = 20f,
            availWidthPx = 1000,
            maxBlockHeightPx = 200,
        )
        assertEquals(5, out.size, "12 条横带合成 1 张图 + 4 个承载空行（其余行删掉，屏上内容变短）")
        val g = out[0].runs.single().graphic as InlineGraphic.Image
        // 块高 12*40=480 → 高限 200 ⇒ scale=200/480≈0.4167；宽 1000→416、高 480→200
        assertEquals(417, g.widthPx, "等比缩到高限：1000 × 200/480 = 416.7 ⇒ 417")
        assertEquals(200, g.heightPx)
        assertEquals(21, g.widthCells, "占宽按缩后像素宽算格数：ceil(417/20) = 21")
        assertEquals(5, g.rowSpan, "整块跨 5 行（绘制侧据此不按单行高压缩）")
        assertEquals(
            (0 until 12).map { "TW_title%03d".format(it) },
            g.bandResources,
            "35 张切片资源名整串带下去（单画首张只有最上面 16px 一条）",
        )
        assertEquals(Alignment.CENTER, out[0].align, "居中沿用行对齐（脚本 p align=center）")
        // ceil(200/40) = 5 行承载；其余行删掉。承载行必须带一个空格 run（否则折行时被丢掉）
        assertEquals(1, out[1].runs.size, "承载行补一个半角空格（图比行高矮时）")
        assertEquals(" ", out[1].runs.single().text)
        assertTrue(out[1].isLineEnd, "承载行必须是已收尾行，否则被 appendMerged 并进上一行")
        assertEquals(1, out[4].runs.size, "共 5 行：1 图 + 4 承载行")
        assertEquals(out[0].runs.single().text.length, 21, "占宽负载是 21 个半角空格")
    }

    @Test
    fun `块高上限按绘制后的自然高取比例`() {
        // 真机形态：缺 height 的横带每带高 = FontSize，可以大于行高（36 > 31）。
        // 高限 200 必须对 12*36=432 取比例（⇒ 绘制高正好 200），而不是对 12*31=372
        // 取比例（那样算出 232，合并块仍会压到下面的文字行）。
        val lines = (0 until 12).map { band(1000, 36) }
        val out = ImageBandMerge.merge(
            lines = lines,
            lineHeightPx = 31,
            cellPixelWidth = 20f,
            availWidthPx = 1000,
            maxBlockHeightPx = 200,
        )
        val g = out[0].runs.single().graphic as InlineGraphic.Image
        assertEquals(200, g.heightPx, "等比缩到高限，不带行高比值的 14% 溢出")
        assertEquals((200 + 31 - 1) / 31, g.rowSpan)
        assertEquals(12, g.bandResources?.size, "12 张切片都要留在块里")
        assertEquals(7, out.size, "ceil(200/31) = 7 行承载")
    }

    @Test
    fun `放得下时原样返回`() {
        val lines = (0 until 12).map { band(400, 40) }
        val out = ImageBandMerge.merge(
            lines = lines,
            lineHeightPx = 40,
            cellPixelWidth = 20f,
            availWidthPx = 1000,
            maxBlockHeightPx = 1080,
        )
        assertSame(lines, out, "scale >= 1 ⇒ 不合并、返回同一实例")
    }

    @Test
    fun `不足最小行数或尺寸不一致不合并`() {
        val few = (0 until 3).map { band(1000, 40) }
        assertSame(
            few,
            ImageBandMerge.merge(few, 40, 20f, 1000, 100),
            "少于 minBands 不合并",
        )
        val mixed = (0 until 12).map { band(if (it % 2 == 0) 1000 else 800, 40) }
        assertSame(
            mixed,
            ImageBandMerge.merge(mixed, 40, 20f, 1000, 100),
            "各带尺寸不一致不合并（宁可漏合，不可错合）",
        )
    }

    @Test
    fun `近方形图块即使连续多行也不合并`() {
        // 8 张同尺寸立绘/头像（宽高比 1）不是同一张图切出的横带，缩成一块会毁版面。
        val portraits = (0 until 12).map { band(200, 200, "portrait%02d".format(it)) }
        assertSame(
            portraits,
            ImageBandMerge.merge(portraits, 40, 20f, 1000, 100),
            "宽高比 < 4 的块不参与竖带合并",
        )
    }

    @Test
    fun `合并块之前的普通行必须保留`() {        // 回归（深审 R9 render-B2）：首页标题图前面由脚本打印的文字行，在标题图被合并后
        // 曾整段丢失（`out` 首次创建时没有补回跳过的前缀行）。
        val title = RenderLine(
            runs = listOf(TextRun("标题文字", 0xC0C0C0, 0, 0, "黑体")),
            isLogicalLine = true,
            isLineEnd = true,
        )
        val lines = listOf(title) + (0 until 12).map { band(1000, 40, "TW_title%03d".format(it)) }
        val out = ImageBandMerge.merge(
            lines = lines,
            lineHeightPx = 40,
            cellPixelWidth = 20f,
            availWidthPx = 1000,
            maxBlockHeightPx = 200,
        )
        assertSame(title, out[0], "合并块之前的普通文字行原样保留，不丢")
        assertEquals(6, out.size, "1 前缀行 + 1 张合成图 + 4 个承载空行")
        assertTrue(
            out[1].runs.single().graphic is InlineGraphic.Image,
            "合成图紧跟在前缀行之后",
        )
    }

    // ==================================================================
    // 第二趟：单张超高图（4.981 首页标题图那一支）
    // ==================================================================

    private fun blank(): RenderLine = RenderLine(
        runs = emptyList(),
        isLogicalLine = true,
        isLineEnd = true,
    )

    private fun textLine(text: String): RenderLine = RenderLine(
        runs = listOf(TextRun(text, 0xC0C0C0, 0, 0, "黑体")),
        isLogicalLine = true,
        isLineEnd = true,
    )

    /**
     * 4.981 真机档的标题图：`height='3500'` 是百分比（`Utils.cs:342-362`），端口按**生效字号** 41 乘
     * ⇒ 高 1435px（比 1080 的屏还高）、缺宽按基图 1041:557 推得宽 2682px。
     */
    private fun tallTitle(w: Int = 2682, h: Int = 1435): RenderLine = RenderLine(
        runs = listOf(
            TextRun(
                text = "",
                fore = 0xC0C0C0,
                back = 0,
                styleMask = 0,
                fontName = "黑体",
                kind = RunKind.IMAGE,
                graphic = InlineGraphic.Image(
                    resource = "TW_title",
                    widthPx = w,
                    heightPx = h,
                    yOffsetPx = 0,
                    widthCells = w / 20,
                ),
            ),
        ),
        isLogicalLine = true,
        isLineEnd = true,
        align = Alignment.CENTER,
    )

    private fun collapse(lines: List<RenderLine>) = ImageBandMerge.merge(
        lines = lines,
        lineHeightPx = 43,
        cellPixelWidth = 20f,
        availWidthPx = 2400,
        maxBlockHeightPx = 648,
    )

    @Test
    fun `单张超高图缩到块高上限并塌缩它预留的空行`() {
        // 4.981 `TITLE.ERB:61-62`：一张 35 行高的图 + 脚本腾出的 34 行空白。
        // 图比整个窗口（30 行）还高 ⇒ 图所在行被推出绘制窗口、整张图一条命令都不出。
        val lines = listOf(tallTitle()) + List(34) { blank() } + List(12) { textLine("文字$it") }
        val out = collapse(lines)

        val g = out[0].runs.single().graphic as InlineGraphic.Image
        assertEquals(648, g.heightPx, "缩到高限 648（= 0.6 屏高那一档预算）")
        assertEquals(1211, g.widthPx, "等比缩：2682 × 648/1435，不单独夹宽")
        assertEquals(16, g.rowSpan, "ceil(648/43) = 16 行承载")
        assertNull(g.bandResources, "单张图不是竖带块，绘制侧不走逐片堆叠")
        assertEquals(Alignment.CENTER, out[0].align, "居中沿用脚本的 p align=center")
        assertEquals(28, out.size, "1 图 + 15 承载 + 12 文字 = 28 行，落得进 30 行窗口")
        assertEquals(out.last(), lines.last(), "图后面的文字行原样在末尾，一行不丢")
    }

    @Test
    fun `不高过块上限的图一行都不动`() {
        // 立绘（180px 那族）与 bodyGraph 都远小于 648：这一趟必须完全放行、返回同一实例，
        // 否则就是拿「标题图缩放」去改所有立绘的版面。
        val lines = listOf(tallTitle(h = 645)) + List(20) { blank() } + List(5) { textLine("x") }
        assertSame(lines, ImageBandMerge.merge(lines, 43, 20f, 2400, 648))
    }

    @Test
    fun `已合成的竖带块不被第二趟重复缩放`() {
        // 第一趟的产物 heightPx 已 ≤ 高限、且带 rowSpan/bandResources；第二趟必须认出它。
        val lines = (0 until 12).map { band(1000, 40, "TW_title%03d".format(it)) }
        val out = ImageBandMerge.merge(lines, 40, 20f, 1000, 200)
        assertSame(out, ImageBandMerge.merge(out, 40, 20f, 1000, 200), "第二趟幂等：不重复缩、不重复塌行")
    }

    @Test
    fun `预留空行不足时补承载行`() {
        // 图缩到 16 行，但脚本只腾了 3 行 ⇒ 后面 12 行由承载行补上，
        // 图不得压在文字行上（那正是 2026-11「图片遮住文字」那一族的复发条件）。
        val lines = listOf(tallTitle()) + List(3) { blank() } + List(5) { textLine("y") }
        val out = collapse(lines)
        assertEquals(21, out.size, "1 图 + 15 承载 + 5 文字")
        for (r in 1 until 16) {
            assertTrue(out[r].isLineEnd, "第 $r 承载行必须已收尾，否则被 appendMerged 并进上一行")
        }
        assertTrue(out[16].runs.single().text.startsWith("y"), "文字行紧跟在承载块之后")
    }

    @Test
    fun `图与文字同行的混排行不参与缩放`() {
        // 判据与 bandOf 同口径（宁可漏合，不可错合）：混排行缩了会把同行文字一起推走。
        val mixed = RenderLine(
            runs = listOf(
                TextRun("说明", 0xC0C0C0, 0, 0, "黑体"),
                TextRun(
                    text = "",
                    fore = 0xC0C0C0,
                    back = 0,
                    styleMask = 0,
                    fontName = "黑体",
                    kind = RunKind.IMAGE,
                    graphic = InlineGraphic.Image(
                        resource = "TW_title",
                        widthPx = 2682,
                        heightPx = 1435,
                        yOffsetPx = 0,
                        widthCells = 134,
                    ),
                ),
            ),
            isLogicalLine = true,
            isLineEnd = true,
        )
        val lines = listOf(mixed) + List(34) { blank() }
        assertSame(lines, ImageBandMerge.merge(lines, 43, 20f, 2400, 648))
    }
}
