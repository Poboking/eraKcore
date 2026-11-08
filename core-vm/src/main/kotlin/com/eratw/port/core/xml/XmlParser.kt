package com.eratw.port.core.xml

/**
 * 极简 XML 解析器（够用即可：元素 / 属性 / 文本 / CDATA / 注释 / PI / 预定义实体 / 字符引用）。
 *
 * 与 .NET `XmlDocument.LoadXml` 对齐的解析口径（load 走 `XmlTextReader`，其
 * `Normalization` 默认 false，故**不按 XML 规范**做行尾/属性空白归一；dotnet 8 实测）：
 * - 文本与属性值里的原始 `\r`/`\n`/`\t` 原样保留；字符引用 `&#13;`/`&#10;` 也原样解码。
 * - `PreserveWhitespace = false`：元素间纯空白文本节点**丢弃**（`LoadXml` 默认），
 *   判据是 XML 空白的四种字符（space/tab/CR/LF），见 [isWhitespaceOnly]。
 *   例外：[parseFragment]（`InnerXml` 赋值）不丢任何非空文本，含纯空白，dotnet 实测。
 * - XML 声明按 .NET `XmlDeclaration.OuterXml` 规范化：双引号、单空格、
 *   version → encoding → standalone 顺序（语料声明用单引号，直接回放会与 PC 不一致）。
 * - 文档必须恰好一个根元素；根元素后的空白/注释/PI 允许。
 * - 重复属性名、标签不配对、未定义实体 → [XmlParseException]。
 *
 * 不支持（无基准覆盖）：DTD / 内部实体定义 / 命名空间前缀校验 / `xml:space`。
 *
 * 双消费面（2026-10-24 ARCH1 P3b 收口后本解析器是 core 内**唯一**的 XML 解析实现，
 * 原 fn 侧私有迷你解析器 XNode 与 MiniXml 已删）：
 * - XML_* 家族（fn/XmlMethods.kt）与 MAP_FROMXML 走 [parse] 默认模式，
 *   对应 .NET `XmlDocument.LoadXml`（MAP_FROMXML 原文 ：3058）；
 * - DT_FROMXML / 存档 EM 装载（fn/MapDataTableMethods.kt）走
 *   [parse] 的 `preserveWhitespace = true` 模式，对应 .NET `DataTable.ReadXmlSchema` /
 *   `ReadXml` 的 XmlReader 口径——那两条不经过 XmlDocument，元素内容里的纯空白
 *   由 `XmlReader.ReadString` 拼回值里（PC 真档的 DT 字符串单元格可能存纯空白，
 *   不能在解析层吃掉），两模式的取舍见 [parse]。
 */
class XmlParseException(message: String) : Exception(message)

object XmlParser {

    /**
     * 元素嵌套深度上限。原版 `XmlDocument.LoadXml` 同样递归解析，超深文档在 .NET 里是栈溢出
     * 而非 XmlException；本移植必须在 [XmlParseException] 处设闸，否则十万层 `<a><a>...`
     * 会直接抛 StackOverflowError，绕开 XmlMethods 里的 CodeEE 转换
     * （`FunctionMethodCreator.cs:1699-1706` 只捕获 XmlException）。
     *
     * 可见性 internal：XML_ADDNODE / XML_SET(style=2) / XML_REPLACE 的插入路径要用同一个
     * 上限做「整文档深度」闸（解析器只约束单次解析，插入能让总深度叠加超过它）。
     */
    internal const val MAX_ELEMENT_DEPTH = 512

    /**
     * 解析完整文档（等价 `XmlDocument.LoadXml`）。
     *
     * [preserveWhitespace] 的两档口径（默认 false，保持 XML_* / MAP_FROMXML 的既有行为）：
     * - false = .NET `LoadXml` 默认的 `PreserveWhitespace = false`：纯空白文本节点丢弃；
     * - true = `DataTable.ReadXmlSchema` / `ReadXml` 的 XmlReader 口径：元素内容原样报告，
     *   纯空白文本节点保留 —— DT 的字符串单元格可能就是空白（PC 真档 `WriteXml` 会把
     *   " " 写成 `<NAME> </NAME>`），装载回读不能吃掉它。两档只差这一处：其余
     *   （实体、属性、结构错误分类）完全共享。
     */
    fun parse(text: String): XmlDocument = parse(text, preserveWhitespace = false)

