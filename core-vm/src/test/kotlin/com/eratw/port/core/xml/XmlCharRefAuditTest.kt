package com.eratw.port.core.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 数字字符引用前缀大小写审计（R11-xml）。
 *
 * 真值来源：dotnet 8.0.425 实跑（`new XmlDocument().LoadXml(...)`）：
 * - `&#X41;`（大写 X）=> XmlException「Invalid syntax for a decimal numeric entity
 *   reference. Line 1, position 7.」——XML 1.0 [66] CharRef 的十六进制前缀只有小写 x；
 *   原版 FunctionMethodCreator.cs:1699-1706 把 XmlException 包成 CodeEE。
 * - `&#x41;` => 正常解码为 'A'。
 *
 * 修复前 XmlParser.decode 同时接受 `#x`/`#X`，把畸形输入静默解成 'A'
 * （不报错），与原版相反。
 */
class XmlCharRefAuditTest {

    @Test
    fun `大写十六进制前缀的字符引用按原版报错`() {
        assertFailsWith<XmlParseException> { XmlParser.parse("<r>&#X41;</r>") }
    }

    @Test
    fun `小写十六进制与十进制引用仍正常解码`() {
        val doc = XmlParser.parse("<r>&#x41;&#65;</r>")
        assertEquals("AA", doc.root!!.innerText)
    }

    /**
     * `&#xFFFE;` / `&#xFFFF;`：dotnet 8.0.425 实测 `new XmlDocument().LoadXml("<r>&#xFFFE;&#xFFFF;</r>")`
     * 成功、`InnerText` 即 U+FFFE/U+FFFF ⇒ 移植同口径**放行**、与 .NET **一致**
     * （原 `XmlParser.kt` 的 TODO(verify) 由本条读数闭环；非字符 `&#xFDD0;` 同理）。
     */
    @Test
    fun `0xFFFE 与 0xFFFF 放行且与 dotnet LoadXml 一致`() {
        assertEquals("\uFFFE\uFFFF", XmlParser.parse("<r>&#xFFFE;&#xFFFF;</r>").root!!.innerText)
        assertEquals("\uFDD0", XmlParser.parse("<r>&#xFDD0;</r>").root!!.innerText)
    }

    /**
     * 低位控制符 `#x0`..`#x1F`（除 `#x9`/`#xA`/`#xD`）：dotnet 8.0.425 `LoadXml` 实测**放行**
     * （XML-FIVE X1 翻案；2026-11-06 复探 `&#x0;&#x1;&#x8;&#x1F;` 全过、InnerText 码位
     * 0,1,8,31）。旧实现拒绝、方向与 .NET 相反，已翻案：移植同口径放行，`InnerText` 即该
     * 控制码位。跨域钉子 `fn/MapDataTableMethodsTest`（`&#0;`）与 `fn/MapXmlUnifyTest`
     * （`&#x1;`）已同步翻钉。回写面把控制符重编成大写十六进制字符引用（`&#x1;`，同轮探针
     * 实测 OuterXml/InnerXml/属性值三处一致），一并在本用例钉住。
     */
    @Test
    fun `低位控制符字符引用放行且与 dotnet LoadXml 一致`() {
        val doc = XmlParser.parse("<r>&#x0;&#x1;&#x8;&#x1F;</r>")
        assertEquals("\u0000\u0001\u0008\u001F", doc.root!!.innerText)
        // OuterXml 回写：除 \t/\n/\r 外的控制符编成大写十六进制字符引用（dotnet 实测同形）
        assertEquals("<r>&#x0;&#x1;&#x8;&#x1F;</r>", doc.root!!.outerXml())
        // 属性值里的控制符同样回编（dotnet 实测 `<r a="p&#x1F;q" />`）
        assertEquals(
            "<r a=\"p&#x1F;q\" />",
            XmlParser.parse("<r a='p&#x1F;q'/>").root!!.outerXml(),
        )
        // XML 空白三兄弟照常放行且原样写出（.NET 同）；夹非空白字符，避免纯空白文本节点被
        // PreserveWhitespace=false 丢弃而看不见解码结果。
        val ws = XmlParser.parse("<r>x&#x9;&#xA;&#xD;y</r>")
        assertEquals("x\t\n\ry", ws.root!!.innerText)
        assertEquals("<r>x\t\n\ry</r>", ws.root!!.outerXml())
    }

    /**
     * 代理区 / 超 0x10FFFF：dotnet 8.0.425 `LoadXml` 抛 **ArgumentException**（实测
     * 「The surrogate pair (0xD800, 0xD800) is invalid.」/「Invalid high surrogate character (0xDC00).」），
     * 而 Emuera 只 catch `XmlException`（FunctionMethodCreator.cs:181-184 / :3061-3064）
     * ⇒ **原版未捕获崩溃**；移植按 ADR-005「官方未捕获崩溃不复刻」转成可诊断的
     * [XmlParseException]（调用方落 CodeEE）。
     */
    @Test
    fun `代理区与超范围字符引用按 ADR-005 转可诊断异常`() {
        assertFailsWith<XmlParseException> { XmlParser.parse("<r>&#xD800;</r>") }
        assertFailsWith<XmlParseException> { XmlParser.parse("<r>&#xDFFF;</r>") }
        assertFailsWith<XmlParseException> { XmlParser.parse("<r>&#x110000;</r>") }
    }

