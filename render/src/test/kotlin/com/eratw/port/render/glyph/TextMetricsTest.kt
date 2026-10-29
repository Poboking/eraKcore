package com.eratw.port.render.glyph

import com.eratw.port.core.font.FontAdvanceTable
import com.eratw.port.core.font.FontSidecarCatalog
import com.eratw.port.core.font.FontSidecarFormatException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/**
 * GLYPH-KEY-SIZE / M4-REALCHECK ②③ · [TextMetrics] 与 §3 sidecar 消费的闸门。
 *
 * 期望值全部**手算**自两处来源，一条也不许从实现回读：
 * - 夹具里的设计单元数值（`SidecarFixtures.kt`，实测自语料 `font/Saitamaar.ttf` 的 hmtx，
 *   与仓内 `tools/fontsubset/probe-report.txt:32-59` 一致）；
 * - 换算式 `design * size / unitsPerEm`（CONTRACT-M4-C §3）与
 *   `CellMetrics` 的量化式（spec: `StringMeasure.cs:37,65`）。
 *
 * 字号 16 / 18 下用到的每格像素宽（手算）：
 * - 16 → 16/2*1.04 = 8.32；30 → 15.6；18 → 9.36；20 → 10.4。
 *
 * 回退链的用例一律走 [fixtureOnlyReader]（§3 成品按授权不入仓，读取口里"有没有文件"必须由夹具
 * 自己声明，不许寄生在 classpath 目录恰好是空的这件事上）；只有「成品缺失时确定性退化」那条
 * 故意走 [TextMetrics.classpath]，它测的就是真缺失。
 */
class TextMetricsTest {

    private val font = CellMetrics.DEFAULT_FONT_NAME

    private fun fixtureMetrics(alias: String? = font): TextMetrics =
        TextMetrics.fromSidecarText(SIDECAR_FIXTURE_PROPORTIONAL, alias = alias)

    // ===== 解析（§3 格式，一字不差）=====

    @Test
    fun `夹具表解析出家族名 unitsPerEm 与逐码位设计单元`() {
        val table = FontAdvanceTable.parse(SIDECAR_FIXTURE_PROPORTIONAL)
        assertEquals("Saitamaar", table.family)
        assertEquals(1280, table.unitsPerEm)
        assertEquals(16, table.referenceSizePx)
        assertTrue(table.generator!!.startsWith("c-glyphmetrics"), table.generator!!)
        assertEquals(8, table.advances.size)
        assertEquals(8, table.codePoints.size)
        // 逐码位的设计单元（fontTools 实测值，不是编的）
        assertEquals(400, table.advanceDesign(' '.code))
        assertEquals(640, table.advanceDesign('0'.code))
        assertEquals(800, table.advanceDesign('A'.code))
        assertEquals(880, table.advanceDesign('C'.code))
        assertEquals(1200, table.advanceDesign('あ'.code))
        assertEquals(1280, table.advanceDesign('中'.code))
        assertEquals(80, table.lsbDesign('B'.code))
        assertEquals(0, table.lsbDesign('A'.code))
        // §3：cmap 没有的码点不出行 → 缺行即缺字（い U+3044 不在夹具的 8 行里）
        assertTrue(table.covers('A'.code))
        assertEquals(false, table.covers('い'.code))
        assertNull(table.advanceDesign('い'.code))
        // 读入序 = 码点升序（§3 强制，解析器保持该序）
        assertEquals(listOf(0x20, 0x30, 0x41, 0x42, 0x43, 0x3042, 0x4E2D, 0xFF71), table.codePoints.toList())
    }

    @Test
    fun `注释行与缺省的可选项不影响解析`() {
        // §3 允许「# 开头为注释」；#generator 与 #size 只是溯源信息，缺省也要能读
        val table = FontAdvanceTable.parse(
            "# 这是注释，不是指令\n" +
                "#family 黑体\n" +
                "#unitsPerEm 256\n" +
                "0041 128 0\n",
        )
        assertEquals("黑体", table.family)
        assertEquals(256, table.unitsPerEm)
        assertNull(table.generator)
        assertNull(table.referenceSizePx)
        assertEquals(128, table.advanceDesign('A'.code))
    }

