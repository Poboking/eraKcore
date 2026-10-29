package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import org.junit.jupiter.api.Assumptions
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.vars.VariableStore
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * j-gfxaudit —— graphics / 文本度量族的**逐函数对拍**结果（权威 = `reference/emuera-cs/
 * MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs` 的 Graphics*Method 类体）。
 *
 * 每条断言钉的是**具体读数**（返回码 / 尺寸 / gId 生命周期 / 度量整数），不是「不抛异常」。
 * 逐条 C# 行号与判定见各用例注释与本文件末尾的度量分歧登记。
 *
 * 本波删掉了移植侧自行加严的一处口径（GSETFONT 的 32767 字号上界，见 [gsetFont 字号上界]），
 * 其余分歧按「登记不修正 / 需组长接线」处理，理由逐个写在下面。
 */
private class AuditCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("AuditCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/**
 * 内存文件系统 + 可注入的字节读取口。
 *
 * [bytes] 里的路径走 [BuiltinHost.readFileBytes]（原版 `Utils.LoadImage` 的那一条路），
 * 其余路径只有 [FileOps.readText] 有内容时才走 GSAVE 文本载体。
 */
private class AuditOps : FileOps {
    val files = LinkedHashMap<String, String>()
    val bytes = LinkedHashMap<String, ByteArray>()
    override fun exists(path: String): Boolean = files.containsKey(path) || bytes.containsKey(path)
    override fun readText(path: String): String? = files[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        files[path] = if (append) (files[path] ?: "") + text else text
        return true
    }
    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private fun auditCtx(ops: AuditOps): BuiltinEvalContext {
    val store = VariableStore()
    val base = AuditCtx()
    base.variables = store
    val host = object : BuiltinHost {
        override val fileOps: FileOps? = ops
        override fun readFileBytes(path: String): ByteArray? = ops.bytes[path]
    }
    return BuiltinEvalContext(base, store, host)
}

private fun call(ctx: BuiltinEvalContext, name: String, vararg args: Any): Value =
    ctx.callFunction(
        name,
        args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
    )

private fun i(ctx: BuiltinEvalContext, name: String, vararg args: Any): Long =
    (call(ctx, name, *args) as Value.IntV).v

private fun s(ctx: BuiltinEvalContext, name: String, vararg args: Any): String =
    (call(ctx, name, *args) as Value.StrV).v

class GraphicsGfxAuditTest {

    // ==================================================================
    // 1) GSETFONT —— 字号上界（本波唯一的实修）
    // ==================================================================

    /**
     * 原版 :6360 直接 `arguments[2].GetIntValue(exm)`，**没有范围检查**；上界只由
     * `new Font(…, emSize, …, GraphicsUnit.Pixel)`（:6394 / :6400）决定，实测判据是
     * 「emSize 必须严格大于 0，上界 Single.MaxValue」—— 32767 / 32768 / 40000 **三个都能构造成功**
     * （`Font.Size` 逐字回读同名值）。因此 :6404-6406 的空 catch 只在 `size <= 0` 时被触发。
     */
    @Test
    fun `GSETFONT 字号上界是 Single MaxValue 而不是 32767`() {
        val ctx = auditCtx(AuditOps())
        assertEquals(1L, i(ctx, "GCREATE", 7, 40, 30))

        // 原版即如此：<= 0 → 空 catch → 0（:6404-6406）
        assertEquals(0L, i(ctx, "GSETFONT", 7, "黑体", 0))
        assertEquals(0L, i(ctx, "GSETFONT", 7, "黑体", -16))
        assertEquals(0L, i(ctx, "GSETFONT", 7, "黑体", -2147483648))
        // 失败的那几次不许把状态写脏：字体仍是未设置
        assertFailsWith<CodeEE> { i(ctx, "GGETFONTSIZE", 7) }

        // 移植侧此前自行加严：> 32767 一律 0。原版 32768 / 40000 都返回 1，且能被 GGETFONTSIZE 读回。
        assertEquals(1L, i(ctx, "GSETFONT", 7, "黑体", 32767))
        assertEquals(32767L, i(ctx, "GGETFONTSIZE", 7))
        assertEquals(1L, i(ctx, "GSETFONT", 7, "黑体", 32768))
        assertEquals(32768L, i(ctx, "GGETFONTSIZE", 7))
        assertEquals(1L, i(ctx, "GSETFONT", 7, "黑体", 40000))
        assertEquals(40000L, i(ctx, "GGETFONTSIZE", 7))
        assertEquals(1L, i(ctx, "GSETFONT", 7, "黑体", 2147483647))
        assertEquals(2147483647L, i(ctx, "GGETFONTSIZE", 7))
    }

    /**
     * 未知字体名**不是**失败口径：:6385-6402 先精确比对 `GlobalStatic.Pfc.Families`
     * （比较口径 = 逐名精确相等，:6392），一个都不命中时落到字符串构造（:6400），
     * 实测 GDI+ 静默回退到 `Microsoft Sans Serif` 而不抛 => 返回值 1。
     */
    @Test
    fun `GSETFONT 未知字体名照样返回 1 且回读实际解析到的族`() {
        val ctx = auditCtx(AuditOps())
        assertEquals(1L, i(ctx, "GCREATE", 8, 10, 10))
        assertEquals(1L, i(ctx, "GSETFONT", 8, "NoSuchFamilyZZZ", 13, 9))
        // GGETFONT 回读的是**解析名**（原版 GraphicsImage.cs:60 的 `Fontname => font.Name`），
        // 请求名解析不到时不许把请求名原样吐回去。移植侧这条分支真正拿去绘制的族是
        // FontSidecarCatalog.fallbackFamily（本夹具没有替代族 => 默认族黑体），与 PC 的
        // `Microsoft Sans Serif` **同构不同族**（移植侧不分发微软版权字体）—— 属平台字体表
        // 差异，完整口径与逐名实测见 PortFonts.resolveFontName 的 KDoc。
        // 样式位 9 = Bold|Underline（:6365-6380 只取 1/2/4/8）。
        assertEquals("黑体", s(ctx, "GGETFONT", 8))
        assertEquals(9L, i(ctx, "GGETFONTSTYLE", 8))
        // 第 4 参的未知位原版同样丢弃（:6365-6380 只看 1/2/4/8）
        assertEquals(1L, i(ctx, "GSETFONT", 8, "黑体", 16, 16))
        assertEquals(0L, i(ctx, "GGETFONTSTYLE", 8))
    }

    // ==================================================================
    // 2) gId 生命周期（口径 3）
    // ==================================================================

    /**
     * `ReadGraphics`（:9325-9337）只校验 0..Int.MAX_VALUE，然后把编号交给
     * `AppContents.GetGraphics`（AppContents.cs:28-37）—— **不存在的编号也会被登记一个空条目**，
     * 这正是基准 651 行 `GDISPOSE(14)=0` 的来源。
     */
    @Test
    fun `gId 生命周期 未创建读 0 且空条目会被登记`() {
        val ctx = auditCtx(AuditOps())
        // 从未用过的编号：GCREATED / GWIDTH / GHEIGHT 一律 0（:6178-6181）
        assertEquals(0L, i(ctx, "GCREATED", 4242))
        assertEquals(0L, i(ctx, "GWIDTH", 4242))
        assertEquals(0L, i(ctx, "GHEIGHT", 4242))
        // 读取把空条目登记上了（不是错误），GDISPOSE 因此返回 0 而不是抛
        assertEquals(0L, i(ctx, "GDISPOSE", 4242))
        // :6946-6949 未创建 → 0，创建后才能成功
        assertEquals(1L, i(ctx, "GCREATE", 4242, 12, 9))
        assertEquals(1L, i(ctx, "GDISPOSE", 4242))
    }

    /**
     * `GDispose`（GraphicsImage.cs:528-560）先无条件 `size = new Size(0,0)`（:530），再释放位图 /
     * 画笔 / 画刷 / 字体并全部置 null ⇒ `GDispose` 之后 GWIDTH / GHEIGHT / GCREATED 一律 0
     * （:6178-6181 的 IsCreated 早退），**字符串侧走 ""、不是抛异常**
     * （:6213-6216 的 `!IsCreated → return ""` 在取 font 之前）。
     */
    @Test
    fun `GDISPOSE 之后的读数是 0 和空串而不是异常`() {
        val ctx = auditCtx(AuditOps())
        assertEquals(1L, i(ctx, "GCREATE", 9, 33, 22))
        assertEquals(1L, i(ctx, "GSETFONT", 9, "黑体", 24, 3))
        assertEquals(1L, i(ctx, "GSETPEN", 9, 0x112233, 5))
        assertEquals(1L, i(ctx, "GSETBRUSH", 9, 0x445566))
        assertEquals(24L, i(ctx, "GGETFONTSIZE", 9))
        assertEquals(1122867L, i(ctx, "GGETPEN", 9))
        assertEquals(4478310L, i(ctx, "GGETBRUSH", 9))

        assertEquals(1L, i(ctx, "GDISPOSE", 9))
        assertEquals(0L, i(ctx, "GCREATED", 9))
        assertEquals(0L, i(ctx, "GWIDTH", 9))
        assertEquals(0L, i(ctx, "GHEIGHT", 9))
        // 这三条的口径都是「IsCreated 为假 → 0 / 空串」，不是「font 为 null → 异常」：
        // 异常那条路只在**已创建但没设过字体**时才走（:6187 → GraphicsImage.cs:62 的 font.Size）。
        assertEquals(0L, i(ctx, "GGETFONTSIZE", 9))
        assertEquals(0L, i(ctx, "GGETFONTSTYLE", 9))
        assertEquals(0L, i(ctx, "GGETPEN", 9))
        assertEquals(0L, i(ctx, "GGETPENWIDTH", 9))
        assertEquals(0L, i(ctx, "GGETBRUSH", 9))
        assertEquals("", s(ctx, "GGETFONT", 9))
        // GDISPOSE 幂等：第二次未创建 → 0（:6946-6949）
        assertEquals(0L, i(ctx, "GDISPOSE", 9))
    }

    /** 已创建但从未设字体：原版这里 `graphicsImage.Fontsize` 直接 NRE（:6187 + GraphicsImage.cs:62）。 */
    @Test
    fun `已创建但未设字体时状态函数炸而非返回 0`() {
        val ctx = auditCtx(AuditOps())
        assertEquals(1L, i(ctx, "GCREATE", 11, 8, 8))
        assertFailsWith<CodeEE> { i(ctx, "GGETFONTSIZE", 11) }
        assertFailsWith<CodeEE> { i(ctx, "GGETFONTSTYLE", 11) }
        assertFailsWith<CodeEE> { i(ctx, "GGETPEN", 11) }
        assertFailsWith<CodeEE> { i(ctx, "GGETBRUSH", 11) }
        // 未创建（→ 0 / ""）与未设置（→ 崩溃）是两条不同的路，别把它们并成一个读数。
        assertEquals(0L, i(ctx, "GCREATED", 909))
    }

    // ==================================================================
    // 3) GCREATE / GCREATEFROMFILE 的尺寸与失败口径（口径 2 / 5）
    // ==================================================================

    /** `MAX_IMAGESIZE` = 8192（AbstractImage.cs:8），边界**包含** 8192；8193 才越界。 */
    @Test
    fun `GCREATE 的 8192 是含边界的上界`() {
        val ctx = auditCtx(AuditOps())
        assertEquals(1L, i(ctx, "GCREATE", 20, 8192, 1))
        assertEquals(8192L, i(ctx, "GWIDTH", 20))
        assertEquals(1L, i(ctx, "GCREATE", 21, 1, 8192))
        assertEquals(8192L, i(ctx, "GHEIGHT", 21))
        // :6829-6844 的四道判断都是抛 CodeEE（GParamIsNegative / GParamTooLarge），不是返回 0
        assertFailsWith<CodeEE> { i(ctx, "GCREATE", 22, 8193, 1) }
        assertFailsWith<CodeEE> { i(ctx, "GCREATE", 23, 1, 8193) }
        assertFailsWith<CodeEE> { i(ctx, "GCREATE", 24, 0, 1) }
        assertFailsWith<CodeEE> { i(ctx, "GCREATE", 25, 1, -7) }
        // 已创建 → 0（:6822-6825），且宽高检查根本不做
        assertEquals(0L, i(ctx, "GCREATE", 20, 8193, 8193))
        // 编号侧的越界才是硬错（ReadGraphics :9328-9335）
        assertFailsWith<CodeEE> { i(ctx, "GCREATE", -1, 10, 10) }
        assertFailsWith<CodeEE> { i(ctx, "GWIDTH", 9223372036854775807L) }
    }

    /**
     * **口径 5（本族最容易写错的一条）**：`Utils.LoadImage`（Utils.cs:502-525）对 `.webp` 只走
     * libwebp 一条路，任何异常都被空 catch（:521-523）吞成 `null`，
     * `GCREATEFROMFILE` 于是 :6901-6904 返回 0。
     *
     * 所以「webp 解不开」必须折成 **0**，既不许抛未定义异常，也**不许回落到 GSAVE 文本载体**。
     * 本用例刻意让同一个路径在文本载体侧放一份**完全合法**的 GSAVE 数据：
     * 回落就会得到 1 与 6x5，原版口径得到 0 与未创建。
     */
    @Test
    fun `webp 解码失败按原版口径返回 0 且不回落文本载体`() {
        val ops = AuditOps()
        // RIFF....WEBP + "VP8 " —— 有损位流，WebpDecoder 登记为不支持（语料 1893 个）
        val lossy = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            24, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
            'V'.code.toByte(), 'P'.code.toByte(), '8'.code.toByte(), ' '.code.toByte(),
            8, 0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8,
        )
        ops.bytes["a.webp"] = lossy
        // 同一份内容写成合法文本载体：回落就会成功
        val seed = auditCtx(ops)
        assertEquals(1L, i(seed, "GCREATE", 60, 6, 5))
        assertEquals(1L, i(seed, "GCLEAR", 60, 0xFF00FF00L))
        assertEquals(1L, i(seed, "GSAVE", 60, 3))
        ops.files["a.webp"] = requireNotNull(ops.files["sav/img0003.png"]) { "文本载体没写进去，装置失效" }

        val ctx = auditCtx(ops)
        assertEquals(0L, i(ctx, "GCREATEFROMFILE", 61, "a.webp"))
        assertEquals(0L, i(ctx, "GCREATED", 61))
        assertEquals(0L, i(ctx, "GWIDTH", 61))

        // 文件不存在 → 0（:6896-6899），同样不是异常
        assertEquals(0L, i(ctx, "GCREATEFROMFILE", 62, "nope.png"))
        // 已创建的缓冲直接 0，且**不求值第 2 参之外的实参**（:6878-6881 在取路径之前）
        assertEquals(1L, i(ctx, "GCREATE", 63, 4, 4))
        assertEquals(0L, i(ctx, "GCREATEFROMFILE", 63, "a.webp", 1))
    }

    /** 第 3 参是「绝对路径开关」（:6883-6895），不改变返回值口径。 */
    @Test
    fun `GCREATEFROMFILE 的第 3 参只影响路径解析`() {
        val ops = AuditOps()
        ops.files["abs.png"] = "不是合法载体也不是 PNG"
        val ctx = auditCtx(ops)
        assertEquals(0L, i(ctx, "GCREATEFROMFILE", 70, "abs.png"))
        assertEquals(0L, i(ctx, "GCREATEFROMFILE", 71, "abs.png", 1))
        // 参数形态 ArgTypeList(Int, String, Int) OmitStart=2 ⇒ 只允许 2 / 3 个（:6855-6867）
        assertFailsWith<CodeEE> { i(ctx, "GCREATEFROMFILE", 72, "abs.png", 1, 1) }
    }

    // ==================================================================
    // 4) 文本度量口径（口径 4）—— 实测的 PC 值与本波读数的差
    // ==================================================================

    /**
     * 对拍装置：GDI+ 侧用 `Graphics.FromImage(new Bitmap(16,16)).MeasureString(s, font,
     * int.MaxValue, StringFormat.GenericTypographic)`，font = `new Font("黑体", size, Regular,
     * GraphicsUnit.Pixel)`，`(long)` 截断一次（FunctionMethodCreator.cs:6584-6587）；
     * 本机 System.Drawing 实测（Windows，2026-10-21，size=16）。移植侧走
     * [measureTextPixels]：黑体没有随包 sidecar => 每个码位都落退化网格
     * （`CellWidth * sizePx / 2`），但**分行、裁行尾空白、组合符零步进**这三条 GDI+ 布局口径
     * 与真实表那一路共用同一份实现（M4-L K-MEASURE 补上，逐码位取证见 TextMeasureAuditTest）。
     *
     * | 串 | PC（黑体 16） | 移植侧 | 判定 |
     * |---|---|---|---|
     * | "ABC" | 24 | 24 | 一致 |
     * | "中文字" | 48 | 48 | 一致（全角 2 格 = 1 em） |
     * | "ＡＢＣ"（全角字母） | 48 | 48 | 一致 |
     * | "１２３４５" | 80 | 80 | 一致 |
     * | "😀"（增补平面，UTF-16 长度 2） | 16 | 16 | 一致（按码位不按码元） |
     * | "abc   "（3 个尾随空格） | 24 | 24 | 一致（GDI+ 裁尾部空白，移植侧同样裁） |
     * | "   "（整串空白） | 0 | 0 | 一致 |
     * | e + U+0301（组合符） | 8 | 8 | 一致（组合符零步进，并进基字簇） |
     * | "AB\nCD" | 16 | 16 | 一致（只按 U+000A 分行，取最宽行） |
     *
     * 仍与 PC 分叉的是**字体表差异**那一类（NBSP 4.25、缺符方框、Cf 零步进、CR 零步进），
     * 不是结构性缺失；逐条读数与卡点登记在 TextMeasureAuditTest 与交付报告。
     */
    @Test
    fun `度量口径 尾随空白与换行与组合符 与 PC 同值`() {
        val ctx = auditCtx(AuditOps())
        // 本来就一致的（PC 实测同值）
        assertEquals(24L, i(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16))
        assertEquals(48L, i(ctx, "GGETTEXTSIZE", "中文字", "黑体", 16))
        assertEquals(48L, i(ctx, "GGETTEXTSIZE", "ＡＢＣ", "黑体", 16))
        assertEquals(80L, i(ctx, "GGETTEXTSIZE", "１２３４５", "黑体", 16))
        // Kotlin 没有 `\U001F600` 这种 8 位转义 => 增补平面字符写成代理对（U+1F600 = D83D DE00）
        assertEquals(16L, i(ctx, "GGETTEXTSIZE", "\uD83D\uDE00", "黑体", 16))
        assertEquals(150L, i(ctx, "GGETTEXTSIZE", "abc", "黑体", 100))
        // M4-L 修掉的三条（PC = 24 / 0 / 8；改前移植侧 = 48 / 24 / 24）
        assertEquals(24L, i(ctx, "GGETTEXTSIZE", "abc   ", "黑体", 16))
        assertEquals(0L, i(ctx, "GGETTEXTSIZE", "   ", "黑体", 16))
        // 分解序列 e + U+0301（组合急性重音符，Mn）；写成转义是为了避免源文件里 NFC/NFD 歧义
        assertEquals(8L, i(ctx, "GGETTEXTSIZE", "\u0065\u0301", "黑体", 16))
        // 分行取最宽（PC 同值 16；高度那一维见下一条用例）
        assertEquals(16L, i(ctx, "GGETTEXTSIZE", "AB\nCD", "黑体", 16))
    }

    /**
     * 高度口径：移植侧是「字号 x 行数」（measureTextPixels 里的 measureHeightPx），PC 是
     * `MeasureString().Height` = GDI 行高「字号 x lineSpacing / emHeight」（黑体 292/256 =
     * 1.140625，本机实测 RawH 18.25@16、114.0625@100）。
     * **行数**这一维移植侧已经对上（"AB\nCD" 两行 -> 32，PC 36.5 -> 36），比例因子还差
     * 1.140625 那一档；空串两边都是 0（改前移植侧恒为字号，是错的）。
     * 基准 `fixtures/golden/baseline/pc-golden-root.txt` 只钉过 `GDRAWTEXT=1`（:648），
     * **宽高从未被断言**，所以这条偏差无处对拍，也不许宣称移植侧的高度对上了；
     * 为什么推不出可复现的推导、需要谁接线，见 measureHeightPx 的 KDoc 与交付报告 ——
     * 本用例只钉住「移植侧现在是什么」。
     */
    @Test
    fun `高度口径是字号乘行数 与 PC 的行高比仍差一档`() {
        val ctx = auditCtx(AuditOps())
        assertEquals(24L, i(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16))
        assertEquals(16L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(48L, i(ctx, "GGETTEXTSIZE", "中文字", "黑体", 16))
        // 移植侧高 = 字号 16；PC 此处是 18（RawH 18.25）=> 宽度对上、高度差行高比那一档
        assertEquals(16L, ctx.store.readInt("RESULT", longArrayOf(1)))
        // 两行：移植侧 32 = 16 x 2，PC 36（RawH 36.5）=> 行数维度同值、比例因子不同值
        assertEquals(16L, i(ctx, "GGETTEXTSIZE", "AB\nCD", "黑体", 16))
        assertEquals(32L, ctx.store.readInt("RESULT", longArrayOf(1)))
        // 空串：PC 宽 0 高 0，移植侧改后同为 0（改前高恒为字号 16）
        assertEquals(0L, i(ctx, "GGETTEXTSIZE", "", "黑体", 16))
        assertEquals(0L, ctx.store.readInt("RESULT", longArrayOf(1)))
    }

    /**
     * `GDRAWTEXT` 的口径（:6475-6529）：参数形态 Int/String/Int/Int 且 `OmitStart = 2`
     * ⇒ 2/3/4 个都合法；**3 个时两条绘制分支都不进、但仍然测量并回写 RESULT**（:6509-6517），
     * 返回值恒 1（:6528），RESULT_ARRAY[1]=宽、[2]=高（:6526-6527）。
     * 未设字体时测量用的是 `Font(Config.FontName, **100f**, Console.StringStyle.FontStyle)`
     * （:6522 —— 100 是原写字面量），退化网格下 1 格 = 100/2 = 50 px。
     */
    @Test
    fun `GDRAWTEXT 三个实参时不求值坐标但仍回写 RESULT`() {
        val ctx = auditCtx(AuditOps())
        assertEquals(0L, i(ctx, "GDRAWTEXT", 500, "ABC"))
        assertEquals(1L, i(ctx, "GCREATE", 500, 10, 10))
        // 2 参 / 4 参同值；3 参不抛（原版 OmitStart=2 允许）
        assertEquals(1L, i(ctx, "GDRAWTEXT", 500, "ABC", 1))
        assertEquals(1L, i(ctx, "GDRAWTEXT", 500, "ABC", 0, 0))
        assertEquals(150L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(100L, ctx.store.readInt("RESULT", longArrayOf(2)))
        // 设过字体后改用缓冲自带的字号（:6519 → GraphicsImage.cs:89 的 Fnt）
        assertEquals(1L, i(ctx, "GSETFONT", 500, "黑体", 16))
        assertEquals(1L, i(ctx, "GDRAWTEXT", 500, "ABC", 0, 0))
        assertEquals(24L, ctx.store.readInt("RESULT", longArrayOf(1)))
    }

    // ==================================================================
    // 5) 语料真实图片（口径 5；缺环境变量即跳过）
    // ==================================================================

    /**
     * 语料真实 PNG（`resources/CharaTH98` 等）走 `GCREATEFROMFILE`，尺寸与 ImageIO 读到的
     * 逐一对上；`ERATW_CORPUS` / `ERATW_GAMEDIR` 缺失时整例跳过（本波 1 例吃语料）。
     */
    @Test
    fun `语料真实 PNG 的尺寸读数与 ImageIO 一致`() {
        val root = listOfNotNull(System.getenv("ERATW_CORPUS"), System.getenv("ERATW_GAMEDIR"))
            .map { it.trim() }.firstOrNull { it.isNotEmpty() }
        Assumptions.assumeTrue(root != null, "跳过：未设置 ERATW_CORPUS / ERATW_GAMEDIR")
        val found = File(root!!).walkTopDown()
            .filter { it.isFile && it.extension.equals("png", true) && it.length() in 100L..2_000_000L }
            .firstOrNull()
        Assumptions.assumeTrue(found != null, "跳过：$root 下没有可读的 PNG 语料")
        // `Assumptions.assumeTrue` 不带 Kotlin contract，不会把判据变成智能转换；
        // assume 失败即抛 TestAbortedException，走不到 `!!`，故这里显式收尾。
        val png = found!!
        val ref = ImageIO.read(png).also {
            Assumptions.assumeTrue(it != null, "跳过：ImageIO 读不了 ${png.absolutePath}")
        }!!

        val ops = AuditOps()
        ops.bytes["corpus.png"] = png.readBytes()
        val ctx = auditCtx(ops)
        assertEquals(1L, i(ctx, "GCREATEFROMFILE", 800, "corpus.png", 1))
        assertEquals(ref.width.toLong(), i(ctx, "GWIDTH", 800))
        assertEquals(ref.height.toLong(), i(ctx, "GHEIGHT", 800))
        // alpha == 0 的像素被 GDI+ 的 source-over 归零（GraphicsImage.cs:119-130，
        // 移植侧在 readImageFile 里做同一件事）：透明处读到的必须是 0，不是 0x00RRGGBB。
        var transparentSeen = 0
        var x = 0
        while (x < ref.width && transparentSeen < 3) {
            var y = 0
            while (y < ref.height && transparentSeen < 3) {
                if ((ref.getRGB(x, y) ushr 24) == 0) {
                    assertEquals(0L, i(ctx, "GGETCOLOR", 800, x, y))
                    transparentSeen++
                }
                y += 7
            }
            x += 7
        }
    }
}
