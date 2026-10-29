package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.CellWidth
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * WIDTH-SINGLE 在 `core/fn` 侧的收口闸。
 *
 * ## 断言的是哪几条裁定事实
 *
 * 1. **名单只有一份**：三处像素近似（`HtmlMethods.kt` 的 approxTextWidth、
 *    `DebugMiscMethods.kt` 的 measureWidth、`GraphicsMethods.kt` 的 measureText）
 *    不得自带全半角区间表，一律读 [CellWidth.isFullWidth]；换算层只保留
 *    「半角步进 = FontSize/2、全角 = FontSize」。
 *    ⇒ 用「三处同值 + 等于 `CellWidth.of(s) * (FontSize/2)`」这条等式钉住
 *    （FontSize=18 为偶数时全角恰为两个半角步进，等式与表内容无关，任何时候都必须绿）。
 * 2. **口径 = CP932(Shift-JIS) 字节数**（组长裁定，main 上的 WIDTH-SINGLE 提交）：
 *    East Asian Width 的 Ambiguous 一类记全角。依据 `EmueraConsole.cs:3255`
 *    （PRINTC 的补齐宽度就是 `Encoding.GetEncoding("Shift-JIS").GetByteCount`，
 *    `STRLEN` 一族同理 `LangManager.cs:15-22,39,74,90`，码页 `Config.cs:412-416` 默认 932，
 *    默认值见 `ConfigData.cs:103` 的 `UseLanguage.JAPANESE`），
 *    以及基准 `fixtures/golden/baseline/pc-golden-root.txt:61`（99 个 `━` 填满 1797px）
 *    与 `:253`（199 个 `=` ⇒ 半角步进 9px）；`pc-golden-root.txt:135` 的
 *    `WIDTH_FULL=6 WIDTH_HALF=3`（`STRLENS(TOFULL("ABC"))`）独立确认「全角 2 / 半角 1」的
 *    字节数口径本身。
 * 3. **收口后变大的那一侧**：Ambiguous 符号的近似像素宽从 9px 变 18px。基准里
 *    **没有**任何能直接钉住近似像素宽的行（FONT-STYLE 节 `:273-281` 只有
 *    FONT/FONT_DEFAULT/CHKFONT；`:646-648` 的 GDRAWTEXT 只记返回值 1；GGETTEXTSIZE 零条），
 *    所以这一组的判据是上面第 2 条的两处间接读数，登记与卡点见
 *    approxTextWidth 的 KDoc（`core/fn/HtmlMethods.kt`）。
 *
 * 注：本文件的第 2、3 组用例钉的是**裁定后**的那张表；若把它们跑在换成 CP932 表之前
 * 的基线上会红，那是预期的（收口前 `core/fn` 自带的是 East Asian Width 副本）。
 */
class FnWidthSingleSourceTest {

    private class FwBaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("FwBaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    /** 默认宿主：clientWidth 760、FontSize 18（基准环境是 1800/18，夹具取内建默认）。 */
    private class FwHost(override val measureText: ((String) -> Long)? = null) : BuiltinHost

    private fun ctx(host: BuiltinHost = FwHost()): BuiltinEvalContext {
        val store = VariableStore()
        val base = FwBaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store, host)
    }

    private fun int(name: String, vararg args: Value): Long =
        (ctx().callFunction(name, args.toList()) as Value.IntV).v

    private fun str(name: String, vararg args: Value): String =
        (ctx().callFunction(name, args.toList()) as Value.StrV).v

    private fun s(v: String): Value = Value.StrV(v)
    private fun n(v: Long): Value = Value.IntV(v)

    /** 码位 → 字符串（增补平面走 `String(int)`，即 UTF-16 码位构造，不是逐码元拼接）。 */
    private fun cpString(cp: Int): String = String(Character.toChars(cp))

    /** 近似像素宽（第二参非 0 ⇒ 原始像素，spec: FunctionMethodCreator.cs:58-66）。 */
    private fun approxPx(text: String): Long = int("HTML_STRINGLEN", s(text), n(1))

    /** 图形侧的近似像素宽（GGETTEXTSIZE 用给定字号，spec: FunctionMethodCreator.cs:6584-6587）。 */
    private fun gfxPx(text: String, size: Long): Long =
        int("GGETTEXTSIZE", s(text), s("黑体"), n(size))

    // ------------------------------------------------------------------
    // 1) 单源等式：三处同值，且等于「格数 × 半角步进」
    // ------------------------------------------------------------------

