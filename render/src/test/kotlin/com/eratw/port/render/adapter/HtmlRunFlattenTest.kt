package com.eratw.port.render.adapter

import com.eratw.port.core.host.Alignment
import com.eratw.port.render.Grid
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [HtmlRunFlatten] 的图形展平判据（M10-GFX 第 3 步 ③：HTML_PRINT 的 `<img>` / `<shape>`）。
 *
 * ## 判据来源
 *
 * 本作屏上的立绘**全部**由 `HTML_PRINT` 交出（语料实测：`<img` 52 处 / 23 个文件，
 * `<shape` 58 处 / 16 个文件；而语句 `PRINT_IMG` 0 处、`SETBGIMAGE` 0 处）。
 * core 的 `LineBufferConsoleSink.printHtml` 明确不解析标签、只存原文，
 * 所以展平发生在这一层。尺寸口径逐条照 C#：
 *
 * | 口径 | C# 出处 | 本文件用例 |
 * |---|---|---|
 * | `height` 缺省 = FontSize 一行高 | `ConsoleImagePart.cs:66` | 无尺寸那条 |
 * | `width` 缺省 = 基图纵横比 `DestBaseSize.Width * h / Height` | `ConsoleImagePart.cs:67-71` | 纵横比推宽那条 |
 * | 带 `px` 用像素、否则按 FontSize 百分比 | `ConsoleImagePart.cs:66,78` | 百分比那条 |
 * | `ypos` 同口径（px / 百分比） | `ConsoleImagePart.cs:81` | ypos 那条 |
 * | `srcb=''` 视作没有 | `FunctionIdentifier.cs:423-426`（PRINT_IMG 侧同式） | srcb 那条 |
 * | `shape type='rect'` 4 参守卫 `x>=0 && w>0 && h>0` | `ConsoleShapePart.cs:56-69` | 负宽不成立那条 |
 * | `shape type='space'` 只有 1 参形态有效 | `ConsoleShapePart.cs:50-55` | space 那条 |
 * | `&nbsp;` 落 U+0020 | `HtmlManager.cs:783-785` | 实体那条 |
 *
 * 占宽用等量半角空格（[InlineGraphic.widthCells] 格）：折行 / 居中 / 脏矩形都按 `Grid.cells(run.text)`
 * 量宽，图形段必须交出同样的占宽，否则一行的列位置会分叉
 * （原版对应「节点自带 Width、`SetWidth` 不再测量」，spec: `ConsoleImagePart.cs:104-115`）。
 *
 * 夹具字号 18px ⇒ 半角步进 `18 / 2 * 1.04 = 9.36px`（[CellMetrics.cellPixelWidth]），
 * 所有期望格数都按 `ceil(px / 9.36)` 手算并写成字面值。
 */
class HtmlRunFlattenTest {

    private val metrics = CellMetrics(18)

    /** 基图尺寸表（`ASprite.DestBaseSize`）：资源名 → 打包的 `w shl 16 or h`。 */
    private class FakeSizes(map: Map<String, Pair<Int, Int>>) : HtmlRunFlatten.ImageSizeSource {
        val packed = map.mapValues { (it.value.first shl 16) or it.value.second }
        override fun destSize(resource: String): Int? = packed[resource]
    }

    private fun flatten(html: String, sizes: HtmlRunFlatten.ImageSizeSource? = null): List<TextRun> =
        HtmlRunFlatten(metrics, sizes).flatten(htmlRun(html))

    private fun htmlRun(html: String) =
        TextRun(html, fore = 0xC0C0C0, back = 0, styleMask = 0, fontName = "msgothic", kind = RunKind.HTML)

    // ==================================================================
    // `<img>`
    // ==================================================================

    /**
     * 标题图那条原文（`ERB/TITLE.ERB:59` 的 `<p align='center'><img src='TW_title001'>`）：
     * 两个尺寸都没给 ⇒ 高 = FontSize（`ConsoleImagePart.cs:66`），宽在**没有基图表**时按见方占位
     * （`HtmlRunFlatten.emitImage` 的第三支），`<p>` 标记本身丢掉（宽容口径）。
     */
    @Test
    fun `img 无尺寸时按一行高占位且标签不残留文本`() {
        val runs = flatten("<p align='center'><img src='TW_title001'>")
        assertEquals(1, runs.size)
        val g = runs[0].graphic
        assertTrue(g is InlineGraphic.Image, "该出图形段")
        g as InlineGraphic.Image
        assertEquals("TW_title001", g.resource)
        assertEquals(18, g.heightPx)
        assertEquals(18, g.widthPx)
        assertEquals(2, g.widthCells, "ceil(18 / 9.36) = 2")
        assertEquals(0, g.yOffsetPx)
        assertNullText(runs[0])
        assertEquals(RunKind.IMAGE, runs[0].kind)
    }

