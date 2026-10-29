package com.eratw.port.core.xml

import java.util.IdentityHashMap

/**
 * XPath 选择器（对应 .NET `XmlDocument.SelectNodes`，覆盖基准与语料实际用到的子集）。
 *
 * 支持：
 * - 路径：绝对/相对（`/root/a`、`root/a`）、子轴 `/`、后代缩写 `//`、联合 `a | b`。
 * - 轴前缀：`child`、`descendant`、`descendant-or-self`、`self`、`parent`、`ancestor`、
 *   `ancestor-or-self`、`following-sibling`、`preceding-sibling`、`attribute`；
 *   缩写 `.` 等于 `self::node()`、`..` 等于 `parent::node()`、`@x` / `@*` 等于 attribute 轴。
 * - 节点测试：元素名、`*`、`text()`、`comment()`、`node()`。
 * - 谓词：`[n]`、`[last()]`、`[@attr]`、`[@attr='v']`、`[@attr=5]`、`[tag]`、`[tag='v']`、
 *   `[tag=5]`、`[text()]`、`[text()='v']`、`[text()=5]`、`[.='v']`。
 *
 * 与 XPath 1.0 一致的关键语义（每条都有单测钉住）：
 * - 元素名区分大小写；谓词按**每个上下文节点**求位置。`//a[1]` 是
 *   `descendant-or-self::node()` 接 `child::a[1]` 的缩写，所以得「每个父的第 1 个 a」，
 *   不是「全树第 1 个 a」。
 * - 反向轴（parent / ancestor / preceding-sibling）按反向文档序给位置，结果再归一到文档序。
 * - 联合按文档序去重合并（.NET `XmlNodeList` 也按文档序返回）。
 * - `text()` 选中文本节点**和 CDATA 段**，且相邻的文本/CDATA **合并成一个**文本节点
 *   （XPath 1.0 数据模型里 CDATA 不是独立节点类型）。`XmlDocument.SelectNodes` 返回的是
 *   合并段里的**首个 DOM 节点**：`<r><![CDATA[x]]>y</r>` 的 `/r/text()` 得 1 个节点（CDATA，
 *   值 "x"）；`<r>a<e/><![CDATA[b]]></r>` 的 `/r/text()` 得 2 个（text "a" 与 CDATA "b"）。
 *   dotnet 8.0.421 实测；`[text()]` 谓词同理（CDATA-only 元素为真）。
 * - `node()` 用同一个合并口径：`<r><![CDATA[x]]>y</r>` 的 `/r/node()` 得 1 个节点、
 *   `//node()` 得 2 个（根元素 + 段首 CDATA）；`<r>a<![CDATA[b]]>c</r>` 的 `/r/node()`
 *   也得 1 个（dotnet 8.0.421 实测）——所以 `node()` 同样要跳过同段的后续节点。
 *
 * 命名空间（dotnet 8.0.31 实测；Emuera 全程用 `SelectNodes(xpath)` 单参重载、从不传
 * `XmlNamespaceManager`——见 FunctionMethodCreator.cs:189/1253/1530/1712/1864）：
 * - **带前缀的名字测试恒报错**：`/a:b`、`//p:q`、`p:*`、`xml:c`、`xmlns:c`、`@p:k`、`[p:q]`
 *   这类形态在 .NET 里抛 XPathException「Namespace Manager or XsltContext needed. This query
 *   has a prefix, variable, or user-defined function.」——即**声明过的前缀也不解析**（因为没人
 *   递 namespace manager 给查询）。本实现照此抛 [XmlPathException]，[XmlPathException.message]
 *   取该 .NET 默认文案原样，调用方 `XmlMethods.xmlSelect` 再包成 CodeEE（对应 `Lang.Error`
 *   的 XmlXPathParseError / XmlGetPathError 一族）。修复前本实现对前缀名按字面量匹配 ⇒ 恒 0
 *   命中而不报错，是总账 FN-10 的第一条缺陷。
 * - **无前缀的名字测试受默认命名空间约束**：`<r xmlns="urn:d"><c/></r>` 的 `/r`、`/r/c`、`//c`
 *   在 .NET 全是 0 命中（无前缀的名字测试要求节点命名空间 URI 为空，而默认 `xmlns` 会施加到
 *   未加前缀的元素上）；`*` 通配不受命名空间约束（`<r xmlns:p="urn:x"><c xmlns="urn:d"/></r>`
 *   的子级通配步命中 `c`）。本实现按 [defaultNamespaceUri] 计算元素的生效命名空间 URI 来钉这条。
 * - 属性不受默认命名空间影响（`<r xmlns="urn:d" a="1">` 上 `@a` 仍可及，dotnet 8 实测），
 *   故属性名测试只做字面名比较。
 *
 * 未实现（基准与语料都没有这些形态，遇到即抛 [XmlPathException]，调用方包成 CodeEE；
 * `.NET` 对非法 XPath 抛 XPathException，原版再包成 `Lang.Error.XmlXPathParseError`）：
 * - 轴：`preceding`、`following`、`namespace`（.NET 支持，本实现未做）；
 * - 节点测试：`processing-instruction()`；
 * - 谓词函数：`last()` 以外的（`position()`、`contains()`、`starts-with()` 等）；
 * - 运算符：`<`、`>`、`!=`。
 *
 * 已知与 dotnet 的残差（登记分歧，语料无 xmlns 声明故不可观测，TODO(verify)）：
 * - `@*` 属性通配：.NET 不把 `xmlns`/`xmlns:p` 声明当属性节点（`<r xmlns:p="urn:x"><c/></r>`
 *   的 `/r/@*` = 0），本实现的解析器把声明存成普通属性，故 `@*` 会把声明带进结果；未过滤。
 * - 畸形限定名（空前缀 `:c`、空前缀尾随 `:`、空本地名 `a:`、多冒号 `a:b:c`、含空格的
 *   `a: b`）：.NET 抛 XPathException 且文案带**位置**（形如 `'/a:b:c' has an invalid token.` /
 *   `... has an invalid qualified name.`）；本实现拿不到整串路径的偏移，按冒号切分处给近似的
 *   `'<name> has an invalid token/qualified name'`，只钉「报错」这一行为不逐字钉文案。
 * - 只有 `InnerText` 赋值产生的纯空白片段在原版是 `#whitespace` 节点（`Name` 不同，
 *   `XML_GET` style=4 可观测），本实现统一用 `#text`。
 */
