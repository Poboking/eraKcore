package com.eratw.port.render.html

import com.eratw.port.core.fn.ColorNames
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 消费点三：HTML 的 `color` / `bcolor` 属性逐项等于唯一权威表 `ColorNames`
 * （COLOR-SYNC，契约 CONTRACT-M4-C §2 c-colorsync）。
 *
 * 原版这里走 GDI+ `Color.FromName`（HtmlManager.cs:1705）并以 `A == 0` 判「认不认」（:1706），
 * 名单在框架内部；`ColorNames` 的逐项取证（140 名、`grey` 系拼写不认、系统色不入表）与
 * 等长等值断言写在 core 侧 `ColorNamesTest`。本文件把 MiniHtml 的实际取色结果逐项锚到同一张表，
 * 于是「`COLOR_FROMNAME` ↔ `SETCOLORBYNAME` ↔ HTML color」三处消费点传递性一致。
 *
 * 依赖方向 `:render -> :core-vm`（契约 §0，`render/build.gradle.kts:13`）允许这里直接读权威表，
 * 收口前本模块自带的第三份副本（60 名）已删除。
 */
class MiniHtmlColorNamesTest {

    private val m = MiniHtml()

    private fun expect(kind: MiniHtmlErrorKind, html: String) {
        val e = assertFailsWith(MiniHtmlException::class, "应抛 $kind：$html") { m.parse(html) }
        assertEquals(kind, e.kind, "错误类别不符：$html")
    }

    /** 表内 140 名逐个走 HTML `color` 属性，取到的 fore 必须等于表值。 */
    @Test
    fun `HTML color 属性逐名等于权威表`() {
        for ((name, rgb) in ColorNames.BY_NAME) {
            val runs = m.toRuns("<font color='$name'>A</font>")
            assertEquals(rgb, runs[0].fore, "color='$name' 的 fore 应等于权威表值")
        }
    }

    /** 大小写不敏感口径（框架用 OrdinalIgnoreCase，实测 `WHITE` 与 `White` 同值）。 */
    @Test
    fun `表内名的全大写与首字母大写同样命中`() {
        for ((name, rgb) in ColorNames.BY_NAME) {
            assertEquals(rgb, m.toRuns("<font color='${name.uppercase(Locale.ROOT)}'>A</font>")[0].fore, "全大写：$name")
            val cap = name[0].uppercaseChar() + name.substring(1)
            assertEquals(rgb, m.toRuns("<font color='$cap'>A</font>")[0].fore, "首字母大写：$cap")
        }
    }

    /** 语料 4.980 的 HTML 实测六名（gray/lime/aqua/orange/gold/yellow）+ 一条框架真名别名。 */
    @Test
    fun `语料实测名与框架真名别名`() {
        assertEquals(0x808080, m.toRuns("<font color='gray'>A</font>")[0].fore)
        assertEquals(0x00FF00, m.toRuns("<font color='lime'>A</font>")[0].fore)
        assertEquals(0x00FFFF, m.toRuns("<font color='aqua'>A</font>")[0].fore)
        assertEquals(0x00FFFF, m.toRuns("<font color='cyan'>A</font>")[0].fore)
        assertEquals(0xFFA500, m.toRuns("<font color='orange'>A</font>")[0].fore)
        assertEquals(0xFFD700, m.toRuns("<font color='gold'>A</font>")[0].fore)
        assertEquals(0xFFFF00, m.toRuns("<font color='yellow'>A</font>")[0].fore)
        assertEquals(0x32CD32, m.toRuns("<font color='limegreen'>A</font>")[0].fore)
        // 收口前的旧表缺这些语料在用的名字，现在由权威表补上（框架枚举里都有）
        for (name in listOf("deepskyblue", "lawngreen", "hotpink", "dodgerblue", "darkturquoise", "aquamarine")) {
            assertEquals(ColorNames.BY_NAME.getValue(name), m.toRuns("<font color='$name'>A</font>")[0].fore, name)
        }
    }

    /**
     * `bcolor` 与 `color` 走同一个颜色解析入口（HM :1651-1667 的继承也在那里），
     * 因此读的是同一张表。注意：`bcolor` 解析值目前不落 `TextRun.back`（Kernel 无槽位，
     * 已记在 `MiniHtml.FOCUS_DEFAULT` 的 KDoc 接线项），所以这里断言的是「认不认」而非落点。
     */
    @Test
    fun `bcolor 与 color 同源一张表`() {
        m.toRuns("<font bcolor='limegreen'>A</font>")
        m.toRuns("<font bcolor='${ColorNames.BY_NAME.keys.first()}'>A</font>")
        expect(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, "<font bcolor='bogus'>A</font>")
        expect(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, "<font bcolor='grey'>A</font>")
        expect(MiniHtmlErrorKind.COLOR_TRANSPARENT_UNSUPPORTED, "<font bcolor='transparent'>A</font>")
    }

    /** 未知色名与 transparent 的抛错口径不变（HM :1706-1718）。 */
    @Test
    fun `未知名口径不变`() {
        expect(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, "<font color='bogus'>A</font>")
        expect(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, "<font color='grey'>A</font>")
        expect(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, "<font color='darkgrey'>A</font>")
        expect(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, "<font color='window'>A</font>")
        expect(MiniHtmlErrorKind.UNKNOWN_COLOR_NAME, "<font color='red '>A</font>")
        expect(MiniHtmlErrorKind.COLOR_REQUIRED, "<font color=''>A</font>")
        expect(MiniHtmlErrorKind.COLOR_TRANSPARENT_UNSUPPORTED, "<font color='transparent'>A</font>")
    }

    /**
     * 两条原版怪癖必须原样在位（ADR-005），收口动作不许把它们牵连掉：
     * - 一：`#` 前缀的越界分支写在 try 内，OoRColorValue 被裸 catch 吞成
     *   CanNotInterpretNumValue（HM :1690-1701）→ COLOR_OUT_OF_RANGE 是死代码。
     * - 二：不带 `#` 的裸十六进制能解析、却仍抛 InvalidColorName2（HM :1712-1720）。
     */
    @Test
    fun `两条原版怪癖未被收口动作改动`() {
        expect(MiniHtmlErrorKind.COLOR_UNPARSABLE, "<font color='#1000000'>A</font>")
        expect(MiniHtmlErrorKind.COLOR_UNPARSABLE, "<font color='#GG'>A</font>")
        expect(MiniHtmlErrorKind.COLOR_NAME_REJECTED, "<font color='FFFF00'>A</font>")
        expect(MiniHtmlErrorKind.COLOR_NAME_REJECTED, "<font color='00ff00'>A</font>")
        expect(MiniHtmlErrorKind.COLOR_NAME_REJECTED, "<font color='ffffff'>A</font>")
    }
}
