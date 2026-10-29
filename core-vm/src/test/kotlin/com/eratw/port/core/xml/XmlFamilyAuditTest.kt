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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * XML_* 家族审计（w7-xmlaudit）：`core/xml/` 四个文件 + 内建接线逐条对拍 C#。
 *
 * 权威实现：`reference/emuera-cs/MinorShift.Emuera.GameData.Function/FunctionMethodCreator.cs`
 * - `XmlGetMethod` :69-216（`OutPutNode` :135-157 的 style 分档、`:161-172` 的键、`:195-213` 的结果写入）
 * - `XmlDocumentMethod` :1067-1145（Create 已存在返回 0、Check/Release 缺失返回 0）
 * - `XmlSetMethod` :1147-1289（`:1259-1264` 全改与 style 越界归 0、`:1282-1286` 写回）
 * - `XmlToStrMethod` :1291-1316（缺失返回空串）
 * - `XmlAddNodeMethod` :1318-1593（`:1442-1490` 插入、`:1494-1495` 位置参下标、`:1566-1570` 属性值缺省）
 * - `XmlRemoveNodeMethod` :1595-1742（`:1656-1673` 删除、`:1677` 死参数、`:1720` 全删）
 * - `XmlReplaceMethod` :1744-1904（`:1819-1828` 先解析新片段、`:1840-1844` 两参整文档替换）
 *
 * 本文件只补 `XmlMethodsTest` / `RealCorpusXmlTest` 没钉的口径（键规范化、属性插入位置与
 * 缺省值、多命中不改、写回、删除边界、替换优先级），以及 `XmlPath` 的 XPath 1.0 求值口径
 * （`text()` 步、数值谓词、轴前缀、联合、后代轴谓词作用域）。w8-xmlpath 路已把原先钉桩的
 * 四处缺口补进 `XmlPath.kt`，本文件对应的用例改成**新语义断言**并保留 C# 依据。
 *
 * 基准侧硬判据仍是 `fixtures/golden/baseline/pc-golden-root.txt:563-590`（那几条已由
 * `XmlMethodsTest` 逐行复现，本文件不重复）。
 */

private class AuditCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("AuditCtx 不应被直接调用：$name")

    override fun printSystemLine(message: String) {}
}

/** 每例一份新 store ⇒ 每例一份新 XmlPool（池挂在 store.aux 的 "xml" 槽）。 */
private fun auditEnv(): Pair<BuiltinEvalContext, VariableStore> {
    val store = VariableStore()
    val base = AuditCtx()
    base.variables = store
    // #DIMS XBUF, 20：接 XML_GET 的 RefString1D 结果
    store.declareUserVar(DimSpec("XBUF", true, intArrayOf(20)))
    // #DIMS XDOC（0 维）：接「内联源 + 写回」路径（XmlSetMethod:1284 的 SetValue）
    store.declareUserVar(DimSpec("XDOC", true, intArrayOf()))
    return BuiltinEvalContext(base, store) to store
}

