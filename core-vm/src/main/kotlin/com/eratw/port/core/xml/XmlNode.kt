package com.eratw.port.core.xml

/**
 * 自建 XML DOM（.NET `System.Xml.XmlDocument` 的最小可用子集）。**文件所有者：A2**。
 *
 * 为什么不用第三方库：构建必须离线可用（契约 §4/A2），且 `XML_TOSTR` 的判据是
 * `XmlDocument.OuterXml` 的**逐字节**产物（`pc-golden-root.txt:572` / `:576`），
 * 任何库的序列化细节都不可控。
 *
 * 已复刻的 .NET 序列化口径（每条都是 OuterXml 的字节差异来源）：
 * - 属性一律双引号，即使源文本用单引号（基准 572 行：输入 `id='1'`，输出 `id="1"`）。
 * - 属性顺序 = 解析顺序（`XmlAttributeCollection` 是保序链表）。
 * - 空元素写成 `<a />`（斜杠前有一个空格）—— **只在 `XmlElement.IsEmpty` 为真时**。
 *   该标志由「创建路径」决定（dotnet 8.0.421 实测）：
 *   `CreateElement` / 自闭合标签 `<a/>` ⇒ 真；源文本的 `<a></a>` 起始标签 ⇒ 假；
 *   追加过子节点后 ⇒ 假（此后即使子节点全被移除也写 `<a></a>`）。
 *   所以 `<a></a>` 加载后 OuterXml 仍是 `<a></a>`，不是 `<a />`。
 * - 处理指令写成 `<target data?>`，target 与数据之间的那**一个**空格是 .NET 无条件写出的：
 *   数据为空时也写（`<?pi?>` → `<?pi ?>`），见 [XmlProcessingInstruction.writeOuterXml]。
 * - 文本节点转义 `& < >`（`>` 也转义，.NET `XmlTextEncoder` 的行为），其余字符
 *   （含 `\t`/`\n`/`\r`）原样写出；属性值另转义 `"`，且属性内 `\n`/`\r` 写成
 *   `&#xA;`/`&#xD;`、`\t` 原样（dotnet 8 `XmlDocument.OuterXml` 实测）。
 * - `PreserveWhitespace = false`（`LoadXml` 默认）：元素间纯空白文本节点丢弃，
 *   非纯空白文本节点原样保留。
 *
 * 未实现（无基准、无语料使用，见报告 TODO(verify)）：命名空间前缀解析、
 * DTD、实体定义、`xml:space`。
 */

/** 节点基类。`name`/`value`/`innerText`/`innerXml`/`outerXml` 对齐 .NET `XmlNode` 同名属性。 */
sealed class XmlNode {
    internal var parentNode: XmlNode? = null

    /**
     * 父节点。属性节点的父是所属元素（对应 .NET `XmlAttribute.ParentNode => OwnerElement`，
     * spec: System.Xml.XmlAttribute.ParentNode）。
     */
    val parent: XmlNode? get() = parentNode

    /** 节点名。元素 = 标签名；属性 = 属性名；文本 = `#text`；CDATA = `#cdata-section`；注释 = `#comment`。 */
    abstract val name: String

    /**
     * 节点值（.NET `XmlNode.Value`）。元素为 null（本实现用空串表示，`XML_GET` style=0
     * 取到元素时原版写入 null → 字符串数组读出来是空串，观测等价）。
     */
    open val value: String get() = ""

    /** 拼接所有后代文本（.NET `XmlNode.InnerText`；元素/文档的拼接里注释/PI 不计入）。 */
    abstract val innerText: String

    abstract fun writeInnerXml(sb: StringBuilder)
    abstract fun writeOuterXml(sb: StringBuilder)

    /** .NET `XmlNode.InnerXml`。 */
    fun innerXml(): String = StringBuilder().also { writeInnerXml(it) }.toString()

    /** .NET `XmlNode.OuterXml` —— `XML_TOSTR` 的返回值。 */
    fun outerXml(): String = StringBuilder().also { writeOuterXml(it) }.toString()

    /**
     * 从当前父节点摘下（.NET 的「节点只有一个父」不变量：`AppendChild`/`InsertBefore`
     * 对已挂节点做的是**移动**而不是复制）。
     */
    internal fun detach() {
        when (val p = parentNode) {
            is XmlElement -> {
                p.children.remove(this)
                if (this is XmlAttribute) {
                    p.attributes.remove(this)
                    ownerElement = null
                }
            }
            is XmlDocument -> p.children.remove(this)
            else -> {}
        }
        parentNode = null
    }
}

