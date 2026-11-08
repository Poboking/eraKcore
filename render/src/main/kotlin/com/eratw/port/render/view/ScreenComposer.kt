package com.eratw.port.render.view

import com.eratw.port.core.host.CbgLayerSnapshot
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.render.DirtyRect
import com.eratw.port.render.GlyphKey
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RunKind
import com.eratw.port.render.adapter.DivChildDraw
import com.eratw.port.render.adapter.DivOverlayFrame
import com.eratw.port.render.adapter.DivOverlayPlanner
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.button.ButtonLayer
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.sprite.DrawLineBar
import com.eratw.port.render.sprite.Raster
import com.eratw.port.render.sprite.SpriteTable

/**
 * 一条立绘摆位（原版 `ClientBackGroundImage` 的绘制侧字段子集）。
 *
 * 出处：`EmueraConsole.cs:35-66` 的内部类 `ClientBackGroundImage`
 * （字段 `Img`/`ImgB`/`x`/`y`/`zdepth`/`isButton`/`buttonValue`/`tooltipString`，
 * 比较器 `CompareTo` = `-zdepth.CompareTo(other.zdepth)`，即 **zdepth 降序**，:58-65）。
 *
 * @property name 立绘名（大小写不敏感，交给 [SpriteTable.find] 归一，`AppContents.cs:45` 口径）；
 *   表里没有这个名字时本条**静默跳过** —— 原版守卫 `aSprite != null && aSprite.IsCreated`
 *   （`EmueraConsole.cs:1844`）
 * @property xPx 横向像素偏移（原版直接当 `Point` 的 x 用，`EmueraConsole.cs:1846`）
 * @property yPx 纵向偏移，**下沿锚定口径**：落位 y = `yPx + 画布高 - 立绘高`
 *   （`EmueraConsole.cs:1846` 的 `cbg.y + window.MainPicBox.Height - aSprite.DestBaseSize.Height`）；
 *   语料里负 ypos 就是这个口径的用法（`ERB/TITLE.ERB:79`、
 *   `ERB/BODY_INFO/PRINT_STATE_BODYGRAPH.ERB:157`）
 * @property zdepth 层级：大于 0 画在文本**之下**（z 降序先画 ⇒ 垫底），等于 0 与文本同批
 *   （先画立绘再画文本 ⇒ 同样在文本之下），小于 0 画在文本**之上**
 *   （`EmueraConsole.cs:1834-1857` 的双路归并：按 `-zdepth.CompareTo(other.zdepth)` 降序取两侧头，
 *   `num7 == 0` 时才画文本行 ⇒ z 越大越先落笔、越靠底）。
 *   注意 C# 比较器是**取负**的降序（:58-65），别把「z 大 = 靠上」的直觉带进来。
 */
data class SpritePlacement(
    val name: String,
    val xPx: Int,
    val yPx: Int,
    val zdepth: Int = 0,
)

/**
 * 一屏的非文本层输入：控制台背景色 + 立绘摆位 + CBG 图层 + CBG 按下态。
 *
 * @property backgroundArgb 整屏背景（ARGB）。出处：`EmueraConsole.cs:1816` 的
 *   `graph.Clear(bgColor)`，`bgColor` 是**控制台级**字段（`:200` 初值 `Config.BackColor`、
 *   `:2844-2846` 的 `SetBgColor` 整体替换，对应语句 SETBGCOLOR `Process.cs:1455`）；
 *   默认取基准实测的默认背景 `0x000000`（`pc-golden-root.txt:270-271` 的
 *   `DEFBG=0`／`BGRESET=0`）。
 * @property sprites 立绘摆位，绘制时按 [SpritePlacement.zdepth] 降序（稳定：同 z 保持入参序）
 * @property cbgLayers CBG 图层快照（`com.eratw.port.core.host.CbgLayerSnapshot`，**已按 depth
 *   降序**），来自 `GraphicsLayer.cbgLayers`。`CBGSETG` / `CBGSETSPRITE` /
 *   `CBGSETBUTTONSPRITE` 的落点：原版与立绘同在一张 `cbgList` 里按 zdepth 合并绘制
 *   （`EmueraConsole.cs:1834-1857`），本类因此把它与 [sprites] / div 覆盖块归并
 *   （见 [CbgDrawOrder.merge]）。空表 = 与接入前逐像素相同。
 * @property pressedCbgButtons 当前处于**按下态**的 CBG 按钮值集合（`0xRRGGBB`）。
 *   语义 = 原版 `selectingCBGButtonInt`（`EmueraConsole.cs:82`）：绘制时
 *   `isButton && buttonValue == selectingCBGButtonInt` 的层改画 `spriteNameB`
 *   （`:1840-1843`）。来源 = `CbgDrawOrder.hitCbgButton` 在指针按下/移动时算出的
 *   `value`（真机接线点见 `CbgDrawOrder.hitCbgButton` 的 KDoc）。
 *   **进 [ScreenScene] 而不是 [composeInto] 的参数**：它一变就必须整屏重画
 *   （换图会跨行改像素），放进场景才有 `ConsoleFrame.previousScene != scene` 这条
 *   既有的整屏判据兜住。空集 = 没有按钮按下（与接入前逐像素相同）。
 */
data class ScreenScene(
    val backgroundArgb: Int = DEFAULT_BACKGROUND,
    val sprites: List<SpritePlacement> = emptyList(),
    val cbgLayers: List<CbgLayerSnapshot> = emptyList(),
    val pressedCbgButtons: Set<Int> = emptySet(),
) {
    companion object {
        /** 默认背景 `0xFF000000`：基准 `pc-golden-root.txt:270-271` 的 DEFBG／BGRESET 读数 0。 */
        const val DEFAULT_BACKGROUND: Int = -0x1000000 // 0xFF000000
    }
}

