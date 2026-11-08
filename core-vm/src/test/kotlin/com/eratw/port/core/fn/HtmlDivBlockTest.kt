package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * div 覆盖块的版面语义（CONTRACT-HTMLDIV-B1 D1/D2/D5/D6/D8）。
 *
 * 原版权威（相对 reference/emuera-cs/）：
 * - `MinorShift.Emuera.Runtime.Utils.EvilMask/ConsoleDivPart.cs`（CDP）：ctor :68-135
 *   （Math.Abs :72-73、MixedNum 折算 :103-134）、DrawTo :174-206、SetWidth :217-219、
 *   ToString :221-233；
 * - `MinorShift.Emuera.UI.Game/HtmlManager.cs`（HM）：div 开标签 :1225-1373、闭标签
 *   :1029-1035、SubDivisionWidth :606-648、子层状态继承 :536-544、折行/对齐 :698-710；
 * - `MinorShift.Emuera.Runtime.Utils.EvilMask/Utils.cs`：MixedNum :15-99、
 *   ParseParam4MixedNum :247-281、AddTag* :364-462、MixedNum4ToInt4 :490-500。
 *
 * D8 语料锚（eraTW4.981-261017-EAGLOV3/ERB，只读）：
 * - `TITLE.ERB:81-85`：border/radius/padding/bcolor/color 全属性 + 黄金锚
 *   `fixtures/golden/baseline/pc-golden-root.txt:71` 的 altHeadTag 字节；
 * - `ステータス表示関連/PRINT_STATE2.ERB:380`：两栏并排（xpos=0 / xpos={HALF}px）；
 * - `CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERB:641`：depth='1' + color 的行高亮条（option=1）；
 * - `魔改内容/qol/QOL_USERCOM.ERB:267`：rect 四参 + depth='-1'（负 depth 画在正文前）。
 */
private class DivBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("DivBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

/** 假宿主：consoleLines 由测试从 sink 快照现算（与生产 GameSession 的接线同构）。 */
private class DivHost(
    override val consoleLines: List<ConsolePrintedLine>? = null,
) : BuiltinHost

private fun divCtx(lines: List<ConsolePrintedLine>): BuiltinEvalContext {
    val store = VariableStore()
    val base = DivBaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store, DivHost(lines))
}

private fun divStr(ctx: BuiltinEvalContext, name: String, vararg args: Value): String =
    (ctx.callFunction(name, args.toList()) as Value.StrV).v

private fun divSink(clientWidth: Int = 80) = LineBufferConsoleSink(clientWidth = clientWidth)

/** 直印一行 HTML 并取快照（option=false 直印即时落行；option=true 要 newLine 收缓冲）。 */
private fun divRows(sink: LineBufferConsoleSink, html: String, option: Boolean = false) {
    sink.printHtml(html, option)
    if (option) sink.newLine()
}

class HtmlDivBlockTest {

    // ===== D1：属性全量 → 几何（TITLE.ERB:81 的黄金形态） =====

    @Test
    fun `TITLE 语料形态 全属性解析与几何折算`() {
        val s = divSink()
        // TITLE.ERB:81-85（4.981）；ERB 表达式代入后的实串（黄金锚 pc-golden-root.txt:71
        // 反推：CLIENTWIDTH=1800、CLIENTHEIGHT-LINEHEIGHT=937、LINE HEIGHT=19 ⇒ CLIENTHEIGHT=956）
        divRows(
            s,
            "<div xpos='1600px' ypos='-937px' width='180px' height='50px' border='2px' " +
                "bcolor='#a5a5a5' padding='10px' radius='4px' color='#363636'>" +
                "<button value='4'>【欢落五子棋】</button></div>",
        )
        val block = s.snapshot().single().divBlocks.single()
        assertEquals(1600, block.xOffsetPx, "xpos px 原值（CDP :103）")
        assertEquals(-937, block.pointYPx, "ypos 负值原样（CDP :116）")
        assertEquals(180, block.widthPx, "width px 原值（CDP :129）")
        assertEquals(50, block.heightPx, "height px 原值（CDP :130）")
        assertEquals(0, block.depth, "depth 缺省 0（HM :1237）")
        assertEquals(0x363636, block.backgroundColor, "color → 块底色（CDP :70）")
        assertEquals(listOf(2, 2, 2, 2), block.border, "border='2px' 1 值展开 CSS 序（Utils.cs:252-256）")
        assertEquals(listOf(10, 10, 10, 10), block.padding)
        assertEquals(listOf(4, 4, 4, 4), block.radius)
        assertEquals(
            listOf(0xA5A5A5, 0xA5A5A5, 0xA5A5A5, 0xA5A5A5),
            block.borderColors,
            "bcolor 单值展开成四值表（HM :1337-1340 → ParseParam4IntNum :336-341）；" +
                "序列化侧按值相等折回 1 值（Utils.cs:429-432）",
        )
        assertEquals(0 + 10 + 2, block.divXOffsetPx, "divXOffset = margin[3]+padding[3]+border[3]（CDP :104-115；TITLE 无 margin）")
        assertEquals(0 + 10 + 2, block.yOffsetPx, "yOffset = margin[0]+padding[0]+border[0]（CDP :117-128）")
        assertEquals(true, block.isRelative, "display 缺省 relative（HM :1236）")
        assertEquals(true, block.isEscaped, "Top=-937 < 0 → 逃逸（ConsoleButtonString.cs:171）")
        assertEquals(1, block.children.size, "子行 = div 体（无 br 就一行）")
        assertEquals("<button value='4'>【欢落五子棋】</button>", block.children.single().html)
        // SubDivisionWidth（HM :618-635）：ToPixel(width) − (margin[3]+margin[1]) −
        // (border[3]+border[1]) − (padding[3]+padding[1])
        assertEquals(180 - 10 - 10 - 2 - 2, block.subDivisionWidthPx)
    }

