package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `BuiltinEvalContext` 按名派发记忆的行为闸（PERF 2026-10-30，刀二）。
 *
 * ## 为什么这组用例必须存在
 *
 * 刀二把每次函数调用的三次查找（名字归一 → 用户函数表 → 内建注册表）合并成按名一次的
 * `HashMap` 命中，并把实参求值从 `map { e -> e?.toValue(this) }` 换成预分配循环 +
 * 空表短路。记忆化最容易吞掉的语义有四样，全部在这里钉死：
 *
 * 1. **优先级**：用户 #FUNCTION 遮蔽同名内建（spec: IdentifierDictionary.cs:548-586），
 *    大小写归一不变，值路径与引用路径同一记忆。
 * 2. **报错**：未定义名字即使进了记忆，每次调用仍逐字抛
 *    「未定义的函数 $name」；引用参数函数走值路径仍抛「函数 $name 的第 1 个参数必须是
 *    变量引用」；checkArity 每次调用都跑（记忆化绝不免检）。
 * 3. **求值顺序**：实参仍从左到右求值（用记录型 VariableReader 观测读取顺序）。
 * 4. **空实参**：`argExprs` 为空时共享不可变空表，不分配。
 *
 * 内建注册表在 JVM 类初始化后只读、用户函数表构造期注入（见 `dispatchMemo` 的失效前提
 * KDoc），所以这些断言在单实例内重复调用即可覆盖记忆命中路径，无需并发。
 */
class BuiltinDispatchMemoTest {

    // ------------------------------------------------------------------
    // 环境
    // ------------------------------------------------------------------

