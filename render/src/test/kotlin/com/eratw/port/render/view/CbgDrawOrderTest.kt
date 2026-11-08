package com.eratw.port.render.view

import com.eratw.port.core.host.CbgLayerSnapshot
import com.eratw.port.render.adapter.DivOverlayPlanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CBG / 立绘 / div / 正文的**单趟 depth 归并**（C 刀）。
 *
 * ## 判据（原版 `EmueraConsole.cs:1834-1873` 的那一个 while）
 *
 * ```
 * num7 = Math.Max(逃逸段头 depth, cbgList 头 zdepth);          // :1836
 * if (cbgList 头的 zdepth == num7) { 画该层; num5++; }          // :1837-1849
 * if (num7 == 0) { 画全部正文行 }                              // :1850-1857
 * if (逃逸段头的 depth == num7) { 画该层; num4++; }             // :1858-1872
 * ```
 *
 * ⇒ 归并序（从先落笔 = 屏底到后落笔 = 屏顶）：
 * - depth 大者先画；
 * - 同 depth：CBG 先、div 后（cbg 判式在逃逸段判式之前）；
 * - 正文在 `num7 == 0` 那一轮：该轮的 CBG 之后、该轮的 div 之前。
 *
 * 本文件的用例都直接断言 [CbgDrawOrder.merge] 的步骤序列，不做像素断言
 * （像素由 `CbgLayerComposeTest` / `CbgFrameButtonComposeTest` 覆盖）。
 */
class CbgDrawOrderTest {

    private fun cbg(depth: Int, name: String): CbgLayerSnapshot =
        CbgLayerSnapshot(
            depth = depth,
            x = 0,
            y = 0,
            spriteName = name,
            gid = -1,
            widthPx = 10,
            heightPx = 10,
        )

    private fun block(depth: Int, width: Int = 10): DivOverlayPlanner.Block =
        DivOverlayPlanner.Block(
            depth = depth,
            borderRectPx = DivOverlayPlanner.Rect(0, 0, width, width),
            backgroundArgb = null,
            borderWidthsPx = emptyList(),
            borderColorArgb = emptyList(),
            radiusPx = emptyList(),
            contentBoxPx = DivOverlayPlanner.Rect(0, 0, width, width),
            children = emptyList(),
            isRelative = false,
        )

    /** 步骤的紧凑标识（断言用；depth 也带上，免得只比类型漏掉排序错）。 */
    private fun tags(steps: List<CbgDrawOrder.Step>): List<String> = steps.map { step ->
        when (step) {
            is CbgDrawOrder.Step.Cbg -> "cbg${step.layer.depth}:${step.layer.spriteName}"
            is CbgDrawOrder.Step.Sprite -> "spr${step.placement.zdepth}:${step.placement.name}"
            is CbgDrawOrder.Step.Div -> "div${step.block.depth}@${step.blockIndex}"
            is CbgDrawOrder.Step.Text -> "text${step.depth}"
        }
    }

    /**
     * 正侧的跨类型先后：**CBG depth=1 + div depth=2 ⇒ 先 div2 再 CBG1**。
     *
     * 这是旧两段式（先全部 CBG → 再 div behind）画反的那一格：原版 num7 取两侧头的
     * 最大值，depth 2 的逃逸段先于 depth 1 的 CBG 落笔。
     */
    @Test
    fun `CBG depth1 与 div depth2 先画 div2`() {
        val steps = CbgDrawOrder.merge(
            cbgLayers = listOf(cbg(1, "BG1")),
            divBehind = listOf(block(2)),
        )
        assertEquals(listOf("div2@0", "cbg1:BG1", "text0"), tags(steps))
    }

    /** 反向：div depth=1 + CBG depth=2 ⇒ 先 CBG2 再 div1（同一套 max 归并，不是「div 恒先」）。 */
    @Test
    fun `CBG depth2 与 div depth1 先画 CBG2`() {
        val steps = CbgDrawOrder.merge(
            cbgLayers = listOf(cbg(2, "BG2")),
            divBehind = listOf(block(1)),
        )
        assertEquals(listOf("cbg2:BG2", "div1@0", "text0"), tags(steps))
    }

