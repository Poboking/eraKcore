package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.font.PortFonts
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 控制台样式查询函数族测试。
 *
 * 判据：`fixtures/golden/baseline/pc-golden-root.txt:263-277`（契约 §4 硬判据）+
 * `fixtures/golden/GOLDEN_02_PRINT.ERB:195-230` 的语句序列。用假 [BuiltinHost] 注入
 * [ConsoleStyleSnapshot] 复现每一行。COLOR_FROMNAME/COLOR_FROMRGB（基线 271-272 行）属其它函数，
 * 不在本包范围，此处不复现。
 *
 * 额外覆盖 GETFONT/CURRENTALIGN/CURRENTREDRAW/PRINTCPERLINE/CLIENTWIDTH/CLIENTHEIGHT
 * （基线 278-290 行，超出契约 §4 硬判据区间，但都是本包注册的函数）。
 */
private class CqBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("CqBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun cqStyleHost(snapshot: ConsoleStyleSnapshot?) = object : BuiltinHost {
    override val consoleStyle: ConsoleStyleSnapshot? = snapshot
}

private fun cqCtxFor(snapshot: ConsoleStyleSnapshot?): BuiltinEvalContext {
    val store = VariableStore()
    val base = CqBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, cqStyleHost(snapshot))
}

private fun cqIv(ctx: BuiltinEvalContext, name: String): Long =
    (ctx.callFunction(name, emptyList()) as Value.IntV).v

private fun cqSv(ctx: BuiltinEvalContext, name: String): String =
    (ctx.callFunction(name, emptyList()) as Value.StrV).v

/**
 * 默认色对照 PC 基线：Config.ForeColor = 0xC0C0C0(银) = 12632256（DEF/RESET 行），
 * Config.BackColor = 0（DEFBG/BGRESET 行），Config.FocusColor = 0xFFFF00(黄) = 16776960（FOCUS 行）。
 */
private fun snap(
    fore: IntArray? = intArrayOf(192, 192, 192),
    back: IntArray? = null,
    defFore: IntArray = intArrayOf(192, 192, 192),
    defBack: IntArray = intArrayOf(0, 0, 0),
    focus: IntArray = intArrayOf(255, 255, 0),
    style: Int = 0,
    font: String = "黑体",
    align: Int = 0,
    redraw: Int = 0,
) = ConsoleStyleSnapshot(fore, back, defFore, defBack, focus, style, font, align, redraw)

class ConsoleQueryMethodsTest {

    // ===== 契约 §4 硬判据：pc-golden-root.txt:263-277 =====

    @Test
    fun `GETCOLOR 复现 COLOR 节 264-267 行`() {
        // 264 RED=16711680  (SETCOLOR 0xFF0000)
        assertEquals(16711680L, cqIv(cqCtxFor(snap(fore = intArrayOf(255, 0, 0))), "GETCOLOR"))
        // 265 RGB=33023      (SETCOLOR 0,128,255)
        assertEquals(33023L, cqIv(cqCtxFor(snap(fore = intArrayOf(0, 128, 255))), "GETCOLOR"))
        // 266 BYNAME=16711680 (SETCOLORBYNAME RED)
        assertEquals(16711680L, cqIv(cqCtxFor(snap(fore = intArrayOf(255, 0, 0))), "GETCOLOR"))
        // 266 DEF=12632256
        assertEquals(12632256L, cqIv(cqCtxFor(snap()), "GETDEFCOLOR"))
        // 266 FOCUS=16776960
        assertEquals(16776960L, cqIv(cqCtxFor(snap()), "GETFOCUSCOLOR"))
        // 267 RESET=12632256  (RESETCOLOR → 未显式设置 → 回落 defFore)
        assertEquals(12632256L, cqIv(cqCtxFor(snap(fore = null)), "GETCOLOR"))
    }

    @Test
    fun `GETBGCOLOR 复现 COLOR 节 268-270 行`() {
        // 268 BG=65280  (SETBGCOLOR 0x00FF00)
        assertEquals(65280L, cqIv(cqCtxFor(snap(back = intArrayOf(0, 255, 0))), "GETBGCOLOR"))
        // 269 BGBYNAME=255  (SETBGCOLORBYNAME BLUE)
        assertEquals(255L, cqIv(cqCtxFor(snap(back = intArrayOf(0, 0, 255))), "GETBGCOLOR"))
        // 269 DEFBG=0
        assertEquals(0L, cqIv(cqCtxFor(snap()), "GETDEFBGCOLOR"))
        // 270 BGRESET=0  (RESETBGCOLOR → 回落 defBack)
        assertEquals(0L, cqIv(cqCtxFor(snap(back = null)), "GETBGCOLOR"))
    }

