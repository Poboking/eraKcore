package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.Expr
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.xml.XmlAttribute
import com.eratw.port.core.xml.XmlCData
import com.eratw.port.core.xml.XmlComment
import com.eratw.port.core.xml.XmlDocument
import com.eratw.port.core.xml.XmlElement
import com.eratw.port.core.xml.XmlNode
import com.eratw.port.core.xml.XmlParseException
import com.eratw.port.core.xml.XmlParser
import com.eratw.port.core.xml.XmlPath
import com.eratw.port.core.xml.XmlPathException
import com.eratw.port.core.xml.XmlPool
import com.eratw.port.core.xml.XmlProcessingInstruction
import com.eratw.port.core.xml.XmlText

/**
 * XML 文档池家族（[registerXmlMethods]）。**文件所有者：A2**（M3-B 第二波）。
 *
 * 落地名单（18 条，`specs/builtin-functions.yaml:834-976` 全部条目）：
 * XML_DOCUMENT / XML_RELEASE / XML_EXIST / XML_GET / XML_GET_BYNAME / XML_SET /
 * XML_SET_BYNAME / XML_TOSTR / XML_ADDNODE / XML_ADDNODE_BYNAME / XML_ADDATTRIBUTE /
 * XML_ADDATTRIBUTE_BYNAME / XML_REMOVENODE / XML_REMOVENODE_BYNAME / XML_REMOVEATTRIBUTE /
 * XML_REMOVEATTRIBUTE_BYNAME / XML_REPLACE / XML_REPLACE_BYNAME
 * （yaml 里没有 XML_DOCUMENT_BYNAME，原版注册表 `FunctionMethodCreator.cs:9226-9243` 也没有）。
 *
 * 权威实现：`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * - XmlGetMethod :69-217
 * - XmlDocumentMethod（Create/Check/Release）:1067-1146
 * - XmlSetMethod :1147-1290
 * - XmlToStrMethod :1291-1317
 * - XmlAddNodeMethod（Node/Attribute）:1318-1594
 * - XmlRemoveNodeMethod（Node/Attribute）:1595-1743
 * - XmlReplaceMethod :1744-1905
 *
 * 硬判据：`fixtures/golden/baseline/pc-golden-root.txt:563-590`（SECTION:XML / XML-SRC / XML-BYNAME），
 * 调用序列见 `fixtures/golden/GOLDEN_10_XMLMAP.ERB` 的同名分节。
 *
 * 第一参数（doc）的解析规则 —— **不是**「按名字查池」，逐条对照原版：
 * - 整数表达式 → 键 = 十进制文本，查文档池；缺失一律返回 -1（`XmlGetMethod:161-172`、
 *   `XmlSetMethod:1225-1236`、`XmlAddNodeMethod:1505-1512`、`XmlRemoveNodeMethod:1687-1694`、
 *   `XmlReplaceMethod:1834-1845`）。
 * - 字符串表达式 + 非 BYNAME → **把字符串当 XML 源文本现场解析**（`XmlGetMethod:173-185`），
 *   解析出的文档只存在于本次调用；XML_SET / XML_ADDNODE / XML_REMOVENODE / XML_REPLACE
 *   在改完后把它写回该字符串变量（`XmlSetMethod:1284-1286` 等，`(arguments[0] as VariableTerm)`）。
 *   所以 `XML_GET("GDOC", ...)` 是解析错误而不是查池（GOLDEN_10_XMLMAP.ERB 的 NOTE 已记录）。
 * - 字符串表达式 + BYNAME → 键 = 字符串本身，查文档池（区分大小写，原版 Dictionary 无比较器）。
 *
 * `XML_GET` 的第三个参数（`XmlGetMethod:195-213`）：
 * - 字符串 1 维数组引用 → 从下标 0 起按命中顺序写入（`RefString1D` 重载）；
 * - 整数表达式且非 0 → 写入 `RESULTS`（原版 `VEvaluator.RESULTS_ARRAY == DataStringArray[2]`，
 *   spec: VariableEvaluator.cs:81）；整数 0 → 不写（原版此处会 NRE，见下）。
 *
 * 已知与原版的差异（都只在原版会崩的路径上，基准不可观测）：
 * - `XML_GET` 第三参数写整数字面量 0：原版 `arguments[2] as VariableTerm` 为 null → NRE；本实现忽略。
 * - `XML_*` 第一参数是**字符串字面量**且需要写回时：原版同样 NRE；本实现不写回。
 * - `XML_REMOVENODE` 命中属性节点：原版 `ParentNode.RemoveChild(attribute)` 抛 ArgumentException；
 *   本实现抛 CodeEE（都是中止脚本，错误文案不同）。
 * - `XML_ADDNODE` 命中根元素（或命中文档节点本身）：原版 `XmlDocument` 的
 *   `AppendChild` / `InsertBefore` 抛 InvalidOperationException（第二个 DocumentElement，
 *   :1449 / :1456 / :1463）；本实现抛 CodeEE（都是中止脚本，错误文案不同）。
 * - `XML_ADDNODE` method=0 打文本/CDATA/注释/PI 节点：原版 `AppendChild` 抛
 *   InvalidOperationException（FMC.cs:1444-1448）；本实现抛 CodeEE（fixcore B2 收敛，此前静默）。
 * - `XML_ADDATTRIBUTE` method=0 打非元素节点：原版 `Attributes.Append` NRE（FMC.cs:1468-1476）；
 *   本实现抛 CodeEE（fixcore B3 收敛，此前静默）。
 */