    /** [parse] 的显式两档形式（口径见上）。 */
    fun parse(text: String, preserveWhitespace: Boolean): XmlDocument =
        Cursor(text, preserveWhitespace).parseDocument()

    /**
     * 解析内容片段（等价给 `InnerXml` 赋值）：允许文本 + 多个元素混合，允许空。
     * 返回的节点尚未挂到任何父节点。
     *
     * 纯空白片段建 [XmlWhitespace]（`#whitespace`）节点、混合内容里的非空白文本建
     * [XmlText]（`#text`）——dotnet 8.0.425 实测（XML-FIVE X4 翻案）：
     * `e.InnerXml = "  "` 后唯一子节点 Name=`#whitespace`（NodeType=Whitespace），
     * `e.InnerXml = " a "` 后是普通 `#text`。注意与 `InnerText` 赋值相反：
     * `InnerText` 赋值（含纯空白值）**一律**建 `#text`（同轮探针实测），
     * 见 fn/XmlMethods.kt `setXmlNode` style=1。
     */
    fun parseFragment(text: String): List<XmlNode> = Cursor(text, preserveWhitespace = false).parseContentFragment()

    private class Cursor(private val s: String, private val preserveWhitespace: Boolean) {
        private var i = 0

        /**
         * 解析完整文档（等价 `XmlDocument.LoadXml`）。
         *
         * 串首 `U+FEFF` 剥除是**已知有意分歧**（契约 C-XML-DIVERGENCE@2 方向乙，OD-1 2026-11-02）：
         * dotnet 8.0.425 实测 `new XmlDocument().LoadXml("\uFEFF<root/>")` 直接抛 XmlException
         * 「Data at the root level is invalid. Line 1, position 1.」（原版 FunctionMethodCreator.cs:181-184
         * 把 XmlException 转 CodeEE）；本实现剥掉前导符继续解析。真实链路 `LOADTEXT` 走
         * `File.ReadAllText`（FunctionMethodCreator.cs:8082）时 BOM 已在读文本层被 StreamReader 消费，
         * 解析层见不到前导符 ⇒ **不可达**。按 ADR-005「不可达即不擅改」保留现状（判层结论另见
         * `XmlBomLoadTest` 的 KDoc）。**不删这一行**。
         */
        fun parseDocument(): XmlDocument {
            val doc = XmlDocument()
            if (i < s.length && s[i] == '\uFEFF') i++
            if (s.startsWith("<?xml", i) && i + 5 < s.length && isWhitespace(s[i + 5])) {
                val end = s.indexOf("?>", i)
                if (end < 0) fail("XML 声明未闭合")
                doc.declaration = normalizeDeclaration(s.substring(i, end + 2))
                i = end + 2
            }
            while (true) {
                skipWhitespace()
                if (i >= s.length) break
                // 根前/根后的「散位」裸非法字符（探针 R1t/R1u：0x0C 领先、0x01 落根后，
                // .NET 同样抛 invalid character）——各 token 读取器只覆盖自己的片段，
                // 走不到读取器的散位在这里统一拦。
                if (isInvalidXmlLiteralChar(s[i])) failInvalidChar(i)
                when {
                    s.startsWith("<!--", i) -> doc.appendChild(parseComment())
                    s.startsWith("<?", i) -> doc.appendChild(parseProcessingInstruction())
                    s.startsWith("<!", i) -> fail("不支持的文档级标记")
                    s.startsWith("</", i) -> fail("意外的结束标签")
                    else -> {
                        if (doc.root != null) fail("文档级出现第二个根元素")
                        doc.appendChild(parseElement(0))
                    }
                }
            }
            if (doc.root == null) fail("缺少根元素")
            return doc
        }

