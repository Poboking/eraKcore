package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.font.PortFonts

/**
 * 第四批：控制台样式/窗口查询函数族（GETCOLOR 家族）。
 *
 * 全部为 0 参函数（原版 `argumentTypeArray = Array.Empty<Type>()`）。数据源是
 * [BuiltinHost.consoleStyle]（[ConsoleStyleSnapshot]）与宿主窗口钩子；样式类函数
 * 在宿主未接线（`consoleStyle == null`）时抛 CodeEE（与契约 §4 同口径）。
 *
 * 权威依据（`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`）：
 * - `GetColorMethod`        : 3648-3664  → GETCOLOR（isDef=false）/ GETDEFCOLOR（isDef=true）
 * - `GetBGColorMethod`      : 3681-3697  → GETBGCOLOR / GETDEFBGCOLOR
 * - `GetFocusColorMethod`   : 3666-3679  → GETFOCUSCOLOR
 * - `GetStyleMethod`        : 3699-3730  → GETSTYLE
 * - `GetFontMethod`         : 3732-3745  → GETFONT
 * - `CurrentAlignMethod`    : 3770-3791  → CURRENTALIGN
 * - `CurrentRedrawMethod`   : 3793-3806  → CURRENTREDRAW
 * - `GetPrintCPerLineMethod`: 3922-3935  → PRINTCPERLINE
 * - `ClientSizeMethod`      : 6777-6799  → CLIENTWIDTH / CLIENTHEIGHT
 * 注册表行号：GETCOLOR/GETDEFCOLOR 9067-9068、GETBGCOLOR/GETDEFBGCOLOR 9070-9071、
 * GETFOCUSCOLOR 9069、GETSTYLE 9072、GETFONT 9073、CURRENTALIGN 9075、CURRENTREDRAW 9076、
 * PRINTCPERLINE 9082、CLIENTWIDTH/CLIENTHEIGHT 9161-9162。
 *
 * 返回编码 = `r shl 16 or g shl 8 or b`（原版 `Color.ToArgb() & 0xFFFFFF`）。
 * 「未设置时回落默认色」：fore/back 为 null 表示未显式设置（RESETCOLOR/RESETBGCOLOR 后），
 * 此时回落 defFore/defBack（见 ConsoleStyleSnapshot KDoc）。
 */

/** `r shl 16 or g shl 8 or b`，等价于原版 `ToArgb() & 0xFFFFFF`（FunctionMethodCreator.cs:3662）。 */
private fun encodeRgb(rgb: IntArray): Long {
    require(rgb.size >= 3) { "颜色分量必须是 [r, g, b] 三元组：实际 $rgb" }
    return ((rgb[0].toLong() and 0xFF) shl 16) or ((rgb[1].toLong() and 0xFF) shl 8) or (rgb[2].toLong() and 0xFF)
}

/** 取宿主样式快照；未接线（null）时抛 CodeEE（契约 §4：GETCOLOR 家族 null → 抛错）。 */
private fun BuiltinContext.style(name: String): ConsoleStyleSnapshot =
    host.consoleStyle ?: throw CodeEE(
        "函数 $name 需要宿主接线控制台样式（consoleStyle == null）",
    )

