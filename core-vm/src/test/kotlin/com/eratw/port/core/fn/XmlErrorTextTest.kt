package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.DimSpec
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XML 家族文案钉（2026-11-04 lane errtext）—— `core/fn/XmlMethods.kt` 的两条通道对账。
 *
 * ## 口径（两份官方表在这里**分岔**，所以必须分流）
 * | 键 | 日文 `Lang.cs` | 语料根 `lang/emuera.zhs.xml` | reference 表 | 原版抛出点（本机可核） |
 * |---|---|---|---|---|
 * | XmlGetError | :1330 | :770 | :287 | FunctionMethodCreator.cs:183（XML_GET 现场解析）/ :1127（XML_DOCUMENT 建池） |
 * | XmlGetPathError | :1334 | :771 | :288 | FunctionMethodCreator.cs:193 |
 * | XmlParseError | :1374 | :772 = `函数{0}:"{1}"解析错误:{2}` | :298 = `{0}函数:"{1}"解析错误:{2}` | FunctionMethodCreator.cs:1246/1523/1551/1705/1827/1857/3063 |
 * | XmlXPathParseError | :1378 | :773 = `函数{0}:XPath"{1}"解析错误:{2}` | :299 = `{0}函数:XPath"{1}"解析错误:{2}` | FunctionMethodCreator.cs:1257/1534/1716/1868 |
 *
 * ⇒ **XML_GET 家族**（含 `XML_GET_BYNAME` 与 `XML_DOCUMENT`，官方把函数名烤死在模板里、
 * 没有 `{0}`）取两表同文的官方串；**其余函数**的前缀形态两表不同文，本波沿用与 reference 表
 * 同形的既有串，等组长裁定语料根表形（`函数{0}:`）还是 reference 表形（`{0}函数:`）。
 *
 * ## 上报取证
 * `信息：XML_GET函数:""解析错误:缺少根元素（位置 0）` 29 份（0.2.32×26、0.2.3×3）——
 * 移植侧漏了官方模板里的「的」，收口后为 `XML_GET函数:""的解析错误:…`。
 *
 * ## 本波不碰的一半
 * 冒号后的**原因子串**（`缺少根元素（位置 0）`）是移植侧 `core/xml/XmlParser` 自造的
 * 中文诊断，原版是 .NET `XmlException.Message`（英文，如
 * `Data at the root level is invalid. Line 1, position 1.`，见 `fixtures/golden/GOLDEN_10_XMLMAP.ERB:95`
 * 的取证注释）⇒ 属「无官方 Lang 键、只有运行时异常文本」一族，列进交付报告交组长裁定，
 * 本波只对齐**前缀**这一段。
 */
class XmlErrorTextTest {

    // ------------------------------------------------------------------
    // 一、组装函数纯对账
    // ------------------------------------------------------------------

    @Test
    fun `XML_GET 家族解析错误逐字等于官方 XmlGetError`() {
        assertEquals(
            "XML_GET函数:\"<a/>\"的解析错误:缺少根元素（位置 0）",
            xmlParseErrorMessage("XML_GET", "<a/>", "缺少根元素（位置 0）"),
        )
        // 官方模板把「XML_GET」烤死，所以 BYNAME / DOCUMENT 也走同一段前缀
        assertEquals(
            "XML_GET函数:\"\"的解析错误:空文档",
            xmlParseErrorMessage("XML_GET", "", "空文档"),
            "空源文本原样回填，不 trim、不加引号形态（照抄纪律）",
        )
    }

    @Test
    fun `XML_GET 家族路径错误逐字等于官方 XmlGetPathError`() {
        assertEquals(
            "XML_GET函数:XPath\"/r/[\"的解析错误:XPath 括号不匹配",
            xmlPathErrorMessage("XML_GET", "/r/[", "XPath 括号不匹配"),
        )
        assertTrue(
            xmlPathErrorMessage("XML_GET", "", "XPath 为空").startsWith("XML_GET函数:XPath\"\""),
            "XPath 前缀紧贴双引号（官方模板没有冒号后的空格）",
        )
    }