        fun parseContentFragment(): List<XmlNode> {
            val out = ArrayList<XmlNode>()
            while (true) {
                if (i >= s.length) break
                when {
                    s.startsWith("<!--", i) -> out.add(parseComment())
                    s.startsWith("<![CDATA[", i) -> out.add(parseCData())
                    s.startsWith("<?", i) -> out.add(parseProcessingInstruction())
                    s.startsWith("<!", i) -> fail("不支持的标记")
                    s.startsWith("</", i) -> fail("片段里出现意外的结束标签")
                    s[i] == '<' -> out.add(parseElement(0))
                    else -> {
                        val t = readText()
                        // 片段（等价 `InnerXml` 赋值）**不**做 PreserveWhitespace=false 的纯空白丢弃：
                        // dotnet 8.0.421 实测 `e.InnerXml = "  "` 后 OuterXml 是 `<r>  </r>`，
                        // 只有空片段（长度为 0）才不建文本节点。
                        // 这里与元素内容路径（parseElement，按 isWhitespaceOnly 丢纯空白）刻意不同：
                        // 丢弃判据只适用于 `LoadXml` 解析整篇文档，不适用于 InnerXml 赋值。
                        // isWhitespaceOnly 收紧的是「哪些字符算空白」（U+3000 / NBSP 在 XML 里不是空白），
                        // 本行连纯空格一起保留，已是其超集，两条口径在此不打架。
                        // 节点类型按 XML-FIVE X4 的 dotnet 8.0.425 实测：纯空白片段建 #whitespace
                        // （XmlWhitespace），混合内容里的非空白文本建 #text。
                        if (t.isNotEmpty()) {
                            out.add(if (isWhitespaceOnly(t)) XmlWhitespace(t) else XmlText(t))
                        }
                    }
                }
            }
            return out
        }

        /**
         * 解析一个元素。`depth` 是本元素的嵌套层数（根 = 0），超过 [MAX_ELEMENT_DEPTH]
         * 抛 [XmlParseException]（原因见该常量）。
         */
        private fun parseElement(depth: Int): XmlElement {
            if (depth > MAX_ELEMENT_DEPTH) fail("元素嵌套过深")
            expect('<')
            val tag = readName()
            val el = XmlElement(tag)
            while (true) {
                val hadWs = skipWhitespace()
                if (i >= s.length) fail("元素 $tag 的起始标签未闭合")
                when {
                    s.startsWith("/>", i) -> {
                        i += 2
                        return el
                    }
                    s[i] == '>' -> {
                        // 有结束标签的形态（哪怕内容为空）⇒ 不是自闭合元素：`.NET` 的
                        // XmlElement.IsEmpty 保持 false，OuterXml 收尾写 `<a></a>`
                        el.isEmptyElement = false
                        i++
                        break
                    }
                    !hadWs -> fail("元素 $tag 的属性之间缺少空白")
                    else -> {
                        val attrName = readName()
                        skipWhitespace()
                        expect('=')
                        skipWhitespace()
                        val attrValue = readAttributeValue()
                        if (el.attribute(attrName) != null) fail("元素 $tag 出现重复属性 $attrName")
                        el.addAttribute(XmlAttribute(attrName, attrValue))
                    }
                }
            }
            // 内容
            while (true) {
                if (i >= s.length) fail("元素 $tag 未闭合")
                when {
                    s.startsWith("</", i) -> {
                        i += 2
                        val close = readName()
                        skipWhitespace()
                        expect('>')
                        if (close != tag) fail("结束标签 $close 与起始标签 $tag 不匹配")
                        return el
                    }
                    s.startsWith("<!--", i) -> el.appendChild(parseComment())
                    s.startsWith("<![CDATA[", i) -> el.appendChild(parseCData())
                    s.startsWith("<?", i) -> el.appendChild(parseProcessingInstruction())
                    s.startsWith("<!", i) -> fail("不支持的标记")
                    s[i] == '<' -> el.appendChild(parseElement(depth + 1))
                    else -> {
                        val t = readText()
                        // PreserveWhitespace=false：只丢 XML 空白（space/tab/CR/LF）构成的文本节点；
                        // preserveWhitespace=true（DataTable.ReadXml 口径，见 [parse]）原样保留。
                        if (preserveWhitespace || !isWhitespaceOnly(t)) el.appendChild(XmlText(t))
                    }
                }
            }
        }

