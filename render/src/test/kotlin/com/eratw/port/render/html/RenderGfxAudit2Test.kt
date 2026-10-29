package com.eratw.port.render.html

import com.eratw.port.render.sprite.DrawLineBar
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 审计第二轮 L15（HTML 文档 / 精灵 / 行合并）的边界用例 —— 每条钉一处**修复前会错**的读数，
 * 或钉一处「核验过但按 ADR-005 不修」的官方口径。
 *
 * 高置信缺陷只在 [Raster] 与 [SpriteTable] 上（1-3 号用例）；`DrawLineBar` / `RowUnionFind`
 * 逐行对拍后未发现可修项 —— 前者的空 builder 崩点与「零可绘宽直接返回一个单元」都是官方行为
 * （5 号用例钉住），`RowUnionFind` 的合并口径已由 `displaylist/DirtyStripsTest` 逐例覆盖，
 * 本类不重复。`MiniHtmlDoc` 的两处口径分歧见 6-7 号用例与交付报告。
 *
 * 判据全部来自反编译 C#：
 * - `GraphicsImage.cs:502-509` 的 `GGetColor` 判完空位图（`:504-506`）就直通
 *   `Bitmap.GetPixel`（`:508`），判界在 GDI+ 侧；
 * - `ASpriteSingle.cs:50-64` 的精灵侧读色越界**返回 Transparent**（`:59-62`），
 *   与 [Raster.pixelAt] 是两条口径，不能混用；
 * - `AppContents.cs:82-85` / `:98-101` 的两个建精灵入口对空名字抛
 *   `ArgumentOutOfRangeException`（抛点 `:84`/`:100`；CSV 侧空名行整行跳过，`:240-243`）；
 * - `AppContents.cs:58-61` 的 `SpriteDispose` 先 `value.Dispose()`（`:60`），
 *   `ASpriteSingle.cs:66-69` 把 `BaseImage` 置 null（`:68`）⇒ `IsCreated` 恒 false
 *   （`:24-34`）；`SpriteDisposeAll(false)` 只把资源表整份拷回运行时表（`AppContents.cs:76`），
 *   拷回的是**同一个已 Dispose 的对象** ⇒ `SPRITEDISPOSE` 对它返回 0
 *   （`FunctionMethodCreator.cs:7087-7090` 的 `!sprite.IsCreated` 守卫，过了守卫才在 `:7091`
 *   调 `SpriteDispose`）；
 * - 绘制侧同一对守卫：`EmueraConsole.cs:1844`（`aSprite != null && aSprite.IsCreated`）、
 *   `ConsoleImagePart.cs:168`；
 * - `getStBar` 的追加/删除循环：`EmueraConsole.cs:3422-3429` 与 `:3431-3435`，
 *   `num` 初值 0 在 `:3424`，空串测量恒 0 在 `StringMeasure.cs:53-55`。
 */
class RenderGfxAudit2Test {

    private val opaque = 0xFF102030.toInt()

    /**
     * [Raster.pixelAt] 的四个越界方向都必须抛，而不是静默读到**另一个像素**。
     *
     * 行主序 `y * width + x` 让 `x == width` 落进下一行行首、`x == -1` 落进上一行行尾，
     * 两者都是合法数组下标 —— 未判界时这里返回的是邻行像素（修复前的实际行为，
     * 连 alpha 一起读错）。
     */
    @Test
    fun `pixelAt 行主序越界不得读到相邻行`() {
        val r = Raster(3, 2)
        r.fillRect(0, 0, 3, 2, opaque)
        assertEquals(opaque, r.pixelAt(0, 0))
        assertEquals(opaque, r.pixelAt(2, 0))
        assertEquals(opaque, r.pixelAt(0, 1))
        assertEquals(opaque, r.pixelAt(2, 1))
        // x == width 的行主序下标是下一行行首；x == -1 的是上一行行尾
        assertFailsWith(IndexOutOfBoundsException::class, "x == width") { r.pixelAt(3, 0) }
        assertFailsWith(IndexOutOfBoundsException::class, "x == -1") { r.pixelAt(-1, 1) }
        assertFailsWith(IndexOutOfBoundsException::class, "y == height") { r.pixelAt(0, 2) }
        assertFailsWith(IndexOutOfBoundsException::class, "y == -1") { r.pixelAt(0, -1) }
        assertFailsWith(IndexOutOfBoundsException::class, "x == width 且 y 在界内") { r.pixelAt(3, 1) }
        assertFailsWith(IndexOutOfBoundsException::class, "x 负得足够多时下标也是正的") { r.pixelAt(-3, 0) }
        assertFailsWith(IndexOutOfBoundsException::class, "零尺寸面什么都读不到") { Raster(0, 0).pixelAt(0, 0) }
    }

