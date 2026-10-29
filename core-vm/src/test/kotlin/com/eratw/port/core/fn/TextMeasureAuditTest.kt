package com.eratw.port.core.fn

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.font.FontAdvanceTable
import com.eratw.port.core.font.FontSidecarCatalog
import com.eratw.port.core.font.SLOT_FONT_SIDECAR
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * M4-L `k-measure` —— 文本度量的**逐类实测对拍**：j-gfxaudit 移交的三条分歧
 * （尾随空白 / 换行 / 组合符）+ 增补平面一类、`GGETFONT` 的归一名、`GDRAWTEXT` 未设字体那条分支。
 *
 * ## PC 侧装置
 *
 * 本机 Windows 的 GDI+（`System.Drawing`，2026-10-21 实测），调用式逐字照抄原版
 * （spec: `reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * 的 `:6518-6528` = GDRAWTEXT，截断在 `:6526-6527`；`:6584-6587` = GGETTEXTSIZE）：
 *
 * ```text
 * font  = new Font("黑体", size, FontStyle.Regular, GraphicsUnit.Pixel)
 * sizeF = Graphics.FromImage(new Bitmap(16, 16)).MeasureString(s, font, int.MaxValue, StringFormat.GenericTypographic)
 * W = (long)sizeF.Width   H = (long)sizeF.Height
 * ```
 *
 * 黑体的字体表（同一装置读 `FontFamily` / `Font.GetHeight`）：
 * `emHeight = 256`、`lineSpacing = 292`、`cellAscent = 220`、`cellDescent = 36`
 * ⇒ `lineSpacing/emHeight = 1.140625`、`(cellAscent+cellDescent)/emHeight = 1.0`、
 * `GetHeight` = 18.25 @16 / 114.0625 @100。
 * 「`字号 × lineSpacing/emHeight` 能不能从 advance 表推出来」要查的就是这个式子，
 * 结论：**行数推不出错、行高比推不出**（§3 的表没有那三个竖度量字段）⇒ 见下面
 * [高度口径是字号乘以行数 与 PC 的行高比差一档] 那条用例的 TODO(verify)。
 *
 * ## 移植侧口径与三态
 *
 * 没有 §3 表时走 `core/host/CellWidth.kt` 的退化网格：半角 1 格 = `size/2` px、全角 2 格 = `size` px。
 * 黑体 @16 实测 `A` = 8、`中` = 16、`Ａ` = 16、`ｱ` = 8、`😀` = 16；@100 半角步进正是 50
 * ⇒ **退化网格与 PC 的拉丁/CJK 步进同值**；分叉集中在「小数步进」那一类（黑体没画的符落进
 * 回退字体的方框：NBSP 4.25、U+2000 系 0 到 16、U+0007 16、U+000C 13.78、`Mn` 里的 U+093C 9.5078…）。
 *
 * | 串 | PC（黑体 16，raw → 截断） | 移植侧（本波后） | 三态 |
 * |---|---|---|---|
 * | `"abc"` | 24.0 → 24 | 24 | 同值 |
 * | `"abc   "` | 24.0 → 24 | 24 | 已修，同值 |
 * | `"   "` | 0 → 0 | 0 | 已修，同值 |
 * | `" abc"` | 32.0 → 32 | 32 | 同值（前导不裁） |
 * | `"abc def"` | 56.0 → 56 | 56 | 同值（行中空格照计） |
 * | `"abc　"`（U+3000 收尾） | 24.0 → 24 | 24 | 已修，同值 |
 * | `"　abc"` | 40.0 → 40 | 40 | 同值 |
 * | `"abc\u00A0"` | 28.25 → 28 | 40 | 字体表差异（NBSP 步进 4.25 vs 2 格） |
 * | `"AB\nCD"` | W 16 / H 36.5 → 36 | W 16 / H 32 | 宽度已修同值；高度差一个行高比 |
 * | `"AB\n"` | W 16 / H 18.25 → 18 | W 16 / H 16 | 同上 |
 * | `"\nAB"` | W 16 / H 36.5 → 36 | W 16 / H 32 | 同上 |
 * | `""` | W 0 / H 0 | W 0 / H 0 | 已修，同值 |
 * | `" "`（单码位空格） | W 0 / H 16 | W 8 @16 / 9 @18 | **有意分叉**：单码位走步进查询通道不裁尾（邻路 `FnWidthSingleSourceTest` 钉 `CellWidth` 的半角档），登记不修 |
 * | `"e\u0301"` | 8.0 → 8 | 8 | 已修，同值 |
 * | `"e\u0903"`（间距型 Mc） | 21.65625 → 21 | 24 | 字体表差异 |
 * | `"e" + Cf + "b"`（U+200B 等） | 16.0 → 16 | 32 | 字体表差异（Cf 不属本波四类，移植侧记全角 2 格 = 16） |
 * | `"😀"` | 16 → 16 | 16 | 同值（按码位不按码元） |
 * | `"\uD83D"`（代理项半） | 16 → 16 | 16 | 同值 |
 * | `"AB\r\nCD"` | W 16 / H 36.5 | W 24 / H 32 | 结构性缺失：GDI+ 给 CR 零步进，移植侧 CR 记 1 格 |
 */
private class MeasOps : FileOps {
    val files = LinkedHashMap<String, String>()
    override fun exists(path: String): Boolean = files.containsKey(path)
    override fun readText(path: String): String? = files[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean {
        files[path] = if (append) (files[path] ?: "") + text else text
        return true
    }

    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private class MeasBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("MeasBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

/**
 * 度量对拍宿主。[installed] 非 null 时接 `CHKFONT` 的已安装字体点查（`GSETFONT` 判「可解析」用它）。
 *
 * `consoleStyle` **故意不接**：接了就会走 `ConfigMethods.storeFromHost` 的 `seedHostState`，
 * 把条目 `FontName` 播成控制台现用名（core/fn/ConfigMethods.kt:235-236）—— 那正是本波要
 * 分开的两个来源（`Config.FontName` 与 `Console.StringStyle.Fontname`），
 * 见 [GDRAWTEXT 未设字体时按 Config 的字体名与字面量 100 测量]。
 */
private class MeasHost(
    override val fileOps: FileOps? = MeasOps(),
    private val installed: Set<String>? = null,
) : BuiltinHost {
    override val installedFonts: Set<String>? get() = installed
}

private fun measCtx(host: BuiltinHost = MeasHost()): BuiltinEvalContext {
    val store = VariableStore()
    val base = MeasBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, host)
}

private fun mCall(ctx: BuiltinEvalContext, name: String, vararg args: Any): Value =
    ctx.callFunction(
        name,
        args.map { if (it is String) Value.StrV(it) else Value.IntV((it as Number).toLong()) },
    )

private fun mI(ctx: BuiltinEvalContext, name: String, vararg args: Any): Long =
    (mCall(ctx, name, *args) as Value.IntV).v

private fun mS(ctx: BuiltinEvalContext, name: String, vararg args: Any): String =
    (mCall(ctx, name, *args) as Value.StrV).v

/** GGETTEXTSIZE 之后 RESULT 第 1 槽 = 高度（spec: FunctionMethodCreator.cs:6586；返回值才是宽度 :6587）。 */
private fun mHeight(ctx: BuiltinEvalContext): Long = ctx.store.readInt("RESULT", longArrayOf(1))

private fun measCatalog(ctx: BuiltinEvalContext): FontSidecarCatalog =
    ctx.store.aux.slot(SLOT_FONT_SIDECAR) { FontSidecarCatalog() }

/**
 * 只有空格（400）与 `A`（800）两行的夹具表，`unitsPerEm = 1280`（与 Saitamaar 同 upm，
 * 步进取本机 fontTools 实测的 hmtx 值，见 `FontSidecarMetricsTest` 同一族夹具）
 * ⇒ @16 时 `A` = 10.0、空格 = 5.0；@100 时 `A` = 62.5、空格 = 31.25。
 * 用途：把「行切分 / 裁尾」两条结构性口径同时跑在**真实表**那一路（不许只在退化网格里成立）。
 */
private const val FIXTURE_TWO_ROWS = "#family MeasureFont\n#unitsPerEm 1280\n" +
    "0020 400 0\n" +
    "0041 800 0\n"

/** 只填 `FontName` 一条的条目表（其余保持注册默认，spec: ConfigData.cs:52-220）。 */
private fun fontEntryStore(fontName: String): ConfigStore =
    ConfigStore().apply { itemOf(ConfigKey.FontName).assign(ConfigValue.StringValue(fontName)) }

class TextMeasureAuditTest {

    // ==================================================================
    // 1) 尾随空白：GenericTypographic 裁掉行尾空白，前导与行中不裁
    // ==================================================================

    /**
     * PC 实测（黑体 16）：`"abc   "` = 24.0、`"   "` = 0、`" abc"` = 32.0、`"abc def"` = 56.0、
     * `"abc　"`（U+3000 收尾）= 24.0、`"　abc"` = 40.0、`"  　"` = 0。
     * 移植侧的裁尾集 = **只有** U+0020 与 U+3000；反例清单（实测都不裁）写在
     * `GraphicsMethods.kt` 的裁尾判据 KDoc 里：U+00A0 = 28.25、U+0009 = 24.0078、
     * U+2000 系是小数步进 ⇒ 把它们折成「裁掉」就是拿几例凑式子，不落地。
     */
    @Test
    fun `尾随空白被裁而前导不裁`() {
        val ctx = measCtx()
        assertEquals(24L, mI(ctx, "GGETTEXTSIZE", "abc", "黑体", 16))
        assertEquals(24L, mI(ctx, "GGETTEXTSIZE", "abc   ", "黑体", 16))
        assertEquals(24L, mI(ctx, "GGETTEXTSIZE", "abc        ", "黑体", 16))
        assertEquals(0L, mI(ctx, "GGETTEXTSIZE", "   ", "黑体", 16))
        assertEquals(32L, mI(ctx, "GGETTEXTSIZE", " abc", "黑体", 16))
        assertEquals(56L, mI(ctx, "GGETTEXTSIZE", "abc def", "黑体", 16))
        assertEquals(24L, mI(ctx, "GGETTEXTSIZE", "abc　", "黑体", 16))
        assertEquals(40L, mI(ctx, "GGETTEXTSIZE", "　abc", "黑体", 16))
        assertEquals(0L, mI(ctx, "GGETTEXTSIZE", "  　", "黑体", 16))
        // 裁完整行空 ⇒ 宽 0（PC 同值），但行数仍是 1 ⇒ 高度照给字号
        assertEquals(16L, mHeight(ctx))
    }

    /**
     * 裁尾与行切分必须也作用在**真实 advance 表**那一路。夹具表 @16：`A` = 10.0、空格 = 5.0。
     * PC 侧同一类口径的读数是 `"abc   "` = 24 = 不裁时的 24（步进被裁掉），
     * 表这一路按比例步进同理：`"AAA   "` = 30 = `"AAA"`，`" AAA"` = 5.0 + 30.0 = 35（前导不裁）。
     */
    @Test
    fun `裁尾在真实表那一路同样生效`() {
        val ctx = measCtx()
        measCatalog(ctx).put(FontAdvanceTable.parse(FIXTURE_TWO_ROWS))
        assertEquals(30L, mI(ctx, "GGETTEXTSIZE", "AAA", "MeasureFont", 16))
        assertEquals(30L, mI(ctx, "GGETTEXTSIZE", "AAA   ", "MeasureFont", 16))
        assertEquals(35L, mI(ctx, "GGETTEXTSIZE", " AAA", "MeasureFont", 16))
        assertEquals(0L, mI(ctx, "GGETTEXTSIZE", "   ", "MeasureFont", 16))
        assertEquals(16L, mHeight(ctx))
        // 整串一次截断（不是逐字）：@100 三个 A = 62.5 x 3 = 187.5 → 187
        assertEquals(187L, mI(ctx, "GGETTEXTSIZE", "AAA", "MeasureFont", 100))
        // 表里没有 B/C ⇒ 逐位退化：A 62.5 + B 50 + C 50 = 162.5 → 162
        assertEquals(162L, mI(ctx, "GGETTEXTSIZE", "ABC", "MeasureFont", 100))
    }

    /**
     * NBSP 既不裁尾、步进也不同：PC 实测 `"abc\u00A0"` = 28.25（黑体的 NBSP = 68/256 em = 4.25 px）；
     * 移植侧 U+00A0 落在全角档 ⇒ 24 + 16 = 40。
     * **三态 = 字体表差异**：要贴 PC 得让 §3 表里有 U+00A0 那一行（覆盖由 c-fontsub 的子集决定）
     * ⇒ 登记不修正，写进交付「需组长接线」。
     */
    @Test
    fun `NBSP 不裁尾且步进按退化网格`() {
        val ctx = measCtx()
        assertEquals(40L, mI(ctx, "GGETTEXTSIZE", "abc\u00A0", "黑体", 16))
        assertEquals(24L, mI(ctx, "GGETTEXTSIZE", "abc", "黑体", 16))
    }

    /**
     * 两条通道的分界（组长裁定，oracle label `one-space` / `3sp` / `space-x2` 系）：
     * 行尾裁剪是**整串布局**的规则，一行恰好一个码位时输入形态是**单码位步进查询** ⇒ 不裁。
     *
     * PC 侧 `" "` 整串实测 0（label `one-space`），移植侧这一档给 [CellWidth] 的半角档
     * （@16 = 8、@18 = 9）⇒ **有意分叉一档**，登记不修：
     * 邻路 `FnWidthSingleSourceTest` 的 `ASCII 与半角假名按半角计` 钉的正是同一张表的半角档
     * （@18 = 9），且不许改它的期望 ⇒ 裁剪路径一旦接进格宽查询就是本波那条真回归
     * （`GFX 侧 U+0020 expected 9 but was 0`）。
     * 两个码位以上仍按 PC 裁尾：`"   "` = 0（label `3sp`）、`"　　"` = 0、
     * `"a "` = 8（label `a-space`）、`" a"` = 16（label `sp-a`，前导不裁）。
     *
     * 全部期望 = 移植侧 2026-10-21 离线装置实跑（借主 worktree 的 `core-vm` main 产物，
     * 直调 `GGETTEXTSIZE`），不是推算值。
     */
    @Test
    fun `单码位空格走步进查询而不裁尾`() {
        val ctx = measCtx()
        // 单码位：不裁（与 PC 差一档，见上）
        assertEquals(9L, mI(ctx, "GGETTEXTSIZE", " ", "黑体", 18))
        assertEquals(8L, mI(ctx, "GGETTEXTSIZE", " ", "黑体", 16))
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", "\u3000", "黑体", 16))
        // 两个码位起：整串裁尾照旧 ⇒ PC 同值
        assertEquals(0L, mI(ctx, "GGETTEXTSIZE", "  ", "黑体", 16))
        assertEquals(0L, mI(ctx, "GGETTEXTSIZE", "   ", "黑体", 16))
        assertEquals(0L, mI(ctx, "GGETTEXTSIZE", "\u3000\u3000", "黑体", 16))
        assertEquals(8L, mI(ctx, "GGETTEXTSIZE", "a ", "黑体", 16))
        assertEquals(8L, mI(ctx, "GGETTEXTSIZE", "a\u3000", "黑体", 16))
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", " a", "黑体", 16))
        // 裁完整行空 ⇒ 行数仍 1 ⇒ 高度照给字号（PC `one-space` 的 H = 16 同值）
        assertEquals(16L, mHeight(ctx))
        // 不在裁尾集里的空白：两个码位也不裁（NBSP 记全角 2 格，PC 实测步进 4.25 ⇒ 字体表差异）
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", "\u00A0", "黑体", 16))
        assertEquals(32L, mI(ctx, "GGETTEXTSIZE", "\u00A0\u00A0", "黑体", 16))
        // TAB 不是裁尾符：`" \t"` 两个码位、行尾是 TAB ⇒ 8 + 8 = 16
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", " \t", "黑体", 16))
        // 串尾那个换行不多算一行 ⇒ 剩下唯一一行是单码位空格 ⇒ 走步进查询 8（高仍 1 行 = 16）
        assertEquals(8L, mI(ctx, "GGETTEXTSIZE", " \n", "黑体", 16))
        assertEquals(16L, mHeight(ctx))
    }

    // ==================================================================
    // 2) 换行：只按 U+000A 分行、取最宽那一行、串尾那个换行不多算一行
    // ==================================================================

    /**
     * PC 实测（黑体 16）宽度：`"AB\nCD"` = 16、`"AB\n"` = 16、`"\nAB"` = 16、`"AB\n\n"` = 16、
     * `"AB\n\nCD"` = 16、`"a\nbcd\nef"` = 24、`"AB\nC D "` = 24、`"中文字\nＡＢＣ"` = 48、
     * `"\n"` = 0、`""` = 0 ⇒ **按行取最宽** + **逐行裁行尾空白** + **串尾换行不多算一行**。
     * 移植侧旧口径把 `\n` 当一个码位累加（`"AB\nCD"` 得 32），本波改掉。
     */
    @Test
    fun `按换行分行取最宽 串尾换行不多算一行`() {
        val ctx = measCtx()
        val lf = "\n"
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", "AB" + lf + "CD", "黑体", 16))
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", "AB" + lf, "黑体", 16))
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", lf + "AB", "黑体", 16))
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", "AB" + lf + lf, "黑体", 16))
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", "AB" + lf + lf + "CD", "黑体", 16))
        assertEquals(24L, mI(ctx, "GGETTEXTSIZE", "a" + lf + "bcd" + lf + "ef", "黑体", 16))
        assertEquals(24L, mI(ctx, "GGETTEXTSIZE", "AB" + lf + "C D ", "黑体", 16))
        assertEquals(48L, mI(ctx, "GGETTEXTSIZE", "中文字" + lf + "ＡＢＣ", "黑体", 16))
        // 行数：AB / CD 两行 ⇒ 移植侧 2 × 16 = 32（PC 36，差行高比 ⇒ 见高度那一组）
        assertEquals(32L, mHeight(ctx))
        assertEquals(0L, mI(ctx, "GGETTEXTSIZE", lf, "黑体", 16))
        assertEquals(16L, mHeight(ctx))
        assertEquals(0L, mI(ctx, "GGETTEXTSIZE", "", "黑体", 16))
        assertEquals(0L, mHeight(ctx))
    }

    /**
     * `\r` / `\v` / NEL / U+2029 **都不是** GenericTypographic 的分行符：PC 实测
     * `"AB" + X + "CD"` 的高全是单行的 18.25（只有 X = U+000A 才跳到 36.5）。
     * 移植侧同样只认 LF ⇒ 这一条两边同构（宽度值不同见下一句）。
     *
     * 宽度分叉（**三态 = 结构性缺失，两侧口径各有依据**）：PC 实测 CR/VT/NEL/U+2029 的步进
     * 分别是 0 / 0 / 0 / 0（`"AB\rCD"` = 32 = 只有 AB 与 CD 的宽），移植侧按
     * `core/host/CellWidth.kt` 的 CP932 字节档给 C0 控制符 1 格 = 8（D 类单字节档，
     * 证据是 `EmueraConsole.cs:3255` 的**字节数**口径），NEL/U+2029 落表外 ⇒ 全角 2 格 = 16
     * ⇒ 移植侧 40 / 40 / 48 / 48。「屏上占几个字节」与「笔位推进几像素」是两个口径，
     * 不许互相冒充（契约 §0.5）；改它要动 CellWidth（M6-RENDER-VIEW 刚按 D 类改过，本路禁改）
     * ⇒ 登记 + 写进「需组长接线」。
     */
    @Test
    fun `换行只认 LF 其余控制符不分行`() {
        val ctx = measCtx()
        assertEquals(1L, mI(ctx, "GCREATE", 60, 8, 8))
        assertEquals(1L, mI(ctx, "GSETFONT", 60, "黑体", 16))
        val cases = linkedMapOf(
            "CR U+000D" to ("\r" to 40L),
            "VT U+000B" to ("\u000B" to 40L),
            "NEL U+0085" to ("\u0085" to 48L),
            "PS U+2029" to ("\u2029" to 48L),
        )
        for ((label, pair) in cases) {
            val x = pair.first
            assertEquals(1L, mI(ctx, "GDRAWTEXT", 60, "AB" + x + "CD", 0, 0), label)
            assertEquals(pair.second, ctx.store.readInt("RESULT", longArrayOf(1)), label)
            // 不分行 ⇒ 高仍是 1 个字号
            assertEquals(16L, ctx.store.readInt("RESULT", longArrayOf(2)), label)
        }
        // LF 才分行：两串同样宽，但高度翻成两行
        assertEquals(1L, mI(ctx, "GDRAWTEXT", 60, "AB\nCD", 0, 0))
        assertEquals(16L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(32L, ctx.store.readInt("RESULT", longArrayOf(2)))
        // CRLF：PC 实测 W=16 / H=36.5（CR 零步进 + LF 分行）；移植侧 CR 记 1 格 ⇒ W=24 / H=32
        assertEquals(1L, mI(ctx, "GDRAWTEXT", 60, "AB\r\nCD", 0, 0))
        assertEquals(24L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(32L, ctx.store.readInt("RESULT", longArrayOf(2)))
    }

    // ==================================================================
    // 3) 组合符与增补平面
    // ==================================================================

    /**
     * PC 实测（黑体 16）：`"e\u0301"` = 8.0（组合符并进基字簇，不推笔位；移植侧旧口径按两个
     * 码位给 24，本波改掉）、`"\u0301e"` = 8.0、`"e\u0300"` = 8.0、`"e\u064E"` = 8.0、
     * `"e\u20D0"` = 8.898（截断后仍 8）、`"一\u0301"` = 16.0、单独一个符 = 0、`"e X "` = 8.898。
     * 移植侧按 Unicode 类别 `Mn`（NON_SPACING_MARK）+ `Me`（ENCLOSING_MARK）给 0 步进；
     * 间距型 `Mc`（U+0903 / U+0BBE）**不**归零。
     *
     * 与 PC 分叉的是**黑体没有的符**（GDI+ 落进回退字体的方框，实测有真步进：
     * `U+093C` / `U+0951` / `U+0E31` 各 9.5078、`U+1AB0` / `U+20DD` / `U+FE00` / `U+FE0F` 各 16、
     * `U+0903` 13.65625、`U+0BBE` 20.85938、`U+0E33` 16.22656、`U+302A` 16）⇒ 归零后偏小。
     * **三态 = 字体表差异**：真实步进是字体数据，一旦 §3 表里有那一行，表值优先、不走这条近似
     * （语料里没有这类码位）⇒ 登记不修正。
     */
    @Test
    fun `组合符零步进而间距型不归零`() {
        val ctx = measCtx()
        assertEquals(8L, mI(ctx, "GGETTEXTSIZE", "e\u0301", "黑体", 16))
        assertEquals(8L, mI(ctx, "GGETTEXTSIZE", "\u0301e", "黑体", 16))
        assertEquals(8L, mI(ctx, "GGETTEXTSIZE", "e\u0300", "黑体", 16))
        assertEquals(8L, mI(ctx, "GGETTEXTSIZE", "e\u0301\u0301", "黑体", 16))
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", "一\u0301", "黑体", 16))
        assertEquals(48L, mI(ctx, "GGETTEXTSIZE", "中文字\u0301", "黑体", 16))
        // 零步进的符单独成串 ⇒ 宽 0（PC 同为 0），但高度按 1 行给字号（PC 这里 18.25 → 18）
        assertEquals(0L, mI(ctx, "GGETTEXTSIZE", "\u0301", "黑体", 16))
        assertEquals(16L, mHeight(ctx))
        // 组合符后面跟尾随空格：先裁尾 ⇒ 8（PC "e X " = 8.898 → 8）
        assertEquals(8L, mI(ctx, "GGETTEXTSIZE", "e\u0301 ", "黑体", 16))
        // 间距型 Mc 不归零：移植侧按全角 2 格给 8 + 16 = 24（PC 21.65625 → 21）
        assertEquals(24L, mI(ctx, "GGETTEXTSIZE", "e\u0903", "黑体", 16))
    }

    /**
     * 格式控制符（`Cf`）不在本波四类之内 ⇒ 维持退化网格读数，登记与 PC 的分叉。
     *
     * 移植侧（2026-10-21 离线装置实跑，`core-vm` main 类 + `GGETTEXTSIZE`）：
     * `CellWidth` 把 U+200B / U+200C / U+200D / U+FEFF / U+2060 / U+180E 全记**全角 2 格**
     * ⇒ @16 每个符 16px：`"a" + X + "b"` = 8 + 16 + 8 = **32**、`U+200C + "b"` = 16 + 8 = **24**。
     * PC 侧（组长 oracle）：同一批符的步进实测都是 **0** —— `zwsp-plus-b` = 8（= 只有 `b` 那一步）、
     * `AB` = 16（两个半角步进 8 + 8）⇒ `"a" + X + "b"` 的 PC 值是 **16** 而不是 32，
     * 两条读数的差是**一个全角档 = 16px**（先前版本按「Cf 记 1 格」算成差 8，实测档位是 2 格）。
     * 单独一个 ZWSP 成串：移植侧 16、PC 0（零步进 + 整串裁剪双双不成立）。
     *
     * 为什么不顺手把它们也归零：`Cf` 含 U+0600 一类**有**步进的控制符，
     * 归零是「凑上实测的 6 个点」而不是可推的判据；要修得连 §3 表与邻路的 `CellWidth` 档位一起定
     * （属组长接线）。末句 `"ab"` = **16** 是这条分叉的尺子本身：两个半角步进、不含任何控制符，
     * 与 PC 的 `AB` = 16 同值（先前版本误写成 24，那是 `"abc"` 的读数）。
     */
    @Test
    fun `格式控制符不归零 与 PC 的零步进分叉`() {
        val ctx = measCtx()
        assertEquals(32L, mI(ctx, "GGETTEXTSIZE", "a\u200Bb", "黑体", 16))
        assertEquals(32L, mI(ctx, "GGETTEXTSIZE", "a\uFEFFb", "黑体", 16))
        // 移植侧 8 + 8 = 16；PC oracle `AB` = 16 ⇒ 同值（差值全在 Cf 那一个码位上）
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", "ab", "黑体", 16))
        // U+200C 单码位 = 全角 2 格 ⇒ 16 + 8 = 24；PC `zwsp-plus-b` = 8 ⇒ 差 16
        assertEquals(24L, mI(ctx, "GGETTEXTSIZE", "\u200Cb", "黑体", 16))
    }

    /**
     * 增补平面按**码位**不按 UTF-16 码元：PC 实测 `"😀"` = 16（1 em = 一个全角位）、
     * `"😀A"` = 24、`"A😀"` = 24、`"😀😀"` = 32、`"\uD83D"`（代理项半）= 16、`"\uDE00"` = 16、
     * `"\uD800\uDC00"`（U+10000）= 16、`"😀   "` = 16（裁尾照旧）、@100 时 `"😀"` = 100。
     * 移植侧 `CellWidth` 对全角与代理项半都给 2 格 ⇒ 逐条**同值**
     * （j-gfxaudit 的表里这类本来同值，本波把代理项半、串接与裁尾组合补齐成读数）。
     */
    @Test
    fun `增补平面与代理项半的步进`() {
        val ctx = measCtx()
        val emoji = "\uD83D\uDE00"
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", emoji, "黑体", 16))
        assertEquals(24L, mI(ctx, "GGETTEXTSIZE", emoji + "A", "黑体", 16))
        assertEquals(24L, mI(ctx, "GGETTEXTSIZE", "A" + emoji, "黑体", 16))
        assertEquals(32L, mI(ctx, "GGETTEXTSIZE", emoji + emoji, "黑体", 16))
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", "\uD83D", "黑体", 16))
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", "\uDE00", "黑体", 16))
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", "\uD800\uDC00", "黑体", 16))
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", emoji + "   ", "黑体", 16))
        assertEquals(100L, mI(ctx, "GGETTEXTSIZE", emoji, "黑体", 100))
        assertEquals(300L, mI(ctx, "GGETTEXTSIZE", "中文字", "黑体", 100))
    }

    // ==================================================================
    // 4) 高度口径：钉移植侧现状（字号 × 行数），PC 的行高比推不出
    // ==================================================================

    /**
     * PC 实测（黑体，raw → `(long)` 截断）：
     * - `"ABC"` → 16.0 → **16**；@100 → 100.0 → **100**（单行整行落西文档：`字号 × (220+36)/256`）
     * - `"中文字"` → 18.25 → **18**；@100 → 114.0625 → **114**（`字号 × 292/256`）
     * - `"AB\nCD"` → 36.5 → **36**；@100 → 228.125 → **228**（2 行 × 1.140625 × 字号）
     * - `"a\nbcd\nef"` → 54.75 → **54**；@100 → 342.1875 → **342**（3 行）
     * - `""` → 0 → **0**
     *
     * 移植侧 = `字号 × 行数` ⇒ `"ABC"` 16（**同值**）、`""` 0（**同值**）、`"中文字"` 16（PC 18）、
     * `"AB\nCD"` 32（PC 36）、@100 两行 200（PC 228）。
     *
     * TODO(verify)：**没有可复现推导**，不是没去推。查的式子是
     * `MeasureString().Height == 字号 × lineSpacing / emHeight`（单行西文档那一档换成
     * `(cellAscent + cellDescent)/emHeight`）：式子在 PC 侧成立
     * （16 × 292/256 = 18.25、100 × 292/256 = 114.0625、`font.GetHeight(g)` 同值），
     * 但等号右边要的 `lineSpacing` / `cellAscent` / `cellDescent` **一个都不在 §3 的握手数据里**
     * （[FontAdvanceTable] 只有 `unitsPerEm` + 逐码位 `advance`/`lsb`）⇒ advance 表推不出行高。
     * 而且 PC 有**两档**：单行落西文档给 1.0、其余给 1.140625 —— 实测 `１２３`（U+FF11）按 1.0
     * 而 `ＡＢＣ`（U+FF21）按 1.140625，两者在退化网格里是同一档 ⇒ 任何按 `CellWidth` 分档的
     * 近似都在这一对上分叉，也没有可推的依据（那是 GDI+ 的字体链接/分档事实）。
     * 三条卡点与出口（逐条写进交付「需组长接线」）：
     * ① 侧车扩 `#lineSpacing` / `#cellAscent` / `#cellDescent`（生成侧属 c-fontsub，
     *   产物与 `render 模块` 本路禁改）；② 「落进西文档」的判定表需要 PC 实机逐码位取证；
     * ③ 基准 `fixtures/golden/baseline/pc-golden-root.txt` 从未断言过宽高（只有 `:648` 的
     *   `GDRAWTEXT=1`）⇒ 没有闸钉得住它。
     * 禁令登记：**不许**把 `1.140625` 当通用系数写进代码 —— 那是黑体的行高比，与随包的
     * Saitamaar 子集（`unitsPerEm = 1280`）无关，写死就是只对上三例的凑式。
     */
    @Test
    fun `高度口径是字号乘以行数 与 PC 的行高比差一档`() {
        val ctx = measCtx()
        assertEquals(24L, mI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 16))
        assertEquals(16L, mHeight(ctx))
        // PC：18（raw 18.25）。移植侧：16 ⇒ 已知偏差，不宣称与 PC 一致。
        assertEquals(48L, mI(ctx, "GGETTEXTSIZE", "中文字", "黑体", 16))
        assertEquals(16L, mHeight(ctx))
        // PC：36（raw 36.5）。移植侧：2 × 16 = 32 ⇒ 行数对上，行高比仍缺。
        assertEquals(16L, mI(ctx, "GGETTEXTSIZE", "AB\nCD", "黑体", 16))
        assertEquals(32L, mHeight(ctx))
        assertEquals(0L, mI(ctx, "GGETTEXTSIZE", "", "黑体", 16))
        assertEquals(0L, mHeight(ctx))
        assertEquals(100L, mI(ctx, "GGETTEXTSIZE", "AB\nCD", "黑体", 100))
        assertEquals(200L, mHeight(ctx))
        // 宽 = 三个半角步进 50×3 = 150（PC oracle `abc` @100 = 150 ⇒ 同值；先前版本误写成 100，
        // 那是**高度**的读数不是宽度的）；高 = 1 行 × 100 = 100（PC `AB` @100 = 100 ⇒ 纯拉丁这一档同值）
        assertEquals(150L, mI(ctx, "GGETTEXTSIZE", "ABC", "黑体", 100))
        assertEquals(100L, mHeight(ctx))
    }

    /**
     * 真实表那一路的高度同样按行数（表里没有竖度量 ⇒ 与退化档同式）：
     * `"AAA\nA"` @16 ⇒ 宽取最宽行 30、高 2 × 16 = 32。
     */
    @Test
    fun `真实表那一路的高度同样按行数`() {
        val ctx = measCtx()
        measCatalog(ctx).put(FontAdvanceTable.parse(FIXTURE_TWO_ROWS))
        assertEquals(30L, mI(ctx, "GGETTEXTSIZE", "AAA\nA", "MeasureFont", 16))
        assertEquals(32L, mHeight(ctx))
        assertEquals(10L, mI(ctx, "GGETTEXTSIZE", "A", "MeasureFont", 16))
        assertEquals(16L, mHeight(ctx))
    }

    // ==================================================================
    // 5) GGETFONT 的归一名
    // ==================================================================

    /**
     * 原版 `GGETFONT` 回读 `graphicsImage.Fontname`（spec: FunctionMethodCreator.cs:6219），
     * 而 `Fontname => font.Name`（spec: GraphicsImage.cs:60）= **GDI+ 解析后的家族名**，
     * 不是脚本传进来的请求名。`GSETFONT` 的两条构造分支（spec: :6385-6402）决定它：
     * - 请求名逐字精确命中 `GlobalStatic.Pfc.Families`（Pfc = `PrivateFontCollection`，
     *   spec: GlobalStatic.cs:38；装进去的是游戏 `font` 目录的 ttf/otf，spec: Program.cs:129-141，
     *   加上回退时解包的 BIZ UDGothic，spec: Config.cs:509-522）⇒ `Name` = 命中的家族名 = 请求名；
     * - 都不命中 ⇒ `new Font(strValue, ...)`（:6400）⇒ `Name` = 系统解析出的规范名；
     *   系统里也没有时 GDI+ **静默换兜底族**（2026-10-21 本机实测：`NoSuchFamilyZZZ` / `Micro` /
     *   空串 / `Saitamaar` / `BIZ UDGothic` / `Meiryo` 全部回 `Microsoft Sans Serif`；
     *   `ms gothic` 回规范名 `MS Gothic`；`SimHei` 回本地化名 `黑体`）。
     *
     * 移植侧：认得的名字（`PortFonts.isAvailable` 命中仓内表或宿主点查，或 §3 里有自己的表）
     * 原样回读请求名；认不得的回到**真正拿去画的那个族**
     * （`FontSidecarCatalog.fallbackFamily`：有替代族给替代族，否则给默认族），
     * 与 PC 的「静默换族」同构而不同名。
     */
    @Test
    fun `GGETFONT 回读解析名 认不得的名字回到实际绘制的族`() {
        val ctx = measCtx()
        assertEquals(1L, mI(ctx, "GCREATE", 70, 10, 10))
        // 仓内表里的名字：原样回读（基准环境 FONT=黑体，spec: pc-golden-root.txt:279-281）
        assertEquals(1L, mI(ctx, "GSETFONT", 70, "黑体", 16))
        assertEquals("黑体", mS(ctx, "GGETFONT", 70))
        assertEquals(1L, mI(ctx, "GSETFONT", 70, "Saitamaar", 16))
        assertEquals("Saitamaar", mS(ctx, "GGETFONT", 70))
        // 宿主点查命中的名字：原样回读
        val hostCtx = measCtx(MeasHost(installed = setOf("Noto Sans CJK")))
        assertEquals(1L, mI(hostCtx, "GCREATE", 71, 10, 10))
        assertEquals(1L, mI(hostCtx, "GSETFONT", 71, "Noto Sans CJK", 16))
        assertEquals("Noto Sans CJK", mS(hostCtx, "GGETFONT", 71))
        // §3 里有自己的表也算认得（装配期直挂，不必在仓内表里登记）
        val tableCtx = measCtx()
        measCatalog(tableCtx).put(FontAdvanceTable.parse(FIXTURE_TWO_ROWS))
        assertEquals(1L, mI(tableCtx, "GCREATE", 72, 10, 10))
        assertEquals(1L, mI(tableCtx, "GSETFONT", 72, "MeasureFont", 16))
        assertEquals("MeasureFont", mS(tableCtx, "GGETFONT", 72))
        // 谁都不认识 ⇒ PC 给 Microsoft Sans Serif，移植侧给自己的兜底族（默认族黑体）
        assertEquals(1L, mI(ctx, "GSETFONT", 70, "NoSuchFamilyZZZ", 13, 9))
        assertEquals("黑体", mS(ctx, "GGETFONT", 70))
        assertNotEquals("NoSuchFamilyZZZ", mS(ctx, "GGETFONT", 70))
        // 装了替代族之后兜底给替代族（真正拿去画的那张表所在的族）
        val subCtx = measCtx()
        measCatalog(subCtx).substituteFamily = "Saitamaar"
        assertEquals(1L, mI(subCtx, "GCREATE", 73, 10, 10))
        assertEquals(1L, mI(subCtx, "GSETFONT", 73, "NoSuchFamilyZZZ", 13))
        assertEquals("Saitamaar", mS(subCtx, "GGETFONT", 73))
        // 失败的那次 GSETFONT（字号非正 ⇒ 原版空 catch 返 0，spec: :6404-6406）不许把已存的解析名改脏
        assertEquals(0L, mI(ctx, "GSETFONT", 70, "也找不到这个族", 0))
        assertEquals("黑体", mS(ctx, "GGETFONT", 70))
        // 空字体名：PC 实测 `new Font("", 16f, Regular, Pixel).Name` = Microsoft Sans Serif；
        // 移植侧不认识空串 ⇒ 兜底族，与「静默换族」同构（测量那一路空串归一到默认族，
        // spec: FontFactory.cs:13-17）
        assertEquals(1L, mI(ctx, "GSETFONT", 70, "", 16))
        assertEquals("黑体", mS(ctx, "GGETFONT", 70))
        // GDISPOSE ⇒ ""（GraphicsImage.cs:528-560 释放 font 字段）；新建但未设字体 ⇒ 抛
        assertEquals(1L, mI(ctx, "GDISPOSE", 70))
        assertEquals("", mS(ctx, "GGETFONT", 70))
        assertEquals(1L, mI(ctx, "GCREATE", 74, 4, 4))
        assertFailsWith<CodeEE> { mS(ctx, "GGETFONT", 74) }
    }

    /**
     * TODO(verify)（GGETFONT 还差一张折名表）：GDI+ 把请求名折成**规范名** —— 本机实测
     * `SimHei` → `黑体`、`ms gothic` → `MS Gothic`，而 `SimHei` 不是 Pfc 成员（走 :6400 那条
     * 字符串构造）⇒ 同一个族在 PC 上有两个可回读的名字。移植侧对「认得的名字」一律原样回读，
     * 所以 `GSETFONT("SimHei")` 之后 `GGETFONT` 得 `SimHei`，与 PC 的 `黑体` 不同值。
     * 卡点：需要「别名 / 大小写 / 本地化名 → 规范名」的平台表
     * （宿主侧 `BuiltinHost.installedFonts` 只报「有没有」，不报规范名）⇒ 属组长接线。
     * 本用例只钉住现状，不宣称与 PC 一致。
     */
    @Test
    fun `GGETFONT 对别名不做折名 现状钉住`() {
        val ctx = measCtx(MeasHost(installed = setOf("SimHei")))
        assertEquals(1L, mI(ctx, "GCREATE", 75, 8, 8))
        assertEquals(1L, mI(ctx, "GSETFONT", 75, "SimHei", 16))
        assertEquals("SimHei", mS(ctx, "GGETFONT", 75))
        // PC 实测这里回的是本地化规范名「黑体」⇒ 已知偏差，登记不修正
        assertNotEquals("黑体", mS(ctx, "GGETFONT", 75))
    }

    // ==================================================================
    // 6) GDRAWTEXT 未设字体那条分支（口径 4）
    // ==================================================================

    /**
     * 原版 `GDRAWTEXT` 在 `graphicsImage.Fnt == null` 时新建
     * `new Font(Config.FontName, 100f, GlobalStatic.Console.StringStyle.FontStyle, GraphicsUnit.Pixel)`
     * （spec: FunctionMethodCreator.cs:6518-6523，**100 是原写字面量**，见
     * [com.eratw.port.core.font.FALLBACK_MEASURE_FONT_SIZE]）。三个来源各是：
     * **配置项的字体名**（`Config.FontName`，spec: Config.cs:333 写入、ConfigData.cs:65 出厂默认
     * 「ＭＳ ゴシック」，基准环境被语料 `emuera.config:21` 覆盖成黑体）、
     * **字面量 100 的字号**、**控制台现用样式位**（这一项确实读控制台）。
     *
     * 移植侧此前把「字体名」也取自 `consoleStyle.fontName`（控制台现用名）⇒ 与原版不同：
     * 脚本执行过控制台 `SETFONT` 之后，原版这条分支仍按 `Config.FontName` 量，移植侧会换成
     * 控制台那个族。本波改成读配置条目（`ConfigMethods.store(c)`，`Config.FontName` 的唯一现值面）。
     * 下面的区分读数：配置条目里的族**有**表、另一个族**没有**表 ⇒ 走配置得真实步进 162，
     * 换成没表的族就全退化 150；旧实现两者都会读控制台现用名（本例宿主没有样式快照 ⇒
     * 落到默认族），拿不到这个区分度。
     *
     * 退化网格在这条分支上 1 半角格 = `100 / 2 = 50` px；本机 GDI+ 实测黑体 @100 的半角步进
     * 正是 50（`A` / `a` / `0` 各 50，全角 100）⇒ 退化读数与 PC 同值，这条也就是
     * 「字面量 100」落地正确的尺子（用例把高度钉成 100）。
     */
    @Test
    fun `GDRAWTEXT 未设字体时按 Config 的字体名与字面量 100 测量`() {
        val ctx = measCtx()
        ConfigMethods.install(ctx, fontEntryStore("MeasureFont"))
        measCatalog(ctx).put(FontAdvanceTable.parse(FIXTURE_TWO_ROWS))
        assertEquals(1L, mI(ctx, "GCREATE", 76, 8, 8))
        assertEquals(1L, mI(ctx, "GDRAWTEXT", 76, "ABC", 0, 0))
        // 表：A 800/1280*100 = 62.5；B/C 缺行 ⇒ 退化 1 格 = 50 ⇒ 162.5 → 162（整串一次截断）
        assertEquals(162L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(100L, ctx.store.readInt("RESULT", longArrayOf(2)))
        // 配置条目换成一个没表的族 ⇒ 全退化 3 × 50 = 150
        val degraded = measCtx()
        ConfigMethods.install(degraded, fontEntryStore("ＭＳ 明朝"))
        measCatalog(degraded).put(FontAdvanceTable.parse(FIXTURE_TWO_ROWS))
        assertEquals(1L, mI(degraded, "GCREATE", 77, 8, 8))
        assertEquals(1L, mI(degraded, "GDRAWTEXT", 77, "ABC", 0, 0))
        assertEquals(150L, degraded.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(100L, degraded.store.readInt("RESULT", longArrayOf(2)))
        // 设过缓冲自带字体之后就与配置无关（:6519 → GraphicsImage.cs:89 的 Fnt）
        assertEquals(1L, mI(ctx, "GSETFONT", 76, "MeasureFont", 16))
        assertEquals(1L, mI(ctx, "GDRAWTEXT", 76, "ABC", 0, 0))
        assertEquals(26L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(16L, ctx.store.readInt("RESULT", longArrayOf(2)))
    }

    /**
     * 宿主没装配置条目时（`configOverrides` 空、也没有控制台样式快照）：条目值落到注册默认
     * 「ＭＳ ゴシック」（spec: ConfigData.cs:65）⇒ 与 j-gfxaudit 那波的退化读数一字不差
     * （150 / 100）。这条是「本波改动不许回退既有用例」的回归尺子。
     */
    @Test
    fun `宿主没装配置条目时退化读数不变`() {
        val ctx = measCtx()
        assertEquals(1L, mI(ctx, "GCREATE", 78, 4, 4))
        assertEquals(1L, mI(ctx, "GDRAWTEXT", 78, "ABC", 0, 0))
        assertEquals(150L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(100L, ctx.store.readInt("RESULT", longArrayOf(2)))
        // 多行按行数给高度（PC：2 行 × 114.0625 = 228.125 → 228）
        assertEquals(1L, mI(ctx, "GDRAWTEXT", 78, "AB\nCD", 0, 0))
        assertEquals(100L, ctx.store.readInt("RESULT", longArrayOf(1)))
        assertEquals(200L, ctx.store.readInt("RESULT", longArrayOf(2)))
    }
}
