package com.eratw.port.core.host

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BackgroundAdmission
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.image.PngImage
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 玩家报告 `eratw-report-20261108-002637-trail.txt`（构建 9363da7 / 0.2.43，
 * Java 堆 511/512 MiB、`trim 0`、长回合 `@GET_SCORE_INFO_NO`）屏上原文：
 *
 * > 脚本错误中止本回合：除数为0的除法运算（QOL_SPTALK 行135）
 *
 * 直接现场是 4.981 语料 `Toolkits.ERB:135` 的 MOB 分支：
 *
 * ```erb
 * RES_NAME '= @"顔絵_%服装%_%表情%_MOB_{デフォルト}"
 * SIF !SPRITECREATED(RES_NAME)
 *     CALL 路人店員画像作成(デフォルト)
 * IMG_W_PX = SPRITEWIDTH(RES_NAME) * IMG_H_PX / SPRITEHEIGHT(RES_NAME)
 * ```
 *
 * `路人店員画像作成` 的第一笔是 `画像合成(GID,"ダミー")`，而 `画像合成` 开头就是
 * `SIF !SPRITECREATED(リソース名) RETURN 0`。CSV 资源表里 `ダミー` 一旦报「未创建」，
 * G 建不出来、`リソース登録` 跟着返回 0，MOB sprite 就不存在 ⇒ `SPRITEHEIGHT == 0` ⇒ 除零。
 *
 * 官方口径（`SpriteF.cs:5-10` / `ASpriteSingle.cs:24-34` / `ASprite.cs:9,16-30` /
 * `AppContents.cs:196-203`）：CSV sprite 的基图是装载期一次建好的 `ConstImage`，
 * `DestBaseSize` 是 readonly，`UnloadGraphicList` 只清 `gList` 里的 `GraphicsImage`。
 * 也就是**只要登记表里还有这条 sprite，SPRITE* 读数就是登记期事实**，
 * 与延迟解码、像素逐出、回标题这些内部内存动作无关。
 *
 * 本文件钉的正是这条不变量（修复前必红）：
 * - `ダミー` 走真 `ResourceContents.load`（含 `infoThumb\blank.webp` 的反斜杠归一）；
 * - 父图被清零（`dispose()` 造出的终态与 `GraphicsBuffer.pixels` 的 OOM 支
 *   `Surface(0,0,...)`、`gotoTitle` 旧实现的无差别 dispose 逐字同形）之后，
 *   `SPRITECREATED / SPRITEWIDTH / SPRITEHEIGHT` 仍给登记值；
 * - 解码失败的「透明降级」支同样不动读数；
 * - 运行时 `SpriteG`（正号段 G_ID）仍跟随 `GDISPOSE`，既有语义不回归；
 * - 父图 0x0 时 `GDRAWSPRITE` 按「缺图降级」静默返回，不抛 CodeEE。
 */
class ResourceSpriteCreatedStateTest {

    /** 与 `resources/MISC/list.csv:81` 逐字同形的一行。 */
    private val dummyCsv = """
        ;ダミー,ダミー.webp
        ダミー,infoThumb\blank.webp,0,0,180,180
    """.trimIndent()

    private val dummyPath = "resources/MISC/infoThumb/blank.webp"

    /**
     * 纯 JVM 假宿主：登记期只给文件头（4.981 的 2,795 张图共 570.6 MB，装载期一个图字节
     * 都不许读），像素按需落地；[broken] 打开时文件头读得出、像素解不出（解码 OOM/缺图）。
     */
    private inner class Host(
        private val csvBytes: ByteArray,
        private val broken: Boolean = false,
    ) : ResourceFileHost {
        override fun listCsvFiles(): List<String> = listOf("resources/MISC/list.csv")

        override fun readBytes(path: String): ByteArray? = when {
            path.endsWith(".csv") -> csvBytes
            path == dummyPath -> byteArrayOf(1) // 延迟闭包首步必须有字节，否则直接按「读不到」降级
            else -> null
        }

        override fun imageSize(path: String, bytes: ByteArray?): IntArray? =
            if (path == dummyPath) intArrayOf(250, 250) else null

        override fun decode(path: String, bytes: ByteArray): PngImage? =
            if (path == dummyPath && !broken) PngImage(250, 250, IntArray(250 * 250)) else null

        override fun decodeRegion(path: String, x: Int, y: Int, w: Int, h: Int): PngImage? = null
    }

