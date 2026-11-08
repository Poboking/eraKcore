package com.eratw.port.core.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * XML 已知分歧登记（T8 · v4.981 审查修复波 · 契约 `C-XML-DIVERGENCE@2` 方向乙）。
 *
 * 每一条断言都附带 **dotnet 8.0.425 实测读数**（探针走 Emuera 同 API：
 * `new XmlDocument().LoadXml(string)` + `SelectNodes(xpath)` 单参重载，见
 * FunctionMethodCreator.cs:179 / :189）。口径 = 方向乙「spec 化登记为有意分歧、不改 main、
 * 不翻转既有绿测试」（OD-1 2026-11-02）：本文件钉的是**当前移植行为**与 .NET 读数之间的差额，
 * 作回归哨兵；裁定文本见交付报告（供组长并入 `specs/verify-notes.md`）。
 *
 * 判定「不可达」的依据（R8.3 / ADR-005）：4.981 语料 `XML_GET*` 实到路径形态只有
 * 元素名 / `*` 步 / `/text()` / `[n]` / `[@a='v']`（`グラフィック特殊効果.ERB:94-113`、
 * `宴会.ERB:64-70`、`TEMP_CHARA_LIST_BY_DT.ERB:194-230`），未出现下列任何形态；
 * `plugins` 下 4 个 XML 也无字符引用 ⇒ 下列分歧均不可观测。
 *
 * 纯 JVM：直接喂 [XmlParser] + [XmlPath]，不吃语料环境变量。
 */
class XmlDivergenceRegistryTest {

    /**
     * BOM（`KNOWN_DIVERGENCE`，OD-1 方向乙）：dotnet 8.0.425 实测
     * `LoadXml("\uFEFF<root/>")` 抛 XmlException「Data at the root level is invalid.
     * Line 1, position 1.」；移植 `XmlParser.parseDocument` 剥掉前导 U+FEFF 继续解析（得根 root）。
     * 真实链路 LOADTEXT 走 `File.ReadAllText`（FunctionMethodCreator.cs:8082）已消费 BOM ⇒ 不可达。
     */
    @Test
    fun `串首 BOM 移植剥除而 dotnet LoadXml 抛 XmlException`() {
        assertEquals("root", XmlParser.parse("\uFEFF<root/>").root?.name)
    }

    /**
     * `@*` 属性通配与 xmlns（XML-FIVE X5 翻案复刻，2026-11-06）：dotnet 8.0.425 实测
     * `<r xmlns:p="urn:x" a="1">` 的 `/r/@*` = 1（只 `a`；xmlns/xmlns:p 在 XPath 数据模型是
     * namespace 节点、不属属性步），移植现已同口径过滤（解析器仍把声明存成普通属性供
     * OuterXml 序列化，只是属性轴/属性谓词不再把它们当属性）。默认 `xmlns` 情形经根通配步
     * （斜杠-星步）抵达后属性通配 = 1。复探补读：显式 `/r/@xmlns` = 0、谓词 `/r[@xmlns]` = 0、
     * `/r[@xmlns='urn:x']` = 0（带前缀的 `@xmlns:p` 走「缺 namespace manager」报错，
     * 见 XmlPathPrefixTest）。4.981 语料 ERB 里的 XML_GET 路径无 xmlns 形态 ⇒ 主线不可观测；
     * schema.xml（资源侧）根上确实带 xmlns 声明，RealCorpusXmlTest 已补根级锚点。
     */
    @Test
    fun `属性通配与属性谓词过滤 xmlns 声明（dotnet 口径）`() {
        assertEquals(1, XmlPath.select(XmlParser.parse("<r xmlns:p='urn:x' a='1'><c/></r>"), "/r/@*").size)
        assertEquals(1, XmlPath.select(XmlParser.parse("<r xmlns='urn:d' a='1'><c/></r>"), "/*/@*").size)
        // 对照组：无 xmlns 时通配照常命中（dotnet 2 / 移植 2）
        assertEquals(2, XmlPath.select(XmlParser.parse("<r a='1' b='2'><c/></r>"), "/*/@*").size)
        // 显式 @xmlns / 属性谓词同口径：namespace 节点不属属性步（2026-11-06 探针实测）
        assertEquals(0, XmlPath.select(XmlParser.parse("<r xmlns='urn:x' a='1'/>"), "/r/@xmlns").size)
        assertEquals(0, XmlPath.select(XmlParser.parse("<r xmlns='urn:x' a='1'/>"), "/r[@xmlns]").size)
        assertEquals(0, XmlPath.select(XmlParser.parse("<r xmlns='urn:x' a='1'/>"), "/r[@xmlns='urn:x']").size)
        // 通配后的属性名仍按文档序只含真属性（dotnet 实测 names = a,b）
        assertEquals(
            listOf("a", "b"),
            XmlPath.select(XmlParser.parse("<r xmlns:p='urn:x' a='1' b='2'/>"), "/r/@*").map { it.name },
        )
    }