    /**
     * 三处像素近似必须给出同一个数，且等于 [CellWidth.of] 的格数乘半角步进（9px）。
     *
     * 这条等式**与表内容无关**（偶数字号下全角恒等于两个半角步进），因此它是
     * 「名单只有一份」的闸门：谁再在 `core/fn` 里加回自己的区间表，这里立刻报红。
     * 样本覆盖 ASCII、半角假名、CJK、Ambiguous 符号、横线、代理项半与增补平面码位。
     */
    @Test
    fun `三处像素近似同值且等于格数乘半角步进`() {
        val samples = listOf(
            "",
            "ABCD",
            "0123456789 ~",
            "ｱｲｳｴｵ ﾟ",
            "漢字あいう、。",
            "※※※GOLDEN_LOG.txt※※※",
            "━".repeat(9),
            "±…―“”①≦≧¢§¬°´×",
            cpString(0x20000),
            cpString(0x1F600),
            "a" + cpString(0x1F600) + "b",
            "￠〜～",
        )
        for (text in samples) {
            val cells = CellWidth.of(text).toLong()
            val expected = cells * (BuiltinHost.DEFAULT_FONT_SIZE / 2)
            assertEquals(expected, approxPx(text), "HTML 侧：$text")
            assertEquals(expected, gfxPx(text, BuiltinHost.DEFAULT_FONT_SIZE), "GFX 侧：$text")
        }
    }

    /** `HTML_STRINGLEN` 无第二参时是 `2 * 像素 / FontSize`（向上取整），即格数。 */
    @Test
    fun `HTML_STRINGLEN 的第一参口径就是格数`() {
        for (text in listOf("ABCD", "ｱｲｳ", "漢字", "※", "━".repeat(3))) {
            assertEquals(CellWidth.of(text).toLong(), int("HTML_STRINGLEN", s(text)), text)
        }
    }

    // ------------------------------------------------------------------
    // 2) 收口后不变的一侧：基准里有读数的 ASCII
    // ------------------------------------------------------------------

    /**
     * 基准 `pc-golden-root.txt:297` 的 `HTML_STRINGLEN("<i>AB</i>CD") = 4 / PLAIN = 36`：
     * 4 个 ASCII 半角 ⇒ 收口前后都必须是 36px、4 格。
     */
    @Test
    fun `基准 297 行的 4 与 36 不因收口改变`() {
        assertEquals(36L, approxPx("ABCD"))
        assertEquals(4L, int("HTML_STRINGLEN", s("<i>AB</i>CD")))
        assertEquals(36L, int("HTML_STRINGLEN", s("<i>AB</i>CD"), n(1)))
    }

    // ------------------------------------------------------------------
    // 3) 裁定后的表：Ambiguous 符号按全角计
    // ------------------------------------------------------------------

    /**
     * East Asian Width 的 Ambiguous 一类（CP932 双字节）按**全角**计 ⇒ 单字 18px / 2 格。
     * 逐点 SJIS 字节序列登记在 `render/src/test/kotlin/com/eratw/port/render/grid/`
     * 的 GridWidthSingleSourceTest 的 A 类表（wave/d-width 路），本文件不复制第二份名单。
     */
    @Test
    fun `Ambiguous 符号按全角计`() {
        val ambiguous = listOf(
            0x00A2, // ¢ CENT SIGN sjis 8191
            0x00A7, // § SECTION SIGN sjis 8198
            0x00AC, // ¬ NOT SIGN sjis 81CA
            0x00B0, // ° DEGREE SIGN sjis 818B
            0x00B1, // ± PLUS-MINUS sjis 817D
            0x00B4, // ´ ACUTE ACCENT sjis 814C
            0x00D7, // × MULTIPLICATION sjis 817E
            0x2010, // ‐ HYPHEN sjis 815D
            0x2015, // ― HORIZONTAL BAR sjis 815C
            0x201C, // “ LEFT DOUBLE QUOTE sjis 8167
            0x201D, // ” RIGHT DOUBLE QUOTE sjis 8168
            0x2026, // … ELLIPSIS sjis 8163
            0x203B, // ※ REFERENCE MARK sjis 81A6
            0x2103, // ℃ DEGREE CELSIUS sjis 818E
            0x2192, // → RIGHTWARDS ARROW sjis 81A8
            0x221A, // √ SQUARE ROOT sjis 81E3
            0x2260, // ≠ NOT EQUAL TO sjis 8182
            0x2266, // ≦ sjis 8185
            0x2267, // ≧ sjis 8186
            0x2460, // ① CIRCLED ONE sjis 8740
            0x2500, // ─ BOX LIGHT sjis 849F
            0x2501, // ━ BOX HEAVY sjis 84AA（基准:61）
            0x2502, // │ BOX LIGHT VERTICAL sjis 84A0
            0x25A0, // ■ BLACK SQUARE sjis 81A1
            0x25BC, // ▼ BLACK DOWN TRIANGLE sjis 81A5
            0x266A, // ♪ MUSICAL NOTE sjis 81F4
        )
        for (cp in ambiguous) {
            val text = cpString(cp)
            assertEquals(2L, CellWidth.of(text).toLong(), "U+%04X 应记 2 格".format(cp))
            assertEquals(18L, approxPx(text), "HTML 侧 U+%04X".format(cp))
            assertEquals(18L, gfxPx(text, 18), "GFX 侧 U+%04X".format(cp))
        }
    }

