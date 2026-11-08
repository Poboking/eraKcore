package com.eratw.port.core.host

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.image.PngImage
import com.eratw.port.core.vars.VariableStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 高清立绘合成链的回归闸（玩家报「立绘脸红变脸黑」）。
 *
 * 玩家症状：本体/发丝/眼睛（不透明内容）正常，腮红/吐息（半透明叠层）发暗。
 * 本闸把 highresChara 8 列 CSV（name,file,x,y,w,h,posx,posy）的登记 → GDRAWSPRITE
 * 六参（pos 位移 + SrcRectangle/DestBaseSize 占比缩放，ASpriteSingle.cs:79-85）→
 * SPRITECREATE 六参（SubRect，FunctionMethodCreator.cs:7022-7046）→ blendPixel
 * source-over 的全链钉在纯 JVM 上：
 * - 合成臂：合成图集（不透明本体 + 半透明红腮红格）+ 语料 049さとり.csv 的逐字行，
 *   断言腮红落点叠在本体上是**亮粉**（暗红 = 预乘值被当直通值消费的签名，直接红）；
 * - 登记臂（语料依赖，缺 `ERATW_GAMEDIR` 不生成）：真 049さとり.csv 的 pos/rect 逐值入表。
 *
 * 画像证据：玩家截图与 magick 参考合成的对拍、core 复现产物（repro_effect_canvas /
 * repro_stand_compose 对参考 RMSE 0.00046）见 BOARD 交付说明；真机侧修复效果
 * TODO(verify)（本机 `adb devices` 空）。
 */
class HighresSpriteComposeGateTest {

    // ------------------------------------------------------------------
    // 装置：内存 ResourceFileHost
    // ------------------------------------------------------------------

    /** 图集尺寸（与真 049さとり.webp 同构：1387x1386，最大格 1276+100, 924+462 全在内）。 */
    private val sheetW = 1387
    private val sheetH = 1386

    private class MapResourceHost(
        private val csvBytes: Map<String, ByteArray>,
        private val images: Map<String, PngImage>,
    ) : ResourceFileHost {
        override fun listCsvFiles(): List<String> =
            csvBytes.keys.filter { it.endsWith(".csv", ignoreCase = true) }.sorted()
        // 延迟解码闭包是 readBytes → decode 两步：图片路径必须给非空字节（decode 不看内容，
        // 直接回表中 PngImage），否则闭包首步就 null、像素落地失败、合成全空。
        override fun readBytes(path: String): ByteArray? =
            csvBytes[path] ?: if (images.containsKey(path)) byteArrayOf(1) else null
        override fun imageSize(path: String, bytes: ByteArray?): IntArray? =
            images[path]?.let { intArrayOf(it.width, it.height) }
        override fun decode(path: String, bytes: ByteArray): PngImage? = images[path]
        override fun decodeRegion(path: String, x: Int, y: Int, w: Int, h: Int): PngImage? = null
    }