    @Test
    fun `百分比单位按 FontSize 整除折算`() {
        val s = divSink()
        // PRINT_STATE_BODYGRAPH.ERB:111 形态：height='2000'（无 px 后缀 = 百分比）
        divRows(s, "<div width='100' height='2000' xpos='0' ypos='0'>X</div>")
        val block = s.snapshot().single().divBlocks.single()
        // MixedNum.ToPixel：num * FontSize / 100（Utils.cs:27-31），sink 缺省字号 18
        assertEquals(18, block.widthPx)
        assertEquals(360, block.heightPx)
        // SubDivisionWidth 用原值折算（HM :619）
        assertEquals(18, block.subDivisionWidthPx)
        assertTrue(block.isEscaped, "Top=0、Bottom=360 > 行高 19 → 逃逸（ConsoleButtonString.cs:171）")
    }

    @Test
    fun `width 取绝对值而 SubDivisionWidth 不取`() {
        val s = divSink()
        // CDP :72-73 的 Math.Abs 只作用于盒宽/盒高；HM :619 的 SubDivisionWidth 读原值
        divRows(s, "<div width='-50px' height='-30px'>X</div>")
        val block = s.snapshot().single().divBlocks.single()
        assertEquals(50, block.widthPx)
        assertEquals(30, block.heightPx)
        assertEquals(-50, block.subDivisionWidthPx, "HM :619 用 CurrentDivTag.Width 原值（负数不 abs）")
    }

    @Test
    fun `未逃逸的小块没有绘制面但仍成块`() {
        val s = divSink()
        // 行高 19（ConfigData.cs:67；Config.cs:436-439 下限 = 字号 18，取 19）
        // height='10px'、ypos='0px'：Top=0、Bottom=10 ≤ 19 → 不逃逸
        divRows(s, "<div width='10px' height='10px' ypos='0px'>X</div>")
        val block = s.snapshot().single().divBlocks.single()
        assertEquals(false, block.isEscaped, "FilterEscaped：Top>=0 且 Bottom<=LineHeight 不登记（ConsoleButtonString.cs:171）")
        // 原版未逃逸块没有绘制面（ConsoleButtonString.cs:361-364 跳过 div + 只有逃逸块登记）
        // —— 渲染层按 isEscaped 过滤，core 侧只保证标志正确
        assertEquals(true, block.isRelative)
    }

    @Test
    fun `rect 四参与 size 两参与 QOL_USERCOM 语料形态`() {
        val s = divSink()
        // QOL_USERCOM.ERB:267：rect='X, Ypx, W, Hpx' + depth='-1'
        divRows(
            s,
            "<div rect='12px, 34px, 56px, 78px' depth='-1' border='2px' bcolor='#888888' " +
                "padding='3px,4px' color='#112233'></div>",
        )
        val block = s.snapshot().single().divBlocks.single()
        assertEquals(12, block.xOffsetPx)
        assertEquals(34, block.pointYPx)
        assertEquals(56, block.widthPx)
        assertEquals(78, block.heightPx)
        assertEquals(-1, block.depth, "负 depth = 画在正文之后（EmueraConsole.cs:1830 的降序里排最末）")
        assertEquals(listOf(3, 4, 3, 4), block.padding, "padding 2 值展开 [v0,v1,v0,v1]（Utils.cs:258-262）")
        assertEquals(listOf(0x888888, 0x888888, 0x888888, 0x888888), block.borderColors)
        assertEquals(0x112233, block.backgroundColor)
    }

