package com.eratw.port.core.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 内存压力下的**延迟资源图回收**（2026-10-30 玩家反馈「死机了」「玩了一会儿就闪退」）。
 *
 * ## 这一刀收的是哪一坨
 *
 * CSV 资源表的父图走 `createDeferred`：登记时只按文件头给尺寸，像素首访问才解
 * （`ResourceContents.kt:197-201`，本语料 134 张全解码约 105 MiB）。但「解过一次」以前
 * 就是**永久常驻** —— 玩家玩久了把角色立绘挨个看过一遍，这 105 MiB 就整坨压在
 * `largeHeap` 的 512 MiB 挡里，再也不下来。回收它不需要任何新语义：解码源是同一份文件字节，
 * 重解出来的像素与上一次逐字相同。
 *
 * ## 判据（为什么只有这一坨能收）
 *
 * ①只收「内容恒等于某文件解码结果」的缓冲：显式写过像素的路径一律失去资格
 * （`pixels` 的私有 setter 清回收源）。②再加一道「没被画过」的证据，两条任一成立即可：
 * **负号 id**（CSV 父图专用段，VM 的画图语句只能按 gid 寻缓冲 ⇒ 负号段恒为「只被读」）
 * 或 **`fileSource` 还在**（`GCREATEFROMFILE` 建的 G_ID 缓冲，`markDirty()` 收在
 * **每一个真正写像素的落点**（2026-10-30 审计 #1 后裁剪成空/全透明的画写不作废），
 * 「文件源还在」⇔「一个像素都没被改写」）。2026-10-30 之前只认前一条，于是 G_ID 段的
 * 工作表被 `GGETCOLOR` / `GSAVE` /
 * `GDRAWG` 读过一次就**永久常驻**（本板欠账第一条），现在它们同样能退回未解码态。
 * ③解不出来的那张不留回收源（否则每轮压力都退回未解码、每次访问重跑注定失败的解码）。
 * ④回收不动尺寸与 `isCreated`：`ASpriteSingle.IsCreated` 只看基图在不在
 * （`ASpriteSingle.cs:24-34`），这条不变 ⇒ 屏上不会闪出空帧，下次画它时透明重解。
 * ⑤一张父图常被切成几十个 Sprite 复用 ⇒ 池级回收按**对象身份**去重，不然释放量虚高。
 *
 * 与基准的差异：原版 `GraphicsImage` 持 GDI+ `Bitmap`（原生内存，不吃 .NET 堆），
 * 没有「Java 堆挡位」这个问题；这一刀是把移植侧换 IntArray 之后多出来的那一坨还回去，
 * 不改任何可观测语义。
 */
class DeferredImageEvictionTest {

    /** 一张确定性图：重解必须逐字相同，靠内容比对而不是靠引用。 */
    private fun image(id: Int, w: Int = 2, h: Int = 2, decodes: IntArray? = null): Pair<GraphicsBuffer, IntArray> {
        val px = IntArray(w * h) { id * 1000 + it }
        val buf = GraphicsBuffer(id)
        assertTrue(buf.createDeferred(w, h) {
            decodes?.let { it[0]++ }
            px
        }, "尺寸合法才能建")
        return buf to px
    }

    @Test
    fun `延迟图首访问才解码，回收后再访问重解同一张`() {
        val decodes = intArrayOf(0)
        val (buf, px) = image(-1, decodes = decodes)
        assertEquals(0, decodes[0], "登记时不该解码")
        assertTrue(buf.isCreated, "尺寸在 ⇒ IsCreated 就在")
        samePixels("首访问的像素", px, buf.pixels)
        assertEquals(1, decodes[0], "首访问解一次")
        samePixels("同一份像素再读不该重解", px, buf.pixels)
        assertEquals(1, decodes[0], "再读不重解")

        assertEquals(4L, buf.evictDeferred(), "释放的像素条数")
        assertEquals(1, decodes[0], "回收本身不该顺手重解")
        samePixels("重解必须逐字相同", px, buf.pixels)
        assertEquals(2, decodes[0], "回收后的第一次访问才重解")
    }

