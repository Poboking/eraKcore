package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.font.FontAdvanceTable
import com.eratw.port.core.font.FontSidecarCatalog
import com.eratw.port.core.font.FontSidecarFormatException
import com.eratw.port.core.font.FontSidecarReader
import com.eratw.port.core.font.SLOT_FONT_SIDECAR
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §3 字体度量 sidecar 的解析、回退链与退化口径（GLYPH-KEY-SIZE / M4-REALCHECK ②③）。
 *
 * 判据两处，一条也不从实现回读：
 * - CONTRACT-M4-C §3 的握手格式（字段名、排序、"缺行即缺字"、"未知构造直接抛并报行号"）；
 * - 设计单元数值取自本机 fontTools 4.67.0 读语料 `eratw-画蛇添足版eratw4.980/font/Saitamaar.ttf`
 *   的 `head.unitsPerEm` 与 `hmtx` 实测（与仓内 `tools/fontsubset/probe-report.txt:32-59` 逐项一致）：
 *   unitsPerEm = 1280，空格 400 / 数字 640 / A 800 / B 800（lsb 80）/ C 880 /
 *   あ(U+3042) 1200 / 中(U+4E2D) 1280（lsb 80）/ ｱ(U+FF71) 800。
 *   **这是小尺寸测试夹具，不是 c-fontsub 的成品**，本波不许依赖那份在飞的文件。
 */
private const val FIXTURE_PROPORTIONAL = "#generator c-glyphmetrics 单测夹具（不是 build_subset.py 产物）\n" +
    "#family Saitamaar\n" +
    "#unitsPerEm 1280\n" +
    "#size 16\n" +
    "0020 400 0\n" +
    "0030 640 0\n" +
    "0041 800 0\n" +
    "0042 800 80\n" +
    "0043 880 0\n" +
    "3042 1200 0\n" +
    "4E2D 1280 80\n" +
    "FF71 800 0\n"

/** 只有 A 一行的替代族表（构造"请求族没有表 → 走替代族"这条链）。 */
private const val FIXTURE_SUBSTITUTE = "#family SubstituteSerif\n" +
    "#unitsPerEm 1000\n" +
    "0041 500 0\n"

/** 畸形夹具：第 4 行是 §3 里没有的指令（用来断言"未知构造直接抛并报行号"）。 */
private const val FIXTURE_UNKNOWN_DIRECTIVE = "#generator build_subset.py\n#family F\n#unitsPerEm 1000\n#advanceWidth 10\n0041 800 0\n"