    /** 缺 `width` 但有基图表 ⇒ 按纵横比推宽（`ConsoleImagePart.cs:67-71`）。 */
    @Test
    fun `img 缺宽时按基图纵横比推宽`() {
        val sizes = FakeSizes(mapOf("bodyGraph_1" to (100 to 50)))
        val runs = flatten("<img src='bodyGraph_1' height='30px'>", sizes)
        val g = runs.single().graphic as InlineGraphic.Image
        assertEquals(30, g.heightPx)
        assertEquals(60, g.widthPx, "100 * 30 / 50 = 60")
        assertEquals(7, g.widthCells, "ceil(60 / 9.36) = 7")
    }

    /**
     * 超宽图**不**按窗口宽夹（ADR-005 回归）。
     *
     * 原版 `destRect` 的宽只由 `height`/`width` 与基图纵横比决定（`ConsoleImagePart.cs:66-82`），
     * 与窗口宽无关；图比窗口宽时的处置是「整块排到下一行 + 右侧被绘制区裁掉」
     * （`CanDivide => false`，`:35`；换行判据 `PrintStringBuffer.cs:217-249`）。
     * 移植侧曾在这里按窗口宽夹过一次（只压宽不压高），会让画面横纵比与原图不一致、
     * 并把脚本给的 `width` 静默改写 —— 2026-10-26 去掉。
     */
    @Test
    fun `img 超宽时不被窗口宽夹`() {
        // 基图 1000×16、缺 height ⇒ 高 = FontSize 18、宽 = 1000 * 18 / 16 = 1125，
        // 远宽于任何手机窗口（真机 2400px 档也在 1125 之上，这里要的是「没有任何夹取」）。
        val sizes = FakeSizes(mapOf("band" to (1000 to 16)))
        val runs = flatten("<img src='band'>", sizes)
        val g = runs.single().graphic as InlineGraphic.Image
        assertEquals(1125, g.widthPx, "宽度只由纵横比决定，不读窗口宽")
        assertEquals(121, g.widthCells, "ceil(1125 / 9.36) = 121")
        assertEquals(" ".repeat(121), runs.single().text, "占宽跟着真实宽度走")
    }

    /** 显式宽高（`ERB/BODY_INFO/GET_BODY_SETS.ERB:117` 的形态）。 */
    @Test
    fun `img 显式宽高逐值交出并按半角步进占宽`() {        val runs = flatten("<img src='bodyGraph_X' width='60px' height='30px'>")
        val g = runs.single().graphic as InlineGraphic.Image
        assertEquals(60, g.widthPx)
        assertEquals(30, g.heightPx)
        assertEquals(7, g.widthCells)
        assertEquals(7, runs.single().text.length, "占宽必须等于 widthCells")
        assertEquals(" ".repeat(7), runs.single().text)
    }

    /** 不带 `px` 的是 FontSize 百分比（`ConsoleImagePart.cs:66,78`）。 */
    @Test
    fun `img 百分比尺寸按字号折算`() {
        val runs = flatten("<img src='a' width='100' height='200'>")
        val g = runs.single().graphic as InlineGraphic.Image
        assertEquals(36, g.heightPx, "18 * 200 / 100")
        assertEquals(18, g.widthPx, "18 * 100 / 100")
        // 占宽只按**宽**折算，与高无关（原版推进列游标用的是 destRect.Width，
        // spec: `ConsoleImagePart.cs:104-115`；`Grid.cells` 那侧读的就是这同一个 widthCells）：
        // ceil(18 / 9.36) = 2，36 是本段的高、不参与占宽。
        assertEquals(2, g.widthCells, "ceil(18 / 9.36) = 2（占宽取宽，高 36 不算）")
    }

    /** `ypos`（`ConsoleImagePart.cs:81`；语料 `ERB/BODY_INFO/XRAY_IMAGE.ERB:212` 就带这个参数）。 */
    @Test
    fun `img 的 ypos 两种单位都认`() {
        val px = flatten("<img src='a' height='30px' ypos='20px'>").single().graphic as InlineGraphic.Image
        assertEquals(20, px.yOffsetPx)
        val pct = flatten("<img src='a' height='30px' ypos='50'>").single().graphic as InlineGraphic.Image
        assertEquals(9, pct.yOffsetPx, "50 * 18 / 100 = 9（整数除，与原版同式）")
    }

    /** `srcb=''` 等于没给（`FunctionIdentifier.cs:423-426` 的空串置 null）。 */
    @Test
    fun `img 的 srcb 空串折成 null 非空才记`() {
        val empty = flatten("<img src='a' srcb=''>").single().graphic as InlineGraphic.Image
        assertEquals(null, empty.buttonResource)
        val given = flatten("<img src='a' srcb='b.webp'>").single().graphic as InlineGraphic.Image
        assertEquals("b.webp", given.buttonResource)
    }

