package com.eratw.port.core.fn

import java.util.Locale

/**
 * 命名色表——本仓唯一权威（COLOR-SYNC，契约 CONTRACT-M4-C §2 c-colorsync）。
 *
 * 收口前命名色在三处各自抄了一份且已分叉：`PureMethods.kt` 的 `namedColors`（COLOR_FROMNAME）、
 * `StatementExecutor.kt` 的 `NAMED_COLORS`（SETCOLORBYNAME / SETBGCOLORBYNAME）、
 * `render` 的 `MiniHtml.NAMED_COLORS`（HTML 的 `color` / `bcolor` 属性）。
 * 本对象是唯一名单，三处消费点只准读它，不再各自维护副本。
 *
 * ## 原版机制：名单不是 Emuera 写的表，而是 GDI+ 内置 KnownColor 表
 *
 * 四个调用点全部把颜色名交给 `System.Drawing.Color.FromName`（该类型来自 `using System.Drawing`，
 * 见 FunctionMethodCreator.cs:5 与 HtmlManager.cs:3），判定口径都是「`color.A > 0` 才算认得」：
 * - FunctionMethodCreator.cs:3808-3836 —— `COLOR_FROMNAME`：:3820 取 `Color.FromName(strValue)`，
 *   :3822 判 `color.A > 0`，:3824 折成 `(R << 16) + (G << 8) + B`，:3828 对 `transparent`
 *   抛 CodeEE，:3832 其余未命中一律 `num = -1`；注册在 FunctionMethodCreator.cs:9077。
 * - Process.cs:1440-1454 —— `SETCOLORBYNAME`：:1443 `Color.FromName(constStr2)`，:1444 判
 *   `A == 0`，:1448 transparent 抛 TransparentUnsupported，:1450 抛 InvalidColorName。
 * - Process.cs:1493-1507 —— `SETBGCOLORBYNAME`：:1496 / :1497 / :1501 / :1503 与上一条同构。
 * - ArgumentParser.cs:463-470 —— `STR_ArgumentBuilder`：解析期就用
 *   `Color.FromName(text).A == 0` 提前校验这两条语句，:467 / :469 抛同样两类 CodeEE。
 * - HtmlManager.cs:1705-1722 —— HTML `color` / `bcolor`：:1705 `Color.FromName(str)`，
 *   :1706 判 `A == 0`，:1710 transparent 抛，:1712-1720 是「裸十六进制能解析却仍抛
 *   InvalidColorName2」的官方怪癖（由 render 层的 MiniHtml 原样复刻，本表不参与）。
 *
 * 反编译快照里没有逐名的 C# 源码行可引（名单在框架内部），故逐名证据改由框架本身取证：
 * 本机 Windows PowerShell 5.1（.NET Framework 4.8 的 System.Drawing）枚举
 * `[System.Drawing.KnownColor]` 全 174 项并逐项 `Color.FromName` 读 `A` / `R` / `G` / `B` /
 * `IsSystemColor`，得 **140 项 `A > 0` 且非系统色**（即 [BY_NAME]）、
 * **1 项 `Transparent` 且 `A == 0`**、**33 项系统色**。复现命令写在 ColorNamesTest 的 KDoc，
 * 名单与数值等长等值由该测试逐项断言（期望值取自框架枚举，不取自本实现）。
 *
 * ## 两项不入表（各自口径不变）
 *
 * 1. `transparent`：框架返回 `A == 0`，原版在 FunctionMethodCreator.cs:3828 一类分支抛错，
 *    因此它**不是**一个可取值的色名；消费者各自的 transparent 抛错分支照旧，本表不含它。
 * 2. 33 个系统色名（见 [SYSTEM_NAMES]）：PC 侧 `FromName` 也认（实测 `A == 255`），但取值来自
 *    `GetSysColor`，随 Windows 主题/机器变化（本机实测 `highlight` = 0x0078D4，是 Win10/11
 *    强调蓝而非固定常量），既不能当黄金基准，安卓侧也没有对应调色板。故不入表，命中即按
 *    「未知名」口径处理——这与收口前三份旧表的行为一致，本波不新增分歧，也不新增取值来源。
 *    TODO(verify)：若将来要让 `SETCOLORBYNAME WINDOW` 之类在安卓侧出颜色，需要组长定一套
 *    固定映射（属产品决策，不是语义复刻）。
 *
 * ## 灰度拼写与大小写口径
 *
 * 框架只认 `gray` 系拼写：`grey` / `darkgrey` / `lightgrey` / `dimgrey` / `slategrey` 实测
 * `A == 0`，即原版按未知名处理（`COLOR_FROMNAME` 返回 -1、语句侧抛 InvalidColorName），
 * 故这些**别名不入表**（收口前三份旧表都自带 `grey` 系别名，本波按 ADR-005 去掉）。
 * `aqua` / `cyan`、`fuchsia` / `magenta` 是框架里各自独立的 KnownColor 成员，属真名，保留。
 *
 * 查表大小写不敏感（框架用 OrdinalIgnoreCase，实测 `WHITE` / `White` 同值），键一律小写；
 * 小写化走 [key]（`Locale.ROOT`）而不是无参 `lowercase()`，避免土耳其语环境下 `Ivory` /
 * `IndianRed` 的 `I` 被折成无点 `ı` 而查不到。框架不做 trim：实测 `"red "` 的 `A == 0`，
 * 所以 [key] 也不去空白。
 *
 * @see BY_NAME 唯一名单（140 项）
 */
