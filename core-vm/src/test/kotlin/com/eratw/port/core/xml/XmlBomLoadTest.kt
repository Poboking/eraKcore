package com.eratw.port.core.xml

import com.eratw.port.boot.CorpusProfile
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
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Node
import org.xml.sax.InputSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * BOM 装载对拍（M4-I i-xmlbom）：`LOADTEXT` 按原版 `File.ReadAllText(path, DetectEncoding(path))`
 * （FunctionMethodCreator.cs:8082；EncodingHandler.cs:15-41）的口径剥掉**唯一的前导** BOM，
 * 使喂给 `MAP_FROMXML`（解析层 = core/xml/XmlParser，FunctionMethodCreator.cs:3058 的
 * `XmlDocument.LoadXml`；2026-10-24 ARCH1 P3b 起原私有 MiniXml 并入 XmlParser）的文本
 * 不再以 U+FEFF 开头 —— 原版里 LoadXml 从来见不到 BOM，因为 BOM 早在读文本这一层就被
 * StreamReader 的 detectEncodingFromByteOrderMarks 消费掉了。
 *
 * 判层结论：**在读文本层（LOADTEXT）剥**；解析层不依赖剥 BOM（XmlParser 对前导 U+FEFF
 * 额外宽容——跳过一个前导符）。该宽容已定为由组长/用户裁决的**已知有意分歧**
 * （`C-XML-DIVERGENCE@2` 方向乙，OD-1 2026-11-02）：dotnet 8.0.425 实测
 * `LoadXml("\uFEFF<root/>")` 抛 XmlException「Data at the root level is invalid. Line 1, position 1.」，
 * 移植剥掉继续解析;真实链路里 BOM 早在 LOADTEXT 的读文本层被消费 ⇒ 解析层分歧**不可达**，
 * 按 ADR-005「不可达即不擅改」保留现状（`XmlParser.parseDocument` 的剥除行不删）。
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

/** UTF-8 BOM 的落字节（`EF BB BF`）：把「这件带 BOM」从注释口径变成实读断言。 */
private val UTF8_BOM_BYTES = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

/** 文件头三字节的十六进制（判红证据用；短文件按实际长度打印）。 */
private fun bomHex(f: File): String =
    f.inputStream().use { it.readNBytes(3) }.joinToString(" ") { String.format("%02X", it.toInt() and 0xFF) }

/** 实读文件字节是否以 UTF-8 BOM 开头（不是靠解码后的首字符推断）。 */
private fun startsWithUtf8Bom(f: File): Boolean =
    f.inputStream().use { it.readNBytes(3) }.contentEquals(UTF8_BOM_BYTES)

/**
 * `plugins` 下 xml 清单的**按根两列**（bomskip 波 2026-11-06 两根逐件实读）。
 *
 * 口径（与 [com.eratw.port.boot.CorpusProfile] 相同）：列是**具体清单**，不是「两根都放过」——
 * 多一件、少一件、带 BOM 的件少一处、map 根件换了名，任一条都判红。
 *
 * 读数出处（语料只读；4.980 = `eratw-画蛇添足版eratw4.980`、
 * 4.981 = `eraTW4.981-261017-EAGLOV3`）：
 * - `plugins` 下 .xml 文件：两根同值 4 件（`bbas_dataset.xml` / `schema.xml` /
 *   `tw_csv_chs.xml` / `tw_taste_chs.xml`；4.981 另有 `tw_csv_chs.xml.bak`、
 *   `tw_taste_chs.xml.bak` 两件备份，扩展名不是 .xml ⇒ 不进清单）；
 * - 头三字节带 `EF BB BF` 的：两根同值 3 件（`schema.xml` / `tw_csv_chs.xml` /
 *   `tw_taste_chs.xml`；`bbas_dataset.xml` 以 `<` 0x3C 开头，不带 BOM，是本测试里
 *   「不带 BOM 原样透传」的对照组）；
 * - 根元素为 `map`（走 `/map/p` 条目对拍）的：两根同值 2 件；
 *   非 map 的 2 件是 `bbas_dataset.xml`（根 `DocumentElement`）与
 *   `schema.xml`（根 `xs:schema`）。
 */