class XmlPathException(message: String) : Exception(message)

object XmlPath {

    /** 选中节点列表（文档序、去重）。 */
    fun select(doc: XmlDocument, path: String): List<XmlNode> {
        val branches = splitUnion(path)
        val order = DocOrder(doc)
        if (branches.size == 1) return selectPath(doc, branches[0], order)
        val collected = ArrayList<XmlNode>()
        for (b in branches) collected.addAll(selectPath(doc, b, order))
        return order.sort(collected)
    }

    /**
     * 单条路径求值：逐步把上下文节点集换成下一层节点集。
     * 每步先取轴上的候选（按轴方向），再套节点测试，再按**该上下文节点**套谓词。
     */
    private fun selectPath(doc: XmlDocument, path: String, order: DocOrder): List<XmlNode> {
        // XPath "/" 选中的是文档节点本身（.NET SelectNodes("/") 返回 1 个节点）
        if (path.trim() == "/") return listOf(doc)
        val steps = parse(path)
        var ctx: List<XmlNode> = listOf(doc)
        for (step in steps) {
            val merged = ArrayList<XmlNode>()
            for (node in ctx) {
                val candidates = axisNodes(node, step.axis).filter { matchesTest(it, step) }
                merged.addAll(applyPredicates(candidates, step.predicates))
            }
            // 单上下文节点 + 正向轴：结果天然是文档序且无重复，省掉整树索引
            ctx = if (ctx.size == 1 && !step.axis.reverse) merged else order.sort(merged)
        }
        return ctx
    }

    // ------------------------------------------------------------------
    // 语法
    // ------------------------------------------------------------------

    /** 轴。`reverse == true` 的轴（XPath 1.0 的 reverse axis）按反向文档序给位置。 */
    private enum class Axis(val reverse: Boolean) {
        CHILD(false),
        DESCENDANT(false),
        DESCENDANT_OR_SELF(false),
        SELF(false),
        PARENT(true),
        ANCESTOR(true),
        ANCESTOR_OR_SELF(true),
        FOLLOWING_SIBLING(false),
        PRECEDING_SIBLING(true),
        ATTRIBUTE(false),
        ;