private class FsBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("FsBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

/** 内存文件系统（§3 侧车按路径给）。 */
private class FsFileOps(initial: Map<String, String> = emptyMap()) : FileOps {
    val files = LinkedHashMap(initial)
    override fun exists(path: String): Boolean = files.containsKey(path)
    override fun readText(path: String): String? = files[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        files[path] = if (append) (files[path] ?: "") + text else text
        return true
    }

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private class FsHost(override val fileOps: FileOps? = null) : BuiltinHost

private fun fsCtx(ops: FileOps? = FsFileOps()): BuiltinEvalContext {
    val store = VariableStore()
    val base = FsBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, FsHost(ops))
}

private fun fsCall(ctx: BuiltinEvalContext, name: String, vararg args: Any): Value =
    ctx.callFunction(
        name,
        args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
    )

private fun fsI(ctx: BuiltinEvalContext, name: String, vararg args: Any): Long = (fsCall(ctx, name, *args) as Value.IntV).v

private fun fsCatalog(ctx: BuiltinEvalContext): FontSidecarCatalog =
    ctx.store.aux.slot(SLOT_FONT_SIDECAR) { FontSidecarCatalog() }

class FontSidecarMetricsTest {

    // ===== 解析（§3 一字不差）=====

    @Test
    fun `夹具表解析出的字段与设计单元值`() {
        val table = FontAdvanceTable.parse(FIXTURE_PROPORTIONAL, origin = "fixture")
        assertEquals("Saitamaar", table.family)
        assertEquals(1280, table.unitsPerEm)
        assertEquals(16, table.referenceSizePx)
        assertEquals("c-glyphmetrics 单测夹具（不是 build_subset.py 产物）", table.generator)
        assertEquals(8, table.advances.size)
        assertEquals(8, table.lsbs.size)
        assertEquals(400, table.advanceDesign(' '.code))
        assertEquals(640, table.advanceDesign('0'.code))
        assertEquals(800, table.advanceDesign('A'.code))
        assertEquals(880, table.advanceDesign('C'.code))
        assertEquals(1200, table.advanceDesign('あ'.code))
        assertEquals(1280, table.advanceDesign('中'.code))
        assertEquals(80, table.lsbDesign('B'.code))
        assertEquals(0, table.lsbDesign('A'.code))
        // 读入序 = 码点升序（§3 强制），解析器原样保持
        assertEquals(listOf(0x20, 0x30, 0x41, 0x42, 0x43, 0x3042, 0x4E2D, 0xFF71), table.codePoints.toList())
        // 缺行即缺字：夹具没有 U+3044
        assertTrue(table.covers('A'.code))
        assertEquals(false, table.covers('い'.code))
        assertNull(table.advanceDesign('い'.code))
        assertNull(table.lsbDesign('い'.code))
    }

    @Test
    fun `换算就是 design 乘 size 除 unitsPerEm`() {
        val table = FontAdvanceTable.parse(FIXTURE_PROPORTIONAL)
        // 手算：字号 16 → 除以 80；字号 18 → 乘 0.0140625
        assertEquals(10.0f, table.advancePixels('A'.code, 16L)!!, 1e-6f)
        assertEquals(11.25f, table.advancePixels('A'.code, 18L)!!, 1e-6f)
        assertEquals(5.625f, table.advancePixels(' '.code, 18L)!!, 1e-6f)
        assertEquals(16.0f, table.advancePixels('中'.code, 16L)!!, 1e-6f)
        assertEquals(1.0f, table.lsbPixels('B'.code, 16L)!!, 1e-6f)
        assertNull(table.advancePixels('い'.code, 16L))
        // 比例宽度四档互不相等（M4-REALCHECK ②：不许为凑 1:2 网格伪造）
        assertEquals(
            listOf(400, 640, 800, 1280),
            listOf(' '.code, '0'.code, 'A'.code, '中'.code).map { table.advanceDesign(it)!! },
        )
    }

    @Test
    fun `未知构造抛异常并带行号与原行`() {
        val e = assertFailsWith<FontSidecarFormatException> {
            FontAdvanceTable.parse(FIXTURE_UNKNOWN_DIRECTIVE, origin = "bad.txt")
        }
        assertEquals(4, e.lineNo)
        assertEquals("#advanceWidth 10", e.rawLine)
        assertEquals("bad.txt", e.origin)
        assertTrue(e.message!!.contains("第 4 行"), e.message!!)
        assertTrue(e.message!!.contains("未知构造"), e.message!!)
    }

    @Test
    fun `逐条格式违规都抛并指出所在行`() {
        val header = "#family F\n#unitsPerEm 1000\n"
        val cases = listOf(
            "两字段数据行" to header + "0041 800\n",
            "小写十六进制" to header + "004a 800 0\n",
            "带前缀的码点" to header + "0x0041 800 0\n",
            "码点降序" to header + "0042 800 0\n0041 800 0\n",
            "码点重复" to header + "0041 800 0\n0041 800 0\n",
            "中间空行" to header + "0041 800 0\n\n0042 800 0\n",
            "advance 不是整数" to header + "0041 8.5 0\n",
            "lsb 不是整数" to header + "0041 800 -\n",
            "码点越界" to header + "110000 800 0\n",
            "unitsPerEm 为 0" to "#family F\n#unitsPerEm 0\n0041 800 0\n",
            "unitsPerEm 带符号" to "#family F\n#unitsPerEm +1000\n0041 800 0\n",
            "缺 #unitsPerEm" to "#family F\n0041 800 0\n",
            "缺 #family" to "#unitsPerEm 1000\n0041 800 0\n",
            "family 为空" to "#family \n#unitsPerEm 1000\n0041 800 0\n",
            "指令重复" to "#family F\n#family G\n#unitsPerEm 1000\n",
        )
        for ((label, body) in cases) {
            val e = assertFailsWith<FontSidecarFormatException>(message = "场景「$label」应当抛", block = {
                FontAdvanceTable.parse(body, origin = label)
            })
            assertTrue(e.message!!.isNotEmpty(), label)
        }
    }

    @Test
    fun `注释行与可缺省的 generator 和 size`() {
        // §3 允许「# 开头为注释」；#generator 与 #size 只是溯源信息，缺省也要能读
        val table = FontAdvanceTable.parse("# 这是注释，不是指令\n#family 黑体\n#unitsPerEm 256\n0041 128 0\n")
        assertEquals("黑体", table.family)
        assertEquals(256, table.unitsPerEm)
        assertNull(table.generator)
        assertNull(table.referenceSizePx)
        assertEquals(128, table.advanceDesign('A'.code))
        // 尾部换行不额外算一行（正规文件都带末尾 LF）
        assertEquals(1, FontAdvanceTable.parse("#family F\n#unitsPerEm 1000\n0041 800 0\n").advances.size)
    }

    @Test
    fun `负 lsb 与零 advance 原样读入不抬升`() {
        // ADR-005：字体度量是什么就是什么，解析器不做防御性钳位
        val table = FontAdvanceTable.parse("#family F\n#unitsPerEm 1000\n0041 0 -50\n")
        assertEquals(0, table.advanceDesign('A'.code))
        assertEquals(-50, table.lsbDesign('A'.code))
        assertEquals(-0.8f, table.lsbPixels('A'.code, 16L)!!, 1e-6f)
    }

    // ===== 回退链 / 缺失 / 畸形 =====

    @Test
    fun `sidecar 路径约定与 slug 折叠`() {
        // §3 只写了 `<family-slug>-advance.txt`，折叠规则由本端口定死并公开（装配层可绕过：put 直挂表）
        val catalog = FontSidecarCatalog()
        assertEquals("fonts/saitamaar-advance.txt", catalog.sidecarPath("Saitamaar"))
        assertEquals("fonts/黑体-advance.txt", catalog.sidecarPath("黑体"))
        assertEquals("fonts/ms-p-gothic-advance.txt", catalog.sidecarPath("MS P Gothic"))
        assertEquals("fonts/ms-p-gothic-advance.txt", catalog.sidecarPath("  MS--P  Gothic "))
    }

    @Test
    fun `成品缺失时退化不抛且原因可读`() {
        val ops = FsFileOps()
        val ctx = fsCtx(ops)
        // 黑体 @16：无表 → 退化 = 格数 * size / 2 = 1 * 8 = 8，三个字符 24
        assertEquals(24L, fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16))
        assertEquals(16L, ctx.store.readInt("RESULT", longArrayOf(1)))
        val catalog = fsCatalog(ctx)
        val reason = assertNotNull(catalog.reasonFor("黑体"))
        assertTrue(reason.contains("fonts/黑体-advance.txt"), reason)
        assertTrue(reason.contains("缺失"), reason)
        assertEquals(reason, catalog.lastReason)
    }

    @Test
    fun `宿主没有文件口时同样退化`() {
        val ctx = fsCtx(ops = null)
        assertEquals(24L, fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16))
        assertTrue(fsCatalog(ctx).lastReason!!.contains("reader"), fsCatalog(ctx).lastReason!!)
    }

    @Test
    fun `畸形表收成原因文本并带行号而不是静默给零`() {
        val ops = FsFileOps(mapOf("fonts/黑体-advance.txt" to FIXTURE_UNKNOWN_DIRECTIVE))
        val ctx = fsCtx(ops)
        // 脚本层不该被随包资产的构建错误打死 → 退化到网格值；但原因必须留着行号
        assertEquals(24L, fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16))
        val reason = assertNotNull(fsCatalog(ctx).reasonFor("黑体"))
        assertTrue(reason.contains("畸形"), reason)
        assertTrue(reason.contains("第 4 行"), reason)
    }

    @Test
    fun `装配期直挂的表优先且不依赖文件口`() {
        val ctx = fsCtx(ops = null)
        val catalog = fsCatalog(ctx)
        catalog.put(FontAdvanceTable.parse(FIXTURE_PROPORTIONAL), alias = "黑体")
        // 800 + 800 + 880 设计单元 @16 = 10 + 10 + 11 = 31（整串一次截断，无小数）
        assertEquals(31L, fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16))
        // 同一串按整串一次截断：@18 是 11.25 + 11.25 + 12.375 = 34.875 → 34
        assertEquals(34L, fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 18))
        // M4-L K-MEASURE：行尾空白被裁（GDI+ MeasureString 口径，FunctionMethodCreator.cs:6585
        // 那一次测量），所以 "  " 从前钉的 11（5.625 x 2 = 11.25 → 11）不再是现状；
        // PC 实测同为 0，这条是**改对**而不是改漂。整串一次截断的判据改用中间带空格的 "A B"：
        // 11.25 + 5.625 + 11.25 = 28.125 → 28（逐字截断会得 11 + 5 + 11 = 27）。
        assertEquals(0L, fsI(ctx, "GGETTEXTSIZE", "  ", "黑体", 18))
        assertEquals(28L, fsI(ctx, "GGETTEXTSIZE", "A B", "黑体", 18))
        // 前导空白不裁（PC 实测 " abc" 同样带上那一个空格）：5.625 + 11.25 = 16.875 → 16
        assertEquals(16L, fsI(ctx, "GGETTEXTSIZE", " A", "黑体", 18))
    }

    @Test
    fun `请求族没有表时用替代族`() {
        val ctx = fsCtx()
        val catalog = fsCatalog(ctx)
        catalog.substituteFamily = "Saitamaar"
        catalog.put(FontAdvanceTable.parse(FIXTURE_PROPORTIONAL))
        assertEquals(31L, fsI(ctx, "GGETTEXTSIZE", "ABC", "无此族", 16))
        assertEquals("Saitamaar", catalog.tableFor("无此族")!!.family)
        assertTrue(catalog.reasonFor("无此族")!!.contains("缺失"))
    }

    @Test
    fun `缺行的码位按等宽网格补`() {
        val ctx = fsCtx()
        fsCatalog(ctx).put(FontAdvanceTable.parse(FIXTURE_PROPORTIONAL), alias = "黑体")
        // "Aい"：A 有表 = 10.0；い(U+3044) 缺行 → 全角 2 格 * 16/2 = 16 → 合计 26
        assertEquals(26L, fsI(ctx, "GGETTEXTSIZE", "Aい", "黑体", 16))
        // 全表覆盖的串才等于纯真实度量
        assertEquals(10L, fsI(ctx, "GGETTEXTSIZE", "A", "黑体", 16))
        assertEquals(16L, fsI(ctx, "GGETTEXTSIZE", "中", "黑体", 16))
        assertEquals(10L, fsI(ctx, "GGETTEXTSIZE", "0", "黑体", 20))
    }

    // ===== 参数口径（逐条对齐 C#）=====

    @Test
    fun `字号非正一律抛 —— 原版 new Font 抛 ArgumentException`() {
        val ctx = fsCtx()
        // spec: FunctionMethodCreator.cs:6584（emSize <= 0 抛，且 GGETTEXTSIZE 没有 try/catch，对比 :6404-6406）
        assertFailsWith<CodeEE> { fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 0) }
        assertFailsWith<CodeEE> { fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", -16) }
    }

    @Test
    fun `参数个数仍是 3 或 4 且第四参可省`() {
        val ctx = fsCtx()
        // spec: :6537-6550（ArgTypes 四项 + OmitStart = 3 → 第 4 个可省，前三个不可省）
        assertFailsWith<CodeEE> { fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体") }
        assertFailsWith<CodeEE> { fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16, 1, 2) }
        assertEquals(24L, fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16))
        assertEquals(24L, fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16, 3))
    }

    @Test
    fun `首实参不是图形编号`() {
        // spec: :6537-6550 的 ArgTypes = String, String, Int, Int（`FunctionMethodCreator.cs:6543-6546`）
        // 若把首参当 gId 读，未创建的编号会走 "读图形" 的错误路径；这里断言它只是文本。
        val ctx = fsCtx()
        assertEquals(24L, fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16))
        assertFailsWith<CodeEE> { fsI(ctx, "GGETTEXTSIZE", 10, "黑体", 16) }
    }

    @Test
    fun `样式位越界不抛（原版只看低四位）`() {
        val ctx = fsCtx()
        // spec: :6564-6583 逐位判 1/2/4/8，其余位忽略；long 也没有 Int 范围检查（:6566）
        assertEquals(24L, fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16, 1L shl 40))
        assertEquals(24L, fsI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16, -1))
    }

    @Test
    fun `字号不做 Int 钳制`() {
        val ctx = fsCtx()
        // 原版把 GetIntValue 的 long 直接喂给 `new Font` 的 float 形参 emSize（spec: :6562 + :6584），
        // 全程没有 Int 范围检查；移植版若沿用 intArg 钳位就会把这条变成 CodeEE —— 那才是分歧。
        // 这里的期望值是**本端口退化模型的口径锁**（1 格 * size/2，KDoc 里定义的线性式），
        // 不是 PC 读数；PC 在 1e12 级字号下大概会由 GDI+ 侧失败，属崩溃通道、基准里没有对拍数据。
        // TODO(verify)：真机字号上限（原版 GDI+ 与 Android Paint 都会钳）需要实机读数才能定。
        val huge = 1L shl 40
        assertEquals(1L shl 39, fsI(ctx, "GGETTEXTSIZE", "A", "黑体", huge))
// 必须先读 RESULT[1] 再跑下一次调用：GGETTEXTSIZE 每次都覆写 RESULT[1]（:6586），
// 反过来写会把上一条的巨型高度盖成 1000，测的就不是「不做 Int 钳制」了。
assertEquals(huge, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(500L, fsI(ctx, "GGETTEXTSIZE", "A", "黑体", 1000))
    }

    // ===== GDRAWTEXT 与 GGETTEXTSIZE 共用同一条度量 =====

    @Test
    fun `GDRAWTEXT 无自带字体时按原版回退到 100 字号`() {
        val ctx = fsCtx()
        assertEquals(1L, fsI(ctx, "GCREATE", 10, 4, 4))
        // spec: :6519-6523 —— font == null 时 new Font(Config.FontName, 100f, …)，100 是原写字面量
        assertEquals(1L, fsI(ctx, "GDRAWTEXT", 10, "ABC", 0, 0))
        assertEquals(150L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(100L, ctx.store.readInt("RESULT", longArrayOf(2)))
    }

    @Test
    fun `GDRAWTEXT 用缓冲自带字体与字号`() {
        val ops = FsFileOps()
        val ctx = fsCtx(ops)
        fsCatalog(ctx).put(FontAdvanceTable.parse(FIXTURE_PROPORTIONAL), alias = "黑体")
        assertEquals(1L, fsI(ctx, "GCREATE", 10, 4, 4))
        assertEquals(1L, fsI(ctx, "GSETFONT", 10, "黑体", 16, 1))
        // 基准 648 行只断言返回值 1；宽/高走 §3 表（10 + 10 + 11 = 31，高 = 字号 16）
        assertEquals(1L, fsI(ctx, "GDRAWTEXT", 10, "ABC", 0, 0))
        assertEquals(31L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(16L, ctx.store.readInt("RESULT", longArrayOf(2)))
    }

    @Test
    fun `GDRAWTEXT 三个参数在原版也测量不抛`() {
        // spec: ArgTypes = Int, String, Int, Int 且 OmitStart = 2（:6480-6494）
        //       → 2/3/4 参都合法；3 参时两条绘制分支都不进但仍然测量（:6509-6524）
        val ctx = fsCtx()
        assertEquals(1L, fsI(ctx, "GCREATE", 10, 4, 4))
        assertEquals(1L, fsI(ctx, "GDRAWTEXT", 10, "ABC", 0))
        assertEquals(150L, ctx.store.readInt("RESULT", longArrayOf(1)))
    }

    @Test
    fun `替代族表按自己的 unitsPerEm 换算`() {
        val ctx = fsCtx()
        val catalog = fsCatalog(ctx)
        catalog.substituteFamily = "SubstituteSerif"
        catalog.put(FontAdvanceTable.parse(FIXTURE_SUBSTITUTE))
        // A：500 设计单元 / unitsPerEm 1000 * 16 = 8.0
        assertEquals(8L, fsI(ctx, "GGETTEXTSIZE", "A", "无此族", 16))
        // 该族没有 B → B 退化 = 1 格 * 16/2 = 8，与 A 真实值同数（用 CJK 才有区分度）
        assertEquals(16L, fsI(ctx, "GGETTEXTSIZE", "中", "无此族", 16))
    }

    // ===== sidecar 条目路径越界守卫 =====

    /**
     * family 是脚本给的自由串（FONT / GDRAWTEXT / GSETFONT 的 fontname），可能混进
     * 「../」「a/b」这类路径成分。读取口（[FontSidecarReader]）是任意宿主缝：目录形态的
     * `RootedFileOps.resolvePath` 有 normalize + startsWith 守卫（BootHosts.kt:409-415），
     * 但 zip / classpath 形态没有，所以折叠与校验必须在**生成侧**钉死：slug 把 `/` 与 `\`
     * 折成分隔符，条目永远单层落在 sidecarDir 下，读不到就按缺失退化。
     */
    @Test
    fun `family混入路径成分时折叠进slug不逃出sidecar目录`() {
        val requested = ArrayList<String>()
        val catalog = FontSidecarCatalog()
        catalog.reader = FontSidecarReader { path -> requested += path; null }
        // 「../../evil」折出的条目名以「.」开头 → 生成侧 containment 校验直接拒，
        // 读取口一次都不被触碰，按「路径越界」告警。
        assertNull(catalog.tableFor("../../evil"))
        assertEquals(0, requested.size)
        assertTrue(catalog.reasonFor("../../evil")!!.contains("路径越界"))
        // 「a/b」「a\b」折成单层条目 a-b-advance.txt：仍在 fonts/ 一层内，只是查不到
        assertNull(catalog.tableFor("a\\b"))
        assertNull(catalog.tableFor("a/b"))
        assertEquals(listOf("fonts/a-b-advance.txt", "fonts/a-b-advance.txt"), requested)
        assertTrue(requested.all { it.startsWith("fonts/") && !it.contains("..") && !it.contains("\\") })
    }

    /** 生成侧的第二道闸：即使条目路径里出现 `..` / 绝对前缀，也拒绝交给读取口并告警。 */
    @Test
    fun `越界条目路径按缺失退化且不触碰读取口`() {
        var readerCalls = 0
        val catalog = FontSidecarCatalog()
        catalog.reader = FontSidecarReader { _ -> readerCalls++; null }
        // family = ".." → slug = ".." → 条目名以「.」开头 → 生成侧 containment 校验拒绝
        assertNull(catalog.tableFor(".."))
        assertEquals(0, readerCalls)
        val reason = assertNotNull(catalog.reasonFor(".."))
        assertTrue(reason.contains("路径越界"), reason)
        assertEquals(reason, catalog.lastReason)
        // 拒绝按「查过且没有」缓存：再查同族不再触碰读取口、原因仍在
        assertNull(catalog.tableFor(".."))
        assertEquals(0, readerCalls)
    }

    @Test
    fun `containment校验的判定表`() {
        val f = { p: String -> FontSidecarCatalog.isContainedSidecarPath(p, "fonts") }
        assertTrue(f("fonts/saitamaar-advance.txt"))
        assertFalse(f("fonts/../evil-advance.txt"))
        assertFalse(f("fonts/sub/x-advance.txt"))
        assertFalse(f("other/x-advance.txt"))
        assertFalse(f("/etc/passwd"))
        assertFalse(f("fonts\\x-advance.txt"))
        assertFalse(f("fonts/"))
        assertFalse(f("fonts/.hidden-advance.txt"))
    }

    /** 带路径成分的 family 折叠后仍能正常命中表（守卫不许把正常路径误杀）。 */
    @Test
    fun `折叠后的family照常命中表`() {
        val catalog = FontSidecarCatalog()
        catalog.reader = FontSidecarReader { path ->
            if (path == "fonts/a-b-advance.txt") FIXTURE_SUBSTITUTE else null
        }
        val table = assertNotNull(catalog.tableFor("a/b"))
        assertEquals("SubstituteSerif", table.family)
    }
}