object ColorNames {

    /** 未知名在 `COLOR_FROMNAME` 里的返回值（spec: FunctionMethodCreator.cs:3832 `num = -1`）。 */
    const val UNKNOWN_RGB: Long = -1L

    /** 名单规模（框架枚举实测：`A > 0` 且非系统色共 140 项）。 */
    const val SIZE: Int = 140

    /**
     * 名（小写）到 0xRRGGBB 的唯一权威表，共 [SIZE] 项。
     *
     * 键为 `KnownColor` 成员名的小写形式（如 `lightgoldenrodyellow`），值为该颜色的
     * `R << 16 + G << 8 + B`（spec: FunctionMethodCreator.cs:3824）。不可变。
     */
    val BY_NAME: Map<String, Int> = mapOf(
        "aliceblue" to 0xF0F8FF, "antiquewhite" to 0xFAEBD7, "aqua" to 0x00FFFF,
        "aquamarine" to 0x7FFFD4, "azure" to 0xF0FFFF, "beige" to 0xF5F5DC,
        "bisque" to 0xFFE4C4, "black" to 0x000000, "blanchedalmond" to 0xFFEBCD,
        "blue" to 0x0000FF, "blueviolet" to 0x8A2BE2, "brown" to 0xA52A2A,
        "burlywood" to 0xDEB887, "cadetblue" to 0x5F9EA0, "chartreuse" to 0x7FFF00,
        "chocolate" to 0xD2691E, "coral" to 0xFF7F50, "cornflowerblue" to 0x6495ED,
        "cornsilk" to 0xFFF8DC, "crimson" to 0xDC143C, "cyan" to 0x00FFFF,
        "darkblue" to 0x00008B, "darkcyan" to 0x008B8B, "darkgoldenrod" to 0xB8860B,
        "darkgray" to 0xA9A9A9, "darkgreen" to 0x006400, "darkkhaki" to 0xBDB76B,
        "darkmagenta" to 0x8B008B, "darkolivegreen" to 0x556B2F, "darkorange" to 0xFF8C00,
        "darkorchid" to 0x9932CC, "darkred" to 0x8B0000, "darksalmon" to 0xE9967A,
        "darkseagreen" to 0x8FBC8B, "darkslateblue" to 0x483D8B, "darkslategray" to 0x2F4F4F,
        "darkturquoise" to 0x00CED1, "darkviolet" to 0x9400D3, "deeppink" to 0xFF1493,
        "deepskyblue" to 0x00BFFF, "dimgray" to 0x696969, "dodgerblue" to 0x1E90FF,
        "firebrick" to 0xB22222, "floralwhite" to 0xFFFAF0, "forestgreen" to 0x228B22,
        "fuchsia" to 0xFF00FF, "gainsboro" to 0xDCDCDC, "ghostwhite" to 0xF8F8FF,
        "gold" to 0xFFD700, "goldenrod" to 0xDAA520, "gray" to 0x808080,
        "green" to 0x008000, "greenyellow" to 0xADFF2F, "honeydew" to 0xF0FFF0,
        "hotpink" to 0xFF69B4, "indianred" to 0xCD5C5C, "indigo" to 0x4B0082,
        "ivory" to 0xFFFFF0, "khaki" to 0xF0E68C, "lavender" to 0xE6E6FA,
        "lavenderblush" to 0xFFF0F5, "lawngreen" to 0x7CFC00, "lemonchiffon" to 0xFFFACD,
        "lightblue" to 0xADD8E6, "lightcoral" to 0xF08080, "lightcyan" to 0xE0FFFF,
        "lightgoldenrodyellow" to 0xFAFAD2, "lightgray" to 0xD3D3D3, "lightgreen" to 0x90EE90,
        "lightpink" to 0xFFB6C1, "lightsalmon" to 0xFFA07A, "lightseagreen" to 0x20B2AA,
        "lightskyblue" to 0x87CEFA, "lightslategray" to 0x778899, "lightsteelblue" to 0xB0C4DE,
        "lightyellow" to 0xFFFFE0, "lime" to 0x00FF00, "limegreen" to 0x32CD32,
        "linen" to 0xFAF0E6, "magenta" to 0xFF00FF, "maroon" to 0x800000,
        "mediumaquamarine" to 0x66CDAA, "mediumblue" to 0x0000CD, "mediumorchid" to 0xBA55D3,
        "mediumpurple" to 0x9370DB, "mediumseagreen" to 0x3CB371, "mediumslateblue" to 0x7B68EE,
        "mediumspringgreen" to 0x00FA9A, "mediumturquoise" to 0x48D1CC, "mediumvioletred" to 0xC71585,
        "midnightblue" to 0x191970, "mintcream" to 0xF5FFFA, "mistyrose" to 0xFFE4E1,
        "moccasin" to 0xFFE4B5, "navajowhite" to 0xFFDEAD, "navy" to 0x000080,
        "oldlace" to 0xFDF5E6, "olive" to 0x808000, "olivedrab" to 0x6B8E23,
        "orange" to 0xFFA500, "orangered" to 0xFF4500, "orchid" to 0xDA70D6,
        "palegoldenrod" to 0xEEE8AA, "palegreen" to 0x98FB98, "paleturquoise" to 0xAFEEEE,
        "palevioletred" to 0xDB7093, "papayawhip" to 0xFFEFD5, "peachpuff" to 0xFFDAB9,
        "peru" to 0xCD853F, "pink" to 0xFFC0CB, "plum" to 0xDDA0DD,
        "powderblue" to 0xB0E0E6, "purple" to 0x800080, "red" to 0xFF0000,
        "rosybrown" to 0xBC8F8F, "royalblue" to 0x4169E1, "saddlebrown" to 0x8B4513,
        "salmon" to 0xFA8072, "sandybrown" to 0xF4A460, "seagreen" to 0x2E8B57,
        "seashell" to 0xFFF5EE, "sienna" to 0xA0522D, "silver" to 0xC0C0C0,
        "skyblue" to 0x87CEEB, "slateblue" to 0x6A5ACD, "slategray" to 0x708090,
        "snow" to 0xFFFAFA, "springgreen" to 0x00FF7F, "steelblue" to 0x4682B4,
        "tan" to 0xD2B48C, "teal" to 0x008080, "thistle" to 0xD8BFD8,
        "tomato" to 0xFF6347, "turquoise" to 0x40E0D0, "violet" to 0xEE82EE,
        "wheat" to 0xF5DEB3, "white" to 0xFFFFFF, "whitesmoke" to 0xF5F5F5,
        "yellow" to 0xFFFF00, "yellowgreen" to 0x9ACD32,
    )