internal fun registerXmlMethods(r: BuiltinRegistry) {
    val I = OperandType.INT
    val S = OperandType.STR

    fun intVal(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> Long) {
        r.register(BuiltinMethod(name, I, min, max) { c, a -> Value.IntV(fn(c, a)) })
    }
    fun intRef(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Expr?>) -> Long) {
        r.register(BuiltinMethod(name, I, min, max, evalRef = { c, e -> Value.IntV(fn(c, e)) }))
    }
    fun strVal(name: String, min: Int, max: Int, fn: (BuiltinContext, List<Value?>) -> String) {
        r.register(BuiltinMethod(name, S, min, max) { c, a -> Value.StrV(fn(c, a)) })
    }

    // ---- 文档池生命周期（spec: :1067-1146）----

    // Create：已存在返回 0 且不解析新文本；成功 1。解析错误走 XmlGetError 文案（:1127）。
    intVal("XML_DOCUMENT", 2, 2) { c, a ->
        val key = xmlKey(a[0])
        val pool = xmlPool(c)
        if (pool.contains(key)) {
            0L
        } else {
            pool.create(key, xmlParse(xmlStr(a[1]), "XML_GET"))
            1L
        }
    }
    // Check：存在 1 / 不存在 0（:1131-1138）
    intVal("XML_EXIST", 1, 1) { c, a -> if (xmlPool(c).contains(xmlKey(a[0]))) 1L else 0L }
    // Release：不存在 0 / 删除成功 1（:1139-1143）
    intVal("XML_RELEASE", 1, 1) { c, a -> if (xmlPool(c).release(xmlKey(a[0]))) 1L else 0L }

    // 缺失文档返回空串（:1309-1315），不是错误。
    strVal("XML_TOSTR", 1, 1) { c, a -> xmlPool(c)[xmlKey(a[0])]?.outerXml() ?: "" }

    // ---- 查询 / 修改（第一参数形态见文件头 KDoc）----

    intRef("XML_GET", 2, 4) { c, e -> xmlGet(c, e, byName = false) }
    intRef("XML_GET_BYNAME", 2, 4) { c, e -> xmlGet(c, e, byName = true) }
    intRef("XML_SET", 3, 5) { c, e -> xmlSet(c, e, byName = false) }
    intRef("XML_SET_BYNAME", 3, 5) { c, e -> xmlSet(c, e, byName = true) }
    intRef("XML_ADDNODE", 3, 5) { c, e -> xmlAddNode(c, e, byName = false, attribute = false) }
    intRef("XML_ADDNODE_BYNAME", 3, 5) { c, e -> xmlAddNode(c, e, byName = true, attribute = false) }
    intRef("XML_ADDATTRIBUTE", 3, 6) { c, e -> xmlAddNode(c, e, byName = false, attribute = true) }
    intRef("XML_ADDATTRIBUTE_BYNAME", 3, 6) { c, e -> xmlAddNode(c, e, byName = true, attribute = true) }
    intRef("XML_REMOVENODE", 2, 3) { c, e -> xmlRemoveNode(c, e, byName = false, attribute = false) }
    intRef("XML_REMOVENODE_BYNAME", 2, 3) { c, e -> xmlRemoveNode(c, e, byName = true, attribute = false) }
    intRef("XML_REMOVEATTRIBUTE", 2, 3) { c, e -> xmlRemoveNode(c, e, byName = false, attribute = true) }
    intRef("XML_REMOVEATTRIBUTE_BYNAME", 2, 3) { c, e -> xmlRemoveNode(c, e, byName = true, attribute = true) }
    intRef("XML_REPLACE", 2, 4) { c, e -> xmlReplace(c, e, byName = false) }
    // byName 重载把基类三份 ArgTypeList 整体覆盖成**一份** 4 槽 String,String,String,Int + OmitStart=3
    // （FunctionMethodCreator.cs:1787-1805，注册在 :9239）⇒ 3..4；2 参整文档替换只属 XML_REPLACE
    // （:1751-1783 第一份 ArgTypes{Any,String} + OmitStart 缺省 -1）。CMP-70 裁决：C# 是权威。
    intRef("XML_REPLACE_BYNAME", 3, 4) { c, e -> xmlReplace(c, e, byName = true) }
}