    /**
     * 修复不得扩大化：绘制侧仍然**静默裁剪**（原版 GDI+ 的裁剪区域，绘制入口一个都不抛），
     * 否则每一帧都会因为一个窗外像素崩掉。
     */
    @Test
    fun `绘制侧越界仍静默裁剪不抛`() {
        val r = Raster(2, 1)
        r.blend(-1, 0, opaque)
        r.blend(2, 0, opaque)
        r.blend(0, 1, opaque)
        r.fillRect(-5, -5, 4, 4, opaque)
        r.fillRect(2, 0, 3, 1, opaque)
        r.drawLine(-3, -3, -1, -1, opaque)
        r.drawLine(2, 0, 7, 0, opaque)
        r.blit(9, 9, Raster(1, 1).apply { fillRect(0, 0, 1, 1, opaque) })
        assertEquals(0, r.pixelAt(0, 0), "四个方向的窗外绘制都不许落到界内像素上")
        assertEquals(0, r.pixelAt(1, 0))
    }

    /**
     * 空名字按原版拒绝（`AppContents.cs:82-85`、`:98-101`），且失败的登记不占 id
     * （原版先抛后建：判据 `:82` 在建对象 `:87` 之前）。空白名不算空，照原版放行
     * （`IsNullOrEmpty` 只挡 null 与空串）。
     */
    @Test
    fun `add 空名字按原版拒绝且不消耗 id`() {
        val t = SpriteTable()
        assertFailsWith(IllegalArgumentException::class, "空名脚本侧") { t.add("", Raster(1, 1)) }
        assertFailsWith(IllegalArgumentException::class, "空名 CSV 侧") { t.add("", Raster(1, 1), fromCsv = true) }
        assertEquals(0, t.size, "失败的登记不得留在运行时表")
        assertEquals(0, t.resourceSize, "失败的登记不得留在资源表")
        assertNull(t.find(""), "空键不得命中（原版 GetSprite(\"\") 恒 null）")
        assertEquals(1, t.add("GSPR", Raster(1, 1)), "被拒的空名不消耗 id 号")
        assertEquals(2, t.add(" ", Raster(1, 1)), "空白名不是空串，原版放行")
        assertNotNull(t.find(" "))
    }

    /**
     * 已释放的资源条目：回滚只复活**字典键**，条目本身仍是死条目。
     *
     * `dispose` → `Dispose()`（`ASpriteSingle.cs:66-69`）；`disposeAll(false)` 把资源表整份
     * 拷回运行时表（`AppContents.cs:76`）⇒ [SpriteTable.find] 非空（原版 `GetSprite` 拿到的是
     * 死对象），但 [SpriteTable.entry] 必须按 `IsCreated == false` 给 null
     * （`ASpriteSingle.cs:24-34`），再 `dispose` 返回 0 且**不动表**（原版在守卫处就返回了，
     * `FunctionMethodCreator.cs:7087-7090`，压根不会去调 `AppContents.SpriteDispose`）。
     */
    @Test
    fun `资源条目释放后回滚只复活字典键 条目仍是死条目`() {
        val t = SpriteTable()
        val res = t.add("res_a", Raster(2, 2), fromCsv = true)
        assertEquals(1, t.dispose(res))
        assertEquals(-1, t.disposeAll(), "count(0) - count2(1)：官方减法照抄")
        assertEquals(1, t.size, "资源表副本回滚进运行时表")
        val resurrected = t.find("RES_A")
        assertNotNull(resurrected, "GetSprite 侧非空：键确实复活了")
        assertNull(t.entry(res), "但对象已 Dispose，IsCreated == false")
        // 绘制侧守卫读的就是这个谓词（EmueraConsole.cs:1844 / ConsoleImagePart.cs:168）
        assertFalse(resurrected.isCreated, "键复活不等于可画")
        assertEquals(0, t.dispose(res), "SPRITEDISPOSE 对未创建对象返回 0")
        assertEquals(1, t.size, "返回 0 的释放不得动运行时表")
        assertSame(resurrected, t.find("RES_A"), "回滚拷回的是同一个对象，不是新副本")
    }

