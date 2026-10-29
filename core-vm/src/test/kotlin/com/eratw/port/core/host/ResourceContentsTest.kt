package com.eratw.port.core.host

import com.eratw.port.core.image.PngImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CSV 资源表装载的定向用例（`AppContents.LoadContents` / `CreateFromCsv`，
 * AppContents.cs:107-178 / :232-370）。覆盖 XRAY 依赖的三件事：
 * 静态 sprite 登记可查、矩形/pos/destSize 字段对齐、像素按需解码；
 * 以及 CSV 动画（`ANIME` 声明 + 后续同名行追加帧）。
 */
class ResourceContentsTest {

    private class FakeHost(
        private val files: MutableMap<String, String> = LinkedHashMap(),
        private val sizes: MutableMap<String, IntArray> = LinkedHashMap(),
        private val pixels: MutableMap<String, IntArray> = LinkedHashMap(),
        private val csVs: MutableList<String> = ArrayList(),
    ) : ResourceFileHost {

        val decodeCalls = HashMap<String, Int>()

        fun csv(path: String, body: String) {
            files[path] = body
            csVs += path
        }

        fun image(path: String, w: Int, h: Int, argb: Int = 0x7F00FF00) {
            sizes[path] = intArrayOf(w, h)
            pixels[path] = IntArray(w * h) { argb }
        }

        /** 文件头读得出尺寸、像素却解不出（文件损坏 / 被删 / 解码 OOM）。 */
        fun brokenImage(path: String, w: Int, h: Int) {
            sizes[path] = intArrayOf(w, h)
        }

        override fun listCsvFiles(): List<String> = csVs

        override fun readBytes(path: String): ByteArray? =
            files[path]?.toByteArray(Charsets.UTF_8) ?: if (sizes.containsKey(path)) ByteArray(0) else null

        override fun imageSize(path: String, bytes: ByteArray?): IntArray? = sizes[path]?.copyOf()

        override fun decode(path: String, bytes: ByteArray): PngImage? {
            decodeCalls[path] = (decodeCalls[path] ?: 0) + 1
            val size = sizes[path] ?: return null
            val argb = pixels[path] ?: return null
            return PngImage(size[0], size[1], argb)
        }
    }

    @Test
    fun `静态 sprite 的矩形 pos destSize 按列解析且像素按需解码`() {
        // 11 列：name,file,x,y,w,h,posX,posY,delay,destW,destH（AppContents.cs:300-356）
        val host = FakeHost()
        host.csv(
            "resources/xray/xray.csv",
            """
            ; 注释行与空行必须跳过（:147-151）

            A_1,A_1.png,0,0,100,100,5,6,1000,120,130
            """.trimIndent(),
        )
        host.image("resources/xray/A_1.png", w = 200, h = 200)
        val pool = SpritePool()

        val report = ResourceContents.load(pool, host)

        assertEquals(1, report.fileCount)
        assertEquals(1, report.spriteCount)
        assertEquals(0, report.animeCount)
        assertTrue(report.warnings.isEmpty(), "不该有告警：${report.warnings}")
        val sprite = pool.find("A_1")!!
        assertEquals(120, sprite.width)
        assertEquals(130, sprite.height)
        assertEquals(0, sprite.srcX)
        assertEquals(0, sprite.srcY)
        assertEquals(100, sprite.srcW)
        assertEquals(100, sprite.srcH)
        assertEquals(5, sprite.posX)
        assertEquals(6, sprite.posY)
        assertTrue(sprite.isCreated, "尺寸已从文件头给出，IsCreated 必须为 true（ASpriteSingle.cs:24-34）")
        assertEquals(0, host.decodeCalls["resources/xray/A_1.png"] ?: 0, "登记期不该解码（延迟到首像素访问）")

        assertEquals(0x7F00FF00, sprite.buffer!!.pixels[0])
        assertEquals(1, host.decodeCalls["resources/xray/A_1.png"])
        sprite.buffer!!.pixels[0]
        assertEquals(1, host.decodeCalls["resources/xray/A_1.png"], "像素落地后不得重复解码")
    }

    @Test
    fun `六列缺省矩形时取整图并共享同一份父图像素`() {
        // 同文件两个 sprite 共用 resourceDic 的一份解码图（AppContents.cs:270-293）
        val host = FakeHost()
        host.csv("resources/p/pack.csv", "A,pack.png\nB,pack.png,10,10,20,20")
        host.image("resources/p/pack.png", w = 60, h = 40)
        val pool = SpritePool()

        ResourceContents.load(pool, host)

        val a = pool.find("A")!!
        assertEquals(60, a.width)
        assertEquals(40, a.height)
        assertEquals(60, a.srcW)
        assertEquals(40, a.srcH)
        val b = pool.find("B")!!
        assertEquals(10, b.srcX)
        assertEquals(10, b.srcY)
        assertEquals(20, b.srcW)
        assertEquals(20, b.srcH)
        assertTrue(a.buffer === b.buffer, "同一 png 必须共享一份解码图")
        a.buffer!!.pixels[0]
        assertEquals(1, host.decodeCalls["resources/p/pack.png"], "共享图只解一次")
    }

