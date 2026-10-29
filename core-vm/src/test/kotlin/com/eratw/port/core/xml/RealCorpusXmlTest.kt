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
import org.junit.jupiter.api.Assumptions
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
 * 语料规模（`ERATW_CORPUS` 指向的已发布游戏目录，外部读数）：
 * - 93 个 XML / 2,804,124 字节；
 * - 26,178 个元素 / 75,791 个属性；
 * - 全部 UTF-8、带 XML 声明（单引号 + `encoding='utf_8'`），无 CDATA / DOCTYPE / 实体 /
 *   制表符 / 回车，正文只有元素与缩进空白。下一个 boot 阶段 `ResourceManager.ERB`
 *   正是用 `XML_DOCUMENT` + `XML_GET_BYNAME` 遍历这些文件。
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
 * 语料缺失（未设 `ERATW_CORPUS` / 找不到 resources 目录）时 assume-skip；
 * 路径一律从环境变量推，不写死主机目录。
 */

private const val CORPUS_ENV = "ERATW_CORPUS"

/** 从 `ERATW_CORPUS` 向上找含 resources 子目录的游戏根；`.../ERB` 与游戏根两种形态都接受。 */
private fun corpusResourcesRoot(): Path? {
    val raw = System.getenv(CORPUS_ENV)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val start = runCatching { Paths.get(raw).toAbsolutePath().normalize() }.getOrNull() ?: return null
    if (!Files.isDirectory(start)) return null
    var cur: Path? = start
    var depth = 0
    while (cur != null && depth < 8) {
        if (Files.isDirectory(cur.resolve("resources"))) return cur
        cur = cur.parent
        depth++
    }
    return null
}

/** resources 目录下递归的全部 xml，按路径排序；没有文件时返回 null（走 assume-skip）。 */
private fun realXmlFiles(): List<Path>? {
    val root = corpusResourcesRoot() ?: return null
    val files = Files.walk(root.resolve("resources")).use { stream ->
        stream.filter { Files.isRegularFile(it) }
            .filter { it.fileName.toString().lowercase(Locale.ROOT).endsWith(".xml") }
            .sorted(compareBy { it.toString() })
            .toList()
    }
    return files.ifEmpty { null }
}

private fun readUtf8(file: Path): String = Files.readString(file, StandardCharsets.UTF_8)