        private fun parseComment(): XmlComment {
            i += 4 // <!--
            val end = s.indexOf("-->", i)
            if (end < 0) fail("注释未闭合")
            scanLiteralChars(i, end) // 探针 R1c：注释正文里的裸控制符 .NET 同抛
            val data = s.substring(i, end)
            i = end + 3
            return XmlComment(data)
        }

        private fun parseCData(): XmlCData {
            i += 9 // <![CDATA[
            val end = s.indexOf("]]>", i)
            if (end < 0) fail("CDATA 未闭合")
            scanLiteralChars(i, end) // 探针 R1d
            val data = s.substring(i, end)
            i = end + 3
            return XmlCData(data)
        }

        private fun parseProcessingInstruction(): XmlProcessingInstruction {
            i += 2 // <?
            val target = readName()
            val end = s.indexOf("?>", i)
            if (end < 0) fail("处理指令未闭合")
            scanLiteralChars(i, end) // 探针 R1e
            var data = s.substring(i, end)
            i = end + 2
            // .NET 把 target 与数据之间的**全部**空白吃掉、数据尾部空白保留：
            // dotnet 8.0.421 实测 `<?pi   x  y ?>` → Data = "x  y "，`<?pi\tx?>` → "x"。
            var k = 0
            while (k < data.length && isWhitespace(data[k])) k++
            if (k > 0) data = data.substring(k)
            return XmlProcessingInstruction(target, data)
        }

        /** 读到下一个 `<` 为止；只做实体解码（.NET load 不做行尾归一）。 */
        private fun readText(): String {
            val start = i
            while (i < s.length && s[i] != '<') {
                if (isInvalidXmlLiteralChar(s[i])) failInvalidChar(i) // 探针 R1a
                i++
            }
            return decode(s.substring(start, i))
        }

        /** 属性值只做实体解码；原始空白原样保留（.NET `XmlTextReader.Normalization=false`）。 */
        private fun readAttributeValue(): String {
            if (i >= s.length) fail("属性值缺失")
            val quote = s[i]
            if (quote != '"' && quote != '\'') fail("属性值必须用引号包围")
            i++
            val start = i
            while (i < s.length && s[i] != quote) {
                if (s[i] == '<') fail("属性值里不能出现 <")
                if (isInvalidXmlLiteralChar(s[i])) failInvalidChar(i) // 探针 R1b/R1s
                i++
            }
            if (i >= s.length) fail("属性值未闭合")
            val raw = s.substring(start, i)
            i++
            return decode(raw)
        }

        private fun readName(): String {
            if (i < s.length && isInvalidXmlLiteralChar(s[i])) {
                // 名字位的裸非法字符 .NET 给的是「Name cannot begin with」变体（探针 R1f：
                // `<\u0001/>` → Name cannot begin with the '' character, hexadecimal value 0x01.），
                // 与内容位的「is an invalid character」是两条文案。
                val ch = s[i]
                failAt(
                    i,
                    "Name cannot begin with the '$ch' character, " +
                        "hexadecimal value 0x${ch.code.toString(16).padStart(2, '0')}",
                )
            }
            if (i >= s.length || !isNameStart(s[i])) fail("缺少合法的名字")
            val start = i
            i++
            while (i < s.length && isNameChar(s[i])) i++
            return s.substring(start, i)
        }

        private fun skipWhitespace(): Boolean {
            val start = i
            while (i < s.length && isWhitespace(s[i])) i++
            return i > start
        }

        private fun expect(ch: Char) {
            if (i >= s.length || s[i] != ch) fail("期望 '$ch'")
            i++
        }

        private fun fail(msg: String): Nothing =
            throw XmlParseException("$msg（位置 $i）")