// ---------------------------------------------------------------------------
// 池 / 参数
// ---------------------------------------------------------------------------

/** 每 VM 一份的文档池（契约 §2：挂 `c.store.aux`，不用 object 单例）。 */
private fun xmlPool(c: BuiltinContext): XmlPool = c.store.aux.slot("xml") { XmlPool() }

/** 文档键：整数 → 十进制文本（原版 `GetIntValue(...).ToString()`，:1112）。 */
private fun xmlKey(v: Value?): String = when (v) {
    is Value.IntV -> v.v.toString()
    is Value.StrV -> v.v
    null -> throw CodeEE("XML 文档键参数缺失")
}

/**
 * `GetStrValue` 的等价：整数表达式取十进制文本（原版对 int 调 GetStrValue 会转成数字串），
 * 字符串原样。
 */
private fun xmlStr(v: Value?): String = when (v) {
    is Value.IntV -> v.v.toString()
    is Value.StrV -> v.v
    null -> ""
}

private fun argStr(c: BuiltinContext, e: List<Expr?>, i: Int, fn: String): String {
    val ex = e.getOrNull(i) ?: throw CodeEE("函数 $fn 的第 ${i + 1} 个参数缺失")
    return xmlStr(ex.toValue(c))
}

private fun optLong(c: BuiltinContext, e: List<Expr?>, i: Int, def: Long = 0L): Long {
    val ex = e.getOrNull(i) ?: return def
    return ex.getIntValue(c)
}

// ---------------------------------------------------------------------------
// 文档来源
// ---------------------------------------------------------------------------

/** 第一参数的两种来源：池中文档（改完不用写回）vs 现场解析的源文本（要写回变量）。 */
private sealed class XmlDocSource {
    abstract val doc: XmlDocument
    class Pooled(override val doc: XmlDocument) : XmlDocSource()
    class Inline(override val doc: XmlDocument) : XmlDocSource()
}

/**
 * 解析第一参数。返回 null = 池里没有该键（调用方按原版返回 -1）。
 *
 * @param fn      当前函数名（错误文案与写回路径用）。
 * @param msgName 解析错误的函数名前缀：XML_GET 家族固定 "XML_GET"（`Lang.Error.XmlGetError`），
 *                其余用自身名字（`Lang.Error.XmlParseError`，`base.Name`）。
 */
private fun resolveXmlDoc(
    c: BuiltinContext,
    e: List<Expr?>,
    byName: Boolean,
    fn: String,
    msgName: String,
): XmlDocSource? {
    val v = e.getOrNull(0)?.toValue(c) ?: throw CodeEE("函数 $fn 的第 1 个参数缺失")
    if (v is Value.IntV) {
        val doc = xmlPool(c)[v.v.toString()] ?: return null
        return XmlDocSource.Pooled(doc)
    }
    val text = (v as Value.StrV).v
    if (byName) {
        val doc = xmlPool(c)[text] ?: return null
        return XmlDocSource.Pooled(doc)
    }
    return XmlDocSource.Inline(xmlParse(text, msgName))
}

/** 解析错误 → CodeEE（文案格式对齐 `Lang.Error.XmlParseError` / `XmlGetError`）。 */
private fun xmlParse(text: String, msgName: String): XmlDocument = try {
    XmlParser.parse(text)
} catch (ex: XmlParseException) {
    throw CodeEE("${msgName}函数:\"$text\"解析错误:${ex.message}")
}

