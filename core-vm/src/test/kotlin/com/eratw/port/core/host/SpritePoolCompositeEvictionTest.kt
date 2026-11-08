package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SpritePool.evictCompositedSpritesWithinBudget] —— **合成缓冲档**（第三档）的判据。
 *
 * ## 这一档为什么必须存在（2026-11-04 端点收件箱 91 份，fix-hiresmem 车道）
 *
 * 0.2.39/0.2.40 的静默死（`LOW_MEMORY` ×8 / `CRASH_NATIVE signal=6` ×5 / `iAwareF[LowMem]` ×3）
 * 前置读数逐字相同：Java 堆 96–99%，`Java 堆结构账面` 里**正号段像素 250–309 MiB**，而预算
 * = 堆上限 ÷4 = 128–170 MiB（[GraphicsPool.PIXEL_BUDGET_DIVISOR]）。抛点全落在 4.981 的
 * 差分立绘合成链（`画像合成` ← `HIGHRES_GET_BASE_RESOURCE` ← `LOOK_AT`/`PRINT_ROSTER`，
 * Java 侧 [GraphicsBuffer.create] / [GraphicsBuffer.blitFrom]）。同一批报告里
 * `turn.trim.budget` 与 `midpage.trim.budget` **一行都没有** —— 前两档结构上看不见这批缓冲：
 * - [GraphicsBuffer.evictDeferred] 要 `deferredSource`，而 `GCREATE` 发的代没有它（合成图不可重解）；
 * - [GraphicsPool.releaseUnused] 的名单含 [SpritePool.referencedBuffers]，而语料把合成结果登记成了
 *   具名 Sprite（`SPRITECREATE OUT_STAND, GID`，4.981 语料 `ERB/BODY_INFO/HIGHRES_CHARA.ERB:185`）。
 *
 * 本测试钉「摘名字这一刀只在预算外动手，且不动那些本来就该重解的图」，逐条对应上面两个不可见性；
 * 不测水位（水位判据在宿主，见 `GameHost.evictCompositedSpritesIfOverBudget`）。
 *
 * 尺寸刻意用小图（4096 条像素）：预算是**字节**判据，逐出行为与真机 1000×1500 立绘同形，
 * 单测不拿真尺寸图烧堆（口径同 [SpritePoolBudgetTest] 类 KDoc）。
 */
class SpritePoolCompositeEvictionTest {

    private val side = 64
    private val sheetPixels = side.toLong() * side          // 4096 条
    private val sheetBytes = sheetPixels * 4L               // 16384 B

    /** 建一张「脚本合成出来」的缓冲：`GCREATE` 全尺寸 + 没有文件源（`@画像合成` 的形状）。 */
    private fun composited(pool: GraphicsPool, gid: Int): GraphicsBuffer =
        pool.get(gid).apply { create(side, side) }

    private fun sprite(name: String, buffer: GraphicsBuffer) =
        SpritePool.Sprite(name = name, width = side, height = side, buffer = buffer)

    @Test
    fun `堆上限不可知时一张都不动`() {
        val pool = GraphicsPool()
        val sprites = SpritePool()
        val a = composited(pool, 1)
        sprites.put(sprite("OUT_A", a))
        // 桌面装置的预算 = Long.MAX_VALUE（[GraphicsPool.defaultPixelBudgetBytes] 的不可知堆回落）
        // ⇒ 判据恒不成立，装载与基准行为逐字不变。
        val r = sprites.evictCompositedSpritesWithinBudget(pool.residentBytes(), Long.MAX_VALUE)
        assertEquals(0, r.sprites)
        assertEquals(0L, r.pixels)
        assertTrue(a.isCreated)
        assertTrue(sprites.find("OUT_A")!!.isCreated)
    }

    @Test
    fun `超预算按登记序逐出，回到预算内就停手`() {
        val pool = GraphicsPool()
        val sprites = SpritePool()
        val buffers = (1..4).map { composited(pool, it) }
        listOf("OLDEST", "MID1", "MID2", "NEWEST").forEachIndexed { i, name ->
            sprites.put(sprite(name, buffers[i]))
        }
        // 预算 = 两张的量 ⇒ 最旧的两张该出去，较新的两张该留下
        val r = sprites.evictCompositedSpritesWithinBudget(pool.residentBytes(), sheetBytes * 2)
        assertEquals(2, r.sprites, "逐到预算内即停，不是一次清表")
        assertEquals(sheetPixels * 2, r.pixels, "读数口径 = 像素条数（同 evictDeferredPixels）")
        assertNull(sprites.find("OLDEST"))
        assertNull(sprites.find("MID1"))
        assertEquals(buffers[2], sprites.find("MID2")?.buffer, "回到预算内的第一张起不许动")
        assertFalse(buffers[0].isCreated, "dispose 后 GCREATED=0 ⇒ 语料的空编号扫描能复用这个 gid")
        assertTrue(buffers[3].isCreated)
        assertTrue(
            pool.residentBytes() <= sheetBytes * 2,
            "收完之后驻留必须真的回到预算内（记账成对，不是只摘名字不释放）",
        )
    }