    private class Base : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw NotImplementedError("base ctx direct call: $name")
        override fun printSystemLine(message: String) {}
    }

    private fun newCtx(): BuiltinEvalContext {
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

    private fun pool(ctx: BuiltinEvalContext): GraphicsPool = ctx.store.aux.slot("gfx") { GraphicsPool() }
    private fun sprites(ctx: BuiltinEvalContext): SpritePool = ctx.store.aux.slot("sprite") { SpritePool() }

    /**
     * 合成图集：不透明「本体」格 + 半透明红「腮红」格 + 半透明白「吐息」格，
     * 其余全透明（a=0, RGB=0 —— 与 core 合成画布的空档同构）。
     */
    private fun syntheticSheet(): PngImage {
        val px = IntArray(sheetW * sheetH) // 全 0 = 全透明
        fun fillRect(cx: Int, cy: Int, w: Int, h: Int, argb: Int) {
            for (y in cy until cy + h) {
                for (x in cx until cx + w) {
                    px[y * sheetW + x] = argb
                }
            }
        }
        // 本体_通常2 (769,924,406,462)：不透明亮肤（真素材脸部皮肤的代理色 R250 G225 B240）
        fillRect(769, 924, 406, 462, 0xFFFAE1F0.toInt())
        // 表情_発情 (1276,436,100,70)：上 34 行不透明深色「睫毛」（像素级判位用），
        // 下半透明 —— 与真素材「眼在上、腮红区透明」的结构同构
        fillRect(1276, 436, 100, 34, 0xFF3C2010.toInt())
        // 効果_赤面 (1276,0,86,58)：半透明红（a=128，R250 G80 B80）
        fillRect(1276, 0, 86, 58, 0x80FA5050.toInt())
        // 効果_喘息 (600,300,182,75)：半透明白（a=200）
        fillRect(600, 300, 182, 75, 0xC8FFFFFF.toInt())
        return PngImage(sheetW, sheetH, px)
    }

    /** 语料 049さとり.csv 的逐字行（本闸用到的 6 行 + 注释头，8 列形态）。 */
    private val csvRows = listOf(
        ";spriteName,元ファイル名,x,y,width,height,posx,posy",
        "highres_49_立ち絵_立ち絵,049さとり.webp,0,0,600,600,0,0",
        "highres_49_効果_赤面,049さとり.webp,1276,0,86,58,258,253",
        "highres_49_効果_喘息,049さとり.webp,600,300,182,75,214,277",
        "highres_49_表情_発情,049さとり.webp,1276,436,100,70,252,241",
        "highres_49_本体_通常2,049さとり.webp,769,924,406,462,93,138",
    )

    private fun loadSprites(ctx: BuiltinEvalContext, sheet: PngImage): ResourceContents.Report {
        val host = MapResourceHost(
            csvBytes = mapOf(
                "resources/highresChara/049さとり.csv" to csvRows.joinToString("\r\n").toByteArray(Charsets.UTF_8),
            ),
            images = mapOf("resources/highresChara/049さとり.webp" to sheet),
        )
        return ResourceContents.load(sprites(ctx), host)
    }

    private fun px(b: GraphicsBuffer, x: Int, y: Int): Int = b.pixels[y * b.width + x]

    private fun desc(argb: Int): String =
        "A=${argb ushr 24} R=${(argb shr 16) and 0xFF} G=${(argb shr 8) and 0xFF} B=${argb and 0xFF}"

    // ------------------------------------------------------------------
    // 合成臂（恒跑）
    // ------------------------------------------------------------------

    @Test
    fun `八列CSV登记_pos位移_腮红叠本体必须是亮粉`() {
        val ctx = newCtx()
        val sheet = syntheticSheet()
        val report = loadSprites(ctx, sheet)
        assertEquals(1, report.fileCount, "1 个 CSV")
        assertEquals(5, report.spriteCount, "5 行静态 sprite")
        assertEquals(0, report.warnings.size, "无告警：${report.warnings.take(3)}")

        // —— 登记读数：8 列的 pos / rect 逐值入表（名字归一大写）——
        val akamen = sprites(ctx).find("HIGHRES_49_効果_赤面")
        assertNotNull(akamen, "赤面未登记")
        assertEquals(1276, akamen.srcX)
        assertEquals(0, akamen.srcY)
        assertEquals(86, akamen.srcW)
        assertEquals(58, akamen.srcH)
        assertEquals(86, akamen.width, "DestBaseSize 缺省 = SrcRect 尺寸")
        assertEquals(58, akamen.height)
        assertEquals(258, akamen.posX, "8 列 CSV 的 posx 必须入表")
        assertEquals(253, akamen.posY, "8 列 CSV 的 posy 必须入表")
        val hontai = sprites(ctx).find("HIGHRES_49_本体_通常2")
        assertNotNull(hontai)
        assertEquals(93, hontai.posX)
        assertEquals(138, hontai.posY)
        assertEquals(406, hontai.srcW)
        val tachae = sprites(ctx).find("HIGHRES_49_立ち絵_立ち絵")
        assertNotNull(tachae)
        assertEquals(600, tachae.width)

        // —— 赤面画布（HIGHRES_CREATE_EFFECT_CANVAS 逐字形态）——
        // 空画布 + 腮红层（原链首笔的 ダミー 是全透明垫底，对落点/颜色判定无影响；
        // 这里不注册 ダミー，等价于「未登记名返回 0、画布留全透明」的原版分支）。
        assertEquals(1L, callI(ctx, "GCREATE", 900, 600, 600))
        val akaW = callI(ctx, "SPRITEWIDTH", "HIGHRES_49_効果_赤面").toInt()
        val akaH = callI(ctx, "SPRITEHEIGHT", "HIGHRES_49_効果_赤面").toInt()
        assertEquals(86, akaW)
        assertEquals(58, akaH)
        assertEquals(1L, callI(ctx, "GDRAWSPRITE", 900, "HIGHRES_49_効果_赤面", 0, 0, akaW, akaH), "赤面绘制")
        assertEquals(1L, callI(ctx, "SPRITECREATE", "PROBE_OUT_AKAMEN", 900, 0, 0, 600, 600), "画布 SPRITECREATE")

        // 赤面格中心 = pos(258,253) + 格内(43,29) = (301,282)：半透明红直接落画布
        val canvas = pool(ctx).get(900)
        val blushOnCanvas = px(canvas, 301, 282)
        assertEquals(128, blushOnCanvas ushr 24, "画布上腮红 alpha = 源 alpha（无预乘往返）：${desc(blushOnCanvas)}")
        assertEquals(250, (blushOnCanvas shr 16) and 0xFF, "透明底上直通 R：${desc(blushOnCanvas)}")
        assertEquals(80, (blushOnCanvas shr 8) and 0xFF, "透明底上直通 G：${desc(blushOnCanvas)}")
        assertEquals(80, blushOnCanvas and 0xFF, "透明底上直通 B：${desc(blushOnCanvas)}")
        assertEquals(0, px(canvas, 0, 0) ushr 24, "画布左上保持全透明")
        assertEquals(0, px(canvas, 599, 599) ushr 24, "画布右下保持全透明")

        // —— 立绘合成（HIGHRES_GET_BASE_RESOURCE 逐字形态：本体 → 表情 → 赤面叠加）——
        assertEquals(1L, callI(ctx, "GCREATE", 901, 600, 600))
        val bodyW = callI(ctx, "SPRITEWIDTH", "HIGHRES_49_本体_通常2").toInt()
        val bodyH = callI(ctx, "SPRITEHEIGHT", "HIGHRES_49_本体_通常2").toInt()
        assertEquals(406, bodyW)
        assertEquals(462, bodyH)
        assertEquals(1L, callI(ctx, "GDRAWSPRITE", 901, "HIGHRES_49_本体_通常2", 0, 0, bodyW, bodyH), "本体绘制")
        val faceW = callI(ctx, "SPRITEWIDTH", "HIGHRES_49_表情_発情").toInt()
        val faceH = callI(ctx, "SPRITEHEIGHT", "HIGHRES_49_表情_発情").toInt()
        assertEquals(1L, callI(ctx, "GDRAWSPRITE", 901, "HIGHRES_49_表情_発情", 0, 0, faceW, faceH), "表情绘制")
        assertEquals(1L, callI(ctx, "GDRAWSPRITE", 901, "HIGHRES_49_効果_赤面", 0, 0, akaW, akaH), "赤面叠加")
        assertEquals(1L, callI(ctx, "SPRITECREATE", "PROBE_OUT_STAND", 901, 0, 0, 600, 600))

        val stand = pool(ctx).get(901)
        // 本体落点 = pos(93,138)：左上角必须是不透明本体色
        val bodyTopLeft = px(stand, 93, 138)
        assertEquals(255, bodyTopLeft ushr 24, "本体左上不透明：${desc(bodyTopLeft)}")
        assertEquals(0xFA, (bodyTopLeft shr 16) and 0xFF, "本体色直通：${desc(bodyTopLeft)}")
        // 表情落点 = pos(252,241)：不透明深色睫毛线
        val facePx = px(stand, 252, 241)
        assertEquals(0x3C, (facePx shr 16) and 0xFF, "表情线落位：${desc(facePx)}")
        // —— 反黑闸：腮红叠本体（(301,282) 处 dst = 本体亮肤，表情格下半已透明）——
        // 直通域精确期望：r=(250*128+250*127)/255=250、g=(80*128+225*127)/255=152、
        // b=(80*128+240*127)/255=160。旧式在此存出预乘形 (125,60,68) ⇒ 屏上发暗（玩家症状）。
        val cheek = px(stand, 301, 282)
        assertEquals(255, cheek ushr 24, "叠加后不透明：${desc(cheek)}")
        assertEquals(250, (cheek shr 16) and 0xFF, "腮红叠本体 R 直通域精确值：${desc(cheek)}")
        assertEquals(152, (cheek shr 8) and 0xFF, "腮红叠本体 G：${desc(cheek)}")
        assertEquals(159, cheek and 0xFF, "腮红叠本体 B（分子截断）：${desc(cheek)}")
        // 吐息格叠本体：(214,277) 处 a=200 白叠肤 → 亮白
        assertEquals(1L, callI(ctx, "GDRAWSPRITE", 901, "HIGHRES_49_効果_喘息", 0, 0, 182, 75), "喘息叠加")
        val breathPx = px(stand, 214, 277)
        assertTrue(
            ((breathPx shr 16) and 0xFF) >= 240 && ((breathPx shr 8) and 0xFF) >= 240,
            "半透明白叠本体必须保持亮白：${desc(breathPx)}",
        )
    }

    @Test
    fun `未登记名返回0_整笔出界不写像素`() {
        val ctx = newCtx()
        loadSprites(ctx, syntheticSheet())
        assertEquals(1L, callI(ctx, "GCREATE", 910, 400, 400))
        assertEquals(0L, callI(ctx, "GDRAWSPRITE", 910, "没有这个Sprite", 0, 0), "未注册名返回 0（GetSprite 未注册名返回 0）")
        // 整笔出界：pos 位移后内容 (258,253) 落在原点 (−500,−500) 的画布外 ⇒ 一个像素都不写
        assertEquals(1L, callI(ctx, "GDRAWSPRITE", 910, "HIGHRES_49_効果_赤面", -500, -500, 86, 58))
        val g = pool(ctx).get(910)
        assertTrue(g.pixels.all { it == 0 }, "整笔出界不写像素（画写不变量）")
        // 部分裁剪的数学由 GraphicsBufferBlitFromTest 钉住，这里不重复
    }

    // ------------------------------------------------------------------
    // 登记臂（语料依赖，缺 ERATW_GAMEDIR 不生成）
    // ------------------------------------------------------------------

    @Test
    fun `真语料049さとり_csv的pos与rect逐值入表`() {
        val gamedir = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
        val csv = gamedir?.resolve("resources/highresChara/049さとり.csv")
        if (gamedir == null || !Files.isRegularFile(csv)) {
            println("[highres-gate] SKIP：缺 ERATW_GAMEDIR 或 049さとり.csv（语料依赖动态用例）")
            return
        }
        val ctx = newCtx()
        val host = MapResourceHost(
            csvBytes = mapOf("resources/highresChara/049さとり.csv" to Files.readAllBytes(csv)),
            // 登记期只读文件头（Deferred）：占位图给真实容器尺寸（1387x1386），否则各格
            // 越界「超出父图」全被拒登
            images = mapOf("resources/highresChara/049さとり.webp" to PngImage(1387, 1386, IntArray(1))),
        )
        val report = ResourceContents.load(sprites(ctx), host)
        assertTrue(
            report.spriteCount >= 29,
            "真表 29 行：${report.spriteCount}（fileCount=${report.fileCount}，warnings=${report.warnings.take(5)}）",
        )
        assertEquals(0, report.warnings.size, "真表登记无告警：${report.warnings.take(3)}")

        fun expect(name: String, srcX: Int, srcY: Int, srcW: Int, srcH: Int, posX: Int, posY: Int) {
            val s = sprites(ctx).find(name)
            assertNotNull(s, "$name 未登记")
            assertEquals(srcX, s.srcX, "$name srcX")
            assertEquals(srcY, s.srcY, "$name srcY")
            assertEquals(srcW, s.srcW, "$name srcW")
            assertEquals(srcH, s.srcH, "$name srcH")
            assertEquals(posX, s.posX, "$name posX")
            assertEquals(posY, s.posY, "$name posY")
        }
        expect("HIGHRES_49_立ち絵_立ち絵", 0, 0, 600, 600, 0, 0)
        expect("HIGHRES_49_効果_赤面", 1276, 0, 86, 58, 258, 253)
        expect("HIGHRES_49_効果_喘息", 600, 300, 182, 75, 214, 277)
        expect("HIGHRES_49_表情_発情", 1276, 436, 100, 70, 252, 241)
        expect("HIGHRES_49_本体_通常2", 769, 924, 406, 462, 93, 138)
        expect("HIGHRES_49_効果_汗", 1276, 58, 46, 44, 280, 264)
    }
}
