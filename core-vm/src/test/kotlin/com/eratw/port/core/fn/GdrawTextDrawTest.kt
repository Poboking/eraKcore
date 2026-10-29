package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GDRAWTEXT` 的**绘制**面（总账 GFX-08）。
 *
 * 原版链路：`FunctionMethodCreator.cs:6503-6528` 先按实参个数选分支调
 * `graphicsImage.GDrawString(str, x, y)`（:6511 / :6516），再由 `GraphicsImage.cs:154-188`
 * 落像素（`AddString` :168-170 → `FillPath` :172-179 → `DrawPath` :180-187），
 * 之后才 `MeasureString` 写 RESULT（:6518-6527）。
 *
 * 移植侧没有字形轮廓（§3 sidecar 只握手 advance/lsb，`:core-vm` 不许依赖 `:render` 的 glyph 包），
 * 落的是**推进盒**：每个码位 `[penX + lsb, penX + lsb + advance) × [行顶, 行顶 + sizePx)`，
 * 先填后描（口径与登记见 `GraphicsMethods.kt` 里 GDRAWTEXT 的 KDoc「替代字形」一节）。
 * 所以本文件的断言钉的是**布局与颜色**（坐标、行距、裁剪、画刷/画笔两路、空白不落墨），
 * **不是**字形形状 —— 字形像素要与 PC 对拍得等宿主字形通道（报告接线项）。
 *
 * 装置口径：未接 §3 表时 `CellWidth` 的等宽网格给出 ASCII 半角步进 = 字号 / 2，
 * 于是字号 10 的 `A` 占 5px 宽、10px 高，坐标全是整数、可逐像素断言。
 */
private class GdtBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("GdtBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private object GdtFileOps : FileOps {
    override fun exists(path: String): Boolean = false
    override fun readText(path: String): String? = null
    override fun writeText(path: String, text: String, append: Boolean): Boolean = true
    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private fun gdtCtx(): BuiltinEvalContext {
    val store = VariableStore()
    val base = GdtBaseCtx()
    base.variables = store
    val host = object : BuiltinHost {
        override val fileOps: FileOps? = GdtFileOps
    }
    return BuiltinEvalContext(base, store, host)
}

private fun gdtCall(ctx: BuiltinEvalContext, name: String, vararg args: Any): Value =
    ctx.callFunction(
        name,
        args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
    )

private fun gdtI(ctx: BuiltinEvalContext, name: String, vararg args: Any): Long =
    (gdtCall(ctx, name, *args) as Value.IntV).v

private fun gdtBuf(ctx: BuiltinEvalContext, id: Int): GraphicsBuffer =
    ctx.store.aux.slot("gfx") { GraphicsPool() }.get(id)

/** `GGETCOLOR` 的读数（无符号 ARGB，越界给 -1，见函数实现）。 */
private fun gdtPixel(ctx: BuiltinEvalContext, id: Int, x: Int, y: Int): Long =
    gdtI(ctx, "GGETCOLOR", id, x, y)

/** 造一个 40x40、字号 [size]、画刷红、画笔蓝的缓冲。 */
private fun gdtSetup(ctx: BuiltinEvalContext, size: Int = 10, id: Int = 10) {
    assertEquals(1L, gdtI(ctx, "GCREATE", id, 40, 40))
    assertEquals(1L, gdtI(ctx, "GSETFONT", id, "黑体", size))
    assertEquals(1L, gdtI(ctx, "GSETBRUSH", id, 0xFFFF0000))
    assertEquals(1L, gdtI(ctx, "GSETPEN", id, 0xFF0000FF, 1))
}

class GdrawTextDrawTest {

    /**
     * 2 参与 4 参都绘制、3 参只测量（:6509-6517）。
     *
     * 2 参 = `GDrawString(str, 0, 0)`（:6511）；4 参 = `ReadPoint` 后按该点画（:6515-6516）；
     * 3 参两个 `if` 都不成立 ⇒ 不画，但 `MeasureString` 与 RESULT 写回照旧（:6518-6527）。
     */
    @Test
    fun `两参与四参都落字 三参只测量不落像素`() {
        val ctx = gdtCtx()
        gdtSetup(ctx)
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 10, "AB"))
        // 首行盒顶 = y、盒高 = 字号（10），框内 1px 是画笔色（:180-187），里面是画刷色（:172-179）
        assertEquals(0xFF0000FFL, gdtPixel(ctx, 10, 0, 0))
        assertEquals(0xFFFF0000L, gdtPixel(ctx, 10, 1, 1))
        assertEquals(0xFF0000FFL, gdtPixel(ctx, 10, 0, 9))
        assertEquals(0L, gdtPixel(ctx, 10, 0, 10), "盒高 = 字号，第 11 行不该有墨")
        // 第二个码位的盒从 x=5 起（ASCII 半角步进 = 字号/2）
        assertEquals(0xFF0000FFL, gdtPixel(ctx, 10, 5, 0))
        assertEquals(0xFFFF0000L, gdtPixel(ctx, 10, 6, 1))

        // 3 参：清空后仍返回 1、RESULT 照写，但一个像素都不落（:6509-6517 的两条分支都不进）
        assertEquals(1L, gdtI(ctx, "GCLEAR", 10, 0))
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 10, "AB", 5))
        assertEquals(0L, gdtPixel(ctx, 10, 0, 0))
        assertEquals(0L, gdtPixel(ctx, 10, 5, 5))
        assertTrue(ctx.store.readInt("RESULT", longArrayOf(1)) > 0L, "3 参仍要测量（:6526-6527）")

        // 4 参：按 (3,7) 平移
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 10, "AB", 3, 7))
        assertEquals(0L, gdtPixel(ctx, 10, 0, 0))
        assertEquals(0xFF0000FFL, gdtPixel(ctx, 10, 3, 7))
        assertEquals(0xFFFF0000L, gdtPixel(ctx, 10, 4, 8))
    }

    /**
     * 填充色取画刷、描边色取画笔；两者都未设时回落 `Config.ForeColor`
     * （`SolidBrush(Config.ForeColor)` :178 / `new Pen(Config.ForeColor)` :186）。
     */
    @Test
    fun `画刷与画笔分掌填充与描边 未设时回落 ForeColor`() {
        val ctx = gdtCtx()
        gdtSetup(ctx)
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 10, "A"))
        assertEquals(0xFFFF0000L, gdtPixel(ctx, 10, 2, 2), "内部是画刷色")
        assertEquals(0xFF0000FFL, gdtPixel(ctx, 10, 0, 2), "左边缘是画笔色")

        // 另一个缓冲不设画刷/画笔：两路都回落 0xFFC0C0C0（基准环境 Config.ForeColor = 192,192,192）
        assertEquals(1L, gdtI(ctx, "GCREATE", 11, 40, 40))
        assertEquals(1L, gdtI(ctx, "GSETFONT", 11, "黑体", 10))
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 11, "A"))
        assertEquals(0xFFC0C0C0L, gdtPixel(ctx, 11, 2, 2))
        assertEquals(0xFFC0C0C0L, gdtPixel(ctx, 11, 0, 0))
    }

    /**
     * 换行：按 U+000A 分行、行距 = 字号（emSize）。
     *
     * 依据是 `AddString`（:170）的实测：MS Gothic、emSize=100、origin=(50,50) 时
     * `A` + U+000A + `B` 的墨迹 y 跨 59..235，两行间隔 100 = emSize（不是 font.Height 的 114）。
     * 移植侧 emSize 取字号（Pixel 单位下 `Height * emHeight / lineSpacing` 回到字号原值），
     * 故第二行盒顶 = 第一行盒顶 + 字号。
     */
    @Test
    fun `换行按字号推进 且与 RESULT 高度一致`() {
        val ctx = gdtCtx()
        gdtSetup(ctx)
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 10, "A\nB", 0, 0))
        assertEquals(0xFFFF0000L, gdtPixel(ctx, 10, 2, 2), "第一行")
        assertEquals(0xFFFF0000L, gdtPixel(ctx, 10, 2, 12), "第二行盒顶 = 字号 10")
        assertEquals(0L, gdtPixel(ctx, 10, 2, 25), "第二行之下不该有墨")
        // RESULT[1] = 宽、RESULT[2] = 高（:6526-6527）；高 = 字号 x 行数（measureHeightPx 的现行口径）
        assertEquals(5L, ctx.store.readInt("RESULT", longArrayOf(1)), "最宽行 = 单个 ASCII 的半角步进")
        assertEquals(20L, ctx.store.readInt("RESULT", longArrayOf(2)))
        assertEquals(0L, ctx.store.readInt("RESULT", longArrayOf(0)), "RESULT[0] 原版不写，保持 0")
    }

    /**
     * 裁剪：负坐标只留下画布内的部分、完全在画布外时零像素、极值坐标不 Int 回绕也不抛错。
     *
     * 依据：GDI+ 的 `FillPath` / `DrawPath`（:174 / :182）只被 `Graphics` 的裁剪区约束，
     * `GraphicsImage.cs:154-188` 通篇没有范围检查（同装置实测：origin=(-20,-20) 留下 1284 像素；
     * origin=(390,50) 在 400x300 画布外时零像素、无异常）。
     */
    @Test
    fun `负坐标与画布外只裁剪不抛错`() {
        val ctx = gdtCtx()
        gdtSetup(ctx)
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 10, "A", -3, -3))
        // 盒 [-3,-3)-[2,7)：画布内可见的填充区含 (0,0)
        assertEquals(0xFFFF0000L, gdtPixel(ctx, 10, 0, 0))
        assertEquals(0L, gdtPixel(ctx, 10, 3, 0), "盒右边界在 x=2")

        assertEquals(1L, gdtI(ctx, "GCLEAR", 10, 0))
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 10, "A", 100, 100))
        assertEquals(0L, gdtPixel(ctx, 10, 0, 0))
        // Int 极值：`x + advance` 在 Int 里会回绕，实现按 Long 裁剪
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 10, "A", Int.MAX_VALUE, Int.MIN_VALUE))
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 10, "A", Int.MIN_VALUE, Int.MAX_VALUE))
        assertEquals(0L, gdtPixel(ctx, 10, 0, 0))
    }

    /**
     * 空白码位不落墨：真实字体的空格字形没有轮廓，`FillPath` 也不出像素。
     *
     * 与之相对，测量**照旧**给宽度（`GDRAWTEXT` 的 RESULT 与 GGETTEXTSIZE 同源，
     * 单码位走步进查询、不裁尾，见 `stepOfCodePoint` 的 KDoc）。
     */
    @Test
    fun `空白码位不落墨但照旧计宽`() {
        val ctx = gdtCtx()
        gdtSetup(ctx)
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 10, " "))
        assertEquals(0L, gdtPixel(ctx, 10, 0, 0))
        assertEquals(5L, ctx.store.readInt("RESULT", longArrayOf(1)))

        // "A B"：三个码位盒 0..4 / 5..9 / 10..14，中间那格必须干净
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 10, "A B", 0, 0))
        assertEquals(0xFFFF0000L, gdtPixel(ctx, 10, 2, 2))
        assertEquals(0L, gdtPixel(ctx, 10, 7, 2), "空格盒内不该有墨")
        assertEquals(0xFFFF0000L, gdtPixel(ctx, 10, 12, 2))
    }

    /** 未创建的缓冲：返回 0、不落像素（:6503-6507 的 `IsCreated` 闸）。 */
    @Test
    fun `未创建的缓冲返回 0 且不落像素`() {
        val ctx = gdtCtx()
        assertEquals(0L, gdtI(ctx, "GDRAWTEXT", 99, "AB"))
        assertFalse(gdtBuf(ctx, 99).isCreated)
    }

    /**
     * 描边宽度跟随 `GSETPEN` 的宽度（`new Pen(color, width)`，FMC.cs:6440 →
     * `GraphicsImage.cs:180-187` 的 `DrawPath(pen, path)`）。
     *
     * 移植侧把宽度全部画在框内（GDI+ 是沿轮廓中线、一半在外），故 3px 宽的边框占
     * x/y 的 0..2 与右/下各 3px；这条几何差异登记在 `GraphicsBuffer.strokeRect` 的 KDoc。
     */
    @Test
    fun `描边宽度跟随 GSETPEN`() {
        val ctx = gdtCtx()
        // 字号 20 ⇒ 码位盒 10x20，3px 边框与内部填充都能分开断言
        gdtSetup(ctx, size = 20)
        assertEquals(1L, gdtI(ctx, "GSETPEN", 10, 0xFF0000FF, 3))
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 10, "A", 0, 0))
        assertEquals(0xFF0000FFL, gdtPixel(ctx, 10, 2, 2), "左上 3px 边框")
        assertEquals(0xFFFF0000L, gdtPixel(ctx, 10, 3, 3), "边框内是填充")
        assertEquals(0xFFFF0000L, gdtPixel(ctx, 10, 6, 16), "右下内角仍是填充")
        assertEquals(0xFF0000FFL, gdtPixel(ctx, 10, 7, 16), "右边框从 x=7 起")
        assertEquals(0xFF0000FFL, gdtPixel(ctx, 10, 5, 17), "下边框从 y=17 起")
    }

    /** 盒高跟随缓冲自带字号（:6519 → `GraphicsImage.cs:89` 的 `Fnt`），不是回退分支的 100。 */
    @Test
    fun `盒高取缓冲自带字号`() {
        val ctx = gdtCtx()
        assertEquals(1L, gdtI(ctx, "GCREATE", 12, 40, 40))
        assertEquals(1L, gdtI(ctx, "GSETFONT", 12, "黑体", 16))
        assertEquals(1L, gdtI(ctx, "GSETBRUSH", 12, 0xFFFF0000))
        assertEquals(1L, gdtI(ctx, "GDRAWTEXT", 12, "A", 0, 0))
        assertEquals(0xFFFF0000L, gdtPixel(ctx, 12, 1, 14), "盒高 16 ⇒ 第 14 行仍是填充")
        assertEquals(0xFFC0C0C0L, gdtPixel(ctx, 12, 1, 15), "最后一行是未设画笔的 ForeColor 描边")
        assertEquals(0L, gdtPixel(ctx, 12, 1, 16), "第 16 行已在盒外")
        assertEquals(8L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(16L, ctx.store.readInt("RESULT", longArrayOf(2)))
    }
}