    // ==================================================================
    // 脚本 px 尺（立绘下方空档，2026-10-27）
    //
    // 判据（三条都要成立，缺一条就是本次的虫）：
    // ① 语料 `emuera.config:22-23` 把 `FONT SIZE` 与 `LINE HEIGHT` 都写成 18 ⇒ PC 上
    //    `height='180px'`（`ERB/魔改内容/QOL_IMAGE.ERB:826`）与 `height='1000%'`
    //    （`ERB/グラフィック表示ライブラリ/グラフィック表示.ERB:213` 算的 `180*100/18`）
    //    是**同一个 180 像素**；
    // ② 剧本两条写法都按 `ceil(180 / 一行の高さ)` 预留空行（`QOL_IMAGE.ERB:357` 的
    //    `@HTML_PRINTL_PX`、`グラフィック表示.ERB:297` 的 `@ピクセル自動改行`）；
    // ③ 所以 px 支必须与百分比支同尺 —— 否则 ①的那张图只吃掉 ②预留位子的一小半，
    //    立绘下面挂出 6~7 行空白（主人报的「间距很大」）。
    //
    // 夹具取真机 110 列档：字号 41 / 行高 43（`view/PageFitter.fit(2400, 1000, 20)` 一档）。
    // ==================================================================

    private fun deviceFlat(scriptFontPx: Int) =
        HtmlRunFlatten(CellMetrics(41, 43), scriptFontPixelSize = scriptFontPx)

    private fun image(runs: List<TextRun>): InlineGraphic.Image = runs.single().graphic as InlineGraphic.Image

    /** ①+③：同一张 180 的图，`px` 与 `%` 两种写法在真机档下必须同高。 */
    @Test
    fun `真机档下 px 与百分比折成同一个高`() {
        val flat = deviceFlat(18)
        val byPx = image(flat.flatten(htmlRun("<img src='a' height='180px'>")))
        val byPct = image(flat.flatten(htmlRun("<img src='a' height='1000'>")))
        assertEquals(410, byPx.heightPx, "180 * 41 / 18（先乘后除的整数除，与 C# 同形）")
        assertEquals(byPct.heightPx, byPx.heightPx, "PC 上两者同为 180px ⇒ 端口也必须同值（语料两条写法等值）")
    }

    /** 折算后列足迹与 PC 逐格相同：`ceil(180/9.36) = ceil(410/21.32) = 20`。 */
    @Test
    fun `px 折算后占宽格数与 PC 同格`() {
        val pcRun = flatten("<img src='a' width='180px' height='180px'>").single()
        val deviceRun = deviceFlat(18).flatten(htmlRun("<img src='a' width='180px' height='180px'>")).single()
        val pc = pcRun.graphic as InlineGraphic.Image
        val device = deviceRun.graphic as InlineGraphic.Image
        assertEquals(20, pc.widthCells, "PC 18px 档：ceil(180 / 9.36) = 20")
        assertEquals(pc.widthCells, device.widthCells, "真机 41px 档：ceil(410 / 21.32) = 20 —— 同一页占同样的列")
        assertEquals(pc.widthCells, deviceRun.text.length, "占宽空格数必须等于 widthCells")
    }

    /** `ypos` 是图层竖向偏移（`ConsoleImagePart.cs:81`），不跟着折就会与主图错开一整档。 */
    @Test
    fun `px 折算时 ypos 一起折算`() {
        val g = image(deviceFlat(18).flatten(htmlRun("<img src='a' height='180px' ypos='-30px'>")))
        assertEquals(-68, g.yOffsetPx, "-30 * 41 / 18 = -68（向零截断，负号原样保留）")
    }

    /** `<shape>` 的两支（1 参宽 / 4 参 x,y,w,h）与 img 同尺，否则色块与立绘各行其是。 */
    @Test
    fun `shape 的 px 形态与 img 同尺`() {
        val flat = deviceFlat(18)
        val rect1 = flat.flatten(htmlRun("<shape type='rect' param='450px'>")).single().graphic
            as InlineGraphic.Rect
        assertEquals(1025, rect1.widthPx, "450 * 41 / 18")
        assertEquals(41, rect1.heightPx, "单参形态的高恒为一行高（= 真机字号）")
        val rect4 = flat.flatten(htmlRun("<shape type='rect' param='0px, 10px, 180px, 20px'>")).single().graphic
            as InlineGraphic.Rect
        assertEquals(410, rect4.widthPx, "4 参形态的宽同样折算")
        assertEquals(22, rect4.yOffsetPx, "10 * 41 / 18 = 22（竖向偏移同一把尺）")
        assertEquals(45, rect4.heightPx, "20 * 41 / 18 = 45")
    }

    /** 缺省（基准未报 = 0）必须**恒等**：headless、黄金装置与全部既有用例逐值不变的判据。 */
    @Test
    fun `基准未报时 px 逐值不变`() {
        val g = image(HtmlRunFlatten(CellMetrics(41, 43)).flatten(htmlRun("<img src='a' height='180px'>")))
        assertEquals(180, g.heightPx, "基准 0 = 不折算")
        val same = image(HtmlRunFlatten(CellMetrics(41, 43), scriptFontPixelSize = 41)
            .flatten(htmlRun("<img src='a' height='180px'>")))
        assertEquals(180, same.heightPx, "基准 == 真机字号时系数 1，同样恒等")
    }

    /** 文本与图形段的顺序 = 原文顺序（图形段与文本共用同一个列游标，spec: `EmueraConsole.cs:3192-3200`）。 */
    @Test
    fun `img 夹在文本中间时切成三段且顺序不变`() {
        val runs = flatten("前<img src='a' width='20px' height='20px'>后")
        assertEquals(listOf("前", "   ", "后"), runs.map { it.text })
        assertEquals(listOf(RunKind.TEXT, RunKind.IMAGE, RunKind.TEXT), runs.map { it.kind })
        assertEquals(3, runs[1].widthCellsOf())
    }

