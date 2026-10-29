package com.eratw.port.render.view

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [GraphicGeometry] 的纯 JVM 判据 —— 这些数是「立绘/背景画在对不对」的全部内容，
 * 绘制侧（`:render-canvas`）在 Android 模块里跑不了单测，所以判据只能钉在这一层。
 *
 * 期望值全部由 `EmueraConsole.cs:947-952`（`BakeBackground`）与
 * `AppContents.cs:80-105`（`CreateSpriteG`）的算式手算得出，不是截图回归。
 */
class GraphicGeometryTest {

    private fun box(areaW: Float, areaH: Float, srcW: Float, srcH: Float) =
        GraphicGeometry.coverBox(areaW, areaH, srcW, srcH)

    @Test
    fun `竖图放在横屏上按宽缩放、纵向溢出裁掉`() {
        // EmueraConsole.cs:947-951 —— num=1800/800=2.25、num2=955/1200=0.7958…
        // flag = 基图高*num(=2700) < H(=955) ? false ⇒ 取 num ⇒ 宽填满、高溢出（cover 的本义）
        val b = box(1800f, 955f, 800f, 1200f)
        assertEquals(0f, b[0], 1e-3f, "x = (1800 - 1800)/2 = 0")
        assertEquals(0f, b[1], 1e-3f, "y 恒 0（:955 的 Rectangle(..., 0, ...)，顶对齐）")
        assertEquals(1800f, b[2], 1e-3f, "w = 800 * 2.25")
        assertEquals(2700f, b[3], 1e-3f, "h = 1200 * 2.25 —— 超出 955 的那截由画布裁掉")
    }

    @Test
    fun `横图放在竖屏上按高缩放、横向左右各溢出一半`() {
        // num = 800/1600 = 0.5、num2 = 1200/900 = 1.3333…
        // flag = 900*0.5(=450) < 1200 ? true ⇒ 取 num2 ⇒ 高填满、宽溢出且居中（x 为负）
        val b = box(800f, 1200f, 1600f, 900f)
        assertEquals(1200f / 900f * 1600f, b[2], 1e-2f, "w = 1600 * num2")
        assertEquals(1200f, b[3], 1e-2f, "h = 900 * num2 = 绘制区高")
        assertEquals((800f - b[2]) / 2f, b[0], 1e-3f, "x = (W - w)/2 ⇒ 负值，两侧等量溢出")
        assertEquals(-666.6667f, b[0], 1e-3f)
    }

    @Test
    fun `纵横比相同时不放大也不位移`() {
        val b = box(1000f, 500f, 1000f, 500f)
        assertEquals(listOf(0f, 0f, 1000f, 500f), b.toList())
    }

    @Test
    fun `两个缩放比相等时走 num 分支（flag 严格小于，不含等号）`() {
        // :949 是 `基图高*num < H`，等号不成立 ⇒ 取 num。这里 num == num2 == 2，取哪支同解，
        // 钉的是「等比时不出现半像素位移」这条。
        val b = box(1000f, 1000f, 500f, 500f)
        assertEquals(listOf(0f, 0f, 1000f, 1000f), b.toList())
    }

    @Test
    fun `sprite 子矩形原样平移，不夹到基图边界`() {
        // AppContents.cs:80-105 —— sprite 就是基图上的一块；越界那截交给 drawBitmap 拒绝
        val src = GraphicGeometry.sourceRect(srcX = 100, srcY = 50, srcW = 200, srcH = 300, bitmapW = 64, bitmapH = 64)
        assertEquals(listOf(100, 50, 300, 350), src.toList())
    }

    @Test
    fun `子矩形尺寸非正时退回整幅基图`() {
        assertEquals(listOf(0, 0, 640, 480), GraphicGeometry.sourceRect(3, 4, 0, 9, 640, 480).toList())
        assertEquals(listOf(0, 0, 640, 480), GraphicGeometry.sourceRect(3, 4, 9, -1, 640, 480).toList())
    }