    @Test
    fun `文件源缓冲归上一档，这一档不摘它的名字`() {
        val pool = GraphicsPool()
        val sprites = SpritePool()
        // GCREATEFROMFILE 那一批：重解逐字得同一张图 ⇒ 归 evictDeferredPixelsWithinBudget；
        // 摘名字等于把「可重解」换成「必须重算」，纯亏。
        val sheet = pool.get(10)
        assertTrue(sheet.createDeferredFromFile(side, side, "resources/as06mark-iv.webp") { IntArray(side * side) })
        sprites.put(sprite("ANIME_SRC", sheet))

        val r = sprites.evictCompositedSpritesWithinBudget(sheetBytes, sheetBytes / 2)
        assertEquals(0, r.sprites, "带文件源的条目在候选集外，一个都不许动")
        assertEquals(sheet, sprites.find("ANIME_SRC")?.buffer)
        assertTrue(sheet.isCreated)
    }

    @Test
    fun `CBG 名单里的那张即便超预算也不许动`() {
        val pool = GraphicsPool()
        val sprites = SpritePool()
        val cbgLayer = composited(pool, 20)
        sprites.put(sprite("BG", cbgLayer))

        val r = sprites.evictCompositedSpritesWithinBudget(
            pool.residentBytes(),
            sheetBytes / 2,
            exclude = setOf(cbgLayer),
        )
        assertEquals(0, r.sprites, "CBG 底图是控制台背景，语料不会自己重算 ⇒ 摘了就是永久缺图")
        assertTrue(cbgLayer.isCreated)
    }

    @Test
    fun `资源表条目即便不进调用方名单也自动护住`() {
        val pool = GraphicsPool()
        val sprites = SpritePool()
        val csvParent = composited(pool, 22)
        sprites.putResource(sprite("CSV_RES", csvParent))

        // 预算 0 = 尽量退，但候选集只剩「运行时表里、无文件源、静态、未被保护」的那批；
        // 这里唯一的条目在资源表 ⇒ 一张都不该动。
        val r = sprites.evictCompositedSpritesWithinBudget(sheetBytes, 0L)
        assertEquals(0, r.sprites, "资源表引用的缓冲重解无损，归负号段那一档")
        assertTrue(csvParent.isCreated)
    }

    @Test
    fun `动画 sprite 不在首版的候选集里`() {
        val pool = GraphicsPool()
        val sprites = SpritePool()
        val frame = composited(pool, 30)
        sprites.put(
            SpritePool.Sprite(
                name = "ANIME",
                width = side,
                height = side,
                frames = mutableListOf(SpritePool.Frame(frame, 0, 0, side, side, 0, 0, 100)),
            ),
        )
        val r = sprites.evictCompositedSpritesWithinBudget(pool.residentBytes(), 0L)
        assertEquals(0, r.sprites, "动图帧缓冲多是文件源工作表，归 evictDeferred；首版不铺到动图")
        assertTrue(frame.isCreated)
    }

    /**
     * 本档唯一会自己造出**永久**缺陷的形状，判词见方法 KDoc「一个缓冲两个名字」那段。
     *
     * 4.981 用同一个 GID 同时登记全身与脸区（`HIGHRES_CHARA.ERB:185` 与 `:194`）。
     * 只摘选中的那一个名字会留下一个「名字还在、缓冲已 dispose」的活 Sprite：
     * `SPRITECREATED` 仍报 true，而语料的脸部重算闸是
     * `IF SPRITECREATED(SRC_FACE) && SPRITECREATED(OUT_STAND) && !SPRITECREATED(OUT_FACE)`（`:189`）
     * —— 恰好因为 `OUT_STAND` 没了而不再进这一支 ⇒ 那张脸永久空白。
     */
    @Test
    fun `同一个 GID 挂着两个名字时两个名字一起消失`() {
        val pool = GraphicsPool()
        val sprites = SpritePool()
        val sheet = composited(pool, 40)
        sprites.put(sprite("OUT_STAND", sheet))
        sprites.put(sprite("OUT_FACE", sheet))

        val r = sprites.evictCompositedSpritesWithinBudget(sheetBytes, 0L)
        assertEquals(2, r.sprites, "两个名字一起摘：读数是摘掉的**名字**数")
        assertEquals(sheetPixels, r.pixels, "同一个缓冲只按一份像素入账，不重复计两遍")
        assertNull(sprites.find("OUT_STAND"))
        assertNull(sprites.find("OUT_FACE"), "留一个指向已 dispose 缓冲的活名字 = 脸永久空白")
        assertFalse(sheet.isCreated)
        assertEquals(0L, pool.residentBytes())
    }

    @Test
    fun `逐出后名字查不到等于 SPRITECREATED 转假，语料会自己重算`() {
        val pool = GraphicsPool()
        val sprites = SpritePool()
        val sheet = composited(pool, 50)
        sprites.put(sprite("OUT_STAND", sheet))
        assertTrue(sprites.find("OUT_STAND")!!.isCreated)

        sprites.evictCompositedSpritesWithinBudget(pool.residentBytes(), 0L)

        // 语料的创建闸（`HIGHRES_CHARA.ERB:174` 的 `IF !SPRITECREATED(OUT_STAND)`）吃的就是这个读数：
        // 查不到 ⇒ SPRITECREATED 假 ⇒ 下一次经过这一屏重合成一遍，自愈而不是缺图。
        assertNull(sprites.find("OUT_STAND"))
        val rebuilt = composited(pool, 50)
        sprites.put(sprite("OUT_STAND", rebuilt))
        assertTrue(sprites.find("OUT_STAND")!!.isCreated)
        assertEquals(sheetBytes, pool.residentBytes(), "重算后的驻留重新入账（逐出不留下欠账）")
    }
}
