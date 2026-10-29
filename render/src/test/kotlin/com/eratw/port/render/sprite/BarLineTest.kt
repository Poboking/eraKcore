package com.eratw.port.render.sprite

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `DRAWLINE` / `CUSTOMDRAWLINE` / `DRAWLINEFORM` 整行生成对拍黄金基准。
 *
 * 期望值全部取自 `fixtures/golden/baseline/pc-golden-root.txt` 的
 * `=== SECTION:DRAWLINE ===` 分节（`:252-255`，由 `GOLDEN_02_PRINT.ERB:181-184`
 * 的 `CUSTOMDRAWLINE =` / `DRAWLINE` / `DRAWLINEFORM -{1+1}` 产生），
 * 归一化规则取 `docs/golden-runbook.md` §5（`:135-136`）。
 */
class BarLineTest {

    private fun baselineLines(): List<String> {
        val dir = sequenceOf(File("fixtures/golden"), File("../fixtures/golden")).first { it.exists() }
        return dir.resolve("baseline/pc-golden-root.txt").readLines(Charsets.UTF_8)
    }

    /** golden-runbook §5 的两条折叠规则（`=` 与 `-` 的 10 连以上整行 → 标记）。 */
    private fun normalize(line: String): String = when {
        line.matches(Regex("={10,}")) -> "LINE(=)"
        line.matches(Regex("-{10,}")) -> "LINE(-)"
        else -> line
    }

    /** 取基准 `=== SECTION:DRAWLINE ===` 之后三行（`=` 串、`-` 串、`-2` 串）。 */
    private fun baselineBarSection(): Triple<String, String, String> {
        val lines = baselineLines()
        val idx = lines.indexOf("=== SECTION:DRAWLINE ===")
        assertTrue(idx >= 0, "基准缺少 DRAWLINE 分节")
        // 基准第 252 行是分节头（1 基），即 0 基下标 251
        assertEquals(251, idx, "DRAWLINE 分节头行号与基准对不上")
        return Triple(lines[idx + 1], lines[idx + 2], lines[idx + 3])
    }

    @Test
    fun `宽度1800下等号与减号生成长度均为199并逐字符等于基准`() {
        val (eqLine, minusLine, formLine) = baselineBarSection()
        val gen = DrawLineBar() // clientWidth=1800（Grid.DEFAULT_CLIENT_WIDTH，基准 :689）
        val built = gen.build("=")   // CUSTOMDRAWLINE =（FunctionIdentifier.cs:522 解析期生成）
        val defBar = gen.defaultBar  // DRAWLINE（stBar：Process.cs:259 + EmueraConsole.cs:3388-3393）
        val form = gen.build("-2")   // DRAWLINEFORM -{1+1}（Process.cs:1162-1169 运行期重算）
        assertEquals(199, eqLine.length, "基准等号串长度")
        assertEquals(199, minusLine.length, "基准减号串长度")
        assertEquals(199, formLine.length, "基准 -2 串长度")
        assertEquals(eqLine, built)
        assertEquals(minusLine, defBar)
        assertEquals(formLine, form)
    }

    @Test
    fun `归一化后等于基准的LINE标记`() {
        val (eqLine, minusLine, _) = baselineBarSection()
        val gen = DrawLineBar()
        assertEquals("LINE(=)", normalize(gen.build("=")))
        assertEquals(normalize(eqLine), normalize(gen.build("=")))
        assertEquals("LINE(-)", normalize(gen.defaultBar))
        assertEquals(normalize(minusLine), normalize(gen.defaultBar))
    }

    @Test
    fun `可绘制宽度模型与半角步进由基准钉死`() {
        val gen = DrawLineBar()
        // shift = max(2, 18/6) = 3（Config.cs:461-465）；drawable = 1800 - 3（Config.cs:466）
        assertEquals(3, gen.shapeShift)
        assertEquals(1797, gen.drawableWidth)
        // 步进 9 是唯一整数解：199*9 = 1791 <= 1797 < 1800 = 200*9（基准 :253 的 199 字符反推）
        assertEquals(1791, 199 * gen.halfCellPx)
        assertTrue(199 * gen.halfCellPx <= gen.drawableWidth && 200 * gen.halfCellPx > gen.drawableWidth)
    }

    @Test
    fun `减号2串按官方逐字符裁剪在单元中间截断`() {
        val (_, _, formLine) = baselineBarSection()
        // 基准实测：99 个「-2」+ 落单「-」（EmueraConsole.cs:3431-3435 的按 char 删除循环）
        assertEquals("-2".repeat(99) + "-", formLine)
        val gen = DrawLineBar()
        assertEquals(formLine, gen.build("-2"))
    }

    @Test
    fun `空重复串按原版抛错`() {
        // printCustomBar 空串 CodeEE（EmueraConsole.cs:3398-3400）
        assertFailsWith<IllegalStateException> { DrawLineBar().build("") }
    }

    @Test
    fun `其他宽度下的算法自洽性`() {
        // 复刻算法（EmueraConsole.cs:3420-3437）在整数网格模型下的宽度扫描：
        // drawable = 900 - 3 = 897，半角 9px：9*99 = 891 <= 897 < 900 = 9*100 → 99 字符
        val gen = DrawLineBar(clientWidth = 900)
        assertEquals(897, gen.drawableWidth)
        assertEquals(99, gen.build("-").length)
        // 单元数与半角同式：floor(drawable / 单元px)（取整方向由 getStBar 的
        // 「追加到 >= 再逐 char 删到 <=」循环钉死，EmueraConsole.cs:3424-3435）。
        // 全角单元按 `Grid.cellWidth` 最多 2 半角格 = 18px（Kernel.kt:104；原版整串
        // 字体度量里没有 4 格类别，StringMeasure.cs:42-47），
        // 18*49 = 882 <= 897 < 900 = 18*50 → 49 个。
        val full = gen.build("─")
        assertEquals(49, full.length)
        assertTrue(full.all { it == '─' })
    }

    @Test
    fun `测量含TAB串时先按8空格展开`() {
        // TAB 展开（StringMeasure.cs:57-60，Grid.expandTabs 同口径）：tab=8格 → 72px
        val gen = DrawLineBar()
        assertEquals(72, gen.measurePx("\t"))
        assertEquals(gen.measurePx("a        b"), gen.measurePx("a\tb"))
    }
}