    private class Base : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw NotImplementedError("base ctx direct call: $name")
        override fun printSystemLine(message: String) {}
    }

    private fun ctx(): BuiltinEvalContext {
        val store = VariableStore()
        val base = Base()
        base.variables = store
        return BuiltinEvalContext(base, store, object : BuiltinHost {})
    }

    private fun call(ctx: BuiltinEvalContext, name: String, vararg args: Any): Value =
        ctx.callFunction(
            name,
            args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
        )

    private fun callI(ctx: BuiltinEvalContext, name: String, vararg args: Any): Long =
        (call(ctx, name, *args) as Value.IntV).v

    /** 装载 `ダミー` 并返回（上下文, sprite 池, CSV 父图）。 */
    private fun loadDummy(broken: Boolean = false): Triple<BuiltinEvalContext, SpritePool, GraphicsBuffer> {
        val c = ctx()
        val pool = c.store.aux.slot<SpritePool>("sprite") { SpritePool() }
        val report = ResourceContents.load(pool, Host(dummyCsv.toByteArray(Charsets.UTF_8), broken))
        assertEquals(1, report.fileCount)
        assertEquals(1, report.spriteCount, "ダミー 这一行不许被丢")
        assertTrue(report.warnings.isEmpty(), "不该有告警：${report.warnings}")
        val sprite = pool.find("ダミー")
        assertNotNull(sprite, "CSV 登记名按 ToUpper 归一，日文名原样可查")
        val parent = sprite.buffer
        assertNotNull(parent, "静态 CSV sprite 必带父图")
        assertTrue(parent.id < 0, "CSV 父图必须落在负号段（SpriteF 的判据）")
        assertEquals(dummyPath, parent.fileSource)
        return Triple(c, pool, parent)
    }

    @Test
    fun `登记期读数按 CSV 的 DestBaseSize 给出且父图落在负号段`() {
        val (c, _, parent) = loadDummy()
        assertEquals(250, parent.width, "父图尺寸来自文件头")
        assertEquals(250, parent.height)
        assertEquals(1L, callI(c, "SPRITECREATED", "ダミー"))
        assertEquals(180L, callI(c, "SPRITEWIDTH", "ダミー"), "6 列形态 destSize = rect.Size（AppContents.cs:300-356）")
        assertEquals(180L, callI(c, "SPRITEHEIGHT", "ダミー"))
    }

    @Test
    fun `父图被清零后 CSV sprite 的 SPRITE 读数仍是登记值，不再给脚本递 0 分母`() {
        val (c, _, parent) = loadDummy()

        // 堆贴顶现场：`GraphicsBuffer.pixels` 的 OOM 支发布 `Surface(0,0,EMPTY_PIXELS,null,null)`；
        // 回标题旧实现逐 sprite `dispose()` 发布 null。两者可观察终态逐字同形：
        // 父图宽高 0、isCreated 假、重解源已清。`dispose()` 是纯 JVM 里确定性的造态手段。
        parent.dispose()
        assertFalse(parent.isCreated)
        assertEquals(0, parent.width)
        assertEquals(0, parent.height)

        // 修复前 Sprite.isCreated 委托给 buffer ⇒ 三行全塌成 0，
        // `Toolkits.ERB:135` 的 SPRITEWIDTH(...) * IMG_H_PX / SPRITEHEIGHT(...) 当场除零。
        assertEquals(1L, callI(c, "SPRITECREATED", "ダミー"), "SpriteF 的创建态是登记期事实，不看像素驻留")
        assertEquals(180L, callI(c, "SPRITEWIDTH", "ダミー"), "DestBaseSize readonly（ASprite.cs:9）")
        assertEquals(180L, callI(c, "SPRITEHEIGHT", "ダミー"), "分母不许变 0")
    }

    @Test
    fun `解码失败退化成透明图时读数同样不动，且不反复重试解码`() {
        val (c, _, parent) = loadDummy(broken = true)
        assertEquals(250 * 250, parent.pixels.size, "失败退化成同尺寸全透明图")
        assertEquals(1L, callI(c, "SPRITECREATED", "ダミー"))
        assertEquals(180L, callI(c, "SPRITEHEIGHT", "ダミー"))
        // 现有语义：失败结果清掉延迟源，不每次访问都重试（DeferredImageEvictionTest 已钉）。
        assertEquals(0L, parent.evictDeferred())
    }

    @Test
    fun `父图 0x0 时 GDRAWSPRITE 按缺图降级静默返回，不抛 CodeEE`() {
        val (c, _, parent) = loadDummy()
        parent.dispose()
        val gfx = c.store.aux.slot<GraphicsPool>("gfx") { GraphicsPool() }
        assertEquals(1L, callI(c, "GCREATE", 10, 180, 180))
        assertTrue(gfx.get(10).isCreated)
        // blitFrom 的 `srcW <= 0 || srcH <= 0` 早退：一笔都不画，但函数照常返回 1，
        // 于是 画像合成 的 `SIF RESULT == 0 RETURN 0` 不会把 GID 判死，
        // リソース登録 能建出 MOB sprite ⇒ QOL_SPTALK 拿到非零分母。
        assertEquals(1L, callI(c, "GDRAWSPRITE", 10, "ダミー"))
        assertEquals(1L, callI(c, "SPRITECREATE", "MOB_0", 10))
        assertEquals(180L, callI(c, "SPRITEHEIGHT", "MOB_0"))
    }

    @Test
    fun `运行时 SpriteG 仍跟随 GDISPOSE，既有语义不回归`() {
        val c = ctx()
        val gfx = c.store.aux.slot<GraphicsPool>("gfx") { GraphicsPool() }
        val pool = c.store.aux.slot<SpritePool>("sprite") { SpritePool() }
        val g = gfx.get(7)
        g.create(4, 4)
        pool.put(SpritePool.Sprite("G_SPR", 4, 4, buffer = g))
        assertTrue(g.id >= 0, "正号段 = gList 的 GraphicsImage（SpriteG）")
        assertEquals(1L, callI(c, "SPRITECREATED", "G_SPR"))
        g.dispose()
        assertEquals(0L, callI(c, "SPRITECREATED", "G_SPR"), "GotoTitleTest 钉的既有语义逐字不变")
        assertEquals(0L, callI(c, "SPRITEHEIGHT", "G_SPR"))
    }

    // ------------------------------------------------------------------
    // 复核补闸：`isCreated` 恒 true 之后被打开的下游消费者
    // ------------------------------------------------------------------

    /**
     * 语料 `グラフィック合成.ERB@画像合成` 用的是 **6 参** `GDRAWSPRITE`，且末尾有
     * `SIF RESULT == 0 / RETURN 0`：这一笔返回 0 会把 GID 判死，`リソース登録` 跟着失败。
     * 上面那例只覆盖 2 参形态，这里按语料逐字走完整条链。
     */
    @Test
    fun `画像合成的 6 参 GDRAWSPRITE 全链在父图 0x0 时仍建得出 MOB sprite`() {
        val (c, _, parent) = loadDummy()
        parent.dispose() // 堆贴顶：pixels 的 OOM 支把父图整代清零
        c.store.aux.slot<GraphicsPool>("gfx") { GraphicsPool() }

        // `IF 横幅 == 0 || 縦幅 == 0` → 横幅/縦幅 取 SPRITEWIDTH/HEIGHT
        val w = callI(c, "SPRITEWIDTH", "ダミー")
        val h = callI(c, "SPRITEHEIGHT", "ダミー")
        assertEquals(180L, w)
        assertEquals(180L, h, "这里就是 QOL_SPTALK 行135 的分母来源")
        assertEquals(1L, callI(c, "GCREATE", 10, w, h), "修复前这里会拿到 0x0 并抛「宽度必须为正数」")
        // GDRAWSPRITE GID, リソース名, POS_X, POS_Y, 横幅, 縦幅
        assertEquals(1L, callI(c, "GDRAWSPRITE", 10, "ダミー", 0, 0, w, h), "RESULT 非 0，画像合成 才不 RETURN 0")
        assertEquals(1L, callI(c, "SPRITECREATE", "MOB_0", 10))
        assertEquals(180L, callI(c, "SPRITEHEIGHT", "MOB_0"), "MOB sprite 的分母不许是 0")
    }

    @Test
    fun `父图 0x0 时 SPRITEGETCOLOR 给透明色而不是越界或异常`() {
        val (c, _, parent) = loadDummy()
        parent.dispose()
        // isCreated 恒 true 让本 builtin 走进取色体；`!buf.isCreated` 守卫给 Color.Transparent
        // （ASpriteSingle.cs:50-64），`pixelAt` 另有越界兜底，两者都不许把 UI/VM 带崩。
        assertEquals(0x00FFFFFFL, callI(c, "SPRITEGETCOLOR", "ダミー", 0, 0))
    }

    @Test
    fun `SPRITEDISPOSE 对资源名按官方命中，SPRITEDISPOSEALL 0 不改读数`() {
        val (c, _, _) = loadDummy()
        assertEquals(0L, callI(c, "SPRITEDISPOSEALL", 0), "count - resourceCount = 0")
        assertEquals(180L, callI(c, "SPRITEHEIGHT", "ダミー"), "回滚成资源表副本后读数逐字不动")
        // 官方 SpriteF.IsCreated 恒 true，`SpriteDisposeMethod` 的守卫放行 ⇒ 返回 1 并摘键。
        // 这是脚本显式释放，与本修复无关；钉住它是为了确认新分支没有把它折成 0。
        assertEquals(1L, callI(c, "SPRITEDISPOSE", "ダミー"))
        assertEquals(0L, callI(c, "SPRITEHEIGHT", "ダミー"), "键已摘：AppContents.SpriteDispose 同口径")
    }

    @Test
    fun `动画 sprite 恒 true 的既有分支不受影响，读数不随 GDISPOSE 变`() {
        val c = ctx()
        val gfx = c.store.aux.slot<GraphicsPool>("gfx") { GraphicsPool() }
        val pool = c.store.aux.slot<SpritePool>("sprite") { SpritePool() }
        val g = gfx.get(7)
        g.create(4, 4)
        val frames = mutableListOf(SpritePool.Frame(g, 0, 0, 4, 4, 0, 0, 100))
        pool.put(SpritePool.Sprite("ANIM", 4, 4, frames = frames))
        assertEquals(1L, callI(c, "SPRITECREATED", "ANIM"))
        g.dispose()
        assertEquals(1L, callI(c, "SPRITECREATED", "ANIM"), "SpriteAnime.IsCreated 恒 true（SpriteAnime.cs:54）")
        assertEquals(4L, callI(c, "SPRITEHEIGHT", "ANIM"))
    }

    @Test
    fun `整幅 CSV sprite 正常态可进背景层，父图 0x0 后被 src 判据挡住`() {
        val parent = GraphicsBuffer(-1)
        assertTrue(parent.createDeferredFromFile(64, 64, "resources/MISC/full.webp") { IntArray(64 * 64) })
        val sprite = SpritePool.Sprite("FULL", 64, 64, parent, 0, 0, 64, 64)
        assertTrue(BackgroundAdmission.admissible(sprite))
        parent.dispose()
        assertTrue(sprite.isCreated, "SpriteF 创建态是登记期事实，不看像素驻留")
        // 恒 true 之后背景准入靠最后一道「src 覆盖整幅 buffer」兜住：登记 srcW=64 而
        // buffer.width=0 ⇒ 仍不收，不存在把 0x0 死图吸进背景层的路径。
        assertFalse(BackgroundAdmission.admissible(sprite))
    }
}