    @Test
    fun `回收不动尺寸与 isCreated，屏上不会闪空帧`() {
        val (buf, _) = image(-2, w = 3, h = 4)
        buf.pixels // 落地
        assertTrue(buf.isCreated)
        assertEquals(12L, buf.evictDeferred())
        assertTrue(buf.isCreated, "尺寸与 IsCreated 必须原样留着")
        assertEquals(3, buf.width)
        assertEquals(4, buf.height)
    }

    @Test
    fun `没有文件源的正号 gid 不回收`() {
        val (buf, _) = image(0)
        buf.pixels
        assertEquals(
            0L,
            buf.evictDeferred(),
            "没有文件源 ⇒ 内容可能是脚本画上去的，重解不回来 ⇒ 一律不碰",
        )
        assertEquals(4, buf.pixels.size, "正号缓冲一字节都不动")
    }

    @Test
    fun `文件源还在的正号 gid 也能回收，重解逐字相同`() {
        val decodes = intArrayOf(0)
        val px = IntArray(4) { 0x44000000 + it }
        val sheet = GraphicsBuffer(11)
        // GCREATEFROMFILE 的延迟形态：尺寸来自文件头，像素按需解，同时登记文件源。
        assertTrue(sheet.createDeferredFromFile(2, 2, "resources/x.webp") {
            decodes[0]++
            px
        })
        assertEquals(0, decodes[0], "登记期不解码")
        samePixels("首访问", px, sheet.pixels)
        assertEquals(1, decodes[0])

        // 本板欠账第一条的落点：122 MiB 级工作表被 GGETCOLOR 读过一次之后必须还能收回。
        assertEquals(4L, sheet.evictDeferred(), "文件源还在 ⇒ 内容恒等于文件 ⇒ 可回收")
        samePixels("重解逐字相同", px, sheet.pixels)
        assertEquals(2, decodes[0], "回收后的第一次访问才重解")
    }

    @Test
    fun `正号 gid 一旦被画写就失去回收资格`() {
        val sheet = GraphicsBuffer(12)
        assertTrue(sheet.createDeferredFromFile(2, 2, "resources/x.webp") { IntArray(4) })
        sheet.pixels
        sheet.setPixel(0, 0, 0x7F00FF00)
        assertEquals(0L, sheet.evictDeferred(), "画过 ⇒ 文件源作废 ⇒ 不能重解")
        assertEquals(0x7F00FF00, sheet.pixelAt(0, 0), "画上去的内容必须还在")
    }

    @Test
    fun `图形池整表回收把 G_ID 工作表也退回未解码`() {
        val decodes = intArrayOf(0)
        val pool = GraphicsPool()
        val sheet = pool.get(30)
        assertTrue(sheet.createDeferredFromFile(2, 2, "resources/y.webp") {
            decodes[0]++
            IntArray(4) { 0x55000000 }
        })
        sheet.pixels // 被 GGETCOLOR / GSAVE / GDRAWG 这类真读像素的入口读过
        assertEquals(1, decodes[0])
        assertEquals(4L, pool.evictDeferredPixels(), "整表扫：正号段的工作表也要收")
        sheet.pixels
        assertEquals(2, decodes[0], "重解一次")
        // 重解落地后再收一次同样成功：缓冲仍旧一个像素都没被画写，内存压力再来
        // （水位再次过线）就该再收一次 —— 这正是欠账 #1 要的「不再永久常驻」。
        assertEquals(4L, pool.evictDeferredPixels(), "重解落地后可再次退回未解码")
        assertEquals(0L, pool.evictDeferredPixels(), "已在未解码态的不重复计数")
    }

