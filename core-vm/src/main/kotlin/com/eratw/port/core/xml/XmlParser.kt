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
     */
    private const val MAX_ELEMENT_DEPTH = 512

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
     */
    fun parseFragment(text: String): List<XmlNode> = Cursor(text, preserveWhitespace = false).parseContentFragment()

    private class Cursor(private val s: String, private val preserveWhitespace: Boolean) {
        private var i = 0

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
                        if (t.isNotEmpty()) out.add(XmlText(t))
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
            val data = s.substring(i, end)
            i = end + 3
            return XmlComment(data)
        }

        private fun parseCData(): XmlCData {
            i += 9 // <![CDATA[
            val end = s.indexOf("]]>", i)
            if (end < 0) fail("CDATA 未闭合")
            val data = s.substring(i, end)
            i = end + 3
            return XmlCData(data)
        }

        private fun parseProcessingInstruction(): XmlProcessingInstruction {
            i += 2 // <?
            val target = readName()
            val end = s.indexOf("?>", i)
            if (end < 0) fail("处理指令未闭合")
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
            while (i < s.length && s[i] != '<') i++
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
                i++
            }
            if (i >= s.length) fail("属性值未闭合")
            val raw = s.substring(start, i)
            i++
            return decode(raw)
        }

        private fun readName(): String {
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
    }
}

private fun isWhitespace(ch: Char): Boolean =
    ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r'

/**
 * 整段文本是否只由 XML 空白（space/tab/CR/LF）构成 —— `PreserveWhitespace=false`
 * 丢弃的判据。不能用 `isNotBlank()`：它按 [Char.isWhitespace] 判，会把全角空格 U+3000、
 * NBSP 这类 .NET XML 语境下**不是**空白的字符也当空白，整段吞掉文本节点。
 */
private fun isWhitespaceOnly(text: String): Boolean = text.all { isWhitespace(it) }

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
 * 原版 `XmlDocument.LoadXml` 抛 XmlException 的码位集（XmlException 由
 * `FunctionMethodCreator.cs:1699-1706` 转成 CodeEE）：
 * - 超范围（大于 0x10FFFF）与代理区 0xD800..0xDFFF（引用必须指向标量值，不能造孤立代理）
 *   —— dotnet 8 实测，本函数**上线即有**的闸；
 * - XML 1.0 Char 集之外的低位控制符（`#x0`..`#x1F` 里除 `#x9`/`#xA`/`#xD` 外，含 0 本身）
 *   —— 2026-10-24 ARCH1 P3b 收口补上：原 fn 侧迷你解析器 MiniXml 的 M4 实测结论
 *   （「码位不在 XML 1.0 Char 集（#x9/#xA/#xD/#x20 以上）→ 全部抛 XmlException，
 *   落 FunctionMethodCreator.cs:3061-3064 或 :2749-2752 的 catch」）随双轨合一迁入本处，
 *   `fn/MapDataTableMethodsTest` 的 `&#0;` 抛错用例即该口径的钉子。
 *
 * 这里必须先校验再写：直接 `appendCodePoint` 对越界值抛 IllegalArgumentException、
 * 对孤立代理则静默写出坏字符，两者都不是 [XmlParseException]，调用方的 CodeEE 转换接不住。
 *
 * TODO(verify)：0xFFFE/0xFFFF 按 XML 1.0 也非 Char，但两套实现历史上都放行且无探针读数，
 * 维持放行（语料与 golden 均不可达）；拿到 dotnet 探针读数后再定收口方向。
 */
private fun appendCharacterRef(sb: StringBuilder, digits: String, radix: Int, ent: String) {
    val cp = digits.toIntOrNull(radix) ?: throw XmlParseException("非法的字符引用 &$ent;")
    if (cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF ||
        (cp < 0x20 && cp != 0x9 && cp != 0xA && cp != 0xD)
    ) {
        throw XmlParseException("非法的字符引用 &$ent;")
    }
    sb.appendCodePoint(cp)
}
