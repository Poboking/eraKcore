package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.xml.XmlElement
import com.eratw.port.core.xml.XmlParser
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * ARCH1 P3b XML 双轨收口的回归面（2026-10-24）：MAP_FROMXML / DT_FROMXML / 存档 EM 装载的
 * 解析层从 fn 内私有 XNode + MiniXml 并入 core/xml/XmlParser（core 内唯一解析器），本文件钉住
 * 收口时**每处有意的行为口径**，分三组：
 *
 * 1. 等价面 —— 原双实现对同输入同读数，迁移后必须原样（基本导入 / 键值取法 / 实体解码）。
 * 2. 已知分歧点 —— 原双实现读数不同，迁移后统一到**官方口径**，每条带证据：
 *    - InnerXml 重序列化：.NET 的 InnerXml 是子节点经 writer 的重序列化（空元素 `<b />`、
 *      文本转义 `& < >`、属性引号归一双引号）—— core/xml/XmlNode.kt 的 dotnet 8.0.421 探针
 *      登记（XML_TOSTR 判据 pc-golden-root.txt:572 / :576 与其同一 writer 家族）；
 *    - 纯空白文本节点：`LoadXml` 默认 PreserveWhitespace=false 丢弃（XmlNode.kt 探针登记），
 *      原 MiniXml 按「源文原文切片」保留，是移植侧偏差，随迁移修正；
 *    - 文档级 DOCTYPE / 前导 BOM / 内容级处理指令：口径注记见各用例（两处为 TODO(verify) 残差）。
 * 3. 双模式差分 —— 收口后解析器只剩两档（XmlParser.parse 的 preserveWhitespace），
 *    钉「两档对非纯空白输入全同、只差纯空白文本节点」这条内部契约（MAP 走 false =
 *    LoadXml 口径，DT 装载走 true = DataTable.ReadXml 的 XmlReader 口径）。
 */