private class PluginsXmlColumn(
    /** `plugins` 下 .xml 文件名的全量（升序）。 */
    val files: List<String>,
    /** 实读带 UTF-8 BOM 的件 —— 只有这些才真正走到「剥 BOM」这条路径。 */
    val bomFiles: List<String>,
    /** 根元素为 map 的件（走 MAP_FROMXML 条目数对拍）。 */
    val mapRootFiles: List<String>,
) {
    /** 根元素非 map 的件（走「rc=1 且条目 0」判据）。 */
    val nonMapRootFiles: List<String> get() = files.filterNot { it in mapRootFiles }
}

/** 按根取列（穷尽枚举：新增语料版本必须在此补一列，否则编译期就红）。 */
private fun pluginsXmlColumn(profile: CorpusProfile): PluginsXmlColumn = when (profile) {
    // 两列在 plugins 这三项上同值，但仍是各自实读的一列；任一根上清单漂移都从本行改起。
    CorpusProfile.V4_980 -> PluginsXmlColumn(
        files = listOf("bbas_dataset.xml", "schema.xml", "tw_csv_chs.xml", "tw_taste_chs.xml"),
        bomFiles = listOf("schema.xml", "tw_csv_chs.xml", "tw_taste_chs.xml"),
        mapRootFiles = listOf("tw_csv_chs.xml", "tw_taste_chs.xml"),
    )
    CorpusProfile.V4_981 -> PluginsXmlColumn(
        files = listOf("bbas_dataset.xml", "schema.xml", "tw_csv_chs.xml", "tw_taste_chs.xml"),
        bomFiles = listOf("schema.xml", "tw_csv_chs.xml", "tw_taste_chs.xml"),
        mapRootFiles = listOf("tw_csv_chs.xml", "tw_taste_chs.xml"),
    )
}

