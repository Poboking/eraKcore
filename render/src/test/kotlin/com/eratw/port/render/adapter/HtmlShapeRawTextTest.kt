package com.eratw.port.render.adapter

import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `HTML_PRINT` 里 `<shape>` 的展平形态覆盖（真机「进度条位置显示裸 HTML」回归）。
 *
 * 真机现场：eratw 的「少女祈祷中」加载条由 `@PRINT_PROGRESS_BAR`（`Visual_Effects_&_Animations.ERB:156`）
 * 经 `@HTML_RECT_BAR`（`UI_Components_&_Tables.ERB:697`）拼出
 * `<shape type='rect' param='0,Y,W,H' color='#RRGGBB'>` 后 `HTML_PRINT`；用户看到的是**裸标签文本**
 * 而不是色块条 —— 这说明 [HtmlRunFlatten.emitShape] 走到了 `emitShapeAltText` 回落分支。
 *
 * 本测试把语料里实际出现的 `<shape>` 写法（`grep` 统计的 15 种形态：单参数/四参数、`px` 后缀、
 * `=` 两侧空格、负 y 偏移、空格分隔的 param、十六进制颜色）逐条喂给展平器，断言：
 * - 输出里**不出现**以 `<shape` 开头的 TEXT run（裸标签 = 回落分支被触发）；
 * - 每条都至少切出一个 SHAPE 图形段（宽 > 0）。
 */
class HtmlShapeRawTextTest {

    private val metrics = CellMetrics(18)

    private fun flatten(html: String): List<TextRun> {
        val flattener = HtmlRunFlatten(metrics)
        val run = TextRun(
            text = html,
            fore = 0xC0C0C0,
            back = 0x000000,
            styleMask = 0,
            fontName = "黑体",
            kind = RunKind.HTML,
        )
        return flattener.flatten(run)
    }

    private fun assertRenders(label: String, html: String) {
        val out = flatten(html)
        val raw = out.filter { it.kind == RunKind.TEXT && it.text.contains("<shape") }
        assertTrue(raw.isEmpty(), "$label：展平后仍残留裸 <shape> 文本：${raw.map { it.text }}")
        val shapes = out.filter { it.kind == RunKind.SHAPE }
        assertTrue(shapes.isNotEmpty(), "$label：没有切出 SHAPE 段（html=$html）")
        val width = shapes.sumOf { it.text.length }
        assertTrue(width > 0, "$label：SHAPE 段总占宽为 0")
    }

    @Test
    fun singleParamSpaceVariants() {
        assertRenders("space px", "<shape type='space' param='100px'>")
        assertRenders("space 裸数", "<shape type='space' param='100'>")
        assertRenders("space = 两侧空格", "<shape type = 'space' param = '100px'>")
        // 负占位（语料 3 处，XRAY 叠图用）：原版 Space 的 Width 就是负值（ConsoleSpacePart.cs:23-33），
        // 展平后必须是**带负宽**的 Space 段，排版层据此回退列游标；折成 0 格会让叠图右移。
        val negative = flatten("<shape type='space' param='-54px'>")
        assertTrue(
            negative.none { it.kind == RunKind.TEXT && it.text.contains("<shape") },
            "负 px space：不得把标签当文本画出来",
        )
        val space = negative.single().graphic as InlineGraphic.Space
        assertTrue(space.widthCells < 0, "负 px space 必须保留负宽，实际 ${space.widthCells}")
        assertTrue(negative.single().text.isEmpty(), "负 space 不占文本格")
    }

    @Test
    fun singleParamRectVariants() {
        assertRenders("rect 单参数", "<shape type='rect' param='100'>")
        assertRenders("rect 单参数 px", "<shape type='rect' param='100px'>")
    }

    @Test
    fun fourParamRectVariants() {
        // 加载条的实际形态（PRINT_PROGRESS_BAR / HTML_RECT_BAR）。
        assertRenders("加载条 2400×64", "<shape type='rect' param='0,37,2400,64' color='#7070C0'>")
        // 全 px 形态（PRINT_RATE_BAR_EX 一脉）。
        assertRenders(
            "全 px 且负 y",
            "<shape type='rect' param='0,-35px,100px,25px' color='#112233'>",
        )
        // param 列表里带空格。
        assertRenders(
            "param 带空格",
            "<shape type='rect' param='0, 30, 1008, 64' color='#FFD700'>",
        )
        // 语料里 `param='N , N , {N} , N'` 的写法。
        assertRenders(
            "逗号两侧空格",
            "<shape type='rect' param='0 , 37 , 2400 , 64' color='#00FFCC'>",
        )
        // 大字面色（INT_TO_RGB 产物是 #RRGGBB）。
        assertRenders("大分红", "<shape type='rect' param='0,40,40,25' color='#D70000'>")
    }

    @Test
    fun rectInsideParagraphKeepsTextAndBar() {
        // 加载条整行：前导空格 + 多个 shape + 文本（[ 42％] 少女祈祷中…）。
        val html = "<p align='left'>" +
            "<shape type='rect' param='0,37,1008,64' color='#7070C0'>" +
            "<shape type='rect' param='0,37,400,64' color='#202050'>" +
            "[ 42％] 少女祈祷中…</p>"
        val out = flatten(html)
        val raw = out.filter { it.kind == RunKind.TEXT && it.text.contains("<shape") }
        assertTrue(raw.isEmpty(), "整行：残留裸 <shape> 文本")
        // 两个色块（同 y/同高）现在合成**一个**带渐变的段：像素级平滑，占宽仍按格算。
        assertTrue(out.count { it.kind == RunKind.SHAPE } >= 1, "整行：色块要成段（合并后至少一段）")
        assertTrue(
            out.any { it.kind == RunKind.TEXT && it.text.contains("少女祈祷中") },
            "整行：条后面的人话文本不能被吞",
        )
    }

    @Test
    fun shapeColorIsOpaqueArgb() {
        // 2026-10-23 云真机实拍：「少女祈祷中」进度条只剩 [100%] 文本、彩色条消失。
        // 根因是 colorOf 返回 RGB（0x00RRGGBB），drawRect 拿到 alpha=0 画成全透明。
        // 这里钉住「展平后的 shape 颜色必须是不透明 ARGB」。
        val out = flatten("<shape type='rect' param='0,37,2400,64' color='#7070C0'>")
        val rect = out.mapNotNull { it.graphic as? com.eratw.port.render.displaylist.InlineGraphic.Rect }.firstOrNull()
        assertTrue(rect != null, "没有切出 Rect 段")
        kotlin.test.assertEquals(
            0xFF7070C0.toInt(),
            rect!!.colorArgb,
            "shape 颜色必须补成不透明 ARGB（否则 drawRect 全透明）",
        )
    }

    @Test
    fun unsupportedShapeDoesNotLeakRawTagWhenRenderableFormExists() {
        // 防御性：即便将来加入语料没有的形态，也不允许「裸标签当文本」这一支扩散到
        // 已支持形态（本用例只钉已支持形态不回落）。
        val out = flatten("<shape type='rect' param='0,37,2400,64' color='#7070C0'>")
        assertFalse(
            out.any { it.kind == RunKind.TEXT && it.text.trimStart().startsWith("<shape") },
            "已支持形态不得走 emitShapeAltText 回落",
        )
    }
}