        private fun failAt(pos: Int, msg: String): Nothing =
            throw XmlParseException("$msg（位置 $pos）")

        /**
         * 报源文本 [pos] 处的「裸」非法字符（RESIDUE-BX2 R1）。文案逐字对齐 dotnet 8.0.425
         * 探针读数（`'<char>', hexadecimal value 0x01, is an invalid character. Line 1,
         * position N.`——行/位置尾段由本实现自己的「（位置 n）」约定替代，0 起）。
         */
        private fun failInvalidChar(pos: Int): Nothing {
            val ch = s[pos]
            failAt(
                pos,
                "'$ch', hexadecimal value 0x${ch.code.toString(16).padStart(2, '0')}, " +
                    "is an invalid character",
            )
        }

        /** 扫 [from] 到 [to]（不含）之间的裸非法字符（注释正文 / CDATA / PI 数据共用）。 */
        private fun scanLiteralChars(from: Int, to: Int) {
            for (k in from until to) {
                if (isInvalidXmlLiteralChar(s[k])) failInvalidChar(k)
            }
        }
    }
}

private fun isWhitespace(ch: Char): Boolean =
    ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'

/**
 * 源文本「裸」非法字符判据（RESIDUE-BX2 R1，dotnet 8.0.425 探针
 * `<work-root>/residue-probe/probe-output.txt`，API 与原版同型
 * `XmlDocument.LoadXml`，FunctionMethodCreator.cs:179/:3058）：
 *
 * - **拦**：`0x00`..`0x1F` 除 `\t`/`\n`/`\r`（探针 R1a 文本 / R1b 属性值 / R1c 注释 /
 *   R1d CDATA / R1e PI 数据 / R1t 散位领先 / R1u 落根后 / R1w InnerXml 片段赋值——
 *   全部抛 XmlException `'<char>', hexadecimal value 0x01, is an invalid character.`，
 *   名字位走 R1f 的「Name cannot begin with」变体）；字面 `U+FFFE`/`U+FFFF` 同判
 *   （探针 R1k/R1n 同文案 invalid character）。
 * - **放行**：`\t`/`\n`/`\r`（探针 R1h）；`0x7F`/`0x85` 等 XML 1.0 合法段照常进 DOM
 *   （探针 R1p/R1q）；**字符引用形态** `&#x1;`/`&#xFFFE;` 不受本判据影响——它拦的是
 *   扫描到的**原始字符**，decode 解出的码位照旧（探针 R1g/R1x 基准，XML-FIVE X1 已钉）。
 *   判据与转义面（XmlNode 的 appendEscapedText/appendEscapedAttribute 把控制符重编成
 *   字符引用）配合：能进 DOM 的控制符只可能来自字符引用，回写即还原成引用形态。
 * - **不拦（TODO(verify)，不硬造）**：孤立代理的字面形态 .NET 报文是「成对消费后的
 *   下一字符 invalid」（探针 R1o：`<r>\uD800</r>` 报 `'<' 0x3C invalid character`），
 *   属读取器成对消费的框架内部行为，本解析器逐字符扫描无此模型；语料/基准无此形态。
 *
 * 旧行为（XML-FIVE 收口时的登记）：移植解析层不拦裸控制符——本判据落地后与 .NET 同拦，
 * 台账 `specs/verify-notes.md` 的 §27.4② 相邻分歧条目就此销账。
 */