    @Test
    fun `未知构造直接抛并报行号`() {
        // 契约 §3 末条：解析器必须对未知构造**直接抛并说明行号**，不许静默给 0
        val malformed = "#generator build_subset.py\n#family F\n#unitsPerEm 1000\n#advanceWidth 10\n0041 800 0\n"
        val e = assertFailsWith<FontSidecarFormatException> { FontAdvanceTable.parse(malformed, origin = "fixture.txt") }
        assertEquals(4, e.lineNo)
        assertTrue(e.message!!.contains("第 4 行"), e.message!!)
        assertTrue(e.message!!.contains("fixture.txt"), e.message!!)
        assertTrue(e.message!!.contains("#advanceWidth"), e.message!!)
    }

    @Test
    fun `逐条格式违规都带行号`() {
        val prefix = "#family F\n#unitsPerEm 1000\n"
        val cases = listOf(
            // 数据行恰好三个字段
            "body" to prefix + "0041 800\n",
            // 码点必须十六进制大写、无前缀
            "lowerHex" to prefix + "004a 800 0\n",
            "prefixed" to prefix + "0x0041 800 0\n",
            // 码点必须严格升序（第 4 行违反）
            "descending" to prefix + "0042 800 0\n0041 800 0\n",
            "duplicated" to prefix + "0041 800 0\n0041 800 0\n",
            // 无空行（第 4 行是空行）
            "blankLine" to prefix + "0041 800 0\n\n0042 800 0\n",
            // advance / lsb 必须是十进制整数
            "badAdvance" to prefix + "0041 8.5 0\n",
            // unitsPerEm 必须为正
            "zeroUnits" to "#family F\n#unitsPerEm 0\n0041 800 0\n",
            // 必需指令缺失
            "noUnits" to "#family F\n0041 800 0\n",
            "noFamily" to "#unitsPerEm 1000\n0041 800 0\n",
        )
        for ((label, body) in cases) {
            val e = assertFailsWith<FontSidecarFormatException>("场景 $label 应抛", block = {
                FontAdvanceTable.parse(body, origin = label)
            })
            assertTrue(e.message!!.isNotEmpty(), label)
        }
    }

    @Test
    fun `尾部换行不算空行`() {
        // 每行以 LF 结尾的正规文件不该因为最后一个换行被判成"空行"
        val table = FontAdvanceTable.parse(prefixForParseTest())
        assertEquals(1, table.advances.size)
    }

    private fun prefixForParseTest(): String = "#family F\n#unitsPerEm 1000\n0041 800 0\n"

    // ===== 换算（M4-REALCHECK ②：如实比例宽度，不许二次近似）=====

    @Test
    fun `换算就是 design 乘 size 除 unitsPerEm 且中间量是浮点`() {
        val table = FontAdvanceTable.parse(SIDECAR_FIXTURE_PROPORTIONAL)
        // 800*16/1280 = 10.0；880*16/1280 = 11.0；1280*16/1280 = 16.0；400*16/1280 = 5.0
        assertEquals(10.0f, table.advancePixels('A'.code, 16L)!!, 1e-6f)
        assertEquals(11.0f, table.advancePixels('C'.code, 16L)!!, 1e-6f)
        assertEquals(16.0f, table.advancePixels('中'.code, 16L)!!, 1e-6f)
        assertEquals(5.0f, table.advancePixels(' '.code, 16L)!!, 1e-6f)
        // 18 字号下是真小数：读取时不许先量化到整格/整像素
        assertEquals(11.25f, table.advancePixels('A'.code, 18L)!!, 1e-6f)
        assertEquals(5.625f, table.advancePixels(' '.code, 18L)!!, 1e-6f)
        assertNull(table.advancePixels('い'.code, 18L))
        // 比例宽度：空格(400) != 数字(640) != 拉丁(800) != CJK(1280)，四档互不相等
        val widths = listOf(' '.code, '0'.code, 'A'.code, '中'.code).map { table.advanceDesign(it)!! }
        assertEquals(listOf(400, 640, 800, 1280), widths)
        assertEquals(4, widths.toSet().size)
    }

