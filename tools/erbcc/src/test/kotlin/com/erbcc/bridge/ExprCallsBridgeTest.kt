package com.erbcc.bridge

import com.eratw.port.core.compile.ExprCallKind
import com.eratw.port.core.expr.OperandType
import com.eratw.port.erbcc.LabelDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A16 · erbcc 符号表 → `ExprCallTable` 的构造桥（[buildExprCallTable]）。
 *
 * 分类口径的权威证据在 `com/erbcc/bridge/ExprCalls.kt` KDoc（IdentifierDictionary.cs:538-586）；
 * 本测试锁三件事：① `#FUNCTION` 才进可降级桶；② `#FUNCTIONS` 或**类型未知**一律不降级
 * （宁可运行期报红，也不把 str 方法当 int 读错槽）；③ 纯事件名不落 nonMethodLabels
 * （原版对事件名走"标识符不可解析"，不是 UsedNonMethodFunc）。
 */
class ExprCallsBridgeTest {

    private fun def(key: String, isMethod: Boolean = false, isEvent: Boolean = false, defIndex: Int = 1) = LabelDef(
        name = key, key = key, file = "t.erb", lineNo = 1, fileIndex = 1, defIndex = defIndex,
        isEvent = isEvent, isSystem = false, isMethod = isMethod, isOnly = false, isPri = false,
        isLater = false, isSingle = false,
    )

    @Test
    fun `int 与 str 方法分桶且键大写归一`() {
        val t = buildExprCallTable(
            listOf(def("g12_func", isMethod = true), def("StrM", isMethod = true)),
            methodTypes = mapOf("G12_FUNC" to OperandType.INT, "STRM" to OperandType.STR),
        )
        assertEquals(setOf("G12_FUNC"), t.intMethods)
        assertEquals(setOf("STRM"), t.strMethods)
        assertEquals(ExprCallKind.METHOD_INT, t.classify("G12_func"))
        assertEquals(ExprCallKind.METHOD_STR, t.classify("strm"))
    }

    @Test
    fun `不传类型表时全部方法保守不降级`() {
        // LabelDef 现状：`#FUNCTION`/`#FUNCTIONS` 在收集期被合并（Symbols.kt:637），
        // 类型唯一来源是调用方的 methodTypes ⇒ 不给类型就落 unknownMethods（METHOD_UNKNOWN），
        // 一律不降级（运行期明确报红）。不落 strMethods：误判成字符串通道会静默读错值。
        val t = buildExprCallTable(listOf(def("M1", isMethod = true), def("M2", isMethod = true)))
        assertTrue(t.intMethods.isEmpty(), "类型未知不得进可降级桶（会把 str 方法当 int 读错槽）")
        assertTrue(t.strMethods.isEmpty(), "类型未知不得进字符串通道桶（误判会静默读错值）")
        assertEquals(setOf("M1", "M2"), t.unknownMethods)
        assertEquals(ExprCallKind.METHOD_UNKNOWN, t.classify("m1"))
    }

    @Test
    fun `普通 label 进 nonMethodLabels 事件名与非事件分开`() {
        val t = buildExprCallTable(listOf(def("PLAIN"), def("EVENTTURNEND", isEvent = true), def("EV_MEM", isEvent = true)))
        assertEquals(setOf("PLAIN"), t.nonMethodLabels)
        assertEquals(ExprCallKind.NON_METHOD_LABEL, t.classify("plain"))
        assertEquals(ExprCallKind.UNKNOWN, t.classify("EVENTTURNEND"))
    }

    @Test
    fun `重名多条定义只看生效者`() {
        // 装载语义（后装载者生效，spec: LabelDictionary.cs:60-68）归符号层；
        // 本表只看胜者的属性：胜者是方法 → 降级桶，胜者是普通 label → UsedNonMethodFunc 桶
        val t = buildExprCallTable(
            listOf(def("DUP", defIndex = 1), def("DUP", isMethod = true, defIndex = 2)),
            methodTypes = mapOf("DUP" to OperandType.INT),
        )
        assertEquals(setOf("DUP"), t.intMethods)
        assertTrue("DUP" !in t.nonMethodLabels, "被遮蔽的普通定义不得再把名字拽进 nonMethodLabels")
        assertEquals(ExprCallKind.METHOD_INT, t.classify("DUP"))

        val lose = buildExprCallTable(
            listOf(def("DUP2", isMethod = true, defIndex = 3), def("DUP2", defIndex = 4)),
            methodTypes = mapOf("DUP2" to OperandType.INT),
        )
        assertEquals(setOf("DUP2"), lose.nonMethodLabels, "胜者是普通 @label ⇒ 表达式位按原版拒绝")
        assertEquals(ExprCallKind.NON_METHOD_LABEL, lose.classify("DUP2"))
    }
}