/**
 * 文档节点。子节点 = 根元素 + 文档级注释/PI；XML 声明单独存**规范化后的**文本
 * （.NET `XmlDeclaration.OuterXml` 口径：双引号、version/encoding/standalone 顺序）。
 */
class XmlDocument : XmlNode() {
    /** 规范化后的 `<?xml ...?>` 声明文本；null = 源文本没有声明。 */
    var declaration: String? = null

    /** 文档级子节点（根元素、注释、PI），按源顺序。 */
    val children: MutableList<XmlNode> = ArrayList()

    /** 根元素；合法文档必有。 */
    val root: XmlElement? get() = children.filterIsInstance<XmlElement>().firstOrNull()

    override val name: String get() = "#document"

    /**
     * .NET `XmlDocument.InnerText`：只拼元素/文本/CDATA，文档级注释与 PI 不计入
     * （dotnet 8 实测 `<!--c--><r>a</r>` 的 `doc.InnerText` 是 "a"）。
     */
    override val innerText: String
        get() {
            val sb = StringBuilder()
            for (c in children) {
                when (c) {
                    is XmlText -> sb.append(c.text)
                    is XmlCData -> sb.append(c.data)
                    is XmlElement -> c.appendInnerText(sb)
                    else -> {}
                }
            }
            return sb.toString()
        }

    internal fun appendChild(node: XmlNode) {
        node.detach()
        node.parentNode = this
        children.add(node)
    }

    internal fun removeChild(node: XmlNode): Boolean {
        if (!children.remove(node)) return false
        node.parentNode = null
        return true
    }

    /**
     * .NET `XmlDocument.InnerXml` 也包含 XML 声明（dotnet 8 实测：
     * `LoadXml("<?xml ...?><r/>").InnerXml` 以声明开头），与 `OuterXml` 同形。
     */
    override fun writeInnerXml(sb: StringBuilder) {
        declaration?.let { sb.append(it) }
        for (c in children) c.writeOuterXml(sb)
    }

    override fun writeOuterXml(sb: StringBuilder) {
        declaration?.let { sb.append(it) }
        for (c in children) c.writeOuterXml(sb)
    }
}

/** 元素节点。属性与子节点分开存（.NET `XmlElement.Attributes` / `ChildNodes`）。 */
class XmlElement(override val name: String) : XmlNode() {
    /** 属性，解析/插入顺序。 */
    val attributes: MutableList<XmlAttribute> = ArrayList()

    /** 子节点（元素/文本/CDATA/注释/PI），文档顺序。 */
    val children: MutableList<XmlNode> = ArrayList()

    /**
     * .NET 的内部标志 `XmlElement.IsEmpty`（决定 OuterXml 收尾写 ` />` 还是 `<a></a>`）。
     *
     * 语义（dotnet 8.0.421 实测）：
     * - `XmlDocument.CreateElement` 与自闭合标签 `<a/>` ⇒ true；
     * - 源文本的 `<a></a>`（有结束标签）⇒ false，此后即使没有子节点也写 `<a></a>`；
     * - `AppendChild`/`InsertBefore`/`InsertAfter` 加进任何子节点 ⇒ 置 false（不可逆：
     *   `<a></a>` 追加后再 RemoveAll 仍写 `<a></a>`）；
     * - 只加属性不改它（`<a x="1" />` 仍是自闭合）。
     */
    internal var isEmptyElement: Boolean = true

    /** 按名取属性（XML 名区分大小写）。 */
    fun attribute(attrName: String): XmlAttribute? = attributes.firstOrNull { it.name == attrName }

    internal fun addAttribute(attr: XmlAttribute) {
        attr.detach()
        attr.ownerElement = this
        attr.parentNode = this
        attributes.add(attr)
    }

    internal fun insertAttributeBefore(attr: XmlAttribute, before: XmlAttribute): Boolean {
        if (attributes.indexOf(before) < 0) return false
        // 先摘再取下标：若先取下标再 detach，attr 已在本表且位于 before 之前时下标会偏移一位
        // （.NET `XmlNode.InsertBefore` 也是先移动节点再定位参照节点，FunctionMethodCreator.cs:1442-1497）。
        attr.detach()
        val idx = attributes.indexOf(before)
        if (idx < 0) return false
        attr.ownerElement = this
        attr.parentNode = this
        attributes.add(idx, attr)
        return true
    }