    @Test
    fun `释放没人引用的合成缓冲，跳过被引用与文件源`() {
        val pool = GraphicsPool()
        // ①脚本画上去的大块（GET_EMPTY_G 那类）：无人引用 ⇒ 该释放
        val drawn = pool.get(40)
        drawn.create(64, 64)
        // ②正在被 Sprite 引用的：不能动
        val shown = pool.get(41)
        shown.create(64, 64)
        // ③文件源还在的：交给 evictDeferred，dispose 掉纯属浪费
        val fileBacked = pool.get(42)
        assertTrue(fileBacked.createDeferredFromFile(64, 64, "resources/z.webp") { IntArray(64 * 64) })
        // ④小块：低于门槛，不动
        val tiny = pool.get(43)
        tiny.create(8, 8)

        val sprites = SpritePool()
        sprites.put(SpritePool.Sprite(name = "S1", width = 64, height = 64, buffer = shown))

        val exclude = sprites.referencedBuffers()
        // 门槛取 64x64：刚好放行 ①（4096 px），又把 ④ 的 8x8=64 px 挡在门外；
        // 用 256x256 当门槛的话 ① 自己也过不了线（4096 < 65536），用例前提就塌了。
        val r = pool.releaseUnused(exclude, minPixels = 64 * 64)

        assertEquals(1, r.buffers, "只有 ① 该被释放")
        assertEquals(64L * 64 * 4, r.bytes)
        assertFalse(drawn.isCreated, "释放后是「未创建」⇒ GET_EMPTY_G 能复用这个 gid")
        assertTrue(shown.isCreated, "正在显示的不能动")
        assertTrue(fileBacked.isCreated, "文件源还在的留给 evictDeferred")
        assertTrue(tiny.isCreated, "低于门槛不折腾")
    }

    @Test
    fun `referencedBuffers 收静态基图与动画帧`() {
        val static = GraphicsBuffer(50).also { it.create(2, 2) }
        val sheet = GraphicsBuffer(51).also { it.create(4, 4) }
        val pool = SpritePool()
        pool.put(SpritePool.Sprite(name = "S", width = 2, height = 2, buffer = static))
        pool.putResource(
            SpritePool.Sprite(
                name = "A",
                width = 4,
                height = 4,
                frames = mutableListOf(SpritePool.Frame(sheet, 0, 0, 2, 2, 0, 0, 100)),
            ),
        )
        val refs = pool.referencedBuffers()
        assertTrue(static in refs, "静态基图")
        assertTrue(sheet in refs, "动画帧的父图")
        assertEquals(2, refs.size)
    }

    @Test
    fun `显式写过像素的缓冲失去回收资格`() {
        val buf = GraphicsBuffer(-3)
        assertTrue(buf.createDeferred(2, 2) { IntArray(4) { 0x11000000 } })
        samePixels("先落地", IntArray(4) { 0x11000000 }, buf.pixels)
        // 走一次真正的建图路径（等价于 GCREATEFROMFILE 之后）：内容不再恒等于延迟源。
        buf.create(2, 2)
        assertEquals(0L, buf.evictDeferred())
        buf.createFrom(2, 2, IntArray(4) { 0x22000000 })
        assertEquals(0L, buf.evictDeferred())
        samePixels("写过之后读到的就是写进去的", IntArray(4) { 0x22000000 }, buf.pixels)
    }

    @Test
    fun `解不出来的那张不留回收源也不反复重试`() {
        val attempts = intArrayOf(0)
        val buf = GraphicsBuffer(-4)
        assertTrue(buf.createDeferred(2, 2) {
            attempts[0]++
            null // 解码失败（原版这条路径在装载期就被丢弃，AppContents.cs:274-278）
        })
        assertEquals(4, buf.pixels.size, "失败退化成同尺寸全透明图")
        assertEquals(1, attempts[0])
        buf.pixels
        assertEquals(1, attempts[0], "失败结果不留源 ⇒ 不每次访问重试")
        assertEquals(0L, buf.evictDeferred())
    }

