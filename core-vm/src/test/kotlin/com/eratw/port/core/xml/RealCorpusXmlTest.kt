package com.eratw.port.core.xml

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 真实语料 XML 回归闸：移植解析器 vs JDK `DocumentBuilderFactory`（仅测试内 oracle）。
 *
 * **按根取列**（xmlcorpus 波，2026-11-06）：本类不再钉单一版本读数。语料根只认
 * `ERATW_GAMEDIR`（闸配方 AGENTS.md §5），版本档由 [com.eratw.port.boot.CorpusProfile.detect]
 * 从 `ERB/DIM.ERH` 的 `NOW_VERSION` 判出，两列读数集中在 [XmlCorpusFacts]：
 * 4.980 = `eratw-画蛇添足版eratw4.980`、4.981 = `eraTW4.981-261017-EAGLOV3`。
 *
 * 语料规模（两根实读）：
 * - 4.980：98 个 XML —— `resources` 下 93 个精灵表 + `lang/emuera.zhs.xml` +
 *   `plugins` 下 4 个；3,263,831 字节 / 48,518 元素 / 76,902 属性；
 * - 4.981：4 个（全在 `plugins/`；`resources` 下精灵表在该版已改成 CSV ⇒ 0 个 XML），
 *   609,494 字节 / 35,271 元素 / 46 属性
 *   （.NET 8 DOM 口径：xmlns 声明也是 XmlAttribute；dotnet 8.0.425 对 schema.xml 实测
 *   elems=20 / attrs=46，根上 id、xmlns、xmlns:xs、xmlns:msdata 全计入）；
 * - 带 BOM 的三份：tw_csv_chs / tw_taste_chs / schema；bbas_dataset 不带（两根同值，实读）。
 *   schema 声明写 encoding="utf-16" 实际是 UTF-8 —— LoadXml(string) 不校验声明编码（.NET 8 实测）。
 * - BOM 口径：.NET 8 `LoadXml(string)` 对 U+FEFF 开头的串直接抛 XmlException，移植
 *   XmlParser.parse 则剥掉继续解析 —— 该语义分歧已按 `C-XML-DIVERGENCE@2` 方向乙（OD-1
 *   2026-11-02）登记为**已知有意分歧**（真实链路 LOADTEXT 走 File.ReadAllText 已剥 BOM ⇒ 不可达）；
 *   对拍两边统一喂剥 BOM 文本。
 *
 * oracle 口径：
 * - 用字符流 `InputSource(StringReader(text))` 喂 JDK（等价 C# `LoadXml(string)`）。
 *   不能用字节流：声明里的 `utf_8` 不是 JDK 的 charset 别名，按字节喂会直接报错。
 * - 逐文件比较根元素名、元素总数、属性总数、每个元素的路径/本地名/属性键值/文本。
 * - 属性按名字排序后比较：JDK 的 `NamedNodeMap` 会重排属性，而 .NET `OuterXml` 与
 *   本移植都保留源顺序；顺序已由 `XmlMethodsTest` 的合成用例单独钉住。
 * - 文本比较：JDK DOM 里纯空白文本节点先按 .NET `PreserveWhitespace=false` 丢弃，
 *   其余文本逐字比较（比折叠空白更严；语料正文本来就是空文本）。
 *
 * 语料缺失（未设 `ERATW_GAMEDIR` / 目录不是语料根 / `plugins` 下缺 xml）**判红不跳过**：
 * 旧实现在这里挂 `Assumptions.assumeTrue`，环境变量一缺用例就静默通过 —— 那是假闸，
 * 与 `core/config/ConfigChainTest` 的口径对齐后改成本类的红线。
 */

/** 游戏根下递归的全部 xml，按路径排序；一个都没有 ⇒ 语料根不完整，判红。 */
private fun realXmlFiles(facts: XmlCorpusFacts): List<Path> {
    val files = Files.walk(facts.root).use { stream ->
        stream.filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().lowercase(Locale.ROOT).endsWith(".xml") }
            .sorted(compareBy { it.toString() })
            .toList()
    }
    if (files.isEmpty()) {
        throw AssertionError(
            "${facts.root}（${facts.profile}）下递归不到任何 xml：语料根形态不符 eraTW 规约",
        )
    }
    return files
}

