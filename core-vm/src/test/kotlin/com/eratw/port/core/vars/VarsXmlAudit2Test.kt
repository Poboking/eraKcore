package com.eratw.port.core.vars

import com.eratw.port.core.xml.XmlCData
import com.eratw.port.core.xml.XmlElement
import com.eratw.port.core.xml.XmlParser
import com.eratw.port.core.xml.XmlPath
import com.eratw.port.core.xml.XmlProcessingInstruction
import com.eratw.port.core.xml.XmlText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * audit2 / L13b —— 变量表与 XML 的第二轮审计（变量侧无缺陷，XML 侧 5 条）。
 *
 * 全部 XML 期望值都来自 **dotnet 8.0.421 实跑** `System.Xml.XmlDocument`（不是猜的；
 * 原版 `Emuera.csproj:7` 的 TargetFramework 是 netcoreapp8.0，所以 .NET 8 的 BCL 行为即基准）：
 * 被测对象是 `XmlDocument.LoadXml` / `XmlNode.OuterXml` / `XmlDocument.SelectNodes`
 * 这三条语义，而原版只是把它们包了一层（`FunctionMethodCreator.cs:179` 的 `LoadXml`、
 * `:189` 的 `SelectNodes`、`:1314` 的 `OuterXml`；`XML_GET` 的 style 分支
 * `OutPutNode` 在 `:135-157`，style=4 取 `node.Name`）。逐条对应见各 @Test 的说明。
 *
 * 第 5 条（`node()` 也要按文本段合并）是续任审计补的：前一轮只合并了 `text()` 步。
 *
 * 变量侧本轮复核结论（只改过一处注释，其余留断言防回退）：
 * - 默认尺寸表逐槽等于 `ConstantData.cs:171-260`（[VariableSizes] 的 int1D/str1D/
 *   charaInt1D/maxDataList 特例）；
 * - `IsSavedata` 位规则等于 `VariableToken.cs:88-154`（STR=false / TSTR=true / CDFLAG=true；
 *   阈值 60 与 1 就是 `VariableData.cs:2922`/`:2924` 的 `intArrayCount`/`strArrayCount`）；
 * - `CharacterTable` 的角色 0 维列数 = `CharacterData.cs:57-62`（int 2 / str 4 / 1D int 84 /
 *   1D str 1 / 2D int 1），2 维的长宽拆分 = `CharacterData.cs:73-75`（高 32 位 = 第一维），
 *   寻址走行主序（`VariableData.cs:117` 的 `long[,]` 布局）。
 */
class VarsXmlAudit2Test {

    // ------------------------------------------------------------------
    // 1. text() 含 CDATA（XmlPath）
    // ------------------------------------------------------------------

    /**
     * .NET 的 XPath 数据模型里 CDATA 不是独立节点类型：`text()` 既选文本也选 CDATA，
     * 且**相邻的文本 + CDATA 合并成一个**文本节点，`XmlDocument.SelectNodes` 返回段首的
     * 那个 DOM 节点。dotnet 8.0.421 实测：
     * - `<r><![CDATA[x]]>y</r>` 的 `/r/text()` → 1 个节点，是 CDATA（Value = "x"）；
     * - `<r>y<![CDATA[x]]></r>` 的 `/r/text()` → 1 个节点，是 text（Value = "y"）；
     * - `<r>a<e/><![CDATA[b]]></r>` 的 `/r/text()` → 2 个（text "a" 与 CDATA "b"，中间隔着 e）。
     */
    @Test
    fun `text 步匹配 CDATA 且相邻文本段合并成段首节点`() {
        val cdataFirst = XmlParser.parse("<r><![CDATA[x]]>y</r>")
        val n1 = XmlPath.select(cdataFirst, "/r/text()")
        assertEquals(1, n1.size, "CDATA+文本 合并成 1 个节点")
        assertTrue(n1[0] is XmlCData, "段首是 CDATA 节点")
        assertEquals("x", n1[0].value, ".NET 返回段首 DOM 节点自身的 Value")

        val textFirst = XmlParser.parse("<r>y<![CDATA[x]]></r>")
        val n2 = XmlPath.select(textFirst, "/r/text()")
        assertEquals(listOf("y"), n2.map { it.value })
        assertTrue(n2[0] is XmlText)

        val split = XmlParser.parse("<r>a<e/><![CDATA[b]]></r>")
        val n3 = XmlPath.select(split, "/r/text()")
        assertEquals(2, n3.size, "元素打断文本段 ⇒ 两个文本节点")
        assertTrue(n3[0] is XmlText && n3[1] is XmlCData)

        // 谓词 [text()] 同理：CDATA-only 元素为真（dotnet 8 实测 a[text()] = 1）
        val pred = XmlParser.parse("<r><a><![CDATA[x]]></a><b/></r>")
        assertEquals(1, XmlPath.select(pred, "//a[text()]").size)
        assertEquals(0, XmlPath.select(pred, "//b[text()]").size)
        assertEquals(1, XmlPath.select(pred, "//a[text()='x']").size)
    }

