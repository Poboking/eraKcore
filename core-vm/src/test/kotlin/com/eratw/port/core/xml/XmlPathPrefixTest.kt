package com.eratw.port.core.xml

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * FN-10 判据：XPath 命名空间前缀口径 + `[text()]` 谓词的逐文本段口径。
 *
 * 每一条期望值都取自 dotnet 8.0.31 对 `new XmlDocument().LoadXml(xml).SelectNodes(path)`
 * 的实测（与 Emuera 一致：Emuera 全程用 `SelectNodes(xpath)` 单参重载、从不传
 * `XmlNamespaceManager`，见 FunctionMethodCreator.cs:189/1253/1530/1712/1864）。
 * 纯 JVM：直接喂 [XmlParser] + [XmlPath]，不吃语料环境变量。
 *
 * dotnet 抛的 XPathException 文案（合法限定名恒这一条，与是否声明过前缀无关）：
 * `Namespace Manager or XsltContext needed. This query has a prefix, variable, or user-defined function.`
 * [XmlPath] 把它原样放进 [XmlPathException.message]，调用方 XmlMethods.xmlSelect 再包成 CodeEE。
 */
private const val NS_MANAGER_MSG =
    "Namespace Manager or XsltContext needed. This query has a prefix, variable, or user-defined function."

private fun sel(xml: String, path: String): List<XmlNode> = XmlPath.select(XmlParser.parse(xml), path)

/** 带前缀的名字测试 / 谓词名一律报错（不静默 0 命中），文案取 dotnet 缺 namespace manager 那条。 */
private fun assertPrefixedThrows(xml: String, path: String) {
    val ex = assertFailsWith<XmlPathException>("带前缀的 $path 应报错而不是 0 命中") { sel(xml, path) }
    assertContains(ex.message ?: "", NS_MANAGER_MSG, message = "报错文案应与 dotnet 一致：$path")
}

class XmlPathPrefixTest {

    // ---------------- 命名空间前缀 ----------------

    /** 无前缀路径的命中/不命中（对照组）：语料里 ERB 宏展开后的名字都走这条，无命名空间。 */
    @Test
    fun `无前缀路径照常命中与不命中`() {
        assertEquals(1, sel("<r><c/></r>", "/r/c").size)
        assertEquals(0, sel("<r><c/></r>", "/r/missing").size)
    }

    /** 声明过前缀也不解析：`xmlns:p` 只是给查询用的、查询里没有 namespace manager ⇒ 报错。 */
    @Test
    fun `声明过的前缀仍报错`() {
        assertPrefixedThrows("<r xmlns:p=\"urn:x\"><p:c/></r>", "/r/p:c")
    }

    /** 未声明前缀同样报错（FN-10 主形态：`/a:b`、`//p:q[@r]` 这类）。 */
    @Test
    fun `未声明前缀报错`() {
        assertPrefixedThrows("<r><c/></r>", "/a:c")
        assertPrefixedThrows("<r><c/></r>", "//p:q[@r]")
    }

    /** 前缀 + 通配本地名、以及保留前缀 xml / xmlns，dotnet 都报缺 namespace manager。 */
    @Test
    fun `通配与保留前缀也报错`() {
        assertPrefixedThrows("<r><c/></r>", "/r/p:*")
        assertPrefixedThrows("<r><c/></r>", "/xml:c")
        assertPrefixedThrows("<r xmlns:p=\"urn:x\"><c/></r>", "/xmlns:c")
    }

    /** 谓词里的前缀名（属性谓词、子元素存在谓词、子元素相等谓词）同样报错，不静默 0 命中。 */
    @Test
    fun `谓词内的前缀报错`() {
        assertPrefixedThrows("<r xmlns:p=\"urn:x\"><c p:k=\"v\"/></r>", "/r/c[@p:k='v']")
        assertPrefixedThrows("<r><c/></r>", "/r[p:q]")
        assertPrefixedThrows("<r><c/></r>", "/r[p:q='1']")
        assertPrefixedThrows("<r><c k=\"v\"/></r>", "/r/c[@p:k='v']")
    }

    /** 属性步带前缀（`@a:b`）报错。 */
    @Test
    fun `属性步前缀报错`() {
        assertPrefixedThrows("<r><c/></r>", "/r/@a:b")
    }

    /** 声明过的前缀查询报错；谓词里的冒号若是引号字面量则不是前缀（`[b='p:q']` 按值比较）。 */
    @Test
    fun `引号里的冒号不是前缀`() {
        // dotnet：/a[b='x::y'] 里 :: 是字面量，命中 1；这里同理不该报错。
        assertEquals(1, sel("<a><b>p:q</b></a>", "/a[b='p:q']").size)
    }

    // ---------------- 默认命名空间 ----------------