/** 判红时的证据：已认到的根与版本档、找过的目录、目录里实际有什么。 */
private fun pluginsEvidence(facts: XmlCorpusFacts, plugins: Path): String {
    val listing = runCatching {
        plugins.toFile().listFiles()?.sortedBy { it.name }
            ?.joinToString { if (it.isDirectory) it.name + "/" else it.name }
    }.getOrElse { "<列不出：${it.message}>" }
    return "已认到的根 = ${facts.root}（版本档 ${facts.profile}，NOW_VERSION=${facts.profile.nowVersion}）；" +
        "找过的目录 = $plugins；目录内容 = $listing"
}

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

    /**
     * 语料对拍（按根取列，2026-11-06 bomskip 波把最后三枚 `Assumptions.assumeTrue` 拆成判红）。
     *
     * 旧实现的三枚 assume 各守一件事，现在依次改成**判红 + 打印证据**，口径与
     * `core/config/ConfigChainTest`、同族 `xml/RealCorpusXmlTest`、`xml/XmlFamilyAuditTest` 一致：
     * 1. 环境变量没设 ⇒ 交给 [XmlCorpusFacts.detect]（只认 `ERATW_GAMEDIR`，未设即 AssertionError）。
     *    旧实现还回落到 `ERATW_CORPUS`，而闸配方（AGENTS.md §5）把该变量指到 `ERB` 子树，
     *    回落分支取到的 `ERB/plugins` 必然不存在 —— 这条回落本身就是假的第二入口，已删。
     * 2. 无 `plugins` 目录 / 目录不是语料根 ⇒ [XmlCorpusFacts.detect] 抛（缺三份必读 xml 即判红）。
     * 3. `plugins` 下没 xml ⇒ 下面的**按根件名清单**断言判红（清单是两列具体定值）。
     *
     * 「带 BOM 的那几件」不再是注释里的口径：[UTF8_BOM_BYTES] 实读每个文件的头三字节，
     * 与按根列的 [PluginsXmlColumn.bomFiles] 逐件对，缺一处就红 —— 否则断言 1 里
     * 「首字符不是 BOM」在整目录都不带 BOM 时会空转变绿。
     *
     * 非 map 根的件也不再 `continue` 静默放过：官方 `SelectNodes("/map/p")`
     * （FunctionMethodCreator.cs:3060）对非 map 根命中 0 条 ⇒ 返回 1 且一个条目都不写
     * （:3065-3076 的循环体不执行），故这两件按「rc=1 且 MAP_SIZE=0」钉死，
     * 并按根列核对其根元素名册（4.980 / 4.981 均为 `bbas_dataset.xml` 的 `DocumentElement`
     * 与 `schema.xml` 的 `xs:schema`）。
     */
    @Test
    fun `语料 plugins 下每个 xml LOADTEXT 无 BOM 且 MAP_FROMXML 条目数等于 p 节点数`() {
        // 取根 + 取列：detect() 自带「未设变量 / 不是语料根 / 缺 plugins 下三份 xml」三判红。
        val facts = XmlCorpusFacts.detect()
        val column = pluginsXmlColumn(facts.profile)
        val plugins = facts.root.resolve("plugins")
        if (!Files.isDirectory(plugins)) {
            throw AssertionError("$plugins 不是目录：${pluginsEvidence(facts, plugins)}")
        }

        val xmls = plugins.toFile().listFiles { f -> f.isFile && f.name.endsWith(".xml", true) }
            ?.sortedBy { it.name }
            ?: throw AssertionError("列不出 $plugins 下的文件（listFiles 返回 null）：${pluginsEvidence(facts, plugins)}")
        assertEquals(
            column.files,
            xmls.map { it.name },
            "plugins 下 xml 清单与 ${facts.profile} 一列不符（多一件少一件都判红）：${pluginsEvidence(facts, plugins)}",
        )
        for (f in xmls) {
            val hasBom = startsWithUtf8Bom(f)
            assertEquals(
                f.name in column.bomFiles,
                hasBom,
                "${f.name} 头三字节 = ${bomHex(f)}，与 ${facts.profile} 列的带 BOM 名录 " +
                    "${column.bomFiles} 不符：${pluginsEvidence(facts, plugins)}",
            )
        }

        // 语料根目录挂成 RootedFileOps（root=plugins，相对路径即文件名）。真文件走
        // `RootedFileOps.readText` ⇒ `EncodingHandler` 三段回退解码（BootHosts.kt:258-282），
        // UTF-8 BOM 在解码步被消费；LOADTEXT 层再按 `stripLeadingBom` 兜一次（HostMethods.kt:292）。
        val ops = RootedFileOps(plugins)
        val overrides = mapOf("VALID EXTENSIONS FOR LOADTEXT AND SAVETEXT" to "xml,txt")
        val ctx = bomCtx(ops, overrides)

        val mapRoots = ArrayList<String>()
        val nonMapRoots = ArrayList<String>()
        for (f in xmls) {
            val text = load(ctx, f.name)
            // 断言 1（端到端）：原文件实读带 BOM 的那几件里，LOADTEXT 结果首字符不得是 BOM。
            assertTrue(text.isNotEmpty(), "${f.name}：LOADTEXT 返回空（文件 ${f.length()} 字节）：${pluginsEvidence(facts, plugins)}")
            assertNotEquals(BOM, text.first(), "${f.name}：LOADTEXT 结果仍以 BOM 开头：${pluginsEvidence(facts, plugins)}")

            // 断言 2：MAP_FROMXML 真解析（禁止用「解析失败返回空 map」伪装）。map 根走端到端：
            // MAP_FROMXML 只把 /map/p 里「恰好一个 k + 一个 v」的项按
            // `dictionary[k.InnerText] = v.InnerXml` 写进 map（FunctionMethodCreator.cs:3068-3072，
            // :3070 的守卫只有元素数、不看键内容，:3072 无条件插入）——重复键 last-wins，
            // 纯空白文本节点已被 LoadXml（PreserveWhitespace=false）丢弃 ⇒ 全部空白键变体并成 ""
            // 一键。⇒ 条目数 = 去重后的**官方 InnerText 键**数（不是裸 <p> 数：语料 tw_csv 有
            // 28 个、tw_taste 有 30 个重复键；各另有 1 个 `<k>　</k>` 形态的纯空白 k，官方读数
            // tw_csv=4371 / tw_taste=2254）。
            iv(ctx, "MAP_CREATE", listOf(str("CSV_MAP")))
            val rc = iv(ctx, "MAP_FROMXML", listOf(str("CSV_MAP"), str(text)))
            assertEquals(1L, rc, "${f.name}：MAP_FROMXML 未返回 1（解析失败？）")
            val entries = iv(ctx, "MAP_SIZE", listOf(str("CSV_MAP")))
            val oracle = oracleMapStats(f)
            if (oracle.root == "map") {
                // 非空且结构匹配：证明不是「解析失败静默返回空 map」的伪装
                assertTrue(entries > 0L, "${f.name}：MAP_FROMXML 后条目为 0，疑似解析被吞")
                assertEquals(
                    oracle.distinctEligibleKeys.toLong(),
                    entries,
                    "${f.name}：MAP_FROMXML 条目数 != 官方 InnerText 口径去重后的 <k> 键数（<p> 总数 ${oracle.pCount}）",
                )
                mapRoots += f.name
            } else {
                // 非 map 根：官方 SelectNodes("/map/p")（:3060）命中 0 条 ⇒ 返回 1、一条都不写。
                assertTrue(
                    f.name in column.nonMapRootFiles,
                    "${f.name}：JDK oracle 读到的根元素是 <${oracle.root}>（非 map），但该件不在 " +
                        "${facts.profile} 一列的非 map 根名册 ${column.nonMapRootFiles} 里：${pluginsEvidence(facts, plugins)}",
                )
                assertEquals(
                    0L,
                    entries,
                    "${f.name}：根元素 <${oracle.root}> 非 map，官方 /map/p 命中 0 条 ⇒ 不应写入任何条目",
                )
                nonMapRoots += f.name
            }
            iv(ctx, "MAP_RELEASE", listOf(str("CSV_MAP")))
        }
        assertEquals(column.mapRootFiles, mapRoots, "${facts.profile} 一列的 map 根件未全部走到条目对拍")
        assertEquals(column.nonMapRootFiles, nonMapRoots, "${facts.profile} 一列的非 map 根件未全部走到 rc/条目判据")
        println(
            "XmlBomLoadTest 语料对拍（${facts.profile}）：根 ${facts.root}、plugins 下 ${xmls.size} 个 xml" +
                "（实读带 BOM ${column.bomFiles.size} 个：${column.bomFiles}）、map 根 $mapRoots、非 map 根 $nonMapRoots",
        )
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
     *
     * 解析失败**判红**（bomskip 波 2026-11-06）：旧实现是 `runCatching { … }
     * .getOrElse { return OracleStats("", 0, 0) }`，空根名随后撞上调用点的
     * `if (oracle.root != "map") continue` ⇒ oracle 自己坏了也会被当成「非 map 根」静默放过。
     * 现在解析失败 / 无根元素一律 AssertionError，非 map 根由件名册按根核对。
     */
    private fun oracleMapStats(f: File): OracleStats {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val raw = f.readText()
        val source = if (raw.isNotEmpty() && raw[0] == BOM) raw.substring(1) else raw
        val doc = try {
            builder.parse(InputSource(StringReader(source)))
        } catch (e: Exception) {
            throw AssertionError("${f.name}：JDK oracle 解析失败（不是被测码的问题）：${e.message}", e)
        }
        val root = doc.documentElement
            ?: throw AssertionError("${f.name}：JDK oracle 解析后没有根元素")
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