        companion object {
            private val BY_NAME = mapOf(
                "child" to CHILD,
                "descendant" to DESCENDANT,
                "descendant-or-self" to DESCENDANT_OR_SELF,
                "self" to SELF,
                "parent" to PARENT,
                "ancestor" to ANCESTOR,
                "ancestor-or-self" to ANCESTOR_OR_SELF,
                "following-sibling" to FOLLOWING_SIBLING,
                "preceding-sibling" to PRECEDING_SIBLING,
                "attribute" to ATTRIBUTE,
            )

            /**
             * 轴名 → 轴。未知轴名抛错：.NET `XPathNavigator` 对拼错的轴名（语料
             * `宴会_SUB.ERB:201` 的 `preceeding-sibling::` 就少一个 e）抛 XPathException，
             * 原版 `XmlGetMethod` 再包成 CodeEE（`FunctionMethodCreator.cs:186-192`）。
             * 所以拼错必须报错，不能静默 0 命中（ADR-005 复刻原版行为）。
             */
            fun ofName(name: String): Axis = BY_NAME[name] ?: throw XmlPathException(
                "不支持的 XPath 轴：$name（XPath 1.0 没有这个轴名）",
            )
        }
    }

    private sealed class NodeTest {
        data class Name(val name: String) : NodeTest()
        object AnyName : NodeTest()
        object TextNode : NodeTest()
        object CommentNode : NodeTest()
        object AnyNode : NodeTest()
    }

    private class Step(
        val axis: Axis,
        val nodeTest: NodeTest,
        val predicates: List<Predicate>,
    )

    /** 谓词右值：引号字面量 vs 数值（XPath 1.0 里两者比较规则不同）。 */
    private sealed class CompareValue {
        data class Text(val v: String) : CompareValue()
        data class Num(val v: Double) : CompareValue()
    }

    private sealed class Predicate {
        data class Position(val n: Int) : Predicate()
        object Last : Predicate()
        data class HasAttr(val name: String) : Predicate()
        data class HasChild(val name: String) : Predicate()
        object HasText : Predicate()
        data class AttrCompare(val name: String, val value: CompareValue) : Predicate()
        data class TextCompare(val value: CompareValue) : Predicate()
        data class SelfCompare(val value: CompareValue) : Predicate()
        data class ChildTextCompare(val name: String, val value: CompareValue) : Predicate()
    }

    /** 顶层竖线切成联合的各个分支（括号与引号里的竖线不算）。 */
    private fun splitUnion(path: String): List<String> {
        val parts = ArrayList<String>()
        var start = 0
        var depth = 0
        var quote: Char? = null
        for (k in path.indices) {
            val ch = path[k]
            if (quote != null) {
                if (ch == quote) quote = null
                continue
            }
            when (ch) {
                '\'', '"' -> quote = ch
                '[' -> depth++
                ']' -> depth--
                '|' -> if (depth == 0) {
                    parts.add(path.substring(start, k))
                    start = k + 1
                }
            }
        }
        parts.add(path.substring(start))
        if (parts.size > 1) {
            for (p in parts) if (p.trim().isEmpty()) throw XmlPathException("XPath 联合分支为空")
        }
        return parts
    }

    private fun parse(path: String): List<Step> {
        val s = path.trim()
        if (s.isEmpty()) throw XmlPathException("XPath 为空")
        val steps = ArrayList<Step>()
        var i: Int
        if (s.startsWith("//")) {
            // `//` 是 `/descendant-or-self::node()/` 的缩写
            steps.add(Step(Axis.DESCENDANT_OR_SELF, NodeTest.AnyNode, emptyList()))
            i = 2
        } else if (s[0] == '/') {
            i = 1
        } else {
            i = 0
        }
        if (i >= s.length) throw XmlPathException("XPath 没有步")
        while (i < s.length) {
            val start = i
            var depth = 0
            var quote: Char? = null
            while (i < s.length) {
                val ch = s[i]
                if (quote != null) {
                    if (ch == quote) quote = null
                    i++
                    continue
                }
                when {
                    ch == '\'' || ch == '"' -> quote = ch
                    ch == '[' -> depth++
                    ch == ']' -> {
                        depth--
                        if (depth < 0) throw XmlPathException("XPath 括号不匹配")
                    }
                    ch == '/' && depth == 0 -> break
                }
                i++
            }
            if (depth != 0) throw XmlPathException("XPath 括号不匹配")
            if (quote != null) throw XmlPathException("XPath 引号未闭合")
            val seg = s.substring(start, i)
            if (seg.isEmpty()) throw XmlPathException("XPath 出现空的步")
            steps.add(parseStep(seg))
            if (i < s.length) {
                if (i + 1 < s.length && s[i + 1] == '/') {
                    steps.add(Step(Axis.DESCENDANT_OR_SELF, NodeTest.AnyNode, emptyList()))
                    i += 2
                } else {
                    i += 1
                }
                if (i >= s.length) throw XmlPathException("XPath 不能以 / 结尾")
            }
        }
        return steps
    }