internal fun isInvalidXmlLiteralChar(ch: Char): Boolean =
    (ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r') || ch == '\uFFFE' || ch == '\uFFFF'

/**
 * 整段文本是否只由 XML 空白（space/tab/CR/LF）构成 —— `PreserveWhitespace=false`
 * 丢弃的判据。不能用 `isNotBlank()`：它按 [Char.isWhitespace] 判，会把全角空格 U+3000、
 * NBSP 这类 .NET XML 语境下**不是**空白的字符也当空白，整段吞掉文本节点。
 */
/**
 * 整段文本是否只由 XML 空白（space/tab/CR/LF）构成 —— `PreserveWhitespace=false`
 * 丢弃的判据。不能用 `isNotBlank()`：它按 [Char.isWhitespace] 判，会把全角空格 U+3000、
 * NBSP 这类 .NET XML 语境下**不是**空白的字符也当空白，整段吞掉文本节点。
 *
 * internal：同一判据也是 [XmlWhitespace] 的建节点判据（片段纯空白建 #whitespace，见
 * [XmlParser.parseFragment]）与 fn/XmlMethods `setXmlNode` 对 #whitespace 节点赋值的
 * 守卫（.NET 对非空白内容抛 ArgumentException，XML-FIVE X4）。
 */
internal fun isWhitespaceOnly(text: String): Boolean = text.all { isWhitespace(it) }

private fun isNameStart(ch: Char): Boolean =
    ch == '_' || ch == ':' || Character.isLetter(ch)

private fun isNameChar(ch: Char): Boolean =
    isNameStart(ch) || ch == '-' || ch == '.' || Character.isDigit(ch)

/**
 * XML 声明规范化：输出 .NET `XmlDeclaration.OuterXml` 的形状。
 *
 * dotnet 8 实测 `new XmlDocument().LoadXml("<?xml version='1.0' encoding='utf_8'?><r/>").OuterXml`
 * 得到 `<?xml version="1.0" encoding="utf_8"?><r />`：引号统一双引号、属性间单空格、
 * 顺序固定为 version → encoding → standalone。语料 93 个文件全部用单引号声明，
 * 原样回放会让 `XML_TOSTR` 的字节与 PC 不一致。
 */
private fun normalizeDeclaration(raw: String): String {
    val body = raw.removePrefix("<?xml").removeSuffix("?>")
    val attrs = LinkedHashMap<String, String>()
    var k = 0
    while (true) {
        while (k < body.length && isWhitespace(body[k])) k++
        if (k >= body.length) break
        val nameStart = k
        while (k < body.length && !isWhitespace(body[k]) && body[k] != '=') k++
        val name = body.substring(nameStart, k)
        while (k < body.length && isWhitespace(body[k])) k++
        if (k >= body.length || body[k] != '=') throw XmlParseException("XML 声明属性 $name 缺少 '='")
        k++
        while (k < body.length && isWhitespace(body[k])) k++
        if (k >= body.length || (body[k] != '"' && body[k] != '\'')) {
            throw XmlParseException("XML 声明属性 $name 的值必须用引号包围")
        }
        val quote = body[k++]
        val valueStart = k
        while (k < body.length && body[k] != quote) k++
        if (k >= body.length) throw XmlParseException("XML 声明属性 $name 的值未闭合")
        attrs[name] = body.substring(valueStart, k)
        k++
    }
    val version = attrs["version"] ?: throw XmlParseException("XML 声明缺少 version")
    val sb = StringBuilder("<?xml version=\"").append(version).append('"')
    attrs["encoding"]?.let { sb.append(" encoding=\"").append(it).append('"') }
    attrs["standalone"]?.let { sb.append(" standalone=\"").append(it).append('"') }
    sb.append("?>")
    return sb.toString()
}

/** 预定义实体 + 数字字符引用。未定义实体 → 解析错误（.NET 对 LoadXml 同口径）。 */
private fun decode(raw: String): String {
    if ('&' !in raw) return raw
    val sb = StringBuilder(raw.length)
    var k = 0
    while (k < raw.length) {
        val ch = raw[k]
        if (ch != '&') {
            sb.append(ch)
            k++
            continue
        }
        val semi = raw.indexOf(';', k + 1)
        if (semi < 0) throw XmlParseException("实体引用未闭合")
        val ent = raw.substring(k + 1, semi)
        when {
            ent == "amp" -> sb.append('&')
            ent == "lt" -> sb.append('<')
            ent == "gt" -> sb.append('>')
            ent == "quot" -> sb.append('"')
            ent == "apos" -> sb.append('\'')
            // 只认小写 `&#x`（XML 1.0 [66] CharRef 的十六进制前缀是小写 x）。
            // dotnet 8.0.425 实测 `&#X41;` 抛 XmlException「Invalid syntax for a
            // decimal numeric entity reference」（经 FMC.cs:1699-1706 转 CodeEE）；
            // 旧实现把大写 `#X` 也当十六进制接受（`&#X41;` → 'A'），
            // 与原版相反。删掉后 `&#X41;` 落 else 抛「未定义的实体」
            // ——同为解析错误 → CodeEE，行为对齐。
            ent.startsWith("#x") ->
                appendCharacterRef(sb, ent.substring(2), 16, ent)
            ent.startsWith("#") ->
                appendCharacterRef(sb, ent.substring(1), 10, ent)
            else -> throw XmlParseException("未定义的实体 &$ent;")
        }
        k = semi + 1
    }
    return sb.toString()
}

/**
 * 数字字符引用的码点校验与写入。
 *
 * 与 .NET 的对拍结论（dotnet 8.0.425；探针走 Emuera 同 API `new XmlDocument().LoadXml(string)`，
 * 见 FunctionMethodCreator.cs:179 / :189）分两层：
 *
 * 1. **本实现上线即有的闸**：超范围（大于 0x10FFFF）与代理区 0xD800..0xDFFF。
 *    dotnet `LoadXml` 对二者抛 **ArgumentException**（实测「The surrogate pair (0xD800, 0xD800)
 *    is invalid.」/「Invalid high surrogate character (0xDC00).」），而 Emuera 只 catch
 *    `XmlException`（FunctionMethodCreator.cs:181-184 / :3061-3064）⇒ **原版未捕获崩溃**；
 *    本实现按 ADR-005「官方未捕获崩溃不复刻」转成可诊断的 [XmlParseException]（调用方落 CodeEE）。
 *    这里必须先校验再写：直接 `appendCodePoint` 对越界值抛 IllegalArgumentException、
 *    对孤立代理则静默写出坏字符，两者都不是 [XmlParseException]，调用方的 CodeEE 转换接不住。
 *
 * 2. **放行（XML-FIVE X1 翻案后与 .NET 全对齐）**：
 *    - `&#xFFFE;` / `&#xFFFF;`（及 `&#xFDD0;` 等非字符）dotnet `LoadXml` **放行**，InnerText
 *      即该码位；本实现同口径放行（旧 TODO(verify) 已闭环）。
 *    - 低位控制符 `#x0`..`#x1F`（除 `#x9`/`#xA`/`#xD` 外，含 0 本身）dotnet `LoadXml`
 *      同样**放行**（2026-11-06 复探实测 `&#x0;&#x1;&#x8;&#x1F;` 全过、InnerText 码位
 *      0,1,8,31），本实现改为同口径放行（旧实现拒绝，方向与 .NET 相反，已翻案；
 *      `fn/MapDataTableMethodsTest` / `fn/MapXmlUnifyTest` 的旧拒绝钉同步翻钉）。
 *      注意只放行**字符引用**形态：源文本里的裸控制符 .NET 抛 XmlException
 *      「'', hexadecimal value 0x01, is an invalid character.」（同轮探针实测），
 *      移植侧已同口径拦截（RESIDUE-BX2 R1 落地，判据与布点见
 *      [isInvalidXmlLiteralChar]），台账 §27.4② 的相邻分歧条目就此销账。
 *    回写面（OuterXml/InnerXml）把除 `\t`/`\n`/`\r` 外的控制符重新编成大写十六进制字符
 *    引用（`&#x1;`/`&#x1F;`，探针实测），见 XmlNode.appendEscapedText / appendEscapedAttribute。
 */
private fun appendCharacterRef(sb: StringBuilder, digits: String, radix: Int, ent: String) {
    val cp = digits.toIntOrNull(radix) ?: throw XmlParseException("非法的字符引用 &$ent;")
    if (cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) {
        throw XmlParseException("非法的字符引用 &$ent;")
    }
    sb.appendCodePoint(cp)
}