    @Test
    fun `脚本给的像素尺寸优先，非正才退回帧自带尺寸`() {
        // ConsoleImagePart.cs:66-80 —— width/height 缺省由基图纵横比推出正数，
        // 到这一层已经全是像素值；非正只剩「没给」这一种含义
        assertEquals(120, GraphicGeometry.inlineSize(120, 64))
        assertEquals(64, GraphicGeometry.inlineSize(0, 64))
        assertEquals(64, GraphicGeometry.inlineSize(-5, 64))
    }

    @Test
    fun `立绘所在纯图片行未被压缩时按脚本尺寸原样画，不再压成行高`() {
        // 2026-10-26 真机 Look 页实测：180px 立绘、执行行高 21px、rowSpan=0 ⇒ 旧口径 k=21/180，
        // 屏上只剩 16×21px。原版行距恒为 Config.LineHeight（EmueraConsole.cs:1855）而
        // ConsoleImagePart.cs:159-174 按 destRect 画 ⇒ 图向下溢出到 ERB 预留的空行。
        assertEquals(1f, GraphicGeometry.artRowScale(180f, 21f, 21f, 0), 1e-6f)
        assertEquals(1f, GraphicGeometry.artRowScale(360f, 21f, 21f, 0), 1e-6f, "尺寸比 200% 时同样不缩")
    }

    @Test
    fun `行高被 rowMetrics 压到低于执行行高时（标题图横带）仍按行高等比缩`() {
        // rowMetrics 的 artAdvance = fit.coerceIn(6f, lineHeightPx)：一屏塞不下时 < 21。
        // 16px 横带落在 6px 行里 ⇒ k = 6/16，带宽正好铺满行距，相邻横带不互相覆盖。
        val k = GraphicGeometry.artRowScale(16f, 6f, 21f, 0)
        assertEquals(0.375f, k, 1e-6f)
    }

    @Test
    fun `图高于一行时无论行高怎么压都不缩（立绘在窄屏大字号下仍是 180px）`() {
        // 这是上一版（只判 rowHeight 小于 lineHeight）的复燃路径：整屏文字多到
        // remain / artRows 只差分毫，artAdvance 变成 20.99 ⇒ 180px 又会被压成 20.99px。
        // 原版 ConsoleImagePart.cs:159-174 从不按行高缩图，超出的部分由画布裁掉。
        assertEquals(1f, GraphicGeometry.artRowScale(180f, 6f, 21f, 0), 1e-6f)
        assertEquals(1f, GraphicGeometry.artRowScale(180f, 20.99f, 21f, 0), 1e-6f)
    }

    @Test
    fun `行高与执行行高只差浮点残差时不算压缩`() {
        // coerceIn 命中上限时返回的就是 lineHeight 本身；留 0.5px 容差吸收 1e-6 级残差
        assertEquals(1f, GraphicGeometry.artRowScale(21f, 20.8f, 21f, 0), 1e-6f)
        // 真被压过 0.5px 以上才缩
        assertEquals(20f / 21f, GraphicGeometry.artRowScale(21f, 20f, 21f, 0), 1e-6f)
    }

    @Test
    fun `合并竖带块（rowSpan 非 0）刻意跨行，任何行高都不缩`() {
        assertEquals(1f, GraphicGeometry.artRowScale(37f, 6f, 21f, 5), 1e-6f)
        assertEquals(1f, GraphicGeometry.artRowScale(1000f, 2f, 21f, 1), 1e-6f)
    }

    @Test
    fun `图比行矮时不缩（标题横带 16px 落在 21px 行内）`() {
        assertEquals(1f, GraphicGeometry.artRowScale(16f, 21f, 21f, 0), 1e-6f)
    }

    @Test
    fun `行高非正时不做除法，返回原尺寸`() {
        assertEquals(1f, GraphicGeometry.artRowScale(180f, 0f, 21f, 0), 1e-6f)
        assertEquals(1f, GraphicGeometry.artRowScale(180f, 21f, 0f, 0), 1e-6f)
    }
}