    private fun parseStep(seg: String): Step {
        if (seg == ".") return Step(Axis.SELF, NodeTest.AnyNode, emptyList())
        if (seg == "..") return Step(Axis.PARENT, NodeTest.AnyNode, emptyList())
        var k = 0
        var axis = Axis.CHILD
        var explicitAxis = false
        // 轴前缀只可能在第一个谓词方括号之前（`[text()='a::b']` 里的 :: 是字面量）
        val firstBracket = seg.indexOf('[')
        val dc = seg.indexOf("::").let { if (it >= 0 && (firstBracket < 0 || it < firstBracket)) it else -1 }
        if (dc >= 0) {
            axis = Axis.ofName(seg.substring(0, dc).trim())
            explicitAxis = true
            k = dc + 2
            if (k >= seg.length) throw XmlPathException("XPath 轴缺少节点测试：$seg")
        }
        if (seg[k] == '@') {
            if (explicitAxis) throw XmlPathException("XPath 轴后面不能再带 @：$seg")
            axis = Axis.ATTRIBUTE
            k++
        }
        if (k >= seg.length) throw XmlPathException("XPath 步缺少名字")
        val bracket = seg.indexOf('[', k)
        val testEnd = if (bracket < 0) seg.length else bracket
        val nodeTest = parseNodeTest(seg.substring(k, testEnd).trim(), seg)
        return Step(axis, nodeTest, parsePredicates(seg, testEnd))
    }

    private fun parseNodeTest(raw: String, seg: String): NodeTest = when (raw) {
        "" -> throw XmlPathException("XPath 步缺少名字：$seg")
        "*" -> NodeTest.AnyName
        "text()" -> NodeTest.TextNode
        "comment()" -> NodeTest.CommentNode
        "node()" -> NodeTest.AnyNode
        else -> when {
            // 带前缀的名字测试：.NET 无 namespace manager ⇒ 恒抛 XPathException（见类 KDoc），
            // 原版包成 CodeEE。修复前这里按字面量匹配 ⇒ 恒 0 命中而不报错（FN-10 第一条）。
            ':' in raw -> throw XmlPathException(qualifiedNameMessage(raw))
            raw.endsWith(")") -> throw XmlPathException("不支持的 XPath 节点测试：$raw")
            else -> NodeTest.Name(raw)
        }
    }

    /**
     * 限定名（含冒号的名字）→ 报错文案。
     *
     * 合法限定名（`前缀:本地` 或 `前缀:*`，两侧都是 NCName）在 .NET 走「没有 namespace
     * manager」这条：`[XmlNamespaceManager]` 缺失 ⇒ XPathException 文案恒为
     * [NS_NAMESPACE_MANAGER_MSG]（与是否声明过该前缀无关，dotnet 8.0.31 实测）。畸形限定名
     * （空本地名、空前缀、多冒号、含空白）是另一族语法错误，文案带位置且分两种，见类 KDoc 残差。
     */
    private fun qualifiedNameMessage(raw: String): String {
        val first = raw.indexOf(':')
        val prefix = raw.substring(0, first)
        val local = raw.substring(first + 1)
        val singleColon = raw.indexOf(':', first + 1) < 0
        val wellFormed = NC_NAME.matches(prefix) &&
            (local == "*" || NC_NAME.matches(local)) &&
            singleColon
        if (wellFormed) return NS_NAMESPACE_MANAGER_MSG
        // 畸形：空本地名 ⇒ invalid qualified name；其余（空前缀 / 多冒号 / 空白）⇒ invalid token
        return if (local.isEmpty()) "'$raw' has an invalid qualified name."
        else "'$raw' has an invalid token."
    }

    /** NCName 近似（决定合法限定名的报错分支用）：首字符字母或下划线，其后名尾字符。 */
    private val NC_NAME = Regex("[A-Za-z_][-.A-Za-z0-9_]*")

