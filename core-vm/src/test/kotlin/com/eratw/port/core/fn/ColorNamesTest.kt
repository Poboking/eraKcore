package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.sem.SemTestEnv
import com.eratw.port.core.sem.instr
import com.eratw.port.core.sem.strArg
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.vars.VariableStore
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 命名色单一权威表（[ColorNames]）的回归。
 *
 * ## 期望值从哪来（不是拿实现回读）
 *
 * 原版四处（`COLOR_FROMNAME` / `SETCOLORBYNAME` / `SETBGCOLORBYNAME` / HTML 的 `color` 属性）
 * 都把名字交给 `System.Drawing.Color.FromName`，判定统一是 `color.A > 0`
 * （spec: FunctionMethodCreator.cs:3820-3822、Process.cs:1443-1444 与 :1496-1497、
 * ArgumentParser.cs:463、HtmlManager.cs:1705-1706）。名单在框架内部，反编译快照里没有逐名的
 * 源码行，所以逐名期望值取自**框架本身**：本机 Windows PowerShell 5.1（.NET Framework 4.8 的
 * System.Drawing）枚举 `KnownColor` 全部 174 项并逐项 `Color.FromName` 读 `A`/`R`/`G`/`B`/
 * `IsSystemColor`。复现命令（原样可粘贴）：
 *
 *     Add-Type -AssemblyName System.Drawing
 *     [Enum]::GetNames([System.Drawing.KnownColor]) | ForEach-Object {
 *         [System.Drawing.Color]::FromName([string]$_) } |
 *         ForEach-Object { '{0}|A={1}|{2:X2}{3:X2}{4:X2}|sys={5}' -f $_.Name, $_.A, $_.R, $_.G, $_.B, $_.IsSystemColor }
 *
 * 读数：174 项 = **140 项 `A > 0` 且非系统色**（[FRAMEWORK_NAMED]）+ **33 项系统色**
 * （[FRAMEWORK_SYSTEM]，取值来自 GetSysColor、随 Windows 主题变化，故不入表）+
 * **1 项 `Transparent`（`A == 0`）**。别名与大小写口径同样实测：`WHITE`/`White` 同值，
 * `"red "` 与 `" white "` 因框架不 trim 而 `A == 0`；`grey`/`darkgrey`/`lightgrey`/`dimgrey`/
 * `slategrey` 系拼写 `A == 0`（框架只有 `gray` 系拼写）；`#FF0000`、`FF0000`、`0xFF0000`
 * 也都 `A == 0`。
 *
 * ## 三处消费点怎么断言
 *
 * 本文件断言消费点一（`COLOR_FROMNAME`，[ColorNames] 直连）与消费点二
 * （`SETCOLORBYNAME` / `SETBGCOLORBYNAME`，走 [SemTestEnv] 的真实语句路径）逐项等于权威表；
 * 消费点三（render 层 HTML 的 `color`/`bcolor`）在
 * `render/src/test/kotlin/com/eratw/port/render/html/MiniHtmlColorNamesTest.kt` 里逐项等于同一张
 * 权威表（`:render -> :core-vm` 单向依赖，见契约 CONTRACT-M4-C §0）。两头都锚定 [ColorNames]，
 * 故三处传递性一致。
 *
 * **注意（组长接线）**：本文件里名字含「SETCOLORBYNAME / SETBGCOLORBYNAME」的两条用例读的是
 * `StatementExecutor`，而该文件归 c-boot2、本路禁改，其 `NAMED_COLORS` 副本改读权威表的需求
 * 以统一 diff 形式交在交付报告。那两条用例在 diff 落地前必红（缺 `limegreen` 一类名），
 * 这是设计如此的红，不是回归。
 */
class ColorNamesTest {

    // ---------------------------------------------------------------- 权威表本身

    @Test
    fun `权威表与框架 KnownColor 名单等长等值`() {
        assertEquals(140, FRAMEWORK_NAMED.size, "框架枚举读数是 140 项")
        assertEquals(33, FRAMEWORK_SYSTEM.size, "框架枚举读数是 33 项系统色")
        assertEquals(140, ColorNames.BY_NAME.size)
        assertEquals(ColorNames.SIZE, ColorNames.BY_NAME.size)
        assertEquals(FRAMEWORK_NAMED, ColorNames.BY_NAME.mapValues { it.value.toLong() }, "逐项名单比对")
        // 174 = 140 名色 + 33 系统色 + transparent（A=0）
        assertEquals(174, ColorNames.BY_NAME.size + ColorNames.SYSTEM_NAMES.size + 1)
    }