internal fun registerConsoleQuery(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR

    // ---- 前景色家族（GetColorMethod : 3648-3664）----

    // GETCOLOR：当前前景色，未显式设置（fore == null）时回落 Config.ForeColor。
    r.register(BuiltinMethod("GETCOLOR", I, 0, 0) { c, _ ->
        val s = c.style("GETCOLOR")
        Value.IntV(encodeRgb(s.fore ?: s.defFore))
    })
    // GETDEFCOLOR：Config.ForeColor（defaultColor 分支，spec: 3662）。
    r.register(BuiltinMethod("GETDEFCOLOR", I, 0, 0) { c, _ ->
        val s = c.style("GETDEFCOLOR")
        Value.IntV(encodeRgb(s.defFore))
    })

    // ---- 背景色家族（GetBGColorMethod : 3681-3697）----

    // GETBGCOLOR：当前背景色，未显式设置（back == null）时回落 Config.BackColor。
    r.register(BuiltinMethod("GETBGCOLOR", I, 0, 0) { c, _ ->
        val s = c.style("GETBGCOLOR")
        Value.IntV(encodeRgb(s.back ?: s.defBack))
    })
    // GETDEFBGCOLOR：Config.BackColor（defaultColor 分支，spec: 3695）。
    r.register(BuiltinMethod("GETDEFBGCOLOR", I, 0, 0) { c, _ ->
        val s = c.style("GETDEFBGCOLOR")
        Value.IntV(encodeRgb(s.defBack))
    })

    // ---- 焦点色（GetFocusColorMethod : 3666-3679）----

    // GETFOCUSCOLOR：Config.FocusColor（spec: 3677）。
    r.register(BuiltinMethod("GETFOCUSCOLOR", I, 0, 0) { c, _ ->
        val s = c.style("GETFOCUSCOLOR")
        Value.IntV(encodeRgb(s.focus))
    })

    // ---- 字体样式 / 字体名（GetStyleMethod 3699-3730 / GetFontMethod 3732-3745）----

    // GETSTYLE：Bold=1 | Italic=2 | Strikeout=4 | Underline=8 位掩码，原样返回（spec: 3708-3729）。
    r.register(BuiltinMethod("GETSTYLE", I, 0, 0) { c, _ ->
        val s = c.style("GETSTYLE")
        Value.IntV(s.fontStyleMask.toLong())
    })
    // GETFONT：当前字体名（spec: 3743）。
    r.register(BuiltinMethod("GETFONT", S, 0, 0) { c, _ ->
        val s = c.style("GETFONT")
        Value.StrV(s.fontName)
    })

    // ---- 对齐 / 重绘（CurrentAlignMethod 3770-3791 / CurrentRedrawMethod 3793-3806）----

    // CURRENTALIGN：0=LEFT、1=CENTER、其余=RIGHT（spec: 3781-3789，DisplayLineAlignment.cs:5-7）。
    r.register(BuiltinMethod("CURRENTALIGN", S, 0, 0) { c, _ ->
        val s = c.style("CURRENTALIGN")
        val name = when (s.alignment) {
            0 -> "LEFT"
            1 -> "CENTER"
            else -> "RIGHT"
        }
        Value.StrV(name)
    })
    // CURRENTREDRAW：ConsoleRedraw != None(0) ? 1 : 0（spec: 3804，ConsoleRedraw.cs:8-9）。
    r.register(BuiltinMethod("CURRENTREDRAW", I, 0, 0) { c, _ ->
        val s = c.style("CURRENTREDRAW")
        Value.IntV(if (s.redraw != 0) 1L else 0L)
    })

    // ---- 窗口 / 配置度量（GetPrintCPerLineMethod 3922-3935 / ClientSizeMethod 6777-6799）----
    // 这三项不是「样式」而是窗口/配置量，不经 consoleStyle；宿主未接线时回落到原版默认
    // （见 BuiltinHost.clientWidth/clientHeight/printCPerLine 的默认实现与 spec 引用）。

    // PRINTCPERLINE：Config.PrintCPerLine（spec: 3933 / ConfigData.cs:63 默认 3）。
    r.register(BuiltinMethod("PRINTCPERLINE", I, 0, 0) { c, _ ->
        Value.IntV(c.host.printCPerLine)
    })
    // PRINTCLENGTH：Config.PrintCLength（spec: 3946-3948 / ConfigData.cs:64 默认 25）。
    // 零参、ReturnType = long、CanRestructure = true（:3941-3943）。
    r.register(BuiltinMethod("PRINTCLENGTH", I, 0, 0) { c, _ ->
        Value.IntV(c.host.printCLength)
    })
    // CLIENTWIDTH：EmueraConsole.ClientWidth = MainPicBox.Width = Config.WindowX（spec: 6797 /
    // EmueraConsole.cs:232 / MainWindow.cs:726 / ConfigData.cs:56 默认 760）。
    r.register(BuiltinMethod("CLIENTWIDTH", I, 0, 0) { c, _ ->
        Value.IntV(c.host.clientWidth)
    })
    // CLIENTHEIGHT：EmueraConsole.ClientHeight = MainPicBox.Height = WindowY - LineHeight
    // （spec: 6793 / EmueraConsole.cs:234 / MainWindow.cs:726 / ConfigData.cs:57,67 默认 461）。
    r.register(BuiltinMethod("CLIENTHEIGHT", I, 0, 0) { c, _ ->
        Value.IntV(c.host.clientHeight)
    })

    // ---- 字体可用性（CheckfontMethod : 3476-3509）----
    // CHKFONT(name)：该字体是否可用于绘制。参数是字符串表达式（argumentTypeArray = string，
    // spec: :3481）；ReturnType = long 且 CanRestructure = true（:3480-3482）。
    //
    // 原版的判定是**两张表的并集、逐名精确比较**（区分大小写，不做相近替代）：
    //  1) `new InstalledFontCollection().Families[].Name == name`（:3488-3497）= 系统装的字体；
    //  2) `GlobalStatic.Pfc.Families[].Name == name`（:3499-3507）= 随包私有字体，
    //     内容是游戏目录 font 下的 ttf 与 otf 文件（Program.cs:129-141）与装配期内嵌的
    //     `BIZ UDGothic`（Config.cs:509-522）。
    // 移植版沿用同一条并集规则，但**权威是仓内字体表 [PortFonts]**：
    // 安卓没有"枚举系统字体"的 API（只能对单个族名点查），把系统查询当权威会让同一份脚本
    // 在不同设备上得到不同答案，直接违反本项目的确定性硬约束；所以宿主那份
    // [BuiltinHost.installedFonts] 只允许**追加**条目、不允许否决仓内表。
    // 同时删掉旧的「当前字体名自证式回退」（installedFonts == null 时答 1）—— 原版从来没有
    // 这条：它答 1 是因为那个名字确实在两张表里，而不是因为"正在用它显示"。
    r.register(BuiltinMethod("CHKFONT", I, 1, 1) { c, a ->
        val name = sv(a[0]!!)
        Value.IntV(if (PortFonts.isAvailable(name, c.host.installedFonts)) 1L else 0L)
    })
}
