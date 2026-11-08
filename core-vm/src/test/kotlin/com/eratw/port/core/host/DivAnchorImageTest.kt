package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * div 锚点是**行内像素游标**，不是「可见文本格数 × 半格」。
 *
 * 原版：相对块的点位 = `ConsoleButtonString.CalcPointX` 的游标累加（:292-299，
 * `PointX = num; num += node.Width`），节点宽里 `<img>` 是**像素宽**
 * （`ConsoleImagePart.cs:66-82`）；`ConsoleDivPart` 用 `PointX + xpos + 左内缩` 落子行
 * （`ConsoleDivPart.cs:134`）⇒ 「图在前、div 在后」的 div 必须落在图**右侧**。
 *
 * 语料形态（`魔改内容/PRINT系/Toolkits.ERB:16-87` 的 `@IMG_TALK`，`@SPTALK` 全走它）：
 * `<nonbutton><img width='180px' height='180px'></nonbutton><div xpos='20px' …>台词</div>`
 * —— 旧实现在这里把 `<img>` 剥成 0 格，div 落回行首 20px，台词直接压在立绘上
 * （2026-11 真机截图实证）。本用例钉住「图元按像素推进」这条。
 */
class DivAnchorImageTest {

    private fun card(xpos: Int) =
        "<div xpos='${xpos}px' ypos='0px' width='300px' height='180px'>台词</div>"

    private fun singleBlock(sink: LineBufferConsoleSink): DisplayDivBlock =
        sink.snapshot().single { it.divBlocks.isNotEmpty() }.divBlocks.single()

    @Test
    fun `显式 width 的 img 后 div 锚点按图宽像素推进`() {
        val sink = LineBufferConsoleSink(clientWidth = 200, scriptFontSize = 18)
        sink.printHtml("<nobr><img src='a' width='180px' height='180px'>" + card(20) + "</nobr>", false)
        val b = singleBlock(sink)

        assertEquals(180, b.anchorPointXPx, "锚点像素 = 图宽（原版像素游标，不是 0）")
        // 格数与折行同尺：graphicCells(180, 18) = ceil(180 / (9 × 1.04)) = 20
        assertEquals(20, b.anchorCol, "格列由同一趟游标给出")
        assertEquals(20, b.xOffsetPx, "xpos 仍由块自己带，锚点不掺它")
    }

    @Test
    fun `缺 width 的 img 按基图纵横比推宽`() {
        val sink = LineBufferConsoleSink(
            clientWidth = 200,
            scriptFontSize = 18,
            imageSizeOf = { name -> if (name == "face") ImageSizePx(600, 600) else null },
        )
        sink.printHtml("<nobr><img src='face' height='180px'>" + card(25) + "</nobr>", false)
        val b = singleBlock(sink)

        // ConsoleImagePart.cs:66-70：高 180、基图 600×600 ⇒ 宽 180。
        assertEquals(180, b.anchorPointXPx, "缺 width 时用基图纵横比（神签.ERB:64 的形态）")

        val noSource = LineBufferConsoleSink(clientWidth = 200, scriptFontSize = 18)
        noSource.printHtml("<nobr><img src='face' height='180px'>" + card(25) + "</nobr>", false)
        assertEquals(0, singleBlock(noSource).anchorPointXPx, "宿主未接线 ⇒ 计 0（headless 零行为）")
    }

    @Test
    fun `纯文本前缀的像素口径与旧实现逐值一致`() {
        val sink = LineBufferConsoleSink(clientWidth = 200, scriptFontSize = 18)
        sink.printHtml("前缀" + card(0), false)
        val b = singleBlock(sink)

        assertEquals(4, b.anchorCol, "「前缀」两字 = 4 半角格")
        assertEquals(36, b.anchorPointXPx, "4 格 × 18/2 = 36（旧口径同值）")
    }

    @Test
    fun `图后多张卡的锚点全相等且不呈阶梯`() {
        val sink = LineBufferConsoleSink(clientWidth = 200, scriptFontSize = 18)
        val html = "<nobr><img src='a' width='90px' height='180px'>" +
            card(0) + card(0) + "</nobr>"
        sink.printHtml(html, false)
        val blocks = sink.snapshot().single { it.divBlocks.isNotEmpty() }.divBlocks

        assertEquals(2, blocks.size)
        assertEquals(listOf(90, 90), blocks.map { it.anchorPointXPx }, "块零宽：前一张卡的子行不推进游标")
    }

    /**
     * 同一个 sink 里「图元占宽」**只许一把尺**：`lineWidth`/折行走
     * `visibleHtmlWidth`（= `visibleHtmlCells(…, scriptFontSize, imageSizeOf)`），
     * 游标若改用 `divFontSize`（未接线时的 18 兜底），`scriptFontSize = 0` 的装置上就会
     * 「行宽说图元 0、锚点说图元 180px」。本用例钉住两者同值。
     */
    @Test
    fun `scriptFontSize 为 0 时图元不占宽 与行宽同尺`() {
        val sink = LineBufferConsoleSink(clientWidth = 200) // scriptFontSize 缺省 0
        sink.printHtml("<nobr><img src='a' width='180px' height='180px'>" + card(20) + "</nobr>", false)
        val b = singleBlock(sink)

        assertEquals(0, b.anchorPointXPx, "不做图形折算的装置上图元与 lineWidth 一样计 0")
        assertEquals(0, b.anchorCol, "格投影同尺")
    }

    @Test
    fun `按钮列位与 div 锚点共用同一把图元尺`() {
        // 同一段 HTML：图前缀 + 按钮。scriptFontSize=18 时按钮列位 = 图宽折格（20），
        // scriptFontSize=0 时 = 0 —— 若两处各用一把尺，这两个读数会互相矛盾。
        val withFont = LineBufferConsoleSink(clientWidth = 400, scriptFontSize = 18)
        withFont.printHtml("<nobr><img src='a' width='180px' height='180px'><button value='1'>继续</button></nobr>", false)
        assertEquals(
            20,
            withFont.snapshot().single { it.htmlOptions.isNotEmpty() }.htmlOptions.single().startCol,
            "按钮列位 = graphicCells(180, 18) = 20",
        )

        val noFont = LineBufferConsoleSink(clientWidth = 400)
        noFont.printHtml("<nobr><img src='a' width='180px' height='180px'><button value='1'>继续</button></nobr>", false)
        assertEquals(
            0,
            noFont.snapshot().single { it.htmlOptions.isNotEmpty() }.htmlOptions.single().startCol,
            "不做图形折算时按钮列位与 lineWidth 一致（0）",
        )
    }
}