    /** 负侧同理：CBG depth=-1 + div front depth=-2 ⇒ 先 CBG-1（depth 更大）再 div-2，正文在两者之前。 */
    @Test
    fun `负侧 CBG depth 大者先画且正文在两者之前`() {
        val steps = CbgDrawOrder.merge(
            cbgLayers = listOf(cbg(-1, "BG_A")),
            divFront = listOf(block(-2)),
        )
        assertEquals(listOf("text0", "cbg-1:BG_A", "div-2@0"), tags(steps))
    }

    /**
     * 同 depth 的稳定序：CBG 侧保持入参序、div 侧保持入参序，且**逐轮交错**
     * （原版每轮各消费一个头：cbg#1 → div#1 → 下一轮 cbg#2 → div#2）。
     */
    @Test
    fun `同 depth 逐轮交错且各自稳定`() {
        val steps = CbgDrawOrder.merge(
            cbgLayers = listOf(cbg(5, "A"), cbg(5, "B")),
            divBehind = listOf(block(5, width = 1), block(5, width = 2)),
        )
        assertEquals(
            listOf("cbg5:A", "div5@0", "cbg5:B", "div5@1", "text0"),
            tags(steps),
        )
    }

    /** depth 0 的 div 属 front（`DivOverlayPlanner` 的 `depth > 0 ⇒ behind`），正文压在它**下**面。 */
    @Test
    fun `depth 0 的 div 画在正文之上`() {
        val steps = CbgDrawOrder.merge(
            cbgLayers = emptyList(),
            divFront = listOf(block(0)),
        )
        assertEquals(listOf("text0", "div0@0"), tags(steps))
    }

    /** 全空 ⇒ 只有一步正文（`composeInto` 的快路径等价物）。 */
    @Test
    fun `全空只有一步正文`() {
        assertEquals(listOf("text0"), tags(CbgDrawOrder.merge(emptyList())))
    }

    /**
     * div 的 blockIndex 按 `behind + front` 拼接序给（`ButtonLayer.DivFocus.blockIndex` 的
     * 同一套编号，`DivOverlayFrame` 的 `all` 口径）：front 的第一颗接在 behind 之后。
     */
    @Test
    fun `div 的 blockIndex 按 behind 加 front 拼接序`() {
        val steps = CbgDrawOrder.merge(
            cbgLayers = emptyList(),
            divBehind = listOf(block(9), block(8)),
            divFront = listOf(block(-1)),
        )
        assertEquals(listOf("div9@0", "div8@1", "text0", "div-1@2"), tags(steps))
    }

    /**
     * 立绘与 CBG 走同一侧：同 depth 时 CBG 先（既有口径，`ScreenComposer` 的两段式注释
     * 「同深度时先画 CBG」），depth 不同则大者先；立绘与 div 也按同一条 depth 序交错。
     */
    @Test
    fun `立绘与 CBG div 同一条 depth 序`() {
        val steps = CbgDrawOrder.merge(
            cbgLayers = listOf(cbg(3, "BG")),
            divBehind = listOf(block(4)),
            sprites = listOf(
                SpritePlacement("S8", 0, 0, 8),
                SpritePlacement("S3", 0, 0, 3),
            ),
        )
        assertEquals(listOf("spr8:S8", "div4@0", "cbg3:BG", "spr3:S3", "text0"), tags(steps))
    }

    /** `Int.MIN_VALUE` 是合法 depth（语料确有），不许被当成「这一侧空了」的哨兵吞掉。 */
    @Test
    fun `IntMIN 的 depth 不会被当成空侧`() {
        val steps = CbgDrawOrder.merge(
            cbgLayers = listOf(cbg(Int.MIN_VALUE, "BOTTOM")),
        )
        assertEquals(listOf("text0", "cbg-2147483648:BOTTOM"), tags(steps))
        assertTrue(steps.last() is CbgDrawOrder.Step.Cbg)
    }
}
