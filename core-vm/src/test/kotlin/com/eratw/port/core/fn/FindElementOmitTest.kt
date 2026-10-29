package com.eratw.port.core.fn

import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.IntLiteral
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.expr.StrLiteral
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VarRef
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.vars.VariableStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FINDELEMENT / FINDLASTELEMENT 的**省略位**契约（M6-FINDELEM）。
 *
 * 语料形态 `FINDELEMENT(L_SPLITSTR , TOSTR(ARG:1) , , ,1)`
 *（`ERB/PRINT_TALENTNAME.ERB:370`，组长首差 pc=682,671）一次踩中三件事，本文件的断言逐条锁住：
 * 1. 实参 **5 个都得收**（槽位数 = 5，spec: FunctionMethodCreator.cs:4919-4927）；
 * 2. 中段两个空实参是**省略位**，不是"多给的空串"（`j >= OmitStart` 才允许空，
 *    spec: FunctionMethod.cs:239-244）；
 * 3. 第 5 位是 **isExact**、不是起始下标（:4942-4945；起始下标在第 3 位，:4940）。
 *
 * 另外锁住区间端点口径（`IsArrayRangeValid` → [0, 长度] 闭区间，spec: VariableData.cs:89-100）、
 * 空区间返回 -1（spec: VariableEvaluator.cs:561-564）、字符串数组第二参走**正则**
 * （:4953-4962；isExact 只在整串命中时返回，:601-605）与个数错误文案
 * （spec: Lang.Error.NotEnoughArgs / TooManyFuncArgs / ArgCanNotBeNull）。
 */
class FindElementOmitTest {

    private class BaseCtx : EvalContext {
        override lateinit var variables: VariableReader
        override fun callFunction(name: String, args: List<Value?>): Value =
            throw CodeEE("BaseCtx 不应被直接调用：$name")
        override fun printSystemLine(message: String) = Unit
    }

