package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.core.xml.XmlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 稳定性审查波 L5：XML 插入型 API 的整文档深度闸（[XmlMethods] 的 `guardXmlDepth`）。
 *
 * 漏洞形态：解析器只约束**单次**解析的深度（[XmlParser.MAX_ELEMENT_DEPTH]），而
 * XML_ADDNODE / XML_SET(style=2) / XML_REPLACE 可以把一棵接近上限的子树挂到已有深度的
 * 节点下，总深度随调用次数叠加。本仓的序列化与 XPath 都是递归实现，超深文档会抛
 * StackOverflowError（不是 XmlParseException / XmlPathException），不会被换成 CodeEE。
 *
 * 证伪方式：把 `guardXmlDepth` 的三处调用或函数体去掉，下面三条 `assertFailsWith<CodeEE>`
 * 会以「调用正常返回」失败；保留闸门后，边界用例（513 层元素，最深 depth = 512）与
 * 浅文档用例证明闸门不误伤。
 */
private class StabilityL5BaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("StabilityL5BaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) {}
}

private fun stabilityL5Env(): BuiltinEvalContext {
    val store = VariableStore()
    val base = StabilityL5BaseCtx()
    base.variables = store
    return BuiltinEvalContext(base, store)
}

private fun l5call(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
    ctx.callFunctionRef(
        name,
        args.map {
            when (it) {
                is Long -> IntLiteral(it)
                is Int -> IntLiteral(it.toLong())
                is String -> StrLiteral(it)
                else -> throw IllegalArgumentException("不支持的测试参数：$it")
            }
        },
    )

private fun l5iv(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Long =
    (l5call(ctx, name, *args) as Value.IntV).v

private fun l5sv(ctx: BuiltinEvalContext, name: String, vararg args: Any?): String =
    (l5call(ctx, name, *args) as Value.StrV).v

/** 解析器允许的最多元素层数：根 depth = 0，`parseElement(depth)` 在 depth > 512 时失败。 */
private const val MAX_LEVELS = XmlParser.MAX_ELEMENT_DEPTH + 1

private fun deepDoc(levels: Int): String = "<a>".repeat(levels) + "</a>".repeat(levels)

private fun deepPath(levels: Int): String = "/a".repeat(levels)

class StabilityL5XmlDepthTest {

    @Test
    fun `边界文档本身可用——513 层元素可建、可序列化`() {
        val ctx = stabilityL5Env()
        val text = deepDoc(MAX_LEVELS)
        assertEquals(1L, l5iv(ctx, "XML_DOCUMENT", 1L, text))
        // 序列化是递归的；513 层（最深 depth = 512）必须仍然安全走通。
        assertEquals(text, l5sv(ctx, "XML_TOSTR", 1L))
        // 再深一层，解析器自己的闸门仍然生效（既有行为，不回归）。
        assertFailsWith<CodeEE> { l5iv(ctx, "XML_DOCUMENT", 2L, deepDoc(MAX_LEVELS + 1)) }
    }

    @Test
    fun `XML_ADDNODE 在最深节点再挂一层元素抛 CodeEE`() {
        val ctx = stabilityL5Env()
        assertEquals(1L, l5iv(ctx, "XML_DOCUMENT", 1L, deepDoc(MAX_LEVELS)))
        assertFailsWith<CodeEE> {
            l5iv(ctx, "XML_ADDNODE", 1L, deepPath(MAX_LEVELS), "<x/>")
        }
    }

    @Test
    fun `XML_SET style2 在最深节点塞入子元素抛 CodeEE`() {
        val ctx = stabilityL5Env()
        assertEquals(1L, l5iv(ctx, "XML_DOCUMENT", 1L, deepDoc(MAX_LEVELS)))
        // XML_SET(doc, path, text, allFlag, style) —— style 2 = InnerXml
        assertFailsWith<CodeEE> {
            l5iv(ctx, "XML_SET", 1L, deepPath(MAX_LEVELS), "<y><z/></y>", 0L, 2L)
        }
    }

    @Test
    fun `XML_REPLACE 在最深节点换成两层片段抛 CodeEE`() {
        val ctx = stabilityL5Env()
        assertEquals(1L, l5iv(ctx, "XML_DOCUMENT", 1L, deepDoc(MAX_LEVELS)))
        // 被替换节点在 depth 512：新片段根落在 512，子元素落在 513 ⇒ 超限。
        assertFailsWith<CodeEE> {
            l5iv(ctx, "XML_REPLACE", 1L, deepPath(MAX_LEVELS), "<y><z/></y>")
        }
    }

    @Test
    fun `浅文档的插入、InnerXml 与替换照常成功——闸门无假阳性`() {
        val ctx = stabilityL5Env()
        val levels = 100
        assertEquals(1L, l5iv(ctx, "XML_DOCUMENT", 1L, deepDoc(levels)))
        assertEquals(1L, l5iv(ctx, "XML_ADDNODE", 1L, deepPath(levels), "<x/>"))
        assertEquals(1L, l5iv(ctx, "XML_SET", 1L, deepPath(levels), "<y><z/></y>", 0L, 2L))
        // style=1 只换文本，不增加深度，同样不该触发闸门。
        assertEquals(1L, l5iv(ctx, "XML_SET", 1L, deepPath(levels), "t", 0L, 1L))
        // 最深节点（depth 99 的 a）换成 w，子 v 落在 depth 100，仍在限内。
        assertEquals(1L, l5iv(ctx, "XML_REPLACE", 1L, deepPath(levels), "<w><v/></w>"))
        assertTrue(
            l5sv(ctx, "XML_TOSTR", 1L).endsWith("<w><v /></w>" + "</a>".repeat(levels - 1)),
        )
    }
}