    /** 记录型变量读口：把每次 readInt 的名字按顺序记账，用于观测实参求值顺序。 */
    private class RecordingReader(
        private val ints: Map<String, Long> = emptyMap(),
    ) : VariableReader {
        val reads = mutableListOf<String>()
        override fun isConst(name: String): Boolean = false
        override fun readInt(name: String, indices: LongArray): Long {
            reads += name
            return ints[name] ?: 0L
        }
        override fun readStr(name: String, indices: LongArray): String = ""
        override fun plusInt(name: String, indices: LongArray, delta: Long): Long = readInt(name, indices)
        override fun keywordToInteger(varName: String, dim: Int, keyword: String): Long = 0L
    }

    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) {}
    }

    /** 与 BuiltinsTest 同构的环境；[reader] 缺省用真 VariableStore。 */
    private fun env(
        userFunctions: Map<String, (List<Value?>) -> Value> = emptyMap(),
        reader: RecordingReader? = null,
    ): BuiltinEvalContext {
        val store = VariableStore()
        val base = BaseCtx()
        base.variables = reader ?: store
        return BuiltinEvalContext(base, store, object : BuiltinHost {}, userFunctions = userFunctions)
    }

    private fun iv(v: Long): Value = Value.IntV(v)
    private fun il(v: Value): Long = (v as Value.IntV).v
    private fun ref(name: String) = VarRef(name, emptyList(), operandType = OperandType.INT)

    // ------------------------------------------------------------------
    // 优先级：用户 #FUNCTION 遮蔽同名内建
    // ------------------------------------------------------------------

    @Test
    fun `用户函数遮蔽同名内建 - 值路径与引用路径共用同一记忆且大小写归一`() {
        var calls = 0
        val ctx = env(userFunctions = mapOf("MIN" to { _ -> calls++; iv(4242L) }))
        assertEquals(4242L, il(ctx.callFunction("MIN", listOf(iv(3), iv(7)))))
        // 记忆命中后行为一致；小写名命中同一条记忆（归一只发生在未命中那次）
        assertEquals(4242L, il(ctx.callFunction("min", listOf(iv(3), iv(7)))))
        assertEquals(2, calls)
        // 引用路径命中同一条记忆（不重复查内建表）
        assertEquals(4242L, il(ctx.callFunctionRef("MIN", listOf(IntLiteral(1), IntLiteral(2)))))
        assertEquals(3, calls)
    }

    @Test
    fun `记忆不串台 - 无用户函数的实例内建照常、有用户函数的实例稳定遮蔽`() {
        val ctx1 = env()
        for (i in 0 until 3) assertEquals(3L, il(ctx1.callFunction("MIN", listOf(iv(3), iv(7)))))
        var calls = 0
        val ctx2 = env(userFunctions = mapOf("MIN" to { _ -> calls++; iv(99L) }))
        for (i in 0 until 3) assertEquals(99L, il(ctx2.callFunction("MIN", listOf(iv(3), iv(7)))))
        assertEquals(3, calls)
        // ctx1 的记忆不受 ctx2 影响
        assertEquals(3L, il(ctx1.callFunction("MIN", listOf(iv(3), iv(7)))))
    }

    // ------------------------------------------------------------------
    // 报错语义：文案逐字、记忆化不免检
    // ------------------------------------------------------------------

    @Test
    fun `未定义函数 - 两条入口文案逐字且重复调用仍报`() {
        val ctx = env()
        val e1 = assertFailsWith<CodeEE> { ctx.callFunction("没有这个函数", emptyList()) }
        assertEquals("未定义的函数 没有这个函数", e1.message)
        // 名字已进记忆（Undefined），第二次调用必须照样报错
        val e2 = assertFailsWith<CodeEE> { ctx.callFunction("没有这个函数", emptyList()) }
        assertEquals("未定义的函数 没有这个函数", e2.message)
        val e3 = assertFailsWith<CodeEE> { ctx.callFunctionRef("没有这个函数", emptyList()) }
        assertEquals("未定义的函数 没有这个函数", e3.message)
    }

    @Test
    fun `引用参数函数走值路径 - 文案逐字`() {
        val ctx = env()
        // SUMARRAY 只有 evalRef（refInt 注册，omitStart 缺省 -1），checkArity(1) 通过后
        // 落到「没有 eval」分支
        val e = assertFailsWith<CodeEE> { ctx.callFunction("SUMARRAY", listOf(iv(0))) }
        assertEquals("函数 SUMARRAY 的第 1 个参数必须是变量引用", e.message)
    }

    @Test
    fun `参数个数 - 文案逐字且记忆命中后每次调用都检查`() {
        val ctx = env()
        // ABS：minArgs=1, maxArgs=1（无省略位）
        val e1 = assertFailsWith<CodeEE> { ctx.callFunction("ABS", emptyList()) }
        assertEquals("函数 ABS 的参数个数不匹配（需要 1 个）：实际 0 个", e1.message)
        val e2 = assertFailsWith<CodeEE> { ctx.callFunction("ABS", emptyList()) }
        assertEquals("函数 ABS 的参数个数不匹配（需要 1 个）：实际 0 个", e2.message)
        // MIN：maxArgs=-1（可变参）
        val e3 = assertFailsWith<CodeEE> { ctx.callFunction("MIN", emptyList()) }
        assertEquals("函数 MIN 的参数个数不足（至少 1 个）：实际 0 个", e3.message)
        // 记忆命中路径上同样不免检：先成功调用一次 ABS，再错参调用仍报
        assertEquals(5L, il(ctx.callFunction("ABS", listOf(iv(-5)))))
        val e4 = assertFailsWith<CodeEE> { ctx.callFunction("ABS", listOf(iv(1), iv(2))) }
        assertEquals("函数 ABS 的参数个数不匹配（需要 1 个）：实际 2 个", e4.message)
    }

    // ------------------------------------------------------------------
    // 实参求值：从左到右、空表短路
    // ------------------------------------------------------------------

    @Test
    fun `实参从左到右求值 - 引用路径的变量读取顺序可观测`() {
        val reader = RecordingReader(mapOf("B" to 11L, "A" to 22L))
        val got = mutableListOf<List<Value?>>()
        val ctx = env(
            userFunctions = mapOf("MIN" to { args -> got += args; iv(1L) }),
            reader = reader,
        )
        val v = ctx.callFunctionRef("min", listOf(ref("B"), IntLiteral(5), ref("A")))
        assertEquals(1L, il(v))
        val args = got.single()
        assertEquals(3, args.size)
        assertEquals(11L, il(args[0]!!))
        assertEquals(5L, il(args[1]!!))
        assertEquals(22L, il(args[2]!!))
        // 读取顺序 = 实参顺序（中间的字面量不读变量）
        assertEquals(listOf("B", "A"), reader.reads)
    }

    @Test
    fun `空实参 - 两条入口都拿共享不可变空表`() {
        val got = mutableListOf<List<Value?>>()
        val ctx = env(userFunctions = mapOf("PING" to { args -> got += args; iv(7L) }))
        assertEquals(7L, il(ctx.callFunction("PING", emptyList())))
        assertEquals(7L, il(ctx.callFunctionRef("PING", emptyList())))
        assertEquals(2, got.size)
        assertTrue(got[0].isEmpty())
        assertTrue(got[1].isEmpty())
        // evalArgs 的空表短路：共享同一个不可变空表单例，不分配
        assertSame(got[0], got[1])
        assertSame(emptyList<Value?>(), got[1])
    }
}