    /**
     * `width='0px'` / `height='0px'` 在原版**不是**「零尺寸」而是「未给尺寸」
     * （spec: `ConsoleImagePart.cs:66-71` 的 `raw_width == null || raw_width.num == 0` 分支），
     * 所以这里得到与无尺寸那条同样的 18px 见方段。
     */
    @Test
    fun `img 的 0 尺寸按未给处理而不是一格不出`() {
        val runs = flatten("甲<img src='a' width='0px' height='0px'>乙")
        assertEquals(3, runs.size)
        val g = runs[1].graphic as InlineGraphic.Image
        assertEquals(18, g.heightPx)
        assertEquals(18, g.widthPx)
        assertEquals(2, g.widthCells)
    }

    /**
     * 真正的负宽段整段不出（[InlineGraphic.cellsFor] 对非正宽返回 0）。
     *
     * 原版对负 `destRect.Width` 是「把 X 推到偏移处、宽取绝对值」（spec: `ConsoleImagePart.cs:84-88`），
     * 本层不做那次翻转。判据：语料 52 处 `<img` 的 width/height **全部**是正数或 `0`
     * （`ERB/BODY_INFO` 目录下的 ERB、`ERB/SHOP関連/TEST.ERB` 抽样实测），负宽没有可取证的对象；
     * 真机若出现负宽立绘，需要按上面那 5 行补翻转。
     */
    @Test
    fun `img 负宽整段不出`() {
        val runs = flatten("甲<img src='a' width='-5px' height='10px'>乙")
        assertEquals(listOf("甲", "乙"), runs.map { it.text })
        assertTrue(runs.none { it.graphic != null })
    }

    // ==================================================================
    // `<shape>`
    // ==================================================================

    /** 4 参 `rect`（语料 `ERB/DLC/神签/神签.ERB:83` 原样一条）。 */
    @Test
    fun `shape rect 四参交出位移与尺寸和颜色`() {
        val runs = flatten("<shape type='rect' param='0,1px,440px,2px' color='#FF8800'>")
        val g = runs.single().graphic as InlineGraphic.Rect
        assertEquals(0, g.xOffsetPx)
        assertEquals(1, g.yOffsetPx)
        assertEquals(440, g.widthPx)
        assertEquals(2, g.heightPx)
        assertEquals(0xFF8800, g.colorArgb and 0xFFFFFF)
        assertEquals(48, g.widthCells, "ceil(440 / 9.36) = 48")
    }

    /** 1 参 `rect` ⇒ 高 = FontSize 的横条（`ConsoleShapePart.cs:57`，且要求 num > 0）。 */
    @Test
    fun `shape rect 单参是一条行高的横条`() {
        val g = flatten("<shape type='rect' param='100'>").single().graphic as InlineGraphic.Rect
        assertEquals(18, g.heightPx)
        assertEquals(18, g.widthPx, "18 * 100 / 100")
        assertEquals(2, g.widthCells)
        // 非正数不成立（ConsoleShapePart.cs:57 的 num > 0）
        assertTrue(flatten("<shape type='rect' param='0'>").none { it.graphic != null })
    }

    /**
     * 守卫 `x >= 0 && w > 0 && h > 0`（`ConsoleShapePart.cs:64`）：负宽 / 负位移落不成图形段。
     *
     * 落不成**不丢弃**：原版落到 `ConsoleErrorShapePart`（ConsoleShapePart.cs:71-74），
     * 其 `Text`/`AltText` 是重建的标签文案、`DrawTo` 按普通文本画（ConsoleErrorShapePart.cs:12-26）、
     * `SetWidth` 按文本量宽（:28-37）——玩家屏上会看到重建出来的 `<shape ...>` 原文。
     * 本层把重建文案当文本接回行内（样式取当前快照，宽容近似）。
     */
    @Test
    fun `shape rect 守卫不成立时按原版出重建标签文本`() {
        val negW = flatten("A<shape type='rect' param='0,10px,-5px,20px'>B")
        assertTrue(negW.none { it.graphic != null })
        assertEquals(
            listOf("A", "<shape type='rect' param='0, 10px, -5px, 20px'>", "B"),
            negW.map { it.text },
        )

        val negX = flatten("A<shape type='rect' param='-10px,10px,5px,20px'>B")
        assertTrue(negX.none { it.graphic != null })
        assertEquals(
            listOf("A", "<shape type='rect' param='-10px, 10px, 5px, 20px'>", "B"),
            negX.map { it.text },
        )
    }

    /** `space` 只推进列游标、什么都不画（`ConsoleShapePart.cs:50-55`；语料 `ERB/COMMON_HTML.ERB:7`）。 */
    @Test
    fun `shape space 只占宽`() {
        val g = flatten("<shape type='space' param='50'>").single().graphic
        assertTrue(g is InlineGraphic.Space)
        assertEquals(1, g!!.widthCells, "18 * 50 / 100 = 9px → ceil(9 / 9.36) = 1")
        // 多参形态无效（原版 space 只认 1 参）
        assertTrue(flatten("<shape type='space' param='1px,2px'>").none { it.graphic != null })
    }