    // ===== D5：SubDivisionWidth 派生（量宽面不可观测 + 几何面在场） =====

    @Test
    fun `量宽面 div 子文本不计父行宽 且 SubDivisionWidth 不改变读数`() {
        // HM :176-181 只累加 array[0].Buttons[].Width；div 0 宽（CDP :217-219）、
        // 子行宽留在递归层（HM :636-642）⇒ SubDivisionWidth 只作用于子行折行/对齐
        // （HM :698/:702-704），对父行首行宽不可观测。既有读数（HtmlDivWidthTest 钉）不变。
        val s = divSink()
        divRows(s, "AB<div width='10px' height='10px'>x</div>CD")
        val row = s.snapshot().single()
        assertEquals(1, row.divBlocks.size)
        assertEquals(listOf("x"), row.divBlocks.single().children.map { it.html }, "子行是块体内容")
        // 块锚列 = 前面已印文本格数
        assertEquals(2, row.divBlocks.single().anchorCol, "锚列 = AB 的 2 格（块零宽，后续列不后移）")
    }

    // ===== D6：GETDISPLAYLINE 重现头尾标签（黄金锚 pc-golden-root.txt:71） =====

    @Test
    fun `GETDISPLAYLINE 按黄金锚逐字节重建块`() {
        val s = divSink()
        // 黄金 71 行的形态：块前文本（【欢落五子棋】）与块同行 + 块内按钮一行。
        // 黄金原文：`【欢落五子棋】<div xpos='1600px' …>【欢落五子棋】\r\n</div>`
        //（前导 token 的采集出处见交付 TODO(verify)；div 部分字节逐字对上）。
        divRows(
            s,
            "【欢落五子棋】<div xpos='1600px' ypos='-937px' width='180px' height='50px' " +
                "border='2px' bcolor='#a5a5a5' padding='10px' radius='4px' color='#363636'>" +
                "<button value='4'>【欢落五子棋】</button></div>",
        )
        val lines = consolePrintedLines(s.snapshot(), "黑体")
        val out = divStr(divCtx(lines), "GETDISPLAYLINE", Value.IntV(0))
        assertEquals(
            "【欢落五子棋】<div xpos='1600px' ypos='-937px' width='180px' color='#363636' " +
                "height='50px' padding='10px' border='2px' radius='4px' bcolor='#A5A5A5'>" +
                "【欢落五子棋】\r\n</div>",
            out,
            "ConsoleDivPart.ToString（CDP :221-233）：altHeadTag + 子行 + \\r\\n + </div>；" +
                "属性次序 xpos,ypos,width,color,height（CDP :75-79）+ padding,border,radius（:82-89）" +
                "+ bcolor（:97）；bcolor 六位大写（HM :856-862）",
        )
    }

    @Test
    fun `GETDISPLAYLINE 多子行每行后都带 CRLF 且百分比归一`() {
        val s = divSink()
        divRows(s, "<div width='100' height='50px' xpos='0'>A<br>B</div>尾")
        val lines = consolePrintedLines(s.snapshot(), "黑体")
        val out = divStr(divCtx(lines), "GETDISPLAYLINE", Value.IntV(0))
        // width='100'（百分比）重建为 100*18/100=18（BuilderString，Utils.cs:87-98，无 px 后缀）
        // xpos='0' 被 AddTagMixedNumArg 的 num==0 门槛整条省略（Utils.cs:366）
        assertEquals(
            "<div width='18' height='50px'>A\r\nB\r\n</div>尾",
            out,
            "CDP :221-233 每条子行后跟 CRLF（含最后一条）；百分比归一不带 px 后缀",
        )
    }

    @Test
    fun `HTML_GETPRINTEDSTR 剔除块节点`() {
        val s = divSink()
        divRows(s, "前<div width='10px' height='10px'>块内</div>后")
        val lines = consolePrintedLines(s.snapshot(), "黑体")
        val out = divStr(divCtx(lines), "HTML_GETPRINTEDSTR", Value.IntV(0))
        assertEquals(
            "<p align='left'><nobr>前后</nobr></p>",
            out,
            "DisplayLine2Html 对 ConsoleDivPart 无分支（HM :446-464）：块整段跳过，子行不出现",
        )
    }