    /**
     * 从未释放过的资源条目：回滚后仍是活条目、可按原 id 再释放
     * （确认修复没有扩大成「一释放全死」）。非资源条目则不走回滚。
     */
    @Test
    fun `未释放过的资源条目回滚后仍可再释放 非资源条目彻底消失`() {
        val t = SpriteTable()
        val res = t.add("res_b", Raster(1, 1), fromCsv = true)
        val user = t.add("user", Raster(1, 1))
        assertEquals(1, t.disposeAll(), "count(2) - count2(1)：纯减法口径（AppContents.cs:68-69/77）")
        assertNotNull(t.entry(res))
        assertTrue(checkNotNull(t.find("RES_B")).isCreated)
        assertNull(t.find("USER"), "非资源条目不回滚（AppContents.cs:76 只拷资源表）")
        assertNull(t.entry(user))
        assertEquals(0, t.dispose(user))
        assertEquals(1, t.dispose(res))
        assertNull(t.find("RES_B"))
    }

    /**
     * 同名覆盖不释放旧对象、旧 id 立即失效（`AppContents.cs:88/104` 的赋新语义）。
     * 覆盖不是释放 ⇒ 新条目照旧可画，旧条目也不该被误标成死条目。
     */
    @Test
    fun `同名覆盖后旧 id 失效 新条目仍存活`() {
        val t = SpriteTable()
        val old = t.add("dup", Raster(1, 1))
        val new = t.add("DUP", Raster(2, 2))
        assertNull(t.entry(old), "旧 id 被同名新项挡住（GetSprite 只会返回新对象）")
        val e = checkNotNull(t.find("DUP"))
        assertEquals(new, e.id)
        assertEquals(2, e.width)
        assertTrue(e.isCreated, "原版覆盖时不对旧对象调 Dispose()，新对象自然仍可用")
        assertEquals(1, t.size, "覆盖不新增键")
        assertEquals(1, t.dispose(new))
        assertFalse(e.isCreated, "释放即打 IsCreated=false 的戳")
    }

    /**
     * 尺寸判据归上层，本表只守名字：原版对尺寸的把关全在建精灵入口的**上一层**
     * （`SPRITECREATE` 的 `FunctionMethodCreator.cs:7044-7047` 不相交即抛、CSV 侧的
     * `AppContents.cs:257-261/316-325` 非正或不相交整行弃），到 `AppContents.CreateSpriteG`
     * （`:80-89`）只剩空名守卫 ⇒ 本层 [SpriteTable.add] 同样只拒空名，`Raster(0, 0)` 合法。
     */
    @Test
    fun `零尺寸登记合法 尺寸把关在上层`() {
        val t = SpriteTable()
        val zero = t.add("zero", Raster(0, 0))
        assertEquals(0, checkNotNull(t.entry(zero)).width)
        assertEquals(0, checkNotNull(t.entry(zero)).height)
        assertTrue(checkNotNull(t.find("ZERO")).isCreated, "原版没有「尺寸为 0 ⇒ 未创建」这一判据")
        assertEquals(1, t.dispose(zero))
        assertEquals(0, t.size)
    }