private fun resourceFile(root: Path, vararg parts: String): Path {
    var p = root.resolve("resources")
    for (part in parts) p = p.resolve(part)
    return p
}

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
         * 参数化源：每个真实 xml 一个用例；语料缺失时给一个空哨兵，
         * 让用例走 `Assumptions.assumeTrue` 跳过而不是零用例报错。
         */
        @JvmStatic
        fun xmlFiles(): List<Arguments> {
            val files = realXmlFiles() ?: return listOf(Arguments.of("", 0L))
            return files.map { Arguments.of(it.toAbsolutePath().toString(), Files.size(it)) }
        }
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("xmlFiles")
    fun `真实语料 XML 结构与 JDK DOM 对拍`(filePath: String, byteSize: Long) {
        val root = corpusResourcesRoot()
        Assumptions.assumeTrue(
            root != null && filePath.isNotEmpty(),
            "未设置 $CORPUS_ENV 或找不到 resources 目录，跳过真实 XML 对拍",
        )
        val file = Paths.get(filePath)
        assertEquals(byteSize, Files.size(file), "文件字节数与发现时不一致：$filePath")
        val text = readUtf8(file)
        val relative = root!!.relativize(file).toString()
        assertSameSignature(relative, signatureOf(XmlParser.parse(text)), signatureOf(oracleParse(text)))
    }

    /** 语料规模 + 解析读数：任一回退说明语料/入口/解析器变了，先查这里。 */
    @Test
    fun `语料规模与解析读数`() {
        val files = realXmlFiles()
        Assumptions.assumeTrue(
            files != null,
            "未设置 $CORPUS_ENV 或找不到 resources 目录，跳过真实 XML 对拍",
        )
        val list = files!!
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
        assertEquals(93, list.size, "语料 XML 文件数")
        assertEquals(2_804_124L, totalBytes, "语料 XML 总字节")
        assertEquals(26_178, elements, "元素总数")
        assertEquals(75_791, attributes, "属性总数")
        println(
            "RealCorpusXmlTest 对拍规模：${list.size} 个 XML / $totalBytes 字节 / " +
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

    /** `XmlPath` 每种路径形式 + `XmlPool` 池语义，全部用真实文件。 */
    @Test
    fun `XmlPath 与 XmlPool 在真实文件上的查询`() {
        val root = corpusResourcesRoot()
        Assumptions.assumeTrue(
            root != null,
            "未设置 $CORPUS_ENV 或找不到 resources 目录，跳过真实 XML 查询",
        )
        val anime = XmlParser.parse(readUtf8(resourceFile(root!!, "As06MARK-IV", "anime", "anime.xml")))
        val kagerou = XmlParser.parse(readUtf8(resourceFile(root, "AI arts", "kagerou.xml")))
        val resource = XmlParser.parse(readUtf8(resourceFile(root, "EN_IMG", "327", "Resource.xml")))
        val as06 = XmlParser.parse(readUtf8(resourceFile(root, "As06MARK-IV", "As06MARK-IV.xml")))

        // 子轴 / 通配 / 位置谓词 / 属性步
        assertEquals(8, XmlPath.select(anime, "/sprites/*").size)
        assertEquals(8, XmlPath.select(anime, "/sprites/a").size)
        assertEquals(1, XmlPath.select(anime, "/sprites/a[1]").size)
        assertEquals(1, XmlPath.select(anime, "/sprites/a[last()]").size)
        assertEquals("500", XmlPath.select(anime, "/sprites/a[1]/@width").single().value)
        assertEquals("300", XmlPath.select(anime, "/sprites/a[last()]/@height").single().value)
        assertEquals(121, XmlPath.select(anime, "/sprites/a[1]/f").size)
        assertEquals(84, XmlPath.select(anime, "/sprites/a[last()]/f").size)
        // 后代轴 / 属性存在谓词
        assertEquals(740, XmlPath.select(anime, "//f").size)
        assertEquals(8, XmlPath.select(anime, "/sprites/a[@name]").size)
        // 文档节点 / 属性通配 / 子元素文本谓词 / 自身文本谓词（语料正文为空串）
        assertEquals("#document", XmlPath.select(anime, "/").single().name)
        val firstAttrs = XmlPath.select(anime, "/sprites/a[1]/@*")
        assertEquals(listOf("name", "width", "height"), firstAttrs.map { it.name })
        assertEquals("500", firstAttrs[1].value)
        assertEquals(8, XmlPath.select(anime, "/sprites/a[f='']").size)
        // 逐文本段口径（dotnet 8.0.31 实测此文件同值 0）：`<a>` 只有 `<f>` 子元素、无直接文本
        // 子节点 ⇒ `[text()='']` 恒 0（旧 InnerText 口径把 `<a>` 的 InnerText 当空串命中 8，是
        // FN-10 第二条近似；本行随 [XmlPath] 修复改为原版的 0）。`[f='']` 走子元素字符串值不变。
        assertEquals(0, XmlPath.select(anime, "/sprites/a[text()='']").size)

        // 属性相等谓词的两种写法（ASCII 值，避免测试源码里的中文）
        assertEquals(153, XmlPath.select(kagerou, "/sprites/i").size)
        assertEquals(153, XmlPath.select(kagerou, "//i").size)
        assertEquals(1, XmlPath.select(kagerou, "/sprites/i[@param='980,720,180,270']").size)
        assertEquals(
            "AI arts/63_rectangular.webp",
            XmlPath.select(kagerou, "/sprites/i[@param='980,720,180,270']/@src").single().value,
        )
        assertEquals(27, XmlPath.select(resource, "/sprites/i[@src='EN_IMG/327/51_alt_Face.webp']").size)
        assertEquals(5, XmlPath.select(resource, "/sprites/i[@param='0,0,180,180']").size)
        assertEquals(216, XmlPath.select(as06, "/sprites/i").size)

        // ResourceManager.ERB 的 style=3 往返：元素 OuterXml 再喂回解析器，走相对路径
        val aOuter = XmlPath.select(anime, "/sprites/a[1]").single().outerXml()
        val reparsed = XmlParser.parse(aOuter)
        assertEquals("a", XmlPath.select(reparsed, "/*").single().name)
        assertEquals(121, XmlPath.select(reparsed, "*/f").size)
        assertEquals("500", XmlPath.select(reparsed, "*/@width").single().value)

        // XmlPool：真实文档进池、按名取、释放
        val pool = XmlPool()
        assertTrue(pool.create("anime", anime))
        assertTrue(pool.create("kagerou", kagerou))
        assertTrue(pool.create("as06", as06))
        assertEquals(3, pool.size)
        assertEquals(8, XmlPath.select(assertNotNull(pool["anime"]), "/sprites/*").size)
        assertEquals(153, XmlPath.select(assertNotNull(pool["kagerou"]), "/sprites/i").size)
        assertEquals(216, XmlPath.select(assertNotNull(pool["as06"]), "/sprites/i").size)
        assertTrue(pool.release("anime"))
        assertEquals(2, pool.size)
        assertNull(pool["anime"])
        assertEquals(false, pool.release("anime"))
    }

    /**
     * `XML_DOCUMENT` / `XML_EXIST` / `XML_GET` / `XML_GET_BYNAME` / `XML_TOSTR` 直接喂
     * 真实文件文本与真实片段（整文件 3 个 + 元素 OuterXml + 单行 i 片段）。
     */
    @Test
    fun `XML 内建家族喂真实语料片段`() {
        val root = corpusResourcesRoot()
        Assumptions.assumeTrue(
            root != null,
            "未设置 $CORPUS_ENV 或找不到 resources 目录，跳过真实 XML 内建调用",
        )
        val animeText = readUtf8(resourceFile(root!!, "As06MARK-IV", "anime", "anime.xml"))
        val kagerouText = readUtf8(resourceFile(root, "AI arts", "kagerou.xml"))
        val resourceText = readUtf8(resourceFile(root, "EN_IMG", "327", "Resource.xml"))
        val (ctx, store) = xmlEnv()
        val buf = refS("XBUF")

        // 1) 整文件：/sprites/* 命中 8 个 a，取第 1 个的 OuterXml 再走相对路径
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 101L, animeText))
        assertEquals(1L, xiv(ctx, "XML_EXIST", 101L))
        assertEquals(8L, xiv(ctx, "XML_GET_BYNAME", 101L, "/sprites/*"))
        assertEquals(1L, xiv(ctx, "XML_GET_BYNAME", 101L, "/sprites/a[1]", buf, 3L))
        val aOuter = strAt(store, "XBUF", 0)
        assertTrue(aOuter.startsWith("<a "), "style=3 应返回元素 OuterXml：${aOuter.take(40)}")
        assertEquals(121L, xiv(ctx, "XML_GET", aOuter, "*/f"))
        assertEquals(1L, xiv(ctx, "XML_GET", aOuter, "*/@width", buf))
        assertEquals("500", strAt(store, "XBUF", 0))

        // 2) 整文件 + 字符串键：声明被规范化为双引号（PC LoadXml 口径）
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", "KDOC", kagerouText))
        assertEquals(153L, xiv(ctx, "XML_GET_BYNAME", "KDOC", "/sprites/i"))
        assertEquals(1L, xiv(ctx, "XML_GET_BYNAME", "KDOC", "/sprites/i[last()]/@src", buf))
        assertEquals("AI arts/63_rectangular.webp", strAt(store, "XBUF", 0))
        val toString = xsv(ctx, "XML_TOSTR", "KDOC")
        assertTrue(
            toString.startsWith("<?xml version=\"1.0\" encoding=\"utf_8\"?><sprites>"),
            "XML_TOSTR 声明未按 .NET 规范化：${toString.take(80)}",
        )
        assertTrue(toString.contains("AI arts/63_rectangular.webp"))

        // 3) 整文件的属性相等谓词
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 102L, resourceText))
        assertEquals(
            27L,
            xiv(ctx, "XML_GET", 102L, "/sprites/i[@src='EN_IMG/327/51_alt_Face.webp']"),
        )

        // 4) 单元素真实片段（Resource.xml 的第一条 i 行）
        val fragment = resourceText.lineSequence().first { it.trimStart().startsWith("<i ") }.trim()
        assertEquals(1L, xiv(ctx, "XML_GET", fragment, "*/@param", buf))
        assertEquals("0,0,180,180", strAt(store, "XBUF", 0))

        // 释放后 XML_EXIST 归零
        assertEquals(1L, xiv(ctx, "XML_RELEASE", 101L))
        assertEquals(0L, xiv(ctx, "XML_EXIST", 101L))
    }
}