    /**
     * 认不出的 `type` 与残缺 param 列表不产段、也不抛（宽容口径），
     * 但按原版接回重建标签文本（`ConsoleErrorShapePart`，ConsoleShapePart.cs:71-74 + :12-26）。
     */
    @Test
    fun `shape 未知类型按原版出重建标签文本`() {
        val runs = flatten("A<shape type='ellipse' param='10'>B")
        assertEquals(listOf("A", "<shape type='ellipse' param='10'>", "B"), runs.map { it.text })
    }

    /**
     * 不可渲染 shape 的重建文案带上 color/bcolor（原版 AltText 由 CreateShape 拼整条标签：
     * color 设过就写、bcolor 只在 != 缺省 0xFFFF00 时写，ConsoleShapePart.cs:33-44/:75；
     * 回落文案按普通文本画，ConsoleErrorShapePart.cs:12-26）。
     */
    @Test
    fun `shape 不可渲染时重建文案带 color bcolor`() {
        val runs = flatten("A<shape type='rect' param='0' color='#00FF00' bcolor='#101010'>B")
        assertEquals(
            listOf("A", "<shape type='rect' param='0' color='#00FF00' bcolor='#101010'>", "B"),
            runs.map { it.text },
        )
    }

    // ==================================================================
    // 标签与实体（非图形部分：证明展平没顺手改文本语义）
    // ==================================================================

    /** 语料里 `<img>` 常在 `<button>` 里（`ERB/BODY_INFO/GET_BODY_SETS.ERB:115`）：
     * 按钮标记按宽容口径丢掉标记本身、保留其间内容，图形段照常出。 */
    @Test
    fun `button 包裹 img 时不抛且图形段产出`() {
        val runs = flatten("<button value='90001'><img src='a' width='60px' height='30px'></button>")
        assertEquals(1, runs.size)
        assertEquals(RunKind.IMAGE, runs[0].kind)
        assertEquals(7, runs[0].widthCellsOf())
    }

    /**
     * 语料剧本选择页：按钮的 `title` 属性里带 `<br>`（`title='初始剧本<br>内容量适中…'`）。
     * 标签结束符必须跳过引号内的 `>`，否则标题尾巴会漏成正文，按钮标签也跟着串位。
     */
    @Test
    fun `属性引号内的尖括号不截断标签`() {
        val runs = flatten(
            "<button value='2010' title='初始剧本<br>内容量适中（有效输出行：7290）'>" +
                "<font color='lime'>　　　└ 初始剧本</font></button>",
        )
        val insideButton = runs.filter { it.buttonValue != null }
        assertEquals(2010L, insideButton.single().buttonValue)
        assertEquals("　　　└ 初始剧本", insideButton.joinToString("") { it.text })
        assertEquals(
            0,
            runs.count { it.text.contains("内容量") || it.text.contains("title=") || it.text.contains("'>") },
            "属性原文不得漏进可见文本：${runs.map { it.text }}",
        )
    }

    @Test
    fun `按钮 title 挂到按钮段上`() {
        val runs = flatten(
            "<button value='2010' title='初始剧本<br>内容量适中'>└ 初始剧本</button>普通文本",
        )
        val button = runs.single { it.buttonValue != null }
        assertEquals("初始剧本<br>内容量适中", button.tooltip, "title 原文要随按钮段带下去")
        val plain = runs.single { it.buttonValue == null }
        assertEquals("普通文本", plain.text)
        assertEquals(null, plain.tooltip, "按钮外的文本不该继承提示")
    }

    /**
     * `<clearbutton>` 区间内的 `<button>` 降级成**不可点**文本（原版 `IsButton = false`，
     * `HtmlManager.cs:1549-1551`）：正文照出、但不带 `buttonValue`，`ButtonLayer.attach`
     * 也就建不出命中区。
     *
     * eraTW 的 CLEARLINE 重绘靠这一手作废上一屏按钮：地图页的 `<button value='34'>34`
     * 在移动后重印成 `<clearbutton>…</clearbutton>` 包起来。不认这个标签时，滚回去点
     * 旧格子仍会把值提交给新页面（真机 2026-10-27 的「上一场景按钮还在」）。
     */
    @Test
    fun `clearbutton 内的按钮不带可点身份`() {
        val runs = flatten("<clearbutton><button value='34'>34</button></clearbutton>")
        assertEquals("34", runs.joinToString("") { it.text }.trim(), "正文照出")
        assertTrue(
            runs.none { it.buttonValue != null },
            "clearbutton 区间内的按钮必须不可点；实际=${runs.map { it.buttonValue }}",
        )
    }

    /** 区间外的按钮照旧可点：作废只作用于 `<clearbutton>` 包住的那一段。 */
    @Test
    fun `clearbutton 之外的按钮仍带值`() {
        val runs = flatten(
            "<clearbutton><button value='1'>A</button></clearbutton><button value='2'>B</button>",
        )
        assertEquals(
            listOf<Any?>(null, 2L),
            runs.filter { it.text.isNotBlank() }.map { it.buttonValue },
            "A 不可点、B 可点；实际=${runs.map { it.text to it.buttonValue }}",
        )
    }