private fun xmlParseFragment(text: String, fn: String): List<XmlNode> = try {
    XmlParser.parseFragment(text)
} catch (ex: XmlParseException) {
    throw CodeEE("${fn}函数:\"$text\"解析错误:${ex.message}")
}

/** XPath 错误 → CodeEE（文案格式对齐 `Lang.Error.XmlXPathParseError`）。 */
private fun xmlSelect(doc: XmlDocument, path: String, fn: String): List<XmlNode> = try {
    XmlPath.select(doc, path)
} catch (ex: XmlPathException) {
    throw CodeEE("${fn}函数:XPath\"$path\"解析错误:${ex.message}")
}

/**
 * 把现场解析的文档写回第一参数（字符串变量）。池中文档不写回。
 *
 * 原版此处是 `(arguments[0] as VariableTerm).SetValue(xmlDocument.OuterXml, exm)`
 * （XmlSetMethod :1284、XmlAddNodeMethod :1588、XmlRemoveNodeMethod :1737、
 * XmlReplaceMethod :1899）：`argumentTypeArrayEx` 的第一槽是 `RefString`/`RefString1D`
 * 一类**无 CharacterData 限定**的引用（:1169、:1195 等），角色字符串变量同样经
 * `VariableToken.SetValue(string, transporter)`（VariableTerm.cs:131-152）写回。
 * 字面量参数会 NRE（见文件头「已知差异」），变量则按 [VarRef.indices] 原样写入
 * （角色字符串是 `[角色位置, 元素]` 两维，解析期已补全，spec: VariableParser.cs:34-63）。
 */
private fun xmlWriteBack(c: BuiltinContext, arg: Expr?, src: XmlDocSource) {
    if (src !is XmlDocSource.Inline) return
    val ref = arg as? VarRef ?: return
    val info = c.store.resolveVarInfo(ref.name) ?: return
    if (info.operandType != OperandType.STR) return
    val idx = LongArray(ref.indices.size) { ref.indices[it].getIntValue(c) }
    c.store.writeStr(ref.name, idx, src.doc.outerXml())
}

// ---------------------------------------------------------------------------
// XML_GET（spec: :69-217）
// ---------------------------------------------------------------------------

private fun xmlGet(c: BuiltinContext, e: List<Expr?>, byName: Boolean): Long {
    val fn = if (byName) "XML_GET_BYNAME" else "XML_GET"
    val src = resolveXmlDoc(c, e, byName, fn, "XML_GET") ?: return -1L
    val path = argStr(c, e, 1, fn)
    val nodes = xmlSelect(src.doc, path, "XML_GET")
    // style：第 4 参，缺省 0（:195）
    val style = if (e.size == 4) optLong(c, e, 3) else 0L
    if (e.size >= 3) xmlWriteGetResult(c, e[2], nodes, style)
    return nodes.size.toLong()
}

/**
 * 结果数组写入（:196-212）。字符串 1 维数组引用优先；否则整数非 0 → `RESULTS`。
 * 两者都按 `min(命中数, 目标长度)` 截断（原版 `Math.Min`）。
 */
private fun xmlWriteGetResult(c: BuiltinContext, target: Expr?, nodes: List<XmlNode>, style: Long) {
    if (target is VarRef) {
        val info = c.store.resolveVarInfo(target.name)
        if (info != null && !info.isCharacterData && info.dimension == 1 &&
            info.operandType == OperandType.STR
        ) {
            val cap = c.store.getLength(target.name)
            for (i in 0 until minOf(nodes.size, cap)) {
                c.store.writeStr(target.name, longArrayOf(i.toLong()), xmlNodeText(nodes[i], style))
            }
            return
        }
    }
    val v = target?.toValue(c) ?: return
    if (v is Value.IntV && v.v != 0L) {
        val cap = c.store.getLength("RESULTS")
        for (i in 0 until minOf(nodes.size, cap)) {
            c.store.writeStr("RESULTS", longArrayOf(i.toLong()), xmlNodeText(nodes[i], style))
        }
    }
}

/**
 * `OutPutNode`（:135-158）：style 1=InnerText、2=InnerXml、3=OuterXml、4=Name，
 * 其余（含 0 与越界）= Value。注意 style 是**从 1 开始**的，0 取的是 Value。
 */