    @Test
    fun `整串一次截断而不是逐字截断`() {
        // 夹具里空格 = 400 设计单元 → 18 字号 = 5.625px。
        // 整串一次截断：5.625 + 5.625 = 11.25 → 11；逐字截断会得 5 + 5 = 10（那是位图布局的口径）。
        val metrics = fixtureMetrics()
        assertEquals(11, metrics.measurePixels("  ", font, 18))
        // 原版口径同一条：MeasureString 返回 float，最后才 (long) 截断（spec: FunctionMethodCreator.cs:6585-6587）
        assertEquals(34, metrics.measurePixels("ABC", font, 18))
        assertEquals(31, metrics.measurePixels("ABC", font, 16))
        assertEquals(0, metrics.measurePixels("", font, 16))
    }

    @Test
    fun `measureCells 是整串一次量化不是逐字格数相加`() {
        val metrics = fixtureMetrics()
        val read = metrics.measure("ABC", font, 16)
        assertEquals(31, read.pixels)
        // ((31-1)/8.32 + 0.95) = 4.555 → 4；而逐字（每字 2 格）相加是 6 —— 两条口径必须能分开
        assertEquals(4, read.cells)
        assertEquals(MetricProvenance.ADVANCE_TABLE, read.provenance)
        assertEquals(0, read.degradedCodePoints)
        assertEquals("Saitamaar", read.tableFamily)
        assertNull(read.reason, "真实度量可用时不许带退化原因")
        assertEquals(16, read.sizePx)
    }

    @Test
    fun `半角假名的真实宽度不等于半格`() {
        // U+FF71（ｱ）在网格里是 1 格，但实测 advance = 800（与拉丁 A 同宽）→ 16 字号 = 10px → 2 格。
        // 这正是"比例宽度不许冒充 1:2 网格"（M4-REALCHECK ②）。
        val metrics = fixtureMetrics()
        assertEquals(1, CellMetrics().cellsOf('ｱ'.code))
        assertEquals(10, metrics.measurePixels("ｱ", font, 16))
        assertEquals(2, metrics.measureCells("ｱ", font, 16))
    }

    // ===== 回退链与退化 =====

    @Test
    fun `缺行码位按网格补且出处标成 MIXED`() {
        val metrics = fixtureMetrics()
        // "A" = 10.0（表）；"い"(U+3044) 缺行 → 网格：全角 2 格 → cellsToPixels(2) = trunc(2*8.32) = 16
        val read = metrics.measure("Aい", font, 16)
        assertEquals(26, read.pixels)
        assertEquals(1, read.degradedCodePoints)
        assertEquals(MetricProvenance.MIXED_TABLE_GRID, read.provenance)
        assertTrue(read.reason!!.isNotEmpty(), "退化必须给得出可读原因")
        // ((26-1)/8.32 + 0.95) = 3.955 → 3
        assertEquals(3, read.cells)
    }

    @Test
    fun `成品缺失时确定性退化且不抛`() {
        // 本波 c-fontsub 的成品还没进 render 模块 resources 下的 fonts 目录：
        // classpath 一律读不到 → 必须走退化，值与等宽网格同源，但出处不许写成真实度量。
        val metrics = TextMetrics.classpath()
        val read = metrics.measure("ABC", font, 16)
        assertEquals(MetricProvenance.DEGRADED_GRID, read.provenance)
        assertEquals(3, read.degradedCodePoints)
        assertNull(read.tableFamily)
        // 退化值：每字 trunc(1*8.32) = 8 → 24；((24-1)/8.32 + 0.95) = 3.714 → 3
        assertEquals(24, read.pixels)
        assertEquals(3, read.cells)
        assertTrue(read.reason!!.contains("fonts/"), read.reason!!)
        assertEquals(read.reason, metrics.degradedReason())
        // 两次测量逐字节一致（确定性：不读时间、不用随机、不依赖迭代序）
        val again = metrics.measure("ABC", font, 16)
        assertEquals(read.pixels, again.pixels)
        assertEquals(read.cells, again.cells)
        assertEquals(read.provenance, again.provenance)
        assertEquals(read.reason, again.reason)
    }