private fun readUtf8(file: Path): String = Files.readString(file, StandardCharsets.UTF_8)

/**
 * 剥掉串首 U+FEFF。.NET 8 `LoadXml(string)` 对带 BOM 字符的串直接抛 XmlException
 * （dotnet 8.0.425 实测："Data at the root level is invalid. Line 1, position 1."），
 * 移植 XmlParser.parse 则剥掉继续解析 —— 该分歧已按 `C-XML-DIVERGENCE@2` 方向乙登记为
 * **已知有意分歧**，不在本测试里钉成绿；对拍口径两边统一取剥 BOM 文本。真实链路上
 * LOADTEXT 走 File.ReadAllText，BOM 由 StreamReader 消费（FunctionMethodCreator.cs:8082），
 * 本就不把 BOM 带进 LoadXml。
 */
private fun withoutBomChar(text: String): String =
    if (text.startsWith("\uFEFF")) text.substring(1) else text

// ---------------------------------------------------------------------------
// JDK oracle 与结构签名
// ---------------------------------------------------------------------------

private fun oracleParse(text: String): org.w3c.dom.Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false
    factory.isCoalescing = true
    factory.isIgnoringComments = false
    // 只做结构对拍；禁用 DTD 外实体，避免测试意外触网。语料本身没有 DOCTYPE。
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    return factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
}

private data class ElementSig(
    val path: String,
    val name: String,
    val attributes: List<Pair<String, String>>,
    val text: String,
)

private data class DocSig(val rootName: String, val elements: List<ElementSig>) {
    val elementCount: Int get() = elements.size
    val attributeCount: Int get() = elements.sumOf { it.attributes.size }
}

private fun signatureOf(doc: XmlDocument): DocSig {
    val root = doc.root ?: throw AssertionError("XmlParser 没有解析出根元素")
    val out = ArrayList<ElementSig>()
    fun walk(el: XmlElement, path: String) {
        out += ElementSig(
            path = path,
            name = el.name,
            attributes = el.attributes.map { it.name to it.value }.sortedBy { it.first },
            text = el.innerText,
        )
        var index = 0
        for (c in el.children) {
            if (c is XmlElement) {
                index++
                walk(c, "$path/${c.name}[$index]")
            }
        }
    }
    walk(root, "/${root.name}[1]")
    return DocSig(root.name, out)
}

private fun isXmlWhitespace(s: String): Boolean =
    s.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' }

/** .NET `PreserveWhitespace=false` 的等价文本：纯空白文本节点不计，CDATA 始终计入。 */
private fun oracleText(node: Node): String {
    val sb = StringBuilder()
    fun walk(n: Node) {
        when (n.nodeType) {
            Node.TEXT_NODE -> {
                val v = n.nodeValue ?: ""
                if (!isXmlWhitespace(v)) sb.append(v)
            }
            Node.CDATA_SECTION_NODE -> sb.append(n.nodeValue ?: "")
            Node.ELEMENT_NODE, Node.DOCUMENT_NODE -> {
                var c = n.firstChild
                while (c != null) {
                    walk(c)
                    c = c.nextSibling
                }
            }
        }
    }
    walk(node)
    return sb.toString()
}

private fun signatureOf(doc: org.w3c.dom.Document): DocSig {
    val root = doc.documentElement ?: throw AssertionError("JDK 没有解析出根元素")
    val out = ArrayList<ElementSig>()
    fun walk(el: Element, path: String) {
        val attrs = ArrayList<Pair<String, String>>(el.attributes.length)
        for (i in 0 until el.attributes.length) {
            val a = el.attributes.item(i)
            attrs += a.nodeName to (a.nodeValue ?: "")
        }
        attrs.sortBy { it.first }
        out += ElementSig(path, el.localName ?: el.nodeName, attrs, oracleText(el))
        var index = 0
        var c = el.firstChild
        while (c != null) {
            if (c.nodeType == Node.ELEMENT_NODE) {
                index++
                val child = c as Element
                walk(child, "$path/${child.nodeName}[$index]")
            }
            c = c.nextSibling
        }
    }
    walk(root, "/${root.nodeName}[1]")
    return DocSig(root.localName ?: root.nodeName, out)
}

