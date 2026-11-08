package com.eratw.port.render.adapter

import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic

/**
 * 「图块」收拢：把屏上**比窗口还高**的图片按块高预算等比缩小，并**删掉多余行**。
 *
 * 两趟，各治一种语料形态：
 * - [mergeBandRuns]：连续若干**纯图片行**（4.980 首页标题图 = 35 条 1041×16 横带）合成一张图；
 * - [collapseOversizedImage]：**单张超高图**（4.981 首页标题图 = 一张 `height='3500'` 的图）
 *   缩到同一预算，并塌掉脚本为它腾出的空行。
 *
 * ## 为什么需要
 *
 * eratw 的首页标题图是 `SPRITECREATE "TW_titleNNN", RM_TITLE, 0, N*16, 1041, 16` 切出的
 * 35 条 1041×16 横带，脚本用 35 次 `HTML_PRINT "<p align='center'><img src='TW_title…'>"`
 * 逐行铺出来（`TITLE.ERB:67-69`）。本端口的排版是**等高行**（`ScreenComposer.rowTopPx` 用
 * 固定 `lineHeight`），每行图片高度必须等于行高 ⇒ 整块 35×行高 ≈ 1440px，比手机屏还高，
 * 玩家只能看到被裁掉的一条；横带还被夹宽拉成扁平，纵横比也不对。
 *
 * 合并后：整块变**一张**图（按「可用宽 / 容许块高」取等比比例，绝不外扩），行数收窄到
 * `ceil(缩放后高 / 行高)`，多余的图块行**整行删掉** —— 屏上内容因此变短，整张标题图就能
 * 完整落在可视区内、并按原行的 [RenderLine.align]（脚本用 `<p align='center'>`）居中。
 *
 * 删行只影响**显示快照**：脚本侧的 `LINECOUNT` / `CLEARLINE` 走 `LineBufferConsoleSink`
 * 自己的行数，与这里无关（见 `ConsoleFeed.publish` 的调用点）。
 *
 * ## 判据（宁可漏合，不可错合）
 *
 * - 连续 ≥ [minBands] 行；
 * - 每行**只有一段** run，`kind = IMAGE`、`graphic` 非空（图形段的 `text` 是占宽用的空格
 *   负载，所以不能拿「文本为空」当判据）；
 * - 各段 `widthPx / heightPx / yOffsetPx` 完全一致；
 * - 每段必须是**扁横条**（`widthPx >= heightPx * [MIN_BAND_ASPECT]`）：标题图的带是
 *   1041×16（约 65:1）。立绘/头像那样的近方形块即使连续 8 行也不合并 —— 它们不是
 *   同一张图切出来的横带，缩成一块会毁版面。
 */
object ImageBandMerge {

    /**
     * 参与合并的横条最小宽高比。
     *
     * 取 4：正常行内图（图标、立绘）宽高比多在一倍上下，标题横带是几十倍；这条把
     * 「连续 8 行同尺寸立绘」这类误合挡在门外（宁可漏合，不可错合）。
     */
    private const val MIN_BAND_ASPECT = 4

    private class Band(val widthPx: Int, val heightPx: Int, val yOffsetPx: Int)

    /**
     * 合并块的承载行：一个半角空格 + `isLineEnd=true`。
     *
     * 不能是零 run 行：`LineFolder.fold` 对零 run 返回空表，`appendMerged` 又会并入
     * 未收尾行，两条都会让承载行在屏上消失，图就压到下面的文字行上。
     */
    private val SPACER: RenderLine = RenderLine(
        runs = listOf(TextRun(" ", 0xC0C0C0, null, 0, "ＭＳ ゴシック")),
        isLogicalLine = false,
        isLineEnd = true,
    )

    private fun bandOf(line: RenderLine): Band? {
        if (line.runs.size != 1) return null
        val run = line.runs[0]
        if (run.kind != RunKind.IMAGE) return null
        val g = run.graphic as? InlineGraphic.Image ?: return null
        if (g.heightPx <= 0 || g.widthPx < g.heightPx * MIN_BAND_ASPECT) return null
        return Band(g.widthPx, g.heightPx, g.yOffsetPx)
    }

