package com.eratw.port.render.view

import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic

/**
 * 逐行纵布局（行顶 / 行高 / 纯图行标记）—— 缺陷 A 的修法落点。
 *
 * ## 为什么**恒按窗口行数**建行，而不是按「有没有绘制命令」
 *
 * 原版绘制循环 `EmueraConsole.cs:1852-1856` 是这样的：
 *
 * ```
 * for (int i = num3; i <= num2 && i < displayLineList.Count; i++) {
 *     displayLineList[i].DrawTo(graph, num, ...);
 *     num += Config.LineHeight;                       // :1855
 * }
 * ```
 *
 * `num3..num2` 是**窗口里的每一条显示行**，`num += Config.LineHeight` 与这一行有没有内容
 * **无关**：空行照样吃一个行高。所以一张向下溢出的立绘，它的身体落在后面那些「脚本预留的
 * 空行」占的那几排里（`@PRINT_IMAGE` 打完 `<img height='1000'>` 会用
 * `HTML_PRINT "<br>"` 预留 9 条纯标签空行 —— 语料 `ERB/ステータス表示関連/IMAGE.ERB:528`
 * 与 `:548-549`，只读）。
 *
 * 端口此前在 `CanvasConsoleView` 里按 `cmds` 的 `minRow..maxRow` 极值建行
 * （旧 `rowMetrics`，main@515d8cf5 的 `:759-765`）：一条命令都没有的行**根本不进表**。
 * 纯 `<br>` 行的展平产物是 `text = ""` 的占位段（`adapter/HtmlRunFlatten.kt:226-231`），
 * 而 `DisplayList.prepare` 对 `cells <= 0` 的段直接 `continue`
 * （`displaylist/DisplayList.kt:532-535`）⇒ 预留块那 9 行**零命令** ⇒ 整排从表里掉出去
 * ⇒ 图行成了表里最后一行、被底对齐贴死在 `contentBottom` 上，360px 的身体有 322px
 * 压在可视区下方被 `clipRect` 裁掉 = 玩家报的「动图落在屏的底边以下」
 * （实测读数见交付消息与修复前的 `render/build/probe-anime.txt`）。
 *
 * 本表改成按**窗口行数**建行：绘制侧传的是 `ConsoleScreen.list.rowCount`（保留列表的行数，
 * 与 [DrawCmd.row] 同一坐标系；容量够时与 `ConsoleScreen.visibleRows` 的大小恒等，
 * 超容量丢头行时仍与命令下标对齐）。这也正是上面那段 C# 的算式，与同仓纯 JVM 侧的参考实现
 * [ScreenComposer.rowTopPx]（`view/ScreenComposer.kt:142`
 * = `canvasHeightPx - (rowCount - row) * metrics.lineHeight`，`rowCount` 取的正是
 * `ConsoleScreen.visibleRows().size`）同一口径。
 * 无压缩（`artAdvance == lineHeightPx`）时两条逐值相同，钉在 `RowLayoutTest` 的
 * 「无压缩时与等距原点逐值相同」。
 *
 * ## 下标坐标系（硬契约）
 *
 * [Plan.tops] / [Plan.heights] / [Plan.artOnly] 的下标 = **绘制口径行号**
 * = `DrawCmd.row` = [ConsoleScreen.visibleRows] 的下标。这条坐标系契约在
 * [ScreenComposer.compose] 的 KDoc（`view/ScreenComposer.kt:147-149`）已经钉死，
 * 本类**不再有 `minRow` 这个概念**（旧实现里「快照行 = minRow + idx」那层平移整体消失）。
 * 绘制侧的越界闸改成直接对 `cmd.row` 判 `in tops.indices`。
 *
 * ## 标题图的等分压缩（保留旧行为，防标题页回归）
 *
 * eratw 标题图是 35 条 1041×16 横带逐行 `<img>` 打印，行数 × 行高在可读字号下远超屏高。
 * 旧实现把「纯图片行」（该行只有 `InlineGraphic.Image`、没有文字）的行高按剩余高度等分
 * 压缩，文字行保持 [lineHeightPx] 不动，整块内容仍底对齐。`artAdvance` 那一支的算式
 * 原样搬进本类（`remain` / `fit` / `coerceIn`），四条守卫与出处见
 * [GraphicGeometry.artRowScale]，本类不重复判式。
 *
 * 纯 JVM：不碰 Android API、不碰 `kotlin.random`、无可变静态状态；返回的 [Plan] 是
 * 一次性只读表，每帧由调用方（`CanvasConsoleView.drawScreen`）重建。
 *
 * @see GraphicGeometry.artRowScale 压缩后图要不要跟着缩
 * @see ScreenComposer.rowTopPx 同一件事在纯 JVM 合成侧的算式
 */
