package com.eratw.port.core.vars

import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.ExprEvalException
import com.eratw.port.core.expr.IdentifierResolver
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarInfo
import com.eratw.port.core.expr.colon
import com.eratw.port.core.expr.evalInt
import com.eratw.port.core.expr.evalStr
import com.eratw.port.core.expr.i
import com.eratw.port.core.expr.id
import com.eratw.port.core.expr.op
import com.eratw.port.core.expr.parseOrThrow
import com.eratw.port.core.expr.s
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 表达式层 × 变量池集成：真实 [VariableStore] 作为 [com.eratw.port.core.expr.VariableReader]，
 * 由解析器按 [VarInfo] 补全下标后直接求值。
 */
class VariableReaderIntegrationTest {

    private class StoreResolver(private val store: VariableStore) : IdentifierResolver {
        override fun resolveVariable(name: String, subKey: String?): VarInfo? = store.resolveVarInfo(name)
        override fun resolveFunction(name: String): OperandType? = null
    }

    private class Ctx(private val store: VariableStore) : EvalContext {
        override val variables get() = store
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw ExprEvalException("测试未绑定函数 $name")
    }

    private fun setup(): Triple<VariableStore, StoreResolver, Ctx> {
        val store = VariableStore()
        return Triple(store, StoreResolver(store), Ctx(store))
    }

    @Test
    fun `A 1 加 B 能算出真实值`() {
        val (store, resolver, ctx) = setup()
        val expr = parseOrThrow(listOf(id("A"), colon(), i(1), op("+"), id("B")), resolver)
        store.writeInt("A", longArrayOf(1), 5)
        store.writeInt("B", longArrayOf(0), 7)
        assertEquals(12L, evalInt(expr, ctx))
    }

    @Test
    fun `后置自增返回值并写回`() {
        val (store, resolver, ctx) = setup()
        store.writeInt("MONEY", longArrayOf(0), 5)
        val expr = parseOrThrow(listOf(id("MONEY"), op("++")), resolver)
        assertEquals(5L, evalInt(expr, ctx))
        assertEquals(6L, store.readInt("MONEY", longArrayOf(0)))
    }

    @Test
    fun `角色变量经字符串关键字下标求值`() {
        val (store, resolver, ctx) = setup()
        store.registry.csvTables[15].set(3, "好感度") // CFLAG.CSV
        store.addPseudoCharacter()
        store.writeInt("CFLAG", longArrayOf(0, 3), 88)
        val expr = parseOrThrow(
            listOf(id("CFLAG"), colon(), i(0), colon(), s("好感度")),
            resolver,
        )
        assertEquals(88L, evalInt(expr, ctx))
    }

    @Test
    fun `0 维角色字符串变量求值`() {
        val (store, resolver, ctx) = setup()
        store.addCharacter(CharacterTemplate(no = 1, name = "フラン", callname = "フラン"))
        val expr = parseOrThrow(listOf(id("CALLNAME"), colon(), i(0), op("+"), s("!")), resolver)
        assertEquals("フラン!", evalStr(expr, ctx))
    }

    @Test
    fun `用户变量也能参与表达式求值`() {
        val (store, resolver, ctx) = setup()
        store.declareUserVar(DimParser.parse("#DIM", "UX, 3 = 1, 2, 3", isPrivate = false))
        val expr = parseOrThrow(listOf(id("UX"), colon(), i(2), op("*"), i(2)), resolver)
        assertEquals(6L, evalInt(expr, ctx))
    }

    @Test
    fun `常量变量在解析期标记为 isConst`() {
        val (store, resolver, _) = setup()
        val expr = parseOrThrow(listOf(id("ITEMPRICE"), colon(), i(0)), resolver) as com.eratw.port.core.expr.VarRef
        assertTrue(expr.isConst)
        val day = parseOrThrow(listOf(id("DAY"), colon(), i(0)), resolver) as com.eratw.port.core.expr.VarRef
        assertTrue(!day.isConst)
    }
}