    /**
     * 没有 `value` 的 `<button title='…'>`：原版 `title` 的解析与 `IsButton` 无关
     * （`HtmlManager.cs:1514-1520` 取值，`:1547` 才按 `value` 有无写 `IsButton`），
     * 所以这种块在 PC 上照样弹提示、但不可点；移植侧同口径。
     */
    @Test
    fun `无 value 的 button 也带提示且不可点`() {
        val runs = flatten("<button title='提示原文'>不可点</button>")
        val run = runs.single { it.text.contains("不可点") }
        assertEquals("提示原文", run.tooltip)
        assertEquals(null, run.buttonValue, "没有 value 就不产可点身份")
    }

    /**
     * `<nonbutton title='…'>`：提示照样挂到文本段，但**不产可点身份**
     * （原版把非按钮的 `ConsoleButtonString.Title` 也赋值，`HtmlManager.cs:841-847`）。
     * 本作这一页的过滤提示就是这种写法（`qol_update.ERB:145`）。
     */
    @Test
    fun `nonbutton 的 title 挂到文本段且不可点`() {
        val runs = flatten(
            "<nonbutton title='当前显示角色均为有剧本角色'><font color='lime'>过滤提示</font></nonbutton>",
        )
        val run = runs.single { it.text.contains("过滤提示") }
        assertEquals("当前显示角色均为有剧本角色", run.tooltip)
        assertEquals(null, run.buttonValue, "nonbutton 不得变成按钮")
    }

    @Test
    fun `实体还原与不认识的实体原样保留`() {
        val runs = flatten("a&nbsp;b&lt;c&xyz;d")
        assertEquals("a b<c&xyz;d", runs.single().text)
    }

    @Test
    fun `font 与 b 标签改样式并在闭合时还原`() {
        val runs = flatten("<b>粗</b>平<font color='#FF0000'>红</font>平")
        assertEquals(listOf("粗", "平", "红", "平"), runs.map { it.text })
        assertEquals(Grid.STYLE_BOLD, runs[0].styleMask)
        assertEquals(0, runs[1].styleMask)
        assertEquals(0x00FF0000, runs[2].fore)
        assertEquals(0xC0C0C0, runs[3].fore, "闭合标签要还原外层色")
    }

    /**
     * 剧本选择页（`qol_update.ERB:145/218/226`）的三种写法：
     * `color='#0x90EE90'`（过滤提示浅绿）、`color='lime'` / `'orange'`（树行选中/未选中）。
     * 原版 `stringToColorInt32`：`#` 开头走 `Convert.ToInt32(text, 16)`，而 .NET 的
     * `ParseNumbers.StringToInt` 接受 `0x` 前缀；不带 `#` 走 `Color.FromName`
     * （spec: `HtmlManager.cs:1687-1721`，`:1705` 是命名色支）。
     */
    @Test
    fun `命名色与 0x 前缀十六进制都生效`() {
        val runs = flatten(
            "<font color='#0x90EE90'>浅绿</font>" +
                "<font color='lime'>选中</font>" +
                "<font color='ORANGE'>未选</font>" +
                "<font color='#0X404040'>灰</font>",
        )
        assertEquals(listOf("浅绿", "选中", "未选", "灰"), runs.map { it.text })
        assertEquals(0x90EE90, runs[0].fore, "#0x 前缀是原版 Convert.ToInt32(…,16) 的合法写法")
        assertEquals(0x00FF00, runs[1].fore)
        assertEquals(0xFFA500, runs[2].fore, "命名色大小写不敏感（Color.FromName 口径）")
        assertEquals(0x404040, runs[3].fore)
    }

    /** 认不出的颜色名与 `transparent` 不抛，保持外层色（原版抛 CodeEE，宽容层只丢这一处颜色）。 */
    @Test
    fun `认不出的颜色名保持外层色`() {
        val runs = flatten("<font color='notacolor'>x</font><font color='transparent'>y</font>")
        assertEquals(0xC0C0C0, runs[0].fore)
        assertEquals(0xC0C0C0, runs[1].fore)
    }

    /**
     * `</font>` 是**整帧出栈**（`HtmlManager.cs:998-1004` 的 `RemoveAt(Count - 1)`，
     * 帧字段见 `:1655-1670`）：`face` 与 `bcolor` 不许泄漏到本串余下的文本。
     *
     * `bcolor` 落的是焦点态字色（`TextRun.focusFore`，原文 `StringStyle.ButtonColor`：
     * `HtmlManager.cs:131-149` 的 `GetSS` 把 `BColor` 交给 `buttonColor`），不是背景色 ——
     * 背景 `back` 是控制台级量（SETBGCOLOR 的行快照），`<font>` 改不到它。
     */
    @Test
    fun `font 闭合时 face 与 bcolor 一起还原`() {
        val runs = flatten("<font face='A' color='#112233' bcolor='#445566'>x</font>y")
        assertEquals(listOf("x", "y"), runs.map { it.text })
        assertEquals("A", runs[0].fontName)
        assertEquals(0x112233, runs[0].fore)
        assertEquals(0x445566, runs[0].focusFore)
        assertEquals("msgothic", runs[1].fontName, "face 不许泄漏到闭合之后的文本")
        assertEquals(0xC0C0C0, runs[1].fore)
        assertEquals(TextRun.DEFAULT_FOCUS_COLOR, runs[1].focusFore, "bcolor 不许泄漏到闭合之后的文本")
        assertEquals(0, runs[1].back, "bcolor 是焦点字色，不写进背景色")
    }

