package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.xml.XmlParser
import com.eratw.port.core.xml.XmlPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * XML 文档池家族测试。判据 = `fixtures/golden/baseline/pc-golden-root.txt:563-590`
 * （SECTION:XML / XML-SRC / XML-BYNAME），调用序列 = `fixtures/golden/GOLDEN_10_XMLMAP.ERB`。
 *
 * 分两层：
 * 1. DOM 层直接断言序列化字节（属性引号/顺序、空元素、转义、空白丢弃）；
 * 2. 内建层用最小 [BuiltinEvalContext] 跑完整调用序列，断言基准里的**字面值**。
 */

private class XmlBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("XmlBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun xmlEnv(): Pair<BuiltinEvalContext, VariableStore> {
    val store = VariableStore()
    val base = XmlBaseCtx()
    base.variables = store
    // #DIMS XBUF, 20 —— GOLDEN_10_XMLMAP.ERB 的 XML 分节用它接 XML_GET 结果
    store.declareUserVar(DimSpec("XBUF", true, intArrayOf(20)))
    return BuiltinEvalContext(base, store) to store
}

private fun xcall(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
    ctx.callFunctionRef(
        name,
        args.map {
            when (it) {
                is VarRef -> it
                is Long -> IntLiteral(it)
                is Int -> IntLiteral(it.toLong())
                is String -> StrLiteral(it)
                else -> throw IllegalArgumentException("不支持的测试参数：$it")
            }
        },
    )

private fun xiv(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Long =
    (xcall(ctx, name, *args) as Value.IntV).v

private fun xsv(ctx: BuiltinEvalContext, name: String, vararg args: Any?): String =
    (xcall(ctx, name, *args) as Value.StrV).v

private fun refS(name: String) = VarRef(name, emptyList(), operandType = OperandType.STR)

private fun strAt(store: VariableStore, name: String, index: Int): String =
    store.readStr(name, longArrayOf(index.toLong()))

class XmlDomTest {

    @Test
    fun `解析后序列化属性统一双引号且保序`() {
        // 基准 563 行输入用单引号，575 行输出是双引号
        val doc = XmlParser.parse("<root><a id='1'>A1</a><a id='2'>A2</a></root>")
        assertEquals(
            "<root><a id=\"1\">A1</a><a id=\"2\">A2</a></root>",
            doc.outerXml(),
        )
        val order = XmlParser.parse("<r b=\"2\" a=\"1\" c=\"3\" />")
        assertEquals("<r b=\"2\" a=\"1\" c=\"3\" />", order.outerXml())
    }

    @Test
    fun `空元素写成斜杠前带空格`() {
        assertEquals("<r><a /></r>", XmlParser.parse("<r><a/></r>").outerXml())
        // .NET 只在自闭合形态上置 IsEmpty：实测 LoadXml("<r></r>") ⇒ IsEmpty=False 且 OuterXml 原样
        // 回写 <r></r>；LoadXml("<r/>") ⇒ IsEmpty=True 回写 <r />。有结束标签的空元素不降级成自闭合。
        assertEquals("<r></r>", XmlParser.parse("<r></r>").outerXml())
        assertEquals("<r />", XmlParser.parse("<r/>").outerXml())
    }

    @Test
    fun `文本与属性转义口径`() {
        val doc = XmlParser.parse("<r a=\"x&amp;y\">1 &lt; 2 &amp;&amp; 3 &gt; 2</r>")
        assertEquals("<r a=\"x&amp;y\">1 &lt; 2 &amp;&amp; 3 &gt; 2</r>", doc.outerXml())
        // 属性值里的双引号必须转义（否则不是合法 XML）
        val q = XmlParser.parse("<r a='say \"hi\"' />")
        assertEquals("<r a=\"say &quot;hi&quot;\" />", q.outerXml())
    }

    @Test
    fun `CDATA 与注释原样保留`() {
        val doc = XmlParser.parse("<r><!--c--><![CDATA[<raw>&amp;]]>t</r>")
        assertEquals("<r><!--c--><![CDATA[<raw>&amp;]]>t</r>", doc.outerXml())
        val root = assertNotNull(doc.root)
        // InnerText 计入 CDATA，不计注释（.NET XmlNode.InnerText 口径）
        assertEquals("<raw>&amp;t", root.innerText)
    }

    @Test
    fun `PreserveWhitespace 为 false 时丢弃元素间纯空白`() {
        val doc = XmlParser.parse("<r>\n  <a>1</a>\n  <b> 2 </b>\n</r>")
        assertEquals("<r><a>1</a><b> 2 </b></r>", doc.outerXml())
    }

    @Test
    fun `字符引用解码与再转义`() {
        val doc = XmlParser.parse("<r>&#65;&#x42;</r>")
        assertEquals("AB", doc.root?.innerText)
        assertEquals("<r>AB</r>", doc.outerXml())
    }

    @Test
    fun `非法 XML 抛解析异常`() {
        assertFailsWith<com.eratw.port.core.xml.XmlParseException> { XmlParser.parse("") }
        assertFailsWith<com.eratw.port.core.xml.XmlParseException> { XmlParser.parse("<r>") }
        assertFailsWith<com.eratw.port.core.xml.XmlParseException> { XmlParser.parse("<r></x>") }
        assertFailsWith<com.eratw.port.core.xml.XmlParseException> {
            XmlParser.parse("<r a=\"1\" a=\"2\" />")
        }
        assertFailsWith<com.eratw.port.core.xml.XmlParseException> { XmlParser.parse("<r>&nope;</r>") }
    }

    @Test
    fun `XPath 数字路径与属性步`() {
        val doc = XmlParser.parse("<root><a id='1'>A1</a><a id='2'>A2</a></root>")
        // 基准 566 行：/root/a/@id → 2 个属性节点，值 1、2
        val ids = XmlPath.select(doc, "/root/a/@id")
        assertEquals(2, ids.size)
        assertEquals("1", ids[0].value)
        assertEquals("2", ids[1].value)
        // 基准 567 行：/root/a[1] → 第 1 个 a（1 起）
        val first = XmlPath.select(doc, "/root/a[1]")
        assertEquals(1, first.size)
        assertEquals("A1", first[0].innerText)
        assertEquals("A2", XmlPath.select(doc, "/root/a[2]").single().innerText)
        // 谓词按每个上下文节点求位置；属性谓词
        assertEquals(1, XmlPath.select(doc, "/root/a[@id='2']").size)
        assertEquals(2, XmlPath.select(doc, "//a").size)
        assertTrue(XmlPath.select(doc, "/root/nope").isEmpty())
        assertTrue(XmlPath.select(doc, "/root/a[3]").isEmpty())
    }

    @Test
    fun `文档池键区分大小写且不覆盖`() {
        val pool = com.eratw.port.core.xml.XmlPool()
        assertTrue(pool.create("GDOC", XmlParser.parse("<r />")))
        assertEquals(false, pool.create("GDOC", XmlParser.parse("<x />")))
        assertNotNull(pool["GDOC"])
        assertNull(pool["gdoc"])
        assertTrue(pool.release("GDOC"))
        assertEquals(false, pool.release("GDOC"))
    }
}

class XmlMethodsTest {

    /** 复现 `pc-golden-root.txt:564-576`（SECTION:XML）。 */
    @Test
    fun `XML 分节逐行复现基准 564-576`() {
        val (ctx, store) = xmlEnv()
        val buf = refS("XBUF")

        // 564 XML_DOCUMENT=1
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 7L, "<root><a id='1'>A1</a><a id='2'>A2</a></root>"))
        // 565 XML_EXIST=1
        assertEquals(1L, xiv(ctx, "XML_EXIST", 7L))
        // 566 XML_GET=2 X0=1 X1=2
        assertEquals(2L, xiv(ctx, "XML_GET", 7L, "/root/a/@id", buf))
        assertEquals("1", strAt(store, "XBUF", 0))
        assertEquals("2", strAt(store, "XBUF", 1))
        // 567 XML_GET_TEXT=1 T=A1
        assertEquals(1L, xiv(ctx, "XML_GET", 7L, "/root/a[1]", buf, 1L))
        assertEquals("A1", strAt(store, "XBUF", 0))
        // 568 XML_SET=1（style=1 = InnerText）
        assertEquals(1L, xiv(ctx, "XML_SET", 7L, "/root/a[1]", "B1", 0L, 1L))
        // 569 XML_GET_AFTER=1 T=B1
        assertEquals(1L, xiv(ctx, "XML_GET", 7L, "/root/a[1]", buf, 1L))
        assertEquals("B1", strAt(store, "XBUF", 0))
        // 570 XML_ADDNODE=1
        assertEquals(1L, xiv(ctx, "XML_ADDNODE", 7L, "/root", "<c>CC</c>"))
        // 571 XML_ADDATTRIBUTE=1
        assertEquals(1L, xiv(ctx, "XML_ADDATTRIBUTE", 7L, "/root/c", "attr", "V"))
        // 572 XML_TOSTR —— 与 XmlDocument.OuterXml 逐字节一致
        assertEquals(
            "<root><a id=\"1\">B1</a><a id=\"2\">A2</a><c attr=\"V\">CC</c></root>",
            xsv(ctx, "XML_TOSTR", 7L),
        )
        // 573 XML_REMOVENODE=1
        assertEquals(1L, xiv(ctx, "XML_REMOVENODE", 7L, "/root/c"))
        // 574 XML_REMOVEATTRIBUTE=1
        assertEquals(1L, xiv(ctx, "XML_REMOVEATTRIBUTE", 7L, "/root/a[1]/@id"))
        // 575 XML_REPLACE=1
        assertEquals(1L, xiv(ctx, "XML_REPLACE", 7L, "/root/a[2]", "<b id='9'>B9</b>"))
        // 576 XML_TOSTR2
        assertEquals("<root><a>B1</a><b id=\"9\">B9</b></root>", xsv(ctx, "XML_TOSTR", 7L))
    }

    /** 复现 `pc-golden-root.txt:577-578`（SECTION:XML-SRC）。 */
    @Test
    fun `XML-SRC 分节复现字符串源文本路径`() {
        val (ctx, store) = xmlEnv()
        // 578 XML_SRC_GET=1 V=10
        assertEquals(
            1L,
            xiv(ctx, "XML_GET", "<root><x>10</x></root>", "/root/x", refS("XBUF"), 1L),
        )
        assertEquals("10", strAt(store, "XBUF", 0))
    }

    /** 复现 `pc-golden-root.txt:580-590`（SECTION:XML-BYNAME）。 */
    @Test
    fun `XML-BYNAME 分节逐行复现基准 580-590`() {
        val (ctx, store) = xmlEnv()
        val buf = refS("XBUF")

        // 580 XML_DOCUMENT=1
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", "GDOC2", "<r><n v='1'>X</n></r>"))
        // 581 XML_GET_BYNAME=1 X=1
        assertEquals(1L, xiv(ctx, "XML_GET_BYNAME", "GDOC2", "/r/n/@v", buf))
        assertEquals("1", strAt(store, "XBUF", 0))
        // 582 XML_SET_BYNAME=1
        assertEquals(1L, xiv(ctx, "XML_SET_BYNAME", "GDOC2", "/r/n", "Y", 0L, 1L))
        // 583 XML_ADDNODE_BYNAME=1
        assertEquals(1L, xiv(ctx, "XML_ADDNODE_BYNAME", "GDOC2", "/r", "<m>M</m>"))
        // 584 XML_ADDATTRIBUTE_BYNAME=1
        assertEquals(1L, xiv(ctx, "XML_ADDATTRIBUTE_BYNAME", "GDOC2", "/r/m", "k", "1"))
        // 585 XML_REMOVENODE_BYNAME=1
        assertEquals(1L, xiv(ctx, "XML_REMOVENODE_BYNAME", "GDOC2", "/r/m"))
        // 586 XML_REMOVEATTRIBUTE_BYNAME=1
        assertEquals(1L, xiv(ctx, "XML_REMOVEATTRIBUTE_BYNAME", "GDOC2", "/r/n/@v"))
        // 587 XML_REPLACE_BYNAME=1
        assertEquals(1L, xiv(ctx, "XML_REPLACE_BYNAME", "GDOC2", "/r/n", "<z>7</z>"))
        // 588 XML_TOSTR3
        assertEquals("<r><z>7</z></r>", xsv(ctx, "XML_TOSTR", "GDOC2"))
        // 589/590 XML_RELEASE2=1 XML_RELEASE=1（golden:110-111；7 号文档须先存在——单测里
        // 没有前置分节帮它建，这里显式补建，否则测的是"释放不存在文档返回 0"）
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 7L, "<d/>"))
        assertEquals(1L, xiv(ctx, "XML_RELEASE", "GDOC2"))
        assertEquals(1L, xiv(ctx, "XML_RELEASE", 7L))
        // 释放后 XML_TOSTR 返回空串（原版 :1309-1315）
        assertEquals("", xsv(ctx, "XML_TOSTR", "GDOC2"))
    }

    @Test
    fun `XML_GET 支持 style 2 3 4 与 RESULTS 写入`() {
        val (ctx, store) = xmlEnv()
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 1L, "<r><a id='7'>T</a></r>"))
        assertEquals(1L, xiv(ctx, "XML_GET", 1L, "/r/a", refS("XBUF"), 3L))
        assertEquals("<a id=\"7\">T</a>", strAt(store, "XBUF", 0))
        assertEquals(1L, xiv(ctx, "XML_GET", 1L, "/r/a", refS("XBUF"), 4L))
        assertEquals("a", strAt(store, "XBUF", 0))
        assertEquals(1L, xiv(ctx, "XML_GET", 1L, "/r/a", refS("XBUF"), 2L))
        assertEquals("T", strAt(store, "XBUF", 0))
        // 第三参给非 0 整数 → 写 RESULTS（DataStringArray[2]，VariableEvaluator.cs:81）
        assertEquals(1L, xiv(ctx, "XML_GET", 1L, "/r/a/@id", 1L))
        assertEquals("7", strAt(store, "RESULTS", 0))
    }

    @Test
    fun `XML_SET style 1 空串留下空文本节点`() {
        val (ctx, _) = xmlEnv()
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 9L, "<r><a>1</a></r>"))
        assertEquals(1L, xiv(ctx, "XML_SET", 9L, "/r/a", "", 0L, 1L))
        // .NET `XmlNode.InnerText` setter 对元素总是建一个文本子节点，空串也不例外：
        // dotnet 8.0.27 + net8.0-windows 实测 `<a>1</a>` 设 InnerText="" 后 OuterXml 是
        // `<a></a>`（FirstChild != null），**不是** `<a />`。故这里保留空 XmlText。
        assertEquals("<r><a></a></r>", xsv(ctx, "XML_TOSTR", 9L))
    }

    @Test
    fun `XML 写回覆盖角色字符串变量`() {
        val (ctx, store) = xmlEnv()
        store.registerTemplate(CharacterTemplate(no = 1, name = "アリス", cstr = mapOf(3 to "<r><a>1</a></r>")))
        store.addCharacter(1L)
        // 角色字符串 = [角色位置, 元素] 两维（VariableParser.cs:34-63 的补全规则）；
        // 原版 `(arguments[0] as VariableTerm).SetValue(OuterXml, exm)`（:1284/:1588/:1737/:1899）
        // 对角色字符串同样写回，修复前移植侧按 isCharacterData 直接 return。
        val cstr = VarRef("CSTR", listOf(IntLiteral(0), IntLiteral(3)), operandType = OperandType.STR)
        assertEquals(1L, xiv(ctx, "XML_SET", cstr, "/r/a", "2", 0L, 1L))
        assertEquals("<r><a>2</a></r>", store.readStr("CSTR", longArrayOf(0, 3)))
        // 同一写回通道：XML_REMOVENODE（:1737）。空元素的回写口径见「空元素写成斜杠前带空格」：
        // 本档 <r> 是带结束标签装载的，.NET 实测删光子节点后 IsEmpty 仍为 False、
        // OuterXml 回写 <r></r>（不降级成自闭合 <r />），移植侧同口径。
        assertEquals(1L, xiv(ctx, "XML_REMOVENODE", cstr, "/r/a"))
        assertEquals("<r></r>", store.readStr("CSTR", longArrayOf(0, 3)))
    }

    @Test
    fun `文档池语义`() {
        val (ctx, _) = xmlEnv()
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 3L, "<r />"))
        // 已存在 → 0，且不解析新文本（原版 :1114-1117 先判存在）
        assertEquals(0L, xiv(ctx, "XML_DOCUMENT", 3L, "<broken"))
        assertEquals(1L, xiv(ctx, "XML_EXIST", 3L))
        assertEquals(0L, xiv(ctx, "XML_RELEASE", 4L))
        // 缺失文档的读操作返回 -1（不是错误）
        assertEquals(-1L, xiv(ctx, "XML_GET", 4L, "/r", refS("XBUF")))
        assertEquals(-1L, xiv(ctx, "XML_SET", 4L, "/r", "x", 0L, 1L))
        assertEquals(-1L, xiv(ctx, "XML_ADDNODE", 4L, "/r", "<a />"))
        assertEquals(-1L, xiv(ctx, "XML_REMOVENODE", 4L, "/r"))
        assertEquals(-1L, xiv(ctx, "XML_REPLACE", 4L, "/r", "<a />"))
        assertEquals("", xsv(ctx, "XML_TOSTR", 4L))
    }

    @Test
    fun `非 BYNAME 的字符串参数按 XML 源文本解析而非查池`() {
        val (ctx, _) = xmlEnv()
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", "GDOC", "<r />"))
        // GOLDEN_10_XMLMAP.ERB 的 NOTE：XML_GET("GDOC", ...) 是解析错误
        assertFailsWith<CodeEE> { xiv(ctx, "XML_GET", "GDOC", "/r", refS("XBUF")) }
    }

    @Test
    fun `XML_SET style 0 作用在元素上抛 CodeEE`() {
        val (ctx, _) = xmlEnv()
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 5L, "<r><a>1</a></r>"))
        // GOLDEN_10_XMLMAP.ERB 的 ENGINE-FAULT ORACLE：原版 InvalidOperationException
        assertFailsWith<CodeEE> { xiv(ctx, "XML_SET", 5L, "/r/a", "x") }
        // 属性节点可以按 Value 赋值
        assertEquals(1L, xiv(ctx, "XML_SET", 5L, "/r/a", "y", 0L, 1L))
        assertEquals(1L, xiv(ctx, "XML_GET", 5L, "/r/a", refS("XBUF"), 1L))
    }

    @Test
    fun `XML_ADDNODE 的插入位置与多命中语义`() {
        val (ctx, _) = xmlEnv()
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 6L, "<r><a>1</a><a>2</a></r>"))
        // method=1 插到命中节点之前（第 4 参）
        assertEquals(1L, xiv(ctx, "XML_ADDNODE", 6L, "/r/a[1]", "<x />", 1L))
        assertEquals("<r><x /><a>1</a><a>2</a></r>", xsv(ctx, "XML_TOSTR", 6L))
        // 多命中且未给「全部应用」→ 不动
        assertEquals(2L, xiv(ctx, "XML_ADDNODE", 6L, "/r/a", "<y />"))
        assertEquals("<r><x /><a>1</a><a>2</a></r>", xsv(ctx, "XML_TOSTR", 6L))
        // 多命中 + 第 5 参非 0 → 全部插入。注意：原版对多个命中复用**同一个**新节点，
        // 而 .NET AppendChild 对已挂节点是「移动」——所以只留在最后一个命中下（照抄该行为）。
        assertEquals(2L, xiv(ctx, "XML_ADDNODE", 6L, "/r/a", "<y />", 0L, 1L))
        assertEquals("<r><x /><a>1</a><a>2<y /></a></r>", xsv(ctx, "XML_TOSTR", 6L))
    }

    @Test
    fun `XML_REPLACE 两参形态替换整文档`() {
        val (ctx, _) = xmlEnv()
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 8L, "<r><a /></r>"))
        assertEquals(1L, xiv(ctx, "XML_REPLACE", 8L, "<z>9</z>"))
        assertEquals("<z>9</z>", xsv(ctx, "XML_TOSTR", 8L))
    }

    @Test
    fun `注册表覆盖占位`() {
        val names = listOf(
            "XML_DOCUMENT", "XML_RELEASE", "XML_EXIST", "XML_GET", "XML_GET_BYNAME",
            "XML_SET", "XML_SET_BYNAME", "XML_TOSTR", "XML_ADDNODE", "XML_ADDNODE_BYNAME",
            "XML_ADDATTRIBUTE", "XML_ADDATTRIBUTE_BYNAME", "XML_REMOVENODE",
            "XML_REMOVENODE_BYNAME", "XML_REMOVEATTRIBUTE", "XML_REMOVEATTRIBUTE_BYNAME",
            "XML_REPLACE", "XML_REPLACE_BYNAME",
        )
        for (n in names) {
            assertNotNull(Builtins.registry[n], "函数 $n 未注册")
            assertTrue(!Builtins.registry.isPlaceholder(n), "函数 $n 仍是占位")
        }
    }
}
