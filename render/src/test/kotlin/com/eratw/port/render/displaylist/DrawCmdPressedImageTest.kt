package com.eratw.port.render.displaylist

import com.eratw.port.render.RunKind
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * GFX-09 / GFX-10 的数据层钉子：按下态选图与图形段按下判定的纯 JVM 部分。
 *
 * 口径出处：
 * - 按下换图判据 `(isSelecting || isFocus) && cImageB != null`：
 *   spec: `ConsoleImagePart.cs:163-167`；`cImageB == null`（GetSprite 未注册名，
 *   `AppContents.cs:46-48`）时保持主图。
 * - isSelecting/isFocus 的传递面：`ConsoleButtonString.cs:319-320`（ButtonIsSelected /
 *   ButtonIsPointing）+ `:363`（逐节点透传给按钮里的 img 节点）⇒ 移植侧按下判定只做
 *   命中区间与本段列区间的几何相交、不限 kind。
 * - altText 回落（render-canvas `drawImage` 的调用时序）对齐 `ConsoleImagePart.cs:175-182`，
 *   那半段在 Android 侧，纯 JVM 只钉数据层判式。
 */
class DrawCmdPressedImageTest {

    private val image = InlineGraphic.Image(
        resource = "MAP",
        widthPx = 40,
        heightPx = 18,
        yOffsetPx = 0,
        widthCells = 5,
        buttonResource = "MAP_B",
        altText = "<img src='MAP' srcb='MAP_B'>",
    )

    @Test
    fun drawResourceKeepsMainWhenNotPressed() {
        // spec: ConsoleImagePart.cs:163 —— aSprite 先取 cImage，非按下态一律主图
        assertEquals("MAP", image.drawResource(pressed = false))
        assertEquals("MAP", image.copy(buttonResource = null).drawResource(pressed = false))
    }

    @Test
    fun drawResourceSwitchesToButtonWhenPressed() {
        // spec: ConsoleImagePart.cs:164-167 —— isSelecting/isFocus 且 cImageB != null ⇒ cImageB
        assertEquals("MAP_B", image.drawResource(pressed = true))
    }

    @Test
    fun drawResourceFallsBackToMainWhenButtonMissing() {
        // spec: ConsoleImagePart.cs:164 的 null 判 + AppContents.cs:46-48 ——
        // GetSprite 未注册名返回 null ⇒ cImageB == null ⇒ 按下仍画主图
        assertEquals("MAP", image.copy(buttonResource = null).drawResource(pressed = true))
    }

    @Test
    fun pressedDataChannelIsCarriedOnTheSegment() {
        // GFX-09/GFX-10 数据通道：flatten 侧喂的 srcb 与重建文案原样挂在段上
        assertEquals("MAP_B", image.buttonResource)
        assertEquals("<img src='MAP' srcb='MAP_B'>", image.altText)
        val bare = InlineGraphic.Image("X", 1, 1, 0, 1)
        assertEquals(null, bare.buttonResource, "不带 srcb 的普通图，按下不换图")
        assertEquals(null, bare.altText, "生产侧没给文案时缺帧回落保持静默")
    }

    @Test
    fun graphicPressedFollowsHitIntersection() {
        // 命中区间与本段列区间半开相交（口径同 drawCmd 文本段的按下判式）
        val cmd = DrawCmd(
            row = 3, colStart = 10, colEnd = 15,
            fore = 0, back = 0, styleMask = 0, kind = RunKind.IMAGE,
            glyphs = emptyList(),
            graphic = image,
        )
        assertTrue(InlineGraphic.graphicPressed(cmd, 3, 8, 12), "区间相交")
        assertTrue(InlineGraphic.graphicPressed(cmd, 3, 10, 20), "命中区间盖住段头")
        assertTrue(InlineGraphic.graphicPressed(cmd, 3, 0, 15), "命中区间盖住段尾（右开）")
        assertFalse(InlineGraphic.graphicPressed(cmd, 3, 5, 10), "左贴边不相交（半开区间）")
        assertFalse(InlineGraphic.graphicPressed(cmd, 3, 15, 20), "右贴边不相交（半开区间）")
        assertFalse(InlineGraphic.graphicPressed(cmd, 3, 0, 5), "完全分离")
        assertFalse(InlineGraphic.graphicPressed(cmd, 2, 0, 20), "行不同不命中")
        assertFalse(InlineGraphic.graphicPressed(cmd, -1, 0, 20), "无按下（-1）不命中")
    }

    @Test
    fun graphicPressedIgnoresSegmentKind() {
        // spec: ConsoleButtonString.cs:363 —— 按钮里 img 节点与文本片共享 isSelecting，
        // 判定只看几何，不问段的 kind（IMAGE 段不是 BUTTON 也要能按下）
        val shape = DrawCmd(
            row = 0, colStart = 0, colEnd = 4,
            fore = 0, back = 0, styleMask = 0, kind = RunKind.SHAPE,
            glyphs = emptyList(),
            graphic = InlineGraphic.Rect(0, 0, 10, 10, 0xFF000000.toInt(), 4),
        )
        assertTrue(InlineGraphic.graphicPressed(shape, 0, 0, 4))
        val text = DrawCmd(
            row = 0, colStart = 0, colEnd = 4,
            fore = 0, back = 0, styleMask = 0, kind = RunKind.TEXT,
            glyphs = emptyList(),
            graphic = null,
        )
        assertTrue(InlineGraphic.graphicPressed(text, 0, 0, 4), "kind 无关，文本段同样成立")
    }
}