    /** 嵌套 `<font>` 逐层还原：内层闭合回外层值，外层闭合回串首值。 */
    @Test
    fun `嵌套 font 逐层还原`() {
        val runs = flatten(
            "<font color='#FF0000' bcolor='#00FF00'><font face='A'>x</font>y</font>z",
        )
        assertEquals(listOf("x", "y", "z"), runs.map { it.text })
        assertEquals(listOf("A", "msgothic", "msgothic"), runs.map { it.fontName })
        assertEquals(listOf(0xFF0000, 0xFF0000, 0xC0C0C0), runs.map { it.fore })
        assertEquals(
            listOf(0x00FF00, 0x00FF00, TextRun.DEFAULT_FOCUS_COLOR),
            runs.map { it.focusFore },
        )
    }

    /**
     * 整条都是标签 ⇒ 仍交一条**同列宽的空段**，不能让这一行「没有任何段」而躲过脏矩形（残影）。
     * 这是 [HtmlRunFlatten.flatten] 末尾那条 `out.isEmpty()` 分支的唯一判据。
     */
    @Test
    fun `全标签的行退化成一个空段而不是空表`() {
        val runs = flatten("<br>")
        assertEquals(1, runs.size)
        assertEquals("", runs[0].text)
        assertEquals(RunKind.HTML, runs[0].kind, "非图形行的 kind 不许被降级")
    }

    /** 非 HTML 段一个都不动（**同一个实例**）：语料闸/E2E 的文本流不经过这条。 */
    @Test
    fun `非 HTML 段原样返回同一实例`() {
        val run = TextRun("普通文本", 0xFFFFFF, 0, 0, "msgothic", RunKind.TEXT)
        val flat = HtmlRunFlatten(metrics)
        assertSame(run, flat.flatten(run).single())
        val batch = listOf(run, run)
        assertSame(batch, flat.flattenAll(batch))
    }

    /** `<` 不是标签（`2 < 3`）时按原文留着，不吞字符。 */
    @Test
    fun `落单的尖括号按原文保留`() {
        assertEquals("2 < 3", flatten("2 < 3").single().text)
        assertEquals("a<b", flatten("a<b").single().text)
    }

    // ==================================================================
    // font 栈（HtmlManager.cs:1651-1669 压栈/继承、:998-1004 弹栈、:128-155 GetSS 读栈顶）
    // ==================================================================

    /** `<font face>` 的 fontName 在闭合时还原（语料 `ERB/method_from_eratohoЯeverse/HTML系_from_eratohoЯeverse.ERB:376` 的 `<font face='%GETFONT()%'>` 形态）。 */
    @Test
    fun `font 的 face 在闭合时还原`() {
        val runs = flatten("<font face='msmincho'>衬</font>平")
        assertEquals(listOf("衬", "平"), runs.map { it.text })
        assertEquals("msmincho", runs[0].fontName)
        assertEquals("msgothic", runs[1].fontName, "`</font>` 弹栈还原 face（HtmlManager.cs:998-1004）")
    }

    /**
     * 未指定的属性**继承外层**（HtmlManager.cs:1651-1669：新 FontTag 从上一个条目
     * 继承缺项——栈上外层帧就是那套缺项值）；内层闭合回到外层帧、外层闭合回到行初值。
     */
    @Test
    fun `嵌套 font 未给属性时沿用外层值`() {
        val runs = flatten("<font color='#FF0000'>外<font face='msmincho'>内</font>仍红</font>还原")
        assertEquals(listOf("外", "内", "仍红", "还原"), runs.map { it.text })
        assertEquals(0x00FF0000, runs[1].fore, "内层没给 color ⇒ 继承外层红")
        assertEquals("msmincho", runs[1].fontName)
        assertEquals(0x00FF0000, runs[2].fore, "内层弹栈回到外层帧：色仍是红")
        assertEquals("msgothic", runs[2].fontName, "内层弹栈还原 face")
        assertEquals(0xC0C0C0, runs[3].fore, "外层弹栈还原行初值")
    }

    /**
     * **styleMask 不归 font 管**：原版 FontStyle 是独立字段，font 标签从不动它
     * （HtmlManager.cs:964-980 的 XOR 只归 b/i/s/u，font 走 :1601-1669）。
     * `<font>` 里开的 `<b>` 要活到它自己的 `</b>` 才闭。
     */
    @Test
    fun `闭合 font 不清掉里面开的粗体`() {
        val runs = flatten("<font color='#FF0000'>红<b>红粗</font>粗</b>平")
        assertEquals(listOf("红", "红粗", "粗", "平"), runs.map { it.text })
        assertEquals(Grid.STYLE_BOLD, runs[1].styleMask)
        assertEquals(Grid.STYLE_BOLD, runs[2].styleMask, "`</font>` 不许把 `<b>` 一起清掉")
        assertEquals(0xC0C0C0, runs[2].fore, "色倒是还原了（栈管色，不管字重）")
        assertEquals(0, runs[3].styleMask)
    }