    /**
     * 未实现轴 / 节点测试 / 谓词函数（`KNOWN_DIVERGENCE`，语料不可达）：dotnet 8.0.425
     * `SelectNodes` 实测支持 `following::`、`preceding::`、`namespace::*`、
     * `processing-instruction()`、`[position()=n]`、`[contains(..)]`（均返回节点或合法求值）；
     * 移植遇即抛 [XmlPathException]（调用方 `XmlMethods.xmlSelect` 再包 CodeEE）。
     */
    @Test
    fun `dotnet 支持而移植未实现的轴与节点测试抛 XmlPathException`() {
        val doc = XmlParser.parse("<root><g><a>1</a><a>2</a></g></root>")
        assertFailsWith<XmlPathException> { XmlPath.select(doc, "/root/g/a/following::a") }
        assertFailsWith<XmlPathException> { XmlPath.select(doc, "/root/g/a/preceding::a") }
        assertFailsWith<XmlPathException> { XmlPath.select(doc, "/root/namespace::*") }
        assertFailsWith<XmlPathException> {
            XmlPath.select(XmlParser.parse("<r><?pi x?><a/></r>"), "/r/processing-instruction()")
        }
        assertFailsWith<XmlPathException> { XmlPath.select(doc, "/root/g/a[position()=1]") }
        assertFailsWith<XmlPathException> { XmlPath.select(doc, "/root/g/a[contains(.,'1')]") }
    }

    /**
     * 谓词通配 `[*]` / `[@*]`（XML-FIVE X2 翻案复刻，2026-11-06）：dotnet 8.0.425 实测
     * `<root><a x='1'><b/></a><a/></root>` 的 `/root/a[*]` 与 `/root/a[@*]` 各命中 1
     * （语义分别是「有子元素」「有属性」）。移植旧实现按「名为 `*` 的子元素/属性」名字
     * 测试 ⇒ 恒 0，方向错误，已按 dotnet 语义实装。补读：注释/PI/纯文本子的元素对 `[*]`
     * 命中 0；只有 xmlns 声明的元素对 `[@*]` 命中 0（与 X5 同口径）。
     */
    @Test
    fun `节点通配与属性通配谓词按 dotnet 有子元素与有属性语义`() {
        val doc = XmlParser.parse("<root><a x='1'><b/></a><a/></root>")
        assertEquals(1, XmlPath.select(doc, "/root/a[*]").size)
        assertEquals(1, XmlPath.select(doc, "/root/a[@*]").size)
        // 「有子元素」只数元素：注释 / PI / 纯文本 / 空元素都 0（dotnet 8.0.425 实测）
        assertEquals(0, XmlPath.select(XmlParser.parse("<root><a><!--c--></a></root>"), "/root/a[*]").size)
        assertEquals(0, XmlPath.select(XmlParser.parse("<root><a><?pi x?></a></root>"), "/root/a[*]").size)
        assertEquals(0, XmlPath.select(XmlParser.parse("<root><a>x</a></root>"), "/root/a[*]").size)
        assertEquals(0, XmlPath.select(XmlParser.parse("<root><a/></root>"), "/root/a[*]").size)
        assertEquals(1, XmlPath.select(XmlParser.parse("<root><a><b/><c/></a></root>"), "/root/a[*]").size)
        // 「有属性」不计 xmlns 声明（dotnet 8.0.425 实测 `<a xmlns='urn:d'/>` = 0）
        assertEquals(0, XmlPath.select(XmlParser.parse("<root><a xmlns='urn:d'/></root>"), "/root/a[@*]").size)
        assertEquals(1, XmlPath.select(XmlParser.parse("<root><a b='1'>c</a></root>"), "/root/a[@*]").size)
    }

    /**
     * 非法 XPath：dotnet 8.0.425 实测全部抛 XPathException（文案各异，如
     * 「Expression must evaluate to a node-set.」/「... has an invalid token.」）；
     * 移植抛 [XmlPathException]（原版 `XmlGetMethod` 再包 `Lang.Error.XmlXPathParseError`，
     * FunctionMethodCreator.cs:191-193）。行为一致（都抛），文案不逐字钉。
     */
    @Test
    fun `非法 XPath 两侧都抛而不逐字钉文案`() {
        val doc = XmlParser.parse("<root><a/></root>")
        assertFailsWith<XmlPathException> { XmlPath.select(doc, "") }
        assertFailsWith<XmlPathException> { XmlPath.select(doc, "/root/") }
        assertFailsWith<XmlPathException> { XmlPath.select(doc, "/root/a[1") }
        // 语料错拼轴名（宴会_SUB.ERB:201 的 preceeding-sibling，少一个 e）：原版抛 ⇒ 移植同抛
        assertFailsWith<XmlPathException> { XmlPath.select(doc, "/root/preceeding-sibling::a") }
    }
}