package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * GROUPMATCH / NOSAMES / ALLSAMES 族（`ArgType.Any` + `ArgType.VariadicSameAsFirst`）。
 *
 * 语义权威：FunctionMethodCreator.cs:4509-4651（三个 Method 的 GetIntValue）、
 * FunctionMethod.cs:174-300（装载期实参类型校验，本轮把它下移到求值期）。
 *
 * 首参类型决定分支：首参 INT 走 `GetIntValue` 逐项数值比较，首参 STR 走 `GetStrValue`
 * 逐项字符串等值比较（FunctionMethodCreator.cs:4531/4544、:4578/4590、:4627/4638）。
 * 这条字符串分支是 2026-10-21 bootSmoke 首差的修复点：`PRINT_STATE.ERB:302`
 * `GROUPMATCH(ABLNAME:LOCAL, "百合属性", "百合中毒")` 的首参是字符串变量。
 */
class GroupMatchTest {

    /** 与 BuiltinsTest 同构的最小环境：家族函数不碰变量与宿主，空 store 足够。 */
    private class Harness : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("Harness 不应被直接调用：$name")
    }

    private fun ctx(): BuiltinEvalContext {
        val store = VariableStore()
        val base = Harness()
        base.variables = store
        return BuiltinEvalContext(base, store)
    }

    /** 值路径（语句层 `callFunction`）。 */
    private fun intOf(c: BuiltinEvalContext, name: String, vararg a: Value?): Long =
        (c.callFunction(name, a.toList()) as Value.IntV).v

    private fun i(v: Long) = Value.IntV(v)
    private fun s(v: String) = Value.StrV(v)

    // ------------------------------------------------------------------
    // GROUPMATCH：字符串分支（FunctionMethodCreator.cs:4544-4551）
    // ------------------------------------------------------------------

    @Test
    fun groupMatchStringHit() {
        val c = ctx()
        // bootSmoke 首差原句：PRINT_STATE.ERB:302
        assertEquals(1L, intOf(c, "GROUPMATCH", s("百合属性"), s("百合属性"), s("百合中毒")))
        assertEquals(1L, intOf(c, "GROUPMATCH", s("百合中毒"), s("百合属性"), s("百合中毒")))
        // 候选内重复项逐个计数（原版是循环累加，不是查表，:4534-4540 / :4545-4551）
        assertEquals(2L, intOf(c, "GROUPMATCH", s("a"), s("a"), s("b"), s("a")))
    }

    @Test
    fun groupMatchStringMiss() {
        val c = ctx()
        assertEquals(0L, intOf(c, "GROUPMATCH", s("断袖属性"), s("百合属性"), s("百合中毒")))
        // 空串是合法字符串，参与等值比较（原版无「空串当缺省」特判）
        assertEquals(1L, intOf(c, "GROUPMATCH", s(""), s("x"), s("")))
        // 大小写与全半角不做归一：原版就是 string.op_Equality（序数比较）
        assertEquals(0L, intOf(c, "GROUPMATCH", s("Abc"), s("abc")))
    }

    // ------------------------------------------------------------------
    // GROUPMATCH：数值分支回归（FunctionMethodCreator.cs:4531-4541）
    // ------------------------------------------------------------------

    @Test
    fun groupMatchIntRegression() {
        val c = ctx()
        assertEquals(2L, intOf(c, "GROUPMATCH", i(1), i(1), i(2), i(1)))
        assertEquals(0L, intOf(c, "GROUPMATCH", i(1), i(2), i(3)))
        assertEquals(3L, intOf(c, "GROUPMATCH", i(-1), i(-1), i(-1), i(-1)))
        // Int64 边界按位比较，不经过 double
        assertEquals(1L, intOf(c, "GROUPMATCH", i(Long.MIN_VALUE), i(Long.MAX_VALUE), i(Long.MIN_VALUE)))
    }

    // ------------------------------------------------------------------
    // 同型性：VariadicSameAsFirst（FunctionMethod.cs:207,254-258）
    // 文案 = Lang.Error.ArgIsNotStr（Lang.cs:3746）/ ArgIsNotInt（Lang.cs:3750）的 zh 译文
    // （lang 目录的 emuera-zhs.xml:890-891）。
    // ------------------------------------------------------------------

    @Test
    fun strFirstRequiresStrCandidates() {
        val c = ctx()
        val e = assertFailsWith<CodeEE> { intOf(c, "GROUPMATCH", s("百合属性"), s("百合中毒"), i(1)) }
        assertEquals("GROUPMATCH函数: 第3个参数不是字符串", e.message)
        // 首参是字符串时，非串的**第一个**位置即报错（原版校验顺序 j=1..n-1，:195-259）
        val first = assertFailsWith<CodeEE> { intOf(c, "GROUPMATCH", s("a"), i(2), s("a")) }
        assertEquals("GROUPMATCH函数: 第2个参数不是字符串", first.message)
    }

    @Test
    fun intFirstRequiresIntCandidates() {
        val c = ctx()
        val e = assertFailsWith<CodeEE> { intOf(c, "GROUPMATCH", i(1), i(1), s("1")) }
        assertEquals("GROUPMATCH函数: 第3个参数不是整数", e.message)
    }

    @Test
    fun emptyArgSlotCrashesLikeOriginal() {
        val c = ctx()
        // 空位实参原版装载期跳过（FunctionMethod.cs:198-205），求值时对 null 解引用崩溃
        // （FunctionMethodCreator.cs:4531 GetOperandType / :4536 GetIntValue）——ADR-005 同点复刻。
        assertFailsWith<NullPointerException> { intOf(c, "GROUPMATCH", null, i(1)) }
    }

    // ------------------------------------------------------------------
    // 表达式桥路径：FuncCall → EvalContext.callFunctionRef（Builtins.kt:722-732）
    // bootSmoke 的调用正是这条路径，值路径之外再钉一次。
    // ------------------------------------------------------------------

    @Test
    fun groupMatchViaExprBridge() {
        val c = ctx()
        val r = c.callFunctionRef(
            "GROUPMATCH",
            listOf(StrLiteral("百合属性"), StrLiteral("百合属性"), StrLiteral("百合中毒")),
        )
        assertEquals(1L, (r as Value.IntV).v)
        val r0 = c.callFunctionRef(
            "GROUPMATCH",
            listOf(StrLiteral("断袖属性"), StrLiteral("百合属性"), StrLiteral("百合中毒")),
        )
        assertEquals(0L, (r0 as Value.IntV).v)
    }

    // ------------------------------------------------------------------
    // NOSAMES（FunctionMethodCreator.cs:4576-4603）
    // ------------------------------------------------------------------

    @Test
    fun nosamesIntAndString() {
        val c = ctx()
        assertEquals(1L, intOf(c, "NOSAMES", i(1), i(2), i(3)))
        assertEquals(0L, intOf(c, "NOSAMES", i(1), i(2), i(1)))
        assertEquals(1L, intOf(c, "NOSAMES", s("a"), s("b"), s("c")))
        assertEquals(0L, intOf(c, "NOSAMES", s("a"), s("b"), s("a")))
        // 两个实参：不同 → 1，相同 → 0
        assertEquals(1L, intOf(c, "NOSAMES", i(7), i(8)))
        assertEquals(0L, intOf(c, "NOSAMES", s("x"), s("x")))
    }

    @Test
    fun nosamesTypeMismatch() {
        val c = ctx()
        assertEquals(
            "NOSAMES函数: 第2个参数不是字符串",
            assertFailsWith<CodeEE> { intOf(c, "NOSAMES", s("a"), i(1)) }.message,
        )
        assertEquals(
            "NOSAMES函数: 第3个参数不是整数",
            assertFailsWith<CodeEE> { intOf(c, "NOSAMES", i(1), i(2), s("3")) }.message,
        )
    }

    // ------------------------------------------------------------------
    // ALLSAMES（FunctionMethodCreator.cs:4625-4650）
    // ------------------------------------------------------------------

    @Test
    fun allsamesIntAndString() {
        val c = ctx()
        assertEquals(1L, intOf(c, "ALLSAMES", i(4), i(4), i(4)))
        assertEquals(0L, intOf(c, "ALLSAMES", i(4), i(4), i(5)))
        assertEquals(1L, intOf(c, "ALLSAMES", s("三途之川"), s("三途之川")))
        assertEquals(0L, intOf(c, "ALLSAMES", s("三途之川"), s("中有之道")))
    }

    /**
     * 每次求值取下一个预置值并计数的 TICK() 用户函数；用于验证 ALLSAMES 的短路
     * （原版 :4632 遇不等立即 return，后续实参不再求值）。
     */
    private fun ctxWithTicks(vararg values: Long): Pair<BuiltinEvalContext, IntArray> {
        val store = VariableStore()
        val base = Harness().also { it.variables = store }
        val calls = IntArray(1)
        val queue = ArrayDeque(values.toList())
        val tick: (List<Value?>) -> Value = {
            calls[0]++
            Value.IntV(queue.removeFirst())
        }
        return BuiltinEvalContext(base, store, userFunctions = mapOf("TICK" to tick)) to calls
    }

    private fun tick() = com.eratw.port.core.expr.FuncCall(
        "TICK", emptyList(), OperandType.INT,
    )

    @Test
    fun allsamesShortCircuitsLaterArguments() {
        val (c, calls) = ctxWithTicks(4, 5, 4)
        assertEquals(0L, (c.callFunctionRef("ALLSAMES", listOf(tick(), tick(), tick())) as Value.IntV).v)
        assertEquals(2, calls[0], "首次不等后第 3 个实参不再求值（FunctionMethodCreator.cs:4632）")

        val (all, allCalls) = ctxWithTicks(7, 7, 7)
        assertEquals(1L, (all.callFunctionRef("ALLSAMES", listOf(tick(), tick(), tick())) as Value.IntV).v)
        assertEquals(3, allCalls[0], "全等时每个恰求值一次")
    }

    @Test
    fun allsamesTypeMismatch() {
        val c = ctx()
        assertEquals(
            "ALLSAMES函数: 第2个参数不是字符串",
            assertFailsWith<CodeEE> { intOf(c, "ALLSAMES", s("a"), i(1)) }.message,
        )
        assertEquals(
            "ALLSAMES函数: 第2个参数不是整数",
            assertFailsWith<CodeEE> { intOf(c, "ALLSAMES", i(1), s("1")) }.message,
        )
    }

    // ------------------------------------------------------------------
    // 参数下限 = 2（ArgTypes.Count=2 + FunctionMethod.cs:183 的 flag3 / :283 NotEnoughArgs）
    // ------------------------------------------------------------------

    @Test
    fun arityMinimumIsTwo() {
        val c = ctx()
        for (name in listOf("GROUPMATCH", "NOSAMES", "ALLSAMES")) {
            val e = assertFailsWith<CodeEE> { intOf(c, name, i(1)) }
            assertContains(e.message!!, "至少 2 个")
            assertContains(e.message!!, name)
        }
        // 空实参表：原版 NotEnoughArgs 同样先拦（TypeCheck 早于求值）
        assertFailsWith<CodeEE> { intOf(c, "GROUPMATCH") }
    }
}
