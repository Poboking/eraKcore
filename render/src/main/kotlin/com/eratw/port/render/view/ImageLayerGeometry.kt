package com.eratw.port.render.view

/**
 * WIRE-3 / WIRE-4 的**纯 JVM 几何与颜色换算**：SETIMAGELAYERL 图层屏幕坐标 + TEXT_BGC 文字衬底。
 *
 * ## 为什么单独一层
 *
 * 绘制侧在 Android 模块（`:render-canvas`），Haruki 无 Android SDK ⇒ `CanvasConsoleView`
 * 的单测跑不了；而「图层落哪、衬底什么色」正是这两条语句上屏的全部判据。把算式放成纯函数后，
 * `:render` 的 JVM 用例可以直接钉数值，`CanvasConsoleView` 只负责把结果塞进
 * `Rect`/`RectF` 再 `drawBitmap` / `drawRect`（OD3：可测逻辑全部下沉 `:render`）。
 *
 * ## 图层公式（specs/isa.yaml:2512）
 *
 * - 横：`x = DrawingParam_ShapePositionShift + xpos`。移植侧 `shapeShiftPx` =
 *   [com.eratw.port.render.view.ShapePositionShift]，但那是 `:render-canvas` 的 `MappingColorGeometry`
 *   里的对象（Android 模块），故本函数只收一个已算好的 `shapeShiftPx: Int`（口径
 *   `Math.Max(2, FontSize / 6)`，`Config.cs:461-466`）。调用侧再补内容区左沿。
 * - 纵：`y = GetLinePointY(lineNo) + LineHeight - ClientHeight + (imageHeight - LineHeight) + ypos`
 *   （`specs/isa.yaml:2512`；原版 `Instraction.Child.cs:4199-4205` 不可本机取证，
 *   见 `specs/verify-notes.md` 23.16）。本函数**原样保留四项**（不提前把 LineHeight 抵消），
 *   以便逐个取自证；算术上等价于 `GetLinePointY - ClientHeight + imageHeight + ypos`。
 *
 * **IMG-A4 I3（2026-11-06）**：把原版 `GetLinePointY(lineNo)`（`EmueraConsole.cs:1786-1795`，
 * 窗口坐标的行顶 `MainPicBox.Height - LineHeight - (lastVisible - lineNo) * LineHeight`）
 * 代入坐标公式后，`ClientHeight` 与 `MainPicBox.Height` 两项严格相消 ⇒ 精确式
 * [layerTopPxExact] 不再需要行顶的绝对坐标（推导见该函数 KDoc）。
 * **IMG-A4 I4（2026-11-07）**：调用侧 `CanvasConsoleView.drawImageLayers` 已切到精确式
 * （传 [linesBelowAnchor] 而不是 `RowLayout.Plan.tops` 的行顶），[layerTopPx] /
 * [layerBoxPx] 保留为近似面（既有单测在册，别删）。
 *
 * 残留 TODO(verify)：① 坐标公式本体出自 fork 源（`Instraction.Child.cs:4199-4205`，
 * 本机 reference 快照无此文件，以 `specs/isa.yaml` 的 SETIMAGELAYERL 条目为 pinned 依据）；
 * ② 立绘行改高 / 滚动中两形态的真机视觉读数（卡点＝无设备）。
 *
 * ## 文字衬底（specs/isa.yaml:2456）
 *
 * `TEXT_BGC_ON rgb, opacity` ⇒ 衬底 = `0xRRGGBB`、`alpha = opacity% × 255 / 100`；
 * 只改**后续**文字段（不改已输出行）。`rgb < 0` 是清除哨兵，**不**在本层处理
 * （由 [GraphicsLayer.setTextBackground] 折成 null）。
 */
object ImageLayerGeometry {

    /**
     * 图层绘制框左沿（View 横坐标）：`contentLeftPx + shapeShiftPx + xpos`。
     *
     * @param contentLeftPx 内容区左沿（`paddingLeft`）
     * @param shapeShiftPx 生效字号档的 `DrawingParam_ShapePositionShift`
     * @param xpos 脚本 `xpos`（与 HTML img 同口径；画蛇添足版 `Flan_UI.ERB:148` 已从中
     *   扣除 `L_SHAPE_SHIFT`，本函数再补回同一项）
     */
    fun layerLeftPx(contentLeftPx: Float, shapeShiftPx: Int, xpos: Int): Float =
        contentLeftPx + shapeShiftPx + xpos