private fun acall(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
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

private fun aiv(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Long =
    (acall(ctx, name, *args) as Value.IntV).v

private fun asv(ctx: BuiltinEvalContext, name: String, vararg args: Any?): String =
    (acall(ctx, name, *args) as Value.StrV).v

private fun buf() = VarRef("XBUF", emptyList(), operandType = OperandType.STR)

private fun docRef() = VarRef("XDOC", emptyList(), operandType = OperandType.STR)

private fun bufAt(store: VariableStore, index: Int): String =
    store.readStr("XBUF", longArrayOf(index.toLong()))

/** 语料游戏根：优先 `ERATW_GAMEDIR`，回落 `ERATW_CORPUS` 的父目录（闸门里两者都导出）。 */
private fun corpusGameRoot(): Path? {
    val raw = System.getenv("ERATW_GAMEDIR")?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("ERATW_CORPUS")?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val p = runCatching { Paths.get(raw).toAbsolutePath().normalize() }.getOrNull() ?: return null
    if (Files.isDirectory(p.resolve("plugins"))) return p
    // ERATW_CORPUS 指到 .../ERB 时回退一层
    val parent = p.parent ?: return null
    return if (Files.isDirectory(parent.resolve("plugins"))) parent else null
}

class XmlFamilyAuditTest {

    /**
     * `OutPutNode`（:135-157）的 style 分档：1=InnerText、2=InnerXml、3=OuterXml、4=Name，
     * 其余（0、5、负数）一律 Value；文档节点 `/` 也能取到（`SelectNodes("/")`）。
     */
    @Test
    fun `XML_GET 的 style 分档与文档节点取值`() {
        val (ctx, store) = auditEnv()
        assertEquals(
            1L,
            aiv(ctx, "XML_DOCUMENT", 1L, "<?xml version='1.0' encoding='utf_8'?><r><a id='7'>T&amp;X</a></r>"),
        )
        // style 3：文档节点的 OuterXml 带规范化声明（.NET XmlDeclaration.OuterXml 口径）
        assertEquals(1L, aiv(ctx, "XML_GET", 1L, "/", buf(), 3L))
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"utf_8\"?><r><a id=\"7\">T&amp;X</a></r>",
            bufAt(store, 0),
        )
        // style 1：文档节点的 InnerText（:143）
        assertEquals(1L, aiv(ctx, "XML_GET", 1L, "/", buf(), 1L))
        assertEquals("T&X", bufAt(store, 0))
        // style 4：节点名（:152），文档节点是 #document
        assertEquals(1L, aiv(ctx, "XML_GET", 1L, "/", buf(), 4L))
        assertEquals("#document", bufAt(store, 0))

        // 元素上逐档
        assertEquals(1L, aiv(ctx, "XML_GET", 1L, "/r/a", buf(), 1L))
        assertEquals("T&X", bufAt(store, 0))
        assertEquals(1L, aiv(ctx, "XML_GET", 1L, "/r/a", buf(), 2L))
        assertEquals("T&amp;X", bufAt(store, 0))
        assertEquals(1L, aiv(ctx, "XML_GET", 1L, "/r/a", buf(), 3L))
        assertEquals("<a id=\"7\">T&amp;X</a>", bufAt(store, 0))
        assertEquals(1L, aiv(ctx, "XML_GET", 1L, "/r/a", buf(), 4L))
        assertEquals("a", bufAt(store, 0))

        // 0 / 5 / 负数 → Value（元素 Value 为 null，落成空串；:137-138 的 (ulong)num <= 3 判定）
        for (style in listOf(0L, 5L, -1L)) {
            assertEquals(1L, aiv(ctx, "XML_GET", 1L, "/r/a", buf(), style), "style=$style")
            assertEquals("", bufAt(store, 0), "style=$style 应取 Value")
        }
        // 属性节点：Value / Name / InnerXml 三档
        assertEquals(1L, aiv(ctx, "XML_GET", 1L, "/r/a/@id", buf(), 0L))
        assertEquals("7", bufAt(store, 0))
        assertEquals(1L, aiv(ctx, "XML_GET", 1L, "/r/a/@id", buf(), 4L))
        assertEquals("id", bufAt(store, 0))
        assertEquals(1L, aiv(ctx, "XML_GET", 1L, "/r/a/@id", buf(), 2L))
        assertEquals("7", bufAt(store, 0))
    }

    /**
     * 第一参数键口径（:1111 / :1228 / :1505）：整数取 `值.ToString()`，字符串原样；
     * `XML_EXIST` / `XML_TOSTR` 没有 BYNAME 形态，但字符串参数同样按键查池，
     * 所以 `XML_EXIST("7")` 与 `XML_DOCUMENT(7, ...)` 是同一个文档。字典无比较器 ⇒ 大小写敏感。
     */
    @Test
    fun `XML 文档键是十进制文本且区分大小写`() {
        val (ctx, _) = auditEnv()
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", 7L, "<r />"))
        // 整数键 7 == 字符串键 "7"
        assertEquals(1L, aiv(ctx, "XML_EXIST", "7"))
        // 已存在 ⇒ 0 且不覆盖（:1116-1119）
        assertEquals(0L, aiv(ctx, "XML_DOCUMENT", "7", "<x />"))
        assertEquals("<r />", asv(ctx, "XML_TOSTR", "7"))
        // 大小写敏感：GD 与 gd 是两个位置
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", "GD", "<g />"))
        assertEquals(0L, aiv(ctx, "XML_EXIST", "gd"))
        assertEquals(-1L, aiv(ctx, "XML_GET_BYNAME", "gd", "/g"))
        assertEquals(1L, aiv(ctx, "XML_GET_BYNAME", "GD", "/g"))
        // 释放后 TO_STR 为空串（:1310-1313）
        assertEquals(1L, aiv(ctx, "XML_RELEASE", "GD"))
        assertEquals("", asv(ctx, "XML_TOSTR", "GD"))
        assertEquals(0L, aiv(ctx, "XML_EXIST", "GD"))
    }

    /**
     * `XML_ADDATTRIBUTE` 的参数位：第 3 参 = 属性名、第 4 参 = 值（缺省空串，:1566-1570）、
     * 第 5 参 = 插入位置（:1494-1495 `num = 5`，与 `XML_ADDNODE` 的第 4 参错开一位）。
     * 位置 1/2 = 插到**命中属性节点**的前/后（:1480-1485）。
     */
    @Test
    fun `XML_ADDATTRIBUTE 的值缺省与插入位置`() {
        val (ctx, _) = auditEnv()
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", 3L, "<r a=\"1\" />"))
        // 三参：CreateAttribute 后没赋 Value ⇒ 空串（.NET 属性值默认 ""，OuterXml 写 name=""）
        assertEquals(1L, aiv(ctx, "XML_ADDATTRIBUTE", 3L, "/r", "k"))
        assertEquals("<r a=\"1\" k=\"\" />", asv(ctx, "XML_TOSTR", 3L))
        // 四参：第 4 参是值（不是位置）
        assertEquals(1L, aiv(ctx, "XML_ADDATTRIBUTE", 3L, "/r", "v", "9"))
        assertEquals("<r a=\"1\" k=\"\" v=\"9\" />", asv(ctx, "XML_TOSTR", 3L))
        // 位置 1：插到命中的 @a 之前；位置 2：插到 @a 之后
        assertEquals(1L, aiv(ctx, "XML_ADDATTRIBUTE", 3L, "/r/@a", "b", "2", 1L))
        assertEquals("<r b=\"2\" a=\"1\" k=\"\" v=\"9\" />", asv(ctx, "XML_TOSTR", 3L))
        assertEquals(1L, aiv(ctx, "XML_ADDATTRIBUTE", 3L, "/r/@a", "c", "3", 2L))
        assertEquals("<r b=\"2\" a=\"1\" c=\"3\" k=\"\" v=\"9\" />", asv(ctx, "XML_TOSTR", 3L))
    }

    /**
     * 重复属性名：原版 `Attributes.Append`（:1478）抛 ArgumentException（不捕获 ⇒ 脚本中止），
     * 本实现抛 CodeEE（同一中止语义，文案不同，已登记在 XmlMethods.kt 文件头）。
     * 位置 >0 但命中节点不是属性 ⇒ `Insert` 返回 false（:1470-1473），单命中再走
     * `:1582-1584` 的 `return 0L`。
     */
    @Test
    fun `XML_ADDATTRIBUTE 重名与错位命中的返回值`() {
        val (ctx, _) = auditEnv()
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", 4L, "<r a=\"1\" />"))
        assertFailsWith<CodeEE> { aiv(ctx, "XML_ADDATTRIBUTE", 4L, "/r", "a", "9") }
        // 位置 1 + 命中元素（非属性）：返回 0，文档不动
        assertEquals(0L, aiv(ctx, "XML_ADDATTRIBUTE", 4L, "/r", "z", "9", 1L))
        assertEquals("<r a=\"1\" />", asv(ctx, "XML_TOSTR", 4L))
        // 位置 0 命中元素：正常追加
        assertEquals(1L, aiv(ctx, "XML_ADDATTRIBUTE", 4L, "/r", "z", "9", 0L))
        assertEquals("<r a=\"1\" z=\"9\" />", asv(ctx, "XML_TOSTR", 4L))
    }

    /**
     * `XML_SET` 多命中口径（:1266-1286）：命中 2 个且没给第 4 参 ⇒ **一个都不改**但返回命中数；
     * 第 4 参非 0 ⇒ 全改；第 5 参 style 越界归 0（:1260-1264）。
     */
    @Test
    fun `XML_SET 多命中是否全改与 style 归零`() {
        val (ctx, _) = auditEnv()
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", 5L, "<r><a>1</a><a>2</a></r>"))
        assertEquals(2L, aiv(ctx, "XML_SET", 5L, "/r/a", "Z"))
        assertEquals("<r><a>1</a><a>2</a></r>", asv(ctx, "XML_TOSTR", 5L))
        assertEquals(2L, aiv(ctx, "XML_SET", 5L, "/r/a", "Z", 1L, 1L))
        assertEquals("<r><a>Z</a><a>Z</a></r>", asv(ctx, "XML_TOSTR", 5L))
        // 单命中 + style 越界（3 → 0）：属性节点按 Value 赋值（:1215-1217）
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", 6L, "<r a=\"1\" />"))
        assertEquals(1L, aiv(ctx, "XML_SET", 6L, "/r/@a", "9", 0L, 3L))
        assertEquals("<r a=\"9\" />", asv(ctx, "XML_TOSTR", 6L))
        // 未命中 ⇒ 0，且不回写
        assertEquals(0L, aiv(ctx, "XML_SET", 6L, "/r/zzz", "9", 0L, 1L))
        assertEquals("<r a=\"9\" />", asv(ctx, "XML_TOSTR", 6L))
    }

    /**
     * 内联源写回（`XmlSetMethod:1282-1286` / `XmlReplaceMethod:1897-1900`）：
     * 第一参数是字符串变量时，改完把 `OuterXml` 写回该变量；只在**命中 >0** 时写
     * （:1266 的 `Count > 0` 包住整段）。字面量第一参数在原版是 NRE，本实现静默跳过（已登记差异）。
     */
    @Test
    fun `内联源改完写回字符串变量且未命中不回写`() {
        val (ctx, store) = auditEnv()
        store.writeStr("XDOC", longArrayOf(), "<r><a>o</a></r>")
        assertEquals(1L, aiv(ctx, "XML_SET", docRef(), "/r/a", "N", 0L, 1L))
        assertEquals("<r><a>N</a></r>", store.readStr("XDOC", longArrayOf()))
        // 未命中：返回 0，变量不动
        assertEquals(0L, aiv(ctx, "XML_SET", docRef(), "/r/zzz", "N", 0L, 1L))
        assertEquals("<r><a>N</a></r>", store.readStr("XDOC", longArrayOf()))
        // XML_REPLACE 同一条写回路径
        assertEquals(1L, aiv(ctx, "XML_REPLACE", docRef(), "/r/a", "<b>1</b>"))
        assertEquals("<r><b>1</b></r>", store.readStr("XDOC", longArrayOf()))
        // 字面量第一参数：原版 `(arguments[0] as VariableTerm)` 为 null ⇒ NRE；本实现不写回、正常返回
        assertEquals(1L, aiv(ctx, "XML_SET", "<r><a>o</a></r>", "/r/a", "N", 0L, 1L))
    }

    /**
     * `XML_REMOVENODE`（:1656-1673 / :1718-1734）：多命中且没给第 3 参 ⇒ 一个都不删但返回命中数；
     * 第 3 参非 0 ⇒ 全删；文档节点父为空 ⇒ `Remove` 返回 false ⇒ 0。
     * （:1677 那个 `arguments[3]` 在原版是死参数，本实现照抄，不观测。）
     */
    @Test
    fun `XML_REMOVENODE 多命中与文档节点边界`() {
        val (ctx, _) = auditEnv()
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", 7L, "<r><a x=\"1\">1</a><a x=\"2\">2</a></r>"))
        // 文档节点没有父节点（:1666-1671）
        assertEquals(0L, aiv(ctx, "XML_REMOVENODE", 7L, "/"))
        assertEquals("<r><a x=\"1\">1</a><a x=\"2\">2</a></r>", asv(ctx, "XML_TOSTR", 7L))
        // 多命中 + 无第 3 参：不改，返回 2
        assertEquals(2L, aiv(ctx, "XML_REMOVENODE", 7L, "/r/a"))
        assertEquals("<r><a x=\"1\">1</a><a x=\"2\">2</a></r>", asv(ctx, "XML_TOSTR", 7L))
        // 多命中 + 第 3 参非 0：全删
        assertEquals(2L, aiv(ctx, "XML_REMOVENODE", 7L, "/r/a", 1L))
        // .NET `XmlElement.IsEmpty` 由「创建路径」决定：`<r>` 是带结束标签的起始标签 ⇒ IsEmpty=false，
        // 子节点全删后仍写 `<r></r>` 而不是 `<r />`（dotnet 8.0.421 实测：同输入删光两个 a 后
        // OuterXml = `<r></r>`）。原断言按「子节点为空 ⇒ 自闭合」写，是与 .NET 不符的旧口径。
        assertEquals("<r></r>", asv(ctx, "XML_TOSTR", 7L))
    }

    /**
     * `XML_REMOVEATTRIBUTE`（:1658-1664）：命中属性才删并返回 1；命中元素 ⇒ false ⇒ 0。
     * `XML_REMOVENODE` 命中属性节点：原版 `ParentNode.RemoveChild(attribute)` 抛 ArgumentException，
     * 本实现抛 CodeEE（中止语义等价，已登记差异）。
     */
    @Test
    fun `XML_REMOVEATTRIBUTE 与删除属性节点的边界`() {
        val (ctx, _) = auditEnv()
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", 8L, "<r><a x=\"1\" y=\"2\" /></r>"))
        assertEquals(0L, aiv(ctx, "XML_REMOVEATTRIBUTE", 8L, "/r/a"))
        assertEquals(1L, aiv(ctx, "XML_REMOVEATTRIBUTE", 8L, "/r/a/@y"))
        assertEquals("<r><a x=\"1\" /></r>", asv(ctx, "XML_TOSTR", 8L))
        assertFailsWith<CodeEE> { aiv(ctx, "XML_REMOVENODE", 8L, "/r/a/@x") }
        // 抛错前没有改动（属性仍在）
        assertEquals("<r><a x=\"1\" /></r>", asv(ctx, "XML_TOSTR", 8L))
    }

    /**
     * `XML_REPLACE`（:1817-1845）：**先**解析新片段（:1819-1828），所以新片段非法时先报解析错误、
     * 不是 -1；两参形态 = 整文档替换（换成新文档，声明跟着新文本走）；三参形态替换命中元素，
     * 目标文档的声明保留。
     */
    @Test
    fun `XML_REPLACE 两参整文档替换与解析优先级`() {
        val (ctx, _) = auditEnv()
        // 两参：无声明的新文本 ⇒ 结果无声明
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", 9L, "<?xml version='1.0' encoding='utf_8'?><r><a>1</a></r>"))
        assertEquals(1L, aiv(ctx, "XML_REPLACE", 9L, "<z>9</z>"))
        assertEquals("<z>9</z>", asv(ctx, "XML_TOSTR", 9L))
        // 三参：替换根元素，声明保留
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", 10L, "<?xml version='1.0'?><r><a>1</a></r>"))
        assertEquals(1L, aiv(ctx, "XML_REPLACE", 10L, "/r", "<z>9</z>"))
        assertEquals("<?xml version=\"1.0\"?><z>9</z>", asv(ctx, "XML_TOSTR", 10L))
        // 键缺失：片段合法 ⇒ -1；片段非法 ⇒ 解析错误优先
        assertEquals(-1L, aiv(ctx, "XML_REPLACE", 99L, "/r", "<z>9</z>"))
        assertFailsWith<CodeEE> { aiv(ctx, "XML_REPLACE", 99L, "/r", "<broken") }
    }

    /**
     * XPath 1.0 里 `text()` 是合法**步**（选文本节点），不是只有谓词能用。原版走
     * `XmlDocument.SelectNodes`（`FunctionMethodCreator.cs:189`），`OutPutNode:135-157`
     * 的 style 0 取 `XmlNode.Value`，文本节点的 Value 就是文本本身。
     *
     * 语料活代码（都不在 `[SKIPSTART]` 区间内）有 11 条这么用，读成 0 会让变量读空：
     * - `ERB/CHARALIST_DT/TEMP_CHARA_LIST_BY_DT.ERB:195`（LIST_COLOR）、`:210`（PER_PAGE）、
     *   `:217`（COLUMN_GROUP_NAME）、`:222`（COLUMN_NAME）
     * - `ERB/CHARALIST_DT/TEMP_CHARA_LIST_BY_DT_FUNC.ERB:1833`（`/ROOT/id/text()`）
     * - `ERB/イベント関連/宴会.ERB:66`、`:70`（ENKAI_WEIGHT / ENKAI_ID）
     * - `ERB/ステータス表示関連/グラフィック特殊効果.ERB:113`、`:185`、`:203`、`:209`（GLIST 精灵名，
     *   其中 `:203`、`:209` 还叠了通配步 `*[1]`）
     *
     * 另有 4 条在 `ERB/イベント関連/宴会/宴会_SUB.ERB:201`、`:202`、`:267`、`:270`，但那个文件
     * 整段被 `[SKIPSTART]` 包住（`:1` 开、`:278` 闭），是死代码（见下一例）。
     */
    @Test
    fun `XPath text 步选中文本节点`() {
        val (ctx, store) = auditEnv()
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", 11L, "<ROOT><PER_PAGE>20</PER_PAGE></ROOT>"))
        assertEquals(1L, aiv(ctx, "XML_GET", 11L, "/ROOT/PER_PAGE", buf(), 1L))
        assertEquals("20", bufAt(store, 0))
        // text() 步：1 个文本节点，style 0 = Value = 文本
        assertEquals(1L, aiv(ctx, "XML_GET", 11L, "/ROOT/PER_PAGE/text()", buf(), 0L))
        assertEquals("20", bufAt(store, 0))
        // style 4 = Name：文本节点名是 #text（.NET XmlText.Name）
        assertEquals(1L, aiv(ctx, "XML_GET", 11L, "/ROOT/PER_PAGE/text()", buf(), 4L))
        assertEquals("#text", bufAt(store, 0))
        // style 3 = OuterXml：文本节点写出转义后的文本
        assertEquals(1L, aiv(ctx, "XML_GET", 11L, "/ROOT/PER_PAGE/text()", buf(), 3L))
        assertEquals("20", bufAt(store, 0))

        val doc = XmlParser.parse("<r><a>1</a><a>2</a></r>")
        val texts = XmlPath.select(doc, "/r/a/text()")
        assertEquals(2, texts.size)
        assertTrue(texts.all { it is XmlText }, "text() 只选文本节点")
        assertEquals(listOf("1", "2"), texts.map { it.value })
        // 只取上下文节点自己的文本子节点，不钻孙辈
        val nested = XmlParser.parse("<r><a>x<b>y</b></a></r>")
        assertEquals(listOf("x"), XmlPath.select(nested, "/r/a/text()").map { it.value })
        assertEquals(listOf("y"), XmlPath.select(nested, "/r/a/b/text()").map { it.value })
        // 没有文本子节点（纯空白在 PreserveWhitespace=false 下已丢）⇒ 0
        assertTrue(XmlPath.select(nested, "/r/text()").isEmpty())
        // comment() / node() 同样是合法步（XPath 1.0 的 NodeTest）
        val mixed = XmlParser.parse("<r><!--c--><a>1</a></r>")
        assertEquals(1, XmlPath.select(mixed, "/r/comment()").size)
        assertEquals(2, XmlPath.select(mixed, "/r/node()").size)
        // text() 也选 CDATA（XPath 数据模型里 CDATA 属于文本节点），且相邻文本/CDATA 合并成
        // 一个节点、.NET SelectNodes 返回段首的 DOM 节点（dotnet 8.0.421 实测：本输入得 1 个
        // 节点、就是 CDATA 本身）。原断言「text() 选不中 CDATA」与 .NET 不符，已按实测改钉。
        val cdata = XmlParser.parse("<r><![CDATA[x]]>y</r>")
        assertEquals(listOf("x"), XmlPath.select(cdata, "/r/text()").map { it.value })
    }

    /**
     * 位置谓词的作用域（XPath 1.0 2.5 节：谓词按**每个上下文节点**求位置）。`//` 是
     * `/descendant-or-self::node()/` 的缩写，所以 `//a[1]` 等价于
     * `descendant-or-self::node()` 接 `child::a[1]`，即「每个父的第 1 个 a 子元素」。
     *
     * 原版走 `XmlDocument.SelectNodes`（`FunctionMethodCreator.cs:189`），这里应得 2 个
     * （a1 与 a3）；w8-xmlpath 之前把后代轴拍平成候选列表再套谓词，只留全树第 1 个。
     */
    @Test
    fun `后代轴位置谓词按每个父节点求位置`() {
        val doc = XmlParser.parse("<r><g><a>1</a><a>2</a></g><g><a>3</a></g></r>")
        assertEquals(3, XmlPath.select(doc, "//a").size)
        // 每个父的第 1 个 a：a1（g1）与 a3（g2）
        assertEquals(listOf("1", "3"), XmlPath.select(doc, "//a[1]").map { it.innerText })
        // 每个父的最后一个 a：a2 与 a3
        assertEquals(listOf("2", "3"), XmlPath.select(doc, "//a[last()]").map { it.innerText })
        // 子轴形态作用域一致：每个 g 的第 1 个 a
        assertEquals(2, XmlPath.select(doc, "/r/g/a[1]").size)
        // 显式写全的等价形式，与 //a[1] 同解
        assertEquals(
            XmlPath.select(doc, "//a[1]").map { it.innerText },
            XmlPath.select(doc, "/descendant-or-self::node()/child::a[1]").map { it.innerText },
        )
    }

    /**
     * 语料 `ERB/イベント関連/宴会/宴会_SUB.ERB:201-202` 的路径形状（元素名与常量取自
     * 同文件 `:57-59`：ROOT / ENKAINAME / ENKAIMEMBER；建树方式见 `:66`、`:102-104`、
     * `:154-156`）用到三种语法。**该文件整段在 `[SKIPSTART]`（`:1` 到 `:278`）里，是死代码**，
     * 但语法本身是合法 XPath 1.0，原版 `SelectNodes`（`FunctionMethodCreator.cs:189`）能解析的
     * 我们就得能解析：
     *
     * 1. 数值比较 `[text()=5]`：谓词右值不带引号 ⇒ XPath 1.0 的 Number，按 number()
     *    （文本去掉首尾空白后是完整数值字面量）比较；原版合法，不抛。
     * 2. 轴前缀 `parent::*` / `following-sibling::*` / `preceding-sibling::*`：按轴取节点。
     * 3. 联合 `|`：两支按文档序去重合并（.NET `XmlNodeList` 也是文档序）。
     *
     * 语料把 preceding 拼成了 `preceeding-sibling`（少一个 e）。.NET 对未知轴名抛
     * XPathException，`XmlGetMethod:186-192` 再包成 CodeEE ⇒ **报错才是复刻**（ADR-005），
     * 静默 0 命中不对。
     */
    @Test
    fun `语料 宴会_SUB 的数值谓词轴前缀与联合`() {
        val doc = XmlParser.parse(
            "<ROOT><ENKAINAME>A" +
                "<ENKAIMEMBER>5</ENKAIMEMBER>" +
                "<ENKAIMEMBER>6</ENKAIMEMBER>" +
                "<ENKAIMEMBER>7</ENKAIMEMBER>" +
                "</ENKAINAME></ROOT>",
        )
        // 1) 数值谓词：文本 "5" 按 number() 比较，命中 1 个；50 不命中；非数值右值是语法错
        assertEquals(listOf("5"), XmlPath.select(doc, "/ROOT//ENKAIMEMBER[text()=5]").map { it.innerText })
        assertTrue(XmlPath.select(doc, "/ROOT//ENKAIMEMBER[text()=50]").isEmpty())
        assertFailsWith<XmlPathException> { XmlPath.select(doc, "/ROOT//ENKAIMEMBER[text()=A]") }
        // 数值形态也适用于属性谓词与子元素谓词
        val attrs = XmlParser.parse("<r><a n='5' /><a n='6' /></r>")
        assertEquals(1, XmlPath.select(attrs, "/r/a[@n=5]").size)
        assertEquals(1, XmlPath.select(doc, "/ROOT/ENKAINAME[ENKAIMEMBER=6]").size)
        // 同一族的布尔形态：存在子元素 / 存在文本子节点 / 取自身字符串值
        assertEquals(1, XmlPath.select(doc, "/ROOT/ENKAINAME[ENKAIMEMBER]").size)
        assertEquals(1, XmlPath.select(doc, "/ROOT/ENKAINAME[text()]").size)
        assertEquals(listOf("6"), XmlPath.select(doc, "/ROOT//ENKAIMEMBER[.='6']").map { it.innerText })
        // 谓词字面量里的 :: 不是轴前缀
        assertEquals(1, XmlPath.select(XmlParser.parse("<r><a k='x::y'>1</a></r>"), "/r/a[@k='x::y']").size)

        // 2) 轴前缀：parent::* 取父元素，再取它的文本子节点（宴会クラスター名）
        assertEquals(
            listOf("A"),
            XmlPath.select(doc, "/ROOT//ENKAIMEMBER[text()=5]/parent::*/text()").map { it.value },
        )
        // following-sibling::* / preceding-sibling::*（正确拼写）
        assertEquals(
            listOf("6", "7"),
            XmlPath.select(doc, "/ROOT//ENKAIMEMBER[text()=5]/following-sibling::*/text()").map { it.value },
        )
        assertEquals(
            listOf("5", "6"),
            XmlPath.select(doc, "/ROOT//ENKAIMEMBER[text()=7]/preceding-sibling::*/text()").map { it.value },
        )
        // 首/末兄弟的对应方向为空
        assertTrue(XmlPath.select(doc, "/ROOT//ENKAIMEMBER[text()=5]/preceding-sibling::*").isEmpty())
        assertTrue(XmlPath.select(doc, "/ROOT//ENKAIMEMBER[text()=7]/following-sibling::*").isEmpty())

        // 3) 联合：两支重叠的成员 6 只出现一次，结果按文档序
        val union = "/ROOT//ENKAIMEMBER[text()=7]/preceding-sibling::*/text()" +
            " | /ROOT//ENKAIMEMBER[text()=5]/following-sibling::*/text()"
        assertEquals(listOf("5", "6", "7"), XmlPath.select(doc, union).map { it.value })

        // 语料的拼写错误：原版抛 XPathException ⇒ 内建层包成 CodeEE（XmlMethods.kt:204-208）
        assertFailsWith<XmlPathException> {
            XmlPath.select(doc, "/ROOT//ENKAIMEMBER[text()=5]/preceeding-sibling::*/text()")
        }
        val (ctx, store) = auditEnv()
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", 13L, "<ROOT><CHR>5</CHR></ROOT>"))
        assertEquals(1L, aiv(ctx, "XML_GET", 13L, "/ROOT/CHR[text()=5]/text()", buf()))
        assertEquals("5", bufAt(store, 0))
        assertFailsWith<CodeEE> {
            aiv(ctx, "XML_GET", 13L, "/ROOT/CHR[text()=5]/preceeding-sibling::*")
        }
    }

    /**
     * `XML_ADDNODE` 位置 1/2 走 `node.ParentNode.InsertBefore/After`（:1451-1464）。
     * 命中根元素时父节点是文档节点（非 null），而 .NET `XmlDocument` 只允许一个 DocumentElement，
     * 该调用抛 `InvalidOperationException`，原版把它包成 CodeEE 并中止脚本。
     *
     * M9 已按原版补齐该守卫：不再产出**双根**文档，改为抛 CodeEE 且文档不被改写。
     * 依据 `FunctionMethodCreator.cs:1451-1464` + `:1456` 的 `XmlDocument.InsertBefore`。
     */
    @Test
    fun `根元素上按位置插节点抛 CodeEE 且文档不被改写`() {
        val (ctx, _) = auditEnv()
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", 12L, "<r><a /></r>"))
        assertFailsWith<CodeEE> { aiv(ctx, "XML_ADDNODE", 12L, "/r", "<x />", 1L) }
        // 原文档保持单根且仍可解析
        assertEquals("<r><a /></r>", asv(ctx, "XML_TOSTR", 12L))
        assertNotNull(XmlParser.parse(asv(ctx, "XML_TOSTR", 12L)).root)
    }

    /**
     * 语料 `plugins/` 下的 XML 只读装载（含 3 个带 UTF-8 BOM 的文件）：
     * 原版走 `File.ReadAllText`（BOM 在读文本层被消费）+ `XmlDocument.LoadXml(string)`，
     * 所以解析层见不到前导符。本实现的 `XmlParser` 自己剥唯一前导 BOM（`XmlParser.kt:35`），
     * 于是直接喂原始文本也能装；`XML_DOCUMENT` + `XML_GET_BYNAME` + `XML_TOSTR` 全链路
     * 条目数用**原始文本里的标签计数**做独立 oracle。
     */
    @Test
    fun `语料 plugins 下带 BOM 的 XML 可直接装载`() {
        val root = corpusGameRoot()
        Assumptions.assumeTrue(root != null, "缺 ERATW_GAMEDIR/ERATW_CORPUS：跳过语料 XML 装载")
        val plugins = root!!.resolve("plugins")
        val files = Files.list(plugins).use { s ->
            s.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().lowercase(Locale.ROOT).endsWith(".xml") }
                .sorted(compareBy { it.fileName.toString() })
                .toList()
        }
        Assumptions.assumeTrue(files.isNotEmpty(), "plugins 下无 xml：跳过语料装载")
        val (ctx, store) = auditEnv()
        var bomFiles = 0
        for (f in files) {
            val raw = Files.readString(f, StandardCharsets.UTF_8)
            val name = f.fileName.toString()
            if (raw.isNotEmpty() && raw[0] == '\uFEFF') bomFiles++
            // 1) 原始文本（可能带 BOM）能装，且产物不带前导符
            val doc = XmlParser.parse(raw)
            assertNotNull(doc.root, "$name 没有根元素")
            assertTrue(doc.outerXml().isNotEmpty() && doc.outerXml()[0] != '\uFEFF', "$name 产物仍以 BOM 开头")
            // 2) 内建全链路：装池 → 按名查询 → OuterXml
            val key = "PL_$name"
            assertEquals(1L, aiv(ctx, "XML_DOCUMENT", key, raw), "$name 装池失败")
            assertEquals(1L, aiv(ctx, "XML_EXIST", key), "$name 池里查不到")
            assertTrue(asv(ctx, "XML_TOSTR", key).isNotEmpty(), "$name XML_TOSTR 为空")
            assertEquals(1L, aiv(ctx, "XML_RELEASE", key), "$name 释放失败")
        }
        // 语料事实：plugins 下 4 个 xml，其中 schema / tw_csv_chs / tw_taste_chs 带 BOM
        assertEquals(4, files.size, "plugins 下 xml 文件数")
        assertEquals(3, bomFiles, "带 BOM 的 xml 数")

        // 独立 oracle：<map> 根文件里 <p> 的文本计数 == XML_GET 的命中数
        val mapFile = files.first { it.fileName.toString() == "tw_csv_chs.xml" }
        val mapRaw = Files.readString(mapFile, StandardCharsets.UTF_8)
        val pCount = "<p>".toRegex().findAll(mapRaw).count()
        assertTrue(pCount > 0, "tw_csv_chs.xml 里没有 <p>")
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", "TW_MAP", mapRaw))
        assertEquals(pCount.toLong(), aiv(ctx, "XML_GET_BYNAME", "TW_MAP", "/map/p"))
        // 首个 <p> 是 <k> </k><v> </v>：纯空白文本节点在 PreserveWhitespace=false 下被丢弃，
        // 但 `<k>` 的 .NET `XmlElement.IsEmpty` 已经是 false（带结束标签的起始标签），
        // 所以 OuterXml 写 `<k></k>` 而不是 `<k />`（dotnet 8.0.421 实测：语料同段
        // `/map/p[1]/k` 的 OuterXml = `<k></k>`、InnerText = ""）。
        assertEquals(1L, aiv(ctx, "XML_GET_BYNAME", "TW_MAP", "/map/p[1]/k", buf(), 3L))
        assertEquals("<k></k>", bufAt(store, 0))
        assertEquals(1L, aiv(ctx, "XML_RELEASE", "TW_MAP"))
    }

    /**
     * 端到端：`TEMP_CHARA_LIST_BY_DT_FUNC.ERB:860-905` 的建树时序，配
     * `TEMP_CHARA_LIST_BY_DT.ERB:194-230` 的读取路径。元素名 `XML_PER_PAGE` /
     * `XML_COLUMN_GROUP` / `XML_COLUMN_NAME` / `XML_LIST_COLOR` 与属性名
     * `CG_NAME_FIX` / `VAR` / `LEN` / `TYPE` / `FIX` 取自 `TEMP_CHARA_LIST_BY_DT.ERH:85-103`。
     *
     * 这 4 条带 `text()` 步的读取（`:195`、`:210`、`:217`、`:222`）在补 `text()` 步之前全部
     * 读 0，于是 `PER_PAGE`、`COLUMN_GROUP_NAME`、`COLUMN_NAME`、`LIST_COLOR` 全空。
     */
    @Test
    fun `语料角色列表 XML 的 text 步端到端`() {
        val (ctx, store) = auditEnv()
        // RESULTS 是系统 1 维字符串数组（SystemLayout.kt:56 → VC.RESULTS），不要重新声明
        val results = VarRef("RESULTS", emptyList(), operandType = OperandType.STR)
        fun result0() = store.readStr("RESULTS", longArrayOf(0))
        // L_XML 是字符串变量：XML_ADDNODE 就地改写并写回（原版 :1282-1286 同路）
        store.declareUserVar(DimSpec("LIST_BY_DT_XML", true, intArrayOf()))
        store.writeStr("LIST_BY_DT_XML", longArrayOf(), "<ROOT />")
        val listRef = VarRef("LIST_BY_DT_XML", emptyList(), operandType = OperandType.STR)

        // 863：每页条数
        assertEquals(1L, aiv(ctx, "XML_ADDNODE", listRef, "/ROOT", "<XML_PER_PAGE>20</XML_PER_PAGE>"))
        // 870：列组；元素的直接文本是组名，另带 CG_NAME_FIX 属性
        assertEquals(
            1L,
            aiv(
                ctx, "XML_ADDNODE", listRef, "/ROOT",
                "<XML_COLUMN_GROUP CG_NAME_FIX='1'>名前</XML_COLUMN_GROUP>",
            ),
        )
        // 875-879：组内列 + 4 个属性（位置 0、命中多个时全改 1）
        assertEquals(
            1L,
            aiv(
                ctx, "XML_ADDNODE", listRef, "/ROOT/XML_COLUMN_GROUP[last()]",
                "<XML_COLUMN_NAME>体力</XML_COLUMN_NAME>", 0L, 1L,
            ),
        )
        val col = "/ROOT/XML_COLUMN_GROUP[last()]/XML_COLUMN_NAME[last()]"
        assertEquals(1L, aiv(ctx, "XML_ADDATTRIBUTE", listRef, col, "VAR", "体力", 0L, 1L))
        assertEquals(1L, aiv(ctx, "XML_ADDATTRIBUTE", listRef, col, "LEN", "6", 0L, 1L))
        assertEquals(1L, aiv(ctx, "XML_ADDATTRIBUTE", listRef, col, "TYPE", "1", 0L, 1L))
        assertEquals(1L, aiv(ctx, "XML_ADDATTRIBUTE", listRef, col, "FIX", "0", 0L, 1L))
        // 904：列表色
        assertEquals(1L, aiv(ctx, "XML_ADDNODE", listRef, "/ROOT", "<XML_LIST_COLOR>255</XML_LIST_COLOR>"))

        // 194/195：FOR 上界 + 每个 /ROOT/XML_LIST_COLOR[n]/text()
        assertEquals(1L, aiv(ctx, "XML_GET", listRef, "/ROOT/XML_LIST_COLOR"))
        assertEquals(1L, aiv(ctx, "XML_GET", listRef, "/ROOT/XML_LIST_COLOR[1]/text()", results))
        assertEquals("255", result0())
        // 210：/ROOT/XML_PER_PAGE/text() ⇒ PER_PAGE
        assertEquals(1L, aiv(ctx, "XML_GET", listRef, "/ROOT/XML_PER_PAGE/text()", results))
        assertEquals("20", result0())
        // 216/217：组数 + 组名
        assertEquals(1L, aiv(ctx, "XML_GET", listRef, "/ROOT/XML_COLUMN_GROUP"))
        assertEquals(1L, aiv(ctx, "XML_GET", listRef, "/ROOT/XML_COLUMN_GROUP[1]/text()", results))
        assertEquals("名前", result0())
        // 221/222：列数 + 列名
        assertEquals(1L, aiv(ctx, "XML_GET", listRef, "/ROOT/XML_COLUMN_GROUP[1]/XML_COLUMN_NAME"))
        assertEquals(
            1L,
            aiv(
                ctx, "XML_GET", listRef,
                "/ROOT/XML_COLUMN_GROUP[1]/XML_COLUMN_NAME[1]/text()", results,
            ),
        )
        assertEquals("体力", result0())
        // 224-230：同一元素的属性，style 1 = InnerText（属性节点上等于值）
        assertEquals(
            1L,
            aiv(
                ctx, "XML_GET", listRef,
                "/ROOT/XML_COLUMN_GROUP[1]/XML_COLUMN_NAME[1]/@VAR", results, 1L,
            ),
        )
        assertEquals("体力", result0())
        assertEquals(
            1L,
            aiv(
                ctx, "XML_GET", listRef,
                "/ROOT/XML_COLUMN_GROUP[1]/XML_COLUMN_NAME[1]/@LEN", results, 1L,
            ),
        )
        assertEquals("6", result0())
    }

    /**
     * 端到端（只读语料 XML）：`plugins/tw_csv_chs.xml` 是 `<map><p><k>词条</k><v>…</v></p>…`，
     * 用 `text()` 步取词条，命中数与「原始文本里非空 `<k>` 的个数」这一独立 oracle 对齐；
     * 再走一遍 `XML_DOCUMENT` + `XML_GET_BYNAME` 的内建全链路。
     */
    @Test
    fun `语料插件 XML 上的 text 步端到端`() {
        val root = corpusGameRoot()
        Assumptions.assumeTrue(root != null, "缺 ERATW_GAMEDIR/ERATW_CORPUS：跳过语料 XML")
        val file = root!!.resolve("plugins/tw_csv_chs.xml")
        Assumptions.assumeTrue(Files.isRegularFile(file), "plugins/tw_csv_chs.xml 不存在：跳过")
        val raw = Files.readString(file, StandardCharsets.UTF_8)
        val doc = XmlParser.parse(raw)

        // 第 1 个 p 的 k 只有一个空格 ⇒ 纯空白文本节点已被丢弃，没有文本子节点
        assertTrue(XmlPath.select(doc, "/map/p[1]/k/text()").isEmpty())
        // 第 3 个 p 起是真实词条：text() 命中 1 个，值与元素 InnerText 相同
        val kText = XmlPath.select(doc, "/map/p[3]/k/text()")
        assertEquals(1, kText.size)
        assertEquals(XmlPath.select(doc, "/map/p[3]/k").single().innerText, kText.single().value)
        assertTrue(kText.single().value.isNotBlank(), "第 3 个 p 的 k 应有词条文本")
        // 独立 oracle：原始文本里非空 <k>…</k> 的个数 == //k/text() 的命中数
        val nonEmptyK = Regex("<k>([^<]*)</k>").findAll(raw).count { it.groupValues[1].isNotBlank() }
        assertTrue(nonEmptyK > 0, "tw_csv_chs.xml 里没有非空 <k>")
        assertEquals(nonEmptyK, XmlPath.select(doc, "//k/text()").size)
        // 联合同一节点只留一份（文档序去重）
        assertEquals(1, XmlPath.select(doc, "/map/p[3]/k/text() | /map/p[3]/k/text()").size)

        // 内建全链路：池 → 路径 → 结果数组
        val (ctx, store) = auditEnv()
        assertEquals(1L, aiv(ctx, "XML_DOCUMENT", "TW_MAP_TXT", raw))
        assertEquals(1L, aiv(ctx, "XML_GET_BYNAME", "TW_MAP_TXT", "/map/p[3]/k/text()", buf()))
        assertEquals(kText.single().value, bufAt(store, 0))
        assertEquals(1L, aiv(ctx, "XML_RELEASE", "TW_MAP_TXT"))
    }
}
