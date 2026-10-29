package com.eratw.port.core.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