    /**
     * 图层绘制框上沿（View 纵坐标）：`rowTopPx + lineHeightPx - clientHeightPx +
     * (imageHeightPx - lineHeightPx) + ypos`。
     *
     * **IMG-A4 I3 起这是近似面**：`rowTopPx` 以 tops 充当窗口坐标的 `GetLinePointY`
     * 差一个内容区原点（见类 KDoc 的 IMG-A4 I3/I4 段）；精确式见 [layerTopPxExact] ——
     * 它在「行高等高 + 内容恰满一屏」时与本函数逐位同值（该等价性由
     * `ImageLayerGeometryTest` 的对拍用例钉住），非该形态（立绘行改高、滚动中）时以
     * 精确式为准。生产调用点已整个切走（IMG-A4 I4），本函数只留给既有单测。
     *
     * @param rowTopPx 锚定行的行顶（`specs/isa.yaml` 的 `GetLinePointY(lineNo)`，
     *   移植侧近似取 [RowLayout.Plan.tops]；见类 KDoc 的 IMG-A4 I3 段）
     * @param lineHeightPx 执行行高（`Config.LineHeight` 落地值）
     * @param clientHeightPx 客户区高（原版 `MainPicBox.Height`，`EmueraConsole.cs:232-235`；
     *   移植侧 `CanvasConsoleView.clientHeightPx()`）
     * @param imageHeightPx 图层实际绘制高（`height > 0` 用脚本值，否则回落图口帧高）
     * @param ypos 脚本 `ypos`
     */
    fun layerTopPx(
        rowTopPx: Float,
        lineHeightPx: Float,
        clientHeightPx: Float,
        imageHeightPx: Float,
        ypos: Int,
    ): Float = rowTopPx + lineHeightPx - clientHeightPx + (imageHeightPx - lineHeightPx) + ypos

    /**
     * 原版 `GetLinePointY(lineNo)` 的**精确**移植式（IMG-A4 I3）。
     *
     * 本地权威定义（`EmueraConsole.cs:1786-1795`，逐行）：
     *
     * ```
     * num  = window.MainPicBox.Height - Config.LineHeight;                    // 底行行顶（窗口坐标）
     * num2 = window.ScrollBar.Value - 1（夹到 displayLineList.Count - 1）;     // 最后一条可见逻辑行
     * return num - (num2 - lineNo) * Config.LineHeight;
     * ```
     *
     * 即「最后一条可见行的行顶 − 锚行到它的行距」。移植侧三项对应物：
     * `MainPicBox.Height` = [clientHeightPx]（`ClientHeight => window.MainPicBox.Height`，
     * `EmueraConsole.cs:232-235`）、`Config.LineHeight` = [lineHeightPx]、
     * `(num2 - lineNo)` = [linesBelowAnchor]（锚行之下仍在可见窗口里的**逻辑行**数 ——
     * 原版每行恰好一个 `LineHeight` 等高步进，`num2` 随滚动条变化 ⇒ 天然 followScroll）。
     */
    fun linePointYPx(clientHeightPx: Float, lineHeightPx: Float, linesBelowAnchor: Int): Float =
        clientHeightPx - lineHeightPx - linesBelowAnchor * lineHeightPx

    /**
     * 精确式的第四项 `(num2 - lineNo)` —— 锚行到**最后可见行**之间的逻辑行数。
     *
     * [lastVisibleLine] 与 [anchorLine] 必须是**同一个坐标系**的显示行下标（保留区/显示列表
     * 行下标，与 `RowLayout.Plan.tops` / `DrawCmd.row` 同一把尺）：
     * - [lastVisibleLine] = 原版 `num2`，即 `Viewport.bottomRingIndex()`
     *   （`num2 = ScrollBar.Value - 1` 夹到 `Count - 1`，`EmueraConsole.cs:1786-1795`）；
     * - [anchorLine] = 锚行在同一保留区的下标（绘制侧 `ConsoleScreen.visibleRowBase` + 绘制行号）。
     *
     * 差为负 = 锚行落到最后可见行**之下**（滚动到历史时锚行在视口外）。原版此时
     * `GetLinePointY` 返回大于底行行顶的值，图层整块落在可视区下方被裁掉；本函数按调用约定
     * 夹到 0（图层贴齐最后可见行），取舍登记在调用侧
     * `CanvasConsoleView.drawImageLayers` 的 TODO(verify)。
     */
    fun linesBelowAnchor(lastVisibleLine: Int, anchorLine: Int): Int =
        (lastVisibleLine - anchorLine).coerceAtLeast(0)