private fun xmlNodeText(node: XmlNode, style: Long): String = when (style) {
    1L -> node.innerText
    2L -> node.innerXml()
    3L -> node.outerXml()
    4L -> node.name
    else -> node.value
}

// ---------------------------------------------------------------------------
// XML_SET（spec: :1147-1290）
// ---------------------------------------------------------------------------

private fun xmlSet(c: BuiltinContext, e: List<Expr?>, byName: Boolean): Long {
    val fn = if (byName) "XML_SET_BYNAME" else "XML_SET"
    val src = resolveXmlDoc(c, e, byName, fn, fn) ?: return -1L
    val path = argStr(c, e, 1, fn)
    val nodes = xmlSelect(src.doc, path, fn)
    // 第 4 参 = 「命中多个时是否全改」（:1259）；第 5 参 = style，越界归 0（:1260-1264）
    val applyAll = e.size >= 4 && optLong(c, e, 3) != 0L
    var style = if (e.size == 5) optLong(c, e, 4) else 0L
    if (style > 2 || style < 0) style = 0L
    val text = argStr(c, e, 2, fn)
    if (nodes.isNotEmpty()) {
        if (nodes.size != 1) {
            if (applyAll) for (n in nodes) setXmlNode(n, text, style, fn)
        } else {
            setXmlNode(nodes[0], text, style, fn)
        }
        xmlWriteBack(c, e.getOrNull(0), src)
    }
    return nodes.size.toLong()
}

/**
 * `SetNode`（:1205-1219）：style 1 = InnerText，2 = InnerXml，其余 = Value。
 * style 2 只对元素/属性可赋值；文本/CDATA/注释/PI 的 .NET `InnerXml` setter 抛
 * `InvalidOperationException`（dotnet 8 实测），本实现抛 CodeEE（中止等价）。
 *
 * style 0 落在元素上时 .NET 抛 `InvalidOperationException: Cannot set a value on node
 * type 'Element'`，原版不捕获 → 脚本中止。`GOLDEN_10_XMLMAP.ERB` 的 ENGINE-FAULT ORACLE
 * 记录的正是这条（所以基准用 style=1）。本实现抛 CodeEE，行为等价（中止），文案不同。
 */
private fun setXmlNode(node: XmlNode, text: String, style: Long, fn: String) {
    when (style) {
        1L -> when (node) {
            // .NET `XmlNode.InnerText` setter 对元素**总是**建一个文本子节点，空串也不例外
            //（dotnet 8.0.27 实测：`<a x="1">1</a>` 设 InnerText="" 后 OuterXml 是 `<a x="1"></a>`，
            // FirstChild != null）。所以这里保留空 XmlText，输出 `<a></a>` 而不是 `<a />`。
            is XmlElement -> node.replaceChildren(listOf(XmlText(text)))
            is XmlAttribute -> node.attrValue = text
            is XmlText -> node.text = text
            is XmlCData -> node.data = text
            is XmlComment -> node.data = text
            is XmlProcessingInstruction -> node.data = text
            else -> throw xmlSetValueError(node, fn)
        }
        2L -> when (node) {
            is XmlElement -> node.replaceChildren(xmlParseFragment(text, fn))
            is XmlAttribute -> node.attrValue = text
            // 文本/CDATA/注释/PI 没有子节点：.NET 的 InnerXml setter 抛 InvalidOperationException
            //（dotnet 8 实测 "Cannot set the 'InnerXml' for the current node ..."），原版不捕获 ⇒ 中止。
            else -> throw CodeEE(
                "${fn}函数:不能给节点类型 '${node.name}' 设置 InnerXml" +
                    "（原版 InvalidOperationException: Cannot set the 'InnerXml' for the current node " +
                    "because it is either read-only or cannot have children.）",
            )
        }
        else -> when (node) {
            is XmlAttribute -> node.attrValue = text
            is XmlText -> node.text = text
            is XmlCData -> node.data = text
            is XmlComment -> node.data = text
            is XmlProcessingInstruction -> node.data = text
            else -> throw xmlSetValueError(node, fn)
        }
    }
}

private fun xmlSetValueError(node: XmlNode, fn: String): CodeEE =
    CodeEE("${fn}函数:不能给节点类型 '${node.name}' 赋值（原版 InvalidOperationException）")