    /** 默认 `xmlns` 会施加到未加前缀的元素 ⇒ 无前缀名字测试全 0 命中（dotnet 实测）。 */
    @Test
    fun `默认命名空间下无前缀测试不命中`() {
        val dd = "<r xmlns=\"urn:d\"><c/></r>"
        assertEquals(0, sel(dd, "/r").size)
        assertEquals(0, sel(dd, "/r/c").size)
        assertEquals(0, sel(dd, "//c").size)
    }

    /** 通配不受命名空间约束：`*` 仍命中带默认命名空间的元素（dotnet 实测：根级通配命中 1、后代通配命中 2）。 */
    @Test
    fun `通配命中带默认命名空间的元素`() {
        val dd = "<r xmlns=\"urn:d\"><c/></r>"
        assertEquals(1, sel(dd, "/*").size)
        assertEquals(2, sel(dd, "//*").size)
    }

    /** 只声明前缀（`xmlns:p`）不产生默认命名空间 ⇒ 未加前缀的 r/c 仍可命中。 */
    @Test
    fun `仅前缀声明不阻断无前缀命中`() {
        assertEquals(1, sel("<r xmlns:p=\"urn:x\"><c/></r>", "/r/c").size)
    }

    /** `xmlns=""` 复位：经通配步抵达时，复位后的子元素可命中、未复位的不可命中（dotnet 实测 1 / 0）。 */
    @Test
    fun `默认命名空间复位`() {
        assertEquals(1, sel("<root xmlns=\"urn:d\"><m xmlns=\"\"><g/></m></root>", "/*/m/g").size)
        assertEquals(0, sel("<root xmlns=\"urn:d\"><m><g/></m></root>", "/*/m/g").size)
    }

    // ---------------- [text()] 五种形态 ----------------

    /** 单段：元素只有一个文本子节点。 */
    @Test
    fun `text 谓词单段`() {
        assertEquals(1, sel("<a>hello</a>", "//a[text()='hello']").size)
        assertEquals(0, sel("<a>hello</a>", "//a[text()='hell']").size)
        assertEquals(1, sel("<a>hello</a>", "//a[text()]").size)
    }

    /** 多段：文本被中间元素切成多段，逐段比较 ⇒ 单段各命中 1、拼接串命中 0（FN-10 第二条）。 */
    @Test
    fun `text 谓词多段逐段比较`() {
        val a = "<a>p<b/>q<c/>r</a>"
        assertEquals(1, sel(a, "//a[text()='p']").size)
        assertEquals(1, sel(a, "//a[text()='q']").size)
        assertEquals(1, sel(a, "//a[text()='r']").size)
        assertEquals(0, sel(a, "//a[text()='pqr']").size)
        // 节点测试口径：/a/text() 得 3 个段首节点（dotnet 实测）
        assertEquals(3, sel(a, "/a/text()").size)
    }

    /** 空元素 / 只有元素子 / 自闭合：无文本子节点 ⇒ `[text()]` 假、`[text()='']` 也 0（旧 InnerText 口径会命中）。 */
    @Test
    fun `text 谓词空元素`() {
        assertEquals(0, sel("<a></a>", "//a[text()]").size)
        assertEquals(0, sel("<a></a>", "//a[text()='']").size)
        assertEquals(0, sel("<a><b/></a>", "//a[text()='']").size)
        assertEquals(0, sel("<a/>", "//a[text()='']").size)
    }

    /** CDATA 段：CDATA 也算文本段；CDATA 与相邻文本合并成一段（dotnet 实测 `[text()='pq']`=1、单段 0）。 */
    @Test
    fun `text 谓词 CDATA 段`() {
        assertEquals(1, sel("<a><![CDATA[x]]></a>", "//a[text()='x']").size)
        assertEquals(1, sel("<a><![CDATA[x]]></a>", "//a[text()]").size)
        val adj = "<a><![CDATA[p]]>q</a>"
        assertEquals(1, sel(adj, "/a/text()").size)
        assertEquals(1, sel(adj, "//a[text()='pq']").size)
        assertEquals(0, sel(adj, "//a[text()='p']").size)
        assertEquals(0, sel(adj, "//a[text()='q']").size)
    }

    /** `[text()='']`：对没有任何文本子节点的元素恒 0（dotnet 实测；这是被改钉的那条口径的来源）。 */
    @Test
    fun `text 谓词空串`() {
        assertEquals(0, sel("<a></a>", "//a[text()='']").size)
        // 有非空文本段时，'' 也不命中
        assertEquals(0, sel("<a>x</a>", "//a[text()='']").size)
    }

    /** 数值右值 `[text()=5]` 同样逐段（语料 `宴会_SUB.ERB:201` 的 `[text()={ARGCHR}]` 走这条）。 */
    @Test
    fun `text 谓词数值逐段`() {
        assertEquals(1, sel("<a>5<b/>x</a>", "//a[text()=5]").size)
        // 拼接成 11 是旧 InnerText 口径才会有的错值，dotnet 逐段是 0
        assertEquals(0, sel("<a>5<b/>6</a>", "//a[text()=11]").size)
    }
}