    private fun env(): Pair<BuiltinEvalContext, VariableStore> {
        val store = VariableStore()
        val base = BaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store) to store
    }

    /** 引用路径调用：**保留 null 占位**（空实参在 Expr 层就是 null 槽）。 */
    private fun callRef(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
        ctx.callFunctionRef(
            name,
            args.map { a ->
                when (a) {
                    null -> null
                    is VarRef -> a
                    is Int -> IntLiteral(a.toLong())
                    is Long -> IntLiteral(a)
                    is String -> StrLiteral(a)
                    else -> throw IllegalArgumentException("$a")
                }
            },
        )

    private fun intOf(v: Value): Long = (v as Value.IntV).v

    private fun iRef(name: String, vararg indices: Long) =
        VarRef(name, indices.map { IntLiteral(it) }, operandType = OperandType.INT)

    private fun sRef(name: String, vararg indices: Long) =
        VarRef(name, indices.map { IntLiteral(it) }, operandType = OperandType.STR)

    /** FLAG[0]=5 FLAG[1]=7 FLAG[2]=5 FLAG[3]=9（系统 1 维整数数组，长度 10000）。 */
    private fun flagEnv(): Pair<BuiltinEvalContext, VariableStore> {
        val (ctx, store) = env()
        store.writeInt("FLAG", longArrayOf(0), 5)
        store.writeInt("FLAG", longArrayOf(1), 7)
        store.writeInt("FLAG", longArrayOf(2), 5)
        store.writeInt("FLAG", longArrayOf(3), 9)
        return ctx to store
    }

    // ------------------------------------------------------------------
    // 1. 槽位数 5：语料形态（中段两个省略位 + 末位 isExact）
    // ------------------------------------------------------------------

    @Test
    fun `中段两个省略位与末位 isExact 都收 5 个实参`() {
        val (ctx, _) = flagEnv()
        // 语料同形：FINDELEMENT(var, value, , ,1)
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, null, null, 1L)))
        assertEquals(2L, intOf(callRef(ctx, "FINDLASTELEMENT", iRef("FLAG"), 5L, null, null, 1L)))
        // 第 5 位若被当成 start=1（组长首差描述的读法），上面这条会得到 2 而不是 0
        assertEquals(2L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, 1L)))
        // isExact 在整数分支不参与判定（spec: VariableEvaluator.cs:559-587 不读该形参）
        assertEquals(
            intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L)),
            intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, null, null, 1L)),
        )
    }

    @Test
    fun `注册元信息 5 槽 OmitStart 2`() {
        val m = Builtins.registry["FINDELEMENT"]!!
        assertEquals(5, m.maxArgs, "spec: FunctionMethodCreator.cs:4919-4926 五槽")
        assertEquals(2, m.omitStart, "spec: :4927 OmitStart = 2")
        val last = Builtins.registry["FINDLASTELEMENT"]!!
        assertEquals(5, last.maxArgs)
        assertEquals(2, last.omitStart)
    }

    // ------------------------------------------------------------------
    // 2. 起始/结束下标：缺省值、只省中段、只省尾段
    // ------------------------------------------------------------------

    @Test
    fun `start end 的缺省与部分省略`() {
        val (ctx, _) = flagEnv()
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L)))
        assertEquals(2L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, 1L)))
        // 只省第 2 个可选位（end），start 给了 1
        assertEquals(2L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, 1L, null)))
        // 省 start、给 end：区间 [0,3) 内首个 5 在 0
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, null, 3L)))
        // end 独占：区间 [0,1) 里只有 5，找不到 7
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 7L, 0L, 1L)))
        // 省 start、给 end：7 落在 [0,2)
        assertEquals(1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 7L, null, 2L)))
        // FINDLASTELEMENT 的 end 同样独占，起点在 end-1
        assertEquals(2L, intOf(callRef(ctx, "FINDLASTELEMENT", iRef("FLAG"), 5L, null, 3L)))
        assertEquals(0L, intOf(callRef(ctx, "FINDLASTELEMENT", iRef("FLAG"), 5L, null, 2L)))
        assertEquals(1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 7L, 0L, 4L)))
    }

    // ------------------------------------------------------------------
    // 3. 找不到 / 空区间 / 端点在长度上 → -1（不是异常）
    // ------------------------------------------------------------------

    @Test
    fun `找不到与空区间返回 -1`() {
        val (ctx, _) = flagEnv()
        val len = 10000L
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 99L)))
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, 3L, 3L)))
        assertEquals(-1L, intOf(callRef(ctx, "FINDLASTELEMENT", iRef("FLAG"), 5L, 3L, 3L)))
        // start > end：原版只判 start >= end 就早退（spec: VariableEvaluator.cs:561-564）
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, 4L, 2L)))
        // 端点等于长度是合法区间（IsArrayRangeValid 用 > length 判越界，spec: VariableData.cs:92-99）
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, len, len)))
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, len - 1L, len)))
    }

    // ------------------------------------------------------------------
    // 4. 端点越界 → OoRInstructionArg 原文
    // ------------------------------------------------------------------

    @Test
    fun `端点越界报 OoRInstructionArg 文案`() {
        val (ctx, _) = flagEnv()
        val startErr = assertFailsWith<CodeEE> {
            callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, -1L)
        }
        assertEquals("FINDELEMENT命令的第3个参数(-1)超出数组\"FLAG\"的范围", startErr.message)

        val endErr = assertFailsWith<CodeEE> {
            callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, 0L, 10001L)
        }
        assertEquals("FINDELEMENT命令的第4个参数(10001)超出数组\"FLAG\"的范围", endErr.message)

        // 函数名跟着变体走（原版把 funcName 传进 IsArrayRangeValid，spec: :4947）
        val lastErr = assertFailsWith<CodeEE> {
            callRef(ctx, "FINDLASTELEMENT", iRef("FLAG"), 5L, 0L, -2L)
        }
        assertEquals("FINDLASTELEMENT命令的第4个参数(-2)超出数组\"FLAG\"的范围", lastErr.message)
    }

    // ------------------------------------------------------------------
    // 5. 个数错误：C# 两条独立分支的原文
    // ------------------------------------------------------------------

    @Test
    fun `个数错误与 C# 文案一致`() {
        val (ctx, _) = flagEnv()
        // 少于 OmitStart=2 → NotEnoughArgs（lang zhs: 至少需要{1}个参数）
        val few = assertFailsWith<CodeEE> { callRef(ctx, "FINDELEMENT", iRef("FLAG")) }
        assertEquals("FINDELEMENT函数: 至少需要2个参数", few.message)
        val none = assertFailsWith<CodeEE> { callRef(ctx, "FINDELEMENT") }
        assertEquals("FINDELEMENT函数: 至少需要2个参数", none.message)
        // 多于槽位 5 → TooManyFuncArgs（lang zhs: 参数过多）
        val many = assertFailsWith<CodeEE> {
            callRef(ctx, "FINDELEMENT", iRef("FLAG"), 5L, 0L, 1L, 1L, 0L)
        }
        assertEquals("FINDELEMENT函数: 参数过多", many.message)
        val manyLast = assertFailsWith<CodeEE> {
            callRef(ctx, "FINDLASTELEMENT", iRef("FLAG"), 5L, 0L, 1L, 1L, 0L)
        }
        assertEquals("FINDLASTELEMENT函数: 参数过多", manyLast.message)
    }

    // ------------------------------------------------------------------
    // 6. OmitStart 的作用面：第 1、2 位不可省略
    // ------------------------------------------------------------------

    @Test
    fun `前两位空实参报不能省略`() {
        val (ctx, _) = flagEnv()
        val noValue = assertFailsWith<CodeEE> { callRef(ctx, "FINDELEMENT", iRef("FLAG"), null) }
        assertEquals("FINDELEMENT函数: 第2个参数不能省略", noValue.message)

        val noVar = assertFailsWith<CodeEE> { callRef(ctx, "FINDELEMENT", null, 5L) }
        assertEquals("FINDELEMENT函数: 第1个参数不能省略", noVar.message)
    }

    // ------------------------------------------------------------------
    // 7. 字符串数组：第二参是正则；isExact 要求整串
    // ------------------------------------------------------------------

    @Test
    fun `字符串数组第二参按正则解释`() {
        val (ctx, store) = env()
        store.writeStr("STR", longArrayOf(0), "abc")
        store.writeStr("STR", longArrayOf(1), "xabc")
        // 正则：`.` 是任意字符 —— 字面比较永远找不到 "a.c"
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "a.c")))
        // 非精确：子串命中，"xabc" 也中
        assertEquals(1L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "abc", 1L)))
        // isExact = 1：只有整串等于 "abc" 的那个（下标 0 是 "xabc"）
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "abc", 1L, null, 1L)))
        store.writeStr("STR", longArrayOf(0), "abc")
        store.writeStr("STR", longArrayOf(2), "abc")
        assertEquals(2L, intOf(callRef(ctx, "FINDLASTELEMENT", sRef("STR"), "abc", null, null, 1L)))
        // 正则非法 → InvalidRegexArg（spec: :4958-4961；lang zhs 模板 "第{1}参数"）
        val bad = assertFailsWith<CodeEE> { callRef(ctx, "FINDELEMENT", sRef("STR"), "a[") }
        assertTrue(
            bad.message!!.startsWith("FINDELEMENT函数: 第2参数无法解析为正则表达式: "),
            bad.message!!,
        )
    }

    @Test
    fun `空串元素在精确模式下可命中`() {
        val (ctx, store) = env()
        store.writeStr("STR", longArrayOf(1), "x")
        // STR[0] = ""；模式 "a*" 整串匹配空串（.NET Match.Length == text.Length == 0）
        assertEquals(0L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "a*", null, null, 1L)))
        assertEquals(-1L, intOf(callRef(ctx, "FINDELEMENT", sRef("STR"), "a+", null, null, 1L)))
    }
}