    // ------------------------------------------------------------------
    // 1b. node() 与 text() 同口径合并（续任审计补的 XML 第 5 条）
    // ------------------------------------------------------------------

    /**
     * `node()` 用的也是「文本/CDATA 合并成一个节点」的数据模型，而不是逐个 DOM 子节点
     * （dotnet 8.0.421 实测：`/r/node()` 对 `<r><![CDATA[x]]>y</r>` 得 1 个（CDATA）、
     * 对 `<r>a<![CDATA[b]]>c</r>` 也得 1 个（text "a"）；`/r/node()[2]` 对
     * `<r><![CDATA[x]]>y<e/></r>` 得那个 `<e/>`；`//node()` 对 `<r><![CDATA[x]]>y</r>`
     * 得 2 个 = 根元素 + 段首 CDATA）。前一轮只给 `text()` 打了合并，`node()` 漏了。
     */
    @Test
    fun `node 步也按文本段合并而不是逐个 DOM 子节点`() {
        val cdataThenText = XmlParser.parse("<r><![CDATA[x]]>y</r>")
        val n1 = XmlPath.select(cdataThenText, "/r/node()")
        assertEquals(1, n1.size, "CDATA+文本 在 node() 里也只算 1 个节点")
        assertTrue(n1[0] is XmlCData)

        assertEquals(1, XmlPath.select(XmlParser.parse("<r>a<![CDATA[b]]>c</r>"), "/r/node()").size)

        val withElement = XmlParser.parse("<r><![CDATA[x]]>y<e/></r>")
        assertEquals(2, XmlPath.select(withElement, "/r/node()").size, "段首节点 + <e/>")
        val second = XmlPath.select(withElement, "/r/node()[2]")
        assertEquals(1, second.size)
        assertTrue(second[0] is XmlElement, "node()[2] 是 <e/> 而不是被合并掉的文本")

        assertEquals(2, XmlPath.select(cdataThenText, "//node()").size, "根元素 + 一个合并文本节点")

        // 注释不合并：它不是文本段的一部分
        assertEquals(3, XmlPath.select(XmlParser.parse("<r>t<!--c--><![CDATA[d]]></r>"), "/r/node()").size)

        // 名字测试与属性轴不受合并影响
        assertEquals(1, XmlPath.select(XmlParser.parse("<r><a/></r>"), "/r/*").size)
        assertEquals(1, XmlPath.select(XmlParser.parse("<r a='1'/>"), "/r/@*").size)
    }

    // ------------------------------------------------------------------
    // 2. 处理指令的数据与序列化（XmlParser / XmlProcessingInstruction）
    // ------------------------------------------------------------------

    /**
     * .NET 把 target 与数据之间的**全部**空白吃掉、尾部空白保留；序列化时无条件写一个空格。
     * dotnet 8.0.421 实测：`<?pi   x  y ?>` → Data = "x  y "；`<?pi\tx?>` → "x"；
     * `<?pi?>` 与 `<?pi ?>` 的 OuterXml 都是 `<?pi ?>`。
     */
    @Test
    fun `处理指令去掉 target 后的全部空白且空数据仍写一个空格`() {
        val d = XmlParser.parse("<r><?pi   x  y ?></r>")
        val pi = d.root!!.children.filterIsInstance<XmlProcessingInstruction>().single()
        assertEquals("x  y ", pi.data, "前置空白全去、尾部空白保留")
        assertEquals("<?pi x  y ?>", pi.outerXml())

        val tab = XmlParser.parse("<r><?pi\tx?></r>").root!!
            .children.filterIsInstance<XmlProcessingInstruction>().single()
        assertEquals("x", tab.data)

        val empty = XmlParser.parse("<r><?pi?></r>").root!!
            .children.filterIsInstance<XmlProcessingInstruction>().single()
        assertEquals("", empty.data)
        assertEquals("<?pi ?>", empty.outerXml(), "空数据也写 target 后的那一个空格")
    }