// ---------------------------------------------------------------------------
// XML_ADDNODE / XML_ADDATTRIBUTE（spec: :1318-1594）
// ---------------------------------------------------------------------------

private fun xmlAddNode(c: BuiltinContext, e: List<Expr?>, byName: Boolean, attribute: Boolean): Long {
    val fn = when {
        attribute && byName -> "XML_ADDATTRIBUTE_BYNAME"
        attribute -> "XML_ADDATTRIBUTE"
        byName -> "XML_ADDNODE_BYNAME"
        else -> "XML_ADDNODE"
    }
    val src = resolveXmlDoc(c, e, byName, fn, fn) ?: return -1L
    val path = argStr(c, e, 1, fn)
    val nodes = xmlSelect(src.doc, path, fn)
    // 插入位置参数下标：Node 是第 4 参、Attribute 是第 5 参（:1494-1497）
    val methodIndex = if (attribute) 5 else 4
    var method = if (e.size >= methodIndex) optLong(c, e, methodIndex - 1).toInt() else 0
    if (method > 2 || method < 0) method = 0
    if (nodes.isEmpty()) return 0L
    // 「命中多个时全部插入」：Node 第 5 参、Attribute 第 6 参（:1538-1539）
    val applyAllIndex = if (attribute) 6 else 5
    val applyAll = e.size == applyAllIndex && optLong(c, e, applyAllIndex - 1) != 0L
    val node: XmlNode = if (attribute) {
        val name = argStr(c, e, 2, fn)
        XmlAttribute(name, if (e.size >= 4) argStr(c, e, 3, fn) else "")
    } else {
        // 片段必须是一个完整元素（原版 LoadXml + DocumentElement，:1540-1562）
        val frag = xmlParse(argStr(c, e, 2, fn), fn)
        val root = frag.root ?: throw CodeEE("${fn}函数:第 3 个参数不是 XML 元素")
        cloneXmlElement(root)
    }
    if (nodes.size != 1) {
        if (applyAll) for (n in nodes) insertXmlNode(n, node, method, attribute, fn)
    } else if (!insertXmlNode(nodes[0], node, method, attribute, fn) && method > 0) {
        return 0L
    }
    xmlWriteBack(c, e.getOrNull(0), src)
    return nodes.size.toLong()
}

/**
 * `Insert`（:1442-1497）。
 * - Node：0 = AppendChild、1 = 插到命中节点之前、2 = 之后；命中节点没有父 → false。
 * - Attribute：0 = 追加到元素属性表、1/2 = 插到**命中属性节点**之前/后
 *   （命中节点不是属性且 method > 0 → false，:1468-1481）。
 *
 * 文档节点只允许一个 DocumentElement：命中根元素（父 = 文档）或命中文档节点本身时，
 * 原版的 `AppendChild` / `InsertBefore` 抛 `InvalidOperationException`（dotnet 8 实测文案
 * "This document already has a 'DocumentElement' node."，调用点在 :1449 / :1456 / :1463，
 * 不捕获 ⇒ 脚本中止）。本实现抛 CodeEE（同一中止语义，文案不同）。
 * 同型中止（2026-10-26 fixcore 审查 fn-data-B2/B3 收敛，此前静默 false 但上层仍返回 1）：
 * method=0 打文本/CDATA/注释/PI 节点（原版 InvalidOperationException）、
 * method=0 打非元素目标加属性（原版 NRE）—— 语料两类形态均 0 例。
 */