private class UnifyBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("UnifyBaseCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

private fun unifyCtx(): BuiltinEvalContext {
    val store = VariableStore()
    val base = UnifyBaseCtx()
    base.variables = store
    val host = object : BuiltinHost {}
    return BuiltinEvalContext(base, store, host)
}

private fun unInt(c: BuiltinEvalContext, name: String, vararg a: Value?): Long =
    (c.callFunction(name, a.toList()) as Value.IntV).v

private fun unStr(c: BuiltinEvalContext, name: String, vararg a: Value?): String =
    (c.callFunction(name, a.toList()) as Value.StrV).v

private fun unS(v: String): Value = Value.StrV(v)

private fun unI(v: Long): Value = Value.IntV(v)

class MapXmlUnifyTest {

    // ==================================================================
    // 等价面：迁移前后同读数（原双实现一致，回归钉）
    // ==================================================================

    @Test
    fun `MAP_FROMXML 基本导入与键值取法保持`() {
        val c = unifyCtx()
        unInt(c, "MAP_CREATE", unS("M"))
        assertEquals(1L, unInt(c, "MAP_FROMXML", unS("M"), unS("<map><p><k>K1</k><v>V1</v></p><p><k>K2</k><v>V2</v></p></map>")))
        assertEquals("V1", unStr(c, "MAP_GET", unS("M"), unS("K1")))
        assertEquals("V2", unStr(c, "MAP_GET", unS("M"), unS("K2")))
        assertEquals(2L, unInt(c, "MAP_SIZE", unS("M")))
    }

    @Test
    fun `MAP_FROMXML 非 map 根返回 1 且不写入`() {
        val c = unifyCtx()
        unInt(c, "MAP_CREATE", unS("M"))
        // SelectNodes("/map/p") 对非 map 根给 0 项（FunctionMethodCreator.cs:3059）⇒ 循环空转、返回 1
        assertEquals(1L, unInt(c, "MAP_FROMXML", unS("M"), unS("<notmap><p><k>x</k><v>1</v></p></notmap>")))
        assertEquals(0L, unInt(c, "MAP_SIZE", unS("M")))
    }

    @Test
    fun `MAP_FROMXML k v 不各恰一个时跳过该项`() {
        val c = unifyCtx()
        unInt(c, "MAP_CREATE", unS("M"))
        assertEquals(
            1L,
            unInt(c, "MAP_FROMXML", unS("M"), unS("<map><p><k>a</k><k>a2</k><v>1</v></p><p><k>b</k><v>2</v></p></map>")),
        )
        assertEquals("2", unStr(c, "MAP_GET", unS("M"), unS("b")))
        assertEquals(1L, unInt(c, "MAP_SIZE", unS("M")))
    }

    @Test
    fun `MAP_FROMXML 键取解码文本 五个预定义实体与数字引用`() {
        val c = unifyCtx()
        unInt(c, "MAP_CREATE", unS("M"))
        assertEquals(1L, unInt(c, "MAP_FROMXML", unS("M"), unS("<map><p><k>a&amp;b</k><v>1</v></p></map>")))
        assertEquals("1", unStr(c, "MAP_GET", unS("M"), unS("a&b")))
        assertEquals(
            1L,
            unInt(c, "MAP_FROMXML", unS("M"), unS("<map><p><k>&lt;x&gt;&quot;&apos;&#65;&#x42;</k><v>2</v></p></map>")),
        )
        assertEquals("2", unStr(c, "MAP_GET", unS("M"), unS("<x>\"'AB")))
    }

    // ==================================================================
    // 已知分歧点：迁移后统一到官方口径（每条带证据）
    // ==================================================================

    @Test
    fun `MAP_FROMXML 值取重序列化 InnerXml 大于号转义`() {
        val c = unifyCtx()
        unInt(c, "MAP_CREATE", unS("M"))
        // .NET InnerXml 经 writer 重序列化：文本里的 '>' 无条件转义成 &gt;（core/xml/XmlNode.kt
        // 的 appendEscapedText 口径，XML_TOSTR 判据 pc-golden-root.txt:572/:576 同 writer 家族）。
        // 原 MiniXml 是源文原文切片，给 "a>b" —— 移植侧偏差，随收口修正。
        assertEquals(1L, unInt(c, "MAP_FROMXML", unS("M"), unS("<map><p><k>k</k><v>a>b</v></p></map>")))
        assertEquals("a&gt;b", unStr(c, "MAP_GET", unS("M"), unS("k")))
    }

    @Test
    fun `MAP_FROMXML 值取重序列化 InnerXml 属性引号归一`() {
        val c = unifyCtx()
        unInt(c, "MAP_CREATE", unS("M"))
        // .NET 属性一律双引号（基准 572 行：输入 id='1' 输出 id="1"，XmlNode.kt 登记）
        assertEquals(1L, unInt(c, "MAP_FROMXML", unS("M"), unS("<map><p><k>k</k><v><b x='1'/></v></p></map>")))
        assertEquals("<b x=\"1\" />", unStr(c, "MAP_GET", unS("M"), unS("k")))
    }

    @Test
    fun `MAP_FROMXML 纯空白键按 PreserveWhitespace=false 丢弃 非纯空白保留`() {
        val c = unifyCtx()
        unInt(c, "MAP_CREATE", unS("M"))
        // LoadXml 默认 PreserveWhitespace=false：`<k>  </k>` 的纯空白文本节点不进 DOM ⇒
        // InnerText ""（dotnet 探针口径，XmlNode.kt 登记）。原 MiniXml 给 " "，随收口修正。
        // 官方对空键**照样插入不跳过**：FunctionMethodCreator.cs:3070 的守卫只有「恰一 k
        // 恰一 v」的元素数、:3072 无条件 dictionary[InnerText]=InnerXml ⇒ 空白键变体经字典
        // last-wins 并成 "" 一键（真语料命中：tw_csv_chs 4372→4371、tw_taste_chs 2255→2254，
        // 各 1 个纯空白 k，见 XmlBomLoadTest 的闸红裁决）。
        assertEquals(1L, unInt(c, "MAP_FROMXML", unS("M"), unS("<map><p><k>  </k><v>1</v></p></map>")))
        // MAP_GET "" 取得到值 = 该条目存在（官方插入语义）
        assertEquals("1", unStr(c, "MAP_GET", unS("M"), unS("")))
        // "a  " 不是纯空白 ⇒ 正常文本节点，两侧（.NET 与本实现）都保留
        assertEquals(1L, unInt(c, "MAP_FROMXML", unS("M"), unS("<map><p><k>a  </k><v>2</v></p></map>")))
        assertEquals("2", unStr(c, "MAP_GET", unS("M"), unS("a  ")))
    }

    @Test
    fun `MAP_FROMXML 文档级 DOCTYPE 抛错`() {
        val c = unifyCtx()
        unInt(c, "MAP_CREATE", unS("M"))
        // .NET Core（reference/emuera-cs/Emuera.csproj:7 的 netcoreapp8.0）XmlDocument.LoadXml
        // 默认禁 DTD（XmlTextReader 的 DtdProcessing 缺省 Prohibit）⇒ XmlException ⇒ :3061-3064
        // 转 CodeEE。原 MiniXml 把文档级 <!...> 跳到首个 '>' 吞掉，随收口改为与 XmlMethods 的
        // XML_LOAD 同一口径（「不支持的文档级标记」）。TODO(verify)：.NET Core 侧禁 DTD 的
        // 缺省行为无本地探针读数，凭框架缺省登记；语料与 golden 均不喂 DOCTYPE（2026-10-24 扫描
        // plugins 4 个 xml 零命中）。
        assertFailsWith<CodeEE> {
            unInt(c, "MAP_FROMXML", unS("M"), unS("<!DOCTYPE x><map><p><k>k</k><v>1</v></p></map>"))
        }
    }

    @Test
    fun `MAP_FROMXML 前导 BOM 解析层剥一个`() {
        val c = unifyCtx()
        unInt(c, "MAP_CREATE", unS("M"))
        // XmlParser.parse 跳过一个前导 U+FEFF（原 MiniXml 报错）。TODO(verify)：原版
        // LoadXml(string) 对字面 BOM 无探针读数；正式路径 BOM 由 LOADTEXT 层剥
        // （XmlBomLoadTest 的判层口径），此处登记解析层的宽容度。
        val bom = 0xFEFF.toChar()
        assertEquals(1L, unInt(c, "MAP_FROMXML", unS("M"), unS("${bom}<map><p><k>k</k><v>1</v></p></map>")))
        assertEquals("1", unStr(c, "MAP_GET", unS("M"), unS("k")))
    }

    @Test
    fun `MAP_FROMXML 孤立代理与低位控制符字符引用抛错`() {
        val c = unifyCtx()
        unInt(c, "MAP_CREATE", unS("M"))
        // 代理区 0xD800..0xDFFF：引用必须指向标量值（XmlParser 既有 dotnet 实测闸；
        // 原 MiniXml 放行孤立代理，随收口修正）。低位控制符 #x1：XML 1.0 Char 集之外
        // （MiniXml 的 M4 实测结论迁入 XmlParser.appendCharacterRef，MapDataTableMethodsTest
        // 的 #x0 用例同口径）。
        assertFailsWith<CodeEE> {
            unInt(c, "MAP_FROMXML", unS("M"), unS("<map><p><k>&#xD800;</k><v>1</v></p></map>"))
        }
        assertFailsWith<CodeEE> {
            unInt(c, "MAP_FROMXML", unS("M"), unS("<map><p><k>&#x1;</k><v>1</v></p></map>"))
        }
    }

    @Test
    fun `MAP_FROMXML 内容级处理指令不进 InnerText 也不成伪元素`() {
        val c = unifyCtx()
        unInt(c, "MAP_CREATE", unS("M"))
        // .NET 建 PI 节点：InnerText 拼接跳过 PI，PI 也不是元素（不占 /map/p 的子位）。
        // 原 MiniXml 把内容级 `<?...>` 当元素（readName 吃 '?'），键恰好多出 PI 文本 —— 随收口修正。
        assertEquals(1L, unInt(c, "MAP_FROMXML", unS("M"), unS("<map><?pi x?><p><k>a<?pi y?>b</k><v>1</v></p></map>")))
        assertEquals("1", unStr(c, "MAP_GET", unS("M"), unS("ab")))
        assertEquals(1L, unInt(c, "MAP_SIZE", unS("M")))
    }

    // ==================================================================
    // DT 装载：preserveWhitespace=true 档（DataTable.ReadXml 的 XmlReader 口径）
    // ==================================================================

    @Test
    fun `DT_FROMXML 字符串单元格纯空白与 CDATA 保真`() {
        val c = unifyCtx()
        // .NET ReadXml 走 XmlReader（不经 XmlDocument）：元素内容由 ReadString 拼回，
        // 纯空白单元格（PC 真档 WriteXml 把 " " 写成 `<NAME> </NAME>`）必须回读 " "。
        // 原 MiniXml 同样保留；收口后由 XmlParser 的 preserveWhitespace=true 档承接。
        assertEquals(1L, unInt(c, "DT_FROMXML", unS("WS"), unS(wsSchema("WS")), unS(wsData("WS"))))
        assertEquals(2L, unInt(c, "DT_ROW_LENGTH", unS("WS")))
        assertEquals("  ", unStr(c, "DT_CELL_GETS", unS("WS"), unI(0), unS("NAME")))
        assertEquals("x", unStr(c, "DT_CELL_GETS", unS("WS"), unI(1), unS("NAME")))
        assertEquals(1L, unInt(c, "DT_CELL_GET", unS("WS"), unI(0), unS("id")))
    }

    @Test
    fun `DT_FROMXML 畸形输入仍静默落零`() {
        val c = unifyCtx()
        // 结构错（缺根）与实体错都从 XmlParser 抛 XmlParseException ⇒ :2749-2752 裸 catch ⇒ 0
        assertEquals(0L, unInt(c, "DT_FROMXML", unS("T"), unS("<schema><unclosed>"), unS("<data>")))
        assertEquals(0L, unInt(c, "DT_FROMXML", unS("T"), unS(wsSchema("T")), unS("<DocumentElement><T>&bad;</T></DocumentElement>")))
    }

    // ==================================================================
    // 双模式差分：XmlParser 的 preserveWhitespace 两档内部契约
    // ==================================================================

    @Test
    fun `XmlParser 两档对非纯空白输入全同 只差纯空白文本节点`() {
        // 同一文档两档解析：非空白文本、结构、属性完全一致
        val doc = "<r a='1'><b>x</b>c<d/></r>"
        val strict = XmlParser.parse(doc)
        val keep = XmlParser.parse(doc, preserveWhitespace = true)
        val strictRoot = strict.root!!
        val keepRoot = keep.root!!
        assertEquals(strictRoot.name, keepRoot.name)
        assertEquals(strictRoot.attribute("a")?.attrValue, keepRoot.attribute("a")?.attrValue)
        assertEquals(strictRoot.innerText, keepRoot.innerText)
        assertEquals("xc", strictRoot.innerText)
        // 只差纯空白文本节点：LoadXml 口径（false）丢弃，ReadXml 口径（true）保留
        val wsDoc = "<r><a>  </a><b>x</b></r>"
        assertEquals("", XmlParser.parse(wsDoc).root!!.childEl("a").innerText)
        assertEquals("  ", XmlParser.parse(wsDoc, true).root!!.childEl("a").innerText)
    }

    // ==================================================================
    // 夹具
    // ==================================================================

    /** 与 [MapDataTableMethodsTest] 的 DT_FROMXML 夹具同形态：id 主键 + NAME 字符串列。 */
    private fun wsSchema(table: String): String =
        "<xs:schema id=\"NewDataSet\" xmlns=\"\" xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"" +
            " xmlns:msdata=\"urn:schemas-microsoft-com:xml-msdata\">\r\n" +
            "  <xs:element name=\"NewDataSet\" msdata:IsDataSet=\"true\" msdata:MainDataTable=\"$table\"" +
            " msdata:UseCurrentLocale=\"true\">\r\n" +
            "    <xs:complexType>\r\n" +
            "      <xs:choice minOccurs=\"0\" maxOccurs=\"unbounded\">\r\n" +
            "        <xs:element name=\"$table\">\r\n" +
            "          <xs:complexType>\r\n" +
            "            <xs:sequence>\r\n" +
            "              <xs:element name=\"id\" type=\"xs:long\" />\r\n" +
            "              <xs:element name=\"NAME\" type=\"xs:string\" minOccurs=\"0\" />\r\n" +
            "            </xs:sequence>\r\n" +
            "          </xs:complexType>\r\n" +
            "        </xs:element>\r\n" +
            "      </xs:choice>\r\n" +
            "    </xs:complexType>\r\n" +
            "  </xs:element>\r\n" +
            "</xs:schema>"

    /** 行 1 的 NAME 是纯空白；行 2 的 NAME 是 CDATA。 */
    private fun wsData(table: String): String =
        "<DocumentElement><$table><id>1</id><NAME>  </NAME></$table>" +
            "<$table><id>2</id><NAME><![CDATA[x]]></NAME></$table></DocumentElement>"
}

/** 测试侧的子元素查找（生产侧同名助手是 MapDataTableMethods.kt 的文件私有扩展）。 */
private fun XmlElement.childEl(n: String): XmlElement =
    children.filterIsInstance<XmlElement>().first { it.name == n }
