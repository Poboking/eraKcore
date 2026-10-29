package com.eratw.port.render.sprite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SpriteTable] 释放计数 / id 生命周期单测。
 *
 * 判据来源：
 * - `SPRITEDISPOSEALL=35`：基准 `pc-golden-root.txt:673`（GVIEW 分节 `:634-692`），
 *   进 GVIEW 前游戏自建 35 个非资源 sprite、资源表为空（core-vm `SpritePool` 同解读，
 *   `GraphicsBuffer.kt:462`），复刻该形态：35 个用户条目 → 全释放返回 35；
 * - `SPRITEDISPOSE=1` / 再释放 0：基准 `:672` + `FunctionMethodCreator.cs:7083-7093`
 *   （未命中或未创建返回 0，命中返回 1）；
 * - 释放不存在对象返回 0 的总约定：基准 `:651` `GDISPOSE14=0`；
 * - 资源/减法/回滚各怪癖逐条对应 `AppContents.cs:66-78`（见 `SpriteTable` 类 KDoc）。
 */
class SpriteTableTest {

    private fun raster(w: Int = 8, h: Int = 4) = Raster(w, h)

    @Test
    fun `id 单调递增且释放后永不复用`() {
        val t = SpriteTable()
        val a = t.add("a", raster())
        val b = t.add("b", raster())
        assertEquals(1, a)
        assertEquals(2, b)
        assertEquals(1, t.dispose(a))
        assertEquals(1, t.dispose(b))
        val c = t.add("c", raster())
        assertEquals(3, c, "释放后 id 不复用（gList 键永不删，AppContents.cs:28-37）")
        assertEquals(0, t.dispose(a), "旧 id 永久失效")
    }

    @Test
    fun `dispose 计数 重复释放返回0`() {
        val t = SpriteTable()
        val gspr = t.add("GSPR", raster(40, 20))
        assertEquals("GSPR", t.find("gspr")?.name, "名字按 InvariantCulture 大写归一（AppContents.cs:45）")
        // 基准 :672 SPRITEDISPOSE=1
        assertEquals(1, t.dispose(gspr))
        // 重复释放 0（同 :651 GDISPOSE14=0 的返回值约定）
        assertEquals(0, t.dispose(gspr))
        assertEquals(0, t.dispose(999), "未知 id 返回 0")
        assertNull(t.find("GSPR"))
        assertEquals(0, t.size)
    }

    @Test
    fun `空表 disposeAll 返回0`() {
        val t = SpriteTable()
        assertEquals(0, t.disposeAll())
        assertEquals(0, t.disposeAll(delCsv = true))
    }

    @Test
    fun `复刻基准形态 35个用户sprite全释放返回35 再调0`() {
        val t = SpriteTable()
        val ids = (0 until 35).map { t.add("pre_$it", raster(1, 1)) }
        val gspr = t.add("GSPR", raster(40, 20))
        // GOLDEN_11_GVIEW.ERB:59 先单独释放 GSPR（基准 :672 =1）
        assertEquals(1, t.dispose(gspr))
        // GOLDEN_11_GVIEW.ERB:60 SPRITEDISPOSEALL(0) → 基准 :673 = 35
        assertEquals(35, t.disposeAll())
        assertTrue(ids.all { t.entry(it) == null })
        // 第二次调用：运行时表已回滚为资源表副本，count - count2 = 0
        assertEquals(0, t.disposeAll())
    }

    @Test
    fun `资源条目在disposeAll false下存活且回滚复活`() {
        val t = SpriteTable()
        val a = t.add("res_a", raster(), fromCsv = true)
        val b = t.add("res_b", raster(), fromCsv = true)
        val c = t.add("user_c", raster())
        // 逐个释放两个资源条目：只删运行时表，资源表仍在（AppContents.cs:53-64 + :22）
        assertEquals(1, t.dispose(a))
        assertEquals(1, t.dispose(b))
        // 官方减法口径返回 count-count2 = 1-2 = -1，照抄不修（AppContents.cs:68-69/77）
        assertEquals(-1, t.disposeAll())
        // 回滚「复活」：资源表整体拷回运行时表（AppContents.cs:76）
        assertNotNull(t.find("RES_A"))
        assertNotNull(t.find("RES_B"))
        assertNull(t.find("USER_C"))
        // 全资源表再全释放：count == count2 → 0
        assertEquals(0, t.disposeAll())
        // 复活的是**字典键**（GetSprite 非空），对象已在第一次 dispose 时 Dispose()
        // ⇒ IsCreated=false，再释放返回 0（ASpriteSingle.cs:66-69 的 Dispose + :24-34 的
        // IsCreated，守卫在 FunctionMethodCreator.cs:7087-7090，过守卫才 :7091 调 SpriteDispose）
        assertNotNull(t.find("RES_A"))
        assertFalse(checkNotNull(t.find("RES_A")).isCreated, "复活的是键，不是对象")
        assertEquals(0, t.dispose(a))
    }

    @Test
    fun `delCsv为真两表全清返回清理前运行时表大小`() {
        val t = SpriteTable()
        t.add("res_a", raster(), fromCsv = true)
        t.add("res_b", raster(), fromCsv = true)
        t.add("user_c", raster())
        // 原版资源恒镜像进运行时表，delCsv 分支返回 imageDictionary.Count（AppContents.cs:70-74）
        assertEquals(3, t.disposeAll(delCsv = true))
        assertEquals(0, t.size)
        assertEquals(0, t.resourceSize)
        assertNull(t.find("res_a"))
        assertEquals(0, t.disposeAll(delCsv = true))
    }

    @Test
    fun `同名覆盖不释放旧对象且旧id失效`() {
        val t = SpriteTable()
        val old = t.add("spr", raster(10, 10))
        val fresh = t.add("SPR", raster(20, 5))
        assertTrue(fresh > old, "覆盖也分配新 id（单调）")
        val e = t.find("Spr")
        assertNotNull(e)
        assertEquals(fresh, e.id, "运行时表直接赋新值（AppContents.cs:88/104）")
        assertEquals(20, e.width)
        assertEquals(5, e.height)
        assertEquals(1, t.size) // 覆盖只占一个键
        assertEquals(0, t.dispose(old), "被覆盖的旧 id 立即失效")
        assertEquals(1, t.dispose(fresh))
    }

    @Test
    fun `条目尺寸取自像素源`() {
        val t = SpriteTable()
        val id = t.add("dim", raster(40, 20))
        val e = t.entry(id)
        assertNotNull(e)
        assertEquals(40, e.width)
        assertEquals(20, e.height)
        assertEquals(1, t.size)
        assertEquals(0, t.resourceSize)
    }
}