    // ==================================================================
    // `<p align>` / `<nobr>`（HtmlManager.cs:1096-1141 → :702-710；
    // ConsoleDisplayLine.cs:50-56 的一次守卫）
    // ==================================================================

    private fun line(vararg runs: TextRun, align: Alignment = Alignment.LEFT) =
        RenderLine(runs = runs.toList(), isLogicalLine = true, isLineEnd = true, align = align)

    /** 直印 HTML 行的 `<p align='center'>` 落到行级 align（语料 `ERB/TITLE.ERB:59`）。 */
    @Test
    fun `直印 html 的 p align 落到行级 align`() {
        val flat = HtmlRunFlatten(metrics)
        val out = flat.flattenLine(line(htmlRun("<p align='center'>标题")))
        assertEquals(Alignment.CENTER, out.align)
        assertEquals(listOf("标题"), out.runs.map { it.text })
    }

    /** `<nobr>` 置 noBreak（HtmlManager.cs:1100-1104 的 FlagNobr → :687-698 的 ButtonsToDisplayLines；语料 `ERB/CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERB:438`）。 */
    @Test
    fun `直印 html 的 nobr 置 noBreak`() {
        val flat = HtmlRunFlatten(metrics)
        val out = flat.flattenLine(line(htmlRun("<nobr>不许断的行")))
        assertTrue(out.noBreak)
    }

    /**
     * 直印 HTML **没写 `<p align>` 时也落 LEFT**、吃掉控制台 ALIGNMENT：
     * 原版 html2DisplayLine 每串 HTML 新建状态、缺省 LEFT（HtmlManager.cs:536），
     * 产出线先经 :702-710 的 `SetAlignment(state.Alignment)` 把 `aligned` 置上
     * （ConsoleDisplayLine.cs:56），随后 addDisplayLine 的 `SetAlignment(alignment)`
     * （EmueraConsole.cs:2941-2944）撞一次守卫（ConsoleDisplayLine.cs:52-55）成 no-op。
     */
    @Test
    fun `直印 html 缺省落 LEFT 且覆盖控制台对齐`() {
        val flat = HtmlRunFlatten(metrics)
        val out = flat.flattenLine(line(htmlRun("没有 p 标签"), align = Alignment.RIGHT))
        assertEquals(Alignment.LEFT, out.align)
    }

    /** 显式 `<p align='left'>` 同样覆盖控制台对齐（同 [直印 html 缺省落 LEFT 且覆盖控制台对齐] 的一次守卫口径）。 */
    @Test
    fun `直印 html 的 p align left 同样覆盖控制台对齐`() {
        val flat = HtmlRunFlatten(metrics)
        val out = flat.flattenLine(line(htmlRun("<p align='left'>靠左"), align = Alignment.RIGHT))
        assertEquals(Alignment.LEFT, out.align)
    }

    /** 不认的 align 取值：原版抛 CodeEE（HtmlManager.cs:1138-1141），这里宽容为不记 ⇒ 落缺省 LEFT。 */
    @Test
    fun `直印 html 的未知 align 值宽容落 LEFT`() {
        val flat = HtmlRunFlatten(metrics)
        val out = flat.flattenLine(line(htmlRun("<p align='justify'>正文")))
        assertEquals(Alignment.LEFT, out.align)
    }

    /** toPrintBuffer 段（htmlOption = true）不走行级对齐：原版在 `buttonsOutput != null` 提前返回（HtmlManager.cs:679-682），align/nobr 都不生效。 */
    @Test
    fun `toPrintBuffer 的 html 段不带行级对齐`() {
        val flat = HtmlRunFlatten(metrics)
        val optionRun = htmlRun("<p align='center'><nobr>缓冲").copy(htmlOption = true)
        val out = flat.flattenLine(line(optionRun, align = Alignment.RIGHT))
        assertEquals(listOf("缓冲"), out.runs.map { it.text }, "段本身照常展平（剥标签），只是行级状态不采纳")
        assertEquals(Alignment.RIGHT, out.align, "控制台对齐原样保留")
        assertTrue(!out.noBreak)
    }

    /** 行里没有任何直印 HTML 段时原行实例原样返回（调用方靠实例同一性省重挂）。 */
    @Test
    fun `无直印 html 的行原实例返回`() {
        val flat = HtmlRunFlatten(metrics)
        val l = line(TextRun("普通", 0xFFFFFF, 0, 0, "msgothic", RunKind.TEXT))
        assertSame(l, flat.flattenLine(l))
    }

    private fun assertNullText(run: TextRun) {
        assertTrue(run.text.none { it != ' ' }, "图形段的文本负载只能是空格（占宽用）：${run.text}")
    }

    private fun TextRun.widthCellsOf(): Int = (graphic as InlineGraphic).widthCells
}