/**
 * 分层合成器：背景色 → 立绘 → 文本，产出纯 JVM 的 ARGB 画布（[Raster]）。
 *
 * ## 为什么输入是 [DrawCmd] 而不是文本行
 *
 * `displaylist` 路的不变量是「`submit` 之后绘制只读列表，不再遍历文本」
 * （见 `displaylist/DrawCmd.kt` 类文档，判据 `ConsoleButtonString.cs:257-270`、
 * `ConsoleDisplayLine.cs:118-125`）。本类吃 [DrawCmd] 正好接住这条：列区间与字形引用
 * 都已在提交期算好，合成阶段只做「格子 → 像素 → 铺块」。文本行入口
 * [compose] 的便捷重载仍然只从 [ConsoleScreen] 拿 `list.render()` 的只读列表。
 *
 * ## 三层顺序与出处
 * 1. 背景色：整屏 `Clear`（`EmueraConsole.cs:1816`）；原版紧随其后还有一张
 *    `bakedBackground` 烘焙背景图（`:1817-1820`），本波不做（背景图合成归 M4-SKEL／真机），
 *    要垫图（铺在其它立绘与文本**之下**）请给一个 `zdepth = Int.MAX_VALUE` 的摆位 ——
 *    归并每一轮取三侧头里 depth **最大**的那一支（`:1836` 的 `Math.Max`），各侧先按 depth
 *    降序排好（`ClientBackGroundImage.CompareTo` = `-zdepth.CompareTo(other.zdepth)` `:58-64`，
 *    逃逸段侧 `Array.Sort(array, (a, b) => -a.CompareTo(b))` `:1830`）⇒ **depth 最大者最先
 *    落笔、最靠底**；而 `Int.MIN_VALUE` 属于「depth 为负」那一支，要等 `num7 == 0` 的文本行
 *    画完之后才落笔（`:1850-1857`），会把整张垫图画在屏幕最上层。
 * 2. 立绘：按 zdepth 降序与文本归并绘制（`:1834-1848`），落位下沿锚定（`:1846`），
 *    选中之上换 `ImgB`（`:1840-1842`）—— 本类用一个 [SpritePlacement] 名字换另一个名字表达，
 *    不做 `ImgB` 字段（本波 `:830-838` 的选中态只有引用比较，没有像素判据可抄）。
 * 2.5 **CBG 图层**（`CBGSETG` / `CBGSETSPRITE` / `CBGSETBUTTONSPRITE` 的落点，
 *    [ScreenScene.cbgLayers]）：与原版同一条归并 —— 它与立绘同在一张 `cbgList`，
 *    按 depth 降序与文本、div 覆盖块合并（`:1834-1873`）。depth >= 0 的层垫在正文之下
 *    （语料活体 `QOL_IMAGE.ERB:2562` 的 `CBGSETG BG_ID, 0, 0, 1000` 就是这条：全屏高亮图垫底），
 *    depth < 0 的层在正文之后落笔（压住正文）。落位与立绘同式：x 直取、y 下沿锚定
 *    （`Point(cbg.x, cbg.y + MainPicBox.Height - DestBaseSize.Height)`，`:1846`）。
 *    按钮按下态改画 `spriteNameB`（`:1840-1843`）、动画层按当前帧尺寸与偏移落位
 *    （`SpriteAnime.cs:137-146`），两条都在 [drawCbgLayer] 里。
 *    **gid 图层当前跳过** —— 像素在 core 的 `GraphicsPool` 里，render 没有像素通道
 *    （见 [drawCbgLayer] 的 TODO(verify) 与卡点）。
 * 2.6 **div 覆盖块与 CBG / 立绘 / 正文在同一个 depth 序里归并**（C 刀）：
 *    序由 [CbgDrawOrder.merge] 给出，[composeInto] 只按它落笔。旧实现是「CBG(z>=0) →
 *    div behind → 正文 → div front → CBG(z<0)」的两段式，在「CBG depth=1 + div depth=2」
 *    这类形态下先后与原版相反（原版先 div2 再 CBG1）。
 * 3. 文本：逐 [DrawCmd] 逐字形铺前景色块（`ConsoleStyledString.DrawTo` :97-140，
 *    取色 `color = base.Color` :103，TEXTRENDERER 路径 `TextRenderer.DrawText` :133/:138）。
 *    **原版默认没有逐段背景填充**：只有 `JSONConfig.Data.UseButtonFocusBackgroundColor`
 *    为真时才给按钮段画底（:121-130），而该开关的默认值是 C# 布尔零值 `false`
 *    （`JSONConfigData.cs:7-8`，没有初始化器），所以本类不读 [DrawCmd.back]，
 *    只用它做「按钮被指着时换色」（见下条）。
 * 4. 焦点态：`isFocus` 时段内文字换成 `ButtonColor`（`:105-111`），
 *    `ButtonColor` 默认 = `Config.FocusColor`（`StringStyle.cs:11,22`），
 *    基准实测 `FOCUS=16776960` = `0xFFFF00`（`pc-golden-root.txt:269`）。
 *    本类把它做成可选入参 [focusedButton]：命中该按钮的段改用 [DrawCmd.focusFore]
 *    （默认 `0xFFFF00`，HTML `bcolor` 可逐段覆盖）当文字色。
 *    **不是** [DrawCmd.back]：原版 `back` 对应控制台背景色（`SETBGCOLOR` 的行快照），
 *    与焦点字色是两个字段；早先误用 `back` 会让默认黑底上的按钮在指针指着时黑字消失。
 *
 * ## 行列位置怎么算（本类的唯一职责）
 * - 画布宽 = `metrics.cellsToPixels(clientWidthCells)`（半角格 → 像素走 GRAPHICS 模式的
 *   量化式，`StringMeasure.cs:37,65`，见 `glyph/CellMetrics.kt`）；
 * - 行顶 y = `canvasHeightPx - (rowCount - row) * metrics.lineHeight`，
 *   即最后一行落在 `canvasHeightPx - lineHeight` —— 逐行对应原版的
 *   `num = MainPicBox.Height - LineHeight`（`EmueraConsole.cs:1805`）、
 *   `num -= (num2 - num3) * LineHeight`（`:1812`）与循环里 `num += LineHeight`（`:1855`）；
 *   绘制入口多要的那一行因此落在负 y、被 [Raster] 静默裁剪；C# 自己也画一行露在窗口上沿外
 *   （955px 画布上是 -17，露出 1 像素，见 `scroll/ViewportAuditTest` 的像素读数）；
 * - 列 x = `metrics.cellsToPixels(col)`，逐字形按 [CellMetrics.cellsOf] 推进
 *   （半角 1／全角 2，判据 `EmueraConsole.cs:3255` 的 Shift-JIS 字节数）。
 *
 * ## 字形块是占位画法
 *
 * 本类把每个字形铺成「单元格内缩 1 像素」的实心块（上下各内缩 `GLYPH_INSET_PX`），
 * 因为 M4 的出口判据是**行列位置**一致、明确允许字形差异
 * （`docs/eratw-项目实施方案.md` 的 M4 节）。真机上这一层换成字形缓存位图
 * （`glyph/GlyphCache.kt` + 平台光栅化器）逐块 `Raster.blit`，坐标算法不变。
 * TODO(verify)：内缩像素与基线位置没有 PC 截图可比（本机无 Android SDK），
 * 真机截图验收时需按平台字体的 ascent/descent 复核文字竖向落点。
 *
 * ## 画布复用与按行重绘（W8-RENDERPERF）
 *
 * 三个入口，像素口径同一个：`compose` 新建画布、[composeInto] 重绘进调用方持有的画布、
 * [repaintRows] 只重绘脏行段。一帧一张 1800 × 954 的画布是 6.9 MB 的 `IntArray`
 * （60 fps 下每秒 400 MB 垃圾），所以真机稳态帧走后者：整屏重绘只在「首帧 / 尺寸变化 /
 * 背景色或立绘变了 / 焦点变了」发生，其余帧按 `DisplayList.dirtyStrips` 的行段作画。
 * 三者对同一个状态必须给出**逐像素相同**的结果 —— 单测 `按行重绘与整屏重绘逐像素相同`
 * 逐帧对拍（末尾追加、改中间行、清屏三种形态），这是本优化「输出不变」的证据。
 *
 * @param clientWidthCells 可绘制宽度（半角格），与 [ConsoleScreen.clientWidth] 同口径
 * @param heightLines 命中口径的可见行数，与 [ConsoleScreen.heightLines] 同口径
 * @param metrics 像素 ↔ 半角格换算器（真实字体度量口径的入口，本波默认 18/19 字号行高）
 * @param canvasHeightPx 画布高（像素）；默认 `heightLines * lineHeight`，
 *   真机上应传窗口客户区高（基准 `CLIENTHEIGHT=955`，`pc-golden-root.txt:688`）
 * @param sizeSource div 子行里 `<img>` 的**尺寸源**（缺宽推宽 + 图元 miss 判定，
 *   [HtmlRunFlatten.ImageSizeSource]）。null（缺省）= 判不了图元在不在，缺宽 `<img>`
 *   保持旧的见方占位（登记偏差）。真机侧同一路在 `CanvasConsoleView` 已自带尺寸源；
 *   桌面预览由 `PreviewRenderer` 转发同一份 `JvmImageFrames`
 */