    /**
     * @param lines 已展平的行
     * @param lineHeightPx 行高（像素）
     * @param cellPixelWidth 半角格宽（像素；算图形占宽格数）
     * @param maxBlockHeightPx 整块**绘制后**允许的最大高度（像素，通常取可视区高的一部分）；<=0 表示不按高限
     * @param availWidthPx 可用宽度（像素）
     * @param minBands 至少连续多少行才当作竖带图块
     */
    fun merge(
        lines: List<RenderLine>,
        lineHeightPx: Int,
        cellPixelWidth: Float,
        availWidthPx: Int,
        maxBlockHeightPx: Int,
        minBands: Int = 8,
    ): List<RenderLine> = collapseOversizedImage(
        mergeBandRuns(lines, lineHeightPx, cellPixelWidth, availWidthPx, maxBlockHeightPx, minBands),
        lineHeightPx,
        cellPixelWidth,
        availWidthPx,
        maxBlockHeightPx,
    )

    /** [merge] 的第一趟：连续扁横带合成一张等比缩小的图（判据见类文档）。 */
    private fun mergeBandRuns(
        lines: List<RenderLine>,
        lineHeightPx: Int,
        cellPixelWidth: Float,
        availWidthPx: Int,
        maxBlockHeightPx: Int,
        minBands: Int,
    ): List<RenderLine> {
        if (lines.size < minBands) return lines
        var out: ArrayList<RenderLine>? = null
        var i = 0
        while (i < lines.size) {
            val first = bandOf(lines[i])
            if (first == null) {
                out?.add(lines[i])
                i++
                continue
            }
            var j = i + 1
            while (j < lines.size) {
                val b = bandOf(lines[j]) ?: break
                if (b.widthPx != first.widthPx || b.heightPx != first.heightPx || b.yOffsetPx != first.yOffsetPx) break
                j++
            }
            val count = j - i
            if (count < minBands) {
                if (out != null) for (k in i until j) out.add(lines[k])
                i = j
                continue
            }
            val naturalHeightPx = count * first.heightPx
            val scaleByWidth = if (first.widthPx > 0) availWidthPx.toFloat() / first.widthPx.toFloat() else 1f
            // 高限必须对**绘制后的自然高**取比例：`<img>` 缺 height 时每带高度 = FontSize，
            // 可以大于行高（真机 36 > 31）；拿 count*lineHeight 当分母会少缩 14% 左右，
            // 合并块仍然压到下面的文字行上。没有自然高（heightPx<=0）时不按高限。
            val scaleByHeight = if (maxBlockHeightPx > 0 && naturalHeightPx > 0) {
                maxBlockHeightPx.toFloat() / naturalHeightPx.toFloat()
            } else {
                1f
            }
            val scale = minOf(1f, scaleByWidth, scaleByHeight)
            if (scale >= 1f) {
                // 本来就放得下：原样保留这些行（避免无谓的布局抖动）
                if (out != null) for (k in i until j) out.add(lines[k])
                i = j
                continue
            }
            val drawWidth = (first.widthPx * scale + 0.5f).toInt().coerceAtLeast(1)
            val drawHeight = (naturalHeightPx * scale + 0.5f).toInt().coerceAtLeast(1)
            val rowsNeeded = ((drawHeight + lineHeightPx - 1) / lineHeightPx).coerceIn(1, count)
            val run = lines[i].runs[0]
            val src = run.graphic as InlineGraphic.Image
            // 每条带是**独立的一张切片 sprite**（TW_title000…034，各 1041×16）。
            // 只留第一张会把整块画成最上面 16px 一条，所以把整串资源名带下去，
            // 由绘制侧逐片堆叠（drawImage 的 bandResources 分支）。
            val bandResources = (i until j).map { k ->
                (lines[k].runs[0].graphic as InlineGraphic.Image).resource
            }
            val cells = InlineGraphic.cellsFor(drawWidth, cellPixelWidth)
            val merged = run.copy(
                text = " ".repeat(cells),
                graphic = InlineGraphic.Image(
                    resource = src.resource,
                    widthPx = drawWidth,
                    heightPx = drawHeight,
                    yOffsetPx = 0,
                    widthCells = cells,
                    buttonResource = src.buttonResource,
                    mappingResource = src.mappingResource,
                    altText = src.altText,
                    // 这一行只是整块的首行：绘制高度故意跨 [rowsNeeded] 行，后面的空行承载。
                    // 绘制侧看到 rowSpan > 0 就不会再按单行高把它压回去。
                    rowSpan = rowsNeeded,
                    bandResources = bandResources,
                ),
            )
            if (out == null) {
                // 首次遇到可合并块：把此前跳过的前缀行（非 band 行、或不足 minBands 的
                // band 段）原样补回。否则一旦本块成功合并、返回非 null 的 out 时，
                // 这段前缀会被整块丢弃 —— 首页标题图之前打印的文字行会凭空消失
                // （2026-10-26 深审 R9 render-B2）。
                out = ArrayList(lines.size)
                for (k in 0 until i) out.add(lines[k])
            }
            out.add(lines[i].copy(runs = listOf(merged)))
            // 图比行高矮时补空行承载；其余图块行**删掉**（屏上内容变短，图才落得进可视区）。
            //
            // 承载行必须**活得过折行与并入**：`LineFolder.fold` 对零 run 的行返回空表
            // （grid/LineFolder.kt:127-129），`ConsoleAdapter.appendMerged` 又会把
            // `isLineEnd=false` 的行并进上一行（adapter/ConsoleAdapter.kt:358-363）。
            // 所以这里给一个单空格 run + `isLineEnd=true`：既在日志里占住一行（页面行数
            // 因此把整块高度算进去，字号适应不会低估），又不会被折叠/并入吞掉。
            for (k in 1 until rowsNeeded) {
                out.add(SPACER)
            }
            i = j
        }
        return out ?: lines
    }