    @Test
    fun `系统色名与 transparent 不入表`() {
        assertEquals(FRAMEWORK_SYSTEM, ColorNames.SYSTEM_NAMES, "系统色名单逐项比对")
        assertTrue(ColorNames.BY_NAME.keys.none { it in ColorNames.SYSTEM_NAMES }, "两份名单必须互斥")
        assertFalse(ColorNames.BY_NAME.containsKey("transparent"), "transparent 在框架里 A=0，不是可取值色名")
        assertNull(ColorNames.rgb("transparent"))
        assertNull(ColorNames.rgb("window"))
        assertNull(ColorNames.rgb("control"))
    }

    @Test
    fun `键全小写且折键幂等`() {
        for ((name, rgb) in ColorNames.BY_NAME) {
            assertEquals(name, ColorNames.key(name), "键必须已是小写形态：$name")
            assertFalse(name.any { it.isUpperCase() }, "键含大写：$name")
            assertEquals(rgb, ColorNames.rgb(name), "原名命中：$name")
            // 框架用 OrdinalIgnoreCase：实测 WHITE / White 同值
            assertEquals(rgb, ColorNames.rgb(name.uppercase(Locale.ROOT)), "全大写命中：$name")
            val cap = name[0].uppercaseChar() + name.substring(1)
            assertEquals(rgb, ColorNames.rgb(cap), "首字母大写命中：$cap")
            // 框架不 trim：实测 "red " 的 A=0
            assertNull(ColorNames.rgb(" $name "), "带空白不命中：$name")
        }
    }

    @Test
    fun `grey 系拼写在框架里不存在`() {
        // 实测 A=0：框架只有 gray 系拼写，故旧表里的 grey 系别名按未知名处理（ADR-005）。
        for (name in listOf("grey", "darkgrey", "lightgrey", "dimgrey", "slategrey", "darkslategrey", "lightslategrey")) {
            assertNull(ColorNames.rgb(name), "框架不认识：$name")
            assertFalse(ColorNames.BY_NAME.containsKey(name))
        }
    }

    @Test
    fun `框架真名 aqua-cyan 与 fuchsia-magenta 成对保留`() {
        // 这四名是各自独立的 KnownColor 成员，不是别名（框架枚举实测同值）。
        assertEquals(0x00FFFF, ColorNames.rgb("aqua"))
        assertEquals(0x00FFFF, ColorNames.rgb("cyan"))
        assertEquals(0xFF00FF, ColorNames.rgb("fuchsia"))
        assertEquals(0xFF00FF, ColorNames.rgb("magenta"))
    }

    @Test
    fun `分量拆分口径与框架折值一致`() {
        for ((name, rgb) in ColorNames.BY_NAME) {
            val c = ColorNames.components(name) ?: fail("三分量缺失：$name")
            assertEquals(3, c.size, "三分量长度：$name")
            // spec: FunctionMethodCreator.cs:3824 的 (R << 16) + (G << 8) + B
            assertEquals(rgb, (c[0] shl 16) + (c[1] shl 8) + c[2], "折回 0xRRGGBB：$name")
            assertTrue(c.all { it in 0..255 }, "分量必须在 0-255：$name")
        }
    }

    // ---------------------------------------------------------- 消费点一 COLOR_FROMNAME

    @Test
    fun `消费点一 COLOR_FROMNAME 逐名等于权威表`() {
        val ctx = fnCtx()
        for ((name, rgb) in ColorNames.BY_NAME) {
            assertEquals(rgb.toLong(), colorFromName(ctx, name), "COLOR_FROMNAME($name)")
            assertEquals(rgb.toLong(), colorFromName(ctx, name.uppercase(Locale.ROOT)), "COLOR_FROMNAME(大写 $name)")
            assertEquals(rgb.toLong(), ColorNames.rgbOrUnknown(name), "rgbOrUnknown($name)")
        }
    }

    @Test
    fun `消费点一 COLOR_FROMNAME 未知名仍返回负一`() {
        // spec: FunctionMethodCreator.cs:3822 判 A>0、:3832 未命中 num = -1
        val ctx = fnCtx()
        for (name in listOf("nosuchcolor", "", " ", "red ", " grey", "GREY", "darkgrey", "lightgrey", "dimgrey", "slategrey", "window", "control", "highlight", "menutext", "#FF0000", "FF0000", "0xFF0000", "aliceblue2")) {
            assertEquals(-1L, colorFromName(ctx, name), "未知名必须折成 -1：[$name]")
            assertEquals(ColorNames.UNKNOWN_RGB, ColorNames.rgbOrUnknown(name), "哨兵口径：[$name]")
        }
    }