    @Test
    fun `请求族没有表时回退到替代族`() {
        // 装配期把随包子集声明成替代族。原版**没有**"逐请求名的回退链"：
        // Config.cs:501-523 只做开局一次替换 —— CheckFont(FontName) 失败才把随包字体
        // AddFontFile 并改写 Config.FontName（:504、:520-522），脚本随便请求一个没装的族时
        // 由 GDI+ 自己在 new Font 内部兜底（FunctionMethodCreator.cs:6584）。
        // 本端口的 substituteFamily 是**装配决策**，与原版的分歧见 FontSidecarCatalog 的 TODO(verify)。
        val table = FontAdvanceTable.parse(SIDECAR_FIXTURE_PROPORTIONAL)
        // 链是按**家族名**接起来的：夹具的 `#family` 必须等于声明的替代族名，否则这条链根本不通
        // （上一版只靠 classpath 恰好没文件才"看起来"走通，名字对不上时也是同一句静默退化）。
        assertEquals(SIDECAR_FIXTURE_FAMILY, table.family)
        val catalog = FontSidecarCatalog(defaultFamily = font, substituteFamily = SIDECAR_FIXTURE_FAMILY)
        // 「只有夹具」的世界：除显式登记的路径外一律读不到，不寄生在"真成品没随包"这件事上。
        catalog.reader = fixtureOnlyReader()
        catalog.put(table)
        val metrics = TextMetrics(catalog)
        // 第一跳（请求族「宋体」）确实没拿到表，退化原因留在目录里 —— 这才能证明是**回退**而不是直查
        val read = metrics.measure("ABC", "宋体", 16)
        assertTrue(catalog.reasonFor("宋体")!!.contains("缺失"), catalog.reasonFor("宋体")!!)
        assertEquals(MetricProvenance.ADVANCE_TABLE, read.provenance)
        assertEquals(SIDECAR_FIXTURE_FAMILY, read.tableFamily)
        assertNull(read.reason, "回退命中真实表之后不许把上一跳的缺失当退化原因带出去")
        // 31 的手算（§3 换算式 `design * sizePx / unitsPerEm`，整串求和后**一次**向零截断）：
        //   夹具设计单元 A = 800、B = 800、C = 880；unitsPerEm = 1280；sizePx = 16 → 每单位 = 16/1280
        //   A：800 * 16 = 12800，12800 / 1280 = 10.0f
        //   B：同上 = 10.0f（lsb 80 不参与前进量）
        //   C：880 * 16 = 14080，14080 / 1280 = 11.0f
        //   合计 10.0f + 10.0f + 11.0f = 31.0f → 截断一次 = 31（三个中间量都是整值，逐字截断同数，
        //   所以本条只锁"回退命中的是真实表"；小数与截断口径由「整串一次截断而不是逐字截断」用例锁）
        // 依据：契约 M4-C §3 第 3 条（读取时不许二次近似）+ 原版把 float 宽度留到最后才强转
        // （spec: FunctionMethodCreator.cs:6585-6587）。
        assertEquals(31, read.pixels)
        // 空字体名先归一到默认族（spec: FontFactory.cs:13-17）；默认族「黑体」这里同样没有表 → 仍回退到替代族
        assertEquals(31, metrics.measurePixels("ABC", "", 16))
        // **串**为空才是 0：原版 GetDisplayLength 对 null/空串显式 return 0（spec: StringMeasure.cs:51-56），
        // GGETTEXTSIZE 那条 MeasureString 也没有码位可量、宽度同为 0（spec: FunctionMethodCreator.cs:6585-6587）。
        // （原来这里断言的是 31 —— 那是把"字体名为空"与"文本为空"混成一个入参；文本为空的权威口径是 0，
        // 与「整串一次截断而不是逐字截断」用例里 `measurePixels("", font, 16) == 0` 同判。）
        assertEquals(0, metrics.measurePixels("", SIDECAR_FIXTURE_FAMILY, 16))
        // 替代族那一跳同样能经**读取口**命中（put 只是装配期旁路，不是链的唯一入口）：
        // 路径由 catalog 自己按 §3 口径给，名字与 c-fontsub 的成品对得上
        // （`subset-build-manifest.txt:17` sidecar-output = saitamaar-advance.txt）。
        val viaReader = FontSidecarCatalog(defaultFamily = font, substituteFamily = SIDECAR_FIXTURE_FAMILY)
        assertEquals("fonts/saitamaar-advance.txt", viaReader.sidecarPath(SIDECAR_FIXTURE_FAMILY))
        viaReader.reader = fixtureOnlyReader(viaReader.sidecarPath(SIDECAR_FIXTURE_FAMILY) to SIDECAR_FIXTURE_PROPORTIONAL)
        assertEquals(31, TextMetrics(viaReader).measurePixels("ABC", "宋体", 16))
    }