    internal fun insertAttributeAfter(attr: XmlAttribute, after: XmlAttribute): Boolean {
        if (attributes.indexOf(after) < 0) return false
        attr.detach()
        val idx = attributes.indexOf(after)
        if (idx < 0) return false
        attr.ownerElement = this
        attr.parentNode = this
        attributes.add(idx + 1, attr)
        return true
    }

    internal fun removeAttribute(attr: XmlAttribute): Boolean {
        if (!attributes.remove(attr)) return false
        attr.ownerElement = null
        attr.parentNode = null
        return true
    }

    /**
     * 追加子节点。**已挂在别处的节点会先被摘下**（.NET `XmlNode.AppendChild` 的移动语义）——
     * `XML_ADDNODE` 在「命中多个 + 全部应用」时复用同一个新节点，原版因此只保留最后一次插入。
     */
    internal fun appendChild(node: XmlNode) {
        node.detach()
        node.parentNode = this
        children.add(node)
        isEmptyElement = false // .NET：加过子节点就不再写自闭合形态
    }

    internal fun insertBefore(node: XmlNode, ref: XmlNode): Boolean {
        if (children.indexOf(ref) < 0) return false
        // 先摘再取下标：node 已在本列表且位于 ref 之前时，先取的下标会被 detach 的移除带偏一位。
        // .NET `XmlNode.InsertBefore` 对已挂节点同样先移动再插入（FunctionMethodCreator.cs:1442-1497）。
        node.detach()
        val idx = children.indexOf(ref)
        if (idx < 0) return false
        node.parentNode = this
        children.add(idx, node)
        isEmptyElement = false
        return true
    }

    internal fun insertAfter(node: XmlNode, ref: XmlNode): Boolean {
        if (children.indexOf(ref) < 0) return false
        node.detach()
        val idx = children.indexOf(ref)
        if (idx < 0) return false
        node.parentNode = this
        children.add(idx + 1, node)
        isEmptyElement = false
        return true
    }

    internal fun removeChild(node: XmlNode): Boolean {
        if (!children.remove(node)) return false
        node.parentNode = null
        return true
    }

    /** 换掉全部子节点（`InnerText`/`InnerXml` 赋值的实现基础）。 */
    internal fun replaceChildren(nodes: List<XmlNode>) {
        for (c in children) c.parentNode = null
        children.clear()
        for (n in nodes) {
            n.parentNode = this
            children.add(n)
        }
        // .NET 的 RemoveAll+AppendChild 组合：删光不改 IsEmpty，加进去才改
        if (nodes.isNotEmpty()) isEmptyElement = false
    }

    override val innerText: String
        get() {
            val sb = StringBuilder()
            appendInnerText(sb)
            return sb.toString()
        }

    internal fun appendInnerText(sb: StringBuilder) {
        for (c in children) {
            when (c) {
                is XmlText -> sb.append(c.text)
                is XmlCData -> sb.append(c.data)
                is XmlElement -> c.appendInnerText(sb)
                else -> {}
            }
        }
    }

    override fun writeInnerXml(sb: StringBuilder) {
        for (c in children) c.writeOuterXml(sb)
    }

    override fun writeOuterXml(sb: StringBuilder) {
        sb.append('<').append(name)
        for (a in attributes) {
            sb.append(' ')
            a.writeOuterXml(sb)
        }
        if (children.isEmpty() && isEmptyElement) {
            // .NET XmlTextWriter 的空元素写法：`<a />`（只有 IsEmpty 的元素才这样写）
            sb.append(" />")
        } else {
            sb.append('>')
            for (c in children) c.writeOuterXml(sb)
            sb.append("</").append(name).append('>')
        }
    }
}

/** 属性节点。`value`/`innerText` 都是属性值，`outerXml` 是 `name="value"`。 */
class XmlAttribute(override val name: String, var attrValue: String) : XmlNode() {
    internal var ownerElement: XmlElement? = null

    /** 所属元素（.NET `XmlAttribute.OwnerElement`）。 */
    val owner: XmlElement? get() = ownerElement