    @Test
    fun `GETSTYLE 复现 FONT-STYLE 节 274-277 行`() {
        // 274 STYLE=1 (FONTBOLD→Bold)
        assertEquals(1L, cqIv(cqCtxFor(snap(style = 1)), "GETSTYLE"))
        // 275 STYLE=3 (Bold|Italic)
        assertEquals(3L, cqIv(cqCtxFor(snap(style = 3)), "GETSTYLE"))
        // 276 STYLE=4 (Strikeout)
        assertEquals(4L, cqIv(cqCtxFor(snap(style = 4)), "GETSTYLE"))
        // 277 STYLE=8 (Underline)
        assertEquals(8L, cqIv(cqCtxFor(snap(style = 8)), "GETSTYLE"))
        // 278 STYLE=0 (FONTREGULAR，语句序列 195-230 内)
        assertEquals(0L, cqIv(cqCtxFor(snap(style = 0)), "GETSTYLE"))
    }

    // ===== 以下覆盖本包其余注册函数（基线 278-290 行，超出 §4 硬判据区间）=====

    @Test
    fun `GETFONT 复现 FONT 节 279-280 行`() {
        assertEquals("黑体", cqSv(cqCtxFor(snap(font = "黑体")), "GETFONT"))
    }

    @Test
    fun `CURRENTALIGN 复现 ALIGNMENT 节 283-286 行`() {
        assertEquals("LEFT", cqSv(cqCtxFor(snap(align = 0)), "CURRENTALIGN"))
        assertEquals("CENTER", cqSv(cqCtxFor(snap(align = 1)), "CURRENTALIGN"))
        assertEquals("RIGHT", cqSv(cqCtxFor(snap(align = 2)), "CURRENTALIGN"))
    }

    @Test
    fun `CURRENTREDRAW 复现 287-288 行`() {
        // 287 REDRAW=0 (None)
        assertEquals(0L, cqIv(cqCtxFor(snap(redraw = 0)), "CURRENTREDRAW"))
        // 288 REDRAW=1 (Normal)
        assertEquals(1L, cqIv(cqCtxFor(snap(redraw = 1)), "CURRENTREDRAW"))
    }

    @Test
    fun `窗口查询使用原版默认窗口语义`() {
        // 未接线窗口钩子时 = 原版默认：CLIENTWIDTH=Config.WindowX(760)、
        // CLIENTHEIGHT=WindowY(480)-LineHeight(19)=461、PRINTCPERLINE=Config.PrintCPerLine(3)。
        val ctx = cqCtxFor(snap())
        assertEquals(760L, cqIv(ctx, "CLIENTWIDTH"))
        assertEquals(461L, cqIv(ctx, "CLIENTHEIGHT"))
        assertEquals(3L, cqIv(ctx, "PRINTCPERLINE"))
    }

    @Test
    fun `样式快照未接线时抛 CodeEE`() {
        val ctx = cqCtxFor(null)
        assertFailsWith<CodeEE> { ctx.callFunction("GETCOLOR", emptyList()) }
        assertFailsWith<CodeEE> { ctx.callFunction("GETBGCOLOR", emptyList()) }
        assertFailsWith<CodeEE> { ctx.callFunction("GETDEFCOLOR", emptyList()) }
        assertFailsWith<CodeEE> { ctx.callFunction("GETDEFBGCOLOR", emptyList()) }
        assertFailsWith<CodeEE> { ctx.callFunction("GETFOCUSCOLOR", emptyList()) }
        assertFailsWith<CodeEE> { ctx.callFunction("GETSTYLE", emptyList()) }
        assertFailsWith<CodeEE> { ctx.callFunction("GETFONT", emptyList()) }
        assertFailsWith<CodeEE> { ctx.callFunction("CURRENTALIGN", emptyList()) }
        assertFailsWith<CodeEE> { ctx.callFunction("CURRENTREDRAW", emptyList()) }
        // 窗口/配置度量不依赖样式快照，null 快照也不抛错（回落默认）。
        assertEquals(760L, cqIv(ctx, "CLIENTWIDTH"))
    }

    @Test
    fun `注册表内建名不缺失`() {
        val names = listOf(
            "GETCOLOR", "GETBGCOLOR", "GETDEFCOLOR", "GETDEFBGCOLOR", "GETFOCUSCOLOR",
            "GETSTYLE", "GETFONT", "CURRENTALIGN", "CURRENTREDRAW", "PRINTCPERLINE",
            "CLIENTWIDTH", "CLIENTHEIGHT",
        )
        for (n in names) {
            kotlin.test.assertNotNull(
                Builtins.registry[n],
                "函数 $n 未注册（应与 HostMethods 占位名单在同一次提交里互斥，避免 check(old==null) 崩溃）",
            )
        }
    }

    // ===== CHKFONT 的安卓／PC 分歧已定策略：以仓内字体表为唯一权威 =====
    // spec: FunctionMethodCreator.cs:3476-3509（两张表的并集、逐名精确比较：
    //       :3488-3497 系统 InstalledFontCollection，:3499-3507 GlobalStatic.Pfc 私有字体）