    @Test
    fun `消费点一 COLOR_FROMNAME 的 transparent 抛错口径不变`() {
        // spec: FunctionMethodCreator.cs:3828-3830（A==0 且名字是 transparent → CodeEE）
        val ctx = fnCtx()
        for (name in listOf("transparent", "TRANSPARENT", "Transparent", "TrAnSpArEnT")) {
            val e = assertFailsWith<CodeEE> { colorFromName(ctx, name) }
            assertTrue(e.message!!.contains("COLOR_FROMNAME"), e.message ?: "无消息")
            assertTrue(e.message!!.contains("transparent"), e.message ?: "无消息")
        }
        assertTrue(ColorNames.isTransparent("TRANSPARENT"))
        assertFalse(ColorNames.isTransparent(" transparent"))
    }

    // ------------------------------------------------- 消费点二 SET(COLOR|BGCOLOR)BYNAME

    @Test
    fun `消费点二 SETCOLORBYNAME 逐名等于权威表`() {
        assertByNameStatement(FunctionCode.SETCOLORBYNAME, "setForeColor")
    }

    @Test
    fun `消费点二 SETBGCOLORBYNAME 逐名等于权威表`() {
        assertByNameStatement(FunctionCode.SETBGCOLORBYNAME, "setBackColor")
    }

    @Test
    fun `消费点二 未知名与 transparent 的抛错文案不变`() {
        // spec: Process.cs:1444-1450 与 :1497-1503（transparent 与无效名两类 CodeEE）
        for (code in listOf(FunctionCode.SETCOLORBYNAME, FunctionCode.SETBGCOLORBYNAME)) {
            val transparent = assertFailsWith<CodeEE> { SemTestEnv().run(instr(code, strArg("transparent"))) }
            assertTrue(transparent.message!!.contains("透明"), transparent.message ?: "无消息")
            for (bad in listOf("notacolor", "grey", "window", "", "#FF0000")) {
                val e = assertFailsWith<CodeEE> { SemTestEnv().run(instr(code, strArg(bad))) }
                assertTrue(e.message!!.contains("无效"), "$code [$bad]：" + (e.message ?: "无消息"))
            }
        }
    }

    // ------------------------------------------------------------------------ 辅助

    private fun assertByNameStatement(code: FunctionCode, eventPrefix: String) {
        val env = SemTestEnv()
        val expected = mutableListOf<String>()
        for ((name, rgb) in ColorNames.BY_NAME) {
            env.run(instr(code, strArg(name)))
            expected += "$eventPrefix(${(rgb shr 16) and 0xFF},${(rgb shr 8) and 0xFF},${rgb and 0xFF})"
            env.run(instr(code, strArg(name.uppercase(Locale.ROOT))))
            expected += "$eventPrefix(${(rgb shr 16) and 0xFF},${(rgb shr 8) and 0xFF},${rgb and 0xFF})"
        }
        assertEquals(expected, env.console.events, "$code 逐项等于权威表（140 名 × 原名与大写）")
    }
}

// ---------------------------------------------------------------------------
// 夹具：语句与函数调用的最小语境
// ---------------------------------------------------------------------------

/** 假 EvalContext：只提供变量读，函数调用一律炸，确保 COLOR_FROMNAME 走的是内建注册表。 */
private class FnEvalContext : EvalContext {
    override lateinit var variables: VariableReader

    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("夹具不该调用函数 $name")
}

private fun fnCtx(): BuiltinEvalContext {
    val store = VariableStore()
    return BuiltinEvalContext(FnEvalContext().apply { variables = store }, store)
}

private fun colorFromName(ctx: BuiltinEvalContext, name: String): Long =
    (ctx.callFunction("COLOR_FROMNAME", listOf(Value.StrV(name))) as Value.IntV).v

/**
 * 框架枚举读数（复现命令见 [ColorNamesTest] 类级注释）。
 * 键 = `KnownColor` 成员名的小写；值 = `R << 16 + G << 8 + B`。共 140 项。
 */