    /**
     * 图层绘制框上沿的**精确式**（IMG-A4 I3，tops 近似的代数闭合面）：
     *
     * ```
     * y = GetLinePointY(lineNo) + LineHeight - ClientHeight + (imageHeight - LineHeight) + ypos
     * ```
     *
     * 坐标公式本体出自 fork 源（`Instraction.Child.cs:4199-4205`，本机 reference 快照无此
     * 文件，公式文本以 `specs/isa.yaml` SETIMAGELAYERL 条目为 pinned 依据）。把本地可证的
     * `GetLinePointY` 展开（[linePointYPx]）代入后，`+LineHeight - ClientHeight` 与
     * `GetLinePointY` 里的 `(ClientHeight - LineHeight)` **严格相消**：
     *
     * ```
     * y = [(ClientHeight - LineHeight) - (num2 - lineNo)·LineHeight] + LineHeight - ClientHeight
     *     + (imageHeight - LineHeight) + ypos
     *   = imageHeight - LineHeight - (num2 - lineNo)·LineHeight + ypos
     * ```
     *
     * 精确式**不再引用锚行行顶的绝对坐标**（也不含窗口高）—— 只剩「锚行到最后一可见行
     * 的行距 × 行高」。tops 近似（[layerTopPx] 的 `rowTopPx`）差的就是这一步：tops 是
     * 内容区坐标，`GetLinePointY` 是窗口坐标，两者差一个内容区原点；精确式把这个原点
     * 代数消掉，调用方不再需要关心滚动/坐标系换算。
     *
     * 逐项对拍 C#：`imageHeight` = 脚本 height（>0）否则图口帧高（调用侧
     * `GraphicGeometry.inlineSize`）；`-LineHeight - (num2-lineNo)·LineHeight` =
     * `EmueraConsole.cs:1786-1795`（本地，逐行可证）；`+ypos` 与 HTML img 同口径。
     */
    fun layerTopPxExact(imageHeightPx: Float, lineHeightPx: Float, linesBelowAnchor: Int, ypos: Int): Float =
        imageHeightPx - lineHeightPx - linesBelowAnchor * lineHeightPx + ypos

    /**
     * 精确式的整框形态：x 走 [layerLeftPx]，y 走 [layerTopPxExact]。
     * 生产调用点 = `CanvasConsoleView.drawImageLayers`（IMG-A4 I4 起；`linesBelowAnchor` 由
     * [linesBelowAnchor] 从视口最后可见行求差得出，不再读 `RowLayout.Plan.tops` 的行顶）。
     */
    fun layerBoxPxExact(
        contentLeftPx: Float,
        imageHeightPx: Float,
        lineHeightPx: Float,
        linesBelowAnchor: Int,
        shapeShiftPx: Int,
        xpos: Int,
        ypos: Int,
        targetWidthPx: Float,
        targetHeightPx: Float,
    ): FloatArray {
        val x = layerLeftPx(contentLeftPx, shapeShiftPx, xpos)
        val y = layerTopPxExact(imageHeightPx, lineHeightPx, linesBelowAnchor, ypos)
        return floatArrayOf(x, y, x + targetWidthPx, y + targetHeightPx)
    }

    /**
     * 一次算出图层的绘制框 `(x, y, w, h)`（View 坐标），供 `CanvasConsoleView` 直接落 `RectF`。
     *
     * [targetWidthPx]/[targetHeightPx] 由调用侧按 `GraphicGeometry.inlineSize` 口径定好
     * （脚本给了正数就用脚本的，否则退图口帧尺寸）。
     */
    fun layerBoxPx(
        contentLeftPx: Float,
        rowTopPx: Float,
        lineHeightPx: Float,
        clientHeightPx: Float,
        imageHeightPx: Float,
        shapeShiftPx: Int,
        xpos: Int,
        ypos: Int,
        targetWidthPx: Float,
        targetHeightPx: Float,
    ): FloatArray {
        val x = layerLeftPx(contentLeftPx, shapeShiftPx, xpos)
        val y = layerTopPx(rowTopPx, lineHeightPx, clientHeightPx, imageHeightPx, ypos)
        return floatArrayOf(x, y, x + targetWidthPx, y + targetHeightPx)
    }

    /**
     * 文字衬底的 ARGB：`alpha = opacityPercent × 255 / 100`（整数除法，`specs/isa.yaml:2456`），
     * 颜色取 [rgb] 的低 24 位（`0xRRGGBB`）。`opacityPercent` 夹到 0..100 防越界。
     *
     * 原版把 `(rgb<<8)+(alpha&0xFF)` 折叠后交给 `Color.FromArgb(int)` 装载期实现；本层按
     * 「颜色整数 + 不透明度百分比」两参原样收下（core 层同口径），折算在此一处完成。
     */
    fun textBackgroundArgb(rgb: Int, opacityPercent: Int): Int {
        val alpha = (opacityPercent.coerceIn(0, 100) * 255) / 100
        return (alpha shl 24) or (rgb and 0xFFFFFF)
    }

    /**
     * 一段文字衬底的绘制矩形 `(left, top, right, bottom)`（View 坐标）—— 直接取该文字段的
     * 像素矩形的四个角，语义上等价于「给这段字铺底色」。纯几何无换算，仅为把落点算式
     * 收进可测单元（`CanvasConsoleView` 不自己拼 `RectF`）。
     */
    fun textBackgroundRect(
        leftPx: Float,
        topPx: Float,
        rightPx: Float,
        bottomPx: Float,
    ): FloatArray = floatArrayOf(leftPx, topPx, rightPx, bottomPx)
}