    private fun cqChkHost(installed: Set<String>? = null, font: String? = "黑体") = object : BuiltinHost {
        override val installedFonts: Set<String>? = installed
        override val consoleStyle: ConsoleStyleSnapshot? = if (font == null) null else snap(font = font)
    }

    private fun cqChk(ctx: BuiltinEvalContext, name: String): Long =
        (ctx.callFunction("CHKFONT", listOf(Value.StrV(name))) as Value.IntV).v

    @Test
    fun `CHKFONT 逐条按仓内表答数并给得出依据`() {
        // 基准：pc-golden-root.txt:279-281（FONT=黑体 / FONT_DEFAULT=黑体 / CHKFONT=1）
        val ctx = cqCtxFor(snap(font = "黑体"))
        assertEquals(1L, cqChk(ctx, "黑体"))
        // 语料 font/Saitamaar.ttf 会被原版 AddFontFile 进 Pfc（Program.cs:129-141 → CHKFONT :3499-3507）
        assertEquals(1L, cqChk(ctx, "Saitamaar"))
        kotlin.test.assertNotNull(PortFonts.evidenceFor("黑体"), "仓内表的每一条都要有依据")
        kotlin.test.assertNotNull(PortFonts.evidenceFor("Saitamaar"))
        // 精确比较、区分大小写（spec: :3493、:3502），不做相近替代
        assertEquals(0L, cqChk(ctx, "saitamaar"))
        assertEquals(0L, cqChk(ctx, "SAITAMAAR"))
        assertEquals(0L, cqChk(ctx, "黑体 "))
        assertEquals(0L, cqChk(ctx, ""))
        // 故意不列的（与 PC 的有意分歧，KDoc 里逐条写了为什么）：
        // 原版内嵌回退字体 BIZ UDGothic（Config.cs:509-522）——移植版不分发
        assertEquals(0L, cqChk(ctx, "BIZ UDGothic"))
        // 语料里的 msgothic.ttc 连 PC 的 Pfc 都进不去（原版只 AddFontFile *.ttf / *.otf，
        // Program.cs:131,136），它在 PC 答 1 只因为 Windows 装了 —— 安卓侧没有这张表
        assertEquals(0L, cqChk(ctx, "ＭＳ Ｐゴシック"))
        assertEquals(0L, cqChk(ctx, "宋体"))
    }

    @Test
    fun `CHKFONT 的宿主追加只能加不能减`() {
        // 单一查表规则：仓内表 ∪ 宿主按平台点查追加的名字；宿主**不能否决**仓内表
        // （否则同一份脚本会随设备字体清单漂移，违反确定性硬约束）。
        val ctx = cqCtxFor(snap(font = "黑体"))
        val host = object : BuiltinHost {
            override val installedFonts: Set<String> = setOf("SimHei", "Yu Gothic")
            override val consoleStyle: ConsoleStyleSnapshot get() = snap(font = "黑体")
        }
        val wired = cqCtxWith(host)
        assertEquals(1L, cqChk(wired, "SimHei"))
        assertEquals(1L, cqChk(wired, "Yu Gothic"))
        assertEquals(1L, cqChk(wired, "黑体"), "宿主追加不许把仓内表挤掉")
        assertEquals(0L, cqChk(wired, "simhei"))
        // 宿主报"系统里一个都没有"也不改变仓内表的答案
        val empty = cqCtxWith(object : BuiltinHost {
            override val installedFonts: Set<String> = emptySet()
            override val consoleStyle: ConsoleStyleSnapshot get() = snap(font = "黑体")
        })
        assertEquals(1L, cqChk(empty, "黑体"))
        assertEquals(0L, cqChk(empty, "SimHei"))
        assertEquals(1L, cqChk(ctx, "黑体"))
    }

    @Test
    fun `CHKFONT 不再自证式回退`() {
        // 旧实现：installedFonts == null 时把"当前正在用的字体名"当成已安装 —— C# 里没有任何一条
        // 分支是这么答的（:3488-3507 只查两张表），故删除。现在当前字体名不在表里就是 0。
        val ctx = cqCtxWith(cqChkHost(installed = null, font = "宋体"))
        assertEquals(0L, cqChk(ctx, "宋体"))
        assertEquals(1L, cqChk(ctx, "黑体"))
        // 样式快照整个没接线也不再抛：查表规则不依赖控制台状态
        val bare = cqCtxWith(cqChkHost(installed = null, font = null))
        assertEquals(1L, cqChk(bare, "黑体"))
        assertEquals(0L, cqChk(bare, "宋体"))
    }

    private fun cqCtxWith(host: BuiltinHost): BuiltinEvalContext {
        val store = VariableStore()
        val base = CqBaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store, host)
    }
}