    @Test
    fun `块文本与块同行 行体剔除后按钮区间不受影响`() {
        val s = divSink()
        divRows(
            s,
            "<button value='7'>甲</button><div width='10px' height='10px'>块内</div>" +
                "<button value='8'>乙</button>",
        )
        val lines = consolePrintedLines(s.snapshot(), "黑体")
        assertEquals(
            "<p align='left'><nobr><button value='7'>甲</button>" +
                "<button value='8'>乙</button></nobr></p>",
            divStr(divCtx(lines), "HTML_GETPRINTEDSTR", Value.IntV(0)),
            "HTML_GETPRINTEDSTR：块剔除后按钮照旧（原版块不产字符也不打断按钮区间）",
        )
        // GETDISPLAYLINE 同一行：块文本在两颗按钮文本之间（节点序）
        val text = divStr(divCtx(lines), "GETDISPLAYLINE", Value.IntV(0))
        assertTrue(text.startsWith("甲<div"), "块前文本在头标签之前（节点序，HM :646）")
        assertTrue(text.endsWith("</div>乙"), "块后文本在尾标签之后")
    }

    // ===== D8：语料锚 —— 缓冲路径（option=1） =====

    @Test
    fun `TEMP_CHARA_LIST_BY_DT 的 depth 加 color 行高亮条 走缓冲路径`() {
        val s = divSink()
        // TEMP_CHARA_LIST_BY_DT.ERB:641：HTML_PRINT …, 1 的整条色块（无子内容）
        divRows(s, "<div width='100px' height='20px' xpos='-50px' depth='1' color='#646464'></div>", option = true)
        val rows = s.snapshot()
        assertEquals(1, rows.size, "缓冲路径整段一行")
        val block = rows[0].divBlocks.single()
        assertEquals(1, block.depth)
        assertEquals(0x646464, block.backgroundColor)
        assertEquals(-50, block.xOffsetPx)
        assertEquals(0, block.children.size, "空内容 → 无子行")
        assertTrue(block.isEscaped, "Bottom=20 > 行高 19 → 逃逸（有绘制面）")
    }

    @Test
    fun `缓冲路径的块随段落行 GETDISPLAYLINE 照常重建`() {
        val s = divSink()
        // TITLE.ERB:81-85 的原样调用形态：HTML_PRINT HTML_STR, 1
        divRows(
            s,
            "<div xpos='1600px' ypos='-937px' width='180px' height='50px' border='2px' " +
                "bcolor='#a5a5a5' padding='10px' radius='4px' color='#363636'>" +
                "<button value='4'>【欢落五子棋】</button></div>",
            option = true,
        )
        val lines = consolePrintedLines(s.snapshot(), "黑体")
        assertEquals(
            "<div xpos='1600px' ypos='-937px' width='180px' color='#363636' height='50px' " +
                "padding='10px' border='2px' radius='4px' bcolor='#A5A5A5'>【欢落五子棋】\r\n</div>",
            divStr(divCtx(lines), "GETDISPLAYLINE", Value.IntV(0)),
            "缓冲段的块区间随整段原文落行，重建与直印路径同字节",
        )
    }

    // ===== 宽容口径（显示路径不抛；严格抛错归 HtmlStrictParser/HtmlStrictTest） =====

    @Test
    fun `width 缺失不成块 标签照旧剥除渲染`() {
        val s = divSink()
        divRows(s, "A<div color='#111111'>B</div>C")
        val row = s.snapshot().single()
        assertEquals(0, row.divBlocks.size, "HM :1360-1366 NotSetAttribute 的宽容替代：不成块")
        assertEquals(null, row.backColor)
    }

    @Test
    fun `未闭合 div 宽容收块`() {
        val s = divSink()
        divRows(s, "<div width='10px' height='10px'>X")
        val block = s.snapshot().single().divBlocks.single()
        assertEquals(listOf("X"), block.children.map { it.html }, "EOS 收帧：子行与区间按现状成块")
    }

    @Test
    fun `div 与 PRINTC 缓冲共存的行不塌`() {
        val s = divSink()
        s.printHtml("普通<div width='10px' height='30px'>块</div>尾", false)
        s.printLine("下一行")
        val rows = s.snapshot()
        assertEquals(2, rows.size)
        assertEquals(1, rows[0].divBlocks.size)
        assertEquals(0, rows[1].divBlocks.size, "块不跨行：br/落行后的新行没有块")
    }
}