    /** 语料 `ERB/カラム機能/カラム機能(div).ERB:21` 的 `GETLINESTR("─")`：全角 ⇒ 填充字数减半。 */
    @Test
    fun `GETLINESTR 的填充字数跟着宽度表走`() {
        // 夹具 DrawableWidth = 760 - max(2, 18/6) = 757（Config.cs:461-466 同式）
        // 半角 `-` 步进 9px ⇒ 84 字（756px）——基准 :253 的 199 个 `=` 是同一算法在
        // WindowX=1800 下的读数，收口前后都不变。
        assertEquals(84, str("GETLINESTR", s("-")).length)
        // 全角 `─`（U+2500，CP932 双字节）步进 18px ⇒ 42 字（756px）。
        assertEquals("─".repeat(42), str("GETLINESTR", s("─")))
        // 基准 :61 的 `━` 与 :253 的 `=` 之比 = 199 : 99，同一档步进。
        assertEquals("━".repeat(42), str("GETLINESTR", s("━")))
    }

    // ------------------------------------------------------------------
    // 4) 半角的一侧：ASCII 与半角假名
    // ------------------------------------------------------------------

    /**
     * 半角 = CP932 单字节 = `U+0000..U+001F` / `U+0020..U+007E` / `U+007F` / `U+0080` /
     * `U+00A5` / 半角假名 `U+FF61..U+FF9F`（[CellWidth.isFullWidth] 的判定式）
     * ⇒ 单字 9px / 1 格。控制符与 `U+00A5` 那三档是 2026-10-21 按 .NET 932 的
     * `GetByteCount` 实测补进来的（`.NET` 目录读数在 `core/host/ConsoleDataSourceTest`）。
     */
    @Test
    fun `ASCII 与半角假名按半角计`() {
        val half = listOf(
            0x0020, // 空格：PRINTC 的步进单位就是它（EmueraConsole.cs:3237-3243）
            0x0030, // '0'
            0x0041, // 'A'
            0x007E, // '~' ASCII 上端
            0x0009, // TAB 的裸码元也是 1 字节（整串路径先展开成 8 空格）
            0x007F, // DEL
            0x00A5, // ¥ best-fit 回 0x5C
            0xFF61, // ｡ 半角假名下端
            0xFF76, // ｶ 半角假名中间
            0xFF9F, // ﾟ 半角假名上端
        )
        for (cp in half) {
            val text = cpString(cp)
            assertEquals(1L, CellWidth.of(text).toLong(), "U+%04X 应记 1 格".format(cp))
            assertEquals(9L, approxPx(text), "HTML 侧 U+%04X".format(cp))
            assertEquals(9L, gfxPx(text, 18), "GFX 侧 U+%04X".format(cp))
        }
        // 收口前后同一侧（两表都记全角），钉住「收口只动 Ambiguous 那一档」：
        for (cp in listOf(0xFF01, 0xFF21, 0xFF60, 0x3042, 0x4E00, 0xAC00, 0x30FB)) {
            assertEquals(18L, approxPx(cpString(cp)), "U+%04X 前后都是全角".format(cp))
        }
    }

    // ------------------------------------------------------------------
    // 5) 契约点名的边界码位
    // ------------------------------------------------------------------