object RowLayout {

    /**
     * 一帧的行表。三个数组**等长**（= 窗口行数），下标即绘制口径行号。
     *
     * @property tops 各行行顶（内容区坐标，像素）
     * @property heights 各行行高（像素：文字行 = 行高，纯图行可能被压缩）
     * @property artOnly 该行是否**只有图片段**（没有文字）：这种行的图可随 [heights] 压缩绘制
     */
    class Plan(
        val tops: FloatArray,
        val heights: FloatArray,
        val artOnly: BooleanArray,
    ) {
        /** 屏幕 y 落在第几行（0 基，绘制口径行号）；都不落在区间内返回 -1。 */
        fun rowIndex(y: Float): Int {
            var i = 0
            while (i < tops.size) {
                if (y >= tops[i] && y < tops[i] + heights[i]) return i
                i++
            }
            return -1
        }
    }

    /**
     * 出行表。
     *
     * @param cmds 本帧的绘制命令（`DisplayList.renderInto` 的产物；只读 `row` / `graphic`）
     * @param windowRows 窗口行数（绘制侧传 `ConsoleScreen.list.rowCount`，与 [DrawCmd.row]
     *   同坐标系）；非正 ⇒ 空表
     * @param contentTop 内容区上沿（像素）
     * @param contentBottom 内容区下沿（像素）；底对齐的基准
     * @param lineHeightPx 执行行高（`Config.LineHeight` 的落地值）
     * @param minArtRowHeightPx 纯图行压缩的下限（旧 `CanvasConsoleView.MIN_ART_ROW_HEIGHT`）
     */
    fun plan(
        cmds: List<DrawCmd>,
        windowRows: Int,
        contentTop: Float,
        contentBottom: Float,
        lineHeightPx: Float,
        minArtRowHeightPx: Float,
    ): Plan {
        // 行表长度**恒为窗口行数**：零命令行也建行（见类文档；原版 EmueraConsole.cs:1855
        // 的 `num += Config.LineHeight` 与该行有无内容无关）。minRow 这个概念在这里消失。
        if (windowRows <= 0) return Plan(FloatArray(0), FloatArray(0), BooleanArray(0))
        val n = windowRows
        val isText = BooleanArray(n)
        val imageHeight = FloatArray(n) { -1f }
        for (c in cmds) {
            // 下标即绘制口径行号（= DrawCmd.row = visibleRows() 的下标），不再平移。
            // 窗口外的命令不进表：绘制侧本来就有 `in tops.indices` 的越界闸拦着。
            val i = c.row
            if (i < 0 || i >= n) continue
            val g = c.graphic
            if (g is InlineGraphic.Image) {
                if (g.heightPx > imageHeight[i]) imageHeight[i] = g.heightPx.toFloat()
            } else {
                isText[i] = true
            }
        }
        // 归并口径与旧 rowMetrics 逐值相同：图行取该行最大 heightPx，Image 之外的 graphic
        // 算 text（旧实现 :771-775）。**没有任何命令的行 = 普通文字行**（旧实现对
        // 「minRow..maxRow 区间内空行」就是这么算的，:784 的 else 支 + :797），
        // 只是现在头部/尾部的空行也在表里。
        var textRows = 0
        var artRows = 0
        val artOnly = BooleanArray(n) { i -> !isText[i] && imageHeight[i] > 0f }
        for (i in 0 until n) {
            when {
                isText[i] -> textRows++
                imageHeight[i] > 0f -> artRows++
                else -> textRows++
            }
        }
        val available = (contentBottom - contentTop).coerceAtLeast(0f)
        var artAdvance = lineHeightPx
        if (artRows > 0) {
            val remain = available - textRows * lineHeightPx
            val fit = if (remain > 0f) remain / artRows else minArtRowHeightPx
            artAdvance = fit.coerceIn(minArtRowHeightPx, lineHeightPx)
        }
        val heights = FloatArray(n) { i ->
            if (!isText[i] && imageHeight[i] > 0f) artAdvance else lineHeightPx
        }
        var total = 0f
        for (h in heights) total += h
        val tops = FloatArray(n)
        var y = contentBottom - total
        for (i in 0 until n) {
            tops[i] = y
            y += heights[i]
        }
        return Plan(tops, heights, artOnly)
    }
}
