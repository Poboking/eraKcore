package com.eratw.port.core.xml

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.BuiltinHost
import com.eratw.port.core.host.FileOps
import com.eratw.port.core.proc.RootedFileOps
import com.eratw.port.core.vars.VariableStore
import java.io.File
import java.io.StringReader
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node
import org.xml.sax.InputSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions

/**
 * BOM 装载对拍（M4-I i-xmlbom）：`LOADTEXT` 按原版 `File.ReadAllText(path, DetectEncoding(path))`
 * （FunctionMethodCreator.cs:8082；EncodingHandler.cs:15-41）的口径剥掉**唯一的前导** BOM，
 * 使喂给 `MAP_FROMXML`（解析层 = core/xml/XmlParser，FunctionMethodCreator.cs:3058 的
 * `XmlDocument.LoadXml`；2026-10-24 ARCH1 P3b 起原私有 MiniXml 并入 XmlParser）的文本
 * 不再以 U+FEFF 开头 —— 原版里 LoadXml 从来见不到 BOM，因为 BOM 早在读文本这一层就被
 * StreamReader 的 detectEncodingFromByteOrderMarks 消费掉了。
 *
 * 判层结论：**在读文本层（LOADTEXT）剥**；解析层不依赖剥 BOM（XmlParser 对前导 U+FEFF
 * 额外宽容——跳过一个前导符，属既登记的 TODO(verify) 残差：原版 LoadXml(string) 对字面
 * BOM 的行为无探针读数，语料与 golden 均不可达）。
 *  - `File.ReadAllText` 重载内部 StreamReader 默认 detectEncodingFromByteOrderMarks = true，
 *    只吃开头一个前导符（FunctionMethodCreator.cs:8082）；
 *  - `XmlDocument.LoadXml(string)`（:3058）作用在已剥过 BOM 的字符串上，判层口径不受
 *    解析层是否额外宽容影响 —— 本用例只钉 LOADTEXT 层。
 *
 * 幂等性：`stripLeadingBom` 只判首字符（U+FEFF）且只 substring 一次，正文中间的 U+FEFF 永不触碰；
 * 对已剥过的文本重复调用即原样返回。下面的边界用例逐条钉死这一口径。
 */

/** 只回吐固定文本的 FileOps（模拟 Files.readString(UTF_8) 把 BOM 解成首字符 U+FEFF）。 */
private class BomFileOps(private val files: Map<String, String>) : FileOps {
    override fun exists(path: String): Boolean = path in files
    override fun readText(path: String): String? = files[path]
    override fun writeText(path: String, text: String, append: Boolean): Boolean = true
    override fun list(dir: String, pattern: String, recursive: Boolean): List<String> = emptyList()
}