private fun insertXmlNode(target: XmlNode, child: XmlNode, method: Int, attribute: Boolean, fn: String): Boolean {
    if (!attribute) {
        return when (method) {
            0 -> {
                when (target) {
                    is XmlElement -> {
                        target.appendChild(child)
                        true
                    }
                    is XmlDocument -> throw xmlSecondRootError(fn)
                    // method=0 打文本/CDATA/注释/PI 节点：原版 node.AppendChild 对无子节点的
                    // 节点抛 InvalidOperationException、不捕获 ⇒ 中止（FMC.cs:1444-1448）。
                    // 此前 return false 且上层 method=0 不把它转 0，最终仍返回命中数 1
                    // （静默无修改）—— 收敛成同款中止（2026-10-26 fixcore 审查 fn-data-B2；
                    // 语料 XML_ADDNODE 全打元素路径，实测零影响）。
                    else -> throw CodeEE("${fn}函数:目标节点不能包含子节点")
                }
            }
            1 -> insertRelative(target, child, before = true, fn = fn)
            else -> insertRelative(target, child, before = false, fn = fn)
        }
    }
    val attr = child as? XmlAttribute ?: return false
    if (method > 0 && target !is XmlAttribute) return false
    return when (method) {
        0 -> {
            // method=0 打非元素节点：原版 node.Attributes.Append 在 Attributes == null 时 NRE、
            // 不捕获 ⇒ 中止（FMC.cs:1468-1476）。此前静默 false + 上层返回 1 —— 收敛成同款中止
            // （2026-10-26 fixcore 审查 fn-data-B3；语料 XML_ADDATTRIBUTE 全打元素路径）。
            val el = target as? XmlElement
                ?: throw CodeEE("${fn}函数:目标节点不是元素，不能追加属性")
            if (el.attribute(attr.name) != null) {
                throw CodeEE("${fn}函数:属性 ${attr.name} 已存在")
            }
            el.addAttribute(attr)
            true
        }
        1 -> {
            val at = target as XmlAttribute
            val owner = at.ownerElement ?: return false
            if (owner.attribute(attr.name) != null) {
                throw CodeEE("${fn}函数:属性 ${attr.name} 已存在")
            }
            owner.insertAttributeBefore(attr, at)
        }
        else -> {
            val at = target as XmlAttribute
            val owner = at.ownerElement ?: return false
            if (owner.attribute(attr.name) != null) {
                throw CodeEE("${fn}函数:属性 ${attr.name} 已存在")
            }
            owner.insertAttributeAfter(attr, at)
        }
    }
}

/** `XmlDocument` 的第二个 DocumentElement：原版 InvalidOperationException（:1449 / :1456 / :1463）。 */
private fun xmlSecondRootError(fn: String): CodeEE =
    CodeEE(
        "${fn}函数:不能在文档节点下插入第二个根元素" +
            "（原版 InvalidOperationException: This document already has a 'DocumentElement' node.）",
    )

private fun insertRelative(target: XmlNode, child: XmlNode, before: Boolean, fn: String): Boolean {
    val p = target.parent ?: return false
    return when (p) {
        is XmlElement -> if (before) p.insertBefore(child, target) else p.insertAfter(child, target)
        is XmlDocument -> throw xmlSecondRootError(fn)
        else -> false
    }
}

// ---------------------------------------------------------------------------
// XML_REMOVENODE / XML_REMOVEATTRIBUTE（spec: :1595-1743）
// ---------------------------------------------------------------------------

private fun xmlRemoveNode(c: BuiltinContext, e: List<Expr?>, byName: Boolean, attribute: Boolean): Long {
    val fn = when {
        attribute && byName -> "XML_REMOVEATTRIBUTE_BYNAME"
        attribute -> "XML_REMOVEATTRIBUTE"
        byName -> "XML_REMOVENODE_BYNAME"
        else -> "XML_REMOVENODE"
    }
    val src = resolveXmlDoc(c, e, byName, fn, fn) ?: return -1L
    val path = argStr(c, e, 1, fn)
    val nodes = xmlSelect(src.doc, path, fn)
    if (nodes.isEmpty()) return 0L
    // 注意：原版的第 3 参只在「命中多个」时决定是否全删（:1720），
    // 而 :1677 读到的 num 除了越界归零之外没有任何用处 —— 照抄这个死参数。
    val applyAll = e.size == 3 && optLong(c, e, 2) != 0L
    if (nodes.size != 1) {
        if (applyAll) for (n in nodes) removeXmlNode(n, attribute, fn)
    } else if (!removeXmlNode(nodes[0], attribute, fn)) {
        return 0L
    }
    xmlWriteBack(c, e.getOrNull(0), src)
    return nodes.size.toLong()
}

/** `Remove`（:1656-1673）：属性走 OwnerElement.Attributes.Remove；节点走 ParentNode.RemoveChild。 */
private fun removeXmlNode(node: XmlNode, attribute: Boolean, fn: String): Boolean {
    if (attribute) {
        val attr = node as? XmlAttribute ?: return false
        val owner = attr.ownerElement ?: return false
        return owner.removeAttribute(attr)
    }
    // 属性节点的 ParentNode 是所属元素，.NET RemoveChild(attribute) 抛 ArgumentException；
    // 原版不捕获 → 脚本中止。
    if (node is XmlAttribute) {
        throw CodeEE("${fn}函数:不能用 XML_REMOVENODE 删除属性节点（原版 ArgumentException）")
    }
    val p = node.parent ?: return false
    return when (p) {
        is XmlElement -> p.removeChild(node)
        is XmlDocument -> p.removeChild(node)
        else -> false
    }
}