    @Test
    fun `池级回收按对象身份去重且只扫资源表`() {
        val shared = image(-5).first
        shared.pixels
        val pool = SpritePool()
        // 同一张父图切三个子图：释放量只算一次（否则读数虚高 3 倍）。
        pool.putResource(sprite("RES_A", shared))
        pool.putResource(sprite("RES_B", shared))
        pool.putResource(sprite("RES_C", image(-6).first.also { it.pixels }))
        // 运行时（非资源）sprite 引用的正号缓冲：不在回收面内。
        val runtime = GraphicsBuffer(7)
        runtime.createFrom(2, 2, IntArray(4) { 0x33000000 })
        pool.put(sprite("RT_1", runtime))

        assertEquals(8L, pool.evictDeferredPixels(), "两张父图 × 4 像素")
        assertEquals(4, runtime.pixels.size)
        samePixels("正号缓冲一字节都不动", IntArray(4) { 0x33000000 }, runtime.pixels)
        assertEquals(0L, pool.evictDeferredPixels(), "再收一次：已回收的不重复计数")
    }

    @Test
    fun `动画帧引用的父图也在回收面内`() {
        val sheet = image(-7, w = 4, h = 4).first
        sheet.pixels
        val pool = SpritePool()
        pool.putResource(
            SpritePool.Sprite(
                name = "RES_ANI",
                width = 4,
                height = 4,
                frames = mutableListOf(
                    SpritePool.Frame(sheet, 0, 0, 2, 2, 0, 0, 100),
                    SpritePool.Frame(sheet, 2, 0, 2, 2, 0, 0, 100),
                ),
            ),
        )
        assertEquals(16L, pool.evictDeferredPixels(), "两帧共用一张父图 ⇒ 只算一次")
        assertTrue(sheet.isCreated)
    }

    @Test
    fun `回收过的图在 dispose 之后不再回来`() {
        val (buf, _) = image(-8)
        buf.pixels
        assertEquals(4L, buf.evictDeferred())
        buf.dispose()
        assertFalse(buf.isCreated, "GDISPOSE 之后是「未创建」，不是「待重解」")
        assertEquals(0, buf.pixels.size)
        assertEquals(0L, buf.evictDeferred())
    }

    // ------------------------------------------------------------------
    // 2026-10-30 稳定性审计 #1：markDirty 必须只落在「真写了像素」的路径上。
    // 旧写法 blitFrom/drawLine/strokeRect/fillRect/blitMasked 在裁剪判定**之前**就
    // markDirty()，一笔裁空的绘制把 122 MiB 工作表永久踢出回收面。
    // ------------------------------------------------------------------

    /** 带文件源的延迟工作表替身：2x2，`createDeferredFromFile` 与 GCREATEFROMFILE 同形态。 */
    private fun fileSheet(id: Int, path: String): GraphicsBuffer {
        val buf = GraphicsBuffer(id)
        assertTrue(buf.createDeferredFromFile(2, 2, path) { IntArray(4) { 0x11000000 + it } })
        return buf
    }

    @Test
    fun `整笔裁没的 blitFrom 不作废文件源且仍可回收，写进一像素才作废`() {
        val src = GraphicsBuffer(90).also { it.createFrom(1, 1, IntArray(1) { 0xFF00FF00.toInt() }) }
        val sheet = fileSheet(91, "resources/audit1.webp")
        sheet.pixels // 落地（模拟被 GGETCOLOR 读过一次——正是欠账第一条的场景）

        // dest 整体在画布右侧外 / 左侧外：iLo>=iHi 早退，一个像素都不写
        sheet.blitFrom(src, 100, 0, 1, 1, 0, 0, 1, 1)
        sheet.blitFrom(src, -1, 0, 1, 1, 0, 0, 1, 1)
        sheet.blitFrom(src, 0, 100, 1, 1, 0, 0, 1, 1)
        assertEquals("resources/audit1.webp", sheet.fileSource, "裁没的绘制不得 markDirty（审计 #1）")
        assertEquals(4L, sheet.evictDeferred(), "缓冲必须仍在延迟回收面内")

        // 一笔真写进 1 个像素 ⇒ 立刻作废并失去回收资格
        sheet.pixels
        sheet.blitFrom(src, 0, 0, 1, 1, 0, 0, 1, 1)
        assertNull(sheet.fileSource, "写了像素必须作废")
        assertEquals(0xFF00FF00.toInt(), sheet.pixelAt(0, 0))
        assertEquals(0L, sheet.evictDeferred())
    }