    /**
     * `getStBar` 的两处官方口径照抄（ADR-005，都**不修**）：
     *
     * 1. `drawableWidth == 0`：`num` 的初值是硬编码 0（`EmueraConsole.cs:3424`），两个循环的
     *    判据分别是 `< DrawableWidth`（`:3426`）与 `> DrawableWidth`（`:3431`），0 与 0 比较
     *    两边都不成立 ⇒ 一次都不测，直接返回循环外那一次 `Append`（`:3423`）的**整个重复单元**
     *    （哪怕它宽 9px > 0）。
     * 2. `drawableWidth < 0`：追加循环同样不进，删除循环先把那一次 `Append` 删空、再对空串
     *    `Remove(Length-1, 1)`（`:3433`）—— 空串测量恒 0（`StringMeasure.cs:53-55`）使
     *    `0 > drawableWidth` 永远成立 ⇒ 原版自己也在这里越界抛，本层同族抛出。
     */
    @Test
    fun `零与负可绘宽按官方各自落点`() {
        // shift = max(2, 18/6) = 3（Config.cs:461-465）⇒ clientWidth 3 → 可绘宽 0、2 → -1
        val zero = DrawLineBar(clientWidth = 3)
        assertEquals(0, zero.drawableWidth)
        assertEquals("-", zero.build("-"), "num 初值 0 ⇒ 不进任何循环，原样返回一个单元")
        assertEquals("-2", zero.build("-2"), "多字符单元同理：连测都不测")
        val neg = DrawLineBar(clientWidth = 2)
        assertEquals(-1, neg.drawableWidth)
        assertFailsWith(IndexOutOfBoundsException::class, "官方在空 builder 上 Remove(-1, 1)") { neg.build("-") }
        assertFailsWith(IndexOutOfBoundsException::class, "重复单元更长也一样删到越界") { neg.build("-2") }
    }

    /**
     * 已知口径分歧（登记，不修）：图片段不计入行宽。
     *
     * 原版给图片段算的是**像素宽**：sprite 在表里时按 `ConsoleImagePart.cs:66-81` 换算
     * （`SetWidth` 两个分支都不动它，`:104-115`），sprite 不在时把段文本换成 alt 串再整串实测
     * （`:61-64` 与 `:110-114`）。本层 [HtmlLine.cells] 一律记 0，所以带图行的
     * CENTER/RIGHT 起点会偏（`ConsoleDisplayLine.cs:80/83` 的分母同样按段宽求和）。
     */
    @Test
    fun `图片段不计行宽与原版像素宽的已知分歧已登记`() {
        val doc = MiniHtml().parse("<p align='right'>AB<img src='PORTRAIT' width='200' height='100'></p>")
        val line = doc.lines[0]
        assertEquals(2, line.cells, "文本 2 格；图片段记 0（原版按 width 属性算像素宽）")
        assertEquals("AB", line.plainText)
        assertEquals(1, line.segments.count { it is HtmlSegment.Image })
        val img = line.segments.filterIsInstance<HtmlSegment.Image>().single()
        assertEquals("PORTRAIT", img.src)
        assertEquals(200, img.width)
        assertEquals(100, img.height)
        assertFalse(img.widthIsPx, "无 px 后缀 ⇒ 原版按字号百分比算宽（ConsoleImagePart.cs:78）")
        assertEquals(8, doc.alignColumn(line.cells, 10), "本层起点只看文本格宽：10 - 2")
        assertEquals(2, line.runs.size, "文本段 + 图片段各一条 run")
        assertEquals("", img.run.text, "图片段 run 文本恒空串")
    }

    /**
     * 已知口径缺口（登记，待解析侧接线）：`ypos` 的 `px` 标志没进模型。
     *
     * 原版 ypos 与宽高分两路（`ConsoleImagePart.cs:81` 的 `isPx ? num : num * FontSize / 100`），
     * 而解析侧只取数值（`MiniHtml.kt:402-405`）⇒ `'40px'` 与 `'40'` 在本模型里**不可区分**。
     * 这里钉住这个不可区分性：解析侧补上标志后本用例即红，逼着模型与解析同时改。
     */
    @Test
    fun `ypos 的 px 标志目前落在模型之外`() {
        val withPx = MiniHtml().parse("<img src='A' ypos='40px'>").lines[0]
            .segments.filterIsInstance<HtmlSegment.Image>().single()
        val bare = MiniHtml().parse("<img src='A' ypos='40'>").lines[0]
            .segments.filterIsInstance<HtmlSegment.Image>().single()
        assertEquals(40, withPx.yPos, "数值仍按 MixedNum 解析（Utils.cs:347-354）")
        assertEquals(bare.yPos, withPx.yPos, "px 与否算出的 ypos 相同 ⇒ 标志确实没落进模型")
        assertEquals(bare.widthIsPx, withPx.widthIsPx, "未给 width 时两者都是默认 false")
        // 同一份 MixedNum 逻辑对 width 是留了标志的，对照出 ypos 这一路的缺失
        assertTrue(MiniHtml().parse("<img src='A' width='40px'>").lines[0]
            .segments.filterIsInstance<HtmlSegment.Image>().single().widthIsPx)
    }
}
