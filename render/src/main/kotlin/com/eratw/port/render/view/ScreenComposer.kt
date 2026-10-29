package com.eratw.port.render.view

import com.eratw.port.render.DirtyRect
import com.eratw.port.render.GlyphKey
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RunKind
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.glyph.CellMetrics
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
 * @property zdepth 层级：大于 0 画在文本**之上**，等于 0 与文本同批（先画立绘再画文本），
 *   小于 0 画在文本**之下**（`EmueraConsole.cs:1834-1857` 的双路归并：
 *   每轮取两侧头的最大 z，`num7 == 0` 时才画文本行）
 */
data class SpritePlacement(
    val name: String,
    val xPx: Int,
    val yPx: Int,
    val zdepth: Int = 0,
)

/**
 * 一屏的非文本层输入：控制台背景色 + 立绘摆位。
 *
 * @property backgroundArgb 整屏背景（ARGB）。出处：`EmueraConsole.cs:1816` 的
 *   `graph.Clear(bgColor)`，`bgColor` 是**控制台级**字段（`:200` 初值 `Config.BackColor`、
 *   `:2844-2846` 的 `SetBgColor` 整体替换，对应语句 SETBGCOLOR `Process.cs:1455`）；
 *   默认取基准实测的默认背景 `0x000000`（`pc-golden-root.txt:270-271` 的
 *   `DEFBG=0`／`BGRESET=0`）。
 * @property sprites 立绘摆位，绘制时按 [SpritePlacement.zdepth] 降序（稳定：同 z 保持入参序）
 */
data class ScreenScene(
    val backgroundArgb: Int = DEFAULT_BACKGROUND,
    val sprites: List<SpritePlacement> = emptyList(),
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
 *    要垫图请用 [ScreenScene.sprites] 给一个 `zdepth = Int.MIN_VALUE` 的摆位。
 * 2. 立绘：按 zdepth 降序与文本归并绘制（`:1834-1848`），落位下沿锚定（`:1846`），
 *    选中之上换 `ImgB`（`:1840-1842`）—— 本类用一个 [SpritePlacement] 名字换另一个名字表达，
 *    不做 `ImgB` 字段（本波 `:830-838` 的选中态只有引用比较，没有像素判据可抄）。
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
 */
class ScreenComposer(
    val clientWidthCells: Int,
    val heightLines: Int,
    val metrics: CellMetrics = CellMetrics.DEFAULT,
    val canvasHeightPx: Int = heightLines * metrics.lineHeight,
) {

    init {
        require(clientWidthCells >= 1) { "clientWidthCells 必须至少 1 半角格，实到 $clientWidthCells" }
        require(canvasHeightPx >= 0) { "canvasHeightPx 不能为负，实到 $canvasHeightPx" }
    }

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
     */
    fun compose(
        commands: List<DrawCmd>,
        rowCount: Int,
        scene: ScreenScene = ScreenScene(),
        sprites: SpriteTable = SpriteTable(),
        focusedButton: HitTarget? = null,
    ): Raster = composeInto(
        target = Raster(canvasWidthPx, canvasHeightPx),
        commands = commands,
        rowCount = rowCount,
        scene = scene,
        sprites = sprites,
        focusedButton = focusedButton,
    )

    /**
     * 整屏重绘到**调用方持有的画布**上（结果与 [compose] 逐像素相同）。
     *
     * 存在的理由是分配：一帧一张 1800 × 954 的 [Raster] 就是 6.9 MB 的 `IntArray`，
     * 60 fps 下等于每秒 400 MB 的垃圾 —— 真机上必然掉帧。持有画布的入口是
     * `view/ConsoleFrame`（[compose] 仍是「新建一张」的便捷形态，行为不变）。
     *
     * @param target 目标画布，尺寸必须与 [canvasWidthPx] / [canvasHeightPx] 相等
     * @return [target] 本身（便于链式读取像素）
     */
    fun composeInto(
        target: Raster,
        commands: List<DrawCmd>,
        rowCount: Int,
        scene: ScreenScene = ScreenScene(),
        sprites: SpriteTable = SpriteTable(),
        focusedButton: HitTarget? = null,
    ): Raster {
        requireCanvas(target)
        target.fillRect(0, 0, canvasWidthPx, canvasHeightPx, scene.backgroundArgb)
        // 无立绘时跳过排序与两次归并遍历（语料的常态帧），绘制序不变。
        if (scene.sprites.isEmpty()) {
            var i = 0
            while (i < commands.size) {
                drawRun(target, commands[i], rowCount, focusedButton)
                i++
            }
            return target
        }
        val ordered = orderByZdesc(scene.sprites)
        var i = 0
        while (i < ordered.size) {
            val placement = ordered[i]
            if (placement.zdepth >= 0) {
                drawSprite(target, placement, sprites)
            }
            i++
        }
        i = 0
        while (i < commands.size) {
            drawRun(target, commands[i], rowCount, focusedButton)
            i++
        }
        i = 0
        while (i < ordered.size) {
            val placement = ordered[i]
            if (placement.zdepth < 0) {
                drawSprite(target, placement, sprites)
            }
            i++
        }
        return target
    }

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
     * `view/ConsoleFrame` 负责这三条判定。
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
    }
}