    /**
     * `※`(U+203B)、`～`(U+FF5E) 与 `〜`(U+301C)、`￠`(U+FFE0) 四个边界逐个钉住：
     * 前两个是「A 类收口 ⇒ 变全角」与「全角 ASCII 变体 ⇒ 一直是全角」的分界，
     * 后两个是「同一个波浪号的两副面孔」——`～` 在全角 ASCII 变体区（旧表的
     * `U+FF00..U+FF60`）、`〜` 在 CJK 标点区（旧表的 `U+2E80..U+303E`），两表都记全角，
     * 收口不动它们；`￠` 在全角符号区（旧表的 `U+FFE0..U+FFE6`），同理。
     */
    @Test
    fun `契约点名的边界码位逐个钉住`() {
        assertEquals(18L, approxPx("※"), "U+203B 收口后按全角（CP932 81A6）")
        assertEquals(2L, CellWidth.of("※").toLong())
        assertEquals(18L, approxPx("～"), "U+FF5E 全角波浪号，收口前后同值")
        assertEquals(18L, approxPx("〜"), "U+301C 假名波浪号，收口前后同值")
        assertEquals(18L, approxPx("￠"), "U+FFE0 全角美分符号，收口前后同值")
        assertEquals(36L, approxPx(" ￠ "), "两侧空格各 1 格 ⇒ 9 + 18 + 9")
    }

    /**
     * 代理项与增补平面：一律**按码位**累加（[CellWidth.of] 的口径），
     * 不得退回逐码元（那是第三种口径，会把一个增补字符算成两格全角）。
     * 登记过的既有差异：`render` 侧逐码元累加的路径对表外 emoji 给 2 格、
     * 按码位给 1 格；`core/fn` 这三处一律按码位，故与 [CellWidth.of] 恒等式成立。
     */
    @Test
    fun `代理项半与增补平面按码位计`() {
        // 完整码位：CJK 扩展 B 的 U+20000（两表都收全角）⇒ 18px，不是逐码元的 36px。
        assertEquals(18L, approxPx(cpString(0x20000)), "U+20000 是一个全角码位")
        assertEquals(2L, CellWidth.of(cpString(0x20000)).toLong())
        // 增补平面 emoji：CP932 表外字按全角 2 格 ⇒ 18px（逐码元会得 36px）。
        assertEquals(18L, approxPx(cpString(0x1F600)), "U+1F600 按码位记 1 个全角")
        assertEquals(2L, CellWidth.of(cpString(0x1F600)).toLong())
        // 代理项半：不是字符，[CellWidth] 的 KDoc 登记为「单独传入按全角 2 计」。
        assertEquals(18L, approxPx("\uD800"), "前代理项半单独传入")
        assertEquals(18L, approxPx("\uDC00"), "后代理项半单独传入")
        assertEquals(2L, CellWidth.of("\uD800").toLong())
        // 混排：半 + 全角码位 + 半 = 9 + 18 + 9。
        assertEquals(36L, approxPx("a" + cpString(0x1F600) + "b"))
        // GFX 侧同样按码位（收口前是逐码元 ⇒ 36px 的第二种口径）。
        assertEquals(18L, gfxPx(cpString(0x1F600), 18), "GFX 侧不得把代理对算成两字")
    }

    /** 字号参数：GGETTEXTSIZE 用第 3 参（单位像素），半角步进 = size/2、全角 = size。 */
    @Test
    fun `像素近似随字号线性缩放`() {
        assertEquals(12L, gfxPx("AB", 12))
        assertEquals(24L, gfxPx("AB", 24))
        assertEquals(12L, gfxPx("━", 12), "字号 12 时全角 12px")
        assertEquals(24L, gfxPx("━", 24))
        // 字号 1：半角步进向下取整后仍被 maxOf(1, …) 抬到 1，与全角同值。
        assertEquals(1L, gfxPx("A", 1))
        assertEquals(1L, gfxPx("━", 1))
    }

    /** 宿主注入 [BuiltinHost.measureText] 时不走近似（真机口径优先）。 */
    @Test
    fun `宿主度量注入优先于等宽近似`() {
        val host = FwHost(measureText = { it.length * 10L })
        assertEquals(20L, (ctx(host).callFunction("HTML_STRINGLEN", listOf(s("AB"), n(1))) as Value.IntV).v)
        // GETLINESTR 同口径：夹具 DrawableWidth 757，10px/字 ⇒ 75 字（基准 :253 的 199 字是 9px 档）。
        assertEquals(75, strWith(ctx(host), "GETLINESTR", s("-")).length)
    }

    private fun strWith(c: BuiltinEvalContext, name: String, vararg args: Value): String =
        (c.callFunction(name, args.toList()) as Value.StrV).v
}