    /**
     * 第二趟：**单张超高图**按同一块高预算缩小，并把它自己腾出来的空行塌缩到缩后行数。
     *
     * ## 为什么需要（4.981 首页标题图整张不见）
     *
     * 4.981 把 4.980 那 35 条 `SPRITECREATE` 横带整段注释掉（`TITLE.ERB:179-183`），改成一张
     * `<img src='TW_title' height='3500'>`（`TITLE.ERB:61`）。裸数字在原版是**百分比**：
     * `ParseMixedNum` 只在带 `px` 后缀时置 `isPx`（spec: `Utils.cs:342-362`），`ToPixel` 那支
     * 走 `num * Config.FontSize / 100`（`:27-31`）⇒ 语料 18/18 档（`emuera.config:22-23`）下
     * 高 = 630 脚本 px = **整 35 行**，脚本紧接着 `PRINTFORML %"\n" * 34%` 给这 35 行腾位置。
     *
     * 端口为了让 136 列在手机上读得清，生效字号是 41（行高 43）而不是 18 ⇒ 同一个 3500% 在端口
     * 折成 **1435px**（`emitImage` 的百分比支按生效字号乘），比 1080 的屏还高；图行 + 脚本腾出的
     * 35 行 = 36 行 > 30 行窗口 ⇒ 图所在那一行整个落在**绘制**窗口之外
     * （`ConsoleAdapter.DRAW_EXTRA_ABOVE = 1` 只多带一行），而绘制命令只来自
     * `ConsoleScreen.visibleRows()` ⇒ 整张标题图**一条命令都不产生**。
     * 玩家看到的不是「图糊了」，是图没了（真机原话：「标题图片看不见，得上滑才能看到」）。
     *
     * 第一趟救不了这一支：合并判据要 ≥ [minBands] 行连续扁横带且宽高比 ≥ [MIN_BAND_ASPECT]，
     * 这里只有一行、且 2682:1435 ≈ 1.87。所以「标题图按屏高缩放」这条既有裁定（2026-10-30 方案 C）
     * 需要一个单图形态。
     *
     * ## 判据（只做脚本自己已经留了位置的那一支）
     *
     * - 行内**只有一个**图形段（混排「图 + 文字」不动，缩了会把同行文字一起推走）；
     * - 不是第一趟的产物（`rowSpan > 0` 或 `bandResources != null` 一律放行，幂等）；
     * - `heightPx > maxBlockHeightPx`：高过块高预算的图在手机上必然看不见；
     * - **其后紧跟至少一行空白**：那是脚本为这张图腾的位置，本趟只在这块位置里动手。
     *   没有预留空行就一行不动 —— 原版让图溢出压到后面内容上（`ConsoleImagePart.cs:82` 的
     *   `destRect` + `EmueraConsole.cs:1855` 的恒定行距），端口不替它发明版面。
     *
     * 语料实测（4.981 全库 `<img>` 的 height 只有 200 / 900 / 1000 / 3500 四档）：1000% 那族
     * 折成 410px、900% 折成 369px，都低于真机 648px 的块高预算 ⇒ 一行都不动；只有标题图过线。
     *
     * @param lines 第一趟的产物
     */
    private fun collapseOversizedImage(
        lines: List<RenderLine>,
        lineHeightPx: Int,
        cellPixelWidth: Float,
        availWidthPx: Int,
        maxBlockHeightPx: Int,
    ): List<RenderLine> {
        if (maxBlockHeightPx <= 0 || lineHeightPx <= 0) return lines
        var out: ArrayList<RenderLine>? = null
        var i = 0
        while (i < lines.size) {
            val image = oversizedImageOf(lines[i], maxBlockHeightPx)
            if (image == null) {
                out?.add(lines[i])
                i++
                continue
            }
            var j = i + 1
            while (j < lines.size && isBlankLine(lines[j])) j++
            if (j == i + 1) {
                // 没有预留空行：这张图不是「脚本给它留了位置的一块」，原样放行。
                out?.add(lines[i])
                i++
                continue
            }
            val scale = minOf(
                1f,
                maxBlockHeightPx.toFloat() / image.heightPx.toFloat(),
                if (availWidthPx > 0) availWidthPx.toFloat() / image.widthPx.toFloat() else 1f,
            )
            val drawWidth = (image.widthPx * scale + 0.5f).toInt().coerceAtLeast(1)
            val drawHeight = (image.heightPx * scale + 0.5f).toInt().coerceAtLeast(1)
            val rowsNeeded = ((drawHeight + lineHeightPx - 1) / lineHeightPx).coerceAtLeast(1)
            val cells = InlineGraphic.cellsFor(drawWidth, cellPixelWidth)
            if (out == null) {
                out = ArrayList(lines.size)
                for (k in 0 until i) out.add(lines[k])
            }
            out.add(
                lines[i].copy(
                    runs = listOf(
                        lines[i].runs[0].copy(
                            text = " ".repeat(cells),
                            graphic = image.copy(
                                widthPx = drawWidth,
                                heightPx = drawHeight,
                                widthCells = cells,
                                // 跨行数交给绘制侧：`artRowScale` 的第一条守卫据此不按单行高压它，
                                // `RowLayout.compressible` 的第三条据此把这行排除出纯图行压缩。
                                rowSpan = rowsNeeded,
                            ),
                        ),
                    ),
                ),
            )
            // 预留空行只留缩后真正需要的那几行，其余删掉（屏上内容变短，图行才落得进窗口）；
            // 预留不够时补承载行，形态与第一趟同口径（见 [SPACER] 的注释）。
            for (k in 1 until rowsNeeded) {
                out.add(SPACER)
            }
            i = j
        }
        return out ?: lines
    }

    /** 本趟要处理的那一行：单段纯图片、不是第一趟产物、且高过块高预算；否则 null。 */
    private fun oversizedImageOf(line: RenderLine, maxBlockHeightPx: Int): InlineGraphic.Image? {
        if (line.runs.size != 1) return null
        val run = line.runs[0]
        if (run.kind != RunKind.IMAGE) return null
        val g = run.graphic as? InlineGraphic.Image ?: return null
        if (g.rowSpan > 0 || g.bandResources != null) return null
        if (g.heightPx <= maxBlockHeightPx) return null
        return g
    }

    /** 空白行（零 run，或全部是空白文本 run）：脚本给超高图腾位置的就是这种行。 */
    private fun isBlankLine(line: RenderLine): Boolean =
        line.runs.all { it.kind == RunKind.TEXT && it.text.isBlank() }
}
