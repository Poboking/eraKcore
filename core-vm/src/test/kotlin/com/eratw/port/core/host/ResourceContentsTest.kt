package com.eratw.port.core.host

import com.eratw.port.core.image.PngImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

        /** 每路径被 [readBytes] 读了几次（分辨「装载期读」与「像素落地时读」）。 */
        val readCalls = HashMap<String, Int>()

        /** [imageSize] 是否收到过整份字节（生产口径只许传 null，走文件头）。 */
        var imageSizeSawBytes: Boolean = false
            private set

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

        override fun readBytes(path: String): ByteArray? {
            readCalls[path] = (readCalls[path] ?: 0) + 1
            return files[path]?.toByteArray(Charsets.UTF_8) ?: if (sizes.containsKey(path)) ByteArray(0) else null
        }

        override fun imageSize(path: String, bytes: ByteArray?): IntArray? {
            if (bytes != null) imageSizeSawBytes = true
            return sizes[path]?.copyOf()
        }

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
        // M12-MEM-GFX：父图必须带文件源，渲染侧（SpriteBitmapSource.bitmapOf）据此按路径解位图，
        // 完全绕开 pixels —— CSV 引用的立绘工作表单张 8000x4000，解成 IntArray 就是 122 MiB Dalvik 堆。
        assertEquals(
            "resources/xray/A_1.png",
            sprite.buffer!!.fileSource,
            "CSV 父图必须登记文件源（点「爱抚」闪退那一刀的落点）",
        )

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

    /**
     * 装载期**不许读图字节**（2026-11-03 真机启动 OOM 的修复点）。
     *
     * 现象：4.981 的 266 个 CSV 引用 2,795 张图、共 570.6 MB，旧实现登记期逐张 `readBytes`
     * 并用延迟 lambda 捕获那份数组 ⇒ 字节整份钉在 Java 堆上，`installGraphics` 里堆到 510/512 MiB
     * 抛 OOM。期望：登记期只走文件头（`imageSize(path, null)`），字节在首像素访问时才读、
     * 用完即弃，父图仍只解一次（同文件多 sprite 共享）。
     */
    @Test
    fun `装载期不读图字节且头部量尺寸不传整份内容`() {
        val host = FakeHost()
        host.csv("resources/big/pack.csv", "A,pack.png\nB,pack.png,1,1,2,2")
        host.image("resources/big/pack.png", w = 64, h = 64)
        val pool = SpritePool()

        val report = ResourceContents.load(pool, host)

        assertTrue(report.warnings.isEmpty(), "不该有告警：${report.warnings}")
        assertEquals(
            0,
            host.readCalls["resources/big/pack.png"] ?: 0,
            "登记期不得读图字节：570.6 MB 整份留在堆上就是 512 MiB 挡的装载必 OOM",
        )
        assertFalse(host.imageSizeSawBytes, "头部量尺寸必须传 null（按路径只读头），不许先把整份内容读进堆")
        assertEquals(2, report.spriteCount, "两个 sprite 都登记（共用同一张父图）")

        pool.find("A")!!.buffer!!.pixels[0]
        assertEquals(1, host.readCalls["resources/big/pack.png"], "像素落地时读一次字节")
        assertEquals(1, host.decodeCalls["resources/big/pack.png"], "同一张父图只解一次")
        pool.find("B")!!.buffer!!.pixels[1]
        assertEquals(1, host.readCalls["resources/big/pack.png"], "第二个 sprite 复用父图，不再读盘")
        assertEquals(1, host.decodeCalls["resources/big/pack.png"])
    }

    @Test
    fun `没有 resources 目录时不抛且零登记`() {
        val report = ResourceContents.load(SpritePool(), FakeHost())
        assertEquals(0, report.fileCount)
        assertEquals(0, report.spriteCount)
    }

    /**
     * 「标题图黑屏、只剩文字」的查表链钉子（4.981 换图后的首屏回归点）。
     *
     * 链条三段：CSV 登记名（`resources/MISC/list.csv:39` 的 `TW_title,title.webp`，混合
     * 大小写）→ 脚本/HTML 按原样小写引用（`TITLE.ERB:61` 的 `img src='TW_title'`）→
     * 按名查表。原版在**插入与查表两侧各自归一**：插入侧 `CreateSpriteG` 的
     * `imgName = imgName.ToUpper()`（AppContents.cs:86，动画形态 :102、CSV 侧
     * `CreateFromCsv` 的 `Trim().ToUpper()` 在 :238）、查表侧 `GetSprite` 的
     * `name.ToUpper(CultureInfo.InvariantCulture)`（AppContents.cs:45）。任何一侧漏了
     * 归一，读数都是「查不到 ⇒ 什么都不画」—— 屏上没有报错可查。
     * 运行时 `SPRITECREATE` 的小写名形态（`Flan_UI.ERB:152` 的
     * `SPRITECREATE @"flan_frame_{gid}"`）与按名释放（AppContents.cs:57）一并钉住。
     */
    @Test
    fun `CSV 混合大小写登记名与脚本原样引用在查表链上互通`() {
        val host = FakeHost()
        host.csv("resources/MISC/list.csv", "TW_title,title.webp")
        host.image("resources/MISC/title.webp", w = 1041, h = 557)
        val pool = SpritePool()

        val report = ResourceContents.load(pool, host)

        assertEquals(1, report.spriteCount)
        assertTrue(report.warnings.isEmpty(), "不该有告警：${report.warnings}")
        // 脚本侧三种写法必须命中同一个条目：4.981 的 <img src='TW_title'> 是原样混合大小写。
        val byScriptForm = pool.find("TW_title")!!
        assertEquals(byScriptForm, pool.find("tw_TITLE"))
        assertEquals(byScriptForm, pool.find("TW_TITLE"))
        assertEquals(1041, byScriptForm.width)
        assertEquals(557, byScriptForm.height)
        assertTrue(byScriptForm.isCreated, "尺寸已由文件头给出，IsCreated 必须为 true（ASpriteSingle.cs:24-34）")
        // 运行时 SPRITECREATE 喂小写名（Flan_UI.ERB:152 形态）：键在本层归一后任意写法查得到，
        // Sprite.name 字段保留调用方原文名（告警与日志还用它）。
        pool.put(SpritePool.Sprite("flan_frame_9001", 10, 20))
        assertEquals("flan_frame_9001", pool.find("Flan_Frame_9001")!!.name)
        assertEquals("flan_frame_9001", pool.find("FLAN_FRAME_9001")!!.name)
        // 按名释放同样按归一键命中（AppContents.SpriteDispose 的 :57）。
        assertTrue(pool.dispose("FLAN_FRAME_9001"))
        assertNull(pool.find("flan_frame_9001"))
        // CSV 资源条目不受运行时释放影响（资源表副本语义，AppContents.cs:66-78）。
        assertEquals(byScriptForm, pool.find("TW_title"))
    }
}