    @Test
    fun `非 XML_GET 家族沿用与 reference 表同形的既有串`() {
        assertEquals(
            "XML_SET函数:\"<a/>\"解析错误:缺少根元素（位置 0）",
            xmlParseErrorMessage("XML_SET", "<a/>", "缺少根元素（位置 0）"),
            "reference 表 :298 = {0}函数:\"{1}\"解析错误:{2}；语料根表 :772 是 函数{0}: 形 ⇒ 待裁定",
        )
        assertEquals(
            "XML_ADDNODE函数:XPath\"/r\"解析错误:XPath 为空",
            xmlPathErrorMessage("XML_ADDNODE", "/r", "XPath 为空"),
            "reference 表 :299 同形",
        )
        assertFalse(
            xmlParseErrorMessage("XML_SET", "x", "y").contains("的解析错误"),
            "非官方键支不许被半程对齐（两表不同文 ⇒ 本波不动）",
        )
    }

    // ------------------------------------------------------------------
    // 二、接线：真实内建调用上屏的前缀
    // ------------------------------------------------------------------

    @Test
    fun `XML_DOCUMENT 现场解析失败的前缀是官方 XmlGetError`() {
        val (ctx, _) = xmlEnv()
        val e = assertFailsWith<CodeEE> { xiv(ctx, "XML_DOCUMENT", 13L, "<broken") }
        assertTrue(
            e.message!!.startsWith("XML_GET函数:\"<broken\"的解析错误:"),
            "实际：${e.message}",
        )
        // 抛错不留半成品（与 XmlMethodsTest 的同一条判据对齐，这里只复核文案没改判性）
        assertEquals(0L, xiv(ctx, "XML_EXIST", 13L))
    }

    @Test
    fun `XML_GET 的源文本支与路径支分别落两个官方键`() {
        val (ctx, _) = xmlEnv()
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", "GDOC", "<r />"))
        // 非 BYNAME 的字符串表达式按 XML 源文本解析 ⇒ 源文本支（XmlGetError）
        val src = assertFailsWith<CodeEE> { xiv(ctx, "XML_GET", "GDOC", "/r", refS("XBUF")) }
        assertTrue(src.message!!.startsWith("XML_GET函数:\"GDOC\"的解析错误:"), "实际：${src.message}")
        // 文档在手、路径非法 ⇒ 路径支（XmlGetPathError）
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 5L, "<r><a>1</a></r>"))
        val path = assertFailsWith<CodeEE> { xiv(ctx, "XML_GET", 5L, "", refS("XBUF")) }
        assertTrue(path.message!!.startsWith("XML_GET函数:XPath\"\"的解析错误:"), "实际：${path.message}")
    }

    @Test
    fun `XML_SET 家族仍用既有前缀不被本波改动`() {
        val (ctx, _) = xmlEnv()
        assertEquals(1L, xiv(ctx, "XML_DOCUMENT", 6L, "<r><a>1</a></r>"))
        val e = assertFailsWith<CodeEE> { xiv(ctx, "XML_SET", 6L, "", "x") }
        assertTrue(e.message!!.startsWith("XML_SET函数:XPath\"\"解析错误:"), "实际：${e.message}")
    }

    // ------------------------------------------------------------------
    // 三、最小内建装置（与 XmlMethodsTest 的私有装置同构，刻意不复用：两个文件各自独立可跑）
    // ------------------------------------------------------------------

    private class Ctx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("XmlErrorTextTest 不应被直接调用：$name")

        override fun printSystemLine(message: String) {}
    }

    private fun xmlEnv(): Pair<BuiltinEvalContext, VariableStore> {
        val store = VariableStore()
        val base = Ctx()
        base.variables = store
        store.declareUserVar(DimSpec("XBUF", true, intArrayOf(20)))
        return BuiltinEvalContext(base, store) to store
    }

    private fun xiv(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Long {
        val value = ctx.callFunctionRef(
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
        return (value as Value.IntV).v
    }

    private fun refS(name: String) = VarRef(name, emptyList(), operandType = OperandType.STR)
}