    // ------------------------------------------------------------------
    // 3. 片段（InnerXml 赋值）保留纯空白文本（XmlParser.parseFragment）
    // ------------------------------------------------------------------

    /**
     * `PreserveWhitespace=false` 只作用于 `LoadXml` 的文档级空白；给 `InnerXml` 赋值走的是
     * 片段解析，纯空白文本节点照样建。dotnet 8.0.421 实测：
     * `e.InnerXml = "  "` → `<r>  </r>`；`e.InnerXml = ""` → `<r />`（不建空文本节点）。
     */
    @Test
    fun `片段保留纯空白文本而文档级空白仍丢弃`() {
        val frag = XmlParser.parseFragment("  ")
        assertEquals(1, frag.size)
        assertTrue(frag[0] is XmlText)
        assertEquals("  ", frag[0].value)
        assertTrue(XmlParser.parseFragment("").isEmpty(), "空片段不建文本节点")

        val doc = XmlParser.parse("<r> <a/> </r>")
        assertEquals("<r><a /></r>", doc.outerXml(), "LoadXml 口径：元素间纯空白丢弃")
    }

    // ------------------------------------------------------------------
    // 4. 空元素收尾形态（XmlElement.IsEmpty）
    // ------------------------------------------------------------------

    /**
     * `OuterXml` 收尾写 ` />` 还是 `<a></a>` 取决于 .NET 的内部标志 `IsEmpty`，与「当前有没有
     * 子节点」不等价。dotnet 8.0.421 实测：
     * - `<a></a>` 加载后仍是 `<a></a>`；`<a/>` 加载后是 `<a />`；
     * - `<a>x</a>` 的文本被移除后是 `<a></a>`（曾有过子节点 ⇒ IsEmpty 已置 false，不可逆）；
     * - `CreateElement` 建的空元素是 `<a />`；只加属性仍是 `<a x="1" />`。
     */
    @Test
    fun `空元素的方括号形态按 IsEmpty 标志而不是子节点数`() {
        assertEquals("<a></a>", XmlParser.parse("<a></a>").outerXml())
        assertEquals("<a />", XmlParser.parse("<a/>").outerXml())
        assertEquals("<r><a></a><b /></r>", XmlParser.parse("<r><a></a><b /></r>").outerXml())

        val had = XmlParser.parse("<a>x</a>")
        had.root!!.removeChild(had.root!!.children.single())
        assertTrue(had.root!!.children.isEmpty())
        assertEquals("<a></a>", had.outerXml(), "加过子节点后 IsEmpty 已不可逆")

        val created = XmlElement("a")
        assertEquals("<a />", created.outerXml(), "CreateElement 口径")
        created.appendChild(XmlText("x"))
        assertEquals("<a>x</a>", created.outerXml())
        created.removeChild(created.children.single())
        assertEquals("<a></a>", created.outerXml(), "创建后加过子节点 ⇒ 不再是自闭合形态")

        val withAttr = XmlParser.parse("<r><a x='1'/></r>").root!!.children.single()
        assertEquals("<a x=\"1\" />", withAttr.outerXml(), "只加属性不改 IsEmpty")
    }

    // ------------------------------------------------------------------
    // 5. 变量侧复核（本轮未发现缺陷，钉住与 C# 一致的读数）
    // ------------------------------------------------------------------