private fun assertSameSignature(relative: String, ours: DocSig, oracle: DocSig) {
    assertEquals(oracle.rootName, ours.rootName, "$relative 的根元素名不一致")
    assertEquals(oracle.elementCount, ours.elementCount, "$relative 的元素总数不一致")
    assertEquals(oracle.attributeCount, ours.attributeCount, "$relative 的属性总数不一致")
    val n = maxOf(oracle.elements.size, ours.elements.size)
    for (i in 0 until n) {
        val expected = oracle.elements.getOrNull(i)
        val actual = ours.elements.getOrNull(i)
        assertEquals(
            expected,
            actual,
            "$relative 第 ${i + 1} 个元素不一致（路径 ${expected?.path ?: actual?.path}）",
        )
    }
}

// ---------------------------------------------------------------------------
// 内建 XML 家族的最小装置（与 XmlMethodsTest 同形；那边的 helper 是文件私有的）
// ---------------------------------------------------------------------------

private class CorpusXmlCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("CorpusXmlCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun xmlEnv(): Pair<BuiltinEvalContext, VariableStore> {
    val store = VariableStore()
    val base = CorpusXmlCtx()
    base.variables = store
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

// ---------------------------------------------------------------------------
// 测试
// ---------------------------------------------------------------------------

class RealCorpusXmlTest {

    companion object {
        /**
         * 参数化源：每个真实 xml 一个用例。语料根缺失 / 形态不符 ⇒ [XmlCorpusFacts.detect]
         * 抛 AssertionError（**不再**退回空哨兵走 assume-skip：跳过等于把这道闸关掉）。
         */
        @JvmStatic
        fun xmlFiles(): List<Arguments> {
            val facts = XmlCorpusFacts.detect()
            return realXmlFiles(facts).map { Arguments.of(it.toAbsolutePath().toString(), Files.size(it)) }
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("xmlFiles")
    fun `真实语料 XML 结构与 JDK DOM 对拍`(filePath: String, byteSize: Long) {
        val facts = XmlCorpusFacts.detect()
        val file = Paths.get(filePath)
        assertEquals(byteSize, Files.size(file), "文件字节数与发现时不一致：$filePath")
        val text = readUtf8(file).let(::withoutBomChar)
        val relative = facts.root.relativize(file).toString()
        assertSameSignature(relative, signatureOf(XmlParser.parse(text)), signatureOf(oracleParse(text)))
    }

    /** 语料规模 + 解析读数（按根取列）：任一回退说明语料/入口/解析器变了，先查这里。 */
    @Test
    fun `语料规模与解析读数`() {
        val facts = XmlCorpusFacts.detect()
        val list = realXmlFiles(facts)
        val totalBytes = list.sumOf { Files.size(it) }
        var elements = 0
        var attributes = 0
        for (f in list) {
            val doc = XmlParser.parse(readUtf8(f))
            val root = assertNotNull(doc.root, "${f.fileName} 没有根元素")
            fun count(el: XmlElement) {
                elements++
                attributes += el.attributes.size
                for (c in el.children) if (c is XmlElement) count(c)
            }
            count(root)
        }
        assertEquals(
            facts.xmlFiles,
            list.size,
            "语料 XML 文件数（按根取列：4.980 含 resources 下 93 个精灵表 + lang 下 1 个；" +
                "4.981 全在 plugins/）",
        )
        assertEquals(facts.xmlBytes, totalBytes, "语料 XML 总字节（按根取列）")
        assertEquals(facts.xmlElements, elements, "元素总数（按根取列）")
        assertEquals(
            facts.xmlAttributes,
            attributes,
            "属性总数（按根取列；.NET 8 DOM 口径：xmlns 声明也计入；.NET 实测 schema.xml 根 4 属性、全档 46）",
        )
        println(
            "RealCorpusXmlTest 对拍规模（${facts.profile}）：${list.size} 个 XML / $totalBytes 字节 / " +
                "$elements 元素 / $attributes 属性",
        )
    }

    /**
     * 解析与序列化的 .NET 口径回归：字面量来自 dotnet 8 对
     * `new XmlDocument().LoadXml(...)` 的实测（声明单引号、属性内 TAB/LF/CR、文本 CR）。
     */
    @Test
    fun `XML 声明与空白序列化按 dotnet LoadXml 口径`() {
        val doc = XmlParser.parse(
            "<?xml version='1.0' encoding='utf_8'?><r a='x\ty&#10;z&#13;w'>a&#13;b</r>",
        )
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"utf_8\"?><r a=\"x\ty&#xA;z&#xD;w\">a\rb</r>",
            doc.outerXml(),
        )
        // .NET XmlDocument.InnerXml 也带声明
        assertEquals(doc.outerXml(), doc.innerXml())
        val attr = assertNotNull(doc.root?.attribute("a"))
        assertEquals("x\ty\nz\rw", attr.value)
        assertEquals("a\rb", doc.root?.innerText)
        // .NET load 不按 XML 规范做行尾归一：原始 CRLF 原样保留
        val raw = XmlParser.parse("<r>a\r\nb</r>")
        assertEquals("a\r\nb", raw.root?.innerText)
        assertEquals("<r>a\r\nb</r>", raw.outerXml())
    }

    /**
     * `XmlPath` 每种路径形式 + `XmlPool` 池语义，全部用真实文件。
     *
     * 4.981 的 anime.xml / kagerou.xml / Resource.xml 都已随 resources 精灵表移除
     * （4.980 的对拍锚点见 git 历史），换 plugins 下在位的等价锚点（两根都在位）：
     * `tw_csv_chs.xml`（`<map><p><k>…</k><v>…</v></p>`，4.980 = 4400 个 p / 4.981 = 7804 个）、
     * `bbas_dataset.xml`（`<DocumentElement><bbas_dataset>…`，4.980 = 160 条 / 4.981 = 167 条，
     * 含条件谓词目标）、`schema.xml`（根通配步 + 属性步目标；带前缀的名字步在 .NET SelectNodes
     * 没有 XmlNamespaceManager 时直接抛 XPathException，移植同口径，锚点不能用前缀）。
     */
    @Test
    fun `XmlPath 与 XmlPool 在真实文件上的查询`() {
        val facts = XmlCorpusFacts.detect()
        val map = XmlParser.parse(readUtf8(facts.root.resolve("plugins/tw_csv_chs.xml")))
        val bbas = XmlParser.parse(readUtf8(facts.root.resolve("plugins/bbas_dataset.xml")))
        val schema = XmlParser.parse(readUtf8(facts.root.resolve("plugins/schema.xml")))

        // 子轴 / 通配 / 位置谓词 / 文本步
        assertEquals(facts.mapEntries, XmlPath.select(map, "/map/p").size)
        assertEquals(facts.mapEntries, XmlPath.select(map, "/map/*").size)
        assertEquals(1, XmlPath.select(map, "/map/p[1]").size)
        assertEquals(1, XmlPath.select(map, "/map/p[last()]").size)
        // 首个有正文的条目按根取列：4.980 = p[3]（p[1] 是纯空格行、p[2] 是空行）、4.981 = p[1]
        assertEquals(
            facts.textEntryK,
            XmlPath.select(map, "/map/p[${facts.textEntry}]/k/text()").single().value,
        )
        assertEquals(facts.lastEntryK, XmlPath.select(map, "/map/p[last()]/k/text()").single().value)
        assertEquals(
            facts.bbasFirstRecordChildren,
            XmlPath.select(bbas, "/DocumentElement/bbas_dataset[1]/*").size,
            facts.bbasFirstRecordChildrenNote,
        )
        // 后代轴 / 子元素文本谓词
        assertEquals(facts.mapEntries, XmlPath.select(map, "//k").size)
        assertEquals(facts.bbasRecords, XmlPath.select(bbas, "//id").size)
        assertEquals(1, XmlPath.select(map, "/map/p[k='${facts.uniqueEntryK}']").size)
        assertEquals(1, XmlPath.select(bbas, "//bbas_dataset[id='3']/name").size)
        // 两根同值，实读：id=3 的 name = 卡娜
        assertEquals("卡娜", XmlPath.select(bbas, "//bbas_dataset[id='3']/name").single().innerText)
        // 文档节点 / 属性步（属性在 schema 上）/ 子元素文本谓词（语料正文为空串）
        // schema 根元素名带 xs: 前缀，但带前缀的名字步在 .NET SelectNodes 没有 XmlNamespaceManager
        // 时直接抛 XPathException（.NET 8 实测消息与本移植 XmlPathException 逐字一致），故用根通配步。
        assertEquals("#document", XmlPath.select(map, "/").single().name)
        // NewDataSet 两根同值，实读
        assertEquals("NewDataSet", XmlPath.select(schema, "/*/@id").single().value)
        assertEquals(
            facts.schemaAttributeWildcard,
            XmlPath.select(schema, "/*/*/@*").size,
            "根的子元素（xs:element）属性全被通配命中（两根同值 5，实读）。原注释登记的根级 xmlns " +
                "分歧已由 XML-FIVE X5（2026-11-06）翻案复刻：移植属性步现在与 .NET 同口径过滤 " +
                "xmlns/xmlns:p 声明，根级步不再踩分歧（见下方根级锚点）",
        )
        // XML-FIVE X5 的真语料锚点：schema.xml 根上带 id/xmlns/xmlns:xs/xmlns:msdata 四个属性
        // （.NET 8 DOM 口径 xmlns 声明也是 XmlAttribute，attrs=46 计入），属性步过滤声明后
        // 根级通配只剩 id 一个 —— dotnet 8.0.425 SelectNodes 对同一文档实测 `/@*` 形态 = 1，
        // 移植（旧实现把声明当属性节点返回 4）已翻案对齐。
        assertEquals(1, XmlPath.select(schema, "/*/@*").size, "根级属性通配过滤 xmlns 声明后只剩 id")
        assertEquals(0, XmlPath.select(map, "/map/p[text()='']").size, "p 只有元素子节点、无直接文本 ⇒ 0")

        // ResourceManager.ERB 的 style=3 往返：元素 OuterXml 再喂回解析器，走相对路径
        val pOuter = XmlPath.select(map, "/map/p[${facts.textEntry}]").single().outerXml()
        val reparsed = XmlParser.parse(pOuter)
        assertEquals("p", XmlPath.select(reparsed, "/*").single().name)
        assertEquals(2, XmlPath.select(reparsed, "*/k").size + XmlPath.select(reparsed, "*/v").size)
        assertEquals(facts.textEntryK, XmlPath.select(reparsed, "*/k/text()").single().value)

        // XmlPool：真实文档进池、按名取、释放
        val pool = XmlPool()
        assertTrue(pool.create("map", map))
        assertTrue(pool.create("bbas", bbas))
        assertTrue(pool.create("schema", schema))
        assertEquals(3, pool.size)
        assertEquals(facts.mapEntries, XmlPath.select(assertNotNull(pool["map"]), "/map/p").size)
        assertEquals(facts.bbasRecords, XmlPath.select(assertNotNull(pool["bbas"]), "//id").size)
        assertEquals("NewDataSet", XmlPath.select(assertNotNull(pool["schema"]), "/*/@id").single().value)
        assertTrue(pool.release("map"))
        assertEquals(2, pool.size)
        assertNull(pool["map"])
        assertEquals(false, pool.release("map"))
    }

    /**
     * `XML_DOCUMENT` / `XML_EXIST` / `XML_GET` / `XML_GET_BYNAME` / `XML_TOSTR` 直接喂
     * 真实文件文本与真实片段（两根在位文件都是 3 个：词典 / bbas / schema + 单行 p 片段）。
     *
     * 按根取列的点只有三处：`<p>` 条目数、bbas 记录数与末条 id、第 1 条 `<p>` 的 `<k>` 文本
     * （4.980 第 1 条是纯空格行 ⇒ 文本为空串；4.981 第 1 条是 `营养`）。
     */
    @Test
    fun `XML 内建家族喂真实语料片段`() {
        val facts = XmlCorpusFacts.detect()
        val mapText = readUtf8(facts.root.resolve("plugins/tw_csv_chs.xml"))
        val bbasText = readUtf8(facts.root.resolve("plugins/bbas_dataset.xml"))
        val schemaText = readUtf8(facts.root.resolve("plugins/schema.xml"))
        val (ctx, store) = xmlEnv()
        val buf = refS("XBUF")

        // 1) 整文件：/map/p 命中全部条目，取第 1 个的 OuterXml 再走相对路径
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 101L, mapText))
        assertEquals(1L, xiv(ctx, "XML_EXIST", 101L))
        assertEquals(facts.mapEntries.toLong(), xiv(ctx, "XML_GET_BYNAME", 101L, "/map/p"))
        assertEquals(1L, xiv(ctx, "XML_GET_BYNAME", 101L, "/map/p[1]", buf, 3L))
        val pOuter = strAt(store, "XBUF", 0)
        assertTrue(pOuter.startsWith("<p>"), "style=3 应返回元素 OuterXml：${pOuter.take(40)}")
        assertEquals(1L, xiv(ctx, "XML_GET", pOuter, "*/k"))
        // style 省略 = 0：.NET OutPutNode 落到 node.Value（FunctionMethodCreator.cs:135-157、:195），
        // 元素节点的 Value 恒为 null ⇒ 写空串；文本要显式传 style=1（InnerText）。
        assertEquals(1L, xiv(ctx, "XML_GET", pOuter, "*/k", buf))
        assertEquals("", strAt(store, "XBUF", 0))
        assertEquals(1L, xiv(ctx, "XML_GET", pOuter, "*/k", buf, 1L))
        assertEquals(facts.firstEntryK, strAt(store, "XBUF", 0))

        // 2) 整文件 + 字符串键：声明被规范化为双引号（PC LoadXml 口径）
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", "BDOC", bbasText))
        assertEquals(facts.bbasRecords.toLong(), xiv(ctx, "XML_GET_BYNAME", "BDOC", "/DocumentElement/bbas_dataset"))
        // id 是元素节点：style 省略 = 0 → node.Value 恒 null ⇒ 空串；文本要显式 style=1（InnerText）
        assertEquals(1L, xiv(ctx, "XML_GET_BYNAME", "BDOC", "/DocumentElement/bbas_dataset[last()]/id", buf, 1L))
        assertEquals(facts.bbasLastId, strAt(store, "XBUF", 0))
        val toString = xsv(ctx, "XML_TOSTR", "BDOC")
        assertTrue(
            toString.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?><DocumentElement>"),
            "XML_TOSTR 声明未按 .NET 规范化：${toString.take(80)}",
        )
        // 首条记录名「靈夢」两根都在（实读）
        assertTrue(toString.contains("靈夢"))

        // 3) 整文件的属性相等谓词（schema 的属性在 xs:element 上；前缀步不合法，用全后代属性谓词）
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 102L, schemaText))
        assertEquals(
            1L,
            xiv(ctx, "XML_GET", 102L, "//*[@name='bbas_dataset']"),
        )
        assertEquals(1L, xiv(ctx, "XML_GET", 102L, "/*/@id", buf))
        assertEquals("NewDataSet", strAt(store, "XBUF", 0))

        // 4) 单元素真实片段（tw_csv_chs.xml 的第一条 p 行；语料 p 全部无属性，锚 "<p>"）
        val fragment = mapText.lineSequence().first { it.trimStart().startsWith("<p>") }.trim()
        assertEquals(1L, xiv(ctx, "XML_GET", fragment, "*/k", buf, 1L))
        assertEquals(facts.firstEntryK, strAt(store, "XBUF", 0))

        // 释放后 XML_EXIST 归零
        assertEquals(1L, xiv(ctx, "XML_RELEASE", 101L))
        assertEquals(0L, xiv(ctx, "XML_EXIST", 101L))
    }
}