// ---------------------------------------------------------------------------
// XML_REPLACE（spec: :1744-1905）
// ---------------------------------------------------------------------------

private fun xmlReplace(c: BuiltinContext, e: List<Expr?>, byName: Boolean): Long {
    val fn = if (byName) "XML_REPLACE_BYNAME" else "XML_REPLACE"
    // 新片段：3 参及以上是第 3 参，2 参形态是第 2 参（:1820）
    val newText = argStr(c, e, if (e.size > 2) 2 else 1, fn)
    // 原版先解析新片段再取文档（:1819-1828），所以解析错误优先于 -1。
    val newDoc = xmlParse(newText, fn)
    val v0 = e.getOrNull(0)?.toValue(c) ?: throw CodeEE("函数 $fn 的第 1 个参数缺失")
    val pool = xmlPool(c)
    val target: XmlDocument
    var inlineSource = false
    if (v0 is Value.IntV || (byName && v0 is Value.StrV) || (v0 is Value.StrV && e.size == 2)) {
        val key = xmlStr(v0)
        target = pool[key] ?: return -1L
        if (e.size == 2) {
            // 2 参形态 = 整文档替换（:1840-1845）
            pool.put(key, newDoc)
            return 1L
        }
    } else {
        target = xmlParse((v0 as Value.StrV).v, fn)
        inlineSource = true
    }
    val path = argStr(c, e, 1, fn)
    val nodes = xmlSelect(target, path, fn)
    if (nodes.isEmpty()) return 0L
    val root = newDoc.root ?: throw CodeEE("${fn}函数:第 3 个参数不是 XML 元素")
    val node = cloneXmlElement(root)
    val applyAll = e.size >= 4 && optLong(c, e, 3) != 0L
    if (nodes.size != 1) {
        if (applyAll) for (n in nodes) replaceXmlNode(n, node, fn)
    } else if (!replaceXmlNode(nodes[0], node, fn)) {
        return 0L
    }
    if (inlineSource) xmlWriteBack(c, e.getOrNull(0), XmlDocSource.Inline(target))
    return nodes.size.toLong()
}

/** `Replace`（:1807-1815）：父节点 ReplaceChild；无父 → false。 */
private fun replaceXmlNode(node: XmlNode, newNode: XmlNode, fn: String): Boolean {
    if (node is XmlAttribute) {
        throw CodeEE("${fn}函数:不能替换属性节点（原版 ArgumentException）")
    }
    val p = node.parent ?: return false
    return when (p) {
        is XmlElement -> {
            val idx = p.children.indexOf(node)
            if (idx < 0) {
                false
            } else {
                newNode.detach()
                p.children[idx] = newNode
                newNode.parentNode = p
                node.parentNode = null
                true
            }
        }
        is XmlDocument -> {
            val idx = p.children.indexOf(node)
            if (idx < 0) {
                false
            } else {
                newNode.detach()
                p.children[idx] = newNode
                newNode.parentNode = p
                node.parentNode = null
                true
            }
        }
        else -> false
    }
}

// ---------------------------------------------------------------------------
// 节点复制（原版 CreateNode + 拷属性 + InnerXml，:1554-1562 / :1872-1881）
// ---------------------------------------------------------------------------

private fun cloneXmlElement(src: XmlElement): XmlElement {
    val el = XmlElement(src.name)
    for (a in src.attributes) el.addAttribute(XmlAttribute(a.name, a.attrValue))
    for (c in src.children) el.appendChild(cloneXmlNode(c))
    return el
}

private fun cloneXmlNode(src: XmlNode): XmlNode = when (src) {
    is XmlElement -> cloneXmlElement(src)
    is XmlText -> XmlText(src.text)
    is XmlCData -> XmlCData(src.data)
    is XmlComment -> XmlComment(src.data)
    is XmlProcessingInstruction -> XmlProcessingInstruction(src.name, src.data)
    is XmlAttribute -> XmlAttribute(src.name, src.attrValue)
    is XmlDocument -> throw CodeEE("不能把文档节点作为子节点复制")
}