    override val value: String get() = attrValue

    override val innerText: String get() = attrValue

    override fun writeInnerXml(sb: StringBuilder) {
        appendEscapedAttribute(sb, attrValue)
    }

    override fun writeOuterXml(sb: StringBuilder) {
        sb.append(name).append("=\"")
        appendEscapedAttribute(sb, attrValue)
        sb.append('"')
    }
}

/** 文本节点。`InnerXml` 恒为空串、`OuterXml` 是转义文本（dotnet 8 实测）。 */
class XmlText(var text: String) : XmlNode() {
    override val name: String get() = "#text"

    override val value: String get() = text

    override val innerText: String get() = text

    override fun writeInnerXml(sb: StringBuilder) {
        // .NET XmlText.InnerXml 是空串（dotnet 8 实测）
    }

    override fun writeOuterXml(sb: StringBuilder) {
        appendEscapedText(sb, text)
    }
}

/** CDATA 节点。`innerText` 计入文本，`outerXml` 原样包 `<![CDATA[...]]>`，`innerXml` 为空串。 */
class XmlCData(var data: String) : XmlNode() {
    override val name: String get() = "#cdata-section"

    override val value: String get() = data

    override val innerText: String get() = data

    override fun writeInnerXml(sb: StringBuilder) {
        // .NET XmlCDataSection.InnerXml 是空串（dotnet 8 实测）
    }

    override fun writeOuterXml(sb: StringBuilder) {
        sb.append("<![CDATA[").append(data).append("]]>")
    }
}

/** 注释节点。`innerText` = 注释正文（.NET `XmlComment.InnerText`），`innerXml` 为空串。 */
class XmlComment(var data: String) : XmlNode() {
    override val name: String get() = "#comment"

    override val value: String get() = data

    override val innerText: String get() = data

    override fun writeInnerXml(sb: StringBuilder) {
        // .NET XmlComment.InnerXml 是空串（dotnet 8 实测）
    }

    override fun writeOuterXml(sb: StringBuilder) {
        sb.append("<!--").append(data).append("-->")
    }
}

/**
 * 处理指令。`value` = `innerText` = 指令体（.NET `XmlProcessingInstruction` 三个属性同值，
 * dotnet 8 实测），`innerXml` 为空串。
 *
 * `data` 已由 [XmlParser] 去掉 target 与数据之间的全部空白；序列化时 .NET **无条件**写一个
 * 空格再接数据（dotnet 8.0.421 实测：`<?pi?>` 与 `<?pi ?>` 加载后 OuterXml 都是 `<?pi ?>`）。
 */
class XmlProcessingInstruction(override val name: String, var data: String) : XmlNode() {
    override val value: String get() = data

    override val innerText: String get() = data

    override fun writeInnerXml(sb: StringBuilder) {
        // .NET XmlProcessingInstruction.InnerXml 是空串（dotnet 8 实测）
    }

    override fun writeOuterXml(sb: StringBuilder) {
        sb.append("<?").append(name).append(' ').append(data).append("?>")
    }
}

// ---------------------------------------------------------------------------
// 转义（.NET XmlTextEncoder 口径）
// ---------------------------------------------------------------------------

/**
 * 文本节点转义。`>` 也转义成 `&gt;`（.NET `XmlTextEncoder` 对 `>` 无条件转义，
 * 比 XML 规范要求的更宽）；`\t`/`\n`/`\r` 原样写出（dotnet 8 实测 `OuterXml` 不转义它们）。
 */
internal fun appendEscapedText(sb: StringBuilder, s: String) {
    for (ch in s) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            else -> sb.append(ch)
        }
    }
}

/**
 * 属性值转义：文本规则 + `"` → `&quot;`；`\n`/`\r` 写成字符引用，`\t` 原样
 * （dotnet 8 `XmlDocument.OuterXml` 实测：`<r a="x&#10;y<TAB>z&#13;w"/>` 的输出
 * 里 TAB 是字面量、LF/CR 是 `&#xA;`/`&#xD;`）。
 */
internal fun appendEscapedAttribute(sb: StringBuilder, s: String) {
    for (ch in s) {
        when (ch) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\r' -> sb.append("&#xD;")
            '\n' -> sb.append("&#xA;")
            else -> sb.append(ch)
        }
    }
}