class ScreenComposer(
    val clientWidthCells: Int,
    val heightLines: Int,
    val metrics: CellMetrics = CellMetrics.DEFAULT,
    val canvasHeightPx: Int = heightLines * metrics.lineHeight,
    private val sizeSource: HtmlRunFlatten.ImageSizeSource? = null,
) {

    init {
        require(clientWidthCells >= 1) { "clientWidthCells 必须至少 1 半角格，实到 $clientWidthCells" }
        require(canvasHeightPx >= 0) { "canvasHeightPx 不能为负，实到 $canvasHeightPx" }
    }

    /**
     * div 子行展平器（自持实例，与正文展平器的调用序互不干扰）。尺寸源与正文路同一条
     * （原版 `ConsoleImagePart.cs:60-71` 的查表在展平期只有一次机会）：传了就以
     * 纵横比推宽 / 按 AltText 落文本，没传才退回见方占位。
     */
    private val divFlattener: HtmlRunFlatten by lazy { HtmlRunFlatten(metrics, sizeSource) }

    /**
     * div 覆盖绘制规划器（CONTRACT-DIV-WIRE W1）：headless 装置没有真机字号可折，
     * `scriptFontPixelSize` 取 0（恒等档 —— 脚本像素就是设备像素，与黄金基准同尺）；
     * 真机侧 `CanvasConsoleView` 自持一份带配置字号的规划器，两头的规划输出共用
     * [DivOverlayPlanner] 一份实现。子行折行（`DivChildDraw.wrap`）由规划器统一做，
     * 本侧只消费 [DivOverlayPlanner.ChildRow] 的成品命令。
     */
    val divPlanner: DivOverlayPlanner = DivOverlayPlanner(metrics, 0, divFlattener)

    /** 画布宽（像素）：半角格宽度按 [CellMetrics.cellsToPixels] 量化。 */
    val canvasWidthPx: Int = metrics.cellsToPixels(clientWidthCells)

    /** 第 [row] 行（0 基，[rowCount] 行窗口内）的顶边像素 y；可为负（绘制口径的溢出行）。 */
    fun rowTopPx(row: Int, rowCount: Int): Int = canvasHeightPx - (rowCount - row) * metrics.lineHeight

    /**
     * 合成一屏（每次新建一张 [Raster]）。
     *
     * @param commands [com.eratw.port.render.displaylist.DisplayList.render] 的只读输出；
     *   `cmd.row` 是行窗口下标（绘制口径，即 [ConsoleScreen.visibleRows] 的下标）
     * @param rowCount 行窗口行数（= [ConsoleScreen.visibleRows] 的大小，脏区裁剪不影响本参数）
     * @param scene 背景色与立绘摆位
     * @param sprites 立绘注册表；[SpritePlacement.name] 在这里解析，未登记的名字静默跳过
     *   （`EmueraConsole.cs:1844`）
     * @param focusedButton 当前被指着的按钮（行坐标必须是**绘制口径**的下标，
     *   从 [ConsoleScreen.hitRowToSnapshotRow] 或 [ConsoleScreen.mapToSnapshotRow] 换过来）；
     *   它覆盖的段改用 [DrawCmd.focusFore] 当文字色（`ConsoleStyledString.cs:105-111` 的
     *   `ButtonColor`），与背景色 [DrawCmd.back] 无关
     * @param focusedDiv div 子行按钮的焦点（CONTRACT-DIV-HIT2）：判据「块下标 + 子行号 +
     *   格区间相交」，命中段换 [DrawCmd.focusFore] 字色（同上），图形段换按下图/焦点色。
     *   来源是 `ButtonLayer.focusedDiv()`（像素指针赢下命中链时才有值）
     * @param pressedDiv div 子行按钮的按下（原版 `isSelecting` 那一路）：文字叠
     *   [RunChrome.PRESSED_BACK]、位图换 [InlineGraphic.Image.buttonResource]、
     *   色块换焦点色（`ConsoleRectangleShapePart.cs:44-45`）
     * @param cbgAnimeNowMs 动画 CBG 的帧号时钟（见 [composeInto] 的同名参数）：null（默认）
     *   用快照自带的帧号（首帧），非 null 则按返回的「该层已播放毫秒数」逐帧推进
     */
    fun compose(
        commands: List<DrawCmd>,
        rowCount: Int,
        scene: ScreenScene = ScreenScene(),
        sprites: SpriteTable = SpriteTable(),
        focusedButton: HitTarget? = null,
        overlay: DivOverlayFrame = DivOverlayFrame.EMPTY,
        focusedDiv: ButtonLayer.DivFocus? = null,
        pressedDiv: ButtonLayer.DivFocus? = null,
        cbgAnimeNowMs: ((CbgLayerSnapshot) -> Long)? = null,
    ): Raster = composeInto(
        target = Raster(canvasWidthPx, canvasHeightPx),
        commands = commands,
        rowCount = rowCount,
        scene = scene,
        sprites = sprites,
        focusedButton = focusedButton,
        overlay = overlay,
        focusedDiv = focusedDiv,
        pressedDiv = pressedDiv,
        cbgAnimeNowMs = cbgAnimeNowMs,
    )

    /**
     * 整屏重绘到**调用方持有的画布**上（结果与 [compose] 逐像素相同）。
     *
     * 存在的理由是分配：一帧一张 1800 × 954 的 [Raster] 就是 6.9 MB 的 `IntArray`，
     * 60 fps 下等于每秒 400 MB 的垃圾 —— 真机上必然掉帧。持有画布的入口是
     * `view/ConsoleFrame`（[compose] 仍是「新建一张」的便捷形态，行为不变）。
     *
     * @param target 目标画布，尺寸必须与 [canvasWidthPx] / [canvasHeightPx] 相等
     * @param overlay div 覆盖块帧（CONTRACT-DIV-WIRE W1）：按 [CbgDrawOrder.merge] 的
     *   **单趟 depth 归并**落笔 —— 立绘 / CBG / div 块 / 正文在**同一个** depth 序上排队，
     *   与原版 `EmueraConsole.cs:1834-1873` 的那一个 while 循环同形（不是「先 CBG → div
     *   → 正文」两段式）。调用方在帧入口算好（`view/ConsoleFrame.draw`），本类不做扫描。
     * @param focusedDiv div 子行按钮焦点（[compose] 的同名参数；见其 KDoc）
     * @param pressedDiv div 子行按钮按下（[compose] 的同名参数；见其 KDoc）
     * @param cbgAnimeNowMs 动画 CBG 的层内已播放毫秒数来源：null（默认）= 用
     *   [CbgLayerSnapshot.frameIndex]（core 恒给首帧）；非 null = 宿主按
     *   [CbgDrawOrder.CbgAnimeClock] 喂时钟、逐帧推进（真机 / [ConsoleFrame] 走这条）
     * @return [target] 本身（便于链式读取像素）
     */
    fun composeInto(
        target: Raster,
        commands: List<DrawCmd>,
        rowCount: Int,
        scene: ScreenScene = ScreenScene(),
        sprites: SpriteTable = SpriteTable(),
        focusedButton: HitTarget? = null,
        overlay: DivOverlayFrame = DivOverlayFrame.EMPTY,
        focusedDiv: ButtonLayer.DivFocus? = null,
        pressedDiv: ButtonLayer.DivFocus? = null,
        cbgAnimeNowMs: ((CbgLayerSnapshot) -> Long)? = null,
    ): Raster {
        requireCanvas(target)
        target.fillRect(0, 0, canvasWidthPx, canvasHeightPx, scene.backgroundArgb)
        // 无立绘、无 CBG、无覆盖块：既有快路径逐字节不变（绝大多数帧）。
        if (overlay.isEmpty && scene.sprites.isEmpty() && scene.cbgLayers.isEmpty()) {
            var i = 0
            while (i < commands.size) {
                drawRun(target, commands[i], rowCount, focusedButton)
                i++
            }
            return target
        }
        // 单趟 depth 归并（CbgDrawOrder.merge）：立绘 / CBG / div 块 / 正文同一条 depth 序，
        // 与原版 EmueraConsole.cs:1834-1873 的 while 同形（逐条证据见该函数的类 KDoc）。
        val steps = CbgDrawOrder.merge(
            cbgLayers = scene.cbgLayers,
            divBehind = overlay.behind,
            divFront = overlay.front,
            textDepth = 0,
            sprites = scene.sprites,
        )
        for (step in steps) {
            when (step) {
                is CbgDrawOrder.Step.Cbg -> drawCbgLayer(
                    canvas = target,
                    layer = step.layer,
                    table = sprites,
                    pressed = step.layer.isButton && step.layer.buttonValue in scene.pressedCbgButtons,
                    elapsedMs = cbgAnimeNowMs?.invoke(step.layer),
                )
                is CbgDrawOrder.Step.Sprite -> drawSprite(target, step.placement, sprites)
                is CbgDrawOrder.Step.Div -> drawDivBlock(
                    canvas = target,
                    block = step.block,
                    blockIndex = step.blockIndex,
                    sprites = sprites,
                    focusedDiv = focusedDiv,
                    pressedDiv = pressedDiv,
                )
                is CbgDrawOrder.Step.Text -> {
                    var i = 0
                    while (i < commands.size) {
                        drawRun(target, commands[i], rowCount, focusedButton)
                        i++
                    }
                }
            }
        }
        return target
    }

    /**
     * 画一条 CBG 图层：**下沿锚定**，与立绘同一落位公式。
     *
     * 出处 `EmueraConsole.cs:1846`：`new Point(cbg.x, cbg.y + MainPicBox.Height - DestBaseSize.Height)`
     * —— x 直接用，y 是下沿锚定偏移（负数把图往下压）。`DestBaseSize` 是 sprite 的基准尺寸，
     * headless 的 [SpriteTable] 不带 `pos`/子矩形，故回落次序是：
     * 快照的 [CbgLayerSnapshot.baseHeightPx]（= `DestBaseSize.Height`，core 已给）→
     * 落位高 → 位图高（静态层的三种写法等价）。
     *
     * ## 动画层（A 刀：`CBGSETSPRITE` 接动画 sprite）
     *
     * 原版动画的 `GraphicsDraw(g, Point)` 与静态**不同**（`SpriteAnime.cs:137-146`）：
     * ```
     * offset.Offset(DestBasePosition);            // 本快照不含 DestBasePosition（headless 恒 0）
     * offset.Offset(currentFrame.Offset);         // 帧偏移：本函数的 frameOffsetX/Y
     * DrawImage(..., new Rectangle(offset, currentFrame.SrcRectangle.Size), ...)  // 尺寸 = 帧尺寸
     * ```
     * 所以动画层的落位 = `(x + frameOffsetX, y + 画布高 - baseHeightPx + frameOffsetY)`、
     * 尺寸 = `(帧宽, 帧高)`；**锚点仍用 `DestBaseSize.Height`**，不是帧高
     * （`EmueraConsole.cs:1846` 在动画分支同样先减 `DestBaseSize.Height`）。
     * 帧几何由 [CbgDrawOrder.frameGeometry] 解析（`elapsedMs == null` 时取快照帧号，
     * 否则按帧时长推进）。
     *
     * ## 按钮按下态（B 刀）
     *
     * `pressed == true` 且快照带 [CbgLayerSnapshot.spriteNameB] 时改画第二张图
     * （原版 `isButton && buttonValue == selectingCBGButtonInt` ⇒ `aSprite = ImgB`，
     * `EmueraConsole.cs:1840-1843`）；`spriteNameB` 为 null 时照画主图（原版
     * `ImgB` 未给就是 null，`GraphicsDraw` 前的 `aSprite.IsCreated` 守卫会跳过 ——
     * 本移植按「退主图」处理，因为 `CBGSETBUTTONSPRITE` 的两个名字都必填、
     * 空名的层在绘制侧查不到，等价于不画）。
     *
     * ## gid 图层：**跳过**（登记的分支缺口）
     *
     * `CBGSETG` 的像素在 core 的 `GraphicsPool` 里（`CbgPool.Layer.gid`），渲染侧只拿到
     * gid 与宽高，没有像素通道 ⇒ 本方法对 `spriteName == null` 直接返回。
     * TODO(verify) 卡点：**core buffer 像素没有到 render 的通道**。要让 `CBGSETG` 上屏，
     * 需要 core 侧扩一份像素/位图快照（例如在 `CbgLayerSnapshot` 之外补
     * `GraphicsBuffer` 的 ARGB 快照，或宿主把 gid 同步登记成同名 sprite）；两条路都要动
     * 跨 lane 契约，由 Lead 决定。语料里 `CBGSETG` 的活体是 `QOL_IMAGE.ERB:2562`
     * 的 `CBGSETG BG_ID, 0, 0, 1000`（`GCREATE` + `GDRAWSPRITE` 合成的全屏图），
     * 名字路（`CBGSETSPRITE` / `CBGSETBUTTONSPRITE`）已可上屏。
     */
    private fun drawCbgLayer(
        canvas: Raster,
        layer: CbgLayerSnapshot,
        table: SpriteTable,
        pressed: Boolean = false,
        elapsedMs: Long? = null,
    ) {
        val name = if (pressed && layer.spriteNameB != null) layer.spriteNameB else layer.spriteName
        if (name == null) return
        val entry = table.find(name) ?: return
        // 原版 `aSprite != null && aSprite.IsCreated`（EmueraConsole.cs:1844）—— 死条目不画。
        if (!entry.isCreated) return
        val raster = entry.raster
        // [CbgLayerSnapshot.NO_FRAME] 是「这一层没有可画的帧」的**显式标记**：动画 sprite 的帧表
        // 为空时原版 `GetCurrentFrame` 返回 null、一个像素都不画（`SpriteAnime.cs:92-95` +
        // `:139-145`）。必须在「帧表为空 ⇒ 当静态层」之前判，否则空帧动画会退化成按位图画一张。
        if (layer.frameIndex == CbgLayerSnapshot.NO_FRAME) return
        val frame = CbgDrawOrder.frameGeometry(layer, elapsedMs)
        // 动画层但当前帧不可画（帧号越界）⇒ 整层跳过。
        if (frame == null && layer.frames.isNotEmpty()) return
        if (frame != null) {
            // 动画层的落位尺寸 = 当前帧 `SrcRectangle.Size`（`SpriteAnime.cs:144`）。
            // **帧矩形被裁空时（srcW/srcH = 0）原版 `DrawImage` 什么也不画**，不能回落到
            // 整张位图 —— 那会把一帧「交集为空」的动画画成一张静态大图。
            if (frame.widthPx <= 0 || frame.heightPx <= 0) return
            val w = frame.widthPx
            val h = frame.heightPx
            val anchorH = when {
                layer.baseHeightPx > 0 -> layer.baseHeightPx
                else -> h
            }
            val x = layer.x + frame.offsetX
            val y = canvasHeightPx + layer.y - anchorH + frame.offsetY
            if (w == raster.width && h == raster.height) canvas.blit(x, y, raster)
            else blitCbgScaled(canvas, raster, x, y, w, h)
            return
        }
        val destW = layer.widthPx
        val destH = layer.heightPx
        val w = if (destW > 0) destW else raster.width
        val h = if (destH > 0) destH else raster.height
        if (w <= 0 || h <= 0) return
        val anchorH = when {
            layer.baseHeightPx > 0 -> layer.baseHeightPx
            destH > 0 -> destH
            else -> raster.height
        }
        val x = layer.x + (frame?.offsetX ?: 0)
        val y = canvasHeightPx + layer.y - anchorH + (frame?.offsetY ?: 0)
        if (w == raster.width && h == raster.height) {
            canvas.blit(x, y, raster)
            return
        }
        blitCbgScaled(canvas, raster, x, y, w, h)
    }

    /**
     * CBG 层的非 1:1 落位：最近邻采样（与 [blitChildImage] 同一条采样式；GDI+ 的
     * `DrawImage` 是双线性，headless 无滤镜 —— 差异是既有的、登记在类文档的像素级口径）。
     *
     * 只在「动画帧尺寸 ≠ 位图尺寸」或「`DestBaseSize` 与位图不等」时走到；静态等尺寸
     * 层仍走 [Raster.blit] 的 1:1 快路径（逐像素与接入前相同）。
     */
    private fun blitCbgScaled(canvas: Raster, src: Raster, destX: Int, destY: Int, destW: Int, destH: Int) {
        val x0 = maxOf(destX, 0)
        val y0 = maxOf(destY, 0)
        val x1 = minOf(destX + destW, canvas.width)
        val y1 = minOf(destY + destH, canvas.height)
        if (x0 >= x1 || y0 >= y1) return
        var ty = y0
        while (ty < y1) {
            val sy = (ty - destY) * src.height / destH
            val row = sy * src.width
            var tx = x0
            while (tx < x1) {
                val sx = (tx - destX) * src.width / destW
                canvas.blend(tx, ty, src.pixels[row + sx])
                tx++
            }
            ty++
        }
    }

    /**
     * 一颗覆盖块：背景 → 四边框条带 → 内容盒内逐子行（块内几何的全部出处见
     * `DivOverlayPlanner.plan` 与其 KDoc；这里只做**铺像素**）。
     *
     * 背景铺 [DivOverlayPlanner.Block.borderRectPx] 整块、边框条带压在其上 —— 原版
     * `DrawBorder` 的四象限背景填充 + 边框多边形的净效果同形（象限 [+1px 重叠] 铺满
     * 矩形、边框画在背景之后，Shape.cs DrawBorder/drawNormalCornerBorderLeftTop）。
     * **圆角不复刻**（headless 占位栅格无路径填充；radius > 0 时四角按直角铺），
     * 真机侧 `CanvasConsoleView` 用 roundRect 画背景 —— 差异登记在
     * verify-notes §33 的消费端条目。
     *
     * 子行里的**按钮焦点/按下**按 `.Value` 判：原版子行与普通行共用 `DrawTo`，
     * `isFocus`/`isSelecting` 按按钮身份算、与是否在 div 内无关
     * （`ConsoleButtonString.cs:317-320` 透传到每个节点，CDP :196-203 逐子行调
     * `ConsoleDisplayLine.DrawTo`）⇒ 这里按 `(blockIndex, childRow, 格区间相交)` 比对。
     *
     * @param blockIndex 本块在 `Plan.all` 里的下标（[focusedDiv]/[pressedDiv] 的比对键）
     */
    private fun drawDivBlock(
        canvas: Raster,
        block: DivOverlayPlanner.Block,
        blockIndex: Int,
        sprites: SpriteTable,
        focusedDiv: ButtonLayer.DivFocus?,
        pressedDiv: ButtonLayer.DivFocus?,
    ) {
        val rect = block.borderRectPx
        if (rect.width > 0 && rect.height > 0) {
            block.backgroundArgb?.let { canvas.fillRect(rect.x, rect.y, rect.width, rect.height, it) }
            drawDivBorder(canvas, block)
        }
        val box = block.contentBoxPx
        if (box.width <= 0 || box.height <= 0) return
        for ((childRow, child) in block.children.withIndex()) {
            // 内容盒底以下不画（原版 SetClip 到内容盒，CDP :195；子行 y 升序，可断）。
            if (child.yPx >= box.y + box.height) break
            // 行对齐偏移（SetAlignment 的 customWidth 口径）折像素加到行左沿；
            // 原版把偏移写进 PointX（ConsoleDisplayLine.cs:80-90），命中侧同式。
            val originX = child.xPx + metrics.cellsToPixels(child.alignOffsetCells)
            for (cmd in child.cmds) {
                drawChildRun(
                    canvas = canvas,
                    cmd = cmd,
                    childXPx = originX,
                    childYPx = child.yPx,
                    box = box,
                    blockIndex = blockIndex,
                    childRow = childRow,
                    sprites = sprites,
                    focusedDiv = focusedDiv,
                    pressedDiv = pressedDiv,
                )
            }
        }
    }

    /**
     * headless 的四边框条带：宽度 0 不画（DrawBorder :92/:109）、bcolor 没写回落
     * `Config.ForeColor` 出厂值（:94/:111 的 colors == null 支；真机配置改动后的回退差
     * 登记 TODO(verify)）。**直角矩形近似**：原版每边是两端斜切的梯形（角上有 radius 时
     * 更是圆角路径），headless 占位栅格不铺路径 —— 角上差出边宽级的像素，见类文档。
     */
    private fun drawDivBorder(canvas: Raster, block: DivOverlayPlanner.Block) {
        val widths = block.borderWidthsPx
        if (widths.size < 4) return
        val rect = block.borderRectPx
        val colors = block.borderColorArgb
        for (edge in 0 until 4) {
            val w = widths[edge]
            if (w <= 0) continue
            val argb = if (edge < colors.size) colors[edge] else opaque(DIV_BORDER_FALLBACK_COLOR)
            when (edge) {
                0 -> canvas.fillRect(rect.x, rect.y, rect.width, w, argb)
                1 -> canvas.fillRect(rect.x + rect.width - w, rect.y, w, rect.height, argb)
                2 -> canvas.fillRect(rect.x, rect.y + rect.height - w, rect.width, w, argb)
                else -> canvas.fillRect(rect.x, rect.y, w, rect.height, argb)
            }
        }
    }

    /**
     * 一条子行的 headless 铺块：**文本段逐字形铺块**（原路径逐像素不变），**图形段**
     * （`<img>` / `<shape>`）走 [drawChildGraphic]（CONTRACT-DIV-HIT2 补的落像素）。
     *
     * x 从 `子行左沿（像素）+ 列 × 半角宽` 起，横向夹到内容盒（原版 SetClip 的水平边，
     * CDP :195），纵向压到内容盒底为止 —— 最后一排被盒底裁掉时只画盒内那截。
     *
     * 焦点/按下：`cmd.kind == BUTTON` 或图形段都可能落在按钮区间里；判据只看
     * 「本段列区间与 `DivFocus` 的列区间相交」（半开区间，口径同 `ButtonLayer` 的命中区间），
     * 原版按钮身份与节点类型无关（`ConsoleButtonString.cs:317-320` 的 isFocus/isSelecting
     * 逐节点透传）。文字换 `focusFore`（`ConsoleStyledString.cs:105-111`），按下再叠
     * [RunChrome.PRESSED_BACK]（与原版按钮按下高亮同形；`isSelecting` 那一路）。
     */
    private fun drawChildRun(
        canvas: Raster,
        cmd: DrawCmd,
        childXPx: Int,
        childYPx: Int,
        box: DivOverlayPlanner.Rect,
        blockIndex: Int,
        childRow: Int,
        sprites: SpriteTable,
        focusedDiv: ButtonLayer.DivFocus?,
        pressedDiv: ButtonLayer.DivFocus?,
    ) {
        val rowHeight = minOf(metrics.lineHeight, box.y + box.height - childYPx)
        if (rowHeight <= 0) return
        val focus = overlaps(focusedDiv, blockIndex, childRow, cmd)
        val pressed = overlaps(pressedDiv, blockIndex, childRow, cmd)
        val graphic = cmd.graphic
        if (graphic != null) {
            drawChildGraphic(canvas, cmd, graphic, childXPx, childYPx, box, focus, pressed, sprites)
            return
        }
        val boxTop = childYPx + GLYPH_INSET_PX
        val boxHeight = rowHeight - 2 * GLYPH_INSET_PX
        if (boxHeight <= 0) return
        val clipLeft = box.x
        val clipRight = box.x + box.width
        // 按下底色先铺（整段列区间；原版 isSelecting 的高亮在文字之下，`RunChrome` 同一条）。
        if (pressed) {
            val pressedLeft = childXPx + metrics.cellsToPixels(cmd.colStart)
            val pressedRight = childXPx + metrics.cellsToPixels(cmd.colEnd)
            val left = maxOf(pressedLeft, clipLeft)
            val right = minOf(pressedRight, clipRight)
            if (right > left) {
                canvas.fillRect(left, boxTop, right - left, boxHeight, RunChrome.PRESSED_BACK)
            }
        }
        val fore = opaque(if (focus || pressed) cmd.focusFore else cmd.fore)
        var col = cmd.colStart
        var i = 0
        val glyphs = cmd.glyphs
        while (i < glyphs.size) {
            val cells = cellsOf(glyphs[i])
            val x0 = childXPx + metrics.cellsToPixels(col)
            val x1 = childXPx + metrics.cellsToPixels(col + cells)
            val left = if (x0 > clipLeft) x0 else clipLeft
            val right = if (x1 - GLYPH_GAP_PX < clipRight) x1 - GLYPH_GAP_PX else clipRight
            if (right > left) {
                canvas.fillRect(left, boxTop, right - left, boxHeight, fore)
            }
            col += cells
            i++
        }
    }

    /** 焦点/按下标记是否覆盖本段（块下标 + 子行号 + 格区间相交，半开区间）。 */
    private fun overlaps(
        focus: ButtonLayer.DivFocus?,
        blockIndex: Int,
        childRow: Int,
        cmd: DrawCmd,
    ): Boolean {
        if (focus == null || focus.blockIndex != blockIndex || focus.childRow != childRow) return false
        return cmd.colStart < focus.colEnd && focus.colStart < cmd.colEnd
    }

    /**
     * 子行里的**图形段**落像素（CONTRACT-DIV-HIT2）：与正文图形段同一套几何，不许另写第二套。
     *
     * ## `<img>`（原版 `ConsoleImagePart.DrawTo` :159-174）
     *
     * - 落点：`x = 列游标 + ShapePositionShift`（`:171` 的 `rectangle.X = destRect.X + PointX +
     *   Config.DrawingParam_ShapePositionShift`）、`y = 行顶 + ypos`（`:172`）；
     * - 尺寸：`GraphicGeometry.inlineBox`（`:66-93`，高缺省退 `Config.FontSize`、宽缺省按基准
     *   纵横比反推）；框内落位 `GraphicGeometry.spriteDestRect`（`ASpriteSingle.cs:79-85`）——
     *   headless 的 sprite 表不带 `pos`/子矩形，`spriteDestRect` 因此恒等返框（与真机同一函数）；
     * - 1:1 用 [Raster.blit]、尺寸不等最近邻采样（真机 `drawBitmap` 的 `isFilterBitmap` 是
     *   双线性，headless 无滤镜 —— 差异登记 TODO(verify)）；
     * - **资源缺失静默跳过**：原版绘制前查 `aSprite != null && aSprite.IsCreated`
     *   （`:168`；立绘层同一条 `EmueraConsole.cs:1844`），未创建什么都不画 ——
     *   差异：原版在 `cImage == null` 时把重建的 img 标签当文本画（`:175-182`），
     *   headless 侧按本波契约静默跳过（`SpriteTable.Entry.isCreated` 的死条目同样跳过）。
     * - 焦点/按下换图：`(isSelecting || isFocus) && cImageB != null`（`:163-167`）⇒
     *   [InlineGraphic.Image.drawResource] 取 `srcb`；按下图查不到时退主图（`:164` 的
     *   `cImageB == null` 判式 + `AppContents.cs:46-48` 的未注册名返回 null）。
     *
     * ## `<shape type='rect'>`（原版 `ConsoleRectangleShapePart.cs:38-47`）
     *
     * `x += PointX`（`:44`）与上文同一个 ShapePositionShift（`:57` 的 `rect.X += shift`，
     * `Config.cs:461-465` = `max(2, FontSize/6)`）、`y = 行顶 + ypos`；填充色在按下/焦点时
     * 换 `ButtonColor`（`:45` 的 `isSelecting ? ButtonColor : Color`）。渐变停点按列取
     * （`gradientArgb` 的「等间距铺满段宽」口径，见 [InlineGraphic.Rect]）。
     */
    private fun drawChildGraphic(
        canvas: Raster,
        cmd: DrawCmd,
        graphic: InlineGraphic,
        childXPx: Int,
        childYPx: Int,
        box: DivOverlayPlanner.Rect,
        focus: Boolean,
        pressed: Boolean,
        sprites: SpriteTable,
    ) {
        val originLeft = childXPx + metrics.cellsToPixels(cmd.colStart) + divShapeShift
        when (graphic) {
            is InlineGraphic.Space -> Unit
            is InlineGraphic.Image -> drawChildImage(canvas, graphic, originLeft, childYPx, box, focus, pressed, sprites)
            is InlineGraphic.Rect -> {
                val left = originLeft + graphic.xOffsetPx
                val top = childYPx + graphic.yOffsetPx
                if (graphic.widthPx <= 0 || graphic.heightPx <= 0) return
                val emphasized = focus || pressed
                val stops = graphic.gradientArgb
                if (!emphasized && stops != null && stops.size >= 2) {
                    fillChildGradient(canvas, left, top, graphic.widthPx, graphic.heightPx, stops, box)
                } else {
                    val argb = opaque(if (emphasized) cmd.focusFore else graphic.colorArgb)
                    fillChildRect(canvas, left, top, graphic.widthPx, graphic.heightPx, box, argb)
                }
            }
        }
    }

    /** `<img>` 分支（几何与出处见 [drawChildGraphic] 的 KDoc）。 */
    private fun drawChildImage(
        canvas: Raster,
        image: InlineGraphic.Image,
        originLeft: Int,
        childYPx: Int,
        box: DivOverlayPlanner.Rect,
        focus: Boolean,
        pressed: Boolean,
        sprites: SpriteTable,
    ) {
        val emphasized = focus || pressed
        val requested = image.drawResource(emphasized)
        var entry = sprites.find(requested)
        if (entry == null && requested != image.resource) {
            // 按下图缺失退主图（ConsoleImagePart.cs:164 的 cImageB == null 判式）。
            entry = sprites.find(image.resource)
        }
        if (entry == null) return
        // 未创建（已 Dispose / 回滚复活的死条目）不画：原版 `aSprite.IsCreated` 守卫
        // （ConsoleImagePart.cs:168；立绘层 EmueraConsole.cs:1844）。
        if (!entry.isCreated) return
        val raster = entry.raster
        val inline = GraphicGeometry.inlineBox(
            givenWidthPx = image.widthPx,
            givenHeightPx = image.heightPx,
            baseW = raster.width,
            baseH = raster.height,
            fontSizePx = metrics.fontPixelSize,
        )
        // 框内落位：headless 的 sprite 无 pos/子矩形/动画帧，恒等返框（与真机同一函数，
        // 出处 ASpriteSingle.cs:79-85 / SpriteAnime.cs:153-156）。
        val place = GraphicGeometry.spriteDestRect(
            boxW = inline[0],
            boxH = inline[1],
            baseW = raster.width,
            baseH = raster.height,
            basePosX = 0,
            basePosY = 0,
            offsetX = 0,
            offsetY = 0,
            srcW = 0,
            srcH = 0,
            anime = false,
        )
        if (place[2] <= 0 || place[3] <= 0) return
        blitChildImage(
            canvas = canvas,
            src = raster,
            destX = originLeft + place[0],
            destY = childYPx + image.yOffsetPx + place[1],
            destW = place[2],
            destH = place[3],
            box = box,
        )
    }

    /** 位图落像素：尺寸与源相等走 1:1 [Raster.blit]，否则最近邻；两边都裁到内容盒。 */
    private fun blitChildImage(
        canvas: Raster,
        src: Raster,
        destX: Int,
        destY: Int,
        destW: Int,
        destH: Int,
        box: DivOverlayPlanner.Rect,
    ) {
        if (destW <= 0 || destH <= 0 || src.width <= 0 || src.height <= 0) return
        val x0 = maxOf(destX, box.x)
        val x1 = minOf(destX + destW, box.x + box.width)
        val y0 = maxOf(destY, box.y)
        val y1 = minOf(destY + destH, box.y + box.height)
        if (x0 >= x1 || y0 >= y1) return
        if (destW == src.width && destH == src.height) {
            // 1:1：整幅贴（源偏移吸收内容盒裁剪，`Raster.blit` 再做画布边界裁剪）。
            canvas.blit(x0, y0, src, srcX = x0 - destX, srcY = y0 - destY, srcW = x1 - x0, srcH = y1 - y0)
            return
        }
        var ty = y0
        while (ty < y1) {
            val sy = (ty - destY) * src.height / destH
            val row = sy * src.width
            var tx = x0
            while (tx < x1) {
                val sx = (tx - destX) * src.width / destW
                canvas.blend(tx, ty, src.pixels[row + sx])
                tx++
            }
            ty++
        }
    }

    /** 实心矩形填充（裁到内容盒）。 */
    private fun fillChildRect(
        canvas: Raster,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        box: DivOverlayPlanner.Rect,
        argb: Int,
    ) {
        val x0 = maxOf(x, box.x)
        val x1 = minOf(x + w, box.x + box.width)
        val y0 = maxOf(y, box.y)
        val y1 = minOf(y + h, box.y + box.height)
        if (x0 >= x1 || y0 >= y1) return
        canvas.fillRect(x0, y0, x1 - x0, y1 - y0, argb)
    }

    /**
     * 渐变矩形填充（[InlineGraphic.Rect.gradientArgb]）：停点等间距铺满段宽，**逐列**取停点
     * （`index = 列偏移 * 停点数 / 段宽`，与原版逐块 FillRectangle 的台阶近似一致，
     * 见 [InlineGraphic.Rect] 的 KDoc）。裁到内容盒。
     */
    private fun fillChildGradient(
        canvas: Raster,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        stops: IntArray,
        box: DivOverlayPlanner.Rect,
    ) {
        val x0 = maxOf(x, box.x)
        val x1 = minOf(x + w, box.x + box.width)
        val y0 = maxOf(y, box.y)
        val y1 = minOf(y + h, box.y + box.height)
        if (x0 >= x1 || y0 >= y1) return
        var tx = x0
        while (tx < x1) {
            val index = ((tx - x) * stops.size / w).coerceIn(0, stops.size - 1)
            canvas.fillRect(tx, y0, 1, y1 - y0, opaque(stops[index]))
            tx++
        }
    }

    /**
     * div 图形段的 `Config.DrawingParam_ShapePositionShift` = `max(2, FontSize/6)`
     * （`Config.cs:461-465`；原版绘制口径把它加到图段 x 上：`ConsoleImagePart.cs:171`、
     * `ConsoleRectangleShapePart.cs:57`）。算式**只有一份** —— 直接读
     * [DrawLineBar.shapeShift]（`sprite/DrawLineBar.kt:50-51`），不在这里重写。
     */
    private val divShapeShift: Int by lazy { DrawLineBar(fontSizePx = metrics.fontPixelSize).shapeShift }

    /**
     * **只重绘脏行**到已有画布（W8-RENDERPERF 的按行刷新）。
     *
     * 最小刷新单元 = 一条显示行 × 该行的列段（`displaylist/DisplayList.dirtyStrips` 的分组，
     * 见其类文档的「两级刷新单元」）：每条 [DirtyRect] 覆盖的行先按行高填背景，
     * 再只画这些行的命令，字形块按列段裁剪。结果与「整屏 [compose] 一帧」**逐像素相同**
     * （单测 `按行重绘与整屏重绘逐像素相同` 用 append/改行/清屏三种帧对拍）。
     *
     * 三个前提缺一不可，任何一条不满足都由调用方退回 [composeInto]：
     * 1. [target] 里已经有**上一帧**的完整结果（同一几何、同一 [backgroundArgb]）；
     * 2. 背景色不透明（半透明背景在新旧像素上叠加结果不同，`Raster.fillRect` 是合成语义）；
     * 3. 立绘层没有变化（本函数不碰立绘像素，只在文字行段内作画）。
     * **CBG 图层（[ScreenScene.cbgLayers]）非空时同样必须退整屏**：它们是整屏垫图层，
     * 换层会跨行带改写像素；`view/ConsoleFrame` 的整屏判定需把
     * `scene.cbgLayers.isNotEmpty()` 算进去（与立绘同一条），本函数不收该参数。
     * div 覆盖块在场时同样**必须**退整屏（块的像素横跨行带、按行擦背景会把块擦出洞，
     * CONTRACT-DIV-WIRE W2 的「不留陈迹」）—— `view/ConsoleFrame` 负责这条判定，
     * 本函数因此**不收**覆盖帧参数（收了也不画，调用方走 [composeInto]）。
     * 同理 [focusedDiv]/[pressedDiv] 只收不用（签名对齐 [composeInto]，免得调用方在两处
     * 写不同的实参表）：div 子行的像素只在整屏路径画。
     *
     * @param strips 行段，按 `rowStart` 升序、行区间互不相交且都在 `[0, rowCount)` 内
     * @return 实际重绘的行数（= 各段行数之和）
     */
    fun repaintRows(
        target: Raster,
        commands: List<DrawCmd>,
        rowCount: Int,
        strips: List<DirtyRect>,
        backgroundArgb: Int = ScreenScene.DEFAULT_BACKGROUND,
        focusedButton: HitTarget? = null,
        focusedDiv: ButtonLayer.DivFocus? = null,
        pressedDiv: ButtonLayer.DivFocus? = null,
    ): Int {
        requireCanvas(target)
        require(backgroundArgb ushr 24 == 255) {
            "按行重绘要求不透明背景（Raster.fillRect 是 source-over 合成），实到 0x" +
                backgroundArgb.toUInt().toString(16)
        }
        var rowsDrawn = 0
        var previousEnd = 0
        var cursor = 0
        var s = 0
        while (s < strips.size) {
            val strip = strips[s]
            require(strip.rowStart >= previousEnd && strip.rowEnd <= rowCount && strip.rowEnd > strip.rowStart) {
                "行段必须升序、互不相交且在 [0, $rowCount) 内：$strip"
            }
            previousEnd = strip.rowEnd
            val x0 = metrics.cellsToPixels(strip.colStart)
            val x1 = metrics.cellsToPixels(strip.colEnd)
            while (cursor < commands.size && commands[cursor].row < strip.rowStart) {
                cursor++
            }
            if (x1 > x0) {
                var row = strip.rowStart
                while (row < strip.rowEnd) {
                    target.fillRect(x0, rowTopPx(row, rowCount), x1 - x0, metrics.lineHeight, backgroundArgb)
                    row++
                }
                while (cursor < commands.size && commands[cursor].row < strip.rowEnd) {
                    drawRun(target, commands[cursor], rowCount, focusedButton, x0, x1)
                    cursor++
                }
            } else {
                while (cursor < commands.size && commands[cursor].row < strip.rowEnd) {
                    cursor++
                }
            }
            rowsDrawn += strip.rowEnd - strip.rowStart
            s++
        }
        return rowsDrawn
    }

    /** 目标画布尺寸必须是本合成器的几何（复用画布时唯一容易搞错的一条）。 */
    private fun requireCanvas(target: Raster) {
        require(target.width == canvasWidthPx && target.height == canvasHeightPx) {
            "画布尺寸 ${target.width}x${target.height} 与本合成器 ${canvasWidthPx}x$canvasHeightPx 不一致"
        }
    }

    /**
     * 便捷入口：直接合成 [screen] 的当前画面（绘制口径行窗口 + 只读绘制命令 + 命中焦点）。
     *
     * div 子行按钮的焦点/按下自动从 [ConsoleScreen.focusedDiv] / [ConsoleScreen.pressedDiv]
     * 取（CONTRACT-DIV-HIT2）—— 它们是像素指针赢下命中链后的产物，与绘制命令同帧；
     * 调用方不需要另传（要显式覆盖请走 [compose] 的 commands 重载）。
     *
     * @param pointing 绘制侧的指（按）位置，命中口径行坐标；null 表示无焦点
     */
    fun compose(
        screen: ConsoleScreen,
        scene: ScreenScene = ScreenScene(),
        sprites: SpriteTable = SpriteTable(),
        pointing: Pair<Int, Int>? = null,
    ): Raster = compose(
        commands = screen.list.render(),
        rowCount = screen.visibleRows().size,
        scene = scene,
        sprites = sprites,
        focusedButton = screen.focusedTarget(pointing),
        focusedDiv = screen.focusedDiv(),
        pressedDiv = screen.pressedDiv(),
    )

    /** 立绘落位：下沿锚定 + 双向裁剪由 [Raster.blit] 负责（原版 GDI+ 同样静默裁剪）。 */
    private fun drawSprite(canvas: Raster, placement: SpritePlacement, table: SpriteTable) {
        val entry = table.find(placement.name) ?: return
        // 原版画前查 `aSprite != null && aSprite.IsCreated`（EmueraConsole.cs:1844-1846）：
        // SPRITEDISPOSE / SPRITEDISPOSEALL(0) 回滚拷回的是已 Dispose 的死条目（像素仍在
        // Raster 里），不查 isCreated 会把已释放立绘照画（2026-10-26 fixcore 审查 render-B1；
        // SpriteTable.Entry.isCreated 谓词已在，此为登记过的「接线做一半」收尾）。
        if (!entry.isCreated) return
        val raster = entry.raster
        canvas.blit(placement.xPx, canvasHeightPx + placement.yPx - raster.height, raster)
    }

    /**
     * 逐字形铺块：x 从 `cellsToPixels(cmd.colStart)` 起，按 [CellMetrics.cellsOf] 逐码位推进；
     * 每块右边界取**下一个**格子的量化起点（避免逐块累加产生的 1 像素漂移），
     * 超出 `clientWidthCells` 的格子由 [Raster.fillRect] 裁掉。
     *
     * [clipX0]/[clipX1] 是像素列裁剪（默认整幅画布宽）：按行重绘时只许在行段内作画，
     * 与 [Raster.fillRect] 逐像素合成等价 —— 每个像素仍只被覆盖一次，颜色与整屏重绘相同。
     */
    private fun drawRun(
        canvas: Raster,
        cmd: DrawCmd,
        rowCount: Int,
        focusedButton: HitTarget?,
        clipX0: Int = 0,
        clipX1: Int = canvas.width,
    ) {
        val top = rowTopPx(cmd.row, rowCount)
        val boxTop = top + GLYPH_INSET_PX
        val boxHeight = metrics.lineHeight - 2 * GLYPH_INSET_PX
        if (boxHeight <= 0) {
            return
        }
        val fore = glyphFore(cmd, focusedButton)
        val glyphs = cmd.glyphs
        var col = cmd.colStart
        var i = 0
        while (i < glyphs.size) {
            if (col >= clientWidthCells) {
                break
            }
            val cells = cellsOf(glyphs[i])
            val x0 = metrics.cellsToPixels(col)
            val x1 = metrics.cellsToPixels(col + cells)
            val left = if (x0 > clipX0) x0 else clipX0
            val right = if (x1 - GLYPH_GAP_PX < clipX1) x1 - GLYPH_GAP_PX else clipX1
            if (right > left) {
                canvas.fillRect(left, boxTop, right - left, boxHeight, fore)
            }
            col += cells
            i++
        }
    }

    /**
     * 取字色：`isFocus` 的按钮段用 [DrawCmd.focusFore]（原文 `ButtonColor`，
     * `ConsoleStyledString.cs:105-111` + `StringStyle.cs:9,11,22`，默认 = `Config.FocusColor`
     * `0xFFFF00`，`ConfigData.cs:70`），其余用 `fore`（原文 `Color`，`:103`）。
     * 区间重叠判定用半开区间（`button/ButtonLayer.kt` 的命中口径）。
     *
     * **焦点色与 [DrawCmd.back] 无关**：`back` 是控制台背景色（`SETBGCOLOR` 的行快照），
     * 原版焦点分支从头到尾没有读过背景色；用 `back` 当焦点字色会在默认黑底上得到黑字。
     */
    private fun glyphFore(cmd: DrawCmd, focusedButton: HitTarget?): Int {
        val focus = focusedButton
        if (focus != null && cmd.kind == RunKind.BUTTON && cmd.row == focus.row &&
            cmd.colStart < focus.colEnd && focus.colStart < cmd.colEnd
        ) {
            return opaque(cmd.focusFore)
        }
        return opaque(cmd.fore)
    }

    /**
     * 单码位占几格：走真实字体度量入口 [CellMetrics.cellsOf]。
     * 代理对在显示列表里已合成一个码位（[GlyphKey]），但**推进量仍是布局的 4 格**
     * （两个 UTF-16 码元各 2 格，见 [CellMetrics.cellsOf] 与 `Grid` 的增补平面 KDoc）。
     */
    private fun cellsOf(key: GlyphKey): Int = metrics.cellsOf(key.codePoint)

    /** `0xRRGGBB` → 不透明 ARGB。原版 `Color` 一律不带 alpha（`Config.BackColor` 等）。 */
    private fun opaque(rgb: Int): Int = (0xFF shl 24) or (rgb and 0xFFFFFF)

    companion object {
        /** 块上下内缩（像素），占位画法用；见类文档「字形块是占位画法」。 */
        const val GLYPH_INSET_PX: Int = 2

        /** 块与块之间留 1 像素缝，保证同色相邻字形在截图上仍可数个。 */
        const val GLYPH_GAP_PX: Int = 1

        /** 基准一屏文本的默认字号／行高组合里的语料行高（`emuera.config:22-23`）。 */
        val CORPUS_METRICS: CellMetrics = CellMetrics(18, 18)

        /**
         * div 边框色的回落值：`bcolor` 没写时原版取 `Config.ForeColor`（DrawBorder :94/:111
         * 的 colors == null 支）。headless 装置没有配置面，取出厂值
         * （[LineBufferConsoleSink.DEFAULT_FORE_COLOR] = `ConfigData.cs:68` 的 192,192,192）；
         * 真机配置改过 ForeColor 时与原版差一档，TODO(verify) 登记（语料带 border 的 div
         * 全部写了 bcolor，现无实例可达）。
         */
        private val DIV_BORDER_FALLBACK_COLOR: Int = LineBufferConsoleSink.DEFAULT_FORE_COLOR

        /**
         * 黄金基准的画布尺寸（半角格 200 = 1800px／9px，行 54 = 955／18 + 1）。
         * 数值**只有一处**：基准窗口尺寸在 `ConsoleScreen` 的伴生常量上，本类按它引用，
         * 免得同一件事在同包内出现两份字面量（ADAPT-CONSUME 的「同一数字只留一处」）。
         */
        const val GOLDEN_CLIENT_WIDTH_CELLS: Int = ConsoleScreen.GOLDEN_CLIENT_WIDTH

        const val GOLDEN_HEIGHT_LINES: Int = ConsoleScreen.DEFAULT_HEIGHT_LINES

        /**
         * zdepth 降序、同 z 保持入参序（原版 `cbgList` 的排序语义，
         * `ClientBackGroundImage.CompareTo` = `-zdepth.CompareTo(other.zdepth)`，
         * `EmueraConsole.cs:58-65`；`Array.Sort` 不保证稳定，本波显式用下标做次键，
         * 以保证快照与画布的确定性）。
         */
        fun orderByZdesc(placements: List<SpritePlacement>): List<SpritePlacement> {
            val n = placements.size
            if (n == 0) {
                return emptyList()
            }
            if (n == 1) {
                return listOf(placements[0])
            }
            // 稳定的降序插入排序：z 大的在前，z 相同保持入参序（下标升序），
            // 与原来的「Triple(-z, index) 排序」逐项同结果。
            val order = IntArray(n) { it }
            var i = 1
            while (i < n) {
                val index = i
                val zdepth = placements[index].zdepth
                var j = i - 1
                while (j >= 0 && placements[order[j]].zdepth < zdepth) {
                    order[j + 1] = order[j]
                    j--
                }
                order[j + 1] = index
                i++
            }
            val out = ArrayList<SpritePlacement>(n)
            i = 0
            while (i < n) {
                out.add(placements[order[i]])
                i++
            }
            return out
        }

        /**
         * CBG 图层的 depth 稳定降序（口径与 [orderByZdesc] 相同）。
         *
         * core 推来的快照**已经**按 depth 降序（`CbgPool.add` 的 `sortByDescending`，
         * 对齐 `EmueraConsole.cs:645-646` 的 `Add + Sort`），这里再排一次只是不让绘制结果
         * 依赖装配侧传入顺序 —— 同 depth 保持入参序，与 [orderByZdesc] 的次键口径一致。
         */
        fun orderCbgByDepthDesc(layers: List<CbgLayerSnapshot>): List<CbgLayerSnapshot> {
            val n = layers.size
            if (n <= 1) return layers
            val order = IntArray(n) { it }
            var i = 1
            while (i < n) {
                val index = i
                val depth = layers[index].depth
                var j = i - 1
                while (j >= 0 && layers[order[j]].depth < depth) {
                    order[j + 1] = order[j]
                    j--
                }
                order[j + 1] = index
                i++
            }
            val out = ArrayList<CbgLayerSnapshot>(n)
            i = 0
            while (i < n) {
                out.add(layers[order[i]])
                i++
            }
            return out
        }
    }
}