    /**
     * 框架 `KnownColor` 里的 33 个系统色名（小写）。不入 [BY_NAME] 的理由见类级 KDoc：
     * PC 侧取值随 Windows 主题变化、不可复现。列在此处只为口径可审计——
     * 命中这些名时三处消费点都按「未知名」处理。
     */
    val SYSTEM_NAMES: Set<String> = setOf(
        "activeborder", "activecaption", "activecaptiontext", "appworkspace", "buttonface",
        "buttonhighlight", "buttonshadow", "control", "controldark", "controldarkdark",
        "controllight", "controllightlight", "controltext", "desktop", "gradientactivecaption",
        "gradientinactivecaption", "graytext", "highlight", "highlighttext", "hottrack",
        "inactiveborder", "inactivecaption", "inactivecaptiontext", "info", "infotext",
        "menu", "menubar", "menuhighlight", "menutext", "scrollbar",
        "window", "windowframe", "windowtext",
    )

    /**
     * 把调用方给的名字折成本表的键。
     *
     * 只做小写化、不做 trim（spec: 框架 `Color.FromName` 不 trim，实测 `"red "` 的 `A == 0`）。
     * 用 `Locale.ROOT` 而不是平台默认 locale，否则 `Ivory` 一类首字母 `I` 在土耳其语环境下
     * 折不出 `i`。
     */
    fun key(name: String): String = name.lowercase(Locale.ROOT)

    /** 按名取 0xRRGGBB；未命中（含 `transparent`、系统色名、`grey` 系拼写）返回 null。 */
    fun rgb(name: String): Int? = BY_NAME[key(name)]

    /**
     * `COLOR_FROMNAME` 口径：未命中折成 [UNKNOWN_RGB]（spec: FunctionMethodCreator.cs:3822-3832）。
     * transparent 的抛错仍由调用方负责（该函数在查表前就抛，口径不变）。
     */
    fun rgbOrUnknown(name: String): Long = (rgb(name) ?: UNKNOWN_RGB.toInt()).toLong()

    /**
     * 拆成 R、G、B 三分量（供 `SETCOLORBYNAME` / `SETBGCOLORBYNAME` 一类的宿主接口用）。
     * 未命中返回 null，抛不抛由调用方决定（各处文案不同，不在本表里统一）。
     */
    fun components(name: String): IntArray? = rgb(name)?.let {
        intArrayOf((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF)
    }

    /**
     * `transparent` 判定（spec: FunctionMethodCreator.cs:3828、Process.cs:1446 / :1499、
     * ArgumentParser.cs:465、HtmlManager.cs:1708 四处都用 OrdinalIgnoreCase）。
     */
    fun isTransparent(name: String): Boolean = name.equals("transparent", ignoreCase = true)
}