    @Test
    fun `全透明源的 blitFrom 一个像素都不写也不作废`() {
        val ghost = GraphicsBuffer(92).also { it.createFrom(1, 1, IntArray(1) { 0x00FF00FF }) }
        val sheet = fileSheet(93, "resources/audit1b.webp")
        sheet.pixels
        sheet.blitFrom(ghost, 0, 0, 2, 2, 0, 0, 1, 1)
        assertEquals("resources/audit1b.webp", sheet.fileSource, "blendPixel 全程无操作 ⇒ 不作废")
        assertEquals(4L, sheet.evictDeferred())
    }

    @Test
    fun `裁没的 fillRect-strokeRect-drawLine 不作废，落笔的作废`() {
        val sheet = fileSheet(94, "resources/audit1c.webp")
        sheet.pixels
        sheet.fillRect(10, 0, 4, 4, 0xFF0000FF.toInt()) // 整块在画布外
        sheet.fillRect(0, 0, 4, 4, 0x00FF0000) // 画布内但 alpha=0（非 replace 全走空 blend）
        sheet.strokeRect(10L, 0L, 12L, 2L, 0xFF00FF00.toInt()) // 框在画布外
        sheet.drawLine(10, 10, 20, 20, 0xFFFFFFFF.toInt()) // 线段被 Liang-Barsky 裁没
        assertEquals("resources/audit1c.webp", sheet.fileSource, "这四笔都没写到像素，必须不作废")
        assertEquals(4L, sheet.evictDeferred())

        sheet.pixels
        sheet.fillRect(0, 0, 1, 1, 0xFF0000FF.toInt(), replace = true)
        assertNull(sheet.fileSource, "replace 写了像素必须作废")
        assertEquals(0xFF0000FF.toInt(), sheet.pixelAt(0, 0))
    }

    @Test
    fun `blitMasked 整块裁外不作废，遮罩写一格才作废`() {
        val src = GraphicsBuffer(95).also { it.createFrom(1, 1, IntArray(1) { 0xFF00FF00.toInt() }) }
        val maskOn = GraphicsBuffer(96).also { it.createFrom(1, 1, IntArray(1) { 0xFF }) } // 蓝通道=255
        val sheet = fileSheet(97, "resources/audit1d.webp")
        sheet.pixels
        sheet.blitMasked(src, maskOn, 10, 10) // dest 整块在画布外
        assertEquals("resources/audit1d.webp", sheet.fileSource, "全 continue 的一趟不该 markDirty")
        assertEquals(4L, sheet.evictDeferred())

        sheet.pixels
        sheet.blitMasked(src, maskOn, 0, 0)
        assertNull(sheet.fileSource)
        assertEquals(0xFF00FF00.toInt(), sheet.pixelAt(0, 0))
    }

    @Test
    fun `未创建的 clear 与越界的 setPixel 不作废也不炸`() {
        val buf = GraphicsBuffer(98)
        assertTrue(buf.createDeferredFromFile(2, 2, "resources/audit1e.webp") { IntArray(4) })
        // setPixel 越界：写不到像素 ⇒ 静默跳过且保留文件源（函数层 GSETCOLOR 自己有边界闸）
        buf.setPixel(9, 9, 0xFF00FF00.toInt())
        assertEquals("resources/audit1e.webp", buf.fileSource)
        buf.pixels
        buf.setPixel(0, 0, 0xFF00FF00.toInt())
        assertNull(buf.fileSource, "写进一格必须作废")
    }

    private fun sprite(name: String, buffer: GraphicsBuffer) = SpritePool.Sprite(
        name = name,
        width = buffer.width,
        height = buffer.height,
        buffer = buffer,
    )

    private fun samePixels(message: String, expected: IntArray, actual: IntArray) {
        assertEquals(expected.toList(), actual.toList(), message)
    }
}