    @Test
    fun `替代族也没有表时按退化`() {
        val catalog = FontSidecarCatalog(defaultFamily = font, substituteFamily = "SubstituteSerif")
        catalog.reader = TextMetrics.classpathReader()
        catalog.put(FontAdvanceTable.parse(SIDECAR_FIXTURE_SUBSTITUTE))
        val metrics = TextMetrics(catalog)
        // 请求族「无此族」→ 替代族 SubstituteSerif 有表但不覆盖 U+0041 以外的码位
        val read = metrics.measure("Aあ", "无此族", 16)
        assertEquals(MetricProvenance.MIXED_TABLE_GRID, read.provenance)
        assertEquals(1, read.degradedCodePoints)
        // A：替代族 500/1000 → 500*16/1000 = 8.0；あ 缺行 → 网格 16 → 合计 24
        assertEquals(8, metrics.measurePixels("A", "无此族", 16))
        assertEquals(24, read.pixels)
        assertTrue(read.reason!!.isNotEmpty())
    }

    // ===== 字号 =====

    @Test
    fun `字号归一与换算器按字号复用`() {
        val metrics = fixtureMetrics()
        assertEquals(18, metrics.defaultSizePx)
        assertEquals(18, metrics.normalizeSize(0))
        assertEquals(18, metrics.normalizeSize(-7))
        assertEquals(30, metrics.normalizeSize(30))
        assertSame(metrics.cellMetrics, metrics.cellMetricsFor(18))
        assertSame(metrics.cellMetricsFor(16), metrics.cellMetricsFor(16))
        assertNotSame(metrics.cellMetricsFor(16), metrics.cellMetricsFor(18))
        // 未给字号（0）= 按默认字号 18 量：A = 11.25 → 11
        assertEquals(11, metrics.measurePixels("A", font, 0))
        assertEquals(10, metrics.measurePixels("A", font, 16))
        // 半角格换算必须用该字号自己的换算器：16 → ((10-1)/8.32+0.95) = 2.03 → 2
        assertEquals(2, metrics.measureCells("A", font, 16))
        // 18 → 每格 9.36，11.25 → ((11-1)/9.36+0.95) = 2.017 → 2
        assertEquals(2, metrics.measureCells("A", font, 18))
        assertEquals(18, metrics.measure("A", font, 0).sizePx)
    }

    @Test
    fun `lsb 随字号换算且缺行给零`() {
        val metrics = fixtureMetrics()
        // U+0042 的 lsb = 80 设计单元 → 80*16/1280 = 1.0；80*32/1280 = 2.0（随字号线性放大）
        assertEquals(1, metrics.lsbPixels('B'.code, font, 16))
        assertEquals(2, metrics.lsbPixels('B'.code, font, 32))
        assertEquals(0, metrics.lsbPixels('A'.code, font, 16))
        assertEquals(0, metrics.lsbPixels('い'.code, font, 16))
        // 没有任何表时也给 0（缺信息不是失败）
        assertEquals(0, TextMetrics.classpath().lsbPixels('B'.code, font, 16))
    }

    @Test
    fun `covers 只在真有表时否决`() {
        val metrics = fixtureMetrics()
        assertTrue(metrics.covers('A'.code, font))
        assertEquals(false, metrics.covers('い'.code, font))
        // 没接表时不能否决（"不知道有没有" != "没有"）：那是 DEGRADED_GRID 的口径
        assertTrue(TextMetrics.classpath().covers('任'.code, font))
    }

    @Test
    fun `表里的家族名与请求名不同时也能按请求名命中`() {
        // catalog.put 的 alias 就是为"脚本请求 黑体、实际由子集代画"这条链准备的
        val catalog = FontSidecarCatalog(defaultFamily = font)
        catalog.put(FontAdvanceTable.parse(SIDECAR_FIXTURE_PROPORTIONAL), alias = font)
        val metrics = TextMetrics(catalog)
        assertEquals("Saitamaar", metrics.measure("A", font, 16).tableFamily)
        assertEquals(10, metrics.measurePixels("A", font, 16))
        // 请求名换成表自带的家族名同样命中
        assertEquals(10, metrics.measurePixels("A", "Saitamaar", 16))
    }
}
