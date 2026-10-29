package com.eratw.port.render.view

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

/**
 * [GraphicGeometry.spriteDestRect] 的纯 JVM 判据：图在**内联框里**落在哪、画多大。
 *
 * 这一式此前整段缺失 —— `CanvasConsoleView.drawImage` 直接把源子矩形铺满整个内联框，
 * 于是 `ASprite.DestBasePosition` 与 `AnimeFrame.Offset` 全部丢掉、`SrcRectangle` 相对
 * `DestBaseSize` 的占比也不缩。判据里的数全部取自语料真值 + 原版算式手算：
 *
 * - 静态：`ASpriteSingle.cs:79-85`（`!DestBasePosition.IsEmpty` 门住整段）
 * - 动画：`SpriteAnime.cs:148-159`（同样的四式，无门，位移叠 `currentFrame.Offset`）
 * - `param` 位序：`ERB/リソース管理/ResourceManager.ERB` 的 `RM_PARSE_PARAMS`
 *   （`x, y, w, h, xpos, ypos, delay, destw, desth`），动画侧再转发给
 *   `SPRITEANIMEADDFRAME name, gid, x, y, w, h, xpos, ypos, delay`
 *
 * 除法一律是 C# 的 `int` 整数除法（向零截断），所以期望值必须是手算的整数值，
 * 不能写成浮点近似 —— 写成近似就钉不住「先乘后除、不升 Long」这条。
 */
class SpriteDestRectTest {

    private fun place(
        boxW: Int,
        boxH: Int,
        baseW: Int,
        baseH: Int,
        basePosX: Int = 0,
        basePosY: Int = 0,
        offsetX: Int = 0,
        offsetY: Int = 0,
        srcW: Int = 0,
        srcH: Int = 0,
        anime: Boolean = false,
    ) = GraphicGeometry.spriteDestRect(
        boxW, boxH, baseW, baseH, basePosX, basePosY, offsetX, offsetY, srcW, srcH, anime,
    )

    @Test
    fun `动画帧 xpos 非零 —— 留琴-服 別立ち2_服_笑顔动_2`() {
        // resources/l13/留琴/留琴-服/anime.xml：<a width="734" height="734">
        //   每帧 param="0,0,704,734,15,0,42" ⇒ src 704x734、xpos=15、delay=42
        // 丢偏移时这里是 (0,0,734,734)：横向偏 15px，且 704 宽被拉成 734 宽（+4.3%）。
        assertArrayEquals(
            intArrayOf(15, 0, 704, 734),
            place(734, 734, 734, 734, offsetX = 15, srcW = 704, srcH = 734, anime = true),
        )
    }

    @Test
    fun `动画帧 ypos 非零 —— 留琴-兔女郎 別立ち2_服_バニー_笑顔动_2`() {
        // resources/l13/留琴/留琴-兔女郎/anime.xml：<a width="752" height="752">
        //   param="0,0,752,752,0,80,42" ⇒ ypos=80
        // srcH 是 Normalize 裁过之后的值（80+752 超 752 ⇒ 672，见 SpriteAnime.cs:24-36）。
        assertArrayEquals(
            intArrayOf(0, 80, 752, 672),
            place(752, 752, 752, 752, offsetY = 80, srcW = 752, srcH = 672, anime = true),
        )
    }

    @Test
    fun `静态 sprite 的 xpos ypos —— As06MARK-IV 拼接件`() {
        // resources/As06MARK-IV/拼接.xml：
        //   <i name="102AS06MARKIV_服_浴衣_表情_イキ顔" param="2462,271,95,104,193,49,,500,500">
        // 95x104 的小件要塞进 500x500 画布的 (193,49)。丢偏移时会被放大成 500x500 铺满整格。
        assertArrayEquals(
            intArrayOf(193, 49, 95, 104),
            place(500, 500, 500, 500, basePosX = 193, basePosY = 49, srcW = 95, srcH = 104),
        )
    }

    @Test
    fun `静态 sprite pos 为 0 时整段算式被 IsEmpty 门住（连缩都不缩）`() {
        // ASpriteSingle.cs:79 —— `if (!DestBasePosition.IsEmpty)` 把位移和缩放一起门掉。
        // 所以 pos=(0,0) 时源子矩形被**拉满**整个框，哪怕它比 DestBaseSize 小。
        assertArrayEquals(
            intArrayOf(0, 0, 180, 180),
            place(180, 180, 500, 500, srcW = 95, srcH = 104),
        )
    }

    @Test
    fun `动画侧没有那道门 —— pos 为 0 也要按子矩形占比缩`() {
        // SpriteAnime.cs:148-159 与 ASpriteSingle 的唯一差别就是少了 IsEmpty 判断。
        // 同一组数换成 anime=true，宽高就要缩成 180*95/500=34、180*104/500=37。
        assertArrayEquals(
            intArrayOf(0, 0, 34, 37),
            place(180, 180, 500, 500, srcW = 95, srcH = 104, anime = true),
        )
    }

    @Test
    fun `框比基准小的时候位移按同比例缩（整数向零截断）`() {
        // 193*180 = 34740，/500 = 69.48 ⇒ 69
        // 49*180  = 8820， /500 = 17.64 ⇒ 17
        assertArrayEquals(
            intArrayOf(69, 17, 34, 37),
            place(180, 180, 500, 500, basePosX = 193, basePosY = 49, srcW = 95, srcH = 104),
        )
    }

    @Test
    fun `负位移向零截断，与 C# 的 int 除法同号`() {
        // C# `-50 * 100 / 300` = -5000/300 = -16（向零截断，不是 floor 的 -17）。
        // Kotlin Int 除法同为向零截断，所以两边必须一致 —— 换成浮点再取整就会分道。
        assertArrayEquals(
            intArrayOf(-16, -16, 100, 100),
            place(100, 100, 300, 300, basePosX = -50, basePosY = -50, srcW = 300, srcH = 300),
        )
    }

    @Test
    fun `DestBaseSize 非正时退回框即落位（原版此处除零崩）`() {
        // ASpriteSingle.cs:81-84 的分母是 DestBaseSize.Width/Height，为 0 时原版抛
        // DivideByZeroException 带走整帧；本层按「不崩」处理，观感上等价于没设过 pos。
        assertArrayEquals(intArrayOf(0, 0, 100, 100), place(100, 100, 0, 500, basePosX = 10, basePosY = 10, srcW = 50, srcH = 50))
        assertArrayEquals(intArrayOf(0, 0, 100, 100), place(100, 100, 500, 0, basePosX = 10, basePosY = 10, srcW = 50, srcH = 50))
    }

    @Test
    fun `源子矩形非正时该轴退回框尺寸（原版乘出 0 宽矩形，GDI 静默不画）`() {
        assertArrayEquals(
            intArrayOf(5, 5, 100, 100),
            place(100, 100, 200, 200, basePosX = 10, basePosY = 10, srcW = 0, srcH = 0, anime = true),
        )
    }

    @Test
    fun `srcW 与 destW 相等时算式退化成纯平移`() {
        // 语料绝大多数静态资源是这种形态（param="0,0,180,180" 且 destW=180），
        // 加了这一步也不该改变它们的落位 —— 这是「不误伤既有画面」的底线判据。
        assertArrayEquals(
            intArrayOf(0, 0, 180, 180),
            place(180, 180, 180, 180, basePosX = 0, basePosY = 0, srcW = 180, srcH = 180),
        )
        assertArrayEquals(
            intArrayOf(12, 24, 180, 180),
            place(180, 180, 180, 180, basePosX = 12, basePosY = 24, srcW = 180, srcH = 180),
        )
    }
}
