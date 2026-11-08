package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 同一行里并排的多张 div 卡，锚列必须**全相等**（原版块零宽）。
 *
 * 现场症状（4.981 qol 设置页）：侧边栏 5 张 tab 卡本应竖排（`xpos=0`、只有 `ypos` 递增，
 * 见 `ERB/魔改内容/PRINT系/UI_Container_Framework.ERB:698-706`），实测却呈**阶梯** ——
 * 每张卡比上一张更靠右一点。
 *
 * 根因：锚列原按「源串前缀的可见文本」量，而卡片 1 的按钮标签就在卡片 2 的源串前缀里，
 * 于是锚列 4/8/12 格递增。原版 `<div>` 是**零宽节点**：子行不占父行宽度
 * （`ConsoleDivPart.SetWidth` 空实现，ConsoleDivPart.cs:217-219），块闭合后父行仍在同一 x
 * 续排（HtmlManager.cs:646 塞进父节点列表当前位置）⇒ 锚列恒等于「外层文本」宽度。
 */
class DivAnchorColTest {

    /** 与语料同形：每张卡 = `<div …><button value='100'>标签</button></div>`，只有 ypos 递增。 */
    private fun card(label: String, value: Int, ypos: Int) =
        "<div xpos='0px' ypos='${ypos}px' width='200px' height='38px' border='2px' " +
            "bcolor='#888888' padding='3px,4px' color='#151A22'>" +
            "<button value='$value'>$label</button></div>"

    private fun pageHtml() = "<nobr>" +
        card("日常设置", 100, 0) +
        card("诶嘿嘿设置", 101, 38) +
        card("视觉设置", 102, 76) +
        "</nobr>"

    @Test
    fun `同一行多张卡的锚列全为 0 不呈阶梯`() {
        val sink = LineBufferConsoleSink(clientWidth = 60)
        sink.printHtml(pageHtml(), false)
        val line = sink.snapshot().single { it.divBlocks.isNotEmpty() }

        assertEquals(3, line.divBlocks.size, "三张卡各一块")
        assertEquals(
            listOf(0, 0, 0),
            line.divBlocks.map { it.anchorCol },
            "块零宽：后面那些卡的锚列不能被前面卡的按钮文字顶出去",
        )
        assertEquals(
            listOf(0, 0, 0),
            line.divBlocks.map { it.anchorPointXPx },
            "锚列折像素同理（PointX 是矩形 X 的加数，CDP:178）",
        )
        // ypos 仍递增 ⇒ 竖排；xpos 全 0 ⇒ 不右移。
        assertEquals(listOf(0, 38, 76), line.divBlocks.map { it.pointYPx })
        assertEquals(listOf(0, 0, 0), line.divBlocks.map { it.xOffsetPx })
    }

    /** 卡的**前面有真外层文本**时，锚列应等于那段外层文本宽度（块的子行不算）。 */
    @Test
    fun `块前的外层文本计入锚列 块子行不计`() {
        val sink = LineBufferConsoleSink(clientWidth = 60)
        sink.printHtml("前缀" + pageHtml(), false)
        val line = sink.snapshot().single { it.divBlocks.isNotEmpty() }
        // 「前缀」两字 = 4 半角格
        assertEquals(listOf(4, 4, 4), line.divBlocks.map { it.anchorCol })
    }

    /** 缓冲路径（`,1`）同口径：卡里带按钮文字也不产生阶梯。 */
    @Test
    fun `缓冲路径的重叠卡也不呈阶梯`() {
        val sink = LineBufferConsoleSink(clientWidth = 60)
        sink.printHtml(pageHtml(), true)
        sink.newLine()
        val line = sink.snapshot().single { it.divBlocks.isNotEmpty() }
        assertTrue(line.divBlocks.size == 3, "缓冲路径也要三块")
        assertEquals(listOf(0, 0, 0), line.divBlocks.map { it.anchorCol })
    }
}