    // ==================================================================
    // RESIDUE-BX2 R1（2026-11-06）：源文本「裸」非法字符的拦截面
    // ==================================================================

    /**
     * 源文本含字面 <0x20 控制符（**非字符引用形态**）：dotnet 8.0.425 `LoadXml` 与
     * `InnerXml` 片段赋值都抛 XmlException
     * "'<char>', hexadecimal value 0x01, is an invalid character. Line 1, position N."
     * （探针 `<work-root>/residue-probe/probe-output.txt`：R1a 文本 / R1b 属性值 / R1c 注释 /
     * R1d CDATA / R1e PI 数据 / R1t 散位领先 / R1u 落根后 / R1w 片段赋值；zh-CN culture
     * 下报文不变——net8 的 System.Xml 无本地化卫星资源）。XML-FIVE 收口时移植层不拦、
     * 已按台账 §27.4② 登记相邻分歧，RESIDUE-BX2 落地同口径拦截（判据与布点见
     * [isInvalidXmlLiteralChar]）。移植侧经 XmlParseException → CodeEE 折化
     * （XML_GET 家族 FunctionMethodCreator.cs:181-184 的 catch 面），与既有口径一致。
     *
     * 覆盖面另含探针 R1p/R1q 的放行侧：0x7F / 0x85 属 XML 1.0 合法段，.NET 放行。
     */
    @Test
    fun `裸控制符源文本按 dotnet 拦且文案对齐`() {
        for (src in listOf(
            "<r>a\u0001b</r>", // 文本内容（R1a）
            "<r a=\"x\u0001y\"/>", // 属性值（R1b）
            "<r><!--\u0001--></r>", // 注释正文（R1c）
            "<r><![CDATA[\u0001]]></r>", // CDATA（R1d）
            "<r><?pi \u0001?></r>", // PI 数据（R1e）
            "\u000C<r/>", // 散位·领先（R1t，0x0C 不属 XML 空白四字符）
            "<r/>\u0001", // 散位·落根后（R1u）
        )) {
            val err = assertFailsWith<XmlParseException>("源=$src") { XmlParser.parse(src) }
            assertTrue(
                err.message!!.contains(", hexadecimal value 0x") &&
                    err.message!!.contains(", is an invalid character"),
                "应对齐 .NET invalid character 文案，实际：${err.message}",
            )
        }
        // InnerXml 片段赋值（XML_SET style=2 面）同拦（探针 R1w）；片层面 fn 侧折 CodeEE
        assertFailsWith<XmlParseException> { XmlParser.parseFragment("x\u0001y") }
        // tab/lf/cr 字面照常放行且原样进 DOM（探针 R1h 基准）
        assertEquals("a\tb\nc\rd", XmlParser.parse("<r>a\tb\nc\rd</r>").root!!.innerText)
        // 0x7F / 0x85 放行（探针 R1p/R1q：XML 1.0 合法段，.NET 不拦）
        assertEquals("\u007F\u0085", XmlParser.parse("<r>\u007F\u0085</r>").root!!.innerText)
    }

    /**
     * 名字位的裸控制符 .NET 给「Name cannot begin with」变体文案（探针 R1f：
     * `<\u0001/>` → "Name cannot begin with the '' character, hexadecimal value 0x01."），
     * 与内容位的「is an invalid character」分属两条。
     */
    @Test
    fun `名字位裸控制符走 Name cannot begin with 变体文案`() {
        val err = assertFailsWith<XmlParseException> { XmlParser.parse("<\u0001/>") }
        assertTrue(
            err.message!!.contains("Name cannot begin with the") &&
                err.message!!.contains("hexadecimal value 0x01"),
            "应对齐 .NET 名字位文案，实际：${err.message}",
        )
    }

    /**
     * 字面 U+FFFE / U+FFFF 与字符引用形态**方向相反**（探针 R1k/R1n：字面抛
     * invalid character；`&#xFFFE;` 放行 = 本文件既有钉）。同一判据内一并拦截字面形态。
     */
    @Test
    fun `字面 FFFE FFFF 拦而字符引用放行`() {
        assertFailsWith<XmlParseException> { XmlParser.parse("<r>\uFFFE</r>") }
        assertFailsWith<XmlParseException> { XmlParser.parse("<r>\uFFFF</r>") }
        assertEquals("\uFFFE", XmlParser.parse("<r>&#xFFFE;</r>").root!!.innerText)
    }

    /**
     * 孤立代理的**字面**形态：.NET 报文是成对消费后的下一字符（探针 R1o：
     * `<r>\uD800</r>` 报 "'<', hexadecimal value 0x3C, is an invalid character."）——
     * 读取器代理对消费的框架内部行为，本解析器逐字符扫描无此模型，不硬造（ADR-005）。
     * 本用例只钉移植现状：孤立代理字面可进 DOM（语料/基准无此形态，不可达）。
     */
    @Test
    fun `孤立代理字面不拦（框架成对消费行为不复刻，见台账）`() {
        assertEquals("\uD800", XmlParser.parse("<r>\uD800</r>").root!!.innerText)
    }
}