private class BomBase : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("BomBase 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

private fun bomCtx(ops: FileOps, overrides: Map<String, String> = emptyMap()): BuiltinEvalContext {
    val store = VariableStore()
    val base = BomBase()
    base.variables = store
    val host = object : BuiltinHost {
        override val fileOps: FileOps = ops
        override val configOverrides: Map<String, String> get() = overrides
    }
    return BuiltinEvalContext(base, store, host)
}

private fun str(v: String) = Value.StrV(v)

private fun load(ctx: BuiltinEvalContext, path: String): String =
    (ctx.callFunction("LOADTEXT", listOf(str(path))) as Value.StrV).v

private fun iv(ctx: BuiltinEvalContext, name: String, args: List<Value?>): Long =
    (ctx.callFunction(name, args) as Value.IntV).v

private const val BOM_CODE = 0xFEFF
private val BOM: Char = BOM_CODE.toChar()

/** JDK oracle 对单个 map xml 的读数：根名、直接子 <p> 总数、去重后「官方 InnerText 键」数。 */
private data class OracleStats(val root: String, val pCount: Int, val distinctEligibleKeys: Int)

class XmlBomLoadTest {

    // ---- 边界：证明只剥唯一的前导 BOM，正文中间的 U+FEFF 一律保留 ----

    @Test
    fun `无 BOM 文本原样返回`() {
        val ctx = bomCtx(BomFileOps(mapOf("a.txt" to "<map><p/></map>")))
        assertEquals("<map><p/></map>", load(ctx, "a.txt"))
    }

    @Test
    fun `单个前导 BOM 被剥掉 首字符不再是 BOM`() {
        val ctx = bomCtx(BomFileOps(mapOf("a.txt" to "$BOM<map><p/></map>")))
        val out = load(ctx, "a.txt")
        assertNotEquals(BOM, out.first())
        assertEquals("<map><p/></map>", out)
    }

    @Test
    fun `双重前导 BOM 只剥第一个 与 StreamReader 前导符消费一致`() {
        val ctx = bomCtx(BomFileOps(mapOf("a.txt" to "$BOM$BOM<map/>")))
        val out = load(ctx, "a.txt")
        // 原版 detectEncodingFromByteOrderMarks 只吃开头一个前导符，第二个落为正文首字符
        assertEquals(BOM, out.first())
        assertEquals("$BOM<map/>", out)
    }

    @Test
    fun `正文中间的 BOM 永不触碰`() {
        val inner = "<map>$BOM<p/></map>"
        val ctx = bomCtx(BomFileOps(mapOf("a.txt" to inner)))
        assertEquals(inner, load(ctx, "a.txt")) // 首字符不是 BOM ⇒ 完全不剥
    }

    @Test
    fun `剥前导后重复剥是幂等的`() {
        // 第一次 LOADTEXT 已剥掉前导 BOM；把结果再喂一次（写盘→读回）仍只剥一个、正文不动。
        val ctx = bomCtx(BomFileOps(mapOf("once.txt" to "$BOM<map><p><k>x</k><v>1</v></p></map>")))
        val first = load(ctx, "once.txt")
        assertEquals("<map><p><k>x</k><v>1</v></p></map>", first)
        val ctx2 = bomCtx(BomFileOps(mapOf("twice.txt" to first)))
        assertEquals(first, load(ctx2, "twice.txt")) // 已剥过的文本再进来原样返回
    }

    // ---- 真实语料：plugins 下每个 xml 逐个对拍（吃 ERATW_GAMEDIR）----

    @Test
    fun `语料 plugins 下每个 xml LOADTEXT 无 BOM 且 MAP_FROMXML 条目数等于 p 节点数`() {
        // 语料游戏根：优先 ERATW_GAMEDIR，缺省回落到闸门里一定导出的 ERATW_CORPUS（同指游戏根）。
        val gamedir = System.getenv("ERATW_GAMEDIR")?.takeIf { it.isNotBlank() }
            ?: System.getenv("ERATW_CORPUS")?.takeIf { it.isNotBlank() }
        Assumptions.assumeTrue(gamedir != null, "缺 ERATW_GAMEDIR/ERATW_CORPUS：跳过语料对拍")
        val plugins = Paths.get(gamedir!!, "plugins")
        Assumptions.assumeTrue(plugins.toFile().isDirectory, "无 plugins 目录：跳过语料对拍")

        val xmls = plugins.toFile().listFiles { f -> f.isFile && f.name.endsWith(".xml", true) }
            ?.sortedBy { it.name } ?: emptyList()
        Assumptions.assumeTrue(xmls.isNotEmpty(), "plugins 下无 xml：跳过语料对拍")

        // 语料根目录挂成 RootedFileOps（真文件走 Files.readString(UTF_8)，BOM 会留在文本里）
        val ops = RootedFileOps(plugins) // root=plugins，相对路径即文件名
        val overrides = mapOf("VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT" to "xml,txt")
        val ctx = bomCtx(ops, overrides)

        for (f in xmls) {
            val text = load(ctx, f.name)
            // 断言 1：LOADTEXT 结果首字符不是 BOM（原文件若带 BOM，这条才真正证明读文本层剥了它）
            assertTrue(text.isNotEmpty(), "${f.name}：LOADTEXT 返回空")
            assertNotEquals(BOM, text.first(), "${f.name}：LOADTEXT 结果仍以 BOM 开头")

            // 断言 2：MAP_FROMXML 真解析（禁止用「解析失败返回空 map」伪装）。仅对根元素为 map 的文件
            // 走端到端：MAP_FROMXML 只把 /map/p 里「恰好一个 k + 一个 v」的项按
            // `dictionary[k.InnerText] = v.InnerXml` 写进 map（FunctionMethodCreator.cs:3068-3072，
            // :3070 的守卫只有元素数、不看键内容，:3072 无条件插入）——重复键 last-wins，
            // 纯空白文本节点已被 LoadXml（PreserveWhitespace=false）丢弃 ⇒ 全部空白键变体并成 ""
            // 一键。⇒ 条目数 = 去重后的**官方 InnerText 键**数（不是裸 <p> 数：语料 tw_csv 有
            // 28 个、tw_taste 有 30 个重复键；各另有 1 个 `<k>　</k>` 形态的纯空白 k，官方读数
            // tw_csv=4371 / tw_taste=2254）。非 map 根原版直接返回 1 不写入（:383 复刻），只保留断言 1。
            val oracle = oracleMapStats(f)
            if (oracle.root != "map") continue
            iv(ctx, "MAP_CREATE", listOf(str("CSV_MAP")))
            val rc = iv(ctx, "MAP_FROMXML", listOf(str("CSV_MAP"), str(text)))
            assertEquals(1L, rc, "${f.name}：MAP_FROMXML 未返回 1（解析失败？）")
            val entries = iv(ctx, "MAP_SIZE", listOf(str("CSV_MAP")))
            // 非空且结构匹配：证明不是「解析失败静默返回空 map」的伪装
            assertTrue(entries > 0L, "${f.name}：MAP_FROMXML 后条目为 0，疑似解析被吞")
            assertEquals(
                oracle.distinctEligibleKeys.toLong(),
                entries,
                "${f.name}：MAP_FROMXML 条目数 != 官方 InnerText 口径去重后的 <k> 键数（<p> 总数 ${oracle.pCount}）",
            )
            iv(ctx, "MAP_RELEASE", listOf(str("CSV_MAP")))
        }
    }

    /**
     * JDK oracle：按**字符流**解析（组长收口修正）。
     *
     * 直接喂字节会让 DOM 尊重 prolog 的 `encoding` 声明，而语料 `plugins/schema.xml` 是
     * 「UTF-8 with BOM 的字节 + prolog 写 `encoding="utf-16"`」的自相矛盾文件，DOM 在 line 1 col 40
     * 抛「前言中不允许有内容」——这是 oracle 的口径错，不是被测码错。
     * 原版走 `XmlDocument.LoadXml(strValue2)`（FunctionMethodCreator.cs:3058），喂的是 LOADTEXT
     * 已剥 BOM 的**字符串**；XML 规范对已解码的字符实体忽略声明编码，.NET 亦如此。
     * 故这里剥前导 BOM、按 UTF-8 解码后用 StringReader 解析，与原版同口径。
     */
    private fun oracleMapStats(f: File): OracleStats {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val raw = f.readText()
        val source = if (raw.isNotEmpty() && raw[0] == BOM) raw.substring(1) else raw
        val doc = runCatching { builder.parse(InputSource(StringReader(source))) }
            .getOrElse { return OracleStats("", 0, 0) }
        val root = doc.documentElement ?: return OracleStats("", 0, 0)
        val ps = ArrayList<Node>()
        var n = root.firstChild
        while (n != null) {
            if (n.nodeType == Node.ELEMENT_NODE && n.nodeName == "p") ps.add(n)
            n = n.nextSibling
        }
        val keys = HashSet<String>()
        for (p in ps) {
            val kids = ArrayList<Node>()
            var m = p.firstChild
            while (m != null) {
                if (m.nodeType == Node.ELEMENT_NODE) kids.add(m)
                m = m.nextSibling
            }
            val ks = kids.filter { it.nodeName == "k" }
            val vs = kids.filter { it.nodeName == "v" }
            // 键口径 = 官方 :3072 的 `dictionary[InnerText]`，InnerText 又建立在 LoadXml
            // （PreserveWhitespace=false）的 DOM 上——用 netInnerText，不能用 textContent
            // （后者保留纯空白文本节点，会把官方并成 "" 一键的空白键变体错算成多个键）。
            if (ks.size == 1 && vs.size == 1) keys.add(netInnerText(ks[0]))
        }
        return OracleStats(root.tagName, ps.size, keys.size)
    }

    /**
     * .NET `XmlNode.InnerText`（`FunctionMethodCreator.cs:3072` 取的属性）建立在 LoadXml
     * 缺省 `PreserveWhitespace=false` 的 DOM 上：逐文本/CDATA 后代拼接，**纯空白文本节点**
     * 在解析层已被丢弃不计，注释与 PI 不计入（.NET InnerText 的拼接范围）。
     *
     * 2026-10-24 闸红裁决：旧实现用 JDK `textContent`（保留纯空白），与官方判据在
     * `<k> </k>` 形态的 k 上分叉——官方 `dictionary[InnerText] = InnerXml` 不看键内容
     * （:3070 的守卫只有「恰一 k 恰一 v」的**元素数**，:3072 无条件插入），空白键变体经
     * 字典 last-wins 全部并成 "" 一键；实测语料各有 1 个纯空白 k，官方读数
     * tw_csv_chs=4371（旧判据 4372）、tw_taste_chs=2254（旧判据 2255）。本函数与移植侧
     * XmlElement.innerText + 解析层丢空白是同一式，判据对全部语料通用。
     */
    private fun netInnerText(node: Node): String {
        val sb = StringBuilder()
        var c = node.firstChild
        while (c != null) {
            when (c.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    val t = c.nodeValue ?: ""
                    if (!isXmlWhitespaceOnly(t)) sb.append(t)
                }
                Node.ELEMENT_NODE -> sb.append(netInnerText(c))
            }
            c = c.nextSibling
        }
        return sb.toString()
    }

    /** XML 1.0 空白（space/tab/CR/LF）判据；U+3000 / NBSP 在 XML 语境不是空白，不能丢（与 XmlParser 同口径）。 */
    private fun isXmlWhitespaceOnly(s: String): Boolean =
        s.all { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
}