private val FRAMEWORK_NAMED: Map<String, Long> = parsePairs(
    """
    aliceblue=F0F8FF antiquewhite=FAEBD7 aqua=00FFFF aquamarine=7FFFD4
    azure=F0FFFF beige=F5F5DC bisque=FFE4C4 black=000000
    blanchedalmond=FFEBCD blue=0000FF blueviolet=8A2BE2 brown=A52A2A
    burlywood=DEB887 cadetblue=5F9EA0 chartreuse=7FFF00 chocolate=D2691E
    coral=FF7F50 cornflowerblue=6495ED cornsilk=FFF8DC crimson=DC143C
    cyan=00FFFF darkblue=00008B darkcyan=008B8B darkgoldenrod=B8860B
    darkgray=A9A9A9 darkgreen=006400 darkkhaki=BDB76B darkmagenta=8B008B
    darkolivegreen=556B2F darkorange=FF8C00 darkorchid=9932CC darkred=8B0000
    darksalmon=E9967A darkseagreen=8FBC8B darkslateblue=483D8B darkslategray=2F4F4F
    darkturquoise=00CED1 darkviolet=9400D3 deeppink=FF1493 deepskyblue=00BFFF
    dimgray=696969 dodgerblue=1E90FF firebrick=B22222 floralwhite=FFFAF0
    forestgreen=228B22 fuchsia=FF00FF gainsboro=DCDCDC ghostwhite=F8F8FF
    gold=FFD700 goldenrod=DAA520 gray=808080 green=008000
    greenyellow=ADFF2F honeydew=F0FFF0 hotpink=FF69B4 indianred=CD5C5C
    indigo=4B0082 ivory=FFFFF0 khaki=F0E68C lavender=E6E6FA
    lavenderblush=FFF0F5 lawngreen=7CFC00 lemonchiffon=FFFACD lightblue=ADD8E6
    lightcoral=F08080 lightcyan=E0FFFF lightgoldenrodyellow=FAFAD2 lightgray=D3D3D3
    lightgreen=90EE90 lightpink=FFB6C1 lightsalmon=FFA07A lightseagreen=20B2AA
    lightskyblue=87CEFA lightslategray=778899 lightsteelblue=B0C4DE lightyellow=FFFFE0
    lime=00FF00 limegreen=32CD32 linen=FAF0E6 magenta=FF00FF
    maroon=800000 mediumaquamarine=66CDAA mediumblue=0000CD mediumorchid=BA55D3
    mediumpurple=9370DB mediumseagreen=3CB371 mediumslateblue=7B68EE mediumspringgreen=00FA9A
    mediumturquoise=48D1CC mediumvioletred=C71585 midnightblue=191970 mintcream=F5FFFA
    mistyrose=FFE4E1 moccasin=FFE4B5 navajowhite=FFDEAD navy=000080
    oldlace=FDF5E6 olive=808000 olivedrab=6B8E23 orange=FFA500
    orangered=FF4500 orchid=DA70D6 palegoldenrod=EEE8AA palegreen=98FB98
    paleturquoise=AFEEEE palevioletred=DB7093 papayawhip=FFEFD5 peachpuff=FFDAB9
    peru=CD853F pink=FFC0CB plum=DDA0DD powderblue=B0E0E6
    purple=800080 red=FF0000 rosybrown=BC8F8F royalblue=4169E1
    saddlebrown=8B4513 salmon=FA8072 sandybrown=F4A460 seagreen=2E8B57
    seashell=FFF5EE sienna=A0522D silver=C0C0C0 skyblue=87CEEB
    slateblue=6A5ACD slategray=708090 snow=FFFAFA springgreen=00FF7F
    steelblue=4682B4 tan=D2B48C teal=008080 thistle=D8BFD8
    tomato=FF6347 turquoise=40E0D0 violet=EE82EE wheat=F5DEB3
    white=FFFFFF whitesmoke=F5F5F5 yellow=FFFF00 yellowgreen=9ACD32
    """.trimIndent(),
)

/** 框架 33 个系统色名（`IsSystemColor` 为真；PC 侧取值随主题变化，本表不含）。 */
private val FRAMEWORK_SYSTEM: Set<String> = setOf(
    "activeborder", "activecaption", "activecaptiontext", "appworkspace", "buttonface",
    "buttonhighlight", "buttonshadow", "control", "controldark", "controldarkdark",
    "controllight", "controllightlight", "controltext", "desktop", "gradientactivecaption",
    "gradientinactivecaption", "graytext", "highlight", "highlighttext", "hottrack",
    "inactiveborder", "inactivecaption", "inactivecaptiontext", "info", "infotext",
    "menu", "menubar", "menuhighlight", "menutext", "scrollbar",
    "window", "windowframe", "windowtext",
)

private fun parsePairs(src: String): Map<String, Long> =
    src.split(Regex("\\s+")).filter { it.isNotEmpty() }.map {
        it.substringBefore('=') to it.substringAfter('=').toLong(16)
    }.toMap()
