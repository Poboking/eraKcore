package com.eratw.port.render.adapter

import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic

/**
 * 「竖带图块」合并：把连续若干**纯图片行**合成一张图、等比缩小，并**删掉多余行**。
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
        runs = listOf(TextRun(" ", 0xC0C0C0, 0x000000, 0, "ＭＳ ゴシック")),
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
}