    @Test
    fun `动画声明后同名行追加帧并保留帧间隔`() {
        // ANIME 声明（:244-262）+ 同名行追加帧（:360-368），帧间隔在第 9 列
        val host = FakeHost()
        host.csv(
            "resources/anime/30.csv",
            """
            30_BG1,ANIME,270,270
            30_BG1,30_BG1.png,0,0,83,52,95,71,100
            30_BG1,30_BG1.png,10,0,83,52,95,71,80
            """.trimIndent(),
        )
        host.image("resources/anime/30_BG1.png", w = 270, h = 270)
        val pool = SpritePool()

        val report = ResourceContents.load(pool, host)

        assertEquals(1, report.spriteCount, "同名帧不另登记 sprite")
        assertEquals(1, report.animeCount)
        val anime = pool.find("30_BG1")!!
        assertTrue(anime.isAnime)
        assertEquals(270, anime.width)
        assertEquals(2, anime.frames!!.size)
        assertEquals(100, anime.frames!![0].delayMs)
        assertEquals(80, anime.frames!![1].delayMs)
        assertEquals(10, anime.frames!![1].srcX)
        assertEquals(95, anime.frames!![0].offsetX)
        assertEquals(71, anime.frames!![0].offsetY)
        assertEquals(180, anime.totalTimeMs)
    }

    @Test
    fun `非法行按原版丢弃`() {
        val host = FakeHost()
        host.csv(
            "resources/bad/bad.csv",
            """
            NODOT,noext,0,0,10,10
            ZEROW,bad.png,0,0,0,10
            OUTSIDE,bad.png,1000,1000,10,10
            BADDELAY,bad.png,0,0,10,10,0,0,0
            """.trimIndent(),
        )
        host.image("resources/bad/bad.png", w = 50, h = 50)
        val pool = SpritePool()

        val report = ResourceContents.load(pool, host)

        assertEquals(0, report.spriteCount)
        assertNull(pool.find("NODOT"))
        assertNull(pool.find("ZEROW"))
        assertNull(pool.find("OUTSIDE"))
        assertNull(pool.find("BADDELAY"))
        assertEquals(4, report.warnings.size, "每行一条告警：${report.warnings}")
        assertTrue(pool.names().isEmpty())
    }

    @Test
    fun `后到同名 sprite 被丢弃并告警`() {
        val host = FakeHost()
        host.csv("resources/a/a.csv", "DUP,a.png,0,0,10,10")
        host.csv("resources/b/b.csv", "DUP,b.png,0,0,10,10")
        host.image("resources/a/a.png", w = 50, h = 50)
        host.image("resources/b/b.png", w = 50, h = 50)
        val pool = SpritePool()

        val report = ResourceContents.load(pool, host)

        assertEquals(1, report.spriteCount)
        assertEquals(1, report.warnings.size)
        assertEquals(10, pool.find("DUP")!!.srcW)
    }

    /**
     * 第 9 列存在但为空 ⇒ 原版 `int.TryParse("")` 失败把 `result` 置 0、`AddFrame` 夹到 1ms
     * （AppContents.cs:340 / SpriteAnime.cs:72-76）。Kotlin 的 `split` 会吞掉结尾空列，
     * 必须按 C# 口径保住列数，否则这里会错拿 1000ms。
     */
    @Test
    fun `帧间隔空列折成1毫秒`() {
        val host = FakeHost()
        host.csv(
            "resources/g/g.csv",
            """
            ANIM,ANIME,32,32
            ANIM,a.png,0,0,16,16,0,0,
            ANIM,a.png,0,0,16,16,0,0,
            """.trimIndent(),
        )
        host.image("resources/g/a.png", w = 32, h = 32)
        val pool = SpritePool()

        ResourceContents.load(pool, host)

        val anime = pool.find("ANIM")!!
        assertEquals(2, anime.frames!!.size)
        assertTrue(anime.frames.all { it.delayMs == 1 }, "空帧间隔列 = TryParse 失败 ⇒ 1ms")
        assertEquals(2, anime.totalTimeMs)
    }

    /**
     * 解码失败不能把「尺寸 > 0 但像素是空数组」的半套状态交给下游：`GDRAWSPRITE` 的
     * arraycopy 与 `SpriteBitmapSource` 的 `Bitmap.createBitmap` 都按 `width * height`
     * 索引，空数组会直接抛越界/非法参数。原版这一行在装载期就被丢弃（AppContents.cs:274-278），
     * 移植侧延迟解码只能退化成同尺寸全透明图。
     */
    @Test
    fun `解码失败退化成同尺寸全透明图而不是空数组`() {
        val host = FakeHost()
        host.csv("resources/g/g.csv", "GONE,gone.png,0,0,10,10")
        host.brokenImage("resources/g/gone.png", 20, 20)
        val pool = SpritePool()

        ResourceContents.load(pool, host)

        val buffer = pool.find("GONE")!!.buffer!!
        assertEquals(400, buffer.pixels.size, "pixels.length 必须恒等于 width * height")
        assertTrue(buffer.pixels.all { it == 0 }, "失败的图退化成全透明")
        assertEquals(1, host.decodeCalls["resources/g/gone.png"], "失败后不得每次访问都重试解码")

        // 下游按尺寸索引（`GDRAWSPRITE` 的 arraycopy 路径）不得越界，目标保持透明。
        val dst = GraphicsBuffer(1).also { it.create(4, 4) }
        dst.blitFrom(buffer, 0, 0, 4, 4, 0, 0, 4, 4)
        assertEquals(0, dst.pixelAt(0, 0))
    }

    @Test
    fun `没有 resources 目录时不抛且零登记`() {
        val report = ResourceContents.load(SpritePool(), FakeHost())
        assertEquals(0, report.fileCount)
        assertEquals(0, report.spriteCount)
    }
}