    /** dotnet `SelectNodes(xpath)` 缺 namespace manager 时对带前缀查询抛的原样文案。 */
    private const val NS_NAMESPACE_MANAGER_MSG =
        "Namespace Manager or XsltContext needed. This query has a prefix, variable, or user-defined function."

    /**
     * 谓词里出现名字的位置统一过一遍前缀闸：带冒号 ⇒ 抛（dotnet 无 namespace manager，
     * `[@p:k='v']`、`[p:q]`、`[p:q='v']` 全报错，修复前按字面量匹配 ⇒ 恒 0 命中）。
     */
    private fun requirePlainName(name: String) {
        if (':' in name) throw XmlPathException(qualifiedNameMessage(name))
    }

    private fun parsePredicates(seg: String, from: Int): List<Predicate> {
        val out = ArrayList<Predicate>()
        var k = from
        while (k < seg.length) {
            if (seg[k] != '[') throw XmlPathException("XPath 谓词外出现多余字符：$seg")
            var depth = 0
            var quote: Char? = null
            var close = -1
            var j = k
            while (j < seg.length) {
                val ch = seg[j]
                if (quote != null) {
                    if (ch == quote) quote = null
                } else {
                    when (ch) {
                        '\'', '"' -> quote = ch
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) {
                                close = j
                                break
                            }
                        }
                    }
                }
                j++
            }
            if (close < 0) throw XmlPathException("XPath 谓词未闭合")
            out.add(parsePredicate(seg.substring(k + 1, close).trim()))
            k = close + 1
        }
        return out
    }

    private fun parsePredicate(text: String): Predicate {
        if (text.isEmpty()) throw XmlPathException("XPath 空谓词")
        if (text.all { it.isDigit() }) {
            // XPath 1.0 里 `[n]` 是 position() 比较；`[2147483648]` 是合法 Number 谓词，
            // .NET `SelectNodes` 只在语法错误时抛 XPathException（FunctionMethodCreator.cs:1710-1717）。
            // 超出 Int 或解析失败（长到溢出 Long）都按「恒不命中」处理，用 0 当哨兵（位置从 1 起）。
            val n = text.toLongOrNull()
            return Predicate.Position(if (n != null && n in 1L..Int.MAX_VALUE.toLong()) n.toInt() else 0)
        }
        if (text == "last()") return Predicate.Last
        if (text == "text()") return Predicate.HasText
        val eq = indexOfTopLevelEquals(text)
        if (eq >= 0) {
            if (eq > 0 && text[eq - 1] == '!') throw XmlPathException("不支持的 XPath 谓词：[$text]")
            val lhs = text.substring(0, eq).trim()
            val value = parseCompareValue(text.substring(eq + 1).trim())
            return when {
                lhs.startsWith("@") -> {
                    val attrName = lhs.substring(1).trim()
                    requirePlainName(attrName)
                    Predicate.AttrCompare(attrName, value)
                }
                lhs == "text()" -> Predicate.TextCompare(value)
                lhs == "." -> Predicate.SelfCompare(value)
                lhs.endsWith("()") -> throw XmlPathException("不支持的 XPath 函数：$lhs")
                lhs.isEmpty() -> throw XmlPathException("XPath 谓词左值为空：[$text]")
                else -> {
                    requirePlainName(lhs)
                    Predicate.ChildTextCompare(lhs, value)
                }
            }
        }
        if (text.startsWith("@")) {
            val attrName = text.substring(1).trim()
            requirePlainName(attrName)
            return Predicate.HasAttr(attrName)
        }
        // `[tag]` = 存在名为 tag 的子元素（XPath 1.0 的 node-set 布尔转换）
        if (NAME.matches(text)) {
            requirePlainName(text)
            return Predicate.HasChild(text)
        }
        throw XmlPathException("不支持的 XPath 谓词：[$text]")
    }

    private fun indexOfTopLevelEquals(text: String): Int {
        var quote: Char? = null
        for (k in text.indices) {
            val ch = text[k]
            if (quote != null) {
                if (ch == quote) quote = null
            } else if (ch == '\'' || ch == '"') {
                quote = ch
            } else if (ch == '=') {
                return k
            }
        }
        return -1
    }

    /**
     * 谓词右值：带引号 ⇒ 字符串；不带引号 ⇒ 必须是数值字面量（XPath 1.0 的 Number）。
     * 语料 `宴会_SUB.ERB:201-202` 的 `[text()={ARGCHR}]` 走数值这条。
     */
    private fun parseCompareValue(raw: String): CompareValue {
        if (raw.length >= 2) {
            val q = raw[0]
            if ((q == '\'' || q == '"') && raw[raw.length - 1] == q) {
                return CompareValue.Text(raw.substring(1, raw.length - 1))
            }
        }
        val num = xpathNumber(raw)
            ?: throw XmlPathException("XPath 谓词右值既不是引号字面量也不是数值：$raw")
        return CompareValue.Num(num)
    }

    // ------------------------------------------------------------------
    // 求值
    // ------------------------------------------------------------------

    private fun matchesTest(node: XmlNode, step: Step): Boolean = when (val t = step.nodeTest) {
        // node() 也走合并口径：`.NET //node()` 对 `<r><![CDATA[x]]>y</r>` 只给 2 个节点
        // （根元素 + 段首 CDATA），同段的 `y` 不单独计数（dotnet 8.0.421 实测）
        NodeTest.AnyNode -> !isTextNode(node) || startsTextRun(node)
        // text() 含 CDATA，且一个「文本/CDATA 连续段」只贡献段首节点（见类注释）
        NodeTest.TextNode -> isTextNode(node) && startsTextRun(node)
        NodeTest.CommentNode -> node is XmlComment
        NodeTest.AnyName ->
            if (step.axis == Axis.ATTRIBUTE) node is XmlAttribute else node is XmlElement
        is NodeTest.Name ->
            if (step.axis == Axis.ATTRIBUTE) {
                // 属性名到这里必无前缀（parseStep / parsePredicate 已设前缀闸）；属性不受默认
                // 命名空间影响（dotnet 8 实测），按字面名比较即可。
                node is XmlAttribute && node.name == t.name
            } else {
                node is XmlElement && matchesName(node, t.name)
            }
    }

    /**
     * 无前缀名字测试的匹配（dotnet 8.0.31 实测）：本地名相等**且**元素生效命名空间 URI 为空。
     * 带前缀的源元素（如 `p:c`）名字含冒号，与无前缀测试永不相等 ⇒ 天然不命中，符合 .NET
     * （要命中它得在查询里用前缀，而带前缀查询恒报错）。
     */
    private fun matchesName(e: XmlElement, name: String): Boolean =
        e.name == name && defaultNamespaceUri(e).isEmpty()

    /**
     * 未加前缀元素在 XPath 里的命名空间 URI = 最近的**祖先或自身**上的默认命名空间声明
     * `xmlns`（默认命名空间具有取代性、继承性；`xmlns=""` 复位为空；XML Namespaces §6）。
     * 带前缀的源元素不走这里（[matchesName] 靠名字相等已排除它们），故无需解析其前缀绑定。
     */
    private fun defaultNamespaceUri(e: XmlElement): String {
        var n: XmlNode? = e
        while (n != null) {
            if (n is XmlElement) {
                val decl = n.attribute("xmlns")
                if (decl != null) return decl.attrValue
            }
            n = n.parent
        }
        return ""
    }

    /** 子节点（文档节点/元素的 ChildNodes；属性与文本没有子节点）。 */
    private fun childNodes(node: XmlNode): List<XmlNode> = when (node) {
        is XmlDocument -> node.children
        is XmlElement -> node.children
        else -> emptyList()
    }

    /** XPath 数据模型里的文本节点：DOM 的 `#text` 与 `#cdata-section` 同归一类。 */
    private fun isTextNode(node: XmlNode): Boolean = node is XmlText || node is XmlCData

    /**
     * 该文本/CDATA 节点是否是一个「连续文本段」的首节点。
     *
     * XPath 1.0 把相邻的文本与 CDATA 合并成一个文本节点，.NET 的 `SelectNodes` 用段首那个
     * DOM 节点代表整段（dotnet 8.0.421 实测），所以后续同段节点必须跳过——否则
     * `<r>a<![CDATA[b]]>c</r>` 会给出 3 个而原版给 1 个。
     */
    private fun startsTextRun(node: XmlNode): Boolean {
        val parent = node.parentNode ?: return true
        val list = childNodes(parent)
        val idx = list.indexOfFirst { it === node }
        if (idx <= 0) return true
        return !isTextNode(list[idx - 1])
    }

    private fun descendants(node: XmlNode): List<XmlNode> {
        val out = ArrayList<XmlNode>()
        fun walk(n: XmlNode) {
            for (c in childNodes(n)) {
                out.add(c)
                walk(c)
            }
        }
        walk(node)
        return out
    }

    /**
     * 某条轴上的候选节点，**按轴方向**排列（反向轴 = 反向文档序，供位置谓词用）。
     */
    private fun axisNodes(node: XmlNode, axis: Axis): List<XmlNode> = when (axis) {
        Axis.CHILD -> childNodes(node)
        Axis.DESCENDANT -> descendants(node)
        Axis.DESCENDANT_OR_SELF -> ArrayList<XmlNode>().also { out ->
            out.add(node)
            out.addAll(descendants(node))
        }
        Axis.SELF -> listOf(node)
        Axis.PARENT -> listOfNotNull(node.parentNode)
        Axis.ANCESTOR -> {
            val out = ArrayList<XmlNode>()
            var p = node.parentNode
            while (p != null) {
                out.add(p)
                p = p.parentNode
            }
            out
        }
        Axis.ANCESTOR_OR_SELF -> {
            val out = ArrayList<XmlNode>()
            var p: XmlNode? = node
            while (p != null) {
                out.add(p)
                p = p.parentNode
            }
            out
        }
        Axis.FOLLOWING_SIBLING -> siblings(node) { list, idx -> list.subList(idx + 1, list.size) }
        Axis.PRECEDING_SIBLING ->
            siblings(node) { list, idx -> if (idx <= 0) emptyList() else list.subList(0, idx).asReversed() }
        Axis.ATTRIBUTE -> (node as? XmlElement)?.attributes?.toList() ?: emptyList()
    }

    /** 兄弟节点的公共部分：属性节点没有兄弟（.NET 里 attribute 不在 ChildNodes 里）。 */
    private inline fun siblings(
        node: XmlNode,
        slice: (List<XmlNode>, Int) -> List<XmlNode>,
    ): List<XmlNode> {
        if (node is XmlAttribute) return emptyList()
        val parent = node.parentNode ?: return emptyList()
        val list = childNodes(parent)
        val idx = list.indexOfFirst { it === node }
        if (idx < 0) return emptyList()
        return slice(list, idx)
    }

    /**
     * 节点字符串值（XPath 1.0 string-value）：元素/文档 = InnerText 拼接，
     * 文本/CDATA/属性 = 自身内容，注释/PI = 正文。
     */
    private fun stringValue(node: XmlNode): String = when (node) {
        is XmlComment -> node.data
        is XmlProcessingInstruction -> node.data
        else -> node.innerText
    }

    /**
     * 上下文节点是否有某个**文本段**（子节点里相邻 text/CDATA 合并的一段）的字符串值与 [value]
     * 相等（dotnet 8.0.31 实测的 `text()='v'` 谓词逐段语义）。属性/文本/注释等非容器节点没有
     * 子文本段 ⇒ 恒不命中（含 `[text()='']` 对无文本子节点的元素，dotnet 实测 0）。
     * 数值右值（`[text()=5]`）同样逐段走 [compare] 的 number 分支。
     */
    private fun nodeHasTextSegment(node: XmlNode, value: CompareValue): Boolean {
        val kids = childNodes(node)
        var idx = 0
        while (idx < kids.size) {
            if (!isTextNode(kids[idx])) {
                idx++
                continue
            }
            val sb = StringBuilder()
            var j = idx
            while (j < kids.size && isTextNode(kids[j])) {
                sb.append(textSegmentValue(kids[j]))
                j++
            }
            if (compare(sb.toString(), value)) return true
            idx = j
        }
        return false
    }

    /** 单个文本/CDATA 节点的段内容（合并到 [nodeHasTextSegment] 的段串里）。 */
    private fun textSegmentValue(node: XmlNode): String = when (node) {
        is XmlText -> node.text
        is XmlCData -> node.data
        else -> ""
    }

    private fun applyPredicates(nodes: List<XmlNode>, predicates: List<Predicate>): List<XmlNode> {
        var cur = nodes
        for (p in predicates) {
            cur = when (p) {
                is Predicate.Position -> if (p.n in 1..cur.size) listOf(cur[p.n - 1]) else emptyList()
                Predicate.Last -> if (cur.isEmpty()) emptyList() else listOf(cur.last())
                is Predicate.HasAttr -> cur.filter { (it as? XmlElement)?.attribute(p.name) != null }
                is Predicate.HasChild -> cur.filter { n ->
                    n is XmlElement && n.children.any { it is XmlElement && matchesName(it, p.name) }
                }
                Predicate.HasText -> cur.filter { n ->
                    // [text()] =「有文本节点子」；CDATA 也算（dotnet 8.0.421 实测）
                    n is XmlElement && n.children.any { isTextNode(it) }
                }
                is Predicate.AttrCompare -> cur.filter { n ->
                    val attr = (n as? XmlElement)?.attribute(p.name) ?: return@filter false
                    compare(attr.value, p.value)
                }
                // [text()='v'] 逐个**文本段**（相邻 text/CDATA 合并成一段）比较，不是 InnerText 拼接：
                // <a>p<b/>q<c/>r</a> 的 'p'/'q'/'r' 各命中 1、'pqr' 命中 0；无文本子节点的元素对
                // 'v' 与 '' 都 0 命中；CDATA 与相邻 text 合并成一段（<a><![CDATA[p]]>q</a> 命中 'pq'）。
                is Predicate.TextCompare -> cur.filter { nodeHasTextSegment(it, p.value) }
                is Predicate.SelfCompare -> cur.filter { compare(stringValue(it), p.value) }
                is Predicate.ChildTextCompare -> cur.filter { n ->
                    n is XmlElement && n.children.any {
                        it is XmlElement && matchesName(it, p.name) && compare(stringValue(it), p.value)
                    }
                }
            }
        }
        return cur
    }

    private fun compare(actual: String, value: CompareValue): Boolean = when (value) {
        is CompareValue.Text -> actual == value.v
        // XPath 1.0：节点集与数值比较时，字符串值按 number() 转换；转换失败是 NaN。
        // 注意用原始 double 的 ==（NaN != NaN），不能拿装箱 Double.equals（那里 NaN == NaN 为真）。
        is CompareValue.Num -> {
            val n = xpathNumber(actual)
            n != null && n.toDouble() == value.v
        }
    }

    /**
     * XPath 1.0 `number(string)`：去掉首尾 XML 空白后必须是完整数值字面量；
     * 否则 NaN（`""`、`"5x"`、`"5 "` 里的 `"5x"` 都是 NaN）。
     */
    private fun xpathNumber(text: String): Double? {
        val s = text.trim(' ', '\t', '\r', '\n')
        if (s.isEmpty()) return null
        if (!NUMBER.matches(s)) return null
        return s.toDoubleOrNull()
    }

    private val NUMBER = Regex("[-+]?(\\d+(\\.\\d*)?|\\.\\d+)")

    /** 谓词里的名字候选宽匹配（允许冒号，好让 `[p:q]` 落到前缀闸报错；无冒号时是普通名）。 */
    private val NAME = Regex("[^\\s\\[\\]()=<>!|/@]+")

    // ------------------------------------------------------------------
    // 文档序
    // ------------------------------------------------------------------

    /** 文档序索引（惰性构建）：用于联合去重与多上下文步的归并。 */
    private class DocOrder(private val doc: XmlDocument) {
        private var index: IdentityHashMap<XmlNode, Int>? = null

        fun sort(nodes: List<XmlNode>): List<XmlNode> {
            if (nodes.isEmpty()) return emptyList()
            if (nodes.size == 1) return nodes
            val idx = index ?: build().also { index = it }
            val seen = IdentityHashMap<XmlNode, Boolean>(nodes.size * 2)
            val out = ArrayList<XmlNode>(nodes.size)
            for (n in nodes) if (seen.put(n, true) == null) out.add(n)
            // 文档序：元素 → 它的属性 → 它的子节点（与 XPath / .NET 一致）
            out.sortWith(Comparator { a, b ->
                (idx[a] ?: Int.MAX_VALUE).compareTo(idx[b] ?: Int.MAX_VALUE)
            })
            return out
        }

        private fun build(): IdentityHashMap<XmlNode, Int> {
            val map = IdentityHashMap<XmlNode, Int>(1024)
            var next = 0
            fun walk(n: XmlNode) {
                map[n] = next++
                if (n is XmlElement) {
                    for (a in n.attributes) map[a] = next++
                }
                for (c in childNodes(n)) walk(c)
            }
            walk(doc)
            return map
        }
    }
}