    /** 默认尺寸逐槽等于 `ConstantData.cs:171-260`；这是 VARSIZE 联动的起点。 */
    @Test
    fun `默认尺寸表与 ConstantData 一致`() {
        val s = VariableSizes()
        assertEquals(65, s.int1D.size)                       // ConstantData.cs:202 VariableIntArrayLength = new int[65]
        assertEquals(10000, s.int1D[3])                       // ConstantData.cs:216 FLAG
        assertEquals(1000, s.int1D[60])                       // ConstantData.cs:217 ITEMPRICE = MaxDataList[6]
        assertEquals(625, s.int1D[64])                        // ConstantData.cs:218 RANDDATA
        assertEquals(20000, s.str1D[1])                       // ConstantData.cs:223 STR = MaxDataList[10]
        assertEquals(100, s.str1D[0])                         // ConstantData.cs:219-222 其余 100
        assertEquals(1000, s.charaInt1D[3])                   // ConstantData.cs:244 TALENT
        assertEquals(1000, s.charaInt1D[9])                   // ConstantData.cs:245 CFLAG
        assertEquals(200, s.charaInt1D[10])                   // ConstantData.cs:246 JUEL
        assertEquals(200, s.charaInt1D[15])                   // ConstantData.cs:247 GOTJUEL
        assertEquals(1000, s.maxDataList[2])                  // ConstantData.cs:174 TALENT.CSV
        assertEquals(200, s.maxDataList[3])                   // ConstantData.cs:178 PALAM.CSV
        assertEquals(1, s.maxDataList[19])                    // ConstantData.cs:192 CDFLAG1.CSV
        assertEquals(1, s.maxDataList[20])                    // ConstantData.cs:193 CDFLAG2.CSV
    }

    /**
     * 存档位规则逐分支等于 `VariableToken.cs:88-154`：`__SAVE_EXTENDED__` 直通；
     * 全局 1 维整数 `低16位 < 60`、全局 1 维字符串 `低16位 < 1`；角色 1 维整数 `< 17`。
     */
    @Test
    fun `存档位规则与 VariableToken 位运算一致`() {
        fun saved(name: String, code: Int) = VariableCode.isSavedata(code, name)
        assertFalse(saved("STR", VariableCode.STR))          // 低16位=1 不满足 <1
        assertTrue(saved("SAVESTR", VariableCode.SAVESTR))   // 低16位=0
        assertTrue(saved("TSTR", VariableCode.TSTR))         // __SAVE_EXTENDED__
        assertTrue(saved("CDFLAG", VariableCode.CDFLAG))     // __SAVE_EXTENDED__
        assertTrue(saved("DAY", VariableCode.DAY))           // 655360 分支：DAY 低 16 位 = 0 < 60
        assertTrue(saved("GLOBAL", VariableCode.GLOBAL_VAR))  // 先命中 `Code == GLOBAL` 直通分支
        assertTrue(saved("STAIN", VariableCode.STAIN))       // 1703936 分支：角色 1 维整数，槽 14 < 17
        assertFalse(saved("RAND", VariableCode.RAND))        // __CALC__ ⇒ false
    }

    /**
     * 角色表列数逐项目等于 `CharacterData.cs:57-62`（`dataInteger[2]` / `dataString[4]` /
     * `dataIntegerArray[84]` / `dataStringArray[1]` / `dataIntegerArray2D[1]`），
     * 2 维列的块大小 = 两维乘积（`CharacterData.cs:73-75` 用高 32 位当第一维、
     * `VariableData.cs:117` 的 `array[i0, i1]` 是行主序）。
     */
    @Test
    fun `角色表列数与 CharacterData 数组形状一致`() {
        val s = VariableSizes()
        assertEquals(2, SystemLayout.charaInt0D.size)
        assertEquals(4, SystemLayout.charaStr0D.size)
        assertEquals(84, s.charaInt1D.size)
        assertEquals(1, s.charaStr1D.size)
        assertEquals(1, s.charaInt2D.size)

        val table = CharacterTable(s, VarConfig())
        assertEquals(2, table.int0.size)
        assertEquals(4, table.str0.size)
        assertEquals(84, table.int1.size)
        assertEquals(1, table.str1.size)
        assertEquals(1, table.int2.size)
        // 默认 CDFLAG = 4294967297 = (1 << 32) + 1 ⇒ 1 x 1（ConstantData.cs:254）
        assertEquals(1, table.int2[0].blockSize)

        val sized = VariableSizes()
        sized.charaInt2D[0] = intArrayOf(3, 5)
        assertEquals(15, CharacterTable(sized, VarConfig()).int2[0].blockSize)
    }

    private fun assertFalse(v: Boolean) = assertTrue(!v)
}
