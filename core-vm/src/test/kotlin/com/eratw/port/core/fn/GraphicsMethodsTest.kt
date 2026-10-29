package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.CbgPool
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 图形缓冲 / Sprite / CBG 家族测试。
 *
 * 判据：`fixtures/golden/baseline/pc-golden-root.txt:634-692`（契约 §4 A4 硬判据）+
 * 调用序列 `fixtures/golden/GOLDEN_11_GVIEW.ERB:13-79`。每个断言用的是**基准字面值**，
 * 不是实现算出来的值。
 *
 * 装置说明：
 * - 每个用例一个 [BuiltinEvalContext]（状态挂 `store.aux`，用例之间不串味）；
 * - [GvFileOps] 是内存文件系统，GSAVE/GLOAD 走它；
 * - `SPRITEDISPOSEALL=35`（基准 673 行）依赖 PC 会话前置状态：进入 GOLDEN_GVIEW 之前
 *   `imageDictionary` 里已有 35 个**非资源** Sprite。装置用 [SpritePool.seedRuntimeSprites]
 *   还原该前置条件（见 GraphicsBuffer.kt 的 KDoc）。
 */
private class GvBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("GvBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 内存 FileOps：路径即键，写入成功恒 true。 */
private class GvFileOps : FileOps {
    val files = LinkedHashMap<String, String>()
    override fun exists(path: String): Boolean = files.containsKey(path)
    override fun readText(path: String): String? = files[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        files[path] = if (append) (files[path] ?: "") + text else text
        return true
    }
    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private fun gvCtx(ops: FileOps? = GvFileOps()): BuiltinEvalContext {
    val store = VariableStore()
    val base = GvBaseCtx()
    base.variables = store
    val host = object : BuiltinHost {
        override val fileOps: FileOps? = ops
    }
    return BuiltinEvalContext(base, store, host)
}

/** 调用内建：字符串参数传 [String]，整数参数传 [Long]/[Int]。 */
private fun gvCall(ctx: BuiltinEvalContext, name: String, vararg args: Any): Value =
    ctx.callFunction(
        name,
        args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
    )

private fun gvI(ctx: BuiltinEvalContext, name: String, vararg args: Any): Long =
    (gvCall(ctx, name, *args) as Value.IntV).v

private fun gvS(ctx: BuiltinEvalContext, name: String, vararg args: Any): String =
    (gvCall(ctx, name, *args) as Value.StrV).v

private fun gvPool(ctx: BuiltinEvalContext): GraphicsPool = ctx.store.aux.slot("gfx") { GraphicsPool() }
private fun gvSprites(ctx: BuiltinEvalContext): SpritePool = ctx.store.aux.slot("sprite") { SpritePool() }

class GraphicsMethodsTest {

    // ==================================================================
    // SECTION:G-BUFFER —— 基准 635-659
    // ==================================================================

    @Test
    fun `G-BUFFER 逐行复现基准 635-659`() {
        val ctx = gvCtx()

        // 635 GCREATE=1  /  GOLDEN_11:15 GCREATE(10, 100, 60)
        assertEquals(1L, gvI(ctx, "GCREATE", 10, 100, 60))
        // 636 GCREATED=1 MISS=0
        assertEquals(1L, gvI(ctx, "GCREATED", 10))
        assertEquals(0L, gvI(ctx, "GCREATED", 999))
        // 637 GWIDTH=100 GHEIGHT=60
        assertEquals(100L, gvI(ctx, "GWIDTH", 10))
        assertEquals(60L, gvI(ctx, "GHEIGHT", 10))
        // 638 GCLEAR=1  —— 注意颜色 0x0000FF 的 alpha=0
        assertEquals(1L, gvI(ctx, "GCLEAR", 10, 0x0000FF))
        // 639 GSETBRUSH=1  / 640 GFILLRECT=1
        assertEquals(1L, gvI(ctx, "GSETBRUSH", 10, 0x00FF00))
        assertEquals(1L, gvI(ctx, "GFILLRECTANGLE", 10, 10, 10, 20, 20))
        // 641 GSETCOLOR=0  —— 参数是 (id, color, x, y)：y=0xFF0000 越界
        assertEquals(0L, gvI(ctx, "GSETCOLOR", 10, 5, 5, 0xFF0000))
        // 642 GGETCOLOR=0/0  —— A=0 的 GCLEAR 结果是全 0（GDI+ 预乘口径）
        assertEquals(0L, gvI(ctx, "GGETCOLOR", 10, 5, 5))
        assertEquals(0L, gvI(ctx, "GGETCOLOR", 10, 0, 0))
        // 643 GGETBRUSH=65280  —— 0x0000FF00 原样保留 alpha 位
        assertEquals(65280L, gvI(ctx, "GGETBRUSH", 10))
        // 644 GSETPEN=1 GGETPEN=6636321 W=3
        assertEquals(1L, gvI(ctx, "GSETPEN", 10, 0x654321, 3))
        assertEquals(6636321L, gvI(ctx, "GGETPEN", 10))
        assertEquals(3L, gvI(ctx, "GGETPENWIDTH", 10))
        // 645 GDRAWLINE=1
        assertEquals(1L, gvI(ctx, "GDRAWLINE", 10, 0, 0, 50, 30))
        // 646 GSETFONT=1  / 647 GGETFONT=黑体 SIZE=16 STYLE=1
        assertEquals(1L, gvI(ctx, "GSETFONT", 10, "黑体", 16, 1))
        assertEquals("黑体", gvS(ctx, "GGETFONT", 10))
        assertEquals(16L, gvI(ctx, "GGETFONTSIZE", 10))
        assertEquals(1L, gvI(ctx, "GGETFONTSTYLE", 10))
        // 648 GDRAWTEXT=1
        assertEquals(1L, gvI(ctx, "GDRAWTEXT", 10, "HELLO", 0, 0))
        // 649 GSAVE=1 GLOAD=1  —— 17 号是全新缓冲
        assertEquals(1L, gvI(ctx, "GSAVE", 10, 0))
        assertEquals(1L, gvI(ctx, "GLOAD", 17, 0))
        // 650 GLOAD_DISPOSE=1
        assertEquals(1L, gvI(ctx, "GDISPOSE", 17))
        // 651 GDISPOSE14=0  —— 14 号此时尚未 GCREATE（654 行才建）
        assertEquals(0L, gvI(ctx, "GDISPOSE", 14))
        // 652 GDRAWG=1  / 653 GDRAWGWITHROTATE=1
        assertEquals(1L, gvI(ctx, "GDRAWG", 10, 10, 30, 0, 20, 20, 0, 0, 20, 20))
        assertEquals(1L, gvI(ctx, "GDRAWGWITHROTATE", 10, 10, 0, 0, 45))
        // 654 GCREATE_MASK=1
        assertEquals(1L, gvI(ctx, "GCREATE", 14, 20, 20))
        // 655 GDRAWGWITHMASK_MISMATCH=0  —— src(100x60) 与 mask(20x20) 尺寸不符
        assertEquals(0L, gvI(ctx, "GDRAWGWITHMASK", 10, 10, 14, 0, 0))
        // 656 GCREATE_SRC=1  / 657 GDRAWGWITHMASK=1
        assertEquals(1L, gvI(ctx, "GCREATE", 15, 20, 20))
        assertEquals(1L, gvI(ctx, "GDRAWGWITHMASK", 10, 15, 14, 0, 0))
        // 658 GDASHSTYLE=1
        assertEquals(1L, gvI(ctx, "GDASHSTYLE", 10, 0, 0))
        // 659 GDISPOSE=1
        assertEquals(1L, gvI(ctx, "GDISPOSE", 10))

        // 649 行同时验证存档路径语义：{Config.SavDir}img{index:0000}.png（:9471-9474）
        val ops = ctx.host.fileOps as GvFileOps
        assertTrue(ops.files.containsKey("sav/img0000.png"), "GSAVE 路径应为 sav/img0000.png，实际 ${ops.files.keys}")
    }

    /**
     * GDRAWG / GFILLRECTANGLE 的大坐标不 Int 溢出。
     *
     * 原版矩形交给 GDI+ 按 float 处理（GraphicsImage.cs:305-315 的
     * `g.DrawImage(bitmap, destRect, srcRect, GraphicsUnit.Pixel)`）：坐标/宽高乘加**不回绕**，
     * 目标区裁剪到表面、源外样本透明；参数级范围检查在 ReadRectangle（FMC.cs:9377-9400，
     * 只拒 w==0/h==0）。故 `sw*sh = 65536*65536`（Int 里回绕成 0）、`dx = Int.MAX_VALUE-2`
     * 这类输入必须照常返回 1 且只影响可见交叠区。
     */
    @Test
    fun `GDRAWG 与 GFILLRECTANGLE 的大坐标不 Int 溢出`() {
        val ctx = gvCtx()
        val pool = gvPool(ctx)
        val src = pool.get(10)
        src.create(4, 4)
        src.setPixel(0, 0, 0xFF112233.toInt())
        val dst = pool.get(11)
        dst.create(4, 4)
        dst.clear(0xFF0000FF.toInt())

        // sw*sh 在 Int 里回绕成 0：旧实现 IntArray(sw*sh) 得到长度 0 的快照后索引崩溃
        assertEquals(1L, gvI(ctx, "GDRAWG", 11, 10, 0, 0, 4, 4, 0, 0, 65536, 65536))
        assertEquals(0xFF112233.toInt(), dst.pixelAt(0, 0), "src(0,0) 采样落点")
        assertEquals(0xFF0000FF.toInt(), dst.pixelAt(3, 3), "源外样本透明，落回原色")

        // 目标/源坐标大到 Int 加法会溢出：不崩、不越面写像素，返回 1
        assertEquals(1L, gvI(ctx, "GDRAWG", 11, 10, Int.MAX_VALUE - 2, 0, 4, 4, 0, 0, 4, 4))
        assertEquals(0xFF0000FF.toInt(), dst.pixelAt(0, 3))
        assertEquals(1L, gvI(ctx, "GDRAWG", 11, 10, 0, Int.MIN_VALUE, 4, 4, 0, 0, 4, 4))
        assertEquals(0xFF0000FF.toInt(), dst.pixelAt(0, 3))

        // GFILLRECTANGLE：x + w 溢出不再把交叠区整段裁没
        val g2 = pool.get(12)
        g2.create(20, 20)
        g2.brushColor = 0xFF00FF00.toInt()
        assertEquals(1L, gvI(ctx, "GFILLRECTANGLE", 12, 1, 0, Int.MAX_VALUE, 20))
        assertEquals(0xFF00FF00.toInt(), g2.pixelAt(19, 10))
        assertEquals(0, g2.pixelAt(0, 10))
    }

    @Test
    fun `GDRAWG 源矩形越界按源图裁剪且不分配超大快照`() {
        val ctx = gvCtx()
        assertEquals(1L, gvI(ctx, "GCREATE", 10, 40, 40))
        assertEquals(1L, gvI(ctx, "GCREATE", 11, 20, 20))
        assertEquals(1L, gvI(ctx, "GCLEAR", 11, 0xFF00FF00))
        // 源矩形 40x40 超出 20x20 的源图：GDI+ 按**声明尺寸**换算缩放、只画落在源图内的采样
        // （dotnet 8.0.27 + net8.0-windows 实测：DrawImage(80x80 dest, 40x40 src, 20x20 图)
        //   (10,10) 有像素、(60,60) 透明）。这里源/目标同为 40 ⇒ 1:1，(0..19,0..19) 命中。
        assertEquals(1L, gvI(ctx, "GDRAWG", 10, 11, 0, 0, 40, 40, 0, 0, 40, 40))
        assertEquals(4278255360L, gvI(ctx, "GGETCOLOR", 10, 0, 0))    // 0xFF00FF00
        assertEquals(4278255360L, gvI(ctx, "GGETCOLOR", 10, 19, 19))
        assertEquals(0L, gvI(ctx, "GGETCOLOR", 10, 20, 20))           // 源图外 ⇒ 不画
        // 修复前 `IntArray(sw * sh)`：1e12 溢出成负数 ⇒ NegativeArraySizeException。
        // 现在只快照裁剪后的 20x20，并按声明尺寸 1e6 换算（只有 (0,0) 命中源像素）。
        assertEquals(1L, gvI(ctx, "GCLEAR", 10, 0))
        assertEquals(1L, gvI(ctx, "GDRAWG", 10, 11, 0, 0, 40, 40, 0, 0, 1000000, 1000000))
        assertEquals(4278255360L, gvI(ctx, "GGETCOLOR", 10, 0, 0))
        assertEquals(0L, gvI(ctx, "GGETCOLOR", 10, 1, 1))
        // 完全越界 / 负宽高：GDI+ 什么都不画、不抛错（同实测）
        assertEquals(1L, gvI(ctx, "GDRAWG", 10, 11, 0, 0, 40, 40, 100, 100, 20, 20))
        assertEquals(1L, gvI(ctx, "GDRAWG", 10, 11, 0, 0, 40, 40, -100, -100, 20, 20))
        assertEquals(1L, gvI(ctx, "GDRAWG", 10, 11, 0, 0, 40, 40, 0, 0, -5, 20))
        // sx + sw 溢出 Int 的极端实参：Long 求和后判定完全越界
        assertEquals(1L, gvI(ctx, "GDRAWG", 10, 11, 0, 0, 40, 40, 2147483647, 0, 2147483647, 20))
    }

    @Test
    fun `GSAVE 后 GLOAD 往返像素逐字节一致`() {
        val ctx = gvCtx()
        assertEquals(1L, gvI(ctx, "GCREATE", 10, 8, 4))
        // 不透明底色 + 两个特征像素（A=0 的 Clear 会归零，这里用 A=255 才能比较像素）
        assertEquals(1L, gvI(ctx, "GCLEAR", 10, 0xFF112233))
        assertEquals(1L, gvI(ctx, "GSETCOLOR", 10, 0xFF00FF00, 1, 2))
        assertEquals(1L, gvI(ctx, "GSETCOLOR", 10, 0x80ABCDEF, 7, 3))

        val before = gvPool(ctx).get(10).pixels.copyOf()
        assertEquals(1L, gvI(ctx, "GSAVE", 10, 3))
        assertEquals(1L, gvI(ctx, "GLOAD", 17, 3))
        val after = gvPool(ctx).get(17).pixels
        assertTrue(before.contentEquals(after), "GSAVE→GLOAD 后像素应完全一致")
        // 顺带核对取色：0xFF00FF00 无符号化 = 4278255360
        assertEquals(4278255360L, gvI(ctx, "GGETCOLOR", 17, 1, 2))
        // 同一个槽再 GLOAD 一次：目标已创建 → 0（基准 649 行注释所指分支）
        assertEquals(0L, gvI(ctx, "GLOAD", 17, 3))
    }

    // ==================================================================
    // SECTION:SPRITE —— 基准 661-673
    // ==================================================================

    @Test
    fun `SPRITE 逐行复现基准 661-673`() {
        val ctx = gvCtx()
        // 673 行的前置条件：PC 运行时已有 35 个非资源 Sprite
        gvSprites(ctx).seedRuntimeSprites(35)

        // 661 GCREATE2=1
        assertEquals(1L, gvI(ctx, "GCREATE", 11, 40, 20))
        // 662 SPRITECREATE=1
        assertEquals(1L, gvI(ctx, "SPRITECREATE", "GSPR", 11))
        // 663 SPRITECREATED=1 MISS=0
        assertEquals(1L, gvI(ctx, "SPRITECREATED", "GSPR"))
        assertEquals(0L, gvI(ctx, "SPRITECREATED", "NO_SUCH_SPR_XYZ"))
        // 664 GDRAWSPRITE=1
        assertEquals(1L, gvI(ctx, "GDRAWSPRITE", 11, "GSPR", 0, 0))
        // 665 CBGSETSPRITE=1
        assertEquals(1L, gvI(ctx, "CBGSETSPRITE", "GSPR", 0, 0, 1))
        // 666 CBGSETBUTTONSPRITE=1
        assertEquals(1L, gvI(ctx, "CBGSETBUTTONSPRITE", 1, "GSPR", "GSPR", 0, 0, 1, "TIP"))
        // 667 SPRITEWIDTH=40 HEIGHT=20
        assertEquals(40L, gvI(ctx, "SPRITEWIDTH", "GSPR"))
        assertEquals(20L, gvI(ctx, "SPRITEHEIGHT", "GSPR"))
        // 668 SPRITEPOS=0,0
        assertEquals(0L, gvI(ctx, "SPRITEPOSX", "GSPR"))
        assertEquals(0L, gvI(ctx, "SPRITEPOSY", "GSPR"))
        // 669 SPRITEMOVE=1 POS=5,7
        assertEquals(1L, gvI(ctx, "SPRITEMOVE", "GSPR", 5, 7))
        assertEquals(5L, gvI(ctx, "SPRITEPOSX", "GSPR"))
        assertEquals(7L, gvI(ctx, "SPRITEPOSY", "GSPR"))
        // 670 SPRITESETPOS=1 POS=1,2
        assertEquals(1L, gvI(ctx, "SPRITESETPOS", "GSPR", 1, 2))
        assertEquals(1L, gvI(ctx, "SPRITEPOSX", "GSPR"))
        assertEquals(2L, gvI(ctx, "SPRITEPOSY", "GSPR"))
        // 671 SPRITEGETCOLOR=0  —— 11 号缓冲从未清屏，像素是 0x00000000
        assertEquals(0L, gvI(ctx, "SPRITEGETCOLOR", "GSPR", 0, 0))
        // 672 SPRITEDISPOSE=1
        assertEquals(1L, gvI(ctx, "SPRITEDISPOSE", "GSPR"))
        // 673 SPRITEDISPOSEALL=35  —— count(35) - resourceCount(0)
        assertEquals(35L, gvI(ctx, "SPRITEDISPOSEALL", 0))
    }

    // ==================================================================
    // SECTION:SPRITE-ANIME —— 基准 675-678
    // ==================================================================

    @Test
    fun `SPRITE-ANIME 逐行复现基准 675-678`() {
        val ctx = gvCtx()
        // 675 GCREATE3=1
        assertEquals(1L, gvI(ctx, "GCREATE", 12, 10, 10))
        // 676 ANIMECREATE=1
        assertEquals(1L, gvI(ctx, "SPRITEANIMECREATE", "GANM", 10, 10))
        // 677 ANIMEADDFRAME=1
        assertEquals(1L, gvI(ctx, "SPRITEANIMEADDFRAME", "GANM", 12, 0, 0, 10, 10, 0, 0, 100))
        // 678 SETANIMETIMER=1
        assertEquals(1L, gvI(ctx, "SETANIMETIMER", 100))
        assertEquals(100, gvSprites(ctx).animeTimerMs)
    }

    // ==================================================================
    // SECTION:CBG —— 基准 680-687
    // ==================================================================

    @Test
    fun `CBG 逐行复现基准 680-687`() {
        val ctx = gvCtx()
        // 680 GCREATE4=1
        assertEquals(1L, gvI(ctx, "GCREATE", 13, 50, 50))
        // 681 CBGSETG=1
        assertEquals(1L, gvI(ctx, "CBGSETG", 13, 0, 0, 1))
        // 682 CBGREMOVERANGE=1
        assertEquals(1L, gvI(ctx, "CBGREMOVERANGE", 0, 10))
        // 683 CBGSETBMAPG=1
        assertEquals(1L, gvI(ctx, "CBGSETBMAPG", 13))
        // 684 CBGCLEARBUTTON=1
        assertEquals(1L, gvI(ctx, "CBGCLEARBUTTON"))
        // 685 CBGREMOVEBMAP=1
        assertEquals(1L, gvI(ctx, "CBGREMOVEBMAP"))
        // 686 CBGCLEAR=1
        assertEquals(1L, gvI(ctx, "CBGCLEAR"))
        // 687 GDISPOSE13=1
        assertEquals(1L, gvI(ctx, "GDISPOSE", 13))
    }

    // ==================================================================
    // SECTION:CBG-ORDER —— 图层序是 zdepth 降序
    // spec: EmueraConsole.cs:58-65（ClientBackGroundImage.CompareTo = -zdepth 比较）
    //       :645-646 / :679-680（Add + Sort）→ cbgList 恒按 zdepth **从深到浅**排列；
    // 消费方：绘制循环按列表序合并（:1834-1868）、tooltip 取列表序首个命中（:1898-1905）。
    // ==================================================================

    @Test
    fun `CBG 图层按 zdepth 降序排列`() {
        val ctx = gvCtx()
        assertEquals(1L, gvI(ctx, "GCREATE", 20, 8, 4))
        assertEquals(1L, gvI(ctx, "GCREATE", 21, 8, 4))
        assertEquals(1L, gvI(ctx, "SPRITECREATE", "GSPR_ORD", 21))
        // 乱序 depth 追加（depth 0 走 CBGSETG 会 CodeEE，原版 :638 ThrowIfZero）：
        // 5 → 1 → 3 → 按钮(2)，期望最终序 [5, 3, 2(按钮), 1]。
        assertEquals(1L, gvI(ctx, "CBGSETG", 20, 0, 0, 5))
        assertEquals(1L, gvI(ctx, "CBGSETG", 20, 1, 1, 1))
        assertEquals(1L, gvI(ctx, "CBGSETG", 20, 2, 2, 3))
        assertEquals(1L, gvI(ctx, "CBGSETBUTTONSPRITE", 9, "GSPR_ORD", "GSPR_ORD", 0, 0, 2, "TIP"))
        val cbg = ctx.store.aux.slot("cbg") { CbgPool() }
        assertEquals(listOf(5, 3, 2, 1), cbg.layers.map { it.depth }, "必须与原版 Sort 结果一致（降序）")
        assertEquals(true, cbg.layers.first { it.depth == 2 }.isButton)
        // 同 depth 并列：原版 List.Sort 不稳定、相对顺序未定义，本实现按插入序稳定化
        // （Kotlin sortByDescending 稳定），把并列项的确定化口径钉在这里。
        assertEquals(1L, gvI(ctx, "CBGSETG", 20, 7, 7, 3))
        assertEquals(listOf(5, 3, 3, 2, 1), cbg.layers.map { it.depth })
        assertEquals(2, cbg.layers.first { it.depth == 3 }.x, "同 depth 保留插入序：先 2 后 7")
    }

    // ==================================================================
    // 边界与错误路径
    // ==================================================================

    @Test
    fun `未创建的缓冲按原版口径返回中性值`() {
        val ctx = gvCtx()
        assertEquals(0L, gvI(ctx, "GWIDTH", 77))
        assertEquals(0L, gvI(ctx, "GHEIGHT", 77))
        assertEquals(0L, gvI(ctx, "GGETFONTSIZE", 77))
        assertEquals(0L, gvI(ctx, "GGETFONTSTYLE", 77))
        assertEquals(0L, gvI(ctx, "GGETBRUSH", 77))
        assertEquals(0L, gvI(ctx, "GGETPEN", 77))
        assertEquals(0L, gvI(ctx, "GGETPENWIDTH", 77))
        assertEquals("", gvS(ctx, "GGETFONT", 77))
        assertEquals(-1L, gvI(ctx, "GGETCOLOR", 77, 0, 0))
        assertEquals(0L, gvI(ctx, "GDISPOSE", 77))
        assertEquals(0L, gvI(ctx, "GSAVE", 77, 0))
        assertEquals(0L, gvI(ctx, "GCLEAR", 77, 0xFFFFFF))
        assertEquals(0L, gvI(ctx, "SPRITEWIDTH", "NOPE"))
        assertEquals(-1L, gvI(ctx, "SPRITEGETCOLOR", "NOPE", 0, 0))
        assertEquals(0L, gvI(ctx, "SPRITEDISPOSE", "NOPE"))
    }

    @Test
    fun `非法参数抛 CodeEE`() {
        val ctx = gvCtx()
        assertFailsWith<CodeEE> { gvI(ctx, "GCREATE", -1, 10, 10) }
        assertFailsWith<CodeEE> { gvI(ctx, "GCREATE", 1, 0, 10) }
        assertFailsWith<CodeEE> { gvI(ctx, "GCREATE", 1, 10, 8193) }
        assertFailsWith<CodeEE> { gvI(ctx, "SPRITEANIMECREATE", "X", 0, 10) }
        assertFailsWith<CodeEE> { gvI(ctx, "SETANIMETIMER", 32768) }
        // 下面几条要求目标已创建（原版先判 IsCreated 再读颜色/深度）
        assertEquals(1L, gvI(ctx, "GCREATE", 1, 10, 10))
        assertFailsWith<CodeEE> { gvI(ctx, "GSETCOLOR", 1, -1, 0, 0) }
        assertFailsWith<CodeEE> { gvI(ctx, "CBGSETG", 1, 0, 0, 0) }
        // 已创建但没设画笔/画刷/字体：原版 NullReferenceException，这里抛 CodeEE 而不是崩
        assertEquals(1L, gvI(ctx, "GCREATE", 2, 4, 4))
        assertFailsWith<CodeEE> { gvI(ctx, "GGETPEN", 2) }
        assertFailsWith<CodeEE> { gvI(ctx, "GGETBRUSH", 2) }
        assertFailsWith<CodeEE> { gvI(ctx, "GGETFONT", 2) }
    }

    @Test
    fun `动画帧按 DestBaseSize 归一化裁掉越界部分`() {
        // AnimeFrame.Normalize（SpriteAnime.cs:24-36）：入表时把
        // `Rectangle(Offset, SrcRectangle.Size)` 与 `Rectangle(0,0,DestBaseSize)` 求交，
        // 交点回写 Offset、交出的宽高回写 SrcRectangle（**X/Y 不动**）。
        // 这一条与「源矩形在父图像内」是两回事：范围检查过了，这里仍可能被裁。
        val ctx = gvCtx()
        assertEquals(1L, gvI(ctx, "GCREATE", 12, 10, 10))
        assertEquals(1L, gvI(ctx, "SPRITEANIMECREATE", "NANM", 10, 10))
        // 参数位序同 RM_PARSE_PARAMS：x, y, w, h, xpos, ypos, delay
        // 10x10 的帧放在 y=6 ⇒ 底边到 16，越过 10 高的 DestBaseSize
        // （语料同款：留琴-兔女郎 param="0,0,752,752,0,80,42"）
        assertEquals(1L, gvI(ctx, "SPRITEANIMEADDFRAME", "NANM", 12, 0, 0, 10, 10, 0, 6, 100))
        val f = gvSprites(ctx).find("NANM")!!.frames!!.single()
        assertEquals(0, f.offsetX)
        assertEquals(6, f.offsetY, "Offset 取交集左上角（:32-33）")
        assertEquals(10, f.srcW, "横向没越界 ⇒ 原样")
        assertEquals(4, f.srcH, "纵向被裁到 10-6=4（:34-35）")

        // 交集为空 ⇒ 原版 `rectangle.IsEmpty` 把 BaseImage 置 null（:27-31）：
        // 这一帧不画，但 delay 照进 totaltime（:78），时间片还在。
        assertEquals(1L, gvI(ctx, "SPRITEANIMECREATE", "NANM2", 10, 10))
        assertEquals(1L, gvI(ctx, "SPRITEANIMEADDFRAME", "NANM2", 12, 0, 0, 10, 10, 0, 20, 100))
        val empty = gvSprites(ctx).find("NANM2")!!
        assertEquals(1, empty.frames!!.size, "帧仍在表里（参与 totaltime）")
        assertEquals(0, empty.frames!!.single().srcW, "没有像素 ⇒ 绘制侧不画")
        assertEquals(0, empty.frames!!.single().srcH)
        assertEquals(100, empty.totalTimeMs, "delay 照加（:78）")
    }

    @Test
    fun `动画帧只能加在动画 Sprite 上`() {
        val ctx = gvCtx()
        assertEquals(1L, gvI(ctx, "GCREATE", 11, 10, 10))
        assertEquals(1L, gvI(ctx, "SPRITECREATE", "STATIC", 11))
        // 原版 :7555 的 `spriteAnime == null && !spriteAnime.IsCreated` 会 NRE，这里 CodeEE
        assertFailsWith<CodeEE> {
            gvI(ctx, "SPRITEANIMEADDFRAME", "STATIC", 11, 0, 0, 10, 10, 0, 0, 100)
        }
        // 帧矩形越界 → 0（:7565）
        assertEquals(1L, gvI(ctx, "SPRITEANIMECREATE", "ANM", 10, 10))
        assertEquals(0L, gvI(ctx, "SPRITEANIMEADDFRAME", "ANM", 11, 5, 5, 10, 10, 0, 0, 100))
        // delay <= 0 → 0（:7571）
        assertEquals(0L, gvI(ctx, "SPRITEANIMEADDFRAME", "ANM", 11, 0, 0, 10, 10, 0, 0, 0))
    }

    @Test
    fun `宿主未接线 FileOps 时 GSAVE 抛 CodeEE`() {
        val ctx = gvCtx(ops = null)
        assertEquals(1L, gvI(ctx, "GCREATE", 10, 4, 4))
        assertFailsWith<CodeEE> { gvI(ctx, "GSAVE", 10, 0) }
        assertFailsWith<CodeEE> { gvI(ctx, "GLOAD", 17, 0) }
    }

    @Test
    fun `GGETTEXTSIZE 回写 RESULT 1`() {
        // 本用例的宿主内存文件系统里没有 §3 成品 → 走确定性退化（契约 §0.5 的另一条口径）。
        // 退化值手算：ABC 三个半角 → 每字 1 格 * 16/2 = 8 像素 → 24；高 = 字号 16
        // （§3 只有 advance/lsb，没有竖度量 —— 见 measureTextPixels 的「竖度量卡点」）。
        // 真实度量那条线（§3 表 + 整串一次截断 = 31）在 FontSidecarMetricsTest 里断言。
        val ctx = gvCtx()
        assertEquals(24L, gvI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16))
        assertEquals(16L, ctx.store.readInt("RESULT", longArrayOf(1)))
    }

    @Test
    fun `51 个图形函数全部落地且不再是占位`() {
        val names = listOf(
            "GCREATE", "GCREATEFROMFILE", "GCREATED", "GDISPOSE", "GWIDTH", "GHEIGHT", "GCLEAR",
            "GFILLRECTANGLE", "GSETCOLOR", "GGETCOLOR", "GSETBRUSH", "GGETBRUSH", "GSETPEN",
            "GGETPEN", "GGETPENWIDTH", "GDRAWLINE", "GDRAWTEXT", "GSETFONT", "GGETFONT",
            "GGETFONTSIZE", "GGETFONTSTYLE", "GGETTEXTSIZE", "GDRAWG", "GDRAWGWITHMASK",
            "GDRAWGWITHROTATE", "GDASHSTYLE", "GSAVE", "GLOAD", "GDRAWSPRITE", "SPRITECREATE",
            "SPRITECREATED", "SPRITEDISPOSE", "SPRITEDISPOSEALL", "SPRITEWIDTH", "SPRITEHEIGHT",
            "SPRITEPOSX", "SPRITEPOSY", "SPRITEMOVE", "SPRITESETPOS", "SPRITEGETCOLOR",
            "SPRITEANIMECREATE", "SPRITEANIMEADDFRAME", "SETANIMETIMER", "CBGSETG",
            "CBGSETSPRITE", "CBGSETBUTTONSPRITE", "CBGSETBMAPG", "CBGCLEAR", "CBGCLEARBUTTON",
            "CBGREMOVERANGE", "CBGREMOVEBMAP",
        )
        assertEquals(51, names.size)
        for (n in names) {
            assertNotNull(Builtins.registry[n], "函数 $n 未注册")
            assertTrue(!Builtins.registry.isPlaceholder(n), "函数 $n 仍是占位")
        }
    }
}